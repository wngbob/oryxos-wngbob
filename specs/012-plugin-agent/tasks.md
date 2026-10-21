# Tasks: 插件化 Agent —— 一个目录定义一个会自己跑的 Agent

**Feature**: `specs/012-plugin-agent` | **Branch**: `class-29` | **Date**: 2026-10-21

**Input**: [spec.md](./spec.md) / [plan.md](./plan.md) / [research.md](./research.md) / [data-model.md](./data-model.md) / [contracts/](./contracts/) / [quickstart.md](./quickstart.md)

**Tests**: 课件「验收 harness」明列 6 个测试类，**全部必须交付**（不是可选）。测试方法名用英文，课件原名进 `@DisplayName`（lesson-dev 第 6 步）。

**Organization**: 按 user story 分阶段；US1 / US2 是 P1，US3 / US4 是 P2。

---

## Phase 1: Setup

- [X] T001 在 `oryxos-boot/src/main/resources/application.yaml` 的 `shell.allowed_commands` 既有列表末尾追加 `python3`（**既有键、只加取值**，MUST NOT 新增配置键；旁边各一行注释说明：章程原则 IV 要求解释器仅在管理员显式白名单内放行，本机无 `python`）

---

## Phase 2: Foundational（阻塞所有 user story）

**目的**：让「启动扫描」与「运行时派生」共用同一段解析与校验代码——这是 FR-004 / SC-003「同一异常类型 + 同一消息」的结构前提。

- [X] T002 把 `oryxos-core/src/main/java/io/oryxlabs/oryxos/core/profile/ProfileLoader.java` 中 `ProfileLoadException` 由 private 提为 `public static final class`（**仅可见性变更**：类名、消息文本、抛出点全部保持第 16 节现状；`final` 规避 FindSecBugs `CT_CONSTRUCTOR_THROW`，javadoc 写明理由）
- [X] T003 在 `ProfileLoader.java` 抽出共享内部方法 `readAgentMarkdown(Path agentDir) → (Map<String,Object> frontmatter, String body)`，`loadOne` 与后续 `inspect` / `deriveProfile` 共用；既有报错文本（`AGENT.md 缺失` / `frontmatter 未闭合` / YAML 非法等）**一字不改**，`loadAllDetailed` 的行为与 `Failure.reason` 不变
- [X] T020 [P] 新增 `oryxos-cli/src/test/java/io/oryxlabs/oryxos/cli/ShellWhitelistSourceTest.java`（ID 为复核追加，**与 T001 配套、执行顺序紧随 T001**）：读 `oryxos-boot/src/main/resources/application.yaml` 源文件，断言 `shell.allowed_commands` 列表**含 `python3`**（体例照 `LogStreamSeparationTest` 的源文件判据——`ToolAssemblyTest` 自己注入属性源，守不了「配置块被整体删掉」；FR-010 必须有机检）

---

## Phase 3: User Story 1 - 丢一个目录就是一个 Agent（Priority: P1）

**Goal**: `.oryxos/agents/<name>/` 被扫描、解析、派生为 `Profile` 并注册进索引；坏配置点名报错、不阻断其它 Agent。附交付示例 Agent 目录。

**Independent Test**: 临时工作区放两个 Agent 目录（一个合法、一个缺 `provider`），断言索引里恰好一个合法项、失败项被点名且不影响另一个。

- [X] T004 [P] [US1] 新增 `oryxos-core/src/test/java/io/oryxlabs/oryxos/core/profile/AgentLoaderTest.java`：`inspect(Path)` 拆出 frontmatter / 正文；`resources` 恰好为 `REFERENCE.md`、`scripts/reconcile.py`、`skills/report-format`（字典序、相对路径、不递归）；失败断言**分两层**（消息本身不含 Agent 名，MUST NOT 往异常消息里拼名字——那会破坏 SC-003「逐字相同」）——① 扫描路径：`loadAllDetailed().failures` 的 `Failure.agentName` 就是目录名、`Failure.reason` 覆盖缺 `AGENT.md` / 缺 `provider` / 厂商未知三种原因；② 运行时路径：`deriveProfile(dir)` 抛 `ProfileLoadException`，消息**逐字等于**同一份 `Failure.reason`（方法名英文 + `@DisplayName` 保留课件原文）
- [X] T005 [US1] 在 `ProfileLoader.java` 实现 `public ProfileLoader.AgentDir inspect(Path agentDir)`、`public Profile deriveProfile(Path agentDir)` 与嵌套 `record AgentDir(String name, Path path, Map<String,Object> frontmatter, String body, List<String> resources)`：`name` 取 `getFileName()`（为 null 先 `requireNonNull` 给出可读消息）；`frontmatter` 返回不可变副本；`resources` 只枚举存在性、不读内容；`deriveProfile` 走既有 `toProfile`（同一套校验、同一消息）
- [X] T006 [P] [US1] 新增 `oryxos-core/src/test/java/io/oryxlabs/oryxos/core/profile/DeriveProfileTest.java`：frontmatter 各字段映射到 `Profile`（`description` / `identity` / `provider` / `tools` / `mcp_servers` / `channels` / `bootstrap` / `settings`）；**`schedules` 原样带进派生 Profile**（内容与顺序不变）；`${ENV_VAR}` 解析与非法配置消息与 `loadAllDetailed` 逐字一致
- [X] T007 [P] [US1] 新增 `examples/skills/report-format/SKILL.md`（公共 Skill 实体；frontmatter `name` / `description`，正文为分级规则 + 报告结构，正文**不预载**）
- [X] T008 [P] [US1] 新增 `examples/agents/daily-reconcile/scripts/reconcile.py`（课件原码：读 `RECON_ORDERS_CSV` / `RECON_SETTLE_CSV` 两个环境变量，按单号比对条数与金额，输出差异 JSON；缺变量时输出说明性 JSON 并退出 0；只用标准库、不联网）
- [X] T009 [US1] 新增 `examples/agents/daily-reconcile/AGENT.md`（frontmatter：`description` / `identity` / `provider` / `tools: [shell, read_file, notify, save_memory]` / `schedules` 一条 6 段 cron `Asia/Shanghai`；**MUST NOT 出现 `notify_channels` / `schedules[].id` / 顶层 `name`**；正文四步：跑脚本（路径写**相对工作区根** `.oryxos/agents/daily-reconcile/scripts/reconcile.py`，见 contracts §7）、判断、按注入路径读子指令写报告、推送 + `save_memory`），新增 `examples/agents/daily-reconcile/REFERENCE.md`（字段字典 + 可接受差异 + 升级联系人），并创建**相对软连接** `examples/agents/daily-reconcile/skills/report-format -> ../../../skills/report-format`（用 `ln -s`，MUST NOT 用普通文件/绝对路径）

---

## Phase 4: User Story 2 - 声明了时间，它到点自己跑（Priority: P1）

**Goal**: `AgentScheduler.registerProfile(Profile)` 注册单 Agent 的定时规则并维护 `ScheduledFuture` 句柄表；重复注册是替换而非翻倍。

**Independent Test**: mock 引擎与持久层，对一个带两条 `schedules` 的 Profile 调 `registerProfile`，断言句柄 2 个；再调一次，断言旧句柄全部 `cancel`、句柄数仍 2 个。

- [X] T010 [P] [US2] 新增 `oryxos-core/src/test/java/io/oryxlabs/oryxos/core/schedule/AgentSchedulerRegisterTest.java`：`registerProfile` 后 `scheduledTaskHandles()` 有对应任务 id 的句柄；cron / 时区来自 `Profile.schedules`（`CapturingTaskScheduler` 捕获 `CronTrigger` 断言 zone 显式传入）；重复注册 → 旧句柄 `isCancelled()` 为真、句柄数不翻倍；单条坏 cron 被跳过并指名、其余照常；`registerProfile` 返回值为新注册成功数（空 schedules → 0）
- [X] T011 [US2] 在 `oryxos-core/src/main/java/io/oryxlabs/oryxos/core/schedule/AgentScheduler.java` 实现 `public int registerProfile(Profile profile)`：先按 **Agent 归属**（`RegisteredTask.profile().name()` 与 `profile.name()` 精确相等，**MUST NOT 用前缀**）取消既有句柄（`cancel(false)`，并从 `scheduledTasks` 与 `registeredTasks` 移除），再逐条注册 `schedules`（`normalizeCron` → `resolveZone` → `nextRunAt` → `taskStore.register` → `taskScheduler.schedule`），句柄写入 `private final Map<String, ScheduledFuture<?>> scheduledTasks`；`registerAll()` 改为循环求和并照旧打印「定时任务注册完成：共 N 条」；新增包级私有 `scheduledTaskHandles()` 返回不可变副本；单条失败隔离沿用既有 ERROR 指名路径（**不吞异常**）**（2026-10-21 修订：本任务原文写「先按前缀 `profile.name() + "#"` 取消既有句柄」，实现已按上面改后的精确相等判定，并补回归测试 `AgentSchedulerRegisterTest.registerProfile_doesNotCancelAgentWhoseNameIsPrefixOfAnother`；见验收报告 §5.1）**
- [X] T012 [P] [US2] 新增 `oryxos-core/src/test/java/io/oryxlabs/oryxos/core/schedule/AgentScanRegisterTest.java`：临时 `.oryxos/agents/` 放 N（≥3）个目录（含一个带两条 `schedules`、一个不带、一个缺 `provider`）→ `loadAllDetailed` + `ProfileRegistry` 得到 N-1 个、失败项被点名；带 `schedules` 的经 `registerAll()` 后 `scheduledTaskHandles()` 恰好含其任务 id，不带的无任何句柄

---

## Phase 5: User Story 3 - 资源按需加载，正文即时生效（Priority: P2）

**Goal**: 正文常驻 system prompt；`REFERENCE.md` / Skill 正文 / 脚本代码不预载；Skill 只注入 name / description / 绝对路径。

**Independent Test**: 组装一次 prompt，断言正文在、参考与脚本内容不在；绑定 Skill 时断言只出现摘要。

- [X] T013 [P] [US3] 新增 `oryxos-core/src/test/java/io/oryxlabs/oryxos/core/context/ProgressiveDisclosureTest.java`：造临时工作区（`ContextLoader` 真实实例）组装 prompt——`AGENT.md` 正文在 system prompt；**不出现** `REFERENCE.md` 正文、**不出现** `scripts/*.py` 的任何代码行；Skill 只出现 name / description / 绝对路径；文件系统不支持软连接时 `assumeTrue` 显式跳过并在输出可见。**无缓存子项不重测**：SC-004 的「改正文后下一次组装即读到新内容」由第 17 节 `ContextLoaderTest.rereadsFileOnEveryLoad` 覆盖（research D10，课件明确不重测），在类 javadoc / `@DisplayName` 注明出处。**（2026-10-21 复核：那条用例读的是 Bootstrap `AGENTS.md`，不覆盖 `AGENT.md` 正文；已在 `ContextLoaderTest` 补 `rereadsAgentBodyOnEveryLoad`，见验收报告 §5.8。）****MUST NOT 修改 `ContextLoader`**——测试失败只能修示例目录或测试夹具

---

## Phase 6: User Story 4 - 运行时注册就位（Priority: P2）

**Goal**: `ProfileRegistry` 由不可变快照改为可变并发索引；运行时派生的失败与启动路径同一异常类型 + 同一消息。

**Independent Test**: 空索引 `register` → 立即 `find` 命中 → `remove` → `exists` 为假；同一份坏配置分别走 `loadAllDetailed` 与 `deriveProfile`，异常类型与消息逐字相同。

- [X] T014 [P] [US4] 新增 `oryxos-core/src/test/java/io/oryxlabs/oryxos/core/profile/ProfileRegistryRuntimeTest.java`：`register` 后立即可见、`remove` 后消失且返回 `true`、不存在时 `remove` 返回 `false`、`exists` 判定；同名覆盖；`all()` / `names()` 按名排序且为不可变快照；`null` 入参抛 `NullPointerException`；**同一份坏配置经 `loadAllDetailed` 与 `deriveProfile` 的失败异常类型与消息逐字相同**（FR-004 / SC-003）
- [X] T015 [US4] 在 `oryxos-core/src/main/java/io/oryxlabs/oryxos/core/profile/ProfileRegistry.java` 实现 `register(Profile)` / `remove(String)` / `exists(String)`：底层 `ConcurrentHashMap`，保留 `ProfileRegistry(List<Profile>)` 构造器并改为逐条 `register`；`find` 语义不变；`all()` / `names()` 返回**按 Agent 名排序**的不可变快照（`TreeMap`/`TreeSet` 包 `Collections.unmodifiable*`，**MUST NOT 用 `Set.copyOf`**）；javadoc 写明不持久化、重启由装配处重建

---

## Phase 7: Polish & Cross-Cutting

- [X] T016 全量门禁：`./mvnw -o clean verify` 全绿（Spotless / P3C / Checkstyle / SpotBugs+FindSecBugs / OWASP）；红了当场修实现，**MUST NOT** 删断言 / `@Disabled` / 放宽阈值
- [X] T017 不变式自查：`git diff --stat` 对 `AgentService` / `ReActLoop` / `ToolExecutor` / `ContextLoader` / `ShellTools` / `WhitelistSandbox` 六个文件为空（FR-011）；`grep` 确认未新增顶层 public 类型（`AgentDir` 为嵌套 record）、未新增配置键 / REST 路径 / CLI 命令；`grep -rn "AgentLoader" oryxos-*/src/main` 零命中（**只查 main 源码**——测试类 `AgentLoaderTest` 自身含该串，查全仓的判据自相矛盾）；另 `grep -rn "class AgentLoader" oryxos-*/src` 零命中。**实测偏差（如实记录）**：`git diff --stat` 五个文件为空，`AgentService` 非空（`+6` 行：一行 `import` + 五行为构造器上的 `@SuppressFBWarnings("EI_EXPOSE_REP2")`），**行为零改动**——那是在 SpotBugs 门禁（不可绕过的硬门禁）下按本仓既有惯例（就地注解优于全局 exclude，先例 `ProfileApiController` / `PromptBuilder`）加的最小豁免，理由写在注解上；`grep -rnE "class AgentLoader([^A-Za-z0-9_]|$)"` 零命中（无词边界时会误命中测试类 `AgentLoaderTest`，那正是 T017 原文警告的那类自相矛盾判据）
- [X] T018 运行 [quickstart.md](./quickstart.md) §1（过滤测试）与 §2~§3（CLI + Web 可见性）做证据采集；记录 §6 为剩余人工项（真模型链路）
- [X] T019 更新 `CLAUDE.md`「当前状态」：第 29 节交付摘要、已知边界（脚本信任边界 / 丢目录可见不可调 / 脚本 cwd）、挂账变化；随后写 `specs/012-plugin-agent/acceptance-report.md`（六项证据 DoD）并在对话中完整输出

---

## Dependencies

```text
T001 ─┐
T002 ─┼─→ T005 ─→ T006
T003 ─┘        └─→ T009（示例目录，独立可先做）
T004 ──────────→ T005（harness 先行）
T007, T008 ──→ T009
T010 ─→ T011 ─→ T012
T013（仅测试，**完全独立**：自造临时 fixture，不依赖 T001 / T009）
T014 ─→ T015
T001 ─→ T020（源文件判据钉住白名单取值）
全部 ─→ T016 ─→ T017 ─→ T018 ─→ T019
```

- **T002 / T003 阻塞 US1 与 US4**（同一异常类型 / 同一段解析）
- **US2 与 US1 无强依赖**，可并行；`AgentScanRegisterTest`（T012）需要 T011 的句柄表
- **US3 无 main 代码改动**，可与任何阶段并行
- **T001 与 US3 的人工观察**相关（脚本可执行），与 T009 的正文写法配合

## Parallel Execution Examples

- **US1 并行组**：T004（测试）/ T007（SKILL.md）/ T008（脚本）三文件互不相干，可同时进行；T009 汇总
- **US1 ∥ US2**：T005+T006 与 T010+T011 触碰不同文件（`profile/` vs `schedule/`）
- **US3 ∥ US4**：`context/` 测试类与 `profile/ProfileRegistry.java` 无交集

## Implementation Strategy

- **MVP = US1 + US2**（两条 P1）：「一个目录变成一个 Agent」+「到点自己跑」构成课件标题的完整机制；US3 / US4 是 P2，但不做 US4 则第 30 节的运行时 API 没有地基
- **增量顺序**：T001~T003（地基）→ US1（目录可用）→ US2（会自己跑）→ US4（可运行时注册）→ US3（披露回归）→ Polish
- **harness 先行**：每个故事先落测试类再落实现（T004→T005、T010→T011、T014→T015）
- **禁止**：删断言、`@Disabled`、放宽阈值、为过测试改 `ContextLoader` / `ShellTools` / `WhitelistSandbox`
