# Contract: REST API(核心 10 端点)

**来源**: spec.md FR-060~064、US-5;`docs/TechnicalSolution.md` §7.2/§7.4。`oryxos serve` 启动,默认端口 8080,virtual thread。无认证(内网假设),CORS 全开放。

## 统一信封与错误

所有端点成功与错误共用同一信封:

```json
{ "code": 200, "message": "ok", "data": { }, "timestamp": "2026-10-10T08:00:00+08:00" }
```

| 情形 | HTTP | code | message 示例 |
| ------ | ------ | ------ | ------------- |
| 参数非法(含消息 >32KB、归档会话发消息) | 400 | 400 | `message exceeds 32KB limit` |
| 资源不存在(session / agent) | 404 | 404 | `session not found: <id>` |
| 内部错误 | 500 | 500 | 内部描述,不泄露堆栈与密钥 |
| Provider 故障 / 超时 | 503 | 503 | `provider unavailable: <name>` |
| Agent 调用超 60s | 504 | 504 | `agent invocation timed out` |

**通用限制**:单条消息 ≤ 32KB;会话历史最多返回最近 100 条;Agent 调用 60s 超时。

## 会话管理(4)

### `POST /api/v1/sessions` — 创建会话

请求:`{ "profile": "<agent 名,必填>", "user": "<可选,默认 default>" }`(channel 固定 `api`)
响应 `data`:`{ "sessionId", "profile", "channel": "api", "user", "status": "active", "createdAt" }`
错误:404 profile 不存在或校验失败不可用。

### `POST /api/v1/sessions/{id}/messages` — 发消息

请求:`{ "message": "<≤32KB>" }`
响应 `data`:`{ "sessionId", "response": "<Agent 最终响应文本>" }`(内部走完 ReAct 循环同步返回)
错误:400 消息超长 / 会话已归档;404 会话不存在;503 Provider 故障;504 超时。

### `GET /api/v1/sessions/{id}` — 查历史

响应 `data`:`{ "sessionId", "profile", "channel", "user", "status", "messages": [ ...最近 100 条,含 LLM 与 Tool 累积记录 ], "createdAt", "lastActiveAt", "archivedAt" }`

### `DELETE /api/v1/sessions/{id}` — 归档

响应 `data`:`{ "sessionId", "status": "archived", "archivedAt" }`。幂等:重复归档返回相同结果。

## Agent 调用(1)

### `POST /api/v1/agents/{name}/invoke` — 无状态调用

请求:`{ "message": "<≤32KB>" }`
响应 `data`:`{ "agent", "response" }`。
语义:每次调用独立,不创建持久会话、不携带历史;LLM 与 Tool 审计照常落库(FR-071/072)。错误:404 / 503 / 504 同上。

## 信息查询(3)

### `GET /api/v1/profiles`

响应 `data`:`[{ "name", "description", "provider": {"name","model"}, "tools": [...], "schedules": [...], "valid": true }]`(含校验失败的 Agent,`valid=false` 并附 `error`)。

### `GET /api/v1/memory`

响应 `data`:`{ "core": "<核心记忆区全量>", "archival": "<归档区(可能已截断)>", "truncated": false }`。实时读文件,不缓存。

### `GET /api/v1/tools`

响应 `data`:`[{ "name", "description", "inputSchema": <JSON Schema 对象>, "source": "builtin|annotation|mcp", "origin": "<mcp 时为本 server 名>" }]`,覆盖 ToolRegistry 全量。

## 系统状态(2)

### `GET /api/v1/health`

响应 `data`:`{ "status": "UP", "db": "UP", "providers": {"deepseek": "UP", "kimi": "DOWN"} }`。任一 Provider 不可达不拉低总状态(只如实报告),db DOWN 则总状态 DOWN。

### `GET /api/v1/info`

响应 `data`:`{ "name": "oryxos", "version", "javaVersion", "agents": <数>, "activeSessions": <数>, "registeredTools": <数>, "uptimeSeconds": <数> }`。

## OpenAPI

springdoc-openapi 自动生成 OpenAPI 3.0 文档,UI 暴露在 `/swagger-ui`(FR-064);各 Controller 注解与本文件口径保持一致,冲突时以本契约为准。

## 明确不做(扩展阶段)

认证、SSE/WebSocket、RBAC、限流、`/api/v1/notify-channels` CRUD、`/api/v2/schedules*` 调度管理、Agent 目录上传/更新端点、审计查询端点。
