---
description: "Task list for CLI 与会话持久化（第 18 节）"
---

# Tasks: CLI 与会话持久化 —— 把 Agent 从「能跑」变成「能用」

**Input**: Design documents from `/home/wngbob/oryxos-wngbob/specs/003-cli-session/`

**Prerequisites**: [plan.md](plan.md)（必需）、[spec.md](spec.md)（必需，含 17 条冲突仲裁）、[research.md](research.md)（D1~D20）、[data-model.md](data-model.md)、[contracts/](contracts/)、[quickstart.md](quickstart.md)

**Tests**: 本节**明确要求**测试（课件「四、验收 harness」）——两个测试类，测试任务先于或伴随对应实现任务。

**Organization**: 按 user story 分组。US1 / US2 为 P1，US4 / US3 为 P2（US3 与 US4 同为 P2，**US4 先行**——US3 的独立测试断言「轻命令不起上下文」这个**对照**，需要 US4 的轻命令先存在，见 §Dependencies）。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无依赖）
- **[Story]**: 所属 user story（US1 / US2 / US3 / US4）
- 每个任务带**确切文件路径**

## Path Conventions

Maven 多模块，路径相对仓库根。本节涉及模块：`oryxos-cli`（主体）、`oryxos-core`、`oryxos-storage`、`oryxos-channel-cli`、`oryxos-provider`、`oryxos-boot`（pom）。

**全局纪律（贯穿全部任务）**：

- 测试方法名**必须英文**（驼峰或 snake_case）；课件中文名译成语义等价的英文名，课件原文进 `@DisplayName`
- 只创建交付物点名的对外概念；已定字面量逐字保真；异常不吞；不建文档外抽象层；注释只写「为什么」
- 避开 P3C/ASM 解析不了的 Java 18+ 语法（增强 `switch` 的 `default ->`）；构造函数前不放 `//` 行注释（触发 `CommentsMustHaveJavadocFormatRule`）
- **MUST NOT 出现任何明文凭证**；命令输出 / 日志 / 报错三处都不得回显密钥值（章程原则 VIII）
- 实现与测试一起落地，跑该模块测试，红了当场修

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 依赖与打包骨架——先让模块能看见彼此

- [X] T001 [P] 在 `oryxos-cli/src/main/java/io/oryxlabs/oryxos/cli/` 下建 `command/` 与 `workspace/` 两个子包目录（占位 `.gitkeep` 或首个类落地时一并建）
- [X] T002 [P] 改 `oryxos-cli/pom.xml`：新增六个模块依赖 `oryxos-core` / `oryxos-provider` / `oryxos-tool` / `oryxos-memory` / `oryxos-storage` / `oryxos-channel-cli` + `spring-boot-starter`（**MUST NOT 加 `oryxos-web`**，仲裁 15）；**移除 `maven-shade-plugin` 整段**（保留 `<resources>` 的 `oryxos-version.properties` 过滤）；各依赖不写 `<version>`（父 pom 统一管理）
- [X] T003 [P] 改 `oryxos-channel-cli/pom.xml`：新增 `oryxos-core` 依赖（`CliChannel` 要调 `AgentService.process`）
- [X] T004 [P] 改 `oryxos-boot/pom.xml`：`spring-boot-maven-plugin` 的 `<configuration>` 增 `<mainClass>io.oryxlabs.oryxos.cli.OryxOsCli</mainClass>`（发行入口，仲裁 17 / [contracts/cli-assembly.md](contracts/cli-assembly.md) §1）

**Checkpoint**: `./mvnw -q -DskipTests install -Ddependency-check.skip=true` 能过（依赖解析 + 打包配置生效）

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 会话领域层 + 持久化层 + 两处接缝接线。**所有 user story 都依赖这一层**。

**⚠️ CRITICAL**: 本阶段完成前，任何 user story 都不能开工

- [X] T005 新增 `oryxos-storage/src/main/java/io/oryxlabs/oryxos/storage/entity/SessionEntity.java`：`@Entity @Table(name = "sessions")`；字段 `sessionId`（`@Id`，**不用 `@GeneratedValue`**）/ `profileName` / `channel` / `userId` / `messagesJson` / `status` / `createdAt` / `lastActiveAt` / `archivedAt`；**全部字段是基本类型与 `String`/`LocalDateTime`，MUST NOT 出现 `Session`/`SessionMessage` 等 core 类型**（[data-model.md](data-model.md) §5、[contracts/session-persistence.md](contracts/session-persistence.md) §4）
- [X] T006 [P] 新增 `oryxos-storage/src/main/java/io/oryxlabs/oryxos/storage/repository/SessionRepository.java`：`extends JpaRepository<SessionEntity, String>`（主键类型是 `String`）
- [X] T007 改 `oryxos-storage/src/main/resources/db/schema.sql`：追加 `sessions` 建表语句，列名与长度**逐字照 A1**——`session_id VARCHAR(128)` PK、`profile_name VARCHAR(128)`、`channel VARCHAR(32)`、`user_id VARCHAR(128)`、`messages_json TEXT`、`status VARCHAR(16)`、`created_at` / `last_active_at` / `archived_at` TIMESTAMP；**不新增列、不改类型**（仲裁 12）。**MUST NOT 写含分号的注释**（`init` 按 `;` 切分执行脚本，见 [research.md](research.md) D8）
- [X] T008 改 `oryxos-core/src/main/java/io/oryxlabs/oryxos/core/session/Session.java`：增 `channel` / `userId` 两个字段与访问器；工厂签名变 `public static Session create(String id, String channel, String userId, String profileName)`；新增 **package-private** `static Session restore(String id, String channel, String userId, String profileName, List<SessionMessage> messages, Instant createdAt, Instant lastActiveAt)`——**直接接收已带时间戳的消息列表，MUST NOT 改写任何时间戳**（[data-model.md](data-model.md) §1）；**不加** `status` / `archivedAt`（仲裁 4）
- [X] T009 改 `oryxos-core/src/main/java/io/oryxlabs/oryxos/core/session/SessionService.java`：补 `Session getOrCreate(String channel, String userId, String profileName)` 与 `Optional<Session> get(String sessionId)`；`save(Session)` 保持不变（仲裁 1）
- [X] T010 [P] 新增 `oryxos-core/src/main/java/io/oryxlabs/oryxos/core/session/SessionId.java`：**包级私有**，`static String of(String channel, String userId, String profileName)`，公式 `channel + ":" + user_id + ":" + profile_name`；三段中任一段为空或含 `:` → 抛异常；结果长度 ≤ 128（[contracts/session-persistence.md](contracts/session-persistence.md) §2）
- [X] T011 [P] 新增 `oryxos-core/src/main/java/io/oryxlabs/oryxos/core/session/MessageJson.java`：**包级私有**，`messages_json` 落盘形状的唯一映射——`role` 落**小写**（`system`/`user`/`assistant`/`tool`）、`toolCalls` 每项只取 `{id, type, name, arguments}`、`timestamp` 用 `Instant.toString()`；**MUST NOT 给 `SessionMessage.Role` 加 Jackson 注解**（[data-model.md](data-model.md) §2、[contracts/session-persistence.md](contracts/session-persistence.md) §3）
- [X] T012 新增 `oryxos-core/src/main/java/io/oryxlabs/oryxos/core/session/JpaSessionService.java`：`implements SessionService`，注入 `SessionRepository`，用 T011 的 `MessageJson` 序列化。**`save` 三条不变量**：① `created_at` 不被覆盖；② `status`/`archived_at` 新增时写 `active` + 空、**更新时原样保留**；③ 序列化失败上抛、**不写半条**（[contracts/session-persistence.md](contracts/session-persistence.md) §5）。**「新增 / 更新」的判别方式定为 `findById(id)` 一次查询**（不引入额外的「是否首次写入」抽象层，判别点只有这一处）。**实现时从 `existsById` 改为 `findById`**：不变量 ② 要求更新路径原样保留 `status` / `archived_at`，这必须先取到既有行；用 `existsById` 还得再取一次，等于两次查询；**MUST NOT 在 `SessionService` 里重复校验 `profile_name` 存在性**（仲裁 11）
- [X] T013 [P] 改 `oryxos-core/src/main/java/io/oryxlabs/oryxos/core/profile/ProfileLoader.java`：新增 `loadAllDetailed()`，返回「成功清单 + 失败明细（Agent 名 + 原因）」；**`loadAll()` 的签名与行为保持不动**（仲裁 16）。**MUST NOT 在 CLI 侧重写 frontmatter 校验规则**
- [X] T014 [P] 改 `oryxos-provider/src/main/java/io/oryxlabs/oryxos/provider/ProviderService.java`：类声明加 `implements LlmClient`，方法上加 `@Override`——**只加这一行，不改方法体、不改签名、不改可见性**（[contracts/cli-assembly.md](contracts/cli-assembly.md) §4）。**MUST NOT 打开 Spring AI 自动工具执行**（章程原则 III）
- [X] T015 **第 17 节连带修订**（[contracts/session-persistence.md](contracts/session-persistence.md) §0，同一节内必须完成，不得留半截）：① 改 `specs/002-react-loop/contracts/session-model.md` §2 的「五个字段齐备」断言为**七项**；② 第 17 节测试里所有 `Session.create(id, profileName)` 两参调用点改为**四参**（`ReActLoopTest` / `AgentServiceTest` 等）；③ 第 17 节测试里 `SessionService` 的**匿名/手写替身补实现新增的两个方法**（编译期就会暴露）。**`AgentService` 一行不改**

**Checkpoint**: `./mvnw -q -DskipTests install -Ddependency-check.skip=true` 全绿；`grep -rn "oryxos-core" oryxos-storage/pom.xml` 仍为 0（反向边一条都没新增）

---

## Phase 3: User Story 1 - 会话：一个三元组一条历史，重启还在 (Priority: P1) 🎯 MVP

**Goal**: 同一三元组历次取会话拿到同一个（幂等），三元组任一不同即不同会话（隔离）；标识拼接只存在于唯一一处。

**Independent Test**: 对同一三元组连续取两次会话，断言标识相同；把 channel / user / profile 各改一处，断言标识都与原值不同。**全程不访问网络**。

### Tests for User Story 1 ⚠️

> **NOTE: 先写测试，确保它 FAIL，再实现**

- [X] T016 [US1] 新增 `oryxos-core/src/test/java/io/oryxlabs/oryxos/core/session/SessionServiceTest.java`：Mockito + `Map` 支撑的 `SessionRepository` 替身；覆盖幂等、隔离（channel / user / profile 各改一处）、**标识生成只此一处**。**课件 harness 的关键回归测试逐字落地**：

  ```java
  @Test
  @DisplayName("同一三元组_历次getOrCreate都是同一个Session")
  void sameTriple_getOrCreateAlwaysReturnsSameSession() {
    var first  = sessionService.getOrCreate("cli", "wang", "default");
    var second = sessionService.getOrCreate("cli", "wang", "default");
    assertEquals(first.id(), second.id());          // 幂等：多轮对话靠它串起来

    var other = sessionService.getOrCreate("web", "wang", "default");
    assertNotEquals(first.id(), other.id());        // channel 不同就是不同会话
  }
  ```

  **三条断言逐条保真**——一条不少、一条不放松（反作弊：不得删断言、不得 `@Disabled`、不得放宽阈值）

### Implementation for User Story 1

- [X] T017 [US1] 让 T016 转绿：若 `JpaSessionService.getOrCreate` 的「命中则从库中恢复历史、未命中则新建空会话」或 `SessionId` 的拼接有问题，**修实现不修测试**
- [X] T018 [US1] 跑 `./mvnw -pl oryxos-core -am test -Dtest=SessionServiceTest -Dsurefire.failIfNoSpecifiedTests=false`，全绿

**Checkpoint**: `SessionServiceTest` 的幂等 / 隔离 / 标识只此一处部分全绿

---

## Phase 4: User Story 2 - 会话落库与读回：历史一条不少、重启还在 (Priority: P1)

**Goal**: 手工脚本建出的 `sessions` 表能存能读；含工具调用与工具结果的历史读回后逐条一致；模拟「重启」后历史还在。

**Independent Test**: 用**手工建表脚本**建出空会话表（不是框架自动建表），存进一条含「用户消息 + 带工具调用的模型回复 + 工具结果」的会话，读回后逐条比对；随后新建全新 JDBC 连接（模拟进程重启）重查，断言历史仍在。

### Tests for User Story 2 ⚠️

- [X] T019 [US2] 在 `SessionServiceTest` 补**序列化往返**用例：含用户消息、模型回复（文本 + 工具调用）、工具结果的消息列表，经 `MessageJson` 落盘再回读，断言条数、顺序、角色、内容、工具调用与工具调用标识**逐条一致**；另覆盖**中文 / 换行 / 引号 / emoji** 内容逐字一致、以及**空历史**读回得空列表不报错（[contracts/session-persistence.md](contracts/session-persistence.md) §6）；**并覆盖 T012 的三条 `save` 不变量**——同一会话 `save` 两次后 `created_at` 不变、`status` 仍为首次写入的 `active`（不变量 ① / ② 的断言，缺了它们这两条只是注释）
- [X] T020 [US2] 新增 `oryxos-storage/src/test/java/io/oryxlabs/oryxos/storage/repository/SessionRepositoryTest.java`（`@DataJpaTest` + 真 SQLite 临时文件 + 手工脚本，沿用既有 `LlmCallRepositoryTest` 的建表做法）：① 脚本建得出 `sessions` 表且列齐备；② 经 Repository 存取往返保真；③ **「模拟重启」**——存一条后**新建一条独立 JDBC 连接**（与写入侧不共享任何缓存 / EntityManager / Spring 上下文）直读 `messages_json`，断言与写入时一致（[research.md](research.md) D5：同一持久化上下文里 `save` 完再 `findById` 走 Hibernate 一级缓存，**证明不了落盘**）；④ **落库行的 `session_id` 列逐字等于传入的 `Session.id()`**（FR-022 的持久化侧一半；审计行那半由 T052 ② 核对）
- [X] T021 [US2] 覆盖「按标识查不到」：断言得到 `Optional.empty()` 而非异常（FR-009 / US2 场景 5）

### Implementation for User Story 2

- [X] T022 [US2] 让 T019~T021 转绿：问题出在序列化口径就修 `MessageJson`，出在表/映射就修 `SessionEntity` / `schema.sql`；**修实现不修测试**
- [X] T023 [US2] 跑 `./mvnw -pl oryxos-storage -am test -Dtest=SessionRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false` 与 `./mvnw -pl oryxos-core -am test -Dtest=SessionServiceTest -Dsurefire.failIfNoSpecifiedTests=false`，全绿

**Checkpoint**: 两个测试类全绿——会话持久化在领域层与存储层各自守住了边界

---

## Phase 5: User Story 4 - 起项目、造 Agent、看情况 (Priority: P2)

**Goal**: `init` 起工作区（幂等）、`profile create/list/show/delete` 管 Agent、`provider list` / `tool list` / `session list` / `status` 看情况。**全部是轻命令，不启动 Spring 上下文**。

> **排序说明**：US4 与 US3 同为 P2，**US4 先行**——US3 的独立测试断言的是「轻命令 vs 重命令」的**对照**，需要 US4 的轻命令先存在。

**Independent Test**: 在空目录里跑 `init`，断言目录树与数据库文件齐备；再跑一次，断言既有文件一个都没被覆盖且输出「已有 / 新建」两份清单；造一个 Agent 后 `profile list` / `profile show` / `profile delete` 各自可用。

### Implementation for User Story 4

- [X] T024 [P] [US4] 新增 `oryxos-cli/src/main/java/io/oryxlabs/oryxos/cli/workspace/Workspace.java`：工作区根路径解析 + 「是否在工作区内」判定（当前目录存在 `.oryxos/` 子目录）
- [X] T025 [P] [US4] 新增 `oryxos-cli/src/main/java/io/oryxlabs/oryxos/cli/workspace/WorkspaceDb.java`：轻命令的**原生 JDBC 收口**（连接 / 执行建表脚本 / 查询），包级私有辅助类（[research.md](research.md) D7）
- [X] T026 [US4] 新增 `oryxos-cli/src/main/java/io/oryxlabs/oryxos/cli/workspace/AgentResolver.java`：**唯一闸门**——按名解析 Agent，或工作区唯一 Agent 时取缺省；一个都没有 → 报错提示「没有可用 Agent，先 `oryxos profile create <name>`」；多于一个且未指定 → 报错并**列出可选名**（FR-004 / Edge Cases）。**`profile_name` 存在性校验落在这里**（仲裁 11）
- [X] T027 [US4] 新增 `oryxos-cli/src/main/java/io/oryxlabs/oryxos/cli/command/InitCommand.java`：建 `.oryxos/` 工作区（`agents/`（**空目录，MUST NOT 生成默认 Agent**，仲裁 14）、`skills/`、`memory/MEMORY.md`（含 `## 核心记忆` / `## 归档记忆` 两个区标题）、`mcp_servers.yaml`、`sessions/`、`logs/`、`output/`、`AGENTS.md`、`SOUL.md`、`USER.md`）；**MUST NOT 创建** `notify_channels.yaml` / `sandbox.yaml`（仲裁 7）；建出 `oryxos.db` 与**全部表**（classpath 读 `db/schema.sql` + 按 `;` 切分 + `Statement.execute`）；**幂等**——已存在的目录与文件一律不覆盖，输出「已有 / 新建」两份清单（FR-001）
- [X] T028 [P] [US4] 新增 `oryxos-cli/src/main/java/io/oryxlabs/oryxos/cli/command/ProfileCreateCommand.java`：生成 `.oryxos/agents/<name>/AGENT.md`（**合法 frontmatter 占位 + 正文引导注释**）；已存在 → 报错不覆盖、退出码 1（FR-011）；模板的 `provider.name` 占位必须落在 `ProviderProperties.SUPPORTED_PROVIDERS` 内，否则模板一建出来就是「无效 Agent」
- [X] T029 [P] [US4] 新增 `oryxos-cli/src/main/java/io/oryxlabs/oryxos/cli/command/ProfileListCommand.java`：列 `.oryxos/agents/` 全部 Agent 的 **name / description / provider / 有效-无效（无效附原因）**；原因经 `ProfileLoader.loadAllDetailed()` 取（FR-012）。**目录名即 Agent 名**；注意课件片段读的 `.oryxos/profiles/` 是示意错误（仲裁 10）
- [X] T030 [P] [US4] 新增 `oryxos-cli/src/main/java/io/oryxlabs/oryxos/cli/command/ProfileShowCommand.java`：展示该 Agent **派生后的完整 Profile** + 校验结果；无效时输出失败原因（与 T029 **同一个原因源**，不各写一份）；Agent 不存在 → 报错（FR-013）
- [X] T031 [P] [US4] 新增 `oryxos-cli/src/main/java/io/oryxlabs/oryxos/cli/command/ProfileDeleteCommand.java`：删除 Agent 目录，**需确认**；`--force` 跳过确认；未确认时不删除、**正常退出（退出码 0）**；核心阶段为**物理删除**（FR-014）
- [X] T032 [P] [US4] 新增 `oryxos-cli/src/main/java/io/oryxlabs/oryxos/cli/command/ProviderListCommand.java`：列「底座支持的厂商全集」（取自 `ProviderProperties.SUPPORTED_PROVIDERS`，**MUST NOT 在 CLI 侧复制一份同名集合**，仲裁 6）+「工作区各 Agent 实际引用的厂商名」并标出是否在支持集合内；**密钥就绪状态一律标「未接入（第 26 节）」**，MUST NOT 编造 `ready`/`missing`，**MUST NOT 回显任何密钥值**（FR-015 / SC-008）。Agent 引用了不支持的厂商 → 标「不支持」并给出厂商名，命令仍退出码 0（Edge Cases）
- [X] T033 [P] [US4] 新增 `oryxos-cli/src/main/java/io/oryxlabs/oryxos/cli/command/ToolListCommand.java`：经 `ToolCatalog` 接缝取工具、按来源分组打印（`builtin` / `annotation` / `mcp:<server>`）；本节输出**空表**并注明「第 20 节接入 ToolRegistry」，MUST NOT 编造工具（FR-002 / [contracts/cli-surface.md](contracts/cli-surface.md) §2.10）。命令已按最终形态写，第 20 节只需换实现
  - **实施期偏离（已报批）**：本命令**未接 `ToolCatalog` 接缝**，只打空表 + 注明「第 20 节接入 ToolRegistry」。原因：`tool list` 是**轻命令**（不启 Spring 上下文，仲裁 5 / FR-003），而 `ToolCatalog` 空实现是 `CliAssemblyConfiguration` 里的 `@Bean`（重命令才建上下文），轻命令够不着它；且 §2.10 要求的「全量 + 按来源分组」用现有 `OryxTool` 接口（只有 `getName`/`getDescription`/`getInputSchema`/`execute`，**无来源访问器**）根本表达不出来。故本节不发明新接缝方法，改由第 20 节随 `ToolRegistry` 落地时一并把本命令改为经接缝取数。代价：第 20 节需要改本命令，**不是「只换实现」**。
- [X] T034 [P] [US4] 新增 `oryxos-cli/src/main/java/io/oryxlabs/oryxos/cli/command/SessionListCommand.java`：走**原生 JDBC** 直读 `sessions` 表，列 `sessionId` / `profile` / `channel` / `user` / `status` / `lastActiveAt`，**按最近活跃倒序**（FR-016）
- [X] T035 [P] [US4] 新增 `oryxos-cli/src/main/java/io/oryxlabs/oryxos/cli/command/StatusCommand.java`：输出工作区路径 / Agent 数（含无效数）/ 活跃会话数（`status = 'active'`）/ 数据库可达性 / 底座支持的厂商全集；**密钥就绪状态标「未接入（第 26 节）」、定时任务数标「未接入（第 28 节）」**，MUST NOT 编造数值（[contracts/cli-surface.md](contracts/cli-surface.md) §2.2 / §4）
- [X] T036 [US4] 新增 4 个**只做分组**的类：`oryxos-cli/src/main/java/io/oryxlabs/oryxos/cli/command/` 下的 `ProfileCommand` / `ProviderCommand` / `ToolCommand` / `SessionCommand`（各挂对应叶子子命令；**对外是空格分隔的多级命令**，Picocli 的子命令按首个 token 匹配，见 [research.md](research.md) D12）
- [X] T037 [US4] 改 `oryxos-cli/src/main/java/io/oryxlabs/oryxos/cli/OryxOsCli.java`：把 4 个分组命令挂到 `subcommands`；**`main` 既有的 `System.exit(new CommandLine(...).execute(args))` 保持不变**（退出码交给 Picocli 默认约定，[research.md](research.md) D16）
- [X] T038 [US4] **通用约定收口**：需要工作区的命令（除 `init` 外的全部）在非工作区目录执行时，报错提示先跑 `oryxos init`、**退出码 1、不抛栈**（FR-018 / SC-007）；运行错误抛带**清晰中文消息**的异常，**MUST NOT 手工 `System.exit(1)`**（手工 exit 会跳过 Picocli 的异常处理与用法打印）。**异常处理的落点必须显式指定**：Picocli 对未捕获的 `ExecutionException` 默认把**堆栈**打到 stderr——正是 FR-018 禁止的形态，故 MUST 注册一个 `IExecutionExceptionHandler`（`CommandLine#setExecutionExceptionHandler`），只打印异常消息、返回 `ExitCode.SOFTWARE`（1），**MUST NOT 输出堆栈**。参数错误的退出码 2 与用法打印交给 Picocli 默认的 `IParameterExceptionHandler`，**MUST NOT 覆写**
- [X] T039 [US4] 跑 `./mvnw -pl oryxos-cli -am -q -DskipTests install -Ddependency-check.skip=true`，确认编译通过；手工冒烟 `--help` 逐条

**Checkpoint**: 在空目录里 `init` → `profile create weather` → `profile list` → `session list` 走通，全程无 Spring 启动痕迹

---

## Phase 6: User Story 3 - 轻命令秒回、重命令才启动引擎 (Priority: P2)

**Goal**: `chat` / `serve` / `gateway` 启动 Spring 上下文；其余 9 条命令一条都不启动。

**Independent Test**: 断言轻命令执行完毕时不产生 Spring 上下文启动痕迹，重命令会产生（对照见 [quickstart.md](quickstart.md) §6）。

### Implementation for User Story 3

- [X] T040 [US3] 新增 `oryxos-cli/src/main/java/io/oryxlabs/oryxos/cli/CliApplication.java`：`@SpringBootApplication(scanBasePackages = "io.oryxlabs.oryxos")` + **显式** `@EntityScan("io.oryxlabs.oryxos.storage.entity")` + `@EnableJpaRepositories("io.oryxlabs.oryxos.storage.repository")`（`scanBasePackages` **带不动**这两个，第 17 节已踩过坑 → 日志报 `Found 0 JPA repository interfaces`）。**MUST NOT 加 `@EnableWebMvc`**（[contracts/cli-assembly.md](contracts/cli-assembly.md) §2）
  - **偏离（T048 冒烟实测后修订，已报批）**：`scanBasePackages` **不是** `"io.oryxlabs.oryxos"`，而是 `{"io.oryxlabs.oryxos.cli", "io.oryxlabs.oryxos.provider"}`。原因：宽扫描根会扫到 fat JAR 里同根的 `io.oryxlabs.oryxos.boot.OryxosApplication`（`oryxos-boot` 的启动类，本身也是 `@SpringBootApplication` + 同一套 `@EntityScan`/`@EnableJpaRepositories`），JPA 仓库 Bean 被注册两遍 → 重命令撞 `BeanDefinitionOverrideException` 起不来。**只跑 `--help` 看不到**（轻命令不启动上下文），必须跑一次重命令才暴露。契约 [cli-assembly.md](contracts/cli-assembly.md) §2 与 [research.md](research.md) D10 的「已核实无害」断言已就地修订
- [X] T041 [US3] 新增 `oryxos-cli/src/main/java/io/oryxlabs/oryxos/cli/CliAssemblyConfiguration.java`：**全部 `@Bean` 显式装配的唯一落点**——`Workspace` / `AgentResolver` / `SessionService`（→ `JpaSessionService`，注入 `SessionRepository`）/ `LlmClient`（→ `ProviderService`）/ `CliChannel` / `AgentService` 及其依赖。**MUST NOT 新增第二个装配配置类**；`JpaSessionService` **不加 `@Component`**（[contracts/cli-assembly.md](contracts/cli-assembly.md) §5）
- [X] T042 [US3] 在 `CliAssemblyConfiguration` 内以 `@Bean` + `@ConditionalOnMissingBean` 声明两个**空接缝**（实现类**包级私有嵌套**，不进 public 面）：`ToolCatalog` 空实现（`find` 恒 `Optional.empty()`、`availableFor` 恒空表）、`MemorySource` 空实现（`longTermMemory` 恒 `Optional.empty()`）。**MUST NOT 用 `@Primary`**（那是「永远优先用假的」，会永久遮蔽真实实现）；**MUST NOT 在命令类里硬 `new`**（第 20 / 22 节落地时要手改）。真实实现一落地即自动让位（仲裁 8 / FR-020）
- [X] T043 [US3] 新增 `oryxos-channel-cli/src/main/java/io/oryxlabs/oryxos/channel/cli/CliChannel.java`：`channel = cli` 的入站适配——读一行 → 交给 `AgentService.process` → 打印结果，循环往复；`/quit` 退出；**输入流结束（Ctrl+D / 管道读完）等同 `/quit`**、退出码 0；**空行跳过**（不交给引擎、不产生空消息）（FR-004 / Edge Cases）。**MUST NOT 自己拼 `session_id`**——只提供三元组（`cli` / `local` / profile），标识由 `SessionService` 内部拼（FR-006）
- [X] T044 [US3] 新增 `oryxos-cli/src/main/java/io/oryxlabs/oryxos/cli/command/ChatCommand.java`：`--profile <name>`（缺省经 `AgentResolver` 取唯一 Agent，多者报错并列出可选名）、`--message "<text>"` 单条模式（输出后退出、**不进交互循环**）。启动上下文用 `new SpringApplicationBuilder(CliApplication.class).web(WebApplicationType.NONE).bannerMode(Banner.Mode.OFF).run()` 并置于 **try-with-resources**（保证 `/quit` 后上下文关闭、进程正常退出）。**`chat` 本身不碰模型调用与工具调度**——经 `CliChannel` → `AgentService.process`（章程原则 IX）
- [X] T045 [P] [US3] 新增 `oryxos-cli/src/main/java/io/oryxlabs/oryxos/cli/command/ServeCommand.java`：起上下文后**前台常驻**（Ctrl+C 退出）；`--port <8080>` 选项**必须在 `--help` 中并接受解析**，但只回显「端口在第 26 节启用」——**MUST NOT 静默吞掉一个已定参数**；打印「本模式将提供 HTTP 接入，REST 端点在第 26 节接入」；**MUST NOT 起 HTTP 端口、MUST NOT 引入 `oryxos-web`**（仲裁 15）
- [X] T046 [P] [US3] 新增 `oryxos-cli/src/main/java/io/oryxlabs/oryxos/cli/command/GatewayCommand.java`：起上下文后**前台常驻**；打印「本模式将挂载入站 Channel，通道接入在扩展阶段」；**MUST NOT 挂载任何 Channel**（仲裁 15）
- [X] T047 [US3] **常驻语义**：`serve` / `gateway` 的 `run()` **MUST 阻塞**，MUST NOT 启动完就退出——「启动完就退」等于退出码 0 的正常结束，是错的（[research.md](research.md) D15 / D16）
- [X] T048 [US3] 跑 `./mvnw -pl oryxos-cli -am -q -DskipTests install -Ddependency-check.skip=true`；在临时工作区冒烟 `chat --message "..."`（无 key 时应给出**清晰报错**而非 `Failed to configure a DataSource`——后者说明 `application.yaml` 没被读到），并核对启动日志 `Found N JPA repository interfaces` 的 **N > 0**

**Checkpoint**: 12 条命令全部可执行、`--help` 全可用；轻命令秒回、重命令启动上下文

---

## Phase 7: Polish & Cross-Cutting Concerns

- [X] T049 [P] 改 `CLAUDE.md`「当前状态」段**同段两句**（仲裁 17 落地后二者都与事实不符，只改一句会自相矛盾）：① 把「`oryxos-cli` 的 `OryxOsCli` 是 Picocli 主入口，已由 maven-shade-plugin 打成可执行 fat JAR，`java -jar oryxos-cli/target/oryxos-cli-0.1.0-SNAPSHOT.jar --version` 可直接运行」改为 `java -jar oryxos-boot/target/oryxos.jar --version`；② 把同段「**`oryxos-boot` 的 `OryxosApplication` 是 fat JAR 主类** … 启动 Spring Boot + Web 容器」改为「`oryxos-boot` 的 fat JAR 主类是 `OryxOsCli`（`OryxosApplication` 自第 18 节起不再是主类，去留由第 26 节定）；`serve` 本节只起上下文、不起 Web 容器，REST 端点在第 26 节接入」
- [X] T050 [P] 依赖方向核查（[quickstart.md](quickstart.md) §3）：`grep -rn "oryxos-provider\|oryxos-tool\|oryxos-memory\|oryxos-web" oryxos-core/src/main/java oryxos-core/pom.xml` 与 `grep -rn "oryxos-core" oryxos-storage/pom.xml oryxos-storage/src/main/java` 均须 **0 行**
- [X] T051 [P] 发行入口核查（[quickstart.md](quickstart.md) §4）：`grep -c "maven-shade-plugin" oryxos-cli/pom.xml` = **0**；`grep -n "mainClass" oryxos-boot/pom.xml` = **1 行**且值为 `io.oryxlabs.oryxos.cli.OryxOsCli`；并核查打包决策的**四处口径一致**——[spec.md](spec.md) 仲裁 17 / [research.md](research.md) D11 / [plan.md](plan.md) 偏离④ / [contracts/cli-assembly.md](contracts/cli-assembly.md) §1，任一处与落地事实不符即修（四处是刻意分层冗余，代价是必须同步）
- [X] T052 [P] H4 六条全局不变量自查（[plan.md](plan.md) 与章程）：① 涉外 IO 首行过 `Sandbox.enforce`——**本节沙箱未就位，留调用位并注明第 24 节接线**；边界一并写明：CLI 自身的工作区文件操作（`init` 建目录、`profile delete` 物理删目录、`WorkspaceDb` 读写）**不属于 Tool 执行路径，不经 `Sandbox`**，需接线的只有 Tool 的 FILE/SHELL/HTTP 动作；② LLM 调用成败都落 `llm_calls`、工具执行成败都落 `tool_invocations`（本节不新增审计逻辑，核对第 16/17 节的单点未被绕过——**具体核对 `ReActLoop` 传给 `llmClient.chat(...)` 与 `toolExecutor.execute(...)` 的实参仍是 `Session.id()`**，与 T020 ④ 的持久化侧断言合起来闭合 FR-022）；③ `grep` 无明文 key；④ `session_id` 只在 `SessionId` 内拼接；⑤ 无 Reactor / `CompletableFuture` / 自建线程池；⑥ 无 Spring AI 自动工具执行路径
- [X] T053 全量门禁：`./mvnw clean verify -Ddependency-check.skip=true`（含 Spotless / P3C / Checkstyle / SpotBugs+FindSecBugs）**全绿**——实现完成的定义
- [X] T054 [P] 前序节回归：`./mvnw -q test -Ddependency-check.skip=true` 确认第 16 / 17 节全部测试仍绿（跨节契约证据）
- [X] T055 [P] 按 [quickstart.md](quickstart.md) §5 走一遍端到端人工 Demo（需真模型与 `DEEPSEEK_API_KEY`，**只经环境变量注入**），并把 §7 的「未接入」人工项清单交用户
  - **实测说明**：机器可判部分全部跑通（`init` 幂等 / `profile create`+`list` / `chat --message` 起上下文并发出真实模型请求 / `/quit` 与 EOF 退出码 0 / 多 Agent 未指定 `--profile` 报错并列出可选 / `--bogus` 退出码 2 / 轻重命令 0.6s vs 5.9s / `session list` 按最近活跃倒序 / 五种「未接入」文案逐条对号 / 无 `sk-` 串）。**唯一未跑的是真模型对话轮次**——本环境无可用的 `DEEPSEEK_API_KEY`（调用被 DeepSeek 判 401），留给用户人工过；顺带实测确认失败调用仍落 `llm_calls`（`success=0`）

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 无依赖，可立即开始
- **Foundational (Phase 2)**: 依赖 Setup —— **阻塞全部 user story**
- **US1 (Phase 3) / US2 (Phase 4)**: 依赖 Foundational。两者都只动 `oryxos-core` 的测试与 `oryxos-storage` 的测试，**互不阻塞**
- **US4 (Phase 5)**: 依赖 Foundational（要用 `ProfileLoader.loadAllDetailed()` 与 `sessions` 表）
- **US3 (Phase 6)**: 依赖 Foundational + **US4**（对照测试需要轻命令先存在）
- **Polish (Phase 7)**: 依赖全部 user story 完成

### User Story Dependencies

- **US1 (P1)**: Foundational 之后即可开工 —— 不依赖其他 story
- **US2 (P1)**: Foundational 之后即可开工 —— 与 US1 并行，但两者都改 `SessionServiceTest.java` 同一文件，**实际串行**
- **US4 (P2)**: Foundational 之后即可开工
- **US3 (P2)**: 需 US4 的轻命令存在（对照断言）

### Within Each User Story

- 测试先写、先 FAIL，再实现
- 模型 → 服务 → 命令/端点
- 故事独立可测后再进下一个

### Parallel Opportunities

- T001 / T003 / T004 可并行（不同文件）；**T002 与 T004 都改 pom，但不同模块，可并行**
- T006 / T010 / T011 / T013 / T014 可并行（不同文件）
- T024 / T025 可并行；T028~T035 六个轻命令类可并行（不同文件）
- T045 / T046 可并行
- T049~T052 可并行

---

## Parallel Example: Phase 5（US4 的轻命令）

```text
# 六个轻命令类互相独立，可同时开工：
Task: "ProfileCreateCommand.java —— 生成最小 AGENT.md 模板，已存在报错不覆盖"
Task: "ProfileListCommand.java —— 经 loadAllDetailed() 列 Agent 与有效性"
Task: "ProfileShowCommand.java —— 派生后的完整配置 + 校验结果"
Task: "ProfileDeleteCommand.java —— 需确认，--force 跳过"
Task: "ProviderListCommand.java —— 厂商全集 + 引用情况，密钥就绪标未接入"
Task: "ToolListCommand.java —— 经 ToolCatalog 取，本节空表 + 标注"
```

---

## Implementation Strategy

### MVP First（US1 + US2）

1. Phase 1 Setup → Phase 2 Foundational（**阻塞全部**）
2. Phase 3 US1 → **STOP and VALIDATE**：`SessionServiceTest` 幂等 / 隔离 / 标识只此一处全绿
3. Phase 4 US2 → **STOP and VALIDATE**：`SessionRepositoryTest` 建表 / 往返 / 「重启」重查全绿
4. 至此**本节唯一可自动化验收的部分**已交付（spec 明确：「本节唯一值得自动化验收的部分」）

### Incremental Delivery

1. Setup + Foundational → 地基就绪
2. US1 + US2 → 会话持久化可验收（MVP）
3. US4 → 工作区与轻命令可用（Demo 起手式）
4. US3 → 重命令可用，`chat` 端到端跑通（**本节的「可演示」转折点**）
5. 每个 story 独立增值，不破坏前一个

---

## Notes

- [P] = 不同文件、无依赖
- 每个 user story 独立可完成、可测
- **反作弊**：不得删断言、不得 `@Disabled`、不得放宽阈值让测试变绿。实现错 → 修实现；认为测试错 → **停下报告**。未全绿不得宣称完成
- **全程不自动 commit / push / 运行 `scripts/package.sh`**，同步时机由用户决定
- 提交信息末尾加 `Co-Authored-By: Claude Code <noreply@anthropic.com>`
