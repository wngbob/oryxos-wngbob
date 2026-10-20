# 契约：REST 端点（第 26 节）

**Feature**: `specs/009-web-service` | **Date**: 2026-10-19 | **上游契约**: `specs/000-runtime-kernel/contracts/rest-api.md`

本文件是本节 10 个端点的**逐字实现契约**。与 `specs/000-runtime-kernel/contracts/rest-api.md` 冲突时以**本文件**为准（本文件是它的本节修订版：字段名对齐裁决 ⑪、`/health` 措辞对齐裁决 ⑤、补两条错误文案）；`rest-api.md` 同步挂带日期（2026-10-19）修订注，原文不重写。

**通用约定**

- 基址：`/api/v1`，只在 `oryxos serve` 下提供（`chat` / `gateway` 不起 HTTP 容器）。
- 编码：UTF-8 JSON。请求 `Content-Type: application/json`。
- 信封：所有响应（成功与失败）都是 `{"code": <int>, "message": <string>, "data": <object|null>, "timestamp": <ISO-8601 offset date-time>}`；`code` = HTTP 状态码，与响应行状态码**恒等**。
- 线程：请求跑在 virtual thread 上，**全程同步阻塞**（无 SSE、无 WebSocket、无响应式类型）。
- CORS：`/api/**` 核心阶段全开放（`allowCredentials=false`）。
- 认证：**无**（内网假设，扩展阶段补 API Key + JWT）。

---

## 1. 会话管理（4 个）

### 1.1 `POST /api/v1/sessions` — 创建（或取回）会话

**请求**

```json
{ "profile": "daily-weather", "userId": "alice" }
```

| 字段 | 必填 | 约束 |
|---|---|---|
| `profile` | 是 | 非空；必须在 `ProfileRegistry` 里存在，否则 404 |
| `userId` | 否 | 空 / 缺省 → `"default"` |

**响应 200**

```json
{
  "code": 200, "message": "ok", "timestamp": "2026-10-19T18:00:00+08:00",
  "data": {
    "sessionId": "web:alice:daily-weather",
    "profile": "daily-weather",
    "channel": "web",
    "userId": "alice",
    "status": "active",
    "createdAt": "2026-10-19T18:00:00+08:00",
    "lastActiveAt": "2026-10-19T18:00:00+08:00",
    "archivedAt": null
  }
}
```

**语义**：会话标识 = `channel:userId:profile` 三元组派生（`SessionId.of`，**唯一拼接点**，H4 ④），因此同一 `(profile, userId)` 重复创建**取回同一个会话**（`SessionService.getOrCreate`）。`channel` 由服务端定死 `"web"`，请求体**不收** `channel`。

**错误**：400 `profile` 缺失；404 `agent not found: <name>`。

### 1.2 `POST /api/v1/sessions/{id}/messages` — 发消息

**请求**：`{ "content": "北京今天天气怎么样" }`

| 字段 | 必填 | 约束 |
|---|---|---|
| `content` | 是 | 非空；`length() ≤ 32768`（FR-008 防呆） |

**响应 200**：`{"code":200,"message":"ok","timestamp":"...","data":{"sessionId":"<id>","reply":"<Agent 最终答复>"}}`

**语义**：**汇入与 `oryxos chat` 完全相同的处理入口** `AgentService.process(session, content)`——MUST NOT 在 HTTP 层另起处理链路（FR-002）。Controller 只做「参数校验 / 响应包装 / 错误处理」，模型调用与审计全在链路内部。

**错误**：

| 情况 | 状态码 | `message` |
|---|---|---|
| `content` 空 / 缺 | 400 | 参数错误文案 |
| `content.length() > 32768` | 400 | `message exceeds 32KB limit` |
| 会话不存在 | 404 | `session not found: <id>` |
| 会话所属 Agent 的配置已不可用 | 404 | `agent not found: <name>` |
| 会话已归档 | 400 | `session is archived: <id>`（**零模型调用**，MUST NOT 静默复活） |
| Provider 调用失败 | 503 | `provider unavailable: <name>` |
| 其他内部错误 | 500 | `Internal server error` |

### 1.3 `GET /api/v1/sessions/{id}` — 查历史

**响应 200**

```json
{
  "code": 200, "message": "ok", "timestamp": "...",
  "data": {
    "sessionId": "web:alice:daily-weather",
    "profile": "daily-weather",
    "channel": "web",
    "userId": "alice",
    "status": "active",
    "messages": [ { "role": "user", "content": "北京今天天气怎么样", "at": "..." } ],
    "createdAt": "...", "lastActiveAt": "...", "archivedAt": null
  }
}
```

**语义**：`messages` 最多返回**最近 100 条**（FR-009），按时间正序；返回 `status` / `archivedAt`，故归档会话查得到、看得见。

**错误**：404 `session not found: <id>`。

### 1.4 `DELETE /api/v1/sessions/{id}` — 归档

**响应 200**：`{"code":200,"message":"ok","timestamp":"...","data":{"sessionId":"<id>","status":"archived","archivedAt":"..."}}`

**语义**：**幂等**（FR-021）——对已归档会话再次 DELETE，返回 `200` 且 `archivedAt` **保持首次归档的时刻不变**。归档后该会话仍可 `GET` 查询（历史不删）。

**错误**：404 `session not found: <id>`（会话从未存在过——这与「重复归档」不同）。

---

## 2. Agent 无状态调用（1 个）

### 2.1 `POST /api/v1/agents/{name}/invoke` — 一次性调用

**请求**：`{ "content": "帮我算一下 3 的 7 次方" }`（`content` 同 1.2 的非空与 32KB 约束）

**响应 200**：`{"code":200,"message":"ok","timestamp":"...","data":{"agent":"daily-weather","reply":"..."}}`

**语义**：**不创建持久会话、不携带历史**。走 `AgentService.processStateless(agentName, content)`——内部用带唯一执行标识的**内存** Session，**不写 `sessions` 表**；审计仍按该执行标识落 `llm_calls`（与上游同款）。

**错误**：400 参数错误；404 `agent not found: <name>`；503 / 500 同 1.2。

---

## 3. 信息查询（3 个）

### 3.1 `GET /api/v1/profiles` — Agent 列表

**响应 200**

```json
{
  "code": 200, "message": "ok", "timestamp": "...",
  "data": [
    {
      "name": "daily-weather",
      "description": "每日天气播报",
      "provider": { "name": "deepseek", "model": "deepseek-chat" },
      "tools": ["http_get", "notify"],
      "schedules": [ { "cron": "0 8 * * *", "zone": "Asia/Shanghai", "message": "播报今天天气" } ],
      "valid": true,
      "error": null
    }
  ]
}
```

**语义**：数据源 = `ProfileLoader.loadAllDetailed()`，**加载失败的 Agent 也在列表里**（`valid:false` + `error:<原因>`），而不是静默消失——管理台上看得见「哪个 Agent 的 `AGENT.md` 写坏了」。列表按 `name` 升序，确定性输出。

### 3.2 `GET /api/v1/memory` — 长期记忆

**响应 200**：`{"code":200,"message":"ok","timestamp":"...","data":{"core":"<核心区全文>","archival":"<归档区内容>","truncated":false}}`

**语义**：**每次实时读、不缓存**（FR-019）——改完立即生效。`core` **永不截断**；`truncated` 只描述归档区。数据源 = `MemoryService.partition()`，当前 `oryxos.memory.backend` 选中的那一档。后端异常 → 500（**不伪装成空记忆**：把「读不到」渲染成「没有记忆」会让运维以为记忆丢了）。

### 3.3 `GET /api/v1/tools` — 工具列表

**响应 200**

```json
{
  "code": 200, "message": "ok", "timestamp": "...",
  "data": [
    {
      "name": "read_file",
      "description": "读取文本文件内容",
      "inputSchema": "{\"type\":\"object\",\"properties\":{...}}",
      "source": "builtin",
      "origin": "builtin"
    },
    { "name": "echo", "description": "...", "inputSchema": "...", "source": "mcp:demo", "origin": "demo" }
  ]
}
```

**语义**：数据源 = `ToolRegistry.registered()`（`RegisteredTool(tool, source)`），`source` 取值 `builtin` / `annotation` / `mcp:<server>`；`origin` = 去掉 `mcp:` 前缀后的 server 名（非 MCP 工具时与 `source` 相同）。**这是全量注册表**，不做按 Agent 过滤（按 Agent 过滤需要 Profile 上下文，归 `availableFor`，不在本端点）。

---

## 4. 系统状态（2 个）

### 4.1 `GET /api/v1/health` — 健康

**响应 200**

```json
{ "code": 200, "message": "ok", "timestamp": "...",
  "data": { "status": "UP", "db": "UP", "providers": { "deepseek": "UP" } } }
```

| 字段 | 取值 | 语义 |
|---|---|---|
| `status` | `"UP"` / `"DOWN"` | 整体状态，**只由 `db` 决定**（FR-020：未就绪的 Provider MUST NOT 拉低整体状态） |
| `db` | `"UP"` / `"DOWN"` | `DataSource.getConnection().isValid(1)` 的真实探测 |
| `providers` | `Map<String, "UP"｜"DOWN">` | 键 = `ProviderProperties.SUPPORTED_PROVIDERS`；值 = **配置就绪**（该厂商已在 `oryxos.providers` 声明且凭证在场）→ `"UP"`，未声明 → `"DOWN"` |

**MUST NOT** 对 Provider 发真实网络探测（裁决 ⑤）——措辞是「配置就绪 / 未配置」，**不是**「连通 / 不可达」。字面量 `UP` / `DOWN` 是已定字面量，不改。

**已知边界**：`"DOWN"` 只在「底座支持该厂商但本实例没声明它」时出现（本仓 `SUPPORTED_PROVIDERS = {deepseek}`，声明了即 `validate()` 保证凭证非空）。如实记录，不假装它能探测网络故障。

### 4.2 `GET /api/v1/info` — 运行信息

**响应 200**

```json
{ "code": 200, "message": "ok", "timestamp": "...",
  "data": {
    "name": "oryxos", "version": "0.1.0",
    "javaVersion": "21.0.12",
    "agents": 3, "activeSessions": 12, "registeredTools": 15,
    "uptimeSeconds": 3600
  } }
```

| 字段 | 来源 |
|---|---|
| `version` | `oryxos-boot` 的 Maven 项目版本（经 `Implementation-Version` / 构建信息读取，读不到时回 `"unknown"`） |
| `agents` | `ProfileRegistry.all().size()`（含加载失败的？**不含**——这个数报的是「可用 Agent 数」，失败项由 `/profiles` 的 `valid:false` 呈现） |
| `activeSessions` | `SessionService.countActive()` |
| `registeredTools` | `ToolRegistry.registered().size()` |
| `uptimeSeconds` | `RuntimeMXBean.getUptime() / 1000`——`serve` 是前台常驻进程，进程时长即服务时长 |

---

## 5. OpenAPI 文档（FR-011）

- springdoc-openapi 自动生成 OpenAPI 3.0 文档，**不手写接口文档**。
- 交互式文档页：`/swagger-ui`（`application.yaml` 的 `springdoc.swagger-ui.path` 已配，本节不改）。
- 每个端点用 springdoc 注解写清「摘要 / 参数说明 / 各状态码含义」，**注解描述与实际行为一致**（描述写错与代码写错同罪）。

---

## 6. 契约测试映射

| 契约条目 | 判卷测试 |
|---|---|
| 信封形状 / `code` = HTTP 状态码 | `GlobalExceptionHandlerTest`（逐类异常断言状态码 + 信封字段齐全） |
| 1.2 的 32KB → 400 | `SessionApiControllerTest` |
| 1.2 的「处理入口恰被调用一次」 | `SessionApiControllerTest`（`verify(agentService, times(1)).process(...)`） |
| 1.3 的 ≤100 条 | `SessionApiControllerTest` |
| 1.4 幂等 | `SessionServiceTest` + `SessionApiControllerTest` |
| 3.1 的失败 Profile 也在列表 | `ProfileApiControllerTest` |
| 3.2 的实时读不缓存 | `MemoryApiControllerTest`（连续两次调用之间改记忆，第二次看到新值） |
| 3.3 的 `source` / `origin` | `ToolApiControllerTest` |
| 4.1 的 `status` 只由 `db` 决定 | `SystemApiControllerTest`（db DOWN + provider UP → 整体 DOWN） |
| 500 不含内部 message | `GlobalExceptionHandlerTest` |
| 真上下文下四个查询端点可达 | `WebSmokeIT`（`@Tag("integration")`） |
