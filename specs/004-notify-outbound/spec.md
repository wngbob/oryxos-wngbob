# Feature Specification: Notify 出站推送 —— 给 Agent 补上「结果主动送出去」的出口

**Feature Directory**: `specs/004-notify-outbound`

**Git Branch**: `004-lesson19-notify-outbound`

**Created**: 2026-10-14

**Status**: Draft

**Input**: User description: "第19节需求：出站通知推送——给 Agent 补上「结果主动送出去」的出口"（课件：`docs/class/第19节：Notify 模块 原理解析、实现与代码讲解.md`；章节依据 `docs/TechnicalSolution.md` §6.8 通知推送、§9.2 `notify_channels` 表）

## 范围说明

前 18 节解决的都是「人推」：CLI 能一问一答，ReAct 能跑循环，有人发起、有人等响应，处理完把响应返回给发起者即可。一旦触发源变成「到点自动」（定时查天气、每天汇总科技新闻），这条链路就断了——没有人在另一端等着接收响应，Agent 必须自己决定把结果送到哪、怎么送。本节补的是**对称的另一半：出站通知**。没有它，第 25 节的定时模块与第 31 节的日报 Agent 到点跑完一整套 ReAct 循环，结果只能烂在会话里没人看到。

本节也是「接口先行」这个设计习惯在本课的第一次亮相：先抽一个**不携带任何具体渠道细节**的接口，表达「把一条内容送到某个通知目标」这个意图，核心阶段只在接口后面挂一档实现（通用 webhook），以后加新渠道只新增实现类，不改接口、不改调用方——第 23/24 节讲 Sandbox 时会是同一套手法的第二次。

两个最容易写歪的地方，本节就钉死：**其一，推送目标不进对话。** webhook 地址是运行时配置，不是模型需要知道的信息；渠道是全局注册表、按名引用，模型只传「内容」和可选的「渠道名」。**其二，出站不能绕过白名单。** `notify` 发出去的同样是一次 HTTP 请求，跟 `http_post` 理应过同一层域名白名单，不能因为方向是「往外推」就跳过去——校验与发送的先后顺序反了就是漏洞。

**本节交付的是「库代码 + 单测」，不是可运行的功能。** 通知工具的真实接线有三个依赖在后面的课：`@Tool` 的注册机制与 `ToolRegistry` 在第 20 节、`Sandbox.enforce` 的实现（`WhitelistSandbox`）在第 23/24 节。课件「实现顺序说明」已明写「`NotifyTools` 这个任务的完成时点在 24 节之后」。因此本节**不动任何 Spring 装配**——尤其不给通知工具造一个「全放行」的占位沙箱（那等于把这道墙直接拆了），通知工具与适配器以库代码交付、用替身（mock）单测，装配点归第 24 节。

**冲突仲裁记录（本规约据此撰写，后续 plan / tasks 不得偏离）**：

1. **`notify_channels` 归 SQLite 全局注册表，不是 Profile frontmatter（上游已有裁决，本节落地执行）**：课件 §二第四点「具体推到哪，配置在 Profile」与 §三的 Profile 示例（`notify_channels: - type: webhook / url: ${TEAM_WEBHOOK_URL}`）都把它写成 Agent 的配置字段——**旧口径**。三处权威文档一致反对：`CLAUDE.md`「通知渠道」条（「`notify_channels` 是 SQLite 全局注册表……**不属于 Profile 或 frontmatter**」）、`docs/TechnicalSolution.md` §6.8（「`AGENT.md` frontmatter 不包含 `notify_channels` 字段」）、`specs/000-runtime-kernel/plan.md` 仲裁项 4。而且**代码里已经把它钉死**：第 16 节交付的 `ProfileLoader.FORBIDDEN_KEYS = Set.of("skills", "notify_channels")` 会让 `AGENT.md` frontmatter 一旦出现该键就抛 `ProfileLoadException`。裁决：按全局注册表落地；课件的 Profile 示例不采用，渠道改由「注册名 → 渠道」的全局注册表解析。

2. **渠道的写入路径本节不做——A6 的 `notify_channels.yaml` 启动导入判为旧口径**：`specs/000-runtime-kernel/data-model.md` A6 末段写「**定义源**：`.oryxos/notify_channels.yaml`；启动时以文件为准全量重建」，而 `docs/TechnicalSolution.md` §6.8 写「通知渠道通过 Web 管理台或 `/api/v1/notify-channels` 做 CRUD，持久化在 SQLite 的 `notify_channels` 表」，且 §9.3「放文件系统不放 SQLite」的清单里**没有**它。按 `CLAUDE.md`「文档间冲突时，以最新技术方案为准」，A6 的 yaml 定义源是旧口径；第 18 节仲裁 7 已经据此把 `notify_channels.yaml` 从 `oryxos init` 的工作区里去掉，并明写「`notify_channels` 归第 19 节的 SQLite 全局注册表……契约落地时再补」。裁决：**本节不引入 yaml 导入路径**，表的写入路径（CRUD 接口与工作区模板）归**第 26 节**；本节交付「表 + 存取 + 按名解析（只读）」。连带影响如实记录：A6 末段「`url` 支持 `${ENV_VAR}` 占位，加载时解析」在本节**没有落地场景**（无写入方、无调用方），随第 26 节的写入路径一并定——本节 MUST NOT 假装实现了它。

3. **课件 `profileContext.resolveNotifyChannel(channel)` 落不了地——渠道解析改由通知工具自持接缝（有意偏离课件代码，2026-10-14 已报批）**：课件 `NotifyTools` 的写法是 `private final ProfileContext profileContext;` + `profileContext.resolveNotifyChannel(channel)`。两处硬伤：① `NotifyTarget` 的契约在 `oryxos-tool`，而 `ProfileContext` 在 `oryxos-core`——给 core 的类加一个返回 tool 模块类型的方法，等于让 core 反向依赖能力模块，违反章程「契约在 core、实现在各模块」与「`oryxos-core` 不反向依赖任何能力模块」（既有例外只有 `Sandbox` / `NotifyChannelAdapter` 两组契约，它们同样**不被 core 引用**，故不成环）；② 第 17 节交付的 `ProfileContext` 是 `final` 类 + **私有构造 + 全静态方法**（`set` / `current` / `clear`），课件把它当注入字段用本身就编译不过，片段是示意。而且从语义上看根本不需要它：渠道是**全局**注册表、按名引用，解析「哪个渠道」不需要知道「当前是哪个 Agent」。裁决：**不动 `ProfileContext` 一行、不动 `Profile` 一行**；`oryxos-tool` 的 `notify` 包新增一个渠道注册表接缝（与 `Sandbox` 同处一个模块、同一套「契约在 tool、core 不引用」的既有例外），通知工具注入它并自己解析 `channel`。

4. **本节零装配改动——通知工具与适配器不进 Spring 容器（装配归第 24 节，2026-10-14 已报批）**：课件给 `WebhookNotifyAdapter` 挂了 `@Component`，与第 18 节既定的装配纪律冲突（非 boot 模块不带 Spring 装配注解，Bean 一律在 `CliAssemblyConfiguration` 里显式声明）。更硬的约束是**根本没有可注入的沙箱**：`NotifyTools` 的构造依赖 `Sandbox`，而 `Sandbox` 的唯一实现 `WhitelistSandbox` 归第 23/24 节，本节没有任何 `Sandbox` Bean。裁决：本节**不改 `CliAssemblyConfiguration` 一行**，两个类只作为库代码交付并单测（`Sandbox` / 适配器 / 注册表用替身）；`RestClient`、`WebhookNotifyAdapter`、渠道注册表实现、`NotifyTools` 的 Bean 声明一并归第 24 节。MUST NOT 为了「让容器起得来」造一个全放行的占位沙箱——那是把本节最该守住的那道墙拆掉。

5. **通知工具保留课件里的 `@Tool` 注解，`oryxos-tool` 新增 `spring-ai-model` 依赖**：课件 `NotifyTools.notify` 带 `@Tool(description = "把一条消息推送到当前 Agent 配置好的通知渠道")`。`@Tool` 的**注册机制**（谁扫描、谁包装成统一工具抽象）归第 20 节，课件「实现顺序说明」明写这一点；注解本身在本节是惰性的（没有任何 `ToolRegistry` 消费它）。裁决：**保留注解**，理由有二——它是 `CLAUDE.md` 原则「Spring AI 只用一半：只用 Provider 抽象 + 协议转换 + `@Tool` schema 生成」的落点，且提前加上可免第 20 节回头改本节交付物。落地物：`oryxos-tool/pom.xml` 显式新增 `org.springframework.ai:spring-ai-model`（已核实该坐标提供 `org.springframework.ai.tool.annotation.Tool`，版本 `1.1.8` 由根 `spring-ai-bom` 托管，**不写版本号**；`oryxos-provider` 已在用同一坐标）——**不引入新的第三方工件、不引入新的版本号**。

6. **`WebhookNotifyAdapter` 用 `RestClient`（随课件），`oryxos-tool` 新增 `spring-web` 依赖**：课件实现用 `RestClient` + `MediaType.APPLICATION_JSON` 发 POST，本节的落地也照此。已实测：`oryxos-tool` 现有 classpath **没有** `spring-web`（只有 `oryxos-core` + `spring-boot-starter` 带来的 spring-core/context/aop/expression），故需显式声明；而 `org.springframework:spring-web:6.2.19` 由 `spring-boot-dependencies` BOM 管（不写版本号），**且它已经在发行物 classpath 上**——`oryxos-cli` 与 `oryxos-boot` 的依赖树里都能看到它（经 `oryxos-provider` → spring-ai 传递进来）。因此本节只是把一条**隐式可用的边**在 `oryxos-tool` 里显式声明出来，**发行物里不新增任何工件**。裁决：照课件用 `RestClient`。

7. **测试依赖用 `mockwebserver3:5.5.0`（okhttp 5），不用课件写的 `mockwebserver:4.12.0`（已报批）**：课件 harness 说「用 MockWebServer 在本地起一个假 webhook（不算外网依赖，仍是单测层）」。已实测：`com.squareup.okhttp3:mockwebserver:4.12.0` 的 `MockWebServer` 类**继承 `org.junit.rules.ExternalResource`**——硬耦合 JUnit 4 且不可 exclude，引入它会污染纯 JUnit 5 的工程；`com.squareup.okhttp3:mockwebserver3:5.5.0` 只 `implements Closeable`，依赖 kotlin-stdlib + okhttp-jvm（均已在本地仓库）。裁决：用 `mockwebserver3:5.5.0`，**只在 `oryxos-tool` 的 test scope**。

8. **渠道注册表接缝是本节新增的对外概念（软门禁 ① 报备项）**：课件的「本节交付物」清单里没有「注册表接缝」这个类型，但用户已裁决「本节建 `notify_channels` 表 + 注册表解析（CRUD 留第 26 节）」。裁决落地为：`oryxos-tool` 的 `notify` 包新增一个渠道注册表接缝（按注册名解析出通知目标；缺省取默认渠道；解析不到时明确报错），其持久化实现消费 `oryxos-storage` 的 `NotifyChannelRepository`。**此处报备**：这是超出课件交付物清单的 public 类型，理由是课件把解析挂在 `ProfileContext` 上的写法按仲裁 3 不可行，解析能力必须有个落点。名称与形状在 plan 阶段定稿。

### 明确不做（边界）

核心阶段不碰以下各项，本节一律不做：

- 不逐家对接企业微信 / 飞书 / 钉钉的**专用 API**（签名算法、AccessToken 刷新等认证细节）——只做通用 webhook 一档；
- 不做企业微信**应用消息**的 AccessToken 刷新（注意与「群机器人 webhook」区分，后者才是本节这档）；
- 不做**邮件（SMTP）、短信**这类非 HTTP 或需签名认证的渠道（扩展阶段的专用实现）；
- 不做**富文本卡片**等结构化消息（按技术方案 §6.8 的边界走 Plugin Tool 方式二 / MCP）；
- 不做渠道的**管理界面与 CRUD 接口**（第 26 节）；
- 不做渠道定义源的 **yaml 导入**（仲裁 2）；
- **不实现沙箱白名单本体**（第 23/24 节）——本节只按接口调用的方式把校验接进去；
- 不做通知渠道配置的**加密存储**（扩展阶段）；
- 不做**定时触发**本身（第 25 / 28 节）——本节只把「送出去」这件事做好；
- 不把通知工具**接进 Spring 容器**（仲裁 4，归第 24 节）；
- 不做**重试、退避、投递回执**（技术方案未定义，无调用方）。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 说一声，消息真的进了群 (Priority: P1)

业务方在群里建了一个机器人、把 webhook 地址配成了渠道。开发者在对话里说「把测试消息推送一下」，Agent 调用通知工具，消息真的出现在群里。如果 webhook 那边返回 5xx，或者压根连不上，这次推送**明确失败**——Agent 不会以为已经发出去了。

**Why this priority**: 这是本节唯一有真实外部效果的部分，也是后面定时模块与日报 Agent 的唯一出口。它自带一条最容易被写歪的边界：**失败不能静默**。适配器里一句 `try { ... } catch (Exception e) { log.warn(...) }` 就能让整条链路「永远成功」——而用户会以为消息已经发到群里了。

**Independent Test**: 在本地起一个假 webhook 服务（收到请求就记下来），对一个指向它的通知目标发一条消息，断言收到的 POST 请求体里带 `content`、且 URL 来自通知目标配置而不是硬编码；再让假 webhook 返回 5xx，断言异常向上抛。全程不访问外网。

**Acceptance Scenarios**:

1. **Given** 一个指向本地假 webhook 的通知目标，**When** 推送一条内容，**Then** 假 webhook 收到一次 POST，请求体里带该内容，请求的目标地址来自通知目标的配置。
2. **Given** 同上，**When** 推送内容里含中文、换行与引号，**Then** 收到的内容逐字一致。
3. **Given** 假 webhook 返回 5xx，**When** 推送，**Then** 调用方收到异常（向上抛），**不是**静默返回成功。
4. **Given** 假 webhook 的地址不可达（连接被拒），**When** 推送，**Then** 同样向上抛异常。

---

### User Story 2 - 推到哪是配置，不是对话内容；出站不能绕过白名单 (Priority: P1)

模型不需要知道 webhook 地址，它只需要说「把这段话发出去」——发到哪由渠道配置决定。渠道在全局注册表里有个注册名，模型按名引用；名字不传时用默认渠道。渠道没配，推送必须**明确报错**。而且无论推到哪，发送之前都必须先过域名白名单校验：通知是「往外推」的 HTTP 请求，跟 `http_post` 一样不能例外。

**Why this priority**: 这两条合起来是本节的安全面与可用面。**地址不进对话**决定了渠道可以随时增删改而不动 Agent（这是它相对「每个 Skill 自己写 http_post」的核心价值）；**校验先于发送**是一条顺序不变量——顺序反了就是一个可被利用的绕过口子，而顺序错误在单请求测试里不会自己暴露，必须专门钉死。

**Independent Test**: 用替身注册表、替身沙箱、替身适配器驱动通知工具，断言：不传渠道名时取默认渠道；渠道未配置或名字解析不到时报明确错误；并且 `enforce` 先于 `send` 被调用（顺序断言）。

**Acceptance Scenarios**:

1. **Given** 一个配好默认渠道的注册表，**When** 只传内容、不传渠道名，**Then** 消息被送到默认渠道。
2. **Given** 渠道名不传或传空，**When** 推送，**Then** 取默认渠道（不是报错）。
3. **Given** 一条渠道都没配，**When** 推送，**Then** 明确报错（不是静默失败）。
4. **Given** 传了一个注册表里不存在的渠道名，**When** 推送，**Then** 明确报错并指出该名字。
5. **Given** 任一次成功的推送，**When** 观察调用顺序，**Then** 白名单校验**先于**发送发生。
6. **Given** 白名单校验不通过，**When** 推送，**Then** 请求根本没有发出去（异常在发送之前抛出）。
7. **Given** 一次推送，**When** 检查送往模型的内容与日志，**Then** 里面没有渠道地址。

---

### User Story 3 - 换渠道只加实现类，不动接口和调用方 (Priority: P2)

核心阶段只做通用 webhook 一档。以后要接飞书专用的消息格式、或者钉钉的加签认证，做法是**新增一个实现类**，`send(通知目标, 内容)` 这个签名一个字都不用改，调用它的通知工具也一个字不用改。同一个企业微信群，可能既是某个 Agent 的入站渠道、又是另一个 Agent 的出站通知目标——两者语义方向相反，分开建模，不合并成一个抽象。

**Why this priority**: 这是「接口先行」在本课的第一次兑现，也是本节的长期价值所在——它决定第 26 节和扩展阶段加渠道的成本是「加一个类」还是「改一片调用方」。它不阻塞 US-1 / US-2，故排在 P2。

**Independent Test**: 契约核对 + 思维练习——接口签名里不出现任何一档实现特有的词（无「企业微信」「飞书」「webhook」字样）；通知工具的依赖只到接口，不出现任何具体实现类型；以一个测试替身实现类接入，断言调用方零改动。

**Acceptance Scenarios**:

1. **Given** 通知接口的签名，**When** 逐字检查，**Then** 只表达「把一条内容送到某个通知目标」，不出现任何具体渠道的专有词。
2. **Given** 通知工具，**When** 检查它的依赖，**Then** 它只依赖通知接口与渠道注册表接缝，不依赖任何具体渠道实现。
3. **Given** 换一个渠道实现（测试替身），**When** 接入，**Then** 通知工具与接口签名零改动。
4. **Given** 入站渠道抽象与出站通知抽象，**When** 检查，**Then** 两者是分开的两个类型，不互相继承、不合并。

---

### Edge Cases

- **假 webhook 返回 4xx**：同样向上抛（非 2xx 即失败），不静默吞。
- **假 webhook 返回 2xx 但响应体里写着业务错误**：按 HTTP 语义视为送达成功——webhook 的送达判据是状态码，本节不去解释对端响应体的业务含义。
- **对端不可达（连接被拒 / DNS 解析失败 / 超时）**：向上抛，不重试（本节不做重试，见边界）。
- **注册表为空（一条渠道都没配）**：等同「渠道未配置」，明确报错。
- **渠道名解析不到**：明确报错并指出该名字，MUST NOT 退回默认渠道（静默退回会让「名字打错」变成「发到了别的群」）。
- **渠道行的 `url` 为空或空白**：明确报错，MUST NOT 把请求发到空地址。
- **`channel` 传空字符串或纯空白**：等同不传，取默认渠道。
- **白名单校验失败**：抛沙箱违规异常，**请求一次都不发**。
- **渠道地址含凭证（token 在 URL 里）**：日志、报错与命令输出里 MUST NOT 出现完整地址。
- **本节运行期注册表恒为空**：因为没有写入路径（仲裁 2），「渠道未配置 → 明确报错」是**预期**行为，不是缺陷。

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 MUST 提供一个**不携带任何具体渠道细节**的出站通知接口，表达「把一条内容送到某个通知目标」这个意图；接口签名里 MUST NOT 出现「企业微信」「飞书」「webhook」这类某一档实现特有的词。
- **FR-002**: 通知目标 MUST 由「渠道类型 + 一份类型相关配置」两部分表达；具体是 webhook 地址还是别的认证信息，由实现类自己解释——接口签名里 MUST NOT 出现某一档实现特有的字段。
- **FR-003**: 核心阶段 MUST 只提供**一档通用 webhook 实现**：给一个地址发一次 HTTP POST，把内容按 JSON 送出。MUST NOT 逐家对接专用 API（签名算法、AccessToken 刷新等认证细节核心阶段不做）。
- **FR-004**: 系统 MUST 提供**内置的通知工具**，接受「内容」与**可选的**「渠道名」两个参数；渠道名不传时取默认渠道。
- **FR-005**: 通知渠道 MUST 是**全局注册表**，MUST NOT 属于任何 Agent 的配置：渠道按注册名引用，具体 webhook 地址 MUST NOT 进入对话内容，增删改渠道 MUST NOT 需要改 Agent。
- **FR-006**: 渠道未配置（或渠道名解析不到）时，通知工具 MUST 明确报错，MUST NOT 静默失败——Agent 不能以为已经发出去了。
- **FR-007**: 发送前 MUST 先过沙箱白名单校验，且**顺序不可颠倒**：通知是「往外推」的 HTTP 请求，MUST NOT 因为方向不同就绕过白名单。校验不通过时 MUST NOT 发出任何请求。
- **FR-008**: 通知工具 MUST 走与其他工具**完全相同**的审计路径（成功失败都落库），MUST NOT 新增审计逻辑、MUST NOT 自建第二条写入路径。
- **FR-009**: 通知渠道 MUST 持久化在 SQLite 全局注册表，字段含注册名、类型、地址、类型相关配置与可选描述。
- **FR-010**: 发送失败（webhook 返回 5xx / 4xx、连接失败）MUST 异常向上抛，MUST NOT 静默吞掉。MUST NOT 引入重试或退避。
- **FR-011**: 默认渠道的口径 MUST 是「注册表里的第一个渠道」，且「第一个」MUST 有**确定性**口径：按注册名升序的第一个（渠道表没有排序或默认标志列，`specs/000-runtime-kernel/data-model.md` A6 也不允许新增列，故取一个不依赖插入顺序的确定性口径）。渠道名不传、传空串或纯空白 MUST 等同不传。渠道名解析不到时 MUST 报错，MUST NOT 静默退回默认渠道。
- **FR-012**: `notify_channels` 表的**列名与语义逐字照** `specs/000-runtime-kernel/data-model.md` A6 抄：`name`（主键）、`type`（核心阶段仅 `webhook`）、`url`、`config_json`（类型相关键值）、`description`。MUST NOT 新增列。表 MUST 由**手工建表脚本**创建，MUST NOT 依赖框架自动迁移。
- **FR-013**: 从注册表行到通知目标的映射 MUST 是：渠道类型 ← `type` 列；类型相关配置 MUST 至少包含 `url` 键（取值来自 `url` 列）与 `config_json` 里的键值。实现类的取用方式（从配置里按 `url` 键取地址）MUST 与契约逐字一致。
- **FR-014**: 本节 MUST NOT 引入渠道的**写入路径**（yaml 导入、CRUD 接口、工作区模板文件）；写入路径归第 26 节。本节交付的解析 MUST 是只读的。
- **FR-015**: 本节 MUST NOT 修改 `Profile` / `ProfileLoader` / `ProfileContext` 的既有签名与行为；`notify_channels` MUST NOT 出现在 `AGENT.md` frontmatter（既有禁令保持有效）。
- **FR-016**: 本节 MUST NOT 新增任何 Spring 装配：通知工具、适配器与渠道注册表实现 MUST 以库代码交付（测试用替身驱动），容器装配点归第 24 节。MUST NOT 造「全放行」的占位沙箱。
- **FR-017**: 通知工具的对外签名 MUST 只依赖接口与注册表接缝，MUST NOT 依赖任何具体渠道实现类型——换渠道实现时调用方零改动。
- **FR-018**: 入站渠道与出站通知 MUST 分开建模，MUST NOT 合并成一个抽象、MUST NOT 互相继承。
- **FR-019**: 渠道地址（含 URL 里的凭证）MUST NOT 出现在对话内容、结构化日志、报错信息与命令输出中；凭证走环境变量占位，MUST NOT 落明文。
- **FR-020**: 本节 MUST NOT 引入响应式编程或自建线程池；执行模型为同步阻塞 + 虚拟线程。
- **FR-021**: 一次推送 MUST 有**有界的等待上限**：对端不响应时 MUST 在有限时间内失败并向上抛，MUST NOT 无限期挂住调用方（第 25 节的定时任务不能被一次推送永久卡住）。等待上限的具体取值见 Assumptions。

### Key Entities

- **通知渠道（`notify_channels` 表）**：渠道的持久化形态，一张**全局**注册表（不属于任何 Agent）。字段：注册名（主键）、类型、地址、类型相关配置、描述。**表由手工建表脚本创建**，运行期在本节**恒为空**（无写入路径，仲裁 2）。
- **通知目标（`NotifyTarget`）**：一次推送的落点。渠道类型 + 一份类型相关配置。骨架期已交付，本节**不改**其签名。
- **通知适配器（`NotifyChannelAdapter`）**：出站推送的抽象，只有一个方法——把一条内容送到一个通知目标。骨架期已交付，本节**不改**其签名；核心阶段唯一实现是通用 webhook 一档。
- **渠道注册表接缝**：按注册名解析出通知目标；缺省取默认渠道；解析不到时明确报错。本节新增（软门禁 ① 报备项，见仲裁 8）。它的持久化实现消费通知渠道表的存取接口。
- **通知工具**：模型可调用的入口，参数为「内容 + 可选渠道名」。它只依赖通知适配器接口、渠道注册表接缝与沙箱契约。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 假 webhook 收到的 POST 请求体里带所发内容的比例 **100%**；请求目标地址来自通知目标配置的比例 **100%**（硬编码地址出现次数 **0**）。
- **SC-002**: 对端返回非 2xx 或不可达时，异常向上抛出的比例 **100%**（静默吞掉次数 **0**）。
- **SC-003**: 渠道未配置时明确报错的比例 **100%**（静默失败次数 **0**）。
- **SC-004**: 渠道名缺省时取默认渠道的比例 **100%**；渠道名解析不到时报错的比例 **100%**（静默退回默认渠道次数 **0**）。
- **SC-005**: 白名单校验先于发送被调用的比例 **100%**（顺序颠倒次数 **0**）；校验不通过时发出请求的次数 **0**。
- **SC-006**: 渠道地址与其中凭证在日志、报错、命令输出中的出现次数 **0**。
- **SC-007**: 通知工具的依赖里具体渠道实现类型的出现次数 **0**（只依赖接口）。
- **SC-008**: 本节全部自动化测试在 `mvn test` 下全绿，且**不访问网络**（假 webhook 起在本地）。
- **SC-009**: 前序节（第 16 / 17 / 18 节）全部测试回归绿。
- **SC-010**: 对端不响应时，推送在等待上限内失败并向上抛的比例 **100%**（无限期挂住调用方的次数 **0**）。

## Assumptions

- **依赖第 16 / 17 / 18 节的成果**：`Profile` 体系与 `ProfileLoader`（含 frontmatter 禁令）、ReAct 引擎四件套与四个 core 接缝、`tool_invocations` / `llm_calls` 两张审计表与写入路径、CLI 与会话持久化。
- **骨架期已交付的两份契约本节不改**：`NotifyChannelAdapter` 与 `NotifyTarget` 的签名与课件逐字一致，本节只新增实现类与调用方。
- **`Sandbox` 契约已交付、实现未就位**：`Sandbox` / `SandboxAction` / `ActionType`（含 `HTTP_REQUEST`）/ `SandboxViolationException` 均在 `oryxos-tool` 的 `sandbox` 包，本节按接口调用把校验接进去，白名单本体归第 23/24 节。
- **`ToolResult` 契约已交付**：通知工具的返回值用 core 既有的工具结果类型，不新增返回类型。
- **核心阶段只有 webhook 一档实现**：企业微信 / 飞书 / 钉钉的群机器人都提供 webhook 地址，一档覆盖大部分场景；加专用渠道是扩展阶段「只新增实现类」的事。
- **通知渠道表的列口径照 A6 逐字抄**：`name` / `type` / `url` / `config_json` / `description`；A6 未给列长度，按建表脚本的既有风格定，**不新增列、不改语义**。
- **`config_json` 的形状**：A6 只写「类型相关键值」，未定格式。按通知目标的配置类型（字符串键值映射）取「JSON 对象、值均为字符串」；非字符串值 MUST 明确报错，MUST NOT 静默转成字符串。
- **`type` 列本节不做取值校验**：核心阶段只有一档实现，`NotifyTools` 注入的就是唯一那个适配器，「按 type 选适配器」的机制要到有第二个实现（扩展阶段）才有调用方。本节只要求 `type` 非空（空则报错）；非 `webhook` 的取值原样交给适配器，写歪了会在对端以非 2xx 暴露（FR-010 保证不静默）。
- **推送的等待上限取 10 秒（新增字面量，待第 5 步固定停点确认）**：课件与技术方案都未定义推送超时，但不设上限时「对端黑洞」会让推送无限期挂住（FR-021）。本节取「连接 + 读取合计 10 秒」，量级对齐技术方案给 Agent 调用定的 60 秒上限之下的单次 IO 预算；超时按 FR-010 上抛，不重试。**这是本规约新增的唯一一个字面量**，值本身可调，口径（必须有界、超时即失败、不重试）不可省。
- **建表脚本的一处硬约束**：脚本是按分号切分后逐条执行的，**注释里 MUST NOT 含分号**（既有脚本文件头已写明这条踩坑记录），本节的建表片段必须遵守。
- **本节运行期注册表恒为空**：没有写入路径（仲裁 2），因此「渠道未配置 → 明确报错」是预期行为；真渠道验证要等第 26 节有了写入路径。
- **审计的实际落库验证不在本节**：通知工具走 `ToolExecutor` 既有审计路径是**构造上成立**（它与其他工具同一条执行路径），但本节没有注册路径、也没有 `Sandbox` 实现，因此「`notify` 调用真的写进 `tool_invocations`」要等第 20 / 24 节接线后才验得了。
- **`@Tool` 注解本节是惰性的**：注册机制归第 20 节；注解先加上是为了不改动本节交付物（仲裁 5）。
- **新增的依赖边都是既有工件的显式化，不引入新工件**：`oryxos-tool` 新增 `spring-ai-model`（提供 `@Tool`）与 `spring-web`（提供 `RestClient`），两者版本均由既有 BOM 托管、且都已在发行物 classpath 上；测试依赖 `mockwebserver3:5.5.0` 只在 test scope（仲裁 5 / 6 / 7）。
- **测试分层**：单测（含本地假 webhook）默认跑；需要真模型或真 webhook 的集成冒烟打 `@Tag("integration")`，CI 跳过。实现完成的定义是 `mvn clean verify` 全绿。
- **每个 user story 完成后打 git commit**，同步时机由用户决定（本节流程内不自动 commit / push / 运行 `package.sh`）。
