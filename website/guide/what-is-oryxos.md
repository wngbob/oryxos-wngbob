# 什么是 OryxOS

**OryxOS 是企业私有部署的 Agent 操作系统（Agent Harness OS）**——让每一家公司，都能用自然语言跑起来自己的 Agent。

装在企业自己的 K8s、虚拟机或物理机上，作为统一底座运行各类业务 Agent（运维助手、客服助手、HR 助手、销售助手、知识管理助手……），共享渠道接入、模型路由、工具调用、记忆系统、沙箱执行能力。**数据完全留在企业自己的基础设施，不锁任何云生态。**

我们的判断是：让 Agent 在生产环境可靠工作，瓶颈通常不在模型本身，而在 **Agent 的运行环境**。OryxOS 做的不是又一个 Agent，而是让一群 Agent 可靠运行和协同的底座本身。

**北极星公式**：

```
自然语言(md) + Memory + Tool + MCP(Connector) + Skill + 知识库 + Notify = 一个 Agent
```

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
- ☕ **Java 原生**：JDK 21 + Spring Boot 3.x，单可执行 JAR 部署，无缝复用企业现有 Java 运维工具链（Nacos、Sentinel、SkyWalking、Arthas、Prometheus + Grafana）
- 🔒 **私有可控**：数据不出企业，不绑任何云；面向严监管行业（银行、政府、电信、能源、医疗）的确定性选择
- 🛡️ **安全是地基不是补丁**：工具白名单沙箱、凭证不落地走环境变量/企业密钥体系、`tool_invocations` / `llm_calls` 审计表 Day One 落库
- 🧠 **自实现 ReAct 引擎**：核心推理循环自己实现，不套外部 Agent 框架，循环行为完全可控
- 🔌 **对接开放标准**：工具用 MCP、Agent 协作用 A2A、Agent 目录借 Anthropic Agent Skills 目录形态，与生态协同不另立协议
- 🧩 **三档工具扩展**：零代码 Agent 目录 + 复用 MCP（主推）→ 任何语言自写 MCP server → `@Tool` Java Bean，按门槛自由选择
- 💾 **跨对话记忆**：会话记忆 + 长期记忆两层（`MEMORY.md`），接口预留向量检索升级空间
- 🌐 **无状态可扩展**：实例无状态、状态外置，从架构第一天为分布式留好路

## 五大核心能力

| 能力 | 说明 |
|------|------|
| **对接 LLM** | Provider 抽象统一对接主流大模型（DeepSeek、通义、Kimi、智谱、混元、豆包、Anthropic、OpenAI…），Agent 不感知厂商，运行时切换无锁定，支持本地推理（Ollama、vLLM） |
| **ReAct 循环** | Agent 的推理引擎：LLM 思考 → 调工具 → 看结果 → 再决定，直到给出最终响应；自实现、行为完全可控 |
| **Memory 记忆** | 会话 + 长期两层记忆，跨对话记住用户偏好、项目背景、关键决策 |
| **Tool 工具体系** | 内置 **15 个**工具（文件读写编辑、Shell、HTTP、网页搜索与抓取、通知推送、交互提问、长期记忆），Plugin Tool 三档扩展，白名单沙箱强制隔离 |
| **Web Service** | 10 个 REST 端点统一封装全部能力，任何能发 HTTP 的语言都能接入；同一端口提供只读管理台与 OpenAPI 文档 |
