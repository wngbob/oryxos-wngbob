# Phase 0 研究：Notify 出站推送的技术决策

**Feature**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md) | **Created**: 2026-10-14

本节所有第三方 API 均在**动手前**于本地依赖中核实过（`javap` / `dependency:tree` / 离线 `dependency:get`），核实结论逐条记在对应决策里。凡「课件与技术方案都查不到」的欠定项，按「有依据的推断 + 显式记入假设」处理，不新开澄清问题。

---

## D1 渠道注册表接缝落 `oryxos-tool`，其持久化实现也落 `oryxos-tool`

**Decision**: 在 `io.oryxlabs.oryxos.tool.notify` 新增 `NotifyChannelRegistry`（**单方法只读**接口：`NotifyTarget resolve(String channelName)`）与 `JpaNotifyChannelRegistry`（消费 `NotifyChannelRepository`）。**不在 `oryxos-core` 的 `ProfileContext` 上加方法**，**不在 `oryxos-storage` 里实现该接口**。

**Rationale**:
1. **依赖方向**。`NotifyTarget` 契约在 `oryxos-tool`。给 `oryxos-core` 的 `ProfileContext` 加一个返回 `NotifyTarget` 的方法，就是 core → tool 的反向依赖，违反章程附加约束。上游参考实现把 `NotifyChannelRegistry` + `NotifyChannelDef` 放在 `oryxos-core`（且带完整 CRUD），那是**另一种取舍**——它把渠道定义当成了 core 的领域概念；本节按已报批的仲裁 3 走「接缝在 tool、core 不引用」这条与 `Sandbox` 完全同构的既有例外模式。
2. **实现放 tool 而非 storage**。若把 `JpaNotifyChannelRegistry` 放 `oryxos-storage`，它就 implements 了 `oryxos-tool` 的接口 → 形成 `storage → tool` 的反向边，把底层持久化模块绑到能力模块上。`tool → storage` 的方向与既有 `core → storage` 一致（都是「上层能力消费持久化」），明显更优。
3. **`oryxos-tool → oryxos-storage` 不新增工件**。实测 `mvn dependency:tree`：该边**本来就是传递依赖**（`oryxos-tool → oryxos-core → oryxos-storage`，compile scope）。显式声明只是把一条「碰巧存在」的隐式边写进 pom，避免 `JpaNotifyChannelRegistry` 编译期依赖偶然的传递关系。
4. **形状按最小可用定**。只有 FR-006 / FR-011 三个诉求（按名解析 / 缺省取默认 / 解析不到明确报错），故接口只留一个方法。上游那套 `list / find / exists / save / delete` 里，`save` / `delete` 属第 26 节的写入路径（仲裁 2 明令本节不做），`list` / `exists` 本节无调用方（YAGNI）。

**Alternatives considered**:
- *（上游做法）`NotifyChannelRegistry` + `NotifyChannelDef` 放 `oryxos-core`，带 CRUD* — **否**。core 会多出一个「通知渠道」领域概念与五个方法，其中三个本节无调用方；且与仲裁 3「core 一行不动」的报批结论冲突。**这是本节与上游参考实现最主要的分歧，已列入第 5 步固定停点的确认议程。**
- *在 `ProfileContext` 上加静态方法 `resolveNotifyChannel`* — **否**，反向依赖；且 `ProfileContext` 是 `final` 类 + 私有构造 + 全静态方法，课件的字段注入写法本身编译不过。
- *`NotifyTools` 直接注入 `NotifyChannelRepository`，自己查库* — **否**。工具会认识 `Jpa`/实体类型，破坏 FR-017「只依赖接口」；且「默认渠道取哪个」的口径会散进工具里，第 26 节加写入路径时更难收口。
- *注册表接缝放 `oryxos-core`、`JpaNotifyChannelRegistry` 放 `oryxos-storage`* — **否**，接口在 core 就得返回 `NotifyTarget`（tool 的类型），还是反向依赖。

---

## D2 `WebhookNotifyAdapter` 自建有界 `RestClient`，构造签名与课件不同

**Decision**: `public WebhookNotifyAdapter()` 无参构造，内部自行构造 `RestClient`；另有一个**包级私有**构造 `WebhookNotifyAdapter(Duration pushTimeout)` 供同包测试注入短超时。

**Rationale**: 课件签名是 `WebhookNotifyAdapter(RestClient restClient)`，把超时责任推给注入方。但 FR-021 / SC-010 要求**本节交付的推送本身**等待有界——否则第 25 节的定时任务会被一次「对端黑洞」永久卡住，而这正是本节要防的那类故障。自持客户端让「有界」成为实现自带的性质，而不是调用方的义务。

**Alternatives considered**:
- *（课件做法）`WebhookNotifyAdapter(RestClient)`* — **否**，FR-021 会退化成「谁装配谁负责」，第 24 节接线时漏设超时即无声失效。**这是与课件代码的第二处有意偏离，已列入固定停点确认议程。**
- *无参构造 + 只在测试里真等 10 秒* — **否**，测试会慢到没人愿意跑；包级私有构造让「有界」能在毫秒级被验证（见 D13）。
- *构造接收 `Duration` 为 public* — **否**，会把一个调参口暴露给第 24 节的装配方，重新引入「装配方可以设成无限」的漏洞。

---

## D3 超时用 `JdkClientHttpRequestFactory.setReadTimeout`，不用 `RestClient` 的 `requestFactory` 替换法

**Decision**: `RestClient.builder().requestFactory(factory)`，其中 `factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(T).followRedirects(NEVER).build())`，再 `factory.setReadTimeout(T)`，`T = Duration.ofSeconds(10)`。

**Rationale**: **已实测**（`javap -classpath spring-web-6.2.19.jar`）：`org.springframework.http.client.JdkClientHttpRequestFactory` 存在，且有 `public void setReadTimeout(Duration readTimeout)`。JDK `HttpClient` 自带 `connectTimeout`，两者相加即 FR-021 要求的「连接 + 读取合计有界」。选 JDK 客户端而非 Apache HttpClient 是因为**不新增第三方依赖**（`java.net.http` 在 JDK 里）。

**Alternatives considered**:
- *`SimpleClientHttpRequestFactory`* — **否**，其 `setReadTimeout` 走 `HttpURLConnection` 的 socket 超时，且 `connectTimeout`/`readTimeout` 语义在 JDK 版本间有漂移；JDK `HttpClient` 的超时语义更明确。
- *Apache `HttpComponentsClientHttpRequestFactory`* — **否**，需要新增 `httpclient5` 依赖（软门禁 ⑥）。
- *依赖 `RestClient` 默认工厂* — **否**，默认 `readTimeout` 不设，等于无界，直接违反 FR-021。

---

## D4 失败口径：只认 HTTP 状态码，不解析对端响应体的业务错误码

**Decision**: 2xx（含 2xx 非 200）视为送达；**其余状态码与连接/读取异常一律上抛**，不重试、不静默吞。**不解析**响应体里的 `code` / `errcode` / `StatusCode` 之类业务错误码。

**Rationale**: spec 的 Edge Case 明文规定「假 webhook 返回 2xx 但响应体里写着业务错误：按 HTTP 语义视为送达成功，本节不去解释对端响应体的业务含义」；仲裁 2 已把「逐家对接专用 API」划到扩展阶段，而业务错误码正是各家专用协议的产物。核心阶段只有一档通用 webhook 实现（FR-003），对端可能是任何东西，猜它的响应体形状就是过度拟合。

**Alternatives considered**:
- *（上游做法）`NotifyPoster` 解析 `errcode` / `StatusCode` / `code`* — **否**。那是逐家专用实现的职责；放进通用实现会变成一个「猜哪家」的分支链，且本节没有测试载体（harness 的假 webhook 返回的是裸响应）。**这是与上游参考实现的一处分歧，已记入固定停点确认议程。**
- *非 2xx 时重试 N 次* — **否**，FR-010 / spec「明确不做」明文禁止重试退避（重试属第 25 节定时任务的关注点）。
- *非 2xx 只记日志不抛* — **否**，违反 FR-006 / FR-010「不得静默失败」。

---

## D5 重定向显式钉为「不跟随」

**Decision**: `HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)`，**显式写在代码里**。

**Rationale**: `enforce` 只在发送前做一次（FR-007 的顺序不变量）。若跟随重定向，白名单内的 webhook 入口可以 302 到白名单外，白名单就成了一道可绕过的墙。JDK `HttpClient` 的**默认值本就是 `NEVER`**，但默认值会被将来的改动或工厂替换悄悄改变——写成显式代码，是让这条安全性质可被 review 看见。3xx 因而落入「非 2xx 即失败」的同一口径。

**Alternatives considered**:
- *（上游做法）逐跳复检白名单* — **更强，但本节做不了**：它需要每跳都调 `sandbox.enforce`，而 `Sandbox` 的实现归第 23/24 节，本节的单测用的是替身。**留待第 24 节评估**，已记入 [contracts/assembly-handoff.md](contracts/assembly-handoff.md) §4。
- *依赖 JDK 默认值、不写这一行* — **否**，安全性质不该建立在默认值上。

---

## D6 默认渠道 = 按注册名升序的第一个，用派生查询实现

**Decision**: `NotifyChannelRepository` 增派生查询 `Optional<NotifyChannelEntity> findFirstByOrderByNameAsc()`；`resolve(null / "" / 纯空白)` 走它。

**Rationale**: A6 的表定义里**没有排序列、也没有默认标志列**，spec 的 Assumptions 因此取「不依赖插入顺序的确定性口径」——按主键 `name` 升序的第一个。这是唯一能从现有列推出来、且在任何插入顺序下都稳定的选择。用 Spring Data 派生查询而不是自己 `findAll()` 再排序，是为了让排序下推到 SQL（`ORDER BY name ASC LIMIT 1`），不把整表读进内存。

**Alternatives considered**:
- *插入顺序的第一个（`findAll().get(0)`）* — **否**，SQLite 不保证无 `ORDER BY` 的行序，结果不可复现。
- *给 A6 加一列 `is_default`* — **否**，软门禁 ②：A6 的列名已定，加列属修改已定字面量；且本节无写入路径，这一列没人能填。
- *在 yaml 里指定默认渠道* — **否**，仲裁 2 已排除 yaml 路径。

---

## D7 `config_json` 解析用 Jackson `readTree`，值必须是字符串

**Decision**: `config_json` 用 `ObjectMapper.readTree` 解析；要求是 **JSON 对象且所有值均为文本**；非对象或出现非文本值 → 明确报错，**不静默转字符串**。空 / 空串 / `null` → 视为空配置（不报错）。

**Rationale**: `NotifyTarget` 的 `config` 是 `Map<String, String>`，值域天生是字符串。若把数字 `true` / `123` 静默 `toString()` 进去，一个「写错的配置」会被伪装成「能用的配置」，直到对端拒绝才发现——这与 FR-006「不得静默失败」的精神一致。空配置不报错，是因为「只有 url 没有额外配置」是正常情形（D8）。

**Alternatives considered**:
- *反序列化成 `Map<String, Object>` 再逐个 `toString()`* — **否**，静默转换，丢掉「写错了」这个信号。
- *不做解析、原样塞进一个键* — **否**，A6 明写 `config_json` 是「类型相关键值」，必须是展开的键值对。
- *用 SnakeYAML 解析* — **否**，列名是 `config_json`，A6 的语义是 JSON；且 Jackson 已在 classpath 上（经 `spring-ai-model`），不新增依赖。

---

## D8 行 → 目标的映射：`config` 恒含 `url` 键（即使为空）

**Decision**: 映射规则 = `channelType ← type 列`；`config = config_json 的键值` **再叠加** `config.put("url", url 列)`（`url` 列优先，覆盖 `config_json` 里可能存在的同名键）。**`url` 键恒定存在**，即使 `url` 列为空 → 空串。

**Rationale**: `url` 是 webhook 这一档实现的**必需参数**，把它提升为「映射层保证存在」的键，让适配器的契约变得可判定：适配器只需检查「`url` 键的值是不是空白」，不必处理「键根本不存在」。`url` 列优先于 `config_json` 里的同名键，是因为 A6 把 `url` 提成了独立列——独立列是权威。注册表**不做**「url 为空就报错」，那是适配器的职责（注册表按 D9 对 `type` 之外不做类型相关校验，见 D12）。

**Alternatives considered**:
- *只把 `url` 塞进 `config_json`、不设独立列* — **否**，A6 已定 `url` 为独立列（软门禁 ②）。
- *`url` 为空时不放这个键* — **否**，适配器要处理两种「没有地址」的形状，错误信息也会不一致。
- *在注册表里就校验 `url` 非空* — **否**，注册表是**类型无关**的（它不知道 `webhook` 需要 url 还是别的），校验属实现类的知识。

---

## D9 `NotifyChannelEntity` 字段全为 `String`，主键 `name` 无生成策略

**Decision**: `@Entity @Table(name = "notify_channels")`，字段 `name`（`@Id`，**无 `@GeneratedValue`**）、`type`、`url`、`configJson`（`@Column(name = "config_json")`）、`description`。全部 `String`。带无参构造 + getter/setter（JPA 要求）。

**Rationale**: 主键是**业务键**（注册名由人指定），不是自增代理键——所以不能有 `@GeneratedValue`，否则插入会被改写。字段全 `String` 让 `oryxos-storage` **不认识 core 与 tool 的任何类型**（章程附加约束：storage 不反向依赖）。列名 `config_json` 与 Java 字段 `configJson` 的差异用 `@Column` 显式桥接，不依赖 Hibernate 的命名策略（既有 `SessionEntity` 同款做法）。

**Alternatives considered**:
- *用 `@GeneratedValue` 自增主键 + 唯一约束 name* — **否**，A6 明写 `name` 是主键；多一个代理键会让「按名解析」多一次查询。
- *`config_json` 映射成 `Map<String,String>` 走 `@Convert`* — **否**，给 storage 引入了「配置内容」的语义知识，且转换器要自己处理异常；把「解析」留在注册表（消费方）更干净。
- *用 record + `@IdClass`* — **否**，JPA 的 record 支持需要额外配置，与既有实体风格不一致。

---

## D10 DDL 追加到既有 `schema.sql`，风格与既有三表一致

**Decision**: 在 `oryxos-storage/src/main/resources/db/schema.sql` **追加**（不改既有三表）：

```sql
CREATE TABLE IF NOT EXISTS notify_channels (
    name VARCHAR(128) PRIMARY KEY,
    type VARCHAR(32),
    url VARCHAR(1024),
    config_json TEXT,
    description VARCHAR(512)
);
```

**列长取值的依据**：A6 未给列长度（spec 已记为欠定项），按建表脚本既有风格定——`name` 对齐 `sessions.profile_name` 一档的 `VARCHAR(128)`（注册名是人写的短标识）；`type` 取 `VARCHAR(32)`（A6 说核心阶段仅 `webhook`，留余量）；`url` 取 `VARCHAR(1024)`（webhook 地址常带长 token 与查询串，1024 足够且不越 SQLite 的舒适区）；`config_json` 取 `TEXT`（与 `messages_json` 同款，JSON 长度不可预估）；`description` 取 `VARCHAR(512)`（人类可读的一句话）。

**不加 `NOT NULL`**：与既有三表风格一致（既有表也没有 `NOT NULL` 子句），且 D12 的校验在应用层做、报错信息更可读。

**注释里 MUST NOT 含分号**：脚本是按分号切分再逐条执行的，注释里的分号会把一条语句切成两半——既有文件头已写明这条踩坑记录，本节照办（本节追加的注释不带分号）。

**Alternatives considered**:
- *单独一个 `notify_channels.sql`* — **否**，既有测试按 classpath 单文件读取并切分执行，多文件要改测试基建，超出本节范围。
- *引 Flyway/Liquibase* — **否**，章程附加约束允许，但本节只加一张表，引入迁移框架属过度工程（YAGNI）；且既有三表也没走框架。
- *靠 `ddl-auto=update` 建表* — **否**，章程附加约束明令 MUST NOT。

---

## D11 不新增异常类型

**Decision**: 复用 JDK 与既有的异常：
- 渠道未配置 / 名字打错 / 默认渠道不存在 → `IllegalStateException`（「当前状态不允许这个操作」）
- 渠道名为空白以外的非法输入、`config_json` 形状错误、`type` 为空 → `IllegalArgumentException`（「参数本身不对」）
- 发送失败（非 2xx / 超时 / 不可达）→ 直接让 `RestClient` 抛出的异常上抛（`RestClientResponseException` 及其子类 / `ResourceAccessException`）
- 沙箱拒绝 → 既有 `SandboxViolationException`（骨架期已交付）

**Rationale**: 这些异常最终都由 `ToolExecutor` 统一接住（第 20 节接线后），把 `e.getMessage()` 写进 `tool_invocations.error_message`（第 17 节已交付的行为，已读源码确认）。所以**异常类型本身不携带额外语义**，新增自定义类型只会多一个没人 catch 的类。`IllegalStateException` vs `IllegalArgumentException` 的分界取「是环境没配好，还是调用方传错了」——前者运维要处理，后者模型要改参数，报错文案据此区分。

**Alternatives considered**:
- *新建 `NotifyChannelNotFoundException`* — **否**，超出交付物清单的 public 类型（软门禁 ①），且无调用方 catch。
- *发送失败也包一层自定义异常* — **否**，会丢掉 `RestClient` 异常里的状态码信息，而那是排障最需要的部分。

---

## D12 注册表对 `type` 只要求非空，不做取值校验

**Decision**: `resolve` 时若 `type` 为空白 → 报错；**不校验** `type` 是否是已知取值。

**Rationale**: spec 的 Assumptions 已记明——本节**没有「按 type 选适配器」的调用方**（只有一个 webhook 实现，`NotifyTools` 直接持有它），所以取值校验无处落地、也无从验证。要求非空是必要的：`type` 是 `NotifyTarget` 的构造参数，空白 `type` 会让目标本身失去意义。

**Alternatives considered**:
- *校验 `type == "webhook"`* — **否**，把「核心阶段只有一档实现」这个**当前事实**固化成校验规则；第 24 节加第二档实现时这条校验就成了要删的债。
- *完全不管 `type`* — **否**，空白类型的目标是坏数据，早报错比晚报错好。

---

## D13 「等待有界」的可测性：包级私有构造注入短超时

**Decision**: `WebhookNotifyAdapterTest` 里用 `new WebhookNotifyAdapter(Duration.ofMillis(200))` 构造一个短超时实例，对着一个**故意拖住响应头**的 MockWebServer（`headersDelay` 远大于超时）验证「超时按失败上抛」。

**Rationale**: FR-021 / SC-010 是「有界」这一性质的可度量判据。若只能拿 10 秒的默认实例测，测试要真等 10 秒——没人会跑，等于没有这条断言。包级私有构造让测试与实现在同一个包里（`io.oryxlabs.oryxos.tool.notify`），不需要把 `Duration` 参数暴露成 public API（见 D2）。**已实测** `mockwebserver3.MockResponse.Builder` 有 `headersDelay(long, TimeUnit)` 与 `bodyDelay(long, TimeUnit)`。

**★ 实测修正（T006 踩到，已改）**：原先写的 `bodyDelay` **测不出超时**——`JdkClientHttpRequest` 用 `HttpResponse.BodyHandlers.ofInputStream()` + 内嵌 `TimeoutHandler`（`javap -c` 已核），读取上限量的是「从请求派发到响应头到达」这一段；而适配器走 `toBodilessEntity()`，**根本不读 body**。用 `bodyDelay` 时响应头立即返回、body 没人读，请求 0.3 秒就正常返回了，超时断言无从触发（首跑 8 个用例里唯一的红）。改用 `headersDelay` 后按预期抛 `ResourceAccessException`。
这条同时**反向确认了 FR-021 的口径**：读取上限从派发计时，故 `connectTimeout(10s)` + `readTimeout(10s)` 的**合计**上界就是 10 秒（不是 20 秒），与「连接 + 读取合计 10 秒」逐字吻合。

**Alternatives considered**:
- *测 10 秒默认值* — **否**，慢到没人跑。
- *把超时做成 public 构造参数或 setter* — **否**，重新打开「装配方可以设成无限」的口子（D2）。
- *用反射改私有字段* — **否**，脆弱且丑。
- *用 `bodyDelay` 模拟对端不响应* — **否**，见上面的实测修正：本路径不读 body，拖 body 等于没拖。

---

## D14 `NotifyTools` 落 `notify` 包；`@Tool` 的 description 按全局注册表口径改写

**Decision**: `io.oryxlabs.oryxos.tool.notify.NotifyTools`（**不是**课件写的 `io.oryxos.tool.builtin`）。`@Tool(description = "把一条消息推送到已注册的通知渠道（不传渠道名时用默认渠道）")`（**不是**课件的「当前 Agent 配置好的通知渠道」）。

**Rationale**: 包落位依 `oryxos-lesson-dev` skill 第 19 节的模块落位表（「notify 包（Adapter/Target/Webhook 实现/`NotifyTools`）」整体归 `notify` 包）。这样本节这一刀切片在**同一个包内闭合**：接口、实现、工具、注册表接缝四者互不跨包暴露，`NotifyChannelRegistry` 的包级私有实现细节也不会漏出去。第 20 节的内置工具（File/Shell/Http）落 `builtin` 包时不受影响。

description 改写的理由是**这句话是模型可见的接口文案**：「当前 Agent 配置好的」正是仲裁 1 判为旧口径的表述（渠道是**全局**注册表、不属于任何 Agent），留着会把模型引向「渠道是每个 Agent 自己配的」——一个会直接影响模型行为的错误心智模型。改后的文案同时把「不传渠道名 = 用默认渠道」这个行为告诉了模型（FR-004）。

**Alternatives considered**:
- *（课件）`io.oryxos.tool.builtin`* — **否**，与模块落位表冲突，且会让本节的四个类跨两个包。
- *保留课件 description 原文* — **否**，旧口径文案会污染模型对渠道归属的理解；这属于**修正**而非「修改已定字面量」（软门禁 ② 点名的是类名/方法签名/配置键/表列名/端点路径）。**已在固定停点确认议程中显式列出。**
- *description 里写上「全局注册表」字样* — **否**，模型不需要知道实现细节；「已注册的」已足够表达「不是你自己配的」。

---

## D15 本节零装配，且 MUST NOT 造「全放行」的占位沙箱

**Decision**: 两个新类（`WebhookNotifyAdapter` / `NotifyTools`）与注册表实现**不带任何 Spring 注解**；`CliAssemblyConfiguration` **一行不改**；通知相关 Bean 声明全归第 24 节。**不提供**任何「测试用的全放行 Sandbox」实现类。

**Rationale**:
1. **装配纪律**：第 18 节定下「非 boot 模块不带 Spring 装配注解，Bean 一律在 `CliAssemblyConfiguration` 里显式声明」，课件的 `@Component` 与之冲突。
2. **硬约束**：`NotifyTools` 的构造依赖 `Sandbox`，而 `Sandbox` 的唯一实现 `WhitelistSandbox` 归第 23/24 节——**本节根本没有可注入的沙箱 Bean**。加 `@Component` 只会让重命令启动时报 `NoSuchBeanDefinitionException`（或更糟：被某个 `@ConditionalOnMissingBean` 悄悄吞掉）。
3. **不造占位沙箱**：一个「全放行」的 `Sandbox` 实现是本节最该拒绝的东西——它会把 FR-007 的核心安全不变量变成一句空话，且一旦进了主源码树，就可能在后续节被误当作可用实现接上去。测试需要替身时用 **Mockito mock**（在测试源码树里），不落主源码。

**Alternatives considered**:
- *（课件）`@Component`* — **否**，装配纪律 + 无沙箱 Bean。
- *造 `PermissiveSandbox` 放主源码供装配* — **否**，见上第 3 点。
- *本节就把 `WhitelistSandbox` 一起做了* — **否**，那是第 23/24 节的交付物，越节（skill 第 1 步依赖检查的反向：不得跳节自造）。

---

## 依赖核实记录（动手前实测，2026-10-14）

| 核实项 | 手段 | 结论 |
| --- | --- | --- |
| `spring-web` 是否已在 `oryxos-tool` | `mvn -pl oryxos-tool dependency:tree` | **不在** → 需显式声明（D3 / 仲裁 6） |
| `spring-web` 是否已在发行物 classpath | 同上 + `oryxos-provider` 依赖链 | **在**（经 spring-ai 传递）→ 显式声明不新增工件 |
| `JdkClientHttpRequestFactory` + `setReadTimeout(Duration)` | `javap` on `spring-web-6.2.19.jar` | **存在**，签名符合（D3） |
| `spring-ai-model` 是否已在 `oryxos-tool` | `dependency:tree` | **在**（经 `oryxos-core`），版本 `1.1.8` 由根 `spring-ai-bom` 托管（D14 / 仲裁 5） |
| `oryxos-storage` 是否已在 `oryxos-tool` | `dependency:tree` | **在**（经 `oryxos-core`，compile scope）→ 显式声明不新增工件（D1） |
| `jackson-databind` 是否已在 classpath | `dependency:tree` | **在**，`2.21.4`（经 `spring-ai-model`）→ 解析 `config_json` 不新增依赖（D7） |
| `mockwebserver3:5.5.0` 可离线解析 | `dependency:get -o` | **EXIT=0**；自身声明 `kotlin-stdlib 2.1.21` + `okhttp-jvm 5.5.0`，两者均已在 `~/.m2`（仲裁 7） |
| **★ `kotlin-stdlib` 被 Spring Boot BOM 降级** | 加依赖后 `mvn -o -pl oryxos-tool -am test` | **实测踩到**：`spring-boot-dependencies 3.5.16` 把 `kotlin.version` 钉在 `1.9.25` 并托管 `kotlin-stdlib`，**覆盖**了 okhttp-jvm 声明的 `2.1.21` → 本机无 `1.9.25`（只有 1.3.72/1.8.21/1.9.10/2.1.21/2.4.20），离线构建 `Could not resolve kotlin-stdlib:jar:1.9.25` 直接 **BUILD FAILURE**。**修正**：`oryxos-tool/pom.xml` 加模块级 `dependencyManagement` 把 `kotlin-stdlib` 钉回 `2.1.21`（软门禁 ⑥ 报批项，用户裁决「钉 2.1.21」） |
| 钉回 2.1.21 后无残留警告 | `dependency:tree` | `kotlin-stdlib:jar:2.1.21:test`，**无** `The POM ... 1.9.25 is missing` 警告。只写直接依赖也能赢仲裁（nearest-wins），但图里会留一个 1.9.25 节点、每次构建刷警告，故覆盖落在 `dependencyManagement` 层 |
| `mockwebserver3.MockWebServer` 不拖 JUnit 4 | `javap` | `implements Closeable`；`start()/url(String)/enqueue()/takeRequest()` 齐备 |
| `MockResponse.Builder` 的能力 | `javap` | 有 `code(int)` / `body(String)` / `setHeader(String,Object)` / `headersDelay(long,TimeUnit)` / `bodyDelay(long,TimeUnit)`（D13） |
| **★ 超时该拖响应头还是拖 body** | `javap -c` on `JdkClientHttpRequest` | 用 `BodyHandlers.ofInputStream()` + 内嵌 `TimeoutHandler` → 读取上限量「派发→响应头到达」，且 `toBodilessEntity()` 不读 body。**必须用 `headersDelay`**，`bodyDelay` 测不出超时（D13 实测修正） |
| `RecordedRequest.getBody()` 的类型 | `javap` | `okio.ByteString`（不是 Buffer）→ 取正文用 `.utf8()` |
| `RecordedRequest` 的读取面 | `javap` | 有 `getMethod()` / `getTarget()` / `getBody()` / `getUrl()` |
| `ToolResult` 的静态工厂 | 读源码 | `success(String)` / `failure(String)` / `retryableFailure(String)` 存在（课件的 `ToolResult.success("已推送")` 原样编译） |
| `ToolExecutor` 的失败审计路径 | 读源码 | `RuntimeException` → `success=false` + `error_message = e.getMessage()`，落 `tool_invocations`（D11 的依据） |
| `ProfileLoader.FORBIDDEN_KEYS` | 读源码 | 第 54 行 `Set.of("skills", "notify_channels")`（仲裁 1 的代码级证据） |
| `ProfileContext` 的形状 | 读源码 | `final` 类 + 私有构造 + 全静态方法（仲裁 3 的代码级证据） |
| 建表脚本的执行方式 | 读 `SessionRepositoryTest` | 读 classpath 单文件 → `split(";")` → 逐条执行（D10 的依据） |
| 根 pom 是否托管 `oryxos-*` 版本 | 读根 `pom.xml` | 是，9 个模块工件全在 `dependencyManagement` 里 → 新增内部模块依赖不写版本号 |

**未使用 `@Tag("integration")`**：本节无集成冒烟测试类（课件 harness 两批都是单测层，MockWebServer 是本地假服务）；真 webhook 验收是人工项（[quickstart.md](quickstart.md) §5）。
