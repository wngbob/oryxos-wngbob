# Tasks: ReAct 循环 —— Agent 的「想—做—看」主循环

**Feature Directory**: `specs/002-react-loop`

**Input**: Design documents from `specs/002-react-loop/`

**Prerequisites**: [plan.md](plan.md) ✅、[spec.md](spec.md) ✅、[research.md](research.md) ✅、[data-model.md](data-model.md) ✅、[contracts/](contracts/) ✅

**Tests**: 本节**要求测试**——课件「四、验收 harness」明确规定了 5 个单测类，且点名两个"最值钱的测试方法"必须原样落地。故每个 user story 阶段均含测试任务，且测试任务先于对应实现任务。本节**无集成冒烟**（不碰网络，一个 `@Tag("integration")` 类都不加）。

**Organization**: 按 user story 分阶段，每个 story 可独立实现与验证。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无未完成依赖）
- **[Story]**: 所属 user story（US1 / US2 / US3 / US4）
- 每条任务都带确切文件路径

## 全局纪律（来自 `/oryxos-lesson-dev`，对每条任务生效）

- **测试方法名必须是英文**（驼峰或 snake_case）。课件 harness 给出的中文方法名翻译成语义等价的英文名，**课件原文进 `@DisplayName`** 以便对号。
- 只创建「本节交付物」点名的对外概念；已定字面量（类名 / 方法签名 / 配置键 / 列名）逐字保真。
- 异常不吞：catch 必落审计或上抛。
- 不得删断言、`@Disabled`、放宽阈值让测试变绿。
- 避开 P3C/ASM 解析不了的 Java 18+ 语法形态（增强 switch 的 `default ->` 写法）；不用 `System.out`；不引响应式/自建线程池。
- **`oryxos-core` MUST NOT import `oryxos-provider` / `oryxos-tool` / `oryxos-memory` 的任何类**（章程附加约束）——违反即为返工。

## ⚠️ 阶段顺序说明（唯一的顺序偏离，理由：编译依赖）

模板要求按 spec 的优先级排阶段（US1 → US2 → US3 → US4）。本节**不照此排**，实际顺序为 **US2 → US3 → US1 → US4**：

`ReActLoop`（US1）持有 `PromptBuilder`（US2）与 `ToolExecutor`（US3）两个具体类型的字段并直接调用，**编译期就必须有这两个类**；Mockito 也无法 mock 不存在的类型。US2 / US3 两者互不依赖、也不依赖 US1，故拓扑序唯一可行解是先把两个协作者落地，再落循环本体。

这与 001 里「US2 改 US1 的同文件 `ProviderService.chat`」是同一类跨 story 编译依赖，处理方式一致：**在 Dependencies 段落显式记录**，不靠隐式假设。US1 仍是本节的招牌 story 与 MVP 收口点（Phase 5），只是它的两个协作者要先就位。

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 补齐本节新增代码所需的依赖与测试目录。骨架期已就位的部分（`spring-ai-bom` 1.1.8 已在根 pom 的 `dependencyManagement` 导入、`oryxos-core` 已有 `spring-boot-starter-test`）不再重复。

- [X] T001 在 `oryxos-core/pom.xml` 增加两个依赖：`org.springframework.ai:spring-ai-model`（版本由根 pom 的 `spring-ai-bom` 1.1.8 托管，**不写版本号**）与 `io.oryxlabs:oryxos-storage`。**MUST NOT** 新增任何其他依赖，**MUST NOT** 引入任何新的第三方库（[plan.md](plan.md) §「需要说明的三处偏离」①②）
- [X] T002 [P] 建立测试源码目录：`oryxos-core/src/test/java/io/oryxlabs/oryxos/core/engine/`、`oryxos-core/src/test/java/io/oryxlabs/oryxos/core/context/`
- [X] T003 依赖可用性核实（属**机器可判**项）：跑 `./mvnw -pl oryxos-core -am dependency:tree`，确认 ① `spring-ai-model` 解析到 `1.1.8`；② `oryxos-storage` 出现在树中；③ 树中**不出现** `oryxos-provider` / `oryxos-tool` / `oryxos-memory`。任一条不满足 → 软门禁，停下报告

**Checkpoint**: 依赖齐备且依赖方向正确，测试可编译运行

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 四个 story 共同依赖的会话领域类型与四个接缝接口。**⚠️ 本阶段完成前，任何 user story 不得开工**。

- [X] T004 [P] 新建 `oryxos-core/src/main/java/io/oryxlabs/oryxos/core/session/SessionMessage.java`：记录类，五个字段**逐字照抄** [data-model.md](data-model.md) §2——`Role role`（嵌套枚举 `SYSTEM` / `USER` / `ASSISTANT` / `TOOL`）、`String content`、`List<AssistantMessage.ToolCall> toolCalls`、`String toolCallId`、`Instant timestamp`。**MUST NOT** 加 `toolName` 字段（A1 的元素形状没有它，工具名由 `PromptBuilder` 反查，[research.md](research.md) D3）
- [X] T005 [P] 新建 `oryxos-core/src/main/java/io/oryxlabs/oryxos/core/llm/LlmClient.java`：接口，方法签名逐字为 `ChatResponse chat(String sessionId, Profile profile, Prompt prompt)`（[contracts/core-seams.md](contracts/core-seams.md) §1）。**MUST NOT** 改成 `CompletableFuture` / `Flux` 返回
- [X] T006 [P] 新建 `oryxos-core/src/main/java/io/oryxlabs/oryxos/core/tool/ToolCatalog.java`：接口，`Optional<OryxTool> find(String name)` + `List<OryxTool> availableFor(Profile profile)`（与既有的 `OryxTool` / `ToolResult` 同包）。**MUST NOT** 合并成一个方法（[contracts/core-seams.md](contracts/core-seams.md) §2 已说明两处过滤口径不同）
- [X] T007 [P] 新建 `oryxos-core/src/main/java/io/oryxlabs/oryxos/core/memory/MemorySource.java`：接口，`Optional<String> longTermMemory(Profile profile)`（[contracts/core-seams.md](contracts/core-seams.md) §3）
- [X] T008 新建 `oryxos-core/src/main/java/io/oryxlabs/oryxos/core/session/Session.java`：`final` 类，字段 `id` / `profileName` / `messages` / `createdAt` / `lastActiveAt` + 三个追加操作 `appendUserMessage(String)` / `appendAssistantMessage(String, List<AssistantMessage.ToolCall>)` / `appendToolResult(String toolCallId, ToolResult)`（[contracts/session-model.md](contracts/session-model.md) §1 逐字照抄）。四条不变量逐条落地（[contracts/session-model.md](contracts/session-model.md) §3）：**只追加**（不删不重排）、**文本与工具请求同属一条消息**（不拆两条）、**`SYSTEM` 角色不写入**、**非线程安全**（不加锁）。依赖 T004
- [X] T009 新建 `oryxos-core/src/main/java/io/oryxlabs/oryxos/core/session/SessionService.java`：接口，**本节只声明 `void save(Session session)`**。**MUST NOT** 预先声明 `load` / `create`——没有调用方的抽象就是凭空造接口（章程原则 II，[contracts/session-model.md](contracts/session-model.md) §4）。依赖 T008

**Checkpoint**: 会话领域类型与四个接缝接口就位——四个 user story 可以开工

---

## Phase 3: User Story 2 - 每轮上下文：四段顺序、历史截断、日期锚点 (Priority: P1)

**Goal**: 每一轮发给模型的内容按固定四部分顺序拼成（system prompt + 长期记忆 + 会话历史 + 可用工具列表），历史只留最近 N 轮且不切出悬空片段，system prompt 末尾附当前日期时间；每次组装现读文件、不缓存。

**Independent Test**: 用 JUnit `@TempDir` 造出启动文件与 Agent 正文，构造一段超过 N 轮的会话历史，断言：四部分顺序与来源一一对应；历史只保留最近 N 轮且**不以工具消息开头**；system prompt 末尾含当前日期时间；改完文件后**下一次**组装立即读到新内容；显式引用的文件缺失时报错指名，默认启动文件缺失至少留一条 WARN。

### Tests for User Story 2 ⚠️ 先写，确认变红

- [X] T010 [US2] 新建 `oryxos-core/src/test/java/io/oryxlabs/oryxos/core/context/ContextLoaderTest.java`（`@TempDir` 造 `.oryxos/` 目录树）：
  - `rereadsFileOnEveryLoad` —— `@DisplayName("改文件后下一次组装立即读到新内容（无缓存）")`：先 `load` 一次，改文件内容，再 `load` 一次，断言第二次的产物含新内容。**SC-008 的直接证据，MUST NOT 删或弱化**
  - `explicitBootstrapMissingThrowsNamingFile` —— `@DisplayName("Profile 显式引用的文件缺失：报错并指明是哪个文件")`：断言异常消息**含那个文件名**
  - `defaultBootstrapMissingWarns` —— `@DisplayName("默认 Bootstrap 缺失：至少 WARN，不静默跳过")`：用 Logback `ListAppender` 挂到 `ContextLoader` 的 logger 上，断言产生了至少一条 WARN（[research.md](research.md) D13）。**MUST NOT** 用"不抛异常"代替这条断言
  - `danglingSkillSymlinkThrows` —— `@DisplayName("Skill 软连接悬空或越出 skills/：报错")`
- [X] T011 [US2] 新建 `oryxos-core/src/main/java/io/oryxlabs/oryxos/core/context/ContextLoader.java`：`ContextLoader(Path oryxosDir)` + `String load(Profile profile)`（[contracts/engine-api.md](contracts/engine-api.md) §6 的路径表逐行落地）。**两条铁律**：①**不缓存**——每次 `load` 重新读文件、重新扫 `skills/`，不持有任何跨调用的缓存；②**不静默跳过**——显式引用缺失抛异常并指名文件，默认 Bootstrap 缺失打 WARN。异常类型只用 `java.io.UncheckedIOException` 与 `IllegalStateException`，**不新增异常类型**。Skill **只注入 name / description / 本地绝对路径**，正文不预载；Skill **MUST NOT** 注册进 `ToolCatalog`（章程原则 IV）

### Tests for User Story 2（PromptBuilder）⚠️ 先写，确认变红

- [X] T012 [US2] 新建 `oryxos-core/src/test/java/io/oryxlabs/oryxos/core/engine/PromptBuilderTest.java`（mock `ContextLoader` / `MemorySource` / `ToolCatalog`，构造真实 `Session`）：
  - `ordersFourPartsInFixedSequence` —— `@DisplayName("四部分顺序与来源一一对应")`：断言 `Prompt.getInstructions()` 的第 1 条是 system prompt、第 2 条是长期记忆、其后是会话历史；`getOptions()` 里带着可用工具
  - `skipsMemoryPartWhenAbsent` —— `@DisplayName("长期记忆为空：整条不出现，不塞占位文本")`：`MemorySource` 返回 `Optional.empty()`，断言消息条数比上一用例**少一条**且无占位文本
  - `appendsCurrentDateTimeAtEndOfSystemPrompt` —— `@DisplayName("system prompt 末尾附当前日期时间")`：断言 system prompt 文本**以日期时间行结尾**且含 `LocalDate.now().toString()`
  - `truncatesToLastNUserTurns` —— `@DisplayName("历史超 N 轮：只保留最近 N 轮")`：造 25 个用户往返、`maxHistoryTurns=20`，断言历史部分只含最近 20 轮、更早的不出现，且 system prompt 一定在
  - `neverStartsHistoryWithToolMessage` —— `@DisplayName("截断只切在用户消息边界：历史从不以工具消息开头")`：断言历史部分第一条是用户消息。**SC-005 的直接证据，MUST NOT 删或弱化**
  - `keepsHistoryIntactWhenExactlyN` —— `@DisplayName("恰好 N 轮：不截断")`（边界值不误伤）
  - `toolCallbackThrowsOnCall` —— `@DisplayName("工具说明只翻译不执行：产物 call() 抛 UnsupportedOperationException")`（章程原则 III）
  - `reversesToolNameFromHistory` —— `@DisplayName("工具消息的 name 由同段历史的 toolCalls 反查得到")`
- [X] T013 [US2] 新建 `oryxos-core/src/main/java/io/oryxlabs/oryxos/core/engine/PromptBuilder.java`：`PromptBuilder(ContextLoader, MemorySource, ToolCatalog)` + `Prompt build(Session session, Profile profile)`。四部分落点逐字照 [contracts/engine-api.md](contracts/engine-api.md) §2 的映射表。要点：①截断保留**最后 N 个用户往返**，切点只落在用户消息边界；②截断**不改动 `Session` 本身**；③`OryxTool → ToolCallback` 用**私有静态方法**完成、产物 `call(String)` 抛 `UnsupportedOperationException`、**不新增 public 类型**（[research.md](research.md) D7）；④日期时间行格式 `当前时间：<LocalDateTime.now()>` 追加在 system prompt 最末尾；⑤角色映射用传统 `switch` + `break` 或 if/else 链，**不用箭头形式的增强 switch**（[research.md](research.md) D16）
- [X] T014 [US2] 跑 `./mvnw -pl oryxos-core -am test -Dsurefire.failIfNoSpecifiedTests=false`，T010 与 T012 全绿；红了当场修，不攒到最后

**Checkpoint**: US2 独立可用——四段组装、截断、日期锚点、无缓存、缺失文件不静默

---

## Phase 4: User Story 3 - 工具执行只有一条路：成败都留痕 (Priority: P2)

**Goal**: 模型说「调 http_get」，系统在**唯一一处**找到工具、执行、把结果包好返回给循环，同时落一条 `tool_invocations` 记录；工具抛异常不被静默吞掉，失败标识与原因照样落账，错误信息回灌给模型。

**Independent Test**: 用可控的工具替身分别制造「成功」「抛异常」「查不到」三种情形，各执行一次，断言三次都产生了一条调用记录；成功那条 `success=true`；失败那条 `success=false` 且原因含替身抛出的错误文本；失败情形下返回给循环的是携带错误信息的失败结果而不是未处理的异常。

### Tests for User Story 3 ⚠️ 先写，确认变红

- [X] T015 [US3] 新建 `oryxos-core/src/test/java/io/oryxlabs/oryxos/core/engine/ToolExecutorTest.java`（mock `ToolCatalog` 与 `ToolInvocationRepository`，用 `ArgumentCaptor<ToolInvocation>` 断言写入的实体字段）：
  - `recordsSuccessfulInvocation` —— `@DisplayName("成功：落一条 success 为真的记录")`：断言 `success=true`、`resultJson` 为工具产出、`errorMessage` 为 `null`、`durationMs` 非负
  - `recordsFailedInvocationWithReason` —— `@DisplayName("工具抛异常：落一条 success 为假、原因含异常文本的记录")`：断言 `success=false`、`errorMessage` 含替身抛出的文本，**且返回的是携带该错误信息的失败结果**
  - `toolExceptionDoesNotBreakLoop` —— `@DisplayName("工具异常不打断循环：转成失败结果返回，不上抛")`：`assertDoesNotThrow` + 断言返回值的 `success()` 为假。**FR-007 的直接证据，MUST NOT 删或弱化**
  - `unknownToolIsRecordedAsFailure` —— `@DisplayName("查不到工具：同样落账，原因写明工具名")`：`ToolCatalog.find` 返回 `Optional.empty()`，断言仍写了一条 `success=false` 且 `errorMessage` 含工具名
  - `logsEveryInvocationOnBothPaths` —— `@DisplayName("每次工具调用都输出结构化日志：成功与失败各一条")`：用 Logback `ListAppender` 挂到 `ToolExecutor` 的 logger（做法同 T010），成功与失败各执行一次，断言两条路径**各自**产生了一条日志事件。**章程附加约束「可观测」要求日志覆盖每一次 Tool 调用，MUST NOT 删或弱化**
  - 每个用例都必须断言"审计记录被写过"，**MUST NOT** 只断言返回值

### Implementation for User Story 3

- [X] T016 [US3] 新建 `oryxos-core/src/main/java/io/oryxlabs/oryxos/core/engine/ToolExecutor.java`：`ToolExecutor(ToolCatalog toolCatalog, ToolInvocationRepository repository)` + `ToolResult execute(String sessionId, AssistantMessage.ToolCall call)`。三种情形按 [contracts/engine-api.md](contracts/engine-api.md) §3 的表逐行落地，三条不变量逐条守住：①**每一次工具调用恰好产生一行审计**（成功 / 失败 / 查不到工具都算）；②**每一次工具调用恰好输出一条结构化 JSON 日志（成败都写，成功 INFO / 失败 WARN，与第 16 节 `ProviderService` 同口径）**，且失败**不静默吞**——原因进审计、进日志、也回灌给模型；③工具异常**不打断循环**——捕 `RuntimeException` 一律转失败结果返回，**MUST NOT** 上抛（捕 `RuntimeException` 即可覆盖第 24 节的 `SandboxViolationException`，**MUST NOT** import `oryxos-tool` 的异常类）。`ToolInvocation` 用公开无参构造后逐列 `set`（[data-model.md](data-model.md) §3）。**本节只写不建**：不改表结构、不新建表、不新增 Repository 查询方法
- [X] T017 [US3] 跑 `./mvnw -pl oryxos-core -am test -Dsurefire.failIfNoSpecifiedTests=false`，T015 全绿

**Checkpoint**: US2 + US3 各自独立可用——上下文组装 + 工具执行唯一落点

---

## Phase 5: User Story 1 - 循环跑通：一轮能收尾，多轮能接上 (Priority: P1) 🎯 本节 MVP 在此收口

**Goal**: `ReActLoop` 把「组装 → 调模型 → 判是否收尾 → 执行工具 → 回灌」串成循环；模型不要工具时一轮收尾；要工具时执行并接上下一轮；转满上限时强制收尾且答复含「达到最大轮数」。

**Independent Test**: 用可控的模型替身（mock `LlmClient`）与可控的工具执行替身（mock `ToolExecutor`）构造三种情形——①第一轮就不要工具；②第一轮要工具、第二轮不要；③每一轮都要工具（永不收敛）。断言：①只调模型 1 次且返回模型文本；②工具被执行且其结果出现在下一轮发出去的内容里；③模型恰好被调 10 次（一轮不多）且答复含「达到最大轮数」。三种情形结束后，会话里都能按序查到本次全部模型响应与工具结果。

### Tests for User Story 1 ⚠️ 先写，确认变红

- [X] T018 [US1] 新建 `oryxos-core/src/test/java/io/oryxlabs/oryxos/core/engine/ReActLoopTest.java`（mock `PromptBuilder` / `LlmClient` / `ToolExecutor`，构造真实 `Session` 与 `Profile`）：
  - `stopsAfterSingleRoundWhenNoToolCalls` —— `@DisplayName("一轮能收尾：模型恰好被调 1 次，工具 0 次")`：`verify(llmClient, times(1)).chat(...)` **且** `verifyNoInteractions(toolExecutor)`。**SC-001 的直接证据**
  - `executesToolAndFeedsResultIntoNextRound` —— `@DisplayName("多轮能接上：工具结果出现在下一轮发出去的上下文里")`：断言工具被执行一次，且第二次 `build` 拿到的 `Session` 里含那条工具结果。**SC-002 的直接证据**
  - `hitsMaxIterationsAndStops` —— `@DisplayName("模型一直要调工具：转满最大轮数强制停")`：`verify(llmClient, times(10)).chat(...)` **且** `verify(toolExecutor, times(10)).execute(...)` **且** `assertTrue(reply.contains("达到最大轮数"))`。**这是课件点名的两个最值钱回归测试之一，MUST NOT 删或放宽——任何把上限改掉或把轮数判断挪到循环尾的改动都必须让这条立刻变红**
  - `respectsConfiguredMaxIterations` —— `@DisplayName("上限可由 Agent 配置覆盖：配 3 就恰好调 3 次")`
  - `accumulatesEveryRoundInOrder` —— `@DisplayName("每轮响应与工具结果都按序累积进会话")`：断言 `session.messages()` 的顺序为 用户 → assistant → tool → assistant → tool → …。**SC-004 的直接证据**
  - `lastRoundToolCallsStillExecuted` —— `@DisplayName("转满上限时最后一轮的工具照常执行")`：断言会话历史里**悬空的工具请求数为 0**（每条 tool 消息都有配对的 assistant 工具请求）。**SC-003 的直接证据**
  - `throwsWhenResponseHasNoGeneration` —— `@DisplayName("模型响应没有 generation：抛 IllegalStateException")`

### Implementation for User Story 1

- [X] T019 [US1] 新建 `oryxos-core/src/main/java/io/oryxlabs/oryxos/core/engine/ReActLoop.java`：`ReActLoop(PromptBuilder, LlmClient, ToolExecutor)` + `String run(Session session, String userMessage, Profile profile)`，算法逐行照 [contracts/engine-api.md](contracts/engine-api.md) §1。四条行为约定：①**轮数判断在循环头**（第 N 轮的响应照常累积、该轮的工具照常执行，不留悬空请求）；②上限取 `profile.settings().maxIterations()`（默认 10）；③工具**顺序执行，MUST NOT 并行**；④**不感知消息来源**——不认识 channel、不认识 CLI/Web/定时（FR-008）。上限答复的字面量 `"达到最大轮数，已停止"` 写成**私有常量**；`getResult() == null` 时抛 `IllegalStateException`
- [X] T020 [US1] 跑 `./mvnw -pl oryxos-core -am test -Dsurefire.failIfNoSpecifiedTests=false`，T018 全绿

**Checkpoint**: US2 + US3 + US1 三者合体——这就是本节最小可交付切片（循环真能转起来）

---

## Phase 6: User Story 4 - 三种触发源同一个入口：当前 Agent 可见、用完即清 (Priority: P2)

**Goal**: CLI / Web / 定时都汇入 `AgentService.process`；处理期间「当前是哪个 Agent」对工具执行可见；处理结束（**哪怕中途抛异常**）一定被清掉。

**Independent Test**: 用替身构造一次正常处理与一次抛异常的处理，断言处理期间能读到当前 Agent 且等于本次会话所属的那个；正常结束与异常结束时**都**被清掉；处理结束后会话经持久化接缝被保存一次。

### Implementation for User Story 4（先落 `ProfileContext`，测试要直接断言它）

- [X] T021 [US4] 新建 `oryxos-core/src/main/java/io/oryxlabs/oryxos/core/engine/ProfileContext.java`：`final` 类，三个静态方法 `set(Profile)` / `current()` / `clear()`。`current()` 未设置时返回 **`null`（不是 `Optional`）**——课件 harness 用 `assertNull(ProfileContext.current())` 逐字钉死这一条（[research.md](research.md) D10）。**只被 `AgentService` 放置与清除**，其他类只读

### Tests for User Story 4 ⚠️ 先写，确认变红

- [X] T022 [US4] 新建 `oryxos-core/src/test/java/io/oryxlabs/oryxos/core/engine/AgentServiceTest.java`（mock `ProfileRegistry` / `ReActLoop` / `SessionService`）：
  - `exposesCurrentProfileDuringProcessing` —— `@DisplayName("处理期间能读到当前 Agent，且等于本次会话所属的那个")`：在 `ReActLoop.run` 的替身里读 `ProfileContext.current()` 并捕获，断言等于本次会话所属的 Agent
  - `clearsProfileContextOnNormalExit` —— `@DisplayName("正常结束：当前 Agent 已被清掉")`：`assertNull(ProfileContext.current())`
  - `clearsProfileContextEvenWhenProcessingThrows` —— `@DisplayName("处理中抛异常：ProfileContext 也必须被清掉")`：`assertThrows(RuntimeException.class, ...)` **并且** `assertNull(ProfileContext.current())`。**这是课件点名的两个最值钱回归测试之一，MUST NOT 删或弱化——ThreadLocal 泄漏在单请求测试里永远不报错，只在并发复用时串号（SC-007）**
  - `savesSessionThroughSeam` —— `@DisplayName("处理结束：会话经持久化接缝被保存一次")`：`verify(sessionService).save(session)`
  - `unknownProfileThrowsNamingAgent` —— `@DisplayName("Profile 查不到：抛 IllegalStateException，消息含 Agent 名")`
  - 每个用例的 `@AfterEach` 都清一次 `ProfileContext`，避免用例间互相污染

### Implementation for User Story 4

- [X] T023 [US4] 新建 `oryxos-core/src/main/java/io/oryxlabs/oryxos/core/engine/AgentService.java`：`AgentService(ProfileRegistry, ReActLoop, SessionService)` + `String process(Session session, String userMessage)`，算法逐行照 [contracts/engine-api.md](contracts/engine-api.md) §4——查 Profile（查不到抛 `IllegalStateException`，消息含 Agent 名）→ `ProfileContext.set` → `try { run; save; return } finally { clear }`。四条约定：①**唯一入口**（三种触发源都汇入）；②`finally` 清上下文（正常与异常路径都清）；③持久化在循环**之后**（`save` 拿到的是累积完的历史）；④**不在这里 try-catch 业务异常**（失败该让调用方知道，本类只负责清干净）。**MUST NOT** 新增异常类型
- [X] T024 [US4] 跑 `./mvnw -pl oryxos-core -am test -Dsurefire.failIfNoSpecifiedTests=false`，T022 全绿

**Checkpoint**: 四个 user story 全部独立可用

---

## Phase 7: Polish & Cross-Cutting Concerns

- [X] T025 [P] 架构约束 grep 自查（机器可判，[quickstart.md](quickstart.md) §3/§4）：
  - `grep -rn "import io\.oryxlabs\.oryxos\.\(provider\|memory\|tool\|web\|channel\|cli\|boot\)" --include="*.java" oryxos-core/src/main` → **无输出**（`oryxos-storage` 是共享基础设施，允许）
  - `grep -rn "Flux\|Mono\|CompletableFuture\|Executors\." --include="*.java" oryxos-core/src/main oryxos-provider/src/main` → **无输出**
  - `grep -rn "internalToolExecutionEnabled" --include="*.java" oryxos-core/src/main` → **无输出**（core 只构造 `toolCallbacks`，开关由第 16 节 `ProviderService` 单点关闭）
  - `grep -rn "spring-ai.*Agent\|ChatClient\|Advisor" --include="*.java" oryxos-core/src/main` → **无输出**（循环是手写的，不用框架现成的 Agent 封装）
  - `grep -rn "implements LlmClient\|implements ToolCatalog\|implements MemorySource\|implements SessionService" --include="*.java" .` → **无输出**（本节只定义与调用，不实现）
  - `grep -rn "sk-" --include="*.java" --include="*.yaml" --include="*.yml" . | grep -v "^./docs/"` → **无输出**
  - `grep -rn "System\.out\|System\.err" --include="*.java" oryxos-core/src/main` → **无输出**（章程附加约束「可观测」：日志走 SLF4J，禁用 `System.out`）
- [X] T026 [P] H4 六条全局不变量逐条自查：①涉外 IO 首行过 `Sandbox.enforce` —— 本节无涉外 IO 工具实现，`ToolExecutor` 是执行入口的**调用方**而非实现方，留调用位并注明第 24 节接线；②LLM 调用成败都落 `llm_calls`、工具执行成败都落 `tool_invocations` **且每次调用各输出一条结构化日志** —— 本节侧由 T015（含 `logsEveryInvocationOnBothPaths`）覆盖；③无明文 key —— T025 已 grep；④`session_id` 只在 `SessionService` 内拼接 —— 本节不生成会话标识，只原样透传（[contracts/session-model.md](contracts/session-model.md) §5）；⑤无 Reactor / `CompletableFuture` / 自建线程池 —— T025 已 grep；⑥无 Spring AI 自动工具执行路径 —— T025 已 grep
- [X] T027 全量门禁：`./mvnw clean verify` 全绿（含 Spotless / P3C / Checkstyle / SpotBugs+FindSecBugs / OWASP Dependency-Check）。改了代码先跑 `./mvnw spotless:apply`
  - 门禁实测：`./mvnw clean verify -Ddependency-check.skip=true` 全绿（10 个模块 SUCCESS）。
    过程中修掉 3 条静态检查报错：2 条改实现（`ContextLoader` 的 `getFileName()` 判空、`PromptBuilder` 的冗余判空），
    1 条 SpotBugs 误报（`EI_EXPOSE_REP2` 报「按依赖注入持有 `ContextLoader`」）经报批后用
    `@SuppressFBWarnings` 就地豁免——为此新增 `com.github.spotbugs:spotbugs-annotations:4.8.6`（provided，纯注解、不进产物）。
- [X] T028 按 [quickstart.md](quickstart.md) 逐场景走查，并整理六项证据 DoD 素材（前序节测试回归绿、课件「本节交付物」存在性核对、课件「做完怎么验」的剩余人工项清单）

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 无依赖，可立即开始
- **Foundational (Phase 2)**: 依赖 Setup 完成——**阻塞全部 user story**
- **Phase 3 (US2) / Phase 4 (US3)**: 均依赖 Foundational；**两者互不依赖，可并行**
- **Phase 5 (US1)**: 依赖 Phase 3 的 `PromptBuilder`（T013）与 Phase 4 的 `ToolExecutor`（T016）——**编译期硬依赖**（见文首「阶段顺序说明」）
- **Phase 6 (US4)**: 依赖 Phase 5 的 `ReActLoop`（T019）与 Phase 2 的 `SessionService`（T009）
- **Polish (Phase 7)**: 依赖全部 story 完成

### User Story Dependencies

- **US2 (P1)**: Foundational 后可开始，无其他 story 依赖
- **US3 (P2)**: Foundational 后可开始，**与 US2 完全并行**（无文件交集）
- **US1 (P1)**: 依赖 US2 的 `PromptBuilder` 与 US3 的 `ToolExecutor`（仅类型存在，行为由替身替代）
- **US4 (P2)**: 依赖 US1 的 `ReActLoop`

### Within Each User Story

- 测试先写并确认变红，再实现
- 接口 / 领域类型 → 实现 → 跑该模块测试
- 每个 story 收尾即跑测试，红了当场修，不攒到最后

### Parallel Opportunities

- T002 与 T003 可并行（建目录与核实依赖互不影响）
- **T004 / T005 / T006 / T007 四条可并行**（四个不同文件、互不引用）
- **Phase 3（US2）与 Phase 4（US3）整段可并行**——两组任务无文件交集
- T010 与 T012 可并行（不同测试文件，但 T012 的 mock 需要 T011 的 `ContextLoader` 类存在）
- T025 与 T026 可并行（都是只读自查）

---

## Parallel Example: Foundational 阶段

```bash
# 四个不同文件、互不引用，可同时开工：
Task: "T004 新建 SessionMessage 记录类 in oryxos-core/.../core/session/SessionMessage.java"
Task: "T005 新建 LlmClient 接缝接口 in oryxos-core/.../core/llm/LlmClient.java"
Task: "T006 新建 ToolCatalog 接缝接口 in oryxos-core/.../core/tool/ToolCatalog.java"
Task: "T007 新建 MemorySource 接缝接口 in oryxos-core/.../core/memory/MemorySource.java"
```

## Parallel Example: US2 与 US3 并行

```bash
# 两条线无文件交集：
Task: "T010-T014 [US2] ContextLoader + PromptBuilder in oryxos-core/.../core/{context,engine}/"
Task: "T015-T017 [US3] ToolExecutor in oryxos-core/.../core/engine/ToolExecutor.java"
```

---

## Implementation Strategy

### MVP First

1. 完成 Phase 1 Setup + Phase 2 Foundational（**关键**——阻塞全部 story）
2. 完成 Phase 3（US2）+ Phase 4（US3）+ Phase 5（US1）——**三者合体才是本节最小可交付切片**：循环真能转起来、上下文真能组装、工具真能执行且留痕
3. **STOP and VALIDATE**：`./mvnw -pl oryxos-core -am test`

> **单模块命令一律带 `-am`**：`-pl` 只把点名的模块放进反应堆，兄弟模块会从 `~/.m2` 解析。只要本地跑过一次 `mvn install`，那里就躺着旧字节码，改过兄弟模块的 API 后单模块命令会静默取到旧版本。本节新增了 `oryxos-core → oryxos-storage` 的依赖边，这条坑更容易踩。
>
> 配 `-Dtest=X` 时还要加 `-Dsurefire.failIfNoSpecifiedTests=false`，否则在无匹配测试的模块上 surefire 会判失败。

### Incremental Delivery

1. Setup + Foundational → 地基就位
2. US2（上下文组装）→ 独立验证
3. US3（工具执行与审计）→ 独立验证
4. US1（循环本体）→ 独立验证 → **本节核心可交付**
5. US4（统一入口与上下文生命周期）→ 独立验证
6. Polish（架构 grep + 全量门禁 + quickstart 走查）

每个 story 增量交付，不破坏前序 story。本节全部完成后，本节才能支撑第 18 节 CLI 的接线。

---

## Notes

- [P] 任务 = 不同文件、无依赖
- 每个 user story 应能独立完成与验证
- 实现前先确认测试变红
- **课件的「`ToolInvocation` 实体 + `ToolInvocationRepository`」无对应任务**——骨架期已交付（实体、Repository、建表脚本齐备），本节只消费、只写不建（[contracts/tool-invocations-table.md](contracts/tool-invocations-table.md) §0）。这是**正确地不做**，不是遗漏
- **课件的「表 `tool_invocations`」无对应任务**——同上，表已在 `oryxos-storage/src/main/resources/db/schema.sql` 里，本节不改表结构、不新建表
- **超出课件「本节交付物」的 6 个类型**（4 个接缝接口 + `Session` + `SessionMessage`）**不是擅自新增**——它们是 spec「冲突仲裁记录」1/2 的已报批裁决产物（core 不反向依赖能力模块的落地方式），已在 [contracts/core-seams.md](contracts/core-seams.md) 与 [contracts/session-model.md](contracts/session-model.md) 固化
- **FR-014（会话标识随模型调用传出）无独立任务**——它由 `LlmClient.chat` 的第一个参数与 `ToolExecutor.execute` 的第一个参数承载，T005 / T016 的签名里已逐字落地
- **SC-009（5 个测试类全绿且不访问网络）不建基准或网络探针**，由 T014 / T017 / T020 / T024 / T027 的测试执行兑现
- **`logsEveryInvocationOnBothPaths`（T015）与 T016 的日志要求来自章程，不是课件**——章程附加约束「可观测」要求结构化 JSON 日志**覆盖每一次** Tool 调用；课件与第 17 节初稿只写了失败路径。这是 `/speckit-analyze` 报出的 CRITICAL（C1）及其实施修法，口径与第 16 节 `ProviderService` 逐字对齐
- **接口名是 `SessionService` 而非课件里的 `SessionManager`**——上游 `specs/000-runtime-kernel/tasks.md` T036/T045 已用 `SessionService`，T077 明确要「统一 `SessionManager`→`SessionService` 命名」。本节点名 `SessionService` 以免第 18 节返工；依据见 spec「冲突仲裁记录」4
- **原则 X「每个 user story 有可演示成果」在本节的形态是测试而非交互 Demo**：ReAct 循环没有独立入口，端到端 Demo 一需要第 18 节 `oryxos chat` 与第 20 节 `http_get` 就位，已列为人工项并如实标注「本节做完时跑不了」（见 [quickstart.md](quickstart.md) §4）
- **给 `ProviderService` 加 `implements LlmClient` 不在本节**（spec Assumptions 明写「只定义与调用，不实现」；与 001 `contracts/provider-service-api.md` §5 的出入已在 [contracts/core-seams.md](contracts/core-seams.md) §5 记录，并在 Step 5 固定停点提请用户确认）
- **全程不自动 commit / push / 运行 `package.sh`**——同步时机由用户决定
- 本节完成后必须跑 `/speckit-analyze`（`/oryxos-lesson-dev` 硬性要求）
