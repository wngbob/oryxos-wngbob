# 契约：运行时注册 API

**Feature**: `specs/012-plugin-agent` | **Date**: 2026-10-21

本节新增的公开面只有三处：`ProfileLoader` 的两个入口 + 一个嵌套 record、`ProfileRegistry` 的三个写方法、`AgentScheduler` 的一个注册方法 + 一张句柄表。三者都在 `oryxos-core`，**不新增模块、不新增依赖、不新增配置键**。

---

## §1 `ProfileLoader`（`io.oryxlabs.oryxos.core.profile`）

### §1.1 `deriveProfile(Path agentDir) → Profile`

```java
public Profile deriveProfile(Path agentDir)
```

- **输入**：一个 Agent 目录（`.../agents/<name>`）；Agent 名取 `agentDir.getFileName().toString()`。
- **行为**：读取 `AGENT.md`、解析 frontmatter、走**与启动扫描完全相同**的 `toProfile` 校验。
- **抛出**：`ProfileLoadException`（§1.3），消息与 `loadAllDetailed()` 里的 `Failure.reason` **逐字相同**。
- **不抛**：IO 以外的任何「软」问题都在这里变成 `ProfileLoadException`，不会返回半成品。

### §1.2 `inspect(Path agentDir) → AgentDir`

```java
public ProfileLoader.AgentDir inspect(Path agentDir)
```

```java
public record AgentDir(
    String name,
    Path path,
    Map<String, Object> frontmatter,
    String body,
    List<String> resources) {}
```

- **行为**：只做结构解析，**不校验 `provider` 等语义**；不读取 `scripts/` / `skills/` / `REFERENCE.md` 的内容。
- **`resources`**：相对路径、字典序、不递归（见 `contracts/agent-directory.md` §3）。
- **抛出**：`ProfileLoadException`（缺 `AGENT.md` / IO / YAML 非法）。
- **不变式**：`deriveProfile(dir)` ≡ `toProfile(inspect(dir))` 的语义结果——两者共用同一段 `toProfile`。

### §1.3 `ProfileLoadException`（可见性变更）

```java
public static final class ProfileLoadException extends RuntimeException
```

- **仅可见性变更**：类名、消息文本、抛出点全部保持第 16 节的现状；从 private 改为 public 嵌套类。
- **为什么**：FR-004 / SC-003 要求启动扫描与运行时注册「同一异常类型 + 同一消息」可机械判定；第 30 节的 API 需要按类型捕获并映射为 HTTP 4xx。
- **`final`**：构造器会抛异常的类不可被继承（FindSecBugs `CT_CONSTRUCTOR_THROW`）。

---

## §2 `ProfileRegistry`（`io.oryxlabs.oryxos.core.profile`）

```java
public void register(Profile profile)        // 同名覆盖；null → NPE
public boolean remove(String agentName)      // 返回是否真的移除；null → NPE
public boolean exists(String agentName)      // null → NPE
```

既有方法签名不变：

```java
public Optional<Profile> find(String agentName)
public List<Profile> all()                   // 按 Agent 名排序的不可变快照
public Set<String> names()                   // 按 Agent 名排序的不可变快照
```

**语义**：

1. `register()` 之后立即 `find()` 可见（同一实例）。
2. `remove()` 之后 `exists()` 为假、`all()` 里不再有它。
3. 同名注册**覆盖**，不抛异常（与既有「同名后者覆盖前者」口径一致）。
4. 线程安全：底层 `ConcurrentHashMap`；`all()` / `names()` 返回快照，调用方改动不影响索引。
5. **不持久化**：重启由 `CliAssemblyConfiguration#profileRegistry` 重新 `loadAll()` 重建。

**构造器**：`ProfileRegistry(List<Profile> profiles)` 保留——启动扫描与 7 个既有测试类都用它；内部改为逐条 `register` 到并发映射。

---

## §3 `AgentScheduler`（`io.oryxlabs.oryxos.core.schedule`）

### §3.1 `registerProfile(Profile profile)`

```java
public int registerProfile(Profile profile)
```

**返回**：本次**新注册成功**的任务数（`schedules` 为空或全部失败 → `0`）。`registerAll()` 把各返回值求和作为「共 N 条」；第 30 节的 API 也可直接复用。

**行为**：

1. **先注销**：取出**属于该 Agent** 的所有句柄——归属判据是 `registeredTasks` 里 `RegisteredTask.profile().name()` 与 `profile.name()` **精确相等**——逐个 `cancel(false)`，并从 `scheduledTasks` 与 `registeredTasks` 移除。**MUST NOT 用 `<Agent>#` 名字前缀判定归属**：`#` 在目录名里合法，`a` 与 `a#b` 并存时前缀匹配会把 `a#b#1` 误取消、把它的 cron 静默废掉。**（2026-10-21 修订：原文写「键以 `profile.name() + "#"` 开头」，实现已按精确相等修正并补回归测试 `AgentSchedulerRegisterTest.registerProfile_doesNotCancelAgentWhoseNameIsPrefixOfAnother`，见验收报告 §5.1）**
2. **再注册**：遍历 `profile.schedules()`，序号从 1 起，id = `<Agent>#<序号>`；每条走 `normalizeCron` → `resolveZone` → `nextRunAt` → `taskStore.register(...)` → `taskScheduler.schedule(...)`，把返回的 `ScheduledFuture<?>` 写入 `scheduledTasks`。
3. **单条失败隔离**：任何一条抛 `RuntimeException` → ERROR 日志指名 Agent 与任务后跳过；同 Profile 的其余规则与其它 Agent 照常。
4. **`schedules` 为空**：不注册任何句柄（该 Agent 只参与人推），返回 `0`。

### §3.2 `registerAll()`

`registerAll()` 的行为改为 `int total = 0; for (Profile p : profileRegistry.all()) total += registerProfile(p);`，末尾仍打印 `定时任务注册完成：共 {total} 条`。**调用点不变**（`serve` / `gateway` 命令体；`chat` 不调用）。

### §3.3 `scheduledTasks` 句柄表

```java
private final Map<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();
```

- **键**：任务 id（`<Agent>#<序号>`）。
- **值**：`TaskScheduler#schedule` 的返回值。
- **测试接缝**：包级私有 `Map<String, ScheduledFuture<?>> scheduledTaskHandles()` 返回不可变副本；**不进对外概念**（`SchedulerAssemblyTest` 只读源码，不碰它）。
- **与 `taskLocks` 并存**：锁管执行期互斥（只增不减），句柄管调度生命周期（可增可减）。

### §3.4 失败语义

| 场景 | 结果 |
|---|---|
| cron 段数非法 / 缺失 | 该条跳过，ERROR 指名；其余照常 |
| 时区非法 | 同上 |
| `taskStore.register` 抛异常 | 同上 |
| 同一 Agent 再次注册 | 旧句柄全部取消（含上次注册失败前留下的），新规则按新配置注册；句柄数不翻倍 |
| 旧句柄正在执行 | `cancel(false)` 不打断，让它跑完；下一次触发用新规则 |

---

## §4 不变式（跨类）

1. **同一套校验**：`deriveProfile` 与 `loadAllDetailed` 共用 `toProfile`；启动扫描与运行时注册的失败消息逐字相同。
2. **同一处理链路**：注册的定时任务仍调用 `AgentService.process`，`ReActLoop` 不感知来源（章程原则 IX）。
3. **引擎零改动**：`ReActLoop` / `ToolExecutor` / `ContextLoader` / `ShellTools` / `WhitelistSandbox` 的 `git diff` 为空（FR-011）；`AgentService` **行为零改动**，但文件非空——多了构造器上的 `@SuppressFBWarnings("EI_EXPOSE_REP2")`（`+6` 行：一行 import + 五行注解），因为 `ProfileRegistry` 自本节起成为可变类，SpotBugs（硬门禁）报「构造器持有可变对象引用」。**（2026-10-21 订正：原文写「六个文件 `git diff` 为空」，实测五个为空、`AgentService` 非空；那处改动是为过门禁按本仓既有口径做的最小豁免，不是行为变更，详见 `tasks.md` T017 与验收报告 §一·5。）**
4. **不新增对外概念**：本节不新增顶层 public 类型（`AgentDir` 是 `ProfileLoader` 的嵌套 record）、不新增配置键、不新增 REST 路径、不新增 CLI 命令。
5. **无持久化副作用**：`register` / `remove` / `registerProfile` 都不写 `scheduled_tasks` 以外的表；`scheduled_tasks` 的写入仍走第 28 节既有 `ScheduledTaskStore`。
