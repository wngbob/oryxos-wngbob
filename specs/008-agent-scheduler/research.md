# Phase 0 研究：定时任务（第 25 节）

**Feature**: `specs/008-agent-scheduler` | **Branch**: `class-25` | **Date**: 2026-10-18

本文件记录 plan 里 D1~D12 十二条抉择的**完整推理**：决策是什么、为什么、否决了哪些备选、连带影响是什么。plan 里只放结论与一句理由，**细节在这里**。

**零 `NEEDS CLARIFICATION`**：五处软门禁冲突已在 `/speckit-specify` 与 `/speckit-clarify` 阶段报用户裁决（见 `spec.md` 的 Clarifications 与「冲突仲裁记录」），两条实测发现也在本节里给出原始证据。

---

## §0 两处实测（本节的硬事实来源）

课件的示例代码有**两处在本仓不成立**。两处都不是「记忆」或「推断」，是**在本地依赖上跑出来的**。这一节把原始证据留档，便于 reviewer 复核。

### §0.1 实测一：Spring 的 cron 触发器要求 **6 段**，本仓既有契约是 **5 段**

**怎么做**：`javap` 看 API 面 → 写一个一次性探针（`/tmp/ctprobe`，用完即删）直接构造触发器。

**探针输出**：

```text
new CronTrigger("0 9 * * *", ZoneId.of("UTC"))
  → java.lang.IllegalArgumentException: Cron expression must consist of 6 fields (found 5 in "0 9 * * *")
new CronTrigger("0 0 9 * * *", ZoneId.of("UTC"))
  → OK
```

**本仓的既有口径（一律 5 段）**：

| 载体 | 内容 |
| --- | --- |
| `oryxos-core/src/test/.../profile/ProfileLoaderTest.java:108,133` | 用例值与断言都是 `"0 9 * * *"` |
| `specs/001-llm-provider/data-model.md:23` | `[{cron, zone, message}]`，示例为 5 段 |
| `specs/000-runtime-kernel/contracts/agent-directory.md:32` | 同上 |

**结论（裁决 1）**：5 段为准、兼容 6 段；归一化落在 `AgentScheduler` 内部一处；**既有测试与文档一字不改**。

### §0.2 实测二：课件的时区断言**写不出来**

课件要求：「ArgumentCaptor 抓注册参数，断言 `CronTrigger` 带上了 cron **和时区**」。

**探针输出**（同一 cron 表达式，两个时区）：

```text
a = new CronTrigger("0 0 9 * * *", ZoneId.of("Asia/Shanghai"))
b = new CronTrigger("0 0 9 * * *", ZoneId.of("UTC"))
a.equals(b)  → true      // 时区被忽略
a.toString() → "0 0 9 * * *"   // 只打表达式
getTimeZone() → 不存在（javap 方法清单里没有）
```

`javap` 的 `CronTrigger` 方法清单：`getExpression()` / `nextExecution(TriggerContext)` / `equals` / `hashCode` / `toString` / 四个静态工厂——**没有任何时区 getter**。

**结论（裁决 6 / D5）**：时区断言改成**行为级**——抓 `Trigger` → `nextExecution(桩 Context)` → 断言落点在配置时区下是 09:00 且与错时区**不等**。

**替代手法的探针输出**（同一表达式，两个时区，同一桩上下文）：

```text
Asia/Shanghai → next = 2026-10-18T01:00:00Z   （在 Asia/Shanghai 下 localHour = 9）
UTC           → next = 2026-10-18T09:00:00Z   （在 UTC 下 localHour = 9）
两者不等 → true（相差 8 小时）
```

比课件原手法**更强**：原手法只证明「构造参数里传了个值」，这条证明的是**时区真的生效了**。

### §0.3 顺带核实的三件事（都不是「记忆」，是命令输出）

| # | 事实 | 怎么核实的 |
| --- | --- | --- |
| ① | `spring-context:6.2.19` 在 `oryxos-core` 的 **compile** classpath 上 | `mvn -o -pl oryxos-core dependency:tree` 第 11~12 行：`spring-boot:3.5.16:compile` → `spring-context:6.2.19:compile` |
| ② | `ThreadPoolTaskScheduler` **没有** `getScheduledTasks()` | `javap` 方法清单（有 `getPoolSize()` / `getActiveCount()` / `getScheduledThreadPoolExecutor()`，**没有** `getScheduledTasks()`） |
| ③ | 全仓 main 源码**零** `TaskScheduler` / `CronTrigger` / `@Scheduled` / `ReentrantLock` 用法 | `grep -rn "TaskScheduler\|CronTrigger\|@Scheduled\|ReentrantLock" --include=*.java oryxos-*/src` → **零命中** |

③ 的含义：本节是**本仓第一次**使用 Spring 的调度能力。这解释了为什么 §0.1 那条冲突此前从未暴露——`schedules` 字段第 16 节就交付了，但它**从来没有人消费过**，所以 5 段 / 6 段的口径分歧一直躺在那里没被触发。

---

## §1 D1 cron 段数归一化【已报批】

**决策**：`AgentScheduler` 内部一处归一化——5 段补 `0 ` 前缀成 6 段，6 段原样透传，其它段数抛 `IllegalArgumentException`。

**理由**：见 §0.1。四条按权重排序：① 本仓既有测试与两份契约文档一律 5 段，改 6 段要回修它们，**改动面最大且方向相反**；② `AGENT.md` 由业务方写，5 段 Unix cron 是通用约定；③ 段数无歧义，兼容不引入方言风险；④ 归一化只在一处、不污染装载层。

**否决的备选**：

| 备选 | 否决理由 |
| --- | --- |
| 把契约改成 6 段 | 要回修第 16 节已交付的 `ProfileLoaderTest` + `data-model.md` + `agent-directory.md`；且 6 段（Spring 方言）对业务方是**更陌生**的写法 |
| 只认 5 段、拒绝 6 段 | 知道 Spring 方言的人被挡在外面，而拒绝的理由对用户毫无意义（他写的表达式明明是对的） |
| 在 `ProfileLoader` 里归一化 | 那是第 16 节的已交付代码（FR-015 / 软门禁 ④）；且会让「Profile 里存的表达式」与「文件里写的表达式」不一致 |
| 用 `@Scheduled(cron = ...)` 静态注解 | FR-002 明令；注解做不到按配置动态生成 |

**连带**：归一化后的表达式**只交给调度器**，**不回写** Profile、不进日志以外的地方。

---

## §2 D2 新建 `ScheduleConfig`，不动 `Profile.ScheduleSpec`【已报批】

**决策**：`public record ScheduleConfig(String id, String cron, String zone, String message)`，落在 `oryxos-core` 的 `schedule` 包。

**理由**：① 课件「本节交付物」逐字点名 `ScheduleConfig`（id/cron/zone/message）；② `id` 是**锁键维度**，也是第 28 节 `schedule_id` 稳定主键的来源，需要一个对外载体；③ 给 `Profile.ScheduleSpec` 加 `id` 是改第 16 节交付的公共类型（软门禁 ②）。

**id 的派生规则**：`<profileName>#<序号>`，序号**从 1 起**、按 `schedules` 列表顺序。`weather-daily` 的第一条是 `weather-daily#1`。

**否决的备选**：

| 备选 | 否决理由 |
| --- | --- |
| 给 `Profile.ScheduleSpec` 加 `id` 字段 | 改前序节公共类型（软门禁 ② / FR-015） |
| 用 `cron + zone + message` 的哈希当 id | FR-013 明令 MUST NOT 依赖哈希；且**两条完全相同的规则会撞成同一个 id**——spec Edge Case 明确要求它们各自注册、各有各的锁键 |
| 用 `UUID.randomUUID()` | FR-013 明令 MUST NOT 依赖随机值（同一份配置两次装载必须是同一个 id） |
| 往 frontmatter 加 `id` / `key` 字段 | 造新的 Profile 字段（软门禁 ①）；且第 28 节的 `schedule_key` 归它自己设计，现在定等于替它做决定 |
| 复用 `Profile.ScheduleSpec` 当调度视图、id 现算 | 现算的 id 需要一个「谁负责算」的位置，最后还是要有这个类型；且 `ScheduleConfig` 是课件点名的交付物 |

---

## §3 D3 注册时机：只在 `serve` / `gateway` 命令体里显式调一行【已报批】

**决策**：`AgentScheduler` 不带 `@Component` / `@PostConstruct`；`CliAssemblyConfiguration` 声明 `@Bean`；`ServeCommand` / `GatewayCommand` 起完上下文后各加一行 `context.getBean(AgentScheduler.class).registerAll();`。

**理由**：① 课件用 `@Component` + `@PostConstruct`（起上下文即注册）→ `oryxos chat` 也会注册，撞 FR-012；② 技术方案 §8.6 与 FR-050 明说「随 `serve`/`gateway` 一起常驻调度」；③ 第 18 节契约 `cli-assembly.md` §3 明令全部 `@Bean` 的唯一落点是 `CliAssemblyConfiguration`，core 类型刻意不带 Spring 注解（可纯 `new` 单测）；④ 第 20 / 24 节课件同样给工具画了 `@Component`，本仓一贯改为装配处声明——**这是第三次同款改造**。

**否决的备选**：

| 备选 | 否决理由 |
| --- | --- |
| `@PostConstruct` 无条件注册 | `chat` 也注册，撞 FR-012 |
| 在装配处 `agentScheduler()` 方法体里调 `registerAll()` | 等价于「起上下文即注册」，同病 |
| 加一个 `--with-scheduler` 开关 | 新增对外参数 = 改命令契约（软门禁 ②）；且没人要求 |
| 在 `ServeCommand` 里 `new AgentScheduler(...)` 手搓 | 绕开容器 = 绕开装配点唯一落点那条纪律；且拿不到容器里的 `TaskScheduler` / `AgentService` / `SessionService` |

**连带**：两个命令的**命令名 / 参数 / 退出码三项一字未变**；`CliCommandTreeTest` 的 15 条叶子集合断言保持绿。

---

## §4 D4 调度线程池：固定 2 线程【已报批】

**决策**：`ThreadPoolTaskScheduler`，`setPoolSize(2)` + `setThreadNamePrefix("oryxos-scheduler-")`；池大小不是配置键。

**理由**：① 课件的坑二只覆盖「同一任务不重叠」，对「**不同**任务之间怎么并发」零表述，技术方案 §8.5 同样只提任务锁——**真空白**；② 池只有 1 线程时，一个长任务把**其它所有任务**的触发点整体推迟（排队而非跳过）——不可接受，而这恰好是「1」这个默认值最容易踩的坑；③ 核心阶段就几个 Demo Agent、任务都短，2 够用；④ 做成配置键 = 新增对外配置概念（软门禁 ①）且 YAGNI。

**为什么 `ThreadPoolTaskScheduler` 而不是 `ScheduledExecutorService`**：前者是 Spring 的 `TaskScheduler` 实现，天然接受 `Trigger`（`CronTrigger` 就是 `Trigger`）——`schedule(Runnable, Trigger)` 正是我们要的形态。裸 `ScheduledExecutorService` 只能按固定延迟 / 固定速率排，**cron 要自己算下一次时刻**，那是把 Spring 已经做好的事重做一遍（技术方案 §8.5 明说「别自己写调度器」）。

**否决的备选**：

| 备选 | 否决理由 |
| --- | --- |
| 池大小 1 | 长任务推迟所有其它任务（见理由 ②） |
| 池大小做成配置键 | 软门禁 ①；核心阶段没人要调 |
| `@EnableScheduling` + 默认 `TaskScheduler` | 那套要 `@Scheduled` 注解配合（FR-002 明令）；且默认池大小是 `availableProcessors` 的倍数，不确定 |
| 虚拟线程执行器（`setVirtualThreads(true)`） | 本仓 `application.yaml` 已开虚拟线程，但**调度器线程数**与**任务执行线程数**是两件事；这里要的是「同时最多几个任务在跑」的上界，虚拟线程反而给不出上界 |

**连带**：跨任务同时到点时最多 2 个并发，超出的**排队等待**（不是跳过、不是失败）——这是**跨任务**行为，与「同一任务重叠跳过」（FR-008）是两件事。SQLite 写串行化由既有 `HikariCP pool=1` + `busy_timeout` + WAL 兜底，**不为调度器改数据库配置**。

---

## §5 D5 时区断言的手法替换【实测发现】

见 §0.2。要点：原手法在本仓**写不出来**（`equals` 忽略时区、无 `getTimeZone()`），替代手法**更强**（行为级证据）。

**为什么固定 `Clock`**：`TriggerContext.getClock()` 是 default 方法，手写桩可以覆盖。用真时钟的话「下一次 09:00」依赖测试运行时刻，会变成时间敏感测试。固定后结果**逐位确定**：

```text
Clock.fixed(Instant.parse("2026-10-18T00:00:00Z"), ZoneOffset.UTC)
  Asia/Shanghai → 2026-10-18T01:00:00Z   localHour(Asia/Shanghai) = 9
  UTC           → 2026-10-18T09:00:00Z   localHour(UTC) = 9
  两者不等
```

---

## §6 D6 不新建异常类型

**决策**：cron 段数非法 / cron 缺失 / 时区非法一律抛 JDK 的 `IllegalArgumentException`，由 `registerAll` 接住成 ERROR 日志。

**理由**：① 新建异常类型 = 新增对外概念，**不在课件交付物清单里**（软门禁 ①）——本节对外概念恰好两个（`AgentScheduler` / `ScheduleConfig`）；② 该异常**不跨越任何接缝**：从 `AgentScheduler` 的私有归一化方法抛出、在同一个类的 `registerAll` 里接住，从不外露；③ `ZoneId.of` 抛的 `DateTimeException` 本身就是 `IllegalArgumentException` 的子类，包一层不丢信息。

**否决的备选**：

| 备选 | 否决理由 |
| --- | --- |
| 新建 `ScheduleConfigException` | 软门禁 ①（不在交付物清单里）；且它没有跨接缝的场合 |
| 注册期不抛、直接返回 null 让调用方判 | 「悄悄失败」的一种变体——FR-009 明确反对 |
| 让异常直接冒到 `registerAll` 之外 | 一条坏规则会**中断整个注册流程**，撞 FR-004（单条失败不中断） |

---

## §7 D7 锁表与解锁【FR-008 / FR-010 的落点】

**决策**：`ConcurrentHashMap<String, ReentrantLock>` + `computeIfAbsent` + `tryLock()`（无参）+ `finally unlock()`。

**逐条对应**：

| 代码形态 | 它守的是哪条 |
| --- | --- |
| `computeIfAbsent(id, ...)` | 两个调度线程同时首次取同一任务的锁时**只造一把**（`ConcurrentHashMap` 保证原子） |
| `tryLock()`（**无参**） | FR-008「直接跳过、不排队」——`lock()` 会**等待**，是反的 |
| 锁表按 **id** 维度 | FR-008「一个任务的锁 MUST NOT 阻塞另一个任务」 |
| `finally unlock()` | FR-010「无论成功失败都释放」——**只有「二进宫」断言抓得住漏放锁** |

**为什么锁表是 `ConcurrentHashMap` 而不是 `HashMap` + 外部同步**：注册发生在单线程的启动期，但**触发发生在 2 个调度线程上**（D4），取锁表这个动作本身必须线程安全。

**否决的备选**：

| 备选 | 否决理由 |
| --- | --- |
| `synchronized (this)` / 一把全局锁 | 撞 FR-008（一个任务的锁不得阻塞另一个） |
| `AtomicBoolean` 标志位 | 没有「谁持有」的概念；异常路径下忘记复位就永久卡死，而 `ReentrantLock` + `finally` 的释放是语言保证的 |
| `Semaphore(1)` | 语义上等价但更绕；`ReentrantLock` 的 `tryLock()` 名字直接表达了「试一试、不等待」 |
| 分布式锁 / ShedLock | FR-008 明令：核心阶段单实例，MUST NOT 引入 |
| 在 DB 里落一个「运行中」标志 | 落库 = FR-016 禁止的任务状态持久化；且引入了「崩溃后标志残留」这个新问题 |

---

## §8 D8 `TaskScheduler` 接口 + 包级私有的测试接缝

**决策**：构造器收 `TaskScheduler` 接口；`registerAll()` public；`runOnce(Profile, ScheduleConfig)` 与 `lockFor(String)` 包级私有。

**理由**：① 收**接口** → 单测传 Mockito 替身，**不起 Spring 上下文、不起真实线程池**（与 `AgentServiceTest` / `ReActLoopTest` 同款）；② `registerAll()` 必须 public——调用方在 `io.oryxlabs.oryxos.cli.command` 包，跨包；③ `runOnce` / `lockFor` 包级私有：US3 要手工占住锁、US4 要直接触发一次，同包测试可见即可，**不进对外概念清单**。

**为什么 `runOnce` 收两个参数**：`ScheduleConfig` 只有四项、**不含 Agent 名**（D2），而 `SessionService.getOrCreate` 的第三个参数是 Agent 名。把 profile 单独传进来，比让 `runOnce` 从 `weather-daily#1` 里切出 Agent 名干净——也避免了「Agent 名里带 `#` 怎么办」这种自造的解析歧义。

**否决的备选**：

| 备选 | 否决理由 |
| --- | --- |
| 构造器收具体类 `ThreadPoolTaskScheduler` | 单测就得起真线程池、真线程——慢且不确定 |
| `runOnce` public | 多一个对外 public 面；反射断言「public 面恰好是 X」就没法写了 |
| `runOnce` 收 `ScheduleConfig`、从 id 解析 Agent 名 | 见上（自造解析歧义） |
| 把 `ScheduleConfig` 扩成五项（加 profileName） | 课件逐字是四项（软门禁 ② 的边缘：改交付物的形状） |

---

## §9 D9 不做重复注册防护、不做优雅停机

**决策一**：`registerAll()` 不做「重复调用只注册一次」的防护。
**理由**：唯一调用点是 `serve` / `gateway` 的命令体，**每进程恰好一次**。加防护是为不存在的场景写代码。真有第二个调用点时，重复注册的后果是同一任务被注册两次——但**同一任务 id 共享同一把锁**（D7），故「不重叠」仍成立，只是触发次数翻倍；届时由**调用点**保证，而不是在这里静默吞掉第二次调用。

**决策二**：不设 `setWaitForTasksToCompleteOnShutdown(true)`，保持默认 `false`。
**理由**：spec Edge Case 明写「进程关闭时有任务正在跑：本节不做优雅停机等待（归扩展阶段）」。`serve` / `gateway` 用 `try-with-resources` + `keepAlive()` 阻塞，Ctrl+C 由 JVM 终止进程。

---

## §10 D10 定时任务里的 `ask_user` 分档：本节不做【已报批】

**决策**：不做；`CliAssemblyConfiguration` 里那句挂账 javadoc 挂带日期的修订注。

**理由**：① 第 25 节课件**零提及**；② 现有设计里 `UserInteraction` 是装配期一次性注入 `InteractionTools` 的**全局** Bean，**结构上无法按触发源分档**——要做得引入「触发源」概念，穿透 `AgentService` / `InteractionTools` / 装配三处（软门禁 ①）。

**实际后果（如实记，避免把边界说得比实际更严重）**：钟推下 `ask_user` 走的是 CLI 注入的 `ConsoleUserInteraction`；常驻进程无终端时它读到 EOF 返回空，而 `InteractionTools` 对空回答**明确报错**（第 20 节已交付的语义）——**不是静默阻塞**。故这是「报错信息不够贴切」的边界，不是「Agent 会卡死」的缺陷。

---

## §11 D11 文档连带修订清单

| # | 文件 | 修订 |
| --- | --- | --- |
| ① | `CliAssemblyConfiguration`（`userInteraction()` javadoc） | 加日期修订注：定时任务 `ask_user` 分档本节不做，改归扩展阶段；原挂账指向不成立 |
| ② | `docs/TechnicalSolution.md` §8.5 | 加日期修订注：「OryxOS 启动时扫描」= **常驻模式启动时**（`serve` / `gateway`），`chat` 不注册——与同文档 §8.6 的表述对齐 |
| ③ | `CLAUDE.md` | 「当前状态」「待办」第 25 节条目更新；补一条定时任务要点 |

**MUST NOT 触碰**：`website/`（不涉及对外表述）；`specs/001-llm-provider/` 与 `specs/000-runtime-kernel/` 里 5 段 cron 的历史文档（**那正是 D1 选 5 段的理由**）。

---

## §12 D12 FR-012 用「读源码」判【测试手法抉择】

见 plan 的 D12。要点：行为断言在本仓**做不到干净**——① `ThreadPoolTaskScheduler` 没有 `getScheduledTasks()`（`javap` 实测）；② `profileRegistry()` 从**进程当前目录**读 Agent，测试喂不进数据；③ 换 `TaskScheduler` 替身需要 `@ConditionalOnMissingBean`，而本节**刻意不加**（YAGNI）——为一条断言改设计不值。

读源码的判定与 `ToolOnboardingCostTest` / `LogStreamSeparationTest` 同款，**git 状态无关**。

**代价如实记**：读源码判不出「间接调用」。本节的调用是**直接的一行**。

---

## §13 课件示例代码 ↔ 本节实现的逐行对照

课件给的是**示意实现**。下表逐处标出差异与原因，便于 reviewer 对号：

| # | 课件写法 | 本节写法 | 为什么 |
| --- | --- | --- | --- |
| ① | `@Component` + `@PostConstruct` 注册 | 装配处 `@Bean`；`serve` / `gateway` 命令体显式调 `registerAll()` | D3 / FR-012：`chat` MUST NOT 注册 |
| ② | `new CronTrigger(sc.getCron(), sc.getZoneId())` | 先归一化（5→6 段）再构造；时区恒为显式 `ZoneId` | D1 / FR-003 / FR-005：Spring 要求 6 段；本仓契约是 5 段 |
| ③ | `sc.getZoneId()`（无缺省处理） | `zone` 空 → `ZoneId.systemDefault()`；非法 → 抛异常被捕获 | FR-005 / FR-004：缺省合法、非法单条跳过 |
| ④ | `sessionManager.getOrCreate("scheduler", "scheduler", profile.getName())` | `sessionService.getOrCreate(...)` | 本仓第 18 节交付的接缝名是 `SessionService`（`SessionManager` 不存在），**签名逐字一致** |
| ⑤ | `taskLocks.computeIfAbsent(sc.getId(), id -> new ReentrantLock())` | 同款 | 逐字一致 |
| ⑥ | `runOnce(sc)`（单参） | `runOnce(profile, config)`（双参） | D8：`ScheduleConfig` 不含 Agent 名 |
| ⑦ | 注册无 try/catch | 每条规则各自 try/catch，失败记 ERROR 并**指名 Agent 与任务** | FR-004：单条失败不中断，其它规则与其它 Agent 不受影响 |
| ⑧ | 执行无 catch | `catch (Exception)` 落 ERROR + `finally unlock()` | FR-009 / FR-010 |
| ⑨ | 线程池未提及 | `ThreadPoolTaskScheduler` 固定 2 线程 | D4 / FR-019 |

**MUST NOT 照抄的只有 ① 与 ②**——那两处在本仓会**当场出错**（`chat` 误注册 / `IllegalArgumentException`）。其余是等价改写或补齐 spec 要求的边界。

---

## §14 未决项

**无。** 全部抉择已定，五处软门禁已报批，两处实测已留证。
