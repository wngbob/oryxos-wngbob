# Phase 1 Data Model: 插件化 Agent

**Feature**: `specs/012-plugin-agent` | **Date**: 2026-10-21

本节**不新增任何持久化实体**：不建表、不加列、不改 `schema.sql`。数据模型只涉及三样**内存对象**与一个**磁盘目录契约**。

---

## 1. Agent 目录（磁盘契约，非数据库实体）

**身份规则**：一个目录 = 一个 Agent；**目录名即 Agent 名**，是唯一真相源。`frontmatter` 里不重复声明 `name`（写了也被忽略，见 §2）。

**位置**：`.oryxos/agents/<name>/`（运行期）；仓库内的示例在 `examples/agents/<name>/`。

**目录内条目**：

| 条目 | 必需 | 形态 | 谁消费 | 何时进上下文 |
|---|---|---|---|---|
| `AGENT.md` | **必需** | 普通文件；`---` 包裹的 frontmatter + 正文 | `ProfileLoader` 派生 Profile；`ContextLoader` 注入正文 | 触发即常驻 system prompt |
| `REFERENCE.md` | 可选 | 普通文件 | 模型经 `read_file` | 需要时（不预载） |
| `skills/<link>` | 可选 | **指向 `.oryxos/skills/<entity>/` 的相对软连接** | `ContextLoader` 只注入 name / description / 路径 | 只进摘要，正文不预载 |
| `scripts/<file>` | 可选 | 普通文件 | 模型经 `shell`（解释器必须在管理员白名单内） | 只进脚本产出，代码不进 |
| 其它文件 | 可选 | 任意 | 无 | 不进 |

**校验规则**：

- `AGENT.md` 缺失 / frontmatter 未闭合 / 不是合法 YAML / 顶层不是映射 → 该 Agent 判无效（点名 Agent + 原因），**不阻断其它 Agent**。
- `skills/` 下的条目必须是软连接且解析后落在公共 Skill 根内；悬空或越界 → `ContextLoader` 抛 `IllegalStateException`（第 17 节既有行为，本节不改）。
- 正文可以为空（只有 frontmatter）——Agent 仍有效，prompt 里只是没有正文段。

---

## 2. `ProfileLoader.AgentDir`（新增，公开嵌套 record）

`inspect(Path agentDir)` 的产物：一个 Agent 目录的**结构快照**。不校验语义、不派生 `Profile`。

| 字段 | 类型 | 来源 | 说明 |
|---|---|---|---|
| `name` | `String` | `agentDir.getFileName().toString()` | 目录名即 Agent 名 |
| `path` | `Path` | 入参 | 目录本身 |
| `frontmatter` | `Map<String, Object>` | `AGENT.md` 首个 `---` 块 | 已按 `String` 键归一化；空 frontmatter 为 `Map.of()` |
| `body` | `String` | `AGENT.md` 收尾 `---` 之后的全部文本 | 空正文为 `""` |
| `resources` | `List<String>` | `scripts/` 直接子文件 + `skills/` 直接子项 + `REFERENCE.md`（若存在） | **相对路径**，按字典序排序；不递归 |

**不变式**：

1. `resources` 中的每一项都是相对 `path` 的路径，MUST NOT 是绝对路径。
2. `resources` 只反映**存在性**，MUST NOT 读取被列出的文件内容（性能与「不预载」口径）。
3. `inspect` 失败（缺 `AGENT.md` / IO 错 / YAML 非法）抛 `ProfileLoadException`，与 `deriveProfile` 同一类型。

---

## 3. `Profile`（既有，不新增字段）

`deriveProfile(Path)` 把 `AgentDir.frontmatter` 映射为 `Profile`。映射表（与 `ProfileLoader.toProfile` 逐项对应）：

| frontmatter 键 | `Profile` 字段 | 缺省 | 校验 |
|---|---|---|---|
| （目录名） | `name` | — | 不由 frontmatter 提供 |
| `description` | `description` | `null` | 必须是字符串 |
| `identity.agent_name` | `identity().agentName()` | `null` | — |
| `identity.prompt` | `identity().prompt()` | `null` | — |
| `provider` | `provider()` | **无缺省** | 缺失 → `缺少 provider 段：每个 Agent 必须声明用哪家厂商、哪个模型` |
| `provider.name` | `provider().name()` | **无缺省** | 缺失 → `provider.name 缺失`；不在已知厂商清单 → `provider.name 'X' 不在全局厂商清单中（已声明: [...]）` |
| `provider.model` | `provider().model()` | **无缺省** | 缺失 → `provider.model 缺失` |
| `provider.temperature` | `provider().temperature()` | `null` | 必须是数字 |
| `tools` | `tools()` | `List.of()` | 每项必须是字符串；**不在加载期校验是否已注册**（运行期 `ToolRegistry.availableFor` 告警，spec 裁决 7） |
| `mcp_servers` | `mcpServers()` | `List.of()` | 同上 |
| `channels` | `channels()` | `List.of()` | 每项必须是键值映射 |
| `schedules` | `schedules()` | `List.of()` | 每项必须是 `{cron, zone, message}` 映射；**原样带入，不校验 cron / 时区**（非法规则在 `AgentScheduler` 注册时逐条报错） |
| `bootstrap` | `bootstrap()` | `List.of()` | 每项必须是字符串 |
| `settings.max_iterations` | `settings().maxIterations()` | `10` | 必须是整数 |
| `settings.max_history_turns` | `settings().maxHistoryTurns()` | `20` | 必须是整数 |
| `skills` | — | — | **禁止键**：`frontmatter 不允许声明 'skills'：Skill 归属由软连接表达、通知渠道归全局注册表` |
| `notify_channels` | — | — | **禁止键**，同上 |
| `name` | — | — | 被忽略（目录名即真相）；写了不报错 |

**字符串解析**：所有字符串值（含列表项）都会把 `${VAR}` 替换为环境变量值；形状合法但变量未设置 → `环境变量 VAR 未设置，占位 ${VAR} 无法解析（报错不回显凭证值）`。

---

## 4. `ProfileRegistry`（既有类，改为可变并发索引）

| 成员 | 签名 | 语义 |
|---|---|---|
| `register` | `void register(Profile profile)` | 按 `profile.name()` 写入；同名**覆盖**（后写者胜） |
| `remove` | `boolean remove(String agentName)` | 移除并返回是否真的存在 |
| `exists` | `boolean exists(String agentName)` | — |
| `find` | `Optional<Profile> find(String agentName)` | 既有，语义不变 |
| `all` | `List<Profile> all()` | 既有；改为**按 Agent 名排序**的不可变快照 |
| `names` | `Set<String> names()` | 既有；同上 |

**并发不变式**：

1. 索引底层是 `ConcurrentHashMap`，读路径（`find`）不加锁。
2. `all()` / `names()` 返回快照，调用方改动不影响索引。
3. `register(null)` / `remove(null)` / `exists(null)` → `NullPointerException`（与 `Objects.requireNonNull` 口径一致）。

**不持久化**：索引内容全部由磁盘目录派生，重启时 `CliAssemblyConfiguration#profileRegistry` 重新 `loadAll()` 重建。

---

## 5. `AgentScheduler.scheduledTasks`（新增，句柄表）

| 成员 | 类型 | 语义 |
|---|---|---|
| `scheduledTasks` | `Map<String, ScheduledFuture<?>>` | 任务 id → 调度句柄；**private final `ConcurrentHashMap`** |
| `registerProfile` | `int registerProfile(Profile profile)` | 先取消该 Agent 的既有句柄，再逐条注册 `schedules`；**返回新注册成功的任务数**（`registerAll` 求和得「共 N 条」） |
| 包级测试接缝 | `Map<String, ScheduledFuture<?>> scheduledTaskHandles()` | 返回不可变副本；**包级私有，不进对外概念** |

**键**：`<Agent 名>#<序号>`（序号从 1 起，按 `Profile.schedules()` 顺序）——与第 25 节 FR-013 完全一致，本节不改 id 规则。

**替换语义**（spec 裁决 5）：

1. 取出**属于该 Agent** 的句柄——判据是 `RegisteredTask.profile().name()` 与 `profile.name()` **精确相等**（**MUST NOT 用 `<Agent>#` 名字前缀**：`#` 在目录名里合法，`a` / `a#b` 并存时前缀匹配会误取消 `a#b#1`）；
2. 逐个 `cancel(false)`，并从 `scheduledTasks` 与 `registeredTasks` 移除；
3. 按新 `schedules` 重新注册，新句柄写入 `scheduledTasks`。

**（2026-10-21 修订：第 1 步原写「以 `profile.name() + "#"` 开头的键」，实现已改为精确相等，见验收报告 §5.1。）**

**与 `taskLocks` 的分工**：

| 表 | 维度 | 生命周期 | 用途 |
|---|---|---|---|
| `taskLocks` | 执行期互斥 | 只增不减（上界 = 任务总数） | 「上一次还没跑完就跳过」 |
| `scheduledTasks` | 调度生命周期 | 可增可减（替换时移除） | 第 30 节的注销 / 更新 |

两者**并存且互不替代**：锁管「同一时刻不跑两份」，句柄管「这条规则还在不在」。

**失败隔离**：单条规则注册失败（cron 段数 / 内容非法、时区非法、持久层写失败）→ ERROR 指名 Agent 与任务后跳过；同 Profile 的其余规则与其它 Agent 照常。既有句柄在替换开始时已被取消——**失败的那条不会留下旧句柄**（宁可没有任务，不可留着旧规则继续触发）。

---

## 6. 示例 Agent（`examples/agents/daily-reconcile/`）

| 文件 | 内容要点 |
|---|---|
| `AGENT.md` | frontmatter：`description` / `identity` / `provider` / `tools: [shell, read_file, notify, save_memory]` / `schedules`（一条，6 段 cron，`Asia/Shanghai`）；正文 = 四步任务指令，**脚本路径写相对工作区根**（`.oryxos/agents/daily-reconcile/scripts/reconcile.py`，见契约 §7 的路径口径） |
| `scripts/reconcile.py` | 课件原码：读两个 CSV 环境变量、按单号比对条数与金额、输出差异 JSON；缺变量时输出说明性 JSON 并退出 0 |
| `skills/report-format` | 相对软连接 → `../../../skills/report-format` |
| `REFERENCE.md` | 字段字典 + 已知可接受差异 + 升级联系人 |
| `examples/skills/report-format/SKILL.md` | frontmatter `name` / `description` + 正文（分级规则 + 报告结构），正文不预载 |

**与课件的三处偏差**（spec 裁决 3，验收报告逐条留痕）：

1. 删除 `notify_channels:` 块——通知渠道归 SQLite 全局注册表，frontmatter 声明会**加载报错**；正文按名引用渠道。
2. 删除 `schedules[].id`——`Profile.ScheduleSpec` 只有 `cron` / `zone` / `message`，任务 id 由 `<Agent>#<序号>` 派生。
3. 删除顶层 `name:`——Profile 名取自目录名。

**另两处措辞偏差**：

1. 正文与说明中的 `python` 写成 `python3`，与白名单取值一致（D7）。
2. 正文中的脚本路径写成**相对工作区根**（`.oryxos/agents/daily-reconcile/scripts/reconcile.py`），不是课件的 `scripts/reconcile.py`——`shell` 的工作目录是 JVM 进程 cwd（= 工作区根），不写全路径永远解析不到（D11）。
