# 验收报告：定时任务 —— 给 Agent 补上第三种触发源（第 25 节）

| 项 | 值 |
| --- | --- |
| Feature | [`specs/008-agent-scheduler/spec.md`](spec.md) |
| Plan | [`specs/008-agent-scheduler/plan.md`](plan.md) |
| Tasks | [`specs/008-agent-scheduler/tasks.md`](tasks.md) |
| Quickstart | [`specs/008-agent-scheduler/quickstart.md`](quickstart.md) |
| 契约 | [`specs/008-agent-scheduler/contracts/agent-scheduler.md`](contracts/agent-scheduler.md) |
| 课件 | `docs/class/第25节：定时任务模块 原理解析、实现与代码讲解.md` |
| 分支 | `class-25` |
| 日期 | 2026-10-19（交付日 2026-10-18，收尾与门禁复核 2026-10-19） |
| 任务完成度 | **18 / 18** |
| 结论 | **通过**。`mvn clean verify` 全绿（9 模块 SUCCESS，**514** 条测试，0 failures / 0 errors / 0 skipped）；课件 harness 四条守点逐条对号；零改动面 `git diff` 为空；H4 六条全局不变量自查通过；**人工验收已执行完毕（2026-10-19，未报告失败）**——判据清单见 §四。 |

---

## 一、六项证据

### 证据 1：`mvn clean verify` 全绿（含 P3C / SpotBugs / FindSecBugs / PMD / Checkstyle / Spotless / OWASP）

```bash
./mvnw -o clean verify
```

关键输出：

```text
[INFO] Reactor Summary for OryxOS Parent 0.1.0-SNAPSHOT:
[INFO] OryxOS Parent ...................................... SUCCESS [  2.738 s]
[INFO] oryxos-storage ..................................... SUCCESS [ 14.047 s]
[INFO] oryxos-core ........................................ SUCCESS [  8.330 s]
[INFO] oryxos-provider .................................... SUCCESS [  4.835 s]
[INFO] oryxos-memory ...................................... SUCCESS [ 11.186 s]
[INFO] oryxos-tool ........................................ SUCCESS [ 14.722 s]
[INFO] oryxos-channel-cli ................................. SUCCESS [  2.396 s]
[INFO] oryxos-web ......................................... SUCCESS [  2.329 s]
[INFO] oryxos-cli ......................................... SUCCESS [ 20.968 s]
[INFO] oryxos-boot ........................................ SUCCESS [  2.436 s]
[INFO] BUILD SUCCESS
[INFO] Total time:  01:24 min
```

测试汇总（各模块级汇总行，合计 **514**）：

| 模块 | Tests | Failures | Errors | Skipped |
| --- | --- | --- | --- | --- |
| `oryxos-storage` | 14 | 0 | 0 | 0 |
| `oryxos-core` | 67 | 0 | 0 | 0 |
| `oryxos-provider` | 7 | 0 | 0 | 0 |
| `oryxos-memory` | 87 | 0 | 0 | 0 |
| `oryxos-tool` | 254 | 0 | 0 | 0 |
| `oryxos-cli` | 85 | 0 | 0 | 0 |
| **合计** | **514** | **0** | **0** | **0** |

前序节（第 24 节）基线是 **488** 条；本节新增 **26** 条（`AgentSchedulerTest` 22 + `SchedulerAssemblyTest` 4），488 + 26 = 514 ✓。

### 证据 2：课件 harness 映射表的每个测试类存在且非空，关键回归测试逐个对号

课件 §四的 harness 映射表是 **4 行**（1 个测试类覆盖 4 个坑）：

| # | 课件测试点（守住的坑） | 落地用例 | 类 / 行 | 状态 |
| --- | --- | --- | --- | --- |
| 1 | 注册时 `CronTrigger` 带上了配置的 cron **和时区**（ArgumentCaptor 抓注册参数） | `registerAll_passesConfiguredCronAndZone`<br>`@DisplayName("注册时 CronTrigger 带上了配置的 cron 和时区")` | `AgentSchedulerTest:281` | ✅ |
| 2 | 锁被占时本次触发直接跳过、不排队 | `runOnce_skipsWhenTaskLockHeld`<br>`@DisplayName("上一次还没跑完_本次触发直接跳过")` | `AgentSchedulerTest:352` | ✅ |
| 3 | `runOnce` 内部抛异常：不外抛、**锁在 finally 里被释放** | `runOnce_swallowsExceptionFromProcess`（`@DisplayName("任务抛异常_不外抛")`）<br>+ `runOnce_releasesLockAfterFailure_secondRunProceeds`（`@DisplayName("任务抛异常_不外抛且锁必须被释放")`） | `AgentSchedulerTest:426` / `:436` | ✅ |
| 4 | 会话三元组固定 `("scheduler","scheduler",profileName)`，两次触发拿到同一 Session | `runOnce_usesFixedSchedulerSessionIdentity` / `runOnce_reusesSameSessionAcrossTriggers` | `AgentSchedulerTest:258` / `:267` | ✅ |

**课件 harness 里「写出代码」的两个最值钱用例，断言逻辑逐条保真**（方法名译成英文，课件中文原名进 `@DisplayName`）：

- 课件 `上一次还没跑完_本次触发直接跳过` → `runOnce_skipsWhenTaskLockHeld`：占锁 → 调 `runOnce` → `verify(agentService, never()).process(any(), any())` → `finally` 放锁。**`never()` 而非「断言抛了异常」**——后者在「调用了但被拒」时也会绿，抓不住「压根没发生」。
- 课件 `任务抛异常_不外抛且锁必须被释放` → `runOnce_releasesLockAfterFailure_secondRunProceeds`：`thenThrow` → `assertDoesNotThrow` → **再调一次** → `verify(agentService, times(2)).process(any(), any())`。**`times(2)` 是判据本身**：漏了 `finally unlock()` 的实现不抛异常、日志照打，只有「二进宫还进得来」能抓住它。该断言**刻意与「不外抛」拆成两个方法**（`runOnce_swallowsExceptionFromProcess` 单独存在），因为只断言「不抛异常」时漏放锁的实现照样绿。

两个测试类均存在且非空：

```bash
$ grep -c "@Test" oryxos-core/src/test/java/io/oryxlabs/oryxos/core/schedule/AgentSchedulerTest.java
22
$ grep -c "@Test" oryxos-cli/src/test/java/io/oryxlabs/oryxos/cli/SchedulerAssemblyTest.java
4
```

`SchedulerAssemblyTest` 另覆盖课件未列但本节 FR 要求的装配面（FR-012 / FR-019）：

| 用例 | 守的 FR | 说明 |
| --- | --- | --- |
| `agentScheduler_hasExactlyOneBean` | — | `getBeansOfType(AgentScheduler.class)` 恰好一个 |
| `taskScheduler_poolSizeIsExactlyTwo` | FR-019 | 断 `getScheduledThreadPoolExecutor().getCorePoolSize() == 2`——**「恰好 2」不是「不超过 2」**，且**不断 `getPoolSize()`**（后者初始化后委托给 JDK 执行器的**存活线程数**，没跑过任务时恒为 0，断它抓到的是假红；2026-10-18 实测抓红后改判） |
| `taskSchedulerBean_hasExactlyOneCandidate` | — | `getBeansOfType(TaskScheduler.class)` 恰好一个候选（多一个会让上面那条读到错的 Bean；第 18 节有 `BeanDefinitionOverrideException` 前科） |
| `scheduledRegistration_happensOnlyInResidentCommands` | FR-012 | **源码级断言**：`ServeCommand` / `GatewayCommand` 含 `.registerAll()`，`ChatCommand` / `CliAssemblyConfiguration` 不含。断的是**调用形态**（带点前缀）而非单词出现——javadoc 里提到 `registerAll()` 是文档，不是调用 |

> **为什么 FR-012 读源码不读行为**（D12 已落档）：Spring 6.2.19 的 `ThreadPoolTaskScheduler` **没有 `getScheduledTasks()`**（T001 已 `javap` 核实），且 `profileRegistry()` 读的是 `Workspace.current()`（进程 cwd），单测造不出「有 `schedules` 的工作区」。两条路都堵死，故按 `ToolOnboardingCostTest` / `LogStreamSeparationTest` 的既有先例走源码判定。

### 证据 3：「本节交付物」逐项存在性核对

课件 §三「本节交付物」四项：

| 课件交付物 | 落地 | 核对 |
| --- | --- | --- |
| 代码：`AgentScheduler`（`registerAll` + `runOnce` + 按任务 id 的 `ReentrantLock` 表） | `oryxos-core/src/main/java/io/oryxlabs/oryxos/core/schedule/AgentScheduler.java`（204 行） | ✅ |
| 代码：`ScheduleConfig`（id/cron/zone/message） | `oryxos-core/src/main/java/io/oryxlabs/oryxos/core/schedule/ScheduleConfig.java` | ✅ |
| 测试：`AgentSchedulerTest` | `oryxos-core/src/test/java/io/oryxlabs/oryxos/core/schedule/AgentSchedulerTest.java`（22 条） | ✅ |
| 配置：Profile 新增 `schedules` 字段 | **第 16 节已交付**（`Profile.ScheduleSpec` + `ProfileLoader` 解析）——本节**只消费、不改**（[`spec.md:49`](spec.md) 已留痕） | ✅（零改动） |
| 约定：会话身份固定 `("scheduler","scheduler",profileName)`；失败只记日志不崩调度器 | `AgentScheduler.runOnce`（`:129` / `:131-135`） | ✅ |

```bash
$ ls -l oryxos-core/src/main/java/io/oryxlabs/oryxos/core/schedule/ \
        oryxos-core/src/test/java/io/oryxlabs/oryxos/core/schedule/ \
        oryxos-cli/src/test/java/io/oryxlabs/oryxos/cli/SchedulerAssemblyTest.java
-rw-r--r-- 1 wngbob wngbob  9767 AgentScheduler.java
-rw-r--r-- 1 wngbob wngbob  1294 ScheduleConfig.java
-rw-r--r-- 1 wngbob wngbob 22510 AgentSchedulerTest.java
-rw-r--r-- 1 wngbob wngbob  4987 SchedulerAssemblyTest.java
```

**超出课件交付物、但被本节 FR / 裁决要求的**（不是自行发挥，逐条有出处）：装配处两个 `@Bean`（`taskScheduler` / `agentScheduler`，契约 §4.1）、`serve` / `gateway` 命令体各一行（契约 §4.2 / 裁决 3）、`SchedulerAssemblyTest`（FR-012 / FR-019 的机器判据）。

### 证据 4：前序节全部测试回归绿（跨节契约证据）

上表 514 条里，**488 条是第 16~24 节的既有测试**，本次全量 `clean verify` 中全部保持绿——其中直接关系到本节的三处跨节契约：

- `ProfileLoaderTest`（`oryxos-core`，5 条）：`schedules` 的 5 段 cron 解析**一字未改**仍绿；
- `SessionServiceTest`（`oryxos-core`，11 条）：`getOrCreate` 三元组取或建仍绿；
- `CliCommandTreeTest`（`oryxos-cli`）：命令树**恰好 15 条叶子**仍绿——本节没有新增 / 删除 / 改名任何命令。

### 证据 5：H4 六条全局不变量逐条自查

| # | 不变量 | 结论 | 证据 |
| --- | --- | --- | --- |
| ① | 涉外 IO 首行过 `Sandbox.enforce` | **N/A（本类不做任何 IO）** | `grep -nE "java\.net\|HttpClient\|Files\.\|ProcessBuilder\|Runtime\.getRuntime" AgentScheduler.java` → **零命中**。本类只调 `SessionService` / `AgentService`，涉外 IO 全在它们的下游（`ToolExecutor` → 各内置工具），那些工具第 24 节已各自过沙箱，本节未触碰 |
| ② | LLM 调用成败落 `llm_calls`、工具执行成败落 `tool_invocations` | **成立，且本节新增审计代码 0 行** | `grep -nE "llm_calls\|tool_invocations\|LlmCall\|ToolInvocation" AgentScheduler.java` → **零命中**；由源码级单测 `runOnce_doesNotLogOrPersistAnythingItself` 钉死。失败落库全部经由 `AgentService.process` 内部既有路径 |
| ③ | 无明文 key | **成立** | `grep -nEi "api[_-]?key\|secret\|token\|password\|Bearer " AgentScheduler.java` → **零命中**。本类不持有任何凭证 |
| ④ | `session_id` 只在 `SessionManager` 内拼接 | **成立** | `grep -nE "session_id\|sessionId" AgentScheduler.java` → **零命中**——本类全程拿不到 `session_id` 字符串，根本没有拼接的位置；它只传三元组给 `SessionService.getOrCreate` |
| ⑤ | 无 Reactor / `CompletableFuture` / 自建线程池 | **成立**（一处需说明，见下） | `grep -nE "Reactor\|Mono\|Flux\|CompletableFuture\|Executors\.\|new Thread\(\|ThreadPoolExecutor" AgentScheduler.java` → **零命中**。**装配处的 `new ThreadPoolTaskScheduler()`（`CliAssemblyConfiguration.java:685`）不是自建线程池**——它是 FR-019 / 契约 §4.1 明令要的那个 Spring 调度 Bean（`setPoolSize(2)` + 线程名前缀 `oryxos-scheduler-`），由 Spring 生命周期管理、`@Bean` 唯一落点声明。**如实记录**：它确实是「本仓第一次出现调度线程池」，但它是**交付物本身**，不是绕过同步模型的旁路；章程原则 8（同步执行模型）约束的是业务执行路径，本节未引入任何异步编排 |
| ⑥ | 无 Spring AI 自动工具执行路径 | **成立** | `grep -rn "MethodToolCallback\|ToolCallingManager\|internalToolExecutionEnabled\|ToolExecutionEligibility" oryxos-core/.../schedule/` → **零命中**。本节不碰 `ToolExecutor` / `ReActLoop`（零改动面已证） |

### 证据 6：静态门禁抓红三处（如实留痕，含唯一的妥协点）

本节交付过程中被静态门禁抓红 **3 次**，均已修复（**没有删断言 / `@Disabled` / 放宽阈值**）：

**抓红 ① P3C `UndefineMagicConstantRule`（魔法值）**
- **抓到什么**：`oryxos-core/target/pmd.xml` 第 136 / 139 行报 `normalizeCron` 里的字面量 `5` / `6`（cron 段数）为魔法值。
- **怎么修的**：抽成三个常量——`UNIX_CRON_FIELDS = 5` / `SPRING_CRON_FIELDS = 6` / `SPRING_SECOND_FIELD = "0 "`（`AgentScheduler.java:36-43`），报错消息由拼接重建，**渲染出的文本与修前逐字一致**（`cron 表达式段数非法：'0 9 * *'（支持 5 段或 6 段，实际 4 段）`），故 `registerAll_rejectsInvalidCronFieldCount_andKeepsOtherRules` 无需改动即绿。

**抓红 ② FindSecBugs `CRLF_INJECTION_LOGS`（CWE-117）×3**
- **抓到什么**：三条带占位符的日志（注册失败 ERROR / 跳过 WARN / 执行失败 ERROR）被报「可能把 CRLF 注入日志」。日志里的值（Profile 名、任务 id、异常消息）来自 `AGENT.md`，属**不可信输入**。
- **第一步修法**：加 `sanitizeForLog`（`\r` / `\n` → `_`，`AgentScheduler.java:145`），与既有的 `GlobalExceptionHandler.sanitizeForLog` **同款**。这一步之后**仍然红**。
- **实测隔离触发点**（不是猜）：把文件备份到 `/tmp/AgentScheduler.bak`，做了一次对照实验——**常量格式 + 已 sanitize 的字符串变参、不带异常对象 → 绿；带上异常对象 `e` → 红**（`error(String, Throwable)` 与 `error(String, Object...)` 两种重载都红）。即触发点是**异常对象本身**，不是消息模板、也不是实参。
- **最终处理**：契约 §1.3 逐字冻结了日志要带「**+ 异常对象**」（那正是栈迹的来源），故**恢复异常对象**，并按父 pom 里写明的本仓既定口径——**「就地 `@SuppressFBWarnings` 注解豁免 > 全局 excludeFilterFile」（豁免点自带理由、跟着代码走）**——在 `registerAll()`（`:77`）与 `runOnce()`（`:119`）上各挂一条 `@SuppressFBWarnings(value = "CRLF_INJECTION_LOGS", justification = ...)`，理由写明「三个占位实参都已过 `sanitizeForLog`；剩余面是契约要求带的异常对象（栈由 logback 渲染、不参与 SLF4J 的格式化），且本仓既有的 `GlobalExceptionHandler.internalError` 也是同样写法」。
- **⚠️ 这是本节唯一一处静态门禁妥协点**，已如实写进本报告与 `CLAUDE.md`。旁证：`GlobalExceptionHandler` 里的 `log.error("internal error", ex)` 是**先例**——同一形态的调用在本仓早已存在且未被告警（因为它的格式串是纯常量、无变参）。

**抓红 ③ P3C `AvoidCommentBehindStatementRule`（行尾注释）**：`CliAssemblyConfiguration.java` 里 `scheduler.setPoolSize(2); // FR-019…` 被报行尾注释，已把注释移到语句上方独立一行（`:686-687`）。该规则在 `p3c-ruleset.xml:29` 引用的 `rulesets/java/ali-comment.xml` 里——**与 ① 同属 P3C/PMD 门禁**（2026-10-19 复核更正：此处原误标为「非静态门禁，实现期」；P3C 本就接入 `mvn verify`，§2.1 变更总结表对该处的「PMD 抓红」标注才是正确口径）。

**还有一次是**：`registerAll_passesConfiguredCronAndZone` 与 `registerAll_rejectsInvalidZone_andKeepsOtherRules` 在 T010 落地时**按设计红**（`expected: 09:00 but was: 17:00` / `Expected size: 1 but was: 2`），T011 实现 `resolveZone` + 两参 `CronTrigger` 后转绿——这是 harness 先行的**预期红→绿循环**，不是缺陷。

---

## 二、变更总结（给 reviewer 的导读）

### 2.1 改动点

以 `git status --short` / `git diff --stat` 实测为准。

```text
$ git status --short
 M CLAUDE.md
 M docs/TechnicalSolution.md
 M oryxos-cli/src/main/java/io/oryxlabs/oryxos/cli/CliAssemblyConfiguration.java
 M oryxos-cli/src/main/java/io/oryxlabs/oryxos/cli/command/GatewayCommand.java
 M oryxos-cli/src/main/java/io/oryxlabs/oryxos/cli/command/ServeCommand.java
?? oryxos-cli/src/test/java/io/oryxlabs/oryxos/cli/SchedulerAssemblyTest.java
?? oryxos-core/src/main/java/io/oryxlabs/oryxos/core/schedule/
?? oryxos-core/src/test/java/io/oryxlabs/oryxos/core/schedule/
?? specs/008-agent-scheduler/

$ git diff --stat
 CLAUDE.md                                          |  6 ++-
 docs/TechnicalSolution.md                          |  9 ++++
 .../oryxos/cli/CliAssemblyConfiguration.java       | 50 ++++++++++++++++++++++
 .../oryxos/cli/command/GatewayCommand.java         |  3 ++
 .../oryxlabs/oryxos/cli/command/ServeCommand.java  |  3 ++
 5 files changed, 69 insertions(+), 2 deletions(-)
```

**新增（`oryxos-core`）**——本节的正面交付物：

| 文件 | 动机 |
| --- | --- |
| `oryxos-core/.../core/schedule/AgentScheduler.java` | 第三种触发源本体：`registerAll()` 扫 Profile 的 `schedules` 逐条注册（含 cron 归一化 / 时区解析 / 单条失败隔离）、`runOnce()` 占锁 → 取或建会话 → 调 `AgentService.process`、按任务 id 的 `ReentrantLock` 表 |
| `oryxos-core/.../core/schedule/ScheduleConfig.java` | 承载调度视图的纯记录类（`id` / `cron` / `zone` / `message`），裁决 2 新建；`cron` 存 Profile 里的**原文**，报错要带用户写的那一句 |
| `oryxos-core/.../core/schedule/AgentSchedulerTest.java` | 22 条：课件 harness 四条守点 + cron 归一化 / 单条失败隔离 / id 确定性 / 不去重 / 时区缺省 |

**新增（`oryxos-cli`）**：

| 文件 | 动机 |
| --- | --- |
| `oryxos-cli/.../cli/SchedulerAssemblyTest.java` | 4 条装配面与源码级断言（FR-012 / FR-019 / 单候选） |

**新增（`specs/`）**：`specs/008-agent-scheduler/`（`spec.md` / `plan.md` / `tasks.md` / `research.md` / `data-model.md` / `quickstart.md` / `manual-test-guide.md` / `contracts/agent-scheduler.md` / `checklists/requirements.md` / 本报告）。其中 `manual-test-guide.md` 是 **2026-10-19 复核时补的**（体例照第 19/22/24 节那三份）。

**修改（前序节文件被本节触碰，逐处动机）**：

| 文件 | 改动 | 动机 |
| --- | --- | --- |
| `oryxos-cli/.../CliAssemblyConfiguration.java` | **+50** | ① 两个新 `@Bean`：`taskScheduler()`（固定池 2，FR-019）、`agentScheduler(...)`（契约 §4.1）；② `userInteraction()` javadoc 挂 `2026-10-18 修订注`（裁决 4：定时任务 `ask_user` 分档本节不做，原挂账「归第 25 节」不成立）；③ 一行 import；④ 行尾注释移到语句上方（PMD 抓红） |
| `oryxos-cli/.../command/ServeCommand.java` | **+3** | 命令体里一行 `context.getBean(AgentScheduler.class).registerAll();` + 一行注释 + 一行 import。**命令名 / 参数 / 退出码一字未动** |
| `oryxos-cli/.../command/GatewayCommand.java` | **+3** | 同 `ServeCommand`（契约 §4.2 / 裁决 3） |
| `docs/TechnicalSolution.md` | **+9** | §8.5 补「实现回填（2026-10-18，第 25 节）」五条口径 + 一条 `2026-10-18 修订注`（§8.5 的「OryxOS 启动时」与 §8.6 的「随 `serve`/`gateway` 常驻」不一致，**以 §8.6 为准**，原文不重写） |
| `CLAUDE.md` | **+8 / -5** | ① 当前状态：推进到第 25 节、测试数 488 → 514、两条「读代码前必知」条目（五条要点 + 静态门禁抓红留痕）、待办指向第 26 节；② **2026-10-19 复核的三处更正**：静态门禁「抓红**两处**」→「**三处**」（补 P3C `AvoidCommentBehindStatementRule`）、SQLite 表清单「**七张表**」→「**已建 5 张**（`schema.sql` 实测）+ 2 张尚未创建（第 28 节）」、新增「第 25 节交付后复核」条目（含向导落点与「**失败那轮不落 `sessions` 行**」这条实测行为） |

**零改动面（本节的硬判据，`git diff` 全为空）**：

```bash
$ git diff --stat -- \
    oryxos-core/.../core/engine/AgentService.java \
    oryxos-core/.../core/engine/ReActLoop.java \
    oryxos-core/.../core/engine/ToolExecutor.java \
    oryxos-core/.../core/profile/ProfileLoader.java \
    oryxos-core/.../core/profile/Profile.java \
    oryxos-core/.../core/session/SessionService.java \
    oryxos-storage/src/main/resources/db/schema.sql \
    oryxos-cli/.../command/ChatCommand.java \
    oryxos-cli/.../cli/CliCommandTreeTest.java \
    oryxos-boot/src/main/resources/application.yaml \
    oryxos-core/pom.xml oryxos-cli/pom.xml oryxos-boot/pom.xml
（无输出）
```

**没有移动 / 改名 / 删除任何文件**；`docs/class/` 未触碰；`specs/005-tool-registry/` 下历史文档按既定约束未触碰。

### 2.2 重点 review 清单（按风险排序）

1. **【架构】注册时机在命令体、不在装配处**——`AgentScheduler` **刻意不带** `@Component` / `@PostConstruct`（`AgentScheduler.java:32` 类声明上无任何注解），注册只发生在 `ServeCommand.java:48` 与 `GatewayCommand.java:36`。若把它挪进装配处或类上，「起上下文即注册」会让交互式 `chat` 也注册定时任务（撞 FR-012）。**判据**：`SchedulerAssemblyTest.scheduledRegistration_happensOnlyInResidentCommands`（读源码断 `.registerAll()` 的**调用形态**）。

2. **【跨节契约】零改动面是否真为零**——本节的架构主张是「新增一个入口不动核心引擎一行代码」（章程原则 IX）。**判据**：上面那段 `git diff --stat` 无输出。重点看 `AgentService.java` / `ReActLoop.java` / `ToolExecutor.java` / `SessionService.java` 四个文件。**若 reviewer 发现其中任何一个被本节碰过，本节的核心主张即不成立。**

3. **【契约 / 课件同构】cron 5 段归一化只在 `AgentScheduler` 内部一处**（`AgentScheduler.java:164` `normalizeCron`）：5 段前补 `0 `、6 段原样透传、其余报错。**MUST NOT** 回写 Profile、**MUST NOT** 进 `ProfileLoader`、**MUST NOT** 在装配处预处理（FR-003）。报错消息里带的是**用户写的原文**（不是 trim 后的）。**为什么这是硬冲突**：课件正文写 `new CronTrigger(sc.getCron(), sc.getZoneId())` 原样透传，但 spring-context 6.2.19 的 `CronTrigger` 要求 6 段，5 段当场抛 `IllegalArgumentException`；而本仓既有契约与测试一律是 5 段（`ProfileLoaderTest` 的用例值就是 `"0 9 * * *"`）。裁决 1 定「5 段为准、兼容 6 段」。

4. **【静态门禁妥协点】**`AgentScheduler.java:77` 与 `:119` 的两条 `@SuppressFBWarnings("CRLF_INJECTION_LOGS")`——**本节唯一一处妥协**。理由见 §一 证据 6；判据是「实参已全部过 `sanitizeForLog`（`:145`）+ 契约 §1.3 要求带异常对象 + 父 pom 认可就地豁免」。**review 时请确认 justification 里的三条理由仍成立**；若将来 `GlobalExceptionHandler` 改了写法，这条豁免的理由要跟着复核。

5. **【测试判据强度】三条容易被写弱、但本节刻意写强的断言**：
   - `runOnce_skipsWhenTaskLockHeld`（`AgentSchedulerTest:352`）断 `never()`，**不是**「断言抛了异常」——后者在「调用了但被拒」时也会绿；
   - `runOnce_releasesLockAfterFailure_secondRunProceeds`（`:436`）断 `times(2)` 的**二进宫**，是唯一能抓住「漏 `finally unlock()`」的判据；
   - `registerAll_passesConfiguredCronAndZone`（`:281`）**不比对 `Trigger` 相等**——`CronTrigger.equals` **忽略时区**（`Asia/Shanghai` 与 `UTC` 的 `equals` 返回 `true`，且类上没有时区 getter），照课件原手法写出来的断言**恒绿、什么都守不住**；本仓改为「对固定 `Clock` 求 `nextExecution`，断言落点在配置时区下是 09:00 且**不等于**错时区那次」。
   - 另：锁相关的观察**必须在另一个线程上执行捕获的 `Runnable`**（`new Thread(r).start(); join()`）——`ReentrantLock` **可重入**，同一线程占着锁再 `tryLock()` 照样成功，同线程执行什么都守不住（2026-10-18 T006 实测抓红）。

6. **【装配面】`ThreadPoolTaskScheduler` 是本节唯一新增的线程池**（`CliAssemblyConfiguration.java:684-689`，固定 2、不做配置键）。池大小是本节唯一一处「跨任务并发度」旋钮，做成配置键等于把行为承诺变成运维可改的不确定性（FR-019）。**断言用 `getCorePoolSize()` 而不是 `getPoolSize()`**（后者委托 JDK 执行器的**存活线程数**，没跑过任务时恒为 0）。另注意：**MUST NOT** 加 `@EnableScheduling`（会打开 Spring 自己的 `@Scheduled` 扫描，是第二真相源）。

### 2.3 如何验证

```bash
# ── 0. 环境（WSL2）
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64   # 或本机 JDK 21 路径

# ── 1. 全量门禁（含 Spotless / P3C / Checkstyle / SpotBugs / FindSecBugs / OWASP）
./mvnw -o clean verify
# 预期：BUILD SUCCESS，9 个模块全 SUCCESS，514 条测试 0 failures/0 errors/0 skipped

# ── 2. 只跑本节两个测试类
./mvnw -o -pl oryxos-core -am test -Dtest=AgentSchedulerTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -o -pl oryxos-cli  -am test -Dtest=SchedulerAssemblyTest -Dsurefire.failIfNoSpecifiedTests=false
# 预期：22 条 + 4 条，全绿

# ── 3. 四条关键回归单独看
./mvnw -o -pl oryxos-core -am test \
  -Dtest='AgentSchedulerTest#registerAll_passesConfiguredCronAndZone+runOnce_skipsWhenTaskLockHeld+runOnce_swallowsExceptionFromProcess+runOnce_releasesLockAfterFailure_secondRunProceeds' \
  -Dsurefire.failIfNoSpecifiedTests=false
# 预期：4 条全绿

# ── 4. 零改动面（本节核心主张：新增入口不动引擎一行）
git diff --stat -- \
  oryxos-core/src/main/java/io/oryxlabs/oryxos/core/engine/AgentService.java \
  oryxos-core/src/main/java/io/oryxlabs/oryxos/core/engine/ReActLoop.java \
  oryxos-core/src/main/java/io/oryxlabs/oryxos/core/engine/ToolExecutor.java \
  oryxos-core/src/main/java/io/oryxlabs/oryxos/core/profile/ProfileLoader.java \
  oryxos-core/src/main/java/io/oryxlabs/oryxos/core/profile/Profile.java \
  oryxos-core/src/main/java/io/oryxlabs/oryxos/core/session/SessionService.java \
  oryxos-storage/src/main/resources/db/schema.sql \
  oryxos-cli/src/main/java/io/oryxlabs/oryxos/cli/command/ChatCommand.java \
  oryxos-cli/src/test/java/io/oryxlabs/oryxos/cli/CliCommandTreeTest.java \
  oryxos-boot/src/main/resources/application.yaml \
  oryxos-core/pom.xml oryxos-cli/pom.xml oryxos-boot/pom.xml
# 预期：无输出

# ── 5. 依赖方向 / 不变量 grep（全部预期零命中）
grep -nE "llm_calls|tool_invocations|LlmCall|ToolInvocation" \
  oryxos-core/src/main/java/io/oryxlabs/oryxos/core/schedule/AgentScheduler.java
grep -nE "session_id|sessionId" \
  oryxos-core/src/main/java/io/oryxlabs/oryxos/core/schedule/AgentScheduler.java
grep -nE "Reactor|Mono|Flux|CompletableFuture|Executors\.|new Thread\(" \
  oryxos-core/src/main/java/io/oryxlabs/oryxos/core/schedule/AgentScheduler.java

# ── 6. 静态门禁妥协点（应恰好 2 条，且都带 justification）
grep -n "SuppressFBWarnings" -A3 \
  oryxos-core/src/main/java/io/oryxlabs/oryxos/core/schedule/AgentScheduler.java

# ── 7. 命令树仍是 15 条（本节未新增 / 删除 / 改名任何命令）
./mvnw -o -pl oryxos-cli -am test -Dtest=CliCommandTreeTest -Dsurefire.failIfNoSpecifiedTests=false
# 预期：绿
```

**剩余人工项**：见 §四。

---

## 三、本节的关键取舍（供 reviewer 理解「为什么是这样」）

1. **cron 5 段为准、兼容 6 段**（裁决 1）——课件正文与本地依赖硬冲突（课件原样透传，Spring 6.2.19 要求 6 段；本仓既有契约与测试一律 5 段）。归一化只发生在一处，既有测试与文档一字不改。
2. **新建 `ScheduleConfig`，`Profile.ScheduleSpec` 一字未改**（裁决 2）——调度视图（含派生的 `id`）与配置视图分离，`id` 由「Profile 名 + 配置内序号」确定性派生，**不去重**（去重是静默行为）。
3. **注册只在 `serve` / `gateway` 命令体**（裁决 3）——不动「起上下文即注册」的语义，`chat` 不注册（FR-012）。
4. **`ask_user` 分档不做**（裁决 4）——**已知边界**：钟推复用命令体那一个终端档，会去读**调度线程的**终端。归扩展阶段评估；`userInteraction()` 的 javadoc 已挂 `2026-10-18 修订注`。
5. **固定 2 线程池、不做配置键**（裁决 5 / FR-019）——池大小是本节唯一一处跨任务并发度旋钮。
6. **失败隔离的 catch 宽度是 `Exception`**（FR-009）——模型 / 工具 / 网络任何失败都不能让调度线程带着异常死掉；**本节新增审计代码 0 行**。
7. **`ReentrantLock` 按任务 id 维度、`tryLock()` 无参**（FR-008）——不排队、不等待；一个任务不阻塞另一个。锁表只增不减（条目数上界 = `schedules` 总条数）。
8. **时区恒显式传 `CronTrigger`**（FR-005）——缺省也是 `ZoneId.systemDefault()` 显式取出来再传，不依赖框架的隐含行为。

---

## 四、剩余人工项清单

> **人工验收状态（2026-10-19）：用户已按 Step 0~7 全程执行完毕**（真 DeepSeek 模型、真 `oryxos.jar`），**未报告任何一步失败**；按既定留痕口径（照第 19 / 22 节）**只记整体结论、不写步骤级细节**。执行过程中实跑命中两处——**换终端丢 `$JAR`** 与 **Step 3 判据不可达**——均已当场修并落档（见 §五 备注 8、9 与 [`manual-test-guide.md`](manual-test-guide.md)）。**下表保留为判据清单**（不再是「待办」）。

**harness 已判卷，以下几项需真模型 + 真 `oryxos.jar`**。**向导落点：[`manual-test-guide.md`](manual-test-guide.md)**（Step 0~7，体例照第 19/22/24 节那三份；步骤素材来自 [`quickstart.md`](quickstart.md)，2026-10-19 复核时单独立卷并补上「实测记录 / 前置事实 / 先排除已被单测覆盖的」三节）。

| # | 人工项 | 向导位置 | 需要什么 | 通过判据 |
| --- | --- | --- | --- | --- |
| 1 | **真实到点触发一次** | Step 1 | `DEEPSEEK_API_KEY` + `oryxos.jar` | 日志出现 `定时任务注册完成：共 1 条`；等 1~2 分钟，`session list` 里多出 `channel=scheduler / user=scheduler / profile=tick-demo` 的会话（`sessionId` 形如 `scheduler:scheduler:tick-demo`）；**全程没有人向它发过消息** |
| 2 | **审计表里留下与人推同形的账** | Step 1 尾 | 同上 | `llm_calls` 里出现 `session_id = scheduler:scheduler:tick-demo` 的行，**字段与 `chat` 跑出来的行没有任何区别**（同一张表、同一套字段、同一条写入路径） |
| 3 | **时区按声明的跑（真链路侧）** | Step 2 | 同上 | 同一 cron 表达式、两个不同 `zone` 的 Agent，**各自注册成功**（日志 `共 3 条` = `tick-demo` + `tz-utc` + `tz-sh`），不因时区不同而报错或跳过。**机器判据已在单测**（落点断言），人工只确认「注册不失败」 |
| 4 | **重叠触发不会并发改写同一会话**（真链路侧） | Step 3 | **反面判据**：用 Step 1 的日志，**不应**出现「同一 session 被并发写」的迹象（同一条 `llm_calls` 时间戳成对、或 `tool_invocations` 里同一次调用记两遍）。**那行 `上一次尚未结束，本次触发跳过` 的 WARN 在真链路上抓不到**——`schedule(task, CronTrigger)` 是自重新调度且以**上一次完成时刻**为基准，同一任务结构上不会自重叠（2026-10-19 实测：每秒 cron + 1.5 秒执行，60 秒 33 次调用、0 条 WARN）；该分支是**纵深防御**，判卷在单测 |
| 5 | **失败之后任务还能继续跑**（真链路侧） | Step 4 | 故意用错的 key | ① 日志出现 `定时任务 tick-demo#1 执行失败：...`（ERROR，失败不静默）；② **进程没退出**；③ **等下一分钟再次出现同一条 ERROR**——这说明锁被释放了；**只出现一次之后再无动静 = 任务永久卡死 = 不通过**；④ `llm_calls` 那几行 `success = 0`、`error_message` 非空 |
| 6 | **改 cron 不用重新编译** | Step 5 | 同上 | 改 `AGENT.md` 里的 cron 后**不重新编译、不 `mvn package`**，直接重启 `serve`，触发间隔按新值变 |
| 7 | **确认 `chat` 不注册定时任务** | Step 6 | 同上 | `chat` 的日志里**不出现** `定时任务注册完成：共 N 条`。**这一条写向导时已在真链路上验过**（`printf '/quit\n' \| chat --profile tick-demo` → 退出码 0、`grep -c` = 0；同一工作区 `serve` 对照 = 1）——列在这里是让你复现，不是新证据 |
| 8 | **清理** | Step 7 | — | `rm -rf .oryxos/agents/tick-demo .oryxos/agents/tz-utc .oryxos/agents/tz-sh`，停掉 `serve` |

**不需要人工的（`mvn test` 绿即打勾）**：注册参数（cron + 时区）正确、锁被占时零调用且不排队、异常不外抛 + 锁真的释放（二进宫）、会话三元组固定且两次触发同一会话、cron 归一化 / 单条失败隔离 / id 确定性、装配面（池大小 2 / 单候选 / `chat` 不注册）。

**排障**：向导末尾有 **13 行**对照表（`Unable to access jarfile`（`$JAR` 为空）/ `定时任务注册完成` 不出现 / `共 0 条` / cron 段数非法 / 时区非法 / 到点没动静 / `session list` 看不到但 `llm_calls` 有行 / `llm_calls` 无行 / 只跑一次就再无动静 / **一直等不到 `上一次尚未结束` 的 WARN（正常，不是缺陷）** / 长任务推迟其它任务 / `chat` 里也看到注册日志 / 改了 `AGENT.md` 但触发时间没变）。

---

## 五、备注

1. **未 commit / 未 push / 未运行 `package.sh`**——按 skill 纪律「全程不自动 commit / push / 运行 `package.sh`，同步时机由用户决定」。当前工作区状态见 §2.1 的 `git status --short`（5 个 modified + 4 个 untracked 条目）。
2. **`docs/class/` 自 2026-10-14 起随仓库跟踪**，本节的课件（`docs/class/第25节：定时任务模块 原理解析、实现与代码讲解.md`）已在库内，本次未改动它。
3. **本报告如实记录了三处静态门禁抓红**（P3C 魔法值 / FindSecBugs CRLF / P3C 行尾注释）与**唯一一处妥协点**（两条 `@SuppressFBWarnings`），见 §一 证据 6 与 §2.2 第 4 条——不是「全绿」一句话带过。
4. **一处与课件的口径差异已落档**：课件「本节交付物」列了「配置：Profile 新增 `schedules` 字段」，但该字段**第 16 节已交付**，本节只消费、不改（[`spec.md:49`](spec.md) 有留痕，T017 的零改动面自查覆盖）。
5. **测试方法名一律英文**（驼峰 / snake_case），课件中文原名进 `@DisplayName`——四条课件守点的中文原文已逐条保留（见 §一 证据 2 的表格）。
6. **前序节文档未回修（范围已更正）**：本节没有产生「以现在时断言、现已为假」的**沙箱窗口期类**前序节表述，故无该类修订注需要补；`userInteraction()` 的 `2026-10-18 修订注` 是**本节自己的**挂账修正（裁决 4），已随 T016 落地。**但初稿这句下得略满**——2026-10-19 复核查出**另一类**从未成立的表述：`CLAUDE.md`「SQLite 核心表」原写「**七张表**」并把 `scheduled_tasks` / `task_executions` 当既有表描述，而 `schema.sql` 实为 **5 张**（这两张是第 28 节的交付物，本节 FR-016 明令 MUST NOT 建）。**该处已改正**（不是加修订注——CLAUDE.md 是项目级上下文文件，写成既有表会让后续 agent 误判），两行表目已标「**尚未创建（第 28 节）**」。
7. **本节交付后复核（2026-10-19）——三处记录**：① 本报告 §一 证据 6 原把 P3C `AvoidCommentBehindStatementRule` 标为「非静态门禁，实现期」，实为**第三处静态门禁抓红**（该规则在 `p3c-ruleset.xml:29` 引用的 `ali-comment.xml` 里），标题与计数已由「两处」改为「**三处**」；② 人工验收向导单独立卷为 [`manual-test-guide.md`](manual-test-guide.md)（本报告 §四 的向导落点已改指它）；③ **写向导时抓到四处错**（前两处是口径 / 命令错，第三处是命令本身有 bug，第四处是**判据本身不可达**）：(a) 初稿把「`session list` 里一定多出一条钟推会话」当成 Step 1 判据，而**失败那一轮不落 `sessions` 行**（`AgentService.process` 的 `save` 在循环之后、不在 `finally`，`AgentService.java:62`）——实测无 key 跑 135 秒得 `llm_calls` 3 行 / `sessions` 0 行，已按「成功路径才断言 `sessions`、失败注入只看 `llm_calls`」改写；(b) Step 0 的 jar 自检 `unzip -l "$JAR" | grep -c "core/schedule/..."` **恒返回 0**——`oryxos-core` 在 fat JAR 里是 `BOOT-INF/lib` 下的**嵌套 jar**、不是 `BOOT-INF/classes` 里的散装 class，照初稿判就是**假警报**（指向「重新构建」，会白烧十分钟），已改为 `unzip -p` 抽出嵌套 jar 再查（实测 3 行）；(c) Step 2 的 `sed "s/^    zone: .*/    zone: $2/"` 用 `/` 当分隔符而 `$2` 含 `Asia/Shanghai`，`sed` 报错后**留下一个空文件**再被 `cp` 出去（实测 `profile list` 显示 `tz-sh 无效 ... frontmatter 缺失`），已改用 `|` 并加 `profile list` 自查段；**(d) Step 3 的判据本身不可达**（2026-10-19 用户实跑时命中：重启 `serve` 后**始终等不到**那行 WARN）——初稿让人用 `http_get https://example.com` 拖慢一次执行，三层都是错：`example.com` **不在** `http.allowed_domains` 里（模型真抓也会被 `WhitelistSandbox` 立刻拦下、零延迟）、那次 `tool_invocations` 是 **0 行**（`AGENT.md` 正文写着「直接用一句话回答」，模型没调工具）、而**最根本的一层**是 `taskScheduler.schedule(task, CronTrigger)` 属**自重新调度**且 `CronTrigger` 以**上一次完成时刻**为基准算下一次（反编译 jar 内实际打包的 `spring-context-6.2.19`：`ReschedulingRunnable.run()` = 执行 → 取 completionTime → `triggerContext.update(...)` → `schedule()`；`CronTrigger.determineLatestTimestamp()` 取 `lastCompletion()`），**同一任务结构上不会自重叠**，那行 WARN 无论怎么调都到不了。已把 Step 3 整节改成「反面判据 + 三条证据」。**根因同第 24 节向导那两次**：只核了「命令能跑」，没核「输出与我写的预期是否一致」；(b) 还多一层——预期写反了方向；(d) 是另一类——命令能跑、预期也自洽，**但那条路径在产品里根本到不了**。
8. **用户实跑时命中的一处（2026-10-19，已加固，不算向导写错）**：Step 2 之后用户在新终端跑 `profile list` 得 `Error: Unable to access jarfile`——**`$JAR` 为空**（`JAVA_HOME` 在 gitignore 的 `local-env.sh` 里、换终端也还在，**`JAR` 只在「准备」段里 `export` 过**）。向导原本写了 `export JAR`，但没有预判「换终端」这个动作，也没写这个报错长什么样——而它长得**很像路径拼错**（`Unable to access jarfile` 后面跟一个空字符串）。已加固两处：「准备」段补 `echo`/`ls` 自查 + 一个 ⚠️ 框说明「末尾空字符串就是 `$JAR` 本身、看到就回准备段、**别重新构建**」，坑表加对应一行（**2026-10-19 晚些时候又加了 Step 3 那一行，现 13 行**）。
9. **三段「原计划留给人工」的判据已在真链路上模型无关地跑过**（不改变 §四 的结论，只是把证据做强）：**单条注册失败隔离**（坏 cron / 坏时区各一条 `注册失败`、其余照常 `共 3 条`）、**失败恢复二进宫**（`执行失败` ERROR 每分钟一条、连出 3 条，进程全程活着）、**`chat` 零注册 vs `serve` 恰好 1 条**（FR-012，`printf '/quit\n' | chat ...` 退出码 0）——三者都写进向导的「实测记录」表。故 §四 真正剩下的只有**成功那一路**（真模型真调用、真会话落库）、**「到点了」这件事本身**、**改 cron 不用重新编译**（Step 5，需一次真重启）。**另加一条（2026-10-19）**：**重叠不可能发生**（每秒 cron + 1.5 秒执行，60 秒内 33 次调用、0 条 WARN）也已模型无关地实测过——**但结论与 §四 第 4 行相反方向**：那行 WARN 不是「还没等到」，是**结构上不可达**，见 ③(d)。
10. **跨节挂账（新增）**：定时任务里 `ask_user` 的分档（裁决 4）归扩展阶段评估——`CLAUDE.md` 待办条目已记。
