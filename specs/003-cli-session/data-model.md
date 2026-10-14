# Data Model: CLI 与会话持久化

**来源**：[spec.md](spec.md) Key Entities + `docs/TechnicalSolution.md` §8.4 / §8.6 / §9.2 + `specs/000-runtime-kernel/data-model.md` A1 + 第 17 节已交付的 `Session` / `SessionMessage`。三种存储形态：SQLite 表（会话）、文件系统（工作区）、内存派生（会话标识）。

---

## 1. `Session`（领域类型，**本节修改**）

`io.oryxlabs.oryxos.core.session.Session`，第 17 节交付，本节增两个字段。

| 字段 | 类型 | 本节变化 | 说明 |
| --- | --- | --- | --- |
| `id` | `String` | 不变 | 会话标识，形如 `cli:local:daily-weather`（见 §3） |
| `channel` | `String` | **新增** | 接入渠道：`cli` / `api` / `scheduler` / `gateway`；对应 A1 的 `channel` 列 |
| `userId` | `String` | **新增** | 用户标识；CLI 固定 `local`；对应 A1 的 `user_id` 列 |
| `profileName` | `String` | 不变 | 所属 Agent 名；`AgentService` 靠它从 `ProfileRegistry` 取 Profile |
| `messages` | `List<SessionMessage>` | 不变 | 按序累积的会话历史，只读视图 |
| `createdAt` | `Instant` | 不变 | 创建时间 |
| `lastActiveAt` | `Instant` | 不变 | 最后一次追加消息的时间 |

**七项齐备**（第 17 节契约 `specs/002-react-loop/contracts/session-model.md` §2 的「五项」断言随本次修订改为七项，该文件同步更新）。

**刻意不带**：`status` / `archivedAt`（spec 仲裁 4）。`sessions` 表有这两列、本节也照 A1 建出来，但领域类型不引入本节无调用方的字段——归档是第 26 节 `DELETE /api/v1/sessions/{id}` 的行为。

**工厂与恢复**：

| 方法 | 可见性 | 用途 |
| --- | --- | --- |
| `Session.create(id, channel, userId, profileName)` | **public**（签名变更，已报批） | 新建一个空会话；第 17 节的两参调用点随之改为四参 |
| `Session.restore(id, channel, userId, profileName, messages, createdAt, lastActiveAt)` | **package-private** | 从库中恢复历史。**不新增 public 概念**——`JpaSessionService` 与 `Session` 同在 `core.session` 包，包内可见即够 |

**为什么必须要有 `restore`**：`Session` 是只追加的（第 17 节契约 §3 不变量 1），三个 `append*` 方法都盖 `Instant.now()` 时间戳。用它们重放历史会把**每条消息的时间戳改成「现在」**——历史读回来时间全错。`restore` 直接接收已带时间戳的消息列表，**不改写任何时间戳**。

**四条不变量（第 17 节契约 §3，本节不改）**：只追加 / 文本与工具请求同属一条消息 / `SYSTEM` 角色不写入 / 非线程安全。

---

## 2. `SessionMessage` 与落盘形状（**本节不改类型，只定义磁盘格式**）

`io.oryxlabs.oryxos.core.session.SessionMessage`，第 17 节交付，**本节一行不改**（加 Jackson 注解会改前序节公共类型，且把磁盘格式绑死在枚举命名上——spec 计划偏离 ②）。

```java
public record SessionMessage(
    Role role, String content, List<AssistantMessage.ToolCall> toolCalls,
    String toolCallId, Instant timestamp) {
  public enum Role { SYSTEM, USER, ASSISTANT, TOOL }
}
```

**落盘形状由 `MessageJson`（**package-private**，`core.session` 包内）单独钉住**：

| JSON 字段 | 类型 | 来源 | 约定 |
| --- | --- | --- | --- |
| `role` | String | `SessionMessage.Role` | **小写**：`system` / `user` / `assistant` / `tool`（A1 要求；`Role` 的枚举名是大写，故**不能直接序列化**） |
| `content` | String | `content` | 原样 |
| `toolCalls` | 数组，可缺省 | `toolCalls` | 每项 `{id, type, name, arguments}`——`AssistantMessage.ToolCall` 的四个组件（Jackson 2.21 原生支持 record 读写，**不需要额外模块**） |
| `toolCallId` | String，可缺省 | `toolCallId` | 原样 |
| `timestamp` | String | `timestamp` | `Instant.toString()`（ISO-8601 UTC，带 `Z`），往返无损 |

**与 A1 逐字对齐**：A1 写「JSON 数组，元素 `{role, content, toolCalls?, toolCallId?, timestamp}`，`role ∈ system/user/assistant/tool`」——上表就是它的展开。

**校验规则**（A1）：`messages_json` 序列化失败视为内部错误**不落库**（上抛，不写半条）。

**为什么是包级私有**：它不是对外概念，不触发软门禁 ①；磁盘格式独立于内存类型演进——将来改 `Role` 枚举名不会悄悄改磁盘格式。

---

## 3. 会话标识口径（**唯一拼接点**）

**公式**：`session_id = channel + ":" + user_id + ":" + profile_name`

| 触发源 | channel | user_id | 示例 |
| --- | --- | --- | --- |
| CLI | `cli` | `local`（固定字面量） | `cli:local:daily-weather` |
| Web Service（第 26 节） | `api` | 调用方传（默认 `default`） | `api:u_123:daily-weather` |
| 定时任务（第 25 节） | `scheduler` | `scheduler`（固定字面量） | `scheduler:scheduler:daily-weather` |

**规则**：

1. **拼接只发生在唯一一处**——`core.session` 包内一个**包级私有**的 `SessionId`（`of(channel, userId, profileName)`）。所有入口只提供三元组，**MUST NOT 自己拼字符串**（FR-006 / SC-003；H4 不变量 ④）。
2. **长度 ≤ 128**（A1 的 `VARCHAR(128)`）。三段中任一段为空 / 含分隔符 → 视为调用方错误，抛异常，不静默产出脏标识。
3. **幂等**：同一三元组历次 `getOrCreate` 返回**同一个** `id`（FR-007）。
4. **隔离**：三元组任一不同 → 不同会话（FR-007）。这是「同一个人分别从 CLI 和 HTTP 接入得到两条互不相认的历史」的实现方式（spec 用户场景 3）。

---

## 4. `sessions` 表（**本节新建**，逐字照 A1）

建表语句手工维护在 `oryxos-storage/src/main/resources/db/schema.sql`（`ddl-auto=none`，不依赖 update 自动迁移）。列名与长度**逐字照抄** `specs/000-runtime-kernel/data-model.md` A1，**不新增列、不改类型**（spec 仲裁 12）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `session_id` | VARCHAR(128) PK | `channel:user:profile` 三段联合生成（§3） |
| `profile_name` | VARCHAR(128) | 关联 Profile；写入前必须存在（校验落在调用方，见 §6） |
| `channel` | VARCHAR(32) | `cli` / `api` / `scheduler` / `gateway` |
| `user_id` | VARCHAR(128) | 用户标识；CLI 固定 `local` |
| `messages_json` | TEXT | JSON 数组，形状见 §2 |
| `status` | VARCHAR(16) | `active` / `archived` |
| `created_at` / `last_active_at` / `archived_at` | TIMESTAMP | 创建 / 最后活跃 / 归档时间 |

**本节的行为**：新建会话落 `status = 'active'`、`archived_at` 为空；**归档是第 26 节的事**，本节不写这两个字段的第二态。

**脚本切分前提**：`init` 内嵌执行脚本时按 `;` 切分（沿用 `LlmCallRepositoryTest` 的既有做法），故新表的语句**不写含分号的注释**。

---

## 5. `SessionEntity`（`oryxos-storage` 侧）

`io.oryxlabs.oryxos.storage.entity.SessionEntity`，与既有 `LlmCall` / `ToolInvocation` 同构。

| Java 字段 | 列 | 类型 | 说明 |
| --- | --- | --- | --- |
| `sessionId` | `session_id` | `String`，`@Id` | **不用 `@GeneratedValue`**——主键由 §3 的三段拼接生成，不是数据库自增 |
| `profileName` | `profile_name` | `String` | |
| `channel` | `channel` | `String` | |
| `userId` | `user_id` | `String` | |
| `messagesJson` | `messages_json` | `String` | **原始 JSON 文本**，实体不理解其内容 |
| `status` | `status` | `String` | |
| `createdAt` / `lastActiveAt` / `archivedAt` | 同名列 | `LocalDateTime` | 与 `LlmCall` 的既有写法一致（ISO-8601 本地时区） |

**硬约束**：所有字段都是基本类型与 `String`，**MUST NOT 出现 `Session` / `SessionMessage` 等 core 类型**——`oryxos-storage` 不依赖 `oryxos-core`，反向即成环（章程附加约束）。实体只负责「原样存、原样取」，序列化口径归 core（§2）。

`io.oryxlabs.oryxos.storage.repository.SessionRepository extends JpaRepository<SessionEntity, String>`——主键类型是 `String`。

---

## 6. `SessionService`（接口，**本节补齐**）

`io.oryxlabs.oryxos.core.session.SessionService`，第 17 节交付、本节补两个操作。

| 方法 | 语义 |
| --- | --- |
| `Session getOrCreate(String channel, String userId, String profileName)` | 按 §3 算出标识；命中则**从库中恢复历史**返回，未命中则新建空会话。同一三元组历次调用返回同一标识（幂等） |
| `Optional<Session> get(String sessionId)` | 按标识读会话；不存在返回空——调用方决定是报错还是跳过 |
| `void save(Session session)` | 落库。**新增**则整行写入；**已存在**则更新 `messages_json` 与 `last_active_at`，**不动** `created_at`、`status`、`archived_at` |

**实现类 `JpaSessionService`**（`oryxos-core`，`core.session` 包）：注入 `SessionRepository`，用 §2 的 `MessageJson` 做序列化。放 core 的理由见 spec 仲裁 3。

**`profile_name` 存在性校验不在这里**（spec 仲裁 12）：接缝签名只有三元组、拿不到 Agent 清单。校验由**调用顺序**天然满足——CLI 先经 `AgentResolver` 解析出 Profile，解析不通过就走不到取会话这一步。

**`save` 的三条不变量**：

1. **`created_at` 不被覆盖**——恢复出来的 `Session.createdAt()` 就是库里那一行的值，写回时必须一致；
2. **`status` / `archived_at` 不被覆盖**——归档是第 26 节的行为，本节只做「新增时写 `active` + 空」，**更新时原样保留**，避免把已归档会话悄悄复活；
3. **序列化失败不落库**（§2）——上抛，不写半条。

---

## 7. 工作区（文件系统，`oryxos init` 产出）

**本节新建**的目录与文件（幂等：已存在的一律不覆盖，重复执行输出「已有 / 新建」两份清单）：

```text
.oryxos/
├── agents/            # 空目录（MUST NOT 生成默认 Agent —— spec 仲裁 14）
├── skills/            # 空目录（公共 Skill 实体库）
├── memory/
│   └── MEMORY.md      # 空文件，含 `## 核心记忆` / `## 归档记忆` 两个区标题
├── mcp_servers.yaml   # MCP server 配置模板
├── sessions/          # 空目录
├── logs/              # 空目录
├── output/            # 空目录
├── AGENTS.md          # Bootstrap：项目级 agent 行为说明
├── SOUL.md            # Bootstrap：默认 agent 人格定义
├── USER.md            # Bootstrap：用户偏好
└── oryxos.db          # SQLite，建出全部表（内嵌执行 db/schema.sql）
```

**MUST NOT 创建**：`notify_channels.yaml`（归第 19 节的 SQLite 全局注册表）、`sandbox.yaml`（归第 24 节）——spec 仲裁 7。

**工作区判定**：某目录是工作区 ⟺ 存在 `.oryxos/` 子目录。需要工作区的命令在非工作区目录执行时，报错提示先跑 `oryxos init`（FR-010）。

---

## 8. 实体关系图

```text
Profile（内存派生，来自 AGENT.md frontmatter）
    │  profileName
    ▼
Session（内存领域类型，§1，七字段）
    │  messages_json 序列化（§2）
    ▼
sessions（SQLite 表，§4）── SessionEntity（§5）── SessionRepository
    │
    └── session_id 由 channel + user + profile 三段拼接（§3，唯一拼接点）

tool_invocations / llm_calls（骨架期已建，本节只读不写、不改结构）
    按 session_id 归集，与 sessions 是并列的三张表，会话历史不替代审计
```

**三张表的关系**：`sessions` 存**对话内容**，`tool_invocations` / `llm_calls` 存**审计留痕**（章程原则 VI）。两者按 `session_id` 归集，但**互不替代**——审计表的权威性高于会话消息（第 17 节 `Session.appendToolResult` 的注释已说明：成败与否的权威记录是 `tool_invocations`，不是会话消息）。
