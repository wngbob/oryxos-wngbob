# Phase 1 数据模型：定时任务（第 25 节）

**Feature**: `specs/008-agent-scheduler` | **Branch**: `class-25` | **Date**: 2026-10-18

本文件定义本节**新增的两个类型**的字段、派生规则、不变式与运行期行为。**本节不新增任何持久化结构**（§7 逐条说明为什么）。

---

## §1 新增类型

### §1.1 `ScheduleConfig`（`oryxos-core`，`io.oryxlabs.oryxos.core.schedule`）

调度视图——调度器眼里的**一个任务**。

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `id` | `String` | 是 | 任务 id，**确定性派生**（§2），锁键维度 |
| `cron` | `String` | 是 | cron 表达式，**Profile 里写的原文**（未归一化） |
| `zone` | `String` | 否 | 时区名（如 `Asia/Shanghai`）；**可为 null / 空白**，表示按服务器系统时区 |
| `message` | `String` | 是 | 到点要发给 Agent 的消息内容 |

**形态**：`public record ScheduleConfig(String id, String cron, String zone, String message) {}`。**纯记录类**——不带任何注解、不做校验、不持有行为。校验与归一化全在 `AgentScheduler` 里（§3），因为它们需要「Agent 名」这个 `ScheduleConfig` 没有的信息来报错。

**为什么 `cron` 存的是原文而不是归一化后的**：归一化是**交给 Spring 之前的最后一刻**才发生的事（§3.1）。存原文的好处是：报错消息里能带上**用户写的那个表达式**（`"0 9 * *"`），而不是被我们改造过的形态；「Profile 里的定义」与「调度器收到的表达式」之间的差异只在一个方法里，可审计。

### §1.2 `AgentScheduler`（`oryxos-core`，`io.oryxlabs.oryxos.core.schedule`）

**构造器（冻结）**：

```java
public AgentScheduler(TaskScheduler taskScheduler,
                      ProfileRegistry profileRegistry,
                      AgentService agentService,
                      SessionService sessionService)
```

| 依赖 | 类型 | 用途 |
| --- | --- | --- |
| `taskScheduler` | `TaskScheduler`（**接口**，D8） | 动态注册任务；单测传替身 |
| `profileRegistry` | `ProfileRegistry` | `registerAll()` 扫 `all()` 取每个 Profile 的 `schedules()` |
| `agentService` | `AgentService` | 到点调 `process(session, message)`——**三种触发源的唯一入口** |
| `sessionService` | `SessionService` | 按三元组取或建钟推会话（FR-007） |

**内部状态（都不是对外面）**：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `taskLocks` | `Map<String, ReentrantLock>`（`ConcurrentHashMap`） | 按任务 id 的进程内锁；注册后条目数固定（D4/§4 不变式 3） |

**对外方法面（恰好三项，可被反射断言）**：

| 成员 | 可见性 | 说明 |
| --- | --- | --- |
| 构造器（四参） | `public` | 装配处与单测用 |
| `registerAll()` | `public` | 唯一 public 方法；`serve` / `gateway` 命令体跨包调用（D3） |
| `runOnce(Profile, ScheduleConfig)` | **包级私有** | 一次触发的全部动作；同包测试直接调（D8） |
| `lockFor(String taskId)` | **包级私有** | 取/建任务锁；同包测试占锁用（US3） |

其余成员（归一化、时区解析、单条注册）一律 `private`。

---

## §2 定义源 → 调度视图的派生

```text
Profile.name()                    ──┐
Profile.schedules() : List<ScheduleSpec>   │  逐条 + 序号
   ScheduleSpec.cron / .zone / .message    │
                                    ──┴──►  ScheduleConfig(id, cron, zone, message)
```

**派生规则（冻结，FR-013）**：

```text
id = <profile.name()> + "#" + <序号>
序号 = 该条在 profile.schedules() 列表中的位置，从 1 起
```

| Profile 名 | `schedules` 条数 | 派生出的 id |
| --- | --- | --- |
| `weather-daily` | 1 | `weather-daily#1` |
| `tech-digest` | 3 | `tech-digest#1` / `tech-digest#2` / `tech-digest#3` |

**三条硬约束**：

1. **确定性**：同一份配置两次装载 → 同一个 id 集合。**MUST NOT** 用随机值、哈希、时间戳（FR-013）。
2. **不去重**：两条完全相同的规则派生**两个不同的 id**（`#1` / `#2`），各自注册、各有各的锁。去重会让「我明明配了两条」变成一条，是**静默行为**（spec Edge Case）。
3. **不回写**：派生只发生在 `AgentScheduler` 内部，**MUST NOT** 往 Profile / frontmatter / 文件里写任何 `id`（D2）。

---

## §3 归一化与校验

### §3.1 cron 段数（FR-003，裁决 1）

| 输入 | 处理 | 结果 |
| --- | --- | --- |
| `"0 9 * * *"`（5 段） | 补 `0 ` 前缀 | `"0 0 9 * * *"` 交给调度器 |
| `"0 0 9 * * *"`（6 段） | 原样透传 | `"0 0 9 * * *"` |
| `"0 9 * *"`（4 段）/ `"0 9 * * * * *"`（7 段） | 抛 `IllegalArgumentException` | 该条**不注册** |
| `null` / 空白 | 抛 `IllegalArgumentException` | 该条**不注册** |

**归一化 MUST 只在这一处发生**（FR-003）：MUST NOT 回写 Profile、MUST NOT 进 `ProfileLoader`、MUST NOT 在装配处预处理。

**为什么补的是 `0 ` 而不是别的**：Spring 的 6 段格式是 `秒 分 时 日 月 周`，5 段 Unix 格式是 `分 时 日 月 周`——**前补秒位**是唯一保语义的映射。`"0 9 * * *"`（每天 9:00）→ `"0 0 9 * * *"`（每天 09:00:00）正确；若误补在后（`"0 9 * * * 0"`）会变成「周日 9:00」，是**静默的错误时刻**。

**段数用 `trim()` 后按 `\\s+` 切分**：多个空格、前后空白、制表符都算一个分隔符——业务方手写 YAML 时这些形态很常见。

### §3.2 时区（FR-005）

| 输入 | 处理 |
| --- | --- |
| `null` / 空白 | `ZoneId.systemDefault()`（服务器系统时区） |
| 合法时区名（`Asia/Shanghai` / `UTC` / `GMT+8`） | `ZoneId.of(trim())` |
| 非法（`Mars/Olympus`） | `ZoneId.of` 抛 `DateTimeException` → 包成 `IllegalArgumentException` → 该条**不注册** |

**无论声明与否，交给调度器的 `CronTrigger` MUST 总是带一个显式 `ZoneId`**（FR-005）——即使缺省走系统时区，也是**显式取出来传进去**的，MUST NOT 依赖「不传时区时框架自己怎么处理」这种隐含行为。这让行为确定、可断言（US2 场景 4）。

### §3.3 单条失败隔离（FR-004）

```text
for each Profile:
  for each ScheduleSpec (序号 i):
      try:
          归一化 cron + 解析时区
          注册（schedule(Runnable, CronTrigger))
      catch (RuntimeException e):
          log.error("定时任务注册失败（Agent <名>，任务 <id>）：<原因>")
          continue          ← 关键：不中断
```

**判定表**：

| 场景 | 该条 | 同 Profile 的其它规则 | 其它 Agent |
| --- | --- | --- | --- |
| cron 段数非法 | 不注册 + ERROR | **照常注册** | 不受影响 |
| cron 缺失 | 不注册 + ERROR | 照常注册 | 不受影响 |
| cron 段数对但内容非法（`"0 0 99 * * *"`） | 不注册 + ERROR（`CronTrigger` 构造时抛） | 照常注册 | 不受影响 |
| zone 非法 | 不注册 + ERROR | 照常注册 | 不受影响 |
| 全部合法 | 注册 | — | — |
| 没有任何 Agent 声明 `schedules` | 零注册 + 一条 INFO 汇总 | — | — |

**与 `ProfileLoader` 同口径**：`ProfileLoader` 的类 javadoc 写明「单条失败只跳过、不中断」，本节在**规则**这一层复刻同一条语义。

---

## §4 不变式

1. **三种触发源一条链路**（FR-006 / 章程原则 IX）：`AgentScheduler` 到点做的事**只有**「取或建会话 → 调 `AgentService.process`」。MUST NOT 有第二条处理链路、MUST NOT 感知 `ReActLoop` 内部、MUST NOT 解析或改写消息内容（FR-014）。
2. **审计同形**（FR-011 / SC-005）：钟推产生的 `llm_calls` / `tool_invocations` 记录与人推**同一张表、同一套字段、同一条写入路径**。**本节新增审计代码 0 行**。
3. **锁表只增不减**：`taskLocks` 的键集合 ⊆ 全部合法规则的 id 集合，且**每条被触发过的规则必然有锁**（首次触发时 `computeIfAbsent` 建好）。运行期**只增不减**——没有「任务结束就删锁」这种事（任务永不结束）。故**不需要清理策略**，条目数上界是 `schedules` 总条数，不会内存泄漏。
4. **锁的持有者恒为触发线程**：`tryLock` 成功 → `finally` 释放，**同一线程**内成对出现。MUST NOT 把锁的引用传出去、MUST NOT 在别的线程释放。
5. **会话三元组固定**（FR-007）：恒为 `("scheduler", "scheduler", profile.name())`。会话标识的**拼接**仍只在 `SessionService` 实现内部一处（H4 不变量 ④）——本节只提供三元组，**MUST NOT 自己拼 id**。
6. **不落任何任务状态**（FR-016）：进程重启后，调度器的状态**完全由 `AGENT.md` 的 `schedules` 重新推导**，没有任何「上次跑到哪」的残留。这是本节与第 28 节的**分界线**。

---

## §5 运行期时序

### §5.1 注册（`registerAll()`，进程启动时一次）

```text
serve / gateway 命令体
   │  起完 Spring 上下文
   ▼
context.getBean(AgentScheduler.class).registerAll()
   │
   ├─ profileRegistry.all()  ──►  [Profile, Profile, ...]
   │
   └─ 对每个 Profile 的每条 ScheduleSpec：
        ├─ 派生 ScheduleConfig（id = <名>#<序号>）        §2
        ├─ 归一化 cron（5→6 段）                          §3.1
        ├─ 解析时区（缺省 = 系统时区，恒显式）             §3.2
        └─ taskScheduler.schedule(() -> runOnce(profile, config), new CronTrigger(cron6, zone))
              │
              └─ 失败 ──► log.error(指名 Agent 与任务) + continue   §3.3
   │
   ▼
log.info("定时任务注册完成：共 N 条")     ← N 可为 0（默认配置下的预期状态，SC-008）
```

**注册期不建锁**：锁在**首次触发**时才由 `runOnce` 里的 `computeIfAbsent` 建好。理由——注册期建锁要在同一处再写一遍 id 派生与归一化之后才能拿到 id，而 `runOnce` 反正必须能独立建锁（`lockFor` 就是那条路径）；建两次是重复，且没有任何收益（`computeIfAbsent` 本身是原子的，不存在竞态）。

### §5.2 一次触发（`runOnce(profile, config)`）

```text
调度线程（池中之一，线程名 oryxos-scheduler-N）
   │
   ├─ lock = lockFor(config.id())     ← 包级私有；内部 taskLocks.computeIfAbsent(id, ReentrantLock::new)
   │
   ├─ lock.tryLock()  ──false──►  log.warn("定时任务 <id> 上一次尚未结束，本次触发跳过")
   │                              return            ← 不排队、不等待（FR-008 / US3）
   │   │true
   │   ▼
   ├─ try {
   │      session = sessionService.getOrCreate("scheduler", "scheduler", profile.name())
   │      agentService.process(session, config.message())        ← 与人推同一个入口
   │  } catch (Exception e) {
   │      log.error("定时任务 <id> 执行失败：<原因>", e)           ← 不外抛（FR-009 / US4）
   │  } finally {
   │      lock.unlock()                                          ← 无论成败（FR-010）
   │  }
   ▼
（返回调度线程池，等下一个触发点）
```

**三个关键时刻的正确性各由一条测试钉死**：

| 时刻 | 测试手法 | 只断言什么会漏掉 |
| --- | --- | --- |
| `tryLock()` 返回 false 时**什么都没发生** | 占住锁 → 调 `runOnce` → 断言 `agentService.process` **零次调用** | 「调用了但被拒」也能让「抛了异常」类断言变绿 |
| 异常**不外抛** | `process` 抛 `RuntimeException` → 断言 `runOnce` 正常返回 | — |
| 锁**真的释放了** | 异常之后**再调一次** `runOnce` → 断言 `process` **这次被调用** | 只断言「不抛异常」**抓不住漏放锁**——漏放锁时既不抛异常、日志也照打，但任务从此永久卡死 |

---

## §6 边界判定表

| # | 场景 | 行为 | 依据 |
| --- | --- | --- | --- |
| 1 | `schedules` 未声明 / 为空 | 零注册、零报错、进程正常起 | FR-001 / SC-008（**预期状态**，不是缺陷） |
| 2 | 一条规则 5 段、另一条 6 段 | 两条都注册 | FR-003 / US1 场景 6 |
| 3 | 两条完全相同的规则 | 两条各自注册，id 为 `#1` / `#2` | §2 约束 2 |
| 4 | cron 段数非法 / 缺失 / 内容非法 | 该条不注册 + ERROR，其它照常 | FR-004 / §3.3 |
| 5 | zone 非法 | 该条不注册 + ERROR，其它照常 | §3.2 |
| 6 | zone 缺失 | 按系统时区跑，**不拒绝注册** | FR-005 / US2 场景 4 |
| 7 | 同一任务上次未跑完 | 本次**直接跳过**（零调用、零排队） | FR-008 / US3 |
| 8 | 两个**不同**任务，一个锁被占 | 另一个**照常执行** | FR-008 / US3 场景 3 |
| 9 | 两个不同任务同时到点 | 最多 2 个并发，超出的**排队等待** | FR-019 / D4（**跨任务**行为） |
| 10 | 一个长任务在跑，另一任务的触发点到 | **不被锁阻塞**（锁按 id）；至多受池容量影响排队 | FR-008 / FR-019 / SC-009 |
| 11 | 池满时又来触发 | 排队等待，**不判失败、不静默丢弃** | FR-019 |
| 12 | `process` 抛任意异常 | 不外抛 + ERROR 日志 + 锁释放 | FR-009 / FR-010 / US4 |
| 13 | `process` 报「Profile 查不到」 | 同 12（属处理入口内部既有语义） | FR-009 |
| 14 | 同一 Agent 连续两次触发 | 拿到**同一个会话**（三元组相同） | FR-007 / US1 场景 5 |
| 15 | 进程关闭时有任务在跑 | 随调度器一起结束，**不等** | spec Edge Case / D9（归扩展阶段） |
| 16 | `registerAll()` 被调两次 | 任务注册两次（同一 id **共享同一把锁**，「不重叠」仍成立，触发次数翻倍） | D9（不做防护，YAGNI） |

---

## §7 本节为什么不落任何持久化结构（FR-016）

| 结构 | 现状 | 为什么不在本节 |
| --- | --- | --- |
| `scheduled_tasks` 表 | **不存在**（`schema.sql` 现状确认只有 5 张表） | 存任务登记 + 运行态，归第 28 节 |
| `task_executions` 表 | **不存在** | 存每次执行历史，归第 28 节 |
| `sessions` 表 | **既有**（第 18 节） | 本节**只经 `SessionService` 取或建**，不新增列、不改结构 |
| `llm_calls` / `tool_invocations` | **既有**（第 16 / 17 节） | 本节**一行不改**，失败/成功都经由 `AgentService` 内部既有路径落库 |
| `memory_entries` | **既有**（第 22 节） | 本节不涉及 |
| `notify_channels` | **既有**（第 19 节） | 本节不涉及 |

**`schema.sql` 本节 MUST NOT 被修改**——这是「本节不建任何表」的机器可判定形式。

**定义源与状态的分工（写进契约，供第 28 节接手）**：`AGENT.md` 的 `schedules` 是**定义源**；本节**没有任何状态**，进程重启后一切从定义源重新推导。第 28 节加的两张表存的是**状态 + 历史**，**不作为定义源**——重启时仍从文件重新协调。这条分工写清楚，第 28 节才不会误把表当成真相源。
