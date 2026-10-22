# 契约：Agent 生命周期 REST API

**Feature**: [spec.md](../spec.md) | **Plan**: [plan.md](../plan.md) | **Date**: 2026-10-21

> 所有端点都在 `/api/v1` 下，响应统一为 `ApiResponse{code,message,data,timestamp}`，`code` 与 HTTP 状态码恒等（第 26 节裁决 ①）。本节端点总数 **27**（第 28 节 15 − 全局记忆 1 + 本节 13）。

---

## 1. 端点总表

| # | 方法 | 路径 | 请求体 | 响应 `data` |
|---|---|---|---|---|
| 1 | `POST` | `/api/v1/agents` | `{name, description}` | `AgentView` |
| 2 | `GET` | `/api/v1/agents` | — | `AgentView[]` |
| 3 | `GET` | `/api/v1/agents/{name}` | — | `AgentView` |
| 4 | `PUT` | `/api/v1/agents/{name}` | `{agentMarkdown}` | `AgentView` |
| 5 | `DELETE` | `/api/v1/agents/{name}` | — | `null` |
| 6 | `POST` | `/api/v1/agents/{name}/invoke` | `{content}` | `InvokeResponse`（既有） |
| 7 | `GET` | `/api/v1/agents/{name}/memory` | — | `MemoryView` |
| 8 | `GET` | `/api/v1/agents/{name}/session` | — | `SessionView` |
| 9 | `POST` | `/api/v1/agents/{name}/session/messages` | `{content}` | `MessageResponse` |
| 10 | `POST` | `/api/v1/agents/{name}/generate-files` | `{description}` | `Map<String,String>` |
| 11 | `POST` | `/api/v1/agents/{name}/files` | `{files: {path: content}}` | `AgentView` |
| 12 | `GET` | `/api/v1/workspace/tree` | — | `FileNode[]` |
| 13 | `GET` | `/api/v1/workspace/file?path=` | — | `{path, content}` |
| 14 | `POST` | `/api/v1/workspace/file` | `{path, content}` | `null` |

> 上表 14 行中第 6 行是既有端点，故本节新增 13 个；「端点总数 27」按 HTTP 路径与方法的独立组合计（`GET /agents` 与 `GET /agents/{name}` 分开计）。

---

## 2. 逐端点行为

### 2.1 `POST /api/v1/agents`

- 校验顺序：名称格式 → 名称未被占用（索引或目录存在）→ provider 存在 → tools 已注册。**任何一步失败都不写盘**。
- 模板脚手架：`AGENT.md` + `REFERENCE.md` + 空 `scripts/` + 空 `skills/`。
- 成功后 `AgentStore.scaffold` → `AgentLifecycleService.register(Path)`；注册抛异常 → 删除刚写目录并原样上抛。
- 返回 `AgentView`。

**错误**：

| 场景 | HTTP | 消息示例 |
|---|---|---|
| name 为空 / 非法 | 400 | `Agent 名称非法: 'a/b'（只允许字母、数字、. _ -，且以字母或数字开头）` |
| name 已存在 | 400 | `Agent 已存在: 'weather'` |
| provider 不在 `oryxos.providers` | 400 | `未知厂商: 'foo'；已配置: [deepseek, mock]` |
| tool 未注册 | 400 | `工具未注册: 'read_pdf'` |

### 2.2 `GET /api/v1/agents`

- 读 `ProfileRegistry.all()`（按名排序）；只返回已注册的 Agent。
- 不读磁盘、不做额外校验。

### 2.3 `GET /api/v1/agents/{name}`

- 命中 → `AgentView`；未命中 → 404 `Agent 不存在: '<name>'`。

### 2.4 `PUT /api/v1/agents/{name}`

- 请求体 `agentMarkdown` 是**完整** `AGENT.md`（frontmatter + 正文）。
- `ProfileLoader.parse(markdown, name)` + provider/tools 校验，全部在写盘前完成。
- 写盘 → `unregisterProfile(旧)` → `register(新)`；`schedules` 变更后旧句柄已取消。
- 正文改动由 `ContextLoader` 每轮重读即时生效（第 29 节已钉死）。

### 2.5 `DELETE /api/v1/agents/{name}`

- 顺序：`unregisterProfile` → `registry.remove` → `store.archive`。任何一步失败，前面的步骤不回滚（停定时比保留半个 Agent 更安全），异常原样上抛。
- 归档目标 `.oryxos/archive/<name>`；已存在则 `<name>-<epochMillis>`。
- 审计表历史不动。

### 2.6 `GET /api/v1/agents/{name}/memory`

- Agent 不存在 → 404。
- 在 `ToolExecutionContext.callAs(name, ...)` 内调 `memoryService.partition()`。
- 返回 `MemoryView{core, archival, truncated}`（与第 26 节同一 DTO）。

### 2.7 `GET /api/v1/agents/{name}/session`

- `SessionService.getOrCreate("admin", "console", name)`。
- 返回 `SessionView`，`messages` 为最近 ≤100 条（正序）；没有消息时为空列表。

### 2.8 `POST /api/v1/agents/{name}/session/messages`

- `content` 非空、≤32KB，否则 400。
- 会话已归档 → 400（与 `SessionApiController` 同口径）。
- 调 `AgentService.process(session, content)` → `sessionService.save(session)` → 返回 `MessageResponse(sessionId, reply)`。
- Agent 不存在 → 404（处理前判 `registry.find`）。

### 2.9 `POST /api/v1/agents/{name}/generate-files`

- Agent 不存在 → 404（生成的是这个 Agent 的文件草稿）。
- `oryxos.author.model` 为空 → 503 `未配置作者模型：请设置 oryxos.author.model`。
- **调用方一律用作者配置**（`oryxos.author.provider` / `model`；provider 缺省取已配置清单第一个）构造 `authorProfile`，一次 `LlmClient.chat("agent-author:<uuid>", authorProfile, prompt)`；审计由 `ProviderService` 落 `llm_calls`。
- 剥代码围栏 → 把**产出草稿** frontmatter 的 `provider` 改写为该 Agent 现有 provider（调用方 provider MUST NOT 跟随该 Agent，否则 mock 档 Agent 的生成会打到固定脚本）→ `ProfileLoader.parse(text, name)`。
- 解析失败 → 400 `生成的 AGENT.md 无法解析: <原因>`。
- 成功 → `{"AGENT.md": "<文本>"}`；**不写盘、不注册**。

### 2.10 `POST /api/v1/agents/{name}/files`

- `files` 必须含 `AGENT.md`；缺 → 400。
- 每个 `path` 必须是相对路径、不越界（同一守卫）。
- 先 `parse(files["AGENT.md"], name)` + provider/tools 校验 → 写全部文件 → `unregisterProfile(旧)` → `register(新)`。
- 校验失败 → 400，**一个文件都不写**。

### 2.11 工作区三端点

见 [workspace-memory.md](./workspace-memory.md)。

---

## 3. `AgentView`

```json
{
  "name": "weather",
  "description": "每天查天气并推送",
  "providerName": "deepseek",
  "providerModel": "deepseek-chat",
  "tools": ["http_get", "notify"],
  "schedules": [{"cron": "0 9 * * *", "zone": "Asia/Shanghai", "message": "查天气"}]
}
```

字段类型：`schedules` 复用 `Profile.ScheduleSpec`，`zone` 可空、`message` 必填。

---

## 4. 错误映射（沿用第 26 节 `GlobalExceptionHandler`）

| 异常 | HTTP |
|---|---|
| `IllegalArgumentException`（含非法名称、冲突、非法 AGENT.md、未知 provider/tool、路径越界） | 400 |
| `ProfileNotFoundException` | 404 |
| `AgentAuthorUnavailableException`（新增） | 503 |
| `ProviderUnavailableException`（既有） | 503 |
| 其他 `RuntimeException` | 500 |

**被拒请求不得触发任何模型调用或工具执行**：校验全部在 `agentService.process` / `llmClient.chat` 之前。
