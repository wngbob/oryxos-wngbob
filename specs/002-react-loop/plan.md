# Implementation Plan: ReAct 循环 —— Agent 的「想—做—看」主循环

**Branch**: `class-17` | **Date**: 2026-10-13 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/002-react-loop/spec.md`（第 17 节课件：`docs/class/第17节：ReAct 原理解析、实现与代码讲解.md`）

**Note**: 本文件由 `/speckit-plan` 生成。本节是核心阶段 US-2 的第一节切片，上游为 `specs/001-llm-provider`（Provider）与 `specs/000-runtime-kernel`（契约与审计地基）。

## Summary

给 OryxOS 装上主循环：一层薄薄的调度（`ReActLoop`）反复做「组装 prompt → 调一次模型 → 若模型提出工具请求就执行、把结果回灌 → 再转一轮」，直到模型不再要求工具、或转满上限强制收尾。

循环自己**不调模型、不执行工具**——它只调度。调模型走第 16 节的 Provider，执行工具走本节唯一的执行落点 `ToolExecutor`。为守住章程「core MUST NOT 反向依赖任何能力模块」，本节在 core 侧定义**四个接缝接口**（`LlmClient` / `ToolCatalog` / `MemorySource` / `SessionService`），本节只定义与调用、不提供任何实现，五个测试类全部用替身。

随本节一并交付：四部分 prompt 的组装与历史截断（`PromptBuilder`）、不缓存的上下文加载（`ContextLoader`）、三触发源共用的唯一入口与逐请求 Agent 上下文（`AgentService` + `ProfileContext`）、以及内存态的会话领域类型（`Session` + `SessionMessage`）。

## Technical Context

**Language/Version**: Java 21（`maven.compiler.release=21`）

**Primary Dependencies**:
- Spring Boot 3.5.16（`spring-boot-starter`；`oryxos-core` 已有）
- Spring AI 1.1.8：`spring-ai-model`（`Prompt` / `SystemMessage` / `AssistantMessage` / `ToolResponseMessage` / `ChatResponse` / `ToolCallback`）——**本节新增到 `oryxos-core`**，理由见 §「需要说明的三处偏离」①
- SnakeYAML（`oryxos-core` 已有，`ContextLoader` 复用其读取 frontmatter 的方式）
- `oryxos-storage`（`ToolInvocation` 实体 + `ToolInvocationRepository`）——**本节新增到 `oryxos-core`**，理由见 §「需要说明的三处偏离」②
- 测试：`spring-boot-starter-test`（JUnit 5 + Mockito，`oryxos-core` 已有）

**Storage**: SQLite `.oryxos/oryxos.db`；`tool_invocations` 表**骨架期已交付**（实体 / Repository / 建表脚本齐备），本节只写不建、不改表结构

**Testing**: JUnit 5 + Mockito；5 个单测类（`ReActLoopTest` / `PromptBuilderTest` / `ToolExecutorTest` / `AgentServiceTest` / `ContextLoaderTest`）全部落在 `oryxos-core`，**不碰网络**（模型调用与工具执行均为替身，文件系统走 JUnit 临时目录）。本节**无集成冒烟**（`@Tag("integration")` 的类一个都不加）。完成定义 = `mvn clean verify` 全绿

**Target Platform**: JVM 21 单进程，Linux / WSL2 开发环境

**Project Type**: Maven 多模块后端库（非独立应用）；本节触及 2 个模块（`oryxos-core` 主体、`oryxos-storage` 只读消费）

**Performance Goals**: 循环调度自身的额外开销可忽略（不引入额外模型调用与额外工具调用：SC-001 要求「恰好调 N 次」）；`ContextLoader` 每次组装重新读文件（SC-008 要求生效延迟 = 0 轮），以「不缓存」换「改完即生效」，读文件开销相对于一次模型推理可忽略

**Constraints**:
- MUST NOT 引入响应式编程（章程原则 I）——不写 `Flux` / `CompletableFuture`，不开自建线程池
- MUST NOT 启用 Spring AI 自动工具执行（章程原则 III，最易写错的一条）——core 只翻译 `ToolCallback`，开关由第 16 节 `ProviderService` 单点关闭
- MUST NOT 反向依赖能力模块（章程附加约束）——`oryxos-core` 不出现 `oryxos-provider` / `oryxos-tool` / `oryxos-memory` 的 import
- MUST NOT 新增任何第三方依赖（除 `spring-ai-model` 与 `oryxos-storage` 两项已报批的依赖）
- MUST NOT 依赖 `hibernate.ddl-auto` 做建表或迁移（章程附加约束）
- 避开 P3C/ASM 解析不了的 Java 18+ 语法形态（增强 switch 的 `default ->` 写法），静态检查是构建门禁

**Scale/Scope**: 单实例 ≥ 10 个 Agent（章程附加约束）；本节落地 6 个引擎类 + 4 个接缝接口 + 2 个会话领域类型，共 12 个新 public 类型；循环上限默认 10 轮、历史截断默认 20 轮，均可按 Agent 覆盖

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 本节的落实 | 结论 |
| --- | --- | --- |
| I. JDK 21 + SB 3.x 单体、多模块、单二进制；同步阻塞，MUST NOT 响应式 | 全部新代码落在既有模块内；`ReActLoop` 是同步 `for` 循环；一次处理独占一个虚拟线程，`Session` 不加锁 | ✅ |
| II. 只交付运行时内核；MUST NOT 引入未被证实的抽象（YAGNI） | 只做 US-2 的循环切片；不做并行工具调用 / Agent 互托 / 流式 / 上下文压缩（课件「有几样先别做」逐项照搬）；`SessionService` 只声明有调用方的 `save`，不预造 `load`/`create` | ✅ |
| III. Agent 引擎自实现，Spring AI 只用一半；自动 tool 执行 MUST 被禁用 | **本节正是「自实现 ReAct 循环」这条的落点**：循环是手写 `for` + 四个类，不碰框架的 Agent 抽象；core 侧只把 `OryxTool` 翻译成 `ToolCallback`，产物 `call()` 抛 `UnsupportedOperationException`，执行权 100% 在 `ToolExecutor` | ✅ |
| IV. Agent 目录不是 Tool；Skill 绑定真相源是软连接、MUST NOT 进 frontmatter | `ContextLoader` 扫 Agent 目录下的 `skills/*` 软连接，**Skill MUST NOT 注册进 `ToolCatalog`**；`AGENT.md` 正文注入 system prompt、不进工具清单；`Profile` 不加 `skills` 字段 | ✅ |
| V. Tool 体系统一抽象、单一模块 | `ToolCatalog` 返回统一抽象 `OryxTool`，`ReActLoop` 不感知工具来源（内置 / MCP / `@Tool` Bean 三档）；不新增模块、不拆 `oryxos-tool` | ✅ |
| VI. 审计数据 Day One 落库；成败都写 | `ToolExecutor` 对**每一次**模型请求的工具调用写一行 `tool_invocations`（成功 / 工具失败 / 查不到工具三种情形全覆盖，SC-006 覆盖率 100%），失败原因与耗时同写 | ✅ |
| VII. 接口先行，实现按档演进；MUST NOT 用 SecurityManager | 四个接缝接口都是中立接口（只声明语义、不携带实现细节）；不涉及沙箱、不引入 SecurityManager | ✅ |
| VIII. SQLite + JPA；凭证环境变量注入；状态外置 | 审计走既有 SQLite 数据源；本节不引入任何凭证；`ProfileContext` 是逐请求运行态、处理结束即清（SC-007），不留实例内跨请求状态 | ✅ |
| IX. 三触发源复用同一链路 | **本节正是这条的落点**：CLI / Web / `AgentScheduler` 都汇入 `AgentService.process`；`ReActLoop` 不认识 channel、不认识触发源，只认识 `Session` + 用户消息 + `Profile`（FR-008） | ✅ |
| X. 每个 user story 有可演示 Demo | 本节是 US-2 的切片；循环无独立交互入口，**本节的「可演示」形态是测试而非交互 Demo**（[quickstart.md](quickstart.md) §5 已说明）。端到端 Demo 一需要第 18 节 CLI 与第 20 节内置 Tool 就位，已列为人工项并如实标注为「本节做完时跑不了」 | ✅ |

**附加约束核对**：静态检查门禁（Spotless / P3C / Checkstyle / SpotBugs+FindSecBugs / OWASP）全部继承父 pom，本节新增代码 MUST 通过；MUST NOT 用 `System.out`（日志走 SLF4J）；MUST NOT 出现明文凭证。

### 需要说明的三处偏离（已论证，非违规）

**① `oryxos-core` 新增 `spring-ai-model`。** 第 16 节已把 `chat(String sessionId, Profile profile, Prompt prompt)` 钉成字面量不可改（[contracts/provider-service-api.md](../001-llm-provider/contracts/provider-service-api.md) §1），故 core 必须能构造 `Prompt`、读 `ChatResponse`。**这是依赖「类型」而不是依赖「模块」**——`spring-ai-model` 是第三方库，不是 OryxOS 的能力模块，章程禁止的是 core 反向依赖 `oryxos-provider` / `oryxos-tool` / `oryxos-memory`。同理，core 直接使用 Spring AI 的消息类型，也避免了自造一套 `Response` / `ToolCall` 包装类型（课件骨架里有，本节**不引入**，见 [research.md](research.md) D4）。已报批。

**② `oryxos-core` 新增 `oryxos-storage`。** `ToolExecutor` 需要写 `tool_invocations`，而实体与 Repository 在 `oryxos-storage`。上游 `specs/000-runtime-kernel/plan.md` U4 已把 `oryxos-storage` 定为**共享基础设施模块**（core 与能力模块都可直接依赖），且 `oryxos-storage` 不依赖 core，**不构成环**。备选方案是自造一个 `ToolInvocationRecorder` 接口 + 由 storage 侧实现，但那会为 1 个写入点造 2 个类型（章程原则 II 的 YAGNI）。已报批。

**③ 不给 `ProviderService` 加 `implements LlmClient`。** 第 16 节 `contracts/provider-service-api.md` §5 写「第 17 节只需让 `ProviderService` 增加一个 `implements`」，而本节 spec 的 Assumptions 明写「本节只定义与调用，不实现；测试一律用替身」（本轮已批准）。**两处不一致，本节按更新且已批准的 spec 顺延到第 18 节装配 CLI 时一并接线**。影响面为零：`ProviderService.chat` 的签名与 [contracts/core-seams.md](contracts/core-seams.md) §1 逐字一致，加 `implements` 是一行、不改方法体；本节代码与测试全部面向 `LlmClient` 编程，接线时不动本节任何一行。**此项已在 Step 5 的固定停点提请用户确认**（[contracts/core-seams.md](contracts/core-seams.md) §5 有完整记录）。

**代价（如实记录）**：本节交付后，`LlmClient` / `ToolCatalog` / `MemorySource` / `SessionService` 四个接口在**生产装配**里都没有实现方，`oryxos-boot` 的 Spring 上下文不会构造 `ReActLoop`——端到端路径在本节结束时是断的。这是 spec 认可的形态（本节无独立交互入口，端到端 Demo 是人工项），且**不产生死代码**：四个引擎类全部被单测覆盖。

## Project Structure

### Documentation (this feature)

```text
specs/002-react-loop/
├── plan.md              # 本文件
├── spec.md              # 需求规约（/speckit-specify 产出）
├── research.md          # Phase 0 产出：D1~D17 技术决策与备选方案
├── data-model.md        # Phase 1 产出：Session / SessionMessage / ToolInvocation / ProfileContext
├── quickstart.md        # Phase 1 产出：可执行的验证指引
├── contracts/           # Phase 1 产出：对外契约
│   ├── core-seams.md            # 四个接缝接口 + 为什么不给 ProviderService 加 implements
│   ├── engine-api.md            # 六个引擎类的对外 API 与行为约定
│   ├── session-model.md         # 会话领域类型 + 留给第 18 节的消费面
│   └── tool-invocations-table.md# 只消费姿态 + 列与写入契约
├── checklists/
│   └── requirements.md  # /speckit-specify 产出的质量清单
└── tasks.md             # Phase 2 产出（/speckit-tasks，非本命令）
```

### Source Code (repository root)

```text
oryxos-core/src/main/java/io/oryxlabs/oryxos/core/
├── engine/
│   ├── ReActLoop.java         # 新增：调度者，循环本体（数十行）
│   ├── PromptBuilder.java     # 新增：四部分组装 + 历史截断 + OryxTool→ToolCallback 私有翻译
│   ├── ToolExecutor.java      # 新增：工具执行权唯一落点 + 审计写入
│   ├── AgentService.java      # 新增：三触发源的唯一入口，管 ProfileContext 生命周期
│   └── ProfileContext.java    # 新增：当前 Agent 的 ThreadLocal（set / current / clear）
├── context/
│   └── ContextLoader.java     # 新增：现读现拼 system prompt 的文件部分，不缓存
├── session/
│   ├── Session.java           # 新增：内存态会话领域类型（字段 + 三种追加操作）
│   ├── SessionMessage.java    # 新增：单条消息（role / content / toolCalls / toolCallId / timestamp）
│   └── SessionService.java    # 新增：接缝接口，本节只声明 save(Session)
├── llm/
│   └── LlmClient.java         # 新增：接缝接口，chat(sessionId, Profile, Prompt)
├── memory/
│   └── MemorySource.java      # 新增：接缝接口，longTermMemory(Profile) → Optional<String>
└── tool/
    └── ToolCatalog.java       # 新增：接缝接口，find(name) / availableFor(Profile)（与既有 OryxTool 同包）

oryxos-core/src/test/java/io/oryxlabs/oryxos/core/
├── engine/
│   ├── ReActLoopTest.java     # 新增：收尾 / 转起来 / 转满上限强制停（关键回归）
│   ├── PromptBuilderTest.java # 新增：四部分顺序 / 截断 / 日期时间在末尾
│   ├── ToolExecutorTest.java  # 新增：成功 / 失败 / 查不到工具三种情形都落审计，异常不吞
│   └── AgentServiceTest.java  # 新增：ProfileContext 设置与清除（关键回归）/ 经接缝持久化
└── context/
    └── ContextLoaderTest.java # 新增：无缓存（改完即生效）/ 缺失文件的报错与 WARN

oryxos-core/pom.xml            # 改：新增 spring-ai-model 与 oryxos-storage 两个依赖
oryxos-storage/...             # 既有，不改（ToolInvocation / ToolInvocationRepository / schema.sql 骨架期已交付）
```

**Structure Decision**: 沿用既有 9 模块骨架，**不新增模块**。六个引擎类与四个接缝接口全部落在 `oryxos-core`——接缝接口放 core 是章程「契约在 core、实现在各模块」的直接落地；`ToolCatalog` 与既有的 `OryxTool` / `ToolResult` 同包（`core.tool`），三者共同构成「core 定义工具契约、`oryxos-tool` 提供实现」的完整面。会话领域类型放 `core.session`（`CLAUDE.md` 模块表已把 `Session` 列在 `oryxos-core`）。审计侧**只消费**：`ToolInvocation` 实体、`ToolInvocationRepository`、建表脚本均由骨架期交付，本节不改表结构、不新建表、不新增查询方法。

依赖方向新增两条边，均已报批并论证：`oryxos-core → spring-ai-model`（依赖类型）与 `oryxos-core → oryxos-storage`（共享基础设施）。**`oryxos-core` 到 `oryxos-provider` / `oryxos-tool` / `oryxos-memory` 的边一条都不新增**（[quickstart.md](quickstart.md) §3 有可 grep 的核查命令）。

## Complexity Tracking

> 本节的偏离已在上文「需要说明的三处偏离」逐条论证，均属已报批的架构裁决或章程明确允许的用法，**无未经论证的复杂性**，故不填违规表。

**一处已知的、有界的重复（记录但不构成违规）**：`PromptBuilder` 需要一个 `OryxTool → ToolCallback` 的翻译，而既有实现 `ToolSchemaAdapter` 在 `oryxos-provider`（core 不能依赖它）。本节的处理是在 `PromptBuilder` 内写一个**私有静态方法**（约 8 行，与 `ToolSchemaAdapter` 有意重复），**不新增 public 类型**。理由：为 8 行翻译造一个跨模块契约，成本高于重复；且两者的产物语义一致（只翻译、不执行）。收敛路径已记入 [research.md](research.md) D7，计划在第 20 节 `ToolRegistry` 落地时把翻译单点收敛到 `oryxos-tool` 并让 `PromptBuilder` 经接缝取用。

## 已知接缝（留给第 18 节）

本节刻意留白、由后续节补齐的四处：

| 留白 | 由谁补 | 本节已就位的部分 |
| --- | --- | --- |
| `ProviderService implements LlmClient` 的接线 | 第 18 节（CLI 装配时一并做） | 签名逐字一致，加 `implements` 是一行（见 §「需要说明的三处偏离」③） |
| `SessionService` 的实现：`sessions` 表 + JPA 实体 + Repository + `load` / `create` / 会话标识生成 | 第 18 节 | `Session` 五个字段与 A1 逐字对齐；`save` 已被 `AgentService` 调用 |
| `ToolCatalog` 的实现 | 第 20 节（`oryxos-tool.ToolRegistry`） | core 侧的接口与两个方法的语义已定 |
| `MemorySource` 的实现 | 第 22 节（`oryxos-memory.MemoryService`） | core 侧的接口与「有就读、没有就跳过」的语义已定 |

另有两处**非接缝**的留白，本节明确不做并已记入 [research.md](research.md)：`tool_invocations` 的入参/结果脱敏（上游 data-model 提出、课件与章程均未要求，写入点只有 `ToolExecutor` 一处，后续引入统一脱敏单点时回改即可）、工具失败重试（`ToolResult.retryable` 原样透传、不消费）。

## Constitution Check（Phase 1 设计后复核）

设计产物（`data-model.md`、`contracts/`）产出后复核 10 条原则，**维持 PASS**：

- [contracts/core-seams.md](contracts/core-seams.md) 把四个接口的签名、包位、实现方与落地节逐条钉死，「core 不反向依赖」与「契约在 core」两条约束在契约层面被固化，不会被后续实现绕过。
- [contracts/core-seams.md](contracts/core-seams.md) §1 的「行为约定」明文写入 `internalToolExecutionEnabled` MUST 为 `false`、MUST NOT 按类型扫描容器 Bean——原则 III 最易写错的两条被写进契约，实现方无法悄悄绕过。
- [contracts/engine-api.md](contracts/engine-api.md) §2 规定翻译产物的 `call(String)` 抛 `UnsupportedOperationException`，把「Spring AI 只用一半」变成可断言的事实；§3 的三条不变量把「成败都落审计」与「异常不打断循环」写死，原则 VI 的落点具体可测。
- [contracts/engine-api.md](contracts/engine-api.md) §4/§5 把「三触发源唯一入口」与「`finally` 清 ThreadLocal」固化，原则 IX 与 SC-007 在契约层面可验证。
- [contracts/session-model.md](contracts/session-model.md) §3 的四条不变量（只追加 / 文本与工具请求同属一条消息 / `SYSTEM` 不写入 / 非线程安全）保证第 18 节换实现时不会破坏本节的语义。
- [contracts/tool-invocations-table.md](contracts/tool-invocations-table.md) §0/§2 明确「只写不建、不改表结构」，原则 VI 的「day one 落库」在骨架期已完成的基础上只做覆盖，不引入新的表或字段。
- 无新增模块、无反向依赖、无响应式代码、无自建线程池、无明文凭证、无 SecurityManager。

## Phase 0 / Phase 1 产出

- 技术决策与备选方案：[research.md](research.md)（D1~D17 + 未决项）
- 实体与字段：[data-model.md](data-model.md)
- 对外契约：[contracts/](contracts/)
- 验证指引：[quickstart.md](quickstart.md)
