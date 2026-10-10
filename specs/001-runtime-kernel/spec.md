# Feature Specification: OryxOS 核心阶段 —— Agent OS 运行时内核

**Feature Directory**: `specs/001-runtime-kernel`

**Created**: 2026-10-10

**Status**: Draft

**Input**: User description: "请根据文档内容生成规约"（输入文档:`docs/DemandAnalysis.md` + `docs/IndustryResearch.md`;冲突仲裁依据 `CLAUDE.md` 与章程原则 II:验收 Demo 以技术方案第 12 章的 3 个为准）

## 范围说明

本规约覆盖 OryxOS **核心阶段**(4 周 × 3 小时)要交付的 Agent OS 运行时内核:五大核心能力(对接 LLM、ReAct 循环、Memory、Plugin Tool、Web Service)加支撑设施(工作区、CLI、定时任务、持久化与审计留痕、配置与密钥加载)。企业级治理层(多租户、SSO、完整审计查询、Tool Policy)与需求文档第 6 章扩展功能**不在本规约范围**。

## Clarifications

### Session 2026-10-10

- Q: 核心阶段通知渠道通过什么方式注册进全局注册表? → A: 工作区配置文件 `.oryxos/notify_channels.yaml`,启动时导入 SQLite 注册表;API CRUD 与管理台放扩展阶段
- Q: 核心阶段 `oryxos gateway` 的"可用"验收口径是什么? → A: 完整守护语义——挂载已配置的全部入站 Channel(核心阶段仅 CLI Channel,非交互方式)+ 常驻钟推;扩展阶段 IM Channel 即插即用、语义不变
- Q: 审计留痕落库时对凭证类敏感字段如何处理? → A: 脱敏后落库——已知敏感字段名(authorization/token/api_key/password/secret 等)的值统一掩码替换,其余完整记录;同一规则适用于结构化日志

## User Scenarios & Testing *(mandatory)*

> **优先级约定**:本项目的 5 个 user story 按**依赖顺序**推进(US-1 → US-2 →(US-3 ∥ US-4)→ US-5),**不使用 P1/P2/P3 标记**——靠后的 story 是依赖前置,不是"可以不做"(依据 `docs/AiProgrammingGuide.md` §1.3)。每个 story 都有独立可演示的验收方式。

### User Story 1 - 对接 LLM:Agent 不感知用的是哪家大模型 (依赖顺序: US-1)

业务方在 Agent 的 `AGENT.md` 里声明用哪个 Provider、哪个模型;运行时 Agent 通过统一的 Provider 抽象完成 LLM 调用,不感知具体厂商。企业可以随时换厂商(DeepSeek、Kimi、通义等)而不改 Agent 定义,也可以接入本地推理服务让数据不出企业。

**为什么排第一**:没有 LLM 调用,后续一切 Agent 能力(ReAct、Memory 注入、Tool 决策)都无从谈起,它是其余 4 个 story 的共同前置。

**Independent Test**:配置 DeepSeek 与 Kimi 两家 Provider(密钥走环境变量)后,用同一条 Prompt 分别请求两家,两家均返回正常响应,且每次调用的 provider、模型、token 用量、耗时都有持久化留痕。

**Acceptance Scenarios**:

1. **Given** 已配置 DeepSeek 与 Kimi 两家 Provider 且密钥经环境变量注入,**When** 以同一条 Prompt 分别请求两家,**Then** 两家都返回正常响应,每次调用的 provider、模型、token 用量、耗时均持久化留痕。
2. **Given** 某 Provider 的 API key 环境变量缺失或格式非法,**When** 加载配置或首次调用该 Provider,**Then** 系统给出指明缺失项/非法项的清晰报错,不静默失败、不暴露密钥内容。
3. **Given** 多 Provider 并存,**When** Agent 的 Profile 按名称指定 Provider,**Then** 调用精确路由到对应厂商,同类型并存时无歧义。
4. **Given** 某 Provider 服务故障或超时,**When** Agent 发起调用,**Then** 故障直接以错误形式返回给 Agent(核心阶段不做自动切换备用厂商)。

---

### User Story 2 - ReAct 循环:Agent 自己决定调不调工具、调哪个 (依赖顺序: US-2,依赖 US-1)

用户(或业务系统)给 Agent 一条消息,Agent 自主完成"思考 → 决定调用工具 → 看结果 → 再思考"的多步循环,直到给出最终响应。多步骤任务(先读文件、再分析、再调 API、再生成报告)在一次对话内连续完成,业务方不需要预先编排流程。

**依赖位置**:ReAct 循环每轮都要调 LLM(US-1),是 Memory(US-3)注入和 Tool(US-4)执行的唯一宿主。

**Independent Test**:`oryxos chat` 启动交互对话,输入"查一下北京天气并告诉我穿什么",Agent 经 ReAct 循环调用 HTTP Tool 获取天气数据并给出穿衣建议,完整对话(含 LLM 思考与 Tool 结果)累积到会话记录中可查。

**Acceptance Scenarios**:

1. **Given** 一个已配好 Provider 和 HTTP Tool 的 Agent,**When** 用户在 CLI 输入天气查询请求,**Then** Agent 完成至少一轮"LLM 决策 → Tool 执行 → 结果回填 → 最终响应"的循环,并给出穿衣建议。
2. **Given** LLM 连续多轮返回 Tool 调用请求,**When** 迭代次数达到上限(默认 10,可按 Agent 配置覆盖),**Then** 循环强制结束、向用户返回带说明的响应,不陷入死循环。
3. **Given** 对话轮数超过配置上限(默认 20 轮),**When** 新一轮组装 prompt,**Then** 早期对话被截断、近期对话保留,system prompt(含 Agent 指令与 Bootstrap)始终完整,且 system prompt 末尾附当前日期时间。
4. **Given** 一次完整的 ReAct 循环结束,**When** 事后查看该会话,**Then** 每轮 LLM 响应与每次 Tool 结果都按序累积在对话历史中,完整可查。

---

### User Story 3 - Memory:Agent 跨对话记得住用户 (依赖顺序: US-3,依赖 US-2,可与 US-4 并行)

Agent 跨多次对话记住用户的偏好、项目背景和关键决策。用户第一次说"我项目用 Spring Boot,部署在 K8s 上",重启或新开会话后再问"我的项目能用什么数据库",Agent 引用既有偏好作答,不需要用户重新解释。这是 Agent OS 区别于普通 chatbot 的核心体验。

**依赖位置**:记忆在 ReAct 循环组装 prompt 时注入,因此依赖 US-2;与 Tool 体系(US-4)互不依赖,可并行实施。

**Independent Test**:第一次对话告知 Agent 一个偏好并确认其写入长期记忆;重启服务;第二次对话问一个与该偏好相关的问题,Agent 在响应中引用该偏好。

**Acceptance Scenarios**:

1. **Given** 用户告知 Agent "我项目用 Spring Boot,部署在 K8s",**When** Agent 判断该信息值得长期保留并调用 `save_memory`,**Then** 内容被追加到长期记忆的归档区。
2. **Given** 偏好已写入长期记忆,**When** 重启服务或新开会话后问"我的项目能用什么数据库",**Then** Agent 的响应体现此前记录的偏好(记忆已注入 system prompt)。
3. **Given** `save_memory` 刚写入一条记忆,**When** 下一轮对话开始,**Then** 新记忆立即可见(记忆不做缓存)。
4. **Given** 长期记忆超过 4000 字,**When** 注入 prompt 与检索时,**Then** 核心记忆区永远完整注入、不截断、不参与检索;归档记忆区可被截断,关键词检索只命中归档区。
5. **Given** 会话因重启中断,**When** 服务恢复后用户继续对话,**Then** 会话历史完整恢复,上下文无缝衔接。

---

### User Story 4 - Plugin Tool:业务方不写后端代码就能扩展 Agent 能力 (依赖顺序: US-4,依赖 US-2,可与 US-3 并行)

业务方按门槛从低到高三档扩展工具:**零代码**(写一个 Agent 目录 + 复用社区现成 MCP server,主推)、**轻代码**(任何语言自写 MCP server)、**重代码**(写 Java Bean 进程内直调)。底座自带读写文件、执行命令、发 HTTP 请求、记事、发通知的内置工具,所有涉外调用都过白名单校验。能力选择原则:能零代码就不轻代码,能轻代码就不重代码。

**依赖位置**:Tool 由 ReAct 循环(US-2)调度执行;与 Memory(US-3)互不依赖,可并行实施。

**Independent Test**:业务方创建 `.oryxos/agents/daily-pr-digest/` 目录(写一份 `AGENT.md` 描述"拉取昨日 PR 评审进度并汇总简报")并配置一个现成的社区 MCP server,启动后 Agent 能读任务指令、调 MCP 工具拉数据、汇总成简报——全程不写一行 Java 代码。

**Acceptance Scenarios**:

1. **Given** 业务方写了一个 Agent 目录(frontmatter 声明 provider、mcp_servers,正文写任务指令)并配置了社区 MCP server,**When** 启动并发起对话,**Then** Agent 读到任务指令、通过 MCP 协议调用外部工具、完成简报汇总。
2. **Given** Agent 尝试读取白名单之外的文件路径(含 `../` 穿越构造),**When** `read_file` 执行,**Then** 被白名单拒绝、本次调用终止、失败留痕,真实 IO 未发生。
3. **Given** LLM 发起 shell 调用,**When** 命令执行,**Then** 以参数数组直传方式执行(不经 Shell 解释)、仅白名单内可执行文件允许运行、有超时控制。
4. **Given** 业务方用注解写了一个 Java Bean 工具,**When** 系统启动,**Then** 该工具与内置工具、MCP 工具一起进入统一工具注册表,Agent 按其 `tools` 字段过滤出可用子集。
5. **Given** Agent 按名称引用一个已注册的通知渠道调用 `notify`,**When** 发送,**Then** 先过域名白名单校验再发出,具体 webhook 地址不出现在对话内容中,发送结果留痕。

---

### User Story 5 - Web Service:业务系统通过 REST API 接入 OryxOS (依赖顺序: US-5,依赖 US-1~4)

企业现有业务系统(任何能发 HTTP 请求的语言)通过一组 REST 端点使用 Agent 能力:创建会话、发消息、查历史、归档、无状态调用 Agent、查 Profile/记忆/工具/系统状态。这是 OryxOS 区别于个人助手项目的关键能力,是企业把 AI 嵌入已有系统的唯一通道。

**依赖位置**:对外暴露的是前四个能力的成果,因此最后实施;排最后**不代表不重要**。

**Independent Test**:外部系统依次调用:创建会话 → 发消息 → 查历史 → 归档,完整链路成功;再调用健康检查、Profile 列表、工具列表、无状态 Agent 调用、记忆查询,五类端点协同返回正确数据。

**Acceptance Scenarios**:

1. **Given** 外部系统,**When** 依次调用 创建会话 → 发消息 → 查历史 → 归档会话 四个端点,**Then** 完整会话生命周期链路成功,所有响应共用统一信封(状态码、消息、数据、时间戳)。
2. **Given** 外部系统,**When** 依次调用 运行信息、Profile 列表、可用工具列表、无状态 Agent 调用、长期记忆查询,**Then** 五类端点各自返回正确数据,可协同完成一次业务流程。
3. **Given** 服务重启,**When** 再次查询此前活跃的会话,**Then** 会话历史完整恢复,可继续对话。
4. **Given** 请求参数非法、资源不存在、Provider 故障、Agent 调用超时,**When** 调用端点,**Then** 分别返回参数错误(400)、不存在(404)、Provider 故障(503)、超时(504)的统一错误信封。
5. **Given** 一条超过 32KB 的消息,**When** 发送,**Then** 被拒绝并返回参数错误;查询会话历史时最多返回最近 100 条。

---

### Edge Cases

- **LLM 陷入工具调用死循环**:达到最大迭代次数(默认 10)强制结束,返回带说明的响应,不挂死。
- **Provider 故障或超时**:直接向 Agent 报错;核心阶段不做 fallback、不做 hedge racing。
- **白名单拒绝**:文件/Shell/HTTP/通知任意一类校验失败,本次 Tool 调用立即终止并留痕(失败原因记录),真实 IO 不发生。
- **MCP server 启动失败或中途失联**:不阻断系统启动与其他工具使用,错误被记录,该 server 的工具不可用。
- **Agent 目录配置非法**(Provider 不存在、Tool 未注册、Bootstrap 文件缺失):不阻断系统启动,记录错误日志,该 Agent 不可用,其余 Agent 不受影响。
- **长期记忆超长**:核心记忆区永不截断;归档记忆区超 4000 字简单截断。
- **会话上下文超 LLM 窗口**:按配置轮数(默认 20)截断早期对话,保留近期。
- **定时任务上次未执行完又到触发点**:本次跳过,不排队、不并行跑两份。
- **定时任务执行失败**:只记日志与留痕,调度器不崩溃,后续任务照常触发。
- **CLI 单条消息模式**:`oryxos chat --message "..."` 发一条消息后正常退出。

## Requirements *(mandatory)*

### Functional Requirements

#### 工作区与 Agent 定义

- **FR-001**: 系统 MUST 提供 `oryxos init` 命令初始化 `.oryxos/` 工作区(agents/skills/output/memory/sessions/logs 子目录 + AGENTS.md/SOUL.md/USER.md 三个 Bootstrap 文件 + `mcp_servers.yaml`、`notify_channels.yaml` 与 `sandbox.yaml`(沙箱白名单,管理员全局配置)配置模板),且幂等——已存在的目录与文件一律不覆盖。
- **FR-002**: 一个 Agent MUST 完全由一个目录(`.oryxos/agents/<name>/`)定义:`AGENT.md` 的 frontmatter 是运行配置(provider、模型、可用工具、引用的 MCP server、channel、定时规则、迭代与历史轮数设置),正文是任务指令;可选 `skills/` 软连接、`scripts/`、`REFERENCE.md`。业务方 MUST NOT 需要写 Agent 后端代码。
- **FR-003**: 系统 MUST 提供 Agent 管理命令:`profile create <name>`(生成最小 AGENT.md 模板)/ `profile list` / `profile show <name>` / `profile delete <name>`。
- **FR-004**: 系统启动时 MUST 扫描所有 Agent 目录,把每份 frontmatter 派生为运行时 Profile,并做合法性校验(Provider 存在、Tool 已注册、Channel 受支持、Bootstrap 文件存在);校验失败的 Agent MUST NOT 阻断启动,但 MUST 记录错误。
- **FR-005**: 同一实例 MUST 支持多个 Agent 并存可用(目标 ≥ 10 个)。
- **FR-006**: Skill 绑定 MUST 由 Agent 目录内指向公共 Skill 实体库的相对软连接集合表达(唯一真相源),MUST NOT 写进 frontmatter;每轮 prompt 只注入已绑定 Skill 的名称、描述与本地读取路径,Skill 正文与附属资源由 Agent 按需经文件读取工具取用。Skill MUST NOT 注册为工具。
- **FR-007**: 三个 Bootstrap 文件(AGENTS.md / SOUL.md / USER.md)与 AGENT.md 正文 MUST 在每次组装 prompt 时注入 system prompt;上下文加载 MUST 不缓存,文件修改下一轮对话即生效。

#### 对接 LLM(US-1)

- **FR-010**: 所有 LLM 调用 MUST 经统一 Provider 抽象,Agent 不感知具体厂商;更换厂商 MUST 只需改配置,不改 Agent 定义。
- **FR-011**: 系统 MUST 按 provider 名称显式路由到对应 LLM,多 Provider 并存时无歧义。
- **FR-012**: 敏感配置(API key、MCP 凭证等)MUST 经环境变量或独立本地配置文件注入,配置中以 `${ENV_VAR}` 占位,MUST NOT 明文写死在 AGENT.md frontmatter;加载时 MUST 做必填项与格式校验,缺失或非法时给出清晰报错。
- **FR-013**: Provider 故障时 MUST 直接向 Agent 报错;核心阶段 MUST NOT 实现 fallback 与 hedge racing。

#### ReAct 循环(US-2)

- **FR-020**: 系统 MUST 实现 ReAct 循环:接收消息 → 追加会话历史 → 组装 prompt(system prompt + 记忆 + 对话历史 + 可用工具列表)→ 调用 LLM → 无工具调用则返回最终响应,有工具调用则执行并把结果追加回历史继续循环。
- **FR-021**: 最大迭代次数 MUST 默认 10 且可按 Agent 配置覆盖;达到上限 MUST 强制结束并返回带说明的响应。
- **FR-022**: 对话历史 MUST 按配置轮数(默认 20)截断,保留 system prompt 与最近 N 轮。
- **FR-023**: 每轮迭代的 LLM 响应与工具结果 MUST 按序累积到会话对话历史,完整可查。
- **FR-024**: system prompt 末尾 MUST 附当前日期时间。
- **FR-025**: 系统 MUST 提供 CLI 交互对话(`oryxos chat`),支持多轮对话、`--profile` 指定 Agent、`--message` 单条消息后退出、`/quit` 退出。

#### Memory(US-3)

- **FR-030**: 会话记忆 MUST 按 渠道 + 用户 + Agent 联合标识管理并持久化,服务重启后可恢复。
- **FR-031**: 长期记忆 MUST 存于单一 Markdown 文件,内部分核心记忆区与归档记忆区;核心区 MUST 永远全量注入、不截断、不参与检索;归档区超 4000 字 MUST 可截断,关键词检索 MUST 只命中归档区。
- **FR-032**: 系统 MUST 提供 `save_memory`(追加写入,可指定写入核心区或归档区)与 `recall_memory`(关键词检索)两个内置工具。
- **FR-033**: 长期记忆 MUST 每次组装 prompt 时重新读取、不缓存,写入后下一轮立即可见。
- **FR-034**: USER.md(用户手写,系统只读)与长期记忆文件(系统读写)MUST 都进入 system prompt。

#### Tool 体系(US-4)

- **FR-040**: 所有工具(内置、注解 Java Bean、MCP 工具)MUST 统一注册到一个工具注册表,以统一形式(名称、描述、入参 schema、执行)对外;ReAct 循环 MUST NOT 感知工具来源;Agent 按其 `tools` 字段过滤可用子集。
- **FR-041**: 内置工具 MUST 包含 9 个:`read_file`、`write_file`、`list_dir`、`shell`、`http_get`、`http_post`、`save_memory`、`recall_memory`、`notify`。
- **FR-042**: 涉外工具调用 MUST 先过白名单校验再执行:文件工具走路径白名单(须处理 `../` 穿越);`shell` 以参数数组直传执行、MUST NOT 经 Shell 解释、可执行文件走精确白名单、有超时;HTTP 工具走域名白名单(支持通配符);邮件类出站走独立的 `host:port` 端点白名单。
- **FR-043**: 白名单校验失败 MUST 终止本次工具调用并留痕(失败 + 原因),不新增独立审计路径。
- **FR-044**: 外部 MCP server MUST 经 `mcp_servers.yaml` 配置(名称、传输方式、命令、环境变量);系统启动时连接、拉取工具列表并注册;server 失联、超时 MUST 有错误恢复且不阻断系统。
- **FR-045**: 注解式 Java Bean 工具 MUST 在系统启动时自动扫描注册,进程内直调。
- **FR-046**: `notify` 工具 MUST 按名称引用全局通知渠道注册表(渠道实体独立于 Agent 配置与 frontmatter 管理);渠道 MUST 经工作区配置文件 `.oryxos/notify_channels.yaml`(name/type/url/config/description,URL 可用 `${ENV_VAR}` 占位)声明,系统启动时导入注册表;核心阶段渠道类型 MUST 仅支持通用 webhook(承接企业微信/飞书/钉钉群机器人),SMTP 等专用类型放扩展阶段;具体 webhook 地址 MUST NOT 出现在对话内容中;发送前 MUST 过域名白名单。

#### 三触发源与定时任务

- **FR-050**: CLI、Web Service(人推)与定时任务(钟推)MUST 汇入同一条处理链路;Agent 循环 MUST NOT 感知消息来源。三种运行模式(`chat` / `serve` / `gateway`)MUST 共享同一份 Profile 配置与会话存储。`gateway` MUST 以守护进程常驻:挂载已配置的全部入站 Channel(核心阶段仅 CLI Channel 可配,以非交互方式挂载)并常驻定时调度;渠道配置错误 MUST 给出清晰报错;扩展阶段新增 IM Channel MUST 即插即用接入 gateway、不改变其语义。
- **FR-051**: 系统 MUST 按 Agent frontmatter 的 `schedules`(cron、时区、消息内容)到点自动触发调用;同一任务上次未执行完时本次 MUST 跳过,不重叠、不排队。
- **FR-052**: 定时任务执行失败 MUST NOT 影响调度器与后续触发;失败调用 MUST 走与人推相同的留痕路径;钟推会话 MUST 以固定身份(channel 与 user 均为 scheduler)复用同一会话。
- **FR-053**: 定时任务的登记信息与运行状态、每次执行历史(成败、错误、耗时)MUST 持久化,重启不丢;任务定义的唯一来源 MUST 仍是 AGENT.md 的 `schedules`,持久化只存状态与历史。

#### Web Service(US-5)

- **FR-060**: 系统 MUST 提供核心 10 个 REST 端点:会话管理 4 个(创建、发消息、查历史、归档)、无状态 Agent 调用 1 个、Profile/记忆/工具查询 3 个、健康检查与运行信息 2 个。
- **FR-061**: 所有端点 MUST 共用统一响应信封(状态码、消息、数据、时间戳);错误 MUST 使用标准 HTTP 状态码(400 参数错误、404 不存在、500 内部错误、503 Provider 故障、504 调用超时)。
- **FR-062**: 单条消息 MUST 限制最大 32KB;会话历史查询 MUST 最多返回最近 100 条;Agent 调用 MUST 60 秒超时。
- **FR-063**: 核心阶段 MUST NOT 实现认证(假设内网);CORS 全开放便于调试。
- **FR-064**: API 文档 MUST 自动生成并可在线浏览。

#### 持久化与审计留痕

- **FR-070**: 会话数据(标识、关联 Agent、渠道、用户、对话历史、active/archived 状态、时间戳)MUST 本地持久化,跨重启恢复;归档会话可查。
- **FR-071**: 每次工具调用 MUST 持久化留痕:工具名、入参、结果、成败、错误信息、耗时。MUST NOT 只写日志。入参与结果落库前 MUST 对已知敏感字段名(authorization、token、api_key、password、secret 等,大小写不敏感)的值做掩码脱敏,其余内容完整记录。
- **FR-072**: 每次 LLM 调用 MUST 持久化留痕:provider、模型、输入/输出/总 token 数、耗时。MUST NOT 只写日志。
- **FR-073**: 工具调用失败 MUST 按重试策略再调,默认指数退避最多 3 次(可重试的错误才重试)。
- **FR-074**: 每次 LLM 调用与工具调用 MUST 同时落结构化 JSON 日志;日志与审计留痕 MUST 适用同一套敏感字段脱敏规则(FR-071)。

#### 命令行

- **FR-080**: 系统 MUST 提供 12 个命令:`init`、`status`、`chat`、`serve`、`gateway`、`profile list/create/show/delete`、`provider list`、`tool list`、`session list`。
- **FR-081**: 不需要 LLM 的命令(如 `init`、`profile list`)MUST 直接走文件操作,保证启动速度;需要 LLM 的命令才启动完整运行时。

### Key Entities

- **Agent 目录**:一个业务 Agent 的定义本体(`.oryxos/agents/<name>/`):`AGENT.md`(frontmatter = 运行配置,正文 = 任务指令)+ 可选 skills/ 软连接、scripts/、REFERENCE.md。
- **Profile**:由 `AGENT.md` frontmatter 派生的运行时宿主配置:名称、描述、人格/系统提示词、Provider 与模型、可用工具子集、引用的 MCP server、渠道、定时规则、Bootstrap 列表、迭代与历史轮数设置。
- **Session(会话)**:渠道 + 用户 + Agent 联合标识的对话上下文容器:对话历史、状态(active/archived)、创建/最后活跃/归档时间。
- **长期记忆**:单一 Markdown 文件,分核心记忆区(全量注入、不截断、不检索)与归档记忆区(可截断、可关键词检索)。
- **工具调用留痕**:每次工具调用的名称、入参、结果、成败、错误、耗时。
- **LLM 调用留痕**:每次 LLM 调用的 provider、模型、token 用量、耗时。
- **通知渠道**:全局注册表项(名称、类型、地址、配置、描述),独立于 Agent 配置,Agent 按名引用;定义源是工作区配置文件 `notify_channels.yaml`,启动时导入注册表,核心阶段仅 webhook 类型。
- **定时任务**:定义源是 AGENT.md 的 `schedules`(cron、时区、消息);运行状态(启用/退役、下次/上次触发、累计次数)与每次执行历史(成败、错误、耗时)持久化。
- **Bootstrap 文件**:AGENTS.md(项目级行为说明)、SOUL.md(人格定义)、USER.md(用户偏好,只读)。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 三个每日自动运行的验收 Demo(每日天气、每日科技日报、每日 GitHub 日报)**无需人工触发**、到点自动跑通完整链路;且同一 Agent 可通过手动入口(CLI 或 API)补跑一次,两种触发方式走同一条处理链路。三个 Demo 跑通是核心阶段发布的硬条件。
- **SC-002**: 业务方**零 Java 代码**上线一个新的定时推送 Agent:只写一个目录 + 编辑配置文件,全程不改任何后端代码(由每日科技日报 Demo 验证)。
- **SC-003**: 100% 的工具调用与 LLM 调用(含失败与定时任务触发的调用)有持久化留痕,任意一次调用可事后查到输入、输出、成败与耗时。
- **SC-004**: 单实例 ≥ 10 个 Agent 并存,稳定运行 4 小时无故障;支持 ≥ 100 个并发会话。
- **SC-005**: 会话创建 P99 延迟 ≤ 200ms;系统内部转发开销 ≤ 50ms(不含 LLM 厂商延迟)。
- **SC-006**: 服务重启后,进行中的会话与长期记忆 100% 恢复、零丢失。
- **SC-007**: Agent 跨对话记住用户偏好并在后续对话中引用(验收路径:告知偏好 → 写入 → 重启 → 相关问题回答体现偏好)。
- **SC-008**: 12 个 CLI 命令与 10 个 REST 端点全部可用;参数错误、资源不存在、Provider 故障、超时四类错误都返回统一格式的错误响应。
- **SC-009**: 新用户参照部署文档 30 分钟内完成单节点部署;命令行工具有清晰的帮助与错误提示。
- **SC-010**: 至少 DeepSeek 与 Kimi 两家 LLM 真实跑通。

## Assumptions

- **已有地基**:Maven 9 模块骨架、核心契约接口(工具/沙箱/长期记忆)、审计表实体与建表脚本、项目官网均已存在,不在本规约重复定义;本规约覆盖其上的业务实现。
- **验收 Demo 口径**:以技术方案第 12 章的 **3 个** Demo(每日天气/每日科技日报/每日 GitHub 日报)为准;需求文档第 13 章的 2 个为旧版口径(依据 CLAUDE.md 冲突仲裁规则与章程原则 II)。
- **多 Provider 验收口径**:需求文档 §5.3 写"先跑通 DeepSeek 或 Kimi",§13 验收标准写"至少跑通 DeepSeek 和 Kimi 两个"——以验收标准为准,两家都要真实跑通。
- **核心阶段明确不做**(扩展阶段范围):认证鉴权(假设内网)、SSE 流式响应、WebSocket、RBAC、限流、Provider fallback、向量检索、记忆自动抽取、情景记忆、多 IM Channel、多租户/SSO、审计查询接口与报表、定时任务的运行时 API 增删改(含 `/api/v2` 调度管理端点)、Web 管理台、GraalVM Native Image。
- **运行环境**:JDK 21 及以上;Linux 主流发行版;单二进制部署;数据不出企业基础设施。
- **Demo 推送前置**:三个每日 Demo 的 `notify` 推送依赖一个可达的 webhook 端点(真实 IM 群机器人或等价 HTTP 端点),经 `notify_channels.yaml` 配置;自动化验收可用等价 HTTP 端点替代真实群机器人。
- **安全前提**:应用层白名单是"劝阻级"防线,核心阶段不用于跑完全不可信代码或对外多租户。
- **实施节奏**:4 周 × 3 小时,每周有可演示成果;某周完不成时把末段功能挪到扩展阶段,保证演示链路完整。
