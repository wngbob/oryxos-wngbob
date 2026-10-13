# Phase 1 Data Model: ReAct 循环 —— Agent 的「想—做—看」主循环

**来源**：[spec.md](spec.md) Key Entities + FR-003/FR-006 + `docs/TechnicalSolution.md` §4.2/§4.3/§9.2 + `specs/000-runtime-kernel/data-model.md` A1/A2。

**本节的数据形态分三种**：内存领域类型（`Session` / `SessionMessage`，本节交付）、运行态（`ProfileContext`，不落盘）、**持久化实体（`ToolInvocation`，骨架期已交付，本节只消费、不改列、不建表）**。

---

## 1. `Session`（内存领域类型，`oryxos-core`）

一次对话的上下文容器。**本节交付的是内存态形态**；持久化形态（表 `sessions`）由第 18 节补（spec「冲突仲裁记录 2」）。

```java
package io.oryxlabs.oryxos.core.session;
```

| 成员 | 类型 | 可变 | 说明 |
| --- | --- | --- | --- |
| `id` | `String` | 否 | 会话标识。生成规则（`channel:user:profile` 三段联合）属第 18 节，本节由调用方传入 |
| `profileName` | `String` | 否 | 所属 Agent 名。`AgentService` 靠它从 `ProfileRegistry` 取 Profile（FR-009） |
| `messages` | `List<SessionMessage>` | **是**（追加） | 按序累积的会话历史；本节不写入 SYSTEM 角色消息 |
| `createdAt` | `Instant` | 否 | 创建时间（`Session.create` 时落定） |
| `lastActiveAt` | `Instant` | **是** | 最后一次追加消息的时间，每次追加都刷新 |

**操作**（FR-003 的三种累积，返回刚追加的那条以便调用方断言）：

| 方法 | 追加的 `Role` | 说明 |
| --- | --- | --- |
| `appendUserMessage(String text)` | `USER` | 本次处理的用户输入，循环第一步 |
| `appendAssistantMessage(String text, List<AssistantMessage.ToolCall> toolCalls)` | `ASSISTANT` | 模型响应。文本与工具请求**同属一条消息**，不拆两条（spec Edge Cases） |
| `appendToolResult(String toolCallId, ToolResult result)` | `TOOL` | 工具执行结果。`ToolResult` → `content` 的映射见 §2 的「失败信息的落法」 |

**不变量**：
- `messages` 只追加、不删除、不重排（截断发生在 `PromptBuilder` 组装 prompt 时，**不改动 Session 本身**——SC-004 要求事后能查到本次全部响应与结果，历史不能因为「这一轮发不出去」而被砍掉）。
- 追加顺序即事实顺序：`USER → ASSISTANT →（TOOL…）→ ASSISTANT → …`。
- 非线程安全：一次处理独占一个虚拟线程（章程原则 I）。

---

## 2. `SessionMessage`（内存值对象，`oryxos-core`）

会话历史的一条。字段口径**逐条对齐** `specs/000-runtime-kernel/data-model.md` A1 的 `messages_json` 元素 `{role, content, toolCalls?, toolCallId?, timestamp}`——第 18 节序列化时不需要改这个类型。

```java
package io.oryxlabs.oryxos.core.session;

public record SessionMessage(
    Role role, String content, List<AssistantMessage.ToolCall> toolCalls,
    String toolCallId, Instant timestamp) {
  public enum Role { SYSTEM, USER, ASSISTANT, TOOL }
}
```

| 字段 | 类型 | 可空 | 说明 |
| --- | --- | --- | --- |
| `role` | `Role` | 否 | `SYSTEM` / `USER` / `ASSISTANT` / `TOOL`，对应 A1 的 `role ∈ system/user/assistant/tool` |
| `content` | `String` | 否（空串表示无文本） | 文本内容。`ASSISTANT` 可能只有工具请求没有文本；`TOOL` 承载结果或失败原因 |
| `toolCalls` | `List<AssistantMessage.ToolCall>` | 否（空表） | 仅 `ASSISTANT` 非空：本轮模型请求的工具调用。元素是 Spring AI 的 record `(id, type, name, arguments)` |
| `toolCallId` | `String` | 是 | 仅 `TOOL` 非空：对应哪一次工具请求（A1 的 `toolCallId`） |
| `timestamp` | `Instant` | 否 | 追加时刻 |

**归一化**：`toolCalls` 为 `null` 时收敛为不可变空表（与 `Profile` 的既有做法一致，下游不必处处判空）。

**`Role.SYSTEM` 保留但本节不产生**：A1 允许该角色，枚举建全；system prompt 每轮现拼（FR-004），不属于会话历史。见 [research.md](research.md) D2。

**失败信息的落法**：A1 的元素形状没有 `success` / `retryable` 字段，故 `appendToolResult` 把 `ToolResult` 摊平成 `content`——成功取 `result.content()`，失败取 `result.errorMessage()`。**成败与否的权威记录是 `tool_invocations`（§3），不是会话消息**。这样模型能看见失败原因并换个办法（FR-007），而审计口径不被消息形状绑架。

**为什么 tool 消息不存工具名**：A1 没有该字段。`PromptBuilder` 重建 `ToolResponseMessage` 时用同一段历史里 assistant 消息的 `toolCalls` 反查（[research.md](research.md) D3），FR-005 保证配对不悬空、反查必命中。

---

## 3. `ToolInvocation`（持久化实体，`oryxos-storage`——**已建，本节只消费**）

`tool_invocations` 表的实体，骨架期已交付：实体 + Repository + 建表脚本 `oryxos-storage/src/main/resources/db/schema.sql` 三件齐备。**本节不改表结构、不新建表、不改列**（spec Assumptions）。

| 列 | 类型 | 本节写入口径 |
| --- | --- | --- |
| `id` | INTEGER PK AI | 由数据库生成，写入方不设 |
| `session_id` | VARCHAR(128) | 本次处理的会话标识（`ToolExecutor.execute` 的第一个参数） |
| `tool_name` | VARCHAR(128) | 模型请求的工具名；查不到工具时也照写（便于事后统计"模型要过什么不存在的工具"） |
| `input_json` | TEXT | 工具调用的入参原文（`ToolCall.arguments()`） |
| `result_json` | TEXT | 成功时为 `ToolResult.content()`；失败时为 `null` |
| `success` | BOOLEAN | 查不到工具 → `false`；工具返回 → `result.success()`；工具抛异常 → `false` |
| `error_message` | TEXT | 失败原因。查不到工具时写明工具名；异常时取 `e.getMessage()`；成功时为 `null` |
| `duration_ms` | LONG | `execute` 首尾的毫秒差（**含查找工具的时间**，与第 16 节 `llm_calls` 的计时口径一致） |
| `created_at` | TIMESTAMP | 写入时刻 |

**读取方式**：既有 `ToolInvocationRepository.findBySessionIdOrderByCreatedAtDesc(String)`，本节不新增查询方法（测试用 `ArgumentCaptor` 断言写入内容即可）。

**审计的触发条件**：每一次模型请求的工具调用**都**产生一行——成功、失败、查不到工具三种情形都写（FR-006，SC-006 覆盖率 100%）。**沙箱拒绝**（第 24 节接入）复用同一条路径，不新增审计逻辑（章程原则 VI）。

**本节不做的两件事**（spec Assumptions 已记）：
- **入参/结果脱敏**：三处权威文档（技术方案 / 第 17 节课件 / 章程）均未提出，本节照第 16 节 `llm_calls` 的写法原样落库；
- **失败重试**：`ToolResult.retryable` 原样透传、不消费（[research.md](research.md) D9）。

---

## 4. `ProfileContext`（运行态，不落盘）

承载「本次处理是哪个 Agent」。生命周期与一次 `AgentService.process` 严格对齐：入口 `set`、`finally` 里 `clear`（FR-009/FR-010）。

```java
package io.oryxlabs.oryxos.core.engine;

public final class ProfileContext {
  public static void set(Profile profile);
  public static Profile current();   // 未设置时返回 null
  public static void clear();
}
```

| 状态 | `current()` | 谁读它 |
| --- | --- | --- |
| 处理进行中 | 本次会话所属的 `Profile` | 工具执行路径（第 19 节 `notify` 读通知渠道、第 20 节按 Profile 过滤工具子集） |
| 处理结束（含异常） | `null` | — |

**不变量**：**任何**退出路径都必须让 `current()` 回到 `null`。异常路径不清 = 下一个复用该线程的请求读到上一个请求的 Agent（SC-007；课件称其为「最阴险的一类 bug」）。

**为什么是 ThreadLocal 而不是参数**：`OryxTool.execute(String)` 的签名不带 Profile（章程原则 V，本节 MUST NOT 改签名）；虚拟线程下每请求独占线程，ThreadLocal 天然不串号。返回 `null` 而非 `Optional` 的理由见 [research.md](research.md) D10（课件 harness 用 `assertNull` 钉死）。

---

## 5. 会话标识的口径（本节只透传，不生成）

`Session.id` 与写进 `tool_invocations.session_id` 的是**同一个值**，由调用方传入、本节原样透传：

```
chat 调用：  LlmClient.chat(session.id(), profile, prompt)   → llm_calls.session_id
工具执行：   ToolExecutor.execute(session.id(), call)        → tool_invocations.session_id
```

生成规则（`channel:user:profile` 三段联合；钟推固定 `scheduler:scheduler:<profile>`）见 `specs/000-runtime-kernel/data-model.md` A1，**属第 18 节**。本节只保证「一次处理里所有审计行的 `session_id` 与本次会话标识一致」（FR-014）。

---

## 6. 实体关系图

```text
                     ProfileRegistry（第 16 节，已交付）
                            │ 按 profileName 查
                            ▼
   AgentService ── 拥有 ──► ProfileContext（ThreadLocal，运行态，处理结束即清）
        │                        ▲
        │ process(session,msg)   │ 工具执行时读
        ▼                        │
   ReActLoop ── 调 ──► LlmClient ──────► ChatResponse（Spring AI）
        │                （core 契约）        │ 文本 + ToolCall[]
        │                                     │
        ├── 每轮 ──► PromptBuilder ──► Prompt ─┘
        │              │  ①SystemMessage(人格+文件+日期时间)
        │              │  ②SystemMessage(长期记忆，可缺)
        │              │  ③历史消息（按 maxHistoryTurns 截断）
        │              └─ ④ChatOptions.toolCallbacks（工具列表）
        │                    ▲                ▲
        │                    │ MemorySource   │ ToolCatalog
        │                    │ (core 契约)     │ (core 契约)
        │
        ├── 每轮 ──► ToolExecutor ──► ToolCatalog.find(name) ──► OryxTool.execute(json)
        │                │
        │                └── 每次调用写 ──► ToolInvocation ──► tool_invocations 表（SQLite）
        │
        └── 累积 ──► Session（SessionMessage[]）
                          │
                          └── 处理结束 ──► SessionService.save（core 契约；第 18 节落 sessions 表）
```

**读图要点**：
- 四个 `core 契约`（`LlmClient` / `ToolCatalog` / `MemorySource` / `SessionService`）是本节的**四个接缝**，本节只定义与调用，实现在后续节（FR-013）；
- `OryxTool` / `ToolResult` 是 core 已有的契约；`ToolInvocation` 是 storage 的既有实体；
- 本节**没有**跨模块的实体依赖：所有新增类型都在 `oryxos-core`，唯一的外部实体消费是写 `ToolInvocation`。
