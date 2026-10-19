# Tasks: 定时任务 —— 给 Agent 补上第三种触发源

**Feature**: `specs/008-agent-scheduler` | **Branch**: `class-25` | **Date**: 2026-10-18

**Input**: Design documents from `specs/008-agent-scheduler/`

**Prerequisites**: [plan.md](plan.md) / [spec.md](spec.md) / [research.md](research.md) / [data-model.md](data-model.md) / [contracts/agent-scheduler.md](contracts/agent-scheduler.md) / [quickstart.md](quickstart.md)

**测试纪律**：课件「验收 harness」里**写出代码**的两个关键回归用例（锁被占时直接跳过 / 抛异常后锁真的被释放）MUST 原样落地，断言逻辑逐条保真；方法名译成英文，**课件中文原名进 `@DisplayName`**。测试方法名一律英文（驼峰 / snake_case），MUST NOT 用中文方法名。

**路径约定**：以下 `…/schedule/` = `oryxos-core/src/main/java/io/oryxlabs/oryxos/core/schedule/`；`…/schedule-test/` = `oryxos-core/src/test/java/io/oryxlabs/oryxos/core/schedule/`；`…/cli/` = `oryxos-cli/src/main/java/io/oryxlabs/oryxos/cli/`；`…/cli-test/` = `oryxos-cli/src/test/java/io/oryxlabs/oryxos/cli/`。

**「零改动面」（FR-015 / FR-003 / FR-016 / FR-012，本节最该被 review 的地方）**：`AgentService.java` / `ReActLoop.java` / `ToolExecutor.java` / `ProfileLoader.java` / `Profile.java` / `SessionService.java` / `schema.sql` / `ChatCommand.java` / `CliCommandTreeTest.java` / `application.yaml`——**本节一行不改**，由 T017 的 `git diff` 自查钉死。

**本节的形状（先说清，免得读起来像偷懒）**：课件「本节交付物」是**一个类**（`AgentScheduler`）加一个 record，四条 user story 是**同一个类的四个行为侧面**（注册 / 时区 / 锁 / 失败隔离），不是四个可独立发布的功能。故 Phase 3~6 **各自按 user story 增量地长出这个类**：US1 长出注册与触发主链路，US2 把时区接上，US3 把锁接上，US4 把失败隔离接上——**每一相结束时该 story 的全部验收场景都绿**，后续相不改前面相的行为。这比「一次写完再回来验证」更贴近 Spec-Kit 的 story 切分，也让「哪个行为是哪一相引入的」有 git 历史可查。

**日志文案**：课件骨架里是英文（`task {} still running, skip this trigger`），本仓一律中文（与 `ToolExecutor` / `ProfileLoader` 一致）。四条文案已在 [contracts/agent-scheduler.md](contracts/agent-scheduler.md) §1.3 逐字冻结，**实现 MUST 逐字照抄，MUST NOT 自行改写**。

---

## Phase 1: Setup（写前核实，无代码产出）

**Goal**: H3 门禁——动手前在本地依赖里核实本节要用到的每一个第三方方法确实存在；核实本节的既有前置（`schedules` 字段）确实已就位。

- [x] T001 [P] **H3 写前核实**（核实不到即触发软门禁 ⑤，停下报告）：
  - `javap -cp ~/.m2/repository/org/springframework/spring-context/6.2.19/spring-context-6.2.19.jar org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler` → 确认有 `setPoolSize(int)` / `getPoolSize()` / `schedule(Runnable, Trigger)`；**确认没有 `getScheduledTasks()`**（research §0.3 已实测，本任务复核——它的缺席正是 D12 存在的原因）
  - 同法核实 `org.springframework.scheduling.support.CronTrigger`：有 `CronTrigger(String, ZoneId)` / `getExpression()` / `nextExecution(TriggerContext)`；**没有时区 getter**（§0.2）
  - 同法核实 `org.springframework.scheduling.TaskScheduler`（`schedule(Runnable, Trigger)` 是抽象方法）与 `org.springframework.scheduling.TriggerContext`（`getClock()` 是 default 方法，**3 个 `last*`** 是抽象方法——**手写桩必须实现它们**；**3 个** `last*Time()` 变体是 default 方法、委托给那 3 个抽象方法，不需要桩）
  - `./mvnw -o -pl oryxos-core dependency:tree` → 确认 `org.springframework:spring-context:jar:6.2.19:compile` 在**编译**类路径上。**本节 MUST NOT 新增任何依赖坐标**（`oryxos-core/pom.xml` 一个字不改）
- [x] T002 [P] **基线核实**（存在性检查，第 1 步 H0 的机器可判定形式）：
  - `grep -rn "TaskScheduler\|ThreadPoolTaskScheduler\|EnableScheduling\|@Scheduled\|CronTrigger\|ReentrantLock" oryxos-*/src` → **零命中**（本节是全仓第一次引入调度，不存在与既有实现撞车的可能）
  - `grep -rn "ScheduleSpec\|schedules" oryxos-core/src/main/java/io/oryxlabs/oryxos/core/profile/` → 确认 `Profile.schedules()` 与 `Profile.ScheduleSpec(cron, zone, message)` 已由第 16 节交付、**本节只读不写**

**Checkpoint**: 四条 `javap` 与两条 `grep` 的结果都符合上面的预期；任一不符 → 停下报告。

---

## Phase 2: Foundational（所有 user story 的阻塞前置）

**Goal**: 调度视图类型就位、测试夹具就位。没有这一步，`AgentScheduler` 的签名写不出来、测试也编不过。

- [x] T003 [P] 新增 `…/schedule/ScheduleConfig.java` —— **逐字**照 [data-model.md](data-model.md) §1.1：`public record ScheduleConfig(String id, String cron, String zone, String message) {}`。**纯记录类**：不带任何注解、不做校验、不持有行为（校验与归一化全在 `AgentScheduler` 里，因为它们需要「Agent 名」这个 `ScheduleConfig` 没有的信息来报错）。**`cron` 存的是 Profile 里写的原文、不是归一化后的形态**——报错消息要带用户写的那个表达式（§1.1 末段）。javadoc 写明四字段含义 + 「`zone` 可为 null / 空白 = 按服务器系统时区」
- [x] T004 `…/schedule-test/AgentSchedulerTest.java` 的**测试替身与夹具**（本任务只写夹具、不写 `@Test`，写不出断言）：
  - `CapturingTaskScheduler implements TaskScheduler` —— 手写桩（**不用 Mockito**，因为要按注册顺序回放 `(Runnable, Trigger)` 对）：`schedule(Runnable, Trigger)` 把两个参数存进两个并列 `List`，**其余 5 个抽象方法**抛 `UnsupportedOperationException`（本节的实现只用这一个；javap 实测 `TaskScheduler` 共 **6 个抽象方法**，5 个 `Date`/`long` 变体是 **default 方法**、委托给抽象方法，**不需要桩**）。提供 `List<Trigger> triggers()` / `List<Runnable> tasks()` 供断言
  - `FixedClockTriggerContext implements TriggerContext` —— 手写桩，持一个固定 `Clock`（`Clock.fixed(Instant.parse("2026-10-18T00:00:00Z"), ZoneOffset.UTC)`），`getClock()` 返回它，**3 个抽象** `last*` 方法一律返回 `null`（「从没跑过」——`CronTrigger.nextExecution` 的语义就是求下一次；**3 个** `last*Time()` 变体是 default 方法、委托给这 3 个抽象方法，不需要桩）
  - `profile(String name, List<Profile.ScheduleSpec> schedules)` 构造助手（`Profile` 是 10 参 record，其余字段传 `null` 走紧凑构造器归一化）与 `config(String id)` 构造助手
  - `@BeforeEach` 里的 `AgentService` / `SessionService` Mockito mock（与 `AgentServiceTest` / `ReActLoopTest` 同款）
  - **此刻不编译**（`AgentScheduler` 尚不存在，`CapturingTaskScheduler` 的方法签名要对着它的真实用法写）——这是 harness 先行的**预期状态**，T006 落地后转绿，**MUST NOT 为了让模块编过而加 `@Disabled` 或删夹具**

**Checkpoint**: T003 单独编译过；T004 与 T006 成对落地后 `./mvnw -pl oryxos-core -am test-compile` 过。

---

## Phase 3: User Story 1 — 配置里写一条定时规则，进程常驻后到点自己跑（Priority: P1）🎯 MVP

**Goal**: `registerAll()` 扫全部 Profile、把每条 `schedules` 注册进调度器（含 cron 归一化与校验、单条失败隔离）；到点 `runOnce()` 按固定三元组取或建会话、调 `AgentService.process`；`serve` / `gateway` 命令体各一行把它接上，`chat` 不接。

**Independent Test**: 纯单测——构造带 `schedules` 的 `Profile` 与 `CapturingTaskScheduler`，调 `registerAll()` 断言注册发生了、cron 与消息逐字一致；再直接调 `runOnce` 断言 `process` 被调一次、会话三元组是 `("scheduler", "scheduler", <名>)`。**全程无 `Thread.sleep`、无真实时钟、不起 Spring 上下文。**

### Tests for User Story 1

- [x] T005 [US1] `…/schedule-test/AgentSchedulerTest.java` 补 US1 组断言（**此刻必然红**），逐条覆盖 [spec.md](spec.md) US1 场景 1~8 与 [data-model.md](data-model.md) §2 / §3.1 / §3.3：
  - `registerAll_registersOneTaskPerScheduleEntry`（场景 1 / 2）：三条 `schedules` → `capturing.triggers()` 恰好 3 个，且逐个断言 `getExpression()` 与配置逐字一致
  - `registerAll_withNoSchedules_registersNothingAndDoesNotThrow`（场景 3）：空 `schedules` → 零注册、不抛异常（**默认配置下的预期状态**）
  - `registerAll_derivesIdsDeterministically`（[data-model.md](data-model.md) §2 约束 1）：**观测机制 = 锁接缝反推**，MUST NOT 为此偷加访问器（冻结的方法面上 id 不可直接观测——`Trigger` 只暴露 cron、捕获的 `Runnable` 是不透明 lambda；加访问器 = 改契约 §1.1，撞软门禁②）。**捕获的 `Runnable` MUST 在另一个线程上执行**（`new Thread(r).start(); join()`，2026-10-18 T006 实测抓红：`ReentrantLock` **可重入**，同一线程占着锁再 `tryLock()` 照样成功，同线程执行什么都守不住；`start+join` 是确定性交接、不是 `Thread.sleep`）。Profile `weather` 一条规则 → `registerAll()` 后从 `capturing.tasks()` 取那个 `Runnable` → `scheduler.lockFor("weather#1").lock()` 占住 → 执行该 `Runnable` → `verify(agentService, never()).process(any(), any())`（**被跳过 = 这个 runnable 的 `config.id()` 就是 `weather#1`**）；清空捕获列表（或换新 `CapturingTaskScheduler`）再来一次 `registerAll()`，同样占着 `#1` 的锁执行新捕获的 `Runnable` → 仍零调用（**两次派生同一个 id**）。`finally` 放锁
  - `registerAll_doesNotDeduplicateIdenticalRules`（§2 约束 2）：两条完全相同的规则 → `capturing.triggers()` **恰好 2 个**（没塌成 1 条 = 没去重）；id 互不相同的证据同样走锁接缝——占住 `weather#1` 的锁后**顺序执行两个捕获的 `Runnable`**，断言**第一个被跳过、第二个正常执行**（`process` 恰好被调 1 次）——第二个不受 `#1` 的锁影响 = 它的 id 是 `#2`。`finally` 放锁
  - `registerAll_normalizesFiveAndPassesSixFieldCronInSameProfile`（场景 6 / §3.1，**一个 Profile 装两条**——场景 6 的要点正是「同一 Profile 内归一化与透传并存」）：第一条 5 段 `"0 9 * * *"` → 交给调度器的是 `"0 0 9 * * *"`；第二条 6 段 `"0 30 8 * * *"` → **原样透传**。`capturing.triggers()` 恰好 2 个，逐个断言 `getExpression()`
  - `registerAll_rejectsInvalidCronFieldCount_andKeepsOtherRules`（场景 7）：`"0 9 * *"`（4 段）→ 该条不注册、**同 Profile 的另一条合法规则照常注册**、**另一 Agent 完全不受影响**
  - `registerAll_rejectsMissingCron_andKeepsOtherRules`（场景 8）：只写 `message` 的规则 → 同 7
  - `registerAll_rejectsSyntacticallyInvalidCron_andKeepsOtherRules`（Edge Case「段数对但内容非法」）：`"0 0 99 * * *"` → `CronTrigger` 构造时抛、被同一层 catch 接住、其它规则照常
  - `runOnce_callsProcessWithConfiguredMessage`（场景 4）：断言 `process(session, <配置里的消息>)` 被调一次
  - `runOnce_usesFixedSchedulerSessionIdentity` / `runOnce_reusesSameSessionAcrossTriggers`（场景 5）：断言 `getOrCreate("scheduler", "scheduler", <名>)` 被调用；两次触发拿到**同一个会话**（`verify(sessionService, times(2))` + 返回同一 mock）
  - ~~`runOnce_doesNotConcatenateSessionIdItself`~~ **已删**（analyze A1）：`AgentScheduler` 全程拿不到 `session_id` 字符串，根本没有拼接的位置——H4 不变量 ④ 由上面的 `verify(getOrCreate("scheduler", "scheduler", <名>))` 隐含覆盖，正面证据由 T017 的 `SessionService` diff 为空承担，单独立项是重复

### Implementation for User Story 1

- [x] T006 [US1] 新增 `…/schedule/AgentScheduler.java`（**与 T005 成对**）——**逐字**照 [contracts/agent-scheduler.md](contracts/agent-scheduler.md) §1.1 的构造器与可见性表：
  - `public class AgentScheduler`，**类上不带** `@Component` / `@Configuration` / `@Service`（D3：注册时机由命令体决定，不交给容器生命周期）
  - 四参构造器 `public AgentScheduler(TaskScheduler, ProfileRegistry, AgentService, SessionService)`，四个字段 `final`；构造时 `requireNonNull` 四个依赖
  - `public void registerAll()`：遍历 `profileRegistry.all()` → 每条 `schedules`（序号从 **1** 起）→ 派生 `ScheduleConfig`（`id = profile.name() + "#" + 序号`，§2）→ 归一化 cron（§3.1：`trim().split("\\s+")` 判段数，**5 段前补 `0 `**，6 段透传，其余抛 `IllegalArgumentException`）→ `taskScheduler.schedule(() -> runOnce(profile, config), new CronTrigger(cron6))`。**每条一个 `try/catch (RuntimeException)`**：失败打 §1.3 的 ERROR（**指名 Agent 与任务 id**，消息里带**用户写的原文 cron**）后 `continue`（§3.3：单条失败不拖垮同 Profile 其它规则、更不影响别的 Agent）。收尾打 §1.3 的 INFO `定时任务注册完成：共 {} 条`——**N 为 0 时也打**（SC-008 的预期状态）
  - `void runOnce(Profile profile, ScheduleConfig config)`（**包级私有**，D8 测试接缝）：`sessionService.getOrCreate("scheduler", "scheduler", profile.name())` → `agentService.process(session, config.message())`。**本相不做异常隔离**（US4 引入）
  - **锁机制在本相交付（2026-10-18 修订注，analyze N2 的必然推论）**：T005 的两条 id 断言用「锁接缝反推」观测（占住预期 id 的锁 → 执行捕获的 `Runnable` → 断言跳过 / 放行），这要求 `taskLocks` / `lockFor` / `tryLock` 跳过 + §1.3 的 WARN 在 T006 就存在，否则 T005 在 Phase 3 结束时既编不过也绿不了。原写「本相不建锁（US3 引入）」在 N2 修复落地那一刻已被证伪，**原文按本注修订**：T006 交付锁机制全量（`taskLocks` 字段 / 包级私有 `lockFor` / 无参 `tryLock()` + 跳过 WARN / `finally` 解锁 / 锁表只增不减的 javadoc），**catch 仍归 US4**。连带：T012 的四条 US3 断言落地时**不再「必然红」**（实现先于它们存在，harness 先行在本节让位于「观测机制决定实现位置」）；T013 改判为**复核任务**（逐条对 §2.7 / §2.8 / §2.10 与 §4 不变式 3 / 4，无新代码）
  - 本相**不写**时区解析（US2 引入）；`new CronTrigger(cron6)` 单参构造，走系统时区
  - 归一化 MUST 只在这一处发生（FR-003）：**MUST NOT** 回写 Profile、**MUST NOT** 进 `ProfileLoader`、**MUST NOT** 在装配处预处理
- [x] T007 [P] [US1] 新增 `…/cli-test/SchedulerAssemblyTest.java`（**此刻必然红**，两个 `@Bean` 还没声明），起真上下文（`CliTestContext.boot(dbFile)`，与 `NotifyAssemblyTest` / `MemoryAssemblyTest` 同款——`CliApplication` 由 `boot` 自动追加，MUST NOT 再把 `CliAssemblyConfiguration.class` 当 extraSource 传：冗余、且与全模块既有形态不一致）：
  - `agentScheduler_hasExactlyOneBean`：`context.getBeansOfType(AgentScheduler.class)` 恰好一个
  - `taskScheduler_poolSizeIsExactlyTwo`（FR-019 / D4）：从容器取出 `ThreadPoolTaskScheduler` 断言 `getScheduledThreadPoolExecutor().getCorePoolSize() == 2`——**「恰好 2」而不是「不超过 2」**：池大小是本节唯一一处「跨任务并发度」的旋钮，改它就改行为，必须钉死。**断 `getCorePoolSize()` 而非 `getPoolSize()`**（2026-10-18 实测抓红修订）：后者初始化后委托给 JDK 执行器的**存活线程数**，没跑过任务时恒为 0，断它抓到的是假红
  - `taskSchedulerBean_hasExactlyOneCandidate`（analyze M2）：`assertThat(context.getBeansOfType(TaskScheduler.class)).hasSize(1)`——多一个候选会让上面那条「池大小 2」读到错的 Bean（第 18 节有 `BeanDefinitionOverrideException` 前科）
  - `scheduledRegistration_happensOnlyInResidentCommands`（FR-012，**源码级断言**，D12）：读 `…/cli/command/ServeCommand.java` 与 `GatewayCommand.java` 的源文件文本，断言**含** `registerAll()`；读 `…/cli/command/ChatCommand.java` 与 `CliAssemblyConfiguration.java` 的 `agentScheduler(` 方法体，断言**不含** `registerAll()`。**为什么读源码不读行为**：Spring 6.2.19 的 `ThreadPoolTaskScheduler` **没有 `getScheduledTasks()`**（T001 已核实），且 `profileRegistry()` 读的是 `Workspace.current()`（进程 cwd），单测造不出「有 schedules 的工作区」——两条路都堵死，故按 `ToolOnboardingCostTest` / `LogStreamSeparationTest` 的既有先例走源码判定（D12 已落档说明）
  - ~~`cliCommandTree_stillHasExactlyFifteenLeaves`~~ **已删**（analyze D1）：与 `CliCommandTreeTest.commandTreeHasExactlyTheContractedLeaves` 重复，且那边 `leaves(...)` / `collect(...)` 是 `private static`，复述要重写收集器。FR-017 的证据是既有测试**保持绿**（T017 的 `mvn clean verify` 覆盖），不是复制一份
- [x] T008 [US1] `…/cli/CliAssemblyConfiguration.java` 加两个 `@Bean` —— **逐字**照 [contracts/agent-scheduler.md](contracts/agent-scheduler.md) §4.1：`taskScheduler()`（`new ThreadPoolTaskScheduler()` + `setPoolSize(2)` + `setThreadNamePrefix("oryxos-scheduler-")`，**不加 `@ConditionalOnMissingBean`**——D4 已说明「为测试改设计」不做）与 `agentScheduler(ThreadPoolTaskScheduler, ProfileRegistry, AgentService, SessionService)`。**MUST NOT** 加 `@EnableScheduling`（本节的注册是显式调用，不是注解扫描；加了会把 Spring 自己的 `@Scheduled` 扫描打开，是第二真相源）。javadoc 写清「为什么注册时机在命令体而不是 `@PostConstruct`」（D3）
- [x] T009 [US1] `…/cli/command/ServeCommand.java` 与 `…/cli/command/GatewayCommand.java` 各加**一行** —— `context.getBean(AgentScheduler.class).registerAll();`，位置在 Spring 上下文起好之后、`keepAlive()` / 打印之前（[contracts/agent-scheduler.md](contracts/agent-scheduler.md) §4.2）。**命令名 / 参数 / 退出码三项一字不动**（不触发 `cli-surface.md` 的修订程序）；`CliCommandTreeTest` 必须保持绿。**`ChatCommand.java` MUST NOT 触碰**（FR-012）

**Checkpoint**: `./mvnw -pl oryxos-core -am test -Dtest=AgentSchedulerTest` 与 `./mvnw -pl oryxos-cli -am test -Dtest=SchedulerAssemblyTest` 双绿——US1 的 8 条验收场景全部可独立验证。

---

## Phase 4: User Story 2 — 时区按声明的跑，不让系统时区替用户做主（Priority: P1）

**Goal**: 把配置里的 `zone` 解析成 `ZoneId` 并**显式**传给 `CronTrigger`；缺省走系统时区但**照样显式取出来传**；非法 zone 不注册且不拖垮别的规则。

**Independent Test**: 纯单测——同一 cron 表达式 + 两个不同时区各注册一次，对同一个固定 `Clock` 的桩触发上下文求 `nextExecution`，断言 `Asia/Shanghai` 那次落点在 `Asia/Shanghai` 下是 09:00，且**不等于** `UTC` 那次（相差 8 小时）。

### Tests for User Story 2

- [X] T010 [US2] `…/schedule-test/AgentSchedulerTest.java` 补 US2 组断言（**此刻必然红**——本相的 `registerAll` 还没接时区），逐条覆盖 [spec.md](spec.md) US2 场景 1~4 与 [data-model.md](data-model.md) §3.2：
  - `registerAll_passesConfiguredCronAndZone`（**课件 harness 守点「注册时带上 cron 和时区」**，`@DisplayName("注册时 CronTrigger 带上了配置的 cron 和时区")`）：从 `capturing.triggers()` 取出 `Trigger` → `nextExecution(new FixedClockTriggerContext())` → 断言该时刻在 `Asia/Shanghai` 下是 **09:00**，且**不等于**同一表达式配 `UTC` 的那次（两者相差 8 小时）。**MUST NOT 改成比对 `Trigger` 相等**——`CronTrigger.equals` **忽略时区**（research §0.2 实测：`Asia/Shanghai` 与 `UTC` 的 `CronTrigger.equals` 返回 `true`，且类上没有时区 getter），照课件原手法写出来的断言**恒绿**、什么都守不住
  - `registerAll_zoneAbsent_fallsBackToSystemDefault_withExplicitZone`（场景 4）：不声明 zone → 仍注册成功、不抛异常；且注册用的 `Trigger` **带一个显式时区**（用 `ZoneId.systemDefault()` 求落点，断言与配置了 `zone: <系统时区名>` 的那条**落点相同**）
  - `registerAll_rejectsInvalidZone_andKeepsOtherRules`（Edge Case「zone 写了非法值」）：`zone: "Mars/Olympus"` → 该条不注册 + §1.3 的 ERROR，同 Profile 其它规则照常
- [X] T011 [US2] `…/schedule/AgentScheduler.java` 接上时区 —— 加一个 `private ZoneId resolveZone(String zone, ...)`（或等价私有方法）：`null` / 空白 → `ZoneId.systemDefault()`；否则 `ZoneId.of(trim())`，`DateTimeException` **包成 `IllegalArgumentException`**（D6：不新建异常类型）后由 `registerAll` 既有的 catch 接住。`registerAll` 里改成 `new CronTrigger(cron6, zoneId)`——**无论声明与否都传显式 `ZoneId`**（FR-005：MUST NOT 依赖「不传时区时框架自己怎么处理」这种隐含行为）。**MUST NOT** 在 `ScheduleConfig` 里做解析（那是纯记录类）

**Checkpoint**: `./mvnw -pl oryxos-core -am test -Dtest=AgentSchedulerTest` 绿，US2 的 4 条验收场景全部可独立验证。

---

## Phase 5: User Story 3 — 上一次还没跑完，这次直接跳过（Priority: P1）

**Goal**: 按任务 id 的进程内 `ReentrantLock` 表；拿不到锁直接跳过（**不排队、不等待、不判失败**）；不同任务 id 互不阻塞。

**Independent Test**: 纯单测——手工占住某个任务 id 的锁（`lockFor(id).lock()`，模拟上一次还没跑完），调 `runOnce`，断言 `agentService.process` **一次都没被调用**（不是「被调用了但被拒」，而是压根没发生）；放锁后再调一次，断言这次被调用了。

### Tests for User Story 3

- [X] T012 [US3] `…/schedule-test/AgentSchedulerTest.java` 补 US3 组断言（**此刻必然红**——本相之前 `runOnce` 无锁，占锁也拦不住），逐条覆盖 [spec.md](spec.md) US3 场景 1~4：
  - `runOnce_skipsWhenTaskLockHeld`（**课件 harness 守点「锁被占时本次触发直接跳过」**，`@DisplayName("上一次还没跑完_本次触发直接跳过")`）：`lockFor("task-1").lock()` → `runOnce` → `verify(agentService, never()).process(any(), any())` → `finally` 放锁。**MUST NOT 写成「断言抛了异常」或「断言返回了」**——那两种写法在「调用了但被拒」时也会绿，抓不住「压根没发生」
  - `runOnce_proceedsAfterLockReleased`（场景 2）：放锁后再调 → `verify(agentService, times(1)).process(any(), any())`
  - `runOnce_doesNotBlockOtherTaskIds`（场景 3）：占住 `task-1` 的锁，调 `runOnce(profile, config("task-2"))` → **照常执行**（锁是按任务 id 的，不是一把全局锁）
  - `runOnce_doesNotWaitOnHeldLock`（场景 1 的「不排队」面）：占锁后调 `runOnce` 的**耗时上界**断言——用 `assertTimeoutPreemptively(Duration.ofSeconds(1), ...)`（若实现写成 `lock.lock()` 阻塞等待，这条会在占锁不放时超时变红）。**MUST NOT 用 `Thread.sleep` 造时序**，超时断言本身就是判据
- [X] T013 [US3] `…/schedule/AgentScheduler.java` 接上任务锁 —— 加 `private final Map<String, ReentrantLock> taskLocks = new ConcurrentHashMap<>();`（D7）与包级私有 `ReentrantLock lockFor(String taskId)`（`computeIfAbsent(taskId, k -> new ReentrantLock())`，D8 测试接缝）。`runOnce` 改成：`lock = lockFor(config.id())` → `if (!lock.tryLock()) { log.warn(<§1.3 的跳过文案>); return; }` → `try { ...既有两行... } finally { lock.unlock(); }`。**`tryLock()` 用无参重载**（不等待，MUST NOT 写 `tryLock(timeout)`——那是排队）。**本相只加「加锁 + finally 解锁」，不加 catch**（异常隔离归 US4）。javadoc 写明锁表**只增不减**的理由（[data-model.md](data-model.md) §4 不变式 3：任务永不结束，条目数上界是 `schedules` 总条数）

**Checkpoint**: `./mvnw -pl oryxos-core -am test -Dtest=AgentSchedulerTest` 绿，US3 的 4 条验收场景全部可独立验证。

---

## Phase 6: User Story 4 — 一次跑挂了，调度器不死、锁不放死（Priority: P1）

**Goal**: `process` 抛任何异常都**不外抛**（调度器不死）、**记 ERROR 日志**（失败不静默）、**锁在 `finally` 里真的被释放**（任务不永久卡死）、失败被隔离在单个任务内。

**Independent Test**: 纯单测——`process` 抛 `RuntimeException` → 断言 `runOnce` **不外抛** → **再调一次** `runOnce` → 断言 `process` 这次**被调用了**。

### Tests for User Story 4

- [X] T014 [US4] `…/schedule-test/AgentSchedulerTest.java` 补 US4 组断言（**此刻必然红**——本相之前异常会直接上抛），逐条覆盖 [spec.md](spec.md) US4 场景 1~4：
  - `runOnce_swallowsExceptionFromProcess`（**课件 harness 守点「执行内部抛异常不外抛」**，`@DisplayName("任务抛异常_不外抛")`）：`when(agentService.process(any(), any())).thenThrow(new RuntimeException("boom"))` → `assertDoesNotThrow(() -> scheduler.runOnce(profile, config("task-1")))`
  - `runOnce_releasesLockAfterFailure_secondRunProceeds`（**课件 harness 守点「锁必须被释放」**，`@DisplayName("任务抛异常_不外抛且锁必须被释放")`）：**同一个方法里连着上面那步**——抛异常后再调一次 `runOnce` → `verify(agentService, times(2)).process(any(), any())`（`times(2)` 而非 `times(1)`：第一次调了并抛、第二次又调了，**说明锁真的放了**）。**这条 MUST 与上一条分开成两个方法**：只断言「不抛异常」时，一个漏了 `finally unlock()` 的实现**照样绿**——它不抛异常、日志也照打，但任务从此永久卡死。这是本节唯一一条「只有二进宫能抓住」的缺陷（US4 的 Why this priority 原文）
  - `runOnce_failureIsIsolatedPerTaskId`（场景 3）：让 `task-1` 抛异常，再调 `task-2` → `task-2` 照常执行
  - `runOnce_doesNotLogOrPersistAnythingItself`（场景 4 / FR-011 的机器可判定形式）：源码级断言——`AgentScheduler.java` 的源文件文本里**不出现** `llm_calls` / `tool_invocations` / `LlmCall` / `ToolInvocation`（**本节新增审计代码 0 行**，失败落库全部经由 `AgentService` 内部既有路径）
- [X] T015 [US4] `…/schedule/AgentScheduler.java` 接上失败隔离 —— 在 `try { ... } finally { lock.unlock(); }` 之间加 `catch (Exception e) { log.error(<§1.3 的执行失败文案>, e); }`。**catch 的宽度是 `Exception` 不是 `RuntimeException`**（FR-009：模型 / 工具 / 网络任何失败都不能让调度线程带着异常死掉）。**MUST NOT 吞了不记**——ERROR 日志是「失败不静默」的唯一载体（FR-006）。**MUST NOT** 在这里补审计代码（场景 4）

**Checkpoint**: `./mvnw -pl oryxos-core -am test -Dtest=AgentSchedulerTest` 绿，US4 的 4 条验收场景全部可独立验证。**至此四条 user story 全部独立可验。**

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: 文档连带修订、全量门禁、quickstart 自动化部分验证。

- [X] T016 [P] **文档连带修订**（[plan.md](plan.md) D11 的清单，逐条按「带日期修订注、不重写原文」的本仓惯例）：
  - `…/cli/CliAssemblyConfiguration.java` 的类 javadoc —— 补一段说明本节新增的两个 `@Bean` 与「注册时机在命令体」的理由（与 T008 同一处，若 T008 已写则本条只复核）
  - `…/cli/CliAssemblyConfiguration.java` 的 `userInteraction()` javadoc（**D11①，analyze N3 抓回的漏项**）——第 409 行附近「挂 `UnsupportedUserInteraction` 的场合…那两处的装配归第 25 / 26 节」这句挂账已部分失效（裁决 4 / D10：定时任务的 `ask_user` 分档**本节不做**），挂带日期（`2026-10-18`）修订注：「归第 25 节」的挂账不成立，归扩展阶段评估；**原文不重写**
  - `docs/TechnicalSolution.md` §8.5 —— ① 补本节实现口径（cron 5 段为准兼容 6 段、`id = <profileName>#<序号>`、固定 2 线程池）；② 挂带日期修订注（`2026-10-18`）修正 §8.5 与 §8.6 的措辞不一致（前者写「OryxOS 启动时」、后者写「随 serve/gateway 一起常驻调度」——**以本节实现的后者为准**，§8.5 那句以现在时写的「启动时」已被证伪）
  - `CLAUDE.md` 「当前状态」—— 按本节交付事实更新（第 25 节完成、测试数、`AgentScheduler` / `ScheduleConfig` 落位、`serve`/`gateway` 各一行、`ask_user` 分档已知边界）
  - **MUST NOT** 触碰 `specs/005-tool-registry/` 下的历史文档（既定约束）
- [X] T017 **全量门禁**：`./mvnw clean verify`（含 Spotless / P3C / Checkstyle / SpotBugs / FindSecBugs / OWASP）→ 全绿。另跑**零改动面自查**：`git diff --stat` 确认 `AgentService.java` / `ReActLoop.java` / `ToolExecutor.java` / `ProfileLoader.java` / `Profile.java` / `SessionService.java` / `schema.sql` / `ChatCommand.java` / `CliCommandTreeTest.java` / `application.yaml` **均不在改动清单里**；`git diff --stat -- oryxos-core/pom.xml oryxos-cli/pom.xml` 为空（**不新增依赖**）。红了当场修，**MUST NOT 删断言 / 加 `@Disabled` / 放宽阈值**
- [X] T018 **quickstart 自动化部分验证 + 人工项清单**：按 [quickstart.md](quickstart.md) 末尾的「不跑真模型的那几条」跑一遍（两个测试类 + `clean verify`），确认预期成立；把 quickstart 的 Step 0~7 中**需要真模型 / 真 `oryxos.jar`** 的部分整理成「剩余人工项清单」写进验收报告，明确告知用户「harness 已判卷，这几项等你人工过」

**Checkpoint**: `mvn clean verify` 全绿；零改动面自查为空；剩余人工项清单成形。

---

## Dependencies & Execution Order

### Phase Dependencies

```text
Phase 1 (Setup)        无依赖
      ↓
Phase 2 (Foundational)  依赖 Phase 1（T001 核实不到就不该往下写）
      ↓
Phase 3 (US1) ──► Phase 4 (US2) ──► Phase 5 (US3) ──► Phase 6 (US4)
      ↓                                                  ↓
Phase 7 (Polish) ◄──────────────────────────────────────┘
```

**Phase 4~6 之间是**顺序**关系而非并行**——三者改的是**同一个文件**（`AgentScheduler.java`）与**同一个测试类**（`AgentSchedulerTest.java`）。这是本节的形状决定的（一个类的四个行为侧面），不是流程选择。

### Within Each Phase

- **测试先于实现**（harness 先行）：T005→T006、T010→T011、T012→T013、T014→T015，每一对里测试先落地且**此刻必然红**，实现落地后转绿
- T007（`SchedulerAssemblyTest`）与 T008 / T009 同相：测试先写、必红，两个 `@Bean` 与两行调用落地后转绿

### 并行机会

- **Phase 1 内**：T001 与 T002 完全独立，可并行
- **Phase 2 内**：T003 与 T004 触及不同文件，可并行（T004 引用了 T003 的类型，但夹具可以先写）
- **Phase 3 内**：T005（`oryxos-core` 测试）与 T007（`oryxos-cli` 测试）分属两个模块、不同文件，可并行
- **跨相无并行**：Phase 4~6 改同一批文件

---

## Parallel Example: Phase 3

```bash
# 两个测试类分属两个模块，可同时写（都先红）：
Task: "AgentSchedulerTest 的 US1 组断言 in oryxos-core/src/test/.../core/schedule/AgentSchedulerTest.java"
Task: "SchedulerAssemblyTest 的装配面与源码级 FR-012 断言 in oryxos-cli/src/test/.../cli/SchedulerAssemblyTest.java"

# 实现侧按依赖顺序（T006 先于 T008：装配要能 new 出这个类）：
Task: "AgentScheduler 的注册与触发主链路 in oryxos-core/src/main/.../core/schedule/AgentScheduler.java"
Task: "CliAssemblyConfiguration 的两个 @Bean in oryxos-cli/src/main/.../cli/CliAssemblyConfiguration.java"
Task: "ServeCommand / GatewayCommand 各加一行 registerAll() in oryxos-cli/src/main/.../cli/command/"
```

---

## Implementation Strategy

### MVP First（Phase 3 一相即可演示）

1. Phase 1（核实）+ Phase 2（类型与夹具）
2. Phase 3（US1）——**到此已可跑通「配置里写一条规则 → 注册 → 到点触发 → 会话与审计落库」的完整主链路**
3. **STOP and VALIDATE**：`./mvnw -pl oryxos-core -am test -Dtest=AgentSchedulerTest` + `./mvnw -pl oryxos-cli -am test -Dtest=SchedulerAssemblyTest`
4. 此时可跑 [quickstart.md](quickstart.md) Step 0~1（真模型人工项）

### Incremental Delivery

1. Phase 3（US1）→ 主链路可用 → 演示
2. Phase 4（US2）→ 时区按声明生效（**最难被发现的错误被消除**）
3. Phase 5（US3）→ 重叠触发不再叠加执行
4. Phase 6（US4）→ 失败不崩、锁不放死
5. Phase 7 → 门禁全绿 + 文档对齐 + 人工项交接

### 每相结束都要做的

- 跑该模块的测试（`./mvnw -pl <module> -am test -Dtest=<类>`），红了当场修，**不攒到最后**
- 改完代码跑 `./mvnw spotless:apply`（google-java-format 是 `mvn verify` 的门禁之一）

---

## Notes

- [P] 任务 = 不同文件、无未完成依赖
- [US] 标签把任务映射到 `spec.md` 的 user story，供追溯
- **四条 user story 全是 P1**：它们不是优先级排序，而是同一个类的四个行为侧面——**US1 是主链路，US2~US4 是三个各自会静默失效的角落**（时区错、重叠执行、锁不放死，三者都「看起来是成功的」）
- 测试方法名一律英文，课件中文原名进 `@DisplayName`
- **MUST NOT** 用 `Thread.sleep` / 真实时钟 / 真起调度线程造时序——`Trigger` 求落点、锁手工占用、`assertTimeoutPreemptively` 三条路已覆盖全部时序判据
- **MUST NOT** 为了让测试变绿而删断言、加 `@Disabled`、放宽阈值；认为测试错 → 停下报告
- 全程**不自动 commit / push / 运行 `package.sh`**
