# Quickstart：Tool 体系 —— 可执行验证指南

**Feature**: `specs/005-tool-registry` | **Branch**: `class-20` | **Date**: 2026-10-14

本文件是**验证指南**，不是实现指南。按 §1→§5 顺序执行即可判定本节是否达标。契约细节见 [contracts/](contracts/)，实体关系见 [data-model.md](data-model.md)。

---

## §0 前置

```bash
export JAVA_HOME=/d/data/work/tools/jdk-21.0.12.1+1     # WSL 下按实际路径调整
cd /home/wngbob/oryxos-wngbob
git branch --show-current                                # 期望：class-20
```

**注意**：单模块 Maven 命令**一律带 `-am`**（否则静默取 `~/.m2` 里的旧字节码，改了代码却测的是老版本）。

---

## §1 门禁全绿（硬门禁 ①）

```bash
./mvnw -s /d/data/work/tools/maven-settings-aliyun.xml clean verify
```

**期望**：`BUILD SUCCESS`，且以下静态检查全部参与并通过——Spotless / 阿里 P3C（PMD 6.55）/ Checkstyle / SpotBugs + FindSecBugs / OWASP Dependency-Check。

**预期会遇到的**：新写的 Java 代码在 Spotless（google-java-format）下多半不合格式。先跑：

```bash
./mvnw -s /d/data/work/tools/maven-settings-aliyun.xml spotless:apply
```

**判据**：`verify` 全绿。**未全绿不得宣称完成**（反作弊纪律）。

---

## §2 只跑本节测试（快速迭代）

```bash
./mvnw -s /d/data/work/tools/maven-settings-aliyun.xml -pl oryxos-tool -am test
./mvnw -s /d/data/work/tools/maven-settings-aliyun.xml -pl oryxos-cli  -am test
```

**期望**：本节新增的测试类全部存在且非空——

| 测试类 | 模块 | 守点 |
| --- | --- | --- |
| `OryxToolContractTest` | **oryxos-cli**（见下方说明） | 参数化遍历全量工具的契约三件套（SC-001） |
| `ToolRegistryTest` | oryxos-tool | 三源注册 / 精确过滤 / 重名 / 未注册名（FR-005 / FR-022 / FR-023） |
| `FileToolsTest` / `ShellToolsTest` / `HttpToolsTest` | oryxos-tool | 每工具"跑通 + 越界被拦 + 校验先于 IO"（FR-015 / SC-003） |
| `AnnotatedToolAdapterTest` | oryxos-tool | schema 参数名非 `arg0`（D2a）/ `ToolResult` 语义保真 / 异常上抛 |
| `McpToolAdapterTest` | oryxos-tool | 包装注册 / 参数原样转发 / schema 是合法 JSON / `isError=null` 不 NPE |
| `McpClientServiceTest` | oryxos-tool | **某个 server 失联不拖垮启动**（SC-004） |
| `PermissiveSandboxTest` | oryxos-tool | 构造时 WARN 恰好一条（SC-010） |
| 扩展五工具单测 | oryxos-tool | 见 [builtin-tools.md](builtin-tools.md) §3.4 |
| `ToolAssemblyTest` | oryxos-cli | `ToolCatalog` Bean 恰好一个且是 `ToolRegistry`（D4）；临时沙箱恰好一个；第 24 节真实现一挂上就让位（SC-009） |

**`OryxToolContractTest` 为什么在 `oryxos-cli` 而不是 `oryxos-tool`**（实施期偏离，已记入验收报告）：它遍历的是
**装配后的真实注册表**（[tool-abstraction.md](contracts/tool-abstraction.md) §3.1），而注册表由
`CliAssemblyConfiguration` 装配，该类在 `oryxos-cli`；依赖方向是 `cli → tool`，`oryxos-tool` 看不到它。
放进 `oryxos-tool` 就只能手工 `new` 一个注册表，那等于把「装配」这件事绕过去——**测的就不是装配后的那一份了**。

连带两处改动（同样记入验收报告）：`oryxos-cli/pom.xml` 新增 `spring-boot-starter-test`（test scope，该模块此前
无测试树）；数据源参数在测试里以 `SpringApplicationBuilder.properties(...)` 直接给，**不新增第二份
`application.yaml`**（本模块 pom 明令禁止）。

---

## §3 关键回归单测（逐个对号）

课件「验收 harness」里**写出代码**的关键回归测试，本节**原样落地**（断言逻辑逐条保真，方法名译成英文，课件原文进 `@DisplayName`）：

```bash
# ① 参数化契约检查（最值钱的一条：新工具自动纳入）
./mvnw -pl oryxos-cli -am test -Dtest=OryxToolContractTest

# ② MCP 失联隔离
./mvnw -pl oryxos-tool -am test -Dtest=McpClientServiceTest

# ③ schema 参数名（守父 pom 的 -parameters）
./mvnw -pl oryxos-tool -am test -Dtest=AnnotatedToolAdapterTest

# ④ 装配让位（守 @Bean 声明顺序）
./mvnw -pl oryxos-cli -am test -Dtest=ToolAssemblyTest
```

**每个都期望绿**。任一条红 → 停下修实现，**不得删断言 / `@Disabled` / 放宽阈值**。

---

## §4 H4 六条全局不变量自查

```bash
# ① 涉外 IO 首行过 Sandbox.enforce
grep -rn "enforce(new SandboxAction" oryxos-tool/src/main/java/ | wc -l
# 期望：每个涉外工具的每个 @Tool 方法各一条；逐个 grep 确认它在方法体第一行

# ② 审计落库：本节不新增第二条写入路径
grep -rn "ToolInvocationRepository\|tool_invocations" oryxos-tool/src/main/java/ oryxos-core/src/main/java/
# 期望：只在 ToolExecutor 命中（oryxos-core）；oryxos-tool 里 0 命中

# ③ 无明文 key
grep -rniE "(api[_-]?key|token|secret|password)\s*[:=]\s*[\"'][A-Za-z0-9_-]{16,}" \
  oryxos-tool/src oryxos-cli/src specs/005-tool-registry/ 2>/dev/null
# 期望：0 命中

# ④ session_id 只在 SessionManager 内拼接
grep -rn "session_id\|sessionId" oryxos-tool/src/main/java/ | wc -l
# 期望：0（本节不碰会话）

# ⑤ 无 Reactor / CompletableFuture / 自建线程池
grep -rnE "reactor\.|CompletableFuture|Executors\.|new Thread\(" \
  oryxos-tool/src/main/java/ oryxos-cli/src/main/java/
# 期望：0 命中（MCP SDK 内部的响应式不算——扫描只覆盖本仓源码）

# ⑥ 无 Spring AI 自动工具执行路径
grep -rn "internalToolExecutionEnabled" oryxos-provider/src/main/java/
# 期望：命中且值为 false（ProviderService 第 125 行附近）；本节一行不改
grep -rn "MethodToolCallback\|ToolCallbacks\.\|toolCallbacks(" oryxos-tool/src/main/java/
# 期望：0 命中（AnnotatedToolAdapter 自己做调用，不用 MethodToolCallback）
```

---

## §5 端到端实跑（**本节首次真正可演示**）

> 与第 19 节相反：第 19 节"工具进不了容器"，本节工具真的进得去。

### §5.1 打包

```bash
./mvnw -s /d/data/work/tools/maven-settings-aliyun.xml clean package -DskipTests
ls -lh oryxos-boot/target/oryxos.jar
java -jar oryxos-boot/target/oryxos.jar --version
```

### §5.2 起工作区 + 看工具清单

```bash
cd /tmp && rm -rf oryxos-demo && mkdir oryxos-demo && cd oryxos-demo
java -jar /home/wngbob/oryxos-wngbob/oryxos-boot/target/oryxos.jar init

# 重命令：会起 Spring 上下文，首次会慢几秒
java -jar /home/wngbob/oryxos-wngbob/oryxos-boot/target/oryxos.jar tool list
```

**期望**：

1. 输出 **13 个**工具（见 [builtin-tools.md](builtin-tools.md) §1 的清单）；
2. 按来源分组：`builtin (13)` / `annotation (0)` / `mcp (0)`；
3. `mcp` 组注明"没有声明任何 server"——**这是预期状态**，不是没做完（模板末尾是 `servers: []`）；
4. **启动日志里出现一条 `PermissiveSandbox` 的 WARN**（SC-010）——这条 WARN 是**有意留的**，看到它说明临时沙箱的缓解措施 2 生效。

### §5.3 起一个 Agent 并让模型真的调工具

在 `.oryxos/agents/` 下建一个目录（示例用 `file-explorer`），`AGENT.md`：

```markdown
---
name: file-explorer
description: 一个会用工具查文件的 Agent
provider:
  name: deepseek
  model: deepseek-chat
tools:
  - list_dir
  - read_file
---

你是文件助手。用户让你看文件时，先 list_dir 看目录，再 read_file 读内容，最后用中文总结。
```

跑：

```bash
export DEEPSEEK_API_KEY=...            # 环境变量注入，不写进任何文件
java -jar /home/wngbob/oryxos-wngbob/oryxos-boot/target/oryxos.jar chat --profile file-explorer
```

对话里输入 `看看当前目录有什么`。

**期望**：

1. 模型**点名** `list_dir`（说明 schema 里的工具名与描述真的被 Provider 传给了模型）；
2. `list_dir` **真的被执行**（说明 `ToolExecutor` 的 `find(name)` 在注册表里命中了）；
3. 结果**被回填给模型**，模型基于真实目录内容作答（说明 ReAct 的 "Act → Observe" 闭合）；
4. 用 SQLite 核对审计：

```bash
sqlite3 .oryxos/oryxos.db \
  "select tool_name, success, duration_ms from tool_invocations order by rowid desc limit 5;"
```

**期望**：有 `list_dir` 的记录，`success = 1`，`duration_ms` 非 0。

### §5.4 演示"加工具成本恒定"（FR-022）

写一个 `@Tool` Java Bean（或临时用 `InteractionTools` 之外的一个小类），在装配处加一行 `registerAnnotated(bean, "annotation")`，重启后：

```bash
java -jar ... tool list | grep -A2 "annotation"
```

**期望**：新工具出现在 `annotation` 组里，且**`FileTools` / 注册表 / 过滤逻辑 / 审计路径一行都没改**。

### §5.5 演示"越界会被拦"（**本节会看到它没被拦**）

```bash
java -jar ... chat --profile file-explorer
# 输入：读一下 /etc/passwd
```

**期望（本节的真实状态）**：`read_file` **会成功返回 `/etc/passwd` 的内容**——因为沙箱挂的是 `PermissiveSandbox`，白名单校验**不生效**。

**这不是 bug，是已报批的取舍**（[research.md](research.md) D1）。第 24 节换上 `WhitelistSandbox` 后，同一条输入会被 `SandboxViolationException` 拦下，且 `tool_invocations` 里落一条失败记录。**本节的验收 MUST NOT 把这条演示当成"沙箱已就位"。**

---

## §6 剩余人工项（harness 判不了，需人工过）

| # | 项 | 判定 |
| --- | --- | --- |
| 1 | 真实 LLM 下模型能正确构造工具调用（§5.3） | 需要真实 API key |
| 2 | 真实 MCP server 能连上并列出工具 | 需要一个真实 server（如 `npx @modelcontextprotocol/server-github`）+ 网络 |
| 3 | 真实搜索服务的可用性与返回质量（§2.10） | 需要外网 |
| 4 | `ask_user` 在真实终端的交互体验 | 需要人工在终端敲键盘 |
| 5 | **沙箱窗口期**：确认团队知晓第 20~23 节无 Tool 治理 | 人工确认，双处留痕（`CLAUDE.md` + 验收报告） |

---

## §7 本节结束时应该**看不到**的东西

| 看不到 | 为什么 | 归哪节 |
| --- | --- | --- |
| `WhitelistSandbox` | 白名单校验本体 | 第 24 节 |
| `save_memory` / `recall_memory` | Memory 内置工具 | 第 22 节 |
| `/api/v1/tools` 端点 | Web 侧工具清单 | 第 26 节 |
| `mcp_servers.yaml` 里有真实 server 声明 | 模板冻结，示例全在注释里 | 用户自行配置 |
| `tool_invocations` 表结构变化 | 本节只写不建 | — |
| 任何 `reactor` import | FR-026 | — |
