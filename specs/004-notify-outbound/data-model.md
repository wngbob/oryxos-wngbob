# Phase 1 数据模型：Notify 出站推送

**Feature**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md) | **Created**: 2026-10-14

本节的数据面很小：**一张表、一个实体、一个 Repository、一个值对象**（值对象骨架期已交付）。核心是「**表的一行 → 通知目标**」这条映射，以及它的三条确定性口径（默认渠道、`url` 键、`config_json` 形状）。

---

## 1. 领域概念与它们的关系

```text
notify_channels（表，A6）            ← 全局注册表，不属于任何 Agent
        │  一行
        ▼
NotifyChannelEntity                  ← oryxos-storage：全 String 字段，不认识 core/tool 类型
        │  经 NotifyChannelRegistry.resolve(name) 映射
        ▼
NotifyTarget                         ← oryxos-tool：骨架期契约，本节不改
   { channelType, config }               channelType ← type 列
        │                                config      ← config_json 键值 + url 键
        │  交给
        ▼
NotifyChannelAdapter.send(target, content)   ← oryxos-tool：骨架期契约，本节不改
        │  唯一实现
        ▼
WebhookNotifyAdapter                 ← 一次 HTTP POST，content 走 JSON body
```

**关系说明**：

- `notify_channels` **不与 `sessions` / `tool_invocations` / `llm_calls` 建立任何外键**。渠道是**全局**的，审计表里的 `tool_invocations` 只记 `tool_name = notify` 与输入输出，**不记渠道地址**（FR-019）——所以「哪次推送发到了哪个群」在审计表里是查不到的，这是刻意的：查得到就意味着地址进了库里的自由文本字段。
- `NotifyChannelEntity` **不是** `Profile` 的一部分，也**不进** `AGENT.md` frontmatter（`ProfileLoader.FORBIDDEN_KEYS` 已在代码层禁止）。Agent 侧只出现**注册名**。
- 本节**只读**：没有任何代码路径写这张表（FR-014）。运行期它恒为空 → 「渠道未配置」是预期路径，不是异常路径。

---

## 2. 表 `notify_channels`

**定义源**：`specs/000-runtime-kernel/data-model.md` **A6**（列名与语义逐字照抄，**不新增列**）。落地在 `oryxos-storage/src/main/resources/db/schema.sql`（**追加**，既有三表不动）。

```sql
CREATE TABLE IF NOT EXISTS notify_channels (
    name VARCHAR(128) PRIMARY KEY,
    type VARCHAR(32),
    url VARCHAR(1024),
    config_json TEXT,
    description VARCHAR(512)
);
```

| 列 | 类型 | 约束 | 语义 | 来源 |
| --- | --- | --- | --- | --- |
| `name` | `VARCHAR(128)` | **主键** | 渠道**注册名**——Agent 与模型引用的唯一标识（如 `team-lark`）。人写的短标识 | A6 `name`（PK） |
| `type` | `VARCHAR(32)` | 应用层要求非空（D12） | 渠道类型。核心阶段仅 `webhook`。**不做取值校验**（本节无「按 type 选适配器」的调用方） | A6 `type` |
| `url` | `VARCHAR(1024)` | 可为空（空即坏数据，由适配器报错） | webhook 地址。**含 token 的敏感串**——MUST NOT 出现在对话 / 日志 / 报错 / 输出（FR-019） | A6 `url` |
| `config_json` | `TEXT` | JSON 对象、值均为字符串（D7） | 类型相关配置的键值对 | A6 `config_json` |
| `description` | `VARCHAR(512)` | 可为空 | 人类可读的一句话说明 | A6 `description` |

**列长取值依据**：A6 未给列长度（spec 已记为欠定项），按建表脚本既有风格定，理由见 [research.md](research.md) D10。**不加 `NOT NULL`**（与既有三表风格一致，校验留在应用层给出可读报错）。**追加的注释 MUST NOT 含分号**（脚本按分号切分逐条执行）。

**明确不做的列**：`is_default`（A6 无此列，且本节无写入路径；默认渠道按 D6 从 `name` 推）、`created_at` / `updated_at`（A6 无此列）、`enabled`（A6 无此列，渠道增删即启用停用）。

---

## 3. 实体 `NotifyChannelEntity`（`oryxos-storage`）

`io.oryxlabs.oryxos.storage.entity.NotifyChannelEntity`

| Java 字段 | 列 | 类型 | 说明 |
| --- | --- | --- | --- |
| `name` | `name` | `String` | `@Id` + `@Column(name = "name", length = 128)`，**无 `@GeneratedValue`**——主键是业务键（人指定的注册名），自增策略会把插入的键改写掉 |
| `type` | `type` | `String` | `@Column(name = "type", length = 32)` |
| `url` | `url` | `String` | `@Column(name = "url", length = 1024)` |
| `configJson` | `config_json` | `String` | `@Column(name = "config_json", columnDefinition = "TEXT")` 显式桥接，**不依赖 Hibernate 命名策略**（既有 `SessionEntity` 同款做法） |
| `description` | `description` | `String` | `@Column(name = "description", length = 512)` |

**形状要求**：`@Entity @Table(name = "notify_channels")`；**无参构造 + getter/setter**（JPA 要求，与既有实体风格一致）。

> **★ 实现期修正（T002 落地时对齐既有实体，本节文档原稿已改）**：本节文档初稿写的是「`type` / `url` / `description` 直接同名列、无需 `@Column`」，并要求 `equals` / `hashCode` 按 `name` 实现。落地时对照 `oryxos-storage` 既有三个实体（`SessionEntity` / `ToolInvocationEntity` / `LlmCallEntity`），实际 house style 是**每个字段都带显式 `@Column`**（VARCHAR 给 `length`、TEXT 给 `columnDefinition`，`@Id` 字段也不例外），且**没有任何实体实现 `equals` / `hashCode`**。故：① 五个字段一律显式 `@Column`，长度按建表脚本的 `VARCHAR(n)` 对齐；② **不实现 `equals` / `hashCode`**——既有实体都没有，且本节没有任何调用方（YAGNI，章程原则 4）。表结构本身的定义源仍是 A6，此处只是把注解写法与既有代码拉齐。

**两条硬约束**：

1. **字段全部是 `String`**——不认识 `NotifyTarget` / `NotifyChannelDef` / `Profile` 等任何 core 或 tool 的类型。`oryxos-storage` 不依赖任何内部模块（章程附加约束），反向即成环。
2. **`configJson` 保持为原始字符串**——不在实体层做 `@Convert` 或 JSON 解析。解析（以及「值必须是字符串」的校验）留在消费方 `JpaNotifyChannelRegistry`（[research.md](research.md) D7 / D9）。

---

## 4. Repository `NotifyChannelRepository`（`oryxos-storage`）

`io.oryxlabs.oryxos.storage.repository.NotifyChannelRepository extends JpaRepository<NotifyChannelEntity, String>`

| 方法 | 来源 | 用途 |
| --- | --- | --- |
| `Optional<NotifyChannelEntity> findById(String name)` | 继承自 `JpaRepository` | 按注册名解析（**打错名字要能被识别出来**——`Optional.empty()` 即「这个名字不存在」） |
| `Optional<NotifyChannelEntity> findFirstByOrderByNameAsc()` | **本节新增的派生查询** | 默认渠道（D6）。下推成 `ORDER BY name ASC LIMIT 1`，不把整表读进内存 |

**本节 MUST NOT 新增的方法**：任何 `save` / `delete` / `deleteByName` 之类的**写入方法**——本节不引入写入路径（FR-014 / 仲裁 2），`JpaRepository` 继承来的 `save` 不在此列（那是框架给的，本节无调用方）。

**为什么用派生查询而不是 `findAll()` + 排序**：SQLite 不保证无 `ORDER BY` 的行序，且 `findAll()` 会把整表读进内存。默认渠道的确定性口径（D6）必须落在 SQL 层。

---

## 5. 行 → 通知目标的映射（本节的核心规则，FR-013）

`JpaNotifyChannelRegistry.resolve(channelName)` 的输出规则，逐条可测：

```text
输入：channelName（String，可为 null）

第 1 步：判定「是否走默认渠道」
        channelName == null            → 走默认
        channelName.isBlank()          → 走默认（空串 / 纯空白等同不传）
        否则                            → 按名解析

第 2 步：取行
        走默认  → repository.findFirstByOrderByNameAsc()
        按名解析 → repository.findById(channelName)
        两者都返回 Optional

第 3 步：Optional 为空 → 报错，MUST NOT 退回默认渠道
        走默认且表为空     → IllegalStateException（"未配置任何通知渠道…"）
        按名解析且名字不存在 → IllegalStateException（"未找到名为 'x' 的通知渠道…"）
        ★ 这两条是分开的报错，措辞不同：前者是「一个都没配」，后者是「你写错了名字」
        ★ 名字打错 MUST NOT 静默退回默认渠道（FR-011 / spec Edge Case）

第 4 步：校验 type 非空白 → 空白则 IllegalArgumentException（D12）

第 5 步：解析 config_json → Map<String, String>（D7）
        null / 空串        → 空 Map
        非 JSON 对象        → IllegalArgumentException
        值出现非字符串       → IllegalArgumentException（不静默转字符串）

第 6 步：叠加 url 键（D8）
        config.put("url", url 列的值，null 视为 "")
        ★ 恒定放入，即使为空串——适配器只需检查「值是不是空白」这一种形状
        ★ url 列的值覆盖 config_json 里可能存在的同名键（独立列是权威）

输出：new NotifyTarget(type, config)
```

**映射规则的三条确定性口径**（都进了 [spec.md](spec.md) 的 Assumptions 或 FR，此处给落地形状）：

| 口径 | 规则 | 为什么这么定 |
| --- | --- | --- |
| 默认渠道 | 按 `name` **升序的第一个** | A6 无排序列、无默认标志列；升序是唯一从现有列推出来的、且不依赖插入顺序的口径（D6） |
| `url` 键 | **恒定存在**于 `config` | 让适配器的契约可判定：只需处理「值是不是空白」，不必处理「键不存在」（D8） |
| `config_json` 形状 | **JSON 对象、值均为字符串** | 对齐 `NotifyTarget.config` 的 `Map<String, String>`；静默转字符串会把「写错的配置」伪装成「能用的配置」（D7） |

**本节 MUST NOT 做的事**：MUST NOT 在 `config` 里做 `${ENV_VAR}` 占位解析——A6 末段那段口径随第 26 节的写入路径一并定，本节**不假装实现了它**（仲裁 2 的连带影响，如实记录）。

---

## 6. 值对象 `NotifyTarget`（骨架期已交付，**本节不改**）

`io.oryxlabs.oryxos.tool.notify.NotifyTarget`

```java
public record NotifyTarget(String channelType, Map<String, String> config)
```

- `config` 在紧凑构造器里做 `config == null ? Map.of() : Map.copyOf(config)` —— 不可变、不为 null。
- **`channelType` 对应 `type` 列，`config` 承载类型相关配置**——接口不出现「企业微信」「飞书」这类某一档实现特有的词（FR-001）。
- **本节对它的唯一约束是不许动它**：签名与课件逐字一致，改一个字就违反软门禁 ④（修改前序节交付的公共接口）。

---

## 7. 状态与生命周期

**本节无状态迁移。** `notify_channels` 的行没有 `enabled` / `status` 列，渠道的存在即启用；本节也没有任何写入路径，故不存在「新建 → 启用 → 停用」这样的迁移。

| 事项 | 本节 | 归谁 |
| --- | --- | --- |
| 建表（DDL） | ✅ 追加到 `schema.sql` | 本节 |
| 读（按名 / 取默认） | ✅ `NotifyChannelRepository` + `JpaNotifyChannelRegistry` | 本节 |
| 写（增 / 改 / 删） | ❌ 不做 | 第 26 节（Web 管理台 + `/api/v1/notify-channels`） |
| 从文件导入 | ❌ 不做（A6 的 yaml 口径判为旧口径） | 第 26 节 |
| `${ENV_VAR}` 占位解析 | ❌ 不做 | 第 26 节（随写入路径） |

**运行期表的预期状态**：**空**。因为本节没有写入路径，`resolve` 在任何真实运行里都会走到「未配置任何通知渠道」这条报错分支。这不是缺陷，是 FR-014 的直接结果——它也让「渠道未配置必须明确报错」这条需求在本节就有了真实的可观测证据（[quickstart.md](quickstart.md) §4）。

---

## 8. 数据量 / 规模假设

- 渠道数量：**个位数**（一个企业几十个群已是上限）。全表扫描在任何实现下都不构成问题，默认渠道的派生查询下推 SQL 只是良好习惯。
- `config_json` 长度：小（几个键值对）；`TEXT` 类型留足余量。
- **无并发写入**：本节只读，且核心阶段 SQLite 走单连接池（`pool=1`），不存在写冲突。
- **无索引需求**：`name` 是主键（自带索引），本节只有按 `name` 的两种查法，都走主键。
