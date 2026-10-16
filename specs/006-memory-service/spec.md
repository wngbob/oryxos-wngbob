# Feature Specification: Memory —— 给 Agent 装上记得住事的仓库

**Feature Directory**: `specs/006-memory-service`

**Git Branch**: `class-22`

**Created**: 2026-10-15

**Status**: Draft

**Input**: User description: "第22节需求：Memory（记忆）——一个 MemoryService 门面，背后一个可插拔的长期记忆后端接口 LongTermMemoryStore，核心阶段一次给出三档实现（Markdown 文件 / SQLite / Mem0），用配置选一个；再加会话记忆走 SQLite、两个内置 Tool 让 Agent 自己读写长期记忆"（课件：`docs/class/第22节：Memory 实现与代码讲解.md`；评审依据 `docs/class/第21节：Memory 原理解析、业界方案与 OryxOS 设计评审.md`；章节依据 `docs/TechnicalSolution.md` §5 Memory）

## Clarifications

### Session 2026-10-15

- Q: 长期记忆到底是一份全局共享的，还是每个 Agent 各自一份？ → A: **全局单份**（`.oryxos/memory/MEMORY.md` 一份，所有 Agent 共享）。core 接缝 javadoc 里「（记忆按 Agent 隔离）」那句括注与核心阶段实际形态不符，记为已知偏差（见仲裁 4）。
- Q: `memory_entries` 是本节新增的表，老工作区的库文件里没有它，怎么保证 SQLite 档跑得起来？ → A: **靠 `oryxos init` 补建**——`InitCommand` 本来就每次都重跑建表脚本（全 `CREATE TABLE IF NOT EXISTS`，天然幂等），老工作区跑一次 `oryxos init` 即补齐。存储实现**不自带建表**（见仲裁 11）。
- Q: 长期记忆到底是一份全局共享的，还是每个 Agent 各自一份？（2026-10-15 用户要求**再对照上游仓库**复核） → A: **全局单份，裁决不变**。上游同一节的交付提交里路径是构造期写死的全局常量（`oryxosRoot.resolve("memory").resolve("MEMORY.md")`，路径里无 Agent 名），门面 javadoc 自陈"当前实现按全局长期记忆"；**按 Agent 分文件是上游第 30 节**——上游 `MarkdownMemoryStore` 的 javadoc 逐字写着"Agent 专属（**30 节**）"，靠 `ToolExecutionContext.agentName()` 分派、无上下文时回退全局。详见仲裁 4。
- Q: 课件要求契约测试参数化遍历三个实现、且 Mem0 档用一个"内存假 Mem0"替身，但这个替身课件没起名，怎么落？ → A: **落成 `InMemoryMemoryStore`（与上游同名同形状）**。上游把同一处落成了一个 public 类（上游独有、课件交付物清单里没有），本仓对齐它，作为第 4 个 `LongTermMemoryStore` 实现供契约测试驱动（软门禁 ①，2026-10-15 用户已报批）。

## 范围说明

前 20 节把 Agent 的"脑子"、"嘴"和"手"都装好了：第 16 节让它会调模型，第 17 节让它会想（ReAct 循环），第 18 节让它能一问一答，第 19 节让它能把结果主动推出去，第 20 节让它真正能动手。**但它至今记不住事。** 大模型每一次调用都是无状态的，上下文窗口有限且昂贵——Demo 能跑是因为整段对话塞得进一个窗口，生产会崩是因为真实用户会跨会话、跨天、跨话题地回来。

第 21 节评审把方向定了：**接口先行、那道墙焊死、实现分阶段长大**。本节把它变成能跑的代码，并当场兑现"墙之下可以随时换实现"这句话——**一次实现三档长期记忆后端**（Markdown 文件 / SQLite / Mem0），靠一行配置切换，`MemoryService` 以上的代码（`PromptBuilder` / `MemoryTools` / `ReActLoop`）一个字不动。

三档是**递进**关系，正好把第 21 节的演进路线走一遍：Markdown 是阶段一默认（零依赖、人可读、git 可跟踪），SQLite 是记忆量变大后的结构化升级（**仍零外部依赖**，复用已有 SQLite），Mem0 是"真需要自动抽取 / 冲突消解 / 语义检索"时的自托管外部集成档。

本节同时是前 20 节埋下的三处伏笔的兑现点：第 17 节在 core 留下的 `MemorySource` 接缝（其 javadoc 明写「实现方：`oryxos-memory` 的 `MemoryService`（第 22 节）」）、第 20 节交付的注解管道与注册表（`save_memory` / `recall_memory` 经**同一套机制**自动进注册表）、第 18~20 节装配处的让位机制（`EmptyMemorySource` 经同类型 Bean 自动让位）。**这三处都不需要回头改一行代码**——这正是"接口先行"这条纪律的复利。

**本节交付物范围**（课件「本节交付物」逐项）：

- **门面**：`MemoryService` 接口 + 实现——对上（`PromptBuilder` / `MemoryTools`）只暴露"读长期记忆文本 / 记一条 / 按关键词查"三类操作
- **后端契约**：`LongTermMemoryStore`（骨架期已交付，**本节不改签名**，见仲裁 1）+ `MemoryScope` + `MemoryCapability`（均已交付）
- **三档实现**：`MarkdownMemoryStore`（默认）、`SqliteMemoryStore`、`Mem0MemoryStore`
- **测试期替身**：`InMemoryMemoryStore`——第 4 个 `LongTermMemoryStore` 实现，课件要求的"内存假 Mem0"替身（课件只描述、未起名），契约测试参数化遍历时用它驱动 Mem0 档那一行。**与上游同名同形状**（软门禁 ①，2026-10-15 用户已报批）。**不属于三档交付之一**：它不是可选后端、不进配置取值、装配处 MUST NOT 能选中它
- **内置工具**：`MemoryTools`（`save_memory` / `recall_memory`）
- **表**：`memory_entries`（SQLite 后端，手工建表脚本）
- **文件**：`.oryxos/memory/MEMORY.md`（`## 核心记忆` / `## 归档记忆` 两区块约定，`oryxos init` 已建模板）
- **配置**：`oryxos.memory.backend`（`markdown` | `sqlite` | `mem0`）；Mem0 的地址与凭证走环境变量占位
- **集成点**：`PromptBuilder` 组装 prompt 时经 `MemorySource` 取长期记忆（第 17 节已就位的调用点，本节只提供实现）
- **装配**：`CliAssemblyConfiguration` 按配置造对应的 `LongTermMemoryStore` 并注入同一个 `MemoryService`；`EmptyMemorySource` 自动让位

### 明确不做（边界）

核心阶段不碰以下各项，本节一律不做（逐项照搬课件「有几样先别做」与第 21 节评审结论）：

- **`save_memory` 的自动触发**——不自动从对话历史提炼记忆，写入时机永远由 Agent 主动判断（第 21 节第十二节的刻意选择：那个机制解决的问题「重要信息容易漏记」在核心阶段还只是推测，没有真实信号支撑）；
- **Memory Wiki 式结构化矛盾检测**（claim / evidence）；
- **记忆压缩**（归并、去重、抽象）——超长就是简单截断；
- **知识图谱后端**（第 21 节第九节：门槛比向量更高）；
- **内置向量库 / 语义检索路径**——md / sqlite 两档**不引入任何向量依赖**，`MemoryCapability.HYBRID_BUILTIN` 核心阶段**无实现方**（见仲裁 5）；
- **情景记忆**（三层里的第三层，放扩展阶段）；
- **长期记忆按 Agent 隔离**——本节是**全局单份**（见仲裁 4）。上游把它放在**第 30 节**（`ToolExecutionContext` 携带 Agent 身份后按 Agent 分文件），本仓**跨节挂账归第 30 节**；本节 MUST NOT 引入 `ToolExecutionContext`、MUST NOT 让 `MemoryTools` 去拿 Agent 身份；
- **`/api/v1/memory` 端点与管理台**——归第 26 节（本节只保证 `chat` 链路里记忆真的生效）；
- **`USER.md` 的写入路径**——`USER.md` 用户手写、OryxOS 只读，本节 MUST NOT 产生任何写它的代码路径。

## 冲突仲裁记录（本规约据此撰写，后续 plan / tasks 不得偏离）

1. **【已报批，2026-10-15 用户裁决】`LongTermMemoryStore` 保留骨架期的 5 方法形状，不裁回课件的 3 方法**——课件 §三给出的是 `append` / `load` / `recallByKeyword` **三个**方法；而骨架期已交付的接口（`oryxos-memory/store/LongTermMemoryStore.java`）是**五个**，多出 `capabilities()`（检索能力三态）与 `archivalEntries()`（归档区条目视图）。这不是本节新造：技术方案 §5.1 明写「原文自称"对外三个方法"但实列五个」，骨架 javadoc 也**主动把这个歧义推给本节**——「类型口径取最直白者，**随 US-3 细化**」，而 US-3 就是本节。裁决：**保留 5 方法，三档实现全部落地**。理由：① 改签名属软门禁 ②（修改已定字面量）；② `capabilities()` 正是"接口墙预留升级空间"的载体（CLAUDE.md 原则 9），裁掉它将来上语义检索时要再加回来（接口变更 + 三档实现全动）；③ 两个方法**都能给出真实语义**，不是空占位——`capabilities()` 区分"关键词档"与"检索外置档"，门面按它路由 `recall`。**代价如实记录**：`archivalEntries()` 在核心阶段**没有真实消费方**（它的用途是技术方案 §5.1 说的"时间新近路与索引对账"，属尚未落地的能力），本节按"接口已定、实现给出"落地并如实标注，不假装它有调用方。

2. **【已报批，2026-10-15 用户裁决】`MemoryService` 实现既有的 core 接缝，不提供课件的 `buildContext(Session)`**——课件 §三的门面签名是 `String buildContext(Session session)`（"核心记忆 + 会话历史，拼进 Prompt"），而第 17 节已交付的 core 接缝是 `MemorySource.longTermMemory(Profile) → Optional<String>`，其 javadoc 明写「**返回的是长期记忆，不是会话历史**……两者 MUST NOT 混在一起」。裁决：**`MemoryService implements MemorySource`**，提供 `longTermMemory(Profile)` / `remember(content, scope)` / `recall(keyword)`；**不提供** `buildContext(Session)`。理由：① 课件说的"核心记忆 + 会话历史拼进 Prompt"在本仓由 `PromptBuilder` 的四部分结构**已经实现**——第②段长期记忆 + 第③段会话历史，效果等价、职责更清晰；② 让记忆门面去持 `Session` 会把两件生命周期完全不同的事（跨会话的仓库 / 这一次的往来）焊在一起，与接缝 javadoc 的禁令直接冲突，且要改第 17 节交付的公共接口（软门禁 ④）。**课件的 `buildContext(Session)` 记为已知偏差**，进验收报告。

3. **【已报批，2026-10-15 用户裁决】记忆的文件 IO 不过 `Sandbox.enforce`**——第 20 节 H4 不变量①要求"涉外 IO 首行过 `Sandbox.enforce`"，但技术方案 §6.7 把沙箱的调用方**只点了三处**（`FileTools` / `ShellTools` / `HttpTools`），第 22 节课件**完全没提沙箱**。裁决：**不过沙箱**。理由：沙箱管的是「**模型可以指定目标**的工具」——`read_file` 的路径来自模型、`shell` 的命令来自模型，所以必须拦；而记忆文件的路径是**编译期固定常量**（`.oryxos/memory/MEMORY.md`），模型无法把它指向别处，不构成越界面。**连带影响如实记录**：`oryxos-memory` **不新增**对 `oryxos-tool` 的依赖边（避免引入一条前序各节都没走过的模块依赖边）。**验收口径**：本节验收报告的自查第 5 项对此条**注明为何不适用**，MUST NOT 记成"漏了校验位"。

4. **记忆是全局单份，不按 Agent 隔离**（**2026-10-15 用户已裁决**，见 `## Clarifications`）——`MemorySource.longTermMemory(Profile)` 的 `@param profile` 括注写的是「记忆按 Agent 隔离」，但课件「本节交付物」、技术方案 §5.2、CLAUDE.md「工作区目录」三处**一致地**给出**单个全局文件** `.oryxos/memory/MEMORY.md`。裁决：**按三份文档走，取全局单份**。理由：① 课件 §三的 `MemoryTools.saveMemory(content, scope)` **拿不到任何 Agent 身份**——工具执行链路上 `ToolExecutor` 只递 JSON 入参，没有 per-Agent 上下文，要做隔离得先给工具执行链路加一套跨节改造；② `oryxos init` 已按全局单份建好模板（`InitCommand` 的 `Template("memory/MEMORY.md", ...)`）。**连带记录**：`longTermMemory(Profile)` 的 `profile` 参数在本节三档实现里**都不使用**（接口签名照旧，一个字不改）；core 接缝 javadoc 里那句「（记忆按 Agent 隔离）」括注**与核心阶段实际形态不符**，记为已知偏差——修正它是改第 17 节交付物的注释，属独立收口，不折进本节。

**上游复核（2026-10-15，用户要求再对照上游仓库后追加）**——上游同一节的交付提交（`aa781d28`，`specs/006-memory-pluggable/`）三条证据一致指向"全局单份"：① `MarkdownMemoryStore` 的路径在**构造期**就 resolve 成 `oryxosRoot.resolve("memory").resolve("MEMORY.md")`，路径里没有 Agent 名；② 其 javadoc 写"长期记忆存 `.oryxos/memory/MEMORY.md` **一个** Markdown 文件"；③ `MemoryServiceImpl` 的 javadoc 自陈"session 参数保留供**未来**按 Agent/用户圈定记忆作用域……**当前实现按全局长期记忆**"。**按 Agent 分文件是上游第 30 节**——上游 `MarkdownMemoryStore` 的 javadoc 逐字写着"**Agent 专属（30 节）**：目标文件由 `ToolExecutionContext.agentName()` 决定……无上下文时回退全局"，且该回退后来又出过 bug（`fix(memory): resolve the global scope to a single MEMORY.md on the markdown tier (#863)` 引入 `MemoryEntry.GLOBAL_AGENT` 哨兵修复）。**结论：裁决 4 不变，且从"按三份文档推断"升级为"有上游同节实证"。** 本仓**不**交付按 Agent 隔离，**不**引入 `ToolExecutionContext`（那是第 30 节的概念），并在验收报告里记为跨节挂账。

5. **`HYBRID_BUILTIN` 核心阶段无实现方，作为预留取值保留**——技术方案 §5.1（015 修订）把 `capabilities()` 定义为三态，其中 `HYBRID_BUILTIN` 指"内置语义 + 关键词 + 时间新近三路加权 RRF 融合"，依赖全局 `embedding.*` 配置与 `oryxos-core/retrieval` 的 `RetrievalPipeline`（技术方案自称"014 知识库同款融合段"）。**本仓不存在 `oryxos-core/retrieval` 包，也没有 `embedding.*` 配置键**（已 grep 核实）。而第 21 节课件与 CLAUDE.md 原则 9 一致地判定"向量检索放扩展阶段、用信号判断"。裁决：**本节只落地 `KEYWORD`（Markdown / SQLite 档）与 `DELEGATED`（Mem0 档）两态**；`HYBRID_BUILTIN` 作为**已声明的取值空间**保留（枚举常量不删），核心阶段**没有任何实现方返回它**，门面对它的处理 MUST 是"明确报错/不静默降级"，MUST NOT 假装支持。**依赖缺失如实记录**：语义路的前置模块与配置键都不存在，本节 MUST NOT 为它建半成品。

6. **`memory_entries` 表要建**——技术方案 §9.2 的「核心表五张」清单里**没有** `memory_entries`，但同文件 §5.1 明写 `SqliteMemoryStore`「记忆按条入库到 `memory_entries` 表」，且课件「本节交付物」明确列了这张表。裁决：**建表**，列名与类型逐字照课件 §三给出的 `CREATE TABLE`（`id` / `scope` / `content` / `created_at`），并加 `idx_memory_scope` 索引。理由：SqliteMemoryStore 没有它无处落数据；§9.2 的"五张"是**会话与审计**口径的清单（本节新增的这张属记忆域）。**连带记录**：建表脚本是**手工维护**的 `schema.sql`（`ddl-auto=none`），与 `sessions` / 审计表同口径；**注释里 MUST NOT 含分号字符**（既有坑：脚本按分号切分执行，注释里的分号会切出碎片并使句柄失效）。

7. **`oryxos-memory` 新增对 `oryxos-storage` 的依赖边**（软门禁 ⑥ 报备项）——`SqliteMemoryStore` 需要 `memory_entries` 的 Repository，而实体与 Repository 归 `oryxos-storage`（与 `tool_invocations` / `llm_calls` / `sessions` / `notify_channels` 同口径）。方向检查：`oryxos-storage` **不依赖** `oryxos-memory`，**不成环**。`oryxos-memory` 另需 `spring-ai` 的 `@Tool` / `@ToolParam` 注解（`MemoryTools` 用）与 HTTP 客户端（`Mem0MemoryStore` 用）——两者的坐标**动手前先核实**（见 plan，第三方 API 未核实到即软门禁 ⑤）。

8. **检索大小写口径：跨档统一不区分大小写**（骨架 javadoc 已定，本规约确认）——课件 §三的 Markdown 实现示意是 `line.contains(keyword)`（**大小写敏感**），而骨架期已交付的 `recallByKeyword` javadoc 明写「跨档统一**不区分大小写**」，技术方案 §5.1 亦标「015 起不区分大小写」。裁决：**不区分大小写**（`MarkdownMemoryStore` 与 `SqliteMemoryStore` 两档行为一致）。理由：三档行为必须一致才叫"同一套契约"——Mem0 档的语义检索天然不区分大小写，另两档若敏感就会出现"换后端后检索结果变了"。**口径不可省，实现手段可调**。

9. **`EmptyMemorySource` 靠既有让位机制自动让位，本节不回头删代码**——`CliAssemblyConfiguration` 里那个空实现带 `@ConditionalOnMissingBean(MemorySource.class)`（第 18 节交付）。本节在装配处声明 `MemoryService` Bean 后它自动失效。**MUST NOT** 手工删掉它（与第 20 节 `ToolCatalog` 空实现、第 24 节 `PermissiveSandbox` 同款机制），MUST NOT 给它加 `@Primary` 之类的手工优先级。

11. **`memory_entries` 的建表落地靠 `oryxos init`，存储实现不自带建表**（**2026-10-15 用户已裁决**，见 `## Clarifications`）——`InitCommand.run()` 里 `database.runSchemaScript()` 是**无条件**执行的（`databaseExisted` 只影响输出里报"已创建"还是"已存在"，不影响是否重跑脚本），脚本内全是 `CREATE TABLE IF NOT EXISTS`，故老工作区跑一次 `oryxos init` 即补齐新表。裁决：**`SqliteMemoryStore` 不做建表**，与 `sessions` / 审计表同口径（表的存在性由工作区初始化负责）。理由：① 零代码改动，且与既有先例一致（`NotifyChannelDb` 自带建表是**特例**——那三条轻命令不启 Spring、且是 `notify_channels` 的唯一写入面，与本节情形不同）；② 引入 Spring 路径的原生 JDBC 连接会与 HikariCP `pool=1` 争同一个库文件，无谓地增加 `SQLITE_BUSY` 面。**代价如实记录**：忘了跑 `oryxos init` 的用户会撞上一条不好读的 `no such table` 报错——**缓解**：① 该报错 MUST 被包成带"请先运行 `oryxos init`"指引的明确消息（与 `NotifyChannelDb` 的既有文案同款）；② 验收向导与人工项里写明这一步。**连带记录**：默认后端是 `markdown`，SQLite 档**不在默认路径上**——这张表只在配置切到 `sqlite` 时才被触达，故这条风险的影响面有限。

12. **`MEMORY.md` 的两区块 header 字面量逐字照既有模板**——`## 核心记忆` / `## 归档记忆`（含两个半角空格与井号数量）由 `InitCommand` 的 `MEMORY_MD` 模板与 `specs/000-runtime-kernel/contracts/agent-directory.md §6` 双重钉死。本节的 `MarkdownMemoryStore` MUST 按**同一组字面量**读写，MUST NOT 自造 header 形式。**连带记录**：`oryxos init` 建的模板**只给分区标题、不写示例条目**（有意为之——凭空塞一条示例记忆等于凭空给 Agent 加一句它没说过的话），故本节 MUST NOT 往模板里加示例。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Agent 记一条、下一轮就想得起：写入即生效，核心区始终在场 (Priority: P1)

用户在一次对话里说了一句值得长期记住的话（"我在性能敏感场景偏好用 Java"）。Agent 判断该记，主动调 `save_memory` 写进长期记忆；同一轮的后续、或下一轮，`recall_memory` 立刻能查回来。用户还说了一句"以后所有回复都用中文"——Agent 把这条标成**核心记忆**，于是它此后**每次**组装上下文都在场，不需要被"检索"。

**Why this priority**: 这是本节的地基，也是"Agent 记得住事"这件事本身。没有门面与默认档后端，后面所有档都无处安放；而"记完立刻生效"（契约一）与"核心记忆始终在场"（契约二）是这一层**体感上的全部**——破了任何一条，用户看到的就是"Agent 又忘了"。它同时把第 17 节的 `MemorySource` 接缝真正跑活。

**Independent Test**: 纯单测可判定，用临时目录——① 记一条核心、一条归档，立刻取长期记忆文本，断言两条都在（契约一）；② 灌 500 条归档后再取，断言核心那条**一字不少**、最早的归档被裁掉、最近的归档还在（契约二）；③ `save_memory` 不带 scope 时落进归档区（契约三）。全程不碰网络、不碰真模型，秒级跑完。

**Acceptance Scenarios**:

1. **Given** 一个空的工作区与默认后端，**When** Agent 调 `save_memory("用户偏好 Java", "core")` 后立刻取长期记忆文本，**Then** 该条内容出现在文本里，且落在核心记忆区。
2. **Given** 核心区已有一条记忆、归档区已被灌入远超阈值的内容，**When** 取长期记忆文本，**Then** 核心区那条完整保留，只有归档区被截断。
3. **Given** Agent 调 `save_memory("随手记一句")` 未指定分区，**When** 取长期记忆文本，**Then** 该条落在归档记忆区（缺省 `archival`）。

---

### User Story 2 - 跨会话不必重复自我介绍：长期记忆自动进 prompt (Priority: P1)

用户关掉 CLI，第二天开一次**全新会话**。他没再说自己是谁、偏好什么，Agent 却已经知道——因为组装 prompt 时长期记忆被自动带上（核心记忆区完整在场）。这是"始终在场"在真实链路里的体感。

**Why this priority**: 这是长期记忆**唯一**的价值出口——存了不注入等于没存。它是第 17 节 `MemorySource` 接缝与 `PromptBuilder` 第二部分这条已铺好的路的兑现点，也是 Demo 二（每日科技日报"要体现用户之前说过的偏好"）的前置条件。

**Independent Test**: 单测可判定——用一个临时目录写一份 `MEMORY.md`，构造接缝实现后断言取到的文本含核心区内容；再把 `MEMORY.md` 置空/不存在，断言返回**空**（`PromptBuilder` 据此整条不出现，不塞占位文本）。`PromptBuilder` 已有的四部分测试与新的接缝实现**组合起来**即覆盖"两者一起注入 system prompt"。

**Acceptance Scenarios**:

1. **Given** 工作区里 `MEMORY.md` 有核心记忆内容，**When** 组装 prompt，**Then** 长期记忆作为**独立的第二部分**（第 2 条 system 消息）出现，且核心区内容完整在内。
2. **Given** 工作区里 `MEMORY.md` 为空或不存在，**When** 组装 prompt，**Then** 第二部分**整条不出现**，MUST NOT 塞占位文本、MUST NOT 报错。
3. **Given** 同一份长期记忆，**When** 换一个全新的会话（不同的会话标识）组装 prompt，**Then** 长期记忆照旧出现——它是跨会话的，与会话历史是两件事。

---

### User Story 3 - 换后端只改一行配置：三档实现同一套契约测试全过 (Priority: P1)

运维把配置从 `markdown` 改成 `sqlite`（或 `mem0`）后重启，**同一段对话、同一个体感**，只是底下换了后端——上层工具、`PromptBuilder`、ReAct 循环一个字都没改。同一套契约断言对三档**统一跑**，谁破了谁红，而且一眼能看出是哪一档破的规矩。

**Why this priority**: 这是本节**最值钱**的部分，也是第 21 节那道"接口墙"的价值兑现——"接口不变、实现随便换"从一句口号变成有自动化保障的事实。排在 P1 是因为它是本节存在的理由；不给它同等优先级，本节就退化成"写了一个 Markdown 记忆文件"。

**Independent Test**: 单测可判定——把三档实现（Mem0 档用**内存替身**）放进同一个参数化契约测试的输入源，四组断言（写后立读 / 截断只裁归档 / scope 路由 / recall 只搜归档）对每一档全过。各档另有专属测试覆盖其特有行为（字符串截断边界、`LIMIT` 生效、REST 请求体）。全程不碰网络。

**Acceptance Scenarios**:

1. **Given** 三档实现，**When** 对每一档跑同一套契约断言，**Then** 每一档都通过，**未通过的档数为 0**。
2. **Given** 配置为 `sqlite`，**When** 记一条再立刻取，**Then** 行为与 `markdown` 档**完全一致**（写后立读、核心区不截断、scope 路由、只搜归档）。
3. **Given** 配置为 `mem0`，**When** 记一条，**Then** 发出的请求体带分区信息、按 Agent 作用域组织；取回时核心区与归档区按 metadata 过滤分开取。

---

### User Story 4 - 按关键词查归档区：未命中不报错 (Priority: P2)

Agent 想不起用户以前说过什么，于是调 `recall_memory("Java")` 去翻归档区。命中了就把几条记忆念出来；没命中就老实说"没有找到相关记忆"，而不是中断这一轮对话。

**Why this priority**: 检索是长期记忆"取用"的那一半，但它的核心阶段实现刻意很弱（关键词匹配），价值不如前三条地基性——即使只交付前三者，Agent 也已经记得住、想得起（核心区永远在场）。它排 P2 的真正原因是**契约四的边界本身**："别做复杂"是这一档的纪律，把它做复杂（正则、分词、向量）才是跑偏。

**Independent Test**: 单测可判定——往归档区灌几条内容，按关键词查命中/不命中/大小写不同的关键词各断言一次；核心区的条目**永远不出现在检索结果里**（它本来就被全量注入，参与检索等于重复占用上下文）。未命中时 `recall_memory` 返回明确的"没有找到相关记忆"文案，MUST NOT 抛异常。

**Acceptance Scenarios**:

1. **Given** 归档区有"用户偏好 Java"，**When** 按关键词检索，**Then** 命中的条目被返回；关键词大小写不同也命中（口径见仲裁 8）。
2. **Given** 核心区有"用户叫小王"、归档区没有相关内容，**When** 按"小王"检索，**Then** 返回**空**——核心区不参与检索。
3. **Given** 归档区无任何匹配，**When** Agent 调 `recall_memory`，**Then** 得到"没有找到相关记忆"的明确回答，这一轮对话**不中断**。

---

### Edge Cases

- **`MEMORY.md` 不存在**（用户手工删了、或工作区是手工拼的）——当**空记忆**处理，不报错。理由：不能因为一个可选文件缺失就让 Agent 起不来。
- **`MEMORY.md` 存在但两个 header 都缺**（用户手写时改了格式）——MUST NOT 把整份文件内容当成核心记忆全量注入（那会把用户的随手笔记塞进每次上下文）；按"两个区都空"处理，并留下可诊断的痕迹。
- **归档区刚好等于阈值**——不截断（阈值是"超过才裁"，不是"达到就裁"）。
- **归档区被裁后，被裁掉的内容仍能被检索到吗**——MUST NOT。`load()` 的截断与 `recallByKeyword` 的作用范围是同一份归档数据：截断只影响"注入上下文的那一份"，检索**仍应覆盖归档区全量**（否则记忆"存了但查不到"）。**这两者的口径必须写清并测试钉死。**
- **`scope` 传了未知值**（如 `"CORE "` 带空格、`"important"`）——MUST 报错（明确告知合法取值），MUST NOT 静默写进归档区（契约三：系统不猜）。
- **同一条记忆被重复写入**——核心阶段是**纯追加**，不去重、不合并（记忆压缩属明确不做）；这是已知取舍，不是缺陷。
- **Mem0 服务不可达**——MUST 以明确错误上抛（落审计路径），MUST NOT 静默返回空（静默返回空会让 Agent 以为"记忆是空的"，进而重复询问用户）。
- **Mem0 的地址环境变量缺失**——MUST 报错且消息**只带变量名、不带值**（与既有 `ProfileLoader` / `JpaNotifyChannelRegistry` 同口径）；MUST NOT 把未解析的占位符当值发出去。
- **配置里 `memory.backend` 填了未知值**（如 `postgres`）——MUST 报错并列出合法取值，MUST NOT 静默退回默认档（静默退回会让运维以为"配置生效了"）。
- **核心区或归档区为空**——取长期记忆文本时 MUST NOT 返回只有 header 的残渣；两边都空时接缝返回**空**，让 prompt 的第二部分整条不出现。

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 MUST 提供一个**统一门面**，对上层只暴露三类操作：取长期记忆文本（跨会话的那一份）、记一条、按关键词查。上层（`PromptBuilder` / `MemoryTools`）MUST 只依赖这个门面，MUST NOT 感知底下是哪一档后端。
- **FR-002**: 长期记忆 MUST 抽成**可插拔的后端接口**（`LongTermMemoryStore`），把"长期记忆的读写契约"与"具体存哪、怎么存"解耦。换后端 MUST 只改配置，MUST NOT 改接口、MUST NOT 改调用方。
- **FR-003**: 系统 MUST 一次交付**三档后端实现**，靠配置选一个：**Markdown 档**（默认，操作 `.oryxos/memory/MEMORY.md`，按两个 header 分区）、**SQLite 档**（记忆按条入库）、**Mem0 档**（自托管外部服务，走 HTTP 集成，地址与凭证走环境变量占位）。
- **FR-004**（契约一·不缓存）: 长期记忆 MUST **每次重新读**（读文件 / 查库 / 调 API），MUST NOT 做进程内缓存。Agent 记完一条后，**同一轮或下一轮**取长期记忆文本 MUST 立刻看到它，检索 MUST 同样立刻命中。
- **FR-005**（契约二·核心区不截断）: 核心记忆区 MUST 始终完整注入；截断 MUST 只作用在归档区。任何后端的截断逻辑 MUST NOT 连累核心区——三档的实现手段各不相同（裁字符串 / 限制查询条数 / 交给后端的作用域机制），但**"核心区一字不少"这条底线三档一致**。
- **FR-006**（契约三·scope 显式指定）: 记一条时 MUST 由调用方显式指定分区（核心 / 归档），缺省 MUST 为**归档**。系统 MUST NOT 猜、MUST NOT 自动从对话里提炼。分区取值非法时 MUST 报错并告知合法取值。
- **FR-007**（契约四·检索只搜归档区）: 按关键词查 MUST 只搜**归档区**；核心区 MUST NOT 参与检索（它本来就被全量注入，参与检索等于重复占用上下文）。核心阶段的检索 MUST 是**简单匹配**，MUST NOT 引入正则、分词或向量。
- **FR-008**: 检索口径 MUST **跨档统一不区分大小写**（仲裁 8）——三档行为一致才叫"同一套契约"。
- **FR-009**: 归档区被截断 MUST NOT 影响检索范围——截断只决定"注入上下文的那一份"，检索 MUST 覆盖归档区全量（Edge Cases 第 4 条）。
- **FR-010**: 后端接口 MUST 暴露**检索能力**声明，门面 MUST 按它路由检索。核心阶段 MUST 只落地两种取值：**关键词档**（Markdown / SQLite）与**检索外置档**（Mem0，其自带检索即契约四的加强版实现）。**内置语义融合档**（`HYBRID_BUILTIN`）核心阶段 MUST 无实现方，门面对它的处理 MUST 是明确报错、MUST NOT 静默降级（仲裁 5）。
- **FR-011**: 后端接口 MUST 暴露**归档区全量条目视图**（供将来的时间新近路与索引对账取数），检索外置档 MUST 返回空。本节如实标注它**尚无消费方**（仲裁 1）。
- **FR-012**: 系统 MUST 提供两个内置工具：**记一条**（带内容与可选分区参数）与**按关键词查**。两者 MUST 经既有注册机制进注册表，与其它内置工具**一视同仁**——工具来源对注册表与过滤逻辑 MUST 零改动（第 20 节「加工具成本恒定」的第二次真实验证）。
- **FR-013**: 按关键词查**未命中**时，工具 MUST 返回明确的"没有找到相关记忆"文案，MUST NOT 抛异常、MUST NOT 让这一轮对话中断。
- **FR-014**: 长期记忆 MUST 经既有的 core 接缝被 `PromptBuilder` 取用，作为 prompt 四部分里的**第二部分**。为空时 MUST 整条不出现，MUST NOT 塞占位文本、MUST NOT 报错。
- **FR-015**: 长期记忆与会话历史 MUST NOT 由同一个方法混合返回——会话历史归 `PromptBuilder` 的会话历史段独立负责。记忆门面 MUST NOT 持有会话。
- **FR-016**: 系统 MUST 提供一个**配置项**（`oryxos.memory.backend`，取值 `markdown` | `sqlite` | `mem0`，缺省 `markdown`）决定装配哪一档后端。取值未知时 MUST 报错并列出合法取值，MUST NOT 静默退回默认档。
- **FR-017**: SQLite 档 MUST 使用一张**手工建表的记忆表**（分区 / 内容 / 创建时间三列 + 分区索引），与 `sessions` / 审计表**同口径**——MUST NOT 依赖 `ddl-auto` 自动建表。
- **FR-018**: Mem0 档的地址与凭证 MUST 走**环境变量占位**、MUST NOT 明文落库或落配置。变量缺失时 MUST 报错，且消息 MUST **只带变量名、不带值**（与既有三处 `envLookup` 同口径）。
- **FR-019**: Mem0 档的地址与凭证 MUST NOT 出现在日志、异常消息或任何 `toString` 里。
- **FR-020**: 记忆文件的读写 MUST 按**既有的两区块 header 字面量**（仲裁 10），MUST NOT 自造 header 形式；每条记忆 MUST 带日期标记。`MEMORY.md` 不存在时 MUST 当空记忆处理。
- **FR-021**: 本节的记忆文件 IO **MUST NOT** 调 `Sandbox.enforce`（已裁决，仲裁 3）；MUST NOT 因此给 `oryxos-memory` 新增对 `oryxos-tool` 的依赖边。
- **FR-022**: `USER.md` MUST 全程只读——本节 MUST NOT 产生任何写 `USER.md` 的代码路径。
- **FR-023**: 长期记忆 MUST 是**全局单份**（仲裁 4）——本节 MUST NOT 按 Agent 隔离，MUST NOT 改动 core 接缝的签名。
- **FR-024**: 三档后端 MUST 遵守**同一套行为契约**（FR-004 ~ FR-009），且 MUST 由**同一套契约测试**参数化遍历验证——任一档破契约，对应用例 MUST 变红，且**能一眼看出是哪一档**。
- **FR-025**: 本节 MUST NOT 引入任何向量检索依赖，MUST NOT 新建语义检索路径；`oryxos-core` MUST 一行不改（`MemorySource` 接缝只被实现、不被修改）。

### Key Entities

- **长期记忆后端（可插拔契约）**：写入（按分区）、载入（核心区全量 + 归档区截断后）、关键词检索、检索能力声明、归档区条目视图。三档实现同形——这是本节"接口墙"的落点。
- **记忆门面**：对上收口三类操作（取长期记忆文本 / 记一条 / 按关键词查），并实现 core 侧的长期记忆接缝。
- **记忆分区**：核心（永远全量在场、不截断、不参与检索）与归档（可截断、可被检索）。写哪个由调用方显式指定，缺省归档。
- **检索能力**：三态取值空间——关键词 / 内置语义融合（核心阶段无实现方）/ 检索外置。门面按它路由。
- **记忆条目**（SQLite 档的持久化形态）：分区、内容、创建时间；按分区建索引。
- **长期记忆文件**（Markdown 档的持久化形态）：两个一级分区的 Markdown 文件，每条带日期。
- **内置记忆工具**：记一条（内容 + 可选分区）、按关键词查（关键词）。两者经既有注册机制进注册表。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 记一条之后，**同一轮或下一轮**取长期记忆文本与按关键词查**都能立刻看到它**，需要重启或等待后台任务才能看到的情况**次数为 0**（契约一）。
- **SC-002**: 归档区积累任意多条内容后，核心记忆在每次组装上下文时**丢失字数为 0**（契约二）。
- **SC-003**: 同一套契约断言对三档后端**全部通过**，**未通过的档数为 0**；任取一档单独破坏其截断或缓存行为，对应参数**立刻变红且能定位到是哪一档**。
- **SC-004**: 把配置从一档切到另一档，`MemoryTools` / `PromptBuilder` / 引擎侧代码的**改动行数为 0**。
- **SC-005**: 核心记忆出现在关键词检索结果里的**次数为 0**（核心区不参与检索）。
- **SC-006**: 按关键词查未命中时，抛出的**异常数为 0**，返回的是明确文案。
- **SC-007**: 长期记忆为空或文件不存在时，prompt 里出现的**占位文本条数为 0**，抛出的**异常数为 0**。
- **SC-008**: 新加的两个内置工具，为「安全校验位 / 审计落库 / 按 Agent 过滤」三处所做的**代码改动行数为 0**（第 20 节「加工具成本恒定」的复验）。
- **SC-009**: 单测全程**不访问外网**（对外请求数 0），不依赖真模型、不依赖真 Mem0 服务，`mvn test` 全绿。
- **SC-010**: 记忆相关的凭证与地址在日志 / 异常消息 / 任何 `toString` 里出现的**次数为 0**。
- **SC-011**: 写 `USER.md` 的代码路径**条数为 0**。

## Assumptions

- **依赖第 16 节** Provider 与 Function Calling 适配、**第 17 节** ReAct 引擎 / `PromptBuilder` / `MemorySource` 接缝 / `ToolExecutor` 与审计表、**第 18 节** CLI 与工作区初始化（`oryxos init` 已建 `memory/MEMORY.md` 模板与 `memory/` 目录）与装配处的让位机制、**第 20 节** 注解管道 / 注册表 / 内置工具机制 / 沙箱接口与临时装配。以上均已交付，开工前已逐项 grep 核实存在。
- **骨架期已交付、本节不改签名**：`LongTermMemoryStore`（5 方法）、`MemoryScope`、`MemoryCapability`（三态）。本节只提供实现类。
- **外部依赖**：Mem0 是**自托管**外部服务，本节只做协议集成与替身驱动的测试，**不真连**（真连属人工项，见课件「五、做完怎么验」）。HTTP 客户端复用既有依赖，动手前核实。
- **四处「课件与技术方案都没给数」的欠定项，已由上游同节（`aa781d28`）定死三处，第四处是与上游的有意分歧**（一并进第 5 步固定停点的确认清单）：
  1. **归档区截断阈值（Markdown 档）= 4000 字符**——课件 §三明写 `MAX_ARCHIVE_CHARS = 4000`；**上游同节取值相同**，已由上游实证。口径（阈值只管归档区、超出即裁头保留尾）不可省，**数值可调**；
  2. **归档区注入条数上限（SQLite 档）= 最近 100 条**——课件 §三明写 `MAX_ARCHIVE_ROWS = 100`；**上游同节取值相同**。同样**数值可调**；
  3. **每条记忆的日期标记形式 = `- [YYYY-MM-DD] 内容`**——课件 §三给出；**上游同节逐字相同**（`"- [" + LocalDate.now() + "] " + content`，**只到日、无时分秒**）。上游后来在第 30 节那版把时间加上了，**本节 MUST 按第 22 节的形状**（只到日）；
  4. **`memory.backend` 的配置键名 = `oryxos.memory.backend`**——**这是本节与上游的唯一有意分歧**。课件写的是 `memory.backend`（裸键），上游同节也落的裸键 `memory.backend`；但本仓既有配置键**一律带 `oryxos.` 前缀**（`application.yaml` 有 `oryxos:` 根块，代码侧唯一先例是 `oryxos.search.tavily-api-key`）。裁决：**取带前缀**，与本仓口径一致；**键名可调，前缀口径不可省**。**连带记录**：Mem0 档的两个子键同口径处理为 `oryxos.memory.mem0.base-url` / `oryxos.memory.mem0.user-id`（上游为 `memory.mem0.base-url` / `memory.mem0.user-id`，环境变量占位分别是 `${MEM0_BASE_URL:}` / `${MEM0_USER_ID:oryxos}`——**环境变量名不带前缀，与上游一致**，因为那是外部系统的既有命名）。
- **本节挂的沙箱仍是临时实现**：第 20~23 节之间白名单校验**不生效**（第 20 节仲裁 1）。本节按裁决 3 根本不调 `Sandbox`，故与这个窗口期无交互——但**验收 MUST NOT 把这个窗口期说成"沙箱已就位"**。
- **本节的演示形态**：`oryxos tool list` 的默认输出从 13 个工具变成 **15 个**（新增两个记忆工具，`builtin (15)`）；一次 `chat` 会话里 Agent 主动调 `save_memory`、开新会话后长期记忆出现在 prompt 里。三档切换（`markdown` ↔ `sqlite`）各跑一次对话是**人工项**，Mem0 真连是**可选的**人工项。
- **`memory_entries` 表属本节新增**：`.oryxos/oryxos.db` 是跨升级保留的用户数据，老工作区的库文件里**没有这张表**——建表脚本 MUST 幂等（`CREATE TABLE IF NOT EXISTS`），且 MUST 有可执行的落地路径（`oryxos init` 内嵌执行或手工执行，见 plan）。
- **契约测试里的 Mem0 档用替身**：契约测的是"这一档有没有守规矩"，真实 REST 交互由该档的专属测试用 mock 单独验证——两者**分工明确、不互相替代**。
- **上游同节对照结论（2026-10-15，`aa781d28` = `specs/006-memory-pluggable/`）**——三处**照上游**：`MemoryEntry` / `MemoryEntryRepository` 落 `oryxos-storage`（印证仲裁 7）、`memory_entries` 的 DDL 逐字相同（印证仲裁 6）、`saveMemory` 的 scope 缺省 `ARCHIVAL` 且**非法值返回点名文案而非抛异常**（与 FR-006 的"报错并告知合法取值"一致，只是载体是工具返回值）。四处**有意不照上游**：① 配置键带 `oryxos.` 前缀（见上条第 4 点）；② 上游同节**漏做了课件要求的 `SqliteMemoryStoreTest`**（上游到更晚才有），本仓**按课件补上**；③ 上游的 `InMemoryMemoryStore` 是**普通类**、本仓按裁决同样落成 public 类（已报批）；④ 上游 core 侧是 `MemoryService.buildContext(Session)`，**上游全仓从未有过 `MemorySource`**——本仓的 `MemorySource` 是本仓骨架自造的接缝，仲裁 2「实现既有接缝」继续有效，不因上游而改。
