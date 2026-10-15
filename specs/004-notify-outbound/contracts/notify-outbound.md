# 契约：出站通知（适配器 + 通知工具）

**Feature**: [spec.md](../spec.md) | **Plan**: [plan.md](../plan.md) | **Created**: 2026-10-14

本文件是**本节对外交付的契约**，也是第 20 / 24 节接线时的接口依据。三部分：中立接口（骨架期已定，本节不改）、唯一实现 `WebhookNotifyAdapter`、内置工具 `NotifyTools`。

---

## 1. 中立接口（骨架期已交付，**本节一个字不改**）

`io.oryxlabs.oryxos.tool.notify`

```java
public interface NotifyChannelAdapter {
    void send(NotifyTarget target, String content);
}

public record NotifyTarget(String channelType, Map<String, String> config) {
    // config 紧凑构造器：null → Map.of()，否则 Map.copyOf(...)
}
```

**契约要求**（FR-001 / FR-002 / SC-007）：

| # | 要求 | 怎么判定 |
| --- | --- | --- |
| 1.1 | 签名里 MUST NOT 出现「企业微信」「飞书」「钉钉」「wecom」「feishu」「dingtalk」「webhook」这类某一档实现特有的词 | **判定口径（2026-10-15 澄清）**：对两个文件的**方法名 / 参数名与类型 / 返回类型 / record 组件名**逐一核对，这些位置命中数 = 0。原文本写「对源码 grep，命中数 = 0」——那个口径本身有歧义且**实际不成立**：`NotifyChannelAdapter` 的 javadoc 里为说明实现去向提到了 `WebhookNotifyAdapter`（是**类名**、不是厂商字面量，且早于本节就存在）。javadoc 里的说明性提及不构成契约违反；**代码标识符**里出现才构成 |
| 1.2 | 接口 MUST NOT 出现「渠道类型枚举」「URL 字段」「HTTP 方法」等实现细节 | 人读签名即可判定；`channelType` 是 `String` 而非枚举，`config` 是不透明的 `Map<String,String>` |
| 1.3 | `send` MUST NOT 有返回值 | 送达即返回，失败即抛异常（无「部分成功」概念） |
| 1.4 | 两个文件的**签名与字段**逐字保持现状 | `git diff` 对这两个文件为空（2026-10-15 补强后复核：仍为空） |

**为什么 `send` 不返回结果对象**：返回值会立刻引出「失败要不要返回而不是抛」「部分渠道成功怎么办」两个本阶段不存在的问题（一次推送只有一个目标）。失败抛异常 + 由 `ToolExecutor` 统一接住落审计（FR-008），是既有的、唯一的一条失败路径。

---

## 2. `WebhookNotifyAdapter` —— 通用档实现（FR-003）

> **修订（2026-10-15 补强，已报批）**：本标题原文是「核心阶段唯一实现」。**「唯一」二字已被推翻**——现在同包另有方言档 `FeishuNotifyAdapter`（飞书 / Lark）与路由档 `RoutingNotifyChannelAdapter`，契约分别见 §2.4 / §2.5。**本节其余内容（§2.1~§2.3、§2.2.10）逐条继续成立**：`WebhookNotifyAdapter` 的代码一行未改（仅类 javadoc 首句由「核心阶段唯一实现」改为「通用档实现」，并补一句指向同包方言档），它的报文形状与「2xx 即送达」口径对**通用 webhook 接收端**依然正确。FR-003 被修订的也只是「唯一」二字，见 `spec.md` FR-003 的修订注。

`io.oryxlabs.oryxos.tool.notify.WebhookNotifyAdapter implements NotifyChannelAdapter`

### 2.1 构造契约

```java
public WebhookNotifyAdapter()                                  // 生产用：内置 10 秒有界超时
WebhookNotifyAdapter(Duration pushTimeout)                     // 包级私有：仅供同包测试注入短超时
```

**要求**：

| # | 要求 | 理由 |
| --- | --- | --- |
| 2.1.1 | **无参构造 MUST 存在且为 public** | 第 24 节的装配点用它声明 Bean；调用方不需要知道超时怎么配 |
| 2.1.2 | 无参构造 MUST 内置 `Duration.ofSeconds(10)`（连接 + 读取合计） | FR-021 / SC-010：等待有界是**实现自带的性质**，不是调用方的义务 |
| 2.1.3 | 另一个构造 MUST 是**包级私有**（无 `public` / `protected`） | 避免把调参口暴露给装配方，否则「有界」可被设成无限（[research.md](../research.md) D2） |
| 2.1.4 | MUST NOT 有 `WebhookNotifyAdapter(RestClient)` 这种把客户端注入进来的 public 构造 | 同上；课件签名如此，本节**有意偏离**（[plan.md](../plan.md) 偏离 ③） |
| 2.1.5 | MUST NOT 带任何 Spring 注解 | 零装配（[assembly-handoff.md](assembly-handoff.md)） |

### 2.2 HTTP 行为契约

| # | 要求 | 判定 |
| --- | --- | --- |
| 2.2.1 | 对 `target.config().get("url")` 发**一次** HTTP **POST** | MockWebServer `takeRequest()` 拿到 1 个请求，`getMethod()` = `"POST"` |
| 2.2.2 | 请求 body 是 JSON，且**含 `content` 字段**，值等于传入的 `content` | 解析 `RecordedRequest.getBody()`，断言 `content` 字段值相等 |
| 2.2.3 | **地址来自 `NotifyTarget` 配置，MUST NOT 硬编码** | 用两个不同的 MockWebServer 端口跑同一段代码，请求分别落在各自的服务上 |
| 2.2.4 | `url` 为空 / 空白 → `IllegalArgumentException`，且**一次请求都不发** | MockWebServer `getRequestCount()` = 0 |
| 2.2.5 | 响应非 2xx（含 5xx / 4xx / 3xx）→ **异常向上抛**，MUST NOT 静默吞 | 断言抛 `RestClientResponseException` 及其子类（或至少 `RuntimeException`）；`assertThatThrownBy` |
| 2.2.6 | 连接失败 / 超时 → 异常向上抛 | 见 2.2.7 的超时用例 |
| 2.2.7 | 超过构造时给定的超时 → 异常向上抛 | 短超时实例 + **`headersDelay`** 远大于超时的 MockWebServer。**不能用 `bodyDelay`**：`JdkClientHttpRequest` 走 `ofInputStream` + `TimeoutHandler`，读取上限量的是「派发→响应头到达」，而 `toBodilessEntity()` 不读 body，拖 body 等于没拖（[research.md](../research.md) D13 实测修正） |
| 2.2.8 | **MUST NOT 重试**——一次 `send` 最多一个 HTTP 请求 | 5xx 用例里断言 `getRequestCount()` = 1 |
| 2.2.9 | **MUST NOT 跟随重定向**（`HttpClient.Redirect.NEVER` 显式设置） | 3xx 落入 2.2.5 的失败口径；见 [research.md](../research.md) D5 |
| 2.2.10 | **MUST NOT 解析响应体的业务错误码**（`code` / `errcode` / `StatusCode` 等） | 2xx 即视为送达，即使 body 里写着业务错误（[research.md](../research.md) D4） |
| 2.2.11 | MUST NOT 把 `url` 写进日志、异常消息或返回值 | grep 源码与异常文案；含 token 的地址是敏感串（FR-019） |

**为什么 `url` 空白的校验在适配器而不在注册表**：注册表是**类型无关**的（它不知道 `webhook` 这一档需要 url 还是别的认证信息），「什么配置才算合法」是**实现类的知识**（[research.md](../research.md) D8）。

**为什么用 `content` 作字段名**：FR-003 说「把内容按 JSON 送出」，spec 的协议假设是 `{"content": ...}`。这是**通用档**被钉死的报文形状；对端要别的字段名时走**专用实现**——2026-10-15 补强落地的 `FeishuNotifyAdapter`（§2.4）正是这句话的兑现：它是另一个类，本档的形状未动。

### 2.3 抛出的异常类型

| 情形 | 异常 |
| --- | --- |
| `url` 键不存在或值为空白 | `IllegalArgumentException`（参数本身不对） |
| 非 2xx 响应 | `RestClient` 抛出的 `RestClientResponseException` 及其子类，**原样上抛，不包装** |
| 连接失败 / 超时 | `RestClient` 抛出的 `ResourceAccessException`，**原样上抛** |

**不新增自定义异常类型**（[research.md](../research.md) D11）：这些异常最终都由第 20 节接上的 `ToolExecutor` 统一接住，把 `e.getMessage()` 写进 `tool_invocations.error_message`（第 17 节已交付的行为）。异常类型不携带额外语义，新增只会多一个没人 catch 的类。

---

## 2.4 `FeishuNotifyAdapter` —— 方言档实现（FR-022 ~ FR-024，2026-10-15 补强新增）

`io.oryxlabs.oryxos.tool.notify.FeishuNotifyAdapter implements NotifyChannelAdapter`

**它解决什么**：飞书 / Lark 群机器人**用 HTTP 200 承载业务错误**。通用档只判状态码，会把「报文写错、群里一条消息都没有」判成成功，`notify` 于是返回「已推送」——失败连 `tool_invocations` 都进不去。本档读响应体、按业务码判成败。

| # | 契约 | 怎么判定 |
| --- | --- | --- |
| 2.4.1 | 请求体形状 MUST 是 `{"msg_type":"text","content":{"text":<content>}}` | 本地假 webhook 收到体的 JSON 逐字段断言 |
| 2.4.2 | 内容 MUST 逐字送达（中文 / 换行 / 引号 / 反斜杠不被转义或截断） | 同上，取值与原串 `isEqualTo` |
| 2.4.3 | `config` 有非空白 `secret` 时，请求体顶层 MUST 附 `timestamp`（秒级）与 `sign` | 固定时钟下断言 `timestamp` 取值 + `sign` 等于按官方算法离线算出的期望值 |
| 2.4.4 | 未配 `secret`（缺失或纯空白）时，`timestamp` 与 `sign` **MUST NOT 出现**（而不是出现为 `null`） | 请求体键集合大小断言 |
| 2.4.5 | 送达判据 fail-closed：成功 ⇔ 体是 JSON 对象 **且** `code` 存在 **且** 为整数 **且** `== 0` | 逐条覆盖：`code:0` 通过；`code:19002` 抛；`{"data":{}}`（无任何业务码）抛 |
| 2.4.6 | `code` 缺失时才退看 `StatusCode`；两者都在时 **`code` 优先** | `{"StatusCode":0,"code":19002}` MUST 抛 |
| 2.4.7 | MUST NOT 用 `path("code").asInt() != 0` 判定 | Jackson 在字段**缺失**时 `asInt()` 也返回 `0`，那个写法把「缺失」判成成功，与 fail-closed 相反。`{"data":{}}` 用例即钉死这一点 |
| 2.4.8 | 空体 / 纯空白 / 非 JSON / 非 JSON 对象 / 业务码非整数 → 一律抛 | 各自的用例。注意 Jackson 对空体与纯空白返回 `MissingNode` 而**不抛异常**，故必须显式判 `isObject()` |
| 2.4.9 | 非 JSON 响应体的异常 **MUST NOT 挂 cause、MUST NOT 回显 body** | body 可能来自网关错误页（常回显带 token 的 URL）；审计表只写 `e.getMessage()`。用例断言消息不含 body 片段 |
| 2.4.10 | 异常消息 MUST 含业务码与 `msg`，MUST NOT 含 `url` | 断言消息含 `19002`、不含地址与 token |
| 2.4.11 | 失败抛 `IllegalStateException`（**不新增自定义异常类型**，同 §2.3）；`url` 缺失/空白抛 `IllegalArgumentException` 且**一次请求都不发** | 异常类型断言 + `getRequestCount()` |
| 2.4.12 | 超时与 `Redirect.NEVER` 与通用档同口径：连接 + 读取各 10 秒，3xx **不跟随**、按失败上抛 | 超时用例；3xx 用例断言第二个假服务收到 **0** 次请求 |
| 2.4.13 | MUST NOT 打日志（`url` 与 `secret` 都是凭证） | 源码人读 |

**读 body 改变了超时语义（2026-10-15 实测确认）**：通用档用 `toBodilessEntity()` 不读 body，读取上限只量到「响应头到达」；本档必须读 body，于是「给了响应头却拖着不给 body」也会被上限兜住。**两条路径抛出的异常类型不同**——响应头超时是 `ResourceAccessException`，响应体读取超时被 Spring 在「提取响应体」那一步包成 `RestClientException`。两者都是 `RestClientException` 的实例，都原样上抛到 `ToolExecutor` 的失败审计路径，故对调用方无差别。这条差异**已被测试实测确认**（`FeishuNotifyAdapterTest` 两条超时用例分别断言这两种类型），不是推测。

**为什么 `lark` 也走本档**：Lark 是飞书的海外版，自定义机器人 hook 的报文与响应语义**同一套协议**，只是域名不同（域名在 `url` 里，本档不关心）。故装配处把两个 `type` 取值指向**同一个实例**（§2.5）。

---

## 2.5 `RoutingNotifyChannelAdapter` —— 按 type 分派的路由接缝（FR-025 / FR-026，2026-10-15 补强新增）

`io.oryxlabs.oryxos.tool.notify.RoutingNotifyChannelAdapter implements NotifyChannelAdapter`

**它解决什么**：有了第二个实现，`NotifyTools` 注入的不再是「唯一那个适配器」，而必须是「按 `type` 选出来的那一个」。本类就是那个选择点。

| # | 契约 | 怎么判定 |
| --- | --- | --- |
| 2.5.1 | 构造收 `Map<String, NotifyChannelAdapter>`，MUST 防御性拷贝 | 构造后改动传入的 `Map`，路由行为不变 |
| 2.5.2 | 查表前对 `channelType` 做 `trim()` + `toLowerCase(Locale.ROOT)` | `"  Feishu  "` / `"LARK"` 命中方言档。`Locale.ROOT` 是为了避开土耳其语 `I`/`i` 陷阱 |
| 2.5.3 | 映射表 MUST 由装配处**显式给出**；本类 MUST NOT 按类型扫描容器 | 章程原则 10。本类源码里 MUST NOT 出现任何厂商字面量 |
| 2.5.4 | 未知 `type` MUST 抛 `IllegalArgumentException`，消息列出已知类型且**顺序确定** | 直接拼 `keySet()` 的迭代顺序未指定，会让测试 flaky；实现按字典序排序。用例断言消息含 `"feishu, lark, webhook, wecom"`（补强③ 起为四个取值） |
| 2.5.5 | 未知 `type` 时 MUST NOT 委派给任何一档、MUST NOT 发出请求 | 两个替身都 `verify(never())`；端到端用例断言 `getRequestCount() == 0` |
| 2.5.6 | `channelType` 为 `null` 或纯空白 MUST 抛明确异常，MUST NOT 是 NPE | 断言异常类型为 `IllegalArgumentException` 且消息含「type 为空」 |
| 2.5.7 | 每次 `send` MUST 恰好委派一次（不重试、不广播） | `verify(times(1))` |
| 2.5.8 | 映射表为空时任何 `type` 都抛，MUST NOT 假装送达 | 空 Map 用例 |

**为什么未知 `type` 是报错而不是静默退回通用档**（2026-10-15 用户裁决）：静默退回会把「type 拼错」变成「用错的方言发出去」——而错的方言在飞书那里是 HTTP 200 + 业务码 19002，正是本补强要消灭的假成功形态。报错在路由处**一次请求都不发**，比发出去再失败更早、更明确。

**装配处的映射表**（`CliAssemblyConfiguration#notifyChannelAdapter`，契约要求写在装配侧而非本类）：

```
"webhook" → WebhookNotifyAdapter
"feishu"  → FeishuNotifyAdapter  ┐ 同一个实例
"lark"    → FeishuNotifyAdapter  ┘
"wecom"   → WeComNotifyAdapter      ← 2026-10-15 补强③
```

**两条装配硬约束**（由 `NotifyAssemblyTest` 起真上下文钉死）：

1. `NotifyChannelAdapter` 类型的 Bean **恰好一个**，且是路由实现。若把 `FeishuNotifyAdapter` 或 `WeComNotifyAdapter` 也声明成 `@Bean`，容器里就有两个同类型候选，`NotifyTools` 注入当场变成 `NoUniqueBeanDefinitionException`——**手工 `new` 一个路由来断言会把这条绕过去**，故必须起真上下文。
2. 映射表 MUST 在方法体内**就地构造**，MUST NOT 写成 `@Bean` 方法的参数（那是既有 `providerMap` 用 `@Qualifier` 绕开的那个坑）。

---

## 2.6 `WeComNotifyAdapter` —— 企业微信群机器人方言档（FR-032 ~ FR-035，2026-10-15 补强③ 新增）

`io.oryxlabs.oryxos.tool.notify.WeComNotifyAdapter implements NotifyChannelAdapter`

**它解决什么**：与 §2.4 同一个理由——企微群机器人**也用 HTTP 200 承载业务错误**（2026-10-14 实测：报文写错时回 `HTTP 200` + `{"errcode":40008,"errmsg":"invalid message type"}`，群里一条消息都没有）。通用档只判状态码 → 假报「已推送」。

**与 §2.4 的关系**：形状照抄飞书档，**减去签名那一层**。两者是**镜像**而不是同一份——见 2.6.1。

| # | 契约 | 怎么判定 |
| --- | --- | --- |
| 2.6.1 | 请求体形状 MUST 是 `{"msgtype":"text","text":{"content":<content>}}` | 本地假 webhook 收到体的 JSON 逐字段断言。**键名 `msgtype` 无下划线、正文在 `text.content`**，与飞书档的 `msg_type` / `content.text` 恰好互换；用例**同时断言 `has("msgtype")` 为真且 `has("msg_type")` 为假**，把镜像关系钉死 |
| 2.6.2 | 内容 MUST 逐字送达（中文 / 换行 / 引号 / 反斜杠不被转义或截断） | 同上，取值与原串 `isEqualTo` |
| 2.6.3 | 送达判据 fail-closed：成功 ⇔ 体是 JSON 对象 **且** `errcode` 存在 **且** 为整数 **且** `== 0` | 逐条覆盖：`errcode:0` 通过；`errcode:40008` 抛；`{"data":{}}`（无业务码）抛；`errcode:null` / `"0"` / `0.5` 抛 |
| 2.6.4 | MUST **只认 `errcode` 一个字段**，MUST NOT 引入飞书那个 `StatusCode` 兼容字段 | 用例：`{"StatusCode":0,"StatusMessage":"success"}` MUST 抛（企微没有该历史包袱，多认一个字段就是多一处要维护的口径） |
| 2.6.5 | MUST NOT 用 `path("errcode").asInt() != 0` 判定 | 同 2.4.7。`{"data":{}}` 与 `{"StatusCode":0}` 两条用例即钉死这一点（变异测试实测：改成该写法后这两条 + `errcode` 非整数那条共 **3 条变红**） |
| 2.6.6 | 空体 / 纯空白 / 非 JSON / 非 JSON 对象 / 业务码非整数 → 一律抛 | 各自的用例 |
| 2.6.7 | 非 JSON 响应体的异常 **MUST NOT 挂 cause、MUST NOT 回显 body** | 用例断言消息不含 `html` 与 URL 里的 key |
| 2.6.8 | 异常消息 MUST 含业务码与 `errmsg`，MUST NOT 含 `url` | 断言消息含 `40008` / `invalid message type`，不含地址与 key |
| 2.6.9 | 失败抛 `IllegalStateException`；`url` 缺失/空白抛 `IllegalArgumentException` 且**一次请求都不发** | 异常类型断言 + `getRequestCount()` |
| 2.6.10 | 超时与 `Redirect.NEVER` 与其余两档同口径：连接 + 读取各 10 秒，3xx **不跟随**、按失败上抛 | 超时用例；3xx 用例断言第二个假服务收到 **0** 次请求 |
| 2.6.11 | **MUST NOT 注入时钟、MUST NOT 读取 `secret`**（企微群机器人没有签名机制，凭证就是 URL 里的 `key`） | 构造器只有两个（无参 / 包级 `Duration`），源码人读；用例断言未配 `secret` 时报文体仍只有 2 个键 |
| 2.6.12 | MUST NOT 打日志（`url` 里的 `key` 是凭证本身） | 源码人读 |

**为什么企微是独立的实现类而不是复用飞书档加个开关**：报文形状差一个字段名、业务码字段不同名、一个有签名一个没有——三处差异里没有一处能用参数抹平，而加开关会让「飞书档」这个名字不再诚实。这与「Lark 复用飞书档」正相反：Lark 与飞书的报文**逐字一致**，只是域名不同，域名在 `url` 里、适配器根本不看，所以复用是零代价的。

**为什么刻意不抽公共基类**（2026-10-15 裁决）：两档的 `send()` 骨架、`parseObject()` 与构造器约 60 行同形。不抽的三条理由：① 抽 = 改动已交付且被 19 条测试钉死的 `FeishuNotifyAdapter`，属软门禁②事件，收益小于风险；② 项目既有裁决同向（`ProfileLoader` / `McpServerConfigLoader` / `JpaNotifyChannelRegistry` 三处 `envLookup` 刻意不合并）；③ 目前只有两档方言。**第三次出现（钉钉）才是抽的时机**——届时抽的是 `send()` 模板方法与 `parseObject()`，各档只留 `payload()` 与「业务码字段名 + 错误文案」。

**未做（边界）**：企微 text 的 2048 字节上限**不做客户端预校验**——超限时企微回非 0 `errcode`，2.6.3 的 fail-closed 判据会兜住并落审计。预校验等于在客户端维护一份对端常量。企微「应用消息」（需 corpid/corpsecret 换 AccessToken）是**另一回事**，属扩展阶段专用 Adapter。

---

## 3. `NotifyTools` —— 内置通知工具（FR-004）

`io.oryxlabs.oryxos.tool.notify.NotifyTools`

> **包落位**：`notify` 包，**不是**课件写的 `io.oryxos.tool.builtin`（[plan.md](../plan.md) 偏离 ⑥）。本节这一刀切片在同一个包内闭合。

### 3.1 构造契约（FR-017 / SC-007）

```java
NotifyTools(Sandbox sandbox,
            NotifyChannelAdapter adapter,
            NotifyChannelRegistry channelRegistry)
```

| # | 要求 | 判定 |
| --- | --- | --- |
| 3.1.1 | 三个参数**全部是接口类型** | 源码里 MUST NOT 出现 `WebhookNotifyAdapter` / `WhitelistSandbox` / `JpaNotifyChannelRegistry` 任何具体实现类型 |
| 3.1.2 | MUST NOT 依赖 `ProfileContext` / `Profile` / `ProfileLoader` / `ApplicationContext` | grep 这些词，命中数 = 0（FR-015） |
| 3.1.3 | MUST NOT 依赖 `NotifyChannelRepository` 或任何 `Jpa` / `Entity` 类型 | 持久化知识全在注册表实现里（FR-017） |
| 3.1.4 | MUST NOT 带任何 Spring 注解 | 零装配（FR-016） |

**为什么是三个接口而不是更多**：`Sandbox` 是「能不能发」的闸门、`NotifyChannelAdapter` 是「怎么发」、`NotifyChannelRegistry` 是「发给谁」。三者正好对应 FR-007 / FR-003 / FR-006+FR-011 三组需求，多一个都属预造。

### 3.2 工具方法契约

```java
@Tool(description = "把一条消息推送到已注册的通知渠道（不传渠道名时用默认渠道）")
public ToolResult notify(String content, String channel)
```

| # | 要求 | 判定 |
| --- | --- | --- |
| 3.2.1 | 方法名 `notify`、参数名 `content` / `channel`、返回 `ToolResult`——**与课件逐字一致** | 源码比对；参数名进 JSON Schema，模型可见 |
| 3.2.2 | `@Tool` 注解 MUST 保留（本节**惰性**：无任何 `ToolRegistry` 消费它，注册归第 20 节） | 注解存在即可 |
| 3.2.3 | description MUST 表达「已注册的渠道」而非「当前 Agent 配置好的渠道」 | 旧口径文案会误导模型（[plan.md](../plan.md) 偏离 ⑦） |
| 3.2.4 | 成功返回 `ToolResult.success(...)`；**MUST NOT** 返回 `retryableFailure`（本节无重试语义） | 源码比对 |
| 3.2.5 | MUST NOT 自己 catch 异常后返回 `failure(...)` 把错误吞掉 | 失败 MUST 上抛给 `ToolExecutor`（FR-008：失败也要落审计，且是**同一处**落） |
| 3.2.6 | MUST NOT 把 `url` / `config` / `NotifyTarget` 的内容写进返回的 content | 返回值会进对话与审计，含 token 的地址不得泄漏（FR-019 / SC-006） |
| 3.2.7 | MUST NOT 输出到 `System.out` / `System.err` | 静态门禁 + FR-019 |

### 3.3 ★ 顺序不变量（本节最重要的一条，FR-007 / SC-005）

```text
notify(content, channel) 的执行顺序：
    ① registry.resolve(channel)      → 得到 target（失败即抛，此时一次请求都没发）
    ② sandbox.enforce(new SandboxAction(ActionType.HTTP_REQUEST, target.config().get("url")))
    ③ adapter.send(target, content)
```

| # | 要求 | 判定 |
| --- | --- | --- |
| 3.3.1 | `enforce` MUST 在 `send` **之前**被调用 | `InOrder` 顺序断言（见下） |
| 3.3.2 | `enforce` MUST 的 action type 是 `ActionType.HTTP_REQUEST`，target 是 `target.config().get("url")` | 参数捕获断言 |
| 3.3.3 | `enforce` 抛异常时 `send` MUST **一次都没被调用** | `verify(adapter, never()).send(any(), any())` |
| 3.3.4 | **顺序 MUST NOT 颠倒** | 3.3.1 的断言即为判据——顺序反了就是可被利用的绕过口子 |

**★ 课件 harness 的关键回归测试（原样落地，方法名译英文 + `@DisplayName` 保留中文原文）：**

```java
@Test
@DisplayName("发送前必须先过白名单校验")
void enforceSandboxBeforeSending() {
    // ... 构造 NotifyTools，注入 mock 的 sandbox / adapter / registry
    notifyTools.notify("测试消息", "team-lark");

    InOrder inOrder = inOrder(sandbox, adapter);
    inOrder.verify(sandbox).enforce(any(SandboxAction.class));
    inOrder.verify(adapter).send(any(NotifyTarget.class), anyString());
    inOrder.verifyNoMoreInteractions();
}
```

**`InOrder` 为什么是这条断言的唯一正确形式**：`verify(sandbox).enforce(...)` 与 `verify(adapter).send(...)` 两条独立断言**都通过**——即使实现把顺序写反了。只有 `InOrder` 能表达「先 A 后 B」。`verifyNoMoreInteractions()` 顺带钉死「一次推送恰好一次 enforce + 一次 send」。

**H4 不变量 ①（涉外 IO 首行过 `Sandbox.enforce`）在本节的落地口径**：`enforce` 由**工具边界**（`NotifyTools`）调用，而非适配器内部。这与课件、与 spec 的 FR-007、与本节的 harness 断言一致。适配器自身**不**调 `enforce`——它的单测注入的是真实的 `WebhookNotifyAdapter`，若适配器内部调 enforce 就需要一个真 Sandbox，而 `Sandbox` 实现归第 24 节（[research.md](../research.md) D15）。第 24 节接线时的评估项（逐跳复检）记在 [assembly-handoff.md](assembly-handoff.md) §4。

### 3.4 错误口径（FR-006 / FR-011）

| 情形 | 行为 | 报错文案要点 |
| --- | --- | --- |
| `channel` 为 `null` / 空串 / 纯空白 | 走默认渠道（= `name` 升序第一个） | — |
| `channel` 非空白但**名字不存在** | `IllegalStateException` | 必须点出「名字打错了」与「未配置任何渠道」**不同** |
| 走默认但表里一个渠道都没有 | `IllegalStateException` | 「未配置任何通知渠道」 |
| `channel` 名字打错 | **MUST NOT 静默退回默认渠道** | spec Edge Case 明文禁止 |

**为什么这两种「找不到」要分开报**：运维看到「一个都没配」会去配渠道，看到「名字 x 不存在」会去查拼写——合成一条文案会让两种完全不同的处理动作失去线索。且静默退回默认渠道意味着**消息发到了错误的群**，这比报错严重得多。

---

## 4. 测试契约（课件 harness 两批 + 本节追加）

| 测试类 | 模块 | 覆盖的契约条目 | 依赖 |
| --- | --- | --- | --- |
| `WebhookNotifyAdapterTest` | `oryxos-tool` | 2.2.1 ~ 2.2.11 | MockWebServer（真实 `WebhookNotifyAdapter`，**不 mock**） |
| `NotifyToolsTest` | `oryxos-tool` | 3.1 / 3.2 / **3.3（含 `InOrder`）** / 3.4 | Mockito mock `Sandbox` / `NotifyChannelAdapter` / `NotifyChannelRegistry` |
| `JpaNotifyChannelRegistryTest` | `oryxos-tool` | 行→目标映射全部规则（[data-model.md](../data-model.md) §5） | Mockito mock `NotifyChannelRepository` |
| `NotifyChannelRepositoryTest` | `oryxos-storage` | 建表列齐备 / 存取往返 / 默认渠道不依赖插入顺序 | `@DataJpaTest` + 真 SQLite 临时文件 + 手工脚本 |

**纪律**（反作弊）：

- MUST NOT 删断言、`@Disabled`、放宽阈值让测试变绿。
- 测试方法名 MUST 是英文（驼峰或 snake_case）；课件的中文名进 `@DisplayName`。
- 全部不碰网络（MockWebServer 起在 `localhost`）；`mvn test` 秒级跑完。
- 实现完成的定义是 `mvn clean verify` 全绿（含静态门禁）。
