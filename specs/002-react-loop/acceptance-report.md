# 第 17 节验收报告：ReAct 循环 —— Agent 的「想—做—看」主循环

**Feature**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md) | **Tasks**: [tasks.md](tasks.md) | **Quickstart**: [quickstart.md](quickstart.md)
**Branch**: `main`（当时）| **完成 commit**: `38d7acd`（2026-10-13）| **任务完成度**: 28 / 28
**结论**: ✅ 节级 DoD 六项满足；harness 已判卷，剩余人工项见 §7

> ⚠️ **本文件是回填报告（2026-10-14 补写）**。第 17 节完成时尚未要求落盘验收报告；本报告按 [`.claude/skills/oryxos-lesson-dev`](../../.claude/skills/oryxos-lesson-dev/SKILL.md) 第 7 步的固定结构重建。**证据强度声明见 §0**——本节被第 18 节改动得比第 16 节更多（含 3 个测试类），务必先读 §0。

---

## 0. 回填方法与证据强度

| 证据类型 | 说明 |
|---|---|
| **实测（今天重跑，强）** | 门禁、测试条数、H4 grep、存在性核对——在 `HEAD` 上重跑得出 |
| **据 commit / 文档重建（弱）** | 任务完成度、当时的门禁结论、harness 对号——来自 `38d7acd` 的 commit 内容与 `tasks.md` 勾选状态 |

**等价性边界**：本节被第 18 节改动得比第 16 节多——**5 个测试类中有 3 个被第 18 节动过**。逐文件核实（`git diff 38d7acd..HEAD -- <path>`）如下：

| 本节文件 | 此后由谁改动 | 改了什么 |
|---|---|---|
| `core/session/Session.java` | 第 18 节（+59 行） | 加 `channel`/`userId` 三参工厂（`Session.create(id, ch, user, profile)`），承载会话身份 |
| `core/session/SessionService.java` | 第 18 节（+31 行） | 持久化接缝扩展（`getOrCreate` 等） |
| `core/engine/AgentServiceTest.java` | 第 18 节（**4 行**） | ★ 见下 |
| `core/engine/PromptBuilderTest.java` | 第 18 节（**14 行**） | ★ 见下 |
| `core/engine/ReActLoopTest.java` | 第 18 节（**2 行**） | ★ 见下 |
| `contracts/session-model.md` | 第 18 节（+3 / −1 行） | 会话标识口径补全 |
| `ReActLoop` / `PromptBuilder` / `ToolExecutor` / `AgentService` / `ProfileContext` / `ContextLoader` | **未被后续节改动** | ✅ 被测实现是当时的 |
| `ToolExecutorTest` / `ContextLoaderTest` | **未被后续节改动** | ✅ 原样 |

**★ 三处测试类改动的性质（已逐行看过 diff，这是本报告最需要交代的一点）**：

第 18 节对这三个测试类的改动**全部是同一个机械替换**——`Session.create(id, profile)` → `Session.create(id, channel, user, profile)`：

```diff
-    session = Session.create(SESSION_ID, "demo");
+    session = Session.create(SESSION_ID, "cli", "alice", "demo");
```

**没有改过任何一条断言、没有删过任何一个 `@Test`、没有改过任何 `@DisplayName`、没有放宽任何阈值。** 改动量（4 / 14 / 2 行）恰好等于各文件里 `Session.create` 的调用点数。因此：

- 这 3 个测试类的**验收语义**仍是第 17 节的原样，今天全绿对第 17 节构成有效证据；
- 但**文件字节**已非当时快照——故本报告在 §2 引用的是**今天从代码里读出的**方法名与 `@DisplayName`，不是从当时快照推断的。

---

## 1. 证据一：全量门禁 `mvn clean verify` 全绿

```text
[INFO] BUILD SUCCESS
```

静态检查逐模块执行并全过：`spotless:check` / `pmd:check`（阿里 P3C）/ `checkstyle:check` / `spotbugs:check`（含 FindSecBugs），根模块聚合跑 `dependency-check:aggregate`（`failBuildOnCVSS=7`）。

本节是第一个需要**跨模块测试替身**的节（core 的测试要替身 `LlmClient` / `ToolExecutor` / `MemorySource`），门禁全过说明替身注入未触发 SpotBugs 的不可序列化之类告警。

---

## 2. 证据二：课件 harness 映射表逐类对号

课件 harness 共 **5 个单测类、29 条**，全部落地且非空：

| 课件测试类 | 落地 | 条数 | 覆盖的验收点（`@DisplayName` 原文，今天从代码读出） |
|---|---|---|---|
| `ReActLoopTest` | ✅ | 7 | 一轮能收尾：模型恰好被调 1 次，工具 0 次 / 多轮能接上：工具结果出现在下一轮发出去的上下文里 / **★ 模型一直要调工具：转满最大轮数强制停** / 上限可由 Agent 配置覆盖：配 3 就恰好调 3 次 / 每轮响应与工具结果都按序累积进会话 / 转满上限时最后一轮的工具照常执行 / 模型响应没有 generation：抛 IllegalStateException |
| `PromptBuilderTest` | ✅ | 8 | 四部分顺序与来源一一对应 / 长期记忆为空：整条不出现，不塞占位文本 / **system prompt 末尾附当前日期时间** / **历史超 N 轮：只保留最近 N 轮** / 截断只切在用户消息边界：历史从不以工具消息开头 / 恰好 N 轮：不截断 / **工具说明只翻译不执行：产物 `call()` 抛 `UnsupportedOperationException`** / 工具消息的 name 由同段历史的 toolCalls 反查得到 |
| `ToolExecutorTest` | ✅ | 5 | 成功：落一条 success 为真的记录 / 工具抛异常：落一条 success 为假、原因含异常文本的记录 / 工具异常不打断循环：转成失败结果返回，不上抛 / 查不到工具：同样落账，原因写明工具名 / 每次工具调用都输出结构化日志：成功与失败各一条 |
| `AgentServiceTest` | ✅ | 5 | 处理期间能读到当前 Agent，且等于本次会话所属的那个 / 正常结束：当前 Agent 已被清掉 / **★ 处理中抛异常：ProfileContext 也必须被清掉** / 处理结束：会话经持久化接缝被保存一次 / Profile 查不到：抛 IllegalStateException，消息含 Agent 名 |
| `ContextLoaderTest` | ✅ | 4 | 改文件后下一次组装立即读到新内容（无缓存）/ Profile 显式引用的文件缺失：报错并指明是哪个文件 / 默认 Bootstrap 缺失：至少 WARN，不静默跳过 / Skill 软连接悬空或越出 `skills/`：报错 |

**课件「最值钱的两个测试方法」逐个对号**（课件给出的是中文方法名，落地译成英文、原文进 `@DisplayName`）：

| 课件方法（中文名） | 落地方法名 | 断言保真度 |
|---|---|---|
| `模型一直要调工具_转满最大轮数强制停` | `ReActLoopTest#hitsMaxIterationsAndStops` | ✅ 逐条保真：模型永远返回 tool_call，断言循环在**第 10 轮**停、工具被调 10 次、循环不无限转 |
| `处理中抛异常_ProfileContext也必须被清掉` | `AgentServiceTest#clearsProfileContextEvenWhenProcessingThrows` | ✅ 逐条保真：处理中抛异常后断言 `ProfileContext` 已被清空——**靠 `finally` 而非正常路径清理**，这是最容易被写漏的一条 |

---

## 3. 证据三：「本节交付物」逐项存在性核对

今天实测（`ls` / `find`）：

| 课件交付物 | 状态 | 落地路径 |
|---|---|---|
| 代码 `ReActLoop` | ✅ | `oryxos-core/.../core/engine/ReActLoop.java` |
| 代码 `PromptBuilder` | ✅ | `oryxos-core/.../core/engine/PromptBuilder.java` |
| 代码 `ToolExecutor` | ✅ | `oryxos-core/.../core/engine/ToolExecutor.java` |
| 代码 `AgentService` | ✅ | `oryxos-core/.../core/engine/AgentService.java` |
| 代码 `ProfileContext` | ✅ | `oryxos-core/.../core/engine/ProfileContext.java` |
| 代码 `ContextLoader` | ✅ | `oryxos-core/.../core/context/ContextLoader.java` |
| 代码 `ToolInvocation` 实体 + `ToolInvocationRepository` | ✅ | `oryxos-storage/.../entity/ToolInvocation.java`、`.../repository/ToolInvocationRepository.java` |
| 代码 会话模型 `Session` / `SessionMessage` / `SessionService` | ✅ | `oryxos-core/.../core/session/`（**本节 commit 创建**，第 18 节在其上扩持久化）——见 [contracts/session-model.md](contracts/session-model.md) |
| 测试 5 个单测类（29 条） | ✅ | 见 §2 |
| 表 `tool_invocations` | ✅ | `oryxos-storage/src/main/resources/db/schema.sql` |
| 约定 最大轮数默认 10 | ✅ | `ReActLoop`（`hitsMaxIterationsAndStops` 断言第 10 轮停）+ `Profile.settings.maxIterations` 可覆盖（`maxIterationsConfigurablePerProfile`） |
| 约定 历史截断默认 20 轮 | ✅ | `PromptBuilderTest#truncatesHistoryBeyondMaxTurns` 一族 |
| 约定 prompt 末尾附当前日期时间 | ✅ | `PromptBuilderTest#appendsCurrentDateTimeAtEndOfSystemPrompt` |

**一处如实披露**：课件点名的 storage 侧测试类 `ToolInvocationRepositoryTest` **不存在**——`oryxos-storage/src/test/.../repository/` 今天只有 `LlmCallRepositoryTest` / `NotifyChannelRepositoryTest` / `SessionRepositoryTest` 三个。`tool_invocations` 的建表与读写由 `ToolExecutorTest` 经替身覆盖，**没有**独立的建表脚本级测试。这属于本节与课件的一处偏差，记此备案（第 19 节写 `NotifyChannelRepositoryTest` 时补的是 `notify_channels`，未回头补它）。

---

## 4. 证据四：前序节全部测试回归绿

以当前 `HEAD` 重跑全量：**87 条测试全绿**。其中本节 29 条、第 16 节 14 条、第 18 节 15 条、第 19 节 29 条。

本节与第 16 节的跨节契约（`LlmClient` 接缝）由 `ReActLoopTest` 用替身 `LlmClient` 直接验证——**ReActLoop 不直接依赖 `ProviderService`**，这正是第 16 节留白、第 18 节接线的那条边。

---

## 5. 证据五：H4 六条全局不变量逐条自查

| # | 不变量 | 结论 | 证据（今天在 `HEAD` 上 grep） |
|---|---|---|---|
| ① | 涉外 IO 首行过 `Sandbox.enforce` | **N/A** | 本节不产生涉外 IO；工具真正执行归第 20 节，`ToolExecutor` 只做调度与审计 |
| ② | 工具执行成败都落 `tool_invocations` | ✅ | **本节的核心不变量**：`ToolExecutor` 成功路径落 `success=true`、失败路径落 `success=false` + 原因，且 catch `RuntimeException` **转成失败结果返回、不上抛**（否则循环会被工具异常打断）。由 `ToolExecutorTest` 5 条钉死 |
| ③ | 无明文 key | ✅ | 本节不引入任何凭证 |
| ④ | `session_id` 只在 `SessionManager` 内拼接 | ✅ | 本节引入会话模型，但**尚未引入拼接**——`SessionId.of(channel, userId, profileName)` 是第 18 节落地的（公式 `channel + ":" + userId + ":" + profileName`），本节只消费 `Session` 对象 |
| ⑤ | 无 Reactor / `CompletableFuture` / 自建线程池 | ✅ | `ReActLoop` 是纯 `while` 同步循环；grep `Reactor` / `CompletableFuture` / `ExecutorService` 在 `core/engine` 下 0 命中 |
| ⑥ | 无 Spring AI 自动工具执行路径 | ✅ | 本节**完全自实现** ReAct 循环（章程原则 3）；`ReActLoop` 不引用任何 Spring AI Agent 抽象。工具 schema 由第 16 节 `ToolSchemaAdapter` 提供，`PromptBuilder` 只把它翻成提示词的一部分（`toolCallbackThrowsOnCall` 断言产物不可执行） |
| ④ 补充 | `ProfileContext` 必须被清 | ✅ | `AgentService.java:59` `ProfileContext.set(profile)` → `:64` `} finally {` → `:66` `ProfileContext.clear()`。**清理在 `finally` 里**，由 `clearsProfileContextEvenWhenProcessingThrows` 钉死 |

---

## 6. 证据六：变更总结

### 6.1 改动点（据 `38d7acd` 的 commit 内容）

**新增 —— `oryxos-core` · `engine` 包**（本节主体）
- `ReActLoop.java` —— 「想—做—看」主循环；最大轮数默认 10，可被 `Profile.settings.maxIterations` 覆盖；转满强制停。
- `PromptBuilder.java` —— 四部分组装（system prompt / 长期记忆 / 工具说明 / 历史），末尾附当前日期时间；历史默认截断 20 轮且**只切在用户消息边界**。
- `ToolExecutor.java` —— 工具调度与审计；异常转失败结果不上抛。
- `AgentService.java` —— 三触发源共用的唯一入口（章程原则 14 的地基）；`finally` 清理 `ProfileContext`。
- `ProfileContext.java` —— 当前 Agent 的线程上下文（`final` 类 + 私有构造 + 全静态方法）。

**新增 —— `oryxos-core` · `context` 包**
- `ContextLoader.java` —— 每次组装 prompt 重新读 Bootstrap 与 Skill 软连接，**不缓存**。

**新增 —— `oryxos-core` · `session` 包**（本节 commit 创建）
- `Session.java` / `SessionMessage.java` / `SessionService.java` —— 会话模型与持久化接缝，见 [contracts/session-model.md](contracts/session-model.md)。

**新增 —— `oryxos-storage`**
- `entity/ToolInvocation.java`、`repository/ToolInvocationRepository.java`、`db/schema.sql` 追加 `tool_invocations` 表。

**新增 —— 测试**：见 §2 的 5 个类（29 条）。

### 6.2 重点 review 清单（按风险排序）

1. **★ 自实现 ReAct 循环，不依赖 Spring AI 的 Agent 抽象** —— `ReActLoop.java`。章程原则 3 的正面体现，也是全项目最易被「顺手用框架」侵蚀的一处。回归测试 `hitsMaxIterationsAndStops`。
2. **★ `ProfileContext` 清理必须在 `finally`** —— `AgentService.java:64-66`。写在正常路径上，异常时上下文会泄漏到下一个请求（线程复用）。回归测试 `clearsProfileContextEvenWhenProcessingThrows`。
3. **工具异常不上抛** —— `ToolExecutor`。抛出去会打断整个循环；正确做法是转成 `success=false` 的结果喂回模型。回归测试 `toolFailureDoesNotBreakTheLoop` 一族。
4. **历史截断只切在用户消息边界** —— `PromptBuilder`。切在工具消息中间会让模型收到「有 tool 结果但没有对应 tool_call」的畸形历史。回归测试 `neverStartsHistoryWithToolMessage`。
5. **`ContextLoader` 不缓存** —— 章程要求「改完下轮即生效」；一旦有人加缓存，`reloadsChangedFileOnNextAssembly` 立刻红。
6. **静态门禁妥协点** —— **无**。本节未为过 P3C 改名或压制任何告警。

### 6.3 如何验证

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64

# 1) 全量门禁 —— 预期 BUILD SUCCESS
./mvnw clean verify

# 2) 本节 5 个单测类（29 条）—— 预期全绿
./mvnw -o -pl oryxos-core -am test \
  -Dtest='ReActLoopTest,PromptBuilderTest,ToolExecutorTest,AgentServiceTest,ContextLoaderTest' \
  -Dsurefire.failIfNoSpecifiedTests=false

# 3) ★ 关键回归：转满最大轮数强制停 —— 预期 1 条绿
./mvnw -o -pl oryxos-core -am test \
  -Dtest='ReActLoopTest#hitsMaxIterationsAndStops' -Dsurefire.failIfNoSpecifiedTests=false

# 4) ★ 关键回归：异常时 ProfileContext 也被清掉 —— 预期 1 条绿
./mvnw -o -pl oryxos-core -am test \
  -Dtest='AgentServiceTest#clearsProfileContextEvenWhenProcessingThrows' \
  -Dsurefire.failIfNoSpecifiedTests=false

# 5) H4-④ ProfileContext 清理在 finally —— 预期命中 set/finally/clear 三行
grep -n "ProfileContext.set\|} finally\|ProfileContext.clear" \
  oryxos-core/src/main/java/io/oryxlabs/oryxos/core/engine/AgentService.java

# 6) H4-⑤ 无异步/线程池 —— 预期无输出
grep -rnE "CompletableFuture|Reactor|ExecutorService|ThreadPool" \
  oryxos-core/src/main/java/io/oryxlabs/oryxos/core/engine/
```

---

## 7. 剩余人工项（课件「做完怎么验」，harness 判不了）

1. **Demo 一（每日天气）的对话版用真模型跑通一次** —— ⚠️ **未做**。依赖真 key；第 18 节的 `oryxos chat` 已能起 Spring 上下文（`serve` 实测无 Bean 撞车），但真模型对话未跑。
2. **循环自实现、未用框架 Agent 封装（code review 项）** —— ✅ 可静态判定：`ReActLoop` 全仓无 Spring AI Agent 抽象引用（§5-⑥ 的 grep）。
3. **「ReAct 要和上一节的 Provider 一起，才撑得起 Demo 一」** —— 说明性结论，非可执行项；跨节契约已由 `ReActLoopTest` 用替身 `LlmClient` 验证。

---

## 8. 备注

- ℹ️ `docs/class/`（课件目录）**已随仓库跟踪**——2026-10-14 由仓库所有者决定入库（本条原写「未入库」，据此更正）。
- 本报告为**回填**，写入时**未 commit / 未 push**。
- 本节与课件的**唯一已知偏差**：无 `ToolInvocationRepositoryTest`（见 §3 末）。其余 5 个测试类逐一对号。
- 第 18 节对本节的改动已完整列于 §0；其中对 3 个测试类的改动是**纯签名机械更新**，断言与用例名一行未动。
