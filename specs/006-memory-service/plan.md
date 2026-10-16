# Implementation Plan: Memory —— 给 Agent 装上记得住事的仓库

**Branch**: `class-22` | **Date**: 2026-10-15 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/006-memory-service/spec.md`

**Note**: 本文件是 `/speckit-plan` 的输出。技术口径以本文件为准，**冲突时以 `spec.md` 的「冲突仲裁记录」为准**（11 条，其中 6 条是 2026-10-15 用户已报批的裁决）。

---

## Summary

第 21 节评审把方向定成「接口先行、那道墙焊死、实现分阶段长大」。本节把它变成能跑的代码，并**当场兑现"墙之下可以随时换实现"**。

一句话：**一个门面（`MemoryService implements MemorySource`）+ 一个可插拔后端契约（`LongTermMemoryStore`，骨架期已交付的 5 方法）+ 三档实现（Markdown 默认 / SQLite / Mem0）+ 两个内置工具（`save_memory` / `recall_memory`）+ 一张新表（`memory_entries`）+ 一个配置键（`oryxos.memory.backend`）**。

三档共守四条行为契约（不缓存 / 核心区不截断 / scope 由调用方显式指定 / 检索只搜归档区），由**同一套参数化契约测试**统一验证——任一档破契约，对应那一行参数立刻变红且能定位到档位。

**本节是"接口先行"纪律的第三次复利兑现**：`oryxos-core` **一行不改**。第 17 节留的 `MemorySource` 接缝只被实现、第 18~20 节装配处的 `EmptyMemorySource` 靠 `@ConditionalOnMissingBean` 自动让位、第 20 节的注解管道与注册表原样收下两个新工具。

---

## Technical Context

**Language/Version**: Java 21（父 pom `maven.compiler.release=21`；**避开 P3C/PMD 6.55 解析不了的 Java 18+ 语法形态**——全仓 main 源码至今只有一个经典冒号 `switch`，零箭头 switch / 零 switch 表达式，本节保持这个保守度）

**Primary Dependencies**（全部已在本仓锁定 BOM 内，`mvn dependency:tree` 实测核实，见 [research.md](./research.md) §1）：
- `org.springframework.ai:spring-ai-model:1.1.8` —— `@Tool` / `@ToolParam` 注解（`MemoryTools` 用；`oryxos-tool` 已验证过的同一条坐标）
- `org.springframework:spring-web:6.2.19` —— `RestClient`（`Mem0MemoryStore` 的 HTTP 出口；`WebhookNotifyAdapter` 已验证过的同一条坐标）
- `com.fasterxml.jackson.core:jackson-databind:2.21.4` —— Mem0 响应体解析。**本是传递边**（`spring-ai-model` → `com.github.victools:jsonschema-module-jackson`），本节**显式化**：与 `oryxos-cli` 显式声明 `jackson-databind`、`oryxos-tool` 显式化 `spring-web` / `oryxos-storage` 同一条纪律（"直接 import 的类型就该有直接的依赖边"，发行物不新增工件，版本由 Spring Boot BOM 管）
- `io.oryxlabs:oryxos-storage` —— `memory_entries` 的实体与 Repository（**新增依赖边**，仲裁 7）
- `org.springframework.boot:spring-boot-starter-test`（test scope）—— JUnit 5 / AssertJ / `spring-test` 的 `MockRestServiceServer`
- **不新增任何第三方依赖**：`MockRestServiceServer` 属 `spring-test`，随 `spring-boot-starter-test` 而来；`sqlite-jdbc` / `hibernate-community-dialects` / `spring-data-jpa` 经 `oryxos-storage` 传递（均为 compile scope），测试期可用
- **不引入**：任何向量库、任何 HTTP mock server（`mockwebserver3`）、任何新的 MCP 依赖

**Storage**:
- Markdown 档：`.oryxos/memory/MEMORY.md` 一个文件，`## 核心记忆` / `## 归档记忆` 两区块（字面量逐字照 `InitCommand.MEMORY_MD` 与 `contracts/agent-directory.md §6`，仲裁 12）
- SQLite 档：`.oryxos/oryxos.db` 的 `memory_entries` 表（手工建表脚本 `oryxos-storage/src/main/resources/db/schema.sql`，`ddl-auto=none`）
- Mem0 档：自托管外部服务，**本节只做协议集成，不真连**（真连是人工项）

**Testing**: JUnit 5 + AssertJ + `@TempDir` + `MockRestServiceServer`（Mem0 档）+ `@DataJpaTest` 打真 SQLite 文件（SQLite 档）。**全程不访问外网**（SC-009）。单测默认跑，无 `@Tag("integration")` 冒烟项（本节无真外部依赖）

**Target Platform**: Linux 服务器（开发机为 WSL2）；单 fat JAR（`oryxos-boot/target/oryxos.jar`）

**Project Type**: Maven 多模块库 + CLI（核心阶段 9 模块，**本节不新增模块**）

**Performance Goals**:
- **零缓存**（FR-004）——这是本节最容易被"优化"坏的一条：任何进程内缓存都会让"记完立刻生效"消失
- Markdown 档 `load()` 一次 `Files.readString` + 两段字符串提取；SQLite 档 `load()` 两条查询；Mem0 档两次 HTTP
- 归档区上限：Markdown 4000 字符、SQLite 最近 100 条、Mem0 交给其作用域机制（三档数值可调，口径不可省）

**Constraints**:
- **`oryxos-core` 一行不改**（FR-025）——`MemorySource` 接缝只被实现、不被修改，签名与正文逐字不动
- 记忆文件 IO **不过 `Sandbox.enforce`**（仲裁 3），且**不因此新增对 `oryxos-tool` 的依赖边**
- 记忆是**全局单份**（仲裁 4 + 上游同节实证），MUST NOT 按 Agent 隔离、MUST NOT 引入 `ToolExecutionContext`
- `USER.md` **全程只读**（FR-022）
- Mem0 的地址与凭证**只走环境变量**，MUST NOT 进日志 / 异常消息 / `toString`（FR-018/019）
- 静态检查是构建门禁：Spotless（google-java-format）+ 阿里 P3C（PMD 6.55）+ Checkstyle + SpotBugs/FindSecBugs + OWASP

**Scale/Scope**:
- **新增主源码 10 个类**（3 档实现 + 1 个测试替身 + 门面接口 + 门面实现 + `MemoryTools` + 1 实体 + 1 Repository + 1 测试启动类）、**新增测试 7 个类**
- 触碰前序节文件 **4 个**：`schema.sql`（加表）、`oryxos-memory/pom.xml`（加依赖边）、`CliAssemblyConfiguration.java`（加 2 个 `@Bean` + `toolRegistry` 参数与一行注册）、`application.yaml`（加 1 个配置块）
- 配置键 1 个（`oryxos.memory.backend`）+ 2 个子键（`oryxos.memory.mem0.base-url` / `-user-id`）
- 新表 1 张（`memory_entries`）+ 1 个索引（`idx_memory_scope`）

---

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| # | 原则 | 本节判定 | 证据 / 说明 |
| --- | --- | --- | --- |
| I | JDK 21 + Spring Boot 3.x 单体，Maven 多模块（核心阶段 9 个），单二进制部署 | ✅ | 不新增模块；`release=21`；发行物仍是 `oryxos-boot/target/oryxos.jar` |
| II | 五大核心能力优先，企业级治理层放扩展阶段 | ✅ | 本节正是能力三（Memory）本体；不做 `/api/v1/memory`、不做管理台（归第 26 节） |
| III | 自实现 ReAct loop；Spring AI 只用一半（Provider + 协议转换 + `@Tool` schema 生成） | ✅ | 只借 `@Tool` / `@ToolParam` 注解生成 schema；`AnnotatedToolAdapter` 是自己的管道（第 20 节），零改动复用 |
| IV | `AGENT.md` 不是 Tool，归 `ContextLoader` | ✅ | 不触碰 `AgentLoader` / `ContextLoader`；`MEMORY.md` 也不是 Tool，是 `LongTermMemoryStore` 的数据文件 |
| V | Tool 统一抽象 / 单模块 | ✅ | `MemoryTools` 是普通 `@Tool` Bean，落 `oryxos-memory`（**不是**新模块，也不是新 Tool 抽象）；经既有 `ToolRegistry.registerAnnotated` 进注册表 |
| VI | 审计表 Day One 写入 | ✅ | 两个新工具走既有 `ToolExecutor` → `tool_invocations` 路径；**非法 scope 抛异常**正是为了让失败**落审计**（见 D4） |
| VII | 接口先行；沙箱 = `Sandbox` + `WhitelistSandbox` | ✅ | 门面与后端契约都在实现之前定死（骨架期已交付 5 方法）；本节**不调** `Sandbox`（仲裁 3，记忆路径是编译期常量，模型无法影响） |
| VIII | SQLite + JPA，状态外置，不落明文凭证 | ✅ | `memory_entries` 手工建表脚本；Mem0 地址/凭证走 `${ENV_VAR}`，缺失时**只报变量名**（FR-018） |
| IX | 三触发源共享同一条 `AgentService` 链路 | ✅ | 门面只实现 core 接缝，链路无感；`AgentScheduler`（第 25 节）接入时零改动 |
| X | 每个 user story 有可演示 Demo | ✅ | 演示形态见 spec「本节的演示形态」：`tool list` 从 `builtin (13)` 变 `builtin (15)`；一次 `chat` 里 Agent 主动 `save_memory`，新会话 system prompt 带上长期记忆 |

**Gate 结论：10/10 通过，无未论证的违规。** 唯一的 Complexity Tracking 项是新增一条模块依赖边（`oryxos-memory → oryxos-storage`），理由与方向检查见下。

---

## 需要说明的设计偏离

> 每条都写明**依据**与**代价**。带【已报批】的是 2026-10-15 用户已裁决的项（软门禁 ①~⑥ 走过报批）。

### D1 【已报批】`MemoryService` 落 `oryxos-memory`，不落 `oryxos-core`

- **课件 / 上游**：课件把它当门面概念讲（不点模块）；上游把接口放 `io.oryxos.core.memory.MemoryService`。
- **本仓**：接口与实现都落 `oryxos-memory`（`io.oryxlabs.oryxos.memory.MemoryService` + `MemoryServiceImpl`）。
- **依据**：本仓的 core 侧接缝是**骨架期自造的** `MemorySource`（上游全仓从未有过这个名字），且 FR-025 明令 `oryxos-core` 一行不改。把门面放 core 就得改 core，与 FR-025 直接冲突。
- **代价**：本仓的 core 只看得见"读长期记忆"这一半（`MemorySource`），"记一条 / 按关键词查"两个方法 core 看不见——**这正是想要的**：`PromptBuilder` 本来只需要读，多给它两个方法等于给它越界的能力。

### D2 【已报批】`LongTermMemoryStore` 保留骨架期的 5 方法，不裁回课件的 3 方法

见 spec 仲裁 1。**代价如实记录**：`archivalEntries()` 在核心阶段**没有真实消费方**（它的用途是技术方案 §5.1 的"时间新近路与索引对账"，属尚未落地的能力），本节按"接口已定、实现给出"落地并如实标注。

### D3 `MemoryTools` 的两个 `@Tool` 方法返回 `ToolResult`，不返回 `String`

- **课件 / 上游**：返回 `String`（`"已记住"` / 拼接后的命中行）。
- **本仓**：返回 `ToolResult`。
- **依据**：① 本仓**全部** 13 个既有内置工具返回 `ToolResult`（`NotifyTools` / `FileTools` / `ShellTools` / `HttpTools` / `InteractionTools` / `WebSearchTools`），两个新工具必须同形，否则 `oryxos tool list` 之后的契约面出现两种返回约定；② `AnnotatedToolAdapter.toResult` 对 `String` 走 `ToolResult.success(String.valueOf(...))`——`retryable` 恒为默认值，**这是第 20 节自造适配器要解决的问题本身**（其 javadoc 逐字写了这一点）。
- **代价**：与课件骨架不同形（但语义等价：`ToolResult.success("已记住")` 的内容就是 `"已记住"`）。FR-013 的"未命中返回明确文案"落成 `ToolResult.success("没有找到相关记忆")`。

### D4 `save_memory` 收到非法 scope 时**抛异常**，不返回错误文案

- **课件 / 上游**：`MemoryScope.valueOf(scope.toUpperCase())`（课件，直接抛 `IllegalArgumentException`）；上游**catch 住**并返回 `"无法识别的记忆分区: ..."`。
- **本仓**：**抛 `IllegalArgumentException`**，消息点名合法取值。
- **依据**：① FR-006 的用词是「MUST **报错**并告知合法取值」；② `ToolExecutor` 的失败路径逐字写着「不打断循环：转成失败结果返回，原因**进审计、进日志、也回灌给模型**」——**抛异常不是"中断对话"，而是把这条调用记成失败并让模型看见原因**，模型下一轮可以自己改对；③ 若返回错误文案，这条调用在 `tool_invocations` 里被记成 **`success=true`**，审计说谎（章程原则 VI）；④ 本仓既有工具一律"失败上抛、不自己 catch"（`NotifyTools` javadoc 明写这条）。
- **代价**：与上游的载体不同（上游是工具返回值、本仓是异常），spec Assumptions 已把上游形态记为"一致但载体不同"，此处选定本仓载体。

### D5 `scope` 参数带 `@ToolParam(required = false)`

- **课件 / 上游**：`@ToolParam("core 或 archival，不确定就填 archival") String scope`——**未声明可选**。
- **本仓**：`@ToolParam(description = "...", required = false)`。
- **依据**：第 20 节反编译 Spring AI 1.1.8 字节码核实过——`JsonSchemaGenerator.isMethodParameterRequired` 对**无 `@ToolParam` / `@JsonProperty` / `@Schema` / `@Nullable` 注解的参数一律返回 true**，`ToolParam.required()` 的 `AnnotationDefault` 同样是 `true`。不声明 `required = false`，`scope` 会被列进 JSON Schema 的 `required`，FR-006 的「缺省归档」在**模型可见层**不成立（模型会以为必须传）。这是第 19 节 `notify` 的 `channel` 参数踩过的同一个坑。
- **代价**：无（一行注解）。

### D6 三档 `load()` 在**两个区都空**时返回空字符串，不返回只有 header 的残渣

- **课件 / 上游**：上游 `MarkdownMemoryStore.load()` 无条件返回 `CORE_HEADER + "\n" + core + "\n" + ARCHIVE_HEADER + "\n" + archive`——两个区都空时返回 `"## 核心记忆\n\n## 归档记忆\n"`。这串**不是空白**，`PromptBuilder` 的 `.filter(memory -> !memory.isBlank())` 拦不住它，于是每次组装 prompt 都会塞进去两行**没有任何内容的分区标题**。
- **本仓**：`load()` 在两个区都空时返回 `""`。
- **依据**：spec Edge Cases 最后一条（「核心区或归档区为空——取长期记忆文本时 MUST NOT 返回只有 header 的残渣；两边都空时接缝返回**空**」）+ FR-014（「为空时 MUST 整条不出现，MUST NOT 塞占位文本」）+ SC-007（占位文本条数为 0）。
- **代价**：与上游同节**有意分歧**（上游那一版在这里有缺陷）。三档**统一**遵守，故它是契约测试的一条断言，不是某一档的特例。

### D7 三档 `load()` 统一带两个 header

- **课件**：Markdown 档与 SQLite 档的示意带 header；**Mem0 档的示意是 `core + "\n" + archive`（不带）**。
- **本仓**：三档都带 `## 核心记忆` / `## 归档记忆`。
- **依据**：契约测试要"同一套断言对三档统一跑"（FR-024），`load()` 的形状不一致就无法统一断言；且两个 header 正是"核心 / 归档"这层语义对模型的表达——`PromptBuilder` 把 `load()` 的返回值整段作为 prompt 第二部分，模型需要知道哪段是"始终在场"的、哪段是可被截断的。
- **代价**：Mem0 档与课件示意不同形（语义更完整）。

### D8 【已报批】配置键带 `oryxos.` 前缀

`oryxos.memory.backend`（课件与上游都是裸键 `memory.backend`）。**这是本节与上游的唯一有意分歧**，见 spec Assumptions 欠定项第 4 条。环境变量名**不带前缀**（`MEM0_BASE_URL` / `MEM0_USER_ID`），因为那是外部系统的既有命名。

### D9 `Mem0MemoryStore` 两个构造器：公开的地址版 + 包级私有的 `RestClient` 版

- `public Mem0MemoryStore(String baseUrl, String userId)` —— 生产路径，**自己**用 `JdkClientHttpRequestFactory` 造一个带连接/读超时的 `RestClient`。
- `Mem0MemoryStore(RestClient restClient, String userId)` —— **包级私有**，测试接缝，让 `Mem0MemoryStoreTest` 能把 `MockRestServiceServer` 绑到 builder 上。
- **依据**：① 超时责任**在实现内**、不推给装配方——`WebhookNotifyAdapter` 的 javadoc 明写「装配方恰恰是最容易漏掉这个约束的地方」，本节沿用同款（同一个 `DEFAULT_TIMEOUT` 形状）；② 测试要用 `MockRestServiceServer.bindTo(RestClient.Builder)`，而 `bindTo` 与 `.build()` 必须是**同一个 builder 实例**——若实现拿到 builder 再自己 `requestFactory(...)`，会把 mock server 装的请求工厂**覆盖掉**，mock 静默失效。故测试接缝收在**已完成**的 `RestClient` 上。
- **代价如实记录**：生产构造器的超时上限**没有测试覆盖**（`MockRestServiceServer` 替换掉的正是被断言的那个部件）。这与 `WebhookWeightAdapter` 既有情形相同，进验收报告的剩余项清单，**不假装测过**。

### D10 检索大小写不敏感，三档一致

见 spec 仲裁 8。**上游的 `InMemoryMemoryStore` 是大小写敏感的**（`line.contains(keyword)`），本仓的替身按仲裁 8 落成不敏感——**这是本仓有意不照上游的一处**。

三档的实现手段：Markdown 档与替身用 `toLowerCase(Locale.ROOT)` 后 `contains`；SQLite 档用 **`LOWER(m.content) LIKE LOWER(:pattern)`**——上游写的是裸 `LIKE`，靠 SQLite "LIKE 对 ASCII 默认不区分大小写"这个**驱动默认行为**，本仓显式 `LOWER()` 两侧，把 FR-008 做成实现层的确定性保证而不是一条依赖驱动默认值的巧合。三档各有一条断言钉死。

### D11 【已报批】`InMemoryMemoryStore` 落成 public 类

软门禁 ①（本节交付物清单之外的新 public 概念），2026-10-15 用户已报批。**与上游同名同形状**。它不是第四档可选后端：不进 `oryxos.memory.backend` 取值、装配处 MUST NOT 能选中它，唯一身份是契约测试的参数化替身。

### D12 补做 `SqliteMemoryStoreTest`

课件「验收 harness」明确要求这个测试类；**上游同节漏做了**（上游到更晚的提交才有）。本仓**按课件补上**。

### D13 `MemorySource` 接缝 javadoc 里那句「（记忆按 Agent 隔离）」**保持不动**

- 该括注与核心阶段的实际形态不符（本节是全局单份，仲裁 4）。**修正它 = 改第 17 节交付的公共文件**，属独立收口，不折进本节（spec 仲裁 4 的连带记录已定这条）。
- **代价如实记录**：一句**当下失效**的注释留在 core 里，直到第 30 节按 Agent 隔离落地才变成真的。进验收报告的「已知接缝」与给 reviewer 的导读。

---

## Project Structure

### Documentation (this feature)

```text
specs/006-memory-service/
├── plan.md              # 本文件（/speckit-plan 输出）
├── research.md          # Phase 0 输出：依赖核实、API 核实、设计决策与备选
├── data-model.md        # Phase 1 输出：表 / 实体 / 枚举 / 文件形状 / 配置键
├── quickstart.md        # Phase 1 输出：可执行的验证向导
├── contracts/           # Phase 1 输出
│   ├── memory-store.md      # LongTermMemoryStore 5 方法契约 + 三档行为对照表
│   ├── memory-service.md    # 门面契约 + 两个工具的对外面（名字 / 入参 Schema / 返回文案）
│   └── memory-config.md     # 配置键 / 环境变量 / 装配规则 / 报错口径
├── checklists/
│   └── requirements.md  # /speckit-specify 输出，已 16/16
├── spec.md
└── tasks.md             # Phase 2 输出（/speckit-tasks，本文件不产）
```

### Source Code (repository root)

```text
oryxos-memory/
├── pom.xml                                          # [改] 加 4 条依赖：oryxos-storage / spring-ai-model / spring-web / jackson-databind（+ test 的 spring-boot-starter-test）
└── src/
    ├── main/java/io/oryxlabs/oryxos/memory/
    │   ├── MemoryModule.java                        # [不动] 骨架占位类（与 oryxos-tool 的 ToolModule 同款保留）
    │   ├── MemoryService.java                       # [新] 门面接口，extends MemorySource；remember / recall
    │   ├── MemoryServiceImpl.java                   # [新] 唯一实现；持有 LongTermMemoryStore，按 capabilities() 路由 recall
    │   ├── builtin/
    │   │   └── MemoryTools.java                     # [新] @Tool save_memory / recall_memory（与上游同包同名）
    │   └── store/
    │       ├── LongTermMemoryStore.java             # [不动] 骨架期契约，5 方法，FR-025 只被实现不被修改
    │       ├── MemoryScope.java                     # [不动] CORE / ARCHIVAL
    │       ├── MemoryCapability.java                # [不动] KEYWORD / HYBRID_BUILTIN / DELEGATED
    │       ├── MarkdownMemoryStore.java             # [新] 档一（默认）：MEMORY.md，两区块，裁字符串
    │       ├── SqliteMemoryStore.java               # [新] 档二：memory_entries，LIMIT 100
    │       ├── Mem0MemoryStore.java                 # [新] 档三：自托管 Mem0 REST（双构造器，见 D9）
    │       └── InMemoryMemoryStore.java             # [新] 测试期替身（D11）；装配处 MUST NOT 能选中
    └── test/java/io/oryxlabs/oryxos/memory/
        ├── MemoryTestApplication.java               # [新] @DataJpaTest 的 @SpringBootConfiguration 落脚点（照 StorageTestApplication）
        ├── MemoryServiceImplTest.java               # [新] 门面语义 + 空记忆返回空 + HYBRID_BUILTIN 报错
        ├── builtin/MemoryToolsTest.java             # [新] scope 缺省归档 / 非法 scope 报错 / 未命中文案
        └── store/
            ├── MemoryStoreContractTest.java         # [新] ★参数化遍历三档，两条关键回归点在此
            ├── MarkdownMemoryStoreTest.java         # [新] 截断边界 / header 解析 / 文件缺失
            ├── SqliteMemoryStoreTest.java           # [新] 手工脚本能建能读 / LIMIT 生效 / LIKE 检索（D12）
            └── Mem0MemoryStoreTest.java             # [新] MockRestServiceServer：请求体带 scope / recall 转发查询

oryxos-storage/
├── src/main/resources/db/schema.sql                 # [改] 追加 memory_entries 表 + idx_memory_scope 索引
├── src/main/java/io/oryxlabs/oryxos/storage/
│   ├── entity/MemoryEntry.java                      # [新] @Entity，表 memory_entries（与上游同名）
│   └── repository/MemoryEntryRepository.java        # [新] 3 个派生/JPQL 查询（与上游同签名）
└── src/test/java/io/oryxlabs/oryxos/storage/repository/
    └── MemoryEntryRepositoryTest.java               # [新] 手工脚本建表核对 + 三个查询的语义

oryxos-cli/
├── src/main/java/io/oryxlabs/oryxos/cli/
│   └── CliAssemblyConfiguration.java                # [改] 新增 memoryService() / memoryTools() 两个 @Bean；toolRegistry() 加一个参数 + 一行注册
└── src/test/java/io/oryxlabs/oryxos/cli/
    └── MemoryAssemblyTest.java                      # [新] 起真上下文：让位机制生效、三档取值、未知取值报错、HYBRID 无实现方

oryxos-boot/
└── src/main/resources/application.yaml              # [改] 加 oryxos.memory 配置块（backend + mem0 两子键）

oryxos-core/                                         # [一行不改] FR-025 / D1 / D13
oryxos-tool/                                         # [一行不改] 只被消费：ToolRegistry.registerAnnotated 与 AnnotatedToolAdapter 原样复用
```

**Structure Decision**：

1. **全部落 `oryxos-memory`，只有三处例外**——`MemoryEntry` / `MemoryEntryRepository` 落 `oryxos-storage`（与 `sessions` / 审计表 / `notify_channels` 同口径，仲裁 7）；装配落 `oryxos-cli`（`@Bean` 的唯一落点，第 18~20 节既定）；配置落 `oryxos-boot`（`application.yaml` 的唯一落点）。
2. **包结构照 `oryxos-tool` 的分法**：`store/`（可插拔后端，与骨架期已交付的契约同包）+ `builtin/`（内置工具，与上游同包同名）；门面 `MemoryService` / `MemoryServiceImpl` 放模块顶层——它是本模块**对上的主类型**，不该埋在子包里。
3. **`MemoryModule.java` 保留**：`oryxos-tool` 的 `ToolModule.java` 在第 20 节交付完整工具体系后**仍然在**，同款保留，不删（删它是无谓的前序节改动）。
4. **测试落位**：`MemoryStoreContractTest` 落 `store/`（它参数化遍历的正是那个包里的四个实现）；`MemoryToolsTest` 落 `builtin/`（与 `NotifyToolsTest` 落 `notify/` 同款）。
5. **`MemoryAssemblyTest` 落 `oryxos-cli` 而非 `oryxos-memory`**：装配后的真实上下文只有 `oryxos-cli` 看得见（`OryxToolContractTest` 的 javadoc 已定这条口径）。

---

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
| --- | --- | --- |
| `oryxos-memory` 新增一条对 `oryxos-storage` 的依赖边 | `SqliteMemoryStore` 需要 `memory_entries` 的 `MemoryEntryRepository`，而实体与 Repository 按本仓既定口径归 `oryxos-storage`（仲裁 7） | 把实体与 Repository 放 `oryxos-memory` 会打破「持久化统一归 `oryxos-storage`」的既有分法，让记忆域自成一套 JPA 装配；把 SQLite 档改成原生 JDBC 会与 HikariCP `pool=1` 争同一个库文件，无谓增加 `SQLITE_BUSY` 面。**方向检查**：`oryxos-storage` **不依赖** `oryxos-memory`，**不成环** |
| `jackson-databind` 显式化（本是传递边） | `Mem0MemoryStore` 直接 `import` `ObjectMapper` / `JsonNode` 解析 Mem0 响应 | 靠传递边拿 Jackson：`spring-ai-model` → `victools` → `jackson-databind` 这条链**不是契约**，上游换个依赖就断。本仓已有先例（`oryxos-cli` 显式声明 `jackson-databind`，注释写明「直接 import 的类型就该有直接的依赖边」）。**发行物不新增任何工件**（那条边本来就在） |

**无其他复杂度**：不新增模块、不新增第三方依赖、不新增对外概念（`InMemoryMemoryStore` 已报批）、不改任何前序节的公共接口。

---

## 已知接缝（留给后续节）

| 接缝 | 归属 | 本节的处理 |
| --- | --- | --- |
| **长期记忆按 Agent 隔离** | **第 30 节** | 本节全局单份（仲裁 4 + 上游同节实证）；MUST NOT 引入 `ToolExecutionContext`；`longTermMemory(Profile)` 的 `profile` 参数**三档都不使用** |
| `MemorySource` javadoc 的「（记忆按 Agent 隔离）」括注 | **第 30 节**（或独立收口） | 本节**不改**（D13），如实记为当下失效的注释 |
| `archivalEntries()` 无消费方 | 时间新近路 / 索引对账落地时 | 接口已定、三档实现给出，如实标注无调用方（仲裁 1） |
| `MemoryCapability.HYBRID_BUILTIN` 无实现方 | 向量检索落地时（扩展阶段） | 枚举常量保留；门面对它**明确报错、不静默降级**（仲裁 5） |
| `/api/v1/memory` 端点 + 管理台 | **第 26 节** | 本节只保证 `chat` 链路里记忆真的生效 |
| **Mem0 真连** | 人工项（可选） | 本节只做协议集成 + mock 测试；**线上报文格式未经真 server 验证**，如实记录 |
| `schema.sql` 里 `notify_channels` 上方那句「第 19 节只读——写入路径归第 26 节，故运行期本表恒为空是预期状态」 | 第 19 节补强② 之后**已失效**（写入面已由 CLI 三条轻命令提供） | 本节**只加新表、不动旧注释**；是否顺带修正进第 5 步固定停点的确认清单 |
| `SqliteMemoryStore` 不做建表（表由 `oryxos init` 补） | 运维动作 | 仲裁 11；`no such table` 报错 MUST 包成带「请先运行 `oryxos init`」指引的消息 |

---

## Constitution Check（Phase 1 设计后复核）

设计完成后逐条重跑，结论与 Phase 0 一致：**10/10 通过**。三处需要复述的复核点：

- **原则 VI（审计 Day One）**：`save_memory` 的非法 scope **抛异常**这条设计（D4）在 Phase 1 得到了更强的依据——`ToolExecutor` 的失败路径会 `record(sessionId, call, false, ...)` 并把原因回灌模型。若改成返回错误文案，这条调用在 `tool_invocations` 里会记成 `success=true`，**审计说谎**。设计定稿后这条不再是"风格偏好"，而是审计口径的硬约束。
- **原则 VII（接口先行）**：三档实现**只新增类**，`LongTermMemoryStore` / `MemoryScope` / `MemoryCapability` / `MemorySource` 四个既有类型 `git diff` 为空——这是"升级只新增实现类、不改接口和调用方"这条纪律在本仓的**第三次**实际兑现（前两次是 `NotifyChannelAdapter` 的两个方言档）。
- **原则 I（多模块）**：新增的 `oryxos-memory → oryxos-storage` 依赖边经方向检查不成环；本节不新增模块。

---

## Phase 0 / Phase 1 产出

| 产物 | 内容 | 状态 |
| --- | --- | --- |
| [research.md](./research.md) | 依赖与 API 的**实测核实**（`mvn dependency:tree` / `javap`）、Mem0 报文口径、D1~D13 的决策与备选 | ✅ |
| [data-model.md](./data-model.md) | `memory_entries` 表 / `MemoryEntry` 实体 / `MemoryEntryRepository` 三个查询 / `MemoryScope` / `MemoryCapability` / `MEMORY.md` 文件形状 / 配置键 | ✅ |
| [contracts/memory-store.md](./contracts/memory-store.md) | `LongTermMemoryStore` 5 方法的逐方法契约 + **三档行为对照表**（截断手段 / 检索手段 / 能力取值 / 归档条目视图） | ✅ |
| [contracts/memory-service.md](./contracts/memory-service.md) | 门面 3 方法的契约 + 两个 `@Tool` 的**对外面**（钉死的工具名、入参 Schema 的 required 集合、返回文案逐字） | ✅ |
| [contracts/memory-config.md](./contracts/memory-config.md) | `oryxos.memory.backend` 取值与报错口径、Mem0 两个环境变量、装配规则（含 `@Bean` 声明顺序契约） | ✅ |
| [quickstart.md](./quickstart.md) | 可复制执行的验证向导（三档切换 / 让位机制 / 两条关键回归单测 / 演示形态） | ✅ |

---

## 下一步

Phase 0 / Phase 1 完成。进 **`/speckit-tasks`**，随后到**流程中唯一的固定软停点**：把任务清单与课件「本节交付物」逐项比对（齐 / 缺什么 / 多什么），输出比对结果并**停下等用户确认**。
