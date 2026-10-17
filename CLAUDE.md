# CLAUDE.md

> 本文件是 AI agent（Claude Code 等）在 OryxOS 项目中工作时的必读上下文。修改代码或生成 Spec-Kit artifacts 之前，先读完本文件。

## 项目概述

**OryxOS 是一个企业能完全掌控的、Java 原生的、私有可审计的 Agent 统一底座（Agent OS）**。装在企业自己的 K8s/服务器上，业务方放一个 Agent 目录（一份 `AGENT.md`）就能跑起一个业务 Agent，共享渠道接入、模型路由、工具调用、记忆系统、沙箱执行能力。数据完全留在企业自己的基础设施，不锁任何云生态。

- **定位锚点**：锚定"严监管企业需要私有可控 Agent 底座"这个不变刚需，不锚"Agent OS"这个概念
- **交付分两段**：核心阶段交付**运行时内核**（五大核心能力）；企业级治理层（多租户/SSO/完整审计/Tool 治理）是扩展阶段和终局，不在核心阶段做
- **核心理念**：业务方写一个 `AGENT.md` 目录 + 配置 MCP server 就能解决业务问题，通过 Web Service 接入已有系统，**不需要写 Agent 后端代码**
- **远期三阶段**：单机内核 → 底座分布式（实例无状态、状态外置）→ 跨节点 Agent 协作（A2A）

## 文档地图

| 文档 | 回答 | 用途 |
| ------ | ------ | ------ |
| `docs/IndustryResearch.md` | Why（业界格局、Java 缺位、定位） | 理解项目动机 |
| `docs/DemandAnalysis.md` | What（五大核心能力、三档需求） | Spec-Kit `/speckit-specify` 的输入 |
| `docs/TechnicalSolution.md` | How（技术栈、关键决策、模块） | Spec-Kit `/speckit-plan` 的输入 |
| `docs/AiProgrammingGuide.md` | How to build（Spec-Kit 流程、user story 拆解） | 实施方法 |
| `docs/oryxos.md` | 项目对外宣言 | 主页/README 素材 |
| `docs/oryx-labs.md` | 孵化社区宣言 | 社区背景 |

**文档间冲突时，以最新技术方案为准。** 当前已知不一致（修订中）：指南说的"9 个模块"是核心阶段骨架（全量 14 个，见技术方案 ch10）；验收 Demo 以技术方案 ch12 的 **3 个**为准（每日天气 / 每日科技日报 / 每日 GitHub 日报），不是指南说的 5 个、也不是需求文档 ch13 的 2 个。

**对外内容三处同步**：项目定位、能力、路线图等对外表述改版时，必须同步更新三处——`README.md`、官网首页（`website/.vitepress/theme/components/Home.vue` 内的中英双语内容对象）、官网文档页（`website/guide/` 与 `website/en/guide/`）。

## 当前状态

- **阶段**：greenfield，设计文档已定稿，Maven 骨架已初始化；已落第一层「契约接口 + 审计地基」（见本节「契约与审计地基」条目），并按逐节开发推进到**第 24 节**——第 16 节 Provider（`specs/001-llm-provider`）、第 17 节 ReAct 循环与 AgentService（`specs/002-react-loop`）、第 18 节 CLI 与会话持久化（`specs/003-cli-session`：12 条命令 + `sessions` 表 + `JpaSessionService`；命令数现为 **15**，见「命令行工具」）、第 19 节 Notify 出站推送（`specs/004-notify-outbound`：`WebhookNotifyAdapter` + `NotifyTools` + 只读渠道注册表接缝 + `notify_channels` 表；**2026-10-15 交付后补强三轮**——① 方言档 `FeishuNotifyAdapter`（飞书 / Lark）+ 路由接缝 `RoutingNotifyChannelAdapter`，新增 35 条测试，接口与通用档零改动，见下文「第 19 节跨节挂账②」；② **写入面**：`notify_channels` 此前只有读半边、写入被挂在第 26 节（而第 26 节的管理台明确「这一版只读」，等于永远不做 → `notify` 在真实部署里 100% 失败却被记成「预期状态」），现由 CLI 三条**轻命令** `notify-channel add|list|remove` 补上，并把 `${ENV_VAR}` 占位兑现为**读时解析**——见下文「第 19 节补强②」；③ 方言档 `WeComNotifyAdapter`（企业微信，`type=wecom`，与飞书报文**镜像**、无签名档），新增 24 条测试，五个受保护文件 `git diff` 为空——见下文「第 19 节跨节挂账②」）、第 20 节 Tool 体系与 MCP（`specs/005-tool-registry`：`ToolRegistry` + 注解管道 + **13** 个内置/扩展工具 + MCP 客户端与适配器 + `PermissiveSandbox` 临时装配（**该临时实现已于第 24 节按裁决 3 整类删除**，见下文「沙箱窗口期已关闭」），新增 108 条测试；另于 2026-10-15 交付后补强两轮——第一轮 P0/P1：`read_file` 分页、`shell` 可配超时、`grep`/`glob` 翻页、新增 `web_fetch`、搜索接缝加一档带 key 实现、免 key 搜索档补 `User-Agent`；第二轮修人工验收抓到的缺陷：`oryxos tool list` 的 MCP 子分组表头打成 `mcp (N):` 而非 `mcp:<server名> (N):`——根因是组内那一层复用了按**前缀**分组的 `group(...)`，已新增按**全串**分组的 `bySource(...)`，并补 `ToolListCommandTest`（5 条）钉死，本节交付时 `oryxos-cli/src/test` 对该输出格式一条断言都没有）均已交付）、第 22 节 Memory 与长期记忆三档后端（`specs/006-memory-service`：`MemoryService` 门面 + `MemoryServiceImpl` + `LongTermMemoryStore` **三档实现**（`MarkdownMemoryStore` 默认 / `SqliteMemoryStore` / `Mem0MemoryStore`）+ 测试替身 `InMemoryMemoryStore` + `builtin/MemoryTools`（`save_memory` / `recall_memory`）+ `memory_entries` 表 + `oryxos.memory.backend` 配置键，新增 **102** 条测试；`oryxos-core` **一行未改**——`MemorySource` 接缝（第 17 节）只被实现、不被修改）、第 24 节 Sandbox 白名单（`specs/007-sandbox-whitelist`：`WhitelistSandbox` 三类校验 + 三个 `@ConfigurationProperties` 值对象 + `application.yaml` 三块白名单；**临时实现 `PermissiveSandbox` 按裁决 3 整类删除**；五个内置工具、四个骨架期契约、`ToolExecutor` **一行未改**——`git diff` 为空是本节的硬判据），`mvn clean verify` 全绿（**488** 条测试）
- **✅ 沙箱窗口期已关闭（第 20~23 节 → 第 24 节）**：第 20~23 节之间沙箱挂的是**临时实现 `PermissiveSandbox`**——它只打一条启动 WARN、**不做任何白名单校验**，那段时间交付的运行时**没有 Tool 治理**（`read_file /etc/passwd` 会真的成功，2026-10-14 端到端实测已确认，见 `specs/005-tool-registry/acceptance-report.md` §2 证据 5）。那是**已报批的取舍**：第 19 节「MUST NOT 造全放行占位沙箱」的裁决被用户有意推翻，理由是第 20 节必须让工具进容器才能交付「Agent 能动手」这件事。**第 24 节（2026-10-17）已交付 `WhitelistSandbox`，窗口期关闭**——`read_file /etc/passwd` 现在在真正读文件之前就被拦。**收口方式与两个空接缝不同（别混为一谈）**：`ToolCatalog` / `MemorySource` 的空实现让位后是 **no-op**（空目录 / 无记忆），故按 `@ConditionalOnMissingBean` 自动让位、**不回头删代码**；而 `PermissiveSandbox` 让位后是「**什么都放行**」——它是个 **fail-open 组件**，Bean 声明一旦被误改（顺序 / 条件注解 / 被别处覆盖）就静默回到全放行、兜底的只有一条 WARN 日志。**故按第 24 节裁决 3 整类删除**：`PermissiveSandbox.java` 与 `PermissiveSandboxTest.java` 已从树里移除，`grep -rn "PermissiveSandbox" oryxos-*/src` **零命中**。装配处保留 `@ConditionalOnMissingBean(Sandbox.class)`，但语义已变——它不再是「等真实现来让位」，而是**有意留的覆盖接缝**（部署方自建 `Sandbox` Bean 即接管，例如未来的容器档）
- **第 19 节遗留特征（两处已在本节解冻）**：① 原「**零装配**」裁决**已被本节推翻**——`RestClient` / `WebhookNotifyAdapter` / 注册表实现 / `NotifyTools` 的 `@Bean` 声明连同沙箱临时实现一并进了 `CliAssemblyConfiguration`（第 19 节不装配的根因是「没有可注入的沙箱 Bean」，本节用 `PermissiveSandbox` 解开；**那个临时实现已于第 24 节整类删除、换成 `WhitelistSandbox`**，本条的其余部分不受影响）② 原「工具进不了容器、`notify` 必然报未配置渠道」**不再成立**——13 个工具已可 `oryxos tool list` 出来、`chat` 里真能调（`notify` 当时仍会报未配置渠道，因为 `notify_channels` 表运行期恒为空；**这条已于 2026-10-15 补强② 清掉**：写入面由 `oryxos notify-channel add` 提供，配了渠道 `notify` 就真能推，没配才走「未配置任何通知渠道」那条报错分支）③ 仍在生效：**`oryxos-tool` 新增两条显式依赖边**（`spring-web`、`oryxos-storage`）——都是既有传递边的显式化，发行物不新增任何工件；另在 `oryxos-tool/pom.xml` 用**模块级 `<dependencyManagement>`** 把 `kotlin-stdlib` 钉回 2.1.21（Spring Boot BOM 会把它降级到 1.9.25，本机仓库没有该版本 → 离线构建直接失败；这段配置看着冗余但删了构建会红，**别清理**）
- **第 19 节跨节挂账（未清）**：`specs/004-notify-outbound/tasks.md` T013 记下的 US3 场景 4——「入站 `ChannelAdapter` 抽象 vs 出站 `NotifyChannelAdapter` 不合并」只做了**可判定部分**（`NotifyChannelAdapter` 不与任何既有类型合并或互相继承）。入站侧的 `ChannelAdapter` 抽象类型**尚未交付**（全仓 grep 仅命中 `NotifyChannelAdapter` 的 javadoc 散文；`CliChannel` 是 CLI 渠道的直连实现、不是抽象类型），完整判定挂到入站抽象落地的那一节
- **第 19 节跨节挂账②：IM 群机器人方言适配（2026-10-15 两轮兑现——飞书 / Lark / 企微已清，只剩钉钉）**（2026-10-14 人工实测发现）——通用 webhook 报文 `{"content":"..."}` 不被 IM 群机器人接受（实测飞书回 **`HTTP 200` + `code:19002` "msg_type need"**，群里无消息；企微同类失败走 `HTTP 200` + `errcode:40008`）。IM 群机器人**用 HTTP 200 承载业务错误**，而 `WebhookNotifyAdapter.send()` 走 `.retrieve().toBodilessEntity()` 只判 HTTP 状态码 → 判成成功 → `notify` **假报「已推送」**。第 19 节交付时按 FR-003 边界**记为已知边界、代码一行不改**（软门禁 ③ 已报批）。**2026-10-15 已兑现为 thin slice（折进第 19 节，不新开 spec）**：新增方言档 `FeishuNotifyAdapter`（覆盖飞书 + Lark，同一套协议；读响应体按业务码 fail-closed 判成败，可选 `secret` 签名）+ 路由接缝 `RoutingNotifyChannelAdapter`（按 `type` 显式映射表分派，**未知 type 报错且一次请求都不发**），装配处把 `webhook` / `feishu` / `lark` 三个取值指向两个实现。**`NotifyChannelAdapter` / `NotifyTarget` 两个接口文件 `git diff` 为空，`NotifyTools` 与通用档代码零改动**（后者仅 javadoc 有变）——被修订的只有 FR-003 里的「唯一」二字，经用户裁决并挂带日期修订注。新增 35 条测试，`mvn clean verify` 全绿（**273** 条）。**企微已于同日补强③ 清掉**（`WeComNotifyAdapter`，`type=wecom`，走的正是原先记下的那条路——装配处映射表加一行 + 加一个实现类，`NotifyChannelAdapter` / `NotifyTarget` / `NotifyTools` / `FeishuNotifyAdapter` / `RoutingNotifyChannelAdapter` 五个文件 `git diff` 为空；新增 24 条测试，`mvn clean verify` 全绿 **349** 条）。**只剩钉钉**，但路由接缝已就位，加它同样是「装配处映射表加一行 + 加一个实现类」，接口与调用方零改动；且钉钉与企微**报文完全相同**（`{"msgtype":"text","text":{"content":"..."}}`），差异只在**业务码语义**与**加签**（钉钉把 `timestamp` + HMAC-SHA256 拼进 **URL 查询串**，飞书是放**请求体**）。**企微与飞书的报文是镜像不是同一份**（企微 `msgtype` **无下划线**、正文在 `text.content`；飞书 `msg_type` **有下划线**、正文在 `content.text`）——这是最易抄错的一处，有单测双向钉死。连带：验收报告 §7.1 原写「配好一个真实群 webhook 后 `notify` 真能把消息送进群」**只对通用 webhook 接收端成立**，已更正。实测证据见 `specs/004-notify-outbound/manual-test-guide.md` **附录 A.2**，真渠道验收向导见同文件**正文 Step 5~8**（企微走 **Step 5b**）。**真渠道验收状态（2026-10-15）**：**飞书档已实测到群**（用户确认群里收到消息）、**企微档已实测到群**（同日，用户确认）；**Lark 档代码就绪但未验收**——用户 2026-10-15 明确裁决**暂不验收**（不是遗漏，是有意推迟；Lark 与飞书同一套协议、同一个实现类，差异只在域名 `open.larksuite.com`，故风险面等于飞书那一档）。**验收覆盖面（别把三档当成等价的「都验过了」）**：飞书档走完了向导的 Step 0~9（正向 + 反向 + 路由三条判据，2026-10-15，用户执行；用户未说明 Step 7 用的哪种故障注入，报告如实不写细节）；企微档验了**正向**（消息进群）与**路由**（`type` 拼错零请求），**反面（对端回业务错误时 `notify` 是否报错）未在真渠道做**，该判据由单测 `WeComNotifyToolTest` 覆盖；Lark 档只到单测层
- **第 19 节补强②：通知渠道写入面（2026-10-15，软门禁 ①② 已报批）**——第 19 节交付的 `notify_channels` 全局注册表**只有读半边**：表建好了、`resolve()` 能按名解析、`notify` 能推，但**没有任何路径能往表里写一行**，写入被挂到「第 26 节」。**这个挂账是错的**，三个独立来源同指一个结论：① 第 26 节的管理台明确「这一版只读」，10 个端点里没有一个碰 notify-channels；② 上游 `class-19` 走的是课件口径（`Profile.notify_channels` frontmatter，`schema.sql` 里 `notify_channels` 出现 **0** 次），**没有表、也就没有这个缺口**——上游 HEAD 才演进到全局表 + REST CRUD，且**写入面与迁移同时交付**，我们复刻时只搬了读半边；③ 业界共识（Hermes / OpenClaw）都是「配置文件为唯一真相源 + 向导 / CLI / UI 写入」，没有一家是「手工改数据库」。**用户裁决（2026-10-15）：保留全局注册表，补最小写入面**。交付三条**轻命令**（判据「要不要调模型、跑引擎」→ 不要，走原生 JDBC 经 `WorkspaceDb.execute`，与 `profile create` / `session list` 同类）：`oryxos notify-channel add <name> --type <type> (--url <值> | --url-env <ENV_VAR>) [--description <text>] [--config <k=v>]...` / `list` / `remove <name> [--force]`。**`${ENV_VAR}` 兑现为读时解析**（库里存字面量 `${FEISHU_HOOK_URL}`，`JpaNotifyChannelRegistry` 在 `toTarget` 里对 `url` 与每个 `config` 值都跑一遍）——凭证永不落库、轮换只改环境变量，合 `docs/TechnicalSolution.md` §6.8「在发送时从环境变量解析，不明文落库」。**变量缺失抛异常、消息只带变量名 MUST NOT 带值**（与 `ProfileLoader.resolveEnv` 同口径；**刻意不同于** `McpServerConfigLoader` 的「跳过整个 server + WARN」——那里一个 server 配错不该拖垮别的 server，这里一个渠道配错**必须**让 `notify` 失败落审计）。**零改动面**：`NotifyChannelRegistry` 接缝**仍无任何写入方法**（写入走 CLI 原生 JDBC，轻命令不启 Spring）、`NotifyChannelAdapter` / `NotifyTarget` 两文件 `git diff` 为空、装配面一行不改（`JpaNotifyChannelRegistry` **保留公开一参构造**，只多一个包级私有二参构造收 `UnaryOperator<String> envLookup` 供测试注入）。**输出纪律（测试钉死，不是补充）**：`url` 含 token——`add` 只打 `name` / `type`（用 `--url-env` 时另起一行打**占位符本身**，那是变量名不是凭证；用字面量时只提示改用 `--url-env`，**MUST NOT 回显那个值**），`list` 的 `url` 列「整串恰好是 `${VAR}` → 原样打印，否则打 `<已配置>`」、`config` 列**只打键名**。`--config url=...` MUST 被拒（读路径会往 `config` 里强塞 `url` 键，从这边也塞就造出两个真相源）；重名报错不覆盖；`--config` 只在**第一个** `=` 处切分（base64 secret 以 `=` 结尾）。两条命令自带幂等建表（`notify_channels` 是第 19 节才有的表，**老工作区的库文件里没有它**，而 `.oryxos/` 是跨升级保留的用户数据）。**仍未做**：第 26 节的 REST + 管理台（`/api/v1/notify-channels` 与接缝的 `save/delete/list` 仍归它）、写入时的 `type` 取值校验（合法取值由装配处映射表决定，轻命令看不到容器——拼错的 type 在**路由处**报错并列出已知类型，那条路径第 19 节已交付；在 CLI 写死一份类型清单会造出第二真相源）、`ConfigLoader` 收口（读时解析与 `ProfileLoader` / `McpServerConfigLoader` 同款「各处自持 `envLookup`」，三处合并是独立收口，不折进这一节）。新增 48 条测试（另 4 条是此前未入库的 `LogStreamSeparationTest`），`mvn clean verify` 全绿（**325** 条）。细节见 `specs/004-notify-outbound/acceptance-report.md`「交付后补强（2026-10-15）②」
- **第 19 节 → 第 20 节改造点：`@ToolParam(required = false)`（✅ 本节已清）**——`NotifyTools.notify(String content, String channel)` 的 `channel` 已补上 `@ToolParam(description = ..., required = false)`（`NotifyTools.java` 方法签名与调用方未变，只多一个参数注解）。背景（2026-10-14 反编译 Spring AI 1.1.8 字节码核实，非记忆）：Spring AI 的 `JsonSchemaGenerator.isMethodParameterRequired` 对**无 `@ToolParam` / `@JsonProperty` / `@Schema` / `@Nullable` 注解的参数一律返回 true**（常量 `PROPERTY_REQUIRED_BY_DEFAULT` = int 1；`ToolParam.required()` 的 AnnotationDefault 同样是 true），不加注解则 `channel` 会被列进 JSON Schema 的 `required`，FR-004「渠道名可选」在模型可见层不成立。第 19 节不改是因为 `@Tool` 当时是惰性的、改了无从验证
- **第 22 节 Memory：四个行为契约 + 三档递进（读代码前必知）**——① **不缓存**：长期记忆每次重新读（读文件 / 查库 / 调 API），`MemoryServiceImpl` 这一层也不缓存；门面一缓存，Agent 调完 `save_memory` 下一轮就看不见自己刚记的东西。② **核心记忆永不被截断**：截断只作用在归档区（Markdown 裁字符串到 4000 字符 / SQLite `LIMIT` 最近 100 条 / Mem0 交给它自己的作用域机制），核心区一字不少——Markdown 档里归档段的字符串**物理上不进截断函数**，故「核心被截断」在那档是不可能发生的事，不是靠边界判断。③ **scope 由调用方显式指定**：`save_memory` 带 `scope` 参数（`core` / `archival`，缺省 `archival` 的落点在 `MemoryTools`、**不在门面**——门面 `remember` 收到 null 直接 `requireNonNull` 抛），系统不猜、不做自动抽取。④ **recall 是关键词检索、只搜归档区**：核心区本来就被全量注入 prompt，参与检索等于同一件事在上下文里出现两遍；跨档统一**不区分大小写**（Markdown 大小写归一 / SQLite `LOWER(...) LIKE LOWER(...)`）；**检索覆盖归档区全量，不受 `load()` 截断影响**（截断只决定「注入上下文的那一份」，否则记忆会「存了但查不到」）。**三档递进 = 三级演进**：`markdown`（默认，零依赖、人可读、git 可跟踪）→ `sqlite`（量大后的结构化升级，仍零外部依赖）→ `mem0`（真要自动抽取 / 冲突消解 / 语义检索时的自托管外部集成档）。**换档只改 `oryxos.memory.backend` 一行，上层 `MemoryTools` / `PromptBuilder` / `ReActLoop` 一个字不改**——`MemoryStoreContractTest` 用**同一套断言对三档统一跑**把这件事钉死（参数源恰好三档，少一档就红）。`Mem0MemoryStore` **真类不进参数化源**（`MockRestServiceServer` 有严格期望序列，不适合当参数），第三行是替身 `InMemoryMemoryStore`；**替身不是第四档可选后端**——不在 `backend` 取值里、无 `@Bean` 声明它，装配处选不中。**`capabilities()` 三态与路由**：`KEYWORD` / `DELEGATED` 都走 `recallByKeyword`；`HYBRID_BUILTIN`（内置语义三路融合）核心阶段**无实现方**，门面**抛异常**、MUST NOT 静默降级成关键词检索（静默降级会让运维以为「语义检索已生效」）。**Mem0 档的凭证边界**：`base-url` 含 token——`Mem0MemoryStore` 不打日志、异常消息不含它、`toString()` 也不含它（显式钉死，防后来有人加 IDE 生成的 `toString`）；异常**刻意不挂 cause**（`ResourceAccessException` 的 message 会把请求 URL 原样带出来）；**类声明 `final`**（两个构造器都会抛异常，可被继承的类抛构造器异常会招 finalizer 攻击，FindSecBugs 的 `CT_CONSTRUCTOR_THROW`）。**报文口径是我们自己定的最小契约、从未与真实 Mem0 服务对过**——真接上外部服务时第一个要核的是 `results` 字段名（**该档经用户裁决不验收，已成永久已知边界**，见下文「第 22 节人工验收状态」）
- **第 22 节的两处已报批偏离（MUST NOT 当成缺陷）**：① **记忆文件 IO 不过 `Sandbox.enforce`**（仲裁 3）——第 20 节 H4 不变量①要求「涉外 IO 首行过 `Sandbox.enforce`」，但技术方案 §6.7 把沙箱的调用方**只点了三处**（`FileTools` / `ShellTools` / `HttpTools`），第 22 节课件完全没提沙箱。裁决理由是：沙箱管的是「**模型可以指定目标**的工具」，而记忆文件路径是**编译期固定常量**（`.oryxos/memory/MEMORY.md`），模型无法把它指向别处，不构成越界面。**连带**：`oryxos-memory` **不新增**对 `oryxos-tool` 的依赖边（`Sandbox` 定义在 `oryxos-tool`，那会引入一条前序各节都没走过的模块依赖边）。**② `MemoryService implements MemorySource`，不提供课件的 `buildContext(Session)`**（仲裁 2 / D1）——core 的 `MemorySource` 接缝是骨架期自造的，实现既有接缝即可让 `PromptBuilder` 零改动地用上真实记忆；课件那个 `buildContext(Session)` 会把长期记忆与会话历史焊在一个方法里返回（FR-015 明令 MUST NOT 混），已记为已知偏差
- **第 22 节 Mem0 档出站 HTTP 与沙箱的边界（2026-10-15 已裁决，非挂账）**——H4 不变量①的另一半。`Mem0MemoryStore` 是本节的第二个涉外 IO 点，它**结构上不可能**过沙箱：`Sandbox` 定义在 `oryxos-tool`，而 `oryxos-memory` MUST NOT 依赖 `oryxos-tool`（FR-021 连带，见上条）。**用户裁决（2026-10-15）：不过沙箱是对的，本条按「已裁决」记，不再挂账。** 依据三条：① 仲裁 3（`specs/006-memory-service/spec.md:65`，交付时已裁决）已定原则——沙箱管的是「**模型可以指定目标**的工具」，本仓 §6.7 的沙箱调用方名单从来只有 FileTools / ShellTools / HttpTools，而 Mem0 的地址来自 `application.yaml`、不来自模型，模型无法把它指向别处，与「记忆文件 IO 不过沙箱」是**同一条理由**；旁证：**上游**章程 VI 的射程原文同为「**工具调用** MUST 经 `SandboxChecker` 白名单校验」（本仓章程 VI 是审计数据落库、**并无此句**——2026-10-15 复核更正，此前误记为本仓原文）；② 上游 `specs/006-memory-pluggable/acceptance-report.md:39` 逐字裁决过同一件事——「Mem0 档是配置的外部服务（**非工具涉外 IO**）」，`plan.md:32` 在宪法 VI 检查项里记了同一结论，且从 `class-22` 守到 `main` HEAD 一步没退（`Mem0MemoryStore` 在两处 grep `OutboundGuard|Sandbox|enforce` 均零命中，`oryxos-memory/pom.xml` 从未长过 `oryxos-tool` 依赖边）；③ 上游 `docs/TechnicalSolution.md` §6.7 的沙箱调用方名单里从来没有 Memory。**口径已写进 `docs/TechnicalSolution.md` §6.7 要点三**（沙箱范围 = 模型可指定目标的工具调用；装配期配置决定的出站地址不在范围内）。**与文件 IO 的一处差异如实留痕**（2026-10-15 复核补记）：文件 IO 数据不出本机，而 Mem0 出站会把 Agent 生成的记忆内容**发往外部服务**——「模型拨不动指针」只覆盖目标可控性，不覆盖误配 / 被篡改 base-url 的数据外泄面（域名白名单本来拦得住这类）；残余风险小（base-url 与白名单本是同一管理员配的、窗口期 `PermissiveSandbox` 内本来就没有白名单、装配期有非空校验；**那个临时实现已于第 24 节整类删除**），真要管时的形态就是下面的 `OutboundGuard`。**备案（不是当前交付物）**：若将来真要管这类装配期出站，形态是**在 `oryxos-core` 放一个 `OutboundGuard` 接缝**（`void check(String url)`，装配层适配成 `url -> sandbox.enforce(new SandboxAction(HTTP_REQUEST, url))`）——这是上游给「模块自建 HTTP 出站」造的既有正解（`oryxos-core/.../channel/OutboundGuard.java`，18 个 channel 模块消费、63 个文件命中），契约在 core、模块不依赖 `oryxos-tool`。**现在不建**：按本仓那条「第三次出现才抽象」的判据（三处 `envLookup` 不合并、两档方言 `send()` 骨架不抽基类），这会是**第二个**「core 接缝 + 模块自建出站」的点（channel 那个是第一个），第二次就抽早了。**候选 ②（给 `oryxos-memory` 开一条到 `oryxos-tool` 的依赖边）已明确否决**——上游零先例，且直接撞 FR-021
- **第 22 节人工验收状态（2026-10-16）**——向导落点 **`specs/006-memory-service/manual-test-guide.md`**（Step 0~9；体例照第 19 节那份）。**用户按 Step 0~8 全程人工执行（真 DeepSeek 模型、真 `oryxos.jar`），结论：全部通过，未报告任何一步失败**。**Step 9（Mem0 档真连）经用户裁决有意不做**——不是遗漏、不是待办：**`Mem0MemoryStore` 与任何真实 Mem0 服务一次都没对过话**，报文契约（`/memories`、`/search`、`results` 字段名）是我们自己定的最小契约，单测只证明「请求发得出去、body 形状对、响应按我们的假设解析得对」，**不证明真实 Mem0 会这样回**——该档由「待验证」转为**永久记录的已知边界**，将来接真服务时 **MUST NOT 把它当成已验证过的档**。**验收覆盖面（别把三档当成等价的「都验过了」）**：`markdown` 与 `sqlite` 两档走完了真模型真链路（判据 1 三档切换 / 判据 2 Agent 主动调 `save_memory` / 判据 3 跨会话核心记忆在场），**`mem0` 档只到单测与 mock 层**。**留痕口径**：用户只给了整体结论、未逐步说明各步输出，故向导与验收报告 §6.5 **只记「整体通过」、不写步骤级细节**（与第 19 节对「用户未说明 Step 7 用的哪种故障注入」的处理同口径）。**切档方式的实测边界**（契约 `specs/006-memory-service/contracts/memory-config.md` §1.1）：契约内只有两条路——改 `application.yaml`、`-Doryxos.memory.backend=sqlite`（**必须写在 `-jar` 前**）；**Spring Boot 惯用的 `--oryxos.memory.backend=sqlite` 不生效**（Picocli 先吃掉命令行 → `Unknown option`，退出码 **2**，已实测），环境变量 `ORYXOS_MEMORY_BACKEND` **事实上生效**（Spring Boot relaxed binding 内建行为）但**不是本仓契约、MUST NOT 写进文档或脚本**（`tasks.md` T002 明令不立这个对外概念）
- **已有**：`.specify/`（Spec-Kit v1.0.10 脚手架）、`.claude/skills/`（10 个 speckit-* skills，dash 风格命名）、docs/、`LICENSE`（Apache 2.0）、Maven 9 模块骨架（各模块暂为占位类；**唯一发行物 = `oryxos-boot/target/oryxos.jar`**，`java -jar oryxos-boot/target/oryxos.jar --version` 可直接运行。`oryxos-boot` 的 fat JAR 主类是 **`OryxOsCli`**（`oryxos-cli` 的 Picocli 主入口，**15** 个子命令——第 18 节的 12 条 + 2026-10-15 补强② 的 `notify-channel add|list|remove`）——`OryxosApplication` 自第 18 节起不再是 fat JAR 主类，去留由第 26 节定；`oryxos-cli` 的 maven-shade-plugin 已移除，该模块退回普通库模块。`serve` 本节只起 Spring 上下文、不起 Web 容器，REST 端点在第 26 节接入）
- **工程地基**（oryxos-init 已装，业务代码在其上生长）：Spring Boot **3.5.16**；`logback-spring.xml`（dev 彩色 console **走 stderr** / prod JSON + traceId MDC **走 stdout**）；Actuator + Prometheus（`/actuator/health|info|prometheus`）；`application.yaml`（虚拟线程开启、SQLite 定式 HikariCP pool=1 + busy_timeout + WAL）；`oryxos-web` 已有统一信封 `ApiResponse` + `GlobalExceptionHandler`（400/404/500，503/504 映射待 US-1/US-5）；springdoc UI 在 `/swagger-ui`；**静态检查门禁已接入 `mvn verify`**：Spotless（google-java-format，改代码后跑 `./mvnw spotless:apply`）+ 阿里 P3C（PMD 6.55）+ Checkstyle + SpotBugs/FindSecBugs + OWASP Dependency-Check（根模块聚合，本地可 `-Ddependency-check.skip=true`）；CI（`.github/workflows/ci.yml`）+ pre-commit（`scripts/hooks/`，本机已启用，JAVA_HOME 走 gitignore 的 `local-env.sh`）
  - **P3C 有三处"看着多余、删了会静默失效"的配置，不要清理**：① 父 pom 的 `asm.version`（9.7.1，覆盖进 maven-pmd-plugin 依赖）——PMD 6.55 自身硬锁 asm 9.4，只认到 class major 64/Java 20，而本项目 `release=21` 编出的 class 是 major 65，类型解析读 `target/classes` 时逐文件抛 `Unsupported class file major version 65`；② `skipPmdError=false`——PMD 默认把这个错误吞成 WARNING，且 `pmd:check` 只数 violation 不看 processing error，两者叠加会让门禁「全挂 = 零违规 = 通过」地**静默空转**（曾实际发生：27 个源文件全挂却 BUILD SUCCESS）；③ 根目录 `p3c-ruleset.xml`——P3C 规约集入口，引用全量 10 个分类并排除 `ClassMustHaveAuthorRule`（git 已是作者事实源，`@author` 属噪音型合规；恢复只需删该文件的 `<exclude>`）。改动这三处前先读它们的文件头注释
  - **`logback-spring.xml` 里 `<target>System.err</target>` 这行不要删**（2026-10-15 修，软门禁 ② 已报批）：logback `ConsoleAppender` 默认打 `System.out`，而 CLI 命令的输出（`System.out.println`）也在 `System.out` 上——**同一条流**。第 20 节把 `tool list` 从轻命令改成重命令（要连 MCP server 才能列出工具清单）后，实测表格前有 **24 行** Spring / Hibernate 启动日志，于是 `oryxos tool list | head -20` **一行工具都打不出来**、`| grep` 之类的管道消费方全被污染。改到 stderr 后按 Unix 惯例分流：**数据走 stdout、诊断走 stderr**，管道只拿命令输出，`2>/dev/null` 可静音，终端上两股都还看得见。**只改 `!prod` 档**——`prod` 的 JSON appender 必须留在 stdout（容器日志采集侧读的是 stdout）。**WARN 级诊断走 stderr 这条口径仍然成立、MUST NOT 被静音**（第 24 节前它是 `PermissiveSandbox` 启动时那条「不校验任何东西」的 WARN，是沙箱窗口期的缓解措施 ②；那个临时实现已于第 24 节整类删除，但「WARN 不被静音」这条契约不随它走）。**原有测试抓不到这个**：`ToolListCommandTest` 用反射直接调私有的 `print`，根本不进 Spring 上下文——故补了 `oryxos-cli` 的 `LogStreamSeparationTest`（4 条，读该配置源文件；落在 `oryxos-cli` 是因为 `oryxos-boot` 零测试基建，且 CLI 正是这条契约的受害方）。已做**变异测试**：删 `<target>`、给 prod 也加 `System.err`、把 dev 根级别压到 `ERROR`，三种各命中对应断言变红
- **官网**：`website/`（VitePress 1.6，中英文双语 root=zh / en，自定义主题首页非默认模板；`base: '/oryxos-wngbob/'` 对应 GitHub Pages 项目页 wngbob.github.io/oryxos-wngbob；`.github/workflows/deploy-pages.yml` 在 `website/**` 变更推送 main 时自动构建部署（Pages Source 须设为 GitHub Actions）。若绑定独立域名 oryxos.wngbob.com：base 改为 `'/'` 并在 `website/public/` 放 CNAME 文件）
- **契约与审计地基**（骨架期已落，均为接口/表结构，不含业务实现）：契约按「实现依赖契约、core 不反向依赖」放置——`oryxos-core` 定义 `OryxTool` / `ToolResult`；`oryxos-tool` 定义 `Sandbox` / `SandboxAction` / `ActionType` / `SandboxViolationException` / `NotifyChannelAdapter` / `NotifyTarget`；`oryxos-memory` 定义 `LongTermMemoryStore` / `MemoryScope` / `MemoryCapability`。审计表 `tool_invocations` / `llm_calls` 的实体 + Repository 在 `oryxos-storage`，建表脚本 `oryxos-storage/src/main/resources/db/schema.sql`（手工执行，`ddl-auto=none`，不走 Spring Boot 的 classpath `schema.sql` 自动执行）；`oryxos-boot` 因实体在兄弟包，必须显式 `@EntityScan` + `@EnableJpaRepositories`
- **打包脚本**：`scripts/package.sh`（编译 → 打包 `dist/` → scp 上传远程；源码包排除所有 `target/`、`node_modules` 等本地产物，唯一上传的构建产物是脱离 target/ 的 `dist/oryxos.jar`；远程目标用 `REMOTE_HOST` / `REMOTE_DIR` 环境变量配置，未设置则跳过上传）
- **待办**：constitution v2.0.0 与 `specs/000-runtime-kernel` 已定稿；第 16~22 节与第 24 节已完成（`specs/001-llm-provider` / `002-react-loop` / `003-cli-session` / `004-notify-outbound` / `005-tool-registry` / `006-memory-service` / `007-sandbox-whitelist` 各自 spec/plan/tasks 齐备并已实现，第 18 节 55 个任务、第 19 节 19 个任务、第 20 节 61 个任务、第 22 节 37 个任务、第 24 节 17 个任务全绿）。下一步按课件顺序推进**第 25 节**（第 23 节是评审课、不产码）。跨节挂账：`specs/003-cli-session/` 记的 `OryxosApplication` 反向撞车归第 26 节；**两个空接缝让位已全部清掉**（`ToolCatalog` 空实现在第 20 节让位给 `ToolRegistry`，**Memory 侧 `EmptyMemorySource` 已在第 22 节让位给 `MemoryServiceImpl`**——两次都是 `@ConditionalOnMissingBean` 自动生效、**没有回头删代码**；`@Bean` 声明顺序是这条机制的隐藏前提：`memoryService()` MUST 声明在 `memorySource()` 之前，`MemoryAssemblyTest` 既断言行为也扫源码顺序。**第三个「让位」是反例**：`PermissiveSandbox` 是 fail-open 组件，第 24 节按裁决 3 **整类删除**而非让它位——见上文「沙箱窗口期已关闭」）；`specs/004-notify-outbound/` 记的入站 `ChannelAdapter` 抽象让位见上条；~~**第 22 节新挂账**：Mem0 档出站 HTTP 没过沙箱~~（**2026-10-15 已裁决**：不过沙箱是对的，口径写进技术方案 §6.7 要点三——见上条，**不再是挂账**）。**第 20 节挂账**：~~`WhitelistSandbox` 归第 24 节~~（**✅ 第 24 节已清**：窗口期关闭，见上文）、~~`save_memory` / `recall_memory` 两个内置工具归第 22 节~~（**✅ 第 22 节已清**：经同一 `ToolRegistry.registerAnnotated` 自动进注册表，注册表与过滤逻辑零改动）、~~`notify_channels` 写入路径归第 26 节~~（**2026-10-15 补强② 已清**：写入面由 CLI 三条轻命令提供；第 26 节仍归 REST CRUD 与管理台）、~~`api.tavily.com` 的域名白名单归第 24 节~~（**✅ 第 24 节已清**：随包默认的 `http.allowed_domains` 已含它，见 `application.yaml`）。**第 24 节新挂账（不是遗漏，是显式推迟）**：`FILE_READ` / `FILE_WRITE` 共用一份 `file.allowed_paths`（**读写分权推迟**——`ActionType` 已把两类分开建模，接缝留着，将来加配置键 + 在 `enforce` 两个 case 上分派即可，接口与调用方不用改）。**第 20 节 P2 挂账（2026-10-15 补强时评估后决定不做，只记不做）**：① `apply_patch` 式批量多文件编辑——`edit_file` 的唯一匹配约束已够用，YAGNI；② 计划/进度工具（`update_plan` 等价物）——要在 `sessions` 表落状态并改 `PromptBuilder`，是跨节改动，不该塞进工具节；③ 后台 shell + 输出拉取（`BashOutput`/`KillShell` 等价物）——违反核心阶段同步模型（章程原则 8），归扩展阶段；④ 图片 / PDF 读取——需 VLM；⑤ MCP resources / prompts——目前只接了 MCP 的 tools，协议面另一半；⑥ 危险操作审批层（Claude Code permission mode / Codex approval mode）——白名单之外的中间层，归第 24 节之后评估；⑦ ~~`notify` 未用 `@Tool(name = "notify")` 钉名~~ **已于 2026-10-15 补上**（`NotifyTools.java`，成本一行）
- **第 24 节交付后复核（2026-10-17，用户验收通过之后）——两处如实记录**：① **向导的回滚指令曾把 T001 的交付物删掉**：`git checkout -- oryxos-boot/src/main/resources/application.yaml` 把 `file`/`shell`/`http` 三块白名单**整体还原掉**（那三块是**本节尚未提交**的新增，`git diff` 在该文件上**永远有输出**，向导却把「有输出」当成「Step 5b 改过还没还原」→ 命令是破坏性的）。**后果是静默的**：`mvn clean verify` 照样全绿（`ToolAssemblyTest` 走 `CliTestContext.boot(..., Map.of("file.allowed_paths[0]", ...))` **自己注入属性源、不读 `application.yaml`**，它守的是「键名拼错」、守不了「配置块整体不见」），但真部署里三个值对象绑到 null → 归一化成空表 → **空 = 全拒** → 所有文件/命令/HTTP 工具全被拦（fail-closed，不是安全洞，但 Agent 彻底不能干活）。已从 20:31 那份 jar `unzip -p ... BOOT-INF/classes/application.yaml` **逐字节取回**（`git diff --numstat` = `22 0` 为证）；口径已改成按内容 `grep`/`sed` 回滚，向导 Step 9 加 MUST NOT 告警框，验收报告 §四 记抓红 3。**教训（与向导第一处抓红同类）**：「命令能跑」不是判据，「这条命令在**『本节尚未提交』**这个前提下会发生什么」才是——`git checkout --` 的语义是「丢弃工作区改动」，而工作区改动**正是本节的交付物本身**。② **`WhitelistSandbox.enforce` 的 null target 行为不一致（未修，记为未覆盖边界）**：`checkShellCommand` 判空、`checkFilePath` / `checkHttpUrl` 不判，故 4 条路由里 **3 条**（`FILE_READ` / `FILE_WRITE` / `HTTP_REQUEST`）抛 `NullPointerException` 而不是 `SandboxViolationException`——与 `checkHttpUrl` 自己 javadoc 写的「解析不出 host 一律拒，**且不得抛 NPE**」相矛盾（`URI.create(null)` 抛的是 NPE 不是 `IllegalArgumentException`，那个 catch 接不住）。**fail-closed 保住了**（异常上抛、IO 未发生、仍走 `ToolExecutor` 既有 `catch (RuntimeException)` 审计路径），代价只是 `error_message` 从「路径不在白名单内: …」退化成一句 NPE；**可达性只有一条、且未证实**——模型对必填 String 参数**显式发 JSON `null`**（六个 `path` 与三个 `url` 都是必填参数，无 `required = false`）。与 §六(c) 的 `InvalidPathException` 同一类，按 YAGNI 不修
- **前序节文档里「沙箱窗口期」失效表述的回修（2026-10-17 已做，**13 条修订注 / 5 个文件**）**——第 24 节关闭窗口后，第 19/22 节文档里以**现在时**写的「沙箱挂的是临时实现 `PermissiveSandbox`、白名单校验不生效」全部变成假话。**已按本仓惯例挂带日期修订注（`2026-10-17`）、一律不重写原文**。落点：`specs/004-notify-outbound/manual-test-guide.md` **4 条**（前置事实表 ④ + 紧邻的硬约束块、Step 4 的启动日志段、坑表第 5 条、附录 B 那条域名白名单）、`specs/004-notify-outbound/acceptance-report.md` **6 条**（`补强·` 段的人工验收边界、**`grep -c PermissiveSandbox   # → 1` 那条自检**、紧随的「保留项」、`LogStreamSeparationTest` 断言表末行、`补强③·十` 的域名白名单、以及紧接第一处的那条「（仍成立）」）、`specs/006-memory-service/manual-test-guide.md` 1 条（前置事实 8）、`specs/006-memory-service/quickstart.md` 1 条（§6 整节）、`specs/006-memory-service/acceptance-report.md` 1 条（仲裁 3 段后的「另注」）。**加注判据（以后同类回修照此）**：该处**以现在时断言当前状态**且现已为假 → 加；**过去时 / 带日期的历史留痕、决策日志不加**——那是留痕不是错误断言。故**故意未加**的：`manual-test-guide.md` 附录 A（该节自带头注「这一节是留痕，不是操作步骤」）、`quickstart.md` 已有的 `2026-10-15 修订注` 块、`tasks.md` / `research.md` 里记「当时决定不造 `PermissiveSandbox`」的决策日志（该裁决后来被用户推翻，但那是**决策史**）。**两条如实留痕**：① 我最初在 `CLAUDE.md` 里列的清单**不完整**——实际全仓命中 12 处（原清单只列了 9 处），复核后补齐；② `004-notify-outbound/acceptance-report.md` 那条自检**命令与预期双双失效**：`grep -c PermissiveSandbox` 现在恒得 `0`，**且退出码变成 `1`**（`grep -c` 无命中即非零），在 `set -e` 脚本里会**直接中断**——已在注里写明「不要再照抄」。**另发现一笔未处理的账**：同段 `wc -l → 19`（数 `tool list` 表格行数）会随内置工具数变化，向导「前置事实 ①」的「13 个工具」同样过期——**那是第 22 节（13 → 15 个内置工具）引起的另一类失效，本次未回修**，已在对应注里标明「以实际输出为准」并**实测补上真实值**（2026-10-17 在第 24 节后的 jar 上跑：`wc -l` = **`21`**，`builtin (15)`）。**`specs/005-tool-registry/` 下同类位置（`manual-test-guide.md:25` / `:107`、`contracts/builtin-tools.md:298`、`spec.md:54`）按既定约束 MUST NOT 触碰**——故该目录里 `PermissiveSandbox` 仍以现在时出现，是**已知的、有意保留的**不一致
- **环境**：JDK 21 + Maven 3.9.16 便携版在 `D:\data\work\tools\`（未入系统 PATH；构建前 `export JAVA_HOME=/d/data/work/tools/jdk-21.0.12.1+1`，仓库自带 `./mvnw`；国内构建用 `-s D:\data\work\tools\maven-settings-aliyun.xml` 走阿里云镜像）；已是 git 仓库（main 分支），remote `origin` = <https://github.com/wngbob/oryxos-wngbob>（Public），本仓库为上游 <https://github.com/oryx-labs/oryxos> 的练习复刻（2026-10-12 由 oryxos 改名 oryxos-wngbob，腾出原名供 fork 上游）；repo-local 配置：`http.proxy=http://127.0.0.1:7890`（本机直连 github.com 不通，走 Clash 代理）、`credential.helper=store`（本机 GCM 异常已旁路，PAT 明文存于 `~/.git-credentials`）；用 commit 标记每个 user story 完成

## 技术栈

JDK 21 + Spring Boot 3.x 单体 + **Spring AI Alibaba（仅用协议转换 + @Tool schema 生成，禁用其自动 tool 执行；ReAct 循环自实现）** + SQLite（Spring Data JPA）+ Picocli + SnakeYAML + Logback/SLF4J。同步阻塞执行模型 + Java 21 virtual thread。打包 `mvn clean package` 出 fat JAR，`java -jar` 启动；扩展阶段引入 GraalVM Native Image。

## 不可违背的原则

AI agent 生成的代码违反以下任何一条，都必须返工：

1. **JDK 21 + Spring Boot 3.x 单体**，Maven 多模块（核心阶段 9 个），单二进制部署
2. **五大核心能力优先**：核心阶段只做运行时内核，企业级治理层放扩展阶段
3. **自实现 ReAct loop**，不依赖 Spring AI 的 Agent 抽象
4. **Spring AI 只用一半**：只用 Provider 抽象 + 协议转换 + `@Tool` schema 生成；**必须禁用 Spring AI 的自动 tool 执行**；tool 调度完全由 `ReActLoop` + `ToolExecutor` 控制（最易写错的一条，启用自动执行会导致 tool 被调两次）
5. **Plugin Tool 三档接入**：主推零代码 `AGENT.md` 目录 + 复用 MCP server；轻代码自写 MCP；重代码 `@Tool` Java Bean
6. **审计表 Day One 写入**：`tool_invocations` 和 `llm_calls` 核心阶段就落库，不是只写日志；定时任务（钟推）的失败调用也走同一审计路径。可审计的数据地基 day one 立起来，避免后期从日志反解析返工
7. **不使用 Java SecurityManager**：JDK 17 起废弃、JDK 21 已不可用。沙箱 = `Sandbox` 接口 + `WhitelistSandbox` 应用层白名单实现，未来按容器 → microVM 升级，接口不变
8. **同步执行模型**：核心阶段同步阻塞 + Java 21 virtual thread，不引入响应式编程；SSE 流式响应、异步 Tool 调用放扩展阶段
9. **SQLite + MEMORY.md 文件存储**：向量检索放扩展阶段，`LongTermMemoryStore` 接口预留升级空间
10. **Provider 显式映射**：维护 provider name → `ChatModel` 的显式映射表，**禁止按类型扫描容器里的 ChatModel Bean**（多 Provider 并存时会有歧义）
11. **`AGENT.md` 不是 Tool**：Agent 目录归 `ContextLoader`（在 `oryxos-core`），正文注入 system prompt；不进 `ToolRegistry`
12. **Tool 单模块**：内置 Tool / MCP Client / ToolRegistry / Sandbox 合并为一个 `oryxos-tool` 模块，不再拆
13. **Skill 绑定的唯一真相源是软连接**：公共实体在 `.oryxos/skills/<name>/`，Agent 通过自身 `skills/<name>` 相对软连接选择可见集合，不写进 frontmatter；Skill 不是 Tool
14. **定时任务是第三触发源**：`AgentScheduler` 钟推与 CLI/Web 人推复用同一条 `AgentService.process` 链路，ReActLoop 不感知消息来源
15. **每个 user story 完成后有可演示 Demo**，跑通优先于完美

## 设计原则

- **四个设计目标**：**统一**（企业内多个 Agent 共享一套底座，新 Agent 放一个目录就能跑）、**私有**（数据完全留在企业基础设施，OryxOS 本身不收集任何企业数据）、**易接入**（标准 Spring Boot 工程结构，对接现有 ERP/CRM/CMDB/SSO，运维复用 Java 工具链）、**可观测**（Prometheus 指标、结构化 JSON 日志、健康检查）
- **接口先行**：`Sandbox` / `LongTermMemoryStore` / `NotifyChannelAdapter` 都只先定不携带实现细节的中立接口，核心阶段挂一档实现，升级只新增实现类、不改接口和调用方（用最重的 microVM 实现反套接口签名，也应该能干净套入）
- **能力选择原则**：Plugin Tool 能用方式一（零代码）就不用方式二（自写 MCP server），能用方式二就不用方式三（@Tool Bean）
- **做减法的哲学**：核心阶段范围卡紧（4 周×3 小时极强约束），每周有可演示成果，跑通优先于完美；没有被证实的需求之前不引入抽象、依赖或功能（YAGNI）

## 配置加载规则

- **`ConfigLoader`（在 `oryxos-cli`）统一加载** LLM API key、Provider 凭证、MCP server 凭证等敏感配置，不散落各模块
- 敏感配置走**环境变量注入**或独立本地配置文件，**不明文写死在 `AGENT.md` frontmatter**；Profile 里用 `${ENV_VAR}` 占位，加载时从环境变量解析
- 加载时做**必填项 + 格式的基础校验**，缺失或非法时给出清晰报错
- **实际落点是「各处自持 `envLookup`」**（三处，**失败策略刻意各不相同**，不是重复代码）：`ProfileLoader.resolveEnv`（core）**抛异常**；`McpServerConfigLoader`（tool）**跳过整个 server + WARN**——那里一个 server 配错不该拖垮别的 server；`JpaNotifyChannelRegistry`（tool，2026-10-15 补强②）**抛异常**、消息只带变量名——这里一个渠道配错**必须**让 `notify` 失败落审计。`ConfigLoader` **没有**收口这三处：把三份共享正则常量省不了几行、共享解析循环反而要传策略函数，合并是一次独立收口，不折进补强
- **`${ENV_VAR}` 一律**读时解析**、MUST NOT 在写入时展开**（补强② 定稿）：通知渠道库里存的是字面量 `${FEISHU_HOOK_URL}`，**凭证永不落库**。写成 `${A B}` 这种形状不合法的占位**原样保留不报错**（判据是正则 `\$\{([A-Za-z_][A-Za-z0-9_]*)}`），只有**形状合法但变量缺失**才报错
- **第 22 节的三个记忆配置键**（`oryxos-boot/src/main/resources/application.yaml`，契约 `specs/006-memory-service/contracts/memory-config.md` §1）：`oryxos.memory.backend`（**字面量不是环境变量占位**，取值 `markdown`(默认) / `sqlite` / `mem0`，切档走改配置或 `-Doryxos.memory.backend=sqlite`）、`oryxos.memory.mem0.base-url`（`${MEM0_BASE_URL:}`）、`oryxos.memory.mem0.user-id`（`${MEM0_USER_ID:oryxos}`）。**两个 mem0 子键都带空/缺省默认值**——`markdown` / `sqlite` 两档根本不读它们，缺失不该让启动失败；校验推迟到「真的选中 `mem0` 档」那一刻，且**消息只带变量名、不带值**（`长期记忆后端 mem0 需要环境变量 MEM0_BASE_URL，当前未设置`）。**取值未知时装配处报错并列出合法取值，MUST NOT 静默退回默认档**——静默退回最坏：运维改了配置、重启、以为生效了，实际还在用旧的。**配置键带 `oryxos.` 前缀是本仓库唯一一处有意偏离上游**（D8）：环境变量名**不带**前缀（那是外部系统的既有命名，与上游一致），但配置键要跟本仓既有的 `oryxos.search.tavily-api-key` 同一形状
- 扩展阶段才做：加密存储、密钥轮转、对接企业 KMS/Vault

## Web Service API

核心阶段 10 个端点（`oryxos serve` 启动，默认端口 8080，virtual thread）：

| 类别 | 端点 |
| ------ | ------ |
| 会话管理 | `POST /api/v1/sessions`（创建）、`POST /api/v1/sessions/{id}/messages`（发消息）、`GET /api/v1/sessions/{id}`（查历史）、`DELETE /api/v1/sessions/{id}`（归档） |
| Agent 调用 | `POST /api/v1/agents/{name}/invoke`（无状态调用） |
| 信息查询 | `GET /api/v1/profiles`、`GET /api/v1/memory`、`GET /api/v1/tools` |
| 系统状态 | `GET /api/v1/health`、`GET /api/v1/info` |

设计要点：

- 6 个 `ApiController`（Session/Agent/Profile/Memory/Tool/System）只做参数校验、响应包装、错误处理，实际逻辑委托核心层服务
- 统一响应信封 `ApiResponse`（`code` / `message` / `data` / `timestamp`），成功与错误共用一个信封；错误码用标准 HTTP 状态码（400 参数错 / 404 不存在 / 500 内部错 / 503 Provider 故障）
- OpenAPI 3.0 文档由 springdoc-openapi 自动生成，暴露在 `/swagger-ui`
- 限制：单条消息最大 32KB；Session 历史最多返回最近 100 条；Agent 调用最长 60 秒超时返回 504；CORS 核心阶段全开放方便调试
- **核心阶段不做**：认证机制（无认证假设内网）、SSE 流式响应、WebSocket、RBAC、限流

## 命令行工具

核心阶段 **15** 个命令（Picocli 实现，`OryxOsCli` 为主入口；第 18 节交付 12 条，2026-10-15 补强② 扩到 15——新增的三条属**命令集扩充**，既有 12 条的命令名 / 参数 / 退出码**一字未动**）：

| 类别 | 命令 |
| ------ | ------ |
| 启动和状态 | `oryxos init`（初始化工作区，幂等）、`oryxos status`、`oryxos chat [--profile <name>]`、`oryxos serve`、`oryxos gateway` |
| Profile 管理 | `oryxos profile list` / `create <name>` / `show <name>` / `delete <name>` |
| 通知渠道管理 | `oryxos notify-channel add <name>` / `list` / `remove <name>` |
| 查询 | `oryxos provider list`、`oryxos tool list`、`oryxos session list` |

- 三种运行模式（`chat` / `serve` / `gateway`）共享同一份 Profile 配置和 Session 存储
- **轻 / 重之分**：判据只有一条——这条命令要不要调模型、跑引擎。不需要 Spring 上下文的轻命令（`init`、`profile list`、`provider list`、`session list`、**`notify-channel add|list|remove`** 等）直接走文件操作保证启动速度；重命令（`chat` / `serve` / `gateway` / **`tool list`**）启动 Spring 上下文
- **`notify-channel` 三条是轻命令**（2026-10-15 补强②）：按书面判据「要不要调模型、跑引擎」——不要。它们**看不到 Spring 容器**，所以写入走原生 JDBC（`WorkspaceDb.execute`）而非 `JpaNotifyChannelRegistry`，**注册表接缝因此仍无写入方法**；也正因为看不到容器，写入时**不做 `type` 取值校验**（合法取值由装配处映射表决定，写死一份会造出第二真相源），拼错的 `type` 在**路由处**报错并列出已知类型
- **命令树的形状有测试钉死**：命令树是**显式装配**的（注解表达不了构造器注入的实例），故 `CliCommandTreeTest` 断言叶子集合**恰好**是契约里的那 15 条——加/删命令会红，逼你回来同步契约
- **`oryxos tool list` 自第 20 节起是重命令**（第 18 节交付时是轻的）：列出 MCP 工具必须连上 server，清单只有连上才知道，这已经是「跑引擎」；第 18 节归轻是因为当时没有注册表可查（`ToolCatalog` 是空实现），不是判据不同。代价如实记录：有 MCP server 声明时最多等待 `10 秒 × server 数`；另会打一段启动日志（**走 stderr**，与 stdout 上的表格分流——2026-10-15 修，见「工程地基」条）。命令名 / 参数 / 退出码三项未变（契约 `specs/003-cli-session/contracts/cli-surface.md` §1 要求走修订程序的正是这三项，已回修该契约 §2.10 与 §1 表格）

## Tool 体系

- **`OryxTool` 接口**（统一抽象，ReAct 循环不感知 Tool 来源）：`getName` / `getDescription` / `getInputSchema`（JSON Schema）/ `execute`（JSON 输入 → `ToolResult`）；`ToolResult` 含成功标识、结果内容、错误信息、是否可重试。骨架期已交付，第 20 节**一行未改**（FR-024）
- **内置 Tool 15 个（第 20 节交付 13 个，第 22 节补 `save_memory` / `recall_memory`）**：`read_file` / `write_file` / `list_dir`（路径白名单；`read_file` 自 2026-10-15 起支持 `offset`/`limit` 分页，默认上限 2000 行）、`shell`（argv 直传**不经 Shell 解释**、可执行文件白名单 + 超时，`timeoutSeconds` 可选、默认 30 秒、上限 600 秒）、`http_get` / `http_post`（域名白名单）、`notify`（出站推送，按名引用全局通知渠道注册表——**渠道从哪来**见下文「`notify-channel` 写入面」）；第 20 节另补六个高频基础工具——`edit_file`（要求 `oldText` 唯一匹配，不唯一则报错且文件一个字不动）、`grep`（按正则搜内容，二进制/非 UTF-8 文件跳过；支持 `offset` 翻页与 `contextLines` 上下文行）、`glob`（按通配找路径，支持 `offset` 翻页）、`ask_user`（向用户提问，渠道不支持时明确报错、不静默阻塞）、`web_search`（搜索引擎接缝，免 key 的 DuckDuckGo 默认 / 带 key 的 Tavily）、`web_fetch`（抓网页正文转纯文本，**跟随重定向且每一跳重过白名单**，上限 20000 字符）；`save_memory` / `recall_memory`（第 22 节，落 `oryxos-memory` 的 `builtin/MemoryTools`——**工具进注册表走的是同一处 `registerAnnotated`，注册表 / 注解管道 / 安全校验位 / 审计落库 / 按 Agent 过滤五处一行未改**，是第 20 节「加工具成本恒定」的第二次真实验证）
- **`oryxos tool list` 的默认输出是 15 个**：`builtin (15)` / `annotation (0)` / `mcp (0)`。后两组为空是**预期状态**——业务方还没写 `@Tool` Bean，且 `.oryxos/mcp_servers.yaml` 模板末尾是 `servers: []`
- **搜索接缝的两档与配置键**：`SearchProvider` 现有两档实现——`DuckDuckGoSearchProvider`（免 key，默认，打的是 Instant Answer「问答」接口，长尾查询常返回 0 条；**自 2026-10-15 起显式带 `User-Agent`**，原因见下条）与 `TavilySearchProvider`（带 key，真检索 API）。`CliAssemblyConfiguration.searchProvider()` 按 `oryxos.search.tavily-api-key`（占位 `${TAVILY_API_KEY:}`，**带空默认值**——搜索 key 可选，缺失不该让启动失败）有无分流；两档都是同一个接缝的实现，`WebSearchTools` / 校验位 / 注册表零改动。**key MUST NOT 进日志、异常消息或 `toString`**，有单测钉死。`api.tavily.com` 的域名白名单归第 24 节
- **免 key 搜索档的 UA 边界（2026-10-15 已修，非挂账）**：`api.duckduckgo.com` **按 UA 分流**——JDK 默认的 `Java-http-client/21.0.12` 被回 **HTTP 403**，浏览器前缀的 UA 被回 **HTTP 202**（同一代理、固定其它变量逐次实测；curl 同款对照）。所以第 20 节交付的 `DuckDuckGoSearchProvider` 在修之前**对任何一台机器都拿不到结果**，不是本机网络问题。修法是显式带 `User-Agent: Mozilla/5.0 (compatible; OryxOS/0.1; +https://github.com/wngbob/oryxos-wngbob)`（改的是**已交付类**，软门禁 ② 已报批）；单测 `search_sendsNonDefaultUserAgent` 钉死「非空且不以 `Java-http-client` 开头」。修后实测：`Alan Turing` → 5 条真结果；长尾查询仍 0 条——**这是 Instant Answer 是问答接口不是检索接口，与 UA 无关**，那部分由带 key 档解决。带 key 档（Tavily）实测不带 UA 也正常，未动
- **Plugin Tool 三档**：① 零代码 `AGENT.md` 目录 + 复用社区 MCP server（主推）② 任何语言自写 MCP server（`mcp_servers.yaml` 配置，`McpClientService` 连接、`McpToolAdapter` 包装成 `OryxTool`）③ `@Tool` 注解 Java Bean（`AnnotatedToolAdapter` 包装，进程内直调）
- **`ToolRegistry`**（`oryxos-tool`，实现 `oryxos-core` 的 `ToolCatalog` 接缝）：三种来源全部收敛成同一个 `OryxTool` 身份——内置与注解 Bean 经 `registerAnnotated(bean, source)` 先注册、MCP 后注册；`ToolRegistry` 里**没有任何 `@Tool` 扫描逻辑**，被包装的 Bean 由 `CliAssemblyConfiguration` 显式传入（工具来源是装配期事实，不是扫描期猜测）。工具重名时**保留先注册者 + WARN**（安全侧优先）；Agent 声明了但未注册的工具名 → WARN + 跳过，不抛异常；Profile 按 `tools` 字段过滤出的子集**恰好等于**声明列表（多一个少一个都是错，FR-011）
- **注解管道（`AnnotatedToolAdapter`）**：MUST NOT 用 Spring AI 的 `MethodToolCallback`（它把返回值字符串化 → `retryable` 丢失；把异常包成错误文本 → 失败落不进 `tool_invocations`）；异常一律剥掉 `InvocationTargetException` 外壳后上抛，交给 `ToolExecutor` 的既有失败审计路径。`@Tool(name = ...)` 显式钉住工具名（模型引用它的唯一凭据，一旦交付不得改），方法名与工具名解耦
- **MCP**：`McpServerConfigLoader` 读 `.oryxos/mcp_servers.yaml`（`env` 里的 `${ENV_VAR}` 解析失败则**跳过整个 server**，绝不把占位符当值传下去；日志只记变量名、MUST NOT 记它的值）；`McpClientService` 启动时逐个连接 + `listTools()` + 包装注册。**失联隔离**（FR-010 / SC-004）：任一 server 连不上/列举失败/包装失败只 WARN 并跳过它自己的工具，OryxOS 照常起、其余 server 照常注册。**默认配置下不连接任何 MCP server**（模板是空清单），这是预期状态
- **`InteractionTools` + `UserInteraction`**：`ask_user` 只负责「把问题递出去、把回答带回来」，能不能问由装配时注入的实现决定（CLI 注入 `ConsoleUserInteraction`；定时任务/无状态调用注入 `UnsupportedUserInteraction`，拿到回答为空即明确报错）
- **`SearchProvider` 接缝 + 两档实现（`DuckDuckGoSearchProvider` / `TavilySearchProvider`）**：核心阶段挂免 key 那档作默认（另见上文「搜索接缝的两档与配置键」「免 key 搜索档的 UA 边界」）；换别的搜索服务只加实现类，`WebSearchTools` 与调用方零改动
- **`NotifyChannelAdapter` 四档 + 路由接缝（2026-10-15 两轮补强）**：① `WebhookNotifyAdapter`（通用档，`{"content":"..."}`，**「2xx 即送达」**——对通用 webhook 接收端正确）② `FeishuNotifyAdapter`（方言档，覆盖飞书 + Lark；报文 `{"msg_type":"text","content":{"text":...}}`；**必须读响应体按业务码 fail-closed 判成败**，因为 IM 群机器人**用 HTTP 200 承载业务错误**；可选 `secret` 签名）③ `WeComNotifyAdapter`（方言档，企业微信；报文 `{"msgtype":"text","text":{"content":...}}`——**与飞书是镜像不是同一份**，`msgtype` **无下划线**、正文在 `text.content`；业务码字段是 `errcode` / `errmsg`，**只认这一个、不引入飞书的 `StatusCode` 兜底**；**没有签名机制**，故**不注入 `Clock`**、不读 `secret`）④ `RoutingNotifyChannelAdapter`（按 `type` 查**装配处显式给出**的映射表分派；**厂商无关，源码里 MUST NOT 出现任何厂商字面量**——由单测读源文件钉死，其厂商清单**已含 `wecom`**）。**fail-closed 判据的两处硬约束**：MUST 用 `has(<业务码字段>)` + `isIntegralNumber()`，**MUST NOT 写 `path(<字段>).asInt() != 0`**（Jackson 在字段**缺失**时也返回 0，那个写法把「缺失」判成成功，正好把要消灭的假成功重新引入）；**未知 / 拼错 / 空白的 `type` MUST 报错并列出已知类型、一次请求都不发**（MUST NOT 静默退回通用档——静默退回会把「拼错」变成「用错的方言发出去」，而错的方言在飞书那里正是 `HTTP 200` + 19002）。**两档方言档都 MUST NOT 单独声明成 `@Bean`**（否则 `NotifyChannelAdapter` 类型有两个候选，`NotifyTools` 注入歧义——`NotifyAssemblyTest` 起**真**上下文断言「恰好一个 Bean」钉死）。**两档方言档的 `send()` 骨架刻意重复、不抽公共基类**（抽 = 改动已交付且被测试钉死的 `FeishuNotifyAdapter`，属软门禁②事件；项目既有裁决同向——三处 `envLookup` 也刻意不合并）——**第三次出现（钉钉）才是抽的时机**。接口与调用方零改动：这是「接口先行」那条升级路径的**前两次**实际兑现
- **`notify-channel` 写入面（2026-10-15 补强②）**：`notify` 按名解析的那个全局注册表，写入由 CLI 三条轻命令 `add|list|remove` 提供（`--url-env <ENV_VAR>` 是 `--url '${ENV_VAR}'` 的糖，是**推荐路径**——凭证不进 shell 历史、不进库）。`NotifyChannelRegistry` 接缝**依旧只有读方法**：轻命令不启 Spring，写入走原生 JDBC；第 26 节加 REST CRUD 时才是往这个接缝上加方法的那一次。**`url` 含 token，输出纪律由测试钉死**——`add` 不回显值、`list` 的 `url` 列非占位一律打 `<已配置>`、`config` 列只打键名，任何路径（含异常消息）MUST NOT 出现 `url` / `config` 的值
- **Sandbox（第 24 节已交付）**：`Sandbox.enforce(SandboxAction{type, target})`，`ActionType` = `FILE_READ` / `FILE_WRITE` / `SHELL_COMMAND` / `HTTP_REQUEST`（**四个值，MUST NOT 增删**）；唯一实现 `WhitelistSandbox`（`io.oryxlabs.oryxos.tool.sandbox`）内部**三个**私有校验——`checkFilePath`（`Path::normalize` + `toAbsolutePath` 后按**路径组件**前缀比对，`../` 穿越与 `/workspace-evil` 这类同前缀兄弟目录都拦得住；`FILE_READ` / `FILE_WRITE` **同路由**到它）、`checkShellCommand`（只比 `trim()` 后的**首 token**，精确比对、**不折叠大小写**——那是文件名；空 token 即拒）、`checkHttpUrl`（解析 host、两侧折叠小写；`*.example.com` 按**点号边界**匹配，`evil-example.com` 被拒）。**三个配置键逐字是 snake_case、不带 `oryxos.` 前缀**（`file.allowed_paths` / `shell.allowed_commands` / `http.allowed_domains`，随包默认见 `oryxos-boot/src/main/resources/application.yaml`；`ToolAssemblyTest` 有字面量绑定用例钉死）。**两条语义 MUST 记住**：① **空表 = 全拒**，不是「不校验」（忘配的后果是「什么都跑不了」，可见可查，不是静默放行）② 它是**劝阻级**防线，防的是模型犯傻与提示注入，**防不住蓄意绕过**（命令拼接 / 软链接 / 编码绕过）——核心阶段 MUST NOT 用它跑完全不可信的代码、MUST NOT 对外做多租户。校验失败抛 `SandboxViolationException`，复用 `ToolExecutor` 既有失败审计路径，不新增审计逻辑；FileTools/ShellTools/HttpTools/WebSearchTools/NotifyTools 的每个涉外 `@Tool` 方法都先 `enforce` 再做 IO（`http_get` / `http_post` 共用 `request()` 里的同一个校验位；**这五个文件第 24 节一行未改**——`git diff` 为空是 SC-004 的判定方式）。**MUST NOT 用 Java `SecurityManager`**（章程原则 7）。**MUST NOT 暴露除 `enforce` 外的 public 方法**（反射测试钉死，防接口被第一档实现带偏）。~~SMTP `host:port` 校验~~：技术方案 §6.7 原写的「四个校验」已按第 24 节修订为三个——`ActionType` 没有 SMTP 路由、核心阶段没有消费者（`EmailNotifyAdapter` 归扩展阶段）。**MCP 工具的出站不过沙箱**（`McpToolAdapter` 零 `enforce` 调用），这是预期范围、不是缺陷
- **加工具的恒定成本（FR-016）**：安全校验位、审计落库、按 Agent 过滤这三处**加工具时一行都不用改**，自动生效。由 `ToolOnboardingCostTest` 钉死：它直接读这三个横切关注点的源文件，断言其中**不含**五个新工具名中的任何一个（git 状态无关的判定，替代原计划的 `git diff --stat`），并用反射做正向对照

## SQLite 核心表

七张表（`.oryxos/oryxos.db`，Spring Data JPA；`notify_channels` 的写入面走原生 JDBC，见下行）：

| 表 | 要点 |
| ---- | ------ |
| `sessions` | `session_id` 主键（channel+user+profile 联合生成）、`profile_name`、`channel`、`user_id`、`messages_json`（JSON 序列化对话历史）、`status`（active/archived）、三个时间戳 |
| `tool_invocations` | 每次 Tool 调用：tool_name、input_json、result_json、success、error_message、duration_ms |
| `llm_calls` | 每次 LLM 调用：provider、model、prompt/completion/total tokens、duration_ms |
| `scheduled_tasks` | 定时任务登记与运行状态（schedule_id 稳定主键、cron/zone/message、enabled/retired、run_count 等）；**定义源仍是 AGENT.md 的 `schedules`**，表只存状态 |
| `task_executions` | 定时任务每次执行历史（schedule_id、session_id、success、error_message、duration_ms），成功失败都记 |
| `memory_entries` | 第 22 节，SQLite 档的长期记忆（`id` / `scope` VARCHAR(16) / `content` TEXT / `created_at`），加 `idx_memory_scope` 索引（三处查询全部先按 `scope` 过滤）。**只有 `backend=sqlite` 时才被读写**——`markdown` 档的记忆在 `memory/MEMORY.md` 文件里，同一时刻只有一档在起作用。建表脚本在 `schema.sql` 里，`oryxos init` 每次无条件重跑（幂等），故老工作区升级后自动补上。**「表不存在」被包成带指引的消息**（提示先跑 `oryxos init`）——按消息文本 `no such table` 认这个错误，因为 SQLite JDBC 把「表不存在」和别的 SQL 错误包成同一类异常 |
| `notify_channels` | 全局通知渠道注册表（name 主键、type、url、config_json、description、时间戳）；**不属于 Profile / frontmatter**，`type` 取值由装配处映射表决定、**数据库层不做约束**。写入面 = CLI `notify-channel add\|remove`（轻命令，原生 JDBC，**绕过 Spring Data**）；读路径 = `JpaNotifyChannelRegistry`（读时解析 `${ENV_VAR}`）。**两张命令都自带幂等建表**——这张表是第 19 节才有的，老工作区的库文件里没有它，而 `.oryxos/` 是跨升级保留的用户数据 |

**工程提示**：SQLite `ALTER TABLE` 能力弱，首次建表可用 `hibernate.ddl-auto=update`，但**表结构演进不要依赖 update 自动迁移**——手动维护建表脚本或引入 Flyway/Liquibase。

## 核心数据模型

**Profile（= AGENT.md frontmatter，由 `AgentLoader.deriveProfile()` 派生，不手写独立 YAML）**：

```yaml
name: string                  # Agent 名（= 目录名）
description: string
identity:
  agent_name: string
  prompt: string              # 人格/系统提示词（或引用 SOUL.md）
provider:
  name: string                # deepseek / qwen / kimi 等
  model: string
  temperature: float          # 可选
tools: [string]               # 可用 Tool 子集
mcp_servers: [string]         # 引用的 MCP Server
channels: [{name, config}]
schedules: [{cron, zone, message}]   # 定时任务（钟推）
bootstrap: [string]           # Bootstrap 文件列表
settings:
  max_iterations: 10          # 最大 ReAct 迭代次数
  max_history_turns: 20       # 最大对话历史轮数
```

**Memory**：`.oryxos/memory/MEMORY.md` 一个 Markdown 文件，内部分 `## 核心记忆` / `## 归档记忆` 两区——核心区永远全量注入、不截断、不参与检索；归档区可截断（超 4000 字简单截断）、关键词检索只命中归档区。`USER.md` 是用户手写的初始设定（**OryxOS 只读**），`MEMORY.md` 是 Agent 通过 `save_memory` 写入的成长记录（OryxOS 读写），两者都进 system prompt。`MemoryService` 三层门面统一收口会话记忆 + 长期记忆，ReAct 循环只调它一个接口。**第 22 节已交付的是其中「长期记忆」这一层**：`MemoryService`（接口，`extends MemorySource`）+ `MemoryServiceImpl`（唯一实现，持一个 `LongTermMemoryStore`），后端三档可插拔、由 `oryxos.memory.backend` 选一。**门面刻意不持 `Session`**（FR-015）：长期记忆跨会话都在、会话历史只属于这一次对话，混在一个方法里返回会把两者焊死——**「长期记忆」与「会话历史」在 prompt 里是两条独立的 system 消息**，各有各的截断口径（`MemoryServiceImplTest` 钉死「长期记忆那一条里不出现对话内容」）。**记忆是全局单份、不按 Agent 隔离**：Markdown 档一个文件、SQLite 档一张表，`longTermMemory(Profile)` 的 `profile` 参数本节**不使用**（签名照旧是因为那是 core 侧既有接缝，本节只实现不修改）。**写入时机永远由 Agent 判断**（主动调 `save_memory`），核心阶段**不做自动提炼**——从对话里自动抽记忆需要另一套模型调用与冲突消解，抽错了用户根本不知道；Agent 主动调时用户在同一段对话里就能看见它记了什么，错了当场纠正

**通知渠道**：`notify_channels` 是 SQLite 全局注册表（name / type / url / config / description），**不属于 Profile 或 frontmatter**；Agent 在 `AGENT.md` 正文中用自然语言按名引用，`notify` Tool 调用时解析。`type` 的合法取值由装配处的映射表决定（当前 `webhook` / `feishu` / `lark` / `wecom` **四个**），**数据库层不做取值约束**；取值未知或拼错时在**路由处就报错并列出已知类型，一次请求都不发**（不再是对端以非 2xx 暴露——那条口径自 2026-10-15 起失效）。`config_json` 是「JSON 对象、值均为字符串」，**只有飞书档**多认一个可选键 `secret`（签名密钥，与 `url` 同级的凭证，MUST NOT 进日志/异常消息）；**企微档没有 `secret`**——企微群机器人没有签名机制，凭证就是 URL 里的 `key`，可选的是对端的 IP 白名单（客户端这边没有对应参数）。**写入面自 2026-10-15 起由 CLI 三条轻命令提供**（`oryxos notify-channel add|list|remove`，见「命令行工具」；演示不再需要手工 `sqlite3 INSERT`，向导见 `specs/004-notify-outbound/manual-test-guide.md` **Step 5**）。`${ENV_VAR}` 占位是**读时解析**——库里存字面量 `${FEISHU_HOOK_URL}`，解析渠道时才替换，**凭证永不落库**（db 被备份/误传也不泄，轮换只改环境变量）；变量缺失**抛异常且消息只带变量名**。第 26 节仍归 `/api/v1/notify-channels` 的 REST CRUD 与管理台

## 工作区目录

`oryxos init` 创建 `.oryxos/`（幂等，已存在的目录和文件一律不覆盖）：

```text
.oryxos/
├── agents/            # 每个子目录 = 一个 Agent（AGENT.md + skills/软连接 + scripts/ + REFERENCE.md）
├── skills/            # 公共 Skill 实体库（SKILL.md + 可选附属资源）
├── memory/
│   └── MEMORY.md      # 长期记忆
├── mcp_servers.yaml   # MCP server 配置
├── sessions/          # Session 数据
├── logs/              # 结构化日志
├── output/            # Agent 产出物
├── AGENTS.md          # Bootstrap：项目级 agent 行为说明
├── SOUL.md            # Bootstrap：默认 agent 人格定义
├── USER.md            # Bootstrap：用户偏好
└── oryxos.db          # SQLite
```

三个 Bootstrap 文件在 Agent 启动时注入 system prompt；`ContextLoader` 每次组装 prompt 重新读取，不缓存，改完下轮即生效。

## 核心阶段 Maven 模块（9 个）

| 模块 | 职责 |
| ------ | ------ |
| `oryxos-core` | 核心抽象与引擎：`OryxTool`、`Session`、`Profile`、`AgentLoader`（deriveProfile）、`ContextLoader`、`ReActLoop`、`PromptBuilder`、`ToolExecutor`、`AgentService`、`AgentScheduler` |
| `oryxos-provider` | 能力一：`ProviderService`、Function Calling 适配、provider name 显式映射 |
| `oryxos-memory` | 能力三：`MemoryService` 三层门面（`MemoryServiceImpl`）+ `LongTermMemoryStore` **三档实现**（`MarkdownMemoryStore` / `SqliteMemoryStore` / `Mem0MemoryStore`）+ 测试替身 `InMemoryMemoryStore` + `builtin/MemoryTools`（save_memory / recall_memory）；**第 22 节新增一条对 `oryxos-storage` 的依赖边**（消费 `MemoryEntryRepository`），方向与 `oryxos-tool` 消费 `NotifyChannelRepository` 一致，storage 不反向依赖 memory、不成环；**MUST NOT 依赖 `oryxos-tool`** |
| `oryxos-tool` | 能力四（三合一）：内置 Tool（File/Shell/Http/Notify + 第 20 节的 Edit/Grep/Glob/AskUser/WebSearch/WebFetch）、`ToolRegistry`（实现 core 的 `ToolCatalog`）、`AnnotatedToolAdapter`、`McpClientService`、`McpToolAdapter`、`McpServerConfigLoader`、`UserInteraction` + 两档实现、`SearchProvider` + 两档实现（免 key 的 DuckDuckGo / 带 key 的 Tavily）、`Sandbox` 接口 + `WhitelistSandbox`（第 24 节） |
| `oryxos-channel-cli` | CLI Channel：`CliChannel`、`oryxos chat` |
| `oryxos-web` | 能力五：`WebServer`、6 个 `ApiController`、`GlobalExceptionHandler`、OpenAPI |
| `oryxos-storage` | SQLite 持久化：`SessionRepository`、审计表 Repository |
| `oryxos-cli` | Picocli 主入口、**15** 个子命令、`ConfigLoader`；`WorkspaceDb`（轻命令的原生 JDBC 读写收口——`notify-channel` 的写入走这里） |
| `oryxos-boot` | Spring Boot 启动模块、自动配置、依赖聚合 |

全量 14 个模块（+ `oryxos-persona` / `oryxos-knowledge` / `oryxos-channel-feishu|wecom|dingtalk`）见技术方案 ch10，属扩展演进，核心阶段不建。

**依赖方向（依赖倒置）**：`oryxos-boot` 聚合其余 8 个；`oryxos-cli` 依赖各能力模块；`oryxos-tool` / `oryxos-memory` 依赖 `oryxos-core`（实现 core 的 `OryxTool` 契约）；**`oryxos-core` 不反向依赖任何能力模块**——契约在 core、实现在各模块。例外是 `Sandbox` / `NotifyChannelAdapter`，契约放 `oryxos-tool`：内置 Tool 在自己的 `execute` 开头调 `sandbox.enforce(...)`，core 全程不引用 Sandbox，故不成环。**第 22 节新增一条边**：`oryxos-memory` → `oryxos-storage`（消费 `MemoryEntryRepository`）；`oryxos-storage` 不反向依赖任何能力模块。**`oryxos-memory` MUST NOT 依赖 `oryxos-tool`**——这既是 FR-021 的连带，也是「记忆文件 IO 不过沙箱」这条裁决能成立的结构前提（`Sandbox` 定义在 `oryxos-tool`）

## 实施路线（Spec-Kit + user story）

主体开发用 Spec-Kit，增量开发切手动提示词。5 个 user story 按依赖序推进：

```text
US-1 对接 LLM → US-2 ReAct 循环 → ┌─ US-3 Memory ────┐ → US-5 Web Service
                                   └─ US-4 Plugin Tool ┘
```

- Spec-Kit 命令是 **dash 风格**（`/speckit-specify`、`/speckit-plan`、`/speckit-tasks`、`/speckit-implement`、`/speckit-analyze`），指南里的 dot 风格是旧命名
- **锁定 Spec-Kit v1.0.10**，主体开发期间不升级
- **每个 user story 结束必跑 `/speckit-analyze`**，不能省
- 跨 task 上下文丢失时，让 AI 重读 `spec.md` + `plan.md` + 最近代码
- 每个 user story 完成打 git commit，方便回退

## AI agent 最易跑偏的点（重点自查）

| 错误写法 | 正确做法 |
| --------- | --------- |
| 启用 Spring AI 自动 tool 执行 | 禁用，tool 调度归 `ToolExecutor`（原则 4） |
| 按类型扫描 `ChatModel` Bean 区分 Provider | 显式 provider name 映射表（原则 10） |
| 把 Tool 拆成 builtin/skill/mcp 多模块 | 合并为一个 `oryxos-tool`（原则 12） |
| 把 `AGENT.md`/`AgentLoader` 当成 Tool | 归 `ContextLoader`，正文注入 prompt（原则 11） |
| 审计只写日志 | `tool_invocations`/`llm_calls` day one 落库（原则 6） |
| 用 Java SecurityManager 做沙箱 | JDK 21 已不可用；用 `Sandbox` 接口 + `WhitelistSandbox`（原则 7） |
| 用了非 JDK 21 特性 | 强制 JDK 21 |
| 只跑 `--help` / `--version` 就宣称「重命令能用」 | 重命令（`chat` / `serve` / `gateway`）MUST 真跑一次——`--help` 不启动 Spring 上下文，扫不出 Bean 注册撞车（第 18 节实测 `BeanDefinitionOverrideException`） |

## 关键术语速查

- **Agent = 一个目录** `.oryxos/agents/<name>/`：`AGENT.md`（frontmatter=运行配置，正文=任务指令）+ 可选 `skills/` 软连接、`scripts/`、`REFERENCE.md`；Agent 是配置出来的，不是写代码写出来的
- **Profile**：底座内部的运行时宿主配置，由 `AgentLoader.deriveProfile()` 从 `AGENT.md` frontmatter 派生，不是单独手写的 YAML
- **底座 vs Agent 两层**：底座（Provider/ReAct/Tool/Memory/Sandbox/Web）所有 Agent 共享；Agent 只消费底座能力
- **Memory 三层门面**：`MemoryService` 统一收口会话记忆（SessionManager/SQLite）+ 长期记忆（MEMORY.md），情景记忆扩展阶段；长期记忆后端三档可插拔（Markdown 默认 / SQLite / Mem0）
- **三触发源**：CLI、Web Service（人推）+ `AgentScheduler` cron（钟推），同一 `AgentService` 链路
- **入站 Channel ≠ 出站 Notify**：`ChannelAdapter` 解决"消息怎么进来"，`NotifyChannelAdapter`/`notify` Tool 解决"结果推到哪"（企业 IM webhook）；同一个群可能既是某 Agent 的入站、又是另一 Agent 的出站
- **Bootstrap**：`AGENTS.md` / `SOUL.md` / `USER.md` 三个文件，启动时注入 system prompt
