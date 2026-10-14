# Contract: 会话领域类型

**来源**：[spec.md](../spec.md) FR-003/FR-005 + 「冲突仲裁记录 2」；字段口径对齐 `specs/000-runtime-kernel/data-model.md` A1。字段清单见 [data-model.md](../data-model.md) §1/§2，本文件只钉**跨节消费面**。

## 1. `Session`（`io.oryxlabs.oryxos.core.session`）

```java
public final class Session {
  public static Session create(String id, String profileName);
  public String id();
  public String profileName();
  public List<SessionMessage> messages();
  public Instant createdAt();
  public Instant lastActiveAt();
  public SessionMessage appendUserMessage(String text);
  public SessionMessage appendAssistantMessage(String text, List<AssistantMessage.ToolCall> toolCalls);
  public SessionMessage appendToolResult(String toolCallId, ToolResult result);
}
```

**本节交付的是内存态形态**。持久化形态（表 `sessions`、`SessionRepository`、`SessionService` 的实现）属**第 18 节**（spec「冲突仲裁记录 2」；`CLAUDE.md` 模块表本就把 `Session` 列在 `oryxos-core`）。

## 2. 跨节消费面：第 18 节要接的三件事

| 消费方 | 它需要什么 | 本节已就位的部分 |
| --- | --- | --- |
| `SessionService` 的实现 | 把 `Session` 落进 `sessions` 表 | `id` / `channel` / `userId` / `profileName` / `messages` / `createdAt` / `lastActiveAt` **七个字段齐备** |
| `messages_json` 序列化器 | 每条消息的 `{role, content, toolCalls?, toolCallId?, timestamp}` | `SessionMessage` 的五个字段与 A1 元素**逐字对齐**，序列化时不需要改这个类型 |
| 会话恢复（第 25 节钟推、第 26 节 Web） | 从表里读回 `Session` 继续跑 | `Session.create(...)` + 三个 append 足以重建（本节的追加操作返回刚追加的消息，便于断言） |

**第 18 节已修订本行**：上表原写「五个字段齐备」。会话标识由 `channel` / `user` / `profile` 三段联合生成（第 18 节 FR-006），表 `sessions` 也把 `channel` / `user_id` 单列存，故 `Session` 补齐 `channel` 与 `userId` 两字段，工厂方法由 `Session.create(id, profileName)` 变为 `Session.create(id, channel, userId, profileName)`。已报批（specs/003-cli-session/spec.md 冲突仲裁记录 2）。

**序列化口径提醒**：`role` 落库为小写字符串（`system` / `user` / `assistant` / `tool`，对齐 A1）；`toolCalls` 的元素取 `AssistantMessage.ToolCall` 的 `id` / `type` / `name` / `arguments` 四个字段；`timestamp` 落 ISO-8601。

## 3. 本节的四条不变量（第 18 节改实现时 MUST 保持）

1. **只追加**：`messages` 只追加、不删除、不重排。截断发生在 `PromptBuilder` 组装 prompt 时，**不改动 `Session`**——SC-004 要求事后能查到本次全部模型响应与工具结果。
2. **文本与工具请求同属一条消息**：某一轮 assistant 消息里既有文本又有工具调用时，是**一条** `ASSISTANT` 消息，不拆两条（spec Edge Cases）。
3. **`SYSTEM` 角色不写入**：system prompt 每轮现拼（FR-004），不属于会话历史。枚举值保留只是为了 A1 的序列化口径不用改。
4. **非线程安全**：一次处理独占一个虚拟线程（章程原则 I 的同步阻塞模型），不加锁。

## 4. `SessionService`（core 契约）

```java
public interface SessionService {
  void save(Session session);
}
```

本节只声明 `save`，`AgentService.process` 在循环跑完后调一次。`load` / `create` / 会话标识生成规则属第 18 节——**没有调用方的抽象就是凭空造接口**（章程原则 II）。

本节对「持久化」的验收口径是**「经接缝被调用一次」**：`AgentServiceTest` 用替身断言 `save` 被调过，真实落库由第 18 节的实现负责。

## 5. 会话标识

`Session.id` 与写进 `llm_calls.session_id`、`tool_invocations.session_id` 的是**同一个值**，本节原样透传。生成规则（`channel:user:profile` 三段联合；钟推固定 `scheduler:scheduler:<profile>`）见 `specs/000-runtime-kernel/data-model.md` A1，**属第 18 节**。

本节只保证：一次处理里所有审计行的 `session_id` 与本次会话标识一致（FR-014）。
