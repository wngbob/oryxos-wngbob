# 验证向导：Sandbox 白名单

**Feature**: `specs/007-sandbox-whitelist` | **Branch**: `class-24` | **Date**: 2026-10-17

本文件是**可跑的验证向导**：四节对应 spec 的四条 user story，每节给出「怎么跑 / 期望看到什么 / 跑不动怎么查」。实现细节在 [data-model.md](./data-model.md) 与 [contracts/sandbox-whitelist.md](./contracts/sandbox-whitelist.md)，本文件不重复。

**环境前置**（WSL2）：

```bash
export JAVA_HOME=/d/data/work/tools/jdk-21.0.12.1+1     # 便携版 JDK 21，未入 PATH
cd /home/wngbob/oryxos-wngbob
```

> 单模块命令一律带 `-am`（否则会静默取 `~/.m2` 里的旧字节码）。

---

## Step 0：本节的可自动化判据一次跑完

```bash
./mvnw -q -pl oryxos-tool -am test          # WhitelistSandboxTest + SandboxToolWiringTest
./mvnw -q -pl oryxos-cli  -am test          # ToolAssemblyTest（起真上下文）
```

**期望**：两条都 BUILD SUCCESS，且 `target/surefire-reports/` 里 `WhitelistSandboxTest` 与 `SandboxToolWiringTest` 的 `tests=` 都 > 0。

全量门禁（含 P3C / Checkstyle / SpotBugs+FindSecBugs / Spotless / OWASP）：

```bash
./mvnw clean verify                          # 期望 BUILD SUCCESS
```

---

## Step 1（US1）：白名单外的路径读不到

**意图**：`file.allowed_paths` 收紧到工作目录后，`read_file /etc/passwd` 在**真正读文件之前**被拦。

```bash
./mvnw -q -pl oryxos-tool -am test -Dtest='WhitelistSandboxTest'
```

**期望看到**：

- 白名单 `/workspace` + 目标 `/etc/passwd` → 抛 `SandboxViolationException`，消息形如 `路径不在白名单内: /etc/passwd`
- 白名单 `/workspace` + 目标 `/workspace/notes.txt` → 不抛
- 白名单 `/workspace` + 目标 `/workspace/../../outside/secret.txt` → 抛（`normalize()` 后不在白名单内）
- 白名单 `/workspace` + 目标 `/workspace-evil/x` → 抛（前缀必须落在**路径分隔符**边界上）

**接线回归**（真沙箱 + 真 `FileTools`）：`SandboxToolWiringTest` 里那条 `write_file` 用例断言**文件根本没被创建**——不是「抛了异常」（先写再校验同样会抛）。

**跑不动怎么查**：若「默认配置下全拒」，先看 `application.yaml` 的 `file.allowed_paths` 是不是空——空 = 全拒是**设计语义**（见 Step 4），不是 bug。

---

## Step 2（US2）：白名单外的命令一次都没被执行

**意图**：白名单只允许 `ls` 时，`rm -rf /` 在 `ProcessBuilder` 起来之前就被拦，**进程根本没起**。

```bash
./mvnw -q -pl oryxos-tool -am test -Dtest='WhitelistSandboxTest,SandboxToolWiringTest'
```

**期望看到**：

| 输入 | 白名单 | 期望 |
| --- | --- | --- |
| `ls -la` | `ls` | 放行（只比首 token） |
| `"  ls -la"` | `ls` | 放行（`trim()` 先跑，前导空白不是绕过手段） |
| `rm -rf /` | `ls` | 抛，消息形如 `命令不在白名单内: rm` |
| `LS` | `ls` | 抛（精确比对，**不**折叠大小写——那是文件名） |
| `"   "` | 任意 | 抛（切不出 token 即拒，不能因「拿不到首 token」放行） |

**接线回归**：`SandboxToolWiringTest` 那条用例跑一条会创建标记文件的命令（`touch <marker>`），白名单里没有 `touch`，断言 **`Files.notExists(marker)`** —— 这是「进程真没起」的可判定形态。

---

## Step 3（US3）：形似域名被拦住

**意图**：白名单 `*.example.com` 时，`https://evil-example.com/x` 必须被拒（它以 `example.com` 结尾但不是子域）。

```bash
./mvnw -q -pl oryxos-tool -am test -Dtest='WhitelistSandboxTest'
```

**期望看到**：

| URL | 白名单 | 期望 |
| --- | --- | --- |
| `https://api.example.com/x` | `*.example.com` | 放行 |
| `https://evil-example.com/x` | `*.example.com` | **抛** |
| `https://example.com/x` | `*.example.com` | 抛（通配符只覆盖子域） |
| `https://API.Example.COM/x` | `api.example.com` | 放行（host 比对不区分大小写） |
| `file:/etc/passwd`（无 host） | 任意 | 抛（不是 NPE） |

**接线回归**：`SandboxToolWiringTest` 用本地假 HTTP 端点（`mockwebserver3`）断言白名单外域名被拦时 **请求计数为 0**。

---

## Step 4（US4）：空配置 = 全拒

**意图**：三块白名单都为空时，四类动作全拒——「忘了配」的后果是「什么都跑不了」（可见、可查），不是「什么都放行」（静默、危险）。

```bash
./mvnw -q -pl oryxos-tool -am test -Dtest='WhitelistSandboxTest'
```

**期望看到**：三个空表构造出的沙箱，对任意路径 / 命令 / 域名（含看起来人畜无害的）一律抛 `SandboxViolationException`。

**这条口径 MUST 能从配置里读到**（不读源码就知道）：

```bash
grep -n "全拒\|劝阻级" oryxos-boot/src/main/resources/application.yaml
```

**期望**：`file` / `shell` / `http` 三块附近各能看到「留空 = 全拒」与「劝阻级防线，防不住蓄意绕过」两条注释。

---

## Step 5：装配层（容器里恰好一个沙箱 Bean）

```bash
./mvnw -q -pl oryxos-cli -am test -Dtest='ToolAssemblyTest'
```

**期望看到**：

- `Sandbox` 类型的 Bean **恰好一个**且是 `WhitelistSandbox`（不是「存在一个是」——后者在「两个都在」时假绿，而「两个都在」只在重命令起上下文时才炸成 `NoUniqueBeanDefinitionException`）
- 容器里已有别的 `Sandbox` Bean 时，白名单实现**让位**（覆盖接缝可复现）
- 三个配置键字面量（`file.allowed_paths[0]` 等）真的绑得上：白名单内的目标放行、白名单外的拒绝

**改键名即变红**——这条用例就是 §3.2 那三个字面量的守卫。

---

## Step 6：零改动面自查（SC-004）

```bash
git diff --stat -- \
  oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/sandbox/Sandbox.java \
  oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/sandbox/SandboxAction.java \
  oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/sandbox/ActionType.java \
  oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/sandbox/SandboxViolationException.java \
  oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/builtin/FileTools.java \
  oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/builtin/ShellTools.java \
  oryxos-core/src/main/java/io/oryxlabs/oryxos/core/engine/ToolExecutor.java
```

**期望**：**空输出**（骨架期四个契约文件 + 五个工具 + `ToolExecutor` 一行未动）。

```bash
# 五个工具的 enforce 首行仍是前序节交付的那一行（NotifyTools 第 19 节，其余四个第 20 节）
grep -c "sandbox.enforce" \
  oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/builtin/FileTools.java \
  oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/builtin/ShellTools.java \
  oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/builtin/HttpTools.java \
  oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/notify/NotifyTools.java \
  oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/search/WebSearchTools.java
```

---

## Step 7（人工项）：真模型真链路上的越界被拦

**harness 判不了这一段**（要真模型 + 真 fat JAR）。步骤：

```bash
# ① 把白名单收紧到只允许 ls（改 oryxos-boot/src/main/resources/application.yaml 的 shell 块）
#    shell:
#      allowed_commands: [ls]

# ② 打包 + 初始化工作区
./mvnw clean package -DskipTests
mkdir -p /tmp/oryxos-sandbox-check && cd /tmp/oryxos-sandbox-check
export DEEPSEEK_API_KEY=<你的 key>
java -jar /home/wngbob/oryxos-wngbob/oryxos-boot/target/oryxos.jar init

# ③ 真跑一次：让模型执行一条白名单外的命令
java -jar /home/wngbob/oryxos-wngbob/oryxos-boot/target/oryxos.jar chat \
  --profile <你的 agent 名>
# 在对话里输入：请用 shell 工具执行：rm -rf /tmp/oryxos-sandbox-check/whatever

# ④ 查审计
sqlite3 .oryxos/oryxos.db \
  "select tool_name, success, error_message from tool_invocations order by id desc limit 5;"
```

**通过判据（三条都要）**：

1. 链路上抛了 `SandboxViolationException`（对话里能看到工具失败）
2. `tool_invocations` 有 `success = 0` 的记录
3. `error_message` 是**人能读懂**的那句（形如 `命令不在白名单内: rm`），不是 NPE、不是英文栈信息

**回滚**：验完把 `shell.allowed_commands` 改回默认清单（见 `contracts/sandbox-whitelist.md` §3.3）。

---

## Step 8（人工项）：接口中立性自查（思维练习，不产码）

问自己一句：**把 `WhitelistSandbox` 换成 `KataMicroVmSandbox`（容器 / microVM 档），需要给 `Sandbox` 接口加方法吗？**

**通过判据**：不需要。调用方只调 `enforce(SandboxAction)`，实现内部是查表还是起容器对调用方不可见；五个工具的 `enforce` 首行一行都不用改。

**反例信号**：如果发现「得加一个 `getViolationReason()`」或「得让调用方传白名单进来」，说明接口被第一档实现带偏了。

---

## 排障对照表

| 现象 | 最可能的原因 | 怎么办 |
| --- | --- | --- |
| 默认配置下**什么工具都被拒** | 白名单为空（或键名拼错） | 这是**设计语义**（空 = 全拒）。先看 `application.yaml` 的三块是否有内容；键名必须逐字是 `file.allowed_paths` / `shell.allowed_commands` / `http.allowed_domains` |
| 配了 `allowed_paths: ["."]` 但工作目录下的文件仍被拒 | 白名单侧没转绝对路径 | 不应发生（D4① 已处理）。若复现，查 `WhitelistSandbox` 构造器里 `toAbsolutePath()` 那一步是否被改掉 |
| `read_file` 抛的是 `NullPointerException` 而不是白名单消息 | URL/host 为 null 那条路径没接住 | 不应发生（D4② 已处理）。查 `checkHttpUrl` 的 null 判断 |
| 容器起不来，报 `NoUniqueBeanDefinitionException` | `Sandbox` 类型有**两个** Bean | 只在重命令起上下文时才炸（`--help` 扫不出来）。查是否有第二个 `Sandbox` Bean 被声明 |
| 日志里再也看不到沙箱那条 WARN | `PermissiveSandbox` 已按裁决 3 整类删除 | **这是预期的**：窗口期已关闭，白名单真在校验了。`LogStreamSeparationTest` 守的是「dev 档根级别不把 WARN 压掉」这个通用日志契约，仍然有效 |
| `mvn test` 报 `Unsupported class file major version 65` | P3C 的 `asm.version` 覆盖被清掉了 | 见 `CLAUDE.md`「工程地基」——那三处配置看着多余但删了会静默失效 |
