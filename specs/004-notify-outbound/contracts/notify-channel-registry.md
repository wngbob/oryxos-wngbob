# 契约：渠道注册表接缝 + 表 / 实体 / Repository

**Feature**: [spec.md](../spec.md) | **Plan**: [plan.md](../plan.md) | **Created**: 2026-10-14

本文件钉死本节新增的**接缝**（`NotifyChannelRegistry`）与**持久化层**（`NotifyChannelEntity` / `NotifyChannelRepository` / DDL）的形状，以及「行 → 通知目标」的确定性口径。

> **接缝是超出课件交付物清单的新 public 类型**（软门禁 ① 报备项，随仲裁 3 一并报批通过）。课件的解析点是 `profileContext.resolveNotifyChannel(channel)`，按依赖方向落不了地（[research.md](../research.md) D1）。**本文件定稿其名称与形状。**

---

## 1. `NotifyChannelRegistry` —— 单方法只读接缝

`io.oryxlabs.oryxos.tool.notify.NotifyChannelRegistry`

```java
public interface NotifyChannelRegistry {
    NotifyTarget resolve(String channelName);
}
```

| # | 要求 | 理由 / 判定 |
| --- | --- | --- |
| 1.1 | **只有一个方法** `resolve(String)` | 本节全部诉求就是「按名解析 / 缺省取默认 / 解析不到报错」（FR-006 / FR-011）。多一个方法都无调用方（YAGNI） |
| 1.2 | **MUST NOT 出现写入方法**（`save` / `delete` / `add` / `remove` / `importFrom`…） | FR-014 / 仲裁 2：本节不引入写入路径。第 26 节加写入时**只能新增方法**，不改本节调用方（章程原则 VII：升级只新增实现类/方法、不改接口和调用方） |
| 1.3 | **MUST NOT 出现 `list()` / `find()` / `exists()`** | 无调用方；`list` 还会诱导调用方自己排序，破坏默认渠道口径的唯一性（1.5） |
| 1.4 | 入参名 `channelName`，`null` 与空白等同「不传」 | FR-004 / FR-011 |
| 1.5 | 返回 `NotifyTarget`（**不是** `Optional`、**不是** `null`） | 「解析不到」是**错误**而非「空结果」——FR-006 要求明确报错。返回 `Optional` 会把「忘了处理空」这个 bug 交给调用方，而调用方（`NotifyTools`）的正确行为恰恰是抛异常 |
| 1.6 | 声明在 `oryxos-tool`，**不是** `oryxos-core` | 返回类型 `NotifyTarget` 在 tool；放 core 即反向依赖（[research.md](../research.md) D1） |
| 1.7 | MUST NOT 带 Spring 注解 | 零装配 |

**接口命名**：`NotifyChannelRegistry`（注册表）。与上游参考实现**同名但不同包、不同职责**——上游那套在 `oryxos-core` 且带 CRUD。**这处分歧已列入第 5 步固定停点的确认议程**（用户的仓库同时是向上游提 PR 的 fork）。

### 1.1 `resolve` 的完整语义（可逐条测试）

| 输入 | 行为 | 抛出的异常 |
| --- | --- | --- |
| `null` | 走默认渠道 = `name` **升序第一个** | 表为空 → `IllegalStateException`（"未配置任何通知渠道…"） |
| `""` / `"   "` | 同上（空白等同不传） | 同上 |
| `"team-lark"`（存在） | 按名取该行 | — |
| `"team-lark "`（首尾空白） | **先 trim 再查**；trim 后为空则等同不传 | — |
| `"team-lrak"`（名字打错） | **报错，MUST NOT 退回默认渠道** | `IllegalStateException`（"未找到名为 'team-lrak' 的通知渠道…"） |
| 任意（行的 `type` 为空白） | 报错 | `IllegalArgumentException`（[research.md](../research.md) D12） |
| 任意（`config_json` 形状错） | 报错 | `IllegalArgumentException`（[research.md](../research.md) D7） |

**「走默认」与「名字打错」的报错文案 MUST 可区分**（两种情形要的是两种不同的处理动作）：

```text
未配置任何通知渠道，请先在注册表中添加一个（第 26 节提供管理入口）
未找到名为 'team-lrak' 的通知渠道，请检查渠道名拼写
```

---

## 2. `JpaNotifyChannelRegistry` —— 接缝的持久化实现

`io.oryxlabs.oryxos.tool.notify.JpaNotifyChannelRegistry implements NotifyChannelRegistry`

```java
public JpaNotifyChannelRegistry(NotifyChannelRepository repository)
```

| # | 要求 | 理由 / 判定 |
| --- | --- | --- |
| 2.1 | 构造只接受 `NotifyChannelRepository` 一个接口 | 依赖面最小；不做缓存（渠道数量个位数，查库成本可忽略，缓存会引入失效问题） |
| 2.2 | **落 `oryxos-tool`，不落 `oryxos-storage`** | 若落 storage 就形成 `storage → tool` 反向边（[research.md](../research.md) D1） |
| 2.3 | 默认渠道 MUST 用 `repository.findFirstByOrderByNameAsc()` | 排序下推 SQL；`findAll()` + 内存排序会把整表读进来，且无 `ORDER BY` 时 SQLite 行序无保证（D6） |
| 2.4 | 行→目标的映射 MUST 逐条实现 [data-model.md](../data-model.md) §5 的六步 | 每个分支都要有对应断言 |
| 2.5 | MUST NOT 做 `${ENV_VAR}` 占位解析 | 该口径随第 26 节的写入路径一并定；本节**不假装实现了它**（仲裁 2） |
| 2.6 | MUST NOT 把 `url` 值写进日志或异常消息 | FR-019：含 token 的地址是敏感串。报错只提渠道名 |
| 2.7 | MUST NOT 带 Spring 注解 | 零装配 |

**为什么实现放 tool 而不是 storage**：见 [research.md](../research.md) D1。要点是方向——`tool → storage` 与既有 `core → storage` 一致（上层能力消费持久化），`storage → tool` 则把低层持久化模块绑到能力模块上。且实测该边**本来就是传递依赖**，显式声明不新增工件。

**为什么不做缓存**：渠道数量个位数、本节无写入路径（表恒为空）、核心阶段 SQLite 单连接。加缓存只会引入「第 26 节写入后缓存未失效」的新问题，而收益是零。

---

## 3. `NotifyChannelEntity` + `NotifyChannelRepository`（`oryxos-storage`）

```java
@Entity @Table(name = "notify_channels")
public class NotifyChannelEntity {
    @Id private String name;                 // 无 @GeneratedValue：主键是人指定的注册名
    private String type;
    private String url;
    @Column(name = "config_json") private String configJson;
    private String description;
    // 无参构造 + getter/setter（JPA 要求）；equals/hashCode 按 name
}

public interface NotifyChannelRepository extends JpaRepository<NotifyChannelEntity, String> {
    Optional<NotifyChannelEntity> findFirstByOrderByNameAsc();   // 本节唯一新增的方法
}
```

| # | 要求 | 理由 / 判定 |
| --- | --- | --- |
| 3.1 | **字段全部是 `String`** | `oryxos-storage` MUST NOT 认识 core / tool 的任何类型（章程附加约束：storage 不反向依赖） |
| 3.2 | `name` 是 `@Id` 且**无 `@GeneratedValue`** | 主键是业务键（人指定的注册名），自增策略会改写插入的键 |
| 3.3 | `configJson` 保持**原始字符串**，MUST NOT 用 `@Convert` 或在此层解析 JSON | 解析与校验留在消费方（`JpaNotifyChannelRegistry`）；实体层解析会把「配置形状」的知识塞进持久化层（[research.md](../research.md) D9） |
| 3.4 | 列名桥接用 `@Column(name = "config_json")` 显式声明 | 不依赖 Hibernate 命名策略（既有 `SessionEntity` 同款做法） |
| 3.5 | Repository MUST NOT 新增任何写入方法 | FR-014；继承来的 `save` 本节无调用方 |
| 3.6 | 本模块 MUST NOT 出现 `NotifyTarget` / `NotifyChannelAdapter` / `NotifyChannelRegistry` 的任何 import | 反向依赖的 grep 判据（3.1 的可执行形式） |

---

## 4. DDL 契约

追加到 `oryxos-storage/src/main/resources/db/schema.sql`（**既有三表不动**）：

```sql
CREATE TABLE IF NOT EXISTS notify_channels (
    name VARCHAR(128) PRIMARY KEY,
    type VARCHAR(32),
    url VARCHAR(1024),
    config_json TEXT,
    description VARCHAR(512)
);
```

| # | 要求 | 判定 |
| --- | --- | --- |
| 4.1 | 列名与语义**逐字**照 `specs/000-runtime-kernel/data-model.md` A6：`name`（主键）/ `type` / `url` / `config_json` / `description` | 列名逐一比对；**不新增列**（软门禁 ②） |
| 4.2 | MUST 用 `CREATE TABLE IF NOT EXISTS` | 与既有三表风格一致；脚本可能被重复执行 |
| 4.3 | MUST NOT 加 `NOT NULL` 子句 | 与既有三表风格一致；校验留在应用层给出可读报错（[research.md](../research.md) D10） |
| 4.4 | **追加的注释 MUST NOT 含分号字符** | 脚本按分号切分逐条执行——注释里的分号会把一条语句切成两半。既有文件头已写明这条踩坑记录 |
| 4.5 | MUST NOT 依赖 `hibernate.ddl-auto` 建表或迁移 | 章程附加约束；测试用 `ddl-auto=none` + 手工脚本（既有 `SessionRepositoryTest` 同款） |
| 4.6 | 列长取值：`name` 128 / `type` 32 / `url` 1024 / `config_json` TEXT / `description` 512 | A6 未给列长（spec 记为欠定项），按建表脚本既有风格定，依据见 [research.md](../research.md) D10 |

---

## 5. 测试契约

| 测试类 | 模块 | 覆盖 |
| --- | --- | --- |
| `JpaNotifyChannelRegistryTest` | `oryxos-tool` | §1.1 全部六行输入 + §2.3 / §2.5 / §2.6（mock `NotifyChannelRepository`） |
| `NotifyChannelRepositoryTest` | `oryxos-storage` | §4 的建表列齐备（`PRAGMA table_info`）+ §3.1/3.2 的存取往返 + §2.3 的默认渠道**不依赖插入顺序**（乱序插入后取到的仍是 `name` 最小者） |

**`NotifyChannelRepositoryTest` 的基建**：沿用 `SessionRepositoryTest` 的既有做法——`@DataJpaTest` + `@AutoConfigureTestDatabase(replace = NONE)` + `@Transactional(propagation = NOT_SUPPORTED)` + `@DynamicPropertySource` 指向 SQLite 临时文件 + 读 classpath `db/schema.sql` 后 `split(";")` 逐条执行。

**纪律**：MUST NOT 删断言 / `@Disabled` / 放宽阈值；方法名英文、课件中文原文进 `@DisplayName`；不碰网络。
