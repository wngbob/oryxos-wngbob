# 第 28 节验收报告 · 全流程串联（二）让底座自己跑得稳

| 项 | 值 |
|---|---|
| 课型 | **串联课**（不开新 feature，不产 spec/plan/tasks——对账清单本身就是交付物） |
| 课件 | [`docs/class/第28节：全流程串联（二）让底座自己跑得稳.md`](../../docs/class/第28节：全流程串联（二）让底座自己跑得稳.md) |
| 技术方案 | `docs/TechnicalSolution.md` §8.5（定时任务）、§12（关键流程） |
| 分支 | `class-28` |
| 日期 | 2026-10-20 |
| 任务完成度 | 课件「本节交付物」**7 / 7** 项落地（逐项见 §二·3） |
| 结论 | **全绿**：`mvn clean verify` **623** 条通过（含静态门禁全绿）；`SchedulerFlowIT` 在真 DeepSeek 上 **1 / 1** 通过、`RestartRecoveryIT` **1 / 1** 通过；人工验收（向导 Step 0~7）2026-10-20 由用户确认通过 |

> 串联课没有 `spec.md` / `plan.md` / `tasks.md`——按 skill 第 0 步的分流，27 / 28 是「逐条执行该节课件的对账清单，并把对账固化成 `@Tag("integration")` 测试类」。本报告即是该课的落盘产物。

---

## 一、这一节到底做了什么

第 25 节让定时任务「到点能跑」，但它只是内存里的一次注册：进程一重启，任务跑过几次、上次成没成、下次几点、有没有被停用，全部归零。第 28 节补的是**让它跑得稳**——把任务的状态与历史落到 SQLite，再把「看得见 / 管得动」做成端点与管理台，最后用四组对账证明底座能长期无人值守地跑。

课件给的对账清单是四组：

| 组 | 判据 |
|---|---|
| **定时子系统**（无 key 可验） | 定义 → 登记 → 立即执行 → 状态 / 历史落库 → 停用；`ScheduledTaskE2ETest` 在 gate 内跑通 |
| **定时链路**（真 key） | 到点自己跑完「查天气 → notify 推送」，webhook 真收到，`llm_calls` / `tool_invocations` 逐表对账不多不少、会话复用；失败路径被 Sandbox 拦下且调度器不死 |
| **重启** | kill 再起：会话历史 / 长期记忆 / 定时任务状态与历史 / 审计记录四样原样恢复，新消息继续写同一张审计表 |
| **多 Agent** | 工具隔离 / 会话隔离 / 定时隔离，三条边界都成立 |

为此本节新增的零件（全部对应课件的「本节交付物」）：

1. **两张表 + 一条持久化接缝**：`scheduled_tasks`（任务登记与运行态）、`task_executions`（每次执行历史）；`ScheduledTaskStore` + `ScheduledTaskView` / `TaskExecutionView` 在 core，JPA 实体与 Repository 在 storage。
2. **`AgentScheduler` 升级**（第 25 节既有类）：注册即登记、触发前查启用、成功失败都记历史并滚动任务状态、新增同步的 `runNow(taskId)`。
3. **四个 REST 端点**：`GET /api/v1/schedules`、`GET /api/v1/schedules/{id}/executions`、`POST /api/v1/schedules/{id}/run`、`PUT /api/v1/schedules/{id}`——核心阶段端点总数 11 → **15**，`ApiController` 6 → **7**。
4. **管理台第一页带写操作**：「定时任务」页新增「立即执行」与「启用 / 停用」两个动作，写面只打 `/api/v1/schedules/**`。
5. **五条新测试类**：`ScheduledTaskE2ETest`（gate 内、mock）、`MultiAgentIsolationTest`、`MultiAgentScheduleIsolationTest`（gate 内）、`SchedulerFlowIT`、`RestartRecoveryIT`（`@Tag("integration")`、真 key），外加对 `AgentSchedulerTest` / `JpaScheduledTaskStoreTest` / `ScheduleApiControllerTest` 的单测补强。

**两条用户裁决（本节的实现口径）**：

- **按课件 v1，不按技术方案 §8.5 的 v2**：端点前缀 `/api/v1`（不是 `/api/v2`）、主键 `task_id = <profileName>#<序号>`（不是稳定 `schedule_id` + `schedule_key` + `display_name`）、没有 `retired` 与退役语义、没有 `/agents/{profileName}/schedules/{key}/run` 与多 Agent 同 key 的 409。v2 的差异已逐条写进 `docs/TechnicalSolution.md` §8.5 的 2026-10-20 修订注，属于**已知偏差**而不是遗漏。
- **管理台精确开写口**：只许 `SchedulesView.vue` 写、只许 `POST` / `PUT`、只许 `/api/v1/schedules/**`、只许经 `api.js`；任务的增 / 删 / 改 cron **不进**管理台（定义源是 `AGENT.md`，开写口会造出第二个真相源）。判据由 `AdminConsoleSourceTest` 从「整站只读」**收窄**而成，断言数 8 条不变、强度不减。

**四处与课件不一致，均如实留痕**：

| # | 课件写的 | 实际落地 | 原因 |
|---|---|---|---|
| 1 | `JpaScheduledTaskStore` 放 `oryxos-storage` | 放 `oryxos-core`（实体 / Repository 在 storage） | 本仓依赖方向是 `oryxos-core → oryxos-storage`；实现放 storage 会迫使 storage 反向依赖 core（成环），与第 18 节 `JpaSessionService` 同款先例 |
| 2 | 一轮定时任务 `llm_calls` 恰 **2** 条 | 真链路恰 **3** 条；mock 脚本下为 2 条 | 真链路里 `notify` 的内容依赖 `http_get` 的结果，两次工具调用结构上无法并排（模型先决定查天气、拿到结果后才决定推什么、最后组织答复）——`SchedulerFlowIT` 断言 3 并在注释里写明，**不是放宽阈值** |
| 3 | 确认 `OpenAiAutoConfiguration` 已排除 | 无需排除：它**不在 classpath 上** | `spring-ai-deepseek` 只拉 model / commons / template-st / retry，不拉任何 autoconfigure 模块（`mvn dependency:tree` 核实），与第 26 节裁决 ④ 同一种结果 |
| 4 | 任务定义来自「skill 的 `schedules`」 | 来自 `Profile.schedules`（= `AGENT.md` frontmatter） | 第 28 节时点还没有 Skill 概念（第 29 节才引入）；本仓第 25 节起定义源就是 `AGENT.md`，本节未改 |

---

## 二、六项证据（DoD）

### 1. `mvn clean verify` 全绿

```bash
./mvnw clean verify -Ddependency-check.skip=true
```

逐模块结果（`/tmp/l28-verify3.log`）：

```text
OryxOS Parent ...... SUCCESS [  3.035 s]
oryxos-storage ..... SUCCESS [ 14.697 s]   Tests run: 14
oryxos-core ........ SUCCESS [ 11.520 s]   Tests run: 94
oryxos-provider .... SUCCESS [  5.980 s]   Tests run: 11
oryxos-memory ...... SUCCESS [ 10.907 s]   Tests run: 87
oryxos-tool ........ SUCCESS [ 15.261 s]   Tests run: 254
oryxos-channel-cli . SUCCESS [  3.278 s]
oryxos-web ......... SUCCESS [ 14.952 s]   Tests run: 63
oryxos-cli ......... SUCCESS [ 34.735 s]   Tests run: 100
oryxos-boot ........ SUCCESS [  0.648 s]
BUILD SUCCESS
```

- **测试总数 623**（14 + 94 + 11 + 87 + 254 + 63 + 100），较第 27 节的 591 **+32**，与新增测试逐条对得上（见 §二·2）。
- **静态门禁全部执行且为 0**：Spotless（`spotless:check`）通过、P3C/PMD 0 violation、Checkstyle **0 violations**、SpotBugs/FindSecBugs **BugInstance size is 0**（逐模块）。
- OWASP Dependency-Check 按本仓既定口径在**本地**用 `-Ddependency-check.skip=true` 跳过（CI 里的 `ci.yml` 会跑全量）；报告中如实披露，不写成「全门禁已跑」。

### 2. 课件 harness 映射：测试类存在且关键回归对号

| 测试类 | 位置 | 条数 | 守的是什么 |
|---|---|---|---|
| `ScheduledTaskE2ETest` | `oryxos-cli/src/test/.../cli` | 1 | 课件**主验**：登记 → 立即执行（mock 走真实 ReAct，触发一次 `save_memory`）→ `run_count=1` / `last_status=success` / 一条成功执行历史 / 记忆可查（直查 SQLite 三张表）→ `PUT` 停用 |
| `SchedulerFlowIT` | 同上 | 1 | 课件**链路验**：真 DeepSeek 跑「http_get 查天气 → notify 推送」，IT 内 `HttpServer` 收推送；逐表对账（`llm_calls` 3 / `tool_invocations` 2 且全成功）、会话复用（两次触发仍一条 `scheduler:scheduler:<agent>`）、白名单外渠道被拦留 `success=false`、调度器不死 |
| `RestartRecoveryIT` | 同上 | 1 | 课件**重启验**：同一临时工作区上先后起两个 Spring 上下文（真重启），核对会话历史 / 长期记忆 / 任务状态与执行历史 / `llm_calls` 四样原样恢复；重启后新消息继续写同一张审计表 |
| `MultiAgentIsolationTest` | 同上 | 2 | 课件**多 Agent 验**的工具隔离 + 会话隔离：`ToolCatalog.availableFor` 恰为各自声明的子集、两边会话 id 与历史互不串 |
| `MultiAgentScheduleIsolationTest` | 同上 | 1 | 多 Agent 的**定时隔离**：A 的定时必抛、B 的照常成功（`@MockitoBean AgentService` 做确定性注入），A 再跑一次仍能进入执行入口（调度器没被拖死） |
| `JpaScheduledTaskStoreTest` | `oryxos-core/src/test/.../schedule` | 10 | 登记只更新定义面、不覆盖 `enabled` 与运行态；执行历史滚动任务状态；`isEnabled` 查不到按启用；`setEnabled` 对未登记任务返回 false；列表排序确定 |
| `AgentSchedulerTest`（补强） | 同上 | 31（**+9**） | 注册即落库并算好 `nextRunAt`；重启后停用状态与历史不被冲掉；停用任务到点跳过且不记历史；成功 / 失败都写历史；`runNow` 同步执行、未知 id 返回 false、**无视停用状态** |
| `ScheduleApiControllerTest` | `oryxos-web/src/test/.../controller` | 9 | 四个端点的正常与错误路径：列表、历史倒序、`run` 返回执行后的视图、`PUT` 缺 `enabled` 400、未知 id 404 |
| `AdminConsoleSourceTest`（改判） | `oryxos-web/src/test/.../web` | 8（**条数不变**） | 写方法只许出现在 `SchedulesView.vue` 与 `api.js`；其余六页逐文件仍只许 GET；定时任务页的写只许打 `/api/v1/schedules/**` 且不得裸 `fetch`；`PATCH` / `DELETE` 全站禁止；定时任务页的数据源改为 `GET /api/v1/schedules` |

**新增测试总数 = 32**，与全量 623 − 591 完全吻合。

### 3. 「本节交付物」逐项存在性核对

```bash
# 持久化
ls oryxos-core/src/main/java/io/oryxlabs/oryxos/core/schedule/
# → AgentScheduler.java JpaScheduledTaskStore.java ScheduleConfig.java
#   ScheduledTaskStore.java ScheduledTaskView.java TaskExecutionView.java
ls oryxos-storage/src/main/java/io/oryxlabs/oryxos/storage/entity/ | grep -E "ScheduledTask|TaskExecution"
# → ScheduledTask.java TaskExecution.java
ls oryxos-storage/src/main/java/io/oryxlabs/oryxos/storage/repository/ | grep -E "ScheduledTask|TaskExecution"
# → ScheduledTaskRepository.java TaskExecutionRepository.java
grep -c "CREATE TABLE IF NOT EXISTS" oryxos-storage/src/main/resources/db/schema.sql
# → 7（原 5 张 + scheduled_tasks + task_executions）

# 端点
grep -n "Mapping" oryxos-web/src/main/java/io/oryxlabs/oryxos/web/controller/ScheduleApiController.java
# → 63:@GetMapping（列表）、74:@GetMapping("/{id}/executions")、
#   89:@PostMapping("/{id}/run")、106:@PutMapping("/{id}")
#   （另有 import 与类级 @RequestMapping("/api/v1/schedules")）

# 前端写页
grep -n "post('/api/v1/schedules/\|put('/api/v1/schedules/" \
  oryxos-web/src/main/frontend/src/views/SchedulesView.vue
# → 两处命中（立即执行 / 启用停用）

# 配置
grep -n "api.open-meteo.com\|api.github.com" oryxos-boot/src/main/resources/application.yaml
# → 两行（天气源 + 第 31 节 Demo 前置；团队 webhook 域名自第 19 / 24 节起已在表内）

# 文档
grep -n "15 个 REST\|schedules" README.md | head
# → 端点表新增定时任务行、能力表与模块表 15 个端点
```

7 项交付物逐项对照：持久化 ✅ / 调度 ✅ / 端点 ✅ / 测试 ✅ / 前端 ✅ / 配置 ✅ / 文档 ✅（另同步了官网中英四页、**官网首页 `Home.vue`**、`CLAUDE.md`、两份第 26 节契约与 `docs/TechnicalSolution.md`）。

### 4. 前序节全部测试回归绿（跨节契约证据）

- 623 条全量通过，含第 16~27 节的全部既有测试；`oryxos-cli` 100 条里的 `CliCommandTreeTest`（15 条命令）、`ToolOnboardingCostTest`、`SchedulerAssemblyTest`、`AdminThemeSourceTest` 等跨节判据均绿。
- **零改动面（硬判据）**：五个内置工具文件（`FileTools` / `ShellTools` / `HttpTools` / `WebSearchTools` / `NotifyTools`）、`ToolExecutor`、`ReActLoop`、`AgentService`、`SessionService`、`PromptBuilder` 的 `git diff` 全为空：

```bash
git diff --stat -- oryxos-tool \
  oryxos-core/src/main/java/io/oryxlabs/oryxos/core/engine \
  oryxos-core/src/main/java/io/oryxlabs/oryxos/core/session
# → 无输出
```

- 前序节的 `AgentScheduler` 行为（cron 5/6 段归一化、时区恒显式、`tryLock` 不排队、失败隔离）在 31 条 `AgentSchedulerTest` 里全部保留，新增 9 条是**追加**而非替换。

### 5. H4 六条全局不变量逐条自查

| # | 不变量 | 本节结论 | 证据 |
|---|---|---|---|
| ① | 涉外 IO 首行过 `Sandbox.enforce` | **未新增涉外 IO**：本节没有新工具；五个工具的 `git diff` 为空，`SchedulerFlowIT` 里白名单外渠道被拦（`errorMessage` 含「白名单」、审计 `success=0`） | 见上条 git diff；`SchedulerFlowIT` 断言 |
| ② | LLM 成败落 `llm_calls`、工具成败落 `tool_invocations` | **新增审计代码 0 行**：钟推与手动执行都汇入 `AgentService.process`，走既有路径；`SchedulerFlowIT` 逐表对账、`MultiAgentScheduleIsolationTest` 断言失败也落历史 | `grep -n "LlmCall\|ToolInvocation" AgentScheduler.java` 零命中；IT 断言 |
| ③ | grep 无明文 key | 源码与前端零命中；`${DEEPSEEK_API_KEY}` 仍是占位；`bin/config.yaml` 未入库（gitignored，本机真 key 只存在那里） | `grep -rlE "sk-[A-Za-z0-9]{10,}" --include="*.java" --include="*.vue" --include="*.js" oryxos-*/src specs/011-scheduler-runtime` 零命中（**须限定范围**：不限定会扫到 gitignored 的 `bin/config.yaml`，那是真 key 的合法存放点，不是泄漏） |
| ④ | `session_id` 只在 `SessionManager` 内拼接 | 调度器只调 `sessionService.getOrCreate("scheduler", "scheduler", profile.name())`，不自行拼串 | `AgentScheduler.java:222` |
| ⑤ | 无 Reactor / `CompletableFuture` / 自建线程池 | 新增代码零命中；调度复用第 25 节的 `ThreadPoolTaskScheduler`（固定 2 线程） | `grep -rnE "Reactor|CompletableFuture|new Thread|Executors\."` 零命中 |
| ⑥ | 无 Spring AI 自动工具执行 | 工具调度仍只在 `ReActLoop` + `ToolExecutor`；`ScheduledTaskE2ETest` 用 mock 跑出的正是「一轮 1 次工具调用 → 4 条消息」 | `ToolSchemaAdapter` / `ReActLoop` 未改 |

### 6. 收尾与人工项

`mvn clean verify` 把机制层全部判完；真 DeepSeek 的两条 IT 由作者在真 key 上实跑通过（见 §四），剩余人工项见 §四。本节**没有**第 22 节那种「有意不验收」的档，已知边界见 §五。

---

## 三、变更总结（给 reviewer 的导读）

以 `git status --short` / `git diff --stat` 实测为准。改动规模：**25 个已跟踪文件修改（+952 / −145），新增 20 个代码 / 测试文件**（另有本报告所在目录）。

### 1. 改动点

**oryxos-core（+新增 4 / 修改 1 / 测试 +1）**

| 文件 | 动作 | 动机 |
|---|---|---|
| `schedule/ScheduledTaskStore.java` | 新增 | 「状态 + 历史」持久化接缝；**不是定义源**，定义仍在 `AGENT.md` |
| `schedule/ScheduledTaskView.java` | 新增 | 任务视图（定义面快照 + 运行态），调度器与 web 共用 |
| `schedule/TaskExecutionView.java` | 新增 | 一次执行的历史视图 |
| `schedule/JpaScheduledTaskStore.java` | 新增 | JPA 实现（落在 core，理由见 §一·差异 1） |
| `schedule/AgentScheduler.java` | 修改 | 注册即登记；`trigger` 先查 `enabled`（停用跳过且不记历史）；`runOnce` 结束写历史并滚动状态；新增同步 `runNow(taskId)` |
| `schedule/AgentSchedulerTest.java` | 修改 | +9 条：登记落库、重启保留停用与历史、停用跳过、成功/失败都记历史、`runNow` 三态 |

**oryxos-storage（+4 / 修改 1）**：`entity/ScheduledTask.java`、`entity/TaskExecution.java`、`repository/ScheduledTaskRepository.java`、`repository/TaskExecutionRepository.java` 新增；`schema.sql` 加两张表（共 7 张）。

**oryxos-web（+5 主 / +1 测试 / 修改 6——2 个 Java + 4 个前端）**：`controller/ScheduleApiController.java` 四端点；`controller/dto/ScheduleView.java`、`ScheduleExecutionView.java`、`UpdateScheduleRequest.java`；`error/ScheduleNotFoundException.java`；`GlobalExceptionHandler` 加一条 404 映射；`ScheduleApiControllerTest` 9 条；前端 `SchedulesView.vue` 从只读配置页改为「列表 + 立即执行 / 启停 / 历史」，`api.js` 导出 `post` / `put`，`router.js` / `App.vue` 文案同步；`AdminConsoleSourceTest` 改判（见 §一 裁决）。

**oryxos-cli（+5 测试 / 修改 1 装配）**：`CliAssemblyConfiguration` 新增 `scheduledTaskStore()` Bean、`agentScheduler(...)` 多注入一个参数；五个新测试类（§二·2）。

**oryxos-boot（修改 1）**：`application.yaml` 的 `http.allowed_domains` 加 `api.open-meteo.com`（天气 Demo）与 `api.github.com`（第 31 节 Demo 前置；webhook 域名此前已在表内）。

**文档（修改 14）**：`README.md` 端点表 11 → 15、能力 / 模块 / 路线图同步；官网中英 `what-is-oryxos` / `architecture` / `quick-start` / `roadmap` 八页同步，外加**官网首页 `Home.vue`**（中英双语内容对象：端点计数 11 → 15、「只读管理台」改「管理台（定时任务页可写）」，共 4 处——`CLAUDE.md` 的「对外内容三处同步」要求的第三处，复核时补上的，见 §五·6）；`CLAUDE.md` 第 28 节条目与端点 / 表 / 模块计数；`docs/TechnicalSolution.md` §8.5 加 v1-vs-v2 修订注；`specs/009-web-service/contracts/{admin-console,rest-endpoints}.md` 各加 2026-10-20（第 28 节）修订注。

### 2. 重点 review 清单（按风险排序）

1. **依赖方向**：`JpaScheduledTaskStore` 在 core、实体与 Repository 在 storage（`oryxos-core/.../schedule/JpaScheduledTaskStore.java:25`）。评审要确认**没有**出现 `oryxos-storage → oryxos-core` 的依赖边，且 `ScheduledTaskStore` 的方法集与 v1 一致（无 `retire`）。
2. **登记不覆盖运行态**：`JpaScheduledTaskStore.register`（`:38`）对已存在的行只更新定义面；这一条错了会让「停用」在重启后自动变回启用、`run_count` 每次重启归零——由 `AgentSchedulerTest.registerAll_afterRestart_keepsDisabledStateAndRunHistory` 与 `RestartRecoveryIT` 双重钉住。
3. **停用语义**：`AgentScheduler.trigger` 先查 `enabled`（`:180`），停用**不执行也不记历史**；`runNow` 刻意不看启用状态（`:246`）。这是「没跑」与「跑了但失败」必须分得开的那条线。
4. **管理台写口**：`AdminConsoleSourceTest.writeMethodsAreConfinedToSchedulesPage` 把写面收窄到「一个文件 + 一个前缀 + 两个方法」；`api.js` 仍禁 `PATCH` / `DELETE`。新增写入口会红。
5. **`run` 的同步语义**：`ScheduleApiController.run` 等 `runNow` 跑完才返回执行后的视图（`:90`）。改成异步会让管理台点完立刻查列表看到旧计数，且违背同步阻塞模型（章程原则 8）。
6. **静态门禁的两处就地豁免**：`ScheduleApiController` 类上的 `SPRING_ENDPOINT`（本类就是对外入口）与构造器的 `EI_EXPOSE_REP2`（依赖注入本身），与 `ProfileApiController` 同款、父 pom 口径一致。

### 3. 如何验证（可直接复制）

```bash
# ① 全量门禁（本节完成定义）
./mvnw clean verify -Ddependency-check.skip=true
# 预期：BUILD SUCCESS，623 条测试，P3C / Checkstyle / SpotBugs / Spotless 0 违规

# ② 只跑本节相关单测
./mvnw -pl oryxos-core,oryxos-web,oryxos-cli -am test \
  -Dtest='AgentSchedulerTest,JpaScheduledTaskStoreTest,ScheduleApiControllerTest,ScheduledTaskE2ETest,MultiAgent*Test,AdminConsoleSourceTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
# 预期：全绿（AgentSchedulerTest 31 / Jpa 10 / Controller 9 / E2E 1 / MultiAgent 2+1 / Admin 8）

# ③ 真 key 集成（默认被 excludedGroups 排除，必须显式打开）
DEEPSEEK_API_KEY=xxx ./mvnw -pl oryxos-cli -am test \
  -DexcludedGroups=none -Dtest=SchedulerFlowIT -Dsurefire.failIfNoSpecifiedTests=false
DEEPSEEK_API_KEY=xxx ./mvnw -pl oryxos-cli -am test \
  -DexcludedGroups=none -Dtest=RestartRecoveryIT -Dsurefire.failIfNoSpecifiedTests=false
# 预期：各 1 条通过（会真调模型、真出网）

# ④ 依赖方向与零改动面
git diff --stat -- oryxos-tool oryxos-core/src/main/java/io/oryxlabs/oryxos/core/engine
# 预期：无输出
grep -rn "oryxos-core" oryxos-storage/pom.xml
# 预期：无命中（storage 不反向依赖 core）

# ⑤ 端点与管理台
grep -n "Mapping" oryxos-web/src/main/java/io/oryxlabs/oryxos/web/controller/ScheduleApiController.java
# 预期：4 个映射（GET / GET executions / POST run / PUT）
```

---

## 四、剩余人工项

> 已单独立卷：[`manual-test-guide.md`](manual-test-guide.md)（Step 0~7，体例照第 19 / 22 / 24 / 25 / 26 节那几份；Step 0~5 的每条命令与预期输出均在 2026-10-20 用 mock 档真跑过）。

| 项 | 状态 | 说明 |
|---|---|---|
| **管理台目视**：打开 `/admin/schedules`，点「立即执行」看计数与历史刷新、点「停用 / 启用」看状态标签 | **用户已执行（2026-10-20，整体通过）** | 机器判据只到「源码里只有这两个写动作、路径受限」；按钮背后的真实交互没有浏览器测试基建（第 26 节 research D3 已决定不挂 `npm test`）。用户报告其余判据均成立，未逐步说明各步输出，按既定口径只记整体结论 |
| **真群 webhook 推送** | **用户已执行（2026-10-20，整体通过）** | 首跑「应用侧全对、群里没消息」：查库定位为 `tool_invocations` 三次 `notify` 全部 `success=0`、飞书业务码 **19024 Key Words Not Found**（群机器人「自定义关键词」安全设置）。**产品侧行为正确**（飞书档 fail-closed、失败如实落审计）；向导两处判据缺陷已修（见下）。用户修复群机器人关键词设置后重跑成功、群里收到消息，最终确认人工验收通过（具体操作未逐步说明，按既定口径只记整体结论） |
| **`SchedulerFlowIT` / `RestartRecoveryIT` 复跑** | **作者已在真 key 上跑过（各 1/1）** | 命令见 §三·3；CI 默认跳过（`@Tag("integration")`），别人机器需自备 key |
| **第 31 节 Demo 前置**：记忆偏好、新闻 MCP、Demo Agent 的 `schedules` | **待 Demo 节** | 本节只落了白名单域名（天气 / GitHub / 既有 webhook 域名）；通知渠道是运行期数据，不入库到仓库 |

**Step 6 首跑抓到的向导缺陷（两处，已修并留痕于向导「实测记录」；修复后重跑通过）**：

1. **把「任务成功」当成了「推送成功」**：向导原判据只有 `lastStatus:"success"` + 群消息。真实语义是 `lastStatus` 只表示 ReAct 跑完没抛异常；工具失败后模型拿到错误继续组织答复，任务照样 `success`。推送成败的唯一权威证据是 `tool_invocations.success` / `error_message`，**15 个 REST 端点里没有任何一个暴露它**——故向导 6.3 新增一条只读查库判据（`python3` 内置 `sqlite3`）。
2. **原「本机没有 sqlite3 CLI、查库一律走 REST」在 Step 6 不成立**：该判据没有 REST 通路。已改为「REST 查不到推送成败，查库用 python3」。

另记录一条**可观测性边界**（不是本节缺陷）：`serve.log` 的 `WARN 工具调用失败` 不带原因，任务级状态与工具级成败也不互相反映；排障必须查 `tool_invocations`。

> 留痕口径照第 19 / 22 / 25 / 27 节：作者实跑的结论写「整体通过」，不虚构步骤级输出；未做的人工项明确标「待人工」，不写成已验过。

---

## 五、备注

1. **本节抓到的静态门禁红（已修，如实留痕）**：
   - **P3C `TransactionMustHaveRollbackRule` ×3**：`JpaScheduledTaskStore` 的 `register` / `recordExecution` / `setEnabled` 上的 `@Transactional` 未声明 `rollbackFor`。修复：三处改为 `@Transactional(rollbackFor = Exception.class)`——`recordExecution` 跨两张表写，回滚语义是真的需要的，不是为过门禁而加。
   - **FindSecBugs `EI_EXPOSE_REP2` ×1**：`ScheduleApiController` 构造器持有 `ScheduledTaskStore` 被报「暴露内部表示」。按本仓既定口径**就地注解豁免并写理由**（与 `ProfileApiController` 同款）：构造器注入协作对象是依赖注入本身。
   - 两次全量 verify 之间还跑了一次 `spotless:apply`（google-java-format 重排新文件），属格式化而非逻辑修改。
2. **写测试时抓到的两个坑（已固化进测试，避免后来者重踩）**：
   - 任务 id 含 `#`：`GET /api/v1/schedules/{id}/executions` 手工拼 `%23` 会被 `RestTemplate` 二次编码成 404；正确做法是走 **URI 模板变量**（测试里统一用 `getData(path, uriVariables)`）。已写进 `specs/009-web-service/contracts/rest-endpoints.md` 的 2026-10-20（第 28 节）修订注。
   - `runNow` 的同步性让 IT 断言无竞态：HTTP 返回时 `runCount` 已含本次，不需要 sleep / 轮询。
3. **已知边界（不是缺陷，均已显式登记）**：
   - **v1 没有 `retired`**：`AGENT.md` 里删掉的任务在 `scheduled_tasks` 留下旧行——`GET /schedules` 照旧列出，`POST .../run` 对它返回 404（调度器内存里没有它）。退役语义随扩展阶段补。
   - **一轮真链路是 3 次模型调用，不是课件写的 2 次**（原因见 §一·差异 2）。
   - **60 秒整体超时仍未强制**：第 26 节裁决 ② 的已知边界（强制掐断会撞「全程同步阻塞」硬门禁），本节未改变；工具 / HTTP / notify / 搜索各自的超时都在。
   - **`ask_user` 在钟推里的分档**：第 25 节裁决 4 的已知边界，本节未动（`userInteraction()` 的 javadoc 有 2026-10-18 修订注）。
   - **`task_executions` 无保留 / 清理策略**：每次执行（成功失败）都插入一行，长期运行会无界增长；课件未要求，核心阶段未做。运维侧若在意，可先手工归档，扩展阶段再补保留策略。
4. **未 commit / 未 push / 未运行 `package.sh`**——同步时机由用户决定。`docs/class/` 自 2026-10-14 起随仓库跟踪。
5. 请求中的「三样域名」：天气源 `api.open-meteo.com` 与 GitHub `api.github.com` 已加入随包默认；团队 webhook 域名（飞书 `open.feishu.cn` / Lark `open.larksuite.com` / 企微 `qyapi.weixin.qq.com`）自第 19 / 24 节起就在表内，本节未重复添加。
6. **交付后复核（2026-10-20，用户要求复核本报告）——抓到四处，均已修**：
   - **官网首页 `Home.vue` 漏同步（本节交付物的真实缺口，不是报告笔误）**：`CLAUDE.md`「对外内容三处同步」要求 README + 官网首页 + 官网文档页三处同改，第 27 节也是这么做的；本节只改了 README 与八页文档页，`Home.vue` 中英 4 处仍写「11 个 REST 端点 / 只读管理台」。已补上（11 → 15、「只读管理台」→「管理台（定时任务页可写）」）。连带发现 `website/guide/roadmap.md` 中文页那句「第一版管理台是只读的」也已过期——**英文页本节已改、中文页漏了**，已按英文页口径对齐。根因与第 24/25 节向导抓红同类：只核了「清单里的文件改了」，没核「宣称的事实（三处同步）是否处处成立」——三处同步的检查应该是一次全仓 grep，不是凭记忆点文件。
   - **报告 §三·1「oryxos-web 修改 3」计数错**：实际修改 6 个文件（2 个 Java + 4 个前端），紧随其后的句子自己列了全部 6 处，计数与自述矛盾。已改。
   - **报告 §二·5 H4③ 证据命令不可复现**：原写的 `grep -rnE "sk-..."` 不带路径限定，在本机会扫到 gitignored 的 `bin/config.yaml`（真 key 的合法存放点）得到 1 条命中，与「零命中」的结论对不上。已改为实际跑过的限定范围命令并注明原因。
   - **`CLAUDE.md` 第 28 节条目漏记 `api.github.com`**：配置句只写了 `api.open-meteo.com`，与本报告 §五·5 及 `application.yaml` 不符。已补。
   - 复核同时**重新核对无误**的：五个测试类条数（1/2/1/1/1）、`AgentSchedulerTest` 22 → 31（+9）、623 = 591 + 32 算术、全部行级引用（`AgentScheduler.java:180/222/246`、`JpaScheduledTaskStore.java:25/38`、控制器四个映射行、`SchedulesView.vue:48/71`）、零改动面 `git diff`、H4⑤ 零命中、`schema.sql` 7 张表、storage 不反向依赖 core。
