# Feature Specification: 定时任务 —— 给 Agent 补上第三种触发源

**Feature Directory**: `specs/008-agent-scheduler`

**Git Branch**: `class-25`

**Created**: 2026-10-18

**Status**: Draft

**Input**: User description: "第25节需求：定时任务模块——给 Agent 补上第三种触发源，让它不用人推、到点自己说话"（课件：`docs/class/第25节：定时任务模块 原理解析、实现与代码讲解.md`；章节依据 `docs/TechnicalSolution.md` §8.5 / §8.6；需求依据 `specs/000-runtime-kernel/spec.md` FR-050 ~ FR-052）

## Clarifications

### Session 2026-10-18

本节开工前（H0 依赖核对阶段）有四处冲突/抉择触发了软门禁，已全部报用户裁决。四条裁决是后续 plan / tasks / 实现的硬约束。

- Q: `AGENT.md` 里 `schedules` 的 cron 表达式用几段？**这是一处硬冲突**——课件正文写 `new CronTrigger(sc.getCron(), sc.getZoneId())`，把配置值原样透传给 Spring；但本地实测（spring-context 6.2.19）`CronTrigger` 要求 **6 段（含秒）**，5 段当场抛 `IllegalArgumentException: Cron expression must consist of 6 fields (found 5 in "0 9 * * *")`。而本仓既有契约与测试**一律是 5 段 Unix cron**：`ProfileLoaderTest` 的用例值与断言都是 `"0 9 * * *"`，`specs/001-llm-provider/data-model.md`、`specs/000-runtime-kernel/contracts/agent-directory.md` 同为 `[{cron, zone, message}]`。 → A: **5 段为准，兼容 6 段**。判据：① `AGENT.md` 由业务方写，5 段 Unix cron 是通用约定、认知成本最低；② 改成 6 段要回修第 16 节已交付的测试与两份契约文档，改动面最大且方向相反；③ 段数无歧义（5 与 6 一眼可分），兼容不引入方言风险。**连带**：`AgentScheduler` 内部一处归一化（5 段补 `0 ` 前缀成 6 段），其它段数报错并**指名 Agent 与任务**；既有测试与文档**一字不改**。

- Q: `ScheduleConfig` 怎么落地？课件「本节交付物」点名 `ScheduleConfig`（id/cron/zone/message），但第 16 节已交付 `Profile.ScheduleSpec(cron, zone, message)`（**无 id**），两者形状相近。 → A: **新建 `ScheduleConfig`**。判据：① 课件交付物逐字点名它；② `id` 是调度器的锁键维度，也是第 28 节 `schedule_id` 稳定主键的来源，需要一个对外载体；③ 给 `Profile.ScheduleSpec` 加 `id` 字段是改第 16 节交付的公共类型（软门禁 ②），代价更高。**连带**：`id` 由 **Profile 名 + 配置内序号**确定性派生（`<profileName>#<index>`），MUST NOT 往 frontmatter 里新增 `id` / `key` 字段——那是造新的 Profile 字段（软门禁 ①），且第 28 节的 `schedule_key` 归它自己设计。

- Q: 定时任务什么时候注册？课件写 `@Component` + `@PostConstruct`（起 Spring 上下文即注册），那样 `oryxos chat` 也会注册定时任务；而技术方案 §8.6 与 FR-050 明说「定时任务随 `serve`/`gateway` 一起常驻调度」，且 `chat` 是交互式短命进程。 → A: **只在 `serve` / `gateway` 里显式注册**。**连带**：要动第 18 节交付的 `ServeCommand` / `GatewayCommand`，各加一行取 Bean 后调 `registerAll()`；两个命令的命令名 / 参数 / 退出码一字未变，不属改公共接口。

- Q: 定时任务里的 `ask_user` 怎么处理？`CliAssemblyConfiguration` 的 javadoc 写着「挂 `UnsupportedUserInteraction` 的场合是『压根没有人』（定时任务、无状态调用），那两处的装配归第 25 / 26 节」，但**第 25 节课件完全没提这件事**；且现有设计里 `UserInteraction` 是装配期一次性注入 `InteractionTools` 的**全局** Bean，**结构上无法按触发源分档**——要做得引入新概念（触发源 ThreadLocal / 组合档），属软门禁 ①。 → A: **本节不做，记为已知边界**。判据：① 课件零提及，不在本节交付物清单里；② 引入「触发源」概念会穿透 `AgentService` / `InteractionTools` / 装配三处，不是本节该夹带的跨节改动。**连带**：`CliAssemblyConfiguration` 那段 javadoc 的挂账指向**不成立**（第 25 节不做这件事），须挂带日期的修订注说明改归扩展阶段评估；验收报告里如实记为未覆盖边界。

- Q: 多个定时任务**同时到点**时怎么并发？课件的坑二只覆盖「**同一个**任务不重叠」，对「**不同**任务之间」零表述；技术方案 §8.5 也只说「每个定时任务用一把进程内的 `ReentrantLock` 防止同一任务重叠执行」。这是一个真空白，而它有两种后果完全不同的走法——调度器线程池只有 1 个线程时，一个长任务会把**其它所有任务**的触发点整体推迟（排队而非跳过）；池 ≥ 2 时不同任务互不阻塞，但会撞上本仓 SQLite 的 `HikariCP pool=1`。做成可配置键则是新增一个对外配置概念（软门禁 ①）。 → A: **固定小池，2 线程，不新增配置键**。判据：① 核心阶段就几个 Demo Agent、任务都短，2 个线程够用；② 单线程的「一个长任务推迟所有任务」是不可接受的副作用，而它恰好是「1」这个默认值最容易踩的坑；③ 核心阶段没有任何人要求调这个值，做成配置键是 YAGNI 且要报批（软门禁 ①）。**连带**：SQLite 的写串行化由既有的 `busy_timeout` + WAL 兜底，不为调度器改数据库配置。**连带**：超出的触发在池里排队等待（这是**跨任务**行为），MUST NOT 与「同一任务重叠跳过」混为一谈——后者由任务锁保证，与池大小无关。

### 另一处实测发现（不改契约，改的是测试的断言手法）

课件要求测试「ArgumentCaptor 抓注册参数，断言 `CronTrigger` 带上了 cron **和时区**」。实测（spring-context 6.2.19）这个断言**写不出来**：`CronTrigger.equals` **不比较时区**（`Asia/Shanghai` 与 `UTC` 的两个同表达式实例 `equals` 返回 `true`），**没有 `getTimeZone()`**，`toString()` 只打表达式。时区确实传进去了，但抓不到。

→ 替代断言（已验证可行）：抓 `Trigger` → 调 `nextExecution(桩 TriggerContext)` → 断言落点 `Instant` 在**配置时区**下是 09:00、且与「同一表达式配错时区」得到的 `Instant` **不等**。这条断言比原来那条**更强**——它证明的是时区**真的生效了**，而不只是「构造参数里传了个值」。课件的意图（坑四：不让服务器时区替用户做主）完整保住。

## 范围说明

Provider、ReAct、CLI、Notify、Tool、Memory、Sandbox 都已交付——Agent 能被喂话、会想、会动手、会往外推消息、记得住事、干活有安全边界。本节补的是基础能力的**最后一个缺口**：**Agent 能不能不用人喂话，自己到点干活。**

**关键定位：定时任务不是一种新能力，是第三种触发源。** CLI 是人推、Web 是人推（换了个入口）、定时任务是**钟推**——到点了系统自己拼一条消息，喂给跟 CLI/Web **完全一样的处理入口**。`ReActLoop` 怎么想、`ToolExecutor` 怎么执行、`ProviderService` 怎么调模型，**一个字都不用改**。这条链路的一致性本身就是本节的验收重点：**新增一个入口不动核心引擎一行代码**（章程原则 IX 的第三次兑现）。

**职责划得很窄，别让它膨胀成一个小型工作流引擎。** 这个模块只干一件事——**到点了，拼一条消息，交给 `AgentService`**。消息里具体说什么话，是 Profile / `SKILL.md` 的事；消息交上去之后怎么处理，是 `ReActLoop` 的事。这两件事都不归本节管。

**别自己写调度器。** Spring 自带 `TaskScheduler`，支持标准 cron 表达式、支持动态注册任务，够用了。这跟 Provider 那节「协议转换不自己造」是同一个原则的延伸——我们要写的只是薄薄一层，把「Profile 里配的定时规则」接到 Spring 的调度能力上。

**本节交付物范围**（课件「本节交付物」逐项）：

- **代码**：`AgentScheduler`（`registerAll` + `runOnce` + 按任务 id 的 `ReentrantLock` 表）、`ScheduleConfig`（id/cron/zone/message）
- **测试**：`AgentSchedulerTest`（见验收 harness）
- **配置**：Profile 新增 `schedules` 字段——**第 16 节已交付**（`Profile.ScheduleSpec` + `ProfileLoader` 解析），本节只消费、不改
- **约定**：会话身份固定 `("scheduler", "scheduler", profileName)`；失败只记日志不崩调度器
- **模块落位**：`AgentScheduler` / `ScheduleConfig` → `oryxos-core`（技术方案 §8.5）

**四个坑（课件「动手前先想清楚」逐项）**：① 配置从哪来——cron / 时区 / 消息必须在 Profile 里声明，不写死在 Java 里；② 重叠执行——进程内本地锁，**不需要分布式锁**；③ 失败隔离——跑挂了不带崩调度器、不悄悄失败、照样走审计；④ 时区——能显式指定，不让服务器时区替用户做主。

### 明确不做（边界）

课件「有几样先别做」逐项照搬，另加本节裁决带出的两项：

- 不做多实例下「这个定时任务该由哪个实例执行」的**分布式协调**（选主、分布式锁、租约）——核心阶段单实例，放扩展阶段跟「状态外置、走向分布式」一起解决
- 不做任务失败后的**自动重试**、失败几次**自动告警**——核心阶段做到「失败不崩、留痕可查」就够
- 不做任务**状态与执行历史的持久化 / 查看 / 立即执行 / 启用停用**——归第 28 节。**本节不建任何新表、不落任何任务状态**（`scheduled_tasks` / `task_executions` 两张表本节 MUST NOT 创建；当前 `schema.sql` 里确实还没有它们）
- 不做**运行时增删改 cron 定义**（免重启生效）——核心阶段改 cron / 新增任务要重启才生效；运行时增删接口属扩展阶段
- 不做定时任务里 `ask_user` 的**分档处理**（裁决 4；记为已知边界，归扩展阶段评估）
- 不做**一个小型工作流引擎**——模块只干「拼一条消息交给处理入口」这一件事
- 不做**配置热加载**：改 `schedules` 后重启生效（与第 24 节「白名单改后重启生效」同口径）
- 不做**面向用户的定时任务 CLI / 查询命令**：本节不新增任何 CLI 命令（命令树是 15 条，`CliCommandTreeTest` 钉死；第 28 节的 v2 管理端点才是任务的可视面）

## 冲突仲裁记录（本规约据此撰写，后续 plan / tasks 不得偏离）

| # | 事项 | 裁决 | 依据 |
| --- | --- | --- | --- |
| 1 | cron 段数契约 | **5 段为准，兼容 6 段**；其它段数报错并指名 Agent 与任务 | 裁决 1（2026-10-18 用户）；本地实测 Spring 要求 6 段，既有测试/契约是 5 段 |
| 2 | `ScheduleConfig` 与 `Profile.ScheduleSpec` 并存 | **新建 `ScheduleConfig(id/cron/zone/message)`**，`ScheduleSpec` 一字不改 | 裁决 2；课件交付物点名；`id` 需对外载体 |
| 3 | `ScheduleConfig.id` 的来源 | **Profile 名 + 配置内序号**确定性派生（`<profileName>#<index>`） | 裁决 2 连带；MUST NOT 新增 frontmatter 字段（软门禁 ①） |
| 4 | 注册时机 | **只在 `serve` / `gateway` 命令体内显式调 `registerAll()`** | 裁决 3；技术方案 §8.6 + FR-050 |
| 5 | `AgentScheduler` 的声明方式 | 装配处 `@Bean`（**不带** `@Component` / `@PostConstruct`） | 第 18 节契约 `contracts/cli-assembly.md` §3：全部 `@Bean` 唯一落点是 `CliAssemblyConfiguration`；core 类型刻意不带 Spring 注解（可纯 `new` 单测）。课件 3 的 `@Component` 是示意写法（第 20 / 24 节课件同样给工具画了 `@Component`，本仓一贯改为装配处声明） |
| 6 | 时区断言的实现手法 | 抓 `Trigger` → `nextExecution(桩 Context)` → 断言落点在配置时区下是 09:00 且与错时区不等 | 实测 `CronTrigger.equals` 忽略时区且无 `getTimeZone()`，课件原手法不可写；新手法更强 |
| 7 | 定时任务里的 `ask_user` | **本节不做**，记为已知边界 | 裁决 4；课件零提及；按触发源分档需引入新概念（软门禁 ①） |
| 8 | 核心引擎改动面 | `AgentService` / `ReActLoop` / `ToolExecutor` / `ProfileLoader` / `SessionService` / `Profile.ScheduleSpec` **本节零改动** | FR-051/052 的「同一链路」要求；`git diff` 为空是可判定证据 |
| 9 | 跨任务并发度 | **固定小池 2 线程**，不新增配置键 | 裁决 5（2026-10-18 用户）；课件与技术方案对此零表述，单线程的「长任务推迟所有任务」不可接受 |

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 配置里写一条定时规则，进程常驻后到点自己跑 (Priority: P1)

业务方在某个 Agent 的 `AGENT.md` frontmatter 里加一段：

```yaml
schedules:
  - cron: "0 9 * * *"
    zone: Asia/Shanghai
    message: 帮我总结昨天的 PR 评审进度
```

运维用 `oryxos serve`（或 `gateway`）把进程常驻起来。第二天 09:00，Agent 自己发起了一次完整对话——没有人问过它。用户回到会话历史里，能看见它自己跑过的这一轮。

**Why this priority**: 这是本节存在的理由。缺了它，「每日天气 / 每日科技日报」这类 Agent 根本不成立——那正是第 31 节要交付的两个验收 Demo。

**Independent Test**: 构造一个带 `schedules` 的 Profile 与一个假的 `TaskScheduler`（捕获注册调用），断言：注册发生了、注册的 cron 表达式就是配置里那条、消息就是配置里那条；再直接调 `runOnce` 断言处理入口被调了一次、且拿到的会话三元组是 `("scheduler", "scheduler", <profileName>)`。

**Acceptance Scenarios**:

1. **Given** 一个声明了 `schedules: [{cron: "0 9 * * *", zone: Asia/Shanghai, message: M}]` 的 Profile，**When** 启动时调 `registerAll()`，**Then** 该 Agent 的这条规则被注册进调度器一次，且注册用的 cron 表达式与消息内容与配置逐字一致
2. **Given** 一个声明了三条 `schedules` 的 Profile，**When** `registerAll()`，**Then** 三条各注册一次（三条的锁键互不相同）
3. **Given** 没有任何 Agent 声明 `schedules`，**When** `registerAll()`，**Then** 一个任务都不注册、不抛异常、进程正常起（**这是默认配置下的预期状态**，不是缺陷）
4. **Given** 一个注册好的任务，**When** 到点触发 `runOnce`，**Then** 处理入口被调用一次，入参是「按 `("scheduler", "scheduler", <profileName>)` 取或建的会话」与配置里的消息
5. **Given** 同一个任务被连续触发两次，**When** 两次都走完，**Then** 两次拿到的是**同一个会话**（同一 Agent 的历次钟推复用一份历史）
6. **Given** 一个 Profile 的 `schedules` 里有一条 cron 是 5 段、另一条是 6 段，**When** `registerAll()`，**Then** 两条都注册成功（5 段被归一化、6 段原样透传）
7. **Given** 某条规则的 cron 既不是 5 段也不是 6 段（例如 `"0 9 * *"`），**When** `registerAll()`，**Then** 该条**不注册**、记一条**指名 Agent 与任务**的错误日志，且**同一 Profile 的其它合法规则照常注册**、其它 Agent 完全不受影响
8. **Given** 一个只声明了 `message`、没写 cron 的规则，**When** `registerAll()`，**Then** 同 7——不注册 + 指名报错，不影响其它规则

---

### User Story 2 - 时区按声明的跑，不让系统时区替用户做主 (Priority: P1)

运维在一台 UTC 的服务器上跑 OryxOS。业务方声明的是 `cron: "0 9 * * *"` + `zone: Asia/Shanghai`——他要的是**北京时间早上 9 点**，不是 UTC 早上 9 点。系统必须按声明的时区跑，而不是按服务器时区。

**Why this priority**: 与 US1 同为 P1。时区错了，任务本身**看起来是成功的**——它确实跑了、也留了审计记录，只是跑在了错误的时刻。这类错误没有报错、没有异常，只有「用户以为的早上 9 点」和「实际跑的时刻」对不上，是最难被发现的一类。

**Independent Test**: 用同一个 cron 表达式 + 两个不同时区各构造一个触发器，对同一个桩触发上下文求下一次执行时刻；断言声明 `Asia/Shanghai` 的那次落点在 `Asia/Shanghai` 下是 09:00，且**不等于**声明 `UTC` 的那次（两者相差 8 小时）。

**Acceptance Scenarios**:

1. **Given** 配置 `cron: "0 9 * * *"` + `zone: Asia/Shanghai`，**When** 注册并求下一次触发时刻，**Then** 该时刻在 `Asia/Shanghai` 下是 09:00
2. **Given** 同一 cron 表达式分别配 `Asia/Shanghai` 与 `UTC`，**When** 各求下一次触发时刻，**Then** 两个时刻**不相等**（时区确实生效了，不是被忽略）
3. **Given** 配置了 `zone: Asia/Shanghai`，**When** 运行环境的默认时区是 UTC，**Then** 结果仍按 `Asia/Shanghai`——系统时区 MUST NOT 覆盖显式声明的时区
4. **Given** 一条规则**没有**声明 zone，**When** 注册，**Then** 按服务器系统时区跑（`zone` 是可选项，MUST NOT 因为缺省而拒绝注册）；无论声明与否，交给调度器的触发器**总是**带一个显式时区，行为确定、可断言

---

### User Story 3 - 上一次还没跑完，这次直接跳过 (Priority: P1)

某个 Agent 的定时规则是「每分钟」，但它一次 ReAct 循环要跑三分钟（模型慢、工具多）。第二分钟、第三分钟的触发点到了——系统**直接跳过**这两次，**不排队、不并发跑两份**。第四分钟上一次跑完了，再触发才正常执行。

**Why this priority**: 与 US1 同为 P1。不跳过的后果是**同一个会话被两个线程同时改写**——对话历史互相覆盖、工具重复执行（比如重复推一条群消息），而且事后从审计表里只能看到「跑了两次」，看不出「这两次本该是一次」。

**Independent Test**: 手工占住某个任务 id 的锁（模拟上一次还没跑完），然后调 `runOnce`——断言处理入口**一次都没被调用**（不是「被调用了但被拒」，而是压根没发生）；放锁后再调一次，断言这次被调用了。

**Acceptance Scenarios**:

1. **Given** 某个任务 id 的锁已被占住（上一次还没跑完），**When** 本次触发，**Then** 处理入口一次都没被调用、不抛异常、不排队等待
2. **Given** 上一次已经跑完（锁已释放），**When** 本次触发，**Then** 正常执行
3. **Given** 两个**不同**任务 id 各有一把锁，**When** 其中一个被占住，**Then** 另一个照常执行——锁是**按任务 id 维度**的，不是一把全局锁
4. **Given** 核心阶段是单实例部署，**When** 检查实现，**Then** 防重叠用的是**进程内**锁，MUST NOT 出现分布式锁 / 选主 / 租约

---

### User Story 4 - 一次跑挂了，调度器不死、锁不放死 (Priority: P1)

某次定时执行中途失败——模型报错、工具报错、网络超时。调度器必须照常活着、其它定时任务照常触发、**这个任务的下一次触发也要能正常进来**。同时这次失败**不能悄悄没声音**：留日志，且失败的那次调用走与人推完全一样的审计路径。

**Why this priority**: 与 US1 同为 P1。这里有一个只有「二进宫」断言才能抓住的 bug——`finally` 里漏了放锁。漏了之后：不抛异常（看起来正常）、日志也照打，但**这个任务从此永久卡死**，再也不触发。只断言「不抛异常」是抓不住它的。

**Independent Test**: 让处理入口抛异常 → 断言 `runOnce` **不外抛**（调度器不死）→ **再调一次 `runOnce`** → 断言处理入口这次被调用了（锁真的放了，没有永久卡死）。

**Acceptance Scenarios**:

1. **Given** 处理入口抛 `RuntimeException`，**When** 触发一次，**Then** 异常**不外抛**，调度器不崩
2. **Given** 上一次触发时处理入口抛了异常，**When** **再次**触发，**Then** 处理入口**被调用了**——锁在 `finally` 里真的被释放，任务没有永久卡死
3. **Given** 上一次触发时处理入口抛了异常，**When** 同一进程里**另一个**任务触发，**Then** 它照常执行（失败被隔离在单个任务内）
4. **Given** 定时触发的处理入口内部失败，**When** 检查审计，**Then** 失败经由处理入口内部既有的审计路径落库（`llm_calls` / `tool_invocations`），**本节 MUST NOT 新增任何审计代码**

---

### Edge Cases

- **cron 段数非法**：`"0 9 * *"`（4 段）、`"0 9 * * * * *"`（7 段）→ 该条不注册 + 指名报错，**不影响**同 Profile 的其它规则与其它 Agent
- **cron 缺失或为空**：frontmatter 里只写了 `message` → 同「段数非法」处理（不注册 + 指名报错）
- **cron 段数对但内容非法**：`"0 0 99 * * *"` → 归一化后交给调度器时抛异常，同样**不得**拖垮其它规则的注册
- **zone 缺失**：按服务器系统时区跑；MUST NOT 因为缺省而拒绝注册
- **zone 写了非法值**：`"Mars/Olympus"` → 该条不注册 + 指名报错（不得拖垮其它规则）
- **`schedules` 为空 / 未声明**：一个任务都不注册，进程正常起（默认配置下的预期状态）
- **同一 Agent 的 `schedules` 里有两条完全相同的规则**：两条**各自**注册、各有各的锁键（不做去重——去重会让「我明明配了两条」变成一条，是静默行为）
- **Agent 在配置里声明了 schedules 但 Agent 目录随后被删**：本节不做运行时增删（要重启才生效），故不在本节范围
- **触发时处理入口报「Profile 查不到」**：该异常属于处理入口内部的既有语义，按「失败隔离」处理——不外抛、记日志
- **两个不同任务同时到点**：最多 2 个并发执行，超出的在池里**排队等待**（不是跳过、不是失败）——这是跨任务行为，与「同一任务重叠跳过」无关
- **一个长任务正在跑、另一个任务的触发点到**：MUST NOT 被这个长任务阻塞在锁上（锁是按任务 id 的）；它至多受池容量影响而排队
- **池被占满时又来了触发**：排队等待，MUST NOT 判失败、MUST NOT 静默丢弃
- **进程关闭时有任务正在跑**：本节不做优雅停机等待（归扩展阶段）；Spring 关闭上下文时随调度器一起结束

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 MUST 在常驻模式（`serve` / `gateway`）启动时，扫描**所有** Profile 的 `schedules` 声明并逐条注册进调度器；注册内容 MUST 全部来自配置，MUST NOT 用编译期写死的 `@Scheduled` 注解常量（改一次触发时间 MUST NOT 需要改代码、重新编译）
- **FR-002**: 注册 MUST 用**动态注册**（把「cron 表达式 + 时区」交给调度器），MUST NOT 使用静态注解式的固定周期注册
- **FR-003**: cron 表达式契约 MUST 以 **5 段 Unix cron** 为准，同时**兼容 6 段**（原样透传）；其它段数 MUST 被拒。归一化 MUST 只发生在调度器内部一处，MUST NOT 回写 Profile、MUST NOT 改动第 16 节的装载逻辑
- **FR-004**: 单条规则注册失败（cron 段数非法 / 内容非法 / 时区非法 / cron 缺失）MUST NOT 中断注册流程：该条跳过、记一条**指名 Agent 名与任务**的错误日志，**同一 Profile 的其它合法规则照常注册**，其它 Agent 完全不受影响（与 `ProfileLoader`「单条失败只跳过、不中断」同口径）
- **FR-005**: cron 表达式 MUST 支持显式声明时区；声明了就必须按声明的跑。时区未声明时按服务器系统时区。**无论声明与否，交给调度器的触发器 MUST 总是带一个显式时区**——让行为确定、可断言，MUST NOT 依赖「不传时区时框架自己怎么处理」这种隐含行为
- **FR-006**: 定时触发 MUST 汇入与人推**完全相同**的处理入口（`AgentService.process`），`ReActLoop` MUST NOT 感知本次消息来自钟推；MUST NOT 为钟推造第二条处理链路
- **FR-007**: 同一个 Agent 的历次定时触发 MUST 复用**同一个会话**，会话身份固定为 `channel = "scheduler"`、`user = "scheduler"`、`profile = <Agent 名>`；会话 MUST 经既有会话接缝按三元组取或建，MUST NOT 由调度器自行拼接会话标识（H4 不变量 ④）、MUST NOT 为钟推新设任何会话概念或新表
- **FR-008**: 同一个定时任务上一次还没跑完时，本次触发 MUST **直接跳过**——MUST NOT 排队、MUST NOT 并发跑两份。防重叠 MUST 用**进程内**锁、按**任务 id** 维度（一个任务的锁 MUST NOT 阻塞另一个任务）；MUST NOT 引入分布式锁、选主或租约。这条只约束**同一任务**，与「不同任务之间怎么并发」是两件事（后者见 FR-019）
- **FR-009**: 一次定时执行内部抛出的**任何**异常 MUST NOT 外抛给调度器（调度器 MUST NOT 因为单次任务失败而崩溃，MUST NOT 影响其它任务的触发）；失败 MUST 记错误日志——MUST NOT 悄悄失败什么都不留
- **FR-010**: 锁 MUST 在 `finally` 中释放，**无论成功失败**。这条 MUST 由「二进宫」式断言钉死（异常之后再触发一次、断言能进来），MUST NOT 只用「不抛异常」自证
- **FR-011**: 定时触发的模型调用与工具执行 MUST 走与人推**完全相同**的审计落库路径（`llm_calls` / `tool_invocations`），MUST NOT 为钟推单开一套记账逻辑、MUST NOT 新增审计代码、MUST NOT 建新表
- **FR-012**: 定时规则 MUST **只在**常驻模式（`serve` / `gateway`）注册；交互式的 `chat` MUST NOT 注册定时任务
- **FR-013**: 调度视图（`ScheduleConfig`）MUST 含四项：任务 id、cron 表达式、时区、到点发给 Agent 的消息。任务 id MUST 由 Profile 名 + 配置内序号**确定性派生**，MUST NOT 依赖随机值或哈希（同一份配置两次装载得到同一个 id）；MUST NOT 往 `AGENT.md` frontmatter 新增 `id` / `key` 字段
- **FR-014**: 本模块的职责 MUST 限定为「到点拼一条消息、交给处理入口」。MUST NOT 解析或改写消息内容、MUST NOT 感知循环内部、MUST NOT 做失败重试或告警
- **FR-015**: 前序节交付的公共类型 MUST NOT 被修改：`AgentService`、`ReActLoop`、`ToolExecutor`、`ProfileLoader`、`SessionService`、`Profile.ScheduleSpec` 全部**零改动**（`git diff` 为空是可判定证据）
- **FR-016**: 定时任务的**状态与执行历史** MUST NOT 在本节落库：MUST NOT 创建 `scheduled_tasks` / `task_executions` 表、MUST NOT 写任何任务运行态字段（归第 28 节）
- **FR-017**: 本节 MUST NOT 新增任何 CLI 命令（命令树保持 15 条，`CliCommandTreeTest` 的叶子集合断言 MUST 保持绿）
- **FR-018**: 本节 MUST NOT 新增第三方依赖——调度能力 MUST 复用 Spring 自带的调度器与 cron 触发器（不自己写调度器）
- **FR-019**: 调度线程池 MUST 是**固定小池（2 个线程）**，池大小 MUST NOT 做成配置键（软门禁 ①）。**跨任务**的语义：同时到点的不同任务最多 2 个并发执行，超出的在池里**排队等待**——这是**跨任务**行为，MUST NOT 与「同一任务重叠跳过」（FR-008，由任务锁保证、与池大小无关）混为一谈。MUST NOT 因为池满而把任务判成失败或静默丢弃

### Key Entities *(include if feature involves data)*

- **定时规则（`Profile.ScheduleSpec`）**：**第 16 节已交付**，三项——cron 表达式、时区、到点发给 Agent 的消息。它是**定义源**（写在 `AGENT.md` frontmatter 里），本节只读不改
- **调度视图（`ScheduleConfig`）**：本节新增。把一条 `ScheduleSpec` 加上一个**确定性派生的任务 id**，成为调度器眼里的一个任务。四项：任务 id、cron 表达式、时区、消息。id 是锁的维度，也是第 28 节 `schedule_id` 稳定主键的来源
- **定时任务（调度器眼里的一个注册项）**：一个 `ScheduleConfig` + 一个被注册进调度器的触发行为。它到点时做的事是「取或建钟推会话 → 交给处理入口」，**仅此而已**
- **钟推会话**：不是新类型——就是既有的会话，只是三元组固定为 `("scheduler", "scheduler", <Agent 名>)`。同一 Agent 的历次钟推复用同一份对话历史
- **任务锁**：不是持久化对象——进程内按任务 id 维度的一把锁，只解决「同一进程内不重叠」。**不是**分布式锁

## Success Criteria *(mandatory)*

### SC-001 ~ SC-008

- **SC-001**: 业务方改一条 cron 表达式后，**不改代码、不重新编译**，重启即按新时间跑
- **SC-002**: 重叠触发 **100% 被跳过**——被占锁期间的触发，处理入口被调用次数为 **0**（不是「调用了但被拒」）
- **SC-003**: 处理入口抛异常时，`runOnce` 外抛次数为 **0**；且异常之后**下一次触发必然能进来**（锁 100% 释放，无永久卡死）
- **SC-004**: 声明了时区的规则，其触发落点在**声明时区**下的时刻与声明一致，且与「同一表达式配错时区」得到的落点**不相等**
- **SC-005**: 定时触发产生的审计记录与人推**完全同形**——同一张表、同一套字段、同一条写入路径；本节新增的审计代码行数为 **0**
- **SC-006**: 新增一个触发源**不动核心引擎一行代码**——`AgentService` / `ReActLoop` / `ToolExecutor` / `ProfileLoader` / `SessionService` 的 `git diff` 为**空**
- **SC-007**: 既有测试全绿（前序节共 488 条），**唯一允许变更的是**：`ServeCommand` / `GatewayCommand` 各加一行注册调用（命令名 / 参数 / 退出码三项不变）
- **SC-008**: 默认配置下（没有任何 Agent 声明 `schedules`）进程正常起、零任务注册、零报错——这是**预期状态**，MUST NOT 被当成缺陷
- **SC-009**: 一个任务的长执行**不会**在锁上阻塞另一个任务（锁按任务 id 维度）；同时到点的不同任务按池容量（2）并发，超出的排队等待而非失败

## Assumptions

- **单实例部署**：核心阶段一个进程，防重叠只需进程内锁。多实例下的任务归属是扩展阶段问题（技术方案 §14、`docs/IndustryResearch.md`）
- **`schedules` 定义源唯一**：只在 `AGENT.md` frontmatter 里；运行态与执行历史归第 28 节，本节不落任何状态
- **生效时机是启动时**：改 `schedules` 后重启生效，核心阶段不做热加载、不做运行时增删
- **会话身份是约定而非新概念**：`("scheduler", "scheduler", <Agent 名>)` 复用既有三元组机制，会话标识的拼接仍只在会话接缝内部一处发生
- **cron 是业务方写的**：按 5 段 Unix cron 的通用约定设计；6 段兼容是为了不把「知道 Spring 方言」的人挡在外面，不是为了鼓励两种写法
- **前序节交付物已就位**：`Profile.ScheduleSpec` + `ProfileLoader` 的 `schedules` 解析（第 16 节）、`AgentService.process` 作为三种触发源的唯一入口（第 17 节）、`SessionService.getOrCreate` 三元组取或建（第 18 节）、`CliAssemblyConfiguration` 作为全部 `@Bean` 的唯一落点（第 18 节）、`serve` / `gateway` 两个常驻命令（第 18 节）
- **外部依赖**：无新增（Spring 自带的调度器与 cron 触发器已在依赖树里，`spring-context` 6.2.19）
