# Phase 1 Data Model: Web Service 与第一版管理平台（第 26 节）

**Feature**: `specs/009-web-service` | **Date**: 2026-10-19 | **Plan**: [plan.md](./plan.md) | **Research**: [research.md](./research.md)

本节**不新建任何数据表**——10 个端点全部读写前序节已有的持久化结构。本节新增的「数据模型」分两类：**① 既有领域对象的两个新字段与两个新查询**（会话的归档态）；**② 一批 HTTP 传输层 DTO**（只在 `oryxos-web` 内，不外泄、不落库）。

---

## 一、既有表：本节零改动

| 表 | 本节关系 |
|---|---|
| `sessions` | **读 + 归档写**。列 `status`（16，`active` / `archived`）与 `archived_at` 第 18 节已建、`SessionEntity` 已有字段，本节只是第一次真的往里写归档态 |
| `tool_invocations` / `llm_calls` | **间接写**。REST 发消息走 `AgentService.process`，审计在链路内部完成，本节不碰 |
| `memory_entries` | 只在 `oryxos.memory.backend=sqlite` 时被 `/memory` 读到 |
| `notify_channels` | 本节无关 |

`oryxos-storage/src/main/resources/db/schema.sql` **本节一行不改**（`sessions` 表两列已就位）。

---

## 二、领域对象改动（`oryxos-core` / `oryxos-memory`）

### 2.1 `Session`（`oryxos-core`，`session` 包）—— 新增两字段

| 字段 | 类型 | 可空 | 取值 | 来源 |
|---|---|---|---|---|
| `status` | `String` | 否 | `"active"` / `"archived"` | 新增 |
| `archivedAt` | `Instant` | 是 | `null` = 未归档 | 新增 |

**约束与状态迁移**：

```text
create(...)      → status="active",   archivedAt=null
restore(...)     → 由持久化行还原（含 archivedAt 可能为 null）
archive()        → status="archived", archivedAt=Instant.now()   ← 仅当当前是 active
archive() 再调   → 幂等：status 与 archivedAt 都不动（FR-021）
appendUserMessage / appendAssistantMessage  → MUST NOT 改动 status
```

**MUST NOT**：已归档会话被 `save()` 悄悄复活——`JpaSessionService.save()` 的更新路径第 18 节就**只写** `messages_json` + `last_active_at`，本节不改这条（那是第 18 节为解决这件事**预先**立的规矩）。

**`restore(...)` 签名**由 7 参增为 9 参（多 `status` / `archivedAt`）。实测确认 P3C `TooManyParametersRule` **不在**本仓 `p3c-ruleset.xml` 引用的 `ali-oop.xml` 里（`p3c-pmd-2.1.1.jar` 内核实），无参数个数门禁。

**连带**：`Session.channel()` 的 `@param` 与 `SessionEntity.channel` 的列注释里写着渠道示例 `cli` / `api` / `scheduler`(/`gateway`)，本节把 `api` 改为 `web`（裁决 ⑪）。两处都是 javadoc / 注释，不改行为。

### 2.2 `SessionService`（`oryxos-core`）—— 新增两方法

| 方法 | 签名 | 语义 |
|---|---|---|
| `archive` | `boolean archive(String sessionId)` | 存在则置归档态并返回 `true`（**已归档也返回 `true`，且 `archivedAt` 不变**——FR-021 幂等）；不存在返回 `false` |
| `countActive` | `long countActive()` | 活跃会话数，供 `/info` 的 `activeSessions` |

既有 `getOrCreate` / `get` / `save` **签名与行为一字不改**。

### 2.3 `SessionRepository`（`oryxos-storage`）—— 新增一派生查询

```java
long countByStatus(String status);
```

Spring Data JPA 派生查询，**零 SQL 手写**。第 18 节该接口的 javadoc 写着「本节不新增查询方法」——本节新增，挂带日期修订注。

### 2.4 `AgentService`（`oryxos-core`）—— 新增一方法

```java
public String processStateless(String agentName, String userMessage)
```

**不改** `process(Session, String)`。行为：

| 步骤 | 说明 |
|---|---|
| 1 | 造**瞬时** `Session`：`Session.create("invoke-exec:" + UUID.randomUUID(), "web", "console", agentName)` |
| 2 | `ProfileContext.set(profile)`，`finally` 里 `ProfileContext.clear()` |
| 3 | `reActLoop.run(...)` 取最终答复 |
| 4 | **MUST NOT** 调 `sessionService.save(...)` —— 不落 `sessions` 表 |

审计仍走既有路径（`llm_calls.session_id` = 那个瞬时 id），与上游同款；请求结束瞬时消息丢弃。

**Profile 不存在**：与 `process` 一致地抛 `IllegalStateException`（由 controller 预校验拦在前面，见 §四）。

### 2.5 `MemoryPartition`（`oryxos-memory`，`store` 包）—— 新类型

```java
public record MemoryPartition(String core, String archival, boolean truncated) {}
```

| 分量 | 类型 | 语义 |
|---|---|---|
| `core` | `String` | 核心区全文，**永不截断**（第 22 节四个行为契约之一） |
| `archival` | `String` | 归档区内容（可能已截断） |
| `truncated` | `boolean` | 归档区**是否被截断过**；核心区不参与这个判断 |

**为什么是字符串不是列表**：三档实现的 `load()` 内部本来就把两区拼成同样的 `## 核心记忆` / `## 归档记忆` 结构（实测四处同款）。`partition()` 因此与 `load()` **共用同一个私有截断助手**——两处口径不可能漂移。

**`truncated` 的判定源（逐档）**：

| 档 | 归档区截断规则 | `truncated` |
|---|---|---|
| `markdown`（默认） | 超 4000 字符简单截断 | 归档区原始长度 > 4000 |
| `sqlite` | `LIMIT` 最近 100 条 | 归档区行数 > 100 |
| `mem0` | 交给对端作用域机制 | 恒 `false`（本档不截断，由服务端决定返回什么） |

### 2.6 `LongTermMemoryStore` / `MemoryService` —— 各新增一个方法

```java
MemoryPartition partition();
```

- `LongTermMemoryStore`（`oryxos-memory` 既有契约）：4 个实现类全部补齐——`MarkdownMemoryStore` / `SqliteMemoryStore` / `Mem0MemoryStore` / 测试替身 `InMemoryMemoryStore`。**连带第 5 处**：`oryxos-memory/src/test/.../MemoryServiceImplTest.java` 内有一个**内联匿名实现**当测试桩，接口加抽象方法它同样编译失败，必须一并补 `partition()`（实现可简单委托或返回固定值）。
- `MemoryService`（门面）：委托给持有的 `LongTermMemoryStore`，**与 `load()` 一样不缓存**（FR-019「每次实时读」）。

**`MemorySource`（`oryxos-core` 的接缝）一个字不改**——它语义上是「一段文本、有就注入」，分区是 `oryxos-memory` 的内部概念，不上提到 core。

---

## 三、传输层 DTO（全部在 `oryxos-web`，`record`，不落库、不外泄到别的模块）

### 3.1 统一信封（既有，本节复用不改）

```java
public record ApiResponse<T>(int code, String message, T data, OffsetDateTime timestamp)
```

成功与错误**共用**（FR-006）；`code` = HTTP 状态码（裁决 ①）；`message` 成功时 `"ok"`，失败时按 §五 的错误文案表；`timestamp` 服务端生成。

### 3.2 请求体

| DTO | 字段 | 校验（controller 内，违规 → 400） |
|---|---|---|
| `CreateSessionRequest` | `String profile`, `String userId` | `profile` 非空；`userId` 空则取默认 `"default"` |
| `MessageRequest` | `String content` | 非空；`content.length() ≤ 32 * 1024`（FR-008） |
| `InvokeRequest` | `String content` | 同上 |

**MUST NOT** 在请求体里收 `channel`：渠道由服务端定死为 `"web"`（会话身份不交给调用方）。

### 3.3 响应体

| DTO | 字段 | 端点 |
|---|---|---|
| `SessionView` | `sessionId`, `profile`, `channel`, `userId`, `status`, `messages`（`List<MessageView>`，≤100 条，仅查询端点带）, `createdAt`, `lastActiveAt`, `archivedAt` | `POST /sessions`（不带 `messages`）、`GET /sessions/{id}`（带） |
| `MessageResponse` | `sessionId`, `reply` | `POST /sessions/{id}/messages` |
| `InvokeResponse` | `agent`, `reply` | `POST /agents/{name}/invoke` |
| `ArchiveResponse` | `sessionId`, `status`（恒 `"archived"`）, `archivedAt` | `DELETE /sessions/{id}` |
| `ProfileView` | `name`, `description`, `provider`(`{name, model}`), `tools`, `schedules`, `valid`, `error` | `GET /profiles` |
| `MemoryView` | `core`, `archival`, `truncated` | `GET /memory` |
| `ToolView` | `name`, `description`, `inputSchema`, `source`, `origin` | `GET /tools` |
| `HealthView` | `status`, `db`, `providers`(`Map<String,String>`) | `GET /health` |
| `InfoView` | `name`, `version`, `javaVersion`, `agents`, `activeSessions`, `registeredTools`, `uptimeSeconds` | `GET /info` |

**字段名口径（裁决 ⑪）**：`userId`（不是 `user`）、`reply`（不是 `response`）、`channel` 恒 `"web"`（不是 `"api"`）、请求体 `content`（不是 `message`）。其余形状**保留本仓契约的丰富度**（`inputSchema` / `source` / `origin`、嵌套 `provider`、`db` + `providers`、`uptimeSeconds`）。

**`MessageView`**：会话历史里的单条消息，`{role, content, at}`（`role` ∈ `user` / `assistant` / `tool`），由 `SessionMessage` 映射。

**`ProfileView.error`**：Profile 加载失败时给失败原因（`ProfileLoader.LoadResult.failures()` 的 `Failure.reason`），成功时为 `null`；`valid` = 是否加载成功。这样「某个 Agent 的 `AGENT.md` 写坏了」在管理台上**看得见**，而不是那个 Agent 静默消失。

**`ToolView.origin`**：`source` 为 `builtin` / `annotation` 时与 `source` 相同；`source` 形如 `mcp:<server>` 时为该 server 名。这样管理台能一眼看出工具从哪来。

---

## 四、状态判定与错误映射（与 spec 场景逐条对应）

| 场景 | 判定点 | 结果 |
|---|---|---|
| `POST /sessions` 的 `profile` 不存在 | controller 预校验 `ProfileRegistry.find` | 404 `agent not found: <name>` |
| `POST /sessions/{id}/messages` 的会话不存在 | `SessionService.get(id)` 空 | 404 `session not found: <id>` |
| `POST /sessions/{id}/messages` 的**会话所属 Agent 配置已不可用** | controller 预校验会话的 `profileName` 是否仍在 `ProfileRegistry` | 404（spec 场景 5，**不是** 503） |
| 同上，**向已归档会话发消息** | controller 判 `session.status()` | 400 `session is archived: <id>`（spec Edge Case），**零模型调用** |
| 消息超 32KB | controller 长度判定 | 400 `message exceeds 32KB limit` |
| `POST /agents/{name}/invoke` 的 Agent 不存在 | controller 预校验 | 404 |
| Provider 调用失败（非配置错） | `ProviderService` 包装成 `ProviderUnavailableException` | 503 `provider unavailable: <name>` |
| Provider 未声明（`provider.name` 与 `application.yaml` 对不上） | `ProviderNotFoundException` 上抛 | **500**（裁决 ④：配置错误不是 503） |
| 其余未预料异常 | catch-all | 500 `Internal server error`，**响应体不含内部异常 message**（FR-005） |

**预校验为什么不会 TOCTOU**：`ProfileRegistry` 是启动期快照、不可变（`find` / `all` / `names`，无写方法），controller 与 `AgentService` 查的是**同一个实例**。

---

## 五、错误文案表（已定字面量，逐字保真）

| 状态码 | `message` |
|---|---|
| 400 | `message exceeds 32KB limit` |
| 400 | `session is archived: <id>` |
| 404 | `session not found: <id>` |
| 404 | `agent not found: <name>` |
| 500 | `Internal server error` |
| 503 | `provider unavailable: <name>` |
| 504 | `agent invocation timed out` |

前六条来自 `specs/000-runtime-kernel/contracts/rest-api.md` 的错误表（本节新增 `session is archived` 与 `agent not found` 两条，按同款措辞；契约同步挂带日期修订注）。

**`data` 分量在错误响应里恒为 `null`**；内部异常的完整描述**只进日志**（`log.error`），不进响应体。

---

## 六、本节不引入的数据概念（边界）

- **不新增任何表、任何列、任何索引**；
- **不新增任何配置键**（`application.yaml` 零改动）；
- **不新增 Profile 字段**；
- **不引入 `errorCode` 字段**（裁决 ①）；
- **不做长期记忆的写入端点、不做 Profile 的增删改、不做「通过 API 定义 Agent」**（归 29/30 节，界面上也不出现假按钮）；
- **不做认证主体（用户表 / 会话令牌）**——本节 `userId` 是调用方自报的字符串，无鉴权语义。
