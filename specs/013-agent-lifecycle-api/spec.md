# Feature Specification: 动态管理 Agent —— 一句话 / 一次 API / 丢一个目录即上线

**Feature Directory**: `specs/013-agent-lifecycle-api`

**Git Branch**: `class-30`

**Created**: 2026-10-21

**Status**: Draft

**Input**: User description: "第30节需求：动态管理 Agent——一句话/一次 API/丢一个目录即上线，全程免重启"（课件：`docs/class/第30节：动态管理 Agent 一句话生成、上传即上线.md`，**以第五部分「实现回写」为最终口径**；章节依据 `docs/TechnicalSolution.md` §11.3 ~ §11.4，与 §11.3 的差异见 Clarifications）

## Clarifications

### Session 2026-10-21

本节开工前（H0 依赖核对阶段）发现课件、技术方案与既有契约三方不一致，触发软门禁 ③（课件 ↔ 技术方案冲突）、①（新增对外概念）、④（修改前序节公共接口）。已报用户裁决，下列五条是后续 plan / tasks / 实现的硬约束。

- Q: 第 30 节按哪一版基线实现？课件正文分两层——一~四（原始设计）与五（实现回写，明写「以实现为准」）；`docs/TechnicalSolution.md` §11.3 与前者一致、与后者不一致。 → A: **全按课件 §五**。端点 13 个、模板脚手架创建 + `generate-files`、可编辑工作区、每 Agent 专属记忆、固定管理台会话、5 tab 管理台。与 §11.3 的每处差异在验收报告里逐条留痕，不回写技术方案原文（按本仓惯例挂带日期修订注）。
- Q: 课件 §5.2.1 把长期记忆从「全局单份」改为「每 Agent 一份」，直接推翻第 22 节 FR-023 与 CLAUDE.md 的既定契约（「记忆全局单份、不按 Agent 隔离」）。是否授权？ → A: **授权**，三项一并做：① 新增 `ToolExecutionContext`（core，`ThreadLocal<String> agentName`），`ToolExecutor` 执行工具前后置入/清除；② 改 `MemoryServiceImpl` / `MarkdownMemoryStore` 走 per-Agent 路径，无上下文时回退全局路径（三档 SPI 与契约测试不得改）；③ 移除 `GET /api/v1/memory`，改 `GET /api/v1/agents/{name}/memory`。第 22 节文档挂带日期修订注，MUST NOT 重写原文。
- Q: 课件 §4 harness 表里的 `GenerateTest` 测的是 `POST /agents/generate`，而 §5.1 已取消该端点。 → A: **就地映射到新语义**：`GenerateTest` 测 `generate-files`（草稿可被既有解析器解析、不落盘不注册、非法产出 400 + 可读原因）；`AgentApiControllerTest` 覆盖新端点。方法名英文、`@DisplayName` 保留课件原文。
- Q: 课件示意里的 `AgentLoader` / `profileRegistry.get(name)` / `providerService.complete(...)` 在本仓都不存在。 → A: **按本仓既有落点**——`AgentLoader` → `ProfileLoader`（第 29 节裁决：MUST NOT 新建 `AgentLoader` 第二真相源）；`get` → `find`；`complete` → `chat(sessionId, Profile, Prompt)`（课件 §5.1 自己已更正）。
- Q: 课件 §5.3 说生成用系统提示词在 `docs/prompt/prompt.md`（`AGENT_AUTHOR_PROMPT`），但该文件不存在（`docs/prompt/` 下只有 `01.md` 提示词记录）。 → A: **本节补写并入库** `docs/prompt/prompt.md`（`AGENT_AUTHOR_PROMPT` 常量文本），属课件点名的交付物。

## 范围说明

第 29 节确立了「一个 Agent = 一个目录」，并交付了运行时注册能力（索引可增删、调度器可按单 Agent 注册并持有句柄表），但录入仍停在「登上服务器往目录里放东西 + 重启才生效」：业务系统够不着、管理台只能看不能管。本节把 Agent 目录做成**唯一且可实时生效**的资源真相源，并把它开放为 REST 资源与管理台页面，让「说一句话 / 调一次 API / 丢一个目录」都能上线一个会自动定时运行的 Agent。

**贯穿始终一条原则：不新增能力，只做编排。** 派生、校验、注册、定时全是第 29 节立好的；本节新增的是一个**编排者**（把步骤按序串起来、失败回滚）、一个**实时监听器**（把文件系统的变更喂给同一段注册代码）、一组 REST 端点与两页管理台。

**已由前序节交付、本节只消费不改动的部分**（依赖核对结论）：

| 依赖 | 本仓现状 |
|---|---|
| 单目录派生 `ProfileLoader.deriveProfile(Path)` / `inspect(Path)` | 第 29 节 ✅ |
| 运行时索引 `ProfileRegistry.register/remove/exists/find/all` | 第 29 节 ✅（按名查询是 `find`，不是 `get`） |
| 按单 Agent 注册定时 + 句柄表 `scheduledTasks` | 第 29 节 ✅；`unregisterProfile` 本节补 |
| 统一信封 `ApiResponse` + `GlobalExceptionHandler` | 第 26 节 ✅ |
| `AgentApiController` 的 `POST /agents/{name}/invoke` | 第 26 节 ✅（本节在同类上加方法） |
| `ProviderService.chat(sessionId, Profile, Prompt)` + `llm_calls` 审计 | 第 16 节 ✅（无 `complete`） |
| 三档记忆后端 + `MemoryService` 门面 | 第 22 节 ✅（本节改 per-Agent 路径） |
| `ToolExecutor` 工具执行 + 审计 | 第 17 节 ✅（本节加执行上下文置入/清除） |
| `SessionService.getOrCreate` + `SessionSummaryView` | 第 18/27 节 ✅ |

**本节真正新增 / 改造的部分**：

- `AgentLifecycleService`（编排：create / get / list / update / delete / register / generateFiles / saveFiles）
- `WorkspaceWatcher`（启动全量扫 + JDK `WatchService` 实时监听）
- `AgentApiController` 扩展（create / list / get / update / delete / generate-files / files / memory / session / session messages）
- `WorkspaceApiController`（tree / read file / write file，三个都防目录穿越）
- `AgentScheduler.unregisterProfile(Profile)`
- `AgentStore`（Agent 目录的写 / 归档 / 删除；模板脚手架）
- `ToolExecutionContext`（core）+ `ToolExecutor` 置入/清除
- 记忆 per-Agent：`MemoryServiceImpl` / `MarkdownMemoryStore` 改造；移除 `GET /api/v1/memory`
- 固定管理台会话：`GET /agents/{name}/session`、`POST /agents/{name}/session/messages`
- 配置键 `oryxos.author.provider` / `oryxos.author.model`
- `docs/prompt/prompt.md`（`AGENT_AUTHOR_PROMPT`）
- 管理台：Agent 列表 + 详情 5 tab，移除独立「工作区」菜单（原无此菜单，新增即按 5 tab 形态）
- 五个 harness 测试类

### 明确不做（边界）

- **认证鉴权**：谁能建 Agent / 读文件——内网假设，扩展阶段随 API Key / RBAC 补
- **带脚本的复杂 Agent 走 multipart / zip 上传**：本版 JSON create 只做模板脚手架，带脚本的走手工丢目录
- **Agent 启用 / 停用状态位**
- **创建时的 dry-run 试跑**
- **Agent 版本历史**
- **一句话生成的多轮追问式细化**
- **通知渠道实体管理**：`notify_channels` 的 REST CRUD 与管理台渠道页仍归扩展阶段（第 26 节挂账未清）
- **文件浏览器在线编辑**在课件一~四里被排除，但课件 §5.2.3 已明确改为可编辑——本 spec 按 §5 实现
- **Agent 内存 / 会话的跨重启持久性之外的新表**：本节不新增 SQLite 表

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 创建 Agent 即上线（Priority: P1）

业务系统调 `POST /api/v1/agents {name, description}`，系统按模板脚手架出完整目录（`AGENT.md` + `scripts/` + `skills/` + `REFERENCE.md`）、派生、注册；不重启，`GET /api/v1/agents` 立刻可见，带 `schedules` 的到 cron 点自己跑。任一步失败，已写目录被回滚，系统里不留半个 Agent。

**Why this priority**: 这是「上传即上线」的主干；没有它，本节只剩文件浏览器。

**Independent Test**: 在临时工作区启动 Spring 上下文，调 create 后立刻查 `/api/v1/agents` 与 `ProfileRegistry`；再注入一次注册失败，断言目录被删、索引干净。

**Acceptance Scenarios**:

1. **Given** 系统已启动、`weather-daily` 不存在，**When** `POST /api/v1/agents {name:"weather-daily", description:"..."}`，**Then** 返回 200 与 Agent 视图；不重启，`GET /api/v1/agents` 含它；`.oryxos/agents/weather-daily/` 下有模板脚手架的四部分。
2. **Given** `weather-daily` 已存在，**When** 再次 create 同名，**Then** 400，且**一个目录都不写**（磁盘无新文件）。
3. **Given** create 走到注册时抛异常，**When** 请求返回错误，**Then** 已写目录被删除、索引里没有它、定时一个都没注册。
4. **Given** create 成功、随后经 `PUT /api/v1/agents/{name}` 写入带 `schedules` 的 `AGENT.md`，**When** 到 cron 点，**Then** Agent 自己跑一次（真链路人工项；模板脚手架本身不含 `schedules`，故本场景以 update 为前提）。

---

### User Story 2 - 丢一个目录也即上线（Priority: P1）

不走 API，直接往 `.oryxos/agents/` 拷一个 Agent 目录（scp / git / 编辑器）。几秒内它被实时监听拾取、登记进索引，`GET /api/v1/agents` 立刻出现；删掉目录，几秒内被注销。

**Why this priority**: 「两条录入路径、一套行为」是本节的核心承诺；没有实时监听，「上传即上线」名不副实。

**Independent Test**: 启动上下文后实际往目录里写一个合法 Agent 目录，轮询（有超时上限）断言索引出现；再删目录，断言索引消失；同时放一个坏目录，断言好目录照常、坏目录只告警。

**Acceptance Scenarios**:

1. **Given** 系统已启动且监听器在跑，**When** 手工把一个合法 Agent 目录写进 `.oryxos/agents/`，**Then** 无需重启，几秒内出现在 `GET /api/v1/agents`。
2. **Given** 某 Agent 已登记，**When** 手工删除它的目录，**Then** 几秒内从索引消失、它的定时句柄被取消。
3. **Given** 目录里同时有一个坏 `AGENT.md` 与一个好目录，**When** 监听事件依次到达，**Then** 坏目录只告警不抛穿监听线程，好目录照常登记。
4. **Given** API create 与手工丢目录两条路径，**When** 各自登记同一个结构的 Agent，**Then** 二者调用的是同一个 `register(agentDir)`（harness 钉死）。

---

### User Story 3 - 一句话生成 Agent 文件（Priority: P2）

运营在管理台填一句描述（「每天早上九点查北京天气，把穿搭建议发到团队群」），系统用 LLM 生成一组文件草稿（至少 `AGENT.md`），**原样返回预览、不落盘、不注册**；可改，尤其 cron 与 tools；确认后 `POST /agents/{name}/files` 保存并生效。

**Why this priority**: 降低运营门槛的差异化能力；但它依赖 US1 的创建/保存路径，故排 P2。

**Independent Test**: mock LLM 返回一份合法 `AGENT.md`，断言返回的映射里有它、磁盘无变化、索引无变化；再让 mock 返回非法内容，断言 400 且原因可读。

**Acceptance Scenarios**:

1. **Given** `weather-daily` 已存在，**When** `POST /agents/weather-daily/generate-files {description:"..."}`，**Then** 返回「相对路径 → 内容」，其中 `AGENT.md` 能通过既有解析器校验；磁盘与索引均无变化。
2. **Given** 生成调用的模型产出不是合法 `AGENT.md`，**When** 端点返回，**Then** 400，`message` 含可读原因（点名解析失败的位置）。
3. **Given** 模型在正文外包了 ``` 代码围栏，**When** 生成结果返回，**Then** 围栏被剥掉、内容可直接解析。
4. **Given** `oryxos.author.model` 为空，**When** 调 generate-files，**Then** 返回可读的 503，且**不向 Provider 发 `model=null` 的请求**。
5. **Given** 用户改过草稿，**When** `POST /agents/{name}/files {files}` 保存，**Then** 写盘并重注册（`schedules` 变则先注销旧句柄），下一次调用即刻使用新配置。

---

### User Story 4 - 工作区文件浏览与编辑（Priority: P2）

运维在管理台「文件」tab 里像文件浏览器一样看 `.oryxos/agents/` 与 `.oryxos/archive/` 的目录树，点开一个文件读内容、改内容并保存。任何试图越出工作区根的路径都被 400 挡住。

**Why this priority**: 管理台「能管」的必要组成；安全边界（防目录穿越）与 US1 同级重要，但功能上依附于 Agent 目录。

**Independent Test**: tree 断言 agents/archive 两个根与可钻取层级；`file?path=../../etc/passwd` 与各种编码变体断言 400；正常文件读回原文；写文件同样防穿越。

**Acceptance Scenarios**:

1. **Given** 工作区有两个 Agent 与一个归档，**When** `GET /workspace/tree`，**Then** 返回 agents/ 与 archive/ 两个根，Agent 目录下可展开 `AGENT.md` / `scripts/` / `skills/` / `REFERENCE.md`。
2. **Given** 任意客户端，**When** `GET /workspace/file?path=../../etc/passwd`（及其他越界形态），**Then** 400，且**不读取该文件**。
3. **Given** 一个合法文件路径，**When** `GET /workspace/file?path=...`，**Then** 返回文本内容。
4. **Given** 编辑 `agents/<name>/AGENT.md` 并保存，**When** `POST /workspace/file`，**Then** 走 update 路径（写 + 校验 + 重注册），修改即时生效；写其他文件则直接写盘。
5. **Given** 写一个越界路径，**When** `POST /workspace/file`，**Then** 400，磁盘无变化。

---

### User Story 5 - 每个 Agent 的眼睛：专属记忆与固定会话（Priority: P3）

在 Agent 详情里，「会话」tab 显示这个 Agent 的固定会话（`admin:console:<name>`），可直接发消息、上下文累积；「记忆」tab 显示它自己的 `MEMORY.md`（由 `save_memory` 写入）。不同 Agent 的记忆与会话互不串。

**Why this priority**: 管理台体验的完善项，依赖 US1/US4；不阻塞主干闭环。

**Independent Test**: 两个 Agent 分别 save_memory，断言各自记忆文件只有自己的内容、无上下文时回退全局路径；同一 Agent 连发两条消息落在同一条 session 且历史累积。

**Acceptance Scenarios**:

1. **Given** `weather-daily` 存在，**When** 它的 Agent 跑一次并调 `save_memory`，**Then** 写入 `.oryxos/agents/weather-daily/MEMORY.md`，`GET /agents/weather-daily/memory` 可见；`news-daily` 的记忆不受影响。
2. **Given** 工具在没有 Agent 上下文的路径下读记忆（如测试直调），**When** Markdown 档读/写，**Then** 回退全局 `.oryxos/memory/MEMORY.md`（向后兼容）。
3. **Given** `weather-daily` 已有一条固定会话，**When** 连发两条消息，**Then** 两次落在同一条 `admin:console:weather-daily`，历史累积。
4. **Given** 全局 `GET /api/v1/memory`，**When** 请求，**Then** 404（端点已移除）。

---

### Edge Cases

- 创建名含路径分隔符 / `..` / 空白的 Agent：MUST 400，MUST NOT 写到工作区外。
- `AGENT.md` 里 `provider` 不在已配置厂商映射表、`tools` 不在注册表：MUST 400，且**一次模型调用都不发生**。
- 监听器收到自己刚写的目录事件（API create 后）：注册必须是幂等的替换语义，不得出现两条定时。
- 两个事件同时到达（新增 + 修改）：以最后一次成功解析为准，失败不得把已登记的好 Agent 弄没。
- 删除时定时正在跑：先注销句柄（`cancel(false)` 不打断在跑的那一次），再移索引，再归档。
- 归档目录重名：MUST 用带时间戳的唯一目录名，MUST NOT 覆盖上一次归档。
- `AGENT.md` 被编辑成非法内容：保存（files / workspace write）MUST 400 且不写坏目录（原有文件保持原样）。
- 读一个二进制 / 超大文件：MUST 有明确上限或明确拒绝，MUST NOT 把进程内存打爆。
- 生成时模型返回空串或只有围栏：MUST 400 + 可读原因。
- 固定会话在 Agent 删除后再访问：MUST 404（Agent 不存在），MUST NOT 500。

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 MUST 提供 Agent 生命周期编排：create（`{name, description}` → 模板脚手架完整目录 → 派生 → 注册 → 有 `schedules` 则注册定时）、get、list、update、delete。
- **FR-002**: create MUST 在 name 已存在时于**第一步**拒绝（400），且 MUST NOT 写任何目录；任一步失败 MUST 回滚已写目录，MUST NOT 留下半个 Agent。
- **FR-003**: create 与实时监听 MUST 调用**同一个**注册方法（派生 + 索引注册 + 定时注册）；MUST NOT 各写一套。
- **FR-004**: 系统 MUST 在启动时全量扫描 `.oryxos/agents/`，之后用 JDK `WatchService` 实时监听该目录；目录新增/修改 → 注册，目录删除 → 注销。
- **FR-005**: 单个坏目录的处理失败 MUST 告警并跳过，MUST NOT 拖垮监听线程；监听线程 MUST 是基础设施守护线程，MUST NOT 把异步编程模型引入请求链路。
- **FR-006**: update MUST 覆写 `AGENT.md` 并重注册；`schedules` 变化时 MUST **先注销旧句柄、再注册新的**，MUST NOT 新旧并存。
- **FR-007**: delete MUST 按「先注销定时 → 移出索引 → 整目录归档到 `.oryxos/archive/`」的顺序执行；MUST NOT 物理删除；归档目标 MUST 为 `.oryxos/archive/<name>`，该目录已存在时 MUST 改用 `<name>-<epochMillis>`，MUST NOT 覆盖既有归档。
- **FR-008**: `AgentScheduler` MUST 提供按 Profile 注销定时的能力（遍历该 Agent 的 `schedules`、取消句柄表里的 `ScheduledFuture`、移出双表），MUST NOT 动任务锁表。
- **FR-009**: `POST /api/v1/agents/{name}/generate-files {description}` MUST 经一次 LLM 调用产出文件草稿，MUST 落 `llm_calls` 审计，MUST NOT 落盘、MUST NOT 注册。
- **FR-010**: 生成结果 MUST 通过既有 `AGENT.md` 解析校验；非法 MUST 400 且原因可读；模型多吐的代码围栏 MUST 被剥掉；输出 `provider` 若该 Agent 已存在 MUST 沿用其 provider。
- **FR-011**: 生成用 provider / model MUST 由配置键 `oryxos.author.provider` / `oryxos.author.model` 决定；provider 缺省取已配置厂商的第一个；model 为空 MUST 返回可读的 503，MUST NOT 发 `model=null` 请求。
- **FR-012**: `POST /api/v1/agents/{name}/files {files}` MUST 先校验 `AGENT.md` 可解析（非法 400、不写坏目录）→ 写盘 → 重注册（`schedules` 变更先注销旧句柄）；写后 MUST 即时生效。
- **FR-013**: `GET /api/v1/workspace/tree` MUST 返回 `.oryxos/agents/` 与 `.oryxos/archive/` 的目录树；`GET /api/v1/workspace/file?path=` MUST 读文本（MUST 设 **1 MiB** 上限，超限 400，MUST NOT 把超大文件整份读入内存）；`POST /api/v1/workspace/file {path, content}` MUST 写文本。
- **FR-014**: 三个工作区端点 MUST 防目录穿越：路径 MUST 相对 `.oryxos/`（本节「工作区根」逐字指既有 `Workspace.root()` 返回的 `.oryxos/` 目录，MUST NOT 再叠一层 `.oryxos`），解析为绝对路径并 normalize 后 MUST 仍落在该根内，越界 MUST 400 且 MUST NOT 发生任何读写。
- **FR-015**: 写 `agents/<name>/AGENT.md` MUST 走 update 路径（写 + 校验 + 重注册）；写其他文件 MUST 直接写盘。
- **FR-016**: 长期记忆 MUST 支持 per-Agent（Markdown 档落 `.oryxos/agents/<name>/MEMORY.md`）；工具执行时 MUST 能获知当前 Agent（执行上下文）；读路径 MUST 在委托后端前后临时置入 Agent 名并复原。
- **FR-017**: 无 Agent 上下文时 Markdown 档 MUST 回退全局 `.oryxos/memory/MEMORY.md`；`LongTermMemoryStore` SPI 与三档契约测试 MUST NOT 改动（向后兼容）。
- **FR-018**: 系统 MUST 提供 `GET /api/v1/agents/{name}/memory`；全局 `GET /api/v1/memory` MUST 被移除（404）。
- **FR-019**: 每个 Agent MUST 有一条固定管理台会话（`channel=admin`、`user=console`、`profile=<name>`），`getOrCreate` 幂等；`GET /api/v1/agents/{name}/session` MUST 返回 sessionId + profileName + 最近 ≤100 条消息（按时间正序，与第 26/27 节同一口径）。
- **FR-020**: `POST /api/v1/agents/{name}/session/messages {content}` MUST 往固定会话发消息并触发与 invoke 相同的 `AgentService` 入口；上下文 MUST 累积。
- **FR-021**: 错误语义 MUST 沿用第 26 节：name 已存在 / 字段非法 / provider 或 tool 不存在 → 400，同类 400 之间由 `message` 区分（信封 `code` 恒等 HTTP 状态码，MUST NOT 造业务错误码字段）；查改删不存在的 Agent → 404；作者模型未配置 → 503；统一 `ApiResponse` 信封。
- **FR-022**: 被拒的请求（400 / 404） MUST NOT 触发任何模型调用或工具执行（审计表零行）。
- **FR-023**: 管理台 MUST 提供 Agent 列表（含「新建 Agent」= name + description）与 Agent 详情 5 tab（基本信息 / 生成 / 文件 / 会话 / 记忆）；MUST NOT 保留独立「工作区」菜单。
- **FR-024**: 管理台写操作的判据 MUST 精确开写口（只允许 Agent 相关页调用写端点），其余页面 MUST 保持只读；`AdminConsoleSourceTest` 的断言 MUST 只收紧、MUST NOT 删减。「不得删减」指断言条数与强度不降低：原「全站禁写按钮文案」断言 MUST 收窄为「只读页禁写按钮文案」（方法保留），`ProfilesView.vue` 的写按钮由端点白名单断言覆盖，MUST NOT 整条删除。
- **FR-025**: 本节 MUST NOT 新建 `AgentLoader` 类；课件里的 `AgentLoader.deriveProfile` 在本仓的落点是 `ProfileLoader.deriveProfile`。
- **FR-026**: `docs/prompt/prompt.md` MUST 提供 `AGENT_AUTHOR_PROMPT`（生成用系统提示词），随仓库入库。

### Key Entities

- **Agent 目录（Agent）**：`.oryxos/agents/<name>/`，唯一真相源；含 `AGENT.md` + 可选 `scripts/` / `skills/`（软连接）/ `REFERENCE.md` / `MEMORY.md`。
- **Agent 视图（AgentView）**：对外呈现的 Agent 摘要与明细（name / description / provider / model / tools / schedules）。
- **文件节点（FileNode）**：工作区目录树的节点（路径 / 名称 / 类型 / 子节点），只覆盖 `agents/` 与 `archive/`。
- **生成文件草稿**：「相对路径 → 内容」的映射，仅预览，不落盘。
- **固定管理台会话**：`admin:console:<name>` 一条，归属该 Agent。
- **归档目录**：`.oryxos/archive/<name>/`（已存在时改用 `<name>-<epochMillis>/`），删除时整目录移入。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 通过 API 创建 Agent 后**无需重启**，下一次列表查询（≤ 1 秒内）即可看到它。
- **SC-002**: 手工把 Agent 目录拷进 `.oryxos/agents/` 后，**≤ 5 秒**内它出现在列表里；删除目录后同等时间内消失。
- **SC-003**: 创建失败（注册异常）后，磁盘上**不存在**该 Agent 目录，索引里**不存在**该 Agent，定时任务数**为 0**。
- **SC-004**: 目录穿越请求（含 `../` 与绝对路径变体）**100%** 被 400 拒绝，且目标文件读取次数为 0。
- **SC-005**: 两个 Agent 各自 `save_memory` 后，彼此的记忆文件**互不包含**对方内容；无上下文路径仍读写全局文件。
- **SC-006**: 管理台可完成「新建 → 预览 → 保存 → 看它自己跑 → 浏览文件 → 编辑 → 删除」全流程，无需手动改文件或重启。
- **SC-007**: `mvn clean verify` 全绿，前序各节测试零回归。

## Assumptions

- 单机、内网部署；不做认证鉴权与多租户（扩展阶段）。
- 工作区根逐字指 `.oryxos/` 目录（即既有 `Workspace.root()` 的返回值，基准目录下已叠过一层 `.oryxos`；MUST NOT 再 resolve 一次）；它由 `Workspace.current()` 决定（支持 `-Doryxos.root` 覆盖，测试用临时工作区）。
- 带脚本的复杂 Agent 仍走手工丢目录；JSON create 只做模板脚手架。
- 一次 ReAct 循环整体跑在一条虚拟线程上，`ThreadLocal` 执行上下文在同步模型下成立（无 Reactor / 异步）。
- 生成用模型由 `oryxos.author.*` 配置；未配置 model 时端点返回 503 而不是猜测。
- `WatchService` 在 macOS 上不监听子目录内文件的改动（课件 §5.2.3 已知），故 AGENT.md 的编辑走显式 update 重注册；监听器主要负责目录级增删。**2026-10-22 修订注（已推翻）**：该边界来自「只监听 `agents/` 一层」的实现选择，不是 `WatchService` 的固有限制。用户实跑要求直接编辑生效，`WorkspaceWatcher` 现为**每个 Agent 子目录单独注册 key**，`AGENT.md` 的 CREATE / MODIFY 在 300ms 静默期后走同一条 `register`（先取消旧定时句柄）；编辑器的「写临时文件 → 改名覆盖」产生的 DELETE 事件被忽略、保留旧运行态，坏内容保存失败也只告警不注销。PUT / 管理台保存仍是推荐路径（同步返回校验结果），但直接编辑不再需要重启。**同日第二处修订**：编辑被检测并重新登记成功后，还会**清空该 Agent 的调度会话历史**（`scheduler:scheduler:<name>`），否则模型会照着旧上下文里的工具参数原样重放、改了正文也看不到效果（用户实跑：把城市改成成都仍推北京）；启动扫描与 `PUT` / `saveFiles` **不清**历史。
- 前序节交付物的方法名按本仓实际（`find` / `chat` / `ProfileLoader`），不按课件示意改名。
