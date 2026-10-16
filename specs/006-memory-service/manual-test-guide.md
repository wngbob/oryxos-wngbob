# Memory（第 22 节）人工验收向导（Step by Step）

**Feature**: `specs/006-memory-service` | **Date**: 2026-10-15 | **分支**: `class-22`

> 这份向导只覆盖**机器判不了的那几项**。`mvn clean verify`（451 条测试）已经跑过的东西不在这里重做——清单见「先排除已被单测覆盖的」。

---

## 适用对象

你手上有这个仓库、能跑 Maven、能拿到一个 DeepSeek API key。**不需要写任何 Java 代码**——本向导的每一条都是 shell 命令 + 眼睛看 + 一个 python3 单行脚本查库。

## 目的

证明四件事，且**只证明这四件**：

| # | 判据 | 落在哪一步 | 通过的标准 |
| --- | --- | --- | --- |
| 1 | **三档切换**：改一行配置，同一段对话、同一个体感，底下换了后端 | Step 6 | `-Doryxos.memory.backend=sqlite` 后，记忆落进 `memory_entries` 表而不是 `MEMORY.md`；上层代码一行没改 |
| 2 | **真模型完整走一遍**：Agent 主动调 `save_memory`，下一轮能查回来 | Step 3 / Step 5 | 审计表里**真有** `save_memory` 那行；文件里**真有**那条记忆 |
| 3 | **跨会话长期记忆**：开一个全新会话，核心记忆自动在场 | Step 4 / Step 5 | 新会话的 `messages_json` 里**没有**上一次的对话，Agent 却答得出那个不可猜的事实 |
| 4 | **`MEMORY.md` 与 `USER.md` 的角色** | Step 1 / Step 8 | `MEMORY.md` 是 Agent 的（读写）；`USER.md` 是你的（OryxOS 只读，全仓无写入路径） |

**预计耗时**：Step 0~8 约 30 分钟（其中 Step 0 构建 5~10 分钟）。Step 9（Mem0）另算，要先有一个自托管 Mem0——**该步已于 2026-10-16 经用户裁决不做**，留在这里是为了让后来者知道「还有这么一档没对过真实服务」，不是为了让你补跑。

## 实测记录

### 写向导时已实测（2026-10-15）

**不需要模型的那几条，写这份向导时已经真跑过了**，命令与输出都对得上（`/tmp/oryxos-l22-check`，跑完已删）：

- `oryxos init` 建出的 `MEMORY.md` 内容**恰好**是 `## 核心记忆` + 空行 + `## 归档记忆`，无示例条目
- `oryxos init` 建出的表里有 `memory_entries`，四列 `id / scope / content / created_at`
- `oryxos profile create memory-demo` 生成的 `AGENT.md` 里 **`tools: []`**（这一步是本向导最容易翻车的地方，见「坑 1」）
- `-Doryxos.memory.backend=sqlite` **真的生效**（上下文正常起、`tool list` 正常）
- `-Doryxos.memory.backend=bogus` **真的报错**：`未知的长期记忆后端 "bogus"（合法取值：markdown / sqlite / mem0）`，**不静默退回默认档**
- `oryxos tool list` 默认输出 **`builtin (15)`**

写向导的那台机器上没有 `DEEPSEEK_API_KEY`，故需要模型的那几条（Step 3 / 5 / 6 / 9）当时**未跑**——本向导里它们的「预期」是按代码推出来的。

### 用户执行（2026-10-16）

**用户按 Step 0~8 全程人工执行，结论：全部通过，未报告任何一步失败。**

| 步骤 | 状态 |
| --- | --- |
| Step 0 构建 / 1 初始形态 / 2 造 Agent | ✅ 过 |
| Step 3 真模型主动调 `save_memory`（审计表为凭据） | ✅ 过 |
| Step 4 隔离「文件 → prompt」 | ✅ 过 |
| Step 5 **决定性证据**：删库后新会话仍答得出不可猜的事实 | ✅ 过 |
| Step 6 切 `sqlite` 档 | ✅ 过 |
| Step 7 非法后端取值报错 | ✅ 过 |
| Step 8 `USER.md` 只读 + 依赖方向自查 | ✅ 过 |
| Step 9 Mem0 档真连 | ⛔ **有意不做**（用户裁决；不是遗漏，也不是待办） |

**如实留痕**：用户未逐步说明各步的具体输出（例如 Step 6 的反向确认做没做），故这里**只记「整体通过」这个结论，不写步骤级细节**。

**因此下面各 Step 的「预期」现在的性质**：Step 0~8 的预期**已被真人真模型走过一遍**；**只有 Step 9 的预期仍是按代码推出来的**（该档经裁决不做）。

---

## 前置事实（开跑前必读）

1. **底座只实现了 `deepseek` 一个 provider**。`AGENT.md` 里 `provider.name` 必须落在它里面，凭证走 `oryxos.providers[].api-key: ${DEEPSEEK_API_KEY}`。**环境变量缺失时启动直接失败**，报错只说变量名、不说值。
2. **`chat` 是重命令**，要起 Spring 上下文。启动日志**走 stderr**（第 20 节的分流），所以 `2>/dev/null` 可以静音，管道不会被污染。
3. **`--message` 是单条模式**：处理这一条就退出，不进交互循环。本向导全程用它——每一步都能复制粘贴、结果可复现。
4. **`session_id` = `channel` + `user` + `profile` 三元组拼出来的，而 `chat` 走的是固定字面量 `cli` / `local`。** 所以：**同一个 profile 反复 `chat`，拿到的是同一个会话**，历史一直在累积。想拿一个「全新会话」，只有两条路——换 profile 名（Step 4）或删库（Step 5）。
5. **`oryxos profile create` 生成的模板里 `tools: []`**，一个工具都没有。不手工填，`save_memory` 对模型**根本不存在**（第 20 节的按 Agent 过滤是真过滤）。**这是本向导最常翻车的一处。**
6. **切后端 = 一个 JVM 系统属性**：`java -Doryxos.memory.backend=sqlite -jar ...`。`-D` 必须在 `-jar` **前面**。系统属性优先级高于 jar 里的 `application.yaml`，已实测。
7. **本机没有 `sqlite3` CLI**（写向导时确认过）。查库一律用 `python3` 的 `sqlite3` 模块，本机 3.45.1 可用。
8. **沙箱窗口期（别误判）**：第 20~23 节挂的是临时实现 `PermissiveSandbox`，**白名单校验不生效**，`read_file /etc/passwd` 会真的成功。这是**已报批的取舍**，归第 24 节。**验收 MUST NOT 把它记成本节的缺陷**；反过来，记忆文件 IO **根本不调** `Sandbox.enforce`（仲裁 3——路径是编译期常量，模型无法影响，不构成越界面）。

## 先排除已被单测覆盖的（手工再验一遍不是测试，是重做）

| 已经被单测证明的事 | 谁证明的 |
| --- | --- |
| 写后立读、不许有缓存（契约一） | `MemoryStoreContractTest#append_isImmediatelyVisible_noCaching`，三档各跑一遍 |
| 截断只裁归档区、核心区一字不少（契约二） | `MemoryStoreContractTest#truncation_onlyTrimsArchival_coreMemorySurvivesIntact` |
| `scope` 路由到正确区块（契约三） | `MemoryStoreContractTest` + `MemoryToolsTest`（缺省写归档） |
| recall 只搜归档区、跨档不区分大小写（契约四） | `MemoryStoreContractTest` + `MarkdownMemoryStoreTest` + `SqliteMemoryStoreTest` |
| 归档区 `LIMIT` 100 条生效 | `SqliteMemoryStoreTest` |
| 未知后端取值报错、不静默退回 | `MemoryAssemblyTest`（起**真**上下文） |
| `HYBRID_BUILTIN` 抛异常、不静默降级成关键词检索 | `MemoryServiceImplTest` |
| 装配顺序（`memoryService()` 在 `memorySource()` 之前，让位机制才生效） | `MemoryAssemblyTest`，既断言行为也扫源码顺序 |
| Mem0 的 `append` 请求体带 scope、`recall` 转发查询 | `Mem0MemoryStoreTest`（`MockRestServiceServer`，**不碰真 server**） |
| Mem0 档的凭证不外泄（日志 / 异常消息 / `toString` 三处） | `Mem0MemoryStoreTest` |
| 两个工具名没渗进三个横切关注点 | `ToolOnboardingCostTest` |

---

## 准备

```bash
export JAVA_HOME=/usr/lib/jvm/java-1.21.0-openjdk-amd64
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

**预期**：BUILD SUCCESS，**451 条测试**全绿，10 个模块。这一条同时跑 Spotless / P3C（PMD 6.55）/ Checkstyle / SpotBugs+FindSecBugs。

（本地想省时间：`-Ddependency-check.skip=true` 只跳过 OWASP；其余静态门禁**不要跳**。）

```bash
"$JAVA_HOME/bin/java" -jar "$JAR" --version
```

**预期**：`0.1.0-SNAPSHOT`。

**为什么先做这步**：`-Doryxos.memory.backend` 能不能生效，取决于 jar 里的 `application.yaml` 长什么样。拿一个旧 jar 跑后面所有步骤，会把「配置没生效」误判成「代码有问题」。

---

## Step 1 — 建一个干净工作区，看记忆文件的初始形态

```bash
rm -rf /tmp/oryxos-l22 && mkdir -p /tmp/oryxos-l22 && cd /tmp/oryxos-l22
"$JAVA_HOME/bin/java" -jar "$JAR" init
```

**预期输出**：列出一串「新建:」，含 `.oryxos/memory/MEMORY.md`、`.oryxos/USER.md`、`.oryxos/oryxos.db`。

```bash
cat .oryxos/memory/MEMORY.md
```

**预期**——**恰好**这两行，**没有任何示例条目**：

```text
## 核心记忆

## 归档记忆
```

```bash
cat .oryxos/USER.md
```

**预期**：一个手写模板，带一行注释 `<!-- 本文件由你手工维护，OryxOS 只读不写。Agent 自己攒的记忆在 memory/MEMORY.md。 -->`，下面是「称呼 / 常用语言 / 关注领域」三个空条目。

**这一步在证明判据 4 的前一半**：两个文件的**角色**从 `init` 那一刻就分开了——一个是你的（只读），一个是 Agent 的（读写）。

顺手把工作区里该有的东西记下来（后面几步都要回头看）：

```bash
ls .oryxos/
python3 -c "import sqlite3;print([r[0] for r in sqlite3.connect('.oryxos/oryxos.db').execute(\"SELECT name FROM sqlite_master WHERE type='table' ORDER BY name\")])"
```

**预期**：表清单里有 `memory_entries`（SQLite 档要用它，此刻还是空的）。

---

## Step 2 — 造一个能记住事的 Agent

```bash
"$JAVA_HOME/bin/java" -jar "$JAR" profile create memory-demo
cat .oryxos/agents/memory-demo/AGENT.md
```

**你会看到 `tools: []`**（第 9 行左右）。**这一行必须改**——不改的话，模型那边压根看不见 `save_memory`，你怎么说它都不会记。

```bash
# 把两个记忆工具加进去
sed -i 's/^tools: \[\]$/tools: [save_memory, recall_memory]/' .oryxos/agents/memory-demo/AGENT.md
grep -n "tools:" .oryxos/agents/memory-demo/AGENT.md
```

**预期**：`tools: [save_memory, recall_memory]`。

> 顺手确认工具真的注册进去了（重命令，起上下文）：
>
> ```bash
> "$JAVA_HOME/bin/java" -jar "$JAR" tool list 2>/dev/null | head -3
> ```
>
> **预期**：`Tool（共 15 个）:` / `builtin (15):`——第 20 节的 13 个 + 本节的 `save_memory` / `recall_memory`。

---

## Step 3 — markdown 档：让 Agent 主动记一条

### 3.1 说一句值得记的话

**用一个模型猜不出来的事实**——这一点很关键，用「我偏好 Java」这种可猜的话，后面 Step 5 的判据就失效了：

```bash
cd /tmp/oryxos-l22
"$JAVA_HOME/bin/java" -jar "$JAR" chat --profile memory-demo \
  --message "记住一件事：我的内部项目代号是 ORYX-7742。请调用 save_memory 记下来。"
```

**预期**：Agent 回一句「已记住」之类的话。

> **但 Agent 说「记住了」不算证据。** 模型完全可能只是嘴上答应、一个工具都没调。下面两条才是判据。

### 3.2 判据 A —— 审计表里真有那行（这是模型有没有真动手的唯一凭据）

```bash
python3 - <<'PY'
import sqlite3
con = sqlite3.connect(".oryxos/oryxos.db")
rows = list(con.execute(
    "SELECT id, tool_name, success, duration_ms, substr(input_json,1,100) "
    "FROM tool_invocations ORDER BY id"))
for r in rows:
    print(r)
print("总条数:", len(rows))
PY
```

**通过**：至少有一行 `tool_name = 'save_memory'`、`success = 1`，且 `input_json` 里能看到 `ORYX-7742`。

**不通过**（一条都没有）：模型没调工具。换个说法再试一次（「请务必调用 save_memory 工具」）。**仍然没有就是真没调**——如实记下来，别把「Agent 说记住了」当成通过。

### 3.3 判据 B —— 文件里真有那条记忆

```bash
cat .oryxos/memory/MEMORY.md
```

**预期**：`## 归档记忆` 区多了一行，形状是 `- [2026-10-15] 我的内部项目代号是 ORYX-7742...`。

**为什么落在归档区**：`save_memory` 的 `scope` 缺省是 `archival`，而这个缺省值定在 `MemoryTools`、**不在门面**（契约三：系统不猜）。

### 3.4 再走一次核心区（判据 3 要用它）

```bash
"$JAVA_HOME/bin/java" -jar "$JAR" chat --profile memory-demo \
  --message "再用 scope=core 记住一条：我的时区是 Asia/Shanghai。请调用 save_memory 并传 scope=core。"
cat .oryxos/memory/MEMORY.md
```

**预期**：这一行落在 **`## 核心记忆`** 区。

**如果模型没传对 `scope`**：**直接手工把那一行挪到 `## 核心记忆` 区下面**。这不是作弊——这个后端的卖点就是「人可以直接改这个文件」（`MarkdownMemoryStore` 的 javadoc 原话），而且 Step 4 本来就要靠手改来隔离两半。

---

## Step 4 — 隔离两半：文件 → prompt（不依赖模型调工具）

记忆链路是两段：**模型 → 工具 → 文件**，和 **文件 → prompt → 模型**。Step 3 证的是第一段。这一段单独证第二段，把模型调工具这个变量摘掉。

手工往文件里塞一条**不可猜**的记忆：

```bash
cd /tmp/oryxos-l22
python3 - <<'PY'
p = ".oryxos/memory/MEMORY.md"
s = open(p, encoding="utf-8").read()
s = s.replace("## 归档记忆\n", "## 归档记忆\n- [2026-10-15] 我的工位编号是 B-3-17（手工写入，用于验证注入链路）\n", 1)
open(p, "w", encoding="utf-8").write(s)
print(s)
PY
```

**再开一个全新会话**——换一个 profile 名就是换一个 `session_id`（前置事实 4）：

```bash
"$JAVA_HOME/bin/java" -jar "$JAR" profile create memory-demo-2
# 这个 Agent 一个工具都不给它：注入是自动的，不需要工具
"$JAVA_HOME/bin/java" -jar "$JAR" chat --profile memory-demo-2 \
  --message "我的工位编号是多少？"
```

**通过**：答出 `B-3-17`。

**这条判据为什么干净**：`memory-demo-2` 的 `tools: []`（一个工具都没有），它**不可能**通过 `recall_memory` 查到——只能从 prompt 里被注入的那份长期记忆里读出来。**注入这条链路被单独证成了。**

---

## Step 5 — 跨会话：删掉库，再问一次（决定性证据）

Step 4 换 profile 换的是 `session_id`，但**没删掉旧会话**——严格说那是「另一个会话」而不是「历史被清空的会话」。这一步把最后的漏洞堵上。

### 5.1 先把证据存下来

```bash
cd /tmp/oryxos-l22
python3 - <<'PY'
import sqlite3
con = sqlite3.connect(".oryxos/oryxos.db")
print("--- 清库前的会话 ---")
for r in con.execute("SELECT session_id, profile_name, length(messages_json) FROM sessions"):
    print(r)
print("--- 清库前的工具审计 ---")
for r in con.execute("SELECT tool_name, success FROM tool_invocations ORDER BY id"):
    print(r)
PY
```

把输出**复制走**（下一步要删库，审计就没了）。

### 5.2 删库、重建

```bash
rm .oryxos/oryxos.db
"$JAVA_HOME/bin/java" -jar "$JAR" init
ls -la .oryxos/memory/MEMORY.md   # 记忆文件必须还在
```

**预期**：`init` 把库和表都重建出来，**`MEMORY.md` 一个字没动**。

> ⚠️ **这一步只在 markdown 档做**。SQLite 档的记忆**就存在这个库里**，删库等于把记忆一起删了。

### 5.3 问一个只有记忆能答的问题

```bash
"$JAVA_HOME/bin/java" -jar "$JAR" chat --profile memory-demo \
  --message "我的内部项目代号是什么？我的时区是什么？"
```

**通过**：答出 **`ORYX-7742`** 和 **`Asia/Shanghai`**。

### 5.4 把「会话历史是空的」这件事也变成证据

```bash
python3 - <<'PY'
import sqlite3, json
con = sqlite3.connect(".oryxos/oryxos.db")
for sid, msgs in con.execute("SELECT session_id, messages_json FROM sessions"):
    print(sid)
    for m in json.loads(msgs or "[]"):
        print("   ", str(m)[:120])
PY
```

**通过**：这个会话里**只有刚才那一次问答**（两条消息），**看不到** Step 3 里说「ORYX-7742」的那段对话。

**为什么这条是决定性的**：会话历史被证成空的，而 Agent 仍然答得出那两个**不可猜**的事实。系统里没有别的通道——没有向量库、没有别的状态、没有第二次模型调用——**只可能是长期记忆被注入进了 prompt**。这正是判据 2 与判据 3 的核心。

> 如果答不出来：先确认 `MEMORY.md` 里那两条真的在、且**在正确的分区标题下面**（`## 核心记忆` / `## 归档记忆` 是逐字匹配的，写错一个字，那一行就不算记忆——`MarkdownMemoryStore` 把 header 之前的行当「前言」，原样保留但不注入）。

---

## Step 6 — 切到 sqlite 档，同一段对话再走一遍

判据 1。**改的只有一个 JVM 系统属性**，上层 `MemoryTools` / `PromptBuilder` / `ReActLoop` 一个字不动。

```bash
cd /tmp/oryxos-l22
"$JAVA_HOME/bin/java" -Doryxos.memory.backend=sqlite -jar "$JAR" chat --profile memory-demo \
  --message "用 scope=core 记住：我的部门代号是 DEPT-9。请调用 save_memory。"
```

**预期**：Agent 正常回答，**和 markdown 档没有任何体感差别**。

### 判据 —— 记忆真的换了地方

```bash
# A) 表里多了行
python3 -c "
import sqlite3
for r in sqlite3.connect('.oryxos/oryxos.db').execute('SELECT id, scope, content FROM memory_entries ORDER BY id'):
    print(r)
"

# B) 文件里没有
grep -c "DEPT-9" .oryxos/memory/MEMORY.md || echo "MEMORY.md 里 0 条（预期）"
```

**通过**：A 输出一行 `scope = 'CORE'`、内容含 `DEPT-9`；B 输出 0。

### 反向确认（可选，但很有说服力）

```bash
"$JAVA_HOME/bin/java" -Doryxos.memory.backend=sqlite -jar "$JAR" chat --profile memory-demo \
  --message "我的工位编号是多少？"
```

**预期**：**答不出来**。因为 `B-3-17` 写在 `MEMORY.md` 里，而 sqlite 档根本不读那个文件。**这一条把「两档读的是不同的地方」钉死了**——比只看正向更硬。

### 收尾：切回去

```bash
"$JAVA_HOME/bin/java" -jar "$JAR" chat --profile memory-demo --message "我的工位编号是多少？"
```

**预期**：又答得出 `B-3-17`（回到默认的 markdown 档）。

**这一趟下来，你改过的只有命令行上的一个 `-D`。** 那就是判据 1 的全部内容。

---

## Step 7 — 非法后端取值必须报错，不许静默退回

这条不是「顺手测的」，是设计上刻意选的：**静默退回最坏**——运维改了配置、重启、以为生效了，实际还在用旧的。

```bash
cd /tmp/oryxos-l22
"$JAVA_HOME/bin/java" -Doryxos.memory.backend=bogus -jar "$JAR" tool list 2>&1 | grep -o '未知的长期记忆后端.*' | head -1
```

**通过**：

```text
未知的长期记忆后端 "bogus"（合法取值：markdown / sqlite / mem0）
```

启动**失败**（非 0 退出码），**没有**退回 markdown 档继续跑。

---

## Step 8 — `USER.md` 只读（判据 4 的后一半）

```bash
cd /home/wngbob/oryxos-wngbob

# ① 全仓没有任何【写】USER.md 的代码路径
grep -rn "USER.md" --include=*.java . | grep -v "/target/"
```

**预期**：**三处命中，但一处写的都没有**——这正是「只读」在代码层面的样子：

| 命中 | 是什么 |
| --- | --- |
| `oryxos-core/.../ContextLoader.java:49` | `DEFAULT_BOOTSTRAP` 里列着它——**读**（启动时注入 prompt） |
| `oryxos-cli/.../InitCommand.java:105` | 模板注释文本 |
| `oryxos-cli/.../InitCommand.java:121` | `new Template("USER.md", USER_MD)`——**首次创建** |

**判据**：没有任何一处 `Files.write` / `writeString` 指向它。（创建 ≠ 写入：`init` 只在文件不存在时建，已存在一律不覆盖。）

```bash
# ② 记忆模块不碰沙箱（仲裁 3 的连带）——只看代码行
grep -rn "Sandbox\|enforce" --include=*.java oryxos-memory/src/main/java/ | grep -v '\*'
#    预期：无命中

# ③ oryxos-memory 不依赖 oryxos-tool
grep -c "<artifactId>oryxos-tool</artifactId>" oryxos-memory/pom.xml
#    预期：0

# ④ core 一行没改
git diff --stat -- oryxos-core/
#    预期：空
```

**②③ 两条的坑（写向导时实测到的）**：直接 `grep -c "oryxos-tool" oryxos-memory/pom.xml` 会得到 **3**，`grep -rn "Sandbox" .../oryxos-memory/` 会得到 **1**——**全是注释命中，不是真调用/真依赖**。那份 pom 的注释里三处在解释「方向与 `oryxos-tool` 一致」，`MarkdownMemoryStore` 的 javadoc 里那句「IO 不过 `Sandbox.enforce`」是在**解释为什么不调**。判据必须落在 `<artifactId>` 和代码行上，否则你会追一个不存在的失败。

> ④ 是判据 1 在代码层面的对照证据：`MemorySource` 是骨架期就有的 core 接缝，本节**只实现它、不修改它**。

---

## Step 9 — Mem0 档真连一次（可选）

**只有你真有一个自托管 Mem0 服务时才做这一步。** 核心阶段这一档的测试全部是 `MockRestServiceServer`，**从未与真实 Mem0 服务对过**——所以这一步是**第一次真正校验报文契约**，价值很高，但它**不是**本节交付的前置条件。

### 9.1 先看清这一档的三个已知边界（都写在代码里）

| 边界 | 后果 |
| --- | --- |
| **不发任何认证头**（`RestClient` 只装了 `baseUrl` + 超时） | 自托管无鉴权时可用；服务端要 token 就 401 连不上 |
| **只认响应里的 `results` 字段**（`Mem0MemoryStore.RESULTS_FIELD`） | 字段名不对时 `load()` **静默返回空**——Agent 会以为「没有记忆」，**不报错**。这是第一个要核的 |
| 请求体是我们自己定的最小契约（`POST /memories`、`POST /search`） | 端点路径或 `filters` 形状不同就直接失败 |

### 9.2 跑

```bash
cd /tmp/oryxos-l22
read -rsp 'MEM0_BASE_URL: ' MEM0_BASE_URL && export MEM0_BASE_URL && echo
export MEM0_USER_ID=oryxos

"$JAVA_HOME/bin/java" -Doryxos.memory.backend=mem0 -jar "$JAR" chat --profile memory-demo \
  --message "记住：我的实验编号是 EXP-42。请调用 save_memory。"
```

**预期**：Agent 正常回答。**注意 `MEMORY.md` 此刻不会再变**——记忆在 Mem0 那边。

### 9.3 判据 —— 绕开 OryxOS，直接问 Mem0

```bash
curl -s "$MEM0_BASE_URL/memories?user_id=$MEM0_USER_ID" | python3 -m json.tool | head -40
```

**第一个要核的**：响应里有没有一个**顶层 `results` 数组**，元素里有没有 **`memory`** 和 **`metadata.scope`** 这两个字段名。**字段名对不上，`load()` 就静默返回空**。

### 9.4 判据 —— 回到 OryxOS，看它读不读得回来

```bash
"$JAVA_HOME/bin/java" -Doryxos.memory.backend=mem0 -jar "$JAR" chat --profile memory-demo \
  --message "我的实验编号是什么？"
```

**通过**：答出 `EXP-42`。

**答不出来但 9.3 里 Mem0 确实存了**：就是字段名或端点形状对不上。**把 9.3 的原始 JSON 存下来**——那是本节报文契约的第一份真实样本，比任何推测都有用。

### 9.5 顺手确认凭证不外泄

```bash
"$JAVA_HOME/bin/java" -Doryxos.memory.backend=mem0 -jar "$JAR" tool list 2>&1 | grep -c "$MEM0_BASE_URL" || echo "0（预期：base-url 含凭证，任何输出里都不该出现）"
```

---

## 你负责什么

机器能判的已经判完了（451 条测试）。**你要判的是机器判不了的那几件**：

1. **模型的意图**——它到底有没有主动去调 `save_memory`。审计表告诉你事实，但「这个说法能不能让模型想起来要记」是体感问题，只有你能判。
2. **「同一个体感」这句话**——Step 6 里换后端前后，对话的感觉是不是真的一样。这是判据 1 的定性部分，测试断言不出来。
3. **可猜 / 不可猜**——Step 5 用的事实必须是模型猜不出来的。换成「我偏好 Java」这条判据当场失效，而它看起来仍然「通过了」。
4. **报文契约**——只有 Step 9 能给出真实 Mem0 的响应样本。**该步经裁决不做**，故 Mem0 档的报文契约**永远不会**由本向导校验；它作为**已知边界**记录在 [acceptance-report.md](acceptance-report.md) §6.4 第 3 项。别把 `Mem0MemoryStore` 当成已验证过的档。
5. **如实记录**——哪一步没通过就写没通过。**尤其别把「Agent 说记住了」写成「`save_memory` 已验证」。**

## 最容易踩的八个坑

| # | 坑 | 症状 | 怎么办 |
| --- | --- | --- | --- |
| 1 | **`tools: []` 没改** | Agent 满口答应「我记住了」，审计表一条都没有 | 回 Step 2，`sed` 那一行。**这是最高频的翻车点** |
| 2 | **把「Agent 说记住了」当证据** | 看起来全过了，其实一次工具调用都没发生 | 判据只有两个：审计表那行 + 文件那行 |
| 3 | **以为反复 `chat` 会开新会话** | Step 5 的判据看起来过了，其实是同一个会话 | `session_id` 由 `channel`+`user`+`profile` 拼出。换 profile 名（Step 4）或删库（Step 5） |
| 4 | **拿可猜的事实做 Step 5** | 模型猜对了，看起来「记忆生效了」 | 用 `ORYX-7742` 这种编出来的编号 |
| 5 | **`-D` 写在 `-jar` 后面** | 配置不生效，`java` 把它当成传给程序的参数 | `java -Doryxos.memory.backend=sqlite -jar ...` |
| 6 | **删了库却忘了 `init`** | `no such table: sessions` | 删库后必跑 `oryxos init`（幂等） |
| 7 | **在 SQLite 档做 Step 5 的删库** | 记忆跟着库一起没了，误判成「记忆没生效」 | 删库那一步只在 markdown 档做 |
| 8 | **用 Spring Boot 惯用的 `--oryxos.memory.backend=sqlite`** | `Unknown option`，退出码 **2**，命令根本没跑起来 | 用 `-D`（坑 5）。`--key=value` 是 Spring Boot 的 `SimpleCommandLinePropertySource` 形式，但这里 **Picocli 先吃掉命令行**，它不认这个选项、直接拒。**已实测**。另外环境变量 `ORYXOS_MEMORY_BACKEND=sqlite` 也生效（Spring Boot 的 relaxed binding），但那是**框架内建行为、不是本仓契约**，别写进脚本 |

## 附录 A — 仍未做的（如实记）

- **Step 9（Mem0 档真连）经用户裁决不做（2026-10-16）**——不是遗漏、不是待办、也不是「没时间跑」。**Mem0 档与真实服务的报文契约从未对过**：契约是我们自己定的最小报文（`/memories`、`/search`、`results`），单测只证明「请求发得出去、body 形状对、响应按我们的假设解析得对」，不证明「真实 Mem0 会这样回」。**这一档从「待验证」转为永久记录的已知边界。**
- **步骤级细节未留痕**：用户 2026-10-16 跑完 Step 0~8 后只给了整体结论（都通过），未逐步说明各步输出（例如 Step 6 的反向确认做没做）。故本向导**不声称**任何步骤级的具体观察结果。
- **Lark 档未验收**（第 19 节遗留，与本节无关，列在这里只是提醒别把它算进本节）。

## 参考来源

- 契约细节：[contracts/](./contracts/)（`memory-config.md` §1 是三个配置键的契约，**§1.1 是切档方式与它的实测边界**）
- 数据形态：[data-model.md](./data-model.md)
- 可复制的验证命令：[quickstart.md](./quickstart.md)
- 本节验收结论与证据：[acceptance-report.md](./acceptance-report.md)（§6.4 是四项人工项的清单与状态，§6.5 是 2026-10-16 的人工验收记录）
- 写作体例参照：[`specs/004-notify-outbound/manual-test-guide.md`](../004-notify-outbound/manual-test-guide.md)
