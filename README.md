<p align="center">
  <img src="website/public/images/logo.svg" alt="OryxOS Logo" width="380"/>
</p>

# OryxOS

**企业 Agent 操作系统（Agent Harness OS）—— 让每一家公司，都能用自然语言跑起来自己的 Agent。**

> 📌 本仓库是上游开源项目 [oryx-labs/oryxos](https://github.com/oryx-labs/oryxos) 的**个人练习复刻**（跟随设计文档从零重建，用于学习与验证），非官方仓库。

[![Language](https://img.shields.io/badge/language-Java%2021-orange.svg)](https://openjdk.org/)
[![Framework](https://img.shields.io/badge/framework-Spring%20Boot%203.x-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Status](https://img.shields.io/badge/status-%E8%BF%90%E8%A1%8C%E6%97%B6%E5%86%85%E6%A0%B8%E5%B7%B2%E8%B7%91%E9%80%9A-brightgreen.svg)](#项目状态)
[![Community](https://img.shields.io/badge/community-oryx--labs-purple.svg)](docs/oryx-labs.md)
[![Website](https://img.shields.io/badge/website-wngbob.github.io%2Foryxos--wngbob-ff69b4.svg)](https://wngbob.github.io/oryxos-wngbob/)

---

## 简介

OryxOS 是企业私有部署的 **Agent 操作系统**：装在企业自己的 K8s、虚拟机或物理机上，作为统一底座运行各类业务 Agent（运维助手、客服助手、HR 助手、销售助手、知识管理助手……），共享渠道接入、模型路由、工具调用、记忆系统、沙箱执行能力。**数据完全留在企业自己的基础设施，不锁任何云生态。**

我们的判断是：让 Agent 在生产环境可靠工作，瓶颈通常不在模型本身，而在 **Agent 的运行环境**。OryxOS 做的不是又一个 Agent，而是让一群 Agent 可靠运行和协同的底座本身。

**北极星公式**：`自然语言(md) + Memory + Tool + MCP(Connector) + Skill + 知识库 + Notify = 一个 Agent`

## 为什么需要 OryxOS

每家公司都有该交给 Agent 的活，但 Agent 大多还停在 demo，卡在四道门槛上：

| 门槛 | OryxOS 的解法 |
|------|--------------|
| 定义一个 Agent 要写代码，最懂业务的人反而做不了 | **自然语言定义**：一个包含 `AGENT.md` 的目录就是一个 Agent，零代码 |
| 云平台要把数据拿走，合规过不去 | **私有部署**：数据不出域，装在自己机器上 |
| 执行是黑盒，企业不敢上生产 | **全链路审计 + 沙箱**：每次 LLM/Tool 调用落库可查，白名单强制隔离 |
| 跑一个容易、跑一群难 | **Agent Harness OS**：为一整队 Agent 准备的生命周期与治理 |

## Agent OS 与 Agent runtime 的区别

这两个概念常被混用，但它们是两层东西：

- **Agent runtime**：让**单个** Agent 跑起来的执行内核——LLM 调用、工具执行、上下文管理、循环控制
- **Agent OS（Agent Harness OS）**：内核包含一个 runtime，但在其之上还要管**一群** Agent——多 Agent 的生命周期、统一的对外渠道与对内接入、统一的记忆、多租户与审计治理

借操作系统类比：runtime 像单个进程的执行环境，Agent OS 像管理一群进程、调度资源、提供共享服务和治理的那一层。一句话：**runtime 让一个 Agent 跑起来，Agent OS 让一群 Agent 在企业里被管起来。** OryxOS 是后者。

跟相邻三类东西的边界：

| | 产物 | 谁来用 | 与 OryxOS 的关系 |
|---|------|--------|-----------------|
| Agent 框架（LangChain、Spring AI） | 代码（库 / SDK） | 开发者写代码 | **复用**：OryxOS 的 LLM 调用层直接复用 Spring AI Alibaba |
| 编排平台（Dify、Coze） | 可执行的 workflow 流程 | 业务/开发者拖拽编排 | **互补**：编排平台可跑在 OryxOS 之上当应用层 |
| 大厂中台 / SaaS | 完整应用 | 业务人员 | **替代**：OryxOS 开源、可私有部署、不锁任何云生态 |
| **Agent OS（OryxOS）** | **配置出来的常驻 Agent** | 业务方配置 + 开发者写 Tool | —— |

一句话总结：框架给你材料自己盖房子；编排平台编排的是"流程"；OryxOS 是盖好的房子——一个让 Agent 常驻、可治理、可审计地跑起来的底座。

## 核心特性

- 🤖 **一个目录 = 一个 Agent**：`AGENT.md`（frontmatter 运行配置 + 正文任务指令）定义一个 Agent，不写代码，多 Agent 同实例并存
- 🔄 **动态管理 Agent，全程免重启**：说一句话生成文件、调一次 API 新建、往 `.oryxos/agents/` 丢一个目录——三条路径同一段注册代码，几秒内出现在列表里并按 cron 自己跑；删除是整目录归档，可追溯
- ☕ **Java 原生**：JDK 21 + Spring Boot 3.x，单可执行 JAR 部署，无缝复用企业现有 Java 运维工具链（Nacos、Sentinel、SkyWalking、Arthas、Prometheus + Grafana）
- 🔒 **私有可控**：数据不出企业，不绑任何云；面向严监管行业（银行、政府、电信、能源、医疗）的确定性选择
- 🛡️ **安全是地基不是补丁**：工具白名单沙箱、凭证不落地走环境变量/企业密钥体系、`tool_invocations` / `llm_calls` 审计表 Day One 落库
- 🧠 **自实现 ReAct 引擎**：核心推理循环自己实现，不套外部 Agent 框架，循环行为完全可控
- 🔌 **对接开放标准**：工具用 MCP、Agent 协作用 A2A、Agent 目录借 Anthropic Agent Skills 目录形态，与生态协同不另立协议
- 🧩 **三档工具扩展**：零代码 Agent 目录 + 复用 MCP（主推）→ 任何语言自写 MCP server → `@Tool` Java Bean，按门槛自由选择
- 💾 **跨对话记忆**：会话记忆 + 长期记忆两层（`MEMORY.md`），接口预留向量检索升级空间
- 🌐 **无状态可扩展**：实例无状态、状态外置，从架构第一天为分布式留好路
- ⏰ **三种触发源，一条链路**：CLI 与 REST API（人推）、cron 定时任务（钟推）汇入同一个 `AgentService`，引擎不感知消息来源
- 📊 **开箱即用的管理台**：`/admin/` 下六页运行时视图（概览、会话、Agent、工具、定时任务、运行状态），与 REST 端点同端口；Agent 详情页（新建 / 生成文件 / 编辑文件 / 固定会话 / 专属记忆）与定时任务页可写，其余只读

## 五大核心能力

| 能力 | 说明 |
|------|------|
| **对接 LLM** | Provider 抽象统一对接主流大模型（DeepSeek、通义、Kimi、智谱、混元、豆包、Anthropic、OpenAI…），Agent 不感知厂商，运行时切换无锁定，支持本地推理（Ollama、vLLM） |
| **ReAct 循环** | Agent 的推理引擎：LLM 思考 → 调工具 → 看结果 → 再决定，直到给出最终响应；自实现、行为完全可控 |
| **Memory 记忆** | 会话 + 长期两层记忆，跨对话记住用户偏好、项目背景、关键决策 |
| **Tool 工具体系** | 内置 **15 个**工具（文件读写编辑、Shell、HTTP、网页搜索与抓取、通知推送、交互提问、长期记忆），Plugin Tool 三档扩展，白名单沙箱强制隔离 |
| **Web Service** | 27 个 REST 端点统一封装全部能力，任何能发 HTTP 的语言都能接入；同一端口提供管理台（Agent 与定时任务页可写）与 OpenAPI 文档 |

## 架构

![OryxOS 整体架构](website/public/images/docs-architecture-light.svg)

- **三触发源统一入口**：CLI / Web Service（人推）与 AgentScheduler（钟推）汇入同一个 `AgentService`，ReActLoop 不感知消息来源
- **引擎调度三能力**：`ReActLoop` 每轮经 `PromptBuilder` 组装上下文、经 `ProviderService` 调 LLM、经 `ToolExecutor`（先 Sandbox 白名单校验）执行工具，审计随调用落库
- **存储下沉**：Session、审计、长期记忆（SQLite 档）与通知渠道落 SQLite；Agent 目录、Bootstrap、`MEMORY.md` 落文件系统——可直接编辑、git 可跟踪
- **外部依赖全部在边界之外**：LLM API、外部 MCP server、企业 IM webhook，OryxOS 不绑定任何一家

## 模块结构

核心阶段为 9 个 Maven 模块，模块间通过接口解耦，`oryxos-boot` 负责聚合：

| 模块 | 职责 |
|------|------|
| `oryxos-core` | 核心抽象与引擎：`OryxTool`、`Session`、`Profile`、`ProfileLoader`（目录解析 / Profile 派生）、`ContextLoader`、`ReActLoop`、`PromptBuilder`、`ToolExecutor`、`AgentService`、`AgentScheduler`、`AgentLifecycleService`（Agent 增删改与热注册）、`WorkspaceWatcher`（目录实时监听） |
| `oryxos-provider` | 能力一「对接 LLM」：`ProviderService`、Function Calling 适配、provider name 显式映射 |
| `oryxos-memory` | 能力三「Memory」：`MemoryService` 三层门面、长期记忆三档后端（Markdown 默认 / SQLite / Mem0）、`MemoryTools` |
| `oryxos-tool` | 能力四「Tool」：15 个内置工具（文件读写编辑、内容与路径检索、Shell、HTTP、网页搜索与抓取、通知、交互、长期记忆）、`ToolRegistry`、`AnnotatedToolAdapter`、`McpClientService`、`SearchProvider` 两档实现、`Sandbox` 接口 + `WhitelistSandbox` |
| `oryxos-channel-cli` | CLI Channel：`CliChannel`、`oryxos chat` |
| `oryxos-web` | 能力五「Web Service」：27 个 REST 端点、7 个 `ApiController`、统一响应信封、管理台（6 页，Agent 详情与定时任务页可写）、OpenAPI |
| `oryxos-storage` | SQLite 持久化：Session、审计、记忆与通知渠道 Repository |
| `oryxos-cli` | `OryxOsCli` 主入口、15 个子命令、`ConfigLoader` |
| `oryxos-boot` | Spring Boot 启动模块、自动配置、依赖聚合；fat JAR `oryxos-boot/target/oryxos.jar`（主类 `OryxOsCli`） |

全量 14 个模块（另含 `oryxos-persona` / `oryxos-knowledge` / `oryxos-channel-feishu|wecom|dingtalk`）属扩展演进，核心阶段不建。

## 设计原则

- **底座优先于 Agent**：最重要的交付不是某个强大的 Agent，而是让任意 Agent 都能可靠运行的环境
- **自实现核心，可控优先**：核心推理循环自己实现；底层模型协议适配复用成熟库（Spring AI Alibaba，仅用协议转换 + `@Tool` schema 生成），不重复造轮子
- **配置即 Agent**：一个 Agent 由一份配置（一个目录）定义，而不是由代码写出
- **对接开放标准**：工具用 MCP、协作用 A2A、技能用开放格式，与生态协同，不另立协议
- **无状态实例，状态外置**：从单机平滑走向分布式的前提
- **安全是地基不是补丁**：工具来源受控、最小权限、强制沙箱、凭证不落地、全链路可审计，安全从第一天就在架构里
- **分阶段克制**：当前只做运行时内核的最小完备集，治理与重型分布式基础设施留到后续，每次架构升级都用真实使用数据证明其必要性

## 快速开始

> 运行时内核（第 16~26 节）已交付并全绿，以下命令**都已在真实构建上跑过**。全量验收 Demo 与跨节串联验收尚在进行中。

```bash
# 环境要求：JDK 21+，Maven 3.8+（仓库自带 ./mvnw）

# 构建（必须带 clean；只出旧字节码是 maven-jar-plugin 的已知坑）
./mvnw clean package -Ddependency-check.skip=true

# 初始化工作区（创建 .oryxos/ 目录，幂等；同时建表并做 schema 漂移补齐）
java -jar oryxos-boot/target/oryxos.jar init

# 创建一个 Agent（生成 .oryxos/agents/<name>/AGENT.md 最小模板）
java -jar oryxos-boot/target/oryxos.jar profile create my-assistant

# 交互式对话（需要模型凭证，见下）
export DEEPSEEK_API_KEY=sk-...
java -jar oryxos-boot/target/oryxos.jar chat

# 常驻模式：REST API + 管理台（Agent 与定时任务页可写）+ OpenAPI，同一个进程同一个端口
java -jar oryxos-boot/target/oryxos.jar serve
#   REST       http://localhost:8080/api/v1
#   管理台     http://localhost:8080/admin/
#   接口文档   http://localhost:8080/swagger-ui
```

### 一个完整的 Agent 目录长什么样

[`examples/agents/daily-reconcile/`](examples/agents/daily-reconcile/) 是一个可直接运行的完整 Agent 目录（**四部分俱全**），照着它写比看文档快：

| 部分 | 文件 | 作用 |
|------|------|------|
| 主文件 | `AGENT.md` | frontmatter = 运行配置（provider / tools / schedules），正文 = 任务指令，**常驻 system prompt** |
| 参考 | `REFERENCE.md` | 字段字典与判定口径，**不预载**，模型用 `read_file` 按需取 |
| 脚本 | `scripts/reconcile.py` | 任意代码，经 `shell` 调解释器运行；**产出进上下文、源码不进** |
| Skill | `skills/report-format -> ../../../skills/report-format` | 相对软连接，指向公共 Skill 实体；只注入 name / description / 路径 |

复制进工作区即可（`-a` 保留软连接，别用 `-r` 跟随）：

```bash
cp -a examples/skills/report-format   .oryxos/skills/
cp -a examples/agents/daily-reconcile .oryxos/agents/
java -jar oryxos-boot/target/oryxos.jar profile list   # daily-reconcile 应出现在列表里
```

REST 端点共 **27 个**，全部落在 `/api/v1` 下，统一响应信封 `{code, message, data, timestamp}`：

| 类别 | 端点 |
|------|------|
| 会话管理 | `POST /api/v1/sessions`（创建或取回）、`GET /api/v1/sessions`（最近 100 条摘要，可按 `status` 过滤）、`POST /api/v1/sessions/{id}/messages`（发消息）、`GET /api/v1/sessions/{id}`（查历史）、`DELETE /api/v1/sessions/{id}`（归档） |
| Agent 管理 | `POST /api/v1/agents`（新建）、`GET /api/v1/agents`（列表）、`GET /api/v1/agents/{name}`（详情）、`PUT /api/v1/agents/{name}`（覆写 `AGENT.md`，即时生效）、`DELETE /api/v1/agents/{name}`（归档）、`POST /api/v1/agents/{name}/generate-files`（一句话生成草稿）、`POST /api/v1/agents/{name}/files`（保存一组文件） |
| Agent 调用 | `POST /api/v1/agents/{name}/invoke`（无状态调用）、`GET /api/v1/agents/{name}/memory`（专属长期记忆）、`GET /api/v1/agents/{name}/session`（固定管理台会话）、`POST /api/v1/agents/{name}/session/messages`（向固定会话发消息） |
| 工作区 | `GET /api/v1/workspace/tree`（目录树）、`GET /api/v1/workspace/file?path=`（读文本）、`POST /api/v1/workspace/file`（写文本） |
| 信息查询 | `GET /api/v1/profiles`、`GET /api/v1/tools` |
| 定时任务 | `GET /api/v1/schedules`（列表与运行状态）、`GET /api/v1/schedules/{id}/executions`（执行历史）、`POST /api/v1/schedules/{id}/run`（立即执行）、`PUT /api/v1/schedules/{id}`（启用 / 停用） |
| 系统状态 | `GET /api/v1/health`、`GET /api/v1/info` |

其中 `GET /api/v1/sessions` 是管理台「会话」页的数据源：列表只返回摘要（不含对话正文），点开某一行才去读 `GET /api/v1/sessions/{id}` 拿最近 100 条历史。

定时任务的定义源仍是 `AGENT.md` 的 `schedules`（cron / 时区 / 消息），`scheduled_tasks` / `task_executions` 两张表只存运行状态与执行历史（跨重启保留）；管理台「定时任务」页是第一个带写操作的页面——只开「立即执行」与「启用 / 停用」两个动作，且只能打 `/api/v1/schedules/**`。

也可以用仓库里的后台启动脚本——它把凭证从 YAML 配置文件读出来（`bin/config.yaml` 不入库，模板是 `bin/config.yaml.example`）：

```bash
cp bin/config.yaml.example bin/config.yaml   # 填入 deepseek.api-key
bin/start.sh                                 # 后台启动（含 init 与健康检查）
bin/stop.sh                                  # 停止
```

### 不用任何模型凭证跑通全链路

没有 API key 也能把「对话 → 工具 → 记忆 → 会话 → 审计」整条链路走一遍：底座内置了一个 `mock` Provider（随包声明在 `application.yaml` 里，是唯一不要求 `api-key` 的厂商），它按固定脚本回一轮工具请求、再回最终答复。

```bash
# 初始化工作区（建表；不做这步 chat 会报 no such table: sessions）
java -jar oryxos-boot/target/oryxos.jar init
mkdir -p .oryxos/agents/mock-demo
cat > .oryxos/agents/mock-demo/AGENT.md <<'EOF'
---
description: 无凭证全链路自测
identity:
  agent_name: 小助手
  prompt: 你是 OryxOS 的全链路自测助手。
provider: { name: mock, model: mock-model }
tools: [save_memory]
---

按脚本把用户说的话记进长期记忆。
EOF

java -jar oryxos-boot/target/oryxos.jar chat --profile mock-demo
# 输入：记住：我喜欢喝美式咖啡
# 答复：好的，我记住了。

cat .oryxos/agents/mock-demo/MEMORY.md  # 事实真的写进了这个 Agent 的长期记忆（第 30 节起按 Agent 隔离）
java -jar oryxos-boot/target/oryxos.jar serve
# 打开 http://localhost:8080/admin/ ：概览里有会话数与工具数，「会话」页能看到这一条
```

`mock` 是**接线自测**用的固定脚本（第一轮必请求一次工具、第二轮给最终答复），不是真实模型——真实对话请把 `provider` 换成 `deepseek` 并配上凭证。

定义一个每日天气 Agent，只需要在 `.oryxos/agents/daily-weather/AGENT.md` 里写：

```markdown
---
name: daily-weather
provider: { name: deepseek, model: deepseek-chat }
tools: [http_get, notify]
schedules: [{ cron: "0 0 8 * * *", message: "查一下今天天气并生成穿搭建议" }]
---

每天早上自动查天气、生成穿搭建议，通过 notify 推送到团队群（渠道名 team-lark）。
```

不写一行代码，到点自动运行，每次调用都有审计记录。

## 项目状态

**当前阶段：运行时内核已跑通。** 五大核心能力（第 16~26 节）全部交付，动态 Agent 管理（第 30 节）接上，`mvn clean verify` 全绿（**723** 条测试）；目前在做跨节串联验收与三个验收 Demo。

- [x] 业界调研 / 需求文档 / 技术方案 / AI 编程指南
- [x] Spec-Kit 开发工作流搭建
- [x] Maven 9 模块工程骨架（单可执行 fat JAR + 15 个 CLI 子命令）
- [x] 能力一：对接 LLM（Provider 抽象 + provider name 显式映射）
- [x] 能力二：自实现 ReAct 循环 + 统一处理入口 `AgentService`
- [x] CLI 与三种运行模式（`chat` / `serve` / `gateway`）、会话持久化与审计落库
- [x] 通知出站（通用 webhook + 飞书 / Lark / 企业微信方言 + 按 `type` 路由）
- [x] 能力四：Tool 体系（15 个内置工具 + MCP 客户端 + 三档扩展）
- [x] 能力三：Memory（门面 + Markdown / SQLite / Mem0 三档长期记忆后端）
- [x] Sandbox 白名单（文件路径 / Shell 命令 / HTTP 域名）
- [x] 定时任务（第三触发源，与 CLI / Web 共用同一条链路）
- [x] 能力五：Web Service（27 个 REST 端点）+ 管理台（6 页，Agent 详情与定时任务页可写）
- [x] 跨节串联验收（第 27 节）
- [x] 定时任务子系统（状态与历史落 SQLite、四个管理端点、跨重启恢复、多 Agent 隔离）
- [ ] 三个验收 Demo：每日天气 / 每日科技日报 / 每日 GitHub 日报
- [ ] OryxOS 1.0 发布 + 项目主页

## 路线图

我们的开发理念是：**慢就是快，克制且聚焦**。先把单机的运行时内核做扎实，再逐步生长出分布式能力。

- **阶段一（当前）单机运行时内核**：五大核心能力已跑通——配置即 Agent、多 Agent 并存、REST API 接入、对接 MCP、管理台；人推主干（第 27 节）与钟推 / 重启恢复 / 多 Agent 隔离（第 28 节）的全流程串联均已用集成测试固定，剩余工作是跑通三个验收 Demo（第 31 节）
- **阶段二（规划）底座分布式**：节点无状态化、状态外置、多副本部署，支撑更大规模与高可用
- **阶段三（愿景）跨节点 Agent 协作**：引入 Agent 通信底座，对接 A2A，跨节点发现、委托、可靠异步协同
- **横向能力**（伴随各阶段补齐）：多租户、SSO、完整审计、工具策略、可观测、**可写**的管理台（当前交付的第一版管理台是只读的，渠道管理等写入口留到扩展阶段）

**长期目标：走进 Apache 基金会，成为 Apache 顶级项目。**

## 文档

| 文档 | 内容 |
|------|------|
| [官网](https://wngbob.github.io/oryxos-wngbob/) | 项目官网（VitePress，中英文双语，源码在 `website/`） |
| [业界调研](docs/IndustryResearch.md) | Agent OS 是什么、业界格局、Java 生态缺位、OryxOS 定位 |
| [需求文档](docs/DemandAnalysis.md) | 五大核心能力、三档需求、验收标准 |
| [技术方案](docs/TechnicalSolution.md) | 技术栈、关键技术决策、模块结构、数据模型 |
| [AI 编程指南](docs/AiProgrammingGuide.md) | Spec-Kit 开发流程、user story 拆解 |
| [逐节开发记录](specs/) | 每节 `spec.md` / `plan.md` / `tasks.md` / `acceptance-report.md`（交付证据与已知边界） |
| [项目宣言](docs/oryxos.md) | OryxOS 的愿景与完整叙事 |
| [CLAUDE.md](CLAUDE.md) | AI agent 在本仓库工作时的必读上下文（架构原则与红线） |

## 参与贡献

OryxOS 由 [oryx-labs](docs/oryx-labs.md) 社区孵化——一个 AI coding 驱动的 AI 探索社区。

- 主体开发采用 **Spec-Driven Development**（Spec-Kit）：`specify → plan → tasks → implement`，核心原则见 [CLAUDE.md](CLAUDE.md) 与 `.specify/memory/constitution.md`
- 增量贡献（修 bug、加 Plugin Tool、补文档）：直接在已有代码上改，提 PR 即可
- 贡献前请先读 [CLAUDE.md](CLAUDE.md) 的「不可违背的原则」一节——违反红线的代码必须返工

## 致谢

OryxOS 借鉴了开源社区已被验证的设计：[OpenClaw](https://github.com/openclaw/openclaw) 与 [Hermes Agent](https://github.com/NousResearch/hermes-agent) 的 Agent OS 形态、[Anthropic Agent Skills](https://agentskills.io) 的目录 + 渐进式披露、[Model Context Protocol](https://modelcontextprotocol.io) 的工具协议、[Spring AI Alibaba](https://java2ai.com) 的 LLM connector。

## License

[Apache License 2.0](LICENSE)
