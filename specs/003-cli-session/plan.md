# Implementation Plan: CLI 与会话持久化 —— 把 Agent 从「能跑」变成「能用」

**Branch**: `class-18` | **Date**: 2026-10-13 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/003-cli-session/spec.md`（第 18 节课件：`docs/class/第18节：CLI 功能概述、实现思路与代码讲解.md`）

**Note**: 本文件由 `/speckit-plan` 生成。本节是核心阶段 US-5 的前置切片，上游为 `specs/001-llm-provider`（Provider）、`specs/002-react-loop`（ReAct 引擎与四个 core 接缝）、`specs/000-runtime-kernel`（契约与审计地基）。

## Summary

给 OryxOS 装上**第一个能亲手操作的入口**：12 个子命令的 CLI，在终端里 `oryxos init` 起一个工程、`oryxos profile create` 造一个 Agent、`oryxos chat --profile <name>` 跟它对话。

CLI 是「消息进出的门，不是干活的人」——它只做**读输入、交给引擎、打印结果**三步，不碰任何 Agent 逻辑。所有实际工作仍归第 17 节的 `AgentService.process` 一条链路（章程原则 IX）。

随本节一并交付**会话持久化**：`sessions` 表 + JPA 实体 + Repository + `SessionService` 实现。会话标识由 channel、user、profile 三段联合生成，**拼接只发生在唯一一处**；所有入口只提供三元组，不自己拼字符串。

本节同时补上第 17 节刻意留白的两处接线：`ProviderService implements LlmClient`（一行 `implements`），以及 `ToolCatalog` / `MemorySource` 两个接缝的**空实现**（第 20 / 22 节的真实实现一落地即经 `@ConditionalOnMissingBean` 自动让位）。

## Technical Context

**Language/Version**: Java 21（`maven.compiler.release=21`）

**Primary Dependencies**:
- Spring Boot 3.5.16（`spring-boot-starter`）——**本节新增到 `oryxos-cli`**，用于重命令的上下文启动
- Picocli 4.7.7（`oryxos-cli` 已有）
- SnakeYAML（`oryxos-core` 已有，`ProfileLoader` 读 `AGENT.md` frontmatter 复用）
- Jackson（`jackson-databind` 2.21.4 + `jackson-datatype-jsr310` 2.21.4）——**经 `spring-ai-model` 传递到 `oryxos-core`**，用于 `messages_json` 序列化；**不新增第三方依赖**
- `oryxos-storage`（`SessionEntity` + `SessionRepository` + `sqlite-jdbc`）——**本节新增到 `oryxos-cli`**
- `oryxos-core` / `oryxos-provider` / `oryxos-tool` / `oryxos-memory` / `oryxos-channel-cli`——**本节新增到 `oryxos-cli`**
- 测试：`spring-boot-starter-test`（JUnit 5 + Mockito）、`spring-boot-starter-data-jpa` 的 `@DataJpaTest`（各模块已有）

**Storage**: SQLite `.oryxos/oryxos.db`；`sessions` 表**本节新建**（列名与长度逐字照 `specs/000-runtime-kernel/data-model.md` A1 抄，建表脚本手工维护在 `oryxos-storage/src/main/resources/db/schema.sql`）；`tool_invocations` / `llm_calls` 骨架期已交付，本节只读不写、不改结构

**Testing**: JUnit 5 + Mockito；2 个测试类——`SessionServiceTest`（`oryxos-core`，纯单测，Mockito + `Map` 支撑的 Repository 替身）、`SessionRepositoryTest`（`oryxos-storage`，`@DataJpaTest` + 真 SQLite 临时文件 + 手工脚本）。两者**都不碰网络**。本节**无 `@Tag("integration")` 集成冒烟**（端到端 Demo 需真模型，列为人工项）。完成定义 = `mvn clean verify` 全绿

**Target Platform**: JVM 21 单进程，Linux / WSL2 开发环境

**Project Type**: Maven 多模块后端 + CLI 应用；本节触及 **6 个模块**（`oryxos-cli` 主体、`oryxos-core`、`oryxos-storage`、`oryxos-channel-cli`、`oryxos-provider` 一行、`oryxos-boot` 改 `pom.xml` 一处并回归）+ `CLAUDE.md` 的发行入口一句

**Performance Goals**: 轻命令（`init` / `status` / `profile *` / `provider list` / `tool list` / `session list`）**不启动 Spring 上下文**，秒级返回（SC-001）；重命令（`chat` / `serve` / `gateway`）启动上下文，允许 2~4 秒

**Constraints**:
- MUST NOT 反向依赖能力模块（章程附加约束）——`oryxos-core` 不出现 `oryxos-provider` / `oryxos-tool` / `oryxos-memory` 的 import
- MUST NOT 启用 Spring AI 自动工具执行（章程原则 III）——`ProviderService` 的 `internalToolExecutionEnabled` 开关由第 16 节单点关闭，本节不动
- MUST NOT 引入响应式编程（章程原则 I）——不写 `Flux` / `CompletableFuture`，不开自建线程池
- MUST NOT 新增任何第三方依赖（Jackson 为传递依赖，Picocli 已定）
- MUST NOT 依赖 `hibernate.ddl-auto` 做建表或迁移（章程附加约束）——建表走手工脚本，`init` 内嵌执行
- MUST NOT 出现明文凭证；`provider list` 的输出 MUST NOT 含任何密钥值（章程原则 VIII）
- MUST NOT 在 `oryxos-cli` 放第二份 `application.yaml`、MUST NOT 恢复其 shade 插件；发行物只有 `oryxos-boot/target/oryxos.jar` 一个（偏离 ④ / 仲裁 17）
- 避开 P3C/ASM 解析不了的 Java 18+ 语法形态（增强 switch 的 `default ->` 写法），静态检查是构建门禁

**Scale/Scope**: 单实例 ≥ 10 个 Agent（章程附加约束）；本节落地 12 个叶子命令 + 4 个分组命令 + 1 个启动类 + 2 个空接缝实现 + 1 个实体 + 1 个 Repository + 1 个持久化实现 + 3 个工作区辅助类；对外命令面 12 条，会话标识形如 `cli:local:<profile>`

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 本节的落实 | 结论 |
| --- | --- | --- |
| I. JDK 21 + SB 3.x 单体、多模块、单二进制；同步阻塞，MUST NOT 响应式 | 不新增模块（9 个模块骨架不变）；`chat` 是同步 `while` 读行循环；`serve` / `gateway` 只起上下文后前台阻塞，无响应式、无自建线程池。**「单二进制」本节反而被夯实**：偏离 ④ 把发行物收敛成 `oryxos-boot/target/oryxos.jar` 一个（此前 CLI 还多一个 shaded jar） | ✅ |
| II. 只交付运行时内核；MUST NOT 引入未被证实的抽象（YAGNI） | 只做课件点名的 12 条命令与 `sessions` 持久化；不做认证 / 流式 / WebSocket / 真正挂 IM 通道 / REST 端点 / 定时调度（课件「有几样先别做」逐项照搬）；`serve` / `gateway` 只立「模式」不预造接入层 | ✅ |
| III. Agent 引擎自实现，Spring AI 只用一半；自动 tool 执行 MUST 被禁用 | CLI **不碰**模型调用与工具调度，只经 `AgentService.process` 走既有链路；本节新增的 `CliApplication` 不引入任何 Spring AI 自动执行路径 | ✅ |
| IV. Agent 目录不是 Tool；Skill 绑定真相源是软连接、MUST NOT 进 frontmatter | `profile create` 生成的是 `.oryxos/agents/<name>/AGENT.md`（配置目录），**不注册进任何工具清单**；`init` 建出空的 `skills/` 公共库，不写 frontmatter 字段 | ✅ |
| V. Tool 体系统一抽象、单一模块 | `tool list` 经 `ToolCatalog` 接缝取工具，返回统一抽象；本节挂空实现，**不新建模块、不拆 `oryxos-tool`** | ✅ |
| VI. 审计数据 Day One 落库；成败都写 | 本节**不新增审计逻辑**：`chat` 经 `AgentService` 触发的工具调用与模型调用，仍由第 16/17 节的 `ToolExecutor` / `JpaLlmCallRecorder` 单点落 `tool_invocations` / `llm_calls`；会话历史落 `sessions` 是第三张表，**不替代**审计表 | ✅ |
| VII. 接口先行，实现按档演进；MUST NOT 用 SecurityManager | `SessionService` 是第 17 节已定的中立接口，本节只**补实现**不改签名语义；`JpaSessionService` 是「核心阶段挂一档实现」，升级只新增实现类；不涉及沙箱、不引入 SecurityManager | ✅ |
| VIII. SQLite + JPA；凭证环境变量注入；状态外置 | 会话状态全部落 SQLite（`sessions` 表），进程内不留跨请求状态；建表走手工脚本，不依赖 `ddl-auto`；`provider list` / `status` 的输出 MUST NOT 含密钥值（spec FR-015 / FR-016） | ✅ |
| IX. 三触发源复用同一链路 | **本节正是这条的落地**：`chat` 经 `CliChannel` → `AgentService.process`，与将来的 Web / `AgentScheduler` 共用同一条链路；CLI 只提供「channel = `cli`、user = `local`」三元组，不自己拼 `session_id`（FR-006） | ✅ |
| X. 每个 user story 有可演示 Demo | **本节正是「可演示」的转折点**：`oryxos init` → `profile create` → `chat` 端到端可跑（[quickstart.md](quickstart.md) §5）。代价是需真模型与 API key，故列为人工项并如实标注 | ✅ |

**附加约束核对**：静态检查门禁（Spotless / P3C / Checkstyle / SpotBugs+FindSecBugs / OWASP）全部继承父 pom，本节新增代码 MUST 通过；MUST NOT 用 `System.out` 直接输出（CLI 的用户可见输出除外——Picocli 的 `spec.commandLine().getOut()` 是**命令行应用的正确输出通道**，日志仍走 SLF4J）；MUST NOT 出现明文凭证。

### 需要说明的五处设计偏离（已论证，非违规）

**① `SessionService` 接口补两个操作、`Session` 增两个字段（改第 17 节交付的公共接口）。** 第 17 节的 `SessionService` 只有 `save`（当时没有 `load` / `create` 的调用方，YAGNI）；`Session` 只有 `id` / `profileName` / `messages` / `createdAt` / `lastActiveAt` 五个字段。本节要落 `sessions` 表，而 A1 定了 `channel` 与 `user_id` 两列——领域类型没有这两个字段就落不了库。变更内容：`SessionService` 补 `getOrCreate(channel, userId, profileName)` / `get(sessionId)`；`Session.create(id, channel, userId, profileName)`。**已报批**（spec 冲突仲裁记录 1、2），并连带修订 [contracts/session-model.md](../002-react-loop/contracts/session-model.md) §2 的「五个字段齐备」断言为七项。

**② `messages_json` 用包级私有 DTO 序列化，不给 `SessionMessage.Role` 加 Jackson 注解。** `SessionMessage.Role` 的枚举名是大写（`USER` / `ASSISTANT`），而 A1 要求落盘小写。加 `@JsonValue` 能解决，但那是**改第 17 节交付的公共类型**，且把磁盘格式绑死在 Java 枚举命名上（将来改枚举名就悄悄改磁盘格式）。本节改为在 core 的 `session` 包内放一个**包级私有**记录类型作为落盘形状的唯一映射。**不新增 public 类型**（不触发软门禁 ①），磁盘格式独立于内存类型演进。理由与备选见 [research.md](research.md) D4。

**③ 「`messages_json` 序列化往返」测试放 core 而不是 storage（与课件 harness 的落位不同）。** 课件把这条列在 `SessionRepositoryTest`，但**序列化器在 core**，而 `oryxos-storage` 的**测试**也不能反向依赖 core（Maven 会让 `core(compile) → storage` 与 `storage(test) → core` 形成回边，破坏反应堆构建顺序）。本节把「序列化往返」放序列化器所在的 core，「表往返 + 重启重查」留 storage；两条合起来覆盖课件那条 harness 的完整意图，且各自守在自己能看见的边界内。**此项在 Step 5 的固定停点提请用户确认。**

**④ 发行入口改为 `oryxos-boot` 的 fat JAR（改骨架期打包配置与已定字面量，已报批）。** 仲裁 5 已定 `application.yaml` 留在 `oryxos-boot`；而重命令要读 `spring.datasource.*` 与 `oryxos.providers`，二者**只在那一个 jar 的 classpath 上**。本节给 `oryxos-cli` 加 6 个模块依赖与 `spring-boot-starter` 后，它原来那个 shaded jar 会变成「轻命令能跑、重命令静默炸」（无 `spring.datasource.url` → DataSource 自动装配失败）——**这个陷阱是本节自己制造的**。变更内容：`oryxos-boot/pom.xml` 的 `spring-boot-maven-plugin` 增显式 `<mainClass>io.oryxlabs.oryxos.cli.OryxOsCli</mainClass>`；`oryxos-cli/pom.xml` **移除 `maven-shade-plugin`**。**已报批**（spec 冲突仲裁记录 17，用户授权参考参考实现 `/home/wngbob/oryxos` 的处理方式）。连带影响：`CLAUDE.md` 中 `java -jar oryxos-cli/target/...jar` 一句改为 `java -jar oryxos-boot/target/oryxos.jar`；上游 `specs/000-runtime-kernel/contracts/cli.md` 第 3 行的 jar 名被细化（**命令面 12 条逐字不变**，该骨架契约文件不修改）；`OryxosApplication` 不再是 fat JAR 主类（本节不改该类）。原计划的「给 shade 补 transformer」一条**随之作废**。理由与四个备选的排除见 [research.md](research.md) D11。

**⑤ `ProfileLoader` 增一个「失败明细」公开方法（改第 16 节交付的公共接口）。** 上游契约要求 `profile list` 标「有效/无效（无效附原因）」，而第 16 节的 `loadAll()` 把每条失败吞成 ERROR 日志、只返回成功的。**已报批**（spec 冲突仲裁记录 16，本轮用户裁决）：新增 `loadAllDetailed()`，`loadAll()` 的签名与行为保持不动（既有调用方零影响），纯 additive。**MUST NOT 在 CLI 侧重写校验规则**——「什么叫有效」的真相源只有 `ProfileLoader` 一处。

### 三处已报批的范围收窄（代价如实记录）

| 收窄项 | 决定 | 代价 |
| --- | --- | --- |
| `provider list` 报密钥就绪 | **不报**，改列「底座支持的厂商全集 + 工作区各 Agent 实际引用的厂商名」，就绪一律标「未接入（第 26 节）」 | FR-015 的原始意图（看某个 Provider 的 key 备好了没）本节交付不了；用户需等第 26 节。根因是**轻命令不启动 Spring 上下文**（FR-003），不加载 `application.yaml`；且该文件里写的是 `${DEEPSEEK_API_KEY}` **占位符**，「环境变量到底设没设」它答不了——要答只能去猜环境变量名，正是契约明令禁止的（spec 仲裁 6） |
| `serve` / `gateway` 的接入 | 只**启动 Spring 上下文并前台常驻**，不起 HTTP 端口、不挂 Channel；`serve --port` 接受解析但只回显「端口在第 26 节启用」 | 这两条命令本节**没有可演示的实际功能**，只有「模式存在 + 三种模式共享同一份 Profile 与 Session 存储」这层语义。属课件明写的范围 |
| `status` / `tool list` 的未接入段 | 未接入的段落**必须标注**，MUST NOT 编造数值（定时任务数标「第 25 节」、工具表标「第 20 节」） | `status` 的输出里有几行是占位说明而非真实数据。这是刻意的——编造比留白更糟 |

## Project Structure

### Documentation (this feature)

```text
specs/003-cli-session/
├── plan.md              # 本文件
├── spec.md              # 需求规约（/speckit-specify 产出）
├── research.md          # Phase 0 产出：D1~D20 技术决策与备选方案
├── data-model.md        # Phase 1 产出：Session / SessionEntity / 会话标识口径 / sessions 表
├── quickstart.md        # Phase 1 产出：可执行的验证指引
├── contracts/           # Phase 1 产出：对外契约
│   ├── cli-surface.md          # 12 条命令的输入 / 输出 / 退出码 / 轻重归属
│   ├── session-persistence.md  # 会话标识、领域类型、实体、Repository、messages_json 口径
│   └── cli-assembly.md         # 重命令的 Spring 装配：CliApplication + 空接缝 + ProviderService 接线
├── checklists/
│   └── requirements.md  # /speckit-specify 产出的质量清单
└── tasks.md             # Phase 2 产出（/speckit-tasks，非本命令）
```

### Source Code (repository root)

```text
oryxos-cli/src/main/java/io/oryxlabs/oryxos/cli/
├── OryxOsCli.java                    # 改：挂上 4 个分组命令（原本只有 version 输出）
├── CliApplication.java               # 新增：重命令的 Spring 启动类（显式 @EntityScan/@EnableJpaRepositories）
├── CliAssemblyConfiguration.java     # 新增：空接缝（ToolCatalog/MemorySource）+ 工作区路径 + Agent 解析的 Bean
├── command/
│   ├── InitCommand.java              # 新增：建工作区 + 执行建表脚本（幂等）
│   ├── StatusCommand.java            # 新增：工作区概览（未接入段标注）
│   ├── ChatCommand.java              # 新增：交互式对话循环 + /quit
│   ├── ServeCommand.java             # 新增：只起上下文常驻 + --port 回显
│   ├── GatewayCommand.java           # 新增：只起上下文常驻
│   ├── ProfileCommand.java           # 新增：分组（无逻辑）
│   ├── ProfileListCommand.java       # 新增：列 .oryxos/agents/ 下的 Agent 与有效性
│   ├── ProfileCreateCommand.java     # 新增：生成最小 AGENT.md 模板（已存在报错）
│   ├── ProfileShowCommand.java       # 新增：派生后的完整配置 + 校验结果
│   ├── ProfileDeleteCommand.java     # 新增：确认删除（--force 跳过）
│   ├── ProviderCommand.java          # 新增：分组（无逻辑）
│   ├── ProviderListCommand.java      # 新增：厂商全集 + 引用情况（未接入标注，不吐密钥）
│   ├── ToolCommand.java              # 新增：分组（无逻辑）
│   ├── ToolListCommand.java          # 新增：经 ToolCatalog 取工具（本节空表 + 标注）
│   ├── SessionCommand.java           # 新增：分组（无逻辑）
│   └── SessionListCommand.java       # 新增：列会话，按最近活跃排序
└── workspace/
    ├── Workspace.java                # 新增：工作区路径解析 + 「是否在工作区内」判定
    ├── WorkspaceDb.java              # 新增：轻命令的原生 JDBC 收口（连接 / 建表 / 查询）
    └── AgentResolver.java            # 新增：Agent 解析唯一闸门（含 profile_name 存在性校验）

oryxos-cli/src/test/java/io/oryxlabs/oryxos/cli/    # 本节不加测试类（见 § Testing）

oryxos-cli/pom.xml                    # 改：新增 6 个模块依赖 + spring-boot-starter；移除 shade 插件
oryxos-channel-cli/pom.xml            # 改：新增 oryxos-core
oryxos-channel-cli/.../CliChannel.java # 新增：channel = cli 的入站适配，调 AgentService.process

oryxos-boot/pom.xml                   # 改：spring-boot-maven-plugin 增显式 <mainClass>io.oryxlabs.oryxos.cli.OryxOsCli</mainClass>
CLAUDE.md                             # 改：CLI 发行入口从 oryxos-cli 的 fat JAR 改为 oryxos-boot/target/oryxos.jar

oryxos-core/src/main/java/io/oryxlabs/oryxos/core/profile/
└── ProfileLoader.java                 # 改：新增 loadAllDetailed()（成功清单 + 失败明细）；loadAll() 不动

oryxos-core/src/main/java/io/oryxlabs/oryxos/core/session/
├── Session.java                       # 改：增 channel / userId 字段与访问器，工厂签名变四参
├── SessionService.java                # 改：补 getOrCreate(channel, userId, profileName) / get(sessionId)
├── SessionId.java                     # 新增：会话标识拼接的唯一落点
├── MessageJson.java                   # 新增：包级私有落盘形状映射（不进 public 面）
└── JpaSessionService.java             # 新增：SessionService 的 JPA 实现（注入 SessionRepository）

oryxos-core/src/test/java/io/oryxlabs/oryxos/core/session/
└── SessionServiceTest.java            # 新增：幂等 / 隔离 / 标识只此一处 / 序列化往返

oryxos-storage/src/main/java/io/oryxlabs/oryxos/storage/
├── entity/SessionEntity.java          # 新增：@Table("sessions")，@Id String，无 @GeneratedValue
└── repository/SessionRepository.java  # 新增：JpaRepository<SessionEntity, String>

oryxos-storage/src/main/resources/db/schema.sql      # 改：追加 sessions 建表语句
oryxos-storage/src/test/java/.../repository/
└── SessionRepositoryTest.java         # 新增：建表 / 存取往返 / 「重启」重查

oryxos-provider/.../ProviderService.java             # 改：加 implements LlmClient（一行，不改方法体）
```

**Structure Decision**: 沿用既有 9 模块骨架，**不新增模块**。落位依据：

- **`oryxos-cli`** 是本节主体——`CLAUDE.md` 模块表已把「Picocli 主入口、12 个子命令、`ConfigLoader`」列在此模块。新增 `workspace/` 子包收口「工作区路径」「轻命令直连 SQLite」「Agent 解析」三件事，避免 12 个命令各写一份。
- **`oryxos-core`** 放会话领域层——`Session` / `SessionService` 是第 17 节交付的 core 类型，本节在此补实现 `JpaSessionService`（core → storage 是既定已报批边，与第 17 节 `ToolExecutor` 直接用 `ToolInvocationRepository` 同一模式）。
- **`oryxos-storage`** 放持久化层——`SessionEntity` / `SessionRepository` 与既有 `LlmCall` / `ToolInvocation` 同构；实体只持 `messagesJson` 字符串，**不认识 core 的类型**（storage 不依赖 core，反向即成环）。
- **`oryxos-channel-cli`** 放 `CliChannel`——`CLAUDE.md` 模块表已列「CLI Channel：`CliChannel`、`oryxos chat`」在此模块。该模块原本只有 `spring-boot-starter`、**没有 core 依赖**，本节补上（`CliChannel` 要调 `AgentService.process`）。
- **`oryxos-provider`** 只动一行：给 `ProviderService` 加 `implements LlmClient`（第 17 节刻意留白、约定在本节装配时一并做，见 [002 的 contracts/core-seams.md](../002-react-loop/contracts/core-seams.md) §5）。签名逐字一致，不改方法体。
- **`oryxos-core.profile`** 只动一处：`ProfileLoader` 新增 `loadAllDetailed()`（见上文偏离 ⑤）。`loadAll()` 与其既有调用方零影响。
- **`oryxos-boot`** 不改 Java 代码，但改 `pom.xml` 一处（`<mainClass>`）并**必须回归**——它是聚合模块，本节起同时承担「唯一发行入口」：CLI 的类打进它的 fat JAR 后与 `application.yaml` 同 classpath，重命令才读得到数据源与 Provider 配置（[research.md](research.md) D11）。两个 `@SpringBootApplication` 并存的风险在此验证（[research.md](research.md) D10）。

依赖方向新增的边（全部已报批）：`oryxos-cli → {core, provider, tool, memory, storage, channel-cli}`、`oryxos-channel-cli → oryxos-core`。**`oryxos-core` 到 `oryxos-provider` / `oryxos-tool` / `oryxos-memory` 的边一条都不新增**，`oryxos-storage → oryxos-core` 的边**一条都不新增**（[quickstart.md](quickstart.md) §3 有可 grep 的核查命令）。

## Complexity Tracking

> 本节的偏离已在上文「需要说明的五处设计偏离」与「三处已报批的范围收窄」逐条论证，均属已报批的架构裁决、课件明写的范围、或纯构建配置修正，**无未经论证的复杂性**，故不填违规表。

**两处已知的、有界的重复（记录但不构成违规）**：

1. **轻命令与重命令各有一条读库路径**（原生 JDBC vs Spring Data JPA）。理由：FR-003 要求轻命令不启动 Spring 上下文，而 `session list` / `status` 必须读库——没有第二条路径就实现不了「秒回」。两条路径**读的是同一个文件、同一套表**，不存在第二处真相：建表脚本只有一份（`oryxos-storage` 的 `schema.sql`），轻命令内嵌执行它、重命令由 JPA 映射它。收敛路径：若将来引入迁移工具，两条路径合并为一条。
2. **`CliApplication` 与 `OryxosApplication` 两个 `@SpringBootApplication` 并存**。理由：`oryxos-boot` 聚合 `oryxos-cli`，反向依赖成环，CLI 无法复用 `OryxosApplication`。已核实无害（[research.md](research.md) D10：`MainClassFinder` 只扫本项目自己的 `target/classes`；且偏离 ④ 之后 `oryxos-boot` 的插件显式写了 `<mainClass>`，自动探测根本不再运行）。代价是 `@SpringBootTest` 不显式指定 `classes` 会报「找到多个 `@SpringBootConfiguration`」——本节测试不用 `@SpringBootTest`，故不受影响，将来若要用须显式传 `classes`。`OryxosApplication` 自本节起不再是 fat JAR 主类，但**本节不改该类**，去留由第 26 节 `serve` 落地时定。

## 已知接缝（留给后续节）

本节刻意留白、由后续节补齐的六处：

| 留白 | 由谁补 | 本节已就位的部分 |
| --- | --- | --- |
| `ToolCatalog` 的真实实现 | 第 20 节（`oryxos-tool.ToolRegistry`） | 空实现经 `@ConditionalOnMissingBean` 声明，真实实现一落地**自动让位**，不需回头删代码 |
| `MemorySource` 的真实实现 | 第 22 节（`oryxos-memory.MemoryService`） | 同上 |
| `serve` 的 HTTP 端口与 REST 端点 | 第 26 节 | `--port` 选项已在 `--help` 中、已接受解析，只回显未接入 |
| `gateway` 真正挂载 IM 通道 | 扩展阶段 | 「模式」与「共享同一份 Profile / Session 存储」两层语义已立起来 |
| 定时任务的注册与调度 | 第 25 / 28 节 | `status` 的定时任务数一行已留位并标注 |
| 会话归档（`status` / `archived_at` 两列的使用） | 第 26 节（`DELETE /api/v1/sessions/{id}`） | 两列**已在表中建出**（A1 逐字照抄），领域类型**刻意不带**这两字段（本节无调用方） |

另有两处**非接缝**的留白，本节明确不做并已记入 [research.md](research.md)：`provider list` 的密钥就绪状态（`application.yaml` 在 `oryxos-boot`，见上文收窄表）、`tool_invocations` 的入参 / 结果脱敏（上游 data-model 提出、课件与章程均未要求，写入点只有 `ToolExecutor` 一处）。

## Constitution Check（Phase 1 设计后复核）

设计产物（`data-model.md`、`contracts/`）产出后复核 10 条原则，**维持 PASS**：

- [contracts/session-persistence.md](contracts/session-persistence.md) 把会话标识的拼接点、领域类型字段、实体字段、`messages_json` 落盘形状逐条钉死——**「标识生成只此一处」（FR-006 / SC-003）与「core 不反向依赖」（章程附加约束）两条在契约层面被固化**，后续实现无法绕过。
- [contracts/session-persistence.md](contracts/session-persistence.md) §4 明文规定 `SessionEntity` 的字段**全部是基本类型与 `String`**、不认识 core 类型——`oryxos-storage → oryxos-core` 的反向边在契约层面被禁止。
- [contracts/cli-surface.md](contracts/cli-surface.md) §1 把 12 条命令的轻 / 重归属逐条列表——原则 IX（三触发源同一链路）与 SC-001（轻命令不启动上下文）变成可断言的事实。
- [contracts/cli-surface.md](contracts/cli-surface.md) §3 的退出码表与 §4 的「未接入段必须标注」把 FR-017 / FR-016 写死，实现方无法悄悄编造数值。
- [contracts/cli-assembly.md](contracts/cli-assembly.md) §2 规定空接缝用 `@ConditionalOnMissingBean` 声明、§3 规定 `ProviderService` 只加 `implements` 不改方法体——原则 VII（接口先行、实现按档演进）在契约层面可验证。
- [contracts/cli-assembly.md](contracts/cli-assembly.md) §1 把发行入口钉死为「`oryxos-boot` 的 fat JAR，其 `<mainClass>` 是 `OryxOsCli`」——原则 I（单二进制）与偏离 ④ 在契约层面可断言：`oryxos-cli` 不得再出现 shade 插件。
- [data-model.md](data-model.md) §4 的 `sessions` 表定义逐字照 A1，不新增列、不改类型——章程附加约束「不依赖 `ddl-auto` 做建表」与原则 VI（审计地基）不冲突：会话表是**第三张表**，不替代两张审计表。
- 无新增模块、无反向依赖、无响应式代码、无自建线程池、无明文凭证、无 SecurityManager。

## Phase 0 / Phase 1 产出

- 技术决策与备选方案：[research.md](research.md)（D1~D20 + 未决项）
- 实体与字段：[data-model.md](data-model.md)
- 对外契约：[contracts/](contracts/)
- 验证指引：[quickstart.md](quickstart.md)
