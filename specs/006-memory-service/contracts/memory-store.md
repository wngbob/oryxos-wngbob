# 契约：`LongTermMemoryStore`（可插拔长期记忆后端）

**Feature**: `specs/006-memory-service` | **Date**: 2026-10-15

> **本契约在骨架期已交付，本节一个字不改**（FR-025 / 仲裁 1）。本文件做两件事：把 5 个方法的行为口径写死，并给出**三档实现的行为对照表**——后者才是本节"接口墙"值钱的地方：同一套断言对三档统一跑（FR-024）。

---

## 1. 接口

**落点**：`oryxos-memory` 的 `io.oryxlabs.oryxos.memory.store.LongTermMemoryStore`（骨架期已交付，`git diff` 为空）

```java
public interface LongTermMemoryStore {
  void append(String content, MemoryScope scope);
  String load();
  List<String> recallByKeyword(String keyword);
  MemoryCapability capabilities();
  List<String> archivalEntries();
}
```

**四个实现**：`MarkdownMemoryStore`（默认）/ `SqliteMemoryStore` / `Mem0MemoryStore` / `InMemoryMemoryStore`（测试期替身，**不是第四档可选后端**）。

## 2. 逐方法契约

### 2.1 `append(String content, MemoryScope scope)`

| 项 | 契约 |
| --- | --- |
| 语义 | 把 `content` **追加**到 `scope` 指定的分区 |
| 重复 | **纯追加**，不去重、不合并（记忆压缩属明确不做）——已知取舍，不是缺陷 |
| `scope` 为 `null` | **不约定**（门面层要求非 null；缺省 `ARCHIVAL` 的落点在 `MemoryTools`） |
| 契约一 | 写完**立即**可被 `load()` / `recallByKeyword()` 看到——任何进程内缓存都破这条 |

### 2.2 `load()`

| 项 | 契约 |
| --- | --- |
| 语义 | 返回**可整段注入 prompt** 的长期记忆文本：核心区**全量** + 归档区**截断后** |
| 形状 | **三档统一**带两个 header（D7）：`## 核心记忆\n<core>\n## 归档记忆\n<archive>` |
| 空记忆 | **两个区都空 → 返回 `""`**（D6）——MUST NOT 返回只有 header 的残渣（FR-014 / SC-007） |
| 契约一 | **每次重新读**（读文件 / 查库 / 调 API），MUST NOT 缓存 |
| 契约二 | 截断**只作用归档区**，核心区一字不少 |
| 非归档区文本 | 核心区**永远完整在场**，不受任何阈值影响 |

### 2.3 `recallByKeyword(String keyword)`

| 项 | 契约 |
| --- | --- |
| 语义 | 关键词检索**归档区** |
| 范围 | **只搜归档区**；核心区 MUST NOT 参与检索（它本来就被全量注入，参与检索等于重复占上下文）——SC-005 |
| 大小写 | **跨档统一不区分大小写**（仲裁 8 / D10） |
| 匹配方式 | 核心阶段是**简单包含匹配**——MUST NOT 正则、MUST NOT 分词、MUST NOT 向量 |
| 截断无关 | 检索 MUST 覆盖**归档区全量**，不受 `load()` 的截断影响（FR-009：截断只决定"注入上下文的那一份"，否则记忆"存了但查不到"） |
| 未命中 | 返回**空列表**（不抛异常、不返回 null） |
| 返回形状 | 每条命中**原样**返回（Markdown 档带 `- [日期]` 前缀的行，SQLite 档是 `content` 原文） |

### 2.4 `capabilities()`

| 项 | 契约 |
| --- | --- |
| 语义 | 声明本档的检索能力，供门面**路由** `recall` |
| 取值 | 见下表；`HYBRID_BUILTIN` 本节**无实现方**（仲裁 5） |
| 稳定性 | 对同一个实例必须**恒定**（不能随调用变化） |

### 2.5 `archivalEntries()`

| 项 | 契约 |
| --- | --- |
| 语义 | 归档区**全量条目视图**，供将来的时间新近路与索引对账取数 |
| `DELEGATED` 档 | **返回空**（FR-011） |
| **消费方** | **本节无消费方**（如实标注，仲裁 1）——MUST NOT 假装有 |

---

## 3. 三档行为对照表（本节最值钱的一张表）

| | `MarkdownMemoryStore`（默认） | `SqliteMemoryStore` | `Mem0MemoryStore` | `InMemoryMemoryStore`（替身） |
| --- | --- | --- | --- | --- |
| **落点** | `.oryxos/memory/MEMORY.md` | `memory_entries` 表 | 自托管 Mem0 server | 进程内 `List` |
| **外部依赖** | 无 | 无（复用已有 SQLite） | 一个自托管服务 | 无 |
| **核心区取法** | 抽 header 段字符串，全量 | `WHERE scope='CORE'` 全量 | `getByScope("CORE")` 全量 | 全量 |
| **归档截断手段** | **裁字符串**：超 4000 字符取尾部 | **`LIMIT`**：最近 100 条 | **交给 Mem0 的作用域机制** | 最近 100 条 |
| **截断阈值** | `MAX_ARCHIVE_CHARS = 4000` | `MAX_ARCHIVE_ROWS = 100` | 无（外部管） | `MAX_ARCHIVE_ROWS = 100` |
| **检索手段** | 行扫 + 大小写归一 | `LOWER(...) LIKE LOWER(...)` | Mem0 自带 `search`（语义） | 行扫 + 大小写归一 |
| **`capabilities()`** | `KEYWORD` | `KEYWORD` | `DELEGATED` | `KEYWORD` |
| **`archivalEntries()`** | 归档区条目行 | 归档区条目正文 | **空**（FR-011） | 归档区条目 |
| **契约一（不缓存）** | 每次 `Files.readString` | 每次查库（天然满足） | 每次调 API | 每次读 List（天然满足） |
| **契约二（核心不截断）** | 归档段**物理上不在入参里** | 核心区查询**不带 LIMIT** | 信任 Mem0 的作用域机制 | 核心 List 不参与截断 |
| **日期标记** | 条目行带 `- [YYYY-MM-DD]` | 只存正文，时间在 `created_at` 列 | 交给 Mem0 | 条目行带 `- [YYYY-MM-DD]` |

**这张表就是"同一套契约、三种落地"的证据**：截断从"字符串掐头"变成 `LIMIT` 再变成"外部服务自己管"；检索从 `contains` 变成 `LIKE` 再变成语义检索——**上层一个字没改**。

---

## 4. 契约测试怎么钉住它（`MemoryStoreContractTest`）

**参数化遍历三档**（`@MethodSource`：`MarkdownMemoryStore` / `SqliteMemoryStore` / `InMemoryMemoryStore`），同一套断言全过——**任一档破契约，对应那一行参数立刻变红且能定位到档位**（SC-003）。

**第三档即课件的「内存假 Mem0」替身**——契约测的是「Mem0 这一档有没有守规矩」，真实 REST 报文由 `Mem0MemoryStoreTest` 用 mock 单独验证（见 §4 末段）。`Mem0MemoryStore` **真类不进参数化源**：`MockRestServiceServer` 是单测级、有严格期望序列，不适合当契约测试的参数。

**两条关键回归点**（课件 harness 里写出代码的两个，逐条保真，方法名译成英文 + `@DisplayName` 保留课件原文）：

| 课件原名 | 英文方法名 | 断言 |
| --- | --- | --- |
| `截断只裁归档区_核心记忆一字不能少` | `truncation_onlyTrimsArchival_coreMemorySurvivesIntact` | 写 1 条核心 + 500 条归档（远超阈值）后 `load()`：含核心那条；**不含** `归档流水 0`；**含** `归档流水 499` |
| `写入后立刻可读_不允许有缓存` | `append_isImmediatelyVisible_noCaching` | `append` 后 `load()` 含它、`recallByKeyword` 立刻命中（非空） |

> 500 条 × 每条约 30 字符 ≈ 15000 字符，对 4000 字符阈值与 100 条上限都成立；故同一条断言对三档都成立。

**其余契约断言**：

| 断言 | 钉住的条款 |
| --- | --- |
| 写 `CORE` 后只在核心区、写 `ARCHIVAL` 后只在归档区 | 契约三（scope 路由） |
| 核心区的内容 `recallByKeyword` **搜不到** | 契约四 / SC-005 |
| 检索大小写不敏感（同一关键词换大小写各命中一次） | FR-008 / D10 |
| 归档区被截断后，**被裁掉的内容仍能被检索到** | FR-009（截断只影响注入的那一份） |
| 两个区都空时 `load()` 返回 `""` | D6 / FR-014 / SC-007 |
| 归档区**刚好等于阈值**时不截断 | Edge Case 第 3 条 |
| `capabilities()` 对同一实例恒定、且取值在三态之内 | §2.4 |

**分工明确**：契约测试用替身驱动 Mem0 档那一行（测的是"这一档有没有守规矩"）；**真实 REST 交互由 `Mem0MemoryStoreTest` 单独 mock 验证**（测的是"我们发出去的报文对不对"）。两者不互相替代。

**`archivalEntries()`**：契约测试断言归档区条目、且与 `load()` 的截断无关（FR-011）。**「`DELEGATED` 档返回空」这条由 `Mem0MemoryStoreTest` 钉**——替身档的 `capabilities()` 是 `KEYWORD`，参数化源里没有 `DELEGATED` 档可测。
