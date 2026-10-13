# Contract: `llm_calls` 审计表

**来源**: spec.md FR-008/FR-009/FR-011/FR-012；`docs/TechnicalSolution.md` §9.2；章程原则 VI。**这是审计数据的存储契约**，列名与语义变更必须走修订程序。

## 1. 载体与建表

- 库文件：`.oryxos/oryxos.db`（SQLite）。
- 建表脚本：`oryxos-storage/src/main/resources/db/schema.sql`（**手工维护**）。
- MUST NOT 依赖 `hibernate.ddl-auto` 自动建表或自动迁移（章程附加约束；SQLite 的 `ALTER TABLE` 能力弱）。
- 应用配置中 `spring.jpa.hibernate.ddl-auto=none`。

## 2. 表结构

```sql
CREATE TABLE IF NOT EXISTS llm_calls (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id        VARCHAR(128),
    provider          VARCHAR(64),
    model             VARCHAR(128),
    prompt_tokens     INTEGER,
    completion_tokens INTEGER,
    total_tokens      INTEGER,
    duration_ms       BIGINT,
    success           BOOLEAN,      -- 本节新增
    error_message     TEXT,         -- 本节新增
    created_at        TIMESTAMP
);
```

## 3. 列语义

| 列 | 语义 | 可空 |
| --- | --- | --- |
| `id` | 自增主键 | 否 |
| `session_id` | 会话标识；查询按此关联 | 是 |
| `provider` | Profile 点名的厂商名 | 是 |
| `model` | 实际请求的模型名 | 是 |
| `prompt_tokens` / `completion_tokens` / `total_tokens` | 取自响应 metadata 的用量 | 是（部分厂商不返回） |
| `duration_ms` | 调用耗时（毫秒），含失败调用 | 是 |
| `success` | 是否成功；**失败 MUST 显式为 false** | 是 |
| `error_message` | 失败原因（异常消息）；成功时为空 | 是 |
| `created_at` | 调用发生时间 | 是 |

## 4. 写入规则（FR-008/FR-009）

1. **成功**：调用返回后写一条，`success = true`，`error_message` 空，用量取自 `ChatResponse` 的 metadata。
2. **失败**：**先**写一条 `success = false` + `error_message = 异常消息`，**再**把异常上抛给上层。MUST NOT 吞掉异常，MUST NOT 只记成功。
3. **用量缺失**：仍落库，用量列留空；不因拿不到 token 数就整条丢弃。
4. 每次调用**恰好一条**，不重不漏（SC-002 覆盖率 100%）。

## 5. 查询

- 既有接口：`LlmCallRepository.findBySessionIdOrderByCreatedAtDesc(String sessionId)`（本节不改）。
- 核心阶段**只保证写入**；审计查询接口与报表放扩展阶段（章程原则 VI）。

## 6. 与结构化日志的关系

每次调用除落库外，MUST 同时输出一条结构化 JSON 日志（FR-013，口径沿用上游 `specs/000-runtime-kernel` FR-074）：
- 成功与失败都输出；
- MUST NOT 用 `System.out`，走 SLF4J / Logback；
- 日志与落库适用**同一套**敏感字段脱敏规则（凭证类字段值一律掩码）。
