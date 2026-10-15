# Tasks: Tool 体系 —— 给 Agent 装上能真正动手的手

**Feature**: `specs/005-tool-registry` | **Branch**: `class-20` | **Date**: 2026-10-14

**Input**: Design documents from `specs/005-tool-registry/`
**Prerequisites**: [plan.md](plan.md) / [spec.md](spec.md) / [research.md](research.md) / [data-model.md](data-model.md) / [contracts/](contracts/) / [quickstart.md](quickstart.md)

**测试纪律**：课件「验收 harness」里**写出代码**的关键回归测试 MUST 原样落地（断言逻辑逐条保真；方法名译成英文，课件原文进 `@DisplayName`）。每个 user story 的测试任务**先于或伴随**对应实现任务。

**路径约定**：以下 `…/tool/` = `oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/`；`…/cli/` = `oryxos-cli/src/main/java/io/oryxlabs/oryxos/cli/`；测试同构于 `src/test/java/`。

---

## Phase 1: Setup（编译与依赖前置）

- [X] T001 在父 `pom.xml` 的 `maven-compiler-plugin` 加 `<parameters>true</parameters>`（**D2a 硬前置**：`@ToolParam` 无 `name()` 元素，不开则 schema 参数名退化成 `arg0`，模型调不动工具）
- [X] T002 [P] 在 `oryxos-tool/pom.xml` 加两个 MCP 坐标 `io.modelcontextprotocol.sdk:mcp-core:0.18.3` 与 `mcp-json-jackson2:0.18.3`（均写死版本号，不由 BOM 托管；**两个都要**，只加核心模块启动抛 `ServiceConfigurationError`）
- [X] T003 [P] 跑 `./mvnw -pl oryxos-tool -am dependency:tree` 核实五个新增工件已在依赖树上、且**没有新增响应式依赖边**（`reactor-core` 自第 16 节即在，不是本节引入）
- [X] T004 跑 `./mvnw -pl oryxos-tool -am compile` 确认 T001/T002 后基线可编译（此时 `-parameters` 尚未被任何测试守住，T012 补上）

---

## Phase 2: Foundational（所有 user story 的阻塞前置）

**Goal**: 让工具能进容器——临时沙箱 + 让位机制。没有这一步，US-1~US-4 全部无处装配。

- [X] T005 [P] 新增 `…/tool/sandbox/PermissiveSandbox.java`：`implements Sandbox`，`enforce` 空实现；**构造函数里打一条 WARN**，文案 MUST 含「临时」与「第 24 节」两个要素（契约见 [contracts/assembly.md](contracts/assembly.md) §4）
- [X] T006 [P] 新增 `PermissiveSandboxTest`：用 logback `ListAppender` 断言构造时 WARN **恰好一条**、`enforce` 对四种 `ActionType` 都不抛（SC-010）
- [X] T007 在 `…/cli/CliAssemblyConfiguration.java` 加 `@Bean @ConditionalOnMissingBean(Sandbox.class) Sandbox sandbox()` 返回 `PermissiveSandbox`（**放在 `toolCatalog()` 之前**，见 T013 的顺序约束）
- [X] T008 [P] 新增 `…/tool/mcp/McpServerConfig.java`：`name` / `transport` / `command` / `env` / `url`（键名冻结，[contracts/mcp-integration.md](contracts/mcp-integration.md) §1.2）
- [X] T009 新增 `…/tool/mcp/McpServerConfigLoader.java` + `McpServerConfigLoaderTest`：SnakeYAML 读 `.oryxos/mcp_servers.yaml`，定位链写死为 **`workspace.root().resolve("mcp_servers.yaml")`**（`Workspace.root()` 返回的就是 `.oryxos` 目录本身，见 `Workspace.java:36`；多写一层 `.resolve(".oryxos")` 会静默读不到——必须由一条**「真读到一个 server」的正向用例**钉死，只测异常分支测不出这个错）；覆盖「文件不存在 / 空文件 / 无 `servers` 键 / `servers: []` / 非法 transport / stdio 缺 command / sse 缺 url / name 重复 / `${VAR}` 解析不到 / YAML 语法错误」**全部只 WARN 或静默跳过、绝不抛异常**（[contracts/mcp-integration.md](contracts/mcp-integration.md) §1.3）

**Checkpoint**: 容器里有一个可注入的 `Sandbox`；MCP 配置读得进来。

---

## Phase 3: User Story 1 — Agent 第一次真的动手（Priority: P1）

**Goal**: 工具进容器、按 Agent 声明过滤、一次调用走完整链路、`oryxos tool list` 看得见。

**Independent Test**: 构造带注解方法的测试类 → 扫描 → 包装 → 注册，断言按名可查且契约三件套非空；按声明清单取子集，断言**恰好等于**已注册的那些。装配由 `oryxos tool list` 实跑判定（人工项）。

### 测试（先行）

- [X] T010 [P] [US1] 新增 `OryxToolContractTest`：`@ParameterizedTest @MethodSource("allRegisteredTools")` 遍历**装配后的真实注册表**，断言 name / description / inputSchema 三件套非空非空白；`@DisplayName("每个已注册工具都必须具备名字、用途说明、入参 Schema 三件套")`（课件 harness 原文，SC-001）
- [X] T011 [P] [US1] 在 `OryxToolContractTest` 加一条：断言全量工具数 ≥ 12 且 `contains("notify")` 为真（第 19 节交付物本节完成注册的证据）
- [X] T012 [US1] 新增 `AnnotatedToolAdapterTest`，含 **D2a 守门测试**：`notify` 的 `getInputSchema()` **含 `content` 与 `channel`、不含 `arg0`**（守 T001 的 `-parameters`）；另断言 `ToolResult` 返回值语义保真（`retryable` 不丢）、非 `ToolResult` 返回值包成 `success`、方法抛异常**上抛不吞**
- [X] T013 [US1] 新增 `ToolRegistryTest`：三种来源都以统一抽象身份注册且 `source` 分别为 `builtin` / `annotation` / `mcp:<server>`；按声明清单过滤后**集合相等**（不是 `containsAll`）；声明了但未注册的名字 → WARN + 跳过 + 结果不含它；`profile.tools()` 为空 → 返回空列表；重名保留先注册者 + WARN。**验收场景对号注记**：US-1 场景 3（调未注册工具 → 明确失败 + 落审计）**本节不新增测试**——第 17 节 `ToolExecutorTest.unknownToolIsRecordedAsFailure()`（`ToolExecutorTest.java:105`）已覆盖该场景，本节靠 T056 的前序节回归绿来证明它仍然成立；评审人按此对号，不要误判为场景漏测
- [X] T014 [US1] 新增装配测试（`oryxos-cli`）：断言容器里 `ToolCatalog` 类型的 Bean **恰好一个**且 `isInstanceOf(ToolRegistry.class)`（**D4 守门**：抓住「空实现没让位」与「两个都在」两种坏法）

### 实现

- [X] T015 [US1] 新增 `…/tool/registry/AnnotatedToolAdapter.java`：`implements OryxTool`；元数据走 `@Tool.name()`/`ToolUtils.getToolName` + `@Tool.description()` + `JsonSchemaGenerator.generateForMethodInput`；执行走 `JsonParser.fromJson` → 逐参数 `convertValue` → `method.invoke` → `ToolResult` 保真（**MUST NOT 用 `MethodToolCallback`**，[contracts/tool-abstraction.md](contracts/tool-abstraction.md) §3.2）
- [X] T016 [US1] 新增 `…/tool/registry/ToolRegistry.java`：`implements ToolCatalog`；`register(OryxTool, String)` / `registerAnnotated(Object, String)` / `contains(String)` / `registered()` / `find` / `availableFor`；嵌套 `public record RegisteredTool(OryxTool tool, String source)`（API 见 [contracts/tool-abstraction.md](contracts/tool-abstraction.md) §2）
- [X] T017 [US1] 在 `CliAssemblyConfiguration` 加 `toolRegistry(...)` Bean，**声明位置 MUST 在 `toolCatalog()` 之前**（T014 守门；顺序错了重命令起不来，`--help` 扫不出来）
- [X] T018 [US1] 在 `CliAssemblyConfiguration` 加第 19 节挂账的三条 Bean：`notifyChannelAdapter()`（`WebhookNotifyAdapter` 无参构造，**不声明 `RestClient` Bean**）、`notifyChannelRegistry(NotifyChannelRepository)`、`notifyTools(Sandbox, NotifyChannelAdapter, NotifyChannelRegistry)`；把 `notifyTools` 经 `registerAnnotated(..., "builtin")` 注册进注册表
- [X] T019 [US1] 给 `NotifyTools.notify` 的 `channel` 参数补 `@ToolParam(required = false)`（L20 改造点，已记于 `CLAUDE.md` 与 `specs/004-notify-outbound/tasks.md`；**只加注解，不改签名**）
- [X] T020 [US1] 改 `…/cli/command/ToolListCommand.java`：**轻 → 重**（起 Spring 上下文经接缝取数），按来源分组输出，空组照样打印并注明原因（格式见 [contracts/assembly.md](contracts/assembly.md) §5.2）；同步改 `OryxOsCli` 的构造
- [X] T021 [US1] 回修 `specs/003-cli-session/contracts/cli-surface.md` 三处（**命令名 / 参数 / 退出码三项不变**）：① §1 第 11 行「轻重」列由轻改重；② §2.10 第二颗子弹——其原文「命令本身已按最终形态写……第 20 节只需换实现，**不改本命令**」与同文件 T033 注（已报批的实施期偏离：「第 20 节需要改本命令，**不是只换实现**」）**直接矛盾**，按「规则文本与既成做法冲突时以做法为准并回修规则」的惯例以 T033 注为准，把该子弹改写为本节的实际状态（轻→重、经接缝取、按来源分组）；③ §4 表的「`tool list` | 工具清单 | 第 20 节接入 ToolRegistry」行更新为已接入

**Checkpoint**: `oryxos tool list` 能看到 13 个工具；US-1 独立可演示。

---

## Phase 4: User Story 2 — 内置工具真的能干活，越界会被拦（Priority: P1）

**Goal**: 文件 / 命令 / HTTP 三组内置工具落地，且校验先于 IO。

**Independent Test**: 每个内置工具两条单测（正常跑通 / 越界被拦），越界那条**同时断言 IO 一次都没发生**。

### 测试（先行）

- [X] T022 [P] [US2] 新增 `FileToolsTest`：`read_file` / `write_file` / `list_dir` 各「正常 + 越界」两条；越界用例用记录型沙箱替身断言 `readAttempts()` 为空（**只断言抛异常不够**——先 IO 再校验也会抛异常，测试会假绿）
- [X] T023 [P] [US2] 新增 `ShellToolsTest`：正常执行 + 越界被拦；**断言 argv 直传不经 Shell 解释**（参数含 `|` / `&&` / `$VAR` 时不被展开，US-2 场景 4）；断言超时上限生效
- [X] T024 [P] [US2] 新增 `HttpToolsTest`：用 `mockwebserver3` 断言 `http_get` / `http_post` 正常路径；越界域名被拦且**一次请求都没发出**（不碰外网，SC-008）
- [X] T025 [US2] 在三个测试类各加一条顺序断言：沙箱替身记录到 `enforce` 调用**发生在** IO 记录之前（顺序由测试钉死，SC-003 / SC-010）

### 实现

- [X] T026 [P] [US2] 新增 `…/tool/builtin/FileTools.java` 的 `read_file` / `write_file` / `list_dir`：每个 `@Tool` 方法**第一行**是 `sandbox.enforce(new SandboxAction(FILE_READ|FILE_WRITE, path))`；失败返回 `ToolResult.failure`；**MUST NOT catch `SandboxViolationException`**（[contracts/builtin-tools.md](contracts/builtin-tools.md) §2.1~2.3）
- [X] T027 [P] [US2] 新增 `…/tool/builtin/ShellTools.java` 的 `shell`：argv 按空白切分**直传 `ProcessBuilder`**（不经 Shell）；`enforce(SHELL_COMMAND, argv[0])`；**超时 30 秒**后销毁进程并返回 `retryableFailure`（[contracts/builtin-tools.md](contracts/builtin-tools.md) §2.7）
- [X] T028 [P] [US2] 新增 `…/tool/builtin/HttpTools.java` 的 `http_get` / `http_post`：`enforce(HTTP_REQUEST, url)` 首行；30 秒有界超时；非 2xx → `failure`，连接失败/超时 → `retryableFailure`；**结果里 MUST NOT 回显请求头**（FR-027）
- [X] T029 [US2] 在 `CliAssemblyConfiguration` 加 `fileTools(Sandbox)` / `shellTools(Sandbox)` / `httpTools(Sandbox)` 三个 Bean，并各自 `registerAnnotated(..., "builtin")`（**放在 `toolRegistry()` 之前**）
- [X] T030 [US2] 跑 `./mvnw -pl oryxos-tool -am test` 确认 US-2 全绿；核对 `tool_invocations` 审计由既有 `ToolExecutor` 自动落库、**本节新增审计代码行数为 0**（FR-021 / SC-006）

**Checkpoint**: 三组内置工具可用；校验先于 IO 有测试钉死。

---

## Phase 5: User Story 3 — 声明一个 MCP server 就能用，它挂了不拖垮启动（Priority: P1）

**Goal**: MCP 接入 + 失联隔离 + 有界等待。

**Independent Test**: 替身 MCP 客户端——工具被包装注册、参数原样转发、结果包成统一结果；一个 server 抛异常时**不抛异常、好的照常注册、坏的没注册**。

### 测试（先行）

- [X] T031 [P] [US3] 新增 `McpToolAdapterTest`：断言 `getInputSchema()` 是**合法 JSON**（用 SDK mapper 序列化，不是 record 的 `toString()`）；断言 `isError()` 为 `null` 时**不 NPE**；断言调用时参数**原样转发**、结果包成 `ToolResult`
- [X] T032 [US3] 新增 `McpClientServiceTest`（**课件 harness 关键回归，原样保真**）：`@DisplayName("某个 MCP server 失联，不能拖垮启动和其他工具")` —— server A 正常、server B 失联；断言 `registry.contains("a_tool")` 为真、`registry.contains("b_tool")` 为假、`connectAll()` **不抛异常**
- [X] T033 [US3] 在 `McpClientServiceTest` 加一条：断言等待**有界**（用短超时替身，断言在 10 秒上限内返回并 WARN，不无限期挂住）；另断言 `mcp_servers.yaml` 空清单时 `connectAll()` 正常返回且注册表无 MCP 来源工具（默认预期状态）

### 实现

- [X] T034 [US3] 新增 `…/tool/mcp/McpClientService.java`：`McpClient.sync(transport).requestTimeout(10s).initializationTimeout(10s).build()` → `initialize()` → `listTools()` → 逐个包装注册；**每个阶段失败只 WARN + 跳过，MUST NOT 抛异常出 `connectAll()`**（[contracts/mcp-integration.md](contracts/mcp-integration.md) §3.3）。**可测性接缝（T032/T033 的替身注入点，research.md D9 已决但原任务漏写）**：客户端的创建 MUST 收在一个**包级私有**构造/工厂参数里（如 `McpClientService(McpServerConfigLoader, Function<McpServerConfig, McpSyncClient> clientFactory)`，包级私有；生产用公开构造委托真实工厂）——与第 19 节 `WebhookNotifyAdapter` 的包级私有构造同款惯例：测试够得着、生产调用方够不着、**不新增任何 public 概念**。没有这条接缝，T032/T033 无从注入替身
- [X] T035 [US3] 在 `McpClientService` 实现两种传输构造：`stdio` → `new StdioClientTransport(ServerParameters.builder(cmd).args(args).env(env).build(), McpJsonDefaults.getMapper())`；`sse` → `HttpClientSseClientTransport.builder(url).build()`
- [X] T036 [US3] 新增 `…/tool/mcp/McpToolAdapter.java`：`implements OryxTool`；`getInputSchema()` 走 `McpJsonDefaults.getMapper().writeValueAsString(tool.inputSchema())`；执行走 `callTool(new CallToolRequest(name, arguments))`，`Boolean.TRUE.equals(result.isError())` 判失败（**MUST NOT 拆箱**）
- [X] T037 [US3] 在 `CliAssemblyConfiguration` 加 `mcpServerConfigLoader(Workspace)` / `mcpClientService(McpServerConfigLoader)` 两个 Bean；MCP 工具**最后注册**（`source = "mcp:<server名>"`），保证重名时内置工具胜出（FR-023）
- [X] T038 [US3] grep 自查：`oryxos-tool/src/main/java` 与 `oryxos-cli/src/main/java` 里 **0 处 `reactor` import**、0 处 `CompletableFuture`、0 处自建线程池（FR-026 / H4 不变量 ⑤）

**Checkpoint**: MCP 工具可接入；失联隔离有课件 harness 同款测试守住。

---

## Phase 6: User Story 4 — 加一个工具的成本恒定在"写一个类"（Priority: P2）

**Goal**: 五个扩展基础工具 + 两个接缝。

**Independent Test**: 五工具各自单测；外加一条断言——为这五个工具所做的改动**没有触碰**校验位 / 审计 / 过滤三处代码。

### 测试（先行）

- [X] T039 [P] [US4] 在 `FileToolsTest` 加 `edit_file` 三条：唯一匹配成功 / 找不到报错 / **出现多次报错**；后两条 MUST 断言**文件内容逐字节不变**（SC-007 的"报错时文件被改动次数为 0"）
- [X] T040 [P] [US4] 在 `FileToolsTest` 加 `grep` 两条（正常命中 / 超 200 条截断并注明）+ 一条**目录里放二进制文件时跳过该文件、整次搜索不中断**（FR-014 / US-4 场景 3）
- [X] T041 [P] [US4] 在 `FileToolsTest` 加 `glob` 两条（正常命中 / 超 200 条截断并注明）
- [X] T042 [P] [US4] 新增 `InteractionToolsTest`：注入 `ConsoleUserInteraction` 时能拿到回答；注入 `UnsupportedUserInteraction` 时**明确报"不支持交互"且不阻塞**（用超时断言，US-4 场景 4）；终端读到输入结束时**报错而非返回空回答**
- [X] T043 [P] [US4] 新增 `WebSearchToolsTest`：替身 `SearchProvider` 断言查询原样透传、结果受 200 上限约束；**越界域名被拦且一次请求都不发**（FR-019，US-4 场景 5）；空结果 → 明确"未找到结果"且**不算失败**；网络不可达 → `retryableFailure`
- [X] T044 [US4] 加一条 `@DisplayName("新增这五个工具没有触碰校验位 / 审计落库 / 按 Agent 过滤三处代码")` 的守点（用 `git diff --stat` 对指定文件的改动行数断言，或等价的可判定形式；SC-005）

### 实现

- [X] T045 [US4] 在 `…/tool/builtin/FileTools.java` 加 `edit_file`：`enforce(FILE_WRITE, path)` 首行；**先读全文 → 内存里校验 `oldText` 恰好出现一次 → 通过才写回**（0 次或 ≥2 次都报错且文件一字不动）。**参数名是 `oldText` / `newText` 而非 snake_case**（2026-10-14 报批变更，理由见 [builtin-tools.md](contracts/builtin-tools.md) §2.4 末段）
- [X] T046 [US4] 在 `FileTools` 加 `grep`：`enforce(FILE_READ, path)` 首行；命中格式 `路径:行号: 行内容`；**上限 200 条 + 截断注明**；读到二进制 / 非 UTF-8 **跳过该文件不中断**
- [X] T047 [US4] 在 `FileTools` 加 `glob`：`enforce(FILE_READ, path)` 首行；按路径排序；**上限 200 条 + 截断注明**
- [X] T048 [P] [US4] 新增 `…/tool/interaction/UserInteraction.java`（`Optional<String> ask(String question)`，**拿不到回答返回 empty，绝不阻塞**）
- [X] T049 [P] [US4] 新增 `…/tool/interaction/ConsoleUserInteraction.java`（读终端一行，原样返回含空行；读到 EOF 报错）
- [X] T050 [P] [US4] 新增 `…/tool/interaction/UnsupportedUserInteraction.java`（明确报"不支持交互"）
- [X] T051 [US4] 新增 `…/tool/interaction/InteractionTools.java` 的 `ask_user`：**无沙箱动作**；拿不到回答 → `ToolResult.failure("当前渠道不支持与用户交互")`
- [X] T052 [P] [US4] 新增 `…/tool/search/SearchProvider.java` + `…/tool/search/DuckDuckGoSearchProvider.java`（免 key Instant Answer）。**接缝含两个方法**：`List<SearchResult> search(String query, int limit)` + **`String endpoint()`**——`web_search` 的沙箱动作需要目标 URL（`enforce(HTTP_REQUEST, provider.endpoint())`），URL 必须是 provider 的属性而非装配处另传，否则换搜索引擎时还要改装配，违反 FR-018「换别的只加实现类」（**复核补漏**：原任务只写了 `search`，URL 来源未定义）
- [X] T053 [US4] 新增 `…/tool/search/WebSearchTools.java` 的 `web_search`：`enforce(HTTP_REQUEST, <provider 目标 URL>)` 首行；上限 200 条
- [X] T054 [US4] 在 `CliAssemblyConfiguration` 加 `userInteraction()` / `interactionTools(UserInteraction)` / `searchProvider()` / `webSearchTools(Sandbox, SearchProvider)` 四个 Bean 并各自注册（**放在 `toolRegistry()` 之前**）

**Checkpoint**: 13 个工具全部在册；FR-022 的"加工具成本恒定"有可判定证据。

---

## Phase 7: Polish & 跨切面

- [X] T055 跑 `./mvnw spotless:apply` 修正格式，再跑 `./mvnw clean verify` 直到**全绿**（Spotless / P3C / Checkstyle / SpotBugs / FindSecBugs / Dependency-Check 全参与）
- [X] T056 **前序节全部测试回归绿**（第 16~19 节的测试类全跑一遍，跨节契约证据）
- [X] T057 H4 六条全局不变量逐条自查并留证据（命令见 [quickstart.md](quickstart.md) §4）：①涉外 IO 首行过 `enforce` ②本节不新增审计写入路径（`oryxos-tool` 里 0 处引用 `tool_invocations`）③无明文 key ④`session_id` 不出现于 `oryxos-tool` ⑤无 reactor/线程池 ⑥无 `MethodToolCallback` / Spring AI 自动执行路径
- [X] T058 FR-024 禁令自查：`git diff` 确认 `OryxTool` / `ToolResult` / `Sandbox` / `SandboxAction` / `ActionType` / `SandboxViolationException` / `Profile` / `ProfileLoader` / `ProfileContext` / `mcp_servers.yaml` 模板 **改动行数为 0**
- [X] T059 端到端实跑（[quickstart.md](quickstart.md) §5）：`oryxos tool list` 列出 13 个工具并按来源分组；`chat` 里模型点名 `list_dir` 被真正执行并回填；`sqlite3` 查到 `tool_invocations` 记录
- [X] T060 在 `CLAUDE.md` 记明本节状态：第 20~23 节的**沙箱窗口期**（白名单不生效）、`tool list` 改重命令、第 19 节挂账已清
- [X] T061 落盘 `specs/005-tool-registry/acceptance-report.md`（六项证据 + 变更总结三段 + 剩余人工项 + 备注），并在对话里完整输出一遍报告正文

---

## Dependencies（user story 完成顺序）

```text
Phase 1 Setup ──→ Phase 2 Foundational ──→ Phase 3 US-1 ──┬──→ Phase 4 US-2
                                                          ├──→ Phase 5 US-3
                                                          └──→ Phase 6 US-4
                                                                    │
                                              Phase 7 Polish ←──────┘
```

- **Phase 1 / 2 阻塞全部**：`-parameters` 不开则所有 `@Tool` 的 schema 坏掉；MCP 依赖不声明则编译不过；临时沙箱不落地则工具进不了容器。
- **US-1 是 US-2 / US-3 / US-4 的共同前置**：注册表与注解管道是所有工具的落地载体。
- **US-2 / US-3 / US-4 之间互相独立**，可并行（不同文件）。
- **US-4 排在 P2**：即使只交付 US-1~US-3，Agent 已经能干活（spec 的优先级判断）。

## Parallel Execution Examples

**US-2 的实现三件可并行**（不同文件、互不依赖）：

```text
T026 FileTools（read/write/list）  ‖  T027 ShellTools  ‖  T028 HttpTools
```

**US-4 的接缝与实现类可并行**：

```text
T048 UserInteraction  ‖  T049 ConsoleUserInteraction  ‖  T050 UnsupportedUserInteraction  ‖  T052 SearchProvider + DuckDuckGo
```

**US-2 / US-3 / US-4 的测试类可并行**（各自独立文件）。

## MVP 范围

**US-1 单独交付即为 MVP**：工具进容器、`oryxos tool list` 可见、按声明过滤、一次调用走完整链路 + 审计落库。这是"Agent 能动手"这件事本身。

**推荐增量**：US-1 → US-2（基本手脚 + 安全落点）→ US-3（零代码接入）→ US-4（补齐业界水准 + 验证抽象）。

## Format Validation

全部 61 个任务均为 `- [ ] T### [P?] [US#?] 描述 + 文件路径` 格式；Setup / Foundational / Polish 阶段无 `[US#]` 标签；user story 阶段任务均带标签。测试任务在各 user story 内**先于**对应实现任务。
