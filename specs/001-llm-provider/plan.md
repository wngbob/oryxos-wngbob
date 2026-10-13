# Implementation Plan: Provider —— 对接大模型的统一入口

**Branch**: `class-16` | **Date**: 2026-10-13 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/001-llm-provider/spec.md`（第 16 节课件：`docs/class/第16节：Agent Provider 原理解析、实现与代码讲解.md`）

**Note**: 本文件由 `/speckit-plan` 生成。本节是核心阶段 US-1 的第一节切片，上游为 `specs/000-runtime-kernel`。

## Summary

给 OryxOS 装上一层薄薄的 Provider 抽象：上层（第 17 节的 ReAct 循环）递进「会话标识 + Profile + 提示内容」，Provider 按 Profile 点名的厂商从**显式映射表**里取出对应模型、发起一次调用、把结果原样返回，并**不论成败**把这次调用落进 `llm_calls` 审计表。工具说明只翻译成模型看得懂的格式随请求发出，**绝不执行**——框架自带的自动工具执行被显式关闭。

随本节一并交付 Profile 的落地形态：由 Agent 目录 `.oryxos/agents/<name>/AGENT.md` 的 frontmatter 派生为 `Profile`，启动时全量加载进内存索引，坏文件记错误但不阻断启动。

## Technical Context

**Language/Version**: Java 21（`maven.compiler.release=21`）

**Primary Dependencies**:
- Spring Boot 3.5.16（`spring-boot-starter`、`spring-boot-starter-data-jpa`）
- Spring AI 1.1.8：`spring-ai-model`（`ChatModel` / `Prompt` / `ToolCallingChatOptions` 等抽象）+ `spring-ai-deepseek`（具体实现），两者均由已导入的 `spring-ai-bom` 1.1.8 托管
- SnakeYAML（`oryxos-core` 已有依赖，用于 `AGENT.md` frontmatter 解析）
- SQLite：`org.xerial:sqlite-jdbc` 3.53.4.0 + `hibernate-community-dialects`（`oryxos-storage` 已有）
- 测试：`spring-boot-starter-test`（JUnit 5 + Mockito）——**当前所有模块均未引入，本节按模块补 test scope**

**Storage**: SQLite `.oryxos/oryxos.db`；`llm_calls` 表由手工建表脚本 `oryxos-storage/src/main/resources/db/schema.sql` 维护（`ddl-auto=none`）

**Testing**: JUnit 5 + Mockito；4 个单测类（`ProfileLoaderTest` / `ProviderServiceTest` / `ToolSchemaAdapterTest` / `LlmCallRepositoryTest`）+ 1 个 `@Tag("integration")` 冒烟 `ProviderSmokeIT`（CI 跳过）。完成定义 = `mvn clean verify` 全绿

**Target Platform**: JVM 21 单进程，Linux / WSL2 开发环境

**Project Type**: Maven 多模块后端库（非独立应用）；本节触及 3 个模块

**Performance Goals**: Provider 抽象层自身的转发开销 ≤ 50ms（不含模型推理与网络往返），且不随并存厂商数量增长（SC-006，口径沿用上游 `000-runtime-kernel` SC-005）

**Constraints**:
- MUST NOT 引入响应式编程（章程原则 I）——不使用 `stream()`、不写 `Flux`
- MUST NOT 明文凭证；凭证只从环境变量读（章程原则 VIII）
- MUST NOT 依赖 `hibernate.ddl-auto` 做建表或迁移（章程附加约束）
- 避开 P3C/ASM 解析不了的 Java 18+ 语法形态（增强 switch 等），静态检查是构建门禁
- 不开新线程池、不用 `CompletableFuture`（H4 全局不变量⑤）

**Scale/Scope**: 单实例 ≥ 10 个 Agent（章程附加约束）；本节落地 1 个示例厂商 `deepseek`，映射表结构上支持 N 家

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 本节的落实 | 结论 |
| --- | --- | --- |
| I. JDK 21 + SB 3.x 单体、多模块、单二进制；同步阻塞，MUST NOT 响应式 | 代码全部落在既有 Maven 模块内；不写响应式代码、不用 `stream()` | ✅（见下方传递依赖说明） |
| II. 只交付运行时内核；MUST NOT 引入未被证实的抽象（YAGNI） | 只做 US-1 的 Provider 切片；不做 fallback / 熔断 / 成本看板 / 流式 | ✅ |
| III. Agent 引擎自实现，Spring AI 只用一半；自动 tool 执行 MUST 被禁用；provider name → `ChatModel` MUST 显式映射、MUST NOT 类型扫描 | 本节不碰 ReAct；**本节正是「关自动执行」与「显式映射表」两条的落点** | ✅ |
| IV. Agent 目录不是 Tool；Skill 绑定真相源是软连接、MUST NOT 进 frontmatter | `Profile` 由 frontmatter 派生、**不含 `skills` / `notify_channels`**；不注册进 ToolRegistry | ✅ |
| V. Tool 体系统一抽象、单一模块 | 本节不改动 `oryxos-tool`，也不新增 Tool 抽象 | ✅ |
| VI. 审计数据 Day One 落库；成败都写 | `llm_calls` 补 `success` / `error_message` 两列；成功与失败路径都落库（FR-008/009） | ✅ |
| VII. 接口先行，实现按档演进；MUST NOT 用 SecurityManager | 本节不引入新的升级型契约接口（`Sandbox` / `LongTermMemoryStore` / `NotifyChannelAdapter` 均不在本节范围）；不涉及沙箱 | ✅ |
| VIII. SQLite + JPA；凭证环境变量注入；状态外置 | `llm_calls` 走既有 SQLite 数据源；凭证走 `${ENV_VAR}`；Provider 无实例内不可重建状态 | ✅ |
| IX. 三触发源复用同一链路 | 本节不引入触发源 | ✅ |
| X. 每个 user story 有可演示 Demo | 本节是 US-1 的切片；Provider 无独立入口，本节的"可演示"形态是**测试**而非交互 Demo（[quickstart.md](quickstart.md) §5 已说明），端到端证据为 `ProviderSmokeIT` | ✅ |

**附加约束核对**：静态检查门禁（Spotless / P3C / Checkstyle / SpotBugs+FindSecBugs / OWASP）全部继承父 pom，本节新增代码 MUST 通过；MUST NOT 用 `System.out`（日志走 SLF4J）。

### 需要说明的两处偏离（已论证，非违规）

**① 传递依赖会带进 `reactor-core`。** `spring-ai-model` 的 `ChatModel` 继承了 `StreamingChatModel`，其 `stream(Prompt)` 签名返回 `Flux`——这是第三方接口的既定签名，无法回避。章程原则 I 禁止的是**引入响应式编程模型**（自己写 `Flux` / 用异步编排），本节不违反：不调用 `stream()`、不出现任何 Reactor 类型引用。已选 `spring-ai-model` 是章程原则 III 明确允许的 Spring AI 用法（Provider 抽象 + 协议转换）。

**② 具体实现依赖用 `spring-ai-deepseek`，而非 `spring-ai-starter-model-deepseek`。** 已实测两者都在 `spring-ai-bom` 1.1.8 中托管。不选 starter 的理由：starter 会通过 `spring-ai-autoconfigure-model-deepseek` 自动配置出**按类型注入**的 `ChatModel` Bean，而本节的核心要求恰是"显式建表、MUST NOT 靠类型扫描"（章程原则 III）；用非 starter 的模型实现 + 自己写的 `@Configuration` 显式构造，才能让"谁对谁"写死可见。代价是少了属性化配置的便利，换来的是架构约束不被框架悄悄绕过。

**③ 示例厂商落地 `deepseek`，课件中的 `qwen` 仅作场景示意。** 已实测 `spring-ai-alibaba-bom` 1.1.2.3 中**不含** dashscope（通义千问）starter，`spring-ai-bom` 1.1.8 中含 `spring-ai-starter-model-deepseek` / `spring-ai-deepseek` / `spring-ai-autoconfigure-model-deepseek`。这正是课件坑三要提前验证的事项。

## Project Structure

### Documentation (this feature)

```text
specs/001-llm-provider/
├── plan.md              # 本文件
├── spec.md              # 需求规约（/speckit-specify 产出）
├── research.md          # Phase 0 产出：技术决策与备选方案
├── data-model.md        # Phase 1 产出：实体与字段
├── quickstart.md        # Phase 1 产出：可执行的验证指引
├── contracts/           # Phase 1 产出：对外契约
│   ├── provider-config.md
│   ├── provider-service-api.md
│   └── llm-calls-table.md
├── checklists/
│   └── requirements.md  # /speckit-specify 产出的质量清单
└── tasks.md             # Phase 2 产出（/speckit-tasks，非本命令）
```

### Source Code (repository root)

```text
oryxos-core/src/main/java/io/oryxlabs/oryxos/core/profile/
├── Profile.java              # 新增：AGENT.md frontmatter 的承载记录类（本节建全字段）
├── ProfileLoader.java        # 新增：扫描 .oryxos/agents/*/AGENT.md，解析 + 校验 + 解析 ${ENV} 占位
└── ProfileRegistry.java      # 新增：Map<String, Profile> 内存索引，按 Agent 名查找

oryxos-core/src/test/java/io/oryxlabs/oryxos/core/profile/
└── ProfileLoaderTest.java    # 新增

oryxos-provider/src/main/java/io/oryxlabs/oryxos/provider/
├── ProviderService.java          # 新增：chat(sessionId, Profile, Prompt) —— 本节核心
├── ToolSchemaAdapter.java        # 新增：OryxTool → Spring AI ToolCallback（只翻译，不执行）
├── LlmCallRecorder.java          # 新增：审计落库的接口（便于 mock）
├── JpaLlmCallRecorder.java       # 新增：基于 LlmCallRepository 的实现
├── ProviderProperties.java       # 新增：绑定 oryxos.providers 全局层配置
├── ProviderConfiguration.java    # 新增：按配置显式构造 Map<String, ChatModel>
└── ProviderNotFoundException.java# 新增：Profile 点名了不存在的厂商（课件示例代码中出现）

oryxos-provider/src/test/java/io/oryxlabs/oryxos/provider/
├── ProviderServiceTest.java      # 新增：路由不串台 / 失败审计 / 关自动执行 三个关键回归点
├── ToolSchemaAdapterTest.java    # 新增
└── ProviderSmokeIT.java          # 新增：@Tag("integration")，真调一次模型

oryxos-storage/src/main/java/io/oryxlabs/oryxos/storage/entity/LlmCall.java   # 改：补 success / errorMessage
oryxos-storage/src/main/java/io/oryxlabs/oryxos/storage/repository/LlmCallRepository.java # 既有，不改
oryxos-storage/src/main/resources/db/schema.sql                               # 改：llm_calls 补两列
oryxos-storage/src/test/java/io/oryxlabs/oryxos/storage/repository/
└── LlmCallRepositoryTest.java    # 新增：真 SQLite + 手工脚本

oryxos-boot/src/main/resources/application.yaml                               # 改：新增 oryxos.providers 全局层

各模块 pom.xml                                                                 # 改：补依赖与 test scope
```

**Structure Decision**: 沿用既有 9 模块骨架，**不新增模块**。契约（`OryxTool`）留在 `oryxos-core`，本节新增的 `Profile` 三件套同属 core；实现（`ProviderService` 与三个配角、装配）落在 `oryxos-provider`；审计实体与建表脚本落在 `oryxos-storage`。依赖方向为 `oryxos-provider → oryxos-core` + `oryxos-provider → oryxos-storage`，`oryxos-core` **不反向依赖**任何能力模块（章程附加约束）。

## Complexity Tracking

> 本节的偏离已在上文「需要说明的两处偏离」逐条论证，均属第三方接口既定形态或章程明确允许的用法，**无未经论证的复杂性**，故不填违规表。

## 已知接缝（留给第 17 节）

章程要求 `oryxos-core` MUST NOT 反向依赖能力模块，而上游 `000-runtime-kernel` plan 已定规则：**core 需消费能力模块服务时，接口（契约）放 core、实现放能力模块、Spring 注入**（同 `ToolCatalog` / `MemoryService` 模式）。

第 17 节的 `ReActLoop` 落在 `oryxos-core`，届时需要为 `ProviderService` 抽象出 core 侧接口并让其实现。**本节不预先引入该接口**——本节没有任何消费者，凭空造接口违背章程原则 II（YAGNI）。第 17 节只需给 `ProviderService` 加一个 `implements`，方法签名沿用 [contracts/provider-service-api.md](contracts/provider-service-api.md) §1，不构成返工。

## Constitution Check（Phase 1 设计后复核）

设计产物（`data-model.md`、`contracts/`）产出后复核 10 条原则，**维持 PASS**：

- `contracts/llm-calls-table.md` 的列与 `data-model.md` §3 一致，`success` / `error_message` 已补入，原则 VI 的落点具体可测。
- `contracts/provider-config.md` §5 明文写入"MUST NOT 类型扫描"与"因此不引 starter"，原则 III 的两条要求在配置契约层面被固化，不会被后续实现绕过。
- `contracts/provider-service-api.md` §2 规定适配器产物的 `call()` 抛 `UnsupportedOperationException`，把"只翻译不执行"变成可断言的事实。
- `contracts/provider-config.md` §2 与 `llm-calls-table.md` §6 分别锁死"凭证只走环境变量"与"日志脱敏同规则"，原则 VIII 的凭证要求无遗漏。
- 无新增模块、无反向依赖、无响应式代码、无自建线程池。

## Phase 0 / Phase 1 产出

- 技术决策与备选方案：[research.md](research.md)
- 实体与字段：[data-model.md](data-model.md)
- 对外契约：[contracts/](contracts/)
- 验证指引：[quickstart.md](quickstart.md)
