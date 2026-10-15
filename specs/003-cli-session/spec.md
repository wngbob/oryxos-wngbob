# Feature Specification: CLI 与会话持久化 —— 把 Agent 从「能跑」变成「能用」

**Feature Directory**: `specs/003-cli-session`

**Git Branch**: `class-18`

**Created**: 2026-10-13

**Status**: Draft

**Input**: User description: "第18节需求：CLI 与会话持久化——把 Agent 从"能跑"变成"能用""（课件：`docs/class/第18节：CLI 功能概述、实现思路与代码讲解.md`；章节依据 `docs/TechnicalSolution.md` §8.4 Channel 接入、§8.6 三种运行模式、§8.7 命令行工具、§9.2 sessions 表）

## 范围说明

前两节交付了 Provider（会调模型）与 ReAct（会思考的循环），引擎已经能转，但还没有一个能亲手操作它的入口。本节交付 **CLI**：在终端里敲 `oryxos chat` 就能跟 Agent 对话、敲 `oryxos profile list` 就能看有哪些 Agent、敲 `oryxos init` 就能起一个工程。CLI 的定位是**消息进出的门，不是干活的人**——它只做「读输入 → 交给引擎 → 打印结果」三步，不碰任何 Agent 逻辑。

同时本节一并交付**会话持久化**。CLI 是第一个真正「用起来」会话的入口，对话历史得存下来、重启还在、多轮能接上。会话标识由 channel、user、profile 三段联合生成，**拼接只发生在唯一一处**，所有入口只提供三元组、不自己拼字符串——两处各拼一遍、格式差一个分隔符，同一个人就会出现两条互不相认的历史。

本节是 CLI 课，但**真正值得自动化验收的是会话层**：它是后面所有入口（Web、定时）共用的地基，出口径问题最难查，所以在这就钉死。12 个子命令里凡是数据源尚未就位的，一律**明确输出「未接入」并注明接入节号**，MUST NOT 造一个假的返回值把命令糊过去。

> **修订记录（2026-10-15，第 19 节交付后补强②）**：本节命令面由 **12 条扩为 15 条**，新增 `notify-channel add|list|remove`
> （契约 [contracts/cli-surface.md](contracts/cli-surface.md) §1 + §2.12）。这是**命令集扩充**，本节既有的 FR / SC 对 12 条旧命令的约束
> **一字未动**，只是分母从 12 变成 15——下面 FR-002 / SC-001 / SC-006 里的数字已同步回修并标出。
> 扩充理由：第 19 节只交付了 `notify_channels` 的读半边，没有任何路径能往表里写一行，`notify` 在真实部署里 100% 失败却被记成「预期状态」。

**冲突仲裁记录（本规约据此撰写，后续 plan / tasks 不得偏离）**：

1. **持久化接缝的接口名是 `SessionService`，不是课件里的 `SessionManager`**：沿用第 17 节已报批裁决（上游 `specs/000-runtime-kernel/tasks.md` T036/T045 用 `SessionService`，T077 明写「统一 `SessionManager`→`SessionService` 命名」）。本节的落地物随之定为：`SessionService` 接口补齐 `getOrCreate` / `get` 两个操作；实现类 `JpaSessionService`（对齐第 16 节 `JpaLlmCallRecorder` 的命名）；测试类 `SessionServiceTest`（课件 harness 里的 `SessionManagerTest`）。课件原文的 `SessionManager` 只作历史措辞保留在本条记录里。
2. **`Session` 领域类型增 `channel` 与 `userId` 两个字段（改前序节公共接口，已报批）**：第 17 节交付的 `Session` 只有 `id` / `profileName` / `messages` / `createdAt` / `lastActiveAt` 五项，而 `sessions` 表有 `channel`、`user_id` 两列——不够落库。裁决：`Session` 增这两个字段，工厂方法变为 `Session.create(id, channel, userId, profileName)`。第 17 节契约 `specs/002-react-loop/contracts/session-model.md` §2 中「五个字段齐备」的断言**随本次修订改为七项**，该文件同步更新。
3. **`SessionService` 的实现放 `oryxos-core`**：`oryxos-core → oryxos-storage` 是已报批的既定边（`specs/000-runtime-kernel/plan.md`「oryxos-storage 是共享基础设施模块：core 与各能力模块可直接依赖其实体与 Repository」），故 `JpaSessionService` 放 core 的 `session` 包、直接消费 `oryxos-storage` 的 `SessionRepository`——与第 17 节 `ToolExecutor` 直接用 `ToolInvocationRepository` 是同一模式。放 `oryxos-storage` 会成环（storage 反向依赖 core）。
4. **`Session` 领域类型不带 `status` / `archivedAt`**：`sessions` 表有 `status` 与 `archived_at` 两列（口径见 `docs/TechnicalSolution.md` §9.2），但归档是**第 26 节** `DELETE /api/v1/sessions/{id}` 的行为。本节只保证新会话落库为 `active`、`archived_at` 为空；领域类型不引入本节无调用方的字段（章程原则 II）。
5. **重命令的 Spring 启动类建在 `oryxos-cli`**：`oryxos-boot` 聚合 `oryxos-cli`（`boot → cli`），故 `cli → boot` 会成环；`CLAUDE.md` 依赖方向明写「`oryxos-cli` 依赖各能力模块」。因此 `oryxos-cli` 新增对 core / provider / tool / memory / storage / channel-cli 六个模块的依赖，重命令的启动类 `CliApplication` 建在 `oryxos-cli`，并按课件「重命令启动类显式声明 `@EnableJpaRepositories`/`@EntityScan` 的 `basePackages`」逐字声明扫描范围（`scanBasePackages` 带不动这两个）。**本节不引入 `oryxos-web`**（见仲裁 15）。
6. **`application.yaml` 留在 `oryxos-boot`（已报批）**：`provider list` / `status` 是**轻命令**（上游 `contracts/cli.md` 明列），不启动 Spring，却要读 `oryxos.providers`；该配置只在 boot 的 classpath 上，而 `oryxos-cli` 看不到 boot（见仲裁 5，反向依赖成环）。两条路——把 `application.yaml` 移到 `oryxos-cli`，或让 Provider 段标「未接入」——已提请用户裁决，**采纳后者：文件不动**。代价如实记录：本节点名的 FR-015（`provider list` 报密钥就绪）与 `status` 的 Provider 段**本节交付不了**，要到第 26 节 Web 起来后才补；`provider list` 本节改列「底座支持的厂商全集 + 工作区各 Agent 实际引用的厂商名」，就绪状态一律标注「未接入（第 26 节）」，MUST NOT 编造 ready/missing（见仲裁 13）。
7. **`init` 建的工作区不含 `notify_channels.yaml` / `sandbox.yaml`**：上游 `specs/000-runtime-kernel/contracts/cli.md` 与 tasks T007 列了这两个模板，但三处更新的权威文档一致反对——`docs/TechnicalSolution.md` §6.8 明写「通知渠道通过 Web 管理台或 `/api/v1/notify-channels` 做 CRUD，持久化在 SQLite 的 `notify_channels` 表」、§9.3 列的「放文件系统不放 SQLite」清单（Agent 目录 / Bootstrap / Memory / MCP 配置 / 日志）不含它、`CLAUDE.md` 工作区目录树同样不含。按 `CLAUDE.md`「文档间冲突时，以最新技术方案为准」，`contracts/cli.md` 与 `data-model.md` B 段里的 `notify_channels.yaml ──启动导入──>` 是旧口径。裁决：`init` 只建 `CLAUDE.md` 工作区目录列出的那些（含 `mcp_servers.yaml`）；`notify_channels` 归第 19 节的 SQLite 全局注册表、`sandbox.yaml` 归第 24 节，各自契约落地时再补。
8. **工具查询与长期记忆两个接缝本节挂空实现（已报批）**：`ToolCatalog` 的真实实现 `ToolRegistry` 在第 20 节、`MemorySource` 的真实实现 `MemoryService` 在第 22 节，本节没有实现方，`chat` 端到端跑不起来。裁决：本节在**装配层**挂两个空实现（查不到任何工具 / 永远没有长期记忆），类注释注明「第 20 / 22 节由真实实现替换」；空实现 MUST 以「真实实现一落地即自动让位」的方式声明（`@ConditionalOnMissingBean`），MUST NOT 形成第二处真相。
9. **`status` / `tool list` 的未接入段**：`contracts/cli.md` 要求 `status` 输出「定时任务数」、`tool list` 列出 ToolRegistry 全量 Tool，而定时任务（第 25/28 节）与 ToolRegistry（第 20 节）均未就位。裁决：`status` 输出工作区路径、Agent 数（含无效数）、活跃会话数、数据库可达性、底座支持的厂商全集，**定时任务数与密钥就绪两段标注「未接入（第 28 / 26 节）」**；`tool list` 输出空表并注明「第 20 节接入 ToolRegistry」。两处都 MUST NOT 编造数值。
10. **课件 `ProfileListCommand` 片段里的 `.oryxos/profiles/` 是示意错误**：工作区是 `.oryxos/agents/`，Profile 由 `AGENT.md` frontmatter 派生、不写独立 YAML（`CLAUDE.md`「Profile」条 + `docs/TechnicalSolution.md` §8.2）。课件本身声明「下面的代码是示意」。裁决：按 `.oryxos/agents/` 落地，片段中的路径不采用。
11. **`provider list` 不读 `application.yaml`，因此 MUST NOT 回显任何密钥**：原计划（SnakeYAML 直读 classpath 配置、按 `${ENV_VAR}` 变量名查 `System.getenv` 判就绪）随仲裁 6 一并作废——配置文件不在 `oryxos-cli` 的 classpath 上，读不到就是读不到，**不许用「猜一个环境变量名」的方式凑出就绪状态**（那是凭空造约定，且猜错会给出错误的 ready）。本节 `provider list` 只输出**非敏感**的厂商名信息，密钥值在任何命令输出、结构化日志与报错中 MUST NOT 出现（章程原则 VIII）。
12. **`profile_name` 存在性校验落在调用方，不落在持久化接缝里**：`specs/000-runtime-kernel/data-model.md` A1 规定「写入前 `profile_name` 必须存在」，但接缝签名 `getOrCreate(channel, user, profileName)` 只有三元组、拿不到 Agent 清单（core 的 `SessionService` 不该反向依赖 `ProfileRegistry` 之外的装配知识，也不该把「Agent 存不存在」混进会话层）。裁决：**调用方（CLI 的 Agent 解析）先解析出 Profile，再取会话**——解析不通过就根本走不到取会话这一步，A1 的校验由「调用顺序」天然满足。同一处顺带满足 `chat` 的「缺省取唯一 Agent / 多个必须显式指定」规则（FR-004）：**Agent 解析是唯一的入口闸门，会话层不重复校验**。
13. **轻命令的「已知厂商集合」取自 `ProviderProperties.SUPPORTED_PROVIDERS`，不新造常量**：`profile list` / `profile show` 等轻命令要构造 `ProfileLoader(agentsDir, knownProviders)` 做 frontmatter 校验，而 `knownProviders` 原本来自 `ProviderProperties`（Spring 绑定 `oryxos.providers`）。轻命令不启动 Spring（仲裁 6 下也读不到配置文件），故直接引用第 16 节已在 `oryxos-provider` 里公开的 `ProviderProperties.SUPPORTED_PROVIDERS`——它**正是** `ProviderProperties.validate()` 判定「厂商名是否合法」的同一个集合（`Set.of("deepseek")`），用它做校验口径与重命令路径逐字一致，**MUST NOT 在 CLI 侧复制一份同名集合**（复制即第二处真相，加厂商时必漏改）。`provider list` 的「底座支持的厂商全集」同样取自这里。
14. **`oryxos init` MUST NOT 生成默认 Agent（已报批）**：`docs/TechnicalSolution.md` §8.1 结尾一句「创建目录、写默认模板、生成默认 Profile」与三处证据相左——课件场景是 `init` → `profile create weather` → `chat --profile weather`（`init` 后工作区里还没有 Agent）；`CLAUDE.md` 工作区目录树的 `agents/` 是空目录；技术方案 §11.2 明写「在 `.oryxos/agents/<name>/` **放**一个目录」由使用者放置。裁决：`init` 只建目录与模板文件，**不生成任何 Agent**。后果如实记录：`init` 之后直接敲 `chat` 会提示「没有可用 Agent，先 `oryxos profile create <name>`」——这是**正确**的首次体验，不是缺陷。
15. **`serve` 与 `gateway` 本节只启动 Spring 上下文并前台常驻（已报批）**：`docs/TechnicalSolution.md` §8.6 说 `serve` =「启动 REST API 服务」、`gateway` =「守护进程，同时挂多个 Channel」，但 REST 端点归第 26 节、调度归第 25/28 节、IM 通道归扩展阶段，本节都没有。课件明写「本节只把『模式』与『三种模式共享同一份 Profile 配置和同一套会话存储』立起来；它们各自接入什么由后续节补齐」。裁决：两条命令都只**启动 Spring 上下文并前台常驻**（Ctrl+C 退出），打印本模式待接入什么、归哪一节；**不引入 `oryxos-web`、不起 HTTP 端口、不挂任何 Channel**（章程原则 II：没有调用方的能力不提前引依赖）。`serve` 的 `--port` 选项本节接受但只回显「端口在第 26 节启用」——保留它是因为上游 `contracts/cli.md` 已把它定为对外契约（`--help` 里必须在），MUST NOT 静默吞掉一个已定参数。
16. **`ProfileLoader` 增一个「失败明细」公开方法（改前序节公共接口，已报批）**：上游 `contracts/cli.md` 要求 `profile list` 列出全部 Agent 并标「有效/无效（无效附原因）」，而第 16 节交付的 `ProfileLoader.loadAll()` 把每条失败吞成 ERROR 日志、只返回装载成功的那些——**原因拿不出来**。三条路——给 `ProfileLoader` 加失败明细方法、`profile list` 只报「有效/无效」而原因是笼统的、`profile list` 自己重写一套 frontmatter 校验——已提请用户裁决，**采纳第一条**。落地物：`ProfileLoader` 新增 `loadAllDetailed()`（返回「成功清单 + 失败明细（Agent 名 + 原因）」的公开记录类型），`loadAll()` 的签名与行为**保持不动**（既有调用方零影响）；`profile list` / `profile show` 经它取原因。**MUST NOT 在 CLI 侧重写校验规则**——「什么叫有效」的真相源只有 `ProfileLoader` 一处，重写即第二处真相。这是纯新增（additive）的接口扩展，不改任何既有签名语义。
17. **发行入口：`oryxos-boot` 的 fat JAR 就是 CLI 的入口，`oryxos-cli` 不再自打 fat JAR（改骨架期打包配置与已定字面量，已报批）**：仲裁 5 已定「`application.yaml` 留在 `oryxos-boot` 不动」。但重命令（`chat` / `serve` / `gateway`）要读 `spring.datasource.*` 与 `oryxos.providers`，这两项**只在那一个 jar 的 classpath 上**。本节给 `oryxos-cli` 加了 6 个模块依赖与 `spring-boot-starter` 之后，若它仍自打 fat JAR，那个 jar 会变成「轻命令能跑、重命令静默炸」（无 `spring.datasource.url` → DataSource 自动装配失败）——**这个陷阱是本节自己制造出来的**，不是既有问题。已提请用户裁决，**用户授权参考参考实现 `/home/wngbob/oryxos` 的处理方式**，其做法正是：`oryxos-boot` 的 fat JAR 即唯一发行物，其 `spring-boot-maven-plugin` 的 `<mainClass>` **就是 CLI 主入口**（发行脚本里 `java -jar libs/oryxos-boot-*.jar serve --port 8080`），`oryxos-cli` 无 shade 插件、`src/main/resources/` 为空，运维侧的外部配置经 `spring.config.additional-location` 叠加。落地物：① `oryxos-boot/pom.xml` 的 `spring-boot-maven-plugin` 增显式 `<mainClass>io.oryxlabs.oryxos.cli.OryxOsCli</mainClass>`；② `oryxos-cli/pom.xml` **移除 `maven-shade-plugin`**（保留 `oryxos-version.properties` 的资源过滤），该模块退回普通库模块。**连带影响（同一节内一并完成）**：上游 `specs/000-runtime-kernel/contracts/cli.md` 第 3 行 `java -jar oryxos-cli-*.jar <command>` 的 **jar 名**被细化为 `oryxos-boot/target/oryxos.jar`（**命令面 12 条逐字不变**，该骨架契约文件不修改，supersession 以本条仲裁为准）；`CLAUDE.md` 中「`oryxos-cli` 的 `OryxOsCli` 已由 maven-shade-plugin 打成可执行 fat JAR，`java -jar oryxos-cli/target/oryxos-cli-0.1.0-SNAPSHOT.jar --version` 可直接运行」一句随之失效，本任务一并改为 `java -jar oryxos-boot/target/oryxos.jar --version`；`OryxosApplication` 不再是 fat JAR 主类（本节**不改该类**，去留由第 26 节定）。原计划的「给 shade 补 Spring 资源合并 transformer」一条**随之作废**（不再有 shade）。
18. **`CliApplication` 的 `scanBasePackages` 收窄为 `{cli, provider}`（第 7 步实测后修订，改本节已定字面量，已报批）**：本节契约原定 `scanBasePackages = "io.oryxlabs.oryxos"`，并断言「扫到 `OryxosApplication` 只是重复执行一遍同一套注解，按类名去重，无实际影响」（[research.md](research.md) D10 / [contracts/cli-assembly.md](contracts/cli-assembly.md) §2）。**该断言被实测推翻**：第 7 步跑重命令冒烟时，`java -jar oryxos-boot/target/oryxos.jar chat --profile weather --message "你好"` 报 `BeanDefinitionOverrideException`（`llmCallRepository` 被 `CliApplication` 与 `OryxosApplication` 两个 `@EnableJpaRepositories` 各注册一遍）后 `APPLICATION FAILED TO START`——**重命令在发行物上完全起不来**。已提请用户裁决，**采纳「收窄扫描根」**：`scanBasePackages` 改为 `{"io.oryxlabs.oryxos.cli", "io.oryxlabs.oryxos.provider"}`（本上下文需要的配置类只有 `CliAssemblyConfiguration` 与 `ProviderConfiguration`；其余模块的类刻意不带 Spring 注解，Bean 一律在 `CliAssemblyConfiguration` 里显式声明）。**未采纳**的两条备选：显式 `@ComponentScan` + REGEX 排除 boot 包（会与 `@SpringBootApplication` 的 `scanBasePackages` 语义打架、且须手写回 Boot 内置的两个 excludeFilter）、让 `OryxosApplication` 退化为 `main` 薄委托（要动第 17 节交付文件，而仲裁 17 已定「本节不改该类，去留由第 26 节定」）。**本条的教训一并记入**：核实「重命令起得来」MUST 真跑一次重命令——`--help` / `--version` 是轻命令、不启动 Spring 上下文，永远看不出这个坑。契约 §2 与 D10 已就地修订。**复核留痕（2026-10-14）——本次修法是不对称的**：`CliApplication` 收窄了，但 `OryxosApplication` 自己的 `scanBasePackages = "io.oryxlabs.oryxos"` 原样未动（仲裁 17 已定「本节不改该类」）。谁若以 `OryxosApplication` 为入口启动（IDE 直接 run、或第 26 节决定复用它作 `serve` 入口），它的宽根会**反向**扫到 `CliApplication`——同样两个 `@EnableJpaRepositories`、同样 `BeanDefinitionOverrideException`，方向相反。当前无触发点（fat JAR 主类是 `OryxOsCli`；`oryxos-boot` 无 `src/test`），但**第 26 节决定 `OryxosApplication` 去留时 MUST 一并处理**：同样收窄它的扫描根，或删除该类。

### 明确不做（边界）

课件「有几样先别做」逐项照搬，本节一律不碰：

- 企业级认证与权限控制；
- 流式响应；
- WebSocket；
- `gateway` 真正挂载 IM 通道（扩展阶段）；
- `serve` 的 REST 端点（第 26 节）；
- 定时任务的注册与调度（第 25 / 28 节）；
- 工具注册表与内置工具（第 20 节）；
- 长期记忆的读写（第 22 节）；
- 沙箱（第 24 节）；
- 配置与密钥的加密存储（扩展阶段）。

## Clarifications

### Session 2026-10-13

- Q: `oryxos init` 要不要顺手生成一个默认 Agent（`.oryxos/agents/default/AGENT.md`）？ → A: **不生成**。课件场景（`init` → `profile create weather` → `chat --profile weather`）、`CLAUDE.md` 工作区目录树（`agents/` 为空目录）、技术方案 §11.2（「在 `.oryxos/agents/<name>/` **放**一个目录」）三处一致；§8.1 结尾「生成默认 Profile」判为旧措辞。落到仲裁 14。
- Q: 本节的 `oryxos serve` 与 `oryxos gateway` 做到哪一步？ → A: **都只启动 Spring 上下文并前台常驻**，不引入 `oryxos-web`、不起 HTTP 端口、不挂 Channel；REST 端点归第 26 节、调度归第 25/28 节、IM 通道归扩展阶段。落到仲裁 15，并连带把 `oryxos-cli` 的模块依赖收窄为六个（去掉 web）。
- Q: `application.yaml` 放哪？ → A: **留在 `oryxos-boot` 不动**。代价是本节交付不了 FR-015 的密钥就绪状态与 `status` 的 Provider 就绪段（要等第 26 节），`provider list` 改列底座支持的厂商全集 + 各 Agent 实际引用的厂商名，就绪一律标「未接入（第 26 节）」。落到仲裁 6 与仲裁 11。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 会话：一个三元组一条历史，重启还在 (Priority: P1)

开发者昨天跟 `weather` 这个 Agent 聊了几轮，今天再 `oryxos chat --profile weather` 进来，上次的内容还在，接着聊就是。同一个人用 CLI 和用 HTTP 分别跟同一个 Agent 对话，是**两条互不相认的历史**——接入渠道不同，历史不该被混成一条。

**Why this priority**: 这是本节唯一值得自动化验收的部分，也是后面所有入口共用的地基。它自带两条最值钱的边界：**幂等**（多轮对话靠它串起来，取不到同一条就等于每句话开一个新会话）与**隔离**（三元组任一不同即不同会话，拼错一个分隔符就会出现两条互不相认的历史，且这种错在单请求测试里永远不报错）。

**Independent Test**: 对同一个三元组连续取两次会话，断言拿到的会话标识相同；把 channel / user / profile 各改一处，断言拿到的会话标识都与原值不同。全程不访问网络。

**Acceptance Scenarios**:

1. **Given** 一个可用的会话存储，**When** 用同一个三元组连续取两次会话，**Then** 两次拿到的是同一个会话（标识相同）。
2. **Given** 同上，**When** 只把 channel 改掉、user 与 profile 不动，**Then** 拿到的是**另一个**会话。
3. **Given** 同上，**When** 只把 user 或只把 profile 改掉，**Then** 同样拿到另一个会话。
4. **Given** 任意入口，**When** 它需要一条会话，**Then** 它只提供三元组，标识由持久化接缝内部拼出——入口侧不存在任何拼字符串的代码。

---

### User Story 2 - 会话落库与读回：历史一条不少、重启还在 (Priority: P1)

一次对话结束后，完整历史被存进库里。第二天进程重启（新建上下文）再查，历史还在；历史里既有模型的文本回复，也有它请求过的工具调用与对应的工具结果，读回来一字不差。

**Why this priority**: 「可审计」是 OryxOS 的差异化卖点（章程原则 VI），而会话历史是它最直接的用户可见面。序列化口径一旦定歪（比如丢了工具调用字段、或者时间戳格式漂移），第 25/26 节的跨入口复用会全线返工。

**Independent Test**: 用**手工建表脚本**建出一张空的会话表（不是靠框架自动建表），存进一条含「用户消息 + 带工具调用的模型回复 + 工具结果」的会话，读回后逐条比对消息的角色、内容、工具调用、工具调用标识与时间戳；随后新建一个全新的上下文（模拟进程重启）重查，断言历史仍在、内容不变。

**Acceptance Scenarios**:

1. **Given** 手工建表脚本建出的空会话表，**When** 存进一条会话再读回，**Then** 读到的会话标识与所属 Agent 与写入时一致。
2. **Given** 一条含用户消息、模型回复（含文本与工具调用）、工具结果的会话，**When** 存进再读回，**Then** 消息条数、顺序、角色、内容、工具调用与工具调用标识**逐条一致**。
3. **Given** 内容里含中文、换行与引号的消息，**When** 存进再读回，**Then** 内容逐字一致。
4. **Given** 已经存过一条会话，**When** 新建一个全新的上下文（模拟进程重启）重查，**Then** 那条历史仍在且内容不变。
5. **Given** 一个从未存过的会话标识，**When** 按标识查，**Then** 得到「不存在」而不是异常。

---

### User Story 3 - 轻命令秒回、重命令才启动引擎 (Priority: P2)

`oryxos profile list` 只是列个目录，敲下去立刻出结果，不该等 4 秒；`oryxos chat` 要调模型、要跑引擎，等引擎起来是应该的。这条分流在动手时就定下来，不是事后优化——不然要么全都慢，要么后面改起来伤筋动骨。

**Why this priority**: 它是 CLI 可用性的主要来源（章程「易接入」）。但它不阻塞 US-1/US-2，故排在 P2。

**Independent Test**: 在进程级断言轻命令（`init`、`status`、`profile *`、`provider list`、`tool list`、`session list`）执行完毕时不产生 Spring 上下文启动痕迹，重命令（`chat`、`serve`、`gateway`）会产生。

**Acceptance Scenarios**:

1. **Given** 一个已初始化的工作区，**When** 执行任一条轻命令，**Then** 命令完成且全程没有 Spring 上下文启动。
2. **Given** 同上，**When** 执行 `chat --message "..."`，**Then** Spring 上下文被启动，模型调用经第 16 节的按名路由发起。
3. **Given** 任一条命令，**When** 加 `--help`，**Then** 打印用法并正常退出。

---

### User Story 4 - 起项目、造 Agent、看情况 (Priority: P2)

开发者在一个空目录里敲 `oryxos init`，得到一份完整的工作区；敲 `oryxos profile create weather` 造一个 Agent；敲 `oryxos profile list` 看有哪些 Agent、哪个是坏的；敲 `oryxos session list` 看聊过哪些会话；敲 `oryxos status` 看工作区与运行环境是否就绪。

**Why this priority**: 这是「业务方放一个目录就能跑」的最小闭环入口，也是 Demo 一的起手式。同样不阻塞 US-1/US-2，故排在 P2。

**Independent Test**: 在一个空目录里跑 `init`，断言工作区目录树与数据库文件齐备；再跑一次，断言既有文件一个都没被覆盖且输出「已有 / 新建」两份清单；造一个 Agent 后 `profile list` 能列出它、`profile show` 能展示派生结果、`profile delete` 能删掉它。

**Acceptance Scenarios**:

1. **Given** 一个空目录，**When** 执行 `init`，**Then** 工作区目录树与数据库文件齐备，退出码 0；**且 `agents/` 是空目录**（不生成默认 Agent，仲裁 14）。
2. **Given** 一个刚 `init` 完、还没有任何 Agent 的工作区，**When** 直接执行 `chat`，**Then** 提示「没有可用 Agent，先 `oryxos profile create <name>`」，退出码 1。
3. **Given** 一个已初始化的工作区，**When** 再次执行 `init`，**Then** 既有文件一个都没被覆盖，输出「已有 / 新建」两份清单，退出码 0。
4. **Given** 一个已初始化的工作区，**When** `profile create weather` 后 `profile list`，**Then** 列表里有 `weather`。
5. **Given** 一个写坏的 Agent（frontmatter 非法），**When** `profile list`，**Then** 它被列为无效并**附上原因**。
6. **Given** 一个已存在的 Agent 名，**When** 再次 `profile create` 同名，**Then** 报错且不覆盖，退出码 1。
7. **Given** 一个非工作区目录，**When** 执行需要工作区的命令，**Then** 提示先跑 `oryxos init`，退出码 1。

---

### Edge Cases

- **非工作区目录执行需要工作区的命令**：提示先跑 `oryxos init`，退出码 1；不得抛栈。
- **工作区里一个 Agent 都没有**：`chat` 未指定 `--profile` 时报错说明「没有可用 Agent」，并提示先 `oryxos profile create`。
- **工作区里多于一个 Agent 且未指定 `--profile`**：报错并**列出可选名**（frontmatter 没有默认字段，唯一即默认，多者必须显式指定）。
- **`--profile` 指定的 Agent 不存在**：报错并列出可选名，退出码 1。
- **交互中输入流结束（Ctrl+D / 管道读完）**：等同 `/quit`，正常退出（退出码 0）。
- **交互中输入空行**：跳过，不交给引擎（不产生一条空消息）。
- **`init` 重复执行**：既有项一律不覆盖，输出两份清单。
- **`profile delete` 未加 `--force` 且用户未确认**：不删除，正常退出。
- **会话历史为空（首次）**：读回得到空消息列表，不报错。
- **内容含中文 / 换行 / 引号 / emoji 的消息**：序列化回读后逐字一致。
- **数据库文件不存在**（未 `init`）时执行查库的轻命令：提示先跑 `oryxos init`，不抛栈。
- **`init` 之后工作区里一个 Agent 都没有**：`chat` 无 `--profile` 时提示「没有可用 Agent，先 `oryxos profile create <name>`」（退出码 1）；`profile list` / `session list` / `provider list` 正常输出空表，不报错。
- **`provider list` 遇到 Agent 引用了底座不支持的厂商名**：该 Agent 标为「不支持」并给出厂商名，命令本身仍正常退出（退出码 0）——配置有问题不等于命令执行失败。
- **`serve` / `gateway` 在无 Agent 的工作区启动**：仍能启动并常驻（本模式不依赖 Agent 存在），提示本模式待接入什么。

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: `oryxos init` MUST 在当前目录创建工作区：Agent 目录、公共 Skill 库、记忆目录与记忆文件、Bootstrap 三件套、MCP 配置模板、会话/日志/产出三个子目录，并建出数据库与全部表（走手工建表脚本）。MUST 幂等——已存在的目录与文件一律不覆盖；MUST 输出「已有 / 新建」两份清单。MUST NOT 生成任何默认 Agent（仲裁 14）——Agent 目录建出来是空的，`init` 之后直接敲 `chat` 提示「没有可用 Agent，先 `oryxos profile create <name>`」是预期行为。
- **FR-002**: CLI MUST 提供 **15** 个子命令且每个 `--help` 可用：`init`、`status`、`chat`、`serve`、`gateway`、`profile list|create|show|delete`、`notify-channel add|list|remove`、`provider list`、`tool list`、`session list`。
  > **2026-10-15 回修**：原为 12 条，第 19 节交付后补强② 新增 `notify-channel add|list|remove`（契约 §2.12）。原 12 条**逐字未动**。
- **FR-003**: 命令 MUST 按轻重分流：轻命令（`init`、`status`、`profile list/create/show/delete`、`notify-channel add/list/remove`、`provider list`、`tool list`、`session list`）MUST NOT 启动 Spring 上下文，直接走文件与数据库；重命令（`chat`、`serve`、`gateway`）MUST 启动。分流判据只有一条：这条命令要不要调模型、跑引擎。
  > **2026-10-15 回修**：`notify-channel` 三条按同一条判据归轻——注册一个通知渠道不调模型、不跑引擎（契约 §1）。
- **FR-004**: `chat` MUST 支持交互式多轮对话：读一行 → 交给引擎处理 → 打印结果，循环往复，直到 `/quit` 或输入流结束。`--profile <name>` 指定 Agent；缺省时取工作区**唯一**的 Agent；多于一个且未指定 MUST 清晰报错并列出可选名。
- **FR-005**: `chat` MUST 支持 `--message "<text>"` 单条模式：输出响应后正常退出（退出码 0），不进入交互循环。
- **FR-006**: 会话标识 MUST 由 channel、user、profile 三段联合生成；生成逻辑 MUST 只存在于持久化接缝的**唯一一处**；所有入口 MUST 只提供三元组，MUST NOT 自行拼接字符串。
- **FR-007**: 取或建会话 MUST 幂等——同一三元组历次调用 MUST 返回同一个会话；三元组任一不同 MUST 是不同会话。
- **FR-008**: 会话 MUST 能落库与读回。对话历史 MUST 整体序列化后存进**一列**（核心阶段不按条拆表）；读回后消息的条数与顺序 MUST 与写入前一致。
- **FR-009**: 持久化接缝 MUST 只对外提供三个操作：取或建（三元组）、按标识取、保存。按标识取不到时 MUST 返回「不存在」而非抛异常。
- **FR-010**: 会话落库的每一行 MUST 至少能表达：会话标识（主键）、所属 Agent 名、接入渠道、用户标识、序列化后的对话历史、状态、创建时间、最后活跃时间、归档时间。
- **FR-011**: `profile create <name>` MUST 生成合法的最小 Agent 定义模板（frontmatter 占位 + 正文引导注释）；同名已存在 MUST 报错且不覆盖。
- **FR-012**: `profile list` MUST 列出全部 Agent 的名称、描述、厂商与有效性；无效的 MUST 附上原因。
- **FR-013**: `profile show <name>` MUST 展示派生后的完整配置与校验结果；Agent 不存在 MUST 报错。
- **FR-014**: `profile delete <name>` MUST 需确认；`--force` MUST 跳过确认；核心阶段为物理删除目录。
- **FR-015**: `provider list` MUST 列出「底座支持的厂商全集」（取自 `ProviderProperties.SUPPORTED_PROVIDERS`）与「工作区各 Agent 实际引用的厂商名」，并标出引用名是否在支持集合内。**密钥就绪状态本节 MUST 标注「未接入（第 26 节）」**，MUST NOT 编造 `ready` / `missing`，MUST NOT 打印、记录或以任何形式回显密钥值（仲裁 6 / 11）。
- **FR-016**: `session list` MUST 列出会话标识、所属 Agent、接入渠道、用户、状态、最后活跃时间，并按最后活跃时间倒序。
- **FR-017**: 退出码 MUST 统一：参数错误 2 并打印用法；运行错误 1 并给清晰中文报错；成功 0。
- **FR-018**: 需要工作区的命令在非工作区目录执行时 MUST 提示先跑 `oryxos init`（退出码 1），MUST NOT 抛未处理异常。
- **FR-019**: 本节起 `chat` MUST 端到端可跑：模型调用 MUST 走第 16 节的**按名显式映射**（MUST NOT 按类型扫描容器 Bean），自动工具执行 MUST 保持关闭。
- **FR-020**: 工具查询与长期记忆两个接缝本节 MUST 提供空实现（查不到任何工具 / 永远没有长期记忆）；空实现 MUST 以「真实实现一落地即自动让位」的方式声明，MUST NOT 形成第二处真相。
- **FR-021**: 三种运行模式（`chat` / `serve` / `gateway`）MUST 共享同一份 Agent 配置与同一套会话存储。本节只把「模式」立起来：`serve` 与 `gateway` 都只启动 Spring 上下文并前台常驻（Ctrl+C 退出），打印本模式待接入什么、归哪一节；MUST NOT 起 HTTP 端口、MUST NOT 挂载任何 Channel（仲裁 15）。`serve` 的 `--port` 选项本节 MUST 出现在 `--help` 中并接受解析，但只回显「端口在第 26 节启用」——上游 `contracts/cli.md` 已把它定为对外契约，MUST NOT 静默吞掉。
- **FR-022**: 会话标识 MUST 与写进审计行（模型调用、工具调用）的会话标识是**同一个值**。
- **FR-023**: 本节 MUST NOT 引入响应式编程或自建线程池；执行模型为同步阻塞 + 虚拟线程。

### Key Entities

- **会话（Session）**：一次对话的上下文容器。本节在原内存态形态上**新增两个字段**：接入渠道（channel）、用户标识（userId）——它们是落库与隔离的必需项（仲裁 2）。仍保持「只追加」语义。
- **会话消息（SessionMessage）**：会话历史的一条。角色、内容、该轮请求的工具调用、工具调用标识、时间戳。口径与 `specs/000-runtime-kernel/data-model.md` A1 的 `messages_json` 元素一致。
- **会话记录（`sessions` 表）**：会话的持久化形态。主键为会话标识，另有所属 Agent 名、接入渠道、用户标识、序列化后的对话历史、状态、创建/最后活跃/归档三个时间戳。**表由手工建表脚本创建**，不依赖框架自动迁移。
- **工作区（`.oryxos/`）**：`oryxos init` 创建的目录树与数据库文件。它是本节所有轻命令的输入。
- **子命令**：CLI 的 **15** 个入口，每个自带帮助与退出码约定（2026-10-15 由 12 扩为 15，见文件头修订记录）。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 轻命令从敲下到输出在**人感知不到等待**的量级（秒级以内），且执行全程**不启动 Spring 上下文**；重命令启动 Spring 上下文（启动日志可见）。**本条只判「分流是否正确」**（**15** 条命令各自该起 / 不该起上下文，正确率 **15/15**）；命令本身是否存在与 `--help` 是否可用由 SC-006 判，两条不重复计数。
  > **2026-10-15 回修**：分母由 12 改为 15，新增的三条 `notify-channel` 归轻（契约 §1）。
- **SC-002**: 同一三元组连续 N 次取会话，得到的**不同**会话标识数为 **1**（幂等）。
- **SC-003**: 三元组三处任一改动，得到的会话标识**必然**与原值不同（隔离率 100%）。
- **SC-004**: 会话落库再读回，消息条数与顺序一致率 **100%**（含工具调用与工具结果字段）。
- **SC-005**: 进程重启（新建上下文）后，已存会话仍可查到（丢失率 **0**）。
- **SC-006**: **15** 个子命令全部可执行且 `--help` 可用（覆盖率 **15/15**）。**本条只判「命令存在且帮助可用」**，不判分流。
  > **2026-10-15 回修**：分母由 12 改为 15。新增三条的「存在且已挂进命令树」由 `CliCommandTreeTest` 钉死（它断言命令树的叶子恰好是契约 §1 的那 15 条——只断言「新增的在」而不钉总数，漏挂或误挂都能溜过去）。
- **SC-007**: 需要工作区的命令在非工作区目录执行时，**100%** 给出「先跑 `oryxos init`」提示并退出码 1。
- **SC-008**: 密钥值在命令输出、结构化日志与报错信息中的出现次数为 **0**。
- **SC-009**: 本节全部自动化测试在 `mvn test` 下全绿，且不访问网络。

## Assumptions

- **依赖第 16 / 17 节的成果**：按名路由不串台的模型调用、已关闭自动工具执行的请求组装、`llm_calls` / `tool_invocations` 两张审计表与写入路径、`Profile` 体系与 `ProfileLoader`（从 Agent 目录派生 Profile，含环境变量占位解析）、ReAct 引擎四件套（`ReActLoop` / `PromptBuilder` / `ToolExecutor` / `AgentService`）与 core 侧四个接缝接口。
- **持久化用 SQLite，表由手工脚本创建**：不依赖框架的自动迁移（章程附加约束 + `CLAUDE.md` 工程提示）。本节的会话表并入既有建表脚本。
- **对话历史整体序列化存一列**：核心阶段不按条拆表（课件明确）。序列化复用 core 现有的 JSON 传递依赖，**不新增第三方依赖**。
- **工作区路径为当前工作目录下的 `.oryxos/`**：与既有数据源配置一致，不引入可配置的工作区根。
- **CLI 的用户标识固定为字面量 `local`、渠道固定为字面量 `cli`**：`docs/TechnicalSolution.md` §9.2 与 `specs/000-runtime-kernel/data-model.md` A1 已把这两个值定死（`channel ∈ cli/api/scheduler/gateway`；CLI 的 `user_id` 固定 `local`），故 CLI 的会话标识形如 `cli:local:<profile>`。REST 接入的默认用户（`default`）与定时触发的固定用户（`scheduler`）由各自的节引入，本节只落 CLI 这一档。
- **会话表的列名与长度逐字保真**：`session_id` VARCHAR(128) 主键、`profile_name` VARCHAR(128)、`channel` VARCHAR(32)、`user_id` VARCHAR(128)、`messages_json` TEXT、`status` VARCHAR(16)、`created_at` / `last_active_at` / `archived_at` TIMESTAMP——全部照 `specs/000-runtime-kernel/data-model.md` A1 抄，**不新增列、不改类型**。时间戳一律 ISO-8601 本地时区（同 A1 末条）。
- **序列化失败视为内部错误，不落库**（A1 明文校验规则）：`messages_json` 序列化失败时 MUST 上抛为内部错误，MUST NOT 写入半截数据。
- **本节不引入新的第三方依赖**：命令行解析用骨架期已定的 Picocli；数据库直连复用既有的 SQLite 驱动；轻命令读 Agent 目录复用既有的 YAML 解析库（`ProfileLoader` 内部已用）。**不读 `application.yaml`**（仲裁 6），故不需要额外的配置读取路径。
- **`serve` 与 `gateway` 本节只是「把模式立起来」**：两条命令都只启动 Spring 上下文并前台常驻，REST 端点归第 26 节、调度归第 25/28 节、IM 通道挂载归扩展阶段。本节保证它们能起来、能与 `chat` 共享同一份配置与存储（仲裁 15）。
- **`oryxos-cli` 的模块依赖收窄为六个**：core / provider / tool / memory / storage / channel-cli（仲裁 5）。**不含 `oryxos-web`**——本节没有任何命令需要 Web 容器（仲裁 15）。`oryxos-boot` 仍是唯一的 fat JAR 聚合模块，`java -jar oryxos-boot/target/oryxos.jar` 的启动路径不变。
- **`init` 不生成默认 Agent，工作区里 `agents/` 建出来是空的**（仲裁 14）：这是 `chat` 缺省 Agent 规则（FR-004）与「一个目录 = 一个 Agent」形态的共同结果，不是遗漏。
- **归档不在本节范围**：`DELETE /api/v1/sessions/{id}` 归第 26 节；本节落库的会话状态恒为 `active`、归档时间为空。
- **工具与记忆空实现是**临时装配**（仲裁 8）：第 20 / 22 节的真实实现一落地即自动让位，本节不保留第二处真相。
- **每个 user story 完成后打 git commit**，同步时机由用户决定（本节流程内不自动 commit / push）。
