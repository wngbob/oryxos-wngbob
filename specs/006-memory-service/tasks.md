# Tasks: Memory —— 给 Agent 装上记得住事的仓库

**Feature**: `specs/006-memory-service` | **Branch**: `class-22` | **Date**: 2026-10-15

**Input**: Design documents from `specs/006-memory-service/`

**Prerequisites**: [plan.md](plan.md) / [spec.md](spec.md) / [research.md](research.md) / [data-model.md](data-model.md) / [contracts/](contracts/) / [quickstart.md](quickstart.md)

**测试纪律**：课件「验收 harness」里**写出代码**的两个参数化回归测试 MUST 原样落地（断言逻辑逐条保真；方法名译成英文，课件中文原名进 `@DisplayName`）。每个 user story 的测试任务**先于或伴随**对应实现任务。测试方法名一律英文（驼峰 / snake_case），MUST NOT 用中文方法名。

**路径约定**：以下 `…/memory/` = `oryxos-memory/src/main/java/io/oryxlabs/oryxos/memory/`；`…/storage/` = `oryxos-storage/src/main/java/io/oryxlabs/oryxos/storage/`；`…/cli/` = `oryxos-cli/src/main/java/io/oryxlabs/oryxos/cli/`；测试同构于各模块的 `src/test/java/`。**`oryxos-core/` 本节一行不改**（FR-025）。

---

## Phase 1: Setup（编译与依赖前置）

**Goal**: 让 `oryxos-memory` 编得过、跑得起测试、配置与建表脚本就位。没有这一步，US-1~US-4 全部开不了工。

- [X] T001 [P] `oryxos-memory/pom.xml` 加 4 条 **compile** 依赖边 + 1 条 test 依赖：`oryxos-storage`（`MemoryEntryRepository` 类型）、`org.springframework.ai:spring-ai-model`（`@Tool` / `@ToolParam`）、`org.springframework:spring-web`（`RestClient`）、`com.fasterxml.jackson.core:jackson-databind`（Mem0 报文）→ `oryxos-memory/pom.xml`。版本由 Spring Boot BOM 管，**不写 `version`**；每条按 `oryxos-cli` 的既有口径加一行注释说明「直接 import 的类型就该有直接的依赖边，发行物不新增任何工件」
- [X] T002 [P] `oryxos-boot/src/main/resources/application.yaml` 的 `oryxos:` 根块下加 `memory:` 子块——`backend: markdown` / `mem0.base-url: ${MEM0_BASE_URL:}` / `mem0.user-id: ${MEM0_USER_ID:oryxos}`，逐字照 [contracts/memory-config.md](contracts/memory-config.md) §1。**`backend` 是字面量、MUST NOT 挂环境变量占位**（切档走配置文件或 `-Doryxos.memory.backend=`，见 [quickstart.md](quickstart.md) §3；自造一个 `ORYXOS_MEMORY_BACKEND` 等于凭空多一个对外概念）。**两个 mem0 子键都带默认值**（markdown / sqlite 两档根本不读它们，缺失不该让启动失败）；**环境变量名不带 `oryxos.` 前缀**（`MEM0_BASE_URL` / `MEM0_USER_ID` 是外部系统既有命名）。配置文件的唯一落点是本文件——`oryxos-cli` 的 pom 明令 MUST NOT 放第二份
- [X] T003 [P] `oryxos-storage/src/main/resources/db/schema.sql` 末尾追加 `memory_entries` 表 + `idx_memory_scope` 索引（DDL 逐字取自 [data-model.md](data-model.md) §1）。**注意该文件头注释的硬约束：任何注释都 MUST NOT 含分号字符**
- [X] T004 `oryxos-memory/src/test/java/io/oryxlabs/oryxos/memory/MemoryTestApplication.java`：`@SpringBootConfiguration` + `@EnableAutoConfiguration` 的空类（照 `oryxos-storage` 的 `StorageTestApplication` 同款）——`@DataJpaTest` 需要一个 `@SpringBootConfiguration` 落脚点，而 `oryxos-memory` 此前零测试基建

**Checkpoint**: `./mvnw -pl oryxos-memory -am test-compile` 过；`sqlite3` 能对建表脚本跑出 `memory_entries`。

---

## Phase 2: Foundational（所有 user story 的阻塞前置）

**Goal**: `memory_entries` 的实体与查询就位。契约测试的 SQLite 那一档、以及 US-3 的整档实现都压在它上面。

- [X] T005 `oryxos-storage` 新增 `entity/MemoryEntry.java`：`@Entity @Table(name = "memory_entries")`，字段 `id`（`@Id @GeneratedValue(strategy = IDENTITY)`）/ `scope`（String，映射 `scope VARCHAR(16)`）/ `content`（String）/ `createdAt`（`Instant`，映射 `created_at`）。**不写 `@Data`**——按 `oryxos-storage` 既有实体的写法
- [X] T006 `oryxos-storage` 新增 `repository/MemoryEntryRepository.java`：`extends JpaRepository<MemoryEntry, Long>` + 三个查询（`findByScopeOrderByIdAsc` / `findByScopeOrderByIdDesc(String, Pageable)` / `@Query searchArchival`），签名逐字取自 [data-model.md](data-model.md) §2。`searchArchival` 用 `LOWER(m.content) LIKE LOWER(:pattern)`——**不区分大小写跨档统一**（FR-008 / D10）
- [X] T007 `oryxos-storage` 新增 `repository/MemoryEntryRepositoryTest.java`（**伴随 T005 / T006**）：照 `NotifyChannelRepositoryTest` 的定式——`@DataJpaTest` + `@AutoConfigureTestDatabase(replace = Replace.NONE)` + `@Transactional(propagation = Propagation.NOT_SUPPORTED)` + `@TempDir static Path` + `@DynamicPropertySource`（SQLite 驱动 / `SQLiteDialect` / `ddl-auto=none`）+ `@BeforeAll` 手工跑 `schema.sql`。断言：① 手工脚本建出的表**列齐备**（`PRAGMA table_info`，含 `scope` / `content` / `created_at` 且 `scope` NOT NULL）② 索引 `idx_memory_scope` 存在 ③ 三个查询的语义（升序 / 降序分页 / 只命中 ARCHIVAL 且大小写不敏感）

**Checkpoint**: `./mvnw -pl oryxos-storage -am test -Dtest=MemoryEntryRepositoryTest` 绿。

---

## Phase 3: User Story 1 — Agent 记一条、下一轮就想得起（Priority: P1）

**Goal**: 门面 + 默认档（Markdown）落地：写入即生效（契约一）、核心区始终在场（契约二）、scope 由调用方显式指定（契约三）。

**Independent Test**: 纯单测可判定，用 `@TempDir`——① 记一条核心、一条归档，立刻 `load()`，断言两条都在；② 灌 500 条归档后再 `load()`，断言核心那条**一字不少**、最早的归档被裁掉、最近的归档还在；③ `save_memory` 不带 scope 时落进归档区。全程不碰网络、不碰真模型。

### 测试（先行）

- [X] T008 [US1] `…/memory/store/MemoryStoreContractTest.java`：`@ParameterizedTest` + `@MethodSource("allStores")`（**此刻只返回 Markdown 一档**，US-3 再扩到三档）+ 两条关键回归点——`truncation_onlyTrimsArchival_coreMemorySurvivesIntact`（`@DisplayName("截断只裁归档区_核心记忆一字不能少")`）与 `append_isImmediatelyVisible_noCaching`（`@DisplayName("写入后立刻可读_不允许有缓存")`），断言逐条照课件（写 1 条核心 + 500 条归档 → `load()` 含核心、**不含** `归档流水 0`、**含** `归档流水 499`）；另加契约三断言（写 `CORE` 只在核心区、写 `ARCHIVAL` 只在归档区）。测试方法名英文
- [X] T009 [P] [US1] `…/memory/store/MarkdownMemoryStoreTest.java`：截断边界（**刚好等于阈值不截断**、超阈值取尾部）/ 两个 header 的解析 / `MEMORY.md` 不存在当空记忆 / **两个区都空时 `load()` 返回 `""`**（D6，MUST NOT 返回只有 header 的残渣）/ **两个 header 都缺时 MUST NOT 把整份文件当核心记忆**（Edge Case 第 2 条）/ 条目行带 `- [YYYY-MM-DD]` 日期标记（**只到日**）
- [X] T010 [P] [US1] `…/memory/builtin/MemoryToolsTest.java`（**先只写 save 部分**，recall 部分归 US-4）：`scope` 缺省（`null` / 空白）落**归档区**；`"core"` 小写能解析成 `CORE`；非法 scope（`"CORE "` 带尾空格、`"important"`）**抛 `IllegalArgumentException`** 且消息点名合法取值（D4）；返回类型是 `ToolResult` 且成功文案是 `"已记住"`
- [X] T011 [P] [US1] `…/memory/MemoryServiceImplTest.java`（**先只写门面语义部分**）：`remember` 转发给 `store.append`；`remember(content, null)` 抛 `NullPointerException`（`Objects.requireNonNull`——缺省归档的落点在 `MemoryTools`，门面不猜）

### 实现

- [X] T012 [US1] `…/memory/store/MarkdownMemoryStore.java`：`public MarkdownMemoryStore(Path oryxosRoot)`，构造时解析 `oryxosRoot.resolve("memory").resolve("MEMORY.md")`。`append` 读全文 → 在目标 header 段尾追加 `- [YYYY-MM-DD] <content>` → 写回；`load()` **每次重新读文件**（契约一，MUST NOT 缓存）→ 抽两个 header 段 → 核心区全量、归档区超 4000 字符取尾部 → 拼回 `## 核心记忆\n<core>\n## 归档记忆\n<archive>`；**两个区都空则返回 `""`**；`recallByKeyword` 行扫归档段 + 大小写归一（`contains`，MUST NOT 正则 / 分词 / 向量）；`capabilities()` 恒返回 `KEYWORD`；`archivalEntries()` 返回归档段条目行。文件不存在时当空记忆处理（MUST NOT 报错）。**MUST NOT 调 `Sandbox.enforce`**（FR-021 / 仲裁 3）
- [X] T013 [US1] `…/memory/MemoryService.java`：`public interface MemoryService extends MemorySource`，两个方法 `void remember(String content, MemoryScope scope)` / `List<String> recall(String keyword)`（`longTermMemory` 从 `MemorySource` 继承）。javadoc 写清「三类操作」与「上层 MUST NOT 感知后端档位」
- [X] T014 [US1] `…/memory/MemoryServiceImpl.java`：唯一实现，持有 `LongTermMemoryStore`。`remember` → `Objects.requireNonNull(scope)` + `store.append`；`recall` → **按 `store.capabilities()` 路由**，**三个分支一次写全**（`KEYWORD` / `DELEGATED` → `recallByKeyword`；`HYBRID_BUILTIN` → **抛异常**）——MUST NOT 只写两个分支留 `HYBRID_BUILTIN` 落进默认分支：冒号 `switch` 下那会**静默降级成关键词检索**，正是 FR-010 / 仲裁 5 明令禁止的形态（静默降级会让运维以为「语义检索已生效」）；`longTermMemory(Profile)` 先返回 `store.load()` 包成 `Optional`（空值语义归 US-2）
- [X] T015 [US1] `…/memory/builtin/MemoryTools.java`：`@Tool(name = "save_memory", description = "记住一件值得长期记住的事")` + `@ToolParam` 两个参数——`content` 必填、`scope` **必须**带 `required = false`（D5：Spring AI 1.1.8 的 `JsonSchemaGenerator` 对无注解参数一律判 required，不声明则「缺省归档」在模型可见层不成立）。scope 解析：`null` / 空白 → `ARCHIVAL`，否则 `MemoryScope.valueOf(scope.toUpperCase(Locale.ROOT))`——**MUST NOT `trim()`**（`"CORE "` 是非法值）。返回 `ToolResult.success("已记住")`（D3：返回 `ToolResult` 而非 `String`，否则 `retryable` 丢失）

**Checkpoint**: `./mvnw -pl oryxos-memory -am test` 绿；契约测试两条关键回归点对 Markdown 档全过。

---

## Phase 4: User Story 2 — 跨会话不必重复自我介绍（Priority: P1）

**Goal**: 长期记忆经既有 core 接缝被 `PromptBuilder` 取用，作为 prompt 四部分里的第二部分；为空时整条不出现，不塞占位文本。

**Independent Test**: 单测可判定——临时目录写一份 `MEMORY.md`，构造接缝实现后断言取到的文本含核心区内容；再把 `MEMORY.md` 置空 / 删掉 / 改成两个 header 都缺，断言返回**空**。与 `oryxos-core` 既有的 `PromptBuilder` 四部分测试**组合起来**即覆盖「两者一起注入 system prompt」。

### 测试（先行）

- [X] T016 [US2] `…/memory/MemoryServiceImplTest.java` **补**：① `longTermMemory` 在三种「无记忆」情形下返回 `Optional.empty()`——文件不存在 / 两个区都空 / 两个 header 都缺；② 有核心记忆时返回的文本含核心区内容且**非空**；③ **组合断言**——用真接缝构造 `PromptBuilder`，断言长期记忆作为**独立的第二条 system 消息**出现；接缝返回空时该条**整条不出现**（`PromptBuilder` 已有 `.filter(memory -> !memory.isBlank())`，本节不新增判断逻辑）；④ 换一个全新 `Session` 组装，长期记忆**照旧出现**（跨会话）

### 实现

- [X] T017 [US2] `…/memory/MemoryServiceImpl.java` 补 `longTermMemory(Profile)` 的空值语义：`store.load()` 为空白 → `Optional.empty()`（让 `PromptBuilder` 整条跳过，FR-014 / SC-007）。javadoc 写明 **`profile` 参数本节不使用**（全局单份，FR-023 / 仲裁 4）与「MUST NOT 混入会话历史」（FR-015）。**`oryxos-core` 一行不改**——`MemorySource` 只被实现、不被修改

**Checkpoint**: 「第二部分为空则整条不出现」有单测钉死；`git diff --stat -- oryxos-core/` 为空。

---

## Phase 5: User Story 3 — 换后端只改一行配置（Priority: P1）

**Goal**: 三档实现（+ 测试替身）落地，**同一套契约断言对三档统一跑**；装配处按 `oryxos.memory.backend` 选档，换后端上层零改动。

**Independent Test**: 把三档实现（第三档即**内存假 Mem0** 替身）放进同一个参数化契约测试的输入源，四组断言对每一档全过；各档另有专属测试覆盖特有行为（字符串截断边界 / `LIMIT` 生效 / REST 请求体）。全程不碰网络。

### 测试（先行）

- [X] T018 [P] [US3] `…/memory/store/SqliteMemoryStoreTest.java`：**手工建表脚本能建能读**（`@DataJpaTest` + `@TempDir`，照 T007 定式）/ 归档 `LIMIT` 生效（灌 101 条，`load()` 只含最近 100 条）/ `LIKE` 检索命中且**大小写不敏感** / 核心区查询**不带 LIMIT** / 表不存在时把底层报错包成带「请先运行 `oryxos init`」指引的明确消息。**上游同节漏做本类，本仓按课件补上**（D12）
- [X] T019 [P] [US3] `…/memory/store/Mem0MemoryStoreTest.java`：`MockRestServiceServer.bindTo(restClientBuilder)`（**spring-test 既有能力，不引 okhttp / mockwebserver3**）——① `append` 发出的**请求体带 scope**；② `recall` 转发查询且按 metadata 过滤；③ 服务不可达时**以明确错误上抛**，MUST NOT 静默返回空（Edge Case）；④ `toString()` MUST NOT 含 `baseUrl`（FR-019）。**不碰真 server**
- [X] T020 [US3] `…/memory/store/MemoryStoreContractTest.java` **补**：`allStores()` 的 `@MethodSource` 扩到**三档**——`MarkdownMemoryStore` / `SqliteMemoryStore` / `InMemoryMemoryStore`，**第三档即课件的「内存假 Mem0」替身**（在 `@MethodSource` 的 javadoc 里写明这一点：契约测的是「Mem0 这一档有没有守规矩」，真实 REST 报文由 `Mem0MemoryStoreTest` 用 mock 单独验证，两者分工明确、不互相替代）。`Mem0MemoryStore` **真类不进参数化源**——`MockRestServiceServer` 是单测级、有严格期望序列，不适合当契约测试的参数。另加一条完整性断言：`allStores()` 返回**恰好 3 个**实现且每档有可读的名字（`Named` 或参数名，保证「一眼看出是哪一档破的规矩」，SC-003 / FR-024）
- [X] T021 [US3] `…/cli/MemoryAssemblyTest.java`：**起真上下文**（照 `OryxToolContractTest` 的口径，装配后的真实上下文只有 `oryxos-cli` 看得见）——① 让位机制生效：容器里**恰好一个** `MemorySource` Bean，且是 `MemoryServiceImpl`（`EmptyMemorySource` 自动让位，仲裁 9）；② `oryxos.memory.backend` 三个取值各造出正确类型；③ 未知取值（如 `postgres`）**报错且列出合法取值**，MUST NOT 静默退回默认档（FR-016）；④ **`backend=mem0` 但 `mem0.base-url` 为空时报错**，且**异常消息含变量名 `MEM0_BASE_URL`、不含任何地址值**（FR-018 / FR-019）；⑤ `InMemoryMemoryStore` **选不中**（不在取值里、无 `@Bean` 声明它）；⑥ `memoryService()` 声明在 `memorySource()` **之前**（`@ConditionalOnMissingBean` 按 `@Bean` 声明顺序求值——顺序反了容器里会出现两个 `MemorySource`，这个错误只在重命令起上下文时才炸）

### 实现

- [X] T022 [P] [US3] `…/memory/store/SqliteMemoryStore.java`：构造器收 `MemoryEntryRepository`。`append` → `save(new MemoryEntry(...))`；`load()` **每次查库**（天然满足契约一）→ 核心区 `findByScopeOrderByIdAsc("CORE")` **全量**（**不带 LIMIT**，契约二）→ 归档区 `findByScopeOrderByIdDesc("ARCHIVAL", PageRequest.of(0, 100))` 后 `.reversed()` → 拼回两个 header；**两区都空返回 `""`**；`recallByKeyword` → `searchArchival("%" + keyword + "%")`（**检索覆盖归档区全量，不受 `load()` 截断影响**，FR-009）；`capabilities()` → `KEYWORD`；`archivalEntries()` → 归档区正文列表
- [X] T023 [P] [US3] `…/memory/store/Mem0MemoryStore.java`：**双构造器**（D9）——`public Mem0MemoryStore(String baseUrl, String userId)`（自建带超时的 `RestClient`）+ 包级私有 `Mem0MemoryStore(RestClient restClient, String userId)`（测试接缝；`MockRestServiceServer.bindTo(builder)` 与 `.build()` 必须是同一个 builder 实例，实现若自己装 `requestFactory` 会覆盖 mock 的请求工厂 → mock 静默失效）。`append` POST 带 scope；`load()` **每次调 API**；`recallByKeyword` 转发 Mem0 自带 `search`；`capabilities()` → `DELEGATED`；`archivalEntries()` → **空列表**（FR-011）。**MUST NOT 自动生成带 `baseUrl` 的 `toString`**（不写 `@Data` / `@ToString`，FR-019）
- [X] T024 [P] [US3] `…/memory/store/InMemoryMemoryStore.java`：**public 类**（D11，对齐上游；课件交付物清单里没有它，属**已报批**的对外概念）——进程内两个 `List`，`append` / `load` / `recallByKeyword` / `archivalEntries` 按契约实现，`capabilities()` → `KEYWORD`。**它是测试期替身，不是第四个可选后端档**：不进 `oryxos.memory.backend` 取值、没有任何 `@Bean` 声明它。类 javadoc 必须写明这一点，否则将来会被误当成一个后端档
- [X] T025 [US3] `…/cli/CliAssemblyConfiguration.java` **改**：新增 `@Bean MemoryService memoryService(...)`——按 `oryxos.memory.backend` 造档（**经典冒号 `switch`**，不用箭头 switch / switch 表达式，避开 PMD 6.55 解析不了的形态；未知取值走 FR-016 报错并列出合法取值）；`mem0` 分支**先做一次显式校验**——`base-url` 为空白则**抛异常**，消息**只带变量名**（`长期记忆后端 mem0 需要环境变量 MEM0_BASE_URL，当前未设置`），MUST NOT 把未解析的占位符当值发出去（FR-018；`${MEM0_BASE_URL:}` 带空默认值，配置侧永远不会失败，**这条校验是唯一的拦截点**）。**该方法 MUST 声明在 `memorySource()` 之前**；新增 `@Bean MemoryTools memoryTools(MemoryService)`；`toolRegistry(...)` 加一个参数并在内置工具那一段的**末尾、`connectAll(registry)` 之前**加 `registry.registerAnnotated(memoryTools, "builtin")`（保持「先内置、后 MCP」的既有顺序）。**`EmptyMemorySource` 一行不改**（MUST NOT 删、MUST NOT 加 `@Primary`）

**Checkpoint**: `./mvnw -pl oryxos-memory -am test` 三档契约测试全过；`oryxos tool list` 出 `builtin (15)`；三档切换冒烟（[quickstart.md](quickstart.md) §3）同一段对话体感一致。

---

## Phase 6: User Story 4 — 按关键词查归档区：未命中不报错（Priority: P2）

**Goal**: 检索只搜归档区（契约四）、跨档统一不区分大小写、未命中返回明确文案而不中断对话。

**Independent Test**: 往归档区灌几条内容，按关键词查命中 / 不命中 / 大小写不同的关键词各断言一次；核心区的条目**永远不出现在检索结果里**；未命中时 `recall_memory` 返回明确的「没有找到相关记忆」文案，MUST NOT 抛异常。

### 测试（先行）

- [X] T026 [US4] `…/memory/store/MemoryStoreContractTest.java` **补契约四断言**：核心区内容 `recallByKeyword` **搜不到**（SC-005）；检索**大小写不敏感**（同一关键词换大小写各命中一次，FR-008 / D10）；**归档区被 `load()` 裁掉的内容仍能被检索到**（FR-009，截断只决定「注入上下文的那一份」）；未命中返回**空列表**（不抛异常、不返回 `null`）；`capabilities()` 对同一实例**恒定**且取值在三态之内；**`archivalEntries()` 返回归档区条目、且与 `load()` 的截断无关**（FR-011——`DELEGATED` 档返回空列表这条由 `Mem0MemoryStoreTest` 单独钉，因为替身档的 `capabilities()` 是 `KEYWORD`）
- [X] T027 [P] [US4] `…/memory/builtin/MemoryToolsTest.java` **补 recall 部分**：命中时返回 `ToolResult.success`，内容为命中条目的拼接；**未命中返回 `"没有找到相关记忆"` 且 MUST NOT 抛异常**（FR-013，文案**逐字钉死**，测试断言这个字面量）；`recallMemory` 的 `@Tool(name = "recall_memory")` 显式钉名
- [X] T028 [P] [US4] `…/memory/MemoryServiceImplTest.java` **补**：`capabilities()` 返回 `HYBRID_BUILTIN` 时门面 `recall` **抛异常**且消息点名「核心阶段无实现方」——MUST NOT 静默降级成关键词检索（FR-010 / 仲裁 5；静默降级会让运维以为「语义检索已生效」）

### 实现

- [X] T029 [US4] `…/memory/builtin/MemoryTools.java` **补** `recallMemory`：`@Tool(name = "recall_memory", description = "按关键词检索长期记忆")` + `@ToolParam` 必填 `keyword`；命中 `ToolResult.success(String.join("\n", hits))`，未命中 `ToolResult.success("没有找到相关记忆")`（**MUST NOT 抛异常**）
- [X] T030 [US4] **确认**（非新增）`…/memory/MemoryServiceImpl.java` 的 `recall` 路由三个分支齐备、`HYBRID_BUILTIN` 抛异常且消息说明它需要什么（内置向量检索路径）——该分支**已在 T014 一次写全**，本任务只做核对与措辞收口，由 T028 的测试守住。若发现 T014 漏了分支，回到 T014 补齐（**MUST NOT** 在本任务里改成静默降级）

**Checkpoint**: 三档契约测试含契约四全过；`recall_memory` 未命中文案逐字钉死。

---

## Phase 7: Polish & 跨切面

- [X] T031 `./mvnw spotless:apply` 后 `./mvnw clean verify` 全绿（含 P3C / Checkstyle / SpotBugs + FindSecBugs / OWASP）。**349 条旧测试一条不许红**——尤其 `OryxToolContractTest`（它用的是 `hasSizeGreaterThanOrEqualTo(13)` 下限断言，工具数 13 → 15 **不需要改**，第 20 节交付时就是这么写的）
- [X] T032 [P] 依赖方向自查（[quickstart.md](quickstart.md) §8 的 7 条 grep）：`oryxos-core/` diff 为空（FR-025）/ `oryxos-storage` 不依赖 `oryxos-memory`（不成环）/ `oryxos-memory` **不依赖** `oryxos-tool`（FR-021 的连带）/ 两个工具名**没有渗进** `ToolRegistry` / `AnnotatedToolAdapter` / `ToolExecutor` 三处横切（FR-012 / SC-008）/ `oryxos-memory` 无 `Sandbox` / `enforce` 命中（仲裁 3）/ 无 `embedding` / `milvus` / `qdrant` / `CompletableFuture` / `reactor` / `Executors.` 命中（FR-025 + 章程原则 8）/ `baseUrl` 不出现在日志与 `toString`
- [X] T033 [P] H4 六条全局不变量逐条自查：① 涉外 IO 首行过 `Sandbox.enforce`——**本节记忆文件 IO 不过沙箱**（仲裁 3，已裁决的例外，需在报告里写明理由而非记成漏项）② LLM 调用 / 工具执行成败都落审计（本节无新审计路径，`MemoryTools` 复用第 20 节的既有路径）③ 无明文 key（FR-018 / FR-019）④ `session_id` 只在 `SessionManager` 内拼接（本节不碰）⑤ 无 Reactor / `CompletableFuture` / 自建线程池 ⑥ 无 Spring AI 自动工具执行路径
- [X] T034 [P] `USER.md` 只读核对（FR-022 / SC-011）：`grep -rn "USER.md" --include=*.java` 逐条确认**没有任何写入路径**；`MEMORY.md` 的角色是「Agent 写、OryxOS 读写」，两者分清
- [X] T035 课件「本节交付物」逐项存在性核对：`MemoryService` + `MemoryServiceImpl` / `MemoryScope`（**骨架期已有，`git diff` 为空**）/ `LongTermMemoryStore`（**同上**）/ `MarkdownMemoryStore` + `SqliteMemoryStore` + `Mem0MemoryStore` / `MemoryTools` / 六个测试类（课件写 `MemoryServiceTest`，本仓落 `MemoryServiceImplTest`——同一件事）/ `memory_entries` 表 / `.oryxos/memory/MEMORY.md` 两区块（**`oryxos init` 第 18 节起就已按两个 header 建好，本节零改动**，只核对 `InitCommand.MEMORY_MD` 常量与契约一致）/ `oryxos.memory.backend` 配置键 / 集成点（课件写 `buildContext(session)`，本仓落 `MemorySource.longTermMemory(profile)`，**已报批的偏离**，进验收报告）
- [X] T036 在 `CLAUDE.md` 记明本节状态：第 22 节交付内容、`oryxos tool list` 变 `builtin (15)`、两个挂账已清（Memory 侧空接缝让位、`save_memory` / `recall_memory` 两个内置工具归位）、`InMemoryMemoryStore` 属测试替身不可被选中
- [X] T037 落盘 `specs/006-memory-service/acceptance-report.md`（六项证据 + 变更总结三段 + 剩余人工项 + 备注），并在对话里**完整输出一遍报告正文**

---

## Dependencies（user story 完成顺序）

```text
Phase 1 Setup ──→ Phase 2 Foundational ──→ Phase 3 US-1 ──┬──→ Phase 4 US-2
                                                          └──→ Phase 5 US-3 ──→ Phase 6 US-4
                                                                                    │
                                                              Phase 7 Polish ←──────┘
```

- **Phase 1 / 2 阻塞全部**：依赖边不加则编译不过；`MemoryEntryRepository` 不落地则 SQLite 档与三档契约测试都无处安放。
- **US-1 是 US-2 / US-3 / US-4 的共同前置**：门面与默认档是记忆能力的地基本身。
- **US-2 只依赖 US-1**：它补的是「读出来怎么进 prompt」这一环，与后端档位无关。
- **US-3 依赖 US-1**：三档契约测试需要一个已成形的契约断言集与一个可用的 `MarkdownMemoryStore` 作参照。
- **US-4 依赖 US-1 / US-3**：契约四要跨三档统一验证；US-3 落地前只有 Markdown 一档可查。
- **US-4 排在 P2**：即使只交付 US-1~US-3，Agent 也已经记得住、想得起（核心区永远在场）——检索是「取用」的那一半，且它的核心阶段实现刻意很弱。

## Parallel Execution Examples

**Phase 1 的三条互不依赖**（不同模块、不同文件）：

```text
T001 oryxos-memory/pom.xml  ‖  T002 application.yaml  ‖  T003 schema.sql
```

**US-3 的三档实现可并行**（不同文件、互不依赖）：

```text
T022 SqliteMemoryStore  ‖  T023 Mem0MemoryStore  ‖  T024 InMemoryMemoryStore
```

**US-3 的两组测试类可并行**：

```text
T018 SqliteMemoryStoreTest  ‖  T019 Mem0MemoryStoreTest
```

**US-1 / US-4 的测试类可并行**（各自独立文件）：

```text
T009 MarkdownMemoryStoreTest  ‖  T010 MemoryToolsTest  ‖  T011 MemoryServiceImplTest
```

## MVP 范围

**US-1 + US-2 即为最小可演示**：Agent 能主动记一条、下一轮立刻想得起、开新会话长期记忆自动进 prompt。这是「Agent 记得住事」这件事本身。

**推荐增量**：US-1（记 + 读 + 核心区在场）→ US-2（进 prompt，闭环）→ **US-3（三档可插拔，本节最值钱的部分）** → US-4（检索）。

**US-3 不能省**：不给它同等优先级，本节就退化成「写了一个 Markdown 记忆文件」——三档同跑一套契约测试才是本节存在的理由。

## Format Validation

全部 37 个任务均为 `- [ ] T### [P?] [US#?] 描述 + 文件路径` 格式；Setup / Foundational / Polish 阶段无 `[US#]` 标签；user story 阶段任务均带标签。测试任务在各 user story 内**先于或伴随**对应实现任务。
