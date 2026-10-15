# 契约：装配、让位机制与 `oryxos tool list`

**Feature**: `specs/005-tool-registry` | **Branch**: `class-20` | **Date**: 2026-10-14

本文件冻结本节对 `CliAssemblyConfiguration` 的改动、`@Bean` 的**声明顺序约束**、临时沙箱的四条缓解，以及 `oryxos tool list` 命令面的改动。

---

## §1 `@Bean` 清单

### §1.1 本节新增（按声明顺序，**顺序是契约的一部分，见 §2**）

| # | Bean 方法 | 类型 | 依赖 | 备注 |
| --- | --- | --- | --- | --- |
| 1 | `sandbox()` | `Sandbox` | — | `PermissiveSandbox`，**临时**（§4） |
| 2 | `notifyChannelAdapter()` | `NotifyChannelAdapter` | — | `WebhookNotifyAdapter`（**无参构造，自建 `RestClient`**，不需要 `RestClient` Bean） |
| 3 | `notifyChannelRegistry(NotifyChannelRepository)` | `NotifyChannelRegistry` | 仓储（`@EnableJpaRepositories` 自动提供） | `JpaNotifyChannelRegistry` |
| 4 | `notifyTools(Sandbox, NotifyChannelAdapter, NotifyChannelRegistry)` | `NotifyTools` | 上三条 | 第 19 节挂账，从第 24 节提前 |
| 5 | `fileTools(Sandbox)` | `FileTools` | 1 | 6 个工具 |
| 6 | `shellTools(Sandbox)` | `ShellTools` | 1 | 1 个工具 |
| 7 | `httpTools(Sandbox)` | `HttpTools` | 1 | 2 个工具 |
| 8 | `userInteraction()` | `UserInteraction` | — | `ConsoleUserInteraction` |
| 9 | `interactionTools(UserInteraction)` | `InteractionTools` | 8 | 1 个工具 |
| 10 | `searchProvider()` | `SearchProvider` | — | `DuckDuckGoSearchProvider` |
| 11 | `webSearchTools(Sandbox, SearchProvider)` | `WebSearchTools` | 1, 10 | 1 个工具 |
| 12 | `mcpServerConfigLoader(Workspace)` | `McpServerConfigLoader` | — | 读 `.oryxos/mcp_servers.yaml` |
| 13 | `mcpClientService(McpServerConfigLoader)` | `McpClientService` | 12 | 连接 + 拉清单 + 失联隔离 |
| 14 | **`toolRegistry(...)`** | `ToolRegistry` | 4~11, 13 | **MUST 声明在 `toolCatalog()` 之前**（§2） |

### §1.2 既有的空实现（**不删、不改**，自动让位）

| Bean 方法 | 现状 | 本节后 |
| --- | --- | --- |
| `toolCatalog()`（`@ConditionalOnMissingBean(ToolCatalog.class)`，返回 `EmptyToolCatalog`） | 第 17 节交付 | 因 #14 先声明而**自动不创建** |
| `memorySource()`（`@ConditionalOnMissingBean(MemorySource.class)`，返回 `EmptyMemorySource`） | 第 17 节交付 | **保持不变**（Memory 归第 22 节） |

### §1.3 明确**不**新增的 Bean

| 项 | 为什么 |
| --- | --- |
| `RestClient` | `WebhookNotifyAdapter` 的无参构造内部自建 `RestClient`（含 10 秒有界超时 + 不跟随重定向）。声明一个 `RestClient` Bean 反而会让人以为它可以被注入替换，而第 19 节的 javadoc 明确把超时收口在类内 |
| `save_memory` / `recall_memory` 相关 | 第 22 节 |
| `WhitelistSandbox` | 第 24 节 |

---

## §2 声明顺序约束（**本节最容易静默踩的坑**）

### §2.1 约束

`toolRegistry()` 的 `@Bean` 方法 **MUST 声明在 `toolCatalog()` 之前**。

### §2.2 为什么

`@ConditionalOnMissingBean` 在同一个 `@Configuration` 类里**按 `@Bean` 方法的声明顺序求值**。两种顺序的结果：

| 顺序 | 结果 |
| --- | --- |
| **正确**：`toolRegistry()` → `toolCatalog()` | 求值 `toolCatalog()` 时，容器里已有 `ToolRegistry` 的 Bean 定义 → 条件不满足 → 空实现**不创建**。容器里**恰好一个** `ToolCatalog` |
| **错误**：`toolCatalog()` → `toolRegistry()` | 求值 `toolCatalog()` 时容器里还没有 `ToolCatalog` → 空实现被注册；随后 `toolRegistry()` **无条件**注册 → 容器里**两个** `ToolCatalog` → `PromptBuilder` / `ToolExecutor` 注入时抛 `NoUniqueBeanDefinitionException` |

**为什么这条特别危险**：这个错误**只在重命令（起 Spring 上下文）时才炸**。`oryxos tool list --help`、`--version` 都扫不出来——与第 18 节实测的 `BeanDefinitionOverrideException` 是同一类坑（`CLAUDE.md`「AI agent 最易跑偏的点」表末行已记该教训：**只跑 `--help` 就宣称命令能用 = 假绿**）。

### §2.3 验证方式（机器判定，**不留给人**）

```java
@Test
@DisplayName("装配后容器里 ToolCatalog 类型的 Bean 恰好一个，且是 ToolRegistry")
void toolCatalog_hasExactlyOneBean_andItIsTheRegistry() {
  Map<String, ToolCatalog> beans = context.getBeansOfType(ToolCatalog.class);
  assertThat(beans).hasSize(1);                                    // 抓住"两个都在"和"空实现没让位"
  assertThat(beans.values().iterator().next()).isInstanceOf(ToolRegistry.class);
}
```

**断言"恰好一个"**（而不是"存在一个是 ToolRegistry"）是关键——后者在"两个都在"时会假绿。

---

## §3 让位机制的可复现性（SC-009）

**本节 MUST NOT** 在让位时删除 `EmptyToolCatalog`，也不给它加 `@Primary`。

**第 24 节的验收方式**：第 24 节在装配处加一行 `@Bean @ConditionalOnMissingBean(Sandbox.class) WhitelistSandbox sandbox(...)` 并**删掉**本节那行 `sandbox()`，其余**一行不改**——本节的工具代码、`NotifyTools`、调用方全部零改动即生效。

**机器判定**：一条测试断言"当容器里存在 `WhitelistSandbox` 类型的 Bean 时，`Sandbox` 类型的 Bean 恰好一个且是它"。本节可先用替身类模拟第 24 节的场景。

**同理适用于第 22 节的 Memory 两件**：加一个带 `@Tool` 的 Bean + 装配处一行 `registerAnnotated`，**注册表与过滤逻辑零改动**。这条本身就是 FR-022 的一次真实验证。

---

## §4 `PermissiveSandbox` 的四条缓解（**SC-010 的判据来源**）

| # | 缓解 | 可判定形式 |
| --- | --- | --- |
| 1 | 类名 + javadoc 明示临时 | 类名就是 `PermissiveSandbox`；javadoc MUST 写明"第 24 节必须替换" |
| 2 | **构造时打一条 WARN 日志** | 写在**构造函数里**（不是装配方法里）——任何构造路径都警告。用 logback `ListAppender` 单测断言恰好一条 WARN |
| 3 | 第 24 节同类型 Bean 自动让位 | §3 的可复现测试；本节交付的工具代码改动行数 **0** |
| 4 | `CLAUDE.md` + 本节验收报告双处留痕 | 记明第 20~23 节的窗口期 |

**WARN 为什么放构造函数而不是装配方法**：

- 放装配方法 → 单测要起 Spring 上下文才能验证，且绕过装配直接 `new` 的路径（例如测试、将来的其他装配点）不会警告；
- 放构造函数 → **任何**构造路径都警告，且可用纯单测（`new PermissiveSandbox()` + `ListAppender`）钉死，不需要 Spring 上下文。

**WARN 文案**（MUST 含"临时"与"第 24 节"两个要素，让读日志的人不可能误判）：

```text
沙箱当前挂的是临时实现（PermissiveSandbox）：白名单校验不生效。第 24 节落地 WhitelistSandbox 后自动替换。
```

---

## §5 `oryxos tool list`（轻 → 重）

### §5.1 改动

| 项 | 改前（第 18 节） | 改后（本节） |
| --- | --- | --- |
| 轻重 | **轻**（不起 Spring 上下文） | **重**（起 Spring 上下文） |
| 数据来源 | 无（打空表 + 注明"第 20 节接入 ToolRegistry"） | `ToolCatalog` 接缝（实际是 `ToolRegistry`） |
| 输出 | `Tool（共 0 个）:` + 表头 + "（无：第 20 节接入 ToolRegistry）" | 按来源分组的全量清单 |
| 命令名 / 参数 / 退出码 | — | **不变** |

**§1 的"轻重"列由轻改重**——命令名、参数、退出码三项（§1 要求走修订程序的正是这三项）**均未触碰**，故本改动在节内权限范围内。

### §5.2 输出格式

```text
Tool（共 13 个）:
  builtin (13):
    ask_user        向用户提问，拿到回答后继续                          builtin
    edit_file       修改文件里的一段文本（要求旧文本唯一匹配）          builtin
    glob            按通配符查找文件路径（最多 200 条）                 builtin
    grep            按正则搜索文件内容（最多 200 条）                   builtin
    http_get        发起 HTTP GET 请求                                  builtin
    ...
  annotation (0):
    （无）
  mcp (0):
    （无：.oryxos/mcp_servers.yaml 里没有声明任何 server）
```

- **列顺序按冻结契约**：`name`、`description`、来源——`cli-surface.md` §2.10 原文即「name、description、来源（`builtin` / `annotation` / `mcp:<server>`）」，MUST NOT 擅自换序；
- 分组键 = `source` 的前缀（`builtin` / `annotation` / `mcp`），`mcp` 组内按 `mcp:<server名>` 再分组；
- 空组**照样打印**并注明原因——"annotation 0 个"是**有用信息**（说明业务方还没写工具），不是噪音。

### §5.3 轻重判据（写进 javadoc，供后来者复核）

`specs/003-cli-session/contracts/cli-surface.md` §1 的判定标准是"这条命令**要不要调模型、跑引擎**"。列出 MCP 工具**必须连接 server**（MCP 工具的清单只有连上才知道），这已经是"跑引擎"。

第 18 节把它归轻，是因为当时**没有注册表可查**——§1 第 11 行的"数据来源"列原文即"`ToolCatalog` 接缝（本节空实现）"，数据源当时是空的。

**代价如实记录**：有 MCP server 声明时最多等待 `10 秒 × server 数`（FR-009 的有界等待）。

---

## §6 本节 MUST NOT

| # | 禁止 | 依据 |
| --- | --- | --- |
| 6.1 | 把 `toolRegistry()` 声明在 `toolCatalog()` 之后 | §2——重命令起不来 |
| 6.2 | 给 `EmptyToolCatalog` 加 `@Primary` 或删掉它 | §3——"让位"不能变成"并存" |
| 6.3 | 在非 boot 模块加 `@Component` / `@Configuration` | 第 18 节既定装配纪律：Bean 声明只在 `CliAssemblyConfiguration` 一处 |
| 6.4 | 造"全放行"的匿名沙箱替代 `PermissiveSandbox` | 匿名类无法让位、无法断言、无法写 javadoc 说明临时性 |
| 6.5 | 把 `PermissiveSandbox` 写进任何"本节交付物"成果清单里当作安全能力 | D1——该窗口期 MUST NOT 被表述为"沙箱已就位" |
| 6.6 | 为保住 `tool list` 的"轻"而另造静态工具清单 | 第二处真相；FR-022 当场不满足 |
| 6.7 | 扩大 `CliApplication` 的 `scanBasePackages` 到 `io.oryxlabs.oryxos` | fat JAR 上会与 `OryxosApplication` 撞车抛 `BeanDefinitionOverrideException` |
