# Phase 1 Data Model: Memory（第 22 节）

**Feature**: `specs/006-memory-service` | **Date**: 2026-10-15 | **Plan**: [plan.md](./plan.md)

> 本节的数据形态有**四种**，分属三个落点：SQLite 表（`oryxos-storage`）、Markdown 文件（`.oryxos/memory/`）、远端 Mem0（协议契约）、配置（`oryxos-boot`）。下面逐个定死。

---

## 1. `memory_entries` 表（SQLite 档的持久化形态）

**落点**：`oryxos-storage/src/main/resources/db/schema.sql`（手工维护，`ddl-auto=none`，与 `sessions` / 审计表同口径）

**DDL（逐字照课件 §三 第三步，spec 仲裁 6）**：

```sql
-- memory_entries：长期记忆条目（手工建表，与 sessions/llm_calls 同口径）
CREATE TABLE IF NOT EXISTS memory_entries (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    scope VARCHAR(16) NOT NULL,          -- CORE / ARCHIVAL
    content TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_memory_scope ON memory_entries (scope);
```

**列清单**：

| 列 | 类型 | 约束 | 语义 |
| --- | --- | --- | --- |
| `id` | `INTEGER` | `PRIMARY KEY AUTOINCREMENT` | 自增主键。**顺序即写入顺序**——归档区的"最近 N 条"按它倒序取 |
| `scope` | `VARCHAR(16)` | `NOT NULL` | `CORE` / `ARCHIVAL`（`MemoryScope.name()`） |
| `content` | `TEXT` | `NOT NULL` | 记忆正文，**不含**日期标记（与 Markdown 档不同形，见 §3） |
| `created_at` | `TIMESTAMP` | `NOT NULL` | 写入时刻，由 `@PrePersist` 兜底 |

**索引**：`idx_memory_scope`（单列 `scope`）。理由：三条查询里 `scope` 都是选择性谓词（`findByScopeOrderByIdAsc` / `findByScopeOrderByIdDesc` / `searchArchival`）。

**脚本约束（既有坑，逐字照 `schema.sql` 文件头）**：**本文件里任何注释都 MUST NOT 含分号字符**——`oryxos init` 与测试都是按分号切分脚本再逐条执行的，注释里的分号会切出"只有注释、没有语句"的碎片，驱动执行它之后底层句柄即失效，下一条真语句直接报 `The prepared statement has been finalized`。故上面的 `-- CORE / ARCHIVAL` 行内注释**不含分号**。

**落地路径（仲裁 11）**：`InitCommand.run()` 里 `database.runSchemaScript()` 是**无条件**执行的，脚本内全是 `CREATE TABLE IF NOT EXISTS` → **老工作区跑一次 `oryxos init` 即补齐**。`SqliteMemoryStore` **不自带建表**（与 `sessions` / 审计表同口径）。代价：忘了跑 `oryxos init` 的用户会撞上 `no such table: memory_entries`——MUST 包成带「请先运行 `oryxos init`」指引的明确消息。

---

## 2. `MemoryEntry` 实体 + `MemoryEntryRepository`

**落点**：`oryxos-storage`（`entity/` 与 `repository/` 两个既有子包），**与上游同名同形**

### 2.1 `MemoryEntry`（`io.oryxlabs.oryxos.storage.entity`）

| 字段 | 类型 | 映射 | 说明 |
| --- | --- | --- | --- |
| `id` | `Long` | `@Id @GeneratedValue(strategy = GenerationType.IDENTITY)` | 不对外暴露 setter（自增列） |
| `scope` | `String` | `@Column(nullable = false)` | 存 `MemoryScope.name()`，**不在实体里用枚举**——`oryxos-storage` 不依赖 `oryxos-memory`（依赖方向），枚举类型过不来 |
| `content` | `String` | `@Column(nullable = false)` | 记忆正文 |
| `createdAt` | `Instant` | `@Column(name = "created_at", nullable = false)` | `@PrePersist void onCreate()`：为 null 时填 `Instant.now()` |

类上：`@Entity @Table(name = "memory_entries")`；空参构造器（JPA 要求）；`scope` / `content` 有 getter + setter，`id` / `createdAt` 只有 getter。

> **为什么 `scope` 是 `String` 而不是 `MemoryScope` 枚举**：`oryxos-storage` MUST NOT 依赖 `oryxos-memory`（依赖方向是 `memory → storage`）。上游同款。这正是"实体层不认领域枚举"这条既有分法的延续（`NotifyChannelEntity.type` 也是 `String`——合法取值由装配处的映射表决定，数据库层不做约束）。

### 2.2 `MemoryEntryRepository`（`io.oryxlabs.oryxos.storage.repository`）

```java
public interface MemoryEntryRepository extends JpaRepository<MemoryEntry, Long> {

  List<MemoryEntry> findByScopeOrderByIdAsc(String scope);

  List<MemoryEntry> findByScopeOrderByIdDesc(String scope, Pageable pageable);

  @Query("SELECT m FROM MemoryEntry m WHERE m.scope = 'ARCHIVAL'"
      + " AND LOWER(m.content) LIKE LOWER(:pattern) ORDER BY m.id ASC")
  List<MemoryEntry> searchArchival(@Param("pattern") String pattern);
}
```

| 方法 | 谁调 | 语义 |
| --- | --- | --- |
| `findByScopeOrderByIdAsc` | `SqliteMemoryStore.load()` 的核心区 | 全量、写入顺序——**不加任何 LIMIT**（契约二：核心区永不截断） |
| `findByScopeOrderByIdDesc` | `SqliteMemoryStore.load()` 的归档区 | 配 `PageRequest.of(0, 100)` 取**最近 100 条**（倒序），调用方再翻回正序 |
| `searchArchival` | `SqliteMemoryStore.recallByKeyword()` | 只搜归档区；`pattern` 由调用方拼成 `"%" + keyword + "%"` |

**关于 `LOWER(...) LIKE LOWER(...)`（与上游的差异，见 plan D10）**：上游写的是裸 `LIKE`，靠 SQLite "LIKE 对 ASCII 默认不区分大小写"这个**驱动默认行为**。本仓显式 `LOWER()` 两侧——FR-008 要求"跨档统一不区分大小写"，把它做成实现层的确定性保证，而不是一条依赖驱动默认值的巧合。代价：`content` 上若有索引会失效（本节没有，且这个量级无所谓）。

**为什么用 `Pageable` 而不是 `LIMIT`**：JPQL 不支持 `LIMIT`；Spring Data 的派生查询收 `Pageable` 时会自动追加 `LIMIT` / `OFFSET`（上游同款）。`PageRequest.of(int, int)` 已 `javap` 核实存在（[research.md](./research.md) §2）。

---

## 3. `.oryxos/memory/MEMORY.md`（Markdown 档的持久化形态）

**路径**：`.oryxos/memory/MEMORY.md`（**全局单份**，仲裁 4——路径里没有 Agent 名）

**模板**：`oryxos init` 已建（`InitCommand.MEMORY_MD`），字面量**逐字钉死**（仲裁 12）：

```markdown
## 核心记忆

## 归档记忆
```

**两个 header 字面量**（含井号数量与两个半角空格，MUST NOT 自造形式）：

| 常量 | 字面量 |
| --- | --- |
| `CORE_HEADER` | `## 核心记忆` |
| `ARCHIVE_HEADER` | `## 归档记忆` |

**条目行格式**（FR-020，课件与上游逐字相同）：

```
- [YYYY-MM-DD] 正文
```

日期**只到日、无时分秒**（`LocalDate.now()`）。理由：课件与上游同节都是这个形状；上游到更晚的版本才加上时分秒，**本节 MUST 按第 22 节的形状**。

**文件的规范形态**（`append` 写回时一律重写成这个形状）：

```
## 核心记忆
- [2026-10-15] 用户叫小王，偏好用 Java

## 归档记忆
- [2026-10-15] 归档流水 0
- [2026-10-15] 归档流水 1
```

**解析规则**（`extractSection`）：按 header 字面量定位，取到**下一个 header 之前**（两个 header 都要找，取更近的那个），`strip()` 后返回。两个 header 都缺时两个区都当空——**MUST NOT 把整份文件内容当成核心记忆全量注入**（Edge Case 第 2 条：那会把用户的随手笔记塞进每次上下文）。

**空记忆的口径（D6）**：文件不存在 → 当空处理，不报错；**两个区都空 → `load()` 返回 `""`**（不是只有两行 header 的残渣），让 `PromptBuilder` 的 `.filter(m -> !m.isBlank())` 把 prompt 第二部分整条跳过（FR-014 / SC-007）。

**归档区截断（契约二）**：`MAX_ARCHIVE_CHARS = 4000`。口径：**只裁归档那一段字符串**——核心区那段根本不在入参里，物理上碰不到（"靠物理隔离保证"，不是靠判断）。**刚好等于阈值不裁**（"超过才裁"）。裁的时候**保留尾部**（最近的），`substring(len - MAX)`。

---

## 4. Mem0 档的协议契约（远端，本节不真连）

**落点**：`Mem0MemoryStore` 的 REST 调用。地址与凭证**只走环境变量**（FR-018/019）。

| 操作 | 方法 + 路径 | 请求体 | 响应 |
| --- | --- | --- | --- |
| `append` | `POST /v1/memories/` | `{"messages":[{"role":"user","content":<正文>}], "user_id":<uid>, "metadata":{"scope":<CORE\|ARCHIVAL>}}` | 不读（`toBodilessEntity`） |
| `load`（按区） | `GET /v1/memories/?user_id={uid}&scope={scope}` | — | Jackson 解析：`results[].memory` 或根 `memory` |
| `recallByKeyword` | `POST /v1/memories/search/` | `{"query":<关键词>, "user_id":<uid>}` | 同上 |

**契约映射**：
- 契约一（不缓存）→ 每次调 API，天然满足
- 契约二（核心区不截断）→ **交给 Mem0 的作用域机制**：`CORE` 全量取、`ARCHIVAL` 由 Mem0 侧管理；本档 `load()` 不自己裁字符串
- 契约四（只搜归档区）→ 由 Mem0 的 `search` 承担，能力声明为 `DELEGATED`（语义检索是关键词检索的**加强版实现**）
- `archivalEntries()` → **返回空**（FR-011：检索外置档不提供归档条目视图）

**服务不可达**：MUST 以明确错误上抛（落审计路径），**MUST NOT 静默返回空**——静默返回空会让 Agent 以为"记忆是空的"，进而重复询问用户（Edge Case）。

**局限如实记录**：报文格式**未经真 server 验证**，只由 `Mem0MemoryStoreTest` 的 mock 断言"我们发出去的是这个形状"。真连是人工项。

---

## 5. 两个枚举（骨架期已交付，本节**一行不改**）

### `MemoryScope`（`io.oryxlabs.oryxos.memory.store`）

| 取值 | 语义 |
| --- | --- |
| `CORE` | 核心记忆——始终完整注入、**不截断**、**不参与检索**（它本来就在场） |
| `ARCHIVAL` | 归档记忆——可截断、可被检索 |

缺省值 `ARCHIVAL` 的落点在 **`MemoryTools`**（工具参数语义），不在门面：`MemoryServiceImpl.remember` 要求 `scope` 非 null。

### `MemoryCapability`（同上包）

| 取值 | 本节谁返回 | 门面的处理 |
| --- | --- | --- |
| `KEYWORD` | `MarkdownMemoryStore` / `SqliteMemoryStore` / `InMemoryMemoryStore` | 调 `recallByKeyword` |
| `DELEGATED` | `Mem0MemoryStore` | 调 `recallByKeyword`（档内自己做语义检索） |
| `HYBRID_BUILTIN` | **无实现方**（仲裁 5） | **抛异常**，MUST NOT 静默降级 |

---

## 6. 配置（`oryxos-boot/src/main/resources/application.yaml`）

```yaml
oryxos:
  memory:
    # 长期记忆后端：markdown（默认，零依赖、人可读、git 可跟踪）| sqlite（量大了的结构化升级，仍零外部依赖）| mem0（自托管外部集成档）
    backend: markdown
    mem0:
      # 自托管 Mem0 server 地址与作用域标识。凭证与地址一律走环境变量占位、禁止明文（章程 VIII / FR-018）。
      # 两个占位都**带默认值**（空 / oryxos）——markdown 与 sqlite 两档不读它们，缺失不该让启动失败。
      # 只有 backend=mem0 时才会校验非空；缺失时抛异常且消息**只带变量名、不带值**。
      base-url: ${MEM0_BASE_URL:}
      user-id: ${MEM0_USER_ID:oryxos}
```

| 键 | 取值 | 缺省 | 未知取值时 |
| --- | --- | --- | --- |
| `oryxos.memory.backend` | `markdown` \| `sqlite` \| `mem0` | `markdown` | **报错并列出合法取值**，MUST NOT 静默退回默认档（FR-016） |
| `oryxos.memory.mem0.base-url` | 环境变量 `MEM0_BASE_URL` | 空 | `backend=mem0` 且为空 → 报错，消息只带变量名 `MEM0_BASE_URL` |
| `oryxos.memory.mem0.user-id` | 环境变量 `MEM0_USER_ID` | `oryxos` | — |

**前缀口径（D8）**：本仓配置键**一律带 `oryxos.` 前缀**（`application.yaml` 有 `oryxos:` 根块，代码侧先例 `oryxos.search.tavily-api-key`）。**环境变量名不带前缀**——那是外部系统的既有命名。这是本节与上游的唯一有意分歧（上游是裸键 `memory.backend`），已报批。

**`MEMORY.md` 的路径不在配置里**：它是编译期固定常量（`workspace.root().resolve("memory").resolve("MEMORY.md")`），不是可配项——正因为固定，记忆文件 IO 才不过 `Sandbox.enforce`（仲裁 3：模型无法把它指向别处，不构成越界面）。
