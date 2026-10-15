# Implementation Plan: Tool 体系 —— 给 Agent 装上能真正动手的手

**Branch**: `class-20` | **Date**: 2026-10-14 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/005-tool-registry/spec.md`

**Note**: 本文件由 `/speckit-plan` 命令填写；其定义描述了执行工作流。Phase 0 / Phase 1 产物见 [research.md](research.md)、[data-model.md](data-model.md)、[contracts/](contracts/)、[quickstart.md](quickstart.md)。

## Summary

本节给 Agent 装上"手"：LLM 决定调哪个工具、传什么参数（第 16 节 Function Calling），OryxOS 把工具真正执行掉、结果递回 LLM（第 17 节 ReAct 的 "Act" 步）。**技术路径是把第 17 节在 core 留的 `ToolCatalog` 接缝、第 17 节的 `ToolExecutor` + `tool_invocations` 审计、第 18 节装配处的空实现让位机制三处伏笔一次接上**——新增一个 `ToolRegistry` Bean，`oryxos-core` 一行不改，工具就自动出现在 prompt 里、可被调度、成败都落审计。

三源工具（内置 / `@Tool` 注解 Bean / 外部 MCP server）全部收敛到骨架期已交付的 `OryxTool` 抽象下，ReAct 循环不感知来源（章程原则 V）。与前 19 节最大的不同：**本节第一次真正装配**——第 19 节"零装配"的根因（没有可注入的沙箱 Bean）被本节的**临时沙箱实现**解开（仲裁 1，已报批，推翻第 19 节裁决 4），因此本节交付的运行时**首次可演示**：`oryxos tool list` 看得见工具、一次 `chat` 里模型点名工具真的会被执行。

## Technical Context

**Language/Version**: JDK 21（`maven.compiler.release=21`）

**Primary Dependencies**: Spring Boot 3.5.16；Spring AI Alibaba / Spring AI `spring-ai-model` 1.1.8（**只用一半**：仅 `@Tool` / `@ToolParam` 注解 + `JsonSchemaGenerator` 的 schema 生成；自动 tool 执行在 `ProviderService` 已被 `internalToolExecutionEnabled(false)` 关闭，章程原则 III）；SnakeYAML 2.4（经 `oryxos-core` 传递）；Jackson `jackson-databind` 2.21.4（经 `spring-ai-model` → victools 传递，**本节不新增**）；**新增** MCP Java SDK `io.modelcontextprotocol.sdk:mcp-core:0.18.3` + `mcp-json-jackson2:0.18.3`（见 §需要说明的设计偏离 D3）

**Storage**: 无新增。`tool_invocations` 是第 17 节既有表（`oryxos-storage`），本节**只写不建**、不新增列、不新增 Repository 方法

**Testing**: JUnit 5 + Mockito + AssertJ（`spring-boot-starter-test`）；`mockwebserver3:5.5.0`（已在 `oryxos-tool` 的 test scope，第 19 节引入）用于 `HttpTools` 的正常路径；logback `ListAppender` 用于断言 WARN 日志（`PermissiveSandbox` 的启动警告、MCP 失联的 WARN）

**Target Platform**: 单二进制 `java -jar oryxos-boot/target/oryxos.jar`（主类 `OryxOsCli`）；Linux / WSL2 开发，核心阶段单机部署

**Project Type**: Maven 多模块单体（核心阶段 9 个模块）；本节落在 `oryxos-tool`（实现）+ `oryxos-cli`（装配 + `tool list`），`oryxos-core` 零改动

**Performance Goals**: 工具执行是同步阻塞 + 虚拟线程（章程原则 I / VIII）；MCP 连接与列举**有界等待 10 秒 / server**（FR-009），命令工具**超时上限 30 秒**（FR-020）；`grep` / `glob` **返回上限 200 条**（FR-013）。三处数值与口径见 [spec.md](spec.md) 的 Assumptions

**Constraints**: 单测全程不访问外网（SC-008）；凭证只走 `${ENV_VAR}` 占位、不落明文、不进日志（FR-027）；MUST NOT 引入响应式编程 / 自建线程池 / 异步工具调用（FR-026）；避开 P3C/ASM 解析不了的 Java 18+ 语法形态（增强 switch 的 `default ->`），静态检查是构建门禁

**Scale/Scope**: 本节新增约 20 个生产类（`oryxos-tool` 主）+ 约 12 个测试类；注册表默认装载 **13 个工具**（**6 个本节新增**：文件 3 + 命令 1 + HTTP 2；**6 个扩展**：`edit_file` / `grep` / `glob` / `ask_user` / `web_search` / `web_fetch`；**1 个**第 19 节已交付、本节注册的 `notify`）+ **0 个 MCP 工具**（默认配置未声明任何 server）+ **0 个 Memory 工具**（归第 22 节，**本节 MUST NOT** 造占位）。第 22 节补齐后为 15 个。逐条清单见 [contracts/builtin-tools.md](contracts/builtin-tools.md) §1（`web_fetch` 为 2026-10-15 交付后补强新增，见 [acceptance-report.md](acceptance-report.md) 的「交付后补强」一节）

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| # | 章程条款 | 本节符合性 | 说明 |
| --- | --- | --- | --- |
| I | JDK 21 + Spring Boot 3.x 单体、单二进制 | ✅ | 无新增模块、无新进程依赖；同步阻塞 + 虚拟线程（FR-026 明令禁止响应式 / 线程池 / 异步） |
| II | 核心阶段只交付运行时内核；新依赖须给出理由 | ⚠️ **有正当理由，见 D3** | Plugin Tool 是五大核心能力之一，MCP 是其"方式二"的落地载体；MCP SDK 的引入理由与发行物代价逐条记在 §需要说明的设计偏离 D3 与 [research.md](research.md) D3 |
| III | ReAct 自实现；Spring AI 只用一半；自动 tool 执行 MUST 禁用 | ✅ | 本节把 Spring AI 的用途**收得更紧**：只用 `@Tool` / `@ToolParam` 注解与 `JsonSchemaGenerator` 生成 schema，**方法调用由 `AnnotatedToolAdapter` 自己做**（见 D2）；`ProviderService.java:125` 的 `internalToolExecutionEnabled(false)` 一行不改 |
| IV | Agent 目录不是 Tool；`AGENT.md` MUST NOT 进 `ToolRegistry` | ✅ | `ToolRegistry` 只收 `OryxTool`；`AGENT.md` 的加载仍在 `ContextLoader`（第 17 节），本节一行不碰。H4 不变量自查项 |
| V | Tool 体系统一抽象、单一模块；三档接入的能力选择顺序 | ✅ | 三源全部包成 `OryxTool` 进同一注册表；全部落 `oryxos-tool`，MUST NOT 拆模块；[contracts/builtin-tools.md](contracts/builtin-tools.md) 明确"方式一优先于方式二优先于方式三"的判定口径 |
| VI | 审计 Day One 落库；Sandbox 校验失败复用既有失败审计路径 | ✅ | **本节一行审计代码都不写**：`ToolExecutor` 是唯一落点，`SandboxViolationException` 继承 `RuntimeException` 被它既有 catch 兜住（FR-021 / SC-006） |
| VII | 接口先行，实现按档演进；核心阶段沙箱 = `Sandbox` + `WhitelistSandbox` | ⚠️ **已报批的有意偏离，见 D1** | 本节交付的**临时沙箱**让白名单校验在第 20~23 节之间**不生效**。这是用户 2026-10-14 的裁决（仲裁 1，推翻第 19 节裁决 4），四条缓解措施已落地；`WhitelistSandbox` 仍归第 24 节 |
| VIII | SQLite + JPA；状态外置；凭证不落明文 | ✅ | 无新增表 / 列；`PermissiveSandbox` / `ToolRegistry` 都不持跨调用可变状态（实例可重建）；MCP 凭证走 `${ENV_VAR}` 占位 + 环境变量解析（FR-027） |
| IX | 三触发源复用同一条链路 | ✅ | 本节只改 `ToolExecutor` **上游**的供给（谁提供工具），`AgentService.process` → `ReActLoop` → `ToolExecutor` 链路一行不改，三个触发源自动同享 |
| X | 每个 user story 有可演示 Demo | ✅ | **本节首次真正可演示**（与第 19 节相反）：`oryxos tool list` 列出全部已注册工具；`chat` 里模型点名内置工具被真正执行并回填。演示步骤见 [quickstart.md](quickstart.md) |

**GATE 结论**：两条 ⚠️（II 的新依赖、VII 的临时沙箱）**均已在 spec 阶段报批并留有缓解措施**，不是无正当理由的复杂性。详见下节与 `## Complexity Tracking`。

## 需要说明的设计偏离（逐条已论证或已报批）

### D1 【已报批，推翻第 19 节裁决 4】装配解冻 + 临时沙箱 `PermissiveSandbox`

第 19 节裁决"零装配 + MUST NOT 造全放行占位沙箱"被用户 2026-10-14 **有意推翻**（spec 仲裁 1）。理由：第 19 节不装配的根因是"没有可注入的沙箱 Bean"，而课件「本节交付物」明写 `Sandbox` 前向接口五件**含临时装配实现**、且明写「`NotifyTools`（19 节）在此完成注册」——根因在本节解开。不装配则本节"Agent 真能调工具"无从演示，与章程原则 X 冲突。

**落地**：`oryxos-tool` 的 `sandbox` 包新增 `PermissiveSandbox`（类名取自课件「本节交付物」原文），在 `CliAssemblyConfiguration` 里以 `@ConditionalOnMissingBean(Sandbox.class)` 声明为沙箱 Bean。

**连带**：第 19 节挂账的 `WebhookNotifyAdapter` / `JpaNotifyChannelRegistry` / `NotifyTools` 三条 Bean 声明**从第 24 节提前到本节**（第 19 节已交付这三件库代码，只差 Bean 声明）。

**注意**：**不需要** `RestClient` Bean——已核实 `WebhookNotifyAdapter` 有公开无参构造（`WebhookNotifyAdapter.java:38`），内部自建带 10 秒有界超时的 `RestClient`（第 19 节 javadoc 明确把超时收口在类内）。声明一个 `RestClient` Bean 会让人误以为它可以被注入替换，与第 19 节的收口意图相反。完整 Bean 清单见 [contracts/assembly.md](contracts/assembly.md) §1。

**残余风险如实记录**：第 20~23 节之间白名单校验**不生效**，即本节交付的运行时**没有 Tool 治理**。四条缓解（逐条可判定，见 [contracts/assembly.md](contracts/assembly.md) §4）：

1. 类名 `PermissiveSandbox` + javadoc 明示「临时装配、第 24 节必须替换」；
2. **构造时打一条 WARN 日志**（写进类里而不是装配方法里——任何构造路径都警告，且可用 logback `ListAppender` 单测断言，对应 SC-010 的"启动日志可见次数 ≥ 1"）；
3. 第 24 节的 `WhitelistSandbox` 作为同类型 Bean 声明后**自动让位**，本节交付的工具代码与调用方**改动行数为 0**（SC-009）；
4. `CLAUDE.md` 与本节验收报告均记明该窗口期。

**本节 MUST NOT** 把这个窗口期说成"沙箱已就位"；`PermissiveSandbox` MUST NOT 出现在任何"本节交付物"的成果清单里被当作安全能力。

### D2 【已报批】`OryxTool` 保留 String 口径；Spring AI 只做 schema，调用自己做

`OryxTool` / `ToolResult` 的骨架期签名（`String getInputSchema()` / `ToolResult execute(String inputJson)`）**一行不改**（spec 仲裁 2，属软门禁 ④ 的报批项）。课件示意的 `JsonSchema getInputSchema()` / `execute(JsonNode)` 记为**已知偏差**。

转换成本收在**一处**：`AnnotatedToolAdapter`。它的分工是——

| 职责 | 由谁做 | 依据 |
| --- | --- | --- |
| 工具名 / 用途说明 / 入参 schema | Spring AI：`ToolUtils.getToolName(Method)` / `@Tool.description()` / `JsonSchemaGenerator.generateForMethodInput(Method)` | 章程原则 III 允许的"`@Tool` 注解的 JSON Schema 生成" |
| JSON 入参 → 方法参数 → 调用方法 | `AnnotatedToolAdapter` 自己做（Spring AI 的 `JsonParser` 做解析与逐参数转换） | **不用 Spring AI 的 `MethodToolCallback.call()`**：它把返回值字符串化，`ToolResult` 的四要素（尤其 `retryable`）会被抹平，而第 19 节已交付的 `NotifyTools.notify` 就返回 `ToolResult`（改它属软门禁 ④） |

**连带**：`@Tool` 方法返回 `ToolResult` 时原样返回；返回其他类型时包成 `ToolResult.success(...)`；抛异常时**上抛**，交给 `ToolExecutor` 既有的失败审计路径（不在这里 catch 成失败结果，否则失败在审计表里消失）。

**MUST**：父 pom 的 `maven-compiler-plugin` 打开 `-parameters`（当前**未开**）。不开则 `Parameter.getName()` 返回 `arg0`，`@Tool` 生成的 schema 里参数名会变成 `arg0`——模型看得见的接口当场坏掉，而编译器不会报任何错。验证方式：一条断言 `notify` 的 schema 里出现 `content` / `channel` 的单测（见 [contracts/builtin-tools.md](contracts/builtin-tools.md) §3）。

### D3 【已报批】MCP 依赖显式声明两个坐标，按真实 SDK API 落地

课件用的是示意 API（`McpClient.connect(cfg)` / `client.callTool(name, input)`）。实测（`javap`，见 [research.md](research.md) D3 的逐条证据）：

- 目标坐标在本地仓库存在，但**不在本项目依赖树上、也不由任何 BOM 托管** → MUST 显式声明并写死版本号；
- **只声明 `mcp-core` 会让启动直接失败**：核心模块不带 mapper 实现、也不带 ServiceLoader 注册文件，传输层构造器的唯一重载要求传入 mapper，客户端构建在未显式给校验器时会主动去 ServiceLoader 找，找不到**抛 `ServiceConfigurationError`（不是降级）**。故 `mcp-core` + `mcp-json-jackson2` **两件一并声明**（均 0.18.3）。

**发行物代价如实记录**：fat JAR 新增**五个**工件——`mcp-core`、`mcp-json-jackson2`、`json-schema-validator`、`com.ethlo.time:itu`（注意 groupId 不是 `com.networknt`）、`jackson-dataformat-yaml`。五件**均已核实存在于本地仓库**，离线构建不受影响。**响应式依赖边不新增**（`reactor-core` 自第 16 节就在 classpath 上）；FR-026 的判定口径是**本项目源码**，第三方库内部用响应式不构成违反（H4 不变量 ⑤ 的扫描只覆盖本仓源码）。

### D4 【已报批】注册表实现 core 的既有接缝，`oryxos-core` 一行不改

`ToolRegistry` 实现 `ToolCatalog.find(String)` / `availableFor(Profile)`（spec 仲裁 5）。装配处既有的 `EmptyToolCatalog`（`@ConditionalOnMissingBean(ToolCatalog.class)`）**自动让位**，不回头删代码。

**实现注意项（必读，写错不会报错只会静默失效）**：`@ConditionalOnMissingBean` 在同一个 `@Configuration` 类里**按 `@Bean` 方法的声明顺序求值**。若 `toolCatalog()`（空实现）声明在 `toolRegistry()` **之前**，条件会先看到空实现、判定"已有同类型 Bean"，于是空实现被注册、真实实现也被注册 → 容器里出现**两个** `ToolCatalog` Bean → 注入 `PromptBuilder` / `ToolExecutor` 时抛 `NoUniqueBeanDefinitionException`。

**落地**：`toolRegistry()` 的 `@Bean` 方法 MUST 声明在 `toolCatalog()` **之前**。**验证方式（机器判定，不留给人）**：一条 Spring 上下文测试，断言容器里 `ToolCatalog` 类型的 Bean **恰好一个**且其运行时类型是 `ToolRegistry`。

### D5 【已报批】`oryxos tool list` 改重命令（第 18 节挂账的兑现）

第 18 节交付 `ToolListCommand` 时留下**已报批的实施期偏离**（`specs/003-cli-session/tasks.md` T033 注）：该命令**未接接缝**、只打空表 + 注明"第 20 节接入 ToolRegistry"，并明确写道「**第 20 节需要改本命令，不是"只换实现"**」。`contracts/cli-surface.md` §2.10 冻结的输出口径是"name、description、**来源**（`builtin` / `annotation` / `mcp:<server>`）"。

**问题**：`tool list` 在 §1 的命令表里被归为**轻命令**（不启动 Spring 上下文），而注册表是 `CliAssemblyConfiguration` 里的 Bean；且"来源"信息 `OryxTool` 接口表达不出来。轻命令**够不着**真实数据。

**裁决**：`tool list` **改重命令**（与 `chat` / `serve` / `gateway` 同列）。依据是 §1 自己的判定标准——"这条命令要不要调模型、跑引擎？"；列出 MCP 工具**必须连接 server**（MCP 工具的清单只有连上才知道），这已经是"跑引擎"。第 18 节把它归轻，是因为当时**没有注册表可查**（数据来源列原文即"`ToolCatalog` 接缝（本节空实现）"）。

**代价如实记录**：① `tool list` 从"秒回"变成"起上下文"，并在有 MCP server 声明时最多等待 `10 秒 × server 数`；② `contracts/cli-surface.md` §1 第 11 行的"轻重"列由**轻**改为**重**（命令名、参数、退出码**不变**，§1 要求走修订程序的三项均未触碰）。

**本节 MUST NOT** 为保持"轻"而另造一个静态工具清单——那是第二处真相，加一个内置工具就要手改两处。

### D6 其余取自课件正文、但课件「本节交付物」清单未列出的名字（软门禁 ① 报备）

| 名字 | 出处 | 为什么必须存在 |
| --- | --- | --- |
| `PermissiveSandbox` | 课件「本节交付物」原文 | 仲裁 1 的落地物 |
| `ConsoleUserInteraction` / `UnsupportedUserInteraction` | 课件 §6.3 正文 | FR-017 要求的两档交互实现 |
| `DuckDuckGoSearchProvider` | 课件 §6.4 正文 | FR-018 要求的一档免 key 搜索实现 |
| `McpServerConfig` | 课件 §三代码（`loadConfigs()` 的返回元素） | 读 `.oryxos/mcp_servers.yaml` 的载体 |
| `ToolRegistry.RegisteredTool`（嵌套 record） | **本节新增** | `contracts/cli-surface.md` §2.10 冻结的"按来源分组"输出需要"工具 + 来源"配对；`OryxTool` 接口不带来源（其 javadoc 亦未承诺）。**嵌套在注册表内部**，把新增的 public 类型压到 1 个，且明确是注册表的伴随物 |

**报备理由**：前三行是抄课件正文而非自创；后两行是**兑现前序节已冻结的契约**所必需。此表与 spec 的「冲突仲裁记录」第 6 条同源，一并进第 5 步固定停点的确认清单。

### D7 课件与本节的其他已知偏差

| # | 课件写法 | 本节口径 | 依据 |
| --- | --- | --- | --- |
| 1 | `McpClientService` 带 `@Component` + `@PostConstruct` | 不带装配注解；`connectAll()` 由 `CliAssemblyConfiguration` 的 `@Bean` 方法显式调用 | 第 18 节既定装配纪律（非 boot 模块不带装配注解，Bean 声明只此一处） |
| 2 | `ToolRegistry` 是独立类、不实现任何 core 接口 | 实现 core 的 `ToolCatalog` | spec 仲裁 5；core 零改动 |
| 3 | 九个内置 Tool 一次交付 | 本节交付七件 + 扩展五件；Memory 两件归第 22 节 | spec 仲裁 9；技术方案 §6.2 明写 Memory 组"归 Memory 模块" |
| 4 | `Profile` 里 `skills` 引用 Skill（方式一） | 本节不碰 Skill 绑定 | spec 仲裁 8；`ProfileLoader` 既有禁令 + 章程原则 IV |
| 5 | MCP 用示意 API | 按真实 SDK API | D3 |
| 6 | `@Tool` 方法返回值形状不统一（`String` / `ToolResult`） | 两形状都支持，`ToolResult` 优先 | 第 19 节 `NotifyTools.notify` 已定 `ToolResult`，改它属软门禁 ④ |

## Project Structure

### Documentation (this feature)

```text
specs/005-tool-registry/
├── plan.md              # 本文件
├── research.md          # Phase 0 产物（技术决策与实测证据）
├── data-model.md        # Phase 1 产物（实体与关系）
├── quickstart.md        # Phase 1 产物（可执行验证指南）
├── contracts/           # Phase 1 产物（对外/对内契约）
│   ├── tool-abstraction.md   # 统一抽象 + 注册表 + 注解管道
│   ├── builtin-tools.md      # 13 个内置/扩展工具的 name / 参数 / 白名单动作
│   ├── mcp-integration.md    # 配置读取 + 连接 + 失联隔离 + 依赖坐标
│   └── assembly.md           # Bean 清单、声明顺序、让位机制、`tool list` 改法
└── tasks.md             # Phase 2 产物（/speckit-tasks，本命令不创建）
```

### Source Code (repository root)

```text
oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/
├── registry/
│   ├── ToolRegistry.java            # 新增：三源汇聚 + 按 Profile 过滤 + 来源分组（实现 core 的 ToolCatalog）
│   └── AnnotatedToolAdapter.java    # 新增：@Tool 方法 → OryxTool（schema 走 Spring AI，调用自己做）
├── builtin/
│   ├── FileTools.java               # 新增：read_file / write_file / list_dir / edit_file / grep / glob
│   ├── ShellTools.java              # 新增：shell
│   └── HttpTools.java               # 新增：http_get / http_post
├── interaction/
│   ├── UserInteraction.java         # 新增：交互接缝（FR-017）
│   ├── ConsoleUserInteraction.java  # 新增：读终端一行
│   ├── UnsupportedUserInteraction.java # 新增：无人值守渠道明确报"不支持交互"
│   └── InteractionTools.java        # 新增：ask_user
├── search/
│   ├── SearchProvider.java          # 新增：搜索接缝（FR-018）
│   ├── DuckDuckGoSearchProvider.java# 新增：免 key Instant Answer
│   └── WebSearchTools.java          # 新增：web_search
├── mcp/
│   ├── McpServerConfig.java         # 新增：一条 MCP server 声明
│   ├── McpServerConfigLoader.java   # 新增：读 .oryxos/mcp_servers.yaml（SnakeYAML）
│   ├── McpClientService.java        # 新增：连接 + 拉清单 + 包装注册 + 失联隔离 + 有界等待
│   └── McpToolAdapter.java          # 新增：MCP tool → OryxTool
├── sandbox/
│   └── PermissiveSandbox.java       # 新增（第五件）：临时装配实现，构造时 WARN
└── notify/                          # 第 19 节已交付，本节只加 Bean 声明，代码不改

oryxos-cli/src/main/java/io/oryxlabs/oryxos/cli/
├── CliAssemblyConfiguration.java    # 修改：新增 Bean 声明（顺序敏感，见 D4）
└── command/
    └── ToolListCommand.java         # 修改：轻 → 重，接 ToolRegistry，按来源分组（D5）

oryxos-core/                         # 零改动（本节的核心卖点）
pom.xml                              # 修改：maven-compiler-plugin 打开 -parameters（D2）
oryxos-tool/pom.xml                  # 修改：新增 MCP 两个坐标
```

**Structure Decision**：沿用第 18 节确立的"契约在 core、实现在能力模块、装配集中在 `CliAssemblyConfiguration`"三层结构。本节全部生产代码落 `oryxos-tool`（章程原则 V：内置 Tool / MCP Client / ToolRegistry / Sandbox 合并为单模块），唯一例外是装配与 CLI 命令面，落 `oryxos-cli`。包结构按**能力族**分组（`builtin` / `interaction` / `search` / `mcp` / `registry` / `sandbox`）而不是按"来源"分组——来源是注册表的属性，不是包的划分依据。

## Complexity Tracking

> 本节 Constitution Check 有两处 ⚠️，逐条给出理由与被否方案。

| 违反项 | 为什么需要 | 更简单的方案为何被否 |
| --- | --- | --- |
| **原则 VII**：核心阶段沙箱 = `Sandbox` + `WhitelistSandbox`，而本节挂 `PermissiveSandbox`（全放行） | 第 19 节不装配的根因是"没有可注入的沙箱 Bean"；不装配则本节"Agent 真能调工具"无从演示（违反原则 X），且课件明写「`NotifyTools` 在此完成注册」。用户 2026-10-14 已报批（spec 仲裁 1） | ① 第 24 节才做 `WhitelistSandbox`，中间三节无工具可用 → 交付物残缺；② 把 `WhitelistSandbox` 提前到本节 → 跨节抢工，第 24 节只剩空壳，且白名单配置属性类（第 24 节交付物）尚未存在，接口定不下来 |
| **原则 II 附加约束**：引入新依赖须对照原则 II 给出理由 | Plugin Tool 是五大核心能力之一，"方式二（MCP）"是其主推落地路径；`McpClientService` / `McpToolAdapter` 是课件「本节交付物」点名的必交项，且第 31 节日报 Agent 硬依赖它 | ① 自写 MCP 协议客户端 → 违反章程附加约束"开放标准优先，MUST NOT 另立协议"的精神，且要自持 JSON-RPC + 传输层；② 只加 `mcp-core` → **实测启动直接抛 `ServiceConfigurationError`**（D3 证据②③） |
| **发行物体积**：fat JAR 新增 5 个工件 | 同上 | 同上 |

## 已知接缝（留给后续节）

| 留白 | 由谁补 | 本节已就位的部分 |
| --- | --- | --- |
| `WhitelistSandbox` 白名单校验**本体** | 第 24 节 | `Sandbox` / `SandboxAction` / `ActionType` / `SandboxViolationException` 四件（骨架期已交付，本节不改）+ `PermissiveSandbox` 临时占位；所有工具的 `enforce` 调用位已按最终形态写好 |
| `save_memory` / `recall_memory` 两个内置工具 | 第 22 节（`oryxos-memory`） | 注册机制已就位——加一个带 `@Tool` 的 Bean + 装配处一行 `registerAnnotated`，**注册表与过滤逻辑零改动**（这本身就是 FR-022 的一次验证） |
| Skill 绑定（方式一的完整形态） | 第 29 节 | 方式一在本节只到"配置里声明 MCP server + 复用社区 server" |
| `/api/v1/tools` REST 端点 | 第 26 节 | `ToolRegistry.registered()` 已提供"全量 + 来源"，第 26 节直接消费 |
| 入站 `ChannelAdapter` 抽象 | 入站抽象落地的那一节 | 见 `specs/004-notify-outbound/tasks.md` T013 的 US3 场景 4 挂账；本节不碰入站侧 |
| MCP 进程生命周期治理（重启 / 健康检查 / 重连退避）、MCP 认证 | 扩展阶段 | 本节只做"连不上就 WARN 跳过"（FR-008） |
| `ToolListCommand` 的来源分组细化（如 `mcp:<server>` 的 server 名在注册表里怎么存） | 第 26 节的 REST 端点复用同一口径 | 本节定死 `source` 字符串的三档取值，见 [contracts/assembly.md](contracts/assembly.md) §5 |

## Constitution Check（Phase 1 设计后复核）

设计完成后按同一张表复核，结论**不变**：两条 ⚠️ 仍是已报批的两条，未因设计细化新增任何违反项。逐项复核要点：

- **原则 III（复核重点）**：Phase 1 设计明确 `AnnotatedToolAdapter` **不用** `MethodToolCallback.call()`，因此 Spring AI 在本节承担的角色比第 16 节**更窄**（只剩注解元数据 + schema 生成）。H4 不变量 ⑥（无 Spring AI 自动工具执行路径）复核命令见 [quickstart.md](quickstart.md) §4。
- **原则 V（复核重点）**：`contracts/builtin-tools.md` 逐条给出三档接入的选择口径，未引入第四档。
- **原则 VII（复核重点）**：`contracts/assembly.md` §4 把四条缓解措施写成**可判定**的形式（类名断言、WARN 日志断言、让位测试、`CLAUDE.md` + 验收报告双处留痕）。
- **原则 VIII**：`data-model.md` 明确本节**不新增任何表 / 列**；`PermissiveSandbox` 与 `ToolRegistry` 均无跨调用可变状态。
- **原则 II**：`Complexity Tracking` 三条逐条给出理由与被否方案。

## Phase 0 / Phase 1 产出

| 产物 | 内容要点 |
| --- | --- |
| [research.md](research.md) | D1~D10 十条技术决策，每条含 Decision / Rationale / Alternatives；**全部第三方 API 形状均以 `javap` 实测证据落地**（H3 写前核实），无一条凭记忆 |
| [data-model.md](data-model.md) | 8 个实体（工具 / 结果 / 注册表条目 / 注解方法映射 / MCP server 配置 / 交互接缝 / 搜索接缝 / 沙箱动作）+ 关系图 + 校验规则 + 明确"无新增表" |
| [contracts/tool-abstraction.md](contracts/tool-abstraction.md) | `OryxTool` / `ToolResult` / `ToolCatalog` 三件既有契约的**冻结声明** + `ToolRegistry` / `AnnotatedToolAdapter` 的新增 API 逐字签名 |
| [contracts/builtin-tools.md](contracts/builtin-tools.md) | 13 个工具的 name / 参数 / 返回 / 白名单动作 / 上限，逐条对齐课件与 spec 的 FR；含"校验先于 IO"的顺序不变量 |
| [contracts/mcp-integration.md](contracts/mcp-integration.md) | `mcp_servers.yaml` 的读取口径（键名冻结）、连接与有界等待、失联隔离、依赖坐标与发行物代价 |
| [contracts/assembly.md](contracts/assembly.md) | `@Bean` 清单与**声明顺序**、让位机制的验证方式、`PermissiveSandbox` 四条缓解、`tool list` 的改法与 `source` 三档取值 |
| [quickstart.md](quickstart.md) | 可复制的验证步骤：单测 / 全量门禁 / `tool list` 实跑 / `chat` 实跑 / H4 六条不变量自查命令 |

**下一步**：`/speckit-tasks`（第 5 步），随后是流程中唯一的固定停点——任务清单 ↔ 课件「本节交付物」自动比对。
