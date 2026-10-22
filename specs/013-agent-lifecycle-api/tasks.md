# Tasks: 动态管理 Agent —— 一句话生成、上传即上线

**Input**: Design documents from `/specs/013-agent-lifecycle-api/`

**Prerequisites**: [plan.md](./plan.md) (required), [spec.md](./spec.md) (required), [research.md](./research.md), [data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md)

**Tests**: 课件「验收 harness」**明确要求** 5 个测试类，故每个故事都先写测试任务再写实现任务。测试方法名用英文 camelCase，`@DisplayName` 保留课件中文原文。

**Organization**: 按 user story 分组；US1 / US2 同为 P1，US3 / US4 为 P2，US5 为 P3。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无未完成依赖）
- **[Story]**: 该任务归属的 user story
- 每个任务都带精确文件路径

## Path Conventions

- core：`oryxos-core/src/main/java/io/oryxlabs/oryxos/core/`
- memory：`oryxos-memory/src/main/java/io/oryxlabs/oryxos/memory/`
- web：`oryxos-web/src/main/java/io/oryxlabs/oryxos/web/`
- 测试与主代码同模块 `src/test/java/...`
- 前端：`oryxos-web/src/main/frontend/src/`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 本节专属的公共常量、配置与目录骨架；不涉及任何 user story 行为。

- [X] T001 创建 `docs/prompt/prompt.md`，标题与正文为 `AGENT_AUTHOR_PROMPT`（AGENT.md 格式说明 + 输出约束：只输出文件内容、frontmatter 必填 provider/model、schedules 用 5 段 cron），随仓库入库
- [X] T002 [P] 新增 `oryxos-core/src/main/java/io/oryxlabs/oryxos/core/agent/AgentAuthorPrompt.java`：`public static final String PROMPT` 与 `docs/prompt/prompt.md` 逐字一致（`GenerateTest` 会判同步）
- [X] T003 [P] 在 `oryxos-boot/src/main/resources/application.yaml` 的 `oryxos:` 下新增 `author.provider: ${ORYXOS_AUTHOR_PROVIDER:}` 与 `author.model: ${ORYXOS_AUTHOR_MODEL:}`，注释写明「只存名称、不存凭证；model 留空 → 503」
- [X] T004 [P] 新增 `oryxos-core/src/main/java/io/oryxlabs/oryxos/core/agent/AgentAuthorUnavailableException.java`（`extends RuntimeException`，构造器收可读消息），并在 `oryxos-web/src/main/java/io/oryxlabs/oryxos/web/api/GlobalExceptionHandler.java` 增加 `@ExceptionHandler` 映射 503
- [X] T005 [P] `oryxos-cli/src/main/java/io/oryxlabs/oryxos/cli/workspace/Workspace.java` 增加 `archiveDir()`（`root.resolve("archive")`）；`oryxos-cli/src/main/java/io/oryxlabs/oryxos/cli/command/InitCommand.java` 幂等创建 `.oryxos/archive/`，输出不改既有段落

**Checkpoint**: 常量、配置键、异常映射、归档目录就位。

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 所有 user story 共用的值对象、目录仓库、记忆上下文与调度注销能力。

**⚠️ CRITICAL**: 本阶段完成前，任何 user story 都不能开工。

- [X] T006 [P] 新增 `oryxos-core/src/main/java/io/oryxlabs/oryxos/core/agent/AgentView.java`：`record AgentView(String name, String description, String providerName, String providerModel, List<String> tools, List<Profile.ScheduleSpec> schedules)`，列表防御性拷贝；附 `from(Profile)` 静态工厂
- [X] T007 [P] 新增 `oryxos-core/src/main/java/io/oryxlabs/oryxos/core/agent/FileNode.java`：`record FileNode(String name, String path, boolean directory, List<FileNode> children)`，`children` 归一化为不可变空表
- [X] T008 [P] `oryxos-core/src/main/java/io/oryxlabs/oryxos/core/profile/ProfileLoader.java` 增加 `public Profile parse(String agentMarkdown, String agentName)`：走既有 `parseAgentMarkdown` + `toProfile`，**不写临时文件**；异常类型与消息保持既有
- [X] T009 [P] `oryxos-core/src/main/java/io/oryxlabs/oryxos/core/schedule/AgentScheduler.java` 增加 `public void unregisterProfile(Profile profile)`：按 `RegisteredTask.profile().name()` 精确相等的归属判定取消句柄、从 `scheduledTasks` / `registeredTasks` 双表移除；**MUST NOT 用 `<Agent>#` 前缀**；取消 MUST 用 `ScheduledFuture.cancel(false)`（不打断正在跑的一次）；**MUST NOT 触碰 `taskLocks`**（只操作 `scheduledTasks` / `registeredTasks`）
- [X] T010 [P] 新增 `oryxos-core/src/main/java/io/oryxlabs/oryxos/core/tool/ToolExecutionContext.java`：`ThreadLocal<String>` + `set/current/clear/callAs(String, Supplier<T>)`，`callAs` 在 `finally` 恢复旧值
- [X] T011 `oryxos-core/src/main/java/io/oryxlabs/oryxos/core/engine/ToolExecutor.java`：`execute` 内用 `ProfileContext.current()` 取 Agent 名，`ToolExecutionContext.callAs(name, ...)` 包住 `tool.execute`；异常路径由 `callAs` 保证清理（依赖 T010）
- [X] T012 `oryxos-memory/src/main/java/io/oryxlabs/oryxos/memory/store/MarkdownMemoryStore.java`：新增私有 `memoryFile()`，`ToolExecutionContext.current()` 非空 → `agents/<name>/MEMORY.md`（父目录懒建），否则原全局路径；所有读写经它；**不在构造器缓存路径**（依赖 T010，故不标 [P]）
- [X] T013 `oryxos-memory/src/main/java/io/oryxlabs/oryxos/memory/MemoryServiceImpl.java`：`longTermMemory(Profile)` 用 `ToolExecutionContext.callAs(profile.name(), store::load)`；`partition()` 保持无参、由调用方置上下文（依赖 T010）
- [X] T014 新增 `oryxos-core/src/main/java/io/oryxlabs/oryxos/core/agent/AgentStore.java`：构造器收 `Path oryxosRoot`（**= `workspace.root()`，它已经是 `<基准>/.oryxos`，MUST NOT 再 resolve `.oryxos`；MUST NOT 依赖 cli 的 `Workspace` 类型**）；实现 `exists(name)` / `scaffold(name, description, provider, model)` / `writeAgentMarkdown(name, markdown)` / `writeAll(name, Map<String,String>)` / `read(relative)` / `tree()` / `archive(name)` / `delete(name)` / `resolveSafe(relative)`；`resolveSafe` 拒绝空白、绝对路径、`..`，`normalize()` 后必须 `startsWith(root)`；`tree()` 只遍历 `agents` 与 `archive`、不跟随软连接目录、目录在前按名排序
- [X] T015 `oryxos-cli/src/main/java/io/oryxlabs/oryxos/cli/CliAssemblyConfiguration.java`：新增 `agentStore(Workspace workspace)` Bean（构造时传 `workspace.root()`，`AgentStore` 收 `Path`）与作者配置解析（`@Value("${oryxos.author.provider:}")` / `model`；provider 空则取 `ProviderProperties.providers` 第一个，清单为空启动期报可读错误），并暴露**已配置厂商名清单** `List<String>`（从 `ProviderProperties.providers` 的 `name` 取出）供 `AgentLifecycleService` 注入——core MUST NOT 直接依赖 `oryxos-provider`

**Checkpoint**: 值对象、目录仓库、记忆上下文、调度注销、配置解析全部就位；user story 可以并行开工。

---

## Phase 3: User Story 1 - 创建 Agent 即上线（Priority: P1）🎯 MVP

**Goal**: `POST /api/v1/agents {name, description}` → 模板脚手架目录 → 派生 → 注册 → 有 schedules 则注册定时；失败回滚；get / list / update / delete 全通。

**Independent Test**: 临时工作区起上下文，create 后立刻 `GET /agents` 可见；注入 `ProfileRegistry.register` 抛异常，断言目录被删、索引干净、定时零注册；delete 断言顺序与归档位置。

### Tests for User Story 1

- [X] T016 [US1] 新增 `oryxos-core/src/test/java/io/oryxlabs/oryxos/core/agent/AgentLifecycleServiceTest.java`：`create_runsStepsInOrder`、`create_rejectsDuplicateBeforeWritingAnything`（`verifyNoInteractions(agentStore.write*)` 等价断言）、`create_rollsBackWrittenDirectoryWhenRegisterFails`（`verify(agentStore).delete` + `verify(agentScheduler, never()).registerProfile`）、`create_andWatcherShareTheSameRegisterPath`、`delete_unregistersThenRemovesThenArchives`（`InOrder`）、`update_unregistersOldSchedulesBeforeRegisteringNew`、`createIsImmediatelyVisibleInList`（SC-001，不设时钟门槛）；冲突 / 未知 provider / 未注册 tool 用例另断言 `verifyNoInteractions(llmClient)`（FR-022 的「被拒请求零模型调用」；审计零行由「零调用」传递保证）；方法名英文、`@DisplayName` 保留课件中文

### Implementation for User Story 1

- [X] T017 [US1] 新增 `oryxos-core/src/main/java/io/oryxlabs/oryxos/core/agent/AgentLifecycleService.java`：构造器收 `ProfileLoader` / `ProfileRegistry` / `AgentScheduler` / `AgentStore` / `LlmClient` / `ToolCatalog` / `SessionService` / `AgentService` / **`List<String> configuredProviders`** / 作者 provider / 作者 model；实现 `create(String name, String description)`（冲突检查 → 脚手架 → `register(Path)`；注册异常 → `store.delete` 回滚后上抛）、`register(Path)`（派生 → 索引注册 → schedules 非空则调度注册）、`unregister(String)`、`get`、`list`、`update`（写前 `parse` + provider/tools 校验 → 写盘 → `unregisterProfile(旧)` → `register`）、`delete`（`unregisterProfile` → `remove` → `archive`）；名称校验 `^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$`；provider 校验用**注入的已配置厂商名清单**（`configuredProviders`；core MUST NOT import `ProviderProperties` / `SUPPORTED_PROVIDERS`——那在 provider 模块），tools 校验用 `ToolCatalog.find`
- [X] T018 [P] [US1] 新增 `oryxos-web/src/main/java/io/oryxlabs/oryxos/web/controller/dto/CreateAgentRequest.java`（`name`, `description`）与 `UpdateAgentRequest.java`（`agentMarkdown`）
- [X] T019 [US1] 改写 `oryxos-web/src/main/java/io/oryxlabs/oryxos/web/controller/AgentApiController.java`：注入 `AgentLifecycleService`（保留 `AgentService` / `ProfileRegistry` 供 invoke），新增 `POST /`（create）、`GET /`（list）、`GET /{name}`、`PUT /{name}`、`DELETE /{name}`；保留既有 `POST /{name}/invoke` 行为一字不改
- [X] T020 [US1] `oryxos-cli/src/main/java/io/oryxlabs/oryxos/cli/CliAssemblyConfiguration.java`：新增 `agentLifecycleService(...)` Bean（依赖 T015 的 AgentStore 与作者配置）
- [X] T021 [US1] 运行 `./mvnw -pl oryxos-core,oryxos-web -am test`，`AgentLifecycleServiceTest` 全绿且既有测试零回归

**Checkpoint**: US1 可独立演示——API 建 Agent、免重启可见、失败回滚、删除归档。

---

## Phase 4: User Story 2 - 丢一个目录也即上线（Priority: P1）

**Goal**: 启动全量扫描 + JDK `WatchService` 实时监听 `.oryxos/agents/`；新增/修改 → `register(Path)`，删除 → `unregister`；坏目录只告警。

**Independent Test**: 临时目录起 watcher，手工写一个合法 Agent 目录，轮询 ≤5 秒断言索引出现；删目录后消失；先放坏目录再放好目录，好目录照常注册。

### Tests for User Story 2

- [X] T022 [US2] 新增 `oryxos-core/src/test/java/io/oryxlabs/oryxos/core/agent/WorkspaceWatcherTest.java`：`handDroppedDirectoryBecomesVisibleWithoutRestart`、`removedDirectoryIsUnregistered`、`brokenDirectoryDoesNotKillTheWatcherThread`、`startupScanRegistersExistingAgents`、`directoryCreatedBeforeAgentMarkdownIsRetried`（先只建目录、300ms 内再写 `AGENT.md`，断言最终注册成功——钉死 `cp -a` 拷贝竞态）；用真临时目录 + 轮询，**轮询上限 5 秒（SC-002）**，不用 `Thread.sleep` 死等

### Implementation for User Story 2

- [X] T023 [US2] 新增 `oryxos-core/src/main/java/io/oryxlabs/oryxos/core/agent/WorkspaceWatcher.java`：`start()` 先全量扫 `agents/` 调 `lifecycle.register`（坏目录 WARN 跳过），再注册 `WatchService`；单个 daemon `Thread`（名 `oryxos-watcher`）循环 `watchService.poll(200ms)`，`ENTRY_CREATE/MODIFY` → `register`、`ENTRY_DELETE` → `unregister`，每事件独立 try/catch；**CREATE 注册失败进入同一线程内的 pending 重试队列（10 次 × 300ms，按目录名去重，成功即移除；耗尽才 WARN），MUST NOT 新开线程 / 定时器 / 线程池**；`stop()` 关闭服务并中断线程；`start()` 幂等
- [X] T024 [US2] `oryxos-cli/src/main/java/io/oryxlabs/oryxos/cli/command/ServeCommand.java` 与 `GatewayCommand.java`：在 `agentScheduler.registerAll()` 之后调用 `context.getBean(WorkspaceWatcher.class).start()`；**`ChatCommand` 不动**
- [X] T025 [US2] `oryxos-cli/src/main/java/io/oryxlabs/oryxos/cli/CliAssemblyConfiguration.java`：新增 `workspaceWatcher(AgentLifecycleService, Workspace)` Bean
- [X] T026 [US2] 运行 `./mvnw -pl oryxos-core -am test`，`WorkspaceWatcherTest` 全绿，`AgentLifecycleServiceTest` 零回归

**Checkpoint**: US1 + US2 都能独立演示；两条录入路径共用 `register(Path)`。

---

## Phase 5: User Story 3 - 一句话生成 Agent 文件（Priority: P2）

**Goal**: `POST /agents/{name}/generate-files {description}` → 一次 `LlmClient.chat`（落 `llm_calls`）→ 剥围栏 → 既有解析器校验 → 返回草稿映射，不落盘不注册；`POST /agents/{name}/files {files}` 校验后写盘并重注册。

**Independent Test**: mock `LlmClient` 返回合法/带围栏/非法三种文本，断言草稿可解析、`AgentStore` 零写、索引零变化；非法 400；作者 model 空 503。

### Tests for User Story 3

- [X] T027 [US3] 新增 `oryxos-core/src/test/java/io/oryxlabs/oryxos/core/agent/GenerateTest.java`：`generatedDraftParsesWithExistingLoader`、`generationDoesNotWriteOrRegister`（`verifyNoInteractions(agentStore)` 的写方法层面 + `verify(registry, never()).register`）、`codeFencesAreStripped`、`invalidGeneratedMarkdownIsRejectedWithReadableReason`、`blankAuthorModelThrowsAuthorUnavailable`（core 只断言异常类型 + `verifyNoInteractions(llmClient)`；503 映射由 T046 的 web 测试断言，`@DisplayName` 保留课件「模型空 503」语义）、`promptDocumentMatchesConstant`（读仓库根的 `docs/prompt/prompt.md` 与 `AgentAuthorPrompt.PROMPT` 比对；**路径按既有 `ShellWhitelistSourceTest` 的做法从 `Path.of("").toAbsolutePath()` 逐级向上找仓库根**，不得写死模块相对路径）

### Implementation for User Story 3

- [X] T028 [US3] `oryxos-core/src/main/java/io/oryxlabs/oryxos/core/agent/AgentLifecycleService.java`：新增 `generateFiles(String name, String description)`（作者 model 空 → `AgentAuthorUnavailableException`；**调用方 Profile 用作者 provider/model**，`sessionId="agent-author:"+UUID`——同第 27 节 `invoke-exec:<uuid>` 先例，只是审计标识、永不落 `sessions`；`llmClient.chat(...).getResult().getOutput().getText()`；剥围栏；**把产出草稿 frontmatter 的 `provider` 改写为现有 Agent 的 provider（不是调用方 provider）**；`profileLoader.parse` 校验；返回 `Map.of("AGENT.md", text)`）与 `saveFiles(String name, Map<String,String> files)`（必须含 `AGENT.md` → `parse` + provider/tools 校验 → `store.writeAll` → 重注册段）
- [X] T029 [P] [US3] 新增 `oryxos-web/src/main/java/io/oryxlabs/oryxos/web/controller/dto/GenerateFilesRequest.java`（`description`）与 `SaveFilesRequest.java`（`Map<String,String> files`）
- [X] T030 [US3] `oryxos-web/src/main/java/io/oryxlabs/oryxos/web/controller/AgentApiController.java`：新增 `POST /{name}/generate-files` 与 `POST /{name}/files`
- [X] T031 [US3] 运行 `./mvnw -pl oryxos-core,oryxos-web -am test`，`GenerateTest` 全绿

**Checkpoint**: US3 可独立演示；生成不落盘、保存即生效。

---

## Phase 6: User Story 4 - 工作区文件浏览与编辑（Priority: P2）

**Goal**: `GET /workspace/tree`、`GET /workspace/file?path=`、`POST /workspace/file`；三个端点都防目录穿越；写 `agents/<name>/AGENT.md` 走 update 路径。

**Independent Test**: tree 含 agents/archive 两棵子树；`../../etc/passwd` 与绝对路径变体 400 且不读文件；正常读回原文；写越界 400 且磁盘无变化。

### Tests for User Story 4

- [X] T032 [US4] 新增 `oryxos-web/src/test/java/io/oryxlabs/oryxos/web/controller/WorkspaceApiControllerTest.java`：`treeReturnsAgentsAndArchiveRoots`、`treeCanDescendIntoAgentDirectory`、`fileTraversalOutsideWorkspaceIsRejected`（`../../etc/passwd`）、`urlEncodedTraversalIsRejected`（`..%2F..%2Fetc%2Fpasswd`）、`absolutePathIsRejected`、`normalFileReturnsContent`、`oversizedFileIsRejected`（> 1 MiB → 400）、`writeTraversalIsRejectedAndNothingIsWritten`、`writingAgentMarkdownDelegatesToLifecycleUpdate`；用 `@WebMvcTest` + 临时工作区上的真 `AgentStore`

### Implementation for User Story 4

- [X] T033 [P] [US4] 新增 `oryxos-web/src/main/java/io/oryxlabs/oryxos/web/controller/dto/WriteFileRequest.java`（`path`, `content`）与 `FileContent.java`（`path`, `content`）
- [X] T034 [US4] 新增 `oryxos-web/src/main/java/io/oryxlabs/oryxos/web/controller/WorkspaceApiController.java`：注入 `AgentStore` 与 `AgentLifecycleService`；tree 直接返回 `agentStore.tree()`；读文件先 `resolveSafe` 再判存在（不存在 → 404）→ **再 `Files.size` 判 > 1 MiB → 400 `文件超过 1 MiB 上限`（MUST NOT 整文件读入内存）** → 按 UTF-8 读（失败 400 `不是 UTF-8 文本文件`）；写文件若路径匹配 `agents/<name>/AGENT.md` 则调 `lifecycle.update`，否则 `store.resolveSafe` + 建父目录 + `writeString`
- [X] T035 [US4] 运行 `./mvnw -pl oryxos-web -am test`，`WorkspaceApiControllerTest` 全绿

**Checkpoint**: US4 可独立演示；三个端点全部过穿越判据。

---

## Phase 7: User Story 5 - 专属记忆与固定会话（Priority: P3）

**Goal**: `GET /agents/{name}/memory` 读该 Agent 的 `MEMORY.md`；`GET /agents/{name}/session` 与 `POST .../session/messages` 用固定会话 `admin:console:<name>`；移除全局 `GET /api/v1/memory`。

**Independent Test**: 两个 Agent 各写记忆互不串；无上下文回退全局；同一 Agent 连发两条消息落同一条 session；`GET /api/v1/memory` → 404。

### Tests for User Story 5

- [X] T036 [US5] 新增 `oryxos-core/src/test/java/io/oryxlabs/oryxos/core/agent/AgentFixedSessionTest.java`（**core 模块**：固定会话与 `callAs` 语义。记忆路径断言**不在这里**——`LongTermMemoryStore` / `MarkdownMemoryStore` 在 `oryxos-memory`，`oryxos-core` MUST NOT 依赖它）：`fixedSessionIdIsStable`（两次 `fixedSession` 的 `sessionId` 恒为 `admin:console:<name>`）、`twoMessagesLandInTheSameSession`（两次 `sendToFixedSession` 后同一条 session 的消息数增长）、`sendingToArchivedSessionIsRejected`（400）、`callAsRestoresPreviousValue`（嵌套 `callAs` 后旧值复原；异常路径同样复原）
- [X] T037 [US5] 新增 `oryxos-memory/src/test/java/io/oryxlabs/oryxos/memory/PerAgentMemoryScopeTest.java`（**memory 模块**，依赖 T012 / T013）：`perAgentMarkdownMemoryIsIsolated`（两个 Agent 名分别 `callAs` 后 `save/load`，两个 `MEMORY.md` 文件互不包含）、`withoutContextFallsBackToGlobalMemoryFile`（无上下文写进 `.oryxos/memory/MEMORY.md`，与第 22 节行为一致）、`markdownStoreDoesNotCachePathBetweenCalls`（同一个 store 实例先后 `callAs("a")` / `callAs("b")`，落两个不同文件）；**并跑 `./mvnw -pl oryxos-memory -am test` 确认既有 `MemoryStoreContractTest` 一行未改仍全绿**

### Implementation for User Story 5

- [X] T038 [US5] `oryxos-core/src/main/java/io/oryxlabs/oryxos/core/agent/AgentLifecycleService.java`：新增 `fixedSession(String name)`（`getOrCreate("admin","console",name)`，转 `SessionView` 由 web 层做；`SessionView` 的 `messages` 取**最近 ≤100 条、按时间正序**，与第 26/27 节同一口径）与 `sendToFixedSession(String name, String content)`（已归档 400；`agentService.process` 后 `sessionService.save`）；常量 `ADMIN_CHANNEL="admin"` / `ADMIN_USER="console"`
- [X] T039 [US5] `oryxos-web/src/main/java/io/oryxlabs/oryxos/web/controller/AgentApiController.java`：新增 `GET /{name}/memory`（控制器内 `ToolExecutionContext.callAs(name, memoryService::partition)` 后转 `MemoryView`）、`GET /{name}/session`、`POST /{name}/session/messages`；控制器新增注入 `MemoryService`
- [X] T040 [US5] 删除 `oryxos-web/src/main/java/io/oryxlabs/oryxos/web/controller/MemoryApiController.java`；确认 `grep -rn "api/v1/memory" oryxos-*/src/main` 只余正式契约外零命中（`MemoryView` DTO 保留给 Agent 记忆端点）
- [X] T041 [US5] 运行 `./mvnw -pl oryxos-core,oryxos-web -am test`，US5 测试全绿；补一条 web 层断言全局 `/api/v1/memory` 返回 404

**Checkpoint**: 五个 user story 全部可独立演示；端点总数 27。

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: 管理台、机器判卷、文档同步、全量门禁。

- [X] T042 [P] `oryxos-web/src/main/frontend/src/api.js`：新增 `del(path)` 写助手（复用 `send('DELETE', ...)`），更新文件头注释说明写口范围
- [X] T043 `oryxos-web/src/main/frontend/src/views/ProfilesView.vue`：改为「列表 + 新建 Agent + 详情五页签」——基本信息（只读）/ 生成（描述 → 生成 → 每文件编辑预览 → 保存并生效）/ 文件（左树右编辑器，保存）/ 会话（固定会话气泡 + 输入框）/ 记忆（只读）；全部经 `api.js`
- [X] T044 [P] `oryxos-web/src/main/frontend/src/router.js` 移除 `/memory` 路由（导航 6 项）；`oryxos-web/src/main/frontend/src/App.vue` 移除 `MemoryView` 导入与注册；删除 `oryxos-web/src/main/frontend/src/views/MemoryView.vue`
- [X] T045 `oryxos-web/src/test/java/io/oryxlabs/oryxos/web/AdminConsoleSourceTest.java`：按新需求**精确开写口**——`api.js` 含 `'POST'` / `'PUT'` / `'DELETE'` 且仍禁 `PATCH`；`ProfilesView.vue` 是唯一可 `post(` / `put(` / `del(` 的视图且路径限 `/api/v1/agents/` 与 `/api/v1/workspace/file`；其余视图逐文件禁写；`VIEW_ENDPOINTS` 移除 `MemoryView.vue`、`ProfilesView.vue` 改判 `/api/v1/agents`；原 `noWriteEntryPointText`（禁「新建/删除/编辑/保存/提交」字样）**MUST NOT 删除**——把它的扫描范围**收窄到只读页**（`OverviewView` / `SessionsView` / `ToolsView` / `SchedulesView` 等，不再含可写的 `ProfilesView.vue`），方法保留、断言条数不减；`ProfilesView.vue` 的写行为由上面的端点白名单断言覆盖——**判据方向随需求反转，断言不减少，验收报告留痕**
- [X] T046 [P] `oryxos-web/src/test/java/io/oryxlabs/oryxos/web/controller/AgentApiControllerTest.java`：新增/补齐薄转发断言（create/list/get/update/delete/generate-files/files/memory/session/session messages/invoke）、冲突 400、不存在 404、信封四字段
- [X] T047 [P] 文档同步（**四处**，见 `CLAUDE.md`「对外内容三处同步」+ 本仓自身计数）：`README.md`、`website/.vitepress/theme/components/Home.vue`（中英）、`website/guide/` 与 `website/en/guide/` 的端点计数 **15 → 27**、CLI/能力描述补「动态 Agent 管理」；**另加 `CLAUDE.md` 自身的「Web Service API」段与「当前状态」条**（端点总数 **15 → 27**、端点表新增本节 13 条、移除 `GET /api/v1/memory`；`ApiController` 总数**仍是 7**——删 `MemoryApiController`、加 `WorkspaceApiController`，一进一出，MUST NOT 写成 8）
- [X] T048 [P] 给 `specs/006-memory-service/spec.md` / `acceptance-report.md` 与 `CLAUDE.md` 挂 **2026-10-21 修订注**：per-agent Markdown 记忆推翻「全局单份」；**MUST NOT 重写原文**，只加注
- [X] T049 全量门禁：`./mvnw clean verify` 全绿；记录测试总数与前序节零回归；另跑 `grep -rnE "class AgentLoader([^A-Za-z0-9_]|$)" oryxos-*/src` **零命中**（唯一允许的同名物是测试类 `AgentLoaderTest`——那是第 29 节既有的，测的是 `ProfileLoader`；判据带词边界，不带会把测试类算成假阳性）
- [X] T050 按 [quickstart.md](./quickstart.md) 走人工路径（至少 Step 1~5、7、8 的 mock 部分），记录真模型 Step 6 为待人工项
- [X] T051 生成 `specs/013-agent-lifecycle-api/acceptance-report.md`（六项证据 + 变更总结三段 + 剩余人工项），并在对话里完整输出

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 无依赖，可立即开始
- **Foundational (Phase 2)**: 依赖 Setup；**阻塞所有 user story**
- **US1 (Phase 3)**、**US2 (Phase 4)**: 在 Foundational 后开工；US2 依赖 US1 的 `AgentLifecycleService.register(Path)`（T017）
- **US3 (Phase 5)**: 依赖 US1（create/update 的校验与重注册段）
- **US4 (Phase 6)**: 依赖 Foundational 的 `AgentStore` 与 US1 的 `update`
- **US5 (Phase 7)**: 依赖 Foundational 的记忆上下文与 US1 的 `AgentLifecycleService`
- **Polish (Phase 8)**: 依赖全部 user story

### Within Each User Story

- 测试先于实现（T016 → T017；T022 → T023；T027 → T028；T032 → T034；T036 / T037 → T038）
- **T037 依赖 T012 / T013**（`MarkdownMemoryStore` 的路径切换与 `MemoryServiceImpl` 的上下文置入），且落在 **`oryxos-memory`** 模块；**T036 落在 `oryxos-core`**，只测固定会话与 `callAs` 语义——core 测试碰不到 `oryxos-memory` 的类型（依赖方向），故记忆路径断言 MUST NOT 写进 T036
- 值对象 / DTO 先于服务；服务先于控制器
- 每完成一个故事跑一次模块测试，不攒到最后

### Parallel Opportunities

- Phase 1：T002 / T003 / T004 / T005 可并行
- Phase 2：T006~T010 可并行；**T011 / T012 / T013 都依赖 T010（`ToolExecutionContext`），三者彼此可并行**；T014 依赖 T006/T007
- US1：T018 与 T017 可并行；T019 依赖 T017 与 T018
- US3：T029 与 T028 可并行
- US4：T033 与 `WorkspaceApiController` 实现可并行
- Polish：T042 / T044 / T046 / T047 / T048 可并行

---

## Parallel Example: Foundational

```bash
# 同一轮可并行的文件：
Task: "AgentView.java + FileNode.java"
Task: "ProfileLoader.parse"
Task: "AgentScheduler.unregisterProfile + 回归测试"
Task: "ToolExecutionContext.java"
Task: "MarkdownMemoryStore per-agent path"
Task: "AgentAuthorUnavailableException + GlobalExceptionHandler 503"
```

---

## Implementation Strategy

### MVP First (US1 + US2)

1. 完成 Phase 1 + Phase 2
2. 完成 US1（API 创建即上线）
3. 完成 US2（丢目录即上线）——两者共用 `register(Path)`，一起构成「动态管理」的主干
4. **STOP and VALIDATE**：跑 `AgentLifecycleServiceTest` + `WorkspaceWatcherTest`，按 quickstart Step 1~3 走一遍

### Incremental Delivery

1. US1 + US2 → 主干闭环（可演示「两条录入路径」）
2. US3 → 一句话生成（真模型人工项）
3. US4 → 文件浏览器
4. US5 → 专属记忆 + 固定会话
5. Polish → 管理台五页签 + 门禁 + 报告

---

## Notes

- `[P]` = 不同文件、无未完成依赖
- 不得删断言、`@Disabled`、放宽阈值；测试红就修实现
- 静态门禁是构建门禁：P3C / Spotless / SpotBugs / FindSecBugs / Checkstyle 一次到位；`/workspace/file` 的路径守卫需防 FindSecBugs `PATH_TRAVERSAL_IN`
- 不自动 commit / push / package.sh；同步时机由用户决定
- 测试方法名英文，`@DisplayName` 保留课件中文
