# Phase 0 Research: 插件化 Agent

**Feature**: `specs/012-plugin-agent` | **Date**: 2026-10-21

本节的技术未知量不多——底座（16 ~ 28 节）已经把「Profile 怎么被消费」全部定死，本节只是给 Profile 添一个来源。真正需要研究的是**落点**：课件里的五个类型 / 方法，在本仓既有代码里各归哪儿、以什么形态存在。

---

## D1：课件 `AgentLoader` 的落点是 `ProfileLoader`，不新增同名类

**Decision**: 不创建 `AgentLoader` 类。`ProfileLoader`（第 16 节交付）承担「扫描目录 → 解析 frontmatter → 派生 Profile」，本节只在它上面补两个公开入口。

**Rationale**:

- `ProfileLoader` 已经在做课件 `AgentLoader` 的全部工作：`loadAll()` 扫描 `.oryxos/agents/` 下的每个子目录、`parseFrontmatter` 拆 frontmatter、`toProfile` 派生 `Profile`。
- 新增一个同名类意味着「什么叫一个合法 Agent」有两份判定代码。`AgentResolver` 的 javadoc 已经为同一问题写过警告：判定真相源只能一处，否则 `profile list` 与 `chat` 会对同一个 Agent 给出相反结论。
- 技术方案 §8.2 仍写 `AgentLoader`，但它在描述**职责**；本仓的既有实现选择是 `ProfileLoader`。`docs/TechnicalSolution.md` 与代码不一致时，按 CLAUDE.md「以最新技术方案为准」——而技术方案没有要求类名逐字一致，只要求职责落点。

**Alternatives considered**:

- **新增 `AgentLoader` 包装 `ProfileLoader`**：多一层无行为的转发类，且第二处判定逻辑迟早漂移。否决。
- **改名 `ProfileLoader` → `AgentLoader`**：改前序节已定字面量（软门禁 ②），且 `ProfileLoader` 被 6 个测试类与 3 个装配点引用，收益为零。否决。

---

## D2：`ProfileLoader` 新增两个公开入口 `inspect(Path)` 与 `deriveProfile(Path)`

**Decision**:

```java
public ProfileLoader.AgentDir inspect(Path agentDir);   // 目录 → frontmatter + 正文 + 资源相对路径
public Profile deriveProfile(Path agentDir);            // 目录 → Profile（走同一套校验）
```

`AgentDir` 是 `ProfileLoader` 的公开嵌套 record：`name` / `path` / `frontmatter` / `body` / `resources`。

**Rationale**:

- 课件把 `AgentLoader` 的职责写成两件：「解析目录 → frontmatter/正文/资源路径」与「`deriveProfile(agentDir) → Profile`」。两件事的产物不同，一个返回 Profile、一个返回解析快照，分开表达才能让 `AgentLoaderTest` 的「认出 `scripts/`、`skills/`、`REFERENCE.md`」有可判定的观测面——**只返回 Profile 的话，资源识别没有任何可观测产物**，测试只能靠日志文本，那是脆判据。
- `deriveProfile(Path)` 是运行时注册（第 30 节）的入口：它必须与启动扫描**共用同一段校验**。实现上 `deriveProfile` 直接调用既有的 `toProfile`，`loadAllDetailed` 也调用它——同一段代码，不可能分叉。
- `inspect` 不校验语义（缺 `provider` 不抛），只做结构解析：这样 `AgentLoaderTest` 能单独断言「拆分正确」，`DeriveProfileTest` 单独断言「字段映射」，两个测试各自守一件事。
- `resources` 用**相对路径字符串**而非 `Path` 列表：磁盘绝对路径随临时目录变化，相对路径是可读、可断言的稳定形态（如 `scripts/reconcile.py`、`skills/report-format`、`REFERENCE.md`）。

**Alternatives considered**:

- **只加 `deriveProfile(Path)`，不加 `inspect`**：资源识别无观测面，`AgentLoaderTest` 退化成日志断言。否决。
- **`AgentDir` 做成顶层 record**：新增一个顶层 public 类型，超出「课件 AgentLoader 产物」的语义边界；嵌套在 `ProfileLoader` 下，名字与归属都更清楚。否决。
- **`inspect` 返回 `Profile` + 一个 side-channel**：两个返回值无法从一个方法干净给出。否决。

---

## D3：`ProfileLoadException` 由 private 提为 **public** 嵌套类

**Decision**: `ProfileLoader.ProfileLoadException` 改为 `public static final class`（`final` 是为了避开 FindSecBugs `CT_CONSTRUCTOR_THROW`：构造器会抛异常的类不应可被继承）。

**Rationale**:

- FR-004 / SC-003 要求「同一份非法配置，启动扫描与运行时注册的失败**异常类型 + 消息**完全相同」。异常类型若仍是 private，测试只能比较 `getClass().getName()` 字符串，判定是间接的；第 30 节的 `AgentLifecycleService` 也需要按类型捕获、映射到 HTTP 4xx。
- 这是**可见性变更，不是新概念**：异常类、消息文本、抛出点全部原样，只是从「只能在本类内流转」变成「调用方可以接住」。
- 既有 `ProfileLoaderTest` 断言的是日志文本，不受影响；`loadAllDetailed` 的 `Failure.reason` 仍是同一条 message，不加工。

**Alternatives considered**:

- **保持 private，测试比较类名字符串**：判定脆弱且第 30 节无法优雅捕获。否决。
- **改成顶层 `ProfileLoadException`**：多一个公开类型文件，收益与嵌套版相同。否决。
- **运行时路径抛 `IllegalStateException`**：与启动路径类型不一致，直接违反 FR-004。否决。

---

## D4：`ProfileRegistry` 改为可变并发索引，`all()` 按 Agent 名排序

**Decision**:

```java
private final Map<String, Profile> byAgentName = new ConcurrentHashMap<>();
public void register(Profile profile);   // 同名覆盖
public boolean remove(String agentName); // 返回是否真的移除
public boolean exists(String agentName);
```

`find` / `all` / `names` 签名不变；`all()` 与 `names()` 返回**按 Agent 名排序**的不可变快照。

**Rationale**:

- 运行时注册（第 30 节）与启动扫描必须写同一个索引实例；不可变对象无法满足。
- `ConcurrentHashMap` 的读是无锁的，`AgentService.process` 每轮都要 `find`，不能被注册操作阻塞。
- 顺序：现有仓库**没有任何测试或调用方断言 `ProfileRegistry` 的顺序**（`ProfileRegistryTest` 不存在；`ProfileService` 走 `ProfileLoader` 而非 registry）。按名排序让 `all()` 的输出与「目录名升序」的既有装载顺序一致，并在运行时新增后仍然稳定。
- 「同名后者覆盖前者」的既有口径保留：`register` 直接 `put`，不抛异常；冲突暴露仍由 `ProfileLoader` 的日志负责。

**Alternatives considered**:

- **`Collections.synchronizedMap(new LinkedHashMap<>())`**：保留插入顺序，但迭代必须手动 `synchronized`，且并发读比 CHM 慢。否决。
- **`CopyOnWriteArrayList` + 按名线性查**：读多写极少，但 `find` 变成 O(n)，`AgentService` 每轮调用。否决。
- **保持不可变、运行时重建一个新 registry 并替换 Bean**：Spring 里替换 Bean 需要 `@RefreshScope` 或自建持有者，把简单问题复杂化。否决。

---

## D5：`AgentScheduler` 的 `scheduledTasks` 句柄表与「替换」语义

**Decision**:

```java
private final Map<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

public int registerProfile(Profile profile);   // 先取消该 Agent 的既有句柄，再逐条注册；返回新注册成功的任务数
```

`registerAll()` 对返回值求和打印「共 N 条」——拆分后这个数只能从 `registerProfile` 拿（`scheduledTasks.size()` 含失败前的残留，且跨多个 Profile 时无法区分）。

- 任务 id 仍是 `<Agent>#<序号>`（第 25 节 FR-013），**替换时按 Agent 归属识别**——`RegisteredTask.profile().name()` 与 `profile.name()` **精确相等**，**MUST NOT 用 `<Agent>#` 前缀**（2026-10-21 修订：原文写「按前缀 `profile.name() + "#"`」，见下条与验收报告 §5.1）。
- 取消用 `ScheduledFuture#cancel(false)`（不打断正在执行的那一次）；同时从 `scheduledTasks` 与 `registeredTasks` 移除旧条目。
- `registerAll()` 退化为 `for (Profile p : profileRegistry.all()) registerProfile(p)`，日志仍打总数。

**Rationale**:

- 课程交付物明写「`scheduledTasks` 句柄表（任务 id → `ScheduledFuture`），与既有 `taskLocks` 并存」；`taskLocks` 是执行期互斥，句柄表是**生命周期管理**，两者维度不同，不能合并。
- 第 30 节的「更新 schedules」必须能撤掉旧规则，否则旧 cron 会继续到点触发。替换语义（spec 裁决 5）让「重复注册」成为幂等操作。
- **归属判据不能用前缀**：目录名可以含 `#`，任务 id 本身又以 `#` 分隔序号——`a` 与 `a#b` 并存时，`a#b#1` 会被 `a#` 前缀匹配误判成 `a` 的任务而取消（该 Agent 的 cron 被静默废掉）。故按 `RegisteredTask.profile().name()` **精确相等**判定归属，第 25 节的 id 规则不变。**（2026-10-21 修订：此处原写「前缀识别的前提是「Agent 名不含 `#`」……本节如实记录、不新增校验」——那个前提在实现中并不成立，已改为精确相等并补回归测试。）**
- `cancel(false)` 而非 `cancel(true)`：正在跑的那一次已经进入 `runOnce`，打断它会让会话/审计留下半截记录；让它跑完，下一次触发用新规则。

**Alternatives considered**:

- **用 `Map<String, List<String>>` 额外记归属**：多一份真相，且与「任务 id 确定性派生」重复。否决。
- **注册前先 `registerAll` 重建整张表**：会撤掉所有 Agent 的任务，代价与收益不成比例；且第 30 节只想更新一个 Agent。否决。
- **不取消、只 `put` 覆盖**：旧 `ScheduledFuture` 失去引用但仍在调度器里跑，句柄表「翻倍」且旧规则继续触发——正是 FR-005 禁止的形态。否决。

---

## D6：示例目录的软连接写成 `../../../skills/report-format`

**Decision**: `examples/agents/daily-reconcile/skills/report-format` 是一条**相对软连接**，目标字符串为 `../../../skills/report-format`。

**Rationale**:

- 同一条链接在两种布局下都指向各自的公共 Skill 根：
  - `examples/agents/daily-reconcile/skills/../../../skills/report-format` → `examples/skills/report-format`
  - `.oryxos/agents/daily-reconcile/skills/../../../skills/report-format` → `.oryxos/skills/report-format`
- 这正是章程原则 IV 要求的形态：**唯一真相源是软连接**，不写 frontmatter、不做跨 Agent 共享（每个 Agent 只是选择可见集合）。
- `ContextLoader.appendSkillBindings` 用 `toRealPath()` 解析后校验目标必须落在 `<oryxosDir>/skills/` 内；相对软连接在两种布局下都通过校验。用绝对路径则复制即失效。

**Alternatives considered**:

- **普通文件 `skills/report-format.md`**（课件原形）：`ContextLoader` 会把 Agent 内 `skills/` 的每个条目当软连接解析，普通文件 `toRealPath()` 落在 Agent 目录内 → 判「越界」抛异常，Agent 根本加载不了。spec 裁决 1 已排除。
- **绝对路径软连接**：复制到工作区后指向 `examples/`，越界。否决。
- **`cp -r` 时跟随软连接变成实体目录**：那就不再是「软连接指向公共实体」，违背章程原则 IV；向导必须用 `cp -a`。见 quickstart。

---

## D7：解释器放行 `python3`，示例正文写 `python3`（路径形态见 D11）

**Decision**: `application.yaml` 的 `shell.allowed_commands` 追加 `python3`；示例 `AGENT.md` 正文与课件脚本说明统一写 `python3`（不是课件里的 `python`）。

**Rationale**:

- 章程原则 IV 原文：通用 `shell` 调用解释器「MUST 仅在管理员显式白名单内放行」。本节把解释器放进随包默认白名单，是这条原则的兑现，不是绕过。
- 本机（WSL2 / Ubuntu）实测只有 `python3`（`/usr/bin/python3`，Python 3.12.3），没有 `python`。白名单比对的是 `argv[0]` 精确值、不折叠——写 `python` 会永不匹配。
- 脚本本身只用标准库、不联网、不需要 key；`RECON_ORDERS_CSV` / `RECON_SETTLE_CSV` 缺失时输出说明性 JSON 并退出 0（课件原码即如此）。

**Alternatives considered**:

- **同时放行 `python` 与 `python3`**：`python` 在本机不存在，加了只会让人以为可用。否决。
- **改用 `bash scripts/...`**：示例是 Python 脚本（课件给定），换语言等于重写示例。否决。

---

## D8：「脚本限定在本 Agent 的 `scripts/` 目录」不做，记已知边界

**Decision**: 本节不实现目录限定。`WhitelistSandbox` 仍只校验 `argv[0]`，`SandboxAction` 不增字段，`ShellTools` 一行不改。

**Rationale**:

- 落地它要改第 24 节的沙箱契约（`SandboxAction` 需携带完整命令与当前 Agent 身份）与第 20 节的 `ShellTools`——软门禁 ④，且超出课件「本节交付物」清单（spec 裁决 2 已报批）。
- 章程原则 IV 自己写了「白名单是劝阻级防线，不替代真隔离」；「装一个带脚本的 Agent = 信任写它的人」这条信任边界**在核心阶段是如实记录的边界**，不是本节能消除的。
- 记录位置：`spec.md` 边界段 + 验收报告「已知边界」+ `contracts/agent-directory.md` 的安全提示。

**Alternatives considered**: 见 spec 裁决 2——不做，归扩展阶段的 Agent 容器 / 网络隔离。

---

## D9：`tools` 引用未注册能力的告警落在 `ToolRegistry.availableFor`

**Decision**: 不在加载期校验工具名；`ProfileLoader` 不新增「已知工具集合」参数。

**Rationale**:

- `ToolRegistry.availableFor(Profile)` 已经对「声明了但注册表里没有」的工具名打 WARN + 跳过（第 20 节 FR-011），且这是**每轮运行时**都会走到的路径。
- 加载期校验需要 `oryxos-core` 知道「工具怎么装配」——那是 `oryxos-tool` 的职责，违反依赖方向；而且轻命令（`profile list`）看不到 Spring 容器，两处会得出不同结论。
- spec 裁决 7 已定。

**Alternatives considered**: 给 `ProfileLoader` 注入 `Set<String> knownTools`（改第 16 节构造器签名）——违反依赖方向与「两处取同一集合」的既有教训。否决。

---

## D10：正文无缓存 / 即时生效**不重测**

**Decision**: `ProgressiveDisclosureTest` 只断言「正文进 prompt、参考 / 子指令 / 脚本不预载、Skill 只注入摘要」；不重复断言「改文件后下一次读到新内容」。

**Rationale**:

- 课件验收 harness 段明写：「Agent 正文改了即时生效」不用在这节重测——第 17 节 `ContextLoaderTest` 的无缓存回归已经钉死（`rereadsFileOnEveryLoad` 一类用例）。**（2026-10-21 复核：那条用例读的是 Bootstrap `AGENTS.md`，不覆盖 `AGENT.md` 正文；已补 `rereadsAgentBodyOnEveryLoad` 直接判「改正文 → 下一次 `load()` 即见」，见验收报告 §5.8。原写 `rereadsFilesOnEveryLoad` 是笔误。）**
- 重复测同一行为会让测试套件变慢、维护面变大，且失败时定位更模糊。

**Alternatives considered**: 顺手加一条——属于「多」出来的断言，与 spec SC-004 的现有覆盖重复。否决。

---

## D11：示例正文引用脚本 MUST 写相对工作区根的路径（设计复核发现，2026-10-21）

**Decision**: 示例 `AGENT.md` 正文写 `python3 .oryxos/agents/daily-reconcile/scripts/reconcile.py`；契约 `agent-directory.md` §7 以 MUST 条款固定该口径。

**Rationale**:

- `ShellTools` 是 `new ProcessBuilder(argv).start()`，**没有** `.directory()` 调用——子进程工作目录 = JVM 进程 cwd，**不是** Agent 目录。
- 两条生产启动路径的 cwd 都是**工作区根**：`chat` / `serve` 要求 cwd 下存在 `.oryxos/`；`bin/start.sh` 先 `cd "$BASE_DIR"` 再 `nohup java -jar`（工作区 = `$BASE_DIR/.oryxos`）。因此相对工作区根的路径（`.oryxos/agents/<name>/...`）在两条路径下都解析得到；照课件写 `python3 scripts/reconcile.py` 则**永远**在工作区根下找 `scripts/` → 文件不存在。
- Skill 实体不手写路径：`ContextLoader` 每次组装已注入绝对路径，正文让模型「按注入的路径读 SKILL.md」。
- 这是对课件的又一处措辞级偏差（与 D7 同类）：语义一致，字面按本仓 cwd 事实修正。

**Alternatives considered**: 给 `ShellTools` 加 `.directory(agentDir)`——改第 20 节引擎行为，违反 FR-011「六个文件 git diff 为空」，且把「当前 Agent 是谁」引入工具层（`SandboxAction` 同样看不到）。否决，记为本节已知边界的一部分（脚本环境不感知 Agent 目录）。

---

## 实现期注意点（tasks 阶段照办，非独立决策）

- `AgentDir.frontmatter` 返回**不可变副本**（`Collections.unmodifiableMap(new LinkedHashMap<>(parsed))`），防调用方改脏解析结果。
- `ProfileRegistry.all()` / `names()` 用 `TreeMap` / `TreeSet` 排序后包 `Collections.unmodifiable*`——**MUST NOT 用 `Set.copyOf`**（不保序）。
- `inspect(Path)` 对 `agentDir.getFileName() == null`（根路径）先 `Objects.requireNonNull` 给出清晰消息，不抛裸 NPE。
- 「取消再注册」不是事务：`registerProfile` 内先取消后注册，中间态（旧句柄已取消、新的未注册）对并发读可见。本节接受——注册只在启动期与第 30 节的单写者路径发生，javadoc 里写明**非并发注册**假设。

---

## 依赖核实（H3）

| 目标 API | 所在位置 | 核实方式 |
|---|---|---|
| `ScheduledFuture` / `cancel` / `ConcurrentHashMap` | JDK 21 标准库 | JDK 自带，无需依赖 |
| `TaskScheduler#schedule(Runnable, Trigger)` 返回 `ScheduledFuture<?>` | `org.springframework:spring-context:6.2.19`（compile） | `./mvnw -o -pl oryxos-core dependency:tree -Dincludes=org.springframework:spring-context` → `\- org.springframework:spring-context:jar:6.2.19:compile` |
| `Files.list` / `toRealPath` / `Path.resolve` | JDK 21 | 既有 `ProfileLoader` / `ContextLoader` 已在用 |
| `@TempDir` / Mockito / AssertJ | `spring-boot-starter-test`（test） | 既有测试已在用 |

**没有需要「动手前核实第三方 API」的新调用**——本节只新增对 JDK 与已在 compile classpath 上的 Spring 调度 API 的调用。

---

## 测试替身设计（供 tasks 阶段参考）

- `AgentScheduler` 的三个协作者全是既有测试用过的替身：`AgentService` / `SessionService`（Mockito）、`ScheduledTaskStore`（`InMemoryTaskStore`，`AgentSchedulerTest` 内已有手写实现）、`TaskScheduler`（`CapturingTaskScheduler`）。本节新测试需要 `CapturingTaskScheduler` 的 `schedule` **返回一个可取消的 `ScheduledFuture` 桩**，并记录被 `cancel` 的句柄。
- `Profile` 是 10 参 record，测试里沿用 `new Profile(name, null, null, null, null, null, null, schedules, null, null)` 的紧凑写法（`AgentSchedulerTest` 已有同款）。
- `ProgressiveDisclosureTest` 造一个临时 `.oryxos/` 布局：`agents/<name>/AGENT.md`、`REFERENCE.md`、`scripts/x.py`、`skills/<link>` → `skills/<entity>/SKILL.md`。软连接用 `Files.createSymbolicLink`；**若文件系统不支持软连接，测试 MUST 显式 `assumeTrue` 跳过并在输出里可见**（WSL2 ext4 与本仓既有 `ContextLoaderTest` 均支持，属兜底）。
