# Sandbox 白名单（第 24 节）人工验收向导（Step by Step）

**Feature**: `specs/007-sandbox-whitelist` | **Date**: 2026-10-17 | **分支**: `class-24`

**上游文档**：[spec.md](spec.md) · [plan.md](plan.md) · [tasks.md](tasks.md) · [quickstart.md](quickstart.md) · [acceptance-report.md](acceptance-report.md) §三

**适用对象**：第 24 节 Sandbox 白名单的最终形态（`WhitelistSandbox` + 三个 `@ConfigurationProperties` + 装配替换）

**目的**：覆盖单测判不了的唯一一件事——**真模型真链路上，越界动作被拦在 IO 之前，而且真的落了一条人能读懂的失败审计**。单测能证明 `WhitelistSandbox.enforce` 会抛异常，但「模型真的会去调它」「`ToolExecutor` 真的接住了」「落库的那句话真的是给运维看的」这三段拼接只在真链路里成立。

**预计耗时**：Step 0~9 约 20 分钟（其中 Step 0 构建 5~10 分钟）。走 Step 5b（照课件原文收紧白名单后重建）再加 5~10 分钟。

---

## 实测记录

### 写向导时已实测（2026-10-17）

**不需要模型的那几条，写这份向导时已经真跑过了**，命令与输出都对得上（工作区 `/tmp/oryxos-l24-check`，跑完已删）：

| 已实测 | 结果 |
| --- | --- |
| `oryxos init` 的输出 | 12 个「新建:」条目 + 「已有（未覆盖）: （无）」，与下文 Step 1 预期逐字一致 |
| `oryxos profile create sandbox-probe` 生成的 `AGENT.md` | frontmatter 里 `provider.name: "deepseek"`、`tools: []`——**`tools: []` 是 Step 2 必须手改的那一行** |
| 随包 jar 里的白名单块 | `unzip -p "$JAR" BOOT-INF/classes/application.yaml` 打出来就是 `file.allowed_paths: ["."]` / 9 条命令 / 5 个域名（与仓库源码逐字一致，jar 是 2026-10-17 16:20 那次 `verify` 的产物） |
| Step 4 / 5 / 6 的三段 `python3` 查询块 | **原样照抄跑过**（往临时库里塞了三条形状照 `ToolExecutor` 实际落库的假行），输出形状与各 Step 写的「通过」判据逐条对得上 |
| 两个 `sed`（Step 2 的 `tools:`、Step 5b 的 `allowed_commands:`） | 在**副本**上试跑过，只命中目标行、注释行不受影响 |
| Step 7 的两条 grep | `grep -c "全拒"` = **4**；`grep -n "全拒\|劝阻级"` = **5 行** |
| `python3` 查库 | 表清单 = `llm_calls / memory_entries / notify_channels / sessions / tool_invocations`；`tool_invocations` 九列齐全、**初始 0 行** |
| 本机 `sqlite3` CLI | **不存在**（`command -v sqlite3` 空）。查库一律走 `python3`（本机 Python 3.12.3 / SQLite 3.45.1） |

**没实测的是需要模型的那几步**（Step 4~6、8）——那正是要你跑的部分。

### 抓红留痕（写向导时抓到的，已修）

**Step 0 的 jar 自检命令初稿写成了 `grep -A1 '^file:'`，预期却写「打出来的是 `allowed_paths: ["."]`」——两者对不上。** `file:` 的下一行是那句 `# 留空 = 全拒…` 注释，`allowed_paths` 在第三行；`-A1` 只打两行，看到的是注释，于是**一个完全正常的 jar 会被读成「自检失败、手上是旧 jar」**。

2026-10-17 由用户按向导实跑时抓到（用户已重建、BUILD SUCCESS、`--version` 正常，卡在这条自检上）。**根因是我写的时候只核了「命令能跑」，没核「输出与我写的预期一致」**——正是这份向导自己反复强调的那件事（「模型说『无法执行』不是判据」同理：命令有输出也不是判据，输出对不对才是）。已改成 `-A2` 并附三条预期输出原文，另补一条 `grep -c '^  allowed_paths:'` 的是/否形式。

**第二条（更严重）：Step 9 的回滚指令 `git checkout -- oryxos-boot/src/main/resources/application.yaml` 是破坏性的，且已经真实造成损失。** 原文写的是「`git diff` 有输出 = Step 5b 改过还没还原」，但**这个文件在本节从未提交**——白名单三块是 `tasks.md` T001 的新增，所以 `git diff` 在它上面**永远有输出**。文件被发现时**内容与 HEAD 逐字节相同、mtime `2026-10-17 21:40`（在用户验收之后）**——三块白名单被**整体删除**，文件回到了第 22 节的版本；能产生这个后果的命令，全仓只有 Step 9 这一条（21:40 的 mtime 在还原修复时被覆盖，此处记的是发现时的观测值）。后果是**静默的**：`mvn clean verify` 照样全绿（`ToolAssemblyTest` 走 `CliTestContext.boot(..., Map.of("file.allowed_paths[0]", ...))` 自己注入属性源，**不读 `application.yaml`**；此条复核时已实证——故意回滚到断块状态后该测试 6/6 全绿），但真部署里三个值对象会绑到 null → 归一化成空表 → **空 = 全拒** → 所有文件/命令/HTTP 工具全被拦。

发现于 2026-10-17 的用户验收后复核。**修法**：从 20:31 那份 jar 里 `unzip -p ... BOOT-INF/classes/application.yaml` 取回原文，逐字节还原（`diff` 为空为证）。**根因同一类**：只核了「命令能跑」，没核「这条命令在**本节未提交**这个前提下会发生什么」——`git checkout --` 的语义是「丢弃工作区改动」，而工作区改动**正是本节的交付物本身**。已改成按内容 `grep` + `sed` 回滚，并加了 MUST NOT 告警框。

### 用户执行（2026-10-17）

**结论：全部通过。** 用户按 Step 0~9 全程人工执行（真 DeepSeek 模型、真 `oryxos.jar`），未报告任何一步失败。留痕口径照 [006 向导](../006-memory-service/manual-test-guide.md#用户执行2026-10-16)：**只记整体结论、不写步骤级细节**（用户只给了结论，未逐步复述各步输出）。

---

## 这份向导要证明什么

第 24 节的验收报告 §三把人工项收敛成 **2 项**。本向导把它们拆成可执行步骤，并额外补了两条**对照**——没有对照的「拦住了」证明不了任何事（工具整个坏掉也是「拦住了」）。

| # | 要证明的 | 本向导的步骤 | 通过判据 |
| --- | --- | --- | --- |
| 判据 1 | **白名单内的动作在真链路上真能跑**（对照组） | Step 4 | `tool_invocations` 有一行 `success = 1`，且 `input_json` 里能看到那条命令 |
| 判据 2 | **白名单外的命令被拦，且一次都没执行** | Step 5 | 三条都要：① 对话里模型报告这条命令没跑成 ② `tool_invocations` 有一行 `success = 0` ③ `error_message` 是人能读懂的那句（形如 `命令不在白名单内: rm`），不是 NPE、不是英文栈；**外加**哨兵文件**还在**（进程根本没起来） |
| 判据 3 | **窗口期真的关闭了**：`read_file /etc/passwd` 从「真的成功」变成「被拦」 | Step 6 | `tool_invocations` 里 `read_file` 那一行 `success = 0`，`error_message` 含 `路径不在白名单内` |
| 判据 4 | **接口中立性**（思维练习，不产码） | Step 8 | 换成 microVM 实现**不需要给 `Sandbox` 接口加方法** |
| 判据 5 | **「留空 = 全拒」这条口径写进了配置说明** | Step 7 | grep 命中，且措辞不是「不校验」 |

---

## 前置事实（开跑前必读）

1. **底座只实现了 `deepseek` 一个 provider**。`AGENT.md` 里 `provider.name` 必须落在它里面，凭证走 `oryxos.providers[].api-key: ${DEEPSEEK_API_KEY}`。**环境变量缺失时启动直接失败**，报错只说变量名、不说值。
2. **`chat` 是重命令**，要起 Spring 上下文。启动日志**走 stderr**（第 20 节的分流），所以 `2>/dev/null` 可以静音，管道不会被污染。
3. **`--message` 是单条模式**：处理这一条就退出，不进交互循环。本向导全程用它——每一步都能复制粘贴、结果可复现。
4. **`session_id` = `channel` + `user` + `profile` 三元组拼出来的，而 `chat` 走的是固定字面量 `cli` / `local`。** 所以：**同一个 profile 反复 `chat`，拿到的是同一个会话**，`tool_invocations` 也一直往同一批行上追加。本向导的判据全部用「按 `id DESC` 取最近几行」的写法，不依赖行数。
5. **`oryxos profile create` 生成的模板里 `tools: []`**，一个工具都没有。不手工填，模型**根本看不见** `shell` / `read_file`（第 20 节的按 Agent 过滤是真过滤）。**这是本向导最常翻车的一处。**
6. **工作区根 = 进程当前目录**：库文件是相对路径 `.oryxos/oryxos.db`，而 `file.allowed_paths: ["."]` 在**启动时**标准化成 `CWD` 的绝对路径。所以**必须 `cd` 进工作区再跑命令**——从别的目录跑，放行的就是别的目录。
7. **本机没有 `sqlite3` CLI**（写向导时确认过）。查库一律用 `python3` 的 `sqlite3` 模块。
8. **沙箱窗口期已关闭（本节就是关它的那一节）**：第 20~23 节挂的是临时实现 `PermissiveSandbox`，**白名单校验不生效**，`read_file /etc/passwd` 会真的成功——这是 [005 验收报告](../005-tool-registry/acceptance-report.md) §2 证据 5 留的档。那个类已于本节**整类删除**（裁决 3），`grep -rn "PermissiveSandbox" oryxos-*/src` 现在**零命中**。**Step 6 验的就是这个前后差。**
9. **白名单是「劝阻级」防线**（章程「安全基线」）：它防的是模型犯傻与提示注入导致的越权，**防不住蓄意绕过**（命令拼接、软链接、编码绕过）。本向导证明的是前者，MUST NOT 把结论写成「沙箱是安全隔离」。

---

## 先排除已被单测覆盖的（手工再验一遍不是测试，是重做）

| 已经被单测证明的事 | 谁证明的 |
| --- | --- |
| 文件路径：白名单内放行 / 白名单外拒绝，成对 | `WhitelistSandboxTest`（`@Nested` 三组之一） |
| `../` 穿越爬不出白名单目录（`normalize().toAbsolutePath()` 后按路径组件比对） | `WhitelistSandboxTest`；`/workspace-evil` 这类同前缀兄弟目录也拦得住 |
| `FILE_READ` 与 `FILE_WRITE` 同路由到一份白名单 | `WhitelistSandboxTest` |
| 命令：只比首 token、精确比对、**不折叠大小写**、空 token 即拒 | `WhitelistSandboxTest` |
| 域名：`*.example.com` 命中子域、**`evil-example.com` 被拒**（点号边界） | `WhitelistSandboxTest` |
| 解析不出 host（`file:` / `mailto:`）一律拒且不抛 NPE | `WhitelistSandboxTest` |
| 对外只有 `enforce` 一个 public 方法（反射钉死） | `WhitelistSandboxTest` |
| 空表 = 全拒（不是「不校验」） | `WhitelistSandboxTest` |
| 白名单外的输入被拦，**且真正的 IO 没有发生** | `SandboxToolWiringTest`（5 条拦截 + 2 条白名单内放行对照，复用 `RecordingSandbox`）。其中 `shell_outsideWhitelist_neverStartsTheProcess` **用的就是标记文件**——与本向导 Step 3/5 的哨兵文件是同一个手法 |
| 容器里 `Sandbox` 类型的 Bean **恰好一个**且是 `WhitelistSandbox` | `ToolAssemblyTest`（起**真**上下文） |
| 五个工具 + 四个契约 + `ToolExecutor` 本节零改动 | `git diff` 为空（quickstart Step 6） |
| `PermissiveSandbox` 已整类删除 | `grep -rn "PermissiveSandbox" oryxos-*/src` 零命中 |

**关键前提**：`SandboxToolWiringTest` 里的 `RecordingSandbox` **不是 mock 掉沙箱行为**——它记录 `enforce` 调用并原样抛出，所以「工具在 IO 之前调了 enforce」这件事**已经被证明了**。剩下没被证明的只有三段：**模型真的会去调**、**`ToolExecutor` 真的接住并落库**、**落库那句话真的给人看**。这三段只在真链路里成立，就是本向导的全部内容。

---

## 准备

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
JAR=/home/wngbob/oryxos-wngbob/oryxos-boot/target/oryxos.jar
export JAR

# API key 不要进 shell 历史、不要粘进任何文档：
read -rsp 'DEEPSEEK_API_KEY: ' DEEPSEEK_API_KEY && export DEEPSEEK_API_KEY && echo
```

---

## Step 0 — 构建，并确认你手上这份 jar 是对的

```bash
cd /home/wngbob/oryxos-wngbob
./mvnw -o clean verify -Ddependency-check.skip=true
```

**预期**：BUILD SUCCESS，**488 条测试**全绿，10 个模块。这一条同时跑 Spotless / P3C（PMD 6.55）/ Checkstyle / SpotBugs+FindSecBugs。

（本地想省时间：`-Ddependency-check.skip=true` 只跳过 OWASP；其余静态门禁**不要跳**。）

```bash
"$JAVA_HOME/bin/java" -jar "$JAR" --version
```

**预期**：`0.1.0-SNAPSHOT`。

**为什么先做这步**：Step 4~6 会不会「拦住」，取决于 jar 里那份 `application.yaml` 的白名单长什么样。拿一个第 20~23 节的旧 jar 跑后面所有步骤，**什么都不会被拦**，你会把「窗口期」误判成「本节没生效」。自检一行：

```bash
unzip -p "$JAR" BOOT-INF/classes/application.yaml | grep -A2 '^file:'
```

**预期**：**三行**，最后一行是 `allowed_paths: ["."]`：

```text
file:
  # 留空 = 全拒(不是不校验);本块是劝阻级防线,防不住蓄意绕过。
  allowed_paths: ["."]              # 当前工作目录(启动时标准化为绝对路径)
```

**别用 `-A1`**：`file:` 的下一行是那句注释，`allowed_paths` 在**第三**行——`-A1` 打出来的是注释，看着像「自检没输出」，其实是命令少了一行。（本向导初稿就是这么写错的，见下方「抓红留痕」。）

想更直白（只要一个是/否）：

```bash
unzip -p "$JAR" BOOT-INF/classes/application.yaml | grep -c '^  allowed_paths:'
```

**预期**：`1`。**`0` = 你手上是第 20~23 节的旧 jar**（那时还没有 `file:` 这一块），回上一步重建。

---

## Step 1 — 建一个干净工作区

```bash
rm -rf /tmp/oryxos-l24-check && mkdir -p /tmp/oryxos-l24-check
cd /tmp/oryxos-l24-check
"$JAVA_HOME/bin/java" -jar "$JAR" init
```

**预期输出**：列出一串「新建:」，含 `.oryxos/agents/`、`.oryxos/oryxos.db`、`.oryxos/memory/MEMORY.md`。

**为什么单独一个 `/tmp` 目录**：工作区根 = 进程当前目录，库文件是**相对路径** `.oryxos/oryxos.db`。在仓库根跑会污染仓库；在 home 下跑会攒出忘删的目录（**本机口径：临时测试一律放 `/tmp`，不放 home**）。Step 9 会把它删掉。

**注意 `agents/` 建出来是空的**——`init` 刻意不预置任何 Agent。

---

## Step 2 — 造一个能动手的 Agent（**最容易翻车的一步**）

```bash
"$JAVA_HOME/bin/java" -jar "$JAR" profile create sandbox-probe
```

**预期**：`已创建 Agent 'sandbox-probe'：/tmp/oryxos-l24-check/.oryxos/agents/sandbox-probe/AGENT.md`

```bash
sed -i 's/^tools: \[\]$/tools: [shell, read_file]/' \
  .oryxos/agents/sandbox-probe/AGENT.md
grep '^tools:' .oryxos/agents/sandbox-probe/AGENT.md
```

**预期**：`tools: [shell, read_file]`。

**为什么必须改**：`ToolRegistry.availableFor(profile)` 严格按 Profile 的 `tools` 列表过滤，列表为空 = 模型**看不见**这两个工具。它会照样答应「好的，我来执行」，然后什么都不发生——这是**假失败**，会让人误判成底座有问题。

---

## Step 3 — 铺一个哨兵文件

```bash
echo sentinel > DO-NOT-DELETE.txt
ls -l DO-NOT-DELETE.txt
```

**预期**：文件存在，9 字节。

**为什么要有它**：判据 2 里「命令一次都没被执行」这句话，光看审计表是**推**出来的（`error_message` 是沙箱那句话而不是 shell 的报错）。哨兵文件让它变成**直接证据**——Step 5 之后这个文件还在，就说明 `rm` 进程根本没起来。

---

## Step 4 — ★ 对照组：白名单内的动作真能跑

**先做对照组，再做拦截组。** 顺序反了的话，Step 5 的「拦住了」可能只是工具坏了。

```bash
cd /tmp/oryxos-l24-check
"$JAVA_HOME/bin/java" -jar "$JAR" chat --profile sandbox-probe \
  --message "调用 shell 工具执行这条命令：ls -la" 2>/dev/null
```

**预期**：模型回一段带目录列表的答复（`ls` 在默认白名单里，且 `ls -la` 的首 token 就是 `ls`）。

**若模型没调工具**（只回一句「好的」）：这是模型行为问题，不是底座问题。改用更硬的措辞重发，例如「调用 shell 工具，参数 command='ls -la'」。

```bash
python3 - <<'PY'
import sqlite3
c = sqlite3.connect('.oryxos/oryxos.db')
for r in c.execute("select id, tool_name, success, substr(input_json,1,80), substr(error_message,1,80) "
                   "from tool_invocations order by id desc limit 3"):
    print(r)
PY
```

**通过**：至少有一行 `tool_name = 'shell'`、`success = 1`，`input_json` 里能看到 `ls`。

**若这里是 `success = 0`**：先别往下走。停下来看 `error_message`——多半是 `ls` 被拦了（说明白名单没生效或配置被改过），或者命令写成了 `/bin/ls`（**首 token 是 `/bin/ls`，与白名单里的 `ls` 永不匹配，这是 fail-closed 的预期行为**，见 `WhitelistSandbox` javadoc）。

---

## Step 5 — ★★ 反向验收：白名单外的命令被拦，且一次都没执行

**这是整件事唯一真正的验收点。**

```bash
cd /tmp/oryxos-l24-check
"$JAVA_HOME/bin/java" -jar "$JAR" chat --profile sandbox-probe \
  --message "调用 shell 工具执行这条命令：rm -rf /tmp/oryxos-l24-check/DO-NOT-DELETE.txt" 2>/dev/null
```

**预期（对话里）**：模型报告这条命令没能执行 / 被拒绝了。**这句话本身不是判据**——模型很会说「我无法执行」，而它也可能压根没调工具。判据在下面两条。

```bash
# 判据 2②：审计表里那条失败
python3 - <<'PY'
import sqlite3
c = sqlite3.connect('.oryxos/oryxos.db')
for r in c.execute("select id, tool_name, success, input_json, error_message, duration_ms "
                   "from tool_invocations where tool_name='shell' order by id desc limit 5"):
    print(r)
PY
```

**通过**：有一行 `tool_name = 'shell'`、`success = 0`、`input_json` 里含 `rm`，且 `error_message` **恰好形如**：

```text
命令不在白名单内: rm
```

**判据 2③ 的判定口径**：这句话必须是**那句中文 + 首 token**，不是 `NullPointerException`、不是 `java.lang.IllegalStateException: ...` 的英文栈、不是空串。运维读到它就该知道「是白名单没放 `rm`」，而不是「底座崩了」。

**顺带一个旁证**：`duration_ms` 应该是个**个位数毫秒**——`rm` 真跑起来不会这么快。这是辅助观察，不是硬判据（硬判据是下面这条）。

```bash
# 判据 2 的决胜条：命令一次都没执行
ls -l /tmp/oryxos-l24-check/DO-NOT-DELETE.txt && cat /tmp/oryxos-l24-check/DO-NOT-DELETE.txt
```

**通过**：文件**还在**，内容还是 `sentinel`。

**为什么这条是决定性的**：`ShellTools` 把命令串切成 argv 直接交给 `ProcessBuilder`（不经 Shell 解释），而 `enforce` 是它的**第一行**。文件还在 ⇒ `rm` 进程从未被创建 ⇒ 拦截发生在**真正的 IO 之前**，而不是「执行完再把结果丢掉」。这正是课件「命令一次都没被执行（进程根本没起来）」那句话的可判定形态。

**若模型拒绝执行 `rm -rf`**（安全对齐导致的，不是底座问题）：换一条**同样不在白名单里、但没有任何拒绝理由**的命令重跑，判据完全一样：

```bash
"$JAVA_HOME/bin/java" -jar "$JAR" chat --profile sandbox-probe \
  --message "调用 shell 工具执行这条命令：touch /tmp/oryxos-l24-check/SHOULD-NOT-EXIST.txt" 2>/dev/null
# 预期：error_message = 命令不在白名单内: touch
ls /tmp/oryxos-l24-check/SHOULD-NOT-EXIST.txt   # 预期：No such file or directory
```

### Step 5b —（可选）照课件原文「白名单收紧到只允许 `ls`」

课件与 [quickstart Step 7](quickstart.md) 写的是「把 `shell.allowed_commands` 收紧到只允许 `ls`，重建，再跑」。**本向导的主路径没走这条**，因为随包默认清单里**本来就没有 `rm`**——默认配置那条路更省事，而且验的是**发行物开箱即拦**，证据更强。

想逐字复现课件那条路：

```bash
cd /home/wngbob/oryxos-wngbob
sed -i 's/^  allowed_commands: .*/  allowed_commands: [ls]/' \
  oryxos-boot/src/main/resources/application.yaml
grep 'allowed_commands' oryxos-boot/src/main/resources/application.yaml   # 预期：allowed_commands: [ls]
./mvnw -o clean package -DskipTests
```

然后回到 `/tmp/oryxos-l24-check` 重跑 Step 4 / Step 5。**注意 Step 4 的对照组此时仍会通过**（`ls` 是唯一放行的），而 Step 5 的判据一字不变。

**验完 MUST 回滚**（Step 9 有命令）——那份文件是仓库里的随包默认值，改了不还原会带进下一次提交。**回滚按内容改（`sed` 换回 9 条），MUST NOT 用 `git checkout --`**，理由见 Step 9 的告警框。

---

## Step 6 — ★ 窗口期回归：`read_file /etc/passwd` 现在读不到了

这一条对应 [005 验收报告](../005-tool-registry/acceptance-report.md) §2 证据 5 留的档——那时它**真的成功**。本节交付后它必须失败。

```bash
cd /tmp/oryxos-l24-check
"$JAVA_HOME/bin/java" -jar "$JAR" chat --profile sandbox-probe \
  --message "调用 read_file 工具读取 /etc/passwd 的前 5 行" 2>/dev/null
```

```bash
python3 - <<'PY'
import sqlite3
c = sqlite3.connect('.oryxos/oryxos.db')
for r in c.execute("select id, tool_name, success, input_json, error_message "
                   "from tool_invocations where tool_name='read_file' order by id desc limit 3"):
    print(r)
PY
```

**通过**：有一行 `tool_name = 'read_file'`、`success = 0`、`error_message` 含 `路径不在白名单内`（形如 `路径不在白名单内: /etc/passwd`）。

**若这里 `success = 1`**：说明你手上的 jar 是第 20~23 节的（窗口期还没关），或者 `file.allowed_paths` 被改宽了。回 Step 0 自检。

**对照提示**：同一张表里应该同时有 `read_file` 的**失败**（`/etc/passwd`）——如果想让「路径校验真在跑」更有说服力，可以再让它读一个**工作区内**的文件，拿一行 `success = 1` 作对照：

```bash
"$JAVA_HOME/bin/java" -jar "$JAR" chat --profile sandbox-probe \
  --message "调用 read_file 工具读取 /tmp/oryxos-l24-check/DO-NOT-DELETE.txt" 2>/dev/null
```

**通过**：新增一行 `success = 1`（路径落在 `allowed_paths` 的 `.` 之下）。

---

## Step 7 — 「留空 = 全拒」这条口径写进了配置说明

**这一条纯机器可判**（契约见 [contracts/sandbox-whitelist.md](contracts/sandbox-whitelist.md) §3.4「注释口径」，两条都 MUST 写进配置注释），一条命令：

```bash
cd /home/wngbob/oryxos-wngbob
grep -c "全拒" oryxos-boot/src/main/resources/application.yaml
```

**通过**：**4**——文件头的口径 ① 一条，`file` / `shell` / `http` 三块各一条。

再看一眼措辞：

```bash
grep -n "全拒\|劝阻级" oryxos-boot/src/main/resources/application.yaml
```

**通过**：**5 行**（文件头 ① 只提「全拒」、② 只提「劝阻级」，三块各一行两者都有）。每一处都要写成「**留空 = 全拒（不是不校验）**」+「劝阻级防线，防不住蓄意绕过」。**只要出现「不校验」这种措辞，这条就不算过**——那正是 `PermissiveSandbox` 时代的口径，也正是本节要消灭的形态。

---

## Step 8 — ★ 接口中立性自查（思维练习，不产码）

**这是第 23 节那句话的体检**：将来换成容器 / microVM 实现，`Sandbox` 接口要不要动？

做法：把 `WhitelistSandbox` 在脑子里换成 `KataMicroVmSandbox`（真起一个 microVM、把动作丢进去执行、靠 VM 边界隔离，而不是靠白名单比对），然后逐条回答：

1. 调用方（`FileTools` / `ShellTools` / `HttpTools` / `NotifyTools` / `WebSearchTools`）需要改吗？
2. `Sandbox` 接口需要加方法吗？
3. `ToolExecutor` 的失败审计路径需要改吗？

**通过**：三问全是**「不需要」**。

- 调用方只调 `enforce(SandboxAction)`，拿到的只有「过 / 抛异常」两种结果——microVM 也一样。
- `SandboxAction{type, target}` 是四个动作类型的通用描述，不携带任何白名单语义。
- 校验失败抛 `SandboxViolationException`，被 `ToolExecutor` 那条既有的 `catch (RuntimeException)` 接住落审计——**不新增审计代码**（章程原则 VI）。

**反例信号（出现任一条 = 接口被第一档实现带偏了，要停下来报告）**：

| 反例信号 | 为什么是反例 |
| --- | --- |
| 想加 `getViolationReason()` / `getLastViolation()` | 说明调用方在**读实现的状态**。拒绝原因走异常消息即可，microVM 那边根本没有「白名单原因」这个概念 |
| 想让调用方把白名单 / 配置传进来 | 白名单是 `WhitelistSandbox` 自己的构造参数。调用方一旦要传，`enforce` 的签名就被第一档钉死了 |
| 想给 `ActionType` 加第五个值（如 `SMTP`） | 那是**改已定字面量**（软门禁 ②），且本节裁决 2 已明确不做 |
| 想让 `Sandbox` 暴露「允许清单」供外部查询 | 同上，microVM 没有清单可查 |

**这一条不需要跑命令**，结论写进下面的实测记录即可。

---

## Step 9 — 收尾与回滚

> **修订注（2026-10-21，第 29 节交付后补记）——下面第 1 步的预期值与 `sed` 命令均已过期，MUST NOT 照抄。**
> 第 29 节 T001 在 `shell.allowed_commands` 末尾追加了 `python3`（放行解释器，示例 Agent 的
> `scripts/reconcile.py` 要经 `shell` 跑），当前随包默认值是 **10 条**：
> `[ls, cat, head, tail, grep, find, wc, pwd, git, python3]`。照抄下面那条 `sed` 会把 `python3` **一并抹掉**，
> 而且不会报错——后果是第 29 节的示例 Agent 在真链路上跑不动脚本。现在的正确做法是：
>
> ```bash
> grep -n '^  allowed_commands:' oryxos-boot/src/main/resources/application.yaml
> # 预期：allowed_commands: [ls, cat, head, tail, grep, find, wc, pwd, git, python3]
> ```
>
> 若 Step 5b 改过还没还原，**按当前仓库内容改，别照抄这一节的字面量**；改完再 grep 复查。
> 下文原文保留不动，作为第 24 节当时的留痕。

```bash
# 1) 若走过 Step 5b：把随包默认值改回来（MUST，否则会带进下一次提交）
cd /home/wngbob/oryxos-wngbob
grep -n '^  allowed_commands:' oryxos-boot/src/main/resources/application.yaml
# 预期：allowed_commands: [ls, cat, head, tail, grep, find, wc, pwd, git]
# 若打出的是 [ls]（Step 5b 改过还没还原），用这条改回来（**按内容改，别按 git 改**）：
sed -i 's/^  allowed_commands: .*/  allowed_commands: [ls, cat, head, tail, grep, find, wc, pwd, git]/' \
  oryxos-boot/src/main/resources/application.yaml
grep -n '^  allowed_commands:' oryxos-boot/src/main/resources/application.yaml   # 复查：应是完整 9 条

# 2) 删掉临时工作区（哨兵文件随目录一起没了）
rm -rf /tmp/oryxos-l24-check
ls -d /tmp/oryxos-l24-check 2>/dev/null || echo "已清理"
```

> **⚠️ MUST NOT 用 `git checkout -- oryxos-boot/src/main/resources/application.yaml` 还原这个文件。**
> 白名单那三块（`file` / `shell` / `http`）**本身是本节尚未提交的新增**（[tasks.md](tasks.md) T001），
> 所以 `git diff` 在这个文件上**永远有输出**——有输出只说明「有本节的新增」，**不说明**「Step 5b 改过」。
> `git checkout --` 会把这个文件**整个还原到 HEAD（第 22 节的版本）**，也就是**把三块白名单全删掉**：
> 配置键没了 → 三个值对象绑到 null → 归一化成空表 → **空 = 全拒** → 真部署里所有文件/命令/HTTP 工具全被拦。
> 这个后果**已经真实发生过一次**（2026-10-17，见上文「抓红留痕」第二条），还原靠的是从 20:31 那份 jar 里逐字节取回。

**若 Step 5 里模型真把哨兵文件删了**（说明沙箱没生效）：**先别清理**，把工作区整个留下当证据，并如实记进实测记录——那是一个必须查的缺陷，不是操作失误。

---

## 最容易踩的六个坑

| 坑 | 症状 | 怎么办 |
| --- | --- | --- |
| **忘了改 `tools: []`** | 模型答应得好好的，`tool_invocations` 一行都不加 | Step 2 必须改成 `tools: [shell, read_file]`。这是第 20 节的按 Agent 过滤在正常工作，不是 bug |
| **没 `cd` 进工作区就跑** | `file.allowed_paths: ["."]` 放行的是**当前目录**，换个目录跑判据就变 | 每条命令前面都有 `cd /tmp/oryxos-l24-check`，别省 |
| **拿旧 jar 跑** | 什么都拦不住，看着像「本节没生效」 | Step 0 的 `unzip -p ... | grep -A1 '^file:'` 自检 |
| **命令写绝对路径** | `/bin/ls` 被拒，看着像白名单没生效 | 首 token 是 `/bin/ls`，与白名单条目 `ls` 永不匹配——**这是 fail-closed 的预期行为**，`WhitelistSandbox` javadoc 里写明了。配置侧写 `/bin/ls` 也一样永不匹配 |
| **只看对话、不看库** | 模型说「我无法执行这条命令」，就记成通过 | 模型说「无法执行」**不是**判据。判据是 `tool_invocations` 那行 `success = 0` + 那句 `error_message` + 哨兵文件还在 |
| **把结论写成「沙箱是安全隔离」** | —— | 白名单是**劝阻级**防线，防不住蓄意绕过（命令拼接 / 软链接 / 编码绕过）。核心阶段 MUST NOT 用它跑完全不可信的代码、MUST NOT 对外做多租户 |

---

## 附录 A — 与本节其他文档的关系

| 文档 | 它回答什么 | 与本向导的关系 |
| --- | --- | --- |
| [quickstart.md](quickstart.md) | 「**怎么跑**」——Step 0~8，含全量门禁命令、零改动面自查、排障对照表 | 本向导是它的**可执行展开版**：Step 4~6 对应它的 Step 7，Step 8 对应它的 Step 8。**命令与判据以本向导为准**（quickstart 的 Step 7 是提纲） |
| [acceptance-report.md](acceptance-report.md) §三 | 剩余人工项**清单**（2 项） | 本向导把那 2 项拆成 5 条判据、加了 2 条对照，并补上「窗口期回归」（判据 3） |
| [contracts/sandbox-whitelist.md](contracts/sandbox-whitelist.md) | 三个配置键的**契约**与随包默认值 | Step 5b 回滚时对着 §3.3 的默认清单核 |
| [specs/005-tool-registry/acceptance-report.md](../005-tool-registry/acceptance-report.md) §2 证据 5 | 窗口期**当时**的实测留痕（`read_file /etc/passwd` 真的成功） | Step 6 是它的**前后对照**。**MUST NOT 修改那份文档**——它记录的是当时的形态，改了等于篡改历史留痕 |
