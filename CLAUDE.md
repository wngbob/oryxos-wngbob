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

- **阶段**：greenfield，设计文档已定稿，Maven 骨架已初始化；已落第一层「契约接口 + 审计地基」（见本节「契约与审计地基」条目），并按逐节开发推进到**第 20 节**——第 16 节 Provider（`specs/001-llm-provider`）、第 17 节 ReAct 循环与 AgentService（`specs/002-react-loop`）、第 18 节 CLI 与会话持久化（`specs/003-cli-session`：12 条命令 + `sessions` 表 + `JpaSessionService`）、第 19 节 Notify 出站推送（`specs/004-notify-outbound`：`WebhookNotifyAdapter` + `NotifyTools` + 只读渠道注册表接缝 + `notify_channels` 表）、第 20 节 Tool 体系与 MCP（`specs/005-tool-registry`：`ToolRegistry` + 注解管道 + **13** 个内置/扩展工具 + MCP 客户端与适配器 + `PermissiveSandbox` 临时装配，新增 108 条测试；另于 2026-10-15 交付后补强两轮——第一轮 P0/P1：`read_file` 分页、`shell` 可配超时、`grep`/`glob` 翻页、新增 `web_fetch`、搜索接缝加一档带 key 实现、免 key 搜索档补 `User-Agent`；第二轮修人工验收抓到的缺陷：`oryxos tool list` 的 MCP 子分组表头打成 `mcp (N):` 而非 `mcp:<server名> (N):`——根因是组内那一层复用了按**前缀**分组的 `group(...)`，已新增按**全串**分组的 `bySource(...)`，并补 `ToolListCommandTest`（5 条）钉死，本节交付时 `oryxos-cli/src/test` 对该输出格式一条断言都没有）均已交付，`mvn clean verify` 全绿（**238** 条测试）
- **⚠️ 沙箱窗口期（第 20~23 节，读代码前必知）**：本节首次把工具装进容器，但沙箱挂的是**临时实现 `PermissiveSandbox`**——它只打一条启动 WARN、**不做任何白名单校验**。因此第 20~23 节交付的运行时**没有 Tool 治理**：`read_file /etc/passwd` 会真的成功（2026-10-14 端到端实测已确认，见 `specs/005-tool-registry/acceptance-report.md` §2 证据 5）。这是**已报批的取舍**，不是 bug：第 19 节「MUST NOT 造全放行占位沙箱」的裁决被用户有意推翻，理由是本节必须让工具进容器才能交付「Agent 能动手」这件事，而白名单本体归第 24 节。缓解四条：① 类名与 javadoc 明示临时 ② 装配时打 WARN（`PermissiveSandbox` 启动即打）③ `@ConditionalOnMissingBean(Sandbox.class)` —— 第 24 节 `WhitelistSandbox` 一落地就自动让位，**不回头删代码** ④ 此处与验收报告双处留痕。**验收 MUST NOT 把 §5.5 的「越界没被拦」当成沙箱已就位**
- **第 19 节遗留特征（两处已在本节解冻）**：① 原「**零装配**」裁决**已被本节推翻**——`RestClient` / `WebhookNotifyAdapter` / 注册表实现 / `NotifyTools` 的 `@Bean` 声明连同沙箱临时实现一并进了 `CliAssemblyConfiguration`（第 19 节不装配的根因是「没有可注入的沙箱 Bean」，本节用 `PermissiveSandbox` 解开）② 原「工具进不了容器、`notify` 必然报未配置渠道」**不再成立**——13 个工具已可 `oryxos tool list` 出来、`chat` 里真能调（`notify` 仍会报未配置渠道，因为 `notify_channels` 表写入路径归第 26 节，表运行期恒为空，这条**依旧**是预期状态）③ 仍在生效：**`oryxos-tool` 新增两条显式依赖边**（`spring-web`、`oryxos-storage`）——都是既有传递边的显式化，发行物不新增任何工件；另在 `oryxos-tool/pom.xml` 用**模块级 `<dependencyManagement>`** 把 `kotlin-stdlib` 钉回 2.1.21（Spring Boot BOM 会把它降级到 1.9.25，本机仓库没有该版本 → 离线构建直接失败；这段配置看着冗余但删了构建会红，**别清理**）
- **第 19 节跨节挂账（未清）**：`specs/004-notify-outbound/tasks.md` T013 记下的 US3 场景 4——「入站 `ChannelAdapter` 抽象 vs 出站 `NotifyChannelAdapter` 不合并」只做了**可判定部分**（`NotifyChannelAdapter` 不与任何既有类型合并或互相继承）。入站侧的 `ChannelAdapter` 抽象类型**尚未交付**（全仓 grep 仅命中 `NotifyChannelAdapter` 的 javadoc 散文；`CliChannel` 是 CLI 渠道的直连实现、不是抽象类型），完整判定挂到入站抽象落地的那一节
- **第 19 节跨节挂账②：IM 群机器人方言适配（未清，归扩展阶段）**（2026-10-14 人工实测发现，已报批）——通用 webhook 报文 `{"content":"..."}` 不被 IM 群机器人接受（实测飞书回 **`HTTP 200` + `code:19002` "msg_type need"**，群里无消息；企微同类失败走 `HTTP 200` + `errcode:40008`）。IM 群机器人**用 HTTP 200 承载业务错误**，而 `WebhookNotifyAdapter.send()` 走 `.retrieve().toBodilessEntity()` 只判 HTTP 状态码 → 判成成功 → `notify` **假报「已推送」**。按 FR-003「核心阶段只提供一档通用 webhook 实现」的边界，**记为已知边界、代码一行不改**；方言适配作为**新增实现类**（如 `FeishuNotifyAdapter`）归扩展阶段，`NotifyChannelAdapter` 接口与调用方零改动。连带：验收报告 §7.1 原写「配好一个真实群 webhook 后 `notify` 真能把消息送进群」**只对通用 webhook 接收端成立**，已更正。实测证据见 `specs/004-notify-outbound/manual-test-guide.md` §4
- **第 19 节 → 第 20 节改造点：`@ToolParam(required = false)`（✅ 本节已清）**——`NotifyTools.notify(String content, String channel)` 的 `channel` 已补上 `@ToolParam(description = ..., required = false)`（`NotifyTools.java` 方法签名与调用方未变，只多一个参数注解）。背景（2026-10-14 反编译 Spring AI 1.1.8 字节码核实，非记忆）：Spring AI 的 `JsonSchemaGenerator.isMethodParameterRequired` 对**无 `@ToolParam` / `@JsonProperty` / `@Schema` / `@Nullable` 注解的参数一律返回 true**（常量 `PROPERTY_REQUIRED_BY_DEFAULT` = int 1；`ToolParam.required()` 的 AnnotationDefault 同样是 true），不加注解则 `channel` 会被列进 JSON Schema 的 `required`，FR-004「渠道名可选」在模型可见层不成立。第 19 节不改是因为 `@Tool` 当时是惰性的、改了无从验证
- **已有**：`.specify/`（Spec-Kit v1.0.10 脚手架）、`.claude/skills/`（10 个 speckit-* skills，dash 风格命名）、docs/、`LICENSE`（Apache 2.0）、Maven 9 模块骨架（各模块暂为占位类；**唯一发行物 = `oryxos-boot/target/oryxos.jar`**，`java -jar oryxos-boot/target/oryxos.jar --version` 可直接运行。`oryxos-boot` 的 fat JAR 主类是 **`OryxOsCli`**（`oryxos-cli` 的 Picocli 主入口，12 个子命令）——`OryxosApplication` 自第 18 节起不再是 fat JAR 主类，去留由第 26 节定；`oryxos-cli` 的 maven-shade-plugin 已移除，该模块退回普通库模块。`serve` 本节只起 Spring 上下文、不起 Web 容器，REST 端点在第 26 节接入）
- **工程地基**（oryxos-init 已装，业务代码在其上生长）：Spring Boot **3.5.16**；`logback-spring.xml`（dev 彩色 console / prod JSON + traceId MDC）；Actuator + Prometheus（`/actuator/health|info|prometheus`）；`application.yaml`（虚拟线程开启、SQLite 定式 HikariCP pool=1 + busy_timeout + WAL）；`oryxos-web` 已有统一信封 `ApiResponse` + `GlobalExceptionHandler`（400/404/500，503/504 映射待 US-1/US-5）；springdoc UI 在 `/swagger-ui`；**静态检查门禁已接入 `mvn verify`**：Spotless（google-java-format，改代码后跑 `./mvnw spotless:apply`）+ 阿里 P3C（PMD 6.55）+ Checkstyle + SpotBugs/FindSecBugs + OWASP Dependency-Check（根模块聚合，本地可 `-Ddependency-check.skip=true`）；CI（`.github/workflows/ci.yml`）+ pre-commit（`scripts/hooks/`，本机已启用，JAVA_HOME 走 gitignore 的 `local-env.sh`）
  - **P3C 有三处"看着多余、删了会静默失效"的配置，不要清理**：① 父 pom 的 `asm.version`（9.7.1，覆盖进 maven-pmd-plugin 依赖）——PMD 6.55 自身硬锁 asm 9.4，只认到 class major 64/Java 20，而本项目 `release=21` 编出的 class 是 major 65，类型解析读 `target/classes` 时逐文件抛 `Unsupported class file major version 65`；② `skipPmdError=false`——PMD 默认把这个错误吞成 WARNING，且 `pmd:check` 只数 violation 不看 processing error，两者叠加会让门禁「全挂 = 零违规 = 通过」地**静默空转**（曾实际发生：27 个源文件全挂却 BUILD SUCCESS）；③ 根目录 `p3c-ruleset.xml`——P3C 规约集入口，引用全量 10 个分类并排除 `ClassMustHaveAuthorRule`（git 已是作者事实源，`@author` 属噪音型合规；恢复只需删该文件的 `<exclude>`）。改动这三处前先读它们的文件头注释
- **官网**：`website/`（VitePress 1.6，中英文双语 root=zh / en，自定义主题首页非默认模板；`base: '/oryxos-wngbob/'` 对应 GitHub Pages 项目页 wngbob.github.io/oryxos-wngbob；`.github/workflows/deploy-pages.yml` 在 `website/**` 变更推送 main 时自动构建部署（Pages Source 须设为 GitHub Actions）。若绑定独立域名 oryxos.wngbob.com：base 改为 `'/'` 并在 `website/public/` 放 CNAME 文件）
- **契约与审计地基**（骨架期已落，均为接口/表结构，不含业务实现）：契约按「实现依赖契约、core 不反向依赖」放置——`oryxos-core` 定义 `OryxTool` / `ToolResult`；`oryxos-tool` 定义 `Sandbox` / `SandboxAction` / `ActionType` / `SandboxViolationException` / `NotifyChannelAdapter` / `NotifyTarget`；`oryxos-memory` 定义 `LongTermMemoryStore` / `MemoryScope` / `MemoryCapability`。审计表 `tool_invocations` / `llm_calls` 的实体 + Repository 在 `oryxos-storage`，建表脚本 `oryxos-storage/src/main/resources/db/schema.sql`（手工执行，`ddl-auto=none`，不走 Spring Boot 的 classpath `schema.sql` 自动执行）；`oryxos-boot` 因实体在兄弟包，必须显式 `@EntityScan` + `@EnableJpaRepositories`
- **打包脚本**：`scripts/package.sh`（编译 → 打包 `dist/` → scp 上传远程；源码包排除所有 `target/`、`node_modules` 等本地产物，唯一上传的构建产物是脱离 target/ 的 `dist/oryxos.jar`；远程目标用 `REMOTE_HOST` / `REMOTE_DIR` 环境变量配置，未设置则跳过上传）
- **待办**：constitution v2.0.0 与 `specs/000-runtime-kernel` 已定稿；第 16~20 节已完成（`specs/001-llm-provider` / `002-react-loop` / `003-cli-session` / `004-notify-outbound` / `005-tool-registry` 各自 spec/plan/tasks 齐备并已实现，第 18 节 55 个任务、第 19 节 19 个任务、第 20 节 61 个任务全绿）。下一步按课件顺序推进**第 22 节（Memory）**（第 21 节是评审课、不产码，是第 22 节 specify 的素材）。跨节挂账：`specs/003-cli-session/` 记的 `OryxosApplication` 反向撞车归第 26 节；**两个空接缝让位已清一个**（`ToolCatalog` 空实现已在第 20 节自动让位给 `ToolRegistry`），另一个（Memory 侧）归第 22 节；`specs/004-notify-outbound/` 记的入站 `ChannelAdapter` 抽象让位见上条。**第 20 节新挂账**：`WhitelistSandbox` 归第 24 节（窗口期见上）、`save_memory` / `recall_memory` 两个内置工具归第 22 节（经同一 `ToolRegistry.registerAnnotated` 自动进注册表，注册表与过滤逻辑零改动）、`notify_channels` 写入路径归第 26 节、`api.tavily.com` 的域名白名单归第 24 节。**第 20 节 P2 挂账（2026-10-15 补强时评估后决定不做，只记不做）**：① `apply_patch` 式批量多文件编辑——`edit_file` 的唯一匹配约束已够用，YAGNI；② 计划/进度工具（`update_plan` 等价物）——要在 `sessions` 表落状态并改 `PromptBuilder`，是跨节改动，不该塞进工具节；③ 后台 shell + 输出拉取（`BashOutput`/`KillShell` 等价物）——违反核心阶段同步模型（章程原则 8），归扩展阶段；④ 图片 / PDF 读取——需 VLM；⑤ MCP resources / prompts——目前只接了 MCP 的 tools，协议面另一半；⑥ 危险操作审批层（Claude Code permission mode / Codex approval mode）——白名单之外的中间层，归第 24 节之后评估；⑦ ~~`notify` 未用 `@Tool(name = "notify")` 钉名~~ **已于 2026-10-15 补上**（`NotifyTools.java`，成本一行）
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
- **轻 / 重之分**：判据只有一条——这条命令要不要调模型、跑引擎。不需要 Spring 上下文的轻命令（`init`、`profile list`、`provider list`、`session list` 等）直接走文件操作保证启动速度；重命令（`chat` / `serve` / `gateway` / **`tool list`**）启动 Spring 上下文
- **`oryxos tool list` 自第 20 节起是重命令**（第 18 节交付时是轻的）：列出 MCP 工具必须连上 server，清单只有连上才知道，这已经是「跑引擎」；第 18 节归轻是因为当时没有注册表可查（`ToolCatalog` 是空实现），不是判据不同。代价如实记录：有 MCP server 声明时最多等待 `10 秒 × server 数`。命令名 / 参数 / 退出码三项未变（契约 `specs/003-cli-session/contracts/cli-surface.md` §1 要求走修订程序的正是这三项，已回修该契约 §2.10 与 §1 表格）

## Tool 体系

- **`OryxTool` 接口**（统一抽象，ReAct 循环不感知 Tool 来源）：`getName` / `getDescription` / `getInputSchema`（JSON Schema）/ `execute`（JSON 输入 → `ToolResult`）；`ToolResult` 含成功标识、结果内容、错误信息、是否可重试。骨架期已交付，第 20 节**一行未改**（FR-024）
- **内置 Tool 15 个（第 20 节交付 13 个，另 2 个归第 22 节）**：`read_file` / `write_file` / `list_dir`（路径白名单；`read_file` 自 2026-10-15 起支持 `offset`/`limit` 分页，默认上限 2000 行）、`shell`（argv 直传**不经 Shell 解释**、可执行文件白名单 + 超时，`timeoutSeconds` 可选、默认 30 秒、上限 600 秒）、`http_get` / `http_post`（域名白名单）、`notify`（出站推送，按名引用全局通知渠道注册表）；第 20 节另补六个高频基础工具——`edit_file`（要求 `oldText` 唯一匹配，不唯一则报错且文件一个字不动）、`grep`（按正则搜内容，二进制/非 UTF-8 文件跳过；支持 `offset` 翻页与 `contextLines` 上下文行）、`glob`（按通配找路径，支持 `offset` 翻页）、`ask_user`（向用户提问，渠道不支持时明确报错、不静默阻塞）、`web_search`（搜索引擎接缝，免 key 的 DuckDuckGo 默认 / 带 key 的 Tavily）、`web_fetch`（抓网页正文转纯文本，**跟随重定向且每一跳重过白名单**，上限 20000 字符）；`save_memory` / `recall_memory` 归第 22 节
- **`oryxos tool list` 的默认输出是 13 个**：`builtin (13)` / `annotation (0)` / `mcp (0)`。后两组为空是**预期状态**——业务方还没写 `@Tool` Bean，且 `.oryxos/mcp_servers.yaml` 模板末尾是 `servers: []`
- **搜索接缝的两档与配置键**：`SearchProvider` 现有两档实现——`DuckDuckGoSearchProvider`（免 key，默认，打的是 Instant Answer「问答」接口，长尾查询常返回 0 条；**自 2026-10-15 起显式带 `User-Agent`**，原因见下条）与 `TavilySearchProvider`（带 key，真检索 API）。`CliAssemblyConfiguration.searchProvider()` 按 `oryxos.search.tavily-api-key`（占位 `${TAVILY_API_KEY:}`，**带空默认值**——搜索 key 可选，缺失不该让启动失败）有无分流；两档都是同一个接缝的实现，`WebSearchTools` / 校验位 / 注册表零改动。**key MUST NOT 进日志、异常消息或 `toString`**，有单测钉死。`api.tavily.com` 的域名白名单归第 24 节
- **免 key 搜索档的 UA 边界（2026-10-15 已修，非挂账）**：`api.duckduckgo.com` **按 UA 分流**——JDK 默认的 `Java-http-client/21.0.12` 被回 **HTTP 403**，浏览器前缀的 UA 被回 **HTTP 202**（同一代理、固定其它变量逐次实测；curl 同款对照）。所以第 20 节交付的 `DuckDuckGoSearchProvider` 在修之前**对任何一台机器都拿不到结果**，不是本机网络问题。修法是显式带 `User-Agent: Mozilla/5.0 (compatible; OryxOS/0.1; +https://github.com/wngbob/oryxos-wngbob)`（改的是**已交付类**，软门禁 ② 已报批）；单测 `search_sendsNonDefaultUserAgent` 钉死「非空且不以 `Java-http-client` 开头」。修后实测：`Alan Turing` → 5 条真结果；长尾查询仍 0 条——**这是 Instant Answer 是问答接口不是检索接口，与 UA 无关**，那部分由带 key 档解决。带 key 档（Tavily）实测不带 UA 也正常，未动
- **Plugin Tool 三档**：① 零代码 `AGENT.md` 目录 + 复用社区 MCP server（主推）② 任何语言自写 MCP server（`mcp_servers.yaml` 配置，`McpClientService` 连接、`McpToolAdapter` 包装成 `OryxTool`）③ `@Tool` 注解 Java Bean（`AnnotatedToolAdapter` 包装，进程内直调）
- **`ToolRegistry`**（`oryxos-tool`，实现 `oryxos-core` 的 `ToolCatalog` 接缝）：三种来源全部收敛成同一个 `OryxTool` 身份——内置与注解 Bean 经 `registerAnnotated(bean, source)` 先注册、MCP 后注册；`ToolRegistry` 里**没有任何 `@Tool` 扫描逻辑**，被包装的 Bean 由 `CliAssemblyConfiguration` 显式传入（工具来源是装配期事实，不是扫描期猜测）。工具重名时**保留先注册者 + WARN**（安全侧优先）；Agent 声明了但未注册的工具名 → WARN + 跳过，不抛异常；Profile 按 `tools` 字段过滤出的子集**恰好等于**声明列表（多一个少一个都是错，FR-011）
- **注解管道（`AnnotatedToolAdapter`）**：MUST NOT 用 Spring AI 的 `MethodToolCallback`（它把返回值字符串化 → `retryable` 丢失；把异常包成错误文本 → 失败落不进 `tool_invocations`）；异常一律剥掉 `InvocationTargetException` 外壳后上抛，交给 `ToolExecutor` 的既有失败审计路径。`@Tool(name = ...)` 显式钉住工具名（模型引用它的唯一凭据，一旦交付不得改），方法名与工具名解耦
- **MCP**：`McpServerConfigLoader` 读 `.oryxos/mcp_servers.yaml`（`env` 里的 `${ENV_VAR}` 解析失败则**跳过整个 server**，绝不把占位符当值传下去；日志只记变量名、MUST NOT 记它的值）；`McpClientService` 启动时逐个连接 + `listTools()` + 包装注册。**失联隔离**（FR-010 / SC-004）：任一 server 连不上/列举失败/包装失败只 WARN 并跳过它自己的工具，OryxOS 照常起、其余 server 照常注册。**默认配置下不连接任何 MCP server**（模板是空清单），这是预期状态
- **`InteractionTools` + `UserInteraction`**：`ask_user` 只负责「把问题递出去、把回答带回来」，能不能问由装配时注入的实现决定（CLI 注入 `ConsoleUserInteraction`；定时任务/无状态调用注入 `UnsupportedUserInteraction`，拿到回答为空即明确报错）
- **`SearchProvider` 接缝 + 两档实现（`DuckDuckGoSearchProvider` / `TavilySearchProvider`）**：核心阶段挂免 key 那档作默认（另见上文「搜索接缝的两档与配置键」「免 key 搜索档的 UA 边界」）；换别的搜索服务只加实现类，`WebSearchTools` 与调用方零改动
- **Sandbox**：`Sandbox.enforce(SandboxAction{type, target})`，`ActionType` = `FILE_READ` / `FILE_WRITE` / `SHELL_COMMAND` / `HTTP_REQUEST`；`WhitelistSandbox` 内部四个校验（路径标准化比对白名单、处理 `../` 穿越；可执行文件精确比对；域名通配符匹配；SMTP `host:port` 精确放行）**归第 24 节**；校验失败抛 `SandboxViolationException`，复用 `ToolExecutor` 既有失败审计路径，不新增审计逻辑；FileTools/ShellTools/HttpTools/WebSearchTools/NotifyTools 的每个涉外 `@Tool` 方法都先 `enforce` 再做 IO（`http_get` / `http_post` 共用 `request()` 里的同一个校验位）。**第 20~23 节挂的是 `PermissiveSandbox` 临时实现，校验不生效——见上文「沙箱窗口期」**
- **加工具的恒定成本（FR-016）**：安全校验位、审计落库、按 Agent 过滤这三处**加工具时一行都不用改**，自动生效。由 `ToolOnboardingCostTest` 钉死：它直接读这三个横切关注点的源文件，断言其中**不含**五个新工具名中的任何一个（git 状态无关的判定，替代原计划的 `git diff --stat`），并用反射做正向对照

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
| `oryxos-tool` | 能力四（三合一）：内置 Tool（File/Shell/Http/Notify + 第 20 节的 Edit/Grep/Glob/AskUser/WebSearch/WebFetch）、`ToolRegistry`（实现 core 的 `ToolCatalog`）、`AnnotatedToolAdapter`、`McpClientService`、`McpToolAdapter`、`McpServerConfigLoader`、`UserInteraction` + 两档实现、`SearchProvider` + 两档实现（免 key 的 DuckDuckGo / 带 key 的 Tavily）、`Sandbox` 接口 + `WhitelistSandbox`（第 24 节） |
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
| 只跑 `--help` / `--version` 就宣称「重命令能用」 | 重命令（`chat` / `serve` / `gateway`）MUST 真跑一次——`--help` 不启动 Spring 上下文，扫不出 Bean 注册撞车（第 18 节实测 `BeanDefinitionOverrideException`） |

## 关键术语速查

- **Agent = 一个目录** `.oryxos/agents/<name>/`：`AGENT.md`（frontmatter=运行配置，正文=任务指令）+ 可选 `skills/` 软连接、`scripts/`、`REFERENCE.md`；Agent 是配置出来的，不是写代码写出来的
- **Profile**：底座内部的运行时宿主配置，由 `AgentLoader.deriveProfile()` 从 `AGENT.md` frontmatter 派生，不是单独手写的 YAML
- **底座 vs Agent 两层**：底座（Provider/ReAct/Tool/Memory/Sandbox/Web）所有 Agent 共享；Agent 只消费底座能力
- **Memory 三层门面**：`MemoryService` 统一收口会话记忆（SessionManager/SQLite）+ 长期记忆（MEMORY.md），情景记忆扩展阶段；长期记忆后端三档可插拔（Markdown 默认 / SQLite / Mem0）
- **三触发源**：CLI、Web Service（人推）+ `AgentScheduler` cron（钟推），同一 `AgentService` 链路
- **入站 Channel ≠ 出站 Notify**：`ChannelAdapter` 解决"消息怎么进来"，`NotifyChannelAdapter`/`notify` Tool 解决"结果推到哪"（企业 IM webhook）；同一个群可能既是某 Agent 的入站、又是另一 Agent 的出站
- **Bootstrap**：`AGENTS.md` / `SOUL.md` / `USER.md` 三个文件，启动时注入 system prompt
