# Tasks: Web Service 与第一版管理平台（第 26 节）

**Input**: Design documents from `specs/009-web-service/`
**Prerequisites**: [plan.md](./plan.md) · [spec.md](./spec.md) · [research.md](./research.md) · [data-model.md](./data-model.md) · `contracts/` · [quickstart.md](./quickstart.md)

**Tests**: 本节 harness 由课件与 spec 明确点名（`SessionApiControllerTest` / `GlobalExceptionHandlerTest` / `WebSmokeIT`），故测试任务**必须**落地；其余测试类按 plan 的验证策略补齐。测试方法名一律英文（驼峰或 snake_case），课件原文用 `@DisplayName` 保留。

**Organization**: 按用户故事分阶段；每个故事可独立实现、独立验证。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 不同文件、无未完成依赖，可并行
- **[Story]**: 所属用户故事（US1~US4）；Setup / Foundational / Polish 不加

## Path Conventions

Maven 多模块单体（仓库根目录即模块根）：`oryxos-core/` · `oryxos-provider/` · `oryxos-memory/` · `oryxos-storage/` · `oryxos-tool/` · `oryxos-web/` · `oryxos-cli/` · `oryxos-boot/`。Java 包根 `io.oryxlabs.oryxos`。

---

## Phase 1: Setup

**Purpose**: 依赖边、构建串联、忽略规则、测试夹具——一次配好，后面不再回头改构建。

- [X] T001 [P] 修改 `oryxos-web/pom.xml`：加 4 条模块依赖边（`oryxos-core` / `oryxos-provider` / `oryxos-memory` / `oryxos-tool`）、`spring-boot-starter-test`（test 作用域）、`frontend-maven-plugin` 2.0.2（`nodeVersion=v20.18.0`，`generate-resources` 绑定 `npm-install` + `npm-build` 两个 execution，**不绑 `npm-test`**），并加 `frontend.skip` 属性（research D1~D3 / P6）
- [X] T002 [P] 修改 `oryxos-cli/pom.xml`：加 `oryxos-web` 依赖边（`serve` 起 servlet 容器后要扫到 `@RestController`），同步改写该文件中「MUST NOT 依赖 `oryxos-web`」的注释为带日期（2026-10-19）修订注
- [X] T003 [P] 修改根 `.gitignore`：加 `**/node_modules/`、`oryxos-web/src/main/frontend/dist/`、`oryxos-web/src/main/resources/static/admin/`（产物不入库，research D2）
- [X] T004 [P] 新增 SPA 回落测试夹具 `oryxos-web/src/test/resources/static/admin/index.html`（`-Dfrontend.skip=true` 时主资源目录为空，`WebConfigTest` 靠它判回落；夹具只在 test classpath，永不进发行物）

**Checkpoint**: `mvn -pl oryxos-web -am generate-resources -Dfrontend.skip=true` 可跑通（前端工程尚未建，该次构建应生效于 T038 之后）。

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 会话归档态、记忆分区、异常类型、DTO、装配与容器接线——所有用户故事共同依赖，未完成前任何故事都无法独立验证。

**⚠️ CRITICAL**: 本节完成前，US1~US4 都无法端到端验证。

### 前序节最小改造（纯新增，零既有签名改动）

- [X] T005 [P] 修改 `oryxos-core/src/main/java/io/oryxlabs/oryxos/core/session/Session.java`：加 `status`（`active` / `archived`）与 `archivedAt` 字段，`restore(...)` 由 7 参扩到 9 参；新字段在 `save` 路径**不得被下一次写入悄悄重置**（第 18 节 javadoc 已预告此坑）
- [X] T006 [P] 修改 `oryxos-core/src/main/java/io/oryxlabs/oryxos/core/session/SessionService.java`：加 `boolean archive(String sessionId)`（存在则置归档并返回 `true`；**已归档也返回 `true` 且 `archivedAt` 不变**，FR-021）与 `long countActive()`（`activeSessions` 数据源）
- [X] T007 [P] 修改 `oryxos-storage/src/main/java/io/oryxlabs/oryxos/storage/repository/SessionRepository.java`：加派生查询 `long countByStatus(String status)`；给文件里「本节不新增」的表述挂带日期修订注
- [X] T008 修改 `oryxos-core/src/main/java/io/oryxlabs/oryxos/core/session/JpaSessionService.java`：实现 `archive` / `countActive`；`save` 的更新列集合必须包含归档态（否则归档被下一次 save 复活）；更新类 javadoc 的 2026-10-19 修订注（依赖 T005~T007）
- [X] T009 [P] `oryxos-memory` 只读分区：新增 `store/MemoryPartition.java`（`record(String core, String archival, boolean truncated)`）；`store/LongTermMemoryStore.java` 加 `partition()`；四个实现补齐——`MarkdownMemoryStore`（与 `load()` 共用私有截断助手）、`SqliteMemoryStore`（`LIMIT` 最近 100 条）、`Mem0MemoryStore`（`truncated` 恒 `false`、`archival` 如实返回空）、`InMemoryMemoryStore`（测试替身）
- [X] T010 [P] 修改 `oryxos-memory/src/main/java/io/oryxlabs/oryxos/memory/MemoryService.java` 与 `MemoryServiceImpl.java`：加 `MemoryPartition partition()`，委托底层 store、**不缓存**（FR-019）；`MemorySource` 接缝一个字不改（依赖 T009）
- [X] T011 [P] 新增异常类型：`oryxos-web/src/main/java/io/oryxlabs/oryxos/web/error/SessionNotFoundException.java`、`ProfileNotFoundException.java`、`AgentTimeoutException.java`（本节无抛出点，裁决 ②）；`oryxos-provider/src/main/java/io/oryxlabs/oryxos/provider/ProviderUnavailableException.java`
- [X] T012 修改 `oryxos-provider/src/main/java/io/oryxlabs/oryxos/provider/ProviderService.java`：调用失败时包装成 `ProviderUnavailableException` 并抛出，**消息 MUST 保留 cause 的 message**（既有 `ProviderServiceTest.recordsAuditBeforeRethrowingOnFailure` 断言 `hasMessageContaining("timeout")`，反作弊纪律下不得放宽）；审计仍先落账再上抛（依赖 T011）

### 传输层 DTO 与健康探针

- [X] T013 [P] `oryxos-web/src/main/java/io/oryxlabs/oryxos/web/controller/dto/` 下新增 13 个 record：`CreateSessionRequest` / `MessageRequest` / `InvokeRequest` / `SessionView` / `MessageView` / `MessageResponse` / `InvokeResponse` / `ArchiveResponse` / `ProfileView` / `MemoryView` / `ToolView` / `HealthView` / `InfoView`——字段名逐字按 [data-model.md](./data-model.md) §3（`userId` / `reply` / `content` / `channel:"web"`，裁决 ⑪）
- [X] T014 [P] 新增 `oryxos-web/src/main/java/io/oryxlabs/oryxos/web/health/DatabaseHealthProbe.java`：`DataSource.getConnection().isValid(1)`，异常即 `DOWN`（不抛、不缓存）

### 装配与容器接线

- [X] T015 修改 `oryxos-cli/src/main/java/io/oryxlabs/oryxos/cli/CliAssemblyConfiguration.java`：新增 `@Bean ProfileLoader profileLoader(Workspace)`（research D27），`profileRegistry(...)` 改为复用该实例（把方法体内联的 `new ProfileLoader(...)` 抽走）；**不改变既有 Bean 的声明顺序契约**
- [X] T016 修改 `oryxos-cli/src/main/java/io/oryxlabs/oryxos/cli/CliApplication.java`：`scanBasePackages` 加 `io.oryxlabs.oryxos.web`（**不放宽到 `io.oryxlabs.oryxos`**，P14），javadoc 的「MUST NOT 起 SERVLET 容器 / MUST NOT 依赖 oryxos-web」挂 2026-10-19 修订注
- [X] T017 修改 `oryxos-cli/src/main/java/io/oryxlabs/oryxos/cli/command/ServeCommand.java`：`.web(WebApplicationType.SERVLET)` + `System.setProperty("server.port", String.valueOf(port))`（D6）；`--port` description 更新为「HTTP 端口（默认 8080）」；三条占位 println 改为真实启动提示；类 javadoc 的「不起 HTTP 端口 / MUST NOT 依赖 oryxos-web」段挂修订注改写；`keepAlive()` 保留（`GatewayCommand` 复用）
- [X] T018 删除 `oryxos-boot/src/main/java/io/oryxlabs/oryxos/boot/OryxosApplication.java`；修改 `oryxos-boot/pom.xml` 的 `<mainClass>` 注释（去掉「并存两个 `@SpringBootApplication`」表述）（裁决 ③）
- [X] T019 [P] 新增 `oryxos-web/src/main/java/io/oryxlabs/oryxos/web/config/WebConfig.java`：SPA 回落（`/` → `/admin/`、`/admin` → `/admin/`、`/admin/` → forward `index.html`、`/admin/assets/**` 长缓存、`/admin/**` 其余回落 `index.html`，`/api/**` 不受影响——research D22）+ CORS `/api/**` 全开放（`allowedOriginPatterns("*")`、`allowCredentials(false)`，FR-023）

### 统一异常出口

- [X] T020 [P] 新增 `oryxos-web/src/test/java/io/oryxlabs/oryxos/web/api/GlobalExceptionHandlerTest.java`（`@WebMvcTest`）：逐类异常断言状态码与信封字段；**500 响应体不含内部异常 message**（用 `IllegalStateException("jdbc:sqlite:/data/oryxos.db connect failed")` 注入，断言响应体不含 `jdbc:sqlite`）；`IllegalArgumentException→400`、`NoResourceFoundException→404` 既有映射不回退
- [X] T021 修改 `oryxos-web/src/main/java/io/oryxlabs/oryxos/web/api/GlobalExceptionHandler.java`：**扩展**既有类——`SessionNotFoundException` / `ProfileNotFoundException` → 404；`ProviderUnavailableException` → 503；`AgentTimeoutException` → 504；catch-all → 500 且响应体固定话术 `Internal server error`（完整异常只进日志）；**不把 `IllegalStateException` 整类映射 503**（裁决 ④：`ProviderNotFoundException` 是配置错，归 500）（依赖 T011、T020）

**Checkpoint**: `mvn -pl oryxos-web,oryxos-core,oryxos-memory,oryxos-provider,oryxos-storage -am test` 全绿；`serve` 起得来（可先只验容器启动，端点尚未全建）。

---

## Phase 3: User Story 1 - 业务系统通过 HTTP 与 Agent 连续对话 (Priority: P1) 🎯 MVP

**Goal**: 会话四端点（创建 / 发消息 / 查历史 / 归档）+ 统一信封与异常出口，构成对外门面的最小闭环。

**Independent Test**: 只实现本节四个端点，用 HTTP 客户端完成「建会话 → 发消息 → 查历史 → 归档」，并验证 32KB→400、不存在→404、归档幂等、归档后再发消息→400。

### Tests for User Story 1 ⚠️ 先写、先红

- [X] T022 [P] [US1] 新增 `oryxos-web/src/test/java/io/oryxlabs/oryxos/web/controller/SessionApiControllerTest.java`（`@WebMvcTest`，mock `AgentService`）：超 32KB → 400 且**零次** `agentService.process`（FR-008 / FR-022）；会话不存在 → 404；正常请求 `verify(agentService, times(1)).process(...)`（FR-002）；归档会话发消息 → 400 `session is archived: <id>`；`GET` 历史最多 100 条（FR-009）；两次 DELETE 返回同一 `archivedAt`（FR-021）；Controller 预校验 Agent 不存在 → 404（FR-007）
- [X] T023 [P] [US1] 修改 `oryxos-core/src/test/java/io/oryxlabs/oryxos/core/session/SessionServiceTest.java`：补 `archive` 幂等（第二次返回 `true` 且 `archivedAt` 不变）、`countActive`（归档后计数下降）、`save` 不重置归档态

### Implementation for User Story 1

- [X] T024 [US1] 新增 `oryxos-web/src/main/java/io/oryxlabs/oryxos/web/controller/SessionApiController.java`：4 个端点逐字按 [contracts/rest-endpoints.md](./contracts/rest-endpoints.md) §1；`channel` 服务端定死 `"web"`、`userId` 缺省 `"default"`；`SessionService.getOrCreate` 建/取会话；发消息调 `AgentService.process(session, content)`（**同一个方法**，MUST NOT 另起链路）；查历史只取最近 100 条正序；归档调 `SessionService.archive`（依赖 T005~T008、T013、T021）

**Checkpoint**: US1 独立可演示——`curl` 四端点全通，错误码与信封齐备。MVP 达成。

---

## Phase 4: User Story 2 - 业务系统一次性调用某个 Agent (Priority: P2)

**Goal**: 无状态调用端点；不建持久会话、不携带历史。

**Independent Test**: 同一 Agent 连续调两次，第二次看不到第一次内容；`sessions` 表零新增 `invoke-exec:%` 行。

### Tests for User Story 2 ⚠️ 先写、先红

- [X] T025 [P] [US2] 修改 `oryxos-core/src/test/java/io/oryxlabs/oryxos/core/engine/AgentServiceTest.java`：补 `processStateless` 两次调用互不可见、且不调用 `sessionService.save`（不落库）
- [X] T026 [P] [US2] 新增 `oryxos-web/src/test/java/io/oryxlabs/oryxos/web/controller/AgentApiControllerTest.java`：正常调用返回 `{agent, reply}`；Agent 不存在 → 404；参数错误 → 400

### Implementation for User Story 2

- [X] T027 [US2] 修改 `oryxos-core/src/main/java/io/oryxlabs/oryxos/core/engine/AgentService.java`：新增 `processStateless(String profileName, String message)`——内部用瞬时会话 `Session.create("invoke-exec:" + UUID, "web", "console", profileName)`，走**同一** `reActLoop.run(...)`，**不调 `save`**；`process(...)` 一行不改（裁决 ⑫ / FR-002）
- [X] T028 [US2] 新增 `oryxos-web/src/main/java/io/oryxlabs/oryxos/web/controller/AgentApiController.java`：`POST /api/v1/agents/{name}/invoke`，参数校验后调 `processStateless`（依赖 T027）

**Checkpoint**: US1 + US2 均独立可用。

---

## Phase 5: User Story 3 - 运维不写代码就能看清底座现状 (Priority: P3)

**Goal**: 五个查询端点（profiles / memory / tools / health / info），全部只读、实时、不缓存。

**Independent Test**: 逐个请求端点，核对返回与工作区真实状态一致（Agent 数含无效项、工具 15 个、记忆核心区全文、Provider 就绪状态、运行时长）。

### Tests for User Story 3 ⚠️ 先写、先红

- [X] T029 [P] [US3] 新增 `oryxos-web/src/test/java/io/oryxlabs/oryxos/web/controller/ProfileApiControllerTest.java`：含加载失败 Agent 的列表（`valid:false` + `error`，FR-018）；按 `name` 升序
- [X] T030 [P] [US3] 新增 `oryxos-web/src/test/java/io/oryxlabs/oryxos/web/controller/MemoryApiControllerTest.java`：`{core, archival, truncated}` 形状；两次调用之间改记忆，第二次见新值（FR-019 不缓存）；后端异常 → 500 而非空记忆
- [X] T031 [P] [US3] 新增 `oryxos-web/src/test/java/io/oryxlabs/oryxos/web/controller/ToolApiControllerTest.java`：`source` / `origin` 在 `builtin` / `annotation` / `mcp:<server>` 三种来源下的取值
- [X] T032 [P] [US3] 新增 `oryxos-web/src/test/java/io/oryxlabs/oryxos/web/controller/SystemApiControllerTest.java`：`status` 只由 `db` 决定（db DOWN + provider UP → 整体 DOWN；provider DOWN 不拉低整体，FR-020）；`providers` 键集合 = `SUPPORTED_PROVIDERS`；`/info` 的 `registeredTools` / `activeSessions` / `uptimeSeconds`

### Implementation for User Story 3

- [X] T033 [US3] 新增 `oryxos-web/src/main/java/io/oryxlabs/oryxos/web/controller/ProfileApiController.java`：注入 `ProfileLoader`，实时调 `loadAllDetailed()`，失败项映射为 `valid:false` + `error`（依赖 T015、T013）
- [X] T034 [US3] 新增 `oryxos-web/src/main/java/io/oryxlabs/oryxos/web/controller/MemoryApiController.java`：调 `MemoryService.partition()`，不缓存（依赖 T010、T013）
- [X] T035 [US3] 新增 `oryxos-web/src/main/java/io/oryxlabs/oryxos/web/controller/ToolApiController.java`：读 `ToolRegistry.registered()`，映射 `source` / `origin`（依赖 T013）
- [X] T036 [US3] 新增 `oryxos-web/src/main/java/io/oryxlabs/oryxos/web/controller/SystemApiController.java`：`/health` 用 `DatabaseHealthProbe` + `ProviderProperties.SUPPORTED_PROVIDERS` 判配置就绪（**不发真实请求**，FR-020）；`/info` 取版本（读不到回 `"unknown"`）、`ProfileRegistry.all().size()`、`SessionService.countActive()`、`ToolRegistry.registered().size()`、`RuntimeMXBean.getUptime()/1000`（依赖 T014、T006、T013）
- [X] T037 [US3] 新增 `oryxos-cli/src/test/java/io/oryxlabs/oryxos/cli/WebSmokeIT.java`：`@SpringBootTest(webEnvironment = RANDOM_PORT)` + `@Tag("integration")`；**（2026-10-19 实测偏离并已改）**原写「静态块把 `user.dir` 指向临时工作区并在 `@AfterAll` 还原」——JDK 21 下 `System.setProperty("user.dir", ...)` 对 `Paths.get("")` **不生效**（默认文件系统首次访问时已缓存该值，探针实测前后同路径），改用 `@Primary Workspace` Bean 指向 `@TempDir` 工作区，判据不变；见 `WebSmokeIT` 类 javadoc；只注入 `DEEPSEEK_API_KEY`（假值即可）起真上下文；断言 `/health`、`/info`、`/profiles`、`/tools` 真实链路可达（Bean 装配 + 扫描范围没炸，FR-010 的结构判卷）

**Checkpoint**: US1~US3 全部独立可用；`WebSmokeIT` 单跑绿。

---

## Phase 6: User Story 4 - 只读管理台 (Priority: P4)

**Goal**: 五页只读 SPA，数据全部来自 US1/US3 端点；设计语言与官网同源；刷新子路由不 404。

**Independent Test**: 起服务后访问 `/admin/`，五页渲染真实数据、零写操作入口；`/admin/tools` 直接刷新不 404；源码断言 `AdminConsoleSourceTest` 绿。

### Implementation for User Story 4

- [X] T038 [US4] 新增前端工程 `oryxos-web/src/main/frontend/`：`package.json`（`vue ^3.5.13` / `vite ^6.0.7` / `@vitejs/plugin-vue ^5.2.1`）、`vite.config.js`（`base:'/admin/'`、`build.outDir:'../resources/static/admin'`、`emptyOutDir:true`、dev 代理 `/api` → `localhost:8080`）、`index.html`、`src/{main.js,App.vue,router.js,api.js}`、`src/styles/tokens.css`（令牌逐字取 `website/.vitepress/theme/custom.css`，research D8）；**不引外部 CDN**（字体 / 图标本地化）
- [X] T039 [US4] 新增五个页面 `src/views/{SessionsView,ProfilesView,ToolsView,MemoryView,StatusView}.vue`：分别调 `GET /api/v1/sessions/{id}`（按 ID 查询）、`/profiles`、`/tools`、`/memory`、`/health` + `/info`；**只发 GET**；每页具备加载中 / 空数据 / 错误三态（错误态显示信封 `message`，FR-015）；无任何写操作控件（FR-013）
- [X] T040 [P] [US4] 新增 `oryxos-web/src/test/java/io/oryxlabs/oryxos/web/config/WebConfigTest.java`：`/admin/` 回落 `index.html`（用 T004 夹具）；`/admin/assets/**` 长缓存头；`/api/v1/**` 未命中**不**回落（404 保持 404，FR-016）；`/api/**` CORS 放开
- [X] T041 [P] [US4] 新增 `oryxos-web/src/test/java/io/oryxlabs/oryxos/web/AdminConsoleSourceTest.java`：前端源码只出现 GET、所有路径以 `/api/v1` 开头、五页文件存在且各引其端点、无「新建 / 删除 / 编辑 / 保存 / 提交」按钮文案（FR-013 机器判卷）
- [X] T042 [US4] 回修 `.claude/skills/oryxos-admin-ui/SKILL.md`：令牌换成 `custom.css` 真值、信封改为 `{code,message,data,timestamp}`、「会话列表」改为「按会话 ID 查询」、补本节 10 端点清单与三态规范（FR-014）
- [X] T043 [US4] 验证构建串联：`mvn -pl oryxos-web -am generate-resources` 产出 `static/admin/index.html`；`mvn -pl oryxos-web -am package -Dfrontend.skip=true` 可跳过前端（两条路径都通，research D1/D3）

**Checkpoint**: 管理台五页在浏览器渲染真实数据；零写入口；子路由刷新不 404。

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: 前序契约与文档的修订留痕、全量门禁、quickstart 验证。

- [X] T044 [P] 回修 `specs/000-runtime-kernel/contracts/rest-api.md`：挂带日期（2026-10-19）修订注——`channel:"api"`→`"web"`、`user`→`userId`、`response`→`reply`、`/health` 的「不可达」→「配置就绪 / 未配置」、补两条错误文案（`session is archived` / `agent not found`）；原文不重写
- [X] T045 [P] 回修文档：`CLAUDE.md`（`OryxosApplication` 已删除的事实陈述）、`website/guide/quick-start.md:35` 与 `website/en/guide/quick-start.md:35`（fat JAR 主类更正）
- [X] T046 [P] 回修 javadoc / 清单里的失效表述（全部挂 2026-10-19 修订注）：`Session.channel()` 的 `@param`、`SessionEntity.channel` 的 `api`→`web`；`specs/009-web-service/checklists/requirements.md:60` 的「sqlite 档没有标题」（前提错误，实测同款标题）
- [X] T047 全量门禁与向导验证：`mvn clean verify` 全绿；按 [quickstart.md](./quickstart.md) Step 1~10 逐条验证（管理台与 `/swagger-ui` 的浏览器步骤留人工项）——**真链路跑 Step 2~9 时抓到一个主流程缺陷（建会话不落库 → 发消息 404），已修并补回归判据**；逐条结果与三个坑见 [manual-test-guide.md](./manual-test-guide.md)「实测记录」

**Checkpoint**: 六项证据 DoD 具备，可进入节级收尾（`manual-test-guide.md` + `acceptance-report.md`）。

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 无依赖，立即开始
- **Foundational (Phase 2)**: 依赖 Setup —— **阻塞所有用户故事**
- **US1 (Phase 3)**: 依赖 Foundational —— 独立可验证
- **US2 (Phase 4)**: 依赖 Foundational —— 与 US1 无文件交集（`AgentService` vs `SessionApiController`）
- **US3 (Phase 5)**: 依赖 Foundational（T015 的 `ProfileLoader` Bean 是 T033 的前提）
- **US4 (Phase 6)**: 依赖 US1（会话页调 `GET /sessions/{id}`）与 US3（其余四页）；`WebConfig` 已在 Foundational 就位
- **Polish (Phase 7)**: 依赖全部用户故事

### User Story Dependencies

- **US1 (P1)**: 无跨故事依赖 —— MVP
- **US2 (P2)**: 无跨故事依赖
- **US3 (P3)**: 无跨故事依赖（`/info` 的 `activeSessions` 依赖 T006，属 Foundational）
- **US4 (P4)**: 依赖 US1 + US3 的端点（纯消费）

### Within Each User Story

- 测试先写、先红，再实现
- 领域模型/接口 → 实现 → Controller → 端点级集成
- 每个故事结束跑该模块测试，红了当场修

### Parallel Opportunities

- Phase 1 的 T001~T004 全 [P]
- Phase 2 中 T005/T006/T007/T009/T010/T011/T013/T014 全 [P]；T008 依赖前三者、T012 依赖 T011、T019 与 T021 有先后
- US1 的 T022/T023 可并行；US2 的 T025/T026 可并行；US3 的 T029~T032 可并行、T033~T035 可并行
- US4 的 T040/T041 可并行

---

## Parallel Example: User Story 3

```bash
# 四个查询端点的契约测试可同时写：
Task: "ProfileApiControllerTest in oryxos-web/src/test/java/.../controller/"
Task: "MemoryApiControllerTest  in oryxos-web/src/test/java/.../controller/"
Task: "ToolApiControllerTest    in oryxos-web/src/test/java/.../controller/"
Task: "SystemApiControllerTest  in oryxos-web/src/test/java/.../controller/"

# 三个互不依赖的只读 Controller 可并行实现：
Task: "ProfileApiController / MemoryApiController / ToolApiController"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Phase 1 Setup → Phase 2 Foundational
2. Phase 3 US1
3. **STOP & VALIDATE**: 用 HTTP 客户端走通「建会话 → 发消息 → 查历史 → 归档」+ 全部错误码
4. 可演示（真模型真链路）

### Incremental Delivery

1. Setup + Foundational → 基础就绪
2. + US1 → 门面最小闭环（MVP）
3. + US2 → 无状态调用
4. + US3 → 运维查询面
5. + US4 → 只读管理台（`API 完备性` 的自证）
6. Polish → 全量门禁与文档收口

---

## Notes

- [P] = 不同文件、无未完成依赖
- 测试方法名英文，课件原文进 `@DisplayName`
- 不自动 commit / push；每完成一个逻辑组由用户决定同步时机
- 反作弊：不得删断言、`@Disabled`、放宽阈值；未全绿不得宣称完成
- 全程不引外部 CDN；凭证不落明文；500 不泄漏内部异常
