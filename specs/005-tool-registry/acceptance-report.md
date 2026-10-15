# 第 20 节 Tool 体系 / MCP —— 验收报告

| 项 | 值 |
| --- | --- |
| Feature | `specs/005-tool-registry/` |
| Spec / Plan / Tasks | [spec.md](spec.md) / [plan.md](plan.md) / [tasks.md](tasks.md) |
| Quickstart | [quickstart.md](quickstart.md) |
| 契约 | [contracts/](contracts/)（`tool-abstraction.md` / `builtin-tools.md` / `mcp-integration.md` / `assembly.md`） |
| 分支 | `class-20` |
| 日期 | 2026-10-14 |
| 任务完成度 | **61 / 61**（`grep -c '^- \[X\]' tasks.md` = 61，`grep -c '^- \[ \]'` = 0） |
| 结论 | **harness 全绿，可交人工验**——`mvn clean verify` BUILD SUCCESS，195 测试 0 失败 0 错误；本节交付物 17 个主代码文件 + 15 个测试类（另 2 个测试替身）全部存在；前序节冻结接口零改动。**但本节交付的运行时没有 Tool 治理**（见证据五·①与 review 清单 R1），验收 MUST NOT 把「越界没被拦」当成沙箱已就位。 |

---

## 证据一：`mvn clean verify` 全绿（含静态门禁）

命令：

```bash
export JAVA_HOME=/d/data/work/tools/jdk-21.0.12.1+1
./mvnw -o clean verify -Ddependency-check.skip=true
```

结果：**BUILD SUCCESS**，10 个模块全部通过（Spotless + 阿里 P3C/PMD 6.55 + Checkstyle + SpotBugs 4.8.6.6 / FindSecBugs 1.13.0 + OWASP Dependency-Check 本地跳过）。

各模块测试计数（取自 `target/surefire-reports/*.txt` 聚合）：

| 模块 | 测试类 | 测试数 | 失败 | 错误 |
| --- | ---: | ---: | ---: | ---: |
| `oryxos-core` | 7 | 45 | 0 | 0 |
| `oryxos-provider` | 2 | 7 | 0 | 0 |
| `oryxos-memory` | 0 | 0 | 0 | 0 |
| **`oryxos-tool`** | **22** | **118** | 0 | 0 |
| `oryxos-channel-cli` | 0 | 0 | 0 | 0 |
| `oryxos-web` | 0 | 0 | 0 | 0 |
| `oryxos-storage` | 3 | 9 | 0 | 0 |
| **`oryxos-cli`** | **2** | **16** | 0 | 0 |
| `oryxos-boot` | 0 | 0 | 0 | 0 |
| **合计** | **36** | **195** | **0** | **0** |

> 表中「测试类」是 **surefire 报告条目数**，不是顶层测试类数：`oryxos-tool` 的 22 条里含 `FileToolsTest` 的 **6 个 `@Nested`** 内部类（`$ReadFile` / `$WriteFile` / `$ListDir` / `$EditFile` / `$Grep` / `$Glob`），该模块顶层测试类实为 16 个（13 个本节新增 + 3 个第 19 节存量）。合计 36 同理，顶层测试类共 30 个。

`oryxos-memory` / `oryxos-channel-cli` / `oryxos-web` / `oryxos-boot` 四个模块 0 测试是**预期状态**：本节未触碰这四个模块（memory 归第 22 节、web 归第 26 节、channel-cli 第 18 节已交付且无测试、boot 是聚合壳）。

---

## 证据二：课件 harness 映射 —— 每个测试类存在且非空，关键回归测试逐个对号

课件「四、验收 harness」给出四行测试类清单，本节全部落地；另有扩展工具与装配让位机制的测试。

| 课件 harness 行 | 落地测试类 | 行数 | 关键回归测试（英文方法名 / `@DisplayName` 保留课件原文） |
| --- | --- | ---: | --- |
| `OryxToolContractTest`：参数化遍历 Registry 里每个工具，name/description/inputSchema 三件套非空 | `oryxos-cli/src/test/.../OryxToolContractTest.java` | 81 | `everyRegisteredTool_exposesCompleteContract`（`@ParameterizedTest` + `@MethodSource("allRegisteredTools")`）、`inputSchema_isValidJson`、`schemaIsJsonWithProperties` |
| `ToolRegistryTest`：三种来源都以 `OryxTool` 身份注册；按声明清单过滤后子集**精确匹配、不多不少** | `oryxos-tool/src/test/.../registry/ToolRegistryTest.java` | 209 | `threeSourcesRegisterWithUnifiedIdentityAndSourceLabel`、`availableForMatchesDeclaredSetExactly`、`duplicateNameKeepsFirstRegistered`、`builtinWinsOverMcpOnCollision`、`declaredButUnregisteredNameIsSkipped`、`availableForFollowsDeclarationOrder` |
| `FileToolsTest` / `ShellToolsTest` / `HttpToolsTest`：各自「正常能跑通 + 越界会被拦」 | `.../builtin/FileToolsTest.java` 352<br>`.../builtin/ShellToolsTest.java` 102<br>`.../builtin/HttpToolsTest.java` 135 | — | 三类的越界用例均断言「沙箱先于 IO」——由 `RecordingSandbox`（68 行测试替身）钉死调用顺序 |
| `McpToolAdapterTest` / `McpClientServiceTest`：mock `McpClient`；**某个 server 失联不能拖垮启动和其他工具** | `.../mcp/McpToolAdapterTest.java` 134<br>`.../mcp/McpClientServiceTest.java` 207 | — | `callTool_forwardsArgumentsVerbatim`、`callTool_isErrorTrue_isNotRetryable`、`callTool_throws_isRetryableFailure`、`whenOneMcpServerIsUnreachable_otherToolsStillRegistered`、`connectAll_serverHandshakeFails_warnsAndContinues` |
| （课件未列，本节补充）扩展工具单测 | `.../search/DuckDuckGoSearchProviderTest.java` 130<br>`.../search/WebSearchToolsTest.java` 127<br>`.../interaction/InteractionToolsTest.java` 99<br>`.../mcp/McpServerConfigLoaderTest.java` 196 | — | `absentOrEmptyConfigYieldsEmptyList`、`unresolvedEnvVarSkipsServer`、`invalidEntriesAreSkippedNotThrown` |
| （课件未列，本节补充）注解管道单测 | `.../registry/AnnotatedToolAdapterTest.java` 166 | — | `notifySchema_usesRealParameterNames`、`missingRequiredParameterFails`、`optionalParameterMayBeOmitted`、`toolResultReturnValueIsPreserved`、`methodExceptionPropagates` |
| （课件未列，本节补充）装配让位机制单测 | `oryxos-cli/src/test/.../ToolAssemblyTest.java` 99 | — | `toolCatalog_hasExactlyOneBean_andItIsTheRegistry`、`sandbox_hasExactlyOneBean_andItIsThePermissiveStandIn`、`whenRealSandboxIsPresent_theStandInBacksOff`、`allTwelveToolsAreRegistered_includingNotifyFromLesson19` |
| （课件未列，本节补充）FR-016 恒定上手成本 | `.../registry/ToolOnboardingCostTest.java` 108 | — | `newTools_leaveTheThreeCrossCuttingSitesUntouched`、`newTools_areHostedByPlainToolClasses` |
| （课件未列，本节补充）临时沙箱 | `.../sandbox/PermissiveSandboxTest.java` 61 | — | `enforce` 全放行 + 构造 WARN |

两个「最值钱」的回归测试**断言逻辑逐条保真**，方法名译成英文、课件中文原文进 `@DisplayName`：

- 参数化契约检查：`allRegisteredTools` 的 `@MethodSource` 直接取 `ToolRegistry.registered()`——**新工具自动纳入检查**，与课件注释「新工具自动纳入契约检查」一致；三个 `assertNotNull`（`getName` / `getDescription` / `getInputSchema`）一条不少。
- MCP 失联隔离：坏 server 的 `listTools()` 抛 `ConnectException` 后，断言 `connectAll` **不抛异常**、好 server 的工具 `contains` 为真、坏 server 的工具 `contains` 为假——与课件示例三行断言一一对应。

测试替身 `CliTestContext`（48 行）把「起一个 NONE 型 Spring 上下文」的引导惯用法抽出共享，避免 `ToolAssemblyTest` / `OryxToolContractTest` 各写一遍。

---

## 证据三：「本节交付物」逐项存在性核对

课件「本节交付物」逐项对号（`ls` + `wc -l`）：

| 交付物 | 文件 | 行数 |
| --- | --- | ---: |
| `ToolRegistry` | `oryxos-tool/.../tool/registry/ToolRegistry.java` | 170 |
| `AnnotatedToolAdapter` | `oryxos-tool/.../tool/registry/AnnotatedToolAdapter.java` | 171 |
| `FileTools`（3 个基础 + `edit_file`/`grep`/`glob` 扩展 = 6 个方法） | `oryxos-tool/.../tool/builtin/FileTools.java` | 380 |
| `ShellTools` | `oryxos-tool/.../tool/builtin/ShellTools.java` | 151 |
| `HttpTools`（`http_get` / `http_post`） | `oryxos-tool/.../tool/builtin/HttpTools.java` | 123 |
| `InteractionTools` + `UserInteraction` 抽象及两档实现 | `.../tool/interaction/{InteractionTools,UserInteraction,ConsoleUserInteraction,UnsupportedUserInteraction}.java` | 37 / 29 / 58 / 21 |
| `WebSearchTools` + `SearchProvider` 抽象及一档实现 | `.../tool/search/{WebSearchTools,SearchProvider,DuckDuckGoSearchProvider}.java` | 86 / 41 / 146 |
| `McpClientService` | `oryxos-tool/.../tool/mcp/McpClientService.java` | 163 |
| `McpToolAdapter` | `oryxos-tool/.../tool/mcp/McpToolAdapter.java` | 160 |
| `McpServerConfig` + `McpServerConfigLoader` | `oryxos-tool/.../tool/mcp/{McpServerConfig,McpServerConfigLoader}.java` | 43 / 240 |
| `PermissiveSandbox`（沙箱前向接口第五件，临时装配） | `oryxos-tool/.../tool/sandbox/PermissiveSandbox.java` | 48 |
| `NotifyTools`（19 节）在此完成注册 | `NotifyTools.java` 改 `@ToolParam(required=false)`；Bean 声明在 `CliAssemblyConfiguration:304` | — |
| 装配（课件未点名文件，属「配置」行） | `oryxos-cli/.../cli/CliAssemblyConfiguration.java` 新增 14 个 `@Bean` 方法 | +253 |

**默认注册的工具清单**（`tool list` 实测，共 13 个，全部落在 `builtin` 组）：

```
notify  edit_file  glob  grep  list_dir  read_file  write_file  shell
http_get  http_post  web_fetch  ask_user  web_search
```

与课件「内置 Tool 9 个」的差额已在 CLAUDE.md 记明：`save_memory` / `recall_memory` 两个归第 22 节（Memory 模块尚未交付 `MemoryTools`），故本节 13 = 课件 9 − 2 + 扩展 6。

> **交付时是 12 个**（`web_fetch` 是 2026-10-15 补强新增的第 13 个，见本报告末尾「交付后补强」一节）。本行与 §5.2 的转录已按**补强后重跑的实际输出**更新，交付时的原始输出保留在 §5.2 的引用块里。

课件「配置」行的 `.oryxos/mcp_servers.yaml` 模板**由第 18 节交付、本节零改动**（`git diff --numstat InitCommand.java` 空），模板里示例全在注释、末尾空清单——故默认配置下不连接任何 MCP server，这是**预期状态**。

`oryxos-tool/src/main/java/.../sandbox/` 下**只有 `PermissiveSandbox.java` 一个新增文件**，`Sandbox` / `SandboxAction` / `ActionType` / `SandboxViolationException` 四件逐字未动（见证据五·②）。

---

## 证据四：前序节全部测试回归绿 + 冻结接口零改动

**测试回归**：证据一的 195 个测试里，非本节的既有测试全部继续通过——`oryxos-core` 45、`oryxos-provider` 7、`oryxos-storage` 9，以及 `oryxos-tool` 内第 19 节交付的 `NotifyToolsTest` / `WebhookNotifyAdapterTest` / `JpaNotifyChannelRegistryTest`。**零回归失败**。

**冻结接口零改动**（`git diff --numstat` 全部为空 = 一行未动）：

| 冻结对象 | 归节 |
| --- | --- |
| `OryxTool` / `ToolResult` / `ToolCatalog` | 骨架期（core 契约） |
| `Sandbox` / `SandboxAction` / `ActionType` / `SandboxViolationException` | 骨架期（tool 契约，plan 裁决 4 要求「不改这四件一行」） |
| `NotifyChannelAdapter` / `NotifyTarget` | 第 19 节 |
| `ToolExecutor` / `ReActLoop` | 第 17 节 |
| `SessionId` | 第 18 节 |
| `InitCommand`（`mcp_servers.yaml` 模板） | 第 18 节（软门禁 ② 要求不改模板结构与键名） |

`OryxTool` 保留骨架期的 String 口径（`String getInputSchema()` / `ToolResult execute(String inputJson)`），未改成课件示意的 `JsonSchema` / `JsonNode` 签名——**已报批偏差**，转换成本收在 `AnnotatedToolAdapter` 内部一处。

---

## 证据五：H4 六条全局不变量逐条自查

### ① 涉外 IO 首行过 `Sandbox.enforce`

`grep -rn "sandbox.enforce" oryxos-tool/src/main/java/` 得到 **10 个调用点**，覆盖 11 个需要沙箱的工具方法：

| 文件 | 调用点 | 覆盖的工具 |
| --- | ---: | --- |
| `builtin/FileTools.java` | 6（L81 / L107 / L130 / L169 / L219 / L272） | `read_file` / `write_file` / `list_dir` / `edit_file` / `grep` / `glob` |
| `builtin/ShellTools.java` | 1（L80） | `shell`（`argv[0]` 白名单） |
| `builtin/HttpTools.java` | 1（L94，在共享的 `request()` 里） | `http_get` **与** `http_post` 共用同一处 |
| `search/WebSearchTools.java` | 1（L52） | `web_search`（`provider.endpoint()`） |
| `notify/NotifyTools.java` | 1（L65） | `notify`（渠道 url） |

10 个调用点覆盖 11 个方法，是因为 `http_get` / `http_post` 共用 `request()` 里那一处；`ask_user` 按契约 §2.9 不带沙箱（它不碰文件/命令/网络）。**顺序由测试钉死**：`FileToolsTest` / `ShellToolsTest` / `HttpToolsTest` 用 `RecordingSandbox` 断言「校验先于 IO」——校验抛异常时 IO 一次都不发生。

### ② LLM 调用落 `llm_calls`、工具执行落 `tool_invocations`

`grep -rln "ToolInvocationRepository\|LlmCallRepository" oryxos-*/src/main/java` 的写入方仍是既有两个：`oryxos-core/.../engine/ToolExecutor.java`（工具）与 `oryxos-provider/.../LlmCallRecorder.java` + `JpaLlmCallRecorder.java`（LLM）。**`oryxos-tool` 下没有任何一处引用这两个 Repository**——本节未新增第二条写入路径，工具结果一律经 `ToolExecutor` 的既有路径落库（FR-015）。

### ③ 无明文 key

```bash
grep -rniE "(api[_-]?key|secret|token|password)\s*[:=]\s*\"[A-Za-z0-9_\-]{16,}\"" \
  --include=*.java --include=*.yaml --include=*.yml --include=*.properties \
  oryxos-*/src specs/005-tool-registry | grep -v '\${'
```

→ **零命中**。MCP server 配置的 `env` 值支持 `${ENV_VAR}` 占位，由 `McpServerConfigLoader` 从环境变量解析；占位未解析时**跳过该 server 并 WARN**（`McpServerConfigLoaderTest.unresolvedEnvVarSkipsServer`），不落默认值、不落明文。

### ④ `session_id` 只在会话服务内拼接

唯一的构造点是 `oryxos-core/.../session/JpaSessionService.java:44` 的 `SessionId.of(channel, userId, profileName)`。本节代码零命中——`ToolRegistry` / 各工具 / MCP 适配器都不感知会话身份。

### ⑤ 无 Reactor / `CompletableFuture` / 自建线程池

```bash
grep -rn "reactor\.\|CompletableFuture\|Executors\.\|new ThreadPoolExecutor\|ThreadPoolTaskExecutor" \
  --include=*.java oryxos-*/src
```

→ **零命中**（MCP SDK 内部使用响应式基础库，按 plan 裁决 14 不构成违反——扫描只覆盖本仓源码）。`ShellTools` 的读流线程走 `Thread.ofVirtual().unstarted(...)`，是项目既定的虚拟线程模型（章程原则 VIII），不是自建线程池。

### ⑥ 无 Spring AI 自动工具执行路径

`ProviderService.java:125` 显式 `options.internalToolExecutionEnabled(false)`，并有回归测试钉死「调用方把自动执行开着传进来、本服务必须把它掰成关闭」（`ProviderServiceTest`，第 16 节交付，本节回归仍绿）。本节新增代码**零命中** `MethodToolCallback`——`AnnotatedToolAdapter` 的 javadoc 专门写明三条 MUST NOT 理由（会丢 `retryable`、会迫使改第 19 节公共接口、会让失败落不进审计表），适配器自行反射调用，不走 Spring AI 的 `MethodToolCallback`。

---

## 证据六：端到端真跑（§5.2 / §5.3 / §5.5）

在干净目录 `/tmp/l20-verify` 里用 `java -jar oryxos-boot/target/oryxos.jar` 实跑。

### §5.2 `oryxos tool list`

**补强后重跑（2026-10-15）**：

```
Tool（共 13 个）:
  builtin (13):
    notify           把一条消息推送到已注册的通知渠道（不传渠道名时用默认渠道）                        builtin
    edit_file        把文件里的一段文本替换成另一段；oldText 必须在文件里恰好出现一次，否则报错且不改动文件      builtin
    glob             按通配符在一个目录下找文件路径，每页最多 200 条                             builtin
    grep             按正则在一个文件或目录里搜内容，返回「路径:行号: 行内容」，每页最多 200 条              builtin
    list_dir         列出一个目录下的条目（每行「类型 + 名字」），按名字排序                        builtin
    read_file        读取一个文本文件的内容；可用 offset/limit 按行读一段，单次最多 2000 行            builtin
    write_file       把文本写入一个文件（覆盖写；父目录不存在时不会自动创建）                         builtin
    shell            执行一个命令（参数按空白切分后直接交给进程，不经 Shell：管道、重定向、&&、$VAR 都不生效）；
                     可传 timeoutSeconds 覆盖默认的 30 秒超时，上限 600 秒                       builtin
    http_get         发起一个 HTTP GET 请求，返回状态码与响应体                           builtin
    http_post        发起一个 HTTP POST 请求（请求体是文本），返回状态码与响应体                  builtin
    web_fetch        抓取一个网页并把正文转成纯文本（HTML 去掉标签；超长会截断）                     builtin
    ask_user         向用户提问并等待回答；当前渠道不支持交互时明确报错，不会卡住                       builtin
    web_search       用搜索引擎检索网页，返回标题、链接与摘要                                 builtin
  annotation (0):
    （无）
  mcp (0):
    （无：.oryxos/mcp_servers.yaml 里没有声明任何 server）
```

**交付时（2026-10-14）的输出是 12 个**，转录如下（保留原始记录，不篡改当时的证据）：

```
Tool（共 12 个）:
  builtin (12):
    notify  edit_file  glob  grep  list_dir  read_file  write_file  shell
    http_get  http_post  ask_user  web_search
```

三处差异全部来自补强：`web_fetch` 是新增的第 13 个；`read_file` / `grep` / `glob` / `shell` 四条说明文案因新增可选参数而变（**工具名一个都没改**，软门禁 ② 的处置见「交付后补强」一节）。`annotation (0)` / `mcp (0)` 两组两次一致。

退出码 **0**。空组照样打印并带说明行——「annotation 0 个」是**有用信息**（业务方还没写工具），不是噪音。启动日志里可见一条 WARN：

```
WARN  i.o.o.tool.sandbox.PermissiveSandbox : 沙箱当前挂的是临时实现（PermissiveSandbox）：白名单校验不生效。
      第 24 节落地 WhitelistSandbox 后自动替换。
```

### §5.3 真模型对话能调通工具（`list_dir` + `read_file`）

真实模型下 Agent 依次调用了 `list_dir` 与 `read_file`，审计表落库：

```sql
('list_dir',  1, 12, '{"path": "."}')
('read_file', 1,  8, '{"path": "sandbox-dir/note.txt"}', 'hello from oryxos\n')
```

两行 `success = 1`，`input_json` 是模型自己构造的参数、`result_json` 是工具的真实返回——工具确实被执行掉了，不是模型「声称」执行了。

### §5.5 沙箱窗口期实测（**预期行为，不是缺陷**）

`read_file /etc/passwd` 在真实运行中**成功**（`success = 1`）——`PermissiveSandbox` 放行一切，白名单校验未生效。这与 `contracts/assembly.md` §6.2 与 CLAUDE.md 的窗口期条款**完全一致**：第 20~23 节交付的运行时没有 Tool 治理。验收 MUST NOT 把它当成「沙箱已就位」。

---

## 变更总结（给 reviewer 的导读）

### 一、改动点

实测依据：`git status --short` + `git diff --stat`（9 个已跟踪文件改动，**+496 / −48**）+ 未跟踪清单。

**新增（`oryxos-tool`，17 个主代码文件，全在未跟踪目录下）**

| 包 | 文件 | 动机 |
| --- | --- | --- |
| `registry/` | `ToolRegistry`(170)、`AnnotatedToolAdapter`(171) | 注册表实现 core 既有接缝 `ToolCatalog`；注解 Bean 包装成 `OryxTool`（方式三） |
| `builtin/` | `FileTools`(380)、`ShellTools`(151)、`HttpTools`(123) | 内置 11 个工具；每个方法首行 `enforce` |
| `interaction/` | `InteractionTools`(37)、`UserInteraction`(29)、`ConsoleUserInteraction`(58)、`UnsupportedUserInteraction`(21) | `ask_user` + 渠道能力接缝两档实现 |
| `search/` | `WebSearchTools`(86)、`SearchProvider`(41)、`DuckDuckGoSearchProvider`(146) | `web_search` + 可替换搜索接缝（免 key 一档） |
| `mcp/` | `McpClientService`(163)、`McpToolAdapter`(160)、`McpServerConfig`(43)、`McpServerConfigLoader`(240) | 方式二：连 server、拉工具、包装注册、失联隔离 |
| `sandbox/` | `PermissiveSandbox`(48) | 沙箱前向接口第五件，临时装配、第 24 节自动让位 |

**新增测试（15 个测试类 + 2 个测试替身）**

- `oryxos-cli/src/test/`（**该模块首次有测试源根**，此前 `git log -- oryxos-cli/src/test` 为空）：`OryxToolContractTest`(81)、`ToolAssemblyTest`(99)、`CliTestContext`(48，替身)
- `oryxos-tool/src/test/`：`FileToolsTest`(352)、`ShellToolsTest`(102)、`HttpToolsTest`(135)、`RecordingSandbox`(68，替身)、`InteractionToolsTest`(99)、`McpClientServiceTest`(207)、`McpServerConfigLoaderTest`(196)、`McpToolAdapterTest`(134)、`AnnotatedToolAdapterTest`(166)、`ToolOnboardingCostTest`(108)、`ToolRegistryTest`(209)、`PermissiveSandboxTest`(61)、`DuckDuckGoSearchProviderTest`(130)、`WebSearchToolsTest`(127)

**修改（9 个已跟踪文件）**

| 文件 | 变更 | 动机 |
| --- | --- | --- |
| `oryxos-cli/.../CliAssemblyConfiguration.java` | +253/−0 | 新增 14 个 `@Bean` 方法：临时沙箱、第 19 节挂账的三件（`notifyChannelAdapter` / `notifyChannelRegistry` / `notifyTools`，`RestClient` 刻意不声明）、各工具、MCP、注册表；让既有 `EmptyToolCatalog` 自动让位 |
| `oryxos-cli/.../command/ToolListCommand.java` | +149/−26 | **轻 → 重**：改起 Spring 上下文从 `ToolRegistry` 取数（契约 §1 的命令名/参数/退出码三项不变，故在节内权限内） |
| `oryxos-cli/.../OryxOsCli.java` | +12/−5 | `toolGroup()` 不再取 `Workspace`（`tool list` 改为重命令后由命令自己检查工作区） |
| `oryxos-tool/.../notify/NotifyTools.java` | +8/−1 | 清第 19 节挂账：给 `channel` 补 `@ToolParam(required = false)`，让 FR-004「渠道名可选」在模型可见的 JSON Schema 层成立 |
| `oryxos-tool/pom.xml` | +30/−0 | 新增 MCP 两个坐标（`mcp-core` + `mcp-json-jackson2`，均 0.18.3 写死版本、不由 BOM 托管）+ `spotbugs-annotations`（`provided`） |
| `oryxos-cli/pom.xml` | +12/−0 | 新增 `spring-boot-starter-test`（`test` 作用域）——`oryxos-cli` 首次需要起真 Spring 上下文做装配断言 |
| `pom.xml` | +6/−0 | `maven-compiler-plugin` 加 `<parameters>true</parameters>`：`Parameter.getName()` 只在打开该开关时返回真名，注解工具的 schema 与执行两边都靠它 |
| `specs/003-cli-session/contracts/cli-surface.md` | +5/−3 | 第 18 节契约修订记录：`tool list` 由轻改重 |
| `CLAUDE.md` | +21/−13 | 阶段推进到第 20 节、新增窗口期警示、Tool 体系章节重写、`@ToolParam` 挂账标记已清 |

**本节未触碰前序节代码**（除上表 `NotifyTools` 一处已报批的注解补充）——`git diff` 对 `oryxos-core` / `oryxos-provider` / `oryxos-storage` / `oryxos-web` / `oryxos-memory` / `oryxos-channel-cli` / `oryxos-boot` 的主代码**零命中**。

### 二、重点 review 清单（按风险排序）

**R1｜沙箱窗口期 —— 本节交付的运行时没有 Tool 治理（最高风险，需人工确认）**

`CliAssemblyConfiguration.java:259-261` 声明 `@ConditionalOnMissingBean(Sandbox.class)` 的 `PermissiveSandbox`，它放行一切。第 20~23 节之间，`read_file /etc/passwd` **会真的成功**（§5.5 已实测，审计表 `success = 1`）；`shell rm -rf` 同理不被拦（**未实测，按 `PermissiveSandbox` 的放行语义推得**——`enforce` 一律返回、IO 照做）。四条缓解：类名与 javadoc 明示临时、构造时打 WARN、第 24 节同类型 Bean 自动覆盖、CLAUDE.md 与本报报告双处留痕。**review 重点**：确认团队知晓这个窗口，且验收口径不把它当成沙箱已就位。

**R2｜装配顺序是契约，不是风格**

`CliAssemblyConfiguration.java:450` 的 `toolRegistry()` MUST 声明在 `:489` 的 `toolCatalog()`（`EmptyToolCatalog`）**之前**。`@ConditionalOnMissingBean` 按声明顺序求值——顺序反了，`EmptyToolCatalog` 会先被创建（它求值的那一刻 `ToolRegistry` 还没声明），而 `toolRegistry()` 是**无条件** `@Bean`、随后照样创建 → 容器里两个 `ToolCatalog` Bean → 按类型注入处 `NoUniqueBeanDefinitionException`。（注意：不存在「真实注册表不创建」这种情形——它没有条件。）**这个错 `--help` 扫不出来**（不起 Spring 上下文），只有重命令真跑才炸；第 18 节的 `BeanDefinitionOverrideException` 属同一类「只有重命令才炸」的坑，故该条被写进 CLAUDE.md 的自查表。`ToolAssemblyTest` 用「`ToolCatalog` 恰好一个 Bean 且就是 `ToolRegistry`」把它钉死。

**R3｜静态门禁的妥协点 —— 三处就地豁免 + 两处改写**

本节中途被静态门禁抓红 3 类共 29 条，修复方式如下（**没有删断言、没有 `@Disabled`、没有放宽阈值**）：

| 抓到 | 数量 | 修法 |
| --- | ---: | --- |
| SpotBugs `CRLF_INJECTION_LOGS` | 13 | 改成项目惯用的流式结构化日志 `log.atWarn().addKeyValue("k", v).setCause(e).log("常量文案")`——`addKeyValue` / `log` 不是 FindSecBugs 的 CRLF sink |
| SpotBugs 其余告警（`EI_EXPOSE_REP`/`REP2`、`CT_CONSTRUCTOR_THROW`、`RCN_REDUNDANT_NULLCHECK`、`NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE`、`COMMAND_INJECTION`、`DP_DO_INSIDE_DO_PRIVILEGED`） | 14 | **优先真修**：`FileTools.listDir` 改 `String.valueOf(entry.getFileName())` 比较、`McpServerConfig` 紧凑构造里 `List.copyOf`/`Map.copyOf`、`McpToolAdapter` 改 `final`、三处显式判空改 `Objects.requireNonNullElse(...)`、`AnnotatedToolAdapter` 改 `isBlank()`。**确无诚实替代的才就地豁免**：`ShellTools.java:69`（`COMMAND_INJECTION`，argv 直传不经 Shell + `argv[0]` 已过白名单，属 FindSecBugs 对工具入参的误报）、`McpToolAdapter.java:58`（`McpSyncClient` 是连接句柄不可拷贝）、`AnnotatedToolAdapter.java:51`（`EI_EXPOSE_REP2` + `DP_DO_INSIDE_DO_PRIVILEGED`——后者是 SpotBugs 为 SecurityManager 设计的建议，而 JDK 21 已无 SecurityManager） |
| P3C `UndefineMagicConstantRule` | 1（`oryxos-cli`） | `ToolListCommand.java:43-49` 把 `builtin`/`annotation`/`mcp` 提成常量，三处同源 |
| FindSecBugs `FORMAT_STRING_MANIPULATION` | 1（`oryxos-cli`，本节引入） | `ToolListCommand.java:120-136` 放弃动态拼格式串的 `String.format("%-Ns")`，改手工 `pad()` 拼接——输出语义完全相同，告警从根上消掉而不是加豁免 |

连带：`oryxos-tool/pom.xml` 新增 `spotbugs-annotations`（`provided`）依赖，这是就地豁免的前提。**review 重点**：三处 `@SuppressFBWarnings` 的理由是否成立（都在 javadoc 里写明了「为什么是误报」），以及 `pad()` 是否真的与 `%-Ns` 同口径（补空格、不截断）。

**R4｜前序节冻结接口零改动 —— 请核对 diff 为空**

`OryxTool` / `ToolResult` / `ToolCatalog` / `Sandbox` 四件 / `NotifyChannelAdapter` / `NotifyTarget` / `ToolExecutor` / `ReActLoop` / `SessionId` / `InitCommand` 的 `git diff --numstat` 全部为空。唯一被触碰的前序节文件是 `NotifyTools.java`（+9 行，只加一个注解、方法签名未动）——这是第 19 节 → 第 20 节的**已报批挂账**，不是擅自改动。

**R5｜审计路径唯一性**

`tool_invocations` 的写入方仍只有 `oryxos-core/.../engine/ToolExecutor.java` 一处；`oryxos-tool` 下零引用审计 Repository。FR-015「不为工具单独新增第二条写入路径」成立。

**R6｜与课件骨架的偏差项（逐条已报批或已记录）**

| 偏差 | 说明 |
| --- | --- |
| `OryxToolContractTest` 落在 `oryxos-cli` | 它要遍历**完整注册表**（含装配进来的工具），只能起 Spring 上下文；该模块此前无测试源根，本次新建 |
| `CliTestContext` 抽成共享引导 | 两个测试类同款引导，抽出来避免重复 |
| 数据源属性内联而非测试 `application.yaml` | `oryxos-cli` MUST NOT 放第二份 `application.yaml` |
| `connectAll(ToolRegistry)` | 课件示意为无参；显式传注册表让「工具注册到哪」在签名上可见 |
| `Optional<String> ask(...)` / `search(query, limit)` + `endpoint()` | 细化了课件示意的接缝形状；`Optional` 让「拿不到回答」在类型上不可忽略 |
| `MAX_RESULTS = 200` 在 `FileTools` 与 `WebSearchTools` 各写一份 | 刻意重复：两处上限是各自工具的契约，不引入跨类共享常量造成的隐性耦合 |
| `**/` 零目录 glob 规则 | 契约补白 |
| `ToolOnboardingCostTest` 用源码文本断言而非 `git diff --stat` | 让 FR-016 成为**可在 CI 里长期跑的**门禁，而不是一次性人工检查 |
| `HttpTools` 的 `http_get`/`http_post` 共用一处 `enforce` | 两工具同一 IO 路径，共用校验点是正确做法（见证据五·①） |
| MCP 调用按真实 API（`McpClient.sync(transport).build()` / `listTools()` / `callTool(request)`） | 课件的示意调用是伪代码，已 javap 核实真实签名 |

### 三、如何验证

```bash
# 0) 环境（2026-10-15 起在 WSL 侧开发；Windows 侧的 JAVA_HOME=/d/data/work/tools/jdk-21.0.12.1+1 仍可用）
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64

# 1) 全量门禁（含 Spotless / P3C / Checkstyle / SpotBugs+FindSecBugs）
./mvnw -o clean verify -Ddependency-check.skip=true
# 预期：BUILD SUCCESS；238 tests, 0 failures, 0 errors（10 模块）

# 2) 只跑本节新增测试
./mvnw -o test -pl oryxos-tool -am
# 预期：Tests run: 153, Failures: 0, Errors: 0
./mvnw -o test -pl oryxos-cli -am
# 预期：Tests run: 24, Failures: 0, Errors: 0

# 3) 两个最值钱的回归单测
./mvnw -o test -pl oryxos-cli -am -Dtest=OryxToolContractTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -o test -pl oryxos-tool -am -Dtest=McpClientServiceTest -Dsurefire.failIfNoSpecifiedTests=false
# 预期：全绿

# 4) 依赖方向（core 不反向依赖能力模块）
grep -rn "oryxos.tool\|oryxos.memory\|oryxos.provider" oryxos-core/src/main/java
# 预期：零命中

# 5) 冻结接口零改动
git diff --numstat -- oryxos-core/src/main/java/io/oryxlabs/oryxos/core/tool/ \
  oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/sandbox/ \
  oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/notify/NotifyChannelAdapter.java
# 预期：仅 NotifyTools.java 有 +9（已报批）

# 6) 端到端（§5.2）
./mvnw -o clean package -DskipTests
cd /tmp && rm -rf l20 && mkdir l20 && cd l20
java -jar /path/to/oryxos-boot/target/oryxos.jar init
java -jar /path/to/oryxos-boot/target/oryxos.jar tool list
# 预期：13 个工具、builtin(13)/annotation(0)/mcp(0)，annotation 与 mcp 各带说明行，
#       启动日志一条 PermissiveSandbox WARN，退出码 0

# 7) H4 不变量 ⑤⑥
grep -rn "reactor\.\|CompletableFuture\|Executors\." --include=*.java oryxos-*/src   # 预期：零命中
grep -rn "internalToolExecutionEnabled" --include=*.java oryxos-*/src/main           # 预期：ProviderService:125 一处，值为 false
```

**剩余人工项**（harness 判不了，见 [quickstart.md](quickstart.md) §6）：

| # | 项 | 判定 |
| --- | --- | --- |
| 1 | 真实 LLM 下模型能正确构造工具调用 | 需要真实 API key（§5.3 已在本机跑通，换环境需重跑） |
| 2 | 真实 MCP server 能连上并列出工具 | 需要一个真实 server（如 `npx @modelcontextprotocol/server-github`）+ 网络 |
| 3 | 真实搜索服务的可用性与返回质量 | 需要外网 |
| 4 | `ask_user` 在真实终端的交互体验 | 需要人工在终端敲键盘 |
| 5 | **沙箱窗口期团队确认** | 人工确认，双处留痕（CLAUDE.md + 本报告 R1） |

> **补记（2026-10-15，不改上文）**：本表是交付时的原貌。课件「五、做完怎么验」第 1 条的**方式一**（SKILL.md + 真实 MCP server）**不在本节范围**，归第 29 节——原因与处置见本报告末尾「交付后补强」一节的「剩余人工项」第 6 行。**验收时 MUST NOT 把方式一当成漏做。**

---

## 备注

1. **未 commit / 未 push**——按 skill 纪律，同步时机由用户决定。当前分支 `class-20`，工作区含 9 个已跟踪文件改动（+496/−48）与 **14 条未跟踪条目**（`oryxos-cli/src/test/`、`oryxos-tool/src/main` 下 5 个新包 + `sandbox/PermissiveSandbox.java`、`oryxos-tool/src/test` 下 6 个新包、`specs/005-tool-registry/`）。
2. **`docs/class/` 自 2026-10-14 起随仓库跟踪**（课件已入库），本节课件 `docs/class/第20节：Tool 体系 原理解析、实现与代码讲解.md` 是已跟踪文件，无需额外处理。
3. **中途抓红并修复**（按 skill 第 7 步第 8 项要求如实记录，不写「一路全绿」）：本节在 `mvn clean verify` 阶段先后抓到 3 类共 29 条静态门禁违规——27 条 SpotBugs/FindSecBugs（`oryxos-tool`）、1 条 P3C magic constant（`oryxos-cli`）、1 条 FindSecBugs `FORMAT_STRING_MANIPULATION`（`oryxos-cli`，本节新引入）。修法见 R3 表格：**能真修的一律真修，确无诚实替代的才就地豁免并写明理由**；无一处删断言、无一处 `@Disabled`、无一处放宽阈值。连带新增 `oryxos-tool` 的 `spotbugs-annotations`（`provided`）依赖。
4. **日志断言随之调整**：改流式结构化日志后，`McpClientServiceTest` 原先把 server 名断言在 `getFormattedMessage()` 里，改为通过新增的 `structuredValue(ILoggingEvent, String)` 助手断言结构化 key-value 字段——这是**更强**而非更弱的断言（不再依赖文案格式）。
5. **`oryxos-cli/src/test/` 是本节净新增**（`git log -- oryxos-cli/src/test` 为空）：本节之前只有 core/provider/storage/tool 四个模块有已跟踪测试。为此 `oryxos-cli/pom.xml` 新增了 `test` 作用域的 `spring-boot-starter-test`。
6. **跨节挂账结转**：第 19 节遗留的两处挂账已在本节各清一项——`@ToolParam(required = false)` 已补（`NotifyTools.java:62`），三个 notify 相关 Bean 已完成装配（`CliAssemblyConfiguration.java` 的 `notifyChannelAdapter():274` / `notifyChannelRegistry():288` / `notifyTools():304`；`RestClient` 刻意不声明，理由见该处 javadoc）。**仍未清**的是入站 `ChannelAdapter` 抽象（全仓仅 `CliChannel` 直连实现，无抽象类型），挂到入站抽象落地的那一节。
7. **本节结束时应看不到**（quickstart §7）：`WhitelistSandbox`（第 24 节）、`save_memory` / `recall_memory`（第 22 节）、`/api/v1/tools` 端点（第 26 节）、任何 `reactor` import、`tool_invocations` 表结构变化——均已核对确认不存在。
8. **报告本体的复核修订**（2026-10-14，落盘后二次核对）：初稿有 7 处数字与 3 处表述偏差，已对着 `git diff --numstat` / `git status` / `surefire-reports` 逐一改正——① 「26 处 `@Bean` 声明」→ 实测 **14 个 `@Bean` 方法**（`git diff` 中 `+  @Bean` 注解行 14 条，全文件现共 33 个）；② 「20 个主代码文件」→ **17 个**；③ 「16 个测试类 + 1 个替身」→ **15 个测试类 + 2 个替身**（`CliTestContext` / `RecordingSandbox`）；④ 备注 6「四个 notify Bean」→ **3 个**（与正文「`RestClient` 刻意不声明」自相矛盾）；⑤ 备注 1「11 组未跟踪路径」→ **14 条**；⑥ 「`oryxos-tool` 22 测试类」→ 加注为 surefire 条目数（含 6 个 `@Nested`，顶层 16 个）；⑦ CLAUDE.md「新增 133 条测试」→ **108 条**（195 − 存量 87）。表述两处：R1 把「`shell rm -rf` 不被拦」从「已实测」改为「按放行语义推得」（§5.5 只实测了 `read_file`）；R2 删去「真实注册表永远不创建」这一不存在的情形（`toolRegistry()` 无条件）。行数口径统一为 `+a/−d`。

---
---

# 交付后补强（2026-10-15）

> **本节是第 20 节交付之后的补强记录，不改动上文任何一条交付时的证据。** 上文是 2026-10-14 交付时的原貌（含当时 `tool list` 的原始转录），本文记 2026-10-15 的第二次交付：P0 / P1 补齐 + P2 只记不做。

| 项 | 值 |
| --- | --- |
| 触发 | 用户指令「请补齐，P0，P1 / 记录 P2」 |
| 落位 | **折进第 20 节**（不新开 spec 目录），随第 20 节一次性提交 |
| 分支 | `class-20`（未 commit / 未 push） |
| 测试 | **195 → 238**（`oryxos-tool` 118 → 153，`oryxos-cli` 16 → 24，其余模块不变） |
| 工具数 | **12 → 13**（新增 `web_fetch`） |
| 结论 | **P0 三项、P1 两项全部落地，`mvn clean verify` 全绿（10 模块）；P2 七项只记不做（一项顺手修完）；另修两处前序交付类的行为缺陷——免 key 搜索档的 `User-Agent`（软门禁 ② 已报批）、`tool list` 的 MCP 子分组表头（见本节末「补强·追加」）** |

## 为什么要补：四条经实测确认的「在了但不好用」

对照 Claude Code / Codex 的工具面后确认：第 20 节的缺口**不在覆盖面**（12 个已覆盖公约数），而在**单工具的可用深度**。

| 缺口 | 实测 / 源码证据 | 后果 |
| --- | --- | --- |
| `read_file` 无分页、无上限 | 原实现直接 `Files.readString`，无任何行数上限 | 读一个 5000 行日志直接撑爆上下文 |
| `shell` 超时写死 30 秒 | 原 `DEFAULT_TIMEOUT` 无参数可调 | `mvn` / `npm install` 必然超时，且**没有出路** |
| `grep` / `glob` 截断后无法翻页 | 原实现只有「已截断」三个字 | 模型只能靠换更窄的模式硬猜 |
| `web_search` 是问答 API 不是检索 API | 实测 `api.duckduckgo.com` 对 `spring boot 3.5 release notes` **返回 0 条**（本文「证据六·补」有原始响应） | 第 31 节的日报类 Demo 大概率撞上「搜不到」 |

## 软门禁报备（本次触发五条，逐条留痕）

| 门禁 | 触发点 | 处置 |
| --- | --- | --- |
| ① 新增对外概念 | 新工具名 `web_fetch`；新配置键 `oryxos.search.tavily-api-key` | **已报批**（AskUserQuestion，2026-10-15）：`web_fetch` = 做、不加新依赖；搜索 = 加一档 keyed provider |
| ② 改已定字面量 | `read_file` / `grep` / `glob` / `shell` 的**参数表**（`builtin-tools.md:5` 声明「冻结对模型可见的接口（名字、参数、说明）」） | **工具名一个都没改**；只**新增可选参数**，模型可见契约向后兼容。按该文件已有先例（`edit_file` 的 `oldText`/`newText` 报批变更）走「契约文档 + 验收报告双处留痕」 |
| ② 改前序交付物行为 | `DuckDuckGoSearchProvider` 补一条 `User-Agent` 请求头（第 20 节已交付并已验收的类） | **已报批**（2026-10-15，用户裁决「修」），证据见证据六·补 ⑤。**模型可见契约未变**——`SearchProvider` 接缝、工具、参数表、返回结构全部零改动，改的只是一条出站请求头；按②的惯例同样走「契约文档 + 验收报告双处留痕」 |
| ⑤ 第三方 API 核实 | Tavily `POST /search` | **已实测**：无 key → HTTP 401（端点路径正确）；带 key → HTTP 200，`results[].{title,url,content}`，`max_results` 服务端上限 20 |
| ⑥ 新增第三方依赖 | —— | **不触发**。Tavily 用 JDK 内置 `HttpClient`（`DuckDuckGoSearchProvider` 已用同款）；HTML 处理手写，**不引 Jsoup** |

**两条已确认不触发**：`spec.md` Assumptions 原第 3 条明写 200 条上限与 30 秒超时都是「**数值可调**」；FR-024 禁改的六件（`OryxTool` / `ToolResult` / `Sandbox` / `SandboxAction` / `ActionType` / `SandboxViolationException`）本次**一个都不碰**。

**一处计划外但同属本次交付物的修复（如实记录，见下文「证据三·补」）**：`web_fetch` 自己的重定向处理——实现时实测发现它按原样交付会在真实文档页上直接失败。改的是**本次新增的工具**，不涉及任何前序交付物。

## 证据一·补：`mvn clean verify` 全绿（含全部静态门禁）

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
./mvnw -o clean verify -Ddependency-check.skip=true
```

```
[INFO] Reactor Summary for OryxOS Parent 0.1.0-SNAPSHOT:
[INFO] OryxOS Parent ...................................... SUCCESS [  4.370 s]
[INFO] oryxos-storage ..................................... SUCCESS [ 13.456 s]
[INFO] oryxos-core ........................................ SUCCESS [  8.292 s]
[INFO] oryxos-provider .................................... SUCCESS [  5.123 s]
[INFO] oryxos-memory ...................................... SUCCESS [  2.469 s]
[INFO] oryxos-tool ........................................ SUCCESS [ 11.866 s]
[INFO] oryxos-channel-cli ................................. SUCCESS [  2.323 s]
[INFO] oryxos-web ......................................... SUCCESS [  2.215 s]
[INFO] oryxos-cli ......................................... SUCCESS [ 10.556 s]
[INFO] oryxos-boot ........................................ SUCCESS [  2.618 s]
[INFO] BUILD SUCCESS
```

各模块测试数（`surefire-reports` 实测）：`oryxos-storage` 9、`oryxos-core` 45、`oryxos-provider` 7、`oryxos-tool` **153**、`oryxos-cli` **24**，合计 **238**，`failures=0 errors=0 skipped=0`。

## 证据二·补：新增测试与守点

| 测试类 | 测试数 | 本次新增方法 | 守点 |
| --- | --- | --- | --- |
| `FileToolsTest` | 29 | `readFile_offsetAndLimit_returnsRequestedRange`、`readFile_overDefaultLimit_truncatesAndTellsHowToContinue`、`readFile_offsetBeyondEnd_returnsEmptyNote`、`readFile_offsetBelowOne_fails`、`grep_offset_pagesWithoutGapsOrOverlap`、`grep_contextLines_marksContextDifferentlyFromMatches`、`grep_contextLines_gapBetweenGroups_isMarked`、`glob_offset_pagesWithoutGapsOrOverlap` | 分页生效且**翻页不重不漏**（第 1 页末条与第 2 页首条恰好相接）、越界不抛异常、上下文行用 `-` 与命中行的 `:` 区分 |
| `ShellToolsTest` | 9 | `shell_customTimeout_isApplied`、`shell_timeoutAboveCap_isClamped`、`shell_timeoutBelowMinimum_fails` | 自定义超时真生效；上限 600 用包级私有的 `effectiveTimeout` 断言，**不真等 600 秒** |
| `HttpToolsTest` | 16 | `webFetch_htmlResponse_returnsPlainTextWithoutTags`、`webFetch_nonHtmlResponse_isReturnedVerbatim`、`webFetch_overlongBody_isTruncated`、`webFetch_outsideWhitelist_isBlockedBeforeIo`、**`webFetch_permanentRedirect_isFollowedToTheFinalPage`**、**`webFetch_relativeLocation_isResolvedAgainstCurrentUrl`**、**`webFetch_everyRedirectHop_passesThroughSandbox`**、**`webFetch_redirectLoop_givesUpAfterMaxHops`**、**`httpGet_doesNotFollowRedirect`** | 去标签 / 非 HTML 原样 / 超长截断 / 越界**一次请求都不发**；重定向跟随、相对 `Location` 解析、**每一跳重过沙箱**、撞环放弃、以及 `http_get` **不跟随**的有意差异 |
| `WebPageTextTest`（新） | 7 | `toPlainText_stripsTagsAndTheirContent` 等 7 条 | 去标签、块级标签转换行、实体解码、**转义过的标签保持文字**、实体解成 `$` 不炸替换、空白归一 |
| `TavilySearchProviderTest`（新） | 7 | `search_sendsAuthorizationHeader_andParsesResults`、`search_clampsLimitToApiMaximum`、`search_emptyResults_returnsEmptyList`、`search_non2xx_throwsWithoutLeakingKey`、`search_unreachableEndpoint_throwsUncheckedIOException`、`endpoint_matchesTheAddressActuallyRequested`、`defaultConstructor_targetsThePublicSearchEndpoint` | 鉴权头、请求体、响应解析、非 2xx；**异常消息里不含 key**；**不碰外网**（`mockwebserver3` 承接，SC-008） |
| `ToolAssemblyTest` | 5 | `searchProvider_withoutKey_fallsBackToDuckDuckGo`、`searchProvider_withKey_usesTavily` | 按 key 有无分流的两条分支 |
| `DuckDuckGoSearchProviderTest` | 6 → **7** | `search_sendsNonDefaultUserAgent` | **免 key 档的 UA**：非空、且不以 `Java-http-client` 开头（默认值实测被回 403，见证据六·补 ⑤） |
| `OryxToolContractTest` | — | 改名 `allThirteenToolsAreRegistered_includingNotifyFromLesson19` | 工具数 12 → 13（断言本身是 `>=`，不会红，但名字会失真，故一并改） |
| `ToolOnboardingCostTest` | — | `NEW_TOOLS` 加 `web_fetch`；正向对照加 `HttpTools` | FR-016「加工具成本恒定」的判定随新工具自动收紧 |

**无一处删断言、无一处 `@Disabled`、无一处放宽阈值。** 三处既有断言因新增可选参数而**补 `null` 实参**（`readFile(path)` → `readFile(path, null, null)` 等），断言语义**逐字未动**（`hasSize(201)` 等原样保留）。

## 证据三·补：五处改动的实际行为

**P0-1 `read_file` 分页 + 默认上限 2000 行**

- 不传分页参数且文件 ≤ 2000 行 → **走原 `Files.readString` 路径**，返回结果与加分页之前逐字节一致（既有调用方零感知）；
- 超过上限 → 返回前 2000 行 + 「（已截断：共 N 行…用 `offset=2001` 继续）」；
- **顺手修掉一个自己写出来的 bug**：按 `\n` 切分时，以换行结尾的文件会多出一个空元素，使「共 N 行」多算一行、并让一个恰好 2000 行的文件误走分页路径而丢掉末尾换行。新增 `lastLineIndex(...)` 统一口径（只影响**行数报法**，行号仍是第 i 行 = i，与 `grep` 一致）。

**P0-2 `shell` 超时可配**

`timeoutSeconds` 可选，默认仍是 30 秒（**默认值不变**——改默认值等于让既有 Agent 的时间预算静默变化），上限 600 秒（超出夹到 600），`< 1` 报不可重试失败。

**P0-3 `grep` / `glob` 翻页 + `grep` 上下文行**

页大小仍是 200；`offset` 是「已看过的命中条数」的全局序号。**又一个自己写出来的 bug**：截断判据原写成 `total > paged`，在第 2 页会误报一次并不存在的截断（total=300、本页 100 条已全部装下），改为 `total > offset + paged - 1`。

**P1-1 新增 `web_fetch`（第 13 个工具）**

`sandbox.enforce(HTTP_REQUEST, url)` 是每一跳的第一条语句；只在 `Content-Type` 含 `html` 时去标签（JSON / 纯文本里的 `<` 是数据不是标签）；上限 20000 字符。

**计划外的修复——跟随重定向，且每一跳重过沙箱。** 实现完成后的实测发现：`https://spring.io/blog/2025/05/22/spring-boot-3-5-0-available-now`（Spring 自己的文档域名）回 **HTTP 308**，按原实现直接报「HTTP 308」失败——一个抓网页的工具在 Spring 的域名上不可用，等于没做。

两条修法之间的取舍已定：**不用 `HttpClient.Redirect.NORMAL`**，自己写循环，理由是 ① `Redirect` 是**客户端级**设置，开了会连带改掉 `http_get` / `http_post` 的语义（POST 撞 301/302 还会被 JDK 改成 GET），那是动前序已交付的行为；② 客户端的自动跳转**不会再过一遍沙箱**——第 24 节的白名单落地后，一个白名单内的域名只要回一个 `Location` 指向任意主机，校验就被绕过去了。手动循环把 `enforce` 放在每一跳开头，这个洞从一开始就不存在（`webFetch_everyRedirectHop_passesThroughSandbox` 钉死）。上限 5 跳，撞环即放弃（`webFetch_redirectLoop_givesUpAfterMaxHops` 断言恰好 6 次请求）。

**P1-2 `TavilySearchProvider`（搜索接缝的第二档）**

`POST https://api.tavily.com/search`，头 `Authorization: Bearer <key>`，体 `{"query":..., "max_results": min(limit, 20)}`（`WebSearchTools` 传的是 200，超过服务端上限会被整单拒掉，故在实现里夹住而不是指望调用方知道别家 API 的脾气）；解析走既有的 `JsonParser`，**不新增 JSON 依赖**。装配处按 `oryxos.search.tavily-api-key` 有无分流（空 → 免 key 的 DDG，非空 → Tavily），两档都是 `SearchProvider`，故 `WebSearchTools` / 校验位 / 注册表**零改动**——FR-018 的第二次真实验证。配置键用 `${TAVILY_API_KEY:}`（**带空默认值**，搜索 key 可选，缺失不该让启动失败；与 `oryxos.providers[].api-key` 的必填占位不同）。

**顺手修完 P2 第 7 条**：`NotifyTools` 的 `@Tool` 补上 `name = "notify"`——回退机制恰好也产出 `notify`，但「工具名是模型引用它的唯一凭据，一旦交付不得改」，钉住名字让工具名与 Java 方法名解耦，成本一行。

## 证据四·补：前序节回归与 H4 不变量

| H4 不变量 | 本次自查 |
| --- | --- |
| ① 涉外 IO 首行过 `Sandbox.enforce` | `web_fetch` 的 `enforce` 是**每一跳**的第一条语句（`HttpTools.java:150`）；`read_file` / `grep` / `glob` / `shell` 的校验位未动 |
| ② 审计路径 | **未新增任何写入路径**；新工具经同一 `ToolExecutor` 落 `tool_invocations` |
| ③ 无明文 key | `grep -rni "tvly-\|tavily_api_key *=\|api-key: *[a-zA-Z0-9]" oryxos-*/src/main` → **0 命中**；配置是 `${TAVILY_API_KEY:}` 占位 |
| ④ `session_id` 只在会话服务内拼接 | 本次零触碰 |
| ⑤ 无 Reactor / `CompletableFuture` / 自建线程池 | `grep -rn "reactor\.\|CompletableFuture\|Executors\." --include=*.java oryxos-*/src` → **0 命中** |
| ⑥ 无 Spring AI 自动工具执行 | `ProviderService.java:125` `internalToolExecutionEnabled(false)` 一处，值仍为 `false` |

前序节测试（storage 9 / core 45 / provider 7）**全绿未动**——本次改动全部落在 `oryxos-tool` 与 `oryxos-cli`。

## 证据五·补：冻结接口零改动

```bash
git diff --numstat -- oryxos-core/src/main/java/io/oryxlabs/oryxos/core/tool/ \
  oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/sandbox/ \
  oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/notify/NotifyChannelAdapter.java \
  oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/search/SearchProvider.java
```

`OryxTool` / `ToolResult` / `Sandbox` / `SandboxAction` / `ActionType` / `SandboxViolationException` / `SearchProvider` **七件全部零改动**。`read_file` / `grep` / `glob` / `shell` 的**工具名**（`@Tool(name = ...)`）也一个都没改。

## 证据六·补：端到端真跑

### ① `tool list`（见上文 §5.2 的补强后转录）

`Tool（共 13 个）: builtin (13)`，含 `web_fetch`；`annotation (0)` / `mcp (0)` 不变；启动日志仍有那条 `PermissiveSandbox` WARN；退出码 0。

### ② 带 key 的搜索档：真搜到了（`TAVILY_API_KEY` 本机已设置）

```
TAVILY_API_KEY present=true len=41
endpoint=https://api.tavily.com/search
results=5
  - Upgrading to Spring Boot 3.5: What's Worth the Effort? | https://medium.com/devdomain/...
  - Spring Boot 3.5 Release Notes · spring-projects/spring-boot Wiki · GitHub | https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-3.5-Release-Notes
  - Spring Boot 3.5.0 available now | https://spring.io/blog/2025/05/22/spring-boot-3-5-0-available-now
```

### ③ 免 key 档对同一个查询返回 **0 条**（补强动机的原始证据）

```
$ curl -s -H "User-Agent: Mozilla/5.0" \
    "https://api.duckduckgo.com/?q=spring+boot+3.5+release+notes&format=json&no_html=1"
{"Abstract":"","AbstractSource":"","AbstractText":"","AbstractURL":"","Answer":"","AnswerType":"",
 "Definition":"","DefinitionSource":"","DefinitionURL":"","Entity":"","Heading":"","Image":"",
 "ImageHeight":"","ImageIsLogo":"","ImageWidth":"","Infobox":"","Redirect":"","RelatedTopics":[],
 "Results":[], ...
```

`RelatedTopics` 与 `Results` **都是空数组**——Instant Answer 是「问答」接口，长尾查询没有直接答案时就是空的。这是 P1-2 的直接依据。

### ④ `web_fetch` 真抓两个 URL（重定向修复前后对照）

```
== https://example.com/                                          success=true  contentLen=171
== https://spring.io/blog/2025/05/22/spring-boot-3-5-0-available-now
   修复前：success=false  err=HTTP 308
   修复后：success=true   contentLen=4354   （正文已去标签）
```

### ⑤ 免 key 搜索档的 UA 边界：**发现 → 报批 → 已修**（2026-10-15）

**发现**。补强过程中实测到免 key 档拿不到任何结果，三种走法逐一固定其它变量：

| 走法 | 结果 |
| --- | --- |
| 直连（无代理） | `HTTP connect timed out` |
| 经本机代理 | `HTTP 403`（`DuckDuckGoSearchProvider` 用 JDK 默认 UA `Java-http-client/21.0.12`） |
| 同一代理 + 浏览器 UA（curl） | `HTTP 202` + 空结果数组（即上文 ③） |

即：**同一个端点，换个 UA 就从 403 变 202**。直连超时是另一回事（本机网络），403 才是这道门。

**报批**。`DuckDuckGoSearchProvider` 是第 20 节已交付并已验收的类，按纪律**不擅自改**——加一个 `User-Agent` 头是行为变更，**软门禁 ②**。报告后用户裁决：**修**。

**修法与验证**。显式带 `User-Agent: Mozilla/5.0 (compatible; OryxOS/0.1; +https://github.com/wngbob/oryxos-wngbob)`（`DuckDuckGoSearchProvider.java` 的 `USER_AGENT` 常量；写成「浏览器前缀 + 自我介绍」——前缀是对端分流认的部分，后半段让对方的运维能查回来）。修后同一个 `Check3` 程序、同一条代理参数实测：

```
$ java -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890 -cp ... Check4
query=[Alan Turing] results=5
  - Alan Turing | https://en.wikipedia.org/wiki/Alan_Turing
  - Official site - Alan Turing | http://www.turingarchive.org/
  - Alan Turing Category | https://duckduckgo.com/c/Alan_Turing
  - Peter Hogg , Turing's friend from Sherborne. | https://duckduckgo.com/Peter_Hogg_(Shiburnian)
  - Computability theorists | https://duckduckgo.com/c/Computability_theorists
query=[spring boot 3.5 release notes] results=0
```

**403 消失，真结果回来了**。第二行同时划清了这次修复的边界：长尾查询仍 0 条——**那是 Instant Answer 是问答接口、不是检索接口，与 UA 无关**，那部分由 P1-2 的带 key 档解决（上文 ② 实测 5 条真结果）。两件事不是一回事，别混。

单测 `search_sendsNonDefaultUserAgent` 钉死「UA 非空且不以 `Java-http-client` 开头」。带 key 档（Tavily）实测不带 UA 也正常，**未动**——不为对称而改一个跑得好好的类。

## 补强·追加：人工验收抓到的一处缺陷（**已修 + 已补测试**，2026-10-15）

> 这条与上文 P0/P1 补强同属 2026-10-15 的第二次交付，但它不是「补深度」而是「修缺陷」，且**由人工验收而非 harness 发现**，故单列。

### 缺陷

`oryxos tool list` 的 MCP 组内子分组表头打成 `mcp (2):`，而不是契约 §5.2 要求的 `mcp:<server名> (N):`。多个 server 的工具全挤在一个表头下，**从输出里分不出哪个工具来自哪个 server**。

根因在 `ToolListCommand.printGroup()`：组内那一层复用了顶层分组用的 `group(...)`，而 `group` 的键是来源的**前缀**（`mcp:time` → `mcp`）。组内每个条目的前缀**都已经等于** `mcp`，按前缀分只会得到唯一一个键。

### 为什么 harness 没抓到

`specs/005-tool-registry/` 交付时 `oryxos-cli/src/test` 只有契约测试、装配测试两个文件，**§5.2 的输出格式一条断言都没有**。而且默认配置下 MCP 是空清单（`mcp (0):`），**MCP 子分组那一层根本没被执行过**——只有真的接上 ≥1 个 MCP server 才看得见。这是「没有测试守着的格式 = 会悄悄坏掉的格式」。

### 处置（四步）

1. **修**：新增 `bySource(...)`（键 = 来源**全串**），`printGroup()` 改调它；`group(...)` 保留给顶层三组，javadoc 写明「只用于顶层」。两处看着只差一个 `prefix(...)`，却是「分开了」与「没分开」的分界——`bySource` 的 javadoc 已把这个反例写进去，防止后人「顺手合并」。
2. **补测试**：新增 `oryxos-cli/src/test/java/io/oryxlabs/oryxos/cli/command/ToolListCommandTest.java`，5 条，覆盖两 server / 单 server / builtin 不平铺成子表头 / 空组说明 / 首行总数。用反射调私有的 `print(List)` + `System.setOut` 取输出——`print` 是纯函数式的一层，为验一个格式串起 Spring 上下文不值；也**没有**把 `print` 放宽成包级私有（对已交付类的可见性触碰越小越好）。
3. **证明测试有牙**：把修复**临时回退**后重跑 → `Tests run: 5, Failures: 3`（`print_mcpToolsFromTwoServers_headsEachServerByName`、`print_mcpToolsFromOneServer_headsByServerName`、`print_header_showsTotalCount` 三条红），随后恢复。**不这样做，「补了测试」只是句声明。**
4. **留痕**：本节 + [manual-test-guide.md](manual-test-guide.md) §4 双处记录。

### 边界

- 命令名 / 参数 / 退出码三项**一个都没碰**（契约 §1 要求走修订程序的正是这三项）。
- 软门禁 ② 触发（改的是第 20 节已交付类的输出行为）——**本次改动已随第 20 节一次性提交，用户在本次批次中明确授权**。
- `ToolListCommand` 在未跟踪目录之外的已跟踪文件里，`git diff` 对它**有**输出，与 `DuckDuckGoSearchProvider` 那条（在未跟踪目录里、`git diff` 无输出）不同。

## 变更总结（给 reviewer 的导读）

### 一、改动点

| 文件 | 变更 | 动机 |
| --- | --- | --- |
| `oryxos-tool/.../builtin/FileTools.java` | 改 | `read_file` 加 `offset`/`limit` + 2000 行上限；`grep` 加 `offset`/`contextLines`；`glob` 加 `offset`；修两处自己写出的 bug（幻影尾行、翻页截断误报） |
| `oryxos-tool/.../builtin/ShellTools.java` | 改 | `shell` 加 `timeoutSeconds`（默认 30 不变、上限 600）；`run(...)` 多收一个超时参数 |
| `oryxos-tool/.../builtin/HttpTools.java` | 改 | 新增 `web_fetch`（第 13 个工具）：去标签 + 超长截断 + **手动重定向循环、每一跳重过沙箱**；`http_get` / `http_post` 行为逐字节不变 |
| `oryxos-tool/.../builtin/WebPageText.java` | **新增**（144 行，包级私有） | 极简 HTML → 纯文本；不引 Jsoup（软门禁 ⑥）。包级私有故**不进对外概念清单**（软门禁 ①） |
| `oryxos-tool/.../search/TavilySearchProvider.java` | **新增**（154 行） | 搜索接缝第二档（真检索 API）；key 不进日志/异常 |
| `oryxos-tool/.../search/DuckDuckGoSearchProvider.java` | 改（+1 个常量、+1 行请求头、javadoc） | **前序节交付物被本次触碰**：补 `User-Agent`（软门禁 ② 已报批）。不加则免 key 档对所有部署环境都拿不到结果 |
| `oryxos-cli/.../CliAssemblyConfiguration.java` | 改 | `searchProvider()` 按 key 有无分流（原来是硬编码 DDG） |
| `oryxos-boot/src/main/resources/application.yaml` | 改（+5） | 新增 `oryxos.search.tavily-api-key: ${TAVILY_API_KEY:}`（**带空默认值**） |
| `oryxos-tool/.../notify/NotifyTools.java` | 改 | P2 第 7 条：`@Tool` 补 `name = "notify"` |
| 测试 8 个文件 | 改 / 新增 | 见证据二·补 |
| `oryxos-cli/.../command/ToolListCommand.java` | 改（1 行 + 1 个新方法） | **前序节交付物被本次触碰**：MCP 组内子分组改按**来源全串**分（`bySource`），原来按前缀分导致多个 server 塌成一个 `mcp` 表头。见本节末「补强·追加」 |
| `oryxos-cli/src/test/.../ToolListCommandTest.java` | **新增**（5 条） | 上面那处缺陷的回归守点——本节交付时 `oryxos-cli/src/test` 一条输出格式断言都没有 |
| `specs/005-tool-registry/` 7 份文档 + `CLAUDE.md` | 改 | 契约、spec、plan、quickstart、tasks、assembly、本报告 + 项目上下文：工具数 12→13、测试数 195→238、新增参数与上限、四处新字面量、P2 七条 |

### 二、重点 review 清单（按风险排序）

1. **重定向的手动循环与「每一跳重过沙箱」**——`HttpTools.java:148-201`。这是本次唯一有安全含义的决策。若改成 `HttpClient.Redirect.NORMAL`，第 24 节的白名单会被 `Location` 绕过（且 `http_post` 的语义会被 JDK 改掉）。`webFetch_everyRedirectHop_passesThroughSandbox` 是这条的守门测试。
2. **`read_file` 的旧路径保留**——`FileTools.java` 的 `readFile`。小文件走 `Files.readString` 原样返回，是「不改变既有调用方可见行为」的关键；删掉这条快路径会让既有测试的逐字节断言失效。
3. **`Integer` 而非 `int`**——所有新增的可选参数。`AnnotatedToolAdapter` 对省略的 `required=false` 参数传 `null`，`int` 形参会直接 NPE。这是 Spring AI schema 那条坑（`PROPERTY_REQUIRED_BY_DEFAULT`）的连带面。
4. **搜索档的分流点**——`CliAssemblyConfiguration.searchProvider()`。判据是「key 是否为空」，不是 profile；空 key 必须回退免 key 档，否则「装上就能跑」这条原则在没配 key 的机器上破掉（`ToolAssemblyTest` 两条分支各一）。
5. **`WebPageText` 的处理顺序**——先剥标签、再解实体。反过来会让页面里写成 `&lt;script&gt;` 的**文字**被解码成真标签连内容一起吃掉。
6. **一处未修的边界**——免 key 搜索档的**长尾查询仍返回 0 条**（证据六·补 ⑤ 末段）。这不是故障、也不在本次改动内：`api.duckduckgo.com` 的 Instant Answer 是**问答**接口不是检索接口，长尾查询本就没有答案条目。该缺口由带 key 档（Tavily）覆盖，**不修免 key 档**。（注：同一条证据里的 UA 403 是**另一件事**，已报批并修完。）
7. **`tool list` 的 MCP 子分组**——`ToolListCommand.java` 的 `bySource`。缺陷由人工验收发现、修复后补了 5 条回归测试（见「补强·追加」）。review 时注意 `group` 与 `bySource` **看着像重复但不可合并**：键一个是前缀、一个是全串，合并回去就是原缺陷。

### 三、如何验证

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
cd /home/wngbob/oryxos-wngbob

# 1) 全量门禁（含 Spotless / P3C / Checkstyle / SpotBugs / FindSecBugs）
./mvnw -o clean verify -Ddependency-check.skip=true
# 预期：BUILD SUCCESS；10 模块全 SUCCESS；合计 238 tests, 0 failures, 0 errors

# 2) 本次改动的模块（单模块命令 MUST 带 -am）
./mvnw -o test -pl oryxos-tool -am
# 预期：Tests run: 153, Failures: 0, Errors: 0
./mvnw -o test -pl oryxos-cli -am
# 预期：Tests run: 24, Failures: 0, Errors: 0

# 3) 关键回归单测
./mvnw -o test -pl oryxos-tool -am -Dtest=FileToolsTest,ShellToolsTest,HttpToolsTest,WebPageTextTest,TavilySearchProviderTest,DuckDuckGoSearchProviderTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -o test -pl oryxos-cli -am -Dtest=OryxToolContractTest,ToolAssemblyTest,ToolOnboardingCostTest -Dsurefire.failIfNoSpecifiedTests=false
# 预期：全绿

# 4) 工具数（端到端）
java -jar oryxos-boot/target/oryxos.jar tool list
# 预期：Tool（共 13 个）: builtin (13) / annotation (0) / mcp (0)，含 web_fetch，
#       启动日志一条 PermissiveSandbox WARN，退出码 0

# 5) 工具名一个都没改（软门禁 ② 的机器化核对）
grep -rho '@Tool(name = "[a-z_]*"' oryxos-tool/src/main/java | sort | uniq -c
# 预期：13 条，名字与第 20 节交付时逐一相同 + 新增 web_fetch

# 6) H4 不变量
grep -rn "reactor\.\|CompletableFuture\|Executors\." --include=*.java oryxos-*/src   # 预期：零命中
grep -rn "internalToolExecutionEnabled" --include=*.java oryxos-*/src/main           # 预期：ProviderService:125 一处，false
grep -rni "tvly-\|tavily_api_key *=" --include=*.java --include=*.yaml oryxos-*/src/main  # 预期：零命中
```

### 剩余人工项

**状态（2026-10-15）**：下表 5 项**已全部人工跑完**；完整步骤、命令与判据见 [manual-test-guide.md](manual-test-guide.md)（M1~M12 共 12 项，记录表见其 §5）。

| # | 项 | 判定 | 结果 |
| --- | --- | --- | --- |
| 1 | `chat` 里让模型 `read_file` 一个 3000 行文件 → 看到截断说明并**续读** | 需真实 LLM key + 人工对话 | ✅ 模型调两次（第二次 `offset=2001`），引述了截断说明 |
| 2 | `chat` 里 `shell "sleep 40"` 配 `timeoutSeconds=10` → 应超时（默认 30 秒下不会） | 同上 | ✅ 约 10 秒返回 `命令超时（10 秒）`，审计 `success=0` |
| 3 | `chat` 里 `web_fetch <一个文档页 URL>` → 返回去标签正文 | 同上 | ✅ 取到 `Example Domain` 正文，标签已去 |
| 4 | 在**没配** `TAVILY_API_KEY` 的机器上跑 `web_search`（免 key 档） | 机器级验证（本机已实测可用：`Alan Turing` → 5 条，见证据六·补 ⑤）。**注意长尾查询会返 0 条，那是 Instant Answer 的性质、不是故障** | ✅ 本机 28 条，首条 Wikipedia；无 403 |
| 5 | 沙箱窗口期团队确认（第 20 节原样结转） | 人工确认，双处留痕 | ✅ 已确认 |
| 6 | **课件的「方式一」不在本节范围（补记，避免验收时误判为漏做）** | 课件「五、做完怎么验」第 1 条要求「写一份 SKILL.md + 连一个真实 MCP server」。**本节只到「配置里声明 MCP server」这一步**——SKILL.md 与 `skills/` 软连接属 **Skill 绑定**，归**第 29 节**（`spec.md` 冲突仲裁 8；`ProfileLoader` 既有禁令已把 `skills` 钉死为不可出现在 `AGENT.md` frontmatter，与 `notify_channels` 同一条）。 | 本节只验**方式二**（真 MCP server）与**方式三**（`@Tool` Bean），方式一不在此列，**不是漏验** |

### P2 记录（只记不做，七条）

写进 `CLAUDE.md` 的「第 20 节 P2 挂账」，逐条附不做理由：

| # | 项 | 不做的理由 |
| --- | --- | --- |
| 1 | `apply_patch` 式批量多文件编辑 | `edit_file` 的唯一匹配约束已够用，**YAGNI** |
| 2 | 计划 / 进度工具（`update_plan` 等价物） | 要在 `sessions` 表落状态并改 `PromptBuilder`，是**跨节改动**，不该塞进工具节 |
| 3 | 后台 shell + 输出拉取（`BashOutput` / `KillShell` 等价物） | **违反核心阶段同步模型**（章程原则 8），归扩展阶段 |
| 4 | 图片 / PDF 读取 | 需 VLM |
| 5 | MCP resources / prompts | 目前只接了 MCP 的 tools，协议面另一半 |
| 6 | 危险操作审批层（Claude Code permission mode / Codex approval mode） | 白名单之外的中间层，归第 24 节之后评估 |
| 7 | `notify` 未用 `@Tool(name = "notify")` 钉名 | **本次已顺手修完**（成本一行），记在此处留痕 |

## 备注

1. **本节交付 + 两次补强（P0/P1 补深度、`tool list` 修缺陷）已在同一批次 commit / merge 到 main / push**——按 skill 纪律同步时机由用户决定，本批次由用户明确授权（「运行 package.sh / commit / merge 当前分支到 main / push」）。**代价如实记下**：三次改动**同在工作区**，因此无法用 `git diff --stat` 把「第 20 节交付」与「两次补强」分开——这是折进第 20 节的直接代价。
2. **中途抓红并修复**（按 skill 第 7 步第 8 项要求如实记录）：本次 `mvn clean verify` 抓到 **2 条 P3C `UndefineMagicConstantRule`**（`WebPageText:129` 的 `"\n"`、`TavilySearchProvider:123` 的 `"results"`——该规则禁止字符串字面量出现在 `if` / `for` 的条件里），已提成命名常量（`LINE_BREAK` / `RESULTS_FIELD` 等）后重跑全绿；另抓到 **Spotless 格式违规 2 处**（javadoc 折行、方法签名换行），跑 `./mvnw spotless:apply` 后重跑全绿。**无一处删断言、无一处 `@Disabled`、无一处放宽阈值。**
3. **两处自己写出来的 bug，都在测试阶段被抓到并修掉**：① `GrepHits.toResult()` 的截断判据 `total > paged` 在第 2 页会误报截断；② `split(LINE_SEPARATOR, -1)` 的幻影尾行让「共 N 行」多算一行。两处都由新写的翻页测试抓出——这正是「翻页不重不漏」这条守点的价值。
4. **`docs/class/` 自 2026-10-14 起随仓库跟踪**（课件已入库），本节不涉及课件改动。
5. **一处边界已报批并修完**（证据六·补 ⑤，免 key 搜索档的 UA）：先报批、后动手，**改的是第 20 节已交付的类**（软门禁 ②），修后实测 403 消失、真结果返回。同时划清边界——**长尾查询仍 0 条与 UA 无关**，那是 Instant Answer 的性质，归 P1-2 的带 key 档解决。
6. **本次对「已交付并已验收的类」的触碰共两处**：① `DuckDuckGoSearchProvider` 的 `User-Agent`（免 key 档 403，软门禁 ② 已报批）② `ToolListCommand` 的 MCP 子分组（人工验收抓到的缺陷，见「补强·追加」）。其余改动全部落在本次新增 / 新补强的文件上。
   - **两处的可追溯性不同，别混**：`ToolListCommand.java` 是第 18 节交付的**已跟踪**文件，`git diff` 对它**有**输出，可直接核；而 `DuckDuckGoSearchProvider.java` 在第 20 节**新引入且当时未跟踪**的目录 `oryxos-tool/.../tool/search/` 里，首次 commit 前 `git diff` 对它无输出——判定依据只能是本条备注与「软门禁报备」表里的留痕，**MUST NOT** 拿 `git diff --stat` 当这一处的证据（同备注 1 的口径）。
7. **反作弊自查**：本批次三次交付全程**无一处删断言、无一处 `@Disabled`、无一处放宽阈值**。唯一一次「让测试变红」是**有意为之**——临时回退 `tool list` 的修复以证明新测试有牙（3 条红），随即恢复。**未全绿不得宣称完成**这条守住了：`mvn clean verify` 在提交前实跑，238 条全绿。
