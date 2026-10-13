# Implementation Plan: OryxOS 核心阶段 —— Agent OS 运行时内核

**Branch**: `000-runtime-kernel` | **Date**: 2026-10-10 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/000-runtime-kernel/spec.md`;`docs/TechnicalSolution.md`(How)+ `docs/AiProgrammingGuide.md`(实施拆解)

## Summary

交付 OryxOS 核心阶段运行时内核:五大核心能力(对接 LLM / ReAct 循环 / Memory / Plugin Tool / Web Service)+ 支撑设施(工作区、CLI 12 命令、定时任务第三触发源、SQLite 持久化与审计、配置与密钥加载),按 5 个 user story 依赖序(US-1 → US-2 →(US-3 ∥ US-4)→ US-5)推进,每个 story 有可演示 Demo,最终以 3 个每日自动验收 Demo 收口。

技术路线(全部来自技术方案第 1 章 7 个关键决策,本 plan 不重新发明):JDK 21 + Spring Boot 3.x 单体单二进制;自实现 ReActLoop + PromptBuilder + ToolExecutor,Spring AI 只用 Provider 抽象 / 协议转换 / `@Tool` schema 生成三件事且禁用自动 tool 执行;同步阻塞 + virtual thread;`OryxTool` 统一抽象 + 单一 `oryxos-tool` 模块;`Sandbox` 接口 + `WhitelistSandbox`;SQLite + Spring Data JPA + `MEMORY.md`;审计两表 day one 落库。在已有 9 模块骨架与契约地基(OryxTool / Sandbox / NotifyChannelAdapter / LongTermMemoryStore + 两张审计表)上实施,不另起炉灶。

## Technical Context

**Language/Version**: Java 21(`maven.compiler.release=21`,父 POM 已锁定;禁用 JDK 21 不可用机制如 SecurityManager)

**Primary Dependencies**(版本经 2026-10-10 联网核实,依据见 [research.md](research.md) 外部核实节):

- Spring Boot **3.3.5 → 3.5.16**(BOM):Spring AI 1.1.8 依赖 Boot 3.5.x,US-1 第一批任务执行 3.x 内升级(章程允许);升级后 sqlite-jdbc 改由 BOM 托管(3.49.1.0,删骨架显式 3.46.1.3)、hibernate-community-dialects 跟随 `${hibernate.version}`(6.6.53.Final,删骨架显式 6.5.3.Final)
- Spring AI **1.1.8**(spring-ai-bom)+ `spring-ai-starter-model-openai`(DeepSeek/Kimi 统一走 OpenAI 兼容 base-url;Kimi 专用模块已随上游移除)+ Spring AI Alibaba **1.1.2.3**(spring-ai-alibaba-bom + dashscope starter,通义及百炼兼容模型)——只用 Provider 抽象/协议转换/`@Tool` schema 生成,`internalToolExecutionEnabled(false)` 禁自动执行
- MCP Java SDK `io.modelcontextprotocol.sdk:mcp`(版本跟 spring-ai-bom 托管 = 0.18.3,不显式覆盖 2.0.1;stdio + SSE 均支持)
- springdoc-openapi-starter-webmvc-ui **2.9.1**(Boot 3.x 线;3.x 系 Boot 4 专用)
- 骨架既有不动:picocli 4.7.7(已是最新)、maven-shade 3.6.0、surefire 3.5.1
- 静态检查工具链(章程强制,接入 `mvn verify`):Spotless(google-java-format)+ 阿里 P3C(p3c-pmd)+ Checkstyle + SpotBugs/Find Security Bugs + OWASP Dependency-Check
- 随 Spring Boot 引入:SnakeYAML、Logback/SLF4J、Spring MVC、Spring Data JPA、Micrometer(仅核心指标,Prometheus 暴露放扩展)

**Storage**: SQLite(`.oryxos/oryxos.db`,手工 `schema.sql`,`ddl-auto=none`,核心阶段结束 6 张表)+ 文件系统(Agent 目录 / Bootstrap / MEMORY.md / 两个 yaml)/ 内存派生(ProfileRegistry、Provider 映射表、ProfileContext)——见 [data-model.md](data-model.md)

**Testing**: JUnit 5 + Spring Boot Test + MockMvc(REST)+ 自实现 `FakeChatModel`(脚本化响应的测试档 ChatModel,E2E 不依赖真实 API key);真实 Provider 冒烟走 [quickstart.md](quickstart.md) 人工验证;章程质量门禁:每个功能模块至少一个端到端测试用例

**Target Platform**: Linux 主流发行版单节点;单 fat JAR `java -jar` 启动

**Project Type**: cli + web-service(Spring Boot 单体多模块)

**Performance Goals**: ≥100 并发会话;会话创建 P99 ≤ 200ms;内部转发开销 ≤ 50ms(不含 LLM 厂商延迟);≥10 Agent 并存稳定 4 小时

**Constraints**: 同步阻塞 + virtual thread(禁响应式);SSE/异步 Tool/认证/限流/RBAC 放扩展;白名单为劝阻级防线;单条消息 ≤32KB、历史 ≤100 条、调用 ≤60s;凭证一律 `${ENV_VAR}` 占位

**Scale/Scope**: 9 个 Maven 模块、12 个 CLI 命令、10 个 REST 端点、9 个内置 Tool、6 张 SQLite 表;4 周 × 3 小时节奏

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 检查点 | 结论 |
| ------ | -------- | ------ |
| I. JDK 21 + Spring Boot 3.x 单体单二进制 | 父 POM 已锁 release=21 + Boot 3.3.5;同步阻塞 + virtual thread(`spring.threads.virtual.enabled=true`),不引入响应式;shade/spring-boot 插件出 fat JAR | ✅ PASS |
| II. 核心阶段只交付运行时内核 | plan 范围 = 五大能力 + 支撑设施;扩展项(SSO/多租户/审计查询/v2 调度端点/GraalVM/Prometheus)显式排除;验收以 3 个每日 Demo 为准 | ✅ PASS |
| III. 引擎自实现,Spring AI 只用一半 | research D2/D3:禁用自动 tool 执行机制 + provider name→ChatModel 显式映射表(禁类型扫描)均已立项核实,US-1 任务卡会再次点明 | ✅ PASS |
| IV. Agent 目录不是 Tool | `AgentLoader`/`ContextLoader` 归 `oryxos-core`;Skill 绑定 = 软连接唯一真相源;shell argv 直传 + 解释器仅管理员显式白名单 | ✅ PASS |
| V. Tool 统一抽象、单一模块 | 契约 `OryxTool` 已在 core;内置/MCP/Notify/Sandbox/Registry 全部归 `oryxos-tool`,不拆;三档取舍零代码优先 | ✅ PASS |
| VI. 审计 Day One 落库 | 两张审计表实体 + schema 骨架期已建;ToolExecutor/ProviderService 写入路径 + 钟推同路径 + Sandbox 拒绝复用已在 data-model A2/A3 固化 | ✅ PASS |
| VII. 接口先行,按档演进 | Sandbox/NotifyChannelAdapter/LongTermMemoryStore 中立契约已在骨架;核心阶段各挂一档实现(Whitelist/Webhook/Markdown),升级只新增实现类 | ✅ PASS |
| VIII. 数据私有,状态外置、存储从简 | SQLite + MEMORY.md 默认后端;实例无状态;凭证 `${ENV_VAR}`;向量检索不进核心阶段(见下方仲裁表 #1) | ✅ PASS |
| IX. 三触发源一条链路 | CLI/Web/Scheduler 均汇入 `AgentService.process`;gateway 守护语义经 Clarification Q2 确认 | ✅ PASS |
| X. 每 story 有可演示 Demo | quickstart.md V1~V5 + D1~D3 覆盖;`/speckit-analyze` 每 story 结束必跑 + git commit | ✅ PASS |

**无违规项,Complexity Tracking 为空。**(静态检查工具链是章程自身强制的工程基线,非额外复杂度。)

### 输入口径仲裁(文档冲突 → 本 plan 取值)

| # | 冲突 | 仲裁依据 | 本 plan 取值 |
| --- | ------ | --------- | ------------- |
| 1 | 技术方案 §5.1(015 演进)称核心阶段一次交付三档记忆后端 + embedding 语义检索;章程 VIII 与 spec FR-031/Assumptions 定 MEMORY.md 默认、向量检索不进核心 | 章程效力最高 + spec 经澄清确认 + YAGNI | 核心阶段只交付 `MarkdownMemoryStore`;`SqliteMemoryStore`/`Mem0MemoryStore`/语义检索放扩展;`LongTermMemoryStore` 契约(含 capabilities/archivalEntries)保持骨架现状不改 |
| 2 | 技术方案 §1 决策七(025 演进)称表结构收编 Flyway 双轨;CLAUDE.md 当前状态为手工 schema.sql + `ddl-auto=none` | CLAUDE.md 当前状态 + 章程附加约束(不依赖 update 自动迁移) | 手工 `schema.sql`,新增 4 张表并入同一脚本;Flyway 属扩展演进 |
| 3 | 技术方案 ch10 列 14 模块(含 persona/knowledge/三 IM channel);CLAUDE.md 与章程定核心阶段 9 个 | 章程原则 II + CLAUDE.md | 9 个模块;扩展模块核心阶段不建 |
| 4 | `docs/class/` 讲义把 `notify_channels` 放 Profile frontmatter;CLAUDE.md + 技术方案 §8.2 定 SQLite 全局注册表 | CLAUDE.md + 技术方案(讲义为旧口径) | 全局注册表;注册入口按 Clarification Q1 = `notify_channels.yaml` 启动导入 |
| 5 | 技术方案 §8.5 含 `/api/v2/schedules*` 管理端点;spec Assumptions 与 CLAUDE.md 10 端点排除 | spec + CLAUDE.md | 核心阶段只落 `scheduled_tasks`/`task_executions` 两表 + 启停/状态;v2 端点放扩展 |
| 6 | 技术方案 ch10 称 CLI 13 子命令(025 加 `agent import`);CLAUDE.md 定 12 个 | CLAUDE.md + spec FR-080 | 12 个;`agent import` 属 025 扩展 |
| 7 | 指南 §4.4 建议 MCP 先 stdio、SSE 放扩展;技术方案 §6.4 写 stdio 或 SSE | 以最新技术方案为准 + 风险回退约定 | 契约保留两种 transport;实施 stdio 先行,SSE 若受 SDK 支持度阻塞则按"砍功能不砍链路"挪扩展并在 tasks 标注 |

## Project Structure

### Documentation (this feature)

```text
specs/000-runtime-kernel/
├── plan.md              # 本文件
├── research.md          # Phase 0:决策与外部核实
├── data-model.md        # Phase 1:6 表 + 文件 + 内存派生
├── contracts/           # Phase 1:对外契约
│   ├── rest-api.md      #   10 端点 + 统一信封
│   ├── cli.md           #   12 命令
│   ├── agent-directory.md # AGENT.md frontmatter / 软连接 / 两个 yaml / 工作区格式
│   └── sandbox-config.md  # 沙箱白名单配置(analyze U1)
├── quickstart.md        # Phase 1:V1~V5 + D1~D3 端到端验证
├── checklists/requirements.md
└── tasks.md             # /speckit-tasks 产出(本命令不生成)
```

### Source Code (repository root)

已有 9 模块 Maven 骨架(父 POM 聚合),本特性在其上填充业务实现,不新增模块:

```text
oryxos-core/          # 引擎与契约:OryxTool/ToolResult(已有)、Profile、Session、
                      # AgentLoader(deriveProfile)、ContextLoader、ReActLoop、PromptBuilder、
                      # ToolExecutor、AgentService、AgentScheduler、ProfileRegistry、ProfileContext、
                      # ToolCatalog / MemoryService(契约,analyze U2/U3)
oryxos-provider/      # 能力一:ProviderService、显式 provider 映射、Function Calling 适配、FakeChatModel(测试)
oryxos-memory/        # 能力三:MemoryService 门面实现(契约在 core)、LongTermMemoryStore 契约(已有)、
                      # MarkdownMemoryStore(核心阶段唯一后端)、MemoryTools(save_memory/recall_memory)
oryxos-tool/          # 能力四(三合一):FileTools/ShellTools/HttpTools/NotifyTools、
                      # ToolRegistry(实现 core ToolCatalog 契约)、McpClientService、McpToolAdapter、
                      # Sandbox 契约(已有)+ WhitelistSandbox、NotifyChannelAdapter 契约(已有)+ WebhookNotifyAdapter
oryxos-channel-cli/   # CliChannel(oryxos chat)
oryxos-web/           # 能力五:6 个 ApiController、GlobalExceptionHandler、ApiResponse 信封、OpenAPI
oryxos-storage/       # SQLite:实体 + Repository(审计两表已有;新增 Session/ScheduledTask/TaskExecution/NotifyChannel)、schema.sql
oryxos-cli/           # Picocli 主入口 OryxOsCli(已有)、12 子命令、ConfigLoader、InitCommand
oryxos-boot/          # Spring Boot 启动(已有,@EntityScan+@EnableJpaRepositories)、自动配置、依赖聚合
```

包根统一 `io.oryxlabs.oryxos.<module>.*`。依赖方向三条规则:

- 契约在 core、实现在能力模块,core MUST NOT 反向依赖能力模块(Sandbox/NotifyChannelAdapter 契约在 oryxos-tool 是既定例外)。
- **跨模块消费接缝**(analyze U2/U3):core 需消费能力模块服务时,接口(契约)放 core、实现放能力模块、Spring 注入——`ToolCatalog`(Tool 注册表查询契约,在 core;oryxos-tool 的 ToolRegistry 实现它,并经 Spring 收集全部 `OryxTool` Bean 汇聚三源)与 `MemoryService`(三层门面契约,在 core;实现在 oryxos-memory)均按此模式。oryxos-memory 只暴露 `OryxTool` Bean,MUST NOT 依赖 oryxos-tool;ToolExecutor 对沙箱拒绝按通用异常捕获,MUST NOT import oryxos-tool 的异常类。
- **oryxos-storage 是共享基础设施模块**(analyze U4):core 与各能力模块可直接依赖其实体与 Repository(审计写入、Session/调度持久化),不构成反向依赖。

**Structure Decision**: 沿用既有 9 模块骨架(章程原则 I/V 与指南 §1.2 强制与技术方案第 10 章一致),不拆不并;测试源码各模块 `src/test/java` 内,E2E 用例按「每模块至少一个」放置。

## 实施组织(与 tasks 的衔接)

`/speckit-tasks` 按 5 个 user story 组织 phase;本 plan 约定跨 story 的工程基线先行落地(US-1 第一批任务内):静态检查工具链接入 `mvn verify`、虚拟线程开启、`FakeChatModel`、审计写入路径打通。每个 story 结束:`/speckit-analyze` + git commit。

## Constitution Check(Phase 1 设计后复核)

契约(rest-api/cli/agent-directory)与数据模型产出后复核:10 原则维持 PASS——契约未引入实现外新承诺;`notify_channels.yaml` 注册入口、gateway 守护语义、审计脱敏三处澄清结果已分别落入 contracts 与 data-model;仲裁表 7 项在产出物中一致执行(如 contracts/agent-directory.md 无 frontmatter `notify_channels`/`skills` 字段、rest-api.md 无 v2 端点)。
