# Tasks: OryxOS 核心阶段 —— Agent OS 运行时内核

**Input**: Design documents from `/specs/001-runtime-kernel/`

**Prerequisites**: plan.md (required), spec.md (required), research.md, data-model.md, contracts/, quickstart.md

**Tests**: 测试任务**包含**——章程质量门禁强制「每个功能模块至少一个端到端测试用例」;E2E 统一用 `FakeChatModel`(D9),不依赖真实 API key;真实 Provider 冒烟走 quickstart 人工验证。不采用 TDD 先行(章程已明确移除脚手架 TDD 原则),E2E 任务排在各 story 实现任务之后。

**Organization**: 5 个 user story 按**依赖顺序**组织(US-1 → US-2 →(US-3 ∥ US-4)→ US-5),不使用 P1/P2/P3;定时任务(FR-050~053)不属于任一 story,单列跨 story phase;每个 story 以 `/speckit-analyze` + git commit 收尾(章程强制)。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行(不同文件、无未完成依赖)
- **[Story]**: 所属 user story(US1~US5);Setup / Foundational / 定时任务 / Polish 阶段无 story 标记
- 所有路径相对仓库根;包根统一 `io.oryxlabs.oryxos.<module>.*`

## 既有地基(不重复建)

Maven 9 模块骨架、契约接口(`OryxTool`/`ToolResult`、`Sandbox`/`SandboxAction`/`ActionType`、`NotifyChannelAdapter`/`NotifyTarget`、`LongTermMemoryStore`/`MemoryScope`/`MemoryCapability`)、审计两表实体 + Repository + `schema.sql`(tool_invocations / llm_calls)、`OryxosApplication`(@EntityScan + @EnableJpaRepositories)、`OryxOsCli` Picocli 主入口。本任务表在其上填充实现。

---

## Phase 1: Setup(工程基线,US-1 第一批)

**Purpose**: 版本对齐、静态检查门禁、运行时与存储基线——全部 story 的公共地基

- [ ] T001 升级 Spring Boot 3.3.5 → **3.5.16**(pom.xml;Spring AI 1.1.8 依赖 Boot 3.5.x,E1):删除骨架显式 sqlite-jdbc 3.46.1.3(改由 BOM 托管 3.49.1.0)、hibernate-community-dialects 版本改为 `${hibernate.version}`(6.6.53.Final);`mvn clean package` 全绿
- [ ] T002 静态检查五件套接入 `mvn verify`,任一失败即阻断(pom.xml + 各工具配置文件):Spotless(google-java-format,`spotless:check` 绑 verify)+ 阿里 P3C(p3c-pmd)+ Checkstyle + SpotBugs + findsecbugs-plugin + OWASP dependency-check-maven(D13)
- [ ] T003 父 POM dependencyManagement 导入 `spring-ai-bom:1.1.8` + `spring-ai-alibaba-bom:1.1.2.3`(pom.xml);oryxos-provider/pom.xml 加 `spring-ai-starter-model-openai` + `spring-ai-alibaba-starter-dashscope`;oryxos-tool/pom.xml 加 `io.modelcontextprotocol.sdk:mcp`(版本跟 BOM = 0.18.3,**不显式覆盖**);oryxos-web/pom.xml 加 `springdoc-openapi-starter-webmvc-ui:2.9.1`
- [ ] T004 [P] 配置 oryxos-boot/src/main/resources/application.yaml:`spring.threads.virtual.enabled=true`;SQLite 数据源 HikariCP `maximum-pool-size=1` + 连接串 `busy_timeout` + `journal_mode=WAL` + `foreign_keys=true`;`ddl-auto=none`(D10,防 `SQLITE_BUSY`)
- [ ] T005 [P] 扩展 oryxos-storage/src/main/resources/db/schema.sql 新增 4 表(data-model A1/A4/A5/A6 约束逐字):`sessions`(PK `session_id` = `channel:user:profile`,`status ∈ active/archived`)、`scheduled_tasks`(PK `schedule_id` 由 `(profile_name, schedule_key)` 生成且稳定,两列联合唯一,`enabled`/`retired` 状态机)、`task_executions`(关联 schedule_id 与 session_id,成败都记)、`notify_channels`(PK `name`,`type` 核心阶段仅 `webhook`)
- [ ] T006 [P] 新增 oryxos-boot/src/main/resources/logback-spring.xml:结构化 JSON 日志,覆盖每次 LLM 调用与 Tool 调用;全项目禁用 `System.out`(章程可观测条款)

**Checkpoint**: `mvn clean package` + `mvn verify` 全绿;虚拟线程与 SQLite 配置生效

---

## Phase 2: Foundational(阻断所有 story 的前置)

**Purpose**: 工作区、配置加载、领域模型、Agent 派生、脱敏单点、CLI 骨架——任何 story 开工前必须完成

**⚠️ CRITICAL**: 本阶段未完成前,任何 user story 不得开工

- [ ] T007 实现 `oryxos init` 幂等工作区初始化(oryxos-cli/src/main/java/io/oryxlabs/oryxos/cli/InitCommand.java,FR-001):创建 agents/skills/memory/sessions/logs/output 子目录 + `AGENTS.md`/`SOUL.md`/`USER.md` 模板 + `mcp_servers.yaml`/`notify_channels.yaml`/`sandbox.yaml` 配置模板(`sandbox.yaml` 契约见 contracts/sandbox-config.md)+ 含 `## 核心记忆`/`## 归档记忆` 两个分区 header 的空 `MEMORY.md`,并内嵌执行 schema.sql 建 `oryxos.db`;已存在项一律不覆盖,重复执行输出已有/新建清单
- [ ] T008 [P] 实现 ConfigLoader(oryxos-cli/src/main/java/io/oryxlabs/oryxos/cli/ConfigLoader.java,FR-012):统一加载敏感配置,`${ENV_VAR}` 占位解析,必填项 + 格式校验,缺失/非法时报错指明缺失的环境变量名、不打印密钥内容
- [ ] T009 [P] 实现核心领域模型(oryxos-core):`Profile`(data-model C 节字段逐字:name/description/identity{agent_name,prompt}/provider{name,model,temperature?}/tools[]/mcp_servers[]/channels[]/schedules[]/bootstrap[]/settings{max_iterations=10, max_history_turns=20};**`notify_channels` 不是 Profile 字段**)、`ProfileRegistry`(按 name 索引)、`ProfileContext`(ThreadLocal 承载当前请求 Profile,入口放置、finally 清除)
- [ ] T010 实现 AgentLoader.deriveProfile(oryxos-core/src/main/java/io/oryxlabs/oryxos/core/agent/AgentLoader.java,FR-002/004):SnakeYAML 解析 AGENT.md frontmatter → Profile;校验 Provider 存在、Tool 已注册、Channel 受支持、Bootstrap 文件存在;frontmatter 禁止项拒绝(`notify_channels`/`skills`/明文密钥);校验失败不阻断启动、记错误日志、该 Agent 不可用
- [ ] T011 [P] 实现 SensitiveDataMasker 脱敏单点(oryxos-core/src/main/java/io/oryxlabs/oryxos/core/audit/SensitiveDataMasker.java,D8 + Clarification Q3):JSON 递归遍历,字段名命中 `authorization`/`token`/`api_key`/`password`/`secret`(大小写不敏感)的值替换 `***`,其余原样;审计表写入与结构化日志共用此单点(FR-071/074)
- [ ] T012 [P] 实现 Session 实体 + SessionRepository(oryxos-storage,data-model A1):`messages_json` 元素结构 `{role, content, toolCalls?, toolCallId?, timestamp}`、`role ∈ system/user/assistant/tool`;状态机 `active → archived` 单向终态,归档会话 MUST NOT 再接收消息;写入前 profile_name 必须命中 ProfileRegistry
- [ ] T013 实现 CLI 轻/重命令分工机制与通用约定(oryxos-cli,FR-080/081 + contracts/cli.md):轻命令(init、profile list/create/show/delete、provider list、tool list、session list、status)直接走文件 + SQLite,不启动 Spring 上下文;重命令(chat/serve/gateway)才启动;参数错误退出码 2 打印用法、运行错误退出码 1 中文报错、成功 0;非工作区目录执行时提示先跑 `oryxos init`
- [ ] T014 实现 profile 四命令(oryxos-cli,FR-003):`profile create <name>` 生成最小 AGENT.md 模板(合法 frontmatter 占位 + 正文引导注释,已存在报错不覆盖)、`profile list`(name/description/provider/有效性,无效附原因)、`profile show <name>`(派生后完整 Profile + 校验结果)、`profile delete <name>`(确认后物理删除,`--force` 跳过确认)
- [ ] T015 [P] oryxos-boot 上下文启动冒烟 E2E(oryxos-boot/src/test):Spring 上下文 + JPA + SQLite + schema.sql 全链路起停一次(覆盖 oryxos-boot / oryxos-storage 两模块 E2E 门禁)
- [ ] T016 [P] oryxos-cli E2E 测试(oryxos-cli/src/test,章程质量门禁——analyze C1 补全):`oryxos init` 重复执行不覆盖已有项并输出已有/新建清单、profile create/list/show/delete 四命令全链路、轻命令(init、profile 四命令)不启动 Spring 上下文可独立执行(进程级断言);status/provider list/session list/tool list 就绪后并入本用例扩展

**Checkpoint**: `oryxos init` + profile 四命令可用;启动冒烟与 oryxos-cli E2E 通过——user story 实施可以开始

---

## Phase 3: User Story 1 - 对接 LLM(依赖顺序: US-1)

**Goal**: Agent 不感知具体厂商,按名路由 Provider,密钥走环境变量,每次调用落审计

**Independent Test**(spec US-1):配置 DeepSeek 与 Kimi 两家 Provider(密钥走环境变量),同一条 Prompt 分别请求两家均返回正常响应,且每次调用的 provider、模型、token 用量、耗时持久化留痕

- [ ] T017 [P] [US1] 实现 ProviderProperties 配置绑定(oryxos-provider/src/main/java/io/oryxlabs/oryxos/provider/ProviderProperties.java,D3):`oryxos.providers.<name>.{type, base-url, api-key(${ENV_VAR}), model}`;type 取值 `openai`(DeepSeek/Kimi 统一 OpenAI 兼容 base-url,E1)/ `dashscope`(通义及百炼兼容)
- [ ] T018 [US1] 实现 ProviderService(oryxos-provider/src/main/java/io/oryxlabs/oryxos/provider/ProviderService.java,FR-010/011/012):启动时按 type 用对应 `ChatModel.Builder` 程序化构建,注册进**显式** `Map<providerName, ChatModel>`——MUST NOT 按类型扫描容器 ChatModel Bean(章程 III);密钥缺失/非法给出指明缺失环境变量名的清晰报错;Provider 故障直接向 Agent 报错,不做 fallback / hedge racing(FR-013)
- [ ] T019 [US1] 实现 LLM 调用审计写入路径(oryxos-provider,FR-072/074):每次调用落 `llm_calls`(provider、model、prompt/completion/total tokens、duration_ms),写入前过 SensitiveDataMasker,同时落结构化 JSON 日志
- [ ] T020 [P] [US1] 实现 FakeChatModel 测试档(oryxos-provider/src/test + maven test-jar 供其他模块测试复用,D9):实现 `ChatModel` 接口,按脚本化规则返回固定响应或工具调用请求(AssistantMessage 含 toolCalls),E2E 不依赖真实 API key
- [ ] T021 [P] [US1] 实现 `oryxos provider list` 轻命令(oryxos-cli):列出 provider name → 模型/端点/密钥就绪状态(只报 ready/missing,不打印密钥本体)
- [ ] T022 [P] [US1] 实现 `oryxos status` 轻命令(oryxos-cli):工作区路径、已注册 Agent 数(含校验失败数)、Provider 映射表及密钥就绪状态、活跃会话数、定时任务数、数据库可达性
- [ ] T023 [US1] US-1 E2E 测试(oryxos-provider/src/test,章程质量门禁):FakeChatModel 双 provider 按名路由无歧义、缺 key 报错不含密钥、每次调用 `llm_calls` 各落一行且敏感字段已掩码
- [ ] T024 [US1] 真实 Provider 冒烟(人工,quickstart V1):DeepSeek 与 Kimi 两家各发一条真实请求成功(SC-010),`sqlite3 .oryxos/oryxos.db 'select provider,model,total_tokens,duration_ms from llm_calls'` 每次各一行
- [ ] T025 [US1] US-1 收尾:跑 `/speckit-analyze` 一致性检查并处理发现项,git commit 标记 US-1 完成(章程开发工作流)

**Checkpoint**: 两家真实 Provider 跑通且留痕可查——US-1 完成,可演示

---

## Phase 4: User Story 2 - ReAct 循环(依赖顺序: US-2,依赖 US-1)

**Goal**: Agent 自主完成「思考 → 调工具 → 看结果 → 再思考」多步循环;内置文件/Shell/HTTP 工具与白名单沙箱作为循环的执行臂随本 story 交付

**Independent Test**(spec US-2):`oryxos chat` 输入「查一下北京天气并告诉我穿什么」,Agent 经 ReAct 循环调用 HTTP Tool 获取天气并给出穿衣建议,完整对话(含 LLM 思考与 Tool 结果)累积到会话记录可查

- [ ] T026 [P] [US2] 实现 ContextLoader(oryxos-core/src/main/java/io/oryxlabs/oryxos/core/context/ContextLoader.java,FR-006/007):每次组装 prompt 现读现拼、不缓存——AGENTS.md/SOUL.md/USER.md 三 Bootstrap + AGENT.md 正文注入 system prompt;现扫 Agent `skills/` 相对软连接(唯一绑定真相源),校验真实目标必须解析到公共 Skill 根内(防越界),只注入 name/description/Agent 本地绝对读取路径,Skill 不注册为 Tool、正文不预载;改文件下轮即生效
- [ ] T027 [P] [US2] 实现 LlmGateway(即 plan 的 Function Calling 适配)统一 LLM 调用入口(oryxos-provider,**章程最易写错点**,D2):`ToolCallingChatOptions.builder().toolDefinitions(...).internalToolExecutionEnabled(false).build()` 禁自动 tool 执行;直调 `ChatModel.call(new Prompt(messages, options))`,MUST NOT 用 ChatClient 高层 API;判 `chatResponse.hasToolCalls()` 返回工具调用列表;结果回灌走 `ToolResponseMessage` 协议转换;MUST NOT 用 `ToolCallingManager` 执行(执行归 ToolExecutor,保证审计单点)
- [ ] T028 [P] [US2] 实现 WhitelistSandbox(oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/sandbox/WhitelistSandbox.java,FR-042):四个校验——路径标准化比对白名单(处理 `../` 穿越)、可执行文件精确比对、域名通配符匹配、SMTP `host:port` 精确放行;校验失败抛 `SandboxViolationException`,真实 IO 不发生;白名单配置来源 = `.oryxos/sandbox.yaml`(管理员全局、启动加载、缺省全拒,契约见 contracts/sandbox-config.md,Agent MUST NOT 自声明)
- [ ] T029 [P] [US2] 实现 FileTools(oryxos-tool):`read_file`/`write_file`/`list_dir`,`execute` 开头先 `sandbox.enforce(SandboxAction{FILE_READ/FILE_WRITE, target})` 再做 IO
- [ ] T030 [P] [US2] 实现 ShellTools(oryxos-tool,FR-042):`shell` 以 argv 数组直传执行、**MUST NOT 经 Shell 解释**,可执行文件精确白名单 + 超时控制
- [ ] T031 [P] [US2] 实现 HttpTools(oryxos-tool):`http_get`/`http_post`,域名白名单(支持通配符),先 enforce 再发请求
- [ ] T032 [US2] 实现 ToolRegistry(oryxos-tool,FR-040):实现 core 定义的 `ToolCatalog` 查询契约(接口在 oryxos-core,Spring 注入 core 的 ToolExecutor/AgentService,core MUST NOT import oryxos-tool);内置工具注册并统一包装为 `OryxTool`(名称/描述/入参 JSON Schema/执行);按 Profile `tools` 字段过滤可用子集;MCP 与注解来源在 US-4 挂载,注册表预留来源标识
- [ ] T033 [US2] 实现 ToolExecutor(oryxos-core/src/main/java/io/oryxlabs/oryxos/core/engine/ToolExecutor.java,FR-043/071/073):统一调度执行(经 core 契约 `ToolCatalog` 按名查询 OryxTool);Sandbox 拒绝按通用异常捕获(core MUST NOT import oryxos-tool 的异常类),复用既有失败审计路径(`success=false` + `error_message`),不新增审计逻辑;每次调用落 `tool_invocations`(tool_name、input_json、result_json、success、error_message、duration_ms),入参与结果落库前过 SensitiveDataMasker;失败按指数退避最多重试 3 次(仅 `ToolResult` 标记可重试的错误)
- [ ] T034 [US2] 实现 PromptBuilder(oryxos-core,FR-020/022/024):组装 system prompt(Bootstrap + AGENT.md 正文 + 记忆 + 可用工具列表)+ 对话历史;历史按 `max_history_turns`(默认 20)截断早期轮次、保留 system prompt 与最近 N 轮;system prompt 末尾附当前日期时间
- [ ] T035 [US2] 实现 ReActLoop(oryxos-core/src/main/java/io/oryxlabs/oryxos/core/engine/ReActLoop.java,FR-020/021/023):接收消息 → 追加历史 → 组装 prompt → 调 LLM → 无工具调用返回最终响应,有则交 ToolExecutor 执行、结果回灌继续循环;`max_iterations`(默认 10,可按 Agent 配置覆盖)到达上限强制结束并返回带说明的响应;每轮 LLM 响应与工具结果按序累积进会话历史
- [ ] T036 [US2] 实现 SessionService(oryxos-core + oryxos-storage,FR-030/070):session_id 统一公式 `channel:user:profile`;追加消息、持久化、跨重启恢复;CLI 固定 user `local`,REST 默认 `default`,钟推固定 `scheduler:scheduler:<profile>`
- [ ] T037 [US2] 实现 AgentService.process 统一链路入口(oryxos-core,FR-050 + 章程 IX):CLI/Web/Scheduler 三触发源都汇入此入口,ReActLoop 不感知消息来源;入口放置 ProfileContext、finally 清除
- [ ] T038 [US2] 实现 CliChannel + `oryxos chat`(oryxos-channel-cli + oryxos-cli,FR-025):多轮对话、`--profile <name>` 指定 Agent(缺省 = 工作区唯一 Agent,多于一个且未指定时报错并列出可选)、`--message` 单条消息后正常退出码 0、`/quit` 退出;channel 固定 `cli`
- [ ] T039 [P] [US2] 实现 `oryxos session list` 轻命令(oryxos-cli):sessionId、profile、channel、user、status、lastActiveAt,按最近活跃排序
- [ ] T040 [US2] US-2 E2E 测试(oryxos-core/src/test,章程质量门禁):FakeChatModel 脚本化「先返回工具调用、再返回最终响应」→ ReAct 两轮循环跑通;断言历史按序累积、`tool_invocations` 落库、迭代上限强停生效、历史截断生效
- [ ] T041 [P] [US2] oryxos-channel-cli E2E 测试:`chat --message` 单条模式端到端(覆盖 oryxos-channel-cli 模块 E2E 门禁)
- [ ] T042 [US2] US-2 收尾:跑 `/speckit-analyze` 并处理发现项,git commit 标记 US-2 完成

**Checkpoint**: `oryxos chat` 天气问答全链路(含白名单拦截演示)——US-2 完成,可演示

---

## Phase 5: User Story 3 - Memory(依赖顺序: US-3,依赖 US-2,可与 US-4 并行)

**Goal**: 跨对话记住用户;长期记忆 = 单一 MEMORY.md 两分区;MemoryService 三层门面统一收口

**Independent Test**(spec US-3):第一次对话告知偏好并确认写入长期记忆;重启服务;第二次对话问相关问题,Agent 响应引用该偏好

- [ ] T043 [P] [US3] 实现 MarkdownMemoryStore(oryxos-memory/src/main/java/io/oryxlabs/oryxos/memory/MarkdownMemoryStore.java,FR-031/033,D5):实现骨架契约 `LongTermMemoryStore`——`## 核心记忆` 区永远全量注入、不截断、不参与检索;`## 归档记忆` 区超 4000 字简单截断,关键词检索不区分大小写、只命中归档区;`append(content, scope)` 默认 ARCHIVAL,每条带日期 header;每次读取现读文件不缓存;`capabilities()` 返回 `MemoryCapability.KEYWORD`;核心阶段**唯一**后端,不建 Sqlite/Mem0 实现
- [ ] T044 [P] [US3] 实现 MemoryTools(oryxos-memory,FR-032):`save_memory`(追加写入,可指定核心区或归档区)与 `recall_memory`(关键词检索)两个 OryxTool 包装
- [ ] T045 [US3] 实现 MemoryService 三层门面(接口作为契约放 oryxos-core,实现在 oryxos-memory/src/main/java/io/oryxlabs/oryxos/memory/MemoryService.java——analyze U3,core MUST NOT 依赖 oryxos-memory 实现类):统一收口会话记忆(SessionService/SQLite)+ 长期记忆(MEMORY.md),ReAct 循环只调该接口;情景记忆不进核心阶段
- [ ] T046 [US3] ContextLoader/PromptBuilder 接入记忆注入(oryxos-core,FR-034):MEMORY.md 两区 + USER.md(用户手写只读)每轮进 system prompt,注入与检索规则同 T043
- [ ] T047 [US3] US-3 E2E 测试(oryxos-memory/src/test,章程质量门禁):save_memory 写入 → 重启(新建 Spring 上下文)→ 新会话 prompt 中可见该记忆;归档区超 4000 字截断、核心区始终完整;手工改 MEMORY.md 不重启下轮即生效
- [ ] T048 [US3] US-3 收尾:跑 `/speckit-analyze` 并处理发现项,git commit 标记 US-3 完成

**Checkpoint**: 偏好「告诉 → 重启 → 引用」全链路(quickstart V3)——US-3 完成,可演示

---

## Phase 6: User Story 4 - Plugin Tool(依赖顺序: US-4,依赖 US-2,可与 US-3 并行)

**Goal**: 三档扩展——零代码复用社区 MCP server(主推)、自写 MCP server(轻代码)、@Tool Java Bean(重代码);统一注册表对外;notify 出站推送

**Independent Test**(spec US-4):创建 `.oryxos/agents/daily-pr-digest/` 目录(AGENT.md 写「拉取昨日 PR 评审进度并汇总简报」)并配置现成社区 MCP server,启动后 Agent 读任务指令、调 MCP 工具拉数据、汇总简报——全程不写一行 Java 代码

- [ ] T049 [P] [US4] 实现 McpClientService(oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/mcp/McpClientService.java,FR-044,D7):加载 `mcp_servers.yaml`(每条 `name`/`transport: stdio|sse`/`command` argv 直传/`env` 支持 `${ENV_VAR}` 占位);直用官方 MCP Java SDK(BOM 托管 0.18.3,不覆盖);启动时逐个连接、拉 `tools/list`;stdio 先行,SSE 若受 SDK 支持度阻塞按「砍功能不砍链路」挪扩展并标注;单个 server 失败不阻断启动,记错误、其工具不可用
- [ ] T050 [P] [US4] 实现 McpToolAdapter(oryxos-tool):MCP tool 包装为 `OryxTool`,schema 透传,来源标识 `mcp:<server>`
- [ ] T051 [P] [US4] 实现 @Tool 注解 Bean 扫描注册(oryxos-tool,FR-045):启动时扫描 `@Tool` 方法,用 Spring AI 的 schema 生成立 JSON Schema,包装为 OryxTool 进程内直调;**只用 schema 生成,不启用自动执行**(章程 III)
- [ ] T052 [P] [US4] 实现 NotifyChannel 实体 + NotifyChannelRepository(oryxos-storage,data-model A6):`name` PK、`type`(仅 `webhook`)、`url`、`config_json`、`description`
- [ ] T053 [US4] 实现 NotifyTools + WebhookNotifyAdapter(oryxos-tool,FR-046):`notify` 按名引用全局注册表;启动时以 `.oryxos/notify_channels.yaml` 为准全量重建 `notify_channels` 表,`url` 支持 `${ENV_VAR}` 占位;发送前过域名白名单;具体 webhook 地址 MUST NOT 出现在对话内容中;发送结果走既有审计路径
- [ ] T054 [US4] ToolRegistry 三源汇聚(oryxos-tool,FR-040):经 Spring 收集全部 `OryxTool` Bean 汇聚内置 / 注解 / MCP / MemoryTools(oryxos-memory 只暴露 Bean,MUST NOT 依赖 oryxos-tool),`source ∈ builtin|annotation|mcp` + `origin`(mcp 时为 server 名);ReAct 循环不感知来源
- [ ] T055 [P] [US4] 实现 `oryxos tool list` 轻命令(oryxos-cli):name、description、来源(builtin / annotation / `mcp:<server>`)
- [ ] T056 [US4] US-4 E2E 测试(oryxos-tool/src/test,章程质量门禁):本地 stdio 测试 MCP server 跑通工具注册与调用;`read_file` 读白名单外路径(含 `../` 穿越)与 `shell` 跑白名单外可执行文件被拒,`tool_invocations` 有 `success=false` 记录且真实 IO 未发生
- [ ] T057 [US4] US-4 收尾:跑 `/speckit-analyze` 并处理发现项,git commit 标记 US-4 完成

**Checkpoint**: 零 Java 代码跑通 MCP 简报 Agent(quickstart V4)——US-4 完成,可演示

---

## Phase 7: User Story 5 - Web Service(依赖顺序: US-5,依赖 US-1~4)

**Goal**: 业务系统经 10 个 REST 端点接入 OryxOS;统一信封;无认证(内网假设)

**Independent Test**(spec US-5):依次调用 创建会话 → 发消息 → 查历史 → 归档 完整链路成功;健康检查、Profile 列表、工具列表、无状态 Agent 调用、记忆查询五类端点协同返回正确数据

- [ ] T058 [P] [US5] 实现 ApiResponse 统一信封 + GlobalExceptionHandler(oryxos-web,FR-061):信封 `{code, message, data, timestamp}` 成功错误共用;400 参数错 / 404 不存在 / 500 内部错(不泄露堆栈与密钥)/ 503 Provider 故障 / 504 Agent 调用超时
- [ ] T059 [P] [US5] 实现 SessionApiController(oryxos-web,contracts/rest-api.md 口径逐字):`POST /api/v1/sessions`(channel 固定 `api`)、`POST /api/v1/sessions/{id}/messages`(单条 ≤32KB,归档会话发消息返回 400,内部走完 ReAct 循环同步返回)、`GET /api/v1/sessions/{id}`(最多返回最近 100 条)、`DELETE /api/v1/sessions/{id}`(归档幂等,重复归档返回相同结果)
- [ ] T060 [P] [US5] 实现 AgentApiController(oryxos-web):`POST /api/v1/agents/{name}/invoke` 无状态调用——不创建持久会话、不携带历史,LLM 与 Tool 审计照常落库;60 秒超时返回 504(FR-062)
- [ ] T061 [P] [US5] 实现 Profile/Memory/Tool 三查询 Controller(oryxos-web):`GET /api/v1/profiles`(含校验失败 Agent,`valid=false` 附 error)、`GET /api/v1/memory`(core 全量 + archival 可能截断 + truncated 标识,实时读文件)、`GET /api/v1/tools`(全量注册表含 source/origin)
- [ ] T062 [P] [US5] 实现 SystemApiController(oryxos-web):`GET /api/v1/health`(任一 Provider 不可达不拉低总状态只如实报告,db DOWN 则总状态 DOWN)、`GET /api/v1/info`(name/version/javaVersion/agents/activeSessions/registeredTools/uptimeSeconds)
- [ ] T063 [US5] 实现 `oryxos serve` 命令 + Web 装配(oryxos-cli + oryxos-web,FR-063/064):默认端口 8080,前台运行 Ctrl+C 退出;CORS 全开放;核心阶段不做认证;springdoc 自动生成 OpenAPI,UI 暴露 `/swagger-ui`,注解口径与 contracts/rest-api.md 一致(定时任务随进程常驻调度的挂载见 Phase 8 T067)
- [ ] T064 [US5] US-5 E2E 测试(oryxos-web/src/test,MockMvc,章程质量门禁):会话生命周期四端点全链路 + 统一信封断言;>32KB 消息 400、乱造 sessionId 404、归档后发消息 400、归档幂等;重启(重建应用上下文)后历史会话完整可查(SC-006)
- [ ] T065 [US5] US-5 收尾:跑 `/speckit-analyze` 并处理发现项,git commit 标记 US-5 完成

**Checkpoint**: quickstart V5 全部 curl 跑通 + `/swagger-ui` 可打开——US-5 完成,可演示

---

## Phase 8: 定时任务第三触发源(FR-050~053,跨 story 能力)

**Purpose**: 钟推与人推同链路;依赖 US-2 的 AgentService,可与 US-3/US-4/US-5 并行,排在 US-5 后便于完整演示

- [ ] T066 [P] 实现 ScheduledTask / TaskExecution 实体 + Repository(oryxos-storage,data-model A4/A5 约束逐字):`schedule_id` 由 `(profile_name, schedule_key)` 生成且 reconcile 复用保持稳定;状态机 活动 ⇄ 停用 → 退役(`retired=true` 终态,不删除);`task_executions` 成功失败都记(schedule_id、session_id、started_at、success、error_message、duration_ms)
- [ ] T067 实现 AgentScheduler(oryxos-core/src/main/java/io/oryxlabs/oryxos/core/scheduler/AgentScheduler.java,FR-051/053,D6):`ThreadPoolTaskScheduler` + `CronTrigger` 动态注册(不用 `@Scheduled` 静态注解);启动时按 frontmatter `schedules` reconcile——同 `(profile, key)` 复用既有 schedule_id 并更新定义字段,消失的 key 置 `retired` 而非删除;任务定义唯一来源仍是 AGENT.md,表只存状态;按 schedule_id 维度 `ReentrantLock` 防重叠——上次未执行完本次跳过,不排队;随 `serve`/`gateway` 进程常驻启动挂载(`chat` 不挂载)
- [ ] T068 实现钟推执行链路(oryxos-core,FR-052):到点以固定会话 `scheduler:scheduler:<profile>`(channel 与 user 均为 `scheduler`)调 `AgentService.process`,ReActLoop 不感知来源;执行失败只记日志 + 走与人推完全相同的审计路径,调度器不崩溃、后续任务照常触发;每次执行落 `task_executions`
- [ ] T069 实现 `oryxos gateway` 守护进程命令(oryxos-cli,FR-050,D12):挂载已配置的全部入站 Channel(核心阶段仅 CLI Channel,非交互方式)+ 常驻定时调度,不启 REST;渠道配置错误清晰报错(哪个 Agent、哪个 channel、缺什么);无可用渠道时明确提示但仍常驻调度
- [ ] T070 定时任务 E2E 测试(oryxos-core/src/test):cron 临时改到 2 分钟后到点自动触发;断言 `task_executions` 落行、钟推调用的 `llm_calls`/`tool_invocations` 与人推同路径、重叠触发被跳过、失败不阻断后续触发
- [ ] T071 git commit 标记定时任务能力完成

**Checkpoint**: 钟推到点自动跑通完整链路且留痕——第三触发源就绪

---

## Phase 9: Polish & 验收 Demo(跨 story 收口)

**Purpose**: 3 个每日验收 Demo(SC-001 硬条件)+ quickstart 全量验证 + 性能抽查

- [ ] T072 [P] 建 D1 每日天气 Demo Agent(examples/daily-weather/):光杆 AGENT.md,`tools: [http_get, notify]`,cron 每天 08:00,白名单放行天气 API 域名;验证点按 quickstart §3(到点自动跑、webhook 收到穿搭建议、两条涉外调用都有审计、`chat`/`invoke` 手动补跑走同一链路)
- [ ] T073 [P] 建 D2 每日科技日报 Demo Agent(examples/daily-tech-digest/):AGENT.md + `skills/digest-format` 相对软连接(公共 Skill 实体放 examples 配套 skills/)+ 新闻 MCP server;验证点按 quickstart §3(prompt 只注入 Skill 元数据、对软连接路径的 `read_file` 有审计、日报侧重记忆偏好、全程零 Java 代码,SC-002)
- [ ] T074 [P] 建 D3 每日 GitHub 日报 Demo Agent(examples/daily-github-digest/):AGENT.md + `scripts/github_trending.py`(经白名单解释器或专用 Tool/MCP 封装);验证点按 quickstart §3(脚本 Tool 调用有审计、产出 JSON 进上下文而代码不进、改 AGENT.md 正文不重启下轮生效)
- [ ] T075 quickstart.md 全量人工验证:V1~V5 逐条执行,SC-001~010 对照表逐条核对(含 SC-003 审计 100% 抽查、SC-008 12 命令 + 10 端点全量)
- [ ] T076 性能与稳定性抽查(SC-004/005):`profile create` 造 10 个 Agent 并存 4 小时观察;会话创建 P99 ≤ 200ms;内部转发开销 ≤ 50ms(不含 LLM 厂商延迟);≥100 并发会话验证(FakeChatModel + 并发脚本压会话创建与消息链路)
- [ ] T077 [P] 文档收尾:README 当前状态由「骨架期」更新为「核心阶段已交付」;CLAUDE.md「当前状态」节同步(只更新状态描述,不改原则;统一 SessionManager→SessionService 命名,补 sandbox.yaml 工作区条目)
- [ ] T078 最终收口:`mvn clean verify` 全绿(含静态检查五件套)+ 三个每日 Demo 实跑验收(SC-001 发布硬条件)+ 最终 git commit

---

## Dependencies & Execution Order

### Phase 依赖

- **Setup(Phase 1)**: 无依赖,立即开工;T001 → T002 → T003 顺序(同改父 POM),T004/T005/T006 并行
- **Foundational(Phase 2)**: 依赖 Setup;T007 依赖 T005(schema.sql);T010 依赖 T009;T013 之后各命令任务才可挂;T016 依赖 T007/T013/T014(命令就绪后);**阻断全部 story**
- **US-1(Phase 3)**: 依赖 Foundational;T018 依赖 T017;T019 依赖 T011 + T018
- **US-2(Phase 4)**: 依赖 US-1(LlmGateway 用 ProviderService);T032 依赖 T028~T031;T033 依赖 T032;T035 依赖 T027/T033/T034;T037 依赖 T035/T036
- **US-3(Phase 5)**: 依赖 US-2;**可与 US-4 并行**;T046 依赖 T043 + T034
- **US-4(Phase 6)**: 依赖 US-2(ToolRegistry/ToolExecutor 已存在);**可与 US-3 并行**;T054 依赖 T049~T051
- **US-5(Phase 7)**: 依赖 US-1~4(对外暴露其成果);T059/T060 依赖 T037(AgentService)
- **定时任务(Phase 8)**: 依赖 US-2(AgentService);可与 US-3/US-4/US-5 并行,建议排在 US-5 后完整演示
- **Polish(Phase 9)**: 依赖全部前置 phase;D2 依赖 US-3 + US-4,D1/D3 依赖 US-4 + Phase 8

### User Story 依赖

- **US-1**: Foundational 完成后即可开始,不依赖其他 story
- **US-2**: 依赖 US-1(每轮循环调 LLM)
- **US-3**: 依赖 US-2(记忆在组装 prompt 时注入);与 US-4 互不依赖
- **US-4**: 依赖 US-2(Tool 由 ReAct 调度);与 US-3 互不依赖
- **US-5**: 依赖 US-1~4

### 每个 story 内部

- 领域模型/配置先于服务,服务先于命令/端点,集成最后
- E2E 测试在该 story 实现任务之后(非 TDD;章程验收机制 = 可演示 Demo + analyze + 人工 review)
- story 收尾任务(`/speckit-analyze` + commit)不可省(章程强制)

### Parallel Opportunities

- Setup:T004 / T005 / T006 三文件互不干扰
- Foundational:T008 / T009 / T011 / T012 / T015 / T016 并行
- US-1:T017 / T020 / T021 / T022 并行
- US-2:T026 / T027 / T028 / T029 / T030 / T031 六个文件并行(本特性最大并行窗口)
- US-3:T043 / T044 并行
- US-4:T049 / T050 / T051 / T052 / T055 并行
- US-5:T058 / T059 / T060 / T061 / T062 五个 Controller/信封并行
- Story 级:US-3 ∥ US-4;定时任务 phase ∥ US-3/US-4/US-5
- Polish:T072 / T073 / T074 / T077 并行

---

## Parallel Example: User Story 2(最大并行窗口)

```bash
# 六个独立文件,可同时开工:
Task: "实现 ContextLoader(oryxos-core/.../context/ContextLoader.java)"
Task: "实现 LlmGateway 统一 LLM 调用入口(oryxos-provider,internalToolExecutionEnabled(false))"
Task: "实现 WhitelistSandbox(oryxos-tool/.../sandbox/WhitelistSandbox.java)"
Task: "实现 FileTools(oryxos-tool)"
Task: "实现 ShellTools(oryxos-tool)"
Task: "实现 HttpTools(oryxos-tool)"
```

---

## Implementation Strategy

### MVP First(User Story 1)

1. 完成 Phase 1 Setup + Phase 2 Foundational(阻断项)
2. 完成 Phase 3 US-1 → 独立验证(两家真实 Provider + 审计留痕)→ 首个可演示成果
3. 每个 story 完成即演示、analyze、commit,随时可回退

### Incremental Delivery

1. Setup + Foundational → 地基就绪
2. US-1 → 演示:双 Provider 跑通(MVP)
3. US-2 → 演示:chat 天气问答全链路
4. US-3 ∥ US-4 → 演示:记忆跨重启引用 / 零代码 MCP 简报
5. US-5 → 演示:REST 全链路 + swagger-ui
6. 定时任务 → 演示:钟推到点自动跑
7. 3 个每日 Demo 实跑 → 核心阶段发布(SC-001 硬条件)

### 节奏约束(4 周 × 3 小时)

某 story 完不成时,按章程原则 II/X 把末段功能挪扩展阶段(如 MCP SSE transport、SMTP 白名单的实际邮件通道),**砍功能不砍链路**,保证当周演示完整。

---

## Notes

- [P] 任务 = 不同文件、无未完成依赖
- [USn] 标记映射 spec.md user story,保证可追溯
- 每个 story 独立可完成、可测试、可演示;任意 checkpoint 可停下验证
- 每个 task 或逻辑组完成后 commit;每个 story 结束必跑 `/speckit-analyze` + commit(章程)
- 避免:模糊任务、同文件并行冲突、破坏 story 独立性的跨 story 依赖
- 实施时最易写错的一条:T027 禁用 Spring AI 自动 tool 执行(启用会导致 tool 被调两次)——code review 必查
