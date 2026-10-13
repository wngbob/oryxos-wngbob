# Contract: `tool_invocations` 表（本节**只消费**）

**来源**：[spec.md](../spec.md) FR-006/FR-007 + Assumptions；`docs/TechnicalSolution.md` §9.2；`specs/000-runtime-kernel/data-model.md` A2；已落地的 `oryxos-storage/src/main/resources/db/schema.sql`。

## 0. 本节的姿态：只写不建

`tool_invocations` 的表、实体、Repository 与建表脚本**骨架期已交付**（`LlmCall` 同期交付）。本节是它预期中的写入方——`ToolInvocation` 实体的无参构造注释原文就是「供写入方（第 17 节的工具执行审计，在 `oryxos-core`）跨包构造后逐列 set」。

**本节 MUST NOT 改表结构、MUST NOT 新建表、MUST NOT 改列名。** 不新增任何查询方法（既有 `findBySessionIdOrderByCreatedAtDesc(String)` 已够用）。

## 1. 列与写入契约

| 列 | 类型 | 可空 | 本节写入口径 |
| --- | --- | --- | --- |
| `id` | INTEGER PK AUTOINCREMENT | 否 | 数据库生成，写入方不设 |
| `session_id` | VARCHAR(128) | 是 | 本次处理的会话标识（`ToolExecutor.execute` 第一个参数） |
| `tool_name` | VARCHAR(128) | 是 | 模型请求的工具名。**查不到工具时也照写**——便于事后统计「模型要过什么不存在的工具」 |
| `input_json` | TEXT | 是 | 工具调用入参原文（`ToolCall.arguments()`） |
| `result_json` | TEXT | 是 | 成功时为 `ToolResult.content()`；失败时为 `null` |
| `success` | BOOLEAN | 是 | 查不到工具 → `false`；工具返回 → `result.success()`；工具抛异常 → `false` |
| `error_message` | TEXT | 是 | 失败原因：查不到工具时写明工具名；异常时取 `e.getMessage()`；成功时 `null` |
| `duration_ms` | INTEGER | 是 | `execute` 首尾毫秒差（含查找工具的时间） |
| `created_at` | TIMESTAMP | 是 | 写入时刻 |

**列名与实体字段的映射由既有 `ToolInvocation` 实体负责**，本节只逐列 `set`。`id` 列必须保持 `INTEGER PRIMARY KEY`（SQLite 的 rowid 别名语义，实体上已用 `@JdbcTypeCode(SqlTypes.INTEGER)` 钉住）——本节不改。

## 2. 写入时机与覆盖率

**每一次模型请求的工具调用都恰好产生一行**（SC-006 覆盖率 100%），三种情形都写：

1. 工具执行成功；
2. 工具执行抛异常（`success=false` + `error_message`）；
3. 模型请求了一个不存在的工具（`success=false` + `error_message` 写明工具名）。

第 24 节的**沙箱拒绝复用同一条路径**（`success=false` + `error_message`），**不新增审计逻辑、不新增表或字段**（章程原则 VI）。本节为此预留的实现方式：`ToolExecutor` 捕 `RuntimeException`——`SandboxViolationException` 继承它，core 因此不需要 import `oryxos-tool` 的异常类（`specs/000-runtime-kernel` T033 的要求）。

## 3. 与 `llm_calls` 的口径一致性

| 维度 | `llm_calls`（第 16 节） | `tool_invocations`（本节） |
| --- | --- | --- |
| 成功标识 | `success` | `success` |
| 失败原因 | `error_message` | `error_message` |
| 耗时 | `duration_ms` | `duration_ms` |
| 关联 | `session_id` | `session_id` |
| 失败时的写法 | **先落账、再上抛异常** | **先落账、再返回失败结果**（不上抛） |

**差别只在异常去向**，不在审计口径：模型调用失败意味着这一轮根本没法继续，必须上抛；工具调用失败是循环**预期内**的一种观察结果，要回灌给模型让它换个办法（FR-007）。两者都做到「一次真实事故在系统里留痕」。

## 4. 本节**不做**的两件事

- **入参/结果脱敏**：`specs/000-runtime-kernel/data-model.md` A2 有一条「写入前对 `input_json` / `result_json` 做敏感字段脱敏」的规则，但它只出现在那一套拆解里——`docs/TechnicalSolution.md`、第 17 节课件、章程三处**均未提出**该要求。本节按课件「跟上一节 Provider 的审计同一个口径」的原文，照第 16 节 `llm_calls` 的写法原样落库。**若后续节引入统一脱敏单点，回改本节写入路径即可**（写入点只有 `ToolExecutor` 一处）。已记入 spec 的 Assumptions。
- **失败重试**：`ToolResult.retryable` 原样透传、不消费。「按指数退避最多重试 3 次」是 `specs/000-runtime-kernel` T033 的内容，本节 spec 已把它列为不做（课件「有几样先别做」未提重试，但 4 周 × 3 小时的范围内它属扩展）。

## 5. 验证方式（见 [quickstart.md](../quickstart.md)）

- `ToolExecutorTest` 用 mock 的 `ToolInvocationRepository` + `ArgumentCaptor` 断言写入的实体字段（成功 / 失败 / 查不到工具三种情形）；
- 建表脚本本身由第 16 节交付的 `LlmCallRepositoryTest` 同款做法验证（真 SQLite + 手工脚本），本节**不重复**——表已经在那儿了，本节只写不建。
