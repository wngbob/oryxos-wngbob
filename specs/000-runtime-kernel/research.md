# Research: OryxOS 核心阶段运行时内核

**目的**: 固化 plan 的全部技术决策(Decision / Rationale / Alternatives),并核实外部依赖版本与关键机制。D1~D13 为内部决策(依据技术方案/章程/spec/澄清结果,无需外部调研);「外部核实」节为联网核实结果(依赖版本与 Spring AI 机制)。

## 内部决策

### D1: ReAct 循环自实现,不依赖 Spring AI Agent 抽象

- **Decision**: `ReActLoop` + `PromptBuilder` + `ToolExecutor` 全部自实现(oryxos-core),核心循环约数十行 Java;不用 Spring AI 的 Agent/ advisor 高层抽象。
- **Rationale**: 章程原则 III;Agent 核心循环是系统中枢,必须完全可控,保留定制空间。
- **Alternatives**: 用 Spring AI ChatClient 的 tool-calling 高层 API —— 自动执行不可控,拒绝。

### D2: 禁用 Spring AI 自动 tool 执行的机制

- **Decision**: `ToolCallingChatOptions.builder().toolDefinitions(...).internalToolExecutionEnabled(false).build()`(`org.springframework.ai.model.tool`,1.1.8 源码已核实);调用层统一直调 `ChatModel.call(new Prompt(messages, options))`——**不用 ChatClient 高层 API**(它默认注册 ToolCallingAdvisor 会自动执行 tool);循环判 `chatResponse.hasToolCalls()`,从 `AssistantMessage` 取 tool 调用列表交 `ToolExecutor` 执行,结果以 `ToolResponseMessage` 回灌对话历史(回灌属协议转换,是"用一半"的合法部分);**不复用 Spring AI 的 `ToolCallingManager` 做执行**——执行必须走 ToolRegistry/ToolExecutor,保证 `tool_invocations` 审计单点埋点无遗漏。
- **Rationale**: 章程原则 III,全项目最易写错的一条(启用自动执行会导致 tool 被调两次);中立 `ToolCallingChatOptions` 而非各 Provider 私有 Options,符合显式映射 + 多 Provider 并存。
- **Alternatives**: ①ChatClient 高层 API —— 默认自动执行,拒绝;②`ToolCallingManager.executeToolCalls` —— 官方用户自控执行器,但执行路径绕开 ToolRegistry,审计与 Sandbox 钩子无法单点收口,拒绝(仅作备查);③裸调 HTTP —— 失去协议转换,违背"用一半"边界。
- **版本预警**: `internalToolExecutionEnabled` 在 Spring AI 2.0.0 已被移除(2.0 由 ToolCallingAdvisor + `spring.ai.chat.client.tool-calling.enabled` 接管);本项目锁定 1.1.8 不受影响,未来升 2.0 需改代码,届时重读本条。

### D3: Provider 显式映射的实现方式

- **Decision**: `application.yaml` 声明 `oryxos.providers.<name>.{type, base-url, api-key(${ENV_VAR}), model}`;`ProviderService` 启动时按 `type` 用对应 `ChatModel.Builder` 程序化构建实例,注册进显式 `Map<providerName, ChatModel>`;Profile 按 name 引用。不按类型扫描容器 Bean,不用 `@Qualifier` 为每个 provider 写配置类。
- **Rationale**: 章程原则 III + 技术方案 §3.2;同类型多 provider 并存无歧义,新增 provider 只改配置不改代码。
- **Alternatives**: ①容器扫描 `ChatModel` Bean —— 类型相同必有歧义(章程明令禁止);②每 provider 一个 `@Configuration` + `@Qualifier` —— 加 provider 要改代码,违背"配置即接入"。

### D4: 持久化 = SQLite + 手工 schema.sql + `ddl-auto=none`

- **Decision**: 6 张表全部手工脚本维护(骨架已有的 `db/schema.sql` 扩 4 张);`oryxos init` 内嵌执行建表;不引入 Flyway/Liquibase。
- **Rationale**: CLAUDE.md 当前状态 + 章程附加约束(SQLite ALTER 弱,不依赖 update 自动迁移);技术方案 025 的 Flyway 双轨属扩展演进(plan 仲裁表 #2)。
- **Alternatives**: Flyway 双轨(db/migration/{vendor}/)—— 025 扩展口径,核心阶段 YAGNI;`ddl-auto=update` —— 章程明确禁止依赖。

### D5: 长期记忆核心阶段只交付 MarkdownMemoryStore

- **Decision**: `memory.backend` 默认且核心阶段唯一实现 = `MarkdownMemoryStore`;契约 `LongTermMemoryStore`(含 `capabilities()` / `archivalEntries()`)保持骨架现状不改,Markdown 档返回 `MemoryCapability.KEYWORD`;`SqliteMemoryStore` / `Mem0MemoryStore` / HYBRID_BUILTIN 语义检索全部放扩展阶段。
- **Rationale**: 章程 VIII(向量检索禁入核心)+ spec FR-031(单一 Markdown 文件)+ YAGNI;契约已预留升级空间,扩展期新增实现类不动调用方。
- **Alternatives**: 技术方案 015 口径(核心阶段三档 + embedding 三路 RRF)—— 被章程 + spec 仲裁排除(plan 仲裁表 #1)。

### D6: 定时调度 = Spring `ThreadPoolTaskScheduler` + `CronTrigger` + 进程内锁

- **Decision**: 动态注册(不用 `@Scheduled` 静态注解);按 `(profile_name, schedule_key)` 稳定 `schedule_id` 维度的 `ReentrantLock` 防重叠(上次未完本次跳过);失败只记日志 + 走同一审计路径;钟推 session 固定 `scheduler:scheduler:<profile>`。
- **Rationale**: 技术方案 §8.5;触发规则来自 frontmatter 必须运行时注册;核心阶段单实例,无需分布式锁。
- **Alternatives**: Quartz —— 引入重型调度依赖,YAGNI;`@Scheduled` —— 编译期写死,无法按 Profile 动态生成。

### D7: MCP Client = 官方 MCP Java SDK,stdio 先行

- **Decision**: 直用官方 MCP Java SDK `io.modelcontextprotocol.sdk:mcp` 实现 `McpClientService`/`McpToolAdapter`,**版本跟随 spring-ai-bom 1.1.8 管理(0.18.3),不显式覆盖**——Spring AI 内部已在用 SDK 类,显式升 2.0.1 有共存冲突风险;0.18.x 同时支持 stdio 与 SSE client,契约 `transport: stdio|sse` 两种均可交付;实施顺序 stdio 先行(指南 §7.2:先以最简 server 验证连通)。
- **Rationale**: 技术方案 §1.2/§6.4;开放标准优先(章程附加约束);跟 BOM 走消除版本共存不确定性。
- **Alternatives**: ①显式用 SDK 2.0.1 —— 与 Spring AI 1.1.8 管理的 0.18.3 冲突,自担兼容,拒绝;②Spring AI MCP starter —— 它就是对 SDK 的包装,自实现 McpClientService 直用 SDK 少一层黑盒;③自实现 JSON-RPC —— 重复造轮。

### D8: 审计脱敏统一在写入路径,不在日志框架

- **Decision**: `ToolExecutor` / `ProviderService` 在写审计表与打日志前,统一过 `SensitiveDataMasker`(JSON 递归遍历,字段名命中 `authorization/token/api_key/password/secret` 大小写不敏感则值替换 `***`);不依赖 Logback PatternLayout 全局脱敏。
- **Rationale**: Clarification Q3;审计表与结构化日志同一套规则(FR-071/074),单点实现两处复用;PatternLayout 正则脱敏对 JSON 嵌套不可靠。
- **Alternatives**: 完整落库(内网接受)—— 与章程"凭证不落地"冲突;不记入参 —— 审计粒度受损,排查困难。

### D9: 测试策略 = FakeChatModel + 每模块 E2E

- **Decision**: 新增测试档 `FakeChatModel`(实现 `ChatModel` 接口,按脚本化规则返回固定响应/工具调用请求),E2E 与单测不依赖真实 API key;每功能模块至少 1 个端到端用例(章程质量门禁);REST 用 MockMvc;MCP 用本地 stdio 测试 server;真实 Provider(DeepSeek/Kimi)冒烟走 quickstart 人工验证。
- **Rationale**: 章程质量门禁 + CI 可重复;真实 LLM 不确定性与成本不该进构建。
- **Alternatives**: WireMock 挡 LLM HTTP —— 协议细节随 Spring AI 版本漂移,维护脆;全用真实 key —— CI 不可行。

### D10: 并发与 IO 模型 = virtual thread + SQLite 写入收敛

- **Decision**: `spring.threads.virtual.enabled=true`(Tomcat 与调度均受益);SQLite 侧按 E5 核实要点配置:HikariCP `maximum-pool-size=1`(库级写锁下最省心)+ 连接串 `busy_timeout` 兜底 + `journal_mode=WAL` + `foreign_keys=true`;写路径(Session 追加、审计、调度状态)为串行短事务,压测发现瓶颈再优化批量落盘(技术方案 §14 既定策略)。
- **Rationale**: 章程原则 I + 技术方案 §14;同步代码 + virtual thread 足以支撑 ≥100 并发会话;审计两表是高频写路径,单连接 + WAL 是 SQLite 标配防 `SQLITE_BUSY` 组合。
- **Alternatives**: 响应式 WebFlux —— 章程明禁;HikariCP 多连接写 SQLite —— 写锁竞争直接 `SQLITE_BUSY`。

### D11: CLI = Picocli 12 命令,轻/重命令分工

- **Decision**: 契约见 contracts/cli.md;轻命令直接文件 + JDBC 操作不启动 Spring 上下文;重命令才启 Spring。
- **Rationale**: 技术方案 §14(CLI 启动速度);章程 FR-081。
- **Alternatives**: 全部命令起 Spring —— 2~4s 启动对 `profile list` 不可接受;GraalVM 降启动 —— 扩展阶段。

### D12: gateway = 完整守护进程语义

- **Decision**: 挂载已配置全部入站 Channel(核心阶段仅 CLI Channel,非交互)+ 常驻钟推;渠道配置错误清晰报错;扩展 IM Channel 即插即用。
- **Rationale**: Clarification Q2 用户确认。
- **Alternatives**: 骨架实现/暂缓 —— 用户未选。

### D13: 静态检查工具链接入 `mvn verify`(工程基线,US-1 第一批任务)

- **Decision**: 父 POM 接入:Spotless(google-java-format,`spotless:check` 绑 verify,`spotless:apply` 本地修复)+ 阿里 P3C(p3c-pmd)+ Checkstyle + SpotBugs + findsecbugs-plugin + OWASP dependency-check-maven;任一失败阻断;pre-commit 跑 spotless;CI 跑 `mvn verify`。
- **Rationale**: 章程开发工作流强制项(用户批准的 C 部分 1~3)。
- **Alternatives**: 只接部分工具 —— 章程明文五件套全接;接进 CI 但不绑 verify —— 本地可绕过,与"失败阻断合并"不符。

## 外部核实

> 核实日期 2026-10-10;版本均以 Maven Central 权威元数据 + 官方文档/源码逐项核实,全部 6 项已核实、无未决项(原回退规则「接入时以 Maven Central 最新稳定版为准」未触发)。

### E1: Spring AI Alibaba(SAA)版本与坐标

- **选用**: SAA **1.1.2.3**(另有 1.1.2.4-security-fix 安全专项,可直接选它;2.0.0-M1.1 跟踪 Spring AI 2.0 / Boot 4,不适用)+ 上游 **Spring AI 1.1.8**。版本对应链:SAA 1.1.2.x ↔ Spring AI 1.1.x ↔ **Spring Boot 3.5.x**(Spring AI 1.1.8 依赖 Boot 3.5.15——**骨架的 Boot 3.3.5 必须升级到 3.5.16**,章程允许 3.x 内升级)。
- **坐标**: BOM `com.alibaba.cloud.ai:spring-ai-alibaba-bom:1.1.2.3`(import)+ `org.springframework.ai:spring-ai-bom:1.1.8`(import);通义 starter `com.alibaba.cloud.ai:spring-ai-alibaba-starter-dashscope`。
- **DeepSeek / Kimi 接法**: SAA **不提供** OpenAI 兼容 starter(全组 261 个 artifact 无 openai/deepseek 字样);Kimi 专用模块在 1.0.0-M7 后已移除。统一用上游 `org.springframework.ai:spring-ai-starter-model-openai:1.1.8` 配 `base-url` 指向各家兼容端点(DeepSeek 另有专用 starter `spring-ai-starter-model-deepseek`,但 OpenAI 兼容一种 type 覆盖两家,更贴合 D3 显式映射)。
- **坑**: 旧坐标 `spring-ai-openai-spring-boot-starter` 只发到 1.0.0-M6,M7 起改名 `spring-ai-starter-model-openai`,照抄旧博客会找不到。
- 依据: repo1.maven.org 的 spring-ai-alibaba-bom / spring-ai-starter-model-openai / spring-ai-starter-model-moonshot 元数据;java2ai.com/docs/versions。

### E2: 禁用自动 tool 执行的确切机制(已并入 D2)

- `ToolCallingChatOptions.internalToolExecutionEnabled(false)`(`org.springframework.ai.model.tool`,spring-ai-model 模块,@since 1.0.0),v1.1.8 源码核实存在;官方文档 "User-Controlled Tool Execution" 节给出同款示例。
- 标准写法:`ChatModel.call(new Prompt(messages, options))` → 判 `chatResponse.hasToolCalls()` → 自行执行 → 回灌;`ToolCallingManager` / `DefaultToolCallingManager` / `ToolExecutionResult` 均在 `org.springframework.ai.model.tool` 包(备查,不采用,理由见 D2)。
- **版本预警**: 该 API 在 Spring AI 2.0.0 已移除(2.0 改由 ToolCallingAdvisor + `spring.ai.chat.client.tool-calling.enabled=false` 接管);锁 1.1.8 无影响。
- 依据: docs.spring.io/spring-ai/reference/1.1/api/tools.html;upgrade-notes;v1.1.8 源码。

### E3: MCP Java SDK

- **最新稳定 2.0.1**(2026-08 上架);但 **Spring AI 1.1.8 的 MCP starter 管理的是 0.18.3**(spring-ai-mcp:1.1.8 → mcp:0.18.3 实证)。
- **决策(见 D7)**: 跟随 BOM 用 0.18.3,不显式覆盖 2.0.1,避免与 Spring AI 内部 SDK 类共存冲突;0.18.x 支持 stdio 与 SSE client 传输(官方文档:核心 `mcp` 模块提供 STDIO、SSE、Streamable HTTP client,无需外部 web 框架)。
- 坐标: `io.modelcontextprotocol.sdk:mcp`(版本由 BOM 托管);客户端场景这一个 artifact 即可。
- 依据: repo1.maven.org mcp 元数据;java.sdk.modelcontextprotocol.io/latest/client/;spring-ai-mcp:1.1.8 POM。

### E4: springdoc-openapi

- **选用 `org.springdoc:springdoc-openapi-starter-webmvc-ui:2.9.1`**(Boot 3.x 线最新);3.x 线是 Spring Boot 4 专用,勿选。
- 零配置路径: swagger-ui 在 `/swagger-ui.html`(重定向 `/swagger-ui/index.html`),OpenAPI JSON 在 `/v3/api-docs`,与 contracts/rest-api.md 口径一致。
- 依据: repo1.maven.org 元数据;springdoc-openapi README(Boot 大版本同步规则)。

### E5: SQLite + JPA 配置要点

- **版本**: sqlite-jdbc 最新 3.53.4.0,但 **Boot 3.5.16 BOM 已托管 3.49.1.0**——升级到 Boot 3.5.16 后**删除骨架显式版本**(当前 3.46.1.3),跟 BOM 走;dialect 坐标 `org.hibernate.orm:hibernate-community-dialects`(`org.hibernate.community` 组实证 404,骨架 groupId 正确),版本与 hibernate-core 严格同号,Boot 3.5.16 托管 6.6.53.Final——**骨架显式的 6.5.3.Final 改为 `${hibernate.version}` 跟随 BOM**;方言类 `org.hibernate.community.dialect.SQLiteDialect`。
- **配置要点(已并入 D10)**: ①url `jdbc:sqlite:<path>.db`,driver `org.sqlite.JDBC`,显式配 dialect 更稳;②**库级写锁:HikariCP `maximum-pool-size=1` + `busy_timeout` 兜底**——审计两表高频写必须处理,否则 `SQLITE_BUSY`;③保持 `ddl-auto=none` + 手工 schema.sql;④开 WAL(`journal_mode=WAL`)+ `foreign_keys=true`;实体不依赖数据库侧默认值;⑤手工脚本约定不变,不配 `spring.sql.init.mode=always`。
- 依据: repo1.maven.org sqlite-jdbc / hibernate-community-dialects / spring-boot-dependencies:3.5.16 元数据。

### E6: Picocli

- **4.7.7 即当前最新稳定**(2025-04 发布后无新版),骨架已锁,**无需变动**;shade 打 fat JAR 是标准做法,与 oryxos-cli 现状一致。
- 依据: repo1.maven.org picocli 元数据。

## 风险与回退

| 风险 | 影响 | 回退 |
| ------ | ------ | ------ |
| Spring AI 1.1.8 要求 Boot 3.5.x,骨架为 3.3.5 | US-1 前置 | 已决议:Boot 3.3.5 → 3.5.16(3.x 内升级,章程允许;E1/E5 版本对齐一并执行),列入 US-1 第一批任务 |
| MCP Java SDK 不成熟/stdio 进程管理踩坑 | US-4 方式二受阻 | 先交付内置 Tool + 方式三(@Tool Bean),方式二收缩到一个最简 stdio server 跑通;SSE 挪扩展 |
| 白名单被误当安全边界 | 安全预期错位 | 文档与 quickstart 明示"劝阻级";不可信代码等容器/microVM Runner |
| 外部核实个别项失败 | 版本未锁定 | 回退规则:接入时取 Maven Central 最新稳定并记录 |
