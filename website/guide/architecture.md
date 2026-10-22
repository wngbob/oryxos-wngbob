# 架构

![OryxOS 整体架构](/images/docs-architecture-light.svg)

## 架构要点

- **三触发源统一入口**：CLI / Web Service（人推）与 AgentScheduler（钟推）汇入同一个 `AgentService`，ReActLoop 不感知消息来源
- **引擎调度三能力**：`ReActLoop` 每轮经 `PromptBuilder` 组装上下文、经 `ProviderService` 调 LLM、经 `ToolExecutor`（先 Sandbox 白名单校验）执行工具，审计随调用落库
- **存储下沉**：Session、审计、长期记忆（SQLite 档）与通知渠道落 SQLite；Agent 目录、Bootstrap、`MEMORY.md` 落文件系统——可直接编辑、git 可跟踪
- **外部依赖全部在边界之外**：LLM API、外部 MCP server、企业 IM webhook，OryxOS 不绑定任何一家

## 技术栈

| 层 | 选型 | 说明 |
|----|------|------|
| 语言 | Java 21 | 企业 Java 生态原生，虚拟线程天然适配 Agent 长阻塞调用 |
| 框架 | Spring Boot 3.x | 单可执行 fat JAR 部署，复用企业运维工具链 |
| LLM 接入 | Spring AI Alibaba | **仅用协议转换 + `@Tool` schema 生成**；ReAct 循环自实现，不用其自动 tool 执行 |
| 存储 | SQLite（Spring Data JPA） | 零依赖内嵌库；显式 `SQLiteDialect` + `ddl-auto=none`，schema 演进靠迁移脚本 |
| CLI | Picocli | 命令行入口，可独立运行 |
| 配置 | SnakeYAML | `AGENT.md` frontmatter 解析 |

## 核心阶段 Maven 模块（9 个）

| 模块 | 职责 |
|------|------|
| `oryxos-core` | ReActLoop、AgentService、PromptBuilder、ToolExecutor、Agent 加载、AgentScheduler |
| `oryxos-provider` | Provider 抽象与 LLM 协议适配（复用 Spring AI Alibaba） |
| `oryxos-memory` | 会话记忆 + 长期记忆三档后端（Markdown / SQLite / Mem0） |
| `oryxos-tool` | 15 个内置工具、Plugin Tool 三档扩展、ToolRegistry、MCP 客户端、Sandbox |
| `oryxos-channel-cli` | CLI 交互渠道 |
| `oryxos-web` | 27 个 REST 端点、管理台（Agent 详情与定时任务页可写）、OpenAPI |
| `oryxos-storage` | SQLite 持久化（Session、审计、记忆、通知渠道） |
| `oryxos-cli` | Picocli 主入口（15 个子命令）与 `ConfigLoader` |
| `oryxos-boot` | Spring Boot 装配与依赖聚合，产出 fat JAR（`oryxos.jar`，主类 `OryxOsCli`） |

依赖方向按依赖倒置：`oryxos-boot` 聚合其余 8 个模块；`oryxos-cli` 依赖各能力模块；`oryxos-tool` / `oryxos-memory` 依赖 `oryxos-core` 实现其契约；`oryxos-core` **不反向依赖**任何能力模块。

## 安全与审计

- **白名单沙箱**：Tool 执行前先过 Sandbox 校验，白名单外的文件/Shell/HTTP 访问直接拒绝
- **凭证不落地**：LLM API Key 等敏感配置走 `${ENV_VAR}` 环境变量注入，不明文写入 `AGENT.md`
- **全链路审计**：`tool_invocations` / `llm_calls` 审计表从第一天落库，每次调用可查
