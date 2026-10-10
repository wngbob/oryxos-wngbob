# OryxOS 章程

## 核心原则

### I. JDK 21 + Spring Boot 3.x 单体,单二进制交付

全部代码 MUST 基于 JDK 21 与 Spring Boot 3.x;MUST NOT 使用 JDK 21 中已废弃或
不可用的机制(Java SecurityManager 见原则 VII)。工程结构 MUST 为 Maven 多模块
(核心阶段 9 个),`mvn clean package` 打出单个 fat JAR,`java -jar` 即可启动,
MUST NOT 引入额外的运行时进程依赖。执行模型 MUST 为同步阻塞 + Java 21 virtual
thread;核心阶段 MUST NOT 引入响应式编程,SSE 流式响应与异步 Tool 调用放扩展阶段。
理由:「易接入」是四个设计目标之一——OryxOS 必须是一个标准 Spring Boot 工程,
装上就跑、直接纳入企业现有 Java 运维体系;同步模型配合 virtual thread 足以支撑
目标并发,响应式的复杂度没有被证实的需求。

### II. 核心阶段只交付运行时内核

核心阶段 MUST 只做五大核心能力(对接 LLM、ReAct 循环、Memory、Plugin Tool、
Web Service);企业级治理层(多租户、SSO、完整审计查询、Tool Policy)MUST NOT
在核心阶段开工。没有被证实的需求之前,MUST NOT 引入抽象、依赖或功能(YAGNI);
任何复杂性 MUST 在计划中给出正当理由。验收范围以技术方案第 12 章的 3 个验收
Demo(每日天气、每日科技日报、每日 GitHub 日报)为准。范围冲突时砍功能不砍
链路,跑通优先于完美。
理由:4 周 × 3 小时是极强的时间约束,只有卡紧范围才能每周交付可演示成果;
核心阶段是地基,企业级治理是终局,顺序不能反。

### III. Agent 引擎自实现,Spring AI 只用一半

ReAct 循环 MUST 由 OryxOS 自实现(`ReActLoop` + `PromptBuilder` +
`ToolExecutor`),MUST NOT 依赖 Spring AI 的 Agent 抽象。Spring AI /
Spring AI Alibaba 只允许用于三件事:Provider 抽象、向各家 LLM 的协议转换、
`@Tool` 注解的 JSON Schema 生成。Spring AI 的自动 tool 执行 MUST 被禁用——
这是全项目最易写错的一条,启用自动执行会导致 Tool 被调用两次;Tool 的调度与
执行 MUST 完全由 `ToolExecutor` 控制。provider name → `ChatModel` MUST 维护
显式映射表;MUST NOT 按类型扫描容器中的 `ChatModel` Bean(多 Provider 并存时
Bean 类型相同,类型扫描必有歧义)。
理由:Agent 核心循环是系统中枢,必须完全可控;Spring AI 在 OryxOS 里只做
协议适配器和 schema 生成器,不做循环引擎。

### IV. Agent 目录不是 Tool

一个 Agent = 一个目录(`.oryxos/agents/<name>/`)。`AGENT.md` 的解析归
`AgentLoader` / `ContextLoader`(`oryxos-core`),MUST NOT 放进 Tool 体系、
MUST NOT 注册进 `ToolRegistry`。`AGENT.md` 正文 MUST 由 `ContextLoader` 注入
system prompt(与 Bootstrap 文件同层);Agent 目录的子资源(Skill 正文、脚本、
参考)MUST 经底座既有能力(`read_file` / `shell`)按需取用,MUST NOT 新造机制。
Skill 绑定的唯一真相源 MUST 是 Agent 目录内指向公共实体(`.oryxos/skills/<name>/`)
的相对软连接集合,MUST NOT 写进 frontmatter;Skill 不是 Tool。通用 `shell` 调用
解释器等于授予模型宿主机进程权限:MUST 仅在管理员显式白名单内放行,且只能用于
可信单机部署;不可信或多租户的代码执行等未来的容器/microVM Runner。
理由:Agent 目录是 prompt 的输入源(上下文),不是可执行能力;把它当 Tool 会
破坏 Tool 抽象的统一性,并让上下文加载与工具执行两层纠缠。白名单是劝阻级防线,
不替代真隔离。

### V. Tool 体系统一抽象、单一模块

所有 Tool(内置 Tool、`@Tool` Java Bean、MCP Tool)MUST 包装为统一的
`OryxTool` 实例注册进 `ToolRegistry`;ReAct 循环 MUST NOT 感知 Tool 来源。
内置 Tool、MCP Client、`ToolRegistry`、Sandbox MUST 合并为单个 `oryxos-tool`
模块,MUST NOT 拆成 builtin/skill/mcp 多模块。Plugin Tool 三档接入的能力选择
MUST 遵循:能零代码(`AGENT.md` 目录 + 复用社区 MCP server,主推)就不自写
MCP server,能自写 MCP server 就不写 `@Tool` Java Bean。
理由:三档共享同一抽象与注册表,耦合天然高,拆模块只会制造接缝;门槛最低的
方式覆盖最广的业务方,是「业务方不写 Agent 后端代码」理念的兑现。

### VI. 审计数据 Day One 落库

`tool_invocations` 与 `llm_calls` 两张审计表 MUST 在核心阶段就写入 SQLite,
MUST NOT 只写日志;查询接口和审计报表可放扩展阶段,写入不行。定时任务(钟推)
触发的调用——包括失败调用——MUST 走与人推完全相同的审计路径。Sandbox 校验
失败 MUST 复用 `ToolExecutor` 既有的失败审计路径(`success=false` +
`error_message`),MUST NOT 为 Sandbox 单独新增审计逻辑。
理由:「可审计」是 OryxOS 的差异化卖点,数据地基 day one 不立起来,后期只能
从零散日志反解析返工。

### VII. 接口先行,实现按档演进

承载未来升级的契约(`Sandbox`、`LongTermMemoryStore`、`NotifyChannelAdapter`
等)MUST 先定不携带任何一档实现细节的中立接口——用最重的 microVM 实现反套
接口签名,也应能干净套入;核心阶段只在接口后挂一档实现(如 `WhitelistSandbox`
应用层白名单)。升级 MUST 只新增实现类,MUST NOT 改接口、MUST NOT 改调用方。
MUST NOT 使用 Java SecurityManager(JDK 17 起废弃、JDK 21 已不可用);核心阶段
的沙箱 = `Sandbox` 接口 + `WhitelistSandbox`。
理由:隔离强度与开销是跷跷板,核心阶段的实现选择不能绑死未来架构;接口墙让
升级成为纯增量。

### VIII. 数据私有,状态外置、存储从简

核心阶段持久化 MUST 为 SQLite + Spring Data JPA;长期记忆 MUST 以 `MEMORY.md`
文件为默认后端;向量检索 MUST NOT 在核心阶段引入,`LongTermMemoryStore` 接口
MUST 预留升级空间。运行实例 MUST 无状态:会话、记忆、审计等一切状态 MUST 外置
到 SQLite / 文件系统,实例自身 MUST NOT 持有不可重建的状态——这是阶段二分布式
演进(实例无状态、状态外置)的前提。敏感配置(LLM API key、Provider/MCP 凭证)
MUST 经环境变量注入或独立本地配置文件加载,Profile 中用 `${ENV_VAR}` 占位;
MUST NOT 明文写死在 `AGENT.md` frontmatter,MUST NOT 提交进版本控制。OryxOS
MUST NOT 主动外发任何企业数据,一切数据留在部署方自己的基础设施。
理由:「私有」是四个设计目标之一,也是严监管企业的铁律;单二进制定位下
SQLite + 文件系统覆盖核心阶段全部存储需求,向量库这类外部进程依赖与定位冲突;
状态外置现在不立规矩,分布式阶段就要大改设计。

### IX. 三触发源复用同一条链路

CLI、Web Service(人推)与 `AgentScheduler`(钟推)MUST 汇入同一个
`AgentService.process` 入口;`ReActLoop` MUST NOT 感知消息来源。未来新增触发源
(新 Channel、Webhook)MUST 以同样方式接入,MUST NOT 在触发源里复制 Agent
处理逻辑。
理由:一条链路意味着一处审计、一处错误处理、一处记忆注入;定时任务只有复用
同一链路,其失败调用才自然落入同一审计路径(原则 VI)。

### X. 每个 user story 有可演示 Demo

主体开发 MUST 按 5 个 user story 的依赖序推进
(US-1 → US-2 →(US-3 ∥ US-4)→ US-5);每个 user story 结束时 MUST 有
可演示成果。某周/某 story 完不成时,MUST 把末段功能挪到扩展阶段,保证演示
链路完整,而不是牺牲「能跑」去追求「完美」。
理由:可演示成果是范围失控的保险丝,也是「做减法的哲学」的执行机制。

## 附加约束

- 设计目标(需求文档第 3 章):统一、私有、易接入、可观测 MUST 是一切取舍的
  准绳。最重要的交付是「让任意 Agent 可靠运行的底座」,而非某个强大的单体
  Agent——业务方 MUST 只需写一个 Agent 目录(`AGENT.md`)+ 配置 MCP server
  就能解决业务问题,MUST NOT 需要写 Agent 后端代码。
- 定位边界:OryxOS MUST 守住运行时层,MUST NOT 做可视化 workflow 编排、复杂
  任务分解、多 Agent 显式协作;需要编排时由 Dify 类平台作为客户端调 OryxOS 的
  API(互补不竞争)。
- 演进路线(业界调研第 6 章):阶段一 单机私有部署(当前)→ 阶段二 底座分布式
  (多实例 + 状态外置 + 高可用)→ 阶段三 跨节点 Agent 协作(A2A)。核心阶段
  MUST NOT 提前引入分布式基础设施,但架构 MUST 为状态外置预留位置。
- 开放标准优先:工具接入用 MCP,技能目录沿用 agentskills.io 的「目录 + 渐进式
  披露」形态,跨节点协作用 A2A;MUST NOT 另立协议。
- 技术栈:JDK 21、Spring Boot 3.x、Spring AI Alibaba、SQLite + Spring Data
  JPA、Picocli、SnakeYAML、Logback/SLF4J。GraalVM Native Image、Micrometer /
  Prometheus 属扩展阶段。引入新依赖 MUST 对照原则 II 给出明确理由。
- 模块结构:核心阶段 9 个 Maven 模块,以最新技术方案第 10 章为准。依赖方向
  为「契约在 core、实现在能力模块」,`oryxos-core` MUST NOT 反向依赖任何能力
  模块;`Sandbox` / `NotifyChannelAdapter` 契约放 `oryxos-tool` 是既定例外
  (内置 Tool 在自己的 `execute` 开头自调 `enforce`,core 全程不引用,不成环)。
- 安全基线:安全 MUST 是 day one 的设计,而非事后打补丁(业界调研 §5.6)。
  核心阶段落地:最小权限白名单(文件系统 / 网络 / shell 默认收紧、按需放开)、
  凭证不落地(环境变量或企业密钥体系,MUST NOT 明文进代码、配置、日志或提交
  历史)、全链路审计落库。来源受控(注册 / 审核 / 签名 / 版本管理)、prompt
  injection 与数据外泄的主动防御、多租户隔离随扩展阶段补齐,但架构 MUST 预留
  位置。
- 白名单是「劝阻级」防线,防误操作不防蓄意绕过;核心阶段 MUST NOT 用
  `WhitelistSandbox` 跑完全不可信的代码或对外做多租户。跨越信任边界的输入
  MUST 经过校验。
- 可观测:结构化 JSON 日志(Logback/SLF4J)MUST 覆盖每次 LLM 调用与 Tool
  调用,MUST NOT 用 `System.out` 输出;Prometheus 指标放扩展阶段。
- 表结构演进:SQLite 的 `ALTER TABLE` 能力弱,首次建表可用 `ddl-auto=update`,
  但后续演进 MUST NOT 依赖 update 自动迁移——手工维护建表脚本或引入
  Flyway/Liquibase。
- 性能目标(需求文档第 8 章):单节点 ≥ 10 个 Agent、≥ 100 个并发 Session、
  Session 创建 P99 ≤ 200ms、内部转发开销 ≤ 50ms;与某功能相关的性能目标 MUST
  以可度量验收标准的形式写入该功能的规格说明。
- 许可证:Apache 2.0。

## 开发工作流

- 文档面向双重读者(沿用 v1.1.0 原则 VI,强制力不变):项目中生成的所有文档
  (spec、plan、tasks、代码注释、README 等)同时面向 AI 与人类两类读者,MUST
  对人类友好——结构清晰、语言明确、术语统一、段落简短;机器可读性与人类可读性
  冲突时,在不破坏自动化处理的前提下,人类可读性优先。
- 主体开发 MUST 遵循 Spec Kit 流程:specify →(clarify)→ plan → tasks →
  implement;规格说明获批之前 MUST NOT 开始实现。Spec-Kit 锁定 v1.0.10,主体
  开发期间 MUST NOT 升级;只用官方核心命令,不引入社区扩展。一次只推进一个
  特性,不并行开多个 spec。
- 输入文档:`/speckit-specify` 的输入是需求文档,`/speckit-plan` 的输入是
  技术方案;文档间冲突时 MUST 以最新技术方案为准(核心阶段模块数为 9 个)。
- plan 生成后 MUST 人工 review,重点核对四项:Memory 未被简并(应是
  `MemoryService` 三层统一门面)、Tool 未被拆成多模块、`AGENT.md` 未被当成
  Tool、Spring AI 自动 tool 执行已禁用。review 通过后 `plan.md` 锁定。
- 每个 user story 结束 MUST 跑 `/speckit-analyze` 做一致性检查(不能省),
  并打 git commit 标记完成,便于随时回退到稳定状态。
- 静态检查门禁:Spotless(Google Java 格式)+ 阿里 P3C 编码规约 +
  Checkstyle + SpotBugs / Find Security Bugs + OWASP Dependency-Check MUST
  全部接入 `mvn verify`,任一检查失败即阻断合并;pre-commit 本地把关格式,
  CI 跑 `mvn verify`。新增检查项或调整阈值 MUST 走章程修订程序。
- 质量门禁:每个功能模块 MUST 至少有一个端到端测试用例覆盖(需求文档第 13
  章);每个 user story 的验收 Demo MUST 实际跑通;违反任何核心原则的实现
  MUST 返工,除非存在已批准的章程修订或有书面记录的例外。
- 章程保护:本章程写定后,主体开发期间不改;发现某条原则有问题 MUST 停下来
  由项目维护者重新讨论,AI agent MUST NOT 自行修改本章程。

## 治理

本章程的效力高于项目中的其他一切实践与约定。

- 修订程序:任何变更 MUST 通过书面提案 / PR 提出,说明动机与影响,并经项目
  维护者批准;若影响既有工作,还须随修订一并记录迁移计划,并同步更新受影响的
  Spec Kit 模板(spec / plan / tasks)与运行时指南(`CLAUDE.md`、`docs/`)。
- 版本策略(语义化版本):MAJOR 用于不向后兼容的治理变更,或原则的移除/重新
  定义;MINOR 用于新增原则或章节、或对指导内容的实质性扩充;PATCH 用于澄清、
  措辞及其他非语义性修订。
- 符合性审查:在每次规格评审、计划评审与代码评审中都要检查章程符合性;对无
  正当理由的复杂性 MUST 予以标记,要么简化,要么明确给出理由。
- 本文件顶部的同步影响报告注释属于审阅临时材料,每次修订后的文件版本提交前
  MUST 将其删除。
- 运行时开发指南以 `CLAUDE.md` 为准,其内容 MUST 与本章程保持一致;二者冲突
  时以本章程为准,并立即修正 `CLAUDE.md`。

**版本**: 2.0.0 | **批准日期**: 2026-09-24 | **最近修订**: 2026-10-10
