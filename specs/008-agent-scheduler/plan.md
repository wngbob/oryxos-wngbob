# Implementation Plan: 定时任务 —— 给 Agent 补上第三种触发源

**Branch**: `class-25` | **Date**: 2026-10-18 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/008-agent-scheduler/spec.md`

**Note**: 本文件是 `/speckit-plan` 的输出。技术口径以本文件为准，**冲突时以 `spec.md` 的「冲突仲裁记录」为准**（9 条，其中 5 条是 2026-10-18 用户已报批的裁决）。

---

## Summary

第 24 节把「工具治理」那道墙的最后一档填上之后，Agent 已经能**被喂话、会想、会动手、会往外推消息、记得住事、干活有边界**。本节补的是基础能力里**最后一个缺口**：**Agent 能不能不用人喂话，自己到点干活。**

一句话：**两个新类（`AgentScheduler` + `ScheduleConfig`）+ 装配处两个 `@Bean` + `serve` / `gateway` 各一行注册调用 + 一个测试类**。

三件事值得单独说：

1. **核心引擎一行不改**（FR-015 / SC-006）。`AgentService.process(session, message)` 是三种触发源的唯一入口，本节只是**又造了一个调用方**。`AgentService` / `ReActLoop` / `ToolExecutor` / `ProfileLoader` / `SessionService` / `Profile.ScheduleSpec` 的 `git diff` 为空，是这条的机器可判定证据。这是章程原则 IX「三触发源复用同一条链路」的**第三次兑现**（前两次：第 18 节的 CLI、第 26 节的 Web 尚未落地）。
2. **时区与 cron 段数是本节唯一有真实分歧的地方**（裁决 1 + 实测发现）。课件的 `new CronTrigger(sc.getCron(), sc.getZoneId())` 把配置值**原样透传**，但本地实测（`spring-context:6.2.19`）该触发器要求 **6 段（含秒）**，而本仓既有测试与两份契约文档**一律是 5 段 Unix cron**——照抄课件会在本仓**当场抛 `IllegalArgumentException`**。裁决 1 定：**5 段为准、兼容 6 段**，归一化只发生在 `AgentScheduler` 内部一处，**既有测试与文档一字不改**。另一处：课件要求断言「触发器带上了 cron **和时区**」，实测该触发器的 `equals` **不比较时区**、**没有 `getTimeZone()`**，原断言**写不出来**——替代手法见 D5，比原手法**更强**。
3. **`registerAll()` 的调用点是本节唯一触碰前序节的地方**（裁决 3）。课件用 `@Component` + `@PostConstruct`（起上下文即注册），那样 `oryxos chat` 也会注册定时任务；技术方案 §8.6 与 FR-050 明说「定时任务随 `serve`/`gateway` 一起常驻调度」。落地为**在 `serve` / `gateway` 的命令体里显式调一行**，两个命令的**命令名 / 参数 / 退出码三项一字未变**。

---

## Technical Context

**Language/Version**: JDK 21 + Spring Boot 3.x + Spring AI Alibaba（动手前先跑 `mvn dependency:tree` 确认锁定 BOM 里目标依赖存在）、SQLite + Spring Data JPA。凭证走环境变量占位，不落明文。SQLite 用手工建表脚本，不依赖 `hibernate.ddl-auto=update`。

> 本节**不新增任何第三方依赖**。`org.springframework:spring-context:6.2.19` 已在 `oryxos-core` 的 **compile** classpath 上——`mvn -o -pl oryxos-core dependency:tree` 实测第 11~12 行：
>
> ```text
> [INFO] |  +- org.springframework.boot:spring-boot:jar:3.5.16:compile
> [INFO] |  |  \- org.springframework:spring-context:jar:6.2.19:compile
> ```
>
> 因此 `mvn dependency:tree` 的核对对象是「**确认没有新坐标**」+「确认 `spring-context` 的 scope 是 compile」，而不是「确认某坐标存在」。

**Primary Dependencies**（全部已在本仓，两个模块的 `pom.xml` 现状即覆盖本节需要）：

- `org.springframework:spring-context`（经 `spring-boot-starter` 传递，**compile**）—— `TaskScheduler` / `ThreadPoolTaskScheduler` / `CronTrigger` / `Trigger` / `TriggerContext` / `ZoneId`
- `org.springframework.boot:spring-boot-starter-test`（test scope）—— JUnit 5 / AssertJ / Mockito（`TaskScheduler` 与 `AgentService` 的替身靠它，与 `AgentServiceTest` / `ReActLoopTest` 同款）
- **不引入**：任何调度框架（Quartz / ShedLock / db-scheduler）、任何分布式协调库、`@EnableScheduling` + `@Scheduled` 那套静态注解（FR-002 明令）

**Storage**: **本节不碰任何存储**。`scheduled_tasks` / `task_executions` 两张表**本节 MUST NOT 创建**（FR-016，归第 28 节；`schema.sql` 现状确认还没有它们）。`llm_calls` / `tool_invocations` 的写入路径本节**一行不改**（FR-011：失败经由 `AgentService` 内部既有路径落库）。会话落库走既有的 `sessions` 表与 `SessionService` 接缝，**不新增表、不新增列**。

**Testing**:

- `AgentSchedulerTest`（`oryxos-core`，纯单测，**不起 Spring 上下文**）—— 课件 harness 的四条关键回归点（注册参数 / 时区落点 / 锁被占跳过 / 二进宫证明锁释放）+ cron 归一化与段数非法 + 单条失败隔离 + id 确定性 + 会话三元组。`TaskScheduler` 用 Mockito 替身 + `ArgumentCaptor` 抓注册参数；`AgentService` / `SessionService` 用 Mockito 替身；`TriggerContext` 用手写桩（固定 `Clock`），**全程无 `Thread.sleep`、无真实时钟依赖**。
- `SchedulerAssemblyTest`（`oryxos-cli`，起**真**上下文，复用 `CliTestContext`）—— 装配面两条：① 容器里 `AgentScheduler` Bean 存在、`TaskScheduler` Bean 的池大小**恰好是 2**（FR-019 钉死）；② 源码级 FR-012 断言（`serve` / `gateway` 各含 `registerAll()` 调用，`chat` 与装配处方法体**不含**）——读源码的判定手法与 `ToolOnboardingCostTest` / `LogStreamSeparationTest` 同款，**git 状态无关**。
- 单测默认跑，**无 `@Tag("integration")` 项**（本节无真外部依赖；真到点触发、改 cron 免编译、端到端预演是人工项）
- 实现完成的定义是 `mvn clean verify` 全绿（含 Spotless / P3C / Checkstyle / SpotBugs+FindSecBugs / OWASP）

**Target Platform**: Linux 服务器（开发机为 WSL2）；单 fat JAR（`oryxos-boot/target/oryxos.jar`）

**Project Type**: Maven 多模块库 + CLI（核心阶段 9 模块，**本节不新增模块**）

**Performance Goals**:

- 注册是**启动时一次性**的线性扫描：Profile 数 × 每条 Profile 的 `schedules` 条数，量级是「人能维护的规模」（个位数 × 个位数）。**不引入索引、不引入缓存**
- 触发路径的开销只有「查一次锁表 + 取或建一次会话 + 一次 `AgentService.process`」——**没有任何属于本节的重活**；真正的耗时在 ReAct 循环里，那与本节的调度开销不在一个量级
- 锁表是 `ConcurrentHashMap` 的 `computeIfAbsent`，**运行期只增不减**（任务 id 集合在注册后就固定了，条目数与 `schedules` 总条数同阶），故**不需要清理策略**
- **MUST NOT 加进程内任务状态缓存**：本节不落任何任务状态（FR-016），缓存只会制造「看起来在跑」的假象

**Constraints**:

- **`AgentService` / `ReActLoop` / `ToolExecutor` / `ProfileLoader` / `SessionService` / `Profile.ScheduleSpec` 六个文件 `git diff` 为空**（FR-015 / SC-006）
- **`ProfileLoader` 的 `schedules` 解析一字不改**：归一化 MUST 只发生在 `AgentScheduler` 内部一处，MUST NOT 回写 Profile（FR-003）
- **MUST NOT 新增 CLI 命令**（FR-017）：命令树是 15 条，`CliCommandTreeTest.EXPECTED_LEAVES` 钉死
- **MUST NOT 创建 `scheduled_tasks` / `task_executions` 表、MUST NOT 落任何任务运行态**（FR-016）
- **MUST NOT 为钟推新增审计代码 / 新开记账路径**（FR-011 / 章程原则 VI）
- **MUST NOT 引入分布式锁 / 选主 / 租约**（FR-008）：核心阶段单实例
- **MUST NOT 用 `@Component` / `@PostConstruct` / `@Scheduled` / `@EnableScheduling`**：装配点唯一落点是 `CliAssemblyConfiguration`（第 18 节契约 `contracts/cli-assembly.md` §3），注册时机由命令体决定（裁决 3 / FR-002 / FR-012）
- **MUST NOT 把池大小做成配置键**（FR-019 / 软门禁 ①）
- **MUST NOT 在 `AgentScheduler` 里拼会话标识**：`channel` / `user` / `profile` 三元组交给 `SessionService.getOrCreate`，标识拼接只在实现内部一处（FR-007 / H4 不变量 ④）
- **避开 P3C/ASM 解析不了的 Java 18+ 语法形态（如增强 switch 的 `default ->` 写法），静态检查是构建门禁。** 本节代码里没有 switch（路由只有「按任务 id 取锁」一条），但仍保持全仓 main 源码零箭头 switch 的保守度
- **MUST NOT 吞异常**：`runOnce` 的 catch MUST 落 ERROR 日志（FR-009 明确禁止「悄悄失败什么都不留」）

**Scale/Scope**:

- 新增 main 代码 **2 个文件**（`AgentScheduler` + `ScheduleConfig`），约 160 行
- 改动装配 **1 处**（`CliAssemblyConfiguration`：加两个 `@Bean`）
- 触碰前序节 main 代码 **2 个文件**（`ServeCommand` / `GatewayCommand` 各加一行，命令名 / 参数 / 退出码不变）
- 新增测试 **2 个类**（`AgentSchedulerTest` / `SchedulerAssemblyTest`）
- 改动配置 **0 处**（不新增配置键、不改 `application.yaml`）
- 建表 **0 张**、改 `schema.sql` **0 次**
- 文档连带修订 **3 处**（见 D11）

---

## Constitution Check

| 原则 | 本节如何满足 | 判定 |
| --- | --- | --- |
| **I. JDK 21 + Spring Boot 3.x 单体、单二进制** | 不新增模块、不新增依赖、不新增发行工件；`spring-context` 已是 `oryxos-core` 的 compile 依赖（实测第 11~12 行） | ✅ |
| **II. 核心阶段只交付运行时内核** | 定时任务是核心阶段五大能力之外的**触发源补全**（技术方案 §8.5 明列）；多实例协调、任务状态持久化、运行时增删一律推到扩展阶段 / 第 28 节 | ✅ |
| **III. Agent 引擎自实现，Spring AI 只用一半** | 完全不碰 LLM 侧；`ReActLoop` / `ToolExecutor` / `ProviderService` 零改动。**MUST NOT 把调度交给 Spring AI**——本节用的是 Spring Framework 的调度能力，与 Spring AI 无关 | ✅ |
| **IV. Agent 目录不是 Tool；配置从 Profile 来** | 定时规则**只从** `AGENT.md` frontmatter 的 `schedules` 读（FR-001 / FR-003），MUST NOT 编译期写死；`schedules` 不进 `ToolRegistry` | ✅ |
| **V. Tool 体系统一抽象、单一模块** | 本节不新增 Tool；`oryxos-tool` 零改动 | ✅ |
| **VI. 审计数据 Day One 落库** | 钟推的模型调用与工具执行**走与人推完全相同**的落库路径，本节**新增审计代码 0 行**（FR-011 / SC-005） | ✅ |
| **VII. 接口先行，实现按档演进** | `AgentScheduler` 持 `TaskScheduler` **接口**而非具体类（D8）；将来换持久化调度档只需换装配处一行 | ✅ |
| **VIII. 数据私有、状态外置、存储从简** | 本节**不落任何状态**（FR-016），故无「状态外置」问题；无凭证、无明文密钥 | ✅ |
| **IX. 三触发源复用同一条链路** | **本节就是这条原则的兑现**：钟推与人推汇入同一个 `AgentService.process`，`ReActLoop` 不感知来源（FR-006）；核心引擎 `git diff` 为空（SC-006） | ✅ |
| **X. 每个 user story 有可演示 Demo** | 4 条 user story 各有独立可跑的验证：到点自己跑（US1）/ 时区按声明的跑（US2）/ 重叠跳过（US3）/ 挂了不死锁（US4）——前三条纯单测可判，第四条靠「二进宫」断言 | ✅ |

**初判：无违规项，无需在 Complexity Tracking 里辩解。**

---

## 需要说明的设计偏离

本节共 12 条偏离/抉择，**全部有 spec 裁决或机器可判据支撑**，逐条落档：

### D1 cron 段数归一化：5 段为准、兼容 6 段，落点唯一【已报批】

**抉择**：`AgentScheduler` 内部一处归一化——**5 段补 `0 ` 前缀成 6 段**（Spring 原生方言），**6 段原样透传**，其它段数抛异常并被 `registerAll` 捕获成一条**指名 Agent 与任务**的 ERROR 日志。

**理由**：① 课件写 `new CronTrigger(sc.getCron(), sc.getZoneId())` 把配置值原样透传，但本地实测（`spring-context:6.2.19`）该触发器**要求 6 段**，5 段当场抛 `IllegalArgumentException: Cron expression must consist of 6 fields (found 5 in "0 9 * * *")`；② 本仓既有测试与契约**一律 5 段**——`ProfileLoaderTest` 的用例值与断言都是 `"0 9 * * *"`，`specs/001-llm-provider/data-model.md`、`specs/000-runtime-kernel/contracts/agent-directory.md` 同为 `[{cron, zone, message}]`；③ `AGENT.md` 由业务方写，5 段 Unix cron 是通用约定、认知成本最低；④ 段数无歧义（5 与 6 一眼可分），兼容不引入方言风险。

**连带**：**既有测试与两份契约文档一字不改**（这是选 5 段而非改 6 段的主要理由）；归一化 MUST NOT 回写 Profile、MUST NOT 进 `ProfileLoader`（FR-003）。

**备选与否决**：① 把契约改成 6 段（否决：要回修第 16 节已交付的测试 + 两份契约文档，改动面最大且方向相反）；② 只认 5 段、拒绝 6 段（否决：知道 Spring 方言的人被挡在外面，且拒绝的理由对用户毫无意义）；③ 在 `ProfileLoader` 里归一化（否决：那是第 16 节的已交付代码，改它撞 FR-015 / 软门禁 ④）。

### D2 新建 `ScheduleConfig`，不动 `Profile.ScheduleSpec`【已报批】

**抉择**：新建 `ScheduleConfig(String id, String cron, String zone, String message)`（`record`，`oryxos-core` 的 `schedule` 包）；`Profile.ScheduleSpec(cron, zone, message)` **一字不改**。

**理由**：① 课件「本节交付物」逐字点名 `ScheduleConfig`；② `id` 是**调度器的锁键维度**，也是第 28 节 `schedule_id` 稳定主键的来源，需要一个对外载体；③ 给 `Profile.ScheduleSpec` 加 `id` 字段是改第 16 节交付的公共类型（软门禁 ②），代价更高。

**连带（id 的派生规则）**：`id = <profileName>#<序号>`，序号**从 1 起**、按 `schedules` 列表顺序（`weather-daily#1` 是第一条）。**MUST NOT 往 frontmatter 新增 `id` / `key` 字段**——那是造新的 Profile 字段（软门禁 ①），且第 28 节的 `schedule_key` 归它自己设计。确定性由测试钉死（同一份配置两次装载得到同一个 id）。

**备选与否决**：① 给 `ScheduleSpec` 加 `id`（否决：改前序节公共类型）；② 用 `cron + zone + message` 的哈希当 id（否决：FR-013 明令 MUST NOT 依赖哈希；且两条完全相同的规则会撞成同一个 id——spec Edge Case 明确要求它们**各自注册、各有各的锁键**）。

### D3 注册时机：只在 `serve` / `gateway` 的命令体里显式调一行【已报批】

**抉择**：`AgentScheduler` **不带** `@Component` / `@PostConstruct`，由 `CliAssemblyConfiguration` 声明成 `@Bean`；`ServeCommand.run()` 与 `GatewayCommand.run()` 在起完上下文后各加一行 `context.getBean(AgentScheduler.class).registerAll();`。

**理由**：① 课件用 `@Component` + `@PostConstruct`（起上下文即注册），那样 `oryxos chat` 也会注册定时任务——而 `chat` 是交互式短命进程，注册了也没有意义，且与「`chat` MUST NOT 注册」这条 FR-012 直接冲突；② 技术方案 §8.6 与 FR-050 明说「定时任务随 `serve`/`gateway` 一起常驻调度」；③ 第 18 节契约 `contracts/cli-assembly.md` §3 明令全部 `@Bean` 的唯一落点是 `CliAssemblyConfiguration`，core 类型刻意不带 Spring 注解（可纯 `new` 单测）；④ 第 20 / 24 节课件同样给工具画了 `@Component`，本仓一贯改为装配处声明——**这是第三次同款改造**，不是新规矩。

**连带**：`ServeCommand` / `GatewayCommand` 的**命令名 / 参数 / 退出码三项一字未变**（不属改公共接口）；`CliCommandTreeTest` 的 15 条叶子集合断言保持绿（FR-017）。

**备选与否决**：① `@PostConstruct` 无条件注册（否决：`chat` 也会注册，撞 FR-012）；② 在装配处 `agentScheduler()` 方法体里调 `registerAll()`（否决：那等于「起上下文即注册」，与 ① 同病——只是把注解换成方法体）；③ 加一个 `--with-scheduler` 开关（否决：新增对外参数 = 改命令契约，软门禁 ②，且没人要求）。

### D4 调度线程池：固定 2 线程，装配处 `@Bean`，不新增配置键【已报批】

**抉择**：`CliAssemblyConfiguration` 声明一个 `ThreadPoolTaskScheduler` Bean，`setPoolSize(2)` + `setThreadNamePrefix("oryxos-scheduler-")`；池大小**不是配置键**。

**理由**：① 课件的坑二只覆盖「**同一个**任务不重叠」，对「**不同**任务之间怎么并发」零表述，技术方案 §8.5 同样只提任务锁——这是一个**真空白**；② 池只有 1 个线程时，一个长任务会把**其它所有任务**的触发点整体推迟（排队而非跳过），这是不可接受的副作用，而它恰好是「1」这个默认值最容易踩的坑；③ 核心阶段就几个 Demo Agent、任务都短，2 个线程够用；④ 做成配置键是新增对外配置概念（软门禁 ①），且核心阶段没有任何人要求调这个值（YAGNI）。

**连带**：**跨任务**同时到点时最多 2 个并发执行，超出的在池里**排队等待**（不是跳过、不是失败）；这与「**同一任务**重叠跳过」（FR-008，由任务锁保证、与池大小无关）是**两件事**，MUST NOT 混为一谈。SQLite 的写串行化由既有的 `HikariCP pool=1` + `busy_timeout` + WAL 兜底，**不为调度器改数据库配置**。

### D5 时区断言的手法替换：抓触发器求落点，而不是比对触发器【实测发现，不改契约】

**抉择**：`AgentSchedulerTest` 的时区断言 = 抓 `Trigger` → 用**固定 `Clock`** 的桩 `TriggerContext` 求 `nextExecution` → 断言落点在**配置时区**下是 09:00，且与「同一表达式配错时区」得到的 `Instant` **不相等**。

**理由**：课件要求「ArgumentCaptor 抓注册参数，断言 `CronTrigger` 带上了 cron **和时区**」。实测（`javap` + 探针，`spring-context:6.2.19`）这个断言**写不出来**：`CronTrigger.equals` **不比较时区**（`Asia/Shanghai` 与 `UTC` 的两个同表达式实例 `equals` 返回 `true`），**没有 `getTimeZone()`**，`toString()` 只打表达式。时区确实传进去了，但抓不到。

**替代手法比原手法更强**：原手法只能证明「构造参数里传了个值」，替代手法证明的是**时区真的生效了**——同一个 cron 表达式在两个时区下算出**相差 8 小时**的两个落点，这是行为级证据。课件的意图（坑四：不让服务器时区替用户做主）完整保住。

**为什么固定 `Clock` 而不是用真时钟**：真时钟下「下一次 09:00」依赖测试运行时刻，断言会变成「在 9 点前后跑可能不稳」的时间敏感测试。`TriggerContext.getClock()` 是 default 方法，手写桩可以覆盖它——固定 `Clock` 后结果**逐位确定**。

### D6 不新建异常类型：段数非法 / 时区非法一律用 JDK 的 `IllegalArgumentException`

**抉择**：cron 段数非法、cron 缺失、时区非法三种情况都抛 `IllegalArgumentException`（消息里带 Agent 名与任务 id），由 `registerAll` 的 `try/catch` 捕获成一条 ERROR 日志。**不新建 `ScheduleConfigException` 之类的类型。**

**理由**：① 新建异常类型 = 新增一个对外概念，**不在课件「本节交付物」清单里**（软门禁 ①）——本节交付物只有 `AgentScheduler` 与 `ScheduleConfig` 两个类型；② 这个异常**不跨越任何接缝**：它从 `AgentScheduler` 的私有归一化方法抛出，在同一个类的 `registerAll` 里被接住，从不外露给调用方；③ JDK 的 `IllegalArgumentException` 语义上正好（参数非法），且 `DateTimeException`（`ZoneId.of` 抛的）本身就是 `IllegalArgumentException` 的子类，包一层不丢信息。

### D7 锁表：`ConcurrentHashMap` + `computeIfAbsent` + `tryLock()` 不等待 + `finally` 解锁

**抉择**：

```java
ReentrantLock lock = taskLocks.computeIfAbsent(config.id(), id -> new ReentrantLock());
if (!lock.tryLock()) {
  log.warn(...);   // 上一次还没跑完，本次触发直接跳过
  return;
}
try {
  ...
} finally {
  lock.unlock();
}
```

**理由**：① `tryLock()`（无参）**不等待、立即返回 false**——这正是 FR-008「直接跳过、不排队」的语义；用 `lock.lock()` 会**排队**，是反的；② `computeIfAbsent` 在 `ConcurrentHashMap` 上是原子操作，两个线程同时首次取同一个任务的锁不会各造一把；③ 锁表是 `ConcurrentHashMap` 而**不是** `HashMap` + 外部同步：注册发生在单线程的启动期，但**触发发生在 2 个调度线程上**（D4），取锁表这个动作本身必须线程安全；④ `finally` 是 FR-010 的落点——**这条只有「二进宫」断言抓得住**（异常之后再触发一次、断言能进来），只断言「不抛异常」是抓不住漏放锁的。

**备选与否决**：① `synchronized (this)` 或一把全局锁（否决：撞 FR-008——一个任务的锁 MUST NOT 阻塞另一个任务）；② `AtomicBoolean` 标志位（否决：没有「谁持有」的概念，异常路径下忘记复位就永久卡死，而 `ReentrantLock` + `finally` 的释放语义是语言保证的）；③ 分布式锁 / ShedLock（否决：FR-008 明令，核心阶段单实例）。

### D8 `AgentScheduler` 持 `TaskScheduler` 接口；`runOnce` / `lockFor` 是包级私有测试接缝

**抉择**：

```java
public AgentScheduler(TaskScheduler taskScheduler,
                      ProfileRegistry profileRegistry,
                      AgentService agentService,
                      SessionService sessionService)

public void registerAll()                       // 唯一 public 方法（serve/gateway 跨包调用）
void runOnce(Profile profile, ScheduleConfig config)   // 包级私有：同包测试直接调
ReentrantLock lockFor(String taskId)                   // 包级私有：同包测试占锁用
```

**理由**：① 构造器收**接口** `TaskScheduler` 而不是 `ThreadPoolTaskScheduler`——单测传 Mockito 替身即可，**不起 Spring 上下文、不起真实线程池**，与 `AgentServiceTest` / `ReActLoopTest` 的替身风格同款；② `registerAll()` 必须 `public`——调用方在 `io.oryxlabs.oryxos.cli.command` 包，跨包；③ `runOnce` 与 `lockFor` 包级私有：它们是**测试接缝**（US3 要手工占住锁、US4 要直接触发一次），放在同包测试里可见即可，**不进对外概念清单**——这也让「本节的 public 面恰好是 `AgentScheduler` + `registerAll` + 构造器」成为一条可被反射断言的硬事实。

**为什么 `runOnce` 收 `(Profile, ScheduleConfig)` 两个参数**：`ScheduleConfig` 按 D2 只有四项、**不含 Agent 名**，而 `SessionService.getOrCreate` 的第三个参数是 Agent 名——把 profile 单独传进来，比让 `runOnce` 去解析 id 字符串（`weather-daily#1` 里切出 Agent 名）干净得多，也避免了「Agent 名里带 `#` 怎么办」这种自造的解析歧义。

### D9 不做重复注册防护、不做优雅停机【YAGNI，如实留痕】

**抉择一**：`registerAll()` **不**做「重复调用只注册一次」的防护。

**理由**：它的唯一调用点是 `serve` / `gateway` 的命令体，**每个进程恰好一次**。加防护是为一个不存在的调用场景写代码。若将来真有第二个调用点，重复注册的后果是同一任务被注册两次——但**同一任务 id 共享同一把锁**（D7 的 `computeIfAbsent`），故「不重叠」这条仍然成立，只是触发次数翻倍；届时由**调用点**保证，而不是在这里静默吞掉第二次调用。

**抉择二**：**不做优雅停机等待**（不设 `setWaitForTasksToCompleteOnShutdown(true)`，保持默认 `false`）。

**理由**：spec Edge Case 明写「进程关闭时有任务正在跑：本节不做优雅停机等待（归扩展阶段）；Spring 关闭上下文时随调度器一起结束」。`serve` / `gateway` 用 `try-with-resources` + `keepAlive()` 阻塞，Ctrl+C 由 JVM 终止进程，不需要额外处理。

### D10 定时任务里的 `ask_user` 分档：本节不做【已报批】

**抉择**：不做。`CliAssemblyConfiguration` 的 `userInteraction()` javadoc 里那句「挂 `UnsupportedUserInteraction` 的场合是『压根没有人』（定时任务、无状态调用），那两处的装配归第 25 / 26 节」——**该挂账指向不成立**（本节不做这件事），须挂**带日期的修订注**说明改归扩展阶段评估。

**理由**：① 第 25 节课件**零提及**这件事，不在本节交付物清单里；② 现有设计里 `UserInteraction` 是装配期一次性注入 `InteractionTools` 的**全局** Bean，**结构上无法按触发源分档**——要做得引入「触发源」概念（ThreadLocal / 组合档），那会穿透 `AgentService` / `InteractionTools` / 装配三处，是软门禁 ①，不是本节该夹带的跨节改动；③ **实际后果有限**：钟推下 `ask_user` 走的是 CLI 注入的 `ConsoleUserInteraction`，无终端时它会读到 EOF 并返回空——`InteractionTools` 对空回答**明确报错**（第 20 节已交付的语义），不是静默阻塞。**如实记为未覆盖边界**，进验收报告。

### D11 文档连带修订清单

| # | 文件 | 修订内容 | 依据 |
| --- | --- | --- | --- |
| ① | `oryxos-cli/.../CliAssemblyConfiguration.java`（`userInteraction()` 的 javadoc） | **加**带日期的修订注：「2026-10-18 第 25 节裁决：定时任务的 `ask_user` 分档本节不做，改归扩展阶段评估；原挂账指向不成立」 | D10 / 裁决 4 连带 |
| ② | `docs/TechnicalSolution.md` §8.5 | 「**OryxOS 启动时**扫描所有 Profile 的 `schedules` 字段逐个注册」→ **加**带日期的修订注：注册时机是**常驻模式启动时**（`serve` / `gateway`），交互式 `chat` 不注册；§8.6 那句「随 `serve`/`gateway` 一起常驻调度」是同一件事的另一种说法 | 裁决 3 连带（§8.5 与 §8.6 表述不一致） |
| ③ | `CLAUDE.md` | 「当前状态」「待办」两处的第 25 节条目更新（已交付 / 下一步第 26 节）；「Tool 体系」附近补一条定时任务要点；「命令行工具」条确认 15 条不变 | 节级收尾 |

**MUST NOT 触碰**：`website/`（本次不涉及对外定位 / 能力 / 路线图表述——定时任务是内核能力，不是对外承诺面）；`specs/001-llm-provider/` 与 `specs/000-runtime-kernel/` 里 5 段 cron 的历史文档（**那正是 D1 选 5 段的理由，改它们等于篡改历史留痕**）。

### D12 FR-012 用「读源码」判，而不是「行为」判【测试手法抉择，如实说明为什么】

**抉择**：`SchedulerAssemblyTest` 里那条「`chat` MUST NOT 注册定时任务」的断言，形式是**读源文件**——断言 `ServeCommand.java` / `GatewayCommand.java` **含** `registerAll`，`ChatCommand.java` 与 `CliAssemblyConfiguration.agentScheduler()` 的方法体**不含** `registerAll`。

**为什么不做成行为断言**：行为断言的形态会是「起一个上下文 → 断言调度器上零注册」。它在本仓**做不到干净**，三条路都被堵死：

1. **观察不到**：`ThreadPoolTaskScheduler` **没有** `getScheduledTasks()`（`javap` 实测，6.2.19 的方法清单里没有它）。退而求其次用 `getScheduledThreadPoolExecutor().getQueue().size()` 也行，但那是靠队列实现细节间接推断，脆。
2. **喂不进数据**：`profileRegistry()` 从 `workspace.agentsDir()` 读 Agent，而 `Workspace.current()` 是**进程当前目录**——测试里造不出「一个有 `schedules` 的工作区」而不 `chdir`。
3. **换不掉 Bean**：要让「零注册」可观测，得把 `TaskScheduler` 换成记录型替身，而 `taskScheduler()` **不带** `@ConditionalOnMissingBean`（见「已知接缝」表），测试里再声明一个同类型 Bean 会撞 `BeanDefinitionOverrideException`。为一条断言加一个本节本来不需要的覆盖接缝，是**为测试改设计**。

**读源码的判定与既有惯例同款**：`ToolOnboardingCostTest`（断言三个横切关注点源文件里不含新工具名）与 `LogStreamSeparationTest`（读 `logback-spring.xml`）都是这个手法，且都是**git 状态无关**的判定——它们判的是「代码里有没有这行」，而不是「工作区改没改」。

**代价如实记**：读源码判不出「间接调用」（比如 `ServeCommand` 调了一个中间方法、中间方法再调 `registerAll`）。本节的调用是**直接的一行**，且注释会写明理由，故这条代价可接受；真要防间接调用，上面三条路里没有一条比它更好。

---

## Project Structure

### Documentation (this feature)

```text
specs/008-agent-scheduler/
├── spec.md              # 需求规约（已定稿，9 条冲突仲裁记录）
├── plan.md              # 本文件（/speckit-plan 输出）
├── research.md          # Phase 0：D1~D12 的决策、理由与否决的备选
├── data-model.md        # Phase 1：两个新类型的字段/派生规则/不变式 + 触发时序
├── quickstart.md        # Phase 1：可跑的验证向导（含真链路人工项）
├── contracts/
│   └── agent-scheduler.md   # Phase 1：本节冻结的字面量、语义边界表、装配改动、MUST NOT
├── checklists/
│   └── requirements.md  # /speckit-specify 产出（12/12）
└── tasks.md             # /speckit-tasks 产出（下一步）
```

### Source Code (repository root)

```text
oryxos-core/src/main/java/io/oryxlabs/oryxos/core/schedule/
├── AgentScheduler.java      # 【新增】注册 + 触发 + 任务锁 + cron 归一化
└── ScheduleConfig.java      # 【新增】record ScheduleConfig(id, cron, zone, message)

oryxos-core/src/test/java/io/oryxlabs/oryxos/core/schedule/
└── AgentSchedulerTest.java  # 【新增】harness：课件四条 + 归一化 + 隔离 + id 确定性

oryxos-cli/src/main/java/io/oryxlabs/oryxos/cli/
├── CliAssemblyConfiguration.java    # 【改】+ taskScheduler() + agentScheduler() 两个 @Bean
└── command/
    ├── ServeCommand.java            # 【改】+ 一行 context.getBean(AgentScheduler.class).registerAll()
    └── GatewayCommand.java          # 【改】+ 同一行

oryxos-cli/src/test/java/io/oryxlabs/oryxos/cli/
└── SchedulerAssemblyTest.java       # 【新增】装配面 + 池大小 2 + 源码级 FR-012 断言
```

**不被触碰的**（本节最该被 review 的「零改动面」）：

```text
oryxos-core/.../engine/AgentService.java        # FR-015：三种触发源的唯一入口，只被调用、不被修改
oryxos-core/.../engine/ReActLoop.java           # FR-015 / FR-006：MUST NOT 感知触发源
oryxos-core/.../engine/ToolExecutor.java        # FR-011：失败审计路径复用
oryxos-core/.../profile/ProfileLoader.java      # FR-003：归一化 MUST NOT 进装载层
oryxos-core/.../profile/Profile.java            # FR-015：ScheduleSpec 一字不改
oryxos-core/.../session/SessionService.java     # FR-007 / H4 不变量 ④：标识拼接仍只在实现内
oryxos-storage/src/main/resources/db/schema.sql # FR-016：本节不建任何表
oryxos-cli/.../command/ChatCommand.java         # FR-012：chat MUST NOT 注册
oryxos-cli/.../CliCommandTreeTest.java          # FR-017：15 条叶子集合断言保持绿
oryxos-boot/src/main/resources/application.yaml # 不新增配置键（FR-019）
```

---

## Complexity Tracking

**无违规项。** 本节没有需要向章程辩解的复杂度：不新增模块、不新增依赖、不新增表、不新增配置键、不新增 CLI 命令、不改任何前序节公共接口。**本节新增的对外概念恰好两个**（`AgentScheduler`、`ScheduleConfig`），逐字来自课件「本节交付物」清单。

唯一值得说明的复杂度是**「两把尺子」**：任务锁（按任务 id、保证同一任务不重叠）与线程池（固定 2、决定跨任务并发度）。这两件事在 spec 里被刻意分成 FR-008 与 FR-019 两条，**测试也分两条断言**——合并成一条会掩盖「池大小改变不影响重叠语义」这个事实。

---

## 已知接缝

| 接缝 | 现状 | 何时收 |
| --- | --- | --- |
| `TaskScheduler` 的覆盖接缝 | **不做**：本节只有一个实现、没有让位链，故 `taskScheduler()` **不带** `@ConditionalOnMissingBean`（YAGNI）。部署方若自建 `TaskScheduler` Bean，会撞 Bean 名冲突而不是静默接管 | 真出现第二档（例如持久化调度 / 分布式档）时加一行注解即可，接口与调用方不改 |
| 任务状态与执行历史 | 不落库（FR-016） | 第 28 节：`scheduled_tasks` / `task_executions` + `ScheduledTaskStore` + v2 端点 |
| 运行时增删改 cron | 不做，改配置重启生效 | 扩展阶段（与「免重启」闭环一起） |
| 多实例任务归属（选主 / 租约 / 分布式锁） | 不做，核心阶段单实例 | 扩展阶段（与「状态外置、走向分布式」一起） |
| 失败自动重试 / 告警 | 不做，只记日志 + 走既有审计 | 扩展阶段 |
| 优雅停机等待 | 不做（`waitForTasksToCompleteOnShutdown` 保持默认 `false`） | 扩展阶段 |
| 定时任务里的 `ask_user` 分档 | 不做（D10），记为未覆盖边界 | 扩展阶段评估；需要「触发源」概念 |
| 配置热加载 | 不做（改 `schedules` 重启生效） | 核心阶段不做配置热加载（与第 24 节白名单同口径） |
| cron 秒级精度 | 不做（5 段归一化后秒位恒为 0；6 段可显式声明秒） | 无计划；需要秒级时写 6 段 |

---

## Constitution Check（Phase 1 复核）

设计产出（`data-model.md` / `contracts/agent-scheduler.md` / `quickstart.md`）写完后按原则逐条复核：

| 原则 | Phase 1 复核结论 |
| --- | --- |
| I | ✅ 无新增坐标、无新增工件（`contracts/agent-scheduler.md` §4 装配改动只有两个 `@Bean` 方法 + 两个命令各一行） |
| II | ✅ `contracts/agent-scheduler.md` §6 把任务状态持久化 / 分布式协调 / 运行时增删 / 重试告警 / 优雅停机逐条列为 MUST NOT |
| III | ✅ 不涉及 LLM 侧；`data-model.md` §5 的时序图里没有任何模型或工具的内部细节 |
| IV | ✅ `contracts/agent-scheduler.md` §3 明确定时规则**只从** `AGENT.md` 的 `schedules` 读，MUST NOT 编译期写死；`schedules` 不进 `ToolRegistry` |
| V | ✅ `oryxos-tool` 零改动 |
| VI | ✅ `data-model.md` §4 把「钟推的审计记录与人推同形」写成不变式，且本节新增审计代码 **0 行** |
| VII | ✅ `AgentScheduler` 持 `TaskScheduler` **接口**（`contracts/agent-scheduler.md` §1.1 冻结构造器签名） |
| VIII | ✅ 无存储改动、无凭证、无明文密钥；`application.yaml` 一个字节都不动 |
| IX | ✅ **本节就是这条原则的兑现**：`data-model.md` §5 的时序图末端汇入 `AgentService.process`，与 CLI / Web 同一条 |
| X | ✅ `quickstart.md` 四节分别对应 US1~US4，每节都有可复制执行的命令 |

**Phase 1 复核：无新增违规项。**

---

## Phase 0 产出

[research.md](./research.md) —— D1~D12 逐条的「决策 / 理由 / 否决的备选」，含课件示例代码与本节实现的逐行对照表（D1 / D3 / D5 / D7），以及两处实测的原始证据（cron 段数、时区不可抓）。**零 `NEEDS CLARIFICATION`**（五处软门禁冲突已在 `/speckit-specify` 与 `/speckit-clarify` 阶段报用户裁决并落进 spec 的 Clarifications）。

## Phase 1 产出

- [data-model.md](./data-model.md) —— 两个新类型的字段与派生规则、三条不变式、触发时序、边界判定表
- [contracts/agent-scheduler.md](./contracts/agent-scheduler.md) —— 冻结字面量 / 语义边界表 / 装配改动 / MUST NOT 清单 / 文档连带修订
- [quickstart.md](./quickstart.md) —— 四节验证向导（US1~US4）+ 真链路人工项 + 排障对照表

## 下一步

`/speckit-tasks` → 任务清单与课件「本节交付物」逐项比对 → **停下等用户确认**（流程唯一的固定停点）。
