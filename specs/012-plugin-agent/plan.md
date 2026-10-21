# Implementation Plan: 插件化 Agent —— 一个目录定义一个会自己跑的 Agent

**Branch**: `class-29` | **Date**: 2026-10-21 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/012-plugin-agent/spec.md`

**Note**: 本文件是 `/speckit-plan` 的输出。技术口径以本文件为准；冲突时以 `spec.md` 的 `## Clarifications` 为准（本节开工前 4 条软门禁裁决 + clarify 3 条，均已有用户书面结论）。

---

## Summary

第 16 ~ 28 节把底座造齐了：Provider、ReAct、内置 Tool、Memory、Sandbox、定时、Web。但「定义一个业务 Agent」仍然要改配置、手工拼装——**内核不等于 OS**。本节补的是「**扫描 → 派生 Profile → 注册 → 到点自己跑**」这套标准机制，让「上线一个 Agent」退化成「往 `.oryxos/agents/` 丢一个目录」。

一句话：**三个既有类各加一段能力（`ProfileLoader` 单目录派生 + 目录快照、`ProfileRegistry` 可变索引、`AgentScheduler` 单 Agent 注册 + 句柄表）+ 一个示例 Agent 目录 + 一个解释器白名单 + 六个 harness 测试类。**

三件事值得单独说：

1. **底座一行引擎代码不改**（FR-011）。`AgentService` / `ReActLoop` / `ToolExecutor` / `ContextLoader` / `ShellTools` / `WhitelistSandbox` 的方法体全部不动；`ProfileLoader` 是唯一被改动的 core「装载」类，且改动是**纯增量**（新增两个公开入口 + 把既有校验路径抽出来复用），既有 `loadAll` / `loadAllDetailed` 的判定与消息一字不改。**「目录派生的 Agent 与手写 Profile 走同一套校验」由代码结构保证**：两条路径最终都落到同一个 `toProfile`。
2. **不新增 `AgentLoader` 类**（spec 裁决 4）。课件把 `AgentLoader` 列为交付物，本仓的同类是 `ProfileLoader`；新增同名类会造出第二真相源。课件 `AgentLoaderTest` 测的是「目录 → frontmatter/正文/资源路径」这条解析链，落点仍是 `ProfileLoader`。
3. **`ContextLoader` 注入 Agent 正文、启动扫描装配、`schedules` 透传三件事前序节已交付**，本节只补测试与示例，不重写。真正的新代码是运行时注册与句柄表——它们是第 30 节「API 新增 Agent / 注销 / 更新」的结构前提。

---

## Technical Context

**Language/Version**: JDK 21 + Spring Boot 3.x + Spring AI Alibaba（动手前先跑 `mvn dependency:tree` 确认锁定 BOM 里目标依赖存在）、SQLite + Spring Data JPA。凭证走环境变量占位，不落明文。SQLite 用手工建表脚本，不依赖 `hibernate.ddl-auto=update`。

> 本节**不新增任何第三方依赖**。`mvn -o -pl oryxos-core dependency:tree` 已确认：需要的新 API 全部来自 JDK 标准库（`java.util.concurrent.ScheduledFuture`、`java.util.concurrent.ConcurrentHashMap`、`java.nio.file`）与已在 compile classpath 上的 `org.springframework:spring-context`（`TaskScheduler`）。两个模块的 `pom.xml` 一行不改。

**Primary Dependencies**（全部已在本仓）：

- JDK 21：`Path` / `Files` / `Stream` / `ConcurrentHashMap` / `ScheduledFuture` —— `ProfileLoader` 的目录扫描、`ProfileRegistry` 的并发索引、`AgentScheduler` 的句柄表
- `org.springframework:spring-context`（经 `spring-boot-starter` 传递，compile）—— `TaskScheduler#schedule` 的返回值类型 `ScheduledFuture<?>`
- `org.springframework.boot:spring-boot-starter-test`（test）—— JUnit 5 / AssertJ / Mockito（`ProfileLoaderTest` / `AgentSchedulerTest` 同款）
- **不引入**：文件监听库（WatchService 属第 30 节）、YAML 之外的新解析器、任何 Agent 版本 / 市场相关依赖

**Storage**: **本节不碰任何存储**。不新增表、不新增列、不改 `schema.sql`；`Profile` 是磁盘目录的派生值，重建成本为零（章程原则 VIII「实例自身 MUST NOT 持有不可重建的状态」）。定时任务的运行状态仍由第 28 节的 `scheduled_tasks` / `task_executions` 承载，本节一行不改。

**Testing**:

- `AgentLoaderTest`（`oryxos-core`，纯单测）—— 课件 harness 第 1 行：frontmatter 与正文拆分、认出 `scripts/` / `skills/` / `REFERENCE.md`、缺 `provider` 报错点名
- `DeriveProfileTest`（`oryxos-core`，纯单测）—— 课件 harness 第 2 行：frontmatter 各字段映射到 `Profile`、**`schedules` 原样带进派生结果**
- `AgentScanRegisterTest`（`oryxos-core`，纯单测）—— 课件 harness 第 3 行：N 个目录 → `ProfileRegistry` 出 N 个、带 `schedules` 的都进 `AgentScheduler`
- `ProfileRegistryRuntimeTest`（`oryxos-core`，纯单测）—— 课件 harness 第 4 行：`register()` 后立即可见、`remove()` 后立即消失、**非法配置与启动路径同一异常类型 + 同一消息**
- `AgentSchedulerRegisterTest`（`oryxos-core`，纯单测）—— 课件 harness 第 5 行：`registerProfile` 后 `scheduledTasks` 有句柄、cron / 时区来自 `Profile.schedules`、重复注册是替换而非翻倍
- `ProgressiveDisclosureTest`（`oryxos-core`，纯单测）—— 课件 harness 第 6 行：Agent 正文进 system prompt，参考 / 子指令 / 脚本**不预载**，Skill 只注入 name / description / 路径
- 单测默认跑，**无 `@Tag("integration")` 项**（本节无真外部依赖；「到点真自己跑 + webhook 收到 + 审计有账」是人工项）
- 实现完成的定义是 `mvn clean verify` 全绿（含 Spotless / P3C / Checkstyle / SpotBugs+FindSecBugs / OWASP）

**Target Platform**: Linux 服务器（开发机为 WSL2，POSIX 软连接可用）；单 fat JAR（`oryxos-boot/target/oryxos.jar`）

**Project Type**: Maven 多模块库 + CLI（核心阶段 9 模块，**本节不新增模块**）

**Performance Goals**:

- 启动扫描是**线性一次**的：目录数 × 每个 `AGENT.md` 的解析成本，量级是「人能维护的规模」。`inspect()` 的资源枚举只 `list` 两层目录，**不递归、不读文件内容**
- `register()` / `remove()` / `exists()` 是 `ConcurrentHashMap` 的单次操作，**O(1)**；`all()` 为保持稳定输出做一次按名排序（n log n，n 为 Agent 数）
- 句柄表的条目数上界 = `schedules` 总条数，替换时按 **Agent 归属**（`RegisteredTask.profile().name()` 精确相等）先取消再写入，**不会翻倍**（2026-10-21 修订：原写「按前缀」，见验收报告 §5.1）
- **MUST NOT 加目录缓存 / 文件监听**（热加载归第 30 节，FR 边界）

**Constraints**:

- **`AgentService` / `ReActLoop` / `ToolExecutor` / `ContextLoader` / `ShellTools` / `WhitelistSandbox` 六个文件 `git diff` 为空**（FR-011）
- **`ProfileLoader` 的既有校验与消息一字不改**：`FORBIDDEN_KEYS`、`provider.name` / `provider.model` 的报错文本、`${ENV_VAR}` 解析口径全部保持原样；新增入口只复用它们
- **MUST NOT 新增 `AgentLoader` 类**（spec 裁决 4）；课件 `AgentLoaderTest` 的断言落在 `ProfileLoader` 上
- **MUST NOT 修改 `SandboxAction` / `WhitelistSandbox` / `ShellTools`** 来实现「脚本限定在本 Agent 的 `scripts/` 目录」——该能力记已知边界（spec 裁决 2）
- **MUST NOT 给 `ProfileLoader` 增加「已知工具集合」参数**；`tools` 未注册的告警落在运行期 `ToolRegistry.availableFor`（spec FR-008 / 裁决 7）
- **示例 `AGENT.md` MUST NOT 出现 `notify_channels` / `schedules[].id` / 顶层 `name`**（本仓既定契约，spec 裁决 3）
- **`skills/<name>` MUST 是指向公共实体的相对软连接**（章程原则 IV），MUST NOT 用普通文件；示例按该形态交付（spec 裁决 1）
- **MUST NOT 用 `@Component` / `@PostConstruct` / `@Scheduled` / `@EnableScheduling`**：装配唯一落点仍是 `CliAssemblyConfiguration`，注册时机仍由 `serve` / `gateway` 命令体决定（第 25 节裁决 3 / FR-012）
- **MUST NOT 新增 CLI 命令 / REST 路径 / 配置键**（`shell.allowed_commands` 是既有键，只加取值）
- **避开 P3C/ASM 解析不了的 Java 18+ 语法形态（如增强 switch 的 `default ->` 写法），静态检查是构建门禁。** 本节代码不新增 switch；`instanceof` 模式匹配沿用既有用法
- **MUST NOT 吞异常**：`registerProfile` 的单条规则失败沿用第 28 节的「ERROR 指名 + 跳过」路径（FR 边界 / 第 25 节 FR-004）

**Scale/Scope**:

- 新增 main 代码：`ProfileLoader` +~90 行（`deriveProfile` / `inspect` / `AgentDir` / 异常可见性）、`ProfileRegistry` +~45 行、`AgentScheduler` +~55 行；`application.yaml` 一行取值
- 新增示例文件 5 个（`AGENT.md` / `REFERENCE.md` / `scripts/reconcile.py` / 公共 `SKILL.md` / 一个软连接）
- 新增测试 6 个类，约 700 行

---

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 本节如何满足 | 结论 |
|---|---|---|
| **I. JDK 21 + Spring Boot 3.x 单体、单二进制** | 不新增模块、不新增依赖、不引入外部进程；调度仍走既有的进程内 `ThreadPoolTaskScheduler` | PASS |
| **II. 核心阶段只交付运行时内核** | 本节正是内核的「定义 Agent」机制；无版本管理 / 市场 / 跨 Agent 复用（spec「明确不做」逐项排除） | PASS |
| **III. ReAct 自实现、Spring AI 只用一半** | 一行引擎代码不改；不触碰 `LlmClient` / `ToolSchemaAdapter` | PASS |
| **IV. Agent 目录不是 Tool** | 本节是这条原则的兑现：目录 → `Profile`，正文由 `ContextLoader` 注入；Skill 走「公共实体 + 相对软连接」；解释器只在管理员白名单内放行（`shell.allowed_commands` 加 `python3`） | PASS |
| **V. Tool 体系统一抽象、单一模块** | 不新增 Tool、不新增注册表来源；`read_file` / `shell` 复用既有实现 | PASS |
| **VI. 审计 Day One 落库** | 不新增审计代码；定时触发仍走 `AgentService.process` → 既有 `llm_calls` / `tool_invocations` | PASS |
| **VII. 接口先行、实现按档演进** | 本节改的是 core 内部类，不碰 `Sandbox` / `LongTermMemoryStore` / `NotifyChannelAdapter` 三个升级接缝 | PASS |
| **VIII. 数据私有、状态外置** | 不新增持久状态；`ProfileRegistry` 是磁盘目录的内存投影，重启可重建 | PASS |
| **IX. 三触发源复用同一条链路** | 定时注册仍是同一条 `AgentService.process`；本节只改「注册入口的形态」 | PASS |
| **X. 每个 user story 有可演示 Demo** | 示例 Agent `daily-reconcile` 就是可演示物；`examples/` 入库、向导复制进工作区 | PASS |

**Phase 0 后复检**：research 的 11 条决策（D1~D11）均未引入新的对外概念、未改已定字面量、未新增依赖 → 上表结论不变，无 `Complexity Tracking` 项。**Phase 1 后设计复核（2026-10-21）**：对照真实代码逐项核实后修正三处——`registerProfile` 返回值由 `void` 改 `int`（`registerAll` 的「共 N 条」计数来源）、示例脚本路径按 JVM cwd 事实改写、quickstart 的 `-Dtest` 命令补 `-Dsurefire.failIfNoSpecifiedTests=false`（`-am` 带上游模块时零命中即红）——上表结论仍不变。

---

## Project Structure

### Documentation (this feature)

```text
specs/012-plugin-agent/
├── plan.md              # 本文件
├── research.md          # Phase 0 输出（10 条决策）
├── data-model.md        # Phase 1 输出（AgentDir / Profile 映射 / 两个内存索引）
├── quickstart.md        # Phase 1 输出（可执行验证向导）
├── contracts/           # Phase 1 输出
│   ├── agent-directory.md        # Agent 目录形态与 frontmatter 契约
│   └── runtime-registration.md   # 三个类的运行时注册 API 契约
├── checklists/requirements.md
├── spec.md
└── tasks.md             # Phase 2 输出（/speckit-tasks）
```

### Source Code (repository root)

```text
oryxos-core/src/main/java/io/oryxlabs/oryxos/core/
├── profile/
│   ├── ProfileLoader.java        # 改：+ deriveProfile(Path) / inspect(Path) / AgentDir record；ProfileLoadException 提为 public
│   └── ProfileRegistry.java      # 改：不可变快照 → 可变并发索引；+ register / remove / exists
└── schedule/
    └── AgentScheduler.java       # 改：+ registerProfile(Profile) / scheduledTasks 句柄表；registerAll 改为循环调用

oryxos-core/src/test/java/io/oryxlabs/oryxos/core/
├── profile/
│   ├── AgentLoaderTest.java          # 新增
│   ├── DeriveProfileTest.java        # 新增
│   └── ProfileRegistryRuntimeTest.java # 新增
├── schedule/
│   ├── AgentScanRegisterTest.java    # 新增（放 schedule 包：需要句柄表的包级访问）
│   └── AgentSchedulerRegisterTest.java # 新增
└── context/
    └── ProgressiveDisclosureTest.java  # 新增

oryxos-boot/src/main/resources/application.yaml   # 改：shell.allowed_commands 加 python3

examples/                                          # 新增（仓库根）
├── agents/daily-reconcile/
│   ├── AGENT.md
│   ├── REFERENCE.md
│   ├── scripts/reconcile.py
│   └── skills/report-format -> ../../../skills/report-format   # 相对软连接
└── skills/report-format/
    └── SKILL.md
```

**Structure Decision**: 沿用既有 9 模块布局，**不新增模块**。运行时注册能力落在 `oryxos-core` 的既有三个类上——它们是 `Profile` 的装载、索引、调度三处真相源，散到别处会制造第二份判定逻辑。示例与公共 Skill 实体落在仓库根 `examples/`（`.oryxos/` 被 `.gitignore` 忽略，不能入库），由验收向导复制进工作区；同一条相对软连接在 `examples/` 与 `.oryxos/` 两种布局下解析到各自的公共 Skill 根（见 research D6）。

**装配层不新增代码**：`CliAssemblyConfiguration#profileRegistry` 已在第 16/26 节从 `profileLoader.loadAll()` 构造索引，`serve` / `gateway` 命令体已在第 25 节调用 `agentScheduler.registerAll()`——「启动扫描并注册」这条链路前序节已交付，本节只补测试把它钉住。

---

## Complexity Tracking

> 本节无章程违例，无 `Complexity Tracking` 项。

---

## Phase 0 研究结论（详见 [research.md](./research.md)）

| # | 决策 | 一句话理由 |
|---|---|---|
| D1 | 不新增 `AgentLoader` 类，`ProfileLoader` 承载 | 第二真相源（spec 裁决 4） |
| D2 | `deriveProfile(Path)` 与 `inspect(Path)` 两个公开入口 | 课件交付物是「解析 + 派生」两件事，前者给运行时注册、后者让资源识别可判 |
| D3 | `ProfileLoadException` 由 private 提为 **public** 嵌套类 | FR-004「同一异常类型」要可机械判定；第 30 节也要按类型捕获 |
| D4 | `ProfileRegistry` 用 `ConcurrentHashMap`，`all()` 按名排序 | 并发读安全 + 输出稳定；现有仓库无顺序断言 |
| D5 | `AgentScheduler.scheduledTasks` 按 **Agent 归属**（`RegisteredTask.profile().name()` 精确相等）做替换；**MUST NOT 用 `<Agent>#` 前缀** | 取消后重注册不翻倍；`#` 在目录名里合法，`a` / `a#b` 并存时前缀匹配会误取消 `a#b#1`（2026-10-21 修订：原 D5 写「按 `<Agent>#` 前缀」，见验收报告 §5.1） |
| D6 | 示例软连接用 `../../../skills/report-format` | 同一条相对路径在 `examples/` 与 `.oryxos/` 下都指向各自的公共 Skill 根 |
| D7 | `python3` 进白名单、示例正文写 `python3` | 本机无 `python`；章程要求解释器必须在管理员白名单内显式放行 |
| D8 | 「脚本限定本 Agent `scripts/`」不做 | 需改第 20/24 节契约，超出交付物；记已知边界 |
| D9 | `tools` 未注册告警沿用 `ToolRegistry.availableFor` | 不给 `ProfileLoader` 注入工具集合（spec 裁决 7） |
| D10 | 正文无缓存 / 即时生效不重测 | 第 17 节 `ContextLoaderTest` 已钉死（课件明确不重测）。**（2026-10-21 复核：那条既有用例读的是 Bootstrap `AGENTS.md`，不覆盖 `AGENT.md` 正文，已补 `rereadsAgentBodyOnEveryLoad` 直接判，见验收报告 §5.8）** |
| D11 | 正文引用脚本写**相对工作区根**路径 | `ShellTools` 无 `.directory()`，cwd = JVM cwd = 工作区根；照课件写 `scripts/reconcile.py` 永远解析不到（设计复核 2026-10-21） |
