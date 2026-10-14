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
| 1.1 | 签名里 MUST NOT 出现「企业微信」「飞书」「钉钉」「wecom」「feishu」「dingtalk」「webhook」这类某一档实现特有的词 | 对两个文件的源码 grep 这些词，命中数 = 0 |
| 1.2 | 接口 MUST NOT 出现「渠道类型枚举」「URL 字段」「HTTP 方法」等实现细节 | 人读签名即可判定；`channelType` 是 `String` 而非枚举，`config` 是不透明的 `Map<String,String>` |
| 1.3 | `send` MUST NOT 有返回值 | 送达即返回，失败即抛异常（无「部分成功」概念） |
| 1.4 | 两个文件的**签名与字段**逐字保持现状 | `git diff` 对这两个文件为空 |

**为什么 `send` 不返回结果对象**：返回值会立刻引出「失败要不要返回而不是抛」「部分渠道成功怎么办」两个本阶段不存在的问题（一次推送只有一个目标）。失败抛异常 + 由 `ToolExecutor` 统一接住落审计（FR-008），是既有的、唯一的一条失败路径。

---

## 2. `WebhookNotifyAdapter` —— 核心阶段唯一实现（FR-003）

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

**为什么用 `content` 作字段名**：FR-003 说「把内容按 JSON 送出」，spec 的协议假设是 `{"content": ...}`。这是本节唯一被钉死的报文形状；对端要别的字段名时走扩展阶段的专用实现。

### 2.3 抛出的异常类型

| 情形 | 异常 |
| --- | --- |
| `url` 键不存在或值为空白 | `IllegalArgumentException`（参数本身不对） |
| 非 2xx 响应 | `RestClient` 抛出的 `RestClientResponseException` 及其子类，**原样上抛，不包装** |
| 连接失败 / 超时 | `RestClient` 抛出的 `ResourceAccessException`，**原样上抛** |

**不新增自定义异常类型**（[research.md](../research.md) D11）：这些异常最终都由第 20 节接上的 `ToolExecutor` 统一接住，把 `e.getMessage()` 写进 `tool_invocations.error_message`（第 17 节已交付的行为）。异常类型不携带额外语义，新增只会多一个没人 catch 的类。

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
