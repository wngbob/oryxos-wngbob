# Contract: 会话持久化（core 接缝 + storage 落位）

**来源**：[spec.md](../spec.md) FR-006 / FR-007 / FR-008 + 冲突仲裁记录 1 / 2 / 3 / 4 / 12；`specs/000-runtime-kernel/data-model.md` A1；第 17 节契约 `specs/002-react-loop/contracts/{core-seams.md, session-model.md}`。这是**模块间的 Java 契约**：签名变更必须走修订程序。

---

## 0. 本节对第 17 节的**修订**（两处，均已报批）

| 修订项 | 第 17 节的形态 | 本节的形态 | 依据 |
| --- | --- | --- | --- |
| `SessionService` 的方法 | 只有 `save(Session)` | 补 `getOrCreate(...)` / `get(...)` | spec 仲裁 1（当时无调用方，YAGNI；本节有调用方了） |
| `Session` 的字段 | 五字段（`id` / `profileName` / `messages` / `createdAt` / `lastActiveAt`） | **七字段**（+ `channel` / `userId`） | spec 仲裁 2（`sessions` 表有 `channel` / `user_id` 两列，领域类型没有就落不了库） |

**连带影响（必须在同一节内完成，不得留半截）**：

1. `specs/002-react-loop/contracts/session-model.md` §2 的「五个字段齐备」断言 → **改为七项**，该文件同步修订。
2. 第 17 节的测试里所有 `Session.create(id, profileName)` 两参调用 → **改为四参**（`ReActLoopTest` / `AgentServiceTest` 等）。
3. 第 17 节的测试里所有 `SessionService` 的**匿名 / 手写替身** → **补实现新增的两个方法**（接口加方法会破坏既有实现类，这是编译期就会暴露的，不是可选项）。
4. 生产装配侧：`AgentService` 的构造函数不变（仍注入 `SessionService`），**不改 `AgentService` 一行**。

---

## 1. `SessionService`（core 契约，本节补齐）

```java
public interface SessionService {
  Session getOrCreate(String channel, String userId, String profileName);
  Optional<Session> get(String sessionId);
  void save(Session session);
}
```

| 方法 | 调用方 | 语义 |
| --- | --- | --- |
| `getOrCreate(channel, userId, profileName)` | CLI 的 `chat`（经 `CliChannel`）；第 25 / 26 节的钟推与 REST 同签名复用 | 按 §2 算出标识；**命中则从库中恢复历史**返回，未命中则新建空会话。同一三元组历次调用返回同一标识（**幂等**，FR-007） |
| `get(sessionId)` | `session list` 之外的按需读取；第 26 节的 `GET /api/v1/sessions/{id}` | 不存在返回 `Optional.empty()`——调用方决定是报错还是跳过 |
| `save(session)` | `AgentService.process`（第 17 节已接好，**本节不改调用点**） | 落库，语义见 §5 |

**`profile_name` 存在性校验不在这里**（spec 仲裁 12）：接缝签名只有三元组、拿不到 Agent 清单，而 `SessionService` 不该把「Agent 存不存在」混进会话层。A1 的「写入前 `profile_name` 必须存在」由**调用顺序**天然满足——CLI 先经 `AgentResolver` 解析出 Profile，解析不通过就走不到取会话这一步。**MUST NOT 在 `SessionService` 里重复校验**（那会让校验出现两处，且其中一处拿不到数据）。

**实现方 `JpaSessionService`**（`oryxos-core`，`io.oryxlabs.oryxos.core.session` 包）：注入 `SessionRepository`，用 §3 的 `MessageJson` 做序列化。放 core 的理由见 spec 仲裁 3——`oryxos-core → oryxos-storage` 是已报批的既定边，与第 17 节 `ToolExecutor` 直接用 `ToolInvocationRepository` 是同一模式；放 storage 会成环。

---

## 2. 会话标识（**唯一拼接点**）

```java
// 包级私有，core.session 包内
final class SessionId {
  static String of(String channel, String userId, String profileName);
}
```

- **公式**：`channel + ":" + user_id + ":" + profile_name`，形如 `cli:local:daily-weather`。
- **拼接只此一处**（FR-006 / SC-003 / H4 不变量 ④）。所有入口只提供三元组，**MUST NOT 自己拼字符串**——两处各拼一遍、格式差一个分隔符，同一个人就会出现两条互不相认的历史。
- **包级私有**：不是对外概念，不触发软门禁 ①。CLI / 第 25 节 / 第 26 节都只需调 `getOrCreate`，拿不到也不需要 `SessionId`。将来真有跨模块需要时再放宽。
- 三段中任一段为空或含 `:` → 抛异常，**不静默产出脏标识**；拼接结果长度 ≤ 128（A1 的 `VARCHAR(128)`）。

**固定字面量**：CLI 的 channel = `cli`、user = `local`（spec 仲裁 13 的姊妹条）。这两个字面量只出现在 CLI 侧一处。

---

## 3. `messages_json` 的落盘形状

由 `core.session` 包内**包级私有**的 `MessageJson` 单独钉住（[data-model.md](../data-model.md) §2 有字段表）：

- `role` 落**小写**（`system` / `user` / `assistant` / `tool`）——A1 要求；`SessionMessage.Role` 的枚举名是大写，**故不能直接序列化 `SessionMessage`**。
- `toolCalls` 每项只取 `{id, type, name, arguments}` 四个字段（`AssistantMessage.ToolCall` 的组件；Jackson 2.21 原生支持 record 读写，**不引入新依赖**）。
- `timestamp` 用 `Instant.toString()`（ISO-8601 UTC，带 `Z`），**往返无损**。

**三条不变量**：

1. **落盘格式独立于内存类型**——给 `SessionMessage.Role` 加 Jackson 注解会改第 17 节的公共类型，且把磁盘格式绑死在枚举命名上（改枚举名就悄悄改磁盘格式）。**MUST NOT** 这么做。
2. **序列化失败不落库**（A1 明文校验规则）——上抛为内部错误，MUST NOT 写半截数据。
3. **顺序即事实顺序**——数组顺序 = `Session.messages()` 顺序，读写都不得重排。

**为什么序列化器在 core 而不是 storage**：磁盘格式的口径属于会话领域层，`oryxos-storage` 只把 `messages_json` 当一个不透明的 `String` 列（§4 的硬约束）。这条也决定了测试的落位（§6）。

---

## 4. `SessionEntity` + `SessionRepository`（storage 侧）

```java
@Entity @Table(name = "sessions")
public class SessionEntity {
  @Id @Column(name = "session_id") private String sessionId;   // 不用 @GeneratedValue
  private String profileName, channel, userId, messagesJson, status;
  private LocalDateTime createdAt, lastActiveAt, archivedAt;
  // JPA 要求的无参构造 + getter/setter
}

public interface SessionRepository extends JpaRepository<SessionEntity, String> {}
```

**硬约束**：字段**全部是基本类型与 `String`**，MUST NOT 出现 `Session` / `SessionMessage` 等 core 类型——`oryxos-storage` **MUST NOT 依赖 `oryxos-core`**（反向即成环，章程附加约束）。实体只负责「原样存、原样取」。

**主键不用 `@GeneratedValue`**：`session_id` 由 §2 的三段拼接生成，不是数据库自增。

**表定义逐字照 A1**：列名与长度见 [data-model.md](../data-model.md) §4，**不新增列、不改类型**（spec 仲裁 12）。建表语句手工维护在 `oryxos-storage/src/main/resources/db/schema.sql`，`ddl-auto=none`。

---

## 5. `save` 的三条不变量

| # | 不变量 | 为什么 |
| --- | --- | --- |
| 1 | **`created_at` 不被覆盖** | 恢复出来的 `Session.createdAt()` 就是库里那一行的值，写回时必须一致——否则「重启后历史还在」会连创建时间一起漂 |
| 2 | **`status` / `archived_at` 不被覆盖** | 新增时写 `active` + 空；**更新时原样保留**。归档是第 26 节的行为，本节若在 `save` 里无条件写 `active`，第 26 节一归档就被下一次 `save` 悄悄复活 |
| 3 | **序列化失败不落库** | 同 §3 不变量 2 |

**`last_active_at` 取 `Session.lastActiveAt()`**（第 17 节已保证「追加消息时刷新」由 `Session` 内部一处负责）。

**复核留痕（2026-10-14）——失败首轮的孤儿 `session_id` 引用**：`getOrCreate` 未命中时只造**内存对象**、不落库；落库只发生在 `AgentService.process` 里 `reActLoop.run()` 返回之后的 `save`（第 17 节既定调用点，本节不改）。因此**首条消息就失败**（如上游 401）的会话：`llm_calls` 里会落一行带 `session_id` 的审计记录（失败也落审计，章程原则 VI），而 `sessions` 表里**没有**对应行。`schema.sql` 无外键约束（已核实），核心阶段不会报错；这不是 bug——不持久化空的失败会话是刻意行为——但审计表与会话表的引用关系从 day one 就是松的。**做审计查询 JOIN 时 MUST 以审计表为左表用 `LEFT JOIN`**，并意识到其中的 `session_id` 可能在 `sessions` 里查无此行。

---

## 6. 测试落位（含课件 harness 的原样落地）

| 测试类 | 模块 | 覆盖 |
| --- | --- | --- |
| `SessionServiceTest` | `oryxos-core`（纯单测，Mockito + `Map` 支撑的 `SessionRepository` 替身） | 幂等 / 隔离 / **标识生成只此一处** / 序列化往返（含工具调用与工具结果的消息） |
| `SessionRepositoryTest` | `oryxos-storage`（`@DataJpaTest` + 真 SQLite 临时文件 + 手工脚本） | 表由脚本建得出来且列齐备 / 经 Repository 存取往返保真 / **「重启」重查历史还在** |

**课件 harness 的关键回归测试原样落地**在 `SessionServiceTest`：

```java
// 课件原文（方法名按纪律译成英文，原文进 @DisplayName）
// 同一三元组_历次getOrCreate都是同一个Session
@Test
@DisplayName("同一三元组_历次getOrCreate都是同一个Session")
void sameTriple_getOrCreateAlwaysReturnsSameSession() {
  var first  = sessionService.getOrCreate("cli", "wang", "default");
  var second = sessionService.getOrCreate("cli", "wang", "default");
  assertEquals(first.id(), second.id());          // 幂等：多轮对话靠它串起来

  var other = sessionService.getOrCreate("web", "wang", "default");
  assertNotEquals(first.id(), other.id());        // channel 不同就是不同会话
}
```

**三条断言逐条保真**，一条不少、一条不放松。

**「模拟重启」的口径**：`SessionRepositoryTest` 里存一条会话后，**新建一条独立的 JDBC 连接**（与写入侧不共享任何缓存、EntityManager 或 Spring 上下文）直读 `messages_json`，断言与写入时一致。理由：同一条持久化上下文里 `save` 完再 `findById` 走的是 Hibernate 一级缓存，**证明不了落盘**（[research.md](../research.md) D5 有完整的备选排除记录）。

**测试落位的一处偏离（如实记录）**：课件把「`messages_json` 序列化回读后消息完整」列在 `SessionRepositoryTest`，本节把它放 `SessionServiceTest`——序列化器在 core（§3），而 `oryxos-storage` 的**测试**也不能反向依赖 core（Maven 会让 `core(compile) → storage` 与 `storage(test) → core` 形成回边，破坏反应堆构建顺序）。两条合起来覆盖课件那条 harness 的完整意图。**此项在 Step 5 的固定停点提请用户确认。**

---

## 7. 本节**不**做的事

- **不写 `status` / `archived_at` 的第二态**：归档归第 26 节（`DELETE /api/v1/sessions/{id}`）。
- **不给 `Session` 加 `status` / `archivedAt` 字段**：本节没有调用方（章程原则 II / spec 仲裁 4）。
- **不做按条拆表**：`messages_json` 一列存整个历史，核心阶段不拆（课件原话「核心阶段不做按条拆表」）。
- **不改 `tool_invocations` / `llm_calls`**：两张审计表骨架期已交付，本节只读不写、不改结构。
