# Tasks: Provider —— 对接大模型的统一入口

**Feature Directory**: `specs/001-llm-provider`

**Input**: Design documents from `specs/001-llm-provider/`

**Prerequisites**: [plan.md](plan.md) ✅、[spec.md](spec.md) ✅、[research.md](research.md) ✅、[data-model.md](data-model.md) ✅、[contracts/](contracts/) ✅

**Tests**: 本节**要求测试**——课件「四、验收 harness」明确规定了 4 个单测类 + 1 个集成冒烟，且点名三个"最值钱的测试方法"必须原样落地。故每个 user story 阶段均含测试任务，且测试任务先于对应实现任务。

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
- 避开 P3C/ASM 解析不了的 Java 18+ 语法形态（增强 switch 等）；不用 `System.out`；不引响应式/自建线程池。

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 补齐本节新增代码所需的依赖与测试脚手架。骨架期已就位的部分（`spring-ai-bom` 1.1.8 已在根 pom 的 `dependencyManagement` 导入）不再重复。

- [X] T001 在 `oryxos-provider/pom.xml` 增加依赖：`org.springframework.ai:spring-ai-model`、`org.springframework.ai:spring-ai-deepseek`（版本由根 pom 的 `spring-ai-bom` 1.1.8 托管，**不写版本号**），并新增对本仓库模块 `io.oryxlabs:oryxos-core`、`io.oryxlabs:oryxos-storage` 的依赖。**MUST NOT** 引入 `spring-ai-starter-model-deepseek`（[research.md](research.md) D3）
- [X] T002 [P] 在 `oryxos-core/pom.xml`、`oryxos-provider/pom.xml`、`oryxos-storage/pom.xml` 各加 `org.springframework.boot:spring-boot-starter-test`，`<scope>test</scope>`（三个模块当前均无测试依赖）
- [X] T003 [P] 建立测试源码目录：`oryxos-core/src/test/java/io/oryxlabs/oryxos/core/profile/`、`oryxos-provider/src/test/java/io/oryxlabs/oryxos/provider/`、`oryxos-storage/src/test/java/io/oryxlabs/oryxos/storage/repository/`
- [X] T004 依赖可用性核实（课件「五、做完怎么验」第 1 项，属**机器可判**项）：跑 `./mvnw -pl oryxos-provider -am dependency:tree`，确认 `spring-ai-model` 与 `spring-ai-deepseek` 均解析到 `1.1.8`。解析不到 → 软门禁，停下报告

**Checkpoint**: 依赖齐备，测试可编译运行

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 四个 story 共同依赖的实体、契约与配置面。**⚠️ 本阶段完成前，任何 user story 不得开工**。

- [X] T005 [P] 新建 `oryxos-core/src/main/java/io/oryxlabs/oryxos/core/profile/Profile.java`：承载 `AGENT.md` frontmatter 的记录类，**本节即建全字段**（[data-model.md](data-model.md) §1 逐字段照抄）。字段约束逐字落地：`name` 必填（取自目录名）、`description` 必填、`provider.name` 必填、`provider.model` 必填、`provider.temperature` 可选（未声明时不显式传给模型）、`tools`/`mcp_servers`/`channels`/`schedules`/`bootstrap` 缺省为空、`settings.max_iterations` 默认 10、`settings.max_history_turns` 默认 20。**MUST NOT** 含 `skills`、**MUST NOT** 含 `notify_channels`（章程原则 IV、[research.md](research.md) D2）
- [X] T006 [P] 修改 `oryxos-storage/src/main/java/io/oryxlabs/oryxos/storage/entity/LlmCall.java`：新增 `success`（`Boolean`）与 `errorMessage`（`String`，映射列 `error_message`）两个字段及其 getter/setter，其余字段不动（[data-model.md](data-model.md) §3）
- [X] T007 [P] 修改 `oryxos-storage/src/main/resources/db/schema.sql`：在 `llm_calls` 的 `CREATE TABLE` 中补 `success BOOLEAN` 与 `error_message TEXT` 两列，位置与 [contracts/llm-calls-table.md](contracts/llm-calls-table.md) §2 的 DDL 一致。**MUST NOT** 改用 `hibernate.ddl-auto` 建表或迁移
- [X] T008 [P] 新建 `oryxos-provider/src/main/java/io/oryxlabs/oryxos/provider/LlmCallRecorder.java`：接口，方法签名逐字为 `void record(String sessionId, String provider, String model, Usage usage, boolean success, String errorMessage, long durationMs)`（`Usage` 为 `org.springframework.ai.chat.metadata.Usage`）。抽成接口是为了让"失败也落审计"的回归测试能 mock 并断言参数（[research.md](research.md) D7）
- [X] T009 新建 `oryxos-provider/src/main/java/io/oryxlabs/oryxos/provider/ProviderProperties.java`（绑定 `oryxos.providers` 的 `@ConfigurationProperties`），并在 `oryxos-boot/src/main/resources/application.yaml` 增加全局层配置段：`oryxos.providers` 数组，每项含 `name` 与 `api-key: ${DEEPSEEK_API_KEY}`（[contracts/provider-config.md](contracts/provider-config.md) §2）。**MUST NOT** 写任何明文凭证
- [X] T010 在 `ProviderProperties` 内实现全局层校验（[contracts/provider-config.md](contracts/provider-config.md) §4、[research.md](research.md) D10）：①清单内 `name` 重复 → 启动加载期报错并指明重复的名字；②`api-key` 的环境变量缺失 → 报错指明**变量名**且不回显任何值；③声明了当前实现不支持的厂商名 → 报错指明该名字（本节仅实现 `deepseek`）

**Checkpoint**: 实体、契约接口、配置面就位——四个 user story 可以开工

---

## Phase 3: User Story 1 - 多 Provider 并存，按名路由不串台 (Priority: P1) 🎯 MVP

**Goal**: `ProviderService.chat(sessionId, profile, prompt)` 按 Profile 点名的厂商从**显式映射表**取出模型、发起一次调用、把结果原样返回；点名的厂商不存在时抛含该名字的异常，不静默换家。

**Independent Test**: 用两个 mock `ChatModel` 冒充两家厂商，让 Profile 点名其中一家发起调用，验证被点名的那家恰好被调用一次、另一家零调用；再让 Profile 点名未声明的名字，验证抛出的异常消息含该原始名字。

### Tests for User Story 1 ⚠️ 先写，确认变红

- [X] T011 [US1] 新建 `oryxos-provider/src/test/java/io/oryxlabs/oryxos/provider/ProviderServiceTest.java`，写两个测试方法（mock 两个 `ChatModel`，构造 `new ProviderService(Map.of("deepseek", deepseek, "kimi", kimi), adapter, audit)`）：
  - `routesToNamedProviderOnly` —— `@DisplayName("按名路由：两个 provider 不串台")`。断言 `verify(kimi, times(1)).call(any())` **且** `verify(deepseek, never()).call(any())`——后者是"不串台"的直接证据，**MUST NOT** 删
  - `unknownProviderNameThrowsWithOriginalName` —— `@DisplayName("引用未声明的 provider：报错须含原始名字")`。断言抛 `ProviderNotFoundException` 且消息含原始输入名

### Implementation for User Story 1

- [X] T012 [P] [US1] 新建 `oryxos-provider/src/main/java/io/oryxlabs/oryxos/provider/ProviderNotFoundException.java`：继承 `RuntimeException`，消息 MUST 含 Profile 点名的**原始厂商名**（大小写与空格按原样回显，不做静默容错）
- [X] T013 [US1] 新建 `oryxos-provider/src/main/java/io/oryxlabs/oryxos/provider/ProviderConfiguration.java`：按 `ProviderProperties` 清单**逐条显式构造** `Map<String, ChatModel>`，键即厂商名；`deepseek` 分支用已核实的签名 `DeepSeekApi.builder().apiKey(..).build()` + `DeepSeekChatModel.builder().deepSeekApi(..).defaultOptions(..).build()`（[research.md](research.md) D3）。**MUST NOT 调用 `.baseUrl(...)`**——配置契约（[contracts/provider-config.md](contracts/provider-config.md) §2）只定义 `name` 与 `api-key` 两个键，端点走 SDK 默认值；凭空造 `base-url` 配置键属软门禁①，须先报批。**MUST NOT** 通过扫描容器中 `ChatModel` 类型的 Bean 建立该映射（章程原则 III、FR-005）。构造分支须写成"逐条追加"形态，使 SC-001（新增厂商零代码）的兑现方式（[contracts/provider-config.md](contracts/provider-config.md) §6）成立
- [X] T014 [US1] 新建 `oryxos-provider/src/main/java/io/oryxlabs/oryxos/provider/ProviderService.java`：构造签名 `ProviderService(Map<String, ChatModel> providerMap, ToolSchemaAdapter adapter, LlmCallRecorder audit)`（与课件骨架同构）；实现 `ChatResponse chat(String sessionId, Profile profile, Prompt prompt)` —— 按 `profile` 点名的厂商名取模型，取不到抛 `ProviderNotFoundException`；发起一次同步阻塞调用；成功时 `audit.record(sessionId, provider, model, usage, true, null, durationMs)` 并输出一条结构化 JSON 日志（SLF4J，**MUST NOT** `System.out`）。**MUST NOT** 返回 `CompletableFuture` / `Flux`、**MUST NOT** 自建线程池
- [X] T015 [US1] 跑 `./mvnw -pl oryxos-provider -am test`，T011 两个用例转绿；红了当场修，不攒到最后

**Checkpoint**: US1 独立可用——路由不串台 + 未知名报错

---

## Phase 4: User Story 2 - 工具说明只翻译、不执行 (Priority: P1)

**Goal**: 把本次可用工具翻译成模型看得懂的格式随请求发出；框架自带的自动工具执行**关闭**；模型表达的工具调用意图原样交回上层，本模块执行次数为 0。

**Independent Test**: 构造一次携带工具说明的调用，捕获实际发给模型的请求，验证工具说明已按目标格式生成且字段与来源一一对齐；验证请求上自动执行开关为关闭；直接调用适配器产物的 `call()` 验证其抛错（不含执行逻辑）。

### Tests for User Story 2 ⚠️ 先写，确认变红

- [X] T016 [P] [US2] 新建 `oryxos-provider/src/test/java/io/oryxlabs/oryxos/provider/ToolSchemaAdapterTest.java`：
  - `translatesSchemaFieldsVerbatim` —— `@DisplayName("schema 字段一一对齐")`：构造带 name/description/inputSchema 的 `OryxTool`，断言翻译产物的 `getToolDefinition()` 三项逐字相同
  - `callbacksCarryNoExecution` —— `@DisplayName("只翻译：产物里不含任何执行逻辑")`：断言产物的 `call(...)` 抛 `UnsupportedOperationException`（[research.md](research.md) D5）
  - `emptyToolsYieldEmptyList` —— `@DisplayName("工具说明为空：返回空列表不报错")`
- [X] T017 [P] [US2] 在 `oryxos-provider/src/test/java/io/oryxlabs/oryxos/provider/ProviderServiceTest.java` 增加 `callWithToolSchema_disablesAutoExecution` —— `@DisplayName("带工具 schema 调用：请求里关闭了自动执行")`：用 `ArgumentCaptor<Prompt>` 捕获实际发出的请求，断言 `ToolCallingChatOptions.isInternalToolExecutionEnabled(captor.getValue().getOptions())` 为 `false`，**且**工具说明非空。**这是坑二的回归测试——任何把它改回开启的改动都必须让这条立刻变红，MUST NOT 删或放宽**

### Implementation for User Story 2

- [X] T018 [US2] 新建 `oryxos-provider/src/main/java/io/oryxlabs/oryxos/provider/ToolSchemaAdapter.java`：`List<ToolCallback> toToolCallbacks(List<OryxTool> tools)`，用 `DefaultToolDefinition.builder().name().description().inputSchema().build()` 逐项对应构造，再包成 `ToolCallback`；产物的 `call(String)` **直接抛 `UnsupportedOperationException`**。空输入返回空列表不报错
- [X] T019 [US2] 在 `ProviderService.chat` 中接线：把 `adapter.toToolCallbacks(...)` 的产物挂进 `ToolCallingChatOptions`，并显式 `internalToolExecutionEnabled(false)`（[research.md](research.md) D4，已核实 1.1.8 真实签名）；响应中的工具调用意图**原样返回**，本模块一次都不执行

**Checkpoint**: US1 + US2 各自独立可用——路由不串台 + 工具只翻译不执行

---

## Phase 5: User Story 3 - Profile 由 Agent 目录派生，配置驱动换模型 (Priority: P2)

**Goal**: 扫描 `.oryxos/agents/<name>/AGENT.md` 的 frontmatter 派生 `Profile`，启动时全量装载进内存索引；坏文件记错误日志但不阻断启动；引用未声明厂商的 Profile 判为不合法并跳过。

**Independent Test**: 在临时目录造若干合法 Agent 目录与一个坏文件，执行加载，验证合法 Profile 全字段解析正确、按 Agent 名可查、坏文件只记错误且其余照常装载；验证引用未声明厂商的 Profile 被判不合法。

### Tests for User Story 3 ⚠️ 先写，确认变红

- [X] T020 [US3] 新建 `oryxos-core/src/test/java/io/oryxlabs/oryxos/core/profile/ProfileLoaderTest.java`（在 JUnit `@TempDir` 内造 Agent 目录与 `AGENT.md`）：
  - `loadsAllFrontmatterFields` —— `@DisplayName("合法 frontmatter：全字段解析到位")`
  - `rejectsProviderNotInGlobalList` —— `@DisplayName("引用不存在的 provider：报错清晰")`
  - `brokenFileDoesNotBlockOthers` —— `@DisplayName("坏文件不阻断其余加载")`：一个坏文件 + 若干合法文件，断言合法者全部装载成功
  - `resolvesEnvPlaceholders` —— `@DisplayName("${ENV} 占位从环境变量解析")`
  - `missingAgentMdTreatedAsBroken` —— `@DisplayName("目录缺 AGENT.md：按坏文件处理")`
  - 每个用例都必须断言"其余 Agent 照常装载"或"错误消息指明具体是哪个 Agent"，**MUST NOT** 只断言抛不抛异常

### Implementation for User Story 3

- [X] T021 [US3] 新建 `oryxos-core/src/main/java/io/oryxlabs/oryxos/core/profile/ProfileLoader.java`：扫描 `.oryxos/agents/*/AGENT.md` → 解析 `---` 包围的 frontmatter（SnakeYAML，`oryxos-core` 已有依赖）→ 映射为 `Profile` → 校验（`provider.name` 必须在全局层清单中、`provider.model` 必须存在）→ 解析 `${ENV_VAR}` 占位（环境变量缺失时报错指明变量名，**MUST NOT** 回显任何凭证值）。任何单条失败**只记错误日志并跳过**，MUST NOT 中断加载（FR-002）
- [X] T022 [US3] 新建 `oryxos-core/src/main/java/io/oryxlabs/oryxos/core/profile/ProfileRegistry.java`：`Map<String, Profile>` 内存索引，按 Agent 名查找。`oryxos-core` **MUST NOT** 反向依赖任何能力模块（章程附加约束）
- [X] T023 [US3] 跑 `./mvnw -pl oryxos-core -am test`，T020 全绿

**Checkpoint**: US1 / US2 / US3 三者均可独立验证

---

## Phase 6: User Story 4 - 每次调用可审计：成败都留痕 (Priority: P2)

**Goal**: 不论成功还是失败，每次 LLM 调用都落一条 `llm_calls` 记录，按会话关联；失败记录带 `success=false` 与原因，且**先落账再上抛异常**。

**Independent Test**: 造一次成功的调用与一次抛异常的调用，验证两次都产生审计记录、失败那条 `success=false` 且带原因；用**手工建表脚本**建出的真实 SQLite 库验证 `success`/`error_message` 两列真实存在、能存能读。

### Tests for User Story 4 ⚠️ 先写，确认变红

- [X] T024 [P] [US4] 在 `oryxos-provider/src/test/java/io/oryxlabs/oryxos/provider/ProviderServiceTest.java` 增加 `recordsAuditBeforeRethrowingOnFailure` —— `@DisplayName("调用失败：审计必须留下 success 为 false 的记录")`。断言：异常**继续上抛**（`assertThrows`）**并且** `verify(audit).record(eq("s-1"), eq("deepseek"), any(), isNull(), eq(false), contains("timeout"), anyLong())`——注意断言的不是"没抛异常"，而是"抛了异常**并且**审计先落了账"，**MUST NOT** 删或弱化
- [X] T025 [P] [US4] 新建 `oryxos-storage/src/test/java/io/oryxlabs/oryxos/storage/repository/LlmCallRepositoryTest.java`：用**真实 SQLite 临时库**，**先执行手工建表脚本 `db/schema.sql` 再存取**（`@AutoConfigureTestDatabase(replace = NONE)` + 用 `@DynamicPropertySource` 把 `spring.datasource.url` 指向 JUnit `@TempDir` 下的 `.db` 文件，[research.md](research.md) D8）。断言写入并读回一条记录，`success` 与 `errorMessage` 两列真实存在、读写一致。**MUST NOT** 改用 H2 或让 Hibernate 自动建表

### Implementation for User Story 4

- [X] T026 [US4] 新建 `oryxos-provider/src/main/java/io/oryxlabs/oryxos/provider/JpaLlmCallRecorder.java`：实现 `LlmCallRecorder`，把 `Usage` 摊平为 `promptTokens`/`completionTokens`/`totalTokens`（`usage` 为 `null` 时三列留空，**不整条丢弃**），`createdAt` 取当前时间，包装 `oryxos-storage` 的 `LlmCallRepository`
- [X] T027 [US4] 在 `ProviderService.chat` 补失败路径：`catch (RuntimeException e)` 中**先** `audit.record(sessionId, provider, model, null, false, e.getMessage(), durationMs)` 并输出一条结构化 JSON 日志（成功与失败适用同一套脱敏规则，FR-013），**再** `throw e`。**MUST NOT** 吞异常、**MUST NOT** 只记成功

**Checkpoint**: 四个 user story 全部独立可用

---

## Phase 7: Polish & Cross-Cutting Concerns

- [X] T028 [P] 新建 `oryxos-provider/src/test/java/io/oryxlabs/oryxos/provider/ProviderSmokeIT.java`：`@Tag("integration")`，读环境变量真 key、真调一次、断言拿到非空响应**且** `llm_calls` 多了一条 `success = true` 的记录。CI 默认跳过（不能让外部 API 的可用性变成自己流水线的可用性）
- [X] T029 [P] 日志脱敏核对（FR-013 / SC-007）：确认 `ProviderService` 成功与失败两条路径各输出一条结构化 JSON 日志，且日志与落库适用同一套敏感字段脱敏规则；确认无 `System.out`
- [X] T030 架构约束 grep 自查（机器可判）：
  - `grep -rn "ChatModel.class\|Map<String, ChatModel>" --include="*.java" oryxos-provider/src/main` → 仅 `ProviderConfiguration` 的显式构造处可命中，**不得**出现按类型扫描容器 Bean 的写法
  - `grep -rn "Flux\|Mono\|CompletableFuture\|Executors\." --include="*.java" oryxos-provider/src/main oryxos-core/src/main` → **无输出**
  - `grep -rn "sk-" --include="*.java" --include="*.yaml" --include="*.yml" . | grep -v "^./docs/"` → **无输出**
  - 确认 `oryxos-core` 的 pom 未新增对 `oryxos-provider` / `oryxos-tool` / `oryxos-memory` 的依赖（依赖方向）
- [X] T030a 结构性核对 SC-006（转发开销 ≤ 50ms，**本节以结构断言兑现，不建基准测试**）：读 `ProviderService.chat` 调用路径，确认 ① 无额外线程切换、无额外网络跳（仅一次 `ChatModel.call`）② 厂商映射为 `Map` O(1) 查表、不随并存厂商数量线性增长 ③ 无自建线程池
- [X] T031 全量门禁：`./mvnw clean verify` 全绿（含 Spotless / P3C / Checkstyle / SpotBugs+FindSecBugs / OWASP Dependency-Check）。改了代码先跑 `./mvnw spotless:apply`
- [X] T032 按 [quickstart.md](quickstart.md) 逐场景走查，并整理六项证据 DoD 素材（前序节测试回归绿、H4 六条不变量逐条自查）

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 无依赖，可立即开始
- **Foundational (Phase 2)**: 依赖 Setup 完成——**阻塞全部 user story**
- **User Stories (Phase 3+)**: 均依赖 Foundational 完成
  - US1 与 US2 同为 P1，但 US2 的实现改动 `ProviderService.chat`（与 US1 同文件），**串行执行**更稳
  - US3 只动 `oryxos-core`，与 US1/US2 无文件交集，**可并行**
  - US4 依赖 US1 的 `ProviderService` 骨架
- **Polish (Phase 7)**: 依赖全部 story 完成

### User Story Dependencies

- **US1 (P1)**: Foundational 后可开始，无其他 story 依赖
- **US2 (P1)**: 依赖 US1 的 `ProviderService` 骨架（T014）；测试任务 T016 可独立先行
- **US3 (P2)**: Foundational 后可开始，**与 US1/US2 完全并行**
- **US4 (P2)**: 依赖 US1 的 `ProviderService`（T014）与 Foundational 的 `LlmCallRecorder` / `llm_calls` 两列

### Within Each User Story

- 测试先写并确认变红，再实现
- 实体 / 异常 / 接口 → 服务 → 接线
- 每个 story 收尾即跑该模块测试，红了当场修，不攒到最后

### Parallel Opportunities

- T002 / T003 可并行（不同 pom 与目录）
- T005 / T006 / T007 / T008 四条可并行（四个不同文件，分属三个模块）
- T011 与 T016 可并行（不同测试文件）
- T024 / T025 可并行（不同模块的测试文件）
- T028 / T029 可并行
- **US3 全阶段可与 US1 / US2 并行**（只在 `oryxos-core` 内工作）

---

## Parallel Example: Foundational 阶段

```bash
# 四个不同文件，可同时开工：
Task: "T005 新建 Profile 记录类 in oryxos-core/.../core/profile/Profile.java"
Task: "T006 LlmCall 实体补 success / errorMessage in oryxos-storage/.../entity/LlmCall.java"
Task: "T007 schema.sql 的 llm_calls 补两列 in oryxos-storage/src/main/resources/db/schema.sql"
Task: "T008 新建 LlmCallRecorder 接口 in oryxos-provider/.../provider/LlmCallRecorder.java"
```

## Parallel Example: US1 与 US3 并行

```bash
# 两条线无文件交集：
Task: "T011-T015 [US1] ProviderService 路由 in oryxos-provider/"
Task: "T020-T023 [US3] ProfileLoader + ProfileRegistry in oryxos-core/"
```

---

## Implementation Strategy

### MVP First

1. 完成 Phase 1 Setup + Phase 2 Foundational（**关键**——阻塞全部 story）
2. 完成 Phase 3 US1 → 独立验证 → 这就是本节最小可交付切片（能稳定调通一次）
3. **STOP and VALIDATE**：`./mvnw -pl oryxos-provider -am test`

> **单模块命令一律带 `-am`**：`-pl` 只把点名的模块放进反应堆，兄弟模块会从 `~/.m2` 解析。只要本地跑过一次 `mvn install`，那里就躺着旧字节码，改过兄弟模块的 API 后单模块命令会静默取到旧版本。本节实施期实测踩过：`LlmCall()` 放宽可见性后冒烟报 `IllegalAccessError`，而全量 `mvn clean verify` 因为走反应堆反而看不见这个坑。

### Incremental Delivery

1. Setup + Foundational → 地基就位
2. US1（路由）→ 独立验证
3. US2（工具只翻译）→ 独立验证
4. US3（Profile 派生）→ 独立验证
5. US4（审计落账）→ 独立验证
6. Polish（冒烟 + 全量门禁 + quickstart 走查）

每个 story 增量交付，不破坏前序 story。本节全部完成后，本节才能支撑第 17 节 ReAct 循环的下游消费。

---

## Notes

- [P] 任务 = 不同文件、无依赖
- 每个 user story 应能独立完成与验证
- 实现前先确认测试变红
- **FR-012（按会话关联、倒序查询）无对应任务**——骨架期既有的 `LlmCallRepository.findBySessionIdOrderByCreatedAtDesc(String)` 已满足，本节不改该接口（见 [data-model.md](data-model.md) §3「查询」）。这是**正确地不做**，不是遗漏
- **SC-006（转发开销 ≤ 50ms）不建基准测试**，由 T030a 的结构性核对兑现——核心阶段引入 JMH 之类的基准设施属 YAGNI（章程原则 II）
- **原则 X「每个 user story 有可演示成果」在本节的形态是测试而非交互 Demo**：Provider 没有独立入口，要与第 17 节的 ReAct 循环一起才撑起 Demo 一。本节端到端证据是 `ProviderSmokeIT`（见 [quickstart.md](quickstart.md) §5）
- **全程不自动 commit / push / 运行 `package.sh`**——同步时机由用户决定
- 本节完成后必须跑 `/speckit-analyze`（`/oryxos-lesson-dev` 硬性要求）
