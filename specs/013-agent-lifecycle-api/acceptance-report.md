# 验收报告：第 30 节 动态管理 Agent

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md) | **Tasks**: [tasks.md](./tasks.md) | **Quickstart**: [quickstart.md](./quickstart.md)
**分支**: `class-30` | **日期**: 2026-10-21 | **任务完成度**: **51 / 51**
**结论**: 全量 `mvn clean verify` 全绿（**723** 条测试，0 失败），课件 harness 的六个测试类全部存在且非空、关键回归点逐条对号，quickstart 的 mock 路径（Step 0~5、7、8 的非浏览器部分，以及 Step 6 的 503 分支）已在真 fat JAR 上端到端跑通；剩余「真模型一句话生成」与「浏览器目视走查」两项为待人工项。

> **2026-10-22 修订注**：上句「两项为待人工项」已不成立——用户当日按向导完成 Step 6（真模型一句话生成）与 Step 8（浏览器全流程），**验证通过**。用户在 Step 8 实跑中命中两处产品缺陷（8.1 的 `vunknown`、8.5 的「Agent 删了定时任务还挂在列表里」），均已修复并复验，见 §五；修复后全量门禁 **727** 条全绿。原文保留作交付时的状态记录。

---

## 一、六项证据

### 证据 1：`mvn clean verify` 全绿

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
./mvnw clean verify
```

**结果**：`BUILD SUCCESS`，Spotless / P3C(PMD 6.55) / Checkstyle / SpotBugs / FindSecBugs / OWASP Dependency-Check 全部通过。

测试总数按 surefire XML 聚合（不取控制台散文，避免多模块并行输出串行）：

| 模块 | 测试数 |
|---|---|
| `oryxos-tool` | 254 |
| `oryxos-core` | 161 |
| `oryxos-cli` | 104 |
| `oryxos-memory` | 90 |
| `oryxos-web` | 89 |
| `oryxos-storage` | 14 |
| `oryxos-provider` | 11 |
| **合计** | **723**（102 个测试类，0 failures / 0 errors） |

**较第 29 节的增减**：665 → 723 = **+58**，逐项可对账：

| 项 | 条数 |
|---|---|
| 新增 `AgentLifecycleServiceTest` | +12 |
| 新增 `WorkspaceWatcherTest` | +5 |
| 新增 `GenerateTest` | +7 |
| 新增 `AgentFixedSessionTest` | +4 |
| 新增 `PerAgentMemoryScopeTest`（memory 模块） | +3 |
| 新增 `WorkspaceApiControllerTest` | +10 |
| 新增 `GlobalMemoryEndpointRemovedTest` | +2 |
| `AgentApiControllerTest` 4 → 20 | +16 |
| `AdminConsoleSourceTest` 8 → 10 | +2 |
| 删除 `MemoryApiControllerTest`（随 `MemoryApiController` 整类删除） | −3 |
| **净增** | **+58** |

### 证据 2：课件 harness 测试类映射与对号

| 课件 harness 类 | 本仓落点 | 条数 | 关键回归测试（方法名） |
|---|---|---|---|
| `AgentLifecycleServiceTest` | `oryxos-core/src/test/java/.../core/agent/AgentLifecycleServiceTest.java` | 12 | 按序执行 / name 冲突第一步就拒且**一个目录都不写** / 注册失败**回滚已写目录** / create 与 watcher 走同一段 `register(Path)` / delete 顺序为「注销定时 → 移出索引 → 归档」/ update 改 `schedules` 先 `unregister` 后 `register` |
| `WorkspaceWatcherTest` | `.../core/agent/WorkspaceWatcherTest.java` | 5 | 手工丢目录免重启出现 / 删目录注销 / **坏目录不拖垮监听** / `cp -a` 竞态有界重试 / 单条守护线程 |
| `WorkspaceApiControllerTest` | `oryxos-web/src/test/java/.../controller/WorkspaceApiControllerTest.java` | 10 | tree 根只有 `agents` / `archive` / `file?path=../../etc/passwd` → 400 / 正常读 / 写文件防穿越 / 写 `AGENT.md` 走 update 路径 |
| `AgentApiControllerTest` | `oryxos-web/.../controller/AgentApiControllerTest.java` | 20（原 4） | 端点薄转发 / 冲突 400 / 不存在 404 / 统一信封 / 固定会话消息累积 |
| `GenerateTest` | `oryxos-core/.../core/agent/GenerateTest.java` | 7 | `generatedDraftParsesWithExistingLoader` / `generationDoesNotWriteOrRegister` / `codeFencesAreStripped` / `invalidGeneratedMarkdownIsRejectedWithReadableReason` / `blankAuthorModelThrowsAuthorUnavailable` / `generationUsesAuthorConfigAsCaller` / `promptDocumentMatchesConstant` |
| （FR-007 追加）`PerAgentMemoryScopeTest` | `oryxos-memory/src/test/java/.../PerAgentMemoryScopeTest.java` | 3 | 有上下文写 `agents/<name>/MEMORY.md` / 无上下文回退全局 / 两个 Agent 互不串 |
| （端点删除追加）`GlobalMemoryEndpointRemovedTest` | `oryxos-web/.../controller/GlobalMemoryEndpointRemovedTest.java` | 2 | `GET /api/v1/memory` → 404；`MemoryApiController` 类型已不存在 |
| （`AdminConsoleSourceTest` 判据反转） | `oryxos-web/src/test/java/.../AdminConsoleSourceTest.java` | 10（原 8） | 写口只允许 `ProfilesView.vue` 且路径限 `/api/v1/agents/` 与 `/api/v1/workspace/file`；其余视图逐文件禁写 |

> `GenerateTest` 比 spec 点名的多一条 `generationUsesAuthorConfigAsCaller`——它判「生成用 provider/model 来自 `oryxos.author.*` 而非被生成 Agent 的配置」，是 FR-004 的必要一条，**不是凑数**。
> `urlEncodedTraversalIsRejected` 里有一处 MockMvc 细节：`?path=../../etc/passwd` 直接传会被 `UriComponentsBuilder` 规范化掉，故用 `%2F%2E%2E` 编码形态才能让穿越真正到达控制器；测试注释已写明，避免后来者误以为断言没生效。

### 证据 3：本节交付物逐项存在性核对

```bash
ls oryxos-core/src/main/java/io/oryxlabs/oryxos/core/agent/
# AgentAuthorPrompt.java  AgentAuthorUnavailableException.java  AgentLifecycleService.java
# AgentStore.java  AgentView.java  FileNode.java  WorkspaceWatcher.java
ls oryxos-core/src/main/java/io/oryxlabs/oryxos/core/tool/ToolExecutionContext.java
ls oryxos-web/src/main/java/io/oryxlabs/oryxos/web/controller/WorkspaceApiController.java
ls oryxos-web/src/main/java/io/oryxlabs/oryxos/web/controller/dto/
# CreateAgentRequest  FileContent  GenerateFilesRequest  SaveFilesRequest  UpdateAgentRequest  WriteFileRequest
ls docs/prompt/prompt.md
grep -rnE "class AgentLoader([^A-Za-z0-9_]|\$)" oryxos-*/src   # → 零命中（MUST NOT 新建第二真相源；必须带词边界——不带会误命中第 29 节测试类 AgentLoaderTest，那是判据自身的假阳性）
grep -rn "MemoryApiController" oryxos-*/src/main   # → 零命中（已整类删除；test 侧 4 处命中是 GlobalMemoryEndpointRemovedTest 与 MockProviderFlowTest 有意引用该名字来断言「它不存在」，属判据的一部分）
grep -c "agent-author:" oryxos-core/src/main/java/io/oryxlabs/oryxos/core/agent/AgentLifecycleService.java
```

端点核对（真进程读 OpenAPI）：`/v3/api-docs` 实测 **21 个 path / 27 个 operation**，与「15 − 1 + 13 = 27」一致；`ApiController` 实现类 **7 个**（Session / Agent / Profile / Workspace / Tool / Schedule / System）。

### 证据 4：前序节回归（跨节契约）

第 16~29 节的测试**全部同一次 `mvn clean verify` 内跑过**，无跳过、无 `@Disabled` 新增。跨节改动面只有五处，且每一处都有对应回归：

| 前序节类 | 改动 | 为什么必须改 |
|---|---|---|
| `ToolExecutor` | 新增私有 `executeWithAgentContext`，读 `ProfileContext.current()` 后包一层 `ToolExecutionContext` | `OryxTool.execute(JsonNode)` 签名 MUST NOT 改动，Agent 名只能走 ThreadLocal |
| `ProfileLoader` | 新增 `parse(String agentMarkdown, String agentName)` | 更新/保存路径要在**不落临时文件**的前提下复用同一套校验 |
| `AgentScheduler` | 新增 `unregisterProfile(Profile)` | 改 `schedules` 必须先注销旧句柄（第 29 节铺好的句柄表） |
| `MemoryServiceImpl` | 委托后端前后置入/复原 Agent 名 | 门面签名不变，FR-007 只改 Markdown 档落点 |
| `MarkdownMemoryStore` | `memoryFile()` 每次重新解析路径 | 缓存一次就会把 A 的记忆写进 B |

五个内置工具文件（`FileTools` / `ShellTools` / `HttpTools` / `WebSearchTools` / `NotifyTools`）`git diff` **为空**；`MemoryStoreContractTest` 仍全绿（它跑在无上下文环境里，行为与今天一致）。

前序节测试代码的适配共 5 个文件（`MockAgentE2ETest` / `MockProviderFlowTest` / `ScheduledTaskE2ETest` / `SchedulerAssemblyTest` / `MemoryServiceImplTest`）：改动原因统一是**记忆从全局单份变为按 Agent 隔离**，断言从「全局 `MEMORY.md` 多一行」改为「按 Agent 那一份多一行」——**断言条数未减、强度未降**。

### 证据 5：H4 六条全局不变量自查

| # | 不变量 | 结论 |
|---|---|---|
| ① | 涉外 IO 首行过 `Sandbox.enforce` | 五个内置工具文件零改动；本节无新增涉外工具。管理台写文件走 `AgentStore.resolveSafe`（工作区根内），非模型可指定目标 |
| ② | LLM 成败落 `llm_calls`、工具成败落 `tool_invocations` | 生成路径走既有 `LlmClient.chat(...)`（同一审计路径），`session_id` 前缀 `agent-author:`，实测有行；无新增绕过路径 |
| ③ | 无明文 key | `oryxos.author.provider` / `oryxos.author.model` **只存名称**；凭证仍只走 `oryxos.providers[].api-key`，未引入第二处 key；`grep -rn "sk-" ` 无命中 |
| ④ | `session_id` 只在 `SessionManager` 内拼接 | 固定会话 `admin:console:<name>` 由 `SessionService` 既有拼接规则产出，本节未自行拼串；`agent-author:` 仅作审计用 session id，沿用第 27 节 `mock` 先例 |
| ⑤ | 无 Reactor / `CompletableFuture` / 自建线程池 | 唯一线程是 `WorkspaceWatcher` 的**一条** daemon `Thread`（章程原则 8：基础设施不得把异步模型带进请求链路），P3C 豁免已就地注明理由 |
| ⑥ | 无 Spring AI 自动工具执行 | 未触碰 `ToolExecutor` 的 tool 调度路径，仅在其外层加 ThreadLocal 包装 |

### 证据 6：quickstart 执行结果

在 `/tmp/oryxos-l30-pZKY`（临时工作区，已 `rm -rf`）上用真 `oryxos.jar` 跑：

| Step | 结果 |
|---|---|
| 0 | `init` 输出含「数据库表结构」；`.oryxos/archive/` 存在 |
| 1 | `POST /agents` → `code:200`；**不重启** `GET /agents` 立刻可见；目录四件套齐全；重名 → 400 `Agent 已存在`；`../evil` → 400 |
| 2 | `PUT` 覆写后 `providerName=mock`、`schedules` 一条；到点自动跑，`runCount:1` / `lastStatus:success`；`sessions` 出现 `scheduler:...:weather-demo`；Agent 目录出现 `MEMORY.md`；连续两次 PUT 不产生重复任务、`runCount` 不双倍增长 |
| 3 | `cp -a` 丢目录 → **6 秒内**出现在 `/agents`（未重启）；`rm -rf` → 注销、详情 404，且**后续 75 秒 `runCount` 冻在 2**（句柄确已取消） |
| 4 | tree 根只有 `agents` / `archive`；`path=../../etc/passwd` → **400** 且响应不含文件内容；写 `REFERENCE.md` 成功；写坏 frontmatter 的 `AGENT.md` → 400 且文件 md5 不变 |
| 5 | 两次 `GET session` 的 `sessionId` 恒为 `admin:console:weather-demo`；消息累积；记忆端点与 `MEMORY.md` 一致；`weather-demo` 与 `other-demo` 两份记忆互不串；`GET /api/v1/memory` → **404** |
| 6（503 分支） | `oryxos.author.model` 留空 → **503**「未配置作者模型」，且 `llm_calls` 中 `agent-author:%` 行数 **仍为 0**（一次模型请求都没发） |
| 7 | DELETE 200；列表无 `weather-demo`、详情 404；目录在 `.oryxos/archive/weather-demo/`；历史 `llm_calls` / `tool_invocations` 仍在库 |
| 8（非浏览器部分） | `/admin/`、`/admin/agents`、`/swagger-ui/index.html` 均 200；`/v3/api-docs` 27 个 operation |

---

## 二、变更总结（给 reviewer 的导读）

### 2.1 改动点（按模块）

`git diff --stat`：**38 个已跟踪文件变更，+1869 / −268**；另有 **23 个新增（未跟踪）源文件**与 `specs/013-agent-lifecycle-api/` 整目录（core 主代码 8 + core 测试 4 + memory 测试 1 + web 主代码 7 + web 测试 2 + `docs/prompt/prompt.md` 1）。

**`oryxos-core`（新增 7 个类 + 改 4 个既有类）**

- 新增 `core/agent/`：`AgentLifecycleService`（生命周期编排，**两条录入路径一段代码**）、`AgentStore`（工作区磁盘读写收口，**路径守卫只有 `resolveSafe` 一处**）、`WorkspaceWatcher`（JDK `WatchService` 单条 daemon 线程 + 有界重试）、`AgentView` / `FileNode`（对外视图）、`AgentAuthorPrompt`（系统提示词常量）、`AgentAuthorUnavailableException`（→503）。
- 新增 `core/tool/ToolExecutionContext`：`ThreadLocal` + `callAs(name, supplier)`，`finally` 复原。
- 改 `ToolExecutor`：新增私有 `executeWithAgentContext`，把工具执行包进上下文，**`OryxTool` 签名零改动**。
- 改 `ProfileLoader`：新增 `parse(text, name)`，复用既有 `toProfile` 校验。
- 改 `AgentScheduler`：新增 `unregisterProfile(Profile)`，委托 `cancelTasksOf`。

**`oryxos-memory`（改 2 个类，SPI 零改动）**

- `MemoryServiceImpl`：只在委托后端前后置入/复原 Agent 名。
- `MarkdownMemoryStore`：`memoryFile()` 每次调用重新解析（`.oryxos/agents/<name>/MEMORY.md`，无上下文回退全局）。

**`oryxos-web`（新增 1 控制器 + 6 DTO + 前端大改，删 2 个类）**

- 新增 `WorkspaceApiController` + `dto/{CreateAgentRequest,UpdateAgentRequest,GenerateFilesRequest,SaveFilesRequest,WriteFileRequest,FileContent}`。
- `AgentApiController`：由「只有 invoke」扩成生命周期主控制器（+251 行）。
- **删除** `MemoryApiController` 及其测试（全局记忆概念已不存在）。
- `GlobalExceptionHandler`：补 503 映射与可读原因。
- 前端：`ProfilesView.vue` 重写为「列表 + 新建 + 详情五页签」（+806 行）；`MemoryView.vue` 与 `/memory` 路由删除；`api.js` 增 `del()`；导航回到 6 项。

**`oryxos-cli` / `oryxos-boot`（装配与配置）**

- `CliAssemblyConfiguration`：+113 行，装配 `AgentLifecycleService` / `AgentStore` / `WorkspaceWatcher` 与作者配置解析。
- `ServeCommand` / `GatewayCommand`：各 +3 行（启动 watcher）。
- `InitCommand`：+10 行（`archive/` 目录）。
- `Workspace`：+10 行（`agents/` / `archive/` 根解析）。
- `application.yaml`：+7 行，新增 `oryxos.author.provider` / `oryxos.author.model`（均带空默认值）。

**文档/对外**

- `README.md`、`website/{,.vitepress/theme,en/guide,guide}`、`CLAUDE.md` 同步第 30 节状态与 723 测试数。
- 新增 `docs/prompt/prompt.md`（生成用系统提示词，由 `GenerateTest.promptDocumentMatchesConstant` 判与常量**逐字一致**）。
- `specs/006-memory-service/{spec.md,acceptance-report.md}`：挂 2026-10-21 修订注（记忆不再全局单份），原文保留作决策史。

### 2.2 重点 review 清单（按风险排序）

1. **`AgentStore.resolveSafe` 是唯一路径守卫**（`oryxos-core/.../core/agent/AgentStore.java`）。三道判据：空白拒 / 绝对路径拒 / `normalize()` 后必须仍以工作区根为前缀；`agents/<name>` 的目录名同样过守卫；`tree()` 只遍历两棵子树且**不跟随软连接**（`skills/<name>` 正是软连接）。任何新增的写盘路径都必须走它——绕过一处就等于打开一次目录穿越。
2. **`AgentLifecycleService.create` 的失败回滚**（同文件 `create` 方法）。任一步失败必须把已写目录清掉，「系统里不留半个 Agent」。回归在 `AgentLifecycleServiceTest`（注册失败回滚 / name 冲突第一步就拒且一个目录都不写）。
3. **`WorkspaceWatcher` 是单线程 + 有界重试**（`.../core/agent/WorkspaceWatcher.java`）。`MAX_ATTEMPTS` 10 × 300ms 由主 `poll(200ms)` 循环驱动，**不另开线程或定时器**；单个坏目录只告警不拖垮监听（章程原则 8，P3C `AvoidManuallyCreateThreadRule` 已就地豁免并注明）。
4. **记忆隔离的 ThreadLocal 边界**（`core/tool/ToolExecutionContext.java` + `MemoryServiceImpl`）。`ToolExecutionContext` 只在**同步阻塞模型**下成立——一次 ReAct 循环整体跑在一条线程上。若将来引入异步 tool 调用，此处必须一并复核（已记入已知边界）。
5. **`MarkdownMemoryStore` 每次重新解析路径**（`oryxos-memory/.../store/MarkdownMemoryStore.java`）。**不要加缓存**：缓存一次就会把 A 的记忆写进 B。`PerAgentMemoryScopeTest` 3 条钉死。
6. **静态门禁妥协点**（全部为就地注解，无全局 exclude）：`AgentStore` 的 `NUL_CHAR` 常量（P3C 魔法值）、`WorkspaceWatcher` 的 `@SuppressWarnings("PMD.AvoidManuallyCreateThreadRule")`、`WorkspaceWatcher` 的 `@SuppressFBWarnings("CRLF_INJECTION_LOGS")`、`AgentStore.children()` 比较器与 `writeAll` 空指针就地修、`SaveFilesRequest` 的 `Map.copyOf`（FindSecBugs `EI_EXPOSE_REP`）。

### 2.3 如何验证

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64

# 1) 全量门禁（含静态检查），预期 BUILD SUCCESS、723 条测试
#    （2026-10-22 交付后修复 +4 条，当前为 727；见 §五）
./mvnw clean verify

# 2) 只跑本节新增的 core 侧测试，预期 28 条全绿
./mvnw -o -pl oryxos-core -am test -Dtest='AgentLifecycleServiceTest,WorkspaceWatcherTest,GenerateTest,AgentFixedSessionTest'

# 3) 只跑 web 侧新增/改动，预期 42 条全绿（10 + 20 + 2 + 10）
./mvnw -o -pl oryxos-web -am test -Dtest='WorkspaceApiControllerTest,AgentApiControllerTest,GlobalMemoryEndpointRemovedTest,AdminConsoleSourceTest'

# 4) 记忆隔离回归，预期 13 条全绿（PerAgentMemoryScopeTest 3 + MemoryServiceImplTest 10）
./mvnw -o -pl oryxos-memory -am test -Dtest='PerAgentMemoryScopeTest,MemoryServiceImplTest'

# 5) 依赖方向与「第二真相源」自查，预期全部零命中
grep -rnE "class AgentLoader([^A-Za-z0-9_]|\$)" oryxos-*/src   # 词边界必须带：不带会误命中 AgentLoaderTest
grep -rn "MemoryApiController" oryxos-*/src/main               # 限 main：test 侧 4 处是有意引用
git status --short -- oryxos-tool/   # 预期空：整个 oryxos-tool 模块零改动

# 6) 端点计数，预期 21 paths / 27 operations
curl -s localhost:8080/v3/api-docs | python3 -c 'import json,sys; d=json.load(sys.stdin); print(len(d["paths"]), sum(len(v) for v in d["paths"].values()))'
```

---

## 三、剩余人工项

| # | 项 | 为什么机器判不了 |
|---|---|---|
| 1 | **Step 6 真模型一句话生成**：配 `DEEPSEEK_API_KEY` + `oryxos.author.model=deepseek-chat`，`POST /agents/{name}/generate-files`，核对草稿可解析、磁盘 `AGENT.md` 未变、`llm_calls` 出现 `agent-author:%` | 要花真钱、要真凭证；mock 档只能判 503 分支。本次已实测 503 分支（零请求）并在单测层判完全部其余语义 |
| 2 | **Step 8 浏览器目视走查**：`/admin/` 六项导航无「长期记忆」、Agent 详情五页签、生成页可编辑保存、文件页树 + 编辑器、会话页气泡、记忆页只读、列表页删除进 `archive/` | 交互与视觉；`AdminConsoleSourceTest` 只判源码形状（10 条），判不了「点下去对不对」 |
| 3 | **Step 3 的 3 秒边界体感**（可选）：`cp -a` 丢目录时观察 5 秒内出现 | 已由 `WorkspaceWatcherTest` 判重试逻辑，人工只看体感 |

> **2026-10-22 修订注（本节人工项已全部收口）**：第 1、2 两项由用户当日实跑完成，**验证通过**；留痕口径照前序各节——只记整体结论，不逐步列各步输出。第 3 项是可选项，用户未单独反馈，按「体感项」记为未观察（不是失败）。实跑过程中命中两处产品缺陷并已修复复验，见 §五。

---

## 四、备注

1. **未 commit / 未 push / 未运行 `package.sh`**——同步时机由用户决定。当前分支 `class-30`。
2. **静态门禁抓红共五轮，全部就地修，未删断言、未放宽阈值**（如实记录）：
   - **Spotless ×3**：本节文本改动撑宽 javadoc 行，`spotless:apply` 重排。
   - **P3C ×2**：① `AgentStore` 的 `'\0'` 魔法值 → 抽 `NUL_CHAR` 常量；② `WorkspaceWatcher` 的 `new Thread` → `@SuppressWarnings("PMD.AvoidManuallyCreateThreadRule")` + javadoc 引章程原则 8 / H4 不变量⑤ 说明「需要恰好一条可精确中断的守护线程」。
   - **SpotBugs ×8**：`AgentStore.writeAll` 的 `Path.getParent()` 空指针、`children()` 比较器、`MarkdownMemoryStore.write` 的 `getParent()` 空指针、`WorkspaceWatcher` 的 CRLF 日志注入、`AgentLifecycleService` 的冗余判空等——**全部改实现语义**，没有一条靠放宽阈值或删断言过。
   - **FindSecBugs `EI_EXPOSE_REP`**：`SaveFilesRequest` 加 `Map.copyOf` 紧凑构造器。
3. **一处与课件的偏差（已挂修订注）**：`data-model.md` §3.2 原写「provider 清单为空 → 装配处给出可读**启动**错误」，实际实现为**请求期**错误（`create` → 400 / `generate-files` → 503）。原因是 `CliTestContext` 启动时不带 `oryxos.providers`，启动期硬失败会把大量既有测试上下文打掉；判据等价（空清单下两条路径都不会发生、报错可读）。已在原地挂 **2026-10-21 修订注**，原文保留作决策史。
4. **`AdminConsoleSourceTest` 的判据方向按裁决反转**：原为「不引入写入口」，现允许 `ProfilesView.vue` 写且路径限 `/api/v1/agents/` 与 `/api/v1/workspace/file`，其余视图逐文件禁写；`noWriteEntryPointText` **保留、只把扫描范围收窄到只读页**，断言条数 8 → **10**（只增不减）。
5. **一处观察，未改动**：`examples/agents/daily-reconcile/skills/report-format` 在磁盘上是 29 字节的**普通文本文件**（内容为 `../../../skills/report-format`），而非第 29 节文档所述的**相对软连接**。它是第 29 节的未跟踪产物、不在本节交付物清单内，故只记录、不修改；若第 31 节 Demo 依赖它，需要先确认软连接语义。
   > **2026-10-22 修订注**：本条已清，且**根因与本条当时的判断不同**——它不是「产物本身存错」，仓库里该项一直是 `120000`（软连接）、blob 内容恰是那串路径；坏的是**签出**：`.git/config` 的 `core.symlinks=false` 让 git 把软连接写成普通文件。修法见 §6.2。
6. `docs/class/` 自 2026-10-14 起随仓库跟踪。

---

## 五、交付后修复（2026-10-22，用户按人工向导 Step 8 实跑命中两处）

### 5.1 概览页「已运行」卡显示 `vunknown`

- **根因**：`SystemApiController.resolveVersion()` 只读 `Package.getImplementationVersion()`，即 JAR manifest 的 `Implementation-Version`；本仓 `maven-jar-plugin` 没有开 `addDefaultImplementationEntries`，fat JAR 的嵌套 jar 里该字段恒为 `null` → 回退 `"unknown"`；前端 `OverviewView.vue` 又硬拼 `'v' + info.version` → `vunknown`。
- **修复**：① 后端**优先读构建期注入的 `/oryxos-version.properties`**（`oryxos-cli` 的资源过滤产物，与 `oryxos --version` 同源），manifest 降为第二来源，仍读不到才回 `unknown`；② 前端对 `unknown` 显示「版本未知」，不再拼出 `vunknown`。
- **测试**：`SystemApiControllerTest` 的断言由 `isString()` 收紧为测试资源里的固定值 `9.9.9-test`——原断言下「打包后恒为 unknown」照样绿，抓不到这个缺陷。
- **端到端**：用 `mvn clean verify` 打出的 `oryxos.jar` 起 `serve`，`GET /api/v1/info` 返回 `"version":"0.1.0-SNAPSHOT"`。

### 5.2 删除 Agent 后「定时任务」页仍显示 `daily-reconcile#1`

- **用户裁决**：「agent 都不在了，它的定时器还在，这不合理。」第 28 节 v1 原把「旧行列表可见」记为已知边界，本次按用户裁决修正**对外可见性**。
- **方案（用户选定：列表按「已注册」过滤，不删库行）**：`AgentScheduler` 新增 `public Set<String> registeredTaskIds()`（当前已注册任务快照）；`ScheduleApiController` 四个端点统一只认已注册任务——
  - `GET /schedules`：过滤掉只剩历史行的任务；
  - `GET /{id}/executions`、`POST /{id}/run`、`PUT /{id}`：走 `requireTask` 时一并判定，未注册即 **404**。
- **保留的数据**：`scheduled_tasks` 历史行与 `task_executions` 都**不物理删除**；v1 仍无 `retired` 字段。重启 `serve` 时注册表按 `.oryxos/agents/` 重建，口径一致。
- **测试**：`AgentSchedulerRegisterTest.registeredTaskIds_tracksLiveRegistrationsOnly`（注册含两条、注销后为空、快照不可变）；`ScheduleApiControllerTest` 9 → **12**（列表隐藏未注册行、executions 404、update 404）。
- **端到端**（`/tmp` 临时工作区，验完已删）：建一个带 schedule 的 mock Agent → `/schedules` 含 `stale-demo#1` → `rm -rf` 目录 + watcher 拾取 → `/schedules` 返回 `data: []`、`POST ...%231/run` **404**、`scheduled_tasks` 里该行仍在。

### 5.3 门禁与文档同步

- **全量 `mvn clean verify` 727 条全绿**（723 → +4：core +1、web +3；本地按既有口径 `-Ddependency-check.skip=true`）。分模块：tool 254 / core 162 / cli 104 / web 92 / memory 90 / storage 14 / provider 11。
- **静态门禁抓红一处并就地修**：`SystemApiController.readVersionResource` 的 `log.debug("…{}", e.getMessage())` 被 FindSecBugs 报 `CRLF_INJECTION_LOGS`；按第 25 节口径在方法上 `@SuppressFBWarnings` 并写明理由（格式串与路径均常量、异常来自本地 classpath 资源读取），行为不变。
- **文档同步**：本向导 Step 3 / 8.1 / 8.5 / 坑表第 11 行 / 已知边界已改成新判据；`specs/011-scheduler-runtime/{manual-test-guide,acceptance-report}.md`、`specs/012-plugin-agent/manual-test-guide.md`、`CLAUDE.md` 的「列表可见」旧口径均挂 **2026-10-22 修订注**（原文保留作决策史）。

---

## 六、人工项收口与遗留观察清理（2026-10-22）

### 6.1 人工项收口

用户当日按向导完成 **Step 6（真模型一句话生成）** 与 **Step 8（浏览器全流程）**，**验证通过**。留痕口径照前序各节：只记整体结论，不逐步罗列各步输出。Step 3 的 3 秒边界体感是可选项，未单独反馈，记为**未观察**（不是失败）。

### 6.2 §四·5 的软连接观察已修（第 31 节前置）

- **原状**：`examples/agents/daily-reconcile/skills/report-format` 在本地工作副本里是 **29 字节普通文本文件**，内容为字面量 `../../../skills/report-format`（见 §四·5）。
- **根因（查清后与 §四·5 的初判不同）**：**入库的形状一直是对的**——`git ls-files -s` 给出 `120000`（软连接），blob 内容恰是那串相对路径。坏的是**签出**：`.git/config` 里有 `core.symlinks=false`（与 `core.filemode=false` 同一来源，从 Windows 盘复制过来时带上的），该开关让 git 把软连接**写成普通文本文件**。所以这既不是第 29 节的产物缺陷，也不是「未跟踪产物」——它受跟踪，只是本地被签出降级了。
- **为什么必须修**：章程原则 13 规定「Skill 绑定的唯一真相源是**软连接**」；一个写着路径的文本文件在磁盘上是**第二真相源**——`ProfileLoader.inspect` 的 `resources` 枚举看得到它、但内容是字符串；第 31 节 Demo 若走渐进式披露，模型拿到的是那行路径而不是 `SKILL.md` 的 name/description。
- **修法**：`git config core.symlinks true`（治本，否则下次签出又退化；全仓只有这一个受跟踪软连接，`git ls-files -s | awk '$1=="120000"'` 命中 1 条，开开关不产生模式差异），随后删掉那个文本文件、`ln -s ../../../skills/report-format ...` 重建工作区条目（相对软连接，与 `.oryxos/` 内同一相对深度——`examples/agents/daily-reconcile/skills/` 与 `.oryxos/agents/<name>/skills/` 都往上三级到各自的 skills 根）。这一步只动本地工作副本与 `.git/config`，**不产生任何可提交的改动**（重建后的内容与 blob 相同，`git status -- examples/` 为空）。
- **实测**：`ls -l` 为 `report-format -> ../../../skills/report-format`；`readlink -f` 指向 `examples/skills/report-format`；`cp -a` 进 `/tmp` 工作区后软连接保留、`readlink -f` 落在 `<WS>/.oryxos/skills/report-format`、`head SKILL.md` 读得到 frontmatter（临时目录验完已 `rm -rf`）。
- **已知边界不变**：`ProgressiveDisclosureTest` 在文件系统不支持软连接时仍 `assumeTrue` 跳过（Linux/WSL 上真跑）。

## 七、交付后扩展：直接编辑 `AGENT.md` 免重启生效（2026-10-22，用户实跑要求）

### 7.1 原状与根因

第 30 节的已知边界是「AGENT.md 的编辑走显式 update 重注册」（见 `spec.md` Assumptions 与 §四）。用户在第 31 节人工验收时直接改工作区里的 `weather-daily/AGENT.md`（cron `0 55 16 * * *` → `0 33 17 * * *`），`/schedules` 一直显示旧 cron。根因不是解析或调度，而是**监听粒度**：`WorkspaceWatcher` 只给 `agents/` 这一层注册了 `WatchService` key，`agents/<name>/AGENT.md` 的写入事件打在**子目录自己的 key** 上，从未被消费。现场证据：serve 启动 17:18:38、文件 mtime 17:31:32，日志中启动扫描后再无登记记录；同一内容走 `PUT /api/v1/agents/{name}` 后 cron 立刻变新。

### 7.2 改动

- `WorkspaceWatcher` 为**每个 Agent 子目录单独注册 key**：启动扫描时挂、目录 `ENTRY_CREATE` 时挂、目录 `ENTRY_DELETE` 时取消并从表里移除；子目录 key 失效只丢该 key，不退出主监听。
- `AGENT.md` 的 `ENTRY_CREATE` / `ENTRY_MODIFY` 经 **300ms 静默期**去抖（持续写入封顶 2s）后调同一条 `lifecycleService.register(dir)`，先取消旧定时句柄再登记，不会新旧并存。
- 编辑器「写临时文件 → 改名覆盖」产生的 `ENTRY_DELETE` **刻意忽略**，保留旧运行态等后续写事件；保存了语法错误的内容时重新登记失败**只 WARN 不注销**。
- `WatchService` 改为**显式传参**（监听线程不再读 `watchService` 字段），过 SpotBugs `IS2_INCONSISTENT_SYNC`。
- 测试：`WorkspaceWatcherTest.editingAgentMarkdownReRegistersWithoutRestart`——改 cron 后 `verify(scheduler, timeout(5s)).registerProfile(...)` 收到新 cron；`WorkspaceWatcherTest` 5 → **6**。
- 文档：`specs/013-agent-lifecycle-api/spec.md` 的 Assumptions 挂 2026-10-22 修订注（该边界已推翻）；`CLAUDE.md` 第 30 节条下新增交付后扩展段；第 31 节向导新增 Step 5.1（直接改 cron 的判据）。

### 7.3 验证

`mvn clean verify -Ddependency-check.skip=true` 全绿，**733** 条（732 → +1），Spotless / P3C / Checkstyle / SpotBugs / FindSecBugs 均通过。首次全量跑时 SpotBugs 报 `IS2_INCONSISTENT_SYNC`（`watchService` 同步写、监听线程无锁读），按上条改为显式传参后清除——**改实现语义，没有放宽阈值**。

## 八、交付后扩展②：直接编辑 AGENT.md 时自动重置调度会话历史（2026-10-22，用户裁决）

### 8.1 现象与根因

§七 让「改 cron」免重启生效后，用户把 `weather-daily` 正文的城市从北京改成成都再 `POST /api/v1/schedules/weather-daily%231/run`，**群里收到的仍是北京天气**。

排查结论：**不是热加载失效**。现场证据：`serve` 的 cwd 与工作区一致、磁盘上的 `AGENT.md` 已是成都两步版、watcher 已重新登记（cron 已更新）、jar 里有 `geocoding-api.open-meteo.com` 白名单；`ContextLoader` / `PromptBuilder` 每轮重读文件（无缓存）。真正原因是**调度会话历史锚定**：`scheduler:scheduler:weather-daily` 已累积 48 条消息，模型看得到上一轮那次北京 `http_get` 的完整参数，直接照抄——连续 8 次触发（`tool_invocations` id 29~36）都只请求 `latitude=39.9&longitude=116.4`，一次 geocoding 都没发。旁证：在 `/tmp` 的隔离副本里只清掉该会话历史（不带 webhook 环境变量、不向真实群发送），同一份 `AGENT.md` 立刻走「geocoding `name=成都` → forecast `30.66667/104.06667`」两步。

### 8.2 用户裁决与改动

用户选择 **「编辑时自动重置」**：监听器检测到 `AGENT.md` 变更并重新登记时，清空该 Agent 的调度会话历史；**启动扫描与 API 保存不动历史**（重启不该丢上下文，API 保存时调用方已拿到校验结果）。

- `AgentScheduler.resetScheduleSession(String profileName)`：三元组落在它这里（`scheduler` / `scheduler` 提为常量，`runOnce` 同步改用常量、字面量不变）。调 `getOrCreate` 取回 → 消息为空则**不写库**（没跑过的 Agent 不会凭空多出一条空会话）→ 否则 `save(Session.create(同 id, 同三元组))`。`JpaSessionService.save` 的更新路径只写 `messages_json` + `last_active_at`，会话标识 / `created_at` / 归档态原样保留；两张审计表一行不动。
- `AgentLifecycleService.resetScheduleSession(String name)`：转发，便于 watcher 只依赖 lifecycle（会话三元组与 id 拼接仍收在 core 内部，H4 ④ 不破）。
- `WorkspaceWatcher.processDueEdits()`：登记成功后单独一段 `try` 调重置，**失败只 WARN**（「已变更并重新登记，但重置调度会话失败」）——不让清历史失败被误报成「重新登记失败」。
- 这是 `Session`「只追加」不变量的**唯一一处有意例外**，已在 `AgentScheduler` 的 javadoc 里写明理由与边界。

### 8.3 测试与门禁

- `AgentSchedulerTest` +2：`resetScheduleSession_clearsAccumulatedHistory_keepsIdentity`（写回同 id / 同三元组 / 空消息）、`resetScheduleSession_withoutHistory_doesNotWriteAnything`（零 `save`）；31 → **33**。
- `WorkspaceWatcherTest` +2：`editingAgentMarkdownResetsSchedulerSession`（编辑后 `resetScheduleSession("weather")` 被调用）、`startupScanDoesNotResetSchedulerSession`（启动扫描 `never()`）；6 → **8**。
- `AgentLifecycleServiceTest` +1：`update_doesNotResetScheduleSession`（API 保存不重置）；12 → **13**。
- 文档：`spec.md` 的 2026-10-22 修订注补第二处；`specs/014-demo-agents/manual-test-guide.md` Step 5.1 判据改为含「已重置调度会话」的日志行、新增 Step 5.2（换城市 + 重置的判据）与坑表第 17 行；`CLAUDE.md` 第 30 节交付后扩展段补记。
- **首次全量跑抓红一处**：`AgentSchedulerTest.runOnce_doesNotLogOrPersistAnythingItself`（读源文件断言不含审计表名）命中我新写的 javadoc 里的 `llm_calls` / `tool_invocations` 字样。按反作弊口径改**注释措辞**（「两张审计表」），不动断言。
