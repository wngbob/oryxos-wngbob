# 契约：AgentScheduler（定时任务 / 第三种触发源）

**Feature**: `specs/008-agent-scheduler` | **Branch**: `class-25` | **Date**: 2026-10-18

本文件冻结本节对**对外字面量**、**语义边界**、**定义源**、**装配与触碰面**的承诺。后续 tasks / 实现 / 验收不得偏离；要偏离先走软门禁（停下报告）。

**上游契约**：

- 第 18 节的 [`specs/003-cli-session/contracts/cli-assembly.md`](../../003-cli-session/contracts/cli-assembly.md) §3 —— 全部 `@Bean` 的唯一落点，本节装配改动的**逐字依据**
- 第 18 节的 [`specs/003-cli-session/contracts/cli-surface.md`](../../003-cli-session/contracts/cli-surface.md) §1 / §2.4 —— 命令名 / 参数 / 退出码三项的修订程序，本节**不触发**它
- 第 16 节的 [`specs/001-llm-provider/data-model.md`](../../001-llm-provider/data-model.md) —— `schedules` 的 5 段 cron 口径（本节**不改**）
- 骨架期的 [`specs/000-runtime-kernel/contracts/agent-directory.md`](../../000-runtime-kernel/contracts/agent-directory.md) §32 —— 同上

---

## §1 冻结字面量

### §1.1 本节新增（交付即冻结）

| 类型 | 全限定名 | 形态 |
| --- | --- | --- |
| `AgentScheduler` | `io.oryxlabs.oryxos.core.schedule.AgentScheduler` | `public class`；**不带** `@Component` / `@Configuration` / `@Service` |
| `ScheduleConfig` | `io.oryxlabs.oryxos.core.schedule.ScheduleConfig` | `public record ScheduleConfig(String id, String cron, String zone, String message) {}` |

**构造器签名（冻结）**：

```java
public AgentScheduler(TaskScheduler taskScheduler,
                      ProfileRegistry profileRegistry,
                      AgentService agentService,
                      SessionService sessionService)
```

**方法可见性（冻结）**：

| 成员 | 可见性 | 理由 |
| --- | --- | --- |
| 上述四参构造器 | `public` | 装配处与单测 |
| `void registerAll()` | `public` | 唯一 public 方法；调用方在 `io.oryxlabs.oryxos.cli.command` 包，跨包 |
| `void runOnce(Profile profile, ScheduleConfig config)` | **包级私有** | 测试接缝（US3 / US4），不进对外概念 |
| `ReentrantLock lockFor(String taskId)` | **包级私有** | 测试接缝（US3 占锁） |
| 归一化 / 时区解析 / 单条注册等 | `private` | 实现细节 |

**`ScheduleConfig` 的字段序（冻结）**：`id` / `cron` / `zone` / `message`——与课件「本节交付物」逐字一致。

### §1.2 骨架期与第 16 / 17 / 18 节既有（**MUST NOT 修改**，FR-015）

| 类型 | 冻结内容 |
| --- | --- |
| `Profile.ScheduleSpec` | `public record ScheduleSpec(String cron, String zone, String message) {}` —— **不加 `id`、不改字段序** |
| `ProfileLoader` | `schedules(Object raw)` 的解析逻辑逐字不动；**归一化 MUST NOT 进这里** |
| `AgentService` | `public String process(Session session, String userMessage)` —— 签名逐字不动，本节**只新增调用方** |
| `ReActLoop` / `ToolExecutor` | 一行不改（MUST NOT 感知触发源） |
| `SessionService` | `Session getOrCreate(String channel, String userId, String profileName)` —— 签名逐字不动 |

**判定方式**：这六个文件 `git diff` 为空（SC-006）。

### §1.3 日志消息口径（FR-004 / FR-009）

四条消息的**前缀逐字冻结**——它们是「指名 Agent 与任务」这条要求的机器可判定形式，也是人工验收时唯一的现场证据：

| 场合 | 级别 | 消息（`{}` 为占位） |
| --- | --- | --- |
| 单条规则注册失败 | `ERROR` | `定时任务注册失败（Agent {}，任务 {}）：{}` —— 参数依次是 Agent 名、任务 id、原因（+ 异常对象） |
| 注册流程结束 | `INFO` | `定时任务注册完成：共 {} 条` —— **0 条也打**（SC-008 的可见形式） |
| 上一次未结束、本次跳过 | `WARN` | `定时任务 {} 上一次尚未结束，本次触发跳过` |
| 一次执行失败 | `ERROR` | `定时任务 {} 执行失败：{}` —— 参数是任务 id、原因（+ 异常对象） |

**两条硬约束**：

- **失败 MUST NOT 静默**（FR-009）：`runOnce` 的 catch **必须**落 ERROR 日志，MUST NOT 空 catch、MUST NOT 只 `e.printStackTrace()`。
- **消息里 MUST NOT 出现任何凭证**：本节四条消息只带 Agent 名、任务 id、原因字符串——**原因字符串来自异常消息**，而本节路径上的异常来自 cron / 时区解析与 `AgentService`，都不含凭证。若将来异常消息里出现了凭证（例如某个 Provider 把 key 拼进了报错），那是**上游的**泄漏面，不归本节处理，但也**不允许本节把它转抄进新日志**。

---

## §2 语义边界（每一条都有测试钉死）

| # | 规则 | 为什么 |
| --- | --- | --- |
| 2.1 | cron **5 段为准**，补 `0 ` 前缀成 6 段后交给调度器；**6 段原样透传**；其它段数抛异常 | FR-003 / 裁决 1；实测 Spring 要求 6 段（research §0.1） |
| 2.2 | 归一化 MUST **只发生在 `AgentScheduler` 内部一处**，MUST NOT 回写 Profile、MUST NOT 进 `ProfileLoader` | FR-003；第 16 节交付物零改动 |
| 2.3 | 时区**声明了就按声明的跑**；未声明按系统时区；**无论声明与否，`CronTrigger` 恒带显式 `ZoneId`** | FR-005 / US2；不依赖框架的隐含行为 |
| 2.4 | 单条规则失败（段数 / 内容 / 时区 / 缺失）**不中断注册流程**，其它规则与其它 Agent 不受影响 | FR-004；与 `ProfileLoader`「单条失败只跳过」同口径 |
| 2.5 | 任务 id MUST 由 **Profile 名 + 序号**（从 1 起）确定性派生，MUST NOT 用随机值 / 哈希 / 时间戳 | FR-013 |
| 2.6 | **不去重**：两条完全相同的规则派生两个 id、各自注册、各有各的锁 | spec Edge Case（去重是静默行为） |
| 2.7 | `tryLock()` **无参**（不等待）；失败即返回，**不排队、不并发** | FR-008 / US3；`lock()` 是反的 |
| 2.8 | 锁按**任务 id** 维度，一个任务的锁 MUST NOT 阻塞另一个任务 | FR-008 / US3 场景 3 |
| 2.9 | `runOnce` 内部的**任何**异常 MUST NOT 外抛 | FR-009 / US4 |
| 2.10 | 锁 MUST 在 `finally` 中释放，无论成功失败 | FR-010；**只有「二进宫」断言抓得住漏放锁** |
| 2.11 | 钟推的会话三元组恒为 `("scheduler", "scheduler", profile.name())`，经 `SessionService.getOrCreate` 取或建 | FR-007 / H4 不变量 ④ |
| 2.12 | 钟推 MUST 汇入 `AgentService.process`，MUST NOT 有第二条处理链路、MUST NOT 解析或改写消息内容 | FR-006 / FR-014 / 章程原则 IX |
| 2.13 | 定时规则 MUST **只在** `serve` / `gateway` 注册；`chat` MUST NOT 注册 | FR-012 / 裁决 3 |
| 2.14 | 调度线程池**固定 2 个线程**，池大小 MUST NOT 做成配置键 | FR-019 / D4 |
| 2.15 | **跨任务**同时到点时最多 2 个并发、超出的**排队等待**；MUST NOT 与「同一任务重叠跳过」混为一谈 | FR-019；两把尺子 |
| 2.16 | 本节 MUST NOT 新增任何审计代码；钟推的 `llm_calls` / `tool_invocations` 走既有路径 | FR-011 / SC-005 / 章程原则 VI |

---

## §3 定义源与配置载体

### §3.1 定义源（唯一）

```yaml
# .oryxos/agents/<name>/AGENT.md 的 frontmatter
schedules:
  - cron: "0 9 * * *"          # 5 段 Unix cron（也接受 6 段）
    zone: Asia/Shanghai        # 可选；缺省按服务器系统时区
    message: 帮我总结昨天的 PR 评审进度
```

- **定义源唯一**：只在 `AGENT.md` frontmatter 里。**MUST NOT** 编译期写死（FR-001 / FR-003）。
- **`schedules` 不进 `ToolRegistry`**：它是 Profile 的字段，不是 Tool（章程原则 IV / XI 的同类）。
- **本节不新增任何配置键**：`oryxos-boot/src/main/resources/application.yaml` **一个字节都不动**。池大小（FR-019）刻意不做成配置键。

### §3.2 生效时机

改 `schedules` → **重启**生效。核心阶段**不做热加载**、**不做运行时增删**（与第 24 节「白名单改后重启生效」同口径）。

---

## §4 装配改动与触碰面

### §4.1 `CliAssemblyConfiguration` 新增两个 `@Bean`（唯一装配落点）

```java
@Bean
public ThreadPoolTaskScheduler taskScheduler() {
  ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
  scheduler.setPoolSize(2);                       // FR-019：固定小池，不做配置键
  scheduler.setThreadNamePrefix("oryxos-scheduler-");
  return scheduler;
}

@Bean
public AgentScheduler agentScheduler(ThreadPoolTaskScheduler taskScheduler,
                                     ProfileRegistry profileRegistry,
                                     AgentService agentService,
                                     SessionService sessionService) {
  return new AgentScheduler(taskScheduler, profileRegistry, agentService, sessionService);
}
```

**三条约束**：

1. **`agentScheduler()` 的方法体 MUST NOT 调 `registerAll()`**——那等于「起上下文即注册」，`chat` 也会注册，撞 FR-012（D3 / D12 的源码断言守这一条）。
2. **`taskScheduler()` 不带 `@ConditionalOnMissingBean`**——本节只有一个实现、没有让位链（YAGNI）。部署方自建 `TaskScheduler` Bean 会撞 Bean 名冲突，而不是静默接管；真出现第二档时加一行注解即可（见 plan「已知接缝」）。
3. **不引入任何新的声明顺序约束**——这两个 Bean 不参与任何 `@ConditionalOnMissingBean` 的求值链，声明位置不影响语义（与 `memoryService()` / `memorySource()` 那对**不同**）。

### §4.2 触碰前序节 main 代码：两个命令各加一行

```java
// ServeCommand.run() / GatewayCommand.run()：起完上下文、打完启动信息之后
context.getBean(AgentScheduler.class).registerAll();
```

| 不变量 | 状态 |
| --- | --- |
| 命令名（`serve` / `gateway`） | **一字未变** |
| 参数（`--port`） | **一字未变** |
| 退出码 | **一字未变** |
| `CliCommandTreeTest` 的 15 条叶子集合断言 | **保持绿**（FR-017） |
| `ServeCommand.keepAlive()` 的语义与复用关系 | **未动** |

### §4.3 不被触碰的（本节最该被 review 的「零改动面」）

```text
oryxos-core/.../engine/AgentService.java        # FR-015：只被调用
oryxos-core/.../engine/ReActLoop.java           # FR-006：MUST NOT 感知触发源
oryxos-core/.../engine/ToolExecutor.java        # FR-011：失败审计路径复用
oryxos-core/.../profile/ProfileLoader.java      # FR-003：归一化不进装载层
oryxos-core/.../profile/Profile.java            # FR-015：ScheduleSpec 一字不改
oryxos-core/.../session/SessionService.java     # FR-007 / H4 ④
oryxos-storage/src/main/resources/db/schema.sql # FR-016：不建任何表
oryxos-cli/.../command/ChatCommand.java         # FR-012：chat MUST NOT 注册
oryxos-boot/src/main/resources/application.yaml # 不新增配置键
oryxos-tool/ · oryxos-memory/ · oryxos-web/     # 本节零改动
```

---

## §5 测试契约（harness 映射）

### §5.1 课件 harness 四条 ↔ 落地断言

| 课件守点 | 落地测试方法（英文名） | 断言形态 |
| --- | --- | --- |
| 注册时带上了 cron **和时区** | `registerAll_passesConfiguredCronAndZone` | 抓 `Trigger` → 固定 `Clock` 求 `nextExecution` → 落点在配置时区下是 09:00 **且与错时区不等**（D5；课件原手法不可写，见 research §0.2） |
| 锁被占时本次触发**直接跳过** | `runOnce_skipsWhenTaskLockHeld` | 占住锁 → 调 `runOnce` → 断言 `process` **零次调用**（不是「调用了但被拒」） |
| 执行抛异常**不外抛** | `runOnce_swallowsExceptionFromProcess` | `process` 抛 `RuntimeException` → `runOnce` 正常返回 |
| 异常后**锁真的释放** | `runOnce_releasesLockAfterFailure_secondRunProceeds` | **二进宫**：异常后再调一次 → 断言这次 `process` **被调用**（只断言「不抛异常」抓不住漏放锁） |
| 会话三元组固定 | `runOnce_usesFixedSchedulerSessionIdentity` / `runOnce_reusesSameSessionAcrossTriggers` | 断言 `getOrCreate("scheduler", "scheduler", <名>)` 被调用；两次触发拿到同一会话 |

### §5.2 两个测试类

| 测试类 | 位置 | 覆盖 |
| --- | --- | --- |
| `AgentSchedulerTest` | `oryxos-core/src/test/.../core/schedule/` | §5.1 五条 + cron 归一化（5→6 段、6 段透传、4/7 段报错、缺失报错）+ 单条失败隔离 + id 确定性 + 不去重 + 不同任务互不阻塞 + 零注册 |
| `SchedulerAssemblyTest` | `oryxos-cli/src/test/.../cli/` | 装配面：`AgentScheduler` Bean 存在 + `TaskScheduler` Bean 池大小**恰好 2**（FR-019）；源码级 FR-012（`serve` / `gateway` **含** `registerAll`，`chat` 与 `agentScheduler()` 方法体**不含**） |

**测试替身**：`TaskScheduler` / `AgentService` / `SessionService` 用 Mockito（与 `AgentServiceTest` / `ReActLoopTest` 同款）；`TriggerContext` 用手写桩（固定 `Clock`）。**全程无 `Thread.sleep`、无真实时钟依赖、不起 Spring 上下文**（`SchedulerAssemblyTest` 除外，它起真上下文）。

**测试方法名 MUST 是英文**（驼峰或 snake_case）。课件 harness 里若给出中文方法名，翻译成语义等价的英文名落地，**课件原文进 `@DisplayName`** 以便对号。

**为什么「二进宫」是必需的**：只断言「不抛异常」时，一个漏了 `finally unlock()` 的实现**照样绿**——它不抛异常、日志也照打，但任务从此**永久卡死**。这是本节唯一一条「只有二进宫能抓住」的缺陷（US4 的 Why this priority 原文）。

---

## §6 本节 MUST NOT

| # | 禁止 | 依据 |
| --- | --- | --- |
| 6.1 | 改 `AgentService` / `ReActLoop` / `ToolExecutor` / `ProfileLoader` / `Profile.ScheduleSpec` / `SessionService` | FR-015 / SC-006 |
| 6.2 | 在 `ProfileLoader` 里做 cron 归一化，或把归一化结果回写 Profile | FR-003 / D1 |
| 6.3 | 新增 CLI 命令（命令树保持 15 条） | FR-017 |
| 6.4 | 创建 `scheduled_tasks` / `task_executions` 表，或落任何任务运行态 | FR-016 |
| 6.5 | 为钟推新增审计代码 / 新开记账路径 / 新表 | FR-011 / 章程原则 VI |
| 6.6 | 引入分布式锁 / 选主 / 租约 / 任何调度框架（Quartz / ShedLock / db-scheduler） | FR-008 / FR-018 |
| 6.7 | 用 `@Component` / `@PostConstruct` / `@Scheduled` / `@EnableScheduling` | FR-002 / FR-012 / 裁决 3 / 第 18 节契约 §3 |
| 6.8 | 把池大小做成配置键，或改 `application.yaml` | FR-019 / 软门禁 ① |
| 6.9 | 在 `AgentScheduler` 里拼会话标识 | FR-007 / H4 不变量 ④ |
| 6.10 | 用 `lock()`（等待）代替 `tryLock()`（跳过） | FR-008：那是**排队**，与「跳过」相反 |
| 6.11 | 用一把全局锁 / `synchronized (this)` | FR-008：一个任务的锁 MUST NOT 阻塞另一个 |
| 6.12 | 空 catch 或只 `printStackTrace()` | FR-009：失败 MUST NOT 静默 |
| 6.13 | 新建异常类型（`ScheduleConfigException` 等） | D6 / 软门禁 ①：不在交付物清单里 |
| 6.14 | 往 `AGENT.md` frontmatter 加 `id` / `key` 字段 | D2 / 软门禁 ① |
| 6.15 | 解析或改写消息内容、感知 `ReActLoop` 内部 | FR-014：模块只干「拼一条消息交给处理入口」 |
| 6.16 | 把定时任务表述为「一种新能力」 | 章程原则 II / 技术方案 §8.5：它是**第三种触发源** |
| 6.17 | 在验收报告里把「默认配置下零任务注册」写成缺陷 | SC-008：那是**预期状态** |

---

## §7 文档连带修订

| # | 文件 | 修订 | 依据 |
| --- | --- | --- | --- |
| ① | `oryxos-cli/.../CliAssemblyConfiguration.java`（`userInteraction()` 的 javadoc） | **加**带日期的修订注：「2026-10-18 第 25 节裁决：定时任务的 `ask_user` 分档本节不做，改归扩展阶段评估；原挂账指向不成立」——**不改原句** | D10 / 裁决 4 |
| ② | `docs/TechnicalSolution.md` §8.5 | **加**带日期的修订注：「OryxOS 启动时扫描」= **常驻模式启动时**（`serve` / `gateway`），交互式 `chat` 不注册——与同文档 §8.6 的表述对齐；**不改历史正文** | 裁决 3 连带 |
| ③ | `CLAUDE.md` | 「当前状态」「待办」两处的第 25 节条目更新；补一条定时任务要点 | 节级收尾 |

**MUST NOT 触碰**：

- `website/` —— 本次不涉及对外定位 / 能力 / 路线图表述（`CLAUDE.md`「对外内容三处同步」那条规则**不触发**：定时任务是内核能力，不是对外承诺面）
- `specs/001-llm-provider/` 与 `specs/000-runtime-kernel/` 里 5 段 cron 的历史文档 —— **那正是 D1 选 5 段的理由**，改它们等于篡改历史留痕
