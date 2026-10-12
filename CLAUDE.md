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

- **阶段**：greenfield，设计文档已定稿，Maven 骨架已初始化；业务代码未开始，但已落第一层「契约接口 + 审计地基」（见本节「契约与审计地基」条目）
- **已有**：`.specify/`（Spec-Kit v1.0.10 脚手架）、`.claude/skills/`（10 个 speckit-* skills，dash 风格命名）、docs/、`LICENSE`（Apache 2.0）、Maven 9 模块骨架（各模块暂为占位类，两个可运行入口：**`oryxos-boot` 的 `OryxosApplication` 是 fat JAR 主类**，`java -jar oryxos-boot/target/oryxos.jar` 启动 Spring Boot + Web 容器；**`oryxos-cli` 的 `OryxOsCli` 是 Picocli 主入口**，已由 maven-shade-plugin 打成可执行 fat JAR（内嵌 picocli），`java -jar oryxos-cli/target/oryxos-cli-0.1.0-SNAPSHOT.jar --version` 可直接运行）
- **工程地基**（oryxos-init 已装，业务代码在其上生长）：Spring Boot **3.5.16**；`logback-spring.xml`（dev 彩色 console / prod JSON + traceId MDC）；Actuator + Prometheus（`/actuator/health|info|prometheus`）；`application.yaml`（虚拟线程开启、SQLite 定式 HikariCP pool=1 + busy_timeout + WAL）；`oryxos-web` 已有统一信封 `ApiResponse` + `GlobalExceptionHandler`（400/404/500，503/504 映射待 US-1/US-5）；springdoc UI 在 `/swagger-ui`；**静态检查门禁已接入 `mvn verify`**：Spotless（google-java-format，改代码后跑 `./mvnw spotless:apply`）+ 阿里 P3C（PMD 6.55）+ Checkstyle + SpotBugs/FindSecBugs + OWASP Dependency-Check（根模块聚合，本地可 `-Ddependency-check.skip=true`）；CI（`.github/workflows/ci.yml`）+ pre-commit（`scripts/hooks/`，本机已启用，JAVA_HOME 走 gitignore 的 `local-env.sh`）
- **官网**：`website/`（VitePress 1.6，中英文双语 root=zh / en，自定义主题首页非默认模板；`base: '/oryxos-wngbob/'` 对应 GitHub Pages 项目页 wngbob.github.io/oryxos-wngbob；`.github/workflows/deploy-pages.yml` 在 `website/**` 变更推送 main 时自动构建部署（Pages Source 须设为 GitHub Actions）。若绑定独立域名 oryxos.wngbob.com：base 改为 `'/'` 并在 `website/public/` 放 CNAME 文件）
- **契约与审计地基**（骨架期已落，均为接口/表结构，不含业务实现）：契约按「实现依赖契约、core 不反向依赖」放置——`oryxos-core` 定义 `OryxTool` / `ToolResult`；`oryxos-tool` 定义 `Sandbox` / `SandboxAction` / `ActionType` / `SandboxViolationException` / `NotifyChannelAdapter` / `NotifyTarget`；`oryxos-memory` 定义 `LongTermMemoryStore` / `MemoryScope` / `MemoryCapability`。审计表 `tool_invocations` / `llm_calls` 的实体 + Repository 在 `oryxos-storage`，建表脚本 `oryxos-storage/src/main/resources/db/schema.sql`（手工执行，`ddl-auto=none`，不走 Spring Boot 的 classpath `schema.sql` 自动执行）；`oryxos-boot` 因实体在兄弟包，必须显式 `@EntityScan` + `@EnableJpaRepositories`
- **打包脚本**：`scripts/package.sh`（编译 → 打包 `dist/` → scp 上传远程；源码包排除所有 `target/`、`node_modules` 等本地产物，唯一上传的构建产物是脱离 target/ 的 `dist/oryxos.jar`；远程目标用 `REMOTE_HOST` / `REMOTE_DIR` 环境变量配置，未设置则跳过上传）
- **待办**：constitution v2.0.0 与 `specs/001-runtime-kernel`（spec/plan/tasks + 五契约）已定稿并通过 `/speckit-analyze`；下一步 `/speckit-implement` 按 tasks.md 实施（T001/T002/T004/T006 已随 oryxos-init 工程地基完成并勾选，实际从 T003/T005/T007 起步）
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

核心阶段 12 个命令（Picocli 实现，`OryxOsCli` 为主入口）：

| 类别 | 命令 |
| ------ | ------ |
| 启动和状态 | `oryxos init`（初始化工作区，幂等）、`oryxos status`、`oryxos chat [--profile <name>]`、`oryxos serve`、`oryxos gateway` |
| Profile 管理 | `oryxos profile list` / `create <name>` / `show <name>` / `delete <name>` |
| 查询 | `oryxos provider list`、`oryxos tool list`、`oryxos session list` |

- 三种运行模式（`chat` / `serve` / `gateway`）共享同一份 Profile 配置和 Session 存储
- 不需要 Spring 上下文的命令（`init`、`profile list`）直接走文件操作保证启动速度；需要 LLM 的（`chat`/`serve`/`gateway`）才启动 Spring 上下文

## Tool 体系

- **`OryxTool` 接口**（统一抽象，ReAct 循环不感知 Tool 来源）：`getName` / `getDescription` / `getInputSchema`（JSON Schema）/ `execute`（JSON 输入 → `ToolResult`）；`ToolResult` 含成功标识、结果内容、错误信息、是否可重试
- **内置 Tool 9 个**：`read_file` / `write_file` / `list_dir`（路径白名单）、`shell`（argv 直传**不经 Shell 解释**、可执行文件白名单 + 超时）、`http_get` / `http_post`（域名白名单）、`save_memory` / `recall_memory`、`notify`（出站推送，按名引用全局通知渠道注册表）
- **Plugin Tool 三档**：① 零代码 `AGENT.md` 目录 + 复用社区 MCP server（主推）② 任何语言自写 MCP server（`mcp_servers.yaml` 配置，`McpClientService` 连接、`McpToolAdapter` 包装成 `OryxTool`）③ `@Tool` 注解 Java Bean（进程内直调，性能最好）
- **`ToolRegistry`**：启动时扫描 `@Tool` 方法 + MCP 工具，全部包装成 `OryxTool`；Profile 按 `tools` 字段过滤可用子集
- **Sandbox**：`Sandbox.enforce(SandboxAction{type, target})`，`ActionType` = `FILE_READ` / `FILE_WRITE` / `SHELL_COMMAND` / `HTTP_REQUEST`；`WhitelistSandbox` 内部四个校验（路径标准化比对白名单、处理 `../` 穿越；可执行文件精确比对；域名通配符匹配；SMTP `host:port` 精确放行）；校验失败抛 `SandboxViolationException`，复用 `ToolExecutor` 既有失败审计路径，不新增审计逻辑；FileTools/ShellTools/HttpTools 在 `execute` 开头先 `enforce` 再做 IO

## SQLite 核心表

五张表（`.oryxos/oryxos.db`，Spring Data JPA）：

| 表 | 要点 |
| ---- | ------ |
| `sessions` | `session_id` 主键（channel+user+profile 联合生成）、`profile_name`、`channel`、`user_id`、`messages_json`（JSON 序列化对话历史）、`status`（active/archived）、三个时间戳 |
| `tool_invocations` | 每次 Tool 调用：tool_name、input_json、result_json、success、error_message、duration_ms |
| `llm_calls` | 每次 LLM 调用：provider、model、prompt/completion/total tokens、duration_ms |
| `scheduled_tasks` | 定时任务登记与运行状态（schedule_id 稳定主键、cron/zone/message、enabled/retired、run_count 等）；**定义源仍是 AGENT.md 的 `schedules`**，表只存状态 |
| `task_executions` | 定时任务每次执行历史（schedule_id、session_id、success、error_message、duration_ms），成功失败都记 |

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

**Memory**：`.oryxos/memory/MEMORY.md` 一个 Markdown 文件，内部分 `## 核心记忆` / `## 归档记忆` 两区——核心区永远全量注入、不截断、不参与检索；归档区可截断（超 4000 字简单截断）、关键词检索只命中归档区。`USER.md` 是用户手写的初始设定（OryxOS 只读），`MEMORY.md` 是 Agent 通过 `save_memory` 写入的成长记录（OryxOS 读写），两者都进 system prompt。`MemoryService` 三层门面统一收口会话记忆 + 长期记忆，ReAct 循环只调它一个接口。

**通知渠道**：`notify_channels` 是 SQLite 全局注册表（name / type / url / config / description），**不属于 Profile 或 frontmatter**；Agent 在 `AGENT.md` 正文中用自然语言按名引用，`notify` Tool 调用时解析。

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
| `oryxos-memory` | 能力三：`MemoryService` 三层门面、`LongTermMemory`、`MemoryTools`（save_memory / recall_memory） |
| `oryxos-tool` | 能力四（三合一）：内置 Tool（File/Shell/Http/Notify）、`McpClientService`、`McpToolAdapter`、`ToolRegistry`、`Sandbox` 接口 + `WhitelistSandbox` |
| `oryxos-channel-cli` | CLI Channel：`CliChannel`、`oryxos chat` |
| `oryxos-web` | 能力五：`WebServer`、6 个 `ApiController`、`GlobalExceptionHandler`、OpenAPI |
| `oryxos-storage` | SQLite 持久化：`SessionRepository`、审计表 Repository |
| `oryxos-cli` | Picocli 主入口、12 个子命令、`ConfigLoader` |
| `oryxos-boot` | Spring Boot 启动模块、自动配置、依赖聚合 |

全量 14 个模块（+ `oryxos-persona` / `oryxos-knowledge` / `oryxos-channel-feishu|wecom|dingtalk`）见技术方案 ch10，属扩展演进，核心阶段不建。

**依赖方向（依赖倒置）**：`oryxos-boot` 聚合其余 8 个；`oryxos-cli` 依赖各能力模块；`oryxos-tool` / `oryxos-memory` 依赖 `oryxos-core`（实现 core 的 `OryxTool` 契约）；**`oryxos-core` 不反向依赖任何能力模块**——契约在 core、实现在各模块。例外是 `Sandbox` / `NotifyChannelAdapter`，契约放 `oryxos-tool`：内置 Tool 在自己的 `execute` 开头调 `sandbox.enforce(...)`，core 全程不引用 Sandbox，故不成环。

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

## 关键术语速查

- **Agent = 一个目录** `.oryxos/agents/<name>/`：`AGENT.md`（frontmatter=运行配置，正文=任务指令）+ 可选 `skills/` 软连接、`scripts/`、`REFERENCE.md`；Agent 是配置出来的，不是写代码写出来的
- **Profile**：底座内部的运行时宿主配置，由 `AgentLoader.deriveProfile()` 从 `AGENT.md` frontmatter 派生，不是单独手写的 YAML
- **底座 vs Agent 两层**：底座（Provider/ReAct/Tool/Memory/Sandbox/Web）所有 Agent 共享；Agent 只消费底座能力
- **Memory 三层门面**：`MemoryService` 统一收口会话记忆（SessionManager/SQLite）+ 长期记忆（MEMORY.md），情景记忆扩展阶段；长期记忆后端三档可插拔（Markdown 默认 / SQLite / Mem0）
- **三触发源**：CLI、Web Service（人推）+ `AgentScheduler` cron（钟推），同一 `AgentService` 链路
- **入站 Channel ≠ 出站 Notify**：`ChannelAdapter` 解决"消息怎么进来"，`NotifyChannelAdapter`/`notify` Tool 解决"结果推到哪"（企业 IM webhook）；同一个群可能既是某 Agent 的入站、又是另一 Agent 的出站
- **Bootstrap**：`AGENTS.md` / `SOUL.md` / `USER.md` 三个文件，启动时注入 system prompt
