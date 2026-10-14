# Implementation Plan: Notify 出站推送 —— 给 Agent 补上「结果主动送出去」的出口

**Branch**: `004-lesson19-notify-outbound` | **Date**: 2026-10-14 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/004-notify-outbound/spec.md`（第 19 节课件：`docs/class/第19节：Notify 模块 原理解析、实现与代码讲解.md`）

**Note**: 本文件由 `/speckit-plan` 生成。本节是核心阶段「能力四（Plugin Tool）」的前置切片，上游为 `specs/000-runtime-kernel`（契约与审计地基）、`specs/001-llm-provider`、`specs/002-react-loop`、`specs/003-cli-session`。章节依据 `docs/TechnicalSolution.md` §6.8（通知推送）、§9.2（`notify_channels` 表）。

## Summary

给 OryxOS 补上**对称的另一半：出站通知**。前 18 节解决的都是「人推」——有人发起、有人等响应，处理完把响应返回给发起者即可；一旦触发源变成「到点自动」（第 25 节定时模块、第 31 节日报 Agent），没有人在另一端等着接收响应，Agent 必须自己决定把结果送到哪、怎么送。本节交付的就是这个出口。

交付形态是**接口先行**在本课的第一次亮相：骨架期已定的中立接口 `NotifyChannelAdapter` / `NotifyTarget` 一个字不改，本节只在其后挂**一档通用 webhook 实现**（`WebhookNotifyAdapter`，给一个地址发一次 HTTP POST），再加一个内置通知工具 `NotifyTools`（参数：内容 + 可选渠道名），以及渠道的持久化与按名解析（`notify_channels` 表 + `JpaNotifyChannelRegistry`）。以后加飞书/钉钉专用渠道只新增实现类，接口与调用方零改动。

两条本节钉死的不变量：

1. **推送目标不进对话。** 渠道是**全局注册表**（不是 Agent 的 frontmatter——代码里 `ProfileLoader.FORBIDDEN_KEYS` 已把这条禁令钉死），模型只传「内容」与可选的「渠道名」，webhook 地址不进对话、不进日志、不进报错。
2. **出站不能绕过白名单。** `notify` 发出去的同样是一次 HTTP 请求，必须与 `http_post` 过同一层域名白名单，且**校验先于发送**——顺序反了就是一个可被利用的绕过口子，而顺序错误在单请求测试里不会自己暴露，故用 `InOrder` 专门钉死。

**本节交付的是「库代码 + 单测」，不是可运行的功能。** 通知工具的真实接线有两个依赖在后面的课：`@Tool` 的注册机制与 `ToolRegistry` 在第 20 节、`Sandbox.enforce` 的实现（`WhitelistSandbox`）在第 23/24 节。因此本节**零装配改动**——`CliAssemblyConfiguration` 一行不改，通知工具与适配器以库代码交付、用替身单测；**尤其不造「全放行」的占位沙箱**（那等于把本节最该守住的那道墙拆掉）。装配点归第 24 节，交接清单见 [contracts/assembly-handoff.md](contracts/assembly-handoff.md)。

## Technical Context

**Language/Version**: Java 21（`maven.compiler.release=21`）

**Primary Dependencies**:
- `org.springframework:spring-web`（`RestClient` + `JdkClientHttpRequestFactory`）——**本节新增到 `oryxos-tool`**，版本由 `spring-boot-dependencies` BOM 托管（不写版本号）。已实测 `oryxos-tool` 现有 classpath **没有** `spring-web`；而它**已经在发行物 classpath 上**（经 `oryxos-provider` → spring-ai 传递进 `oryxos-cli` / `oryxos-boot`），故只是把一条隐式可用的边显式化，**发行物不新增任何工件**（仲裁 6）
- `org.springframework.ai:spring-ai-model`（`@Tool` 注解）——**本节新增到 `oryxos-tool`**（显式声明；已实测它**已是** `oryxos-tool` 的传递依赖，经 `oryxos-core` 进来，版本 `1.1.8` 由根 `spring-ai-bom` 托管，不写版本号；`oryxos-provider` 已在用同一坐标）（仲裁 5）
- `io.oryxlabs:oryxos-storage`——**本节新增到 `oryxos-tool`**（显式声明；同样**已是**传递依赖，经 `oryxos-core` 进来）。`JpaNotifyChannelRegistry` 消费 `NotifyChannelRepository`，而 core → storage 是第 18 节已报批的既定边，故这是**把既有传递边显式化**，不新增工件、不成环（[research.md](research.md) D1）
- Jackson（`jackson-databind` 2.21.4）——解析 `config_json`；经 `spring-ai-model` 已在 classpath 上，**不新增第三方依赖**
- 测试：`spring-boot-starter-test`（JUnit 5 + Mockito + AssertJ，**本节新增到 `oryxos-tool` 的 test scope**——该模块此前没有任何测试依赖）、`com.squareup.okhttp3:mockwebserver3:5.5.0`（test scope，**必须写版本号**，无 BOM 托管）（仲裁 7）
- **★ `org.jetbrains.kotlin:kotlin-stdlib:2.1.21`（test scope + 模块级 `dependencyManagement`）**——T001 实测踩到的坑，**软门禁 ⑥ 报批项**（用户裁决「钉 2.1.21」）：`spring-boot-dependencies` 把 `kotlin.version` 钉在 `1.9.25` 并托管 `kotlin-stdlib`，**覆盖**了 `okhttp-jvm:5.5.0` 自己声明的 `2.1.21`，而本机仓库没有 `1.9.25` → 离线构建 `Could not resolve kotlin-stdlib:jar:1.9.25` **BUILD FAILURE**。且 `okhttp-jvm:5.5.0` 由 Kotlin 2.1 编译，配 `1.9.25` stdlib 有运行期元数据不兼容风险——`2.1.21` 才是它真正需要的版本。覆盖必须落在 `dependencyManagement` 层（只写直接依赖虽能赢仲裁，但图里仍留一个 `1.9.25` 节点、每次构建刷警告）。**这行看着多余，删了构建会红**（[research.md](research.md) 依赖核实记录）

**Storage**: SQLite `.oryxos/oryxos.db`；`notify_channels` 表**本节新建**（列名与语义逐字照 `specs/000-runtime-kernel/data-model.md` A6 抄，建表脚本手工维护在 `oryxos-storage/src/main/resources/db/schema.sql`）。运行期该表**恒为空**——本节不引入写入路径（仲裁 2），故「渠道未配置 → 明确报错」是**预期**行为

**Testing**: JUnit 5 + Mockito + AssertJ + MockWebServer；**4 个测试类、全部不碰网络**（假 webhook 起在 `localhost`）：
- `WebhookNotifyAdapterTest`（`oryxos-tool`，MockWebServer）——课件 harness **第一批**，本节立即可跑
- `NotifyToolsTest`（`oryxos-tool`，Mockito 替身）——课件 harness **第二批**，本节即可跑（课件原计划「20/24 节接线后补跑」，但用替身驱动不需要真接线）
- `JpaNotifyChannelRegistryTest`（`oryxos-tool`，Mockito 替身 `NotifyChannelRepository`）——行→目标映射与错误口径
- `NotifyChannelRepositoryTest`（`oryxos-storage`，`@DataJpaTest` + 真 SQLite 临时文件 + 手工脚本）——建表列齐备 / 存取往返 / 默认渠道的确定性口径

本节**无 `@Tag("integration")` 集成冒烟测试类**：课件 harness 两批都是单测层（MockWebServer 是本地假服务，不是外网依赖），真 webhook 验收列为**人工项**（[quickstart.md](quickstart.md) §5）。完成定义 = `mvn clean verify` 全绿

**Target Platform**: JVM 21 单进程，Linux / WSL2 开发环境

**Project Type**: Maven 多模块后端；本节触及 **2 个模块**（`oryxos-tool` 主体、`oryxos-storage` 的表与存取）+ `CLAUDE.md` 状态段一处。**零装配**：`oryxos-cli` / `oryxos-boot` 的 Java 代码与 `CliAssemblyConfiguration` **一行不改**

**Performance Goals**: 一次推送 = 一次 HTTP POST，无重试、无退避；**等待有界**——连接 + 读取合计 10 秒上限（FR-021 / SC-010），超时按失败上抛。推送在 ReAct 循环内同步阻塞执行（章程原则 I）

**Constraints**:
- MUST NOT 反向依赖能力模块（章程附加约束）——`oryxos-core` 不出现 `oryxos-tool` 的 import；本节**不动 `oryxos-core` 一行**
- MUST NOT 修改骨架期已交付的两份契约（`NotifyChannelAdapter` / `NotifyTarget` 的签名与课件逐字一致）
- MUST NOT 修改 `Profile` / `ProfileLoader` / `ProfileContext` 的既有签名与行为（FR-015）；`notify_channels` MUST NOT 出现在 `AGENT.md` frontmatter
- MUST NOT 新增任何 Spring 装配（FR-016）——两个类不带 `@Component`，`CliAssemblyConfiguration` 一行不改；MUST NOT 造「全放行」的占位沙箱
- MUST NOT 引入渠道的写入路径（yaml 导入 / CRUD / 工作区模板文件）（FR-014）——本节的解析是**只读**的
- MUST NOT 引入响应式编程或自建线程池（章程原则 I / FR-020）——不写 `Flux` / `CompletableFuture`
- MUST NOT 启用 Spring AI 自动工具执行（章程原则 III）——`@Tool` 注解本节是**惰性的**，没有任何 `ToolRegistry` 消费它
- MUST NOT 出现明文凭证，MUST NOT 让渠道地址（含 URL 里的 token）出现在对话内容、结构化日志、报错与命令输出中（FR-019）
- MUST NOT 依赖 `hibernate.ddl-auto` 做建表或迁移——建表走手工脚本；**建表脚本里任何注释都 MUST NOT 含分号字符**（脚本按分号切分逐条执行，既有文件头已写明这条踩坑记录）
- 避开 P3C/ASM 解析不了的 Java 18+ 语法形态（增强 switch 的 `default ->` 写法），静态检查是构建门禁

**Scale/Scope**: 本节落地 **3 个新增 public 类型 + 1 个实现**（`NotifyChannelRegistry` 接缝 / `WebhookNotifyAdapter` / `NotifyTools` / `JpaNotifyChannelRegistry`）、**2 个 storage 类型**（`NotifyChannelEntity` / `NotifyChannelRepository`）、**1 张表 5 列**、**4 个测试类**；对外命令面**零变化**（无新命令、无新参数）

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 本节的落实 | 结论 |
| --- | --- | --- |
| I. JDK 21 + SB 3.x 单体、多模块、单二进制；同步阻塞，MUST NOT 响应式 | 不新增模块（9 个模块骨架不变）；推送是一次同步阻塞的 `RestClient` 调用，由 virtual thread 承载；无响应式、无自建线程池、无 `CompletableFuture`。发行物仍只有 `oryxos-boot/target/oryxos.jar` 一个，且本节**不新增任何工件**（两条新依赖边都是既有传递边的显式化） | ✅ |
| II. 只交付运行时内核；MUST NOT 引入未被证实的抽象（YAGNI） | 只做课件点名的 4 个类 + 表；不做重试/退避/投递回执、不做邮件短信、不做富文本卡片、不做渠道 CRUD、不做逐家签名算法（spec「明确不做」11 条逐项照搬）。`NotifyChannelRegistry` 接缝是**为满足 FR-006/FR-011 所必需**、且已按软门禁 ① 报批，不是预造抽象 | ✅ |
| III. Agent 引擎自实现，Spring AI 只用一半；自动 tool 执行 MUST 被禁用 | 只用到 `@Tool` **注解**（章程允许的三件事之一：schema 生成）；本节**没有任何** `ToolRegistry` / 自动执行路径——注解是惰性的，注册归第 20 节。`NotifyTools` 是普通 Java 方法，由第 20 节的 `ToolExecutor` 链路调度 | ✅ |
| IV. Agent 目录不是 Tool；Skill 绑定真相源是软连接、MUST NOT 进 frontmatter | 本节**零接触** `AGENT.md` / `AgentLoader` / `ContextLoader`；`notify_channels` 不进 frontmatter（既有禁令保持有效，且本节不改 `ProfileLoader`） | ✅ |
| V. Tool 体系统一抽象、单一模块 | 通知工具落 `oryxos-tool` 的 `notify` 包，**不新建模块、不拆模块**；`NotifyTools` 走第 20 节的统一抽象路径（本节先以库代码交付） | ✅ |
| VI. 审计数据 Day One 落库；成败都写；Sandbox 失败复用既有审计路径 | 本节**不新增审计逻辑、不自建第二条写入路径**（FR-008）：`NotifyTools` 与其他工具同处 `ToolExecutor` 的执行入口之下，成功失败都由那**一处**落 `tool_invocations`；沙箱违规抛 `SandboxViolationException`，同样由既有失败审计路径吸收 | ✅ |
| VII. 接口先行，实现按档演进；MUST NOT 用 SecurityManager | **本节正是这条的第一次兑现**：`NotifyChannelAdapter` 是骨架期已定的中立接口，本节只**新增实现类**（`WebhookNotifyAdapter`），签名一个字不改；`Sandbox` 按接口调用接进去，白名单本体归第 23/24 节。不涉及 SecurityManager | ✅ |
| VIII. SQLite + JPA；凭证环境变量注入；状态外置 | 渠道落 SQLite 全局注册表（状态外置）；建表走手工脚本，不依赖 `ddl-auto`；`config_json` 只存非敏感的类型配置，URL 里的 token 由部署方以环境变量提供，MUST NOT 落明文进代码或日志（FR-019） | ✅ |
| IX. 三触发源复用同一链路 | `NotifyTools` 是 `AgentService.process` 链路内的一环（第 20 节接线后由 `ToolExecutor` 调度），**不感知**消息来自 CLI / Web 还是 `AgentScheduler`；第 25 节的钟推到点跑完循环后调它，与人推走的是同一个工具 | ✅ |
| X. 每个 user story 有可演示 Demo | **本节的诚实代价**：交付物是库代码 + 单测，**没有端到端可演示的功能**（工具进不了容器、注册表运行期恒为空）。课件「实现顺序说明」已明写 `NotifyTools` 的完成时点在 24 节之后，spec 也把「本节交付的是库代码 + 单测」写在范围说明首段。可演示的那一天是第 24 节接线后——本节的可验证成果是 4 个测试类全绿 + 契约存在性核对 | ⚠️ 如实记录 |

**附加约束核对**：静态检查门禁（Spotless / P3C / Checkstyle / SpotBugs+FindSecBugs / OWASP）全部继承父 pom，本节新增代码 MUST 通过——特别是 SpotBugs 的 `EI_EXPOSE_REP2`（`RestClient` / `Sandbox` 字段暴露，见 [research.md](research.md) D3 的规避设计）；MUST NOT 用 `System.out` 输出；MUST NOT 出现明文凭证。**性能目标**：本节与性能相关的目标（推送等待有界）已按附加约束写成可度量验收标准 **SC-010**。

## 需要说明的设计偏离（逐条已论证或已报批）

**① 渠道解析改由通知工具自持接缝，不挂 `ProfileContext`（有意偏离课件代码，2026-10-14 已报批）。** 课件 `NotifyTools` 的写法是 `private final ProfileContext profileContext;` + `profileContext.resolveNotifyChannel(channel)`。两处硬伤：`NotifyTarget` 契约在 `oryxos-tool` 而 `ProfileContext` 在 `oryxos-core`，给 core 加一个返回 tool 模块类型的方法即 **core 反向依赖能力模块**（违反章程附加约束）；且第 17 节交付的 `ProfileContext` 是 `final` 类 + **私有构造 + 全静态方法**，课件把它当注入字段用**本身就编译不过**。语义上也不需要它——渠道是**全局**注册表、按名引用，解析「哪个渠道」不需要知道「当前是哪个 Agent」。落地：`oryxos-tool` 的 `notify` 包新增 `NotifyChannelRegistry` 接缝（软门禁 ① 报备项，见仲裁 8），`NotifyTools` 注入它自己解析。**`ProfileContext` / `Profile` / `ProfileLoader` 一行不动。** 详见 [research.md](research.md) D1。

**② 本节零装配改动（2026-10-14 已报批）。** 课件给 `WebhookNotifyAdapter` 挂了 `@Component`，与第 18 节既定装配纪律冲突（非 boot 模块不带 Spring 装配注解，Bean 一律在 `CliAssemblyConfiguration` 里显式声明）。更硬的约束是**根本没有可注入的沙箱**：`NotifyTools` 的构造依赖 `Sandbox`，而 `Sandbox` 的唯一实现 `WhitelistSandbox` 归第 23/24 节。落地：`CliAssemblyConfiguration` **一行不改**，两个类不带任何 Spring 注解，测试用替身驱动；`RestClient` / `WebhookNotifyAdapter` / 注册表实现 / `NotifyTools` 的 Bean 声明一并归第 24 节（交接清单见 [contracts/assembly-handoff.md](contracts/assembly-handoff.md)）。**MUST NOT 造全放行的占位沙箱。**

**③ `WebhookNotifyAdapter` 自建有界 HTTP 客户端，构造签名与课件不同（FR-021 所迫，如实记录）。** 课件签名是 `WebhookNotifyAdapter(RestClient restClient)`——把超时责任推给了注入方，而 FR-021 要求**本节交付的推送本身**有界（第 25 节的定时任务不能被一次推送永久卡住）。落地：`public WebhookNotifyAdapter()` 内部构造 `RestClient`（`JdkClientHttpRequestFactory` + `HttpClient.Redirect.NEVER` + `setReadTimeout(10s)`，连接超时同为 10s）；另有一个**包级私有**构造 `WebhookNotifyAdapter(Duration)` 供同包测试注入短超时（用来在毫秒级验证「有界」，不必真等 10 秒）。副作用是**规避了 `EI_EXPOSE_REP2`**——不再持有外部传入的 `RestClient` 引用（上游参考实现需要 `@SuppressFBWarnings` 压制该告警，本节不需要）。**MUST NOT 把 `RestClient` 换回构造参数**，否则 FR-021 会重新变成调用方的责任。详见 [research.md](research.md) D2 / D3 / D13。

**④ 失败口径：只认 HTTP 状态码，不解释对端响应体的业务含义。** 上游参考实现的 `NotifyPoster` 会在 2xx 后继续解析飞书/企微/钉钉的 `code` / `errcode` / `StatusCode` 业务错误码。本节**不做**——spec 的 Edge Case 明文规定「假 webhook 返回 2xx 但响应体里写着业务错误：按 HTTP 语义视为送达成功，本节不去解释对端响应体的业务含义」，且仲裁 2 已把「逐家对接专用 API」划到扩展阶段。非 2xx / 不可达一律上抛，不重试。详见 [research.md](research.md) D4。

**⑤ 重定向策略显式钉为「不跟随」。** 跟随重定向意味着「白名单内的 webhook 入口 302 到白名单外」，而本节的 `enforce` 只在发送前做一次（FR-007 的顺序不变量），跟随重定向会绕开它。落地：`HttpClient.Redirect.NEVER` 显式设置（JDK `HttpClient` 的默认值本就是 `NEVER`，此处**写成代码**以免将来被改成默认值而无声失效）；3xx 因而落入「非 2xx 即失败」。上游参考实现的「逐跳复检白名单」是更强的方案，但它需要 `Sandbox` 实现就位——**留待第 24 节评估**，见 [contracts/assembly-handoff.md](contracts/assembly-handoff.md) §4。详见 [research.md](research.md) D5。

**⑥ `NotifyTools` 落 `notify` 包，而课件写的是 `tool.builtin` 包。** 课件的 `NotifyTools` 声明在 `io.oryxos.tool.builtin`；本节的模块落位表（`oryxos-lesson-dev` skill 第 19 节行）把「notify 包（Adapter/Target/Webhook 实现/`NotifyTools`）」整体归入 `notify` 包。落地：`io.oryxlabs.oryxos.tool.notify.NotifyTools`——本节这一刀切片（接口 + 实现 + 工具 + 注册表接缝）全在同一个包内闭合，不跨包暴露。第 20 节的内置工具（File/Shell/Http）落 `builtin` 包时不受影响。

**⑦ `@Tool` 的 description 文案按全局注册表口径改写（一处文案，如实记录）。** 课件原文是 `@Tool(description = "把一条消息推送到当前 Agent 配置好的通知渠道")`——「当前 Agent 配置好的」正是仲裁 1 判为**旧口径**的那句话，而它是**模型可见的接口文案**，留着会把模型引向「渠道是每个 Agent 自己配的」。落地：改为 `"把一条消息推送到已注册的通知渠道（不传渠道名时用默认渠道）"`。这不是软门禁 ② 点名的「已定字面量」（类名/方法签名/配置键/表列名/端点路径），但它确实动了课件原文，故在此显式记录并列入第 5 步固定停点的确认议程。

**⑧ `oryxos-tool` 显式声明 `oryxos-storage`（把传递边显式化，记录新模块边）。** `JpaNotifyChannelRegistry` 消费 `NotifyChannelRepository`，需要 `oryxos-tool → oryxos-storage` 这条边。已实测：该边**本来就是传递依赖**（`oryxos-tool → oryxos-core → oryxos-storage`，compile scope），显式声明**不新增任何工件**。方向不成环（`oryxos-storage` 不依赖任何内部模块）。**为什么实现放 tool 而不是 storage**：storage 若实现 tool 的接口，就形成 `storage → tool` 的反向边，把低层持久化模块绑到能力模块上，比 `tool → storage` 差得多。备选与排除见 [research.md](research.md) D1。

## Project Structure

### Documentation (this feature)

```text
specs/004-notify-outbound/
├── plan.md              # 本文件
├── spec.md              # 需求规约（/speckit-specify 产出）
├── research.md          # Phase 0 产出：D1~D15 技术决策与备选方案
├── data-model.md        # Phase 1 产出：notify_channels 表 / 实体 / 行→目标映射
├── quickstart.md        # Phase 1 产出：可执行的验证指引
├── contracts/           # Phase 1 产出：对外契约
│   ├── notify-outbound.md          # 适配器 + 通知工具：签名、顺序不变量、错误口径
│   ├── notify-channel-registry.md  # 注册表接缝 + 表 / 实体 / Repository + 默认渠道口径
│   └── assembly-handoff.md         # 零装配的交接清单：第 24 节要声明哪些 Bean
├── checklists/
│   └── requirements.md  # /speckit-specify 产出的质量清单
└── tasks.md             # Phase 2 产出（/speckit-tasks，非本命令）
```

### Source Code (repository root)

```text
oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/notify/
├── NotifyChannelAdapter.java          # 不改：骨架期契约，课件逐字一致
├── NotifyTarget.java                  # 不改：骨架期契约，课件逐字一致
├── WebhookNotifyAdapter.java          # 新增：核心阶段唯一实现（自建有界 RestClient，不跟随重定向）
├── NotifyChannelRegistry.java         # 新增：渠道注册表接缝（按名解析通知目标，只读）
├── JpaNotifyChannelRegistry.java      # 新增：接缝的持久化实现（消费 NotifyChannelRepository）
└── NotifyTools.java                   # 新增：内置通知工具（内容 + 可选渠道名），带惰性 @Tool

oryxos-tool/src/test/java/io/oryxlabs/oryxos/tool/notify/
├── WebhookNotifyAdapterTest.java      # 新增：课件 harness 第一批（MockWebServer）
├── NotifyToolsTest.java               # 新增：课件 harness 第二批（替身）+ InOrder 顺序断言
└── JpaNotifyChannelRegistryTest.java  # 新增：行→目标映射 / 默认渠道 / 错误口径（替身 Repository）

oryxos-tool/pom.xml                    # 改：+spring-web、+spring-ai-model、+oryxos-storage（三者均为显式化既有边）
                                       #      +spring-boot-starter-test（test）、+mockwebserver3:5.5.0（test）
                                       #      +kotlin-stdlib 2.1.21（test + dependencyManagement，压 Boot BOM 的 1.9.25）

oryxos-storage/src/main/java/io/oryxlabs/oryxos/storage/
├── entity/NotifyChannelEntity.java          # 新增：@Table("notify_channels")，@Id String，无 @GeneratedValue
└── repository/NotifyChannelRepository.java  # 新增：JpaRepository<NotifyChannelEntity, String> + findFirstByOrderByNameAsc()

oryxos-storage/src/main/resources/db/schema.sql       # 改：追加 notify_channels 建表语句（注释不含分号）
oryxos-storage/src/test/java/.../repository/
└── NotifyChannelRepositoryTest.java    # 新增：建表列齐备 / 存取往返 / 默认渠道不依赖插入顺序

CLAUDE.md                              # 改：状态段（第 19 节完成、下一步第 20 节；发行入口与装配纪律无变化）
```

**明确不碰的文件**（本节的存在感靠这份清单背书）：

```text
oryxos-cli/src/main/java/io/oryxlabs/oryxos/cli/CliAssemblyConfiguration.java   # 零装配：一行不改
oryxos-core/src/main/java/io/oryxlabs/oryxos/core/engine/ProfileContext.java    # 一行不改（仲裁 3）
oryxos-core/src/main/java/io/oryxlabs/oryxos/core/profile/{Profile,ProfileLoader}.java  # 一行不改（FR-015）
oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/notify/{NotifyChannelAdapter,NotifyTarget}.java  # 契约不动
oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/sandbox/*                     # 契约不动，实现归第 24 节
oryxos-boot/**                                                                  # 不改（依赖传递，fat JAR 无需调整）
README.md / website/**                                                          # 不改（见下「对外内容」）
```

**Structure Decision**: 沿用既有 9 模块骨架，**不新增模块**。落位依据：

- **`oryxos-tool.notify`** 是本节主体——`CLAUDE.md` 模块表已把「内置 Tool（File/Shell/Http/Notify）……`Sandbox` 接口 + `WhitelistSandbox`」列在此模块。本节这一刀切片（中立接口 + 唯一实现 + 内置工具 + 注册表接缝）**在同一个包内闭合**：`NotifyChannelRegistry` 是 `oryxos-tool` 自持的接缝，与 `Sandbox` 同属「契约在 tool、core 不引用」的既有例外模式（章程附加约束），故**不成环**。
- **`oryxos-storage`** 放持久化层——`NotifyChannelEntity` / `NotifyChannelRepository` 与既有 `SessionEntity` / `SessionRepository` 同构；实体字段**全部是 `String`**，**不认识 core 与 tool 的类型**（storage 不依赖任何内部模块，反向即成环）。
- **`oryxos-core` 本节零改动**——这是仲裁 3 的直接结果，也是「core 不反向依赖能力模块」在代码上的证据（[quickstart.md](quickstart.md) §3 有可 grep 的核查命令）。
- **`oryxos-cli` / `oryxos-boot` 零改动**——`spring-web` 已在它们的 classpath 上（经 `oryxos-provider`），`oryxos-tool` 的新依赖不需要向上冒泡；没有 Bean 要声明，故 `CliAssemblyConfiguration` 不动。

**对外内容三处同步**：本节**不触发**。`README.md`（第 26 / 78 / 99 / 147 / 151 行）、官网首页 `Home.vue`（第 23 / 150 / 423 行）与官网文档页（`website/guide/what-is-oryxos.md` 第 12 / 65 行、`quick-start.md` 第 46 / 50 行及其英文版）**已经**把 Notify 写进北极星公式与能力表，且「渠道名 team-lark」的表述与本节「按注册名引用」的口径一致——本节是**兑现**既有表述，不新增对外承诺，故三处不动（`CLAUDE.md` 状态段除外，那是进度记录不是对外表述）。

## Complexity Tracking

> 本节的偏离已在上文「需要说明的设计偏离」逐条论证，均属**已报批的架构裁决**（①②）、**FR-021 所迫并有测试背书**（③）、**spec 明文规定**（④）、**安全不变量所迫**（⑤）、**模块落位表所定**（⑥）、**旧口径文案修正**（⑦）、**把既有传递边显式化**（⑧），**无未经论证的复杂性**，故不填违规表。

**三处有界的新增，记录但不构成违规**：

1. **`NotifyChannelRegistry` 是超出课件交付物清单的新 public 类型。** 已按软门禁 ① 报备并随仲裁 3 获批（仲裁 8）；名称与形状在本 plan 定稿（[contracts/notify-channel-registry.md](contracts/notify-channel-registry.md)）。它的存在理由单一：课件的解析点按仲裁 3 不可行，而 FR-006 / FR-011 要求「按名解析 + 缺省取默认 + 解析不到明确报错」必须有个落点。
2. **`oryxos-tool → oryxos-storage` 是显式化的新模块边。** 该边**本来就是传递依赖**（经 `oryxos-core`），显式声明不新增工件、不成环；不显式声明则 `JpaNotifyChannelRegistry` 编译期依赖一条「碰巧存在」的边，属于隐式耦合。
3. **本节无可演示的端到端功能（原则 X 的诚实代价）。** 工具进不了容器、注册表运行期恒为空——这是仲裁 2 + 仲裁 4 的必然结果，不是实现偷懒。可演示的那一天是第 24 节接线之后；本节的可验证成果是 4 个测试类全绿 + 交付物存在性核对 + 契约核对（[quickstart.md](quickstart.md)）。

## 已知接缝（留给后续节）

本节刻意留白、由后续节补齐的五处：

| 留白 | 由谁补 | 本节已就位的部分 |
| --- | --- | --- |
| `@Tool` 的注册机制（谁扫描、谁包装成统一工具抽象） | 第 20 节（`ToolRegistry`） | 注解已按课件加上（仲裁 5），**惰性**；`NotifyTools` 的签名已按第 20 节可包装的形状定死 |
| `Sandbox` 的实现（`WhitelistSandbox`） | 第 23 / 24 节 | 调用位已在 `NotifyTools` 里就位：`enforce` 先于 `send`，顺序不变量已用 `InOrder` 钉死；本节用替身验证 |
| `notify_channels` 的**写入路径**（CRUD / 工作区模板 / yaml 导入） | 第 26 节（Web 管理台 + `/api/v1/notify-channels`） | 表与存取已就位（只读）；运行期表恒为空是**预期**行为 |
| 通知相关 Bean 的 Spring 装配 | 第 24 节 | 交接清单（构造签名 + 依赖 + 前置条件）已写死在 [contracts/assembly-handoff.md](contracts/assembly-handoff.md) |
| `url` 的 `${ENV_VAR}` 占位解析 | 第 26 节（随写入路径一并定） | **本节不假装实现了它**（仲裁 2 的连带影响，如实记录） |

另有两处**非接缝**的留白，本节明确不做并已记入 [research.md](research.md)：重定向的逐跳白名单复检（D5，需 `Sandbox` 实现）、对端业务错误码的解析（D4，属扩展阶段的专用渠道对接）。

## Constitution Check（Phase 1 设计后复核）

设计产物（`data-model.md`、`contracts/`）产出后复核 10 条原则，**维持 PASS**：

- [contracts/notify-outbound.md](contracts/notify-outbound.md) §2 把「`enforce` 先于 `send`」写成**顺序不变量**并附课件原样的 `InOrder` 断言骨架——原则 VII 与 FR-007 / SC-005 在契约层面可断言，实现方无法把顺序写反还宣称完成。
- [contracts/notify-outbound.md](contracts/notify-outbound.md) §3 明文规定 `NotifyTools` 的构造只接受 `Sandbox` / `NotifyChannelAdapter` / `NotifyChannelRegistry` 三个**接口**，MUST NOT 出现任何具体实现类型——FR-017 / SC-007 变成可 grep 的事实。
- [contracts/notify-channel-registry.md](contracts/notify-channel-registry.md) §1 把接缝钉成**单方法只读**接口（`resolve`），MUST NOT 出现 `save` / `delete`——FR-014（本节不引入写入路径）在契约层面被固化，第 26 节加写入时只能新增方法、不改本节调用方（原则 VII）。
- [contracts/notify-channel-registry.md](contracts/notify-channel-registry.md) §3 规定 `NotifyChannelEntity` 的字段**全部是 `String`**、不认识 core 与 tool 类型——`oryxos-storage` 不反向依赖的约束在契约层面被禁止违反。
- [contracts/assembly-handoff.md](contracts/assembly-handoff.md) §1 把「本节零装配、Bean 声明全归第 24 节」写成交接契约，§3 明文 MUST NOT 造全放行的占位沙箱——FR-016 与章程原则 VII（实现按档演进）在契约层面可验证。
- [data-model.md](data-model.md) §2 的 `notify_channels` 表定义逐字照 A6，不新增列、不改语义；建表走手工脚本——章程附加约束「不依赖 `ddl-auto` 做建表」得到遵守。
- 无新增模块、无 core 反向依赖、无响应式代码、无自建线程池、无明文凭证、无 SecurityManager、无 Spring AI 自动执行路径。

## Phase 0 / Phase 1 产出

- 技术决策与备选方案：[research.md](research.md)（D1~D15）
- 实体与字段、行→目标映射：[data-model.md](data-model.md)
- 对外契约：[contracts/](contracts/)（`notify-outbound.md` / `notify-channel-registry.md` / `assembly-handoff.md`）
- 验证指引：[quickstart.md](quickstart.md)
