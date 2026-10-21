# Feature Specification: 插件化 Agent —— 一个目录定义一个会自己跑的 Agent

**Feature Directory**: `specs/012-plugin-agent`

**Git Branch**: `class-29`

**Created**: 2026-10-21

**Status**: Draft

**Input**: User description: "第29节需求：插件化 Agent——一个目录定义一个会自己跑的 Agent"（课件：`docs/class/第29节：插件化 Agent 一个目录定义一个会自己跑的 Agent.md`；章节依据 `docs/TechnicalSolution.md` §11.1 ~ §11.2；章程依据原则 IV「Agent 目录不是 Tool」）

## Clarifications

### Session 2026-10-21

本节开工前（H0 依赖核对阶段）有四处冲突触发了软门禁（两处课件 ↔ 本仓既定契约、一处课件 ↔ 技术方案、一处超出课件交付物清单），已全部报用户裁决。四条裁决是后续 plan / tasks / 实现的硬约束。

- Q: 示例 Agent 的「子指令」放哪、什么形态？课件 §1.3 写 `skills/report-format.md` 是 Agent 目录内的**普通文件**；但本仓章程原则 IV / 技术方案 §8.3 / 既有 `ContextLoader` 把 `skills/<name>` 定义为**指向 `.oryxos/skills/` 公共实体的相对软连接**，并校验目标必须落在公共 Skill 根内。普通文件会被判「越界」直接抛异常，**该 Agent 根本加载不了**。 → A: **公共实体 + 软连接**。公共实体落 `.oryxos/skills/report-format/SKILL.md`，Agent 内 `skills/report-format` 作相对软连接；不改 `ContextLoader` 一行，不修订章程原则 IV。示例目录的四部分变成：`AGENT.md` + `skills/report-format`（软连接）+ `scripts/reconcile.py` + `REFERENCE.md`。

- Q: 「限定只能跑本 Agent 的 `scripts/` 目录」做不做？课件的「本节交付物·代码」清单里**没有列**这一项；而当前 `SandboxAction` 只携带 `target=argv[0]`，`WhitelistSandbox` 看不到完整命令、也不知道当前是哪个 Agent。要落地须改第 24 节交付的沙箱契约或第 20 节交付的 `ShellTools`（软门禁 ④）。 → A: **只放行解释器，记已知边界**。`application.yaml` 的 `shell.allowed_commands` 加 `python3`，使示例脚本可经 `shell` 工具运行；「脚本限定在 Agent 自己的 `scripts/` 目录」记为本节**已知边界**，归扩展阶段的「Agent 沙箱 / 容器隔离」一并解决。依据：章程原则 IV 原文只要求「解释器 MUST 在管理员显式白名单内放行」。

- Q: 课件示例 `AGENT.md` 里三处 frontmatter 与本仓契约冲突，怎么处理？① `notify_channels:` 被 `ProfileLoader.FORBIDDEN_KEYS` 明令禁止（通知渠道归 SQLite 全局注册表，技术方案 §8.2 同口径），原样落地会**加载报错**；② `schedules[].id` 不存在于 `Profile.ScheduleSpec(cron, zone, message)`，任务 id 由 `<Agent>#<序号>` 确定性派生（第 25 节 FR-013）；③ `name:` 被忽略，Profile 名取自目录名。 → A: **按本仓口径修正**——去掉 `notify_channels:`（正文按名引用全局渠道）、去掉 `schedules[].id`、去掉 `name:`；在验收报告里逐条记录与课件的偏差。

- Q: 课件把 `AgentLoader` 列为交付物，但本仓的同类是 `ProfileLoader`（第 16 节交付，且技术方案 §8.2 仍写 `AgentLoader`）。是否新增一个 `AgentLoader` 类？ → A: **不新增**。本仓以 `ProfileLoader` 为课件 `AgentLoader` 的落点（它已经完成「扫目录 → 解析 frontmatter → 派生 Profile」），本节只在它上面补一个**单目录派生入口** `deriveProfile(Path)`，让启动扫描与运行时注册共用同一段校验。新增同名类会造出第二个真相源，正是 `AgentResolver` javadoc 警告过的形态。测试类名仍按课件落地（`AgentLoaderTest` 测 `ProfileLoader` 的单目录派生）。

- Q: 示例 Agent 目录放仓库的哪个位置？ `.oryxos/` 已被 `.gitignore` 忽略，放那里根本进不了版本库。 → A: **`examples/agents/daily-reconcile/`**（仓库根新增 `examples/`）。示例是给使用者复制的模板、不属于任何工作区；验收向导用 `cp -r` 复制到 `.oryxos/agents/`，公共 Skill 实体一并复制到 `.oryxos/skills/`。

- Q: 同一个 Agent 被重复注册定时任务时，语义是什么？ → A: **先注销再重注册（替换）**。按该 Agent 的既有任务 id 逐个 `cancel()` 句柄表里的 `ScheduledFuture`，再按新 Profile 重新注册。这是第 30 节「更新 `schedules` 先注销旧句柄再注册新的」的结构前提，也避免同一任务被触发两次。

- Q: `tools` 引用未注册能力时的告警落在哪一层？ → A: **沿用运行期 `ToolRegistry.availableFor` 的 WARN**。`availableFor` 已经对「声明了但注册表里没有」的工具名打 WARN + 跳过；加载期再校验需要给 `ProfileLoader` 注入已知工具集合、改第 16 节构造器签名，且 `oryxos-core` 会因此认识「工具如何装配」。不改。

## 范围说明

底座（第 16 ~ 28 节）已经造齐：Provider、ReAct、内置 Tool、Memory、Sandbox、定时、Web。但到目前为止，「定义一个业务 Agent」还没有标准机制——指令要靠配置、装配要靠手工。**内核不等于 OS**：操作系统的定义不是「能跑一个程序」，而是「一套内核之上，能装、能跑任意多个程序，彼此隔离、不改内核、免重编译」。本节补的正是这套「**扫描 → 派生 Profile → 注册 → 到点自己跑**」的机制。

**贯穿始终一条原则：不重写底座，只给「Profile」添一个来源。** 底座的一切都吃 `Profile` 这个值对象，所以本节一行引擎代码都不动，只是把「Profile 从哪来」标准化为「从 Agent 目录的 `AGENT.md` frontmatter 派生」，走同一套注册与校验。

**已由前序节交付、本节只消费不改动的部分**（依赖核对结论）：

| 课件交付物 | 本仓现状 |
|---|---|
| 扫描 `.oryxos/agents/`、解析 frontmatter、派生 Profile | 第 16 节 `ProfileLoader` |
| `ContextLoader` 注入 Agent 正文（去 frontmatter） | 第 17 节 `ContextLoader.appendAgentBody` |
| 启动扫描并注册进 `ProfileRegistry` | 第 16/26 节装配处 `profileRegistry` Bean |
| 按 Profile 的 `schedules` 注册 cron | 第 25/28 节 `AgentScheduler.registerAll` |
| 渐进式披露的读取能力（`read_file` / `shell`） | 第 20 节内置工具 |

**本节真正新增 / 改造的部分**：

- `ProfileRegistry` 运行时注册（`register` / `remove` / `exists`，可变并发索引）
- `AgentScheduler.registerProfile(Profile)` + `scheduledTasks` 句柄表
- `ProfileLoader.deriveProfile(Path agentDir)` 单目录派生入口（启动扫描与运行时注册共用同一段校验）
- 示例 Agent 目录 `daily-reconcile/` 及其公共 Skill 实体
- 六个验收 harness 测试类
- `shell.allowed_commands` 放行解释器

### 明确不做（边界）

课件「有几样先别做」逐项照搬，另加本节裁决带出的两项：

- Agent **版本管理**
- Agent **市场 / 共享**
- **跨 Agent 的能力复用**——本版每个 Agent 独立自足，不做 `use_skill` / 能力库 / 全局索引
- L3 脚本的**容器 / 网络隔离**——核心阶段只到解释器 + 目录白名单
- Agent **同名冲突策略**
- **文件监听热加载**——归第 30 节
- Agent 运行时 API（`POST /api/v1/agents`）与**一句话生成**——归第 30 节
- **脚本限定在本 Agent 的 `scripts/` 目录**（裁决 2；记为已知边界，须改前序节沙箱契约）
- **「装一个带脚本的 Agent = 信任写它的人」这条信任边界不消除**：脚本是任意代码，`python3 scripts/foo.py` 一旦放行，它能读写文件、能自己发网络请求，绕过 `http_get` 的域名白名单。核心阶段对此**如实记录、不做缓解**，容器 / 网络隔离归扩展阶段

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 丢一个目录就是一个 Agent (Priority: P1)

业务方写一个 `daily-reconcile` 目录：`AGENT.md`（frontmatter 是运行配置、正文是任务指令）、`scripts/reconcile.py`（确定性抓数）、一个子指令、一份 `REFERENCE.md`。把它放进 `.oryxos/agents/`，`oryxos profile list` 与 `GET /api/v1/profiles` 立刻能看到它——全程不写一行 Java、不动一行底座。

**Why this priority**: 这是「Agent OS」这个词的兑现处。没有这条，业务方每加一个 Agent 都要改代码，底座就只是「一个能跑 Agent 的框架」，不是 OS。

**Independent Test**: 在临时工作区放两个 Agent 目录，启动扫描后断言索引里恰好两个、字段映射正确、其中一个写坏的被点名报错而另一个照常可用。

**Acceptance Scenarios**:

1. **Given** 一个合法的 Agent 目录，**When** 执行扫描，**Then** 它出现在已注册清单里，`provider` / `tools` / `schedules` 等字段与 frontmatter 一一对应。
2. **Given** 一个缺少 `provider` 段的 Agent 目录，**When** 执行扫描，**Then** 该 Agent 被判无效，错误消息**点名 Agent 名与原因**，其余 Agent 照常注册。
3. **Given** 一个 `provider.name` 不在已知厂商清单里的 Agent，**When** 执行扫描，**Then** 同上：点名报错、不阻断其它 Agent。

---

### User Story 2 - 声明了时间，它到点自己跑 (Priority: P1)

Agent 的 frontmatter 声明 `schedules`，系统扫到后把每条规则注册进调度器；到点自动触发，走与 CLI / Web **完全相同**的处理链路，会话与审计有账。

**Why this priority**: 「会自己跑」是本节标题的后半句。少了它，Agent 只是一个等人发消息的配置。

**Independent Test**: mock 掉引擎与持久层，扫描一个带 `schedules` 的 Agent，断言调度器收下了规则、注册后留下可注销的句柄；再用固定时钟断言 `next_run_at`。

**Acceptance Scenarios**:

1. **Given** 一个声明了两条 `schedules` 的 Agent，**When** 执行扫描并注册定时，**Then** 两条规则都以 `<Agent>#<序号>` 为 id 注册，`scheduledTasks` 里各有一个句柄。
2. **Given** 已注册的任务，**When** 再次注册同一 Profile，**Then** 该 Agent 的旧句柄先被取消、再按新配置重新注册，句柄数量不翻倍。
3. **Given** 一个 cron 非法的规则，**When** 注册，**Then** 该条规则被跳过并点名 Agent 与任务，同 Profile 的其余规则与其它 Agent 照常注册。

---

### User Story 3 - 资源按需加载，正文即时生效 (Priority: P2)

Agent 运行时，**正文**常驻 system prompt；`REFERENCE.md`、子指令、脚本**不预载**，由模型按正文指引用底座既有的 `read_file` / `shell` 按需取。脚本的产出进上下文、代码不进。改 Agent 目录里的正文不重启，下一次触发即生效。

**Why this priority**: 这是「渐进式披露」的实质——不一次性把整个目录塞进上下文，Agent 的上下文预算才不会被参考文档吃光。正文即时生效则保证业务方能当场调行为。

**Independent Test**: 组装一次 prompt，断言正文在、参考与脚本内容不在；再改一次正文文件，断言下一次组装读到新内容。

**Acceptance Scenarios**:

1. **Given** 一个含 `REFERENCE.md` 与 `scripts/` 的 Agent，**When** 组装 system prompt，**Then** 正文在里面，参考文件的正文与脚本代码**都不在**。
2. **Given** 已加载的 Agent，**When** 修改 `AGENT.md` 正文后再次组装，**Then** 读到新内容（无缓存）。
3. **Given** 一个绑定公共 Skill 的 Agent，**When** 组装 prompt，**Then** 只出现该 Skill 的 name / description / 本地路径，正文不预载。

---

### User Story 4 - 运行时注册就位，为下一节铺路 (Priority: P2)

`ProfileRegistry` 不再是一次性快照：新增 Agent 可以 `register()` 后立即可见、`remove()` 后立即消失、`exists()` 可判定；非法配置的报错与启动路径**完全一致**（同一异常类型 + 同一消息）。`AgentScheduler` 留下任务句柄表，供第 30 节的注销 / 更新使用。

**Why this priority**: 第 30 节的 API 上传与实时监听都走「运行时注册」这段代码。本节不立好，下一节就要一边做新功能一边改地基。

**Independent Test**: 空索引 → `register()` → 立即 `get()` 命中 → `remove()` → `exists()` 为假；再用同一份坏配置分别走「启动扫描」与「运行时注册」，断言异常类型与消息逐字相同。

**Acceptance Scenarios**:

1. **Given** 空索引，**When** `register(profile)`，**Then** `exists(name)` 为真、`find(name)` 命中同一个对象。
2. **Given** 已注册的 Agent，**When** `remove(name)`，**Then** `exists(name)` 为假、`all()` 里不再有它。
3. **Given** 同一份非法配置，**When** 分别经启动扫描与运行时注册，**Then** 两次失败的异常类型与消息完全相同。

### Edge Cases

- Agent 目录里没有 `AGENT.md`、或 frontmatter 缺失 / 未闭合 / 不是合法 YAML → 点名报错，不阻断其它 Agent。
- `AGENT.md` 正文为空（只有 frontmatter）→ Agent 仍有效（正文为空是合法配置），prompt 里只是没有正文段。
- `tools` 里声明了底座未注册的工具名 → **告警**并跳过，不阻断该 Agent 加载（运行期按 Profile 过滤时同样跳过）。
- `skills/` 下的软连接悬空、或指向公共 Skill 根之外 → 报错（这是既有行为，本节不改）。
- 同名 Agent 在索引里被再次注册 → 覆盖为后者（与既有「同名后者覆盖前者」口径一致）。
- `schedules` 为空 / 未声明 → 该 Agent 只参与人推，不注册任何定时。
- 示例脚本在缺少两个 CSV 环境变量时 → 脚本自己输出一段说明性 JSON 并正常退出，不抛异常（保证演示路径在无数据时也能跑通）。

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 MUST 扫描 `.oryxos/agents/` 下的每个子目录，解析 `AGENT.md` 的 frontmatter 与正文，并识别 `scripts/`、`skills/`、`REFERENCE.md` 等资源的存在。
- **FR-002**: 系统 MUST 把 frontmatter 派生成一个 Agent 运行时配置：`name`（取自目录名）、`description`、`identity`、`provider`、`tools`、`mcp_servers`、`channels`、`schedules`、`bootstrap`、`settings` 一一映射；`schedules` MUST 原样带进派生结果。
- **FR-003**: 系统 MUST 在启动扫描后把派生出的配置注册进索引；带 `schedules` 的 MUST 交给调度器注册定时。
- **FR-004**: 索引 MUST 支持运行时 `register` / `remove` / `exists`；`register` 后 MUST 立即可查；非法配置的失败 MUST 与启动路径**完全一致**（同一异常类型 + 同一消息）。
- **FR-005**: 调度器 MUST 提供按单个 Agent 注册的入口，并维护「任务 id → 调度句柄」的表，与既有的任务锁表并存。再次注册同一 Agent 时 MUST **先取消该 Agent 的既有句柄、再按新配置注册**（替换语义，句柄数量不翻倍）。
- **FR-006**: 上下文加载 MUST 注入 Agent 正文（从目录主文件读、去 frontmatter），MUST NOT 缓存，改完下一次组装即生效。
- **FR-007**: 参考文件、子指令、脚本 MUST NOT 预置进 system prompt；模型经底座既有 `read_file` / `shell` 按需取用。绑定公共 Skill 时，prompt 里只出现其 name / description / 本地路径。
- **FR-008**: `AGENT.md` 缺 `provider`（含 `provider.name` / `provider.model`）、或厂商未知时 MUST 加载失败并**点名 Agent 与原因**；`tools` 里引用未注册的能力 MUST 告警但不阻断——告警落在运行期 `ToolRegistry.availableFor`（既有行为），MUST NOT 为它给 `ProfileLoader` 增加已知工具集合参数。
- **FR-009**: 交付一个示例 Agent 目录 `examples/agents/daily-reconcile/`（入库位置），四部分（主文件 / 子指令 / 脚本 / 参考）俱全，作为手动验收的参照物；验收向导负责把它复制进工作区。
- **FR-010**: 系统 MUST 允许（在管理员显式白名单内）放行 `python3` 解释器，使 Agent 的脚本可经 `shell` 工具运行；「只能跑本 Agent 的 `scripts/` 目录」MUST NOT 在本节实现，并且 MUST 作为已知边界记录。
- **FR-011**: 本节 MUST NOT 修改 `AgentService` / `ReActLoop` / `ToolExecutor` / `ContextLoader` / `ShellTools` / `WhitelistSandbox` 的行为；派生出的 Agent 与既有手写路径 MUST 走同一个处理入口、同一套校验。

### Key Entities

- **Agent 目录**：`.oryxos/agents/<name>/`，一个目录就是一个 Agent；目录名是 Agent 名的唯一真相源。
- **Agent 运行时配置（Profile）**：由 `AGENT.md` frontmatter 派生，是底座一切能力的输入；不含 `name` 声明、不含 `notify_channels`。
- **Agent 索引**：已派生配置的内存索引，支持启动期一次性装入与运行期增删。
- **定时任务句柄**：任务 id 到调度句柄的映射，是「注销 / 更新」能力的结构前提。
- **示例 Agent**：`daily-reconcile`，演示「正文常驻 / 子指令按需 / 脚本确定性 / 参考兜底」四部分。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 往 `.oryxos/agents/` 放一个合法目录后，不改任何代码、不重启任何常驻进程，该 Agent 在 Agent 列表（CLI 与 Web 两个入口）中可见。
- **SC-002**: 一个声明了 `schedules` 的 Agent，注册后其每条规则都有一个可注销的调度句柄；规则数量与句柄数量一一对应。
- **SC-003**: 同一份非法配置分别经启动扫描与运行时注册，失败原因逐字相同（异常类型 + 消息），证明「两条来源同规矩」。
- **SC-004**: Agent 正文出现在 system prompt 中，参考文件内容与脚本代码不出现；修改正文后下一次组装即读到新内容。
- **SC-005**: 全量构建门禁（`mvn clean verify` + 静态检查）全绿，前序节测试无回归。

## Assumptions

- 业务方在单机、可信环境部署；脚本的信任边界按章程原则 IV 与课件 §2.4 如实告知，不做隔离。
- `notify_channels` 归 SQLite 全局注册表，Agent 只在正文中按名引用；示例 Agent 的推送渠道需由使用者用 `oryxos notify-channel add` 自行配置。
- `skills/` 下的条目是指向 `.oryxos/skills/` 公共实体的相对软连接（章程原则 IV）；示例 Agent 的子指令以「公共实体 + 软连接」形态交付。
- 任务 id 由 `<Agent>#<序号>` 确定性派生，不在 frontmatter 新增 `id` 字段。
- 示例脚本只用 Python 标准库、不联网、不需要 key；缺少数据文件时输出说明性 JSON 并正常退出。
- 示例 Agent 入库在 `examples/agents/daily-reconcile/`，公共 Skill 实体入库在 `examples/skills/report-format/`；两者由验收向导复制进 `.oryxos/`（该目录已被 `.gitignore` 忽略，不入库）。
- 第 30 节会直接消费本节立好的运行时注册与句柄表；本节不实现任何 HTTP 端点、不做文件监听。
