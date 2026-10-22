# Implementation Plan: 动态管理 Agent —— 一句话生成、上传即上线

**Branch**: `class-30` | **Date**: 2026-10-21 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/013-agent-lifecycle-api/spec.md`

**Note**: 本文件是 `/speckit-plan` 的输出。技术口径以本文件为准；冲突时以 `spec.md` 的 `## Clarifications` 为准（本节开工前 3 条软门禁裁决均已有用户书面结论：**全按课件 §五**、**授权 per-agent 记忆三处改动**、**harness 就地映射到新语义**）。

---

## Summary

第 29 节把「一个 Agent = 一个目录」立住了，但录入还停在「登上服务器放目录 + 重启」。本节补最后一块：**Agent 目录是唯一真相源，且实时生效**——REST 能增删改查、`WatchService` 实时拾取、管理台能管能编辑。

一句话：**一个编排者（`AgentLifecycleService`）+ 一个文件仓库（`AgentStore`）+ 一个守护线程（`WorkspaceWatcher`）+ 两个控制器（`AgentApiController` 扩写、`WorkspaceApiController` 新增）+ 一处记忆上下文（`ToolExecutionContext`）+ 管理台 Agent 详情五页签。**

四条主线：

1. **两条录入路径一段代码**：API 创建写完目录后调 `register(Path)`，监听器拾取目录后也调同一个 `register(Path)`——派生、注册索引、注册定时全在这一段里。`AgentLifecycleServiceTest` 用 `InOrder` 钉死。
2. **创建用模板、生成用模型**：`POST /agents {name, description}` 只做模板脚手架（稳定可复现）；`POST /agents/{name}/generate-files {description}` 才调一次 LLM（落 `llm_calls`），产出草稿返回给前端预览，**不落盘、不注册**。
3. **每个 Agent 一份专属记忆**：Markdown 档路径从全局 `memory/MEMORY.md` 改为 `agents/<name>/MEMORY.md`；工具经 `ToolExecutionContext` 获知自己在替谁跑；三档后端 SPI 与契约测试一行不改。
4. **每个 Agent 一条固定会话**：`channel=admin` + `user=console` + `profile=<name>`，`getOrCreate` 幂等，`admin:console:<name>`（`session_id` 拼接规则未动）。

**端点总账**：第 28 节 15 个 → 本节 **−1**（移除全局 `GET /api/v1/memory`）**+13**（agents 10 条 + workspace 3 条）＝ **27** 个。

---

## Technical Context

**Language/Version**: JDK 21 + Spring Boot 3.x + Spring AI Alibaba（动手前先跑 `mvn dependency:tree` 确认锁定 BOM 里目标依赖存在）、SQLite + Spring Data JPA。凭证走环境变量占位，不落明文。SQLite 用手工建表脚本，不依赖 `hibernate.ddl-auto=update`。

> 本节**不新增任何第三方依赖**。文件监听用 JDK 标准库 `java.nio.file.WatchService`；HTTP 复用 `spring-web`；模型调用复用 core 的 `LlmClient` 接缝；断言用既有 JUnit 5 / AssertJ / Mockito。3 个 `pom.xml`（core / web / boot）一行不改。

**Primary Dependencies**（全部已在本仓）：

- JDK 21：`WatchService` / `Path.normalize()` / `Files.move` / `Thread`（daemon）—— 监听与文件操作
- `org.springframework.ai:spring-ai-model`（经 `oryxos-provider` 传递，core 已有 compile 依赖）—— `Prompt` / `ChatResponse`
- `org.springframework.boot:spring-boot-starter-test`（test）—— `@WebMvcTest` / Mockito `InOrder`
- **不引入**：文件监听库、模板引擎、zip 库、前端新依赖

**Storage**: **不新增表、不改 `schema.sql`**。Agent 定义是磁盘目录，`ProfileRegistry` 是它的内存投影。`sessions` / `scheduled_tasks` / `task_executions` 全部沿用既有结构；固定管理台会话只是既有 `sessions` 表里 `channel='admin'` 的行。

**Testing**（课件 harness 就地映射到 §五 语义）：

- `AgentLifecycleServiceTest`（core，纯单测，mock `AgentStore` / `ProfileLoader` / `ProfileRegistry` / `AgentScheduler` / `LlmClient`）—— create 按序、name 冲突一个目录都不写、注册失败回滚、create 与 watcher 同一段 `register(Path)`、delete 顺序、update 先注销后注册
- `WorkspaceWatcherTest`（core，临时目录 + 真 `WatchService`）—— 手工丢目录免重启出现、删目录注销、坏目录不拖垮监听
- `WorkspaceApiControllerTest`（web，`@WebMvcTest` + 真 `AgentStore` 指临时工作区）—— tree 结构、`file?path=../../etc/passwd` → 400、正常读、写文件防穿越
- `AgentApiControllerTest`（web，`@WebMvcTest`）—— 端点薄转发、冲突 400、不存在 404、统一信封
- `GenerateTest`（core，mock `LlmClient`）—— 草稿可被既有解析器解析、只生成不落盘不注册、非法产出 400 可读原因、作者模型留空 503
- 单测默认跑，**无 `@Tag("integration")` 项**（真模型生成、真浏览器走查是人工项）
- 实现完成的定义是 `mvn clean verify` 全绿（含 Spotless / P3C / Checkstyle / SpotBugs+FindSecBugs / OWASP）

**Target Platform**: Linux 服务器（开发机 WSL2，`WatchService` inotify 可用）；单 fat JAR

**Project Type**: Maven 多模块库 + CLI + 内嵌管理台，**本节不新增模块**

**Performance Goals**:

- 启动扫描线性一次；watch 线程只在事件到达时工作，**不轮询、不自建线程池**（单 daemon `Thread`）
- `GET /agents` 是 `ProfileRegistry.all()` 的排序快照，O(n log n)
- 工作区 tree 只遍历 `agents/` 与 `archive/` 两层内的真实文件，**不跟随软连接目录**（防环）
- 生成端点是一次同步 LLM 调用，沿用现有 Provider 超时口径

**Constraints**:

- **MUST NOT 新建 `AgentLoader`**；课件 `AgentLoader.parse` 的落点是 `ProfileLoader.parse(String, String)`（spec 裁决 4）
- **MUST NOT 改 `LongTermMemoryStore` SPI 与 `MemoryStoreContractTest` 的三档参数源**；per-agent 只影响默认 Markdown 档，SQLite / Mem0 保持全局
- **MUST NOT 改 `OryxTool.execute` 签名**；Agent 名经 `ToolExecutionContext`（ThreadLocal）传递
- **MUST NOT 用 `@Component` / `@PostConstruct` / `@Scheduled`**；watch 启动点仍是 `serve` / `gateway` 命令体
- **MUST NOT 引入异步编程模型**：`WorkspaceWatcher` 是一个 daemon `Thread`，不是池、不是 Reactor、不是 `CompletableFuture`
- **MUST NOT 物理删除 Agent 目录**：删除一律 `archive/`
- **路径安全**：所有 `path` 参数 `normalize()` 后必须 `startsWith(oryxosRoot)`，越界 400
- **凭证纪律**：`oryxos.author.*` 只存 provider/model 名，不存 key；作者模型留空返回 503，**MUST NOT 发 `model=null`**
- **避开 P3C/ASM 解析不了的 Java 18+ 语法形态（如增强 switch 的 `default ->` 写法），静态检查是构建门禁。**

**Scale/Scope**:

- 新增 main 代码：core 约 900 行（`AgentLifecycleService` / `AgentStore` / `WorkspaceWatcher` / `ToolExecutionContext` / `AgentView` / `FileNode` / 作者提示词 / 1 个异常），web 约 450 行（两个控制器 + 6 个 DTO），前端约 700 行（Agent 列表/详情五页签 + 文件编辑 + 删 `MemoryView`）
- 改动既有：`ProfileLoader` +1 公开方法、`AgentScheduler` +1 公开方法、`ToolExecutor` +6 行、`MemoryServiceImpl` +6 行、`MarkdownMemoryStore` +25 行、`ProfileApiController` 保留、`MemoryApiController` 删除、`application.yaml` +2 键、`Workspace` / init +archive 目录
- 新增测试 5 个类，约 800 行

---

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 本节如何满足 | 结论 |
|---|---|---|
| **I. JDK 21 + Spring Boot 3.x 单体、单二进制** | 不新增模块/依赖；watch 是进程内 daemon 线程，仍单 JAR | PASS |
| **II. 核心阶段只做运行时内核** | Agent 增删改查属于「定义 Agent」的内核闭环；版本历史/启用状态/鉴权明确不做 | PASS |
| **III. ReAct 自实现、Spring AI 只用一半** | 生成端点走 `LlmClient.chat` 单次调用，不进 ReAct、不启自动工具执行 | PASS |
| **IV. Agent 目录不是 Tool** | 本节正是目录 → Profile 的兑现；`AGENT.md` 不进 `ToolRegistry` | PASS |
| **V. Tool 体系统一抽象、单模块** | 不改 `OryxTool` / `ToolRegistry`；`ToolExecutionContext` 在 core，工具实现零改动 | PASS |
| **VI. 审计 Day One 落库** | 生成是一次 `LlmClient.chat`，由 `ProviderService` 落 `llm_calls`；会话消息走既有 `AgentService.process` | PASS |
| **VII. 接口先行、实现按档演进** | 三档记忆 SPI 一行不改；只改默认 Markdown 档的路径解析 | PASS |
| **VIII. 数据私有、状态外置** | 不新增持久状态；索引可由目录重建；删除归档不物理删 | PASS |
| **IX. 三触发源复用同一条链路** | 管理台会话消息走 `AgentService.process`；定时注册复用第 25/28 节 | PASS |
| **X. 每个 user story 有可演示 Demo** | `create → 列表可见 → 文件树 → 会话 → 记忆` 全流程可演示；向导见 quickstart | PASS |

**Phase 0 后复检**：research 的 12 条决策均未引入 plan 外的第三方依赖、未改已定字面量（除用户已裁决的 per-agent 记忆与移除 `/memory`）→ 上表结论不变，无 `Complexity Tracking` 项。

---

## Project Structure

### Documentation (this feature)

```text
specs/013-agent-lifecycle-api/
├── plan.md              # 本文件
├── research.md          # Phase 0 输出（12 条决策）
├── data-model.md        # Phase 1 输出（Agent 目录 / 视图 / 固定会话 / 记忆上下文）
├── quickstart.md        # Phase 1 输出（可执行验证向导）
├── contracts/
│   ├── agent-lifecycle-api.md    # 端点 / DTO / 错误码契约
│   └── workspace-memory.md       # 路径安全 / per-agent 记忆 / watcher 契约
├── checklists/requirements.md
├── spec.md
└── tasks.md             # Phase 2 输出（/speckit-tasks）
```

### Source Code (repository root)

```text
oryxos-core/src/main/java/io/oryxlabs/oryxos/core/
├── agent/
│   ├── AgentLifecycleService.java   # 新增：create / get / list / update / delete / register / unregister / generateFiles / saveFiles / fixed session
│   ├── AgentStore.java              # 新增：scaffold / writeAll / read / tree / archive / delete / 路径守卫
│   ├── AgentView.java               # 新增：对外视图（name/description/provider/tools/schedules）
│   ├── FileNode.java                # 新增：工作区树节点
│   ├── AgentAuthorPrompt.java       # 新增：AGENT_AUTHOR_PROMPT 常量（与 docs/prompt/prompt.md 同步）
│   ├── AgentAuthorUnavailableException.java # 新增：作者模型未配置 → 503
│   └── WorkspaceWatcher.java        # 新增：daemon Thread + WatchService
├── tool/
│   └── ToolExecutionContext.java    # 新增：ThreadLocal<String> agentName
├── engine/
│   └── ToolExecutor.java            # 改：执行工具前后置入/清除 Agent 名（+6 行）
├── profile/
│   ├── ProfileLoader.java           # 改：+ parse(String, String)
│   └── ProfileRegistry.java         # 不改（第 29 节已交付运行时索引）
└── schedule/
    └── AgentScheduler.java          # 改：+ unregisterProfile(Profile)

oryxos-memory/src/main/java/io/oryxlabs/oryxos/memory/
├── MemoryServiceImpl.java                # 改：longTermMemory(Profile) 置入 Agent 上下文
└── store/MarkdownMemoryStore.java        # 改：按上下文解析 per-agent MEMORY.md，无上下文回退全局

oryxos-web/src/main/java/io/oryxlabs/oryxos/web/
├── controller/
│   ├── AgentApiController.java           # 改：+12 端点
│   ├── WorkspaceApiController.java       # 新增：tree / file GET / file POST
│   ├── MemoryApiController.java          # 删除（全局 /memory 端点移除）
│   └── dto/                              # + CreateAgentRequest / UpdateAgentRequest / GenerateFilesRequest / SaveFilesRequest / WriteFileRequest
├── api/GlobalExceptionHandler.java       # 改：+ AgentAuthorUnavailableException → 503
└── main/frontend/src/
    ├── api.js                            # 改：+ del（DELETE）
    ├── router.js                         # 改：移除 /memory，导航 6 项
    ├── App.vue                           # 改：移除 MemoryView 注册
    └── views/ProfilesView.vue            # 改：列表 + 新建 + 详情五页签（基本信息/生成/文件/会话/记忆）

oryxos-cli/src/main/java/io/oryxlabs/oryxos/cli/
├── CliAssemblyConfiguration.java         # 改：+ AgentStore / AgentLifecycleService / WorkspaceWatcher Bean
└── command/ServeCommand.java / GatewayCommand.java  # 改：+ watcher.start()
oryxos-cli/src/main/java/io/oryxlabs/oryxos/cli/workspace/Workspace.java # 改：+ archiveDir()
oryxos-cli/src/main/java/io/oryxlabs/oryxos/cli/command/InitCommand.java  # 改：创建 archive/

oryxos-boot/src/main/resources/application.yaml       # 改：+ oryxos.author.provider / model
docs/prompt/prompt.md                                  # 新增：作者系统提示词
```

**Structure Decision**: 沿用 9 模块布局。`AgentLifecycleService` / `AgentStore` / `WorkspaceWatcher` / 值对象 / `ToolExecutionContext` 全部落 `oryxos-core`——它们只依赖 core 既有接缝（`ProfileLoader`、`ProfileRegistry`、`AgentScheduler`、`SessionService`、`AgentService`、`LlmClient`、`ToolCatalog`），**不新增模块依赖边**；控制器与请求 DTO 落 `oryxos-web`（对外契约层）。前端详情页放在既有 `ProfilesView.vue` 内，不新增顶层路由——「对外只有一个 Agent 列表」是 §5.4 的裁决。

---

## Complexity Tracking

> 本节无章程违例，无 `Complexity Tracking` 项。

---

## Phase 0 研究结论（详见 [research.md](./research.md)）

| # | 决策 | 一句话理由 |
|---|---|---|
| D1 | `AgentLifecycleService` / `AgentStore` / `WorkspaceWatcher` / 值对象全落 core | 只依赖 core 接缝，不新增模块依赖边；控制器只做薄转发 |
| D2 | 不建 `AgentLoader`；`ProfileLoader.parse(String, String)` 复用既有校验 | 第二真相源（第 29 节裁决 4）；`parse` 与磁盘路径走同一个 `toProfile` |
| D3 | 创建 = 模板脚手架；生成 = 对已存在 Agent 的 `generate-files` | 课件 §5.1「以实现为准」：创建稳定可复现，生成人在环里 |
| D4 | 新增 `oryxos.author.provider` / `oryxos.author.model`；**生成调用一律用作者配置**（provider 缺省取已配置清单第一个）；model 空 → `AgentAuthorUnavailableException` → 503 | 不向 OpenAI 兼容端点发 `model=null`；core 不能依赖 provider 的异常类型；**产出草稿的 provider 字段另按 FR-010 改写，MUST NOT 用 Agent 现有 provider 去发起调用** |
| D5 | `ToolExecutionContext`（core，ThreadLocal）+ `ToolExecutor` 前后置入；`MemoryServiceImpl.longTermMemory(Profile)` 包一层；`MarkdownMemoryStore` 动态解析 | 工具签名不变、三档 SPI 不变；无上下文回退全局 |
| D6 | 固定管理台会话 `channel=admin` / `user=console` / `profile=<name>` | 复用 `getOrCreate`，`session_id` 拼接规则一处不动 |
| D7 | 工作区文件操作全走 `AgentStore`；`path` 相对 `.oryxos`，`normalize()` 后必须 `startsWith(root)` | 一个守卫覆盖 tree / read / write 三个端点 |
| D8 | `WorkspaceWatcher` = 单 daemon `Thread` + `WatchService`，启动先全量扫；**目录 CREATE 的 register 失败进入有界延迟重试（单线程 pending 队列，10 次 × 300ms）** | 不引异步模型；`cp -a` 先建目录后落文件，无重试会让 Agent 永久漏注册；坏目录重试耗尽后 WARN 跳过，线程不死 |
| D9 | `AgentScheduler.unregisterProfile(Profile)` 公开化（内部复用既有归属判定） | 第 29 节句柄表兑现；删除/更新都要先停定时 |
| D10 | 归档到 `.oryxos/archive/<name>`，重名追加时间戳后缀 | 不物理删、可追溯；同名重建不互相覆盖 |
| D11 | `oryxos init` 创建 `archive/`；`AgentStore` 归档时懒建 | 老工作区不跑 init 也能归档 |
| D12 | `AdminConsoleSourceTest` 精确开写口：`ProfilesView.vue` 可 `post/put/delete`，`api.js` 增 `delete` 助手；其余页逐文件只读；**保留 `noWriteEntryPointText` 方法名，把扫描范围收窄到只读页** | 用户已裁决「按本节精确开写口并留痕」；断言条数与强度不降，MUST NOT 整条删除（FR-024） |
| D13 | `GET /api/v1/profiles` 保留不动，管理台 Agent 页改读 `/api/v1/agents` | 不破坏第 26 节契约；`/profiles` 含 `valid/error`，仍有诊断价值 |
| D14 | `generate-files` 只产出 `AGENT.md`，返回 `Map<String,String>`；剥代码围栏；**产出草稿** frontmatter 的 provider 沿用该 Agent 的 provider（调用方 provider 走作者配置） | 课件 §5.2.4 原文 + FR-011；两者混用会让 mock 档 Agent 的生成打到固定脚本 |
| D15 | `update` 请求体 `{agentMarkdown}`；`saveFiles` 请求体 `{files:{path:content}}` | 与「覆写 AGENT.md」「保存一组文件」逐字对应 |
| D16 | 名称校验 `^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$`；`/`、`\`、`..`、隐藏名一律 400 | 目录名即真相源，API 不能造出越界目录 |
| D17 | 错误语义：冲突/非法定义 → 400（`IllegalArgumentException`）；不存在 → 404（`ProfileNotFoundException`）；作者模型未配 → 503 | 沿用第 26 节 `GlobalExceptionHandler`，不发明新状态码 |
| D18 | 管理台详情五页签在 `ProfilesView.vue` 内切换，不新增路由 | §5.4「对外只有一个 Agent 列表」；路由库是极简自实现，加动态段不划算 |

---

## 设计要点（实现时必读）

### 1. `AgentLifecycleService` 的编排顺序

- `create(name, description)`：① `store.exists` / `registry.exists` 冲突检查（**此前不写任何文件**）→ ② `store.scaffold(name, description)` → ③ `register(dir)`；③ 抛任何 `RuntimeException` 都 `store.delete(name)` 回滚后原样上抛。
- `register(Path agentDir)`：`profileLoader.deriveProfile(dir)` → `registry.register(profile)` → 若 `schedules` 非空 `agentScheduler.registerProfile(profile)`。**API 与 watcher 只调这一个方法**。
- `update(name, markdown)`：先 `profileLoader.parse(markdown, name)` 校验 → `store.writeAgentMarkdown(name, markdown)` → `agentScheduler.unregisterProfile(old)` → `registry.register(new)` → 若 `new.schedules` 非空 `agentScheduler.registerProfile(new)`。写盘失败不注销旧定时。
- `delete(name)`：`agentScheduler.unregisterProfile(profile)` → `registry.remove(name)` → `store.archive(name)`。顺序反了会出现「cron 触发时索引已空」。
- `saveFiles(name, files)`：先校验 `files` 里的 `AGENT.md`（必须有、可解析）→ `store.writeAll` → 同 update 的重注册段。**校验不过一个字都不写**。
- `generateFiles(name, description)`：**调用方一律用作者配置**（`oryxos.author.provider` / `model`；provider 缺省取已配置清单第一个）构造生成用 `Profile` → `llmClient.chat("agent-author:" + UUID, 生成用 Profile, prompt)` → 取文本 → 剥围栏 → **把产出草稿 frontmatter 的 `provider` 改写为该 Agent 现有 provider**（FR-010 指的是草稿字段，不是调用方——两者混用会让 mock 档 Agent 的生成打到 mock 固定脚本）→ `profileLoader.parse(text, name)`（非法 → 400 可读原因）→ 返回 `Map.of("AGENT.md", text)`。**全程不碰 `AgentStore` 的写方法、不碰索引**。`agent-author:<uuid>` 与第 27 节 `invoke-exec:<uuid>` 同一先例：只是审计标识、永不落 `sessions`，不构成第二处持久化 session_id 拼接（H4 ④）。

### 2. `AgentStore` 的路径守卫

- 根 = `Workspace.root()`（它**已经是** `<基准>/.oryxos`，MUST NOT 再叠一层 `.oryxos`）；`AgentStore` 构造器收 `Path oryxosRoot`（不是 cli 的 `Workspace` 类型，避免 core → cli 反向依赖），装配处传 `workspace.root()`。
- `resolveSafe(String relative)`：拒绝空白、绝对路径、`..` 段；`root.resolve(relative).normalize()` 后必须 `startsWith(root)`，否则 `IllegalArgumentException`。
- `tree()` 只遍历 `agents` 与 `archive`；目录节点带 children，文件节点 `directory=false`；**不跟随软连接目录**（`Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)`）。
- `scaffold` 写出 `AGENT.md`（frontmatter：`description` / `provider.name` / `provider.model` / `tools: []`；正文含任务占位）、`REFERENCE.md`、空的 `scripts/` 与 `skills/`。

### 3. `WorkspaceWatcher`

- `start()`：先全量扫 `agents/` 下每个目录调 `lifecycle.register`（坏目录 WARN 跳过），再注册 `WatchService` 到 `agents/`。
- 单 daemon `Thread`（名 `oryxos-watcher`）循环 `watchService.poll(200ms)`；`ENTRY_CREATE` / `ENTRY_MODIFY` → `register`，`ENTRY_DELETE` → `unregister(name)`；每个事件独立 try/catch，WARN 后继续循环。
- **拷贝竞态必须处理**：`cp -a` 先 mkdir、后落文件，`ENTRY_CREATE` 到达时 `AGENT.md` 可能还没写完。register 失败 MUST 进入**有界延迟重试**（单线程 pending 队列，按目录名去重，10 次 × 300ms；主循环用 `poll` 驱动）；重试耗尽才 WARN 跳过。**MUST NOT 为此新开线程 / 定时器 / 线程池**。
- `stop()` 供测试与优雅关闭；`serve` / `gateway` 的 `run()` 在 `registerAll()` 后调用 `start()`。

### 4. 每 Agent 记忆

- `ToolExecutionContext.callAs(name, supplier)`：保存旧值 → 设置 → 执行 → `finally` **恢复旧值**（异常路径同样恢复，可嵌套）；`set(null)` 视同 `clear()`（与契约 §4.1 一致，plan 不再表述为「不动上下文」）。
- `ToolExecutor.execute`：`String agent = ProfileContext.current() == null ? null : ProfileContext.current().name();` 包住 `tool.execute`（`callAs` 允许 null = 不动上下文）。
- `MemoryServiceImpl.longTermMemory(Profile)`：`ToolExecutionContext.callAs(profile.name(), () -> store.load())`。
- `MarkdownMemoryStore`：`current()` 非空 → `agents/<name>/MEMORY.md`（目录不存在则创建）；否则旧全局路径。**不在构造器缓存路径**，每次方法调用重新解析。
- web 的 `GET /agents/{name}/memory` 在 `ToolExecutionContext.callAs(name, memoryService::partition)` 里取分区。

### 5. 管理台

- `ProfilesView.vue`：列表（新建按钮）→ 点行进入详情；详情顶部返回按钮 + 5 个 tab；基本信息只读、生成/文件/会话可写、记忆只读。
- `api.js` 增加 `del(path)`；`AdminConsoleSourceTest` 的 `WRITE_METHODS` 仍禁 PATCH，`DELETE` 只允许经 `del(` 出现在 `ProfilesView.vue` 且路径 `/api/v1/agents/`。
- 导航去掉「长期记忆」，页数 7 → 6（概览、会话、Agent、工具、定时任务、运行状态）。

---

## 测试与验收映射

| 课件 harness | 本仓落点 | 守着什么 |
|---|---|---|
| `AgentLifecycleServiceTest` | `oryxos-core/src/test/java/.../agent/AgentLifecycleServiceTest.java` | create 顺序、冲突零写、注册失败回滚、同一段 register、delete 顺序、update 先注销后注册 |
| `WorkspaceWatcherTest` | `.../agent/WorkspaceWatcherTest.java` | 丢目录免重启、删目录注销、坏目录不拖垮线程 |
| `WorkspaceApiControllerTest` | `oryxos-web/src/test/java/.../controller/WorkspaceApiControllerTest.java` | tree 结构、穿越 400、正常读、写穿越 400 |
| `AgentApiControllerTest` | `.../controller/AgentApiControllerTest.java` | 薄转发、冲突 400、404、信封 |
| `GenerateTest`（就地映射 generate-files） | `.../agent/GenerateTest.java` | 可解析、不落盘不注册、非法 400、模型空抛 `AgentAuthorUnavailableException`（503 由 web 层 T046 断言） |
| 补充（非课件 harness） | `.../agent/AgentFixedSessionTest.java`（core）、`oryxos-memory/src/test/java/.../PerAgentMemoryScopeTest.java` | 固定会话幂等/累积；per-agent 记忆隔离、无上下文回退、路径不缓存 |

人工项：建 Agent 即上线、丢目录即上线、一句话生成并保存、文件浏览器编辑、删除进 `archive/`、管理台五页签走一遍、真模型生成一次。

---

## 风险与已知边界

1. **`WatchService` 不递归**：只监听 `agents/` 这一层（内层文件改动不产生 `agents/` 事件）；目录 CREATE 的拷贝竞态由 pending 重试兜住；改 `AGENT.md` 正文（`ContextLoader` 每轮重读）不需要重注册，但 `schedules` / `tools` 变化必须走 API update 或整目录替换——写进 quickstart。
2. **`/workspace/file` 的根是 `.oryxos/`**（课件原文），因此理论上可读写 `oryxos.db`；本节按课件口径不做更细的白名单，记已知边界，扩展阶段随鉴权一起收。
3. **生成只产出 `AGENT.md`**：不生成脚本；带脚本的 Agent 走手工丢目录（课件明确不做）。
4. **SQLite / Mem0 记忆仍是全局**：per-agent 只改 Markdown 档；换档后的隔离归扩展阶段。
5. **`ProfileRegistry` 不持久化**：重启由启动扫描 + watcher 重建，与第 29 节一致。
6. **章程原则 IV 的类名过期**：章程写 `AgentLoader`，本仓落点是 `ProfileLoader`（FR-025 / 第 29 节裁决 4）。本节按 FR-025 执行；章程更新另开一次 `/speckit-constitution`，不在本节改章程。
