# 契约：内置工具与扩展工具清单

**Feature**: `specs/005-tool-registry` | **Branch**: `class-20` | **Date**: 2026-10-14

本文件冻结**每个工具对模型可见的接口**（名字、参数、说明）与**对沙箱的动作**。名字一旦交付即不得改——模型见过的工具名改掉，等于让所有依赖它的 `SKILL.md` / `AGENT.md` 失效。

---

## §1 清单总览

**本节在册 13 个**（注册表启动后 `oryxos tool list` 应显示这 13 个）：

| # | 名字 | 来源标签 | 所属类 | 沙箱动作 | 状态 |
| --- | --- | --- | --- | --- | --- |
| 1 | `read_file` | `builtin` | `FileTools` | `FILE_READ` | 本节新增 |
| 2 | `write_file` | `builtin` | `FileTools` | `FILE_WRITE` | 本节新增 |
| 3 | `list_dir` | `builtin` | `FileTools` | `FILE_READ` | 本节新增 |
| 4 | `edit_file` | `builtin` | `FileTools` | `FILE_WRITE` | 本节新增（扩展） |
| 5 | `grep` | `builtin` | `FileTools` | `FILE_READ` | 本节新增（扩展） |
| 6 | `glob` | `builtin` | `FileTools` | `FILE_READ` | 本节新增（扩展） |
| 7 | `shell` | `builtin` | `ShellTools` | `SHELL_COMMAND` | 本节新增 |
| 8 | `http_get` | `builtin` | `HttpTools` | `HTTP_REQUEST` | 本节新增 |
| 9 | `http_post` | `builtin` | `HttpTools` | `HTTP_REQUEST` | 本节新增 |
| 10 | `web_fetch` | `builtin` | `HttpTools` | `HTTP_REQUEST` | 本节新增（补强，见 §2.12） |
| 11 | `ask_user` | `builtin` | `InteractionTools` | **无** | 本节新增（扩展） |
| 12 | `web_search` | `builtin` | `WebSearchTools` | `HTTP_REQUEST` | 本节新增（扩展） |
| 13 | `notify` | `builtin` | `NotifyTools` | `HTTP_REQUEST` | **第 19 节已交付**，本节只做注册 |

**第 22 节补齐后为 15 个**（`save_memory` / `recall_memory` 归 `oryxos-memory`）。**本节 MUST NOT** 为凑数而造这两个的占位实现——第 22 节落地后经同一注册机制自动进注册表，注册表与过滤逻辑零改动（FR-022 的一次真实验证）。

**`web_fetch` 为什么排在 `http_post` 之后、却记在 §2.12**：表格按「工具归属的类」分组，而章节号是**追加**的——§2.9~§2.11 已被三个扩展工具占用，新工具取下一个空号，不去动已交付章节的编号（改号等于让所有引用它的注释失效）。

---

## §2 逐工具契约

### §2.1 `read_file`

| 项 | 值 |
| --- | --- |
| 参数 | `path`（String，必填）、`offset`（Integer，**可选**，起始行号 1 起）、`limit`（Integer，**可选**，最多读多少行） |
| 返回 | 文件全文；带分页参数或超过默认上限时返回指定区间 + 续读提示 |
| **默认上限 2000 行** | 不传 `offset` / `limit` 且文件超过 2000 行 → 返回前 2000 行并注明「共 N 行，用 `offset=2001` 继续」 |
| **旧行为逐字节保留** | 不传分页参数且文件不超过上限时，返回结果与加分页之前**完全一致**（走 `Files.readString` 原样返回） |
| 沙箱 | 首行 `enforce(new SandboxAction(FILE_READ, path))` |
| 失败 | 文件不存在 / 不是普通文件 / 读取失败 / `offset < 1` / `limit < 1` → `ToolResult.failure`，`retryable=false` |
| 越界 | `offset` 超出文件末尾 → **成功**返回一句「文件共 N 行，offset 超出末尾」，不抛异常（模型据此知道读到头了） |

**为什么加默认上限**（2026-10-15 补强，软门禁 ② 报批项）：加分页前 `read_file` 走 `Files.readString`，没有任何上限——一个 5000 行的日志文件会原样进模型上下文，这一轮就废了。上限取 2000 行与 Claude Code 的 Read 默认值对齐。

**为什么保留旧路径而不是一律切片**：既有调用方（与既有测试）读小文件时看到的结果必须一个字节都不差。上限是给「大文件」加的天花板，不是给「小文件」换的实现。

### §2.2 `write_file`

| 项 | 值 |
| --- | --- |
| 参数 | `path`（String，必填）、`content`（String，必填） |
| 行为 | 覆盖写；父目录不存在时**不自动创建**（明确报错，避免模型把文件写到意外位置） |
| 沙箱 | 首行 `enforce(new SandboxAction(FILE_WRITE, path))` |
| 返回 | 写入字节数 + 目标路径 |

### §2.3 `list_dir`

| 项 | 值 |
| --- | --- |
| 参数 | `path`（String，必填） |
| 返回 | 目录项列表（名字 + 类型：文件/目录），按名字排序 |
| 沙箱 | 首行 `enforce(new SandboxAction(FILE_READ, path))` |
| 失败 | 路径不存在 / 不是目录 → `ToolResult.failure` |

### §2.4 `edit_file`（扩展）

| 项 | 值 |
| --- | --- |
| 参数 | `path`（String，必填）、`oldText`（String，必填）、`newText`（String，必填） |
| 行为 | 把文件里的 `oldText` 替换成 `newText` |
| **唯一匹配要求** | `oldText` 在文件里必须**恰好出现一次**。**0 次** → 报错；**≥2 次** → 报错（附出现次数） |
| **原子性** | 上述任一失败时，**文件一个字都不动**（先读全文 → 在内存里校验唯一性 → 通过才写回） |
| 沙箱 | 首行 `enforce(new SandboxAction(FILE_WRITE, path))` |
| 返回 | 替换位置的行号 + 替换前后片段 |

**为什么要求唯一匹配**：模型给的 `oldText` 若在多处命中，"替换哪一个"无从判定，猜错就是静默改坏文件。宁可报错让模型给更长的上下文。

**参数名为什么是驼峰 `oldText` / `newText` 而不是 snake_case**（2026-10-14 实施期报批变更，见验收报告）：Spring AI 1.1.8 的 `JsonSchemaGenerator.generateForMethodInput` 反编译核实，属性键**逐字取 `Parameter.getName()`**，且 `@ToolParam` **没有 `name()` 元素**——即模型可见的参数名只能等于 Java 形参名，没有任何注解通道可以重命名。而 snake_case 形参名会被两处静态门禁判红：Checkstyle（`google_checks.xml` 的 `ParameterName`，format `^[a-z]([a-z0-9][a-zA-Z0-9]*)?$`）与 P3C（`LowerCamelCaseVariableNamingRule`，`visit(ASTVariableDeclaratorId)` 覆盖形参）。两条路只能选一条：给门禁开豁免口子，或改参数名。选了后者——**门禁的"能机器判的绝不留给人"纪律不开豁免**。代价是模型看到的是 `oldText` / `newText`，与人类习惯的 JSON snake_case 不同；语义、必填性、唯一匹配要求一字未变。

### §2.5 `grep`（扩展）

| 项 | 值 |
| --- | --- |
| 参数 | `pattern`（String，必填，正则）、`path`（String，必填）、`offset`（Integer，**可选**，跳过前 N 条命中用于翻页）、`contextLines`（Integer，**可选**，每个命中前后各显示 N 行） |
| 返回 | 命中行 `路径:行号: 行内容`；上下文行 `路径:行号- 行内容`；两组之间用 `--` 分隔 |
| **上限 200 条** | 每页 200 条；超出则截断，并在结果里**注明已截断 + 总命中数 + 下一页的 `offset`** |
| **翻页不重不漏** | 第 1 页末条与第 2 页首条**恰好相接**——`offset` 是「已看过的命中条数」的全局序号 |
| **上下文去重** | 相邻命中的上下文会重叠，同一 `(路径, 行号)` 只渲染一次 |
| **二进制跳过** | 读到二进制或非 UTF-8 文件 → **跳过该文件**，不中断整次搜索（FR-014） |
| 沙箱 | 首行 `enforce(new SandboxAction(FILE_READ, path))` |
| 失败 | 正则非法 / `offset < 0` / `contextLines < 0` → `ToolResult.failure`（`retryable=false`，重试同一个正则不会变好） |

**为什么加 `offset`**（2026-10-15 补强，软门禁 ② 报批项）：加分页前只有「已截断」三个字，没有续读手段——模型在大仓库里搜到 300 条命中，只能靠换更窄的模式硬猜。截断说明里现在直接给出下一页的 `offset`。

**为什么加 `contextLines`**：只看命中行常常判不出这段代码在干什么（`}` 也匹配的宽正则尤其如此）。格式沿用 grep 惯例：命中用 `:`、上下文用 `-`，模型与人都能一眼分辨。

### §2.6 `glob`（扩展）

| 项 | 值 |
| --- | --- |
| 参数 | `pattern`（String，必填，通配符）、`path`（String，必填，搜索根）、`offset`（Integer，**可选**，跳过前 N 条匹配用于翻页） |
| 返回 | 匹配的**路径**列表，按路径排序 |
| **上限 200 条** | 每页 200 条；超出则截断 + 注明总条数与下一页的 `offset` |
| **翻页不重不漏** | 与 `grep` 同款口径 |
| 沙箱 | 首行 `enforce(new SandboxAction(FILE_READ, path))` |
| 失败 | `offset < 0` → `ToolResult.failure` |

### §2.7 `shell`

| 项 | 值 |
| --- | --- |
| 参数 | `command`（String，必填）、`timeoutSeconds`（Integer，**可选**，超时秒数） |
| **argv 直传** | 参数按空白切分后**直接作为 argv 传给 `ProcessBuilder`**，**不经 Shell 解释**（FR-020）——管道、重定向、`&&`、`$VAR` 一律不生效 |
| 沙箱 | 首行 `enforce(new SandboxAction(SHELL_COMMAND, argv[0]))`——校验对象是**可执行文件名**，不是整条命令 |
| **超时默认 30 秒、上限 600 秒** | 不传 `timeoutSeconds` → 30 秒；传 9999 → **夹到 600**；超时则销毁进程并返回 `ToolResult.retryableFailure` |
| 返回 | 退出码 + stdout + stderr |
| 失败 | 非零退出码 → `ToolResult.failure`（附退出码与 stderr）；`timeoutSeconds < 1` → `ToolResult.failure` |

**为什么超时要可配**（2026-10-15 补强，软门禁 ② 报批项）：写死 30 秒时 `mvn` / `npm install` 这类必然超时的命令**没有任何出路**——模型只能眼看着失败。上限 600 秒是给「长但有限」的构建留的余量，不是给「无限等待」开的口子：真需要后台跑的任务归扩展阶段（P2 已记）。

**为什么默认值仍是 30 秒**：这是已交付的行为，改默认值等于让既有 Agent 的执行时间预算静默变化。要长的自己传。

**为什么不经 Shell**：`sh -c "<字符串>"` 等于把 Shell 的全部语法能力交给模型——命令注入的经典入口。argv 直传把可执行文件钉在第一位，白名单校验才有意义。

### §2.8 `http_get` / `http_post`

| 项 | 值 |
| --- | --- |
| 参数（GET） | `url`（String，必填） |
| 参数（POST） | `url`（String，必填）、`body`（String，必填） |
| 沙箱 | 首行 `enforce(new SandboxAction(HTTP_REQUEST, url))` |
| 返回 | 状态码 + 响应体 |
| 超时 | 与 `shell` 同款有界等待（**30 秒**，复用 FR-020 的字面量，见 spec Assumptions 第 3 条） |
| 失败 | 非 2xx → `ToolResult.failure`；连接失败/超时 → `ToolResult.retryableFailure` |

**凭证不入结果**：请求头里若带凭证（来自 `${ENV_VAR}`），**响应正文与错误信息里 MUST NOT 回显请求头**（FR-027）。

### §2.9 `ask_user`（扩展）

| 项 | 值 |
| --- | --- |
| 参数 | `question`（String，必填） |
| 沙箱 | **无**（不碰文件、不跑进程、不发网络请求） |
| 行为 | 调 `UserInteraction.ask(question)` |
| 拿到回答 | `ToolResult.success(回答)` |
| **拿不到回答** | `ToolResult.failure("当前渠道不支持与用户交互")` —— **明确报错，绝不静默阻塞**（FR-017） |

**为什么必须明确报错**：定时任务（第 25 节）与无状态调用（第 26 节）根本没有另一端的人在等。静默阻塞会让钟推任务挂死到超时，且日志里看不出原因。

### §2.10 `web_search`（扩展）

| 项 | 值 |
| --- | --- |
| 参数 | `query`（String，必填） |
| 沙箱 | 首行 `enforce(new SandboxAction(HTTP_REQUEST, <provider 的目标 URL>))` |
| 行为 | 调 `SearchProvider.search(query, 200)` |
| 返回 | 结果列表（标题 + 链接 + 摘要） |
| 上限 | **200 条**（FR-013） |
| 失败 | provider 不可达 → `ToolResult.retryableFailure` |

**免 key**：`DuckDuckGoSearchProvider` 用免 key 的 Instant Answer 接口；换别的搜索服务只加 `SearchProvider` 实现类，**工具与调用方零改动**（FR-018）。

**免 key 档显式带 `User-Agent`**（2026-10-15 补强，软门禁 ② 报批项——改的是第 20 节已交付的 `DuckDuckGoSearchProvider`）：该端点按 UA 分流，**JDK 默认的 `Java-http-client/<版本>` 被回 HTTP 403**，换成浏览器前缀的 UA 回 HTTP 202。也就是说这一档在修之前对任何一台机器都拿不到结果（不是本机网络问题）。UA 写成「浏览器前缀 + 自我介绍」（`Mozilla/5.0 (compatible; OryxOS/0.1; +<仓库地址>)`）：前缀是对方分流认的部分，后半段让对方的运维能查回来。有单测钉死「UA 非空且不以 `Java-http-client` 开头」。带 key 档（Tavily）实测不带 UA 也正常，**不动**。

**两档实现与分流规则**（2026-10-15 补强，软门禁 ① 报批项）：

| 档 | 实现类 | 端点 | 何时挂上 |
| --- | --- | --- | --- |
| 免 key（默认） | `DuckDuckGoSearchProvider` | `https://api.duckduckgo.com/` | `oryxos.search.tavily-api-key` **为空** |
| 带 key | `TavilySearchProvider` | `https://api.tavily.com/search` | 该键**非空** |

**为什么加一档带 key 的**：DuckDuckGo 打的是 **Instant Answer**——那是「问答」接口不是「检索」接口。实测（2026-10-15）查询 `spring boot 3.5 release notes` 三路来源**全返回 0 条**，而第 31 节的「每日科技日报」Demo 正是这类长尾查询。核心阶段仍默认用免 key 那档（不注册账号就能跑通），配了 key 就自动换成真检索。

**装配处按 key 有无分流，不按 profile 分流**：`CliAssemblyConfiguration.searchProvider()` 读 `@Value("${oryxos.search.tavily-api-key:}")`，空则 DDG、非空则 Tavily。两档都是 `SearchProvider`，所以 `WebSearchTools`、沙箱校验位、注册表**零改动**——这是 FR-018「换搜索服务只加实现类」的第二次真实验证（第一次是第 20 节从零引入这个接缝）。

**key 不进任何日志、异常消息或 `toString`**（FR-027 同款口径）：非 2xx 时异常只报 HTTP 状态码，绝不回显凭证；`SearchResult` 只装 title / url / snippet，没有位置放 key。这条有单测钉死（`search_non2xx_throwsWithoutLeakingKey`）。

**配置键 `oryxos.search.tavily-api-key` 带空默认值**（`${TAVILY_API_KEY:}`）：与 `oryxos.providers[].api-key` 的**必填**占位不同——搜索 key 是可选的，缺失不该让启动失败。值 MUST 走环境变量占位，禁止明文（章程 VIII）。

### §2.11 `notify`（第 19 节已交付，本节只注册）

| 项 | 值 |
| --- | --- |
| 参数 | `content`（String，必填）、`channel`（String，**可选**） |
| 沙箱 | 首行 `enforce(new SandboxAction(HTTP_REQUEST, target.config().get("url")))` |
| 行为 | 按名解析渠道 → 校验 → 推送 |
| 返回 | `ToolResult.success("已推送")` |

**本节的两处动作**：

1. **Bean 声明**（第 19 节挂账，从第 24 节提前到本节）；
2. **给 `channel` 参数补 `@ToolParam(required = false)`** —— 它是可选参数，不标注的话 schema 会把它列进 `required`，模型每次都必须编一个渠道名出来。这条改造点已在 `CLAUDE.md` 与 `specs/004-notify-outbound/tasks.md` 记为 L20 改造点。

**已知边界（第 19 节实测，本节不修）**：通用 webhook 报文 `{"content":"..."}` 不被 IM 群机器人接受（飞书回 `HTTP 200` + `code:19002`），而 `WebhookNotifyAdapter.send()` 只判 HTTP 状态码 → `notify` 会**假报"已推送"**。方言适配（如 `FeishuNotifyAdapter`）归扩展阶段，**作为新增实现类**，接口与调用方零改动。实测证据见 `specs/004-notify-outbound/manual-test-guide.md` 附录 A.2。

### §2.12 `web_fetch`（补强新增）

| 项 | 值 |
| --- | --- |
| 参数 | `url`（String，必填，完整 http/https URL） |
| 沙箱 | 首行 `enforce(new SandboxAction(HTTP_REQUEST, url))`，**且每一跳重定向都重新过一遍** |
| 返回 | 正文纯文本；`Content-Type` 含 `html` 时先去掉标签 |
| **上限 20000 字符** | 超出则截断并注明原始长度 |
| **跟随重定向** | 301 / 302 / 303 / 307 / 308，最多 5 跳；相对 `Location` 按当前地址解析 |
| 超时 | 与 `http_get` 同款（30 秒） |
| 失败 | 非 2xx → `ToolResult.failure`（附状态码与响应体）；连接失败 / 超时 → `ToolResult.retryableFailure`；超过 5 跳 / 3xx 但无 `Location` → `ToolResult.failure` |

**为什么是「抓取」而不是又一个 `http_get`**：`http_get` 把整个 HTML（含标签、脚本、样式）原样交给模型，一个普通文档页光标签就能占掉大半个上下文。`web_fetch` 的差别只有一条——**把标签去掉，让模型直接读正文**。

**只在 `Content-Type` 说是 HTML 时才去标签**：JSON、纯文本响应里的 `<` 是数据不是标签，一律去标签会静默改坏内容。判据取自响应头，不做「看正文像不像 HTML」的猜测。

**为什么自己写重定向循环而不用 `HttpClient.Redirect.NORMAL`**（两条，缺一不可）：

1. `Redirect` 是**客户端级**设置，开了就连带改掉 `http_get` / `http_post` 的语义（POST 撞上 301/302 还会被 JDK 改成 GET）——那是动前序已交付的行为；
2. 客户端的自动跳转**不会再过一遍沙箱**——第 24 节的白名单落地后，一个白名单内的域名只要回一个 `Location` 指向任意主机，校验就被绕过去了。

手动循环把 `enforce` 放在**每一跳的开头**，这个洞从一开始就不存在（`webFetch_everyRedirectHop_passesThroughSandbox` 钉死）。

**与 `http_get` 的有意差异**：`http_get` **不跟随**重定向，301/308 原样作为状态码返回——它的语义是「把这一跳的响应给我」。`web_fetch` 的语义是「把那一页的正文给我」，不跟随就等于在最该好用的地方不可用（实测 `https://spring.io/blog/...` 回 **308**，修前报 `HTTP 308` 失败，修后取到 4354 字符正文）。

**朴素去标签，不引 Jsoup**（软门禁 ⑥）：核心阶段要的只是「把标签去掉、把实体解回来」，一条正则流水线足够；DOM 树、CSS 选择器、容错解析都不在工具契约里。实现细节在包级私有的 `WebPageText`（不进对外概念清单）。

---

## §3 契约测试（课件 harness，原样落地）

### §3.1 参数化契约检查（**最值钱的一条**）

```java
@ParameterizedTest
@MethodSource("allRegisteredTools")
@DisplayName("每个已注册工具都必须具备名字、用途说明、入参 Schema 三件套")
void everyRegisteredTool_exposesCompleteContract(OryxTool tool) {
  assertThat(tool.getName()).isNotBlank();
  assertThat(tool.getDescription()).isNotBlank();
  assertThat(tool.getInputSchema()).isNotBlank();
}
```

`allRegisteredTools()` 从**装配后的真实注册表**取全量工具——**新工具自动纳入检查**，不需要每加一个工具就补一条测试。这正是 FR-022（"加工具成本恒定在写一个类"）的机器化验证。

### §3.2 schema 参数名检查（D2a 的守门测试）

```java
@Test
@DisplayName("notify 的入参 Schema 里必须是真实参数名 content / channel，不能退化成 arg0")
void notifySchema_usesRealParameterNames() {
  String schema = registry.find("notify").orElseThrow().getInputSchema();
  assertThat(schema).contains("content").contains("channel").doesNotContain("arg0");
}
```

**这条测试守的是父 pom 的 `<parameters>true</parameters>`**。谁把它删了，这条立刻红——避免"编译通过但模型调不动工具"的静默失效。

### §3.3 每个工具的"正常能跑通 + 越界会被拦"两条

**越界那一条 MUST 同时断言目标一次都没被碰过**（FR-015 的顺序不变量）：

```java
@Test
@DisplayName("read_file 越界时必须被拦，且目标文件一次都没被读过")
void readFile_outsideWhitelist_isBlockedBeforeIo() {
  Path target = tempDir.resolve("secret.txt");   // 真实存在的文件
  Files.writeString(target, "secret");
  assertThatThrownBy(() -> readFileTool.execute("{\"path\":\"" + target + "\"}"))
      .isInstanceOf(SandboxViolationException.class);
  assertThat(strictSandbox.readAttempts()).isEmpty();   // ← 关键：IO 一次都没发生
}
```

**只断言"抛了异常"是不够的**——先 IO 再校验也会抛异常，测试会假绿。必须用记录型沙箱替身断言"校验发生在 IO 之前"。

### §3.4 扩展六工具的单测守点

| 工具 | 必测的守点 |
| --- | --- |
| `edit_file` | 找不到 `oldText` → 报错；出现 2 次 → 报错；**两种情况文件内容都逐字节不变** |
| `grep` | 目录里放一个二进制文件，搜索不中断，二进制文件被跳过 |
| `glob` | 命中超过 200 条时结果恰好 200 条且带截断说明 |
| `ask_user` | 注入 `UnsupportedUserInteraction` 时明确报错，**且不阻塞**（用超时断言） |
| `web_search` | 注入替身 `SearchProvider`，断言查询原样透传、结果条数受 200 上限约束 |
| `web_fetch` | HTML 去标签 / 非 HTML 原样返回 / 超长截断 / 越界一次请求都不发 |

**分页与超时参数（2026-10-15 补强）另加守点**：`read_file` 的 `offset`/`limit` 生效且越界不抛异常；`grep` / `glob` 的 `offset` **翻页不重不漏**（第 1 页末条与第 2 页首条恰好相接）；`grep` 的上下文行用 `-` 与命中行的 `:` 区分且重叠不重复；`shell` 的 `timeoutSeconds` 生效、超上限被夹到 600（用包级私有的 `effectiveTimeout` 断言，不真等 600 秒）。

---

## §4 本节 MUST NOT

| # | 禁止 | 依据 |
| --- | --- | --- |
| 4.1 | 实现白名单校验本体 | 归第 24 节；本节只以 `enforce` 调用形式接入 |
| 4.2 | 改 `NotifyTools` 的任何方法签名 | 软门禁 ④；第 19 节已交付（补 `@ToolParam` 是加注解，不改签名） |
| 4.3 | 让 `shell` 走 Shell 解释 | FR-020；命令注入入口 |
| 4.4 | 在工具内 catch `SandboxViolationException` 转成失败结果 | FR-021——失败必须在 `ToolExecutor` 落审计 |
| 4.5 | 给 Memory 两件造占位实现 | 归第 22 节；占位会让契约测试"通过"但功能是假的 |
| 4.6 | 在工具返回里回显凭证 / 渠道地址 | FR-027；第 19 节 javadoc 已立此规矩 |
