# Phase 0 Research: 动态管理 Agent

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md) | **Date**: 2026-10-21

> 本文件记录实现前核实过的代码事实与据此做出的决策。所有「文件名 / 方法名 / 现状」均以本仓当前代码为准，不引用课件示意签名。

---

## 事实核对（动手前已逐项 grep / read）

| 事实 | 证据 | 对设计的影响 |
|---|---|---|
| `ProfileRegistry` 已有 `register` / `remove` / `exists` / `find` / `all` | `oryxos-core/.../profile/ProfileRegistry.java` | 生命周期服务只调这五个方法；**不新建索引** |
| `ProfileLoader.deriveProfile(Path)` / `inspect(Path)` 已交付，内部统一走 `toProfile` | `.../profile/ProfileLoader.java` | 目录注册直接复用；新增 `parse(String,String)` 也落到 `toProfile` |
| `ProfileLoadException` 是 `public static final`，构造器包级私有 | 同上 | 外部只能捕获、不能伪造；web 层包装成 400 |
| `AgentScheduler.registerProfile(Profile)` 已有，`cancelTasksOf(String)` 是 private | `.../schedule/AgentScheduler.java` | 新增 `public void unregisterProfile(Profile)`，复用归属判定（`RegisteredTask.profile().name()` 精确相等） |
| `AgentScheduler.scheduledTasks` 句柄表在第 29 节已交付 | 同上 | 删除/更新的「先停定时」有东西可停 |
| `LlmClient` 在 core，签名 `chat(String, Profile, Prompt)` | `.../core/llm/LlmClient.java` | 生成端点走 core 接缝；不 import provider 模块 |
| `ProviderService` 把 `RuntimeException` 包成 `ProviderUnavailableException` | `oryxos-provider/.../ProviderService.java` | 该异常类型在 provider 模块，core 的生成前置校验不能抛它 |
| `Profile` 是 record，`ProviderSpec(name, model, temperature)` | `.../profile/Profile.java` | 构造生成用 Profile 无需 builder，直接 `new Profile(...)` |
| `ProviderProperties.SUPPORTED_PROVIDERS = {deepseek, mock}`，`providers` 是 `List<ProviderEntry>` | `oryxos-provider/.../ProviderProperties.java` | **core MUST NOT import 它**（在 provider 模块）：装配处把 `providers` 的 `name` 抽成 `List<String>` 注入 `AgentLifecycleService`，校验用这份注入清单；缺省 provider 也在装配处取 `providers` 第一个 |
| `ToolCatalog.find(String)` 返回 `Optional<OryxTool>` | `.../core/tool/ToolCatalog.java` | tools 已注册校验用它，不依赖 `oryxos-tool` |
| `ToolExecutor.execute(String sessionId, ToolCall)` 在 core，执行前无 Agent 信息 | `.../engine/ToolExecutor.java` | 用 `ProfileContext.current()` 取名，不改正签名 |
| `ProfileContext` 是 ThreadLocal，`AgentService.process` 入口 set、finally clear | `.../engine/ProfileContext.java` | 工具执行期间必然有值；定时/无状态调用同样走 `process` / `processStateless` |
| `MarkdownMemoryStore` 构造器收 `.oryxos` 根，字段 `memoryDir` / `memoryFile` 固定 | `oryxos-memory/.../store/MarkdownMemoryStore.java` | 改为每次调用动态解析路径；构造签名与 SPI 不变 |
| `MemoryServiceImpl.partition()` 无参，`longTermMemory(Profile)` 有 Profile | `oryxos-memory/.../MemoryServiceImpl.java` | 前者由调用方置上下文，后者自己置 |
| `MemoryStoreContractTest` 用同一套断言跑三档 | `oryxos-memory/src/test/...` | 不能改参数源与断言；per-agent 只在有上下文时改变 Markdown 路径 |
| `GET /api/v1/memory` 由 `MemoryApiController` 提供 | `oryxos-web/.../controller/MemoryApiController.java` | 按用户裁决删除该控制器与路由 |
| `SessionService.getOrCreate(channel, userId, profile)` / `save` / `get` | `.../session/SessionService.java` | 固定会话直接用三元组；GET 只读、POST 处理后再 save |
| `SessionApiController` 的 `CHANNEL="web"` / `DEFAULT_USER_ID="default"` 是 web 会话口径 | `oryxos-web/.../SessionApiController.java` | 管理台固定会话用 **另一组**常量 `admin` / `console`，不污染 web 会话 |
| `AdminConsoleSourceTest` 目前只允许 SchedulesView 写 | `oryxos-web/src/test/.../AdminConsoleSourceTest.java` | 按本节裁决精确开写口并留痕 |
| `api.js` 只有 get / post / put | `oryxos-web/src/main/frontend/src/api.js` | 增加 `del`；`DELETE` 只出现一次 |
| `application.yaml` 已有 `oryxos.providers` / `search` / `memory` | `oryxos-boot/src/main/resources/application.yaml` | 新增 `oryxos.author.*` 两块，沿用空默认值风格 |
| `docs/prompt/prompt.md` 不存在，只有 `01.md` | `ls docs/prompt/` | 本节补写；常量与文件同步由 `GenerateTest` 判 |

---

## 决策

### D1 — 模块落位：编排 / 存储 / 监听全在 core

`AgentLifecycleService`、`AgentStore`、`WorkspaceWatcher`、`AgentView`、`FileNode`、`ToolExecutionContext` 放 `oryxos-core`；`AgentApiController`、`WorkspaceApiController`、请求 DTO 放 `oryxos-web`。

理由：编排只依赖 `ProfileLoader` / `ProfileRegistry` / `AgentScheduler` / `SessionService` / `AgentService` / `LlmClient` / `ToolCatalog`，这些全在 core；若放 web，`oryxos-cli` 的命令体（watcher 启动）反而要依赖 web 的内部类。core 不反向依赖任何能力模块，这条不破。

### D2 — 不新建 `AgentLoader`；`ProfileLoader.parse(String, String)`

课件 §5.2.4 写 `AgentLoader.parse`。本仓的目录派生器是 `ProfileLoader`（第 29 节裁决 4）。新增：

```java
public Profile parse(String agentMarkdown, String agentName)
```

它走与磁盘路径完全相同的 `parseAgentMarkdown` → `toProfile`，因此「生成草稿能通过校验」与「保存后能注册」是同一个判据。**不写临时文件再读**——那会引入无谓 IO 与清理责任。

### D3 — 创建用模板，生成用模型

按课件 §5.1：`POST /agents` 只收 `{name, description}`，后台用模板脚手架出 `AGENT.md` + `REFERENCE.md` + `scripts/` + `skills/`；`POST /agents/{name}/generate-files` 才对**已存在** Agent 调模型重生成。

理由：创建必须可复现、不能依赖模型；生成必须人在环里预览。两者语义分开，测试也更好钉（create 不 mock `LlmClient`）。

### D4 — 作者模型配置与 503

新增配置键：

```yaml
oryxos:
  author:
    provider: ${ORYXOS_AUTHOR_PROVIDER:}
    model: ${ORYXOS_AUTHOR_MODEL:}
```

- `provider` 留空 → 取 `ProviderProperties.providers` 第一个的 `name`（装配时解析，运行期不再猜）。
- `model` 留空 → `generate-files` 抛 `AgentAuthorUnavailableException`（core，`RuntimeException`），`GlobalExceptionHandler` 映射 503，消息「未配置作者模型：请设置 oryxos.author.model」。**MUST NOT 发 `model=null`**。
- 为什么不在 core 抛 `ProviderUnavailableException`：它是 provider 模块类型，core 依赖它会造成反向依赖（章程硬约束）。新增一个 core 侧异常类型比破坏依赖方向便宜。

生成用 `Profile`：`new Profile("__author__", "agent author", Identity.EMPTY, new ProviderSpec(authorProvider, authorModel, null), List.of(), List.of(), List.of(), List.of(), List.of(), Settings.DEFAULT)`；`sessionId` 用 `"agent-author:" + UUID.randomUUID()`，保证 `llm_calls` 有可追溯会话（与第 27 节 `invoke-exec:<uuid>` 同一先例：永不落 `sessions`，不构成第二处持久化拼接，H4 ④）。

**调用方与产出草稿的 provider 必须分开**：调用 `LlmClient.chat` 用作者配置（`oryxos.author.provider/model`）；模型产出的 `AGENT.md` 文本里 frontmatter 的 `provider` 字段按 FR-010 改写为该 Agent 现有 provider。两者混用会让 mock 档 Agent 的生成调用打到 mock 的固定脚本。

### D5 — per-agent 记忆：上下文 + 三处改动

- `ToolExecutionContext`（core `tool` 包）：`ThreadLocal<String>`，`set` / `current` / `clear` / `callAs(String, Supplier<T>)`；`set(null)` 视同 `clear()`；`callAs` 保存旧值 → 设置 → 执行 → `finally` **恢复旧值**（异常路径同样恢复，可嵌套）。
- `ToolExecutor.execute`：用 `ProfileContext.current()` 取 Agent 名，`ToolExecutionContext.callAs(name, () -> ...)` 包住 `tool.execute`。**不改 `OryxTool` 签名**。
- `MemoryServiceImpl.longTermMemory(Profile)`：`callAs(profile.name(), store::load)`。
- `MarkdownMemoryStore`：新增私有 `memoryFile()`：`ToolExecutionContext.current()` 非空 → `oryxosDir/agents/<name>/MEMORY.md`；否则原全局路径。所有读写都经它。
- `partition()` 无参：由调用方（web 端点）包 `callAs`。

边界：SQLite / Mem0 档**不隔离**，仍全局；`MemoryStoreContractTest` 一行不改（它没有 Agent 上下文，走全局路径，行为与今天一致）。这条与第 22 节「记忆全局单份」的旧契约冲突，**已在 spec Clarifications 记录为用户裁决的有意覆盖**，交付时在 `CLAUDE.md` / 第 22 节文档挂带日期修订注。

### D6 — 固定管理台会话

`channel = "admin"`、`user = "console"`、`profile = <name>`。`SessionService.getOrCreate` 的内部拼接规则不动，结果恒为 `admin:console:<name>`。

- `GET /agents/{name}/session`：`getOrCreate` 后转 `SessionView`（最近 ≤100 条）。
- `POST /agents/{name}/session/messages`：取同一条；已归档 → 400（与 `SessionApiController` 同口径）；`agentService.process` 后 `sessionService.save`。

### D7 — 工作区路径守卫

`AgentStore.resolveSafe(String relative)`：

1. 拒绝 `null` / 空白 / 含 `\0`；
2. `Path.of(relative)` 若是绝对路径 → 拒；
3. `root.resolve(relative).normalize()` 必须 `startsWith(root)`；
4. 不额外限制 `.oryxos` 下的子目录（课件原文「落在 `.oryxos/` 内」）。

`WorkspaceApiController` 的 `path` 参数相对 `.oryxos` 根（如 `agents/foo/AGENT.md`）。tree 的 `path` 也用同一坐标系。

### D8 — `WorkspaceWatcher` 的线程模型

- `start()`：先扫 `agents/` 下所有直接子目录 → `lifecycle.register`（每个 try/catch，坏目录 WARN）；再 `FileSystems.getDefault().newWatchService()` 注册 `agents/` 的 `ENTRY_CREATE|ENTRY_MODIFY|ENTRY_DELETE`。
- 一个 `new Thread(...)`，`setDaemon(true)`，名 `oryxos-watcher`；循环 `poll(200ms)`。**不是线程池、不是 Reactor、不是 `CompletableFuture`**（H4 ⑤）。
- `ENTRY_CREATE`/`ENTRY_MODIFY` → 目录存在则 `register`；`ENTRY_DELETE` → `unregister(name)`。事件处理独立 try/catch，异常只 WARN。
- **拷贝竞态**：`cp -a` 的 `ENTRY_CREATE` 早于内部 `AGENT.md` 落盘，首次 `register` 会因缺 `AGENT.md` 失败；失败目录进入 pending 队列，**10 次 × 300ms** 重试（按目录名去重，主循环 `poll` 驱动），耗尽才 WARN。**MUST NOT 新开线程 / 定时器 / 线程池**——重试仍在同一 daemon 线程内。
- `stop()`：`watchService.close()` + `thread.interrupt()`；测试用。

### D9 — `AgentScheduler.unregisterProfile(Profile)`

公开方法，内部与第 29 节 `registerProfile` 的取消逻辑共用同一段归属判定（按 `RegisteredTask.profile().name()` 精确相等）。**MUST NOT 用 `<Agent>#` 前缀**（第 29 节已修的 bug）。

### D10 — 删除 = 归档

`archive/<name>`；若已存在，目标名 `archive/<name>-<epochMillis>`。**不物理删**，`delete` 只用于 create 回滚（此时目录刚由本次创建）。

### D11 — `archive/` 目录

`Workspace` 增加 `archiveDir()`；`oryxos init` 创建（幂等，不覆盖）；`AgentStore.archive` 也 `Files.createDirectories` 懒建，保证老工作区可用。

### D12 — 管理台与判卷

- `ProfilesView.vue` 承担列表 + 新建 + 详情五页签；导航移除「长期记忆」（`MemoryView.vue` 删除）。
- `api.js` 增加 `del(path)`。
- `AdminConsoleSourceTest` 重写写边界：
  - `api.js` 必须含 `'POST'` / `'PUT'` / `'DELETE'`，仍禁 `PATCH`；
  - `ProfilesView.vue` 是唯一允许 `post(` / `put(` / `del(` 的视图，且写路径必须以 `/api/v1/agents/` 或 `/api/v1/workspace/file` 开头；
  - 其余视图逐文件禁写方法；
  - **保留 `noWriteEntryPointText` 方法名**，把扫描范围从「全部视图」收窄为「只读视图」（Overview / Sessions / Tools / Schedules / Status）——它禁「新建/删除/编辑/保存/提交」字样，收窄后与 `ProfilesView.vue` 的写按钮不再冲突；`ProfilesView.vue` 由端点白名单断言覆盖。**断言条数与强度不降，MUST NOT 整条删除**（FR-024），在验收报告里留痕。
- `VIEW_ENDPOINTS`：移除 `MemoryView.vue`，`ProfilesView.vue` 改判 `/api/v1/agents`，`OverviewView.vue` 继续读既有四端点。

### D13 — `/profiles` 保留

`GET /api/v1/profiles` 第 26 节已交付，含 `valid/error` 诊断信息，管理台概览页仍在用。本节不删、不改；Agent 管理页改读 `/api/v1/agents`。

### D14 — 生成产物形状

`generate-files` 返回 `Map<String, String>`，当前只含 `"AGENT.md"`。剥围栏规则：若首行以 ``` 开头、末行以 ``` 结尾，去掉这两行；再 `strip()`。生成后把该 Agent 的 `provider` 覆盖为现有 `AGENT.md` 里的 provider（若该 Agent 已存在），避免模型写错厂商导致草稿不可跑。

### D15 — DTO / 请求体

| 端点 | 请求体 |
|---|---|
| `POST /agents` | `CreateAgentRequest(String name, String description)` |
| `PUT /agents/{name}` | `UpdateAgentRequest(String agentMarkdown)` |
| `POST /agents/{name}/generate-files` | `GenerateFilesRequest(String description)` |
| `POST /agents/{name}/files` | `SaveFilesRequest(Map<String,String> files)` |
| `POST /agents/{name}/session/messages` | 复用 `MessageRequest` |
| `POST /workspace/file` | `WriteFileRequest(String path, String content)` |

`AgentView` 放 core，字段 `name / description / providerName / providerModel / tools / schedules`；web 直接返回它（record 自带 JSON 序列化）。`FileNode` 放 core，字段 `name / path / directory / children`。

### D16 — 名称校验

`^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$`，且不允 `.` / `..`。拒绝 `/`、`\`、空格、中文——目录名即 `Profile.name`，也是 URL 段，限制在 URL 安全集合内。

### D17 — 错误语义

| 场景 | 异常 | HTTP |
|---|---|---|
| name 已存在 / 名称非法 / AGENT.md 非法 / tools 未注册 / provider 未知 | `IllegalArgumentException`（消息带具体原因） | 400 |
| 查/改/删不存在的 Agent | `ProfileNotFoundException` | 404 |
| 作者模型未配置 | `AgentAuthorUnavailableException` | 503 |
| 路径穿越 | `IllegalArgumentException("path escapes workspace: ...")` | 400 |

所有响应走既有 `ApiResponse`。

### D18 — 详情页不新增路由

管理台的 `router.js` 是自实现的极简路由，`ROUTES` 是静态清单；加 `/agents/:name` 需要引入前缀匹配与参数解析。本轮把详情放在 `ProfilesView.vue` 内部（`selected` ref），点「详情」切换视图，返回按钮回列表。URL 不变，刷新回列表——可接受，记已知边界。

### D19 — 生成提示词落点

`docs/prompt/prompt.md` 标题为 `AGENT_AUTHOR_PROMPT`，内容与 core 的 `AgentAuthorPrompt.PROMPT` 逐字一致。`GenerateTest` 读文件并断言相等——文档与代码不同步会红。

### D20 — 端点计数

第 28 节 15 → 本节 −1（`GET /api/v1/memory`）+13 = **27**。`AgentApiController` 从 1 个方法扩到 13 个（含 invoke）；`WorkspaceApiController` 3 个；控制器总数**仍是 7**（删 `MemoryApiController`、加 `WorkspaceApiController`，一进一出）。文档同步：README / 官网首页 / 官网文档页的计数从 15 改为 27。

---

## 未决 / 已知边界

1. `/workspace/file` 根是 `.oryxos`，可读写数据库文件——按课件口径，扩展阶段随鉴权收。
2. `WatchService` 非递归，改子文件不会自动重注册；`AGENT.md` 正文改动即时生效（`ContextLoader` 每轮重读），`schedules` 改动走 API。
3. SQLite / Mem0 记忆不隔离。
4. 生成只产 `AGENT.md`，不产脚本。
5. 详情页不占独立 URL。
