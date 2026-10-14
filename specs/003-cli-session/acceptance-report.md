# 第 18 节验收报告：CLI 与会话持久化 —— 12 条命令 + sessions 表 + JpaSessionService

**Feature**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md) | **Tasks**: [tasks.md](tasks.md) | **Quickstart**: [quickstart.md](quickstart.md)
**Branch**: `main`（当时）| **完成 commit**: `da37705`（2026-10-14）| **任务完成度**: 55 / 55
**结论**: ✅ 节级 DoD 六项满足；harness 已判卷，剩余人工项见 §7

> ⚠️ **本文件是回填报告（2026-10-14 补写）**。第 18 节完成时尚未要求落盘验收报告；本报告按 [`.claude/skills/oryxos-lesson-dev`](../../.claude/skills/oryxos-lesson-dev/SKILL.md) 第 7 步的固定结构重建。
>
> ✅ **本节是 16~18 三节中唯一「零漂移」的一节**——`git diff --stat da37705..HEAD` **输出为空**，即此后无任何提交改动过本节任何文件。故本报告的全部证据（含 §2 测试、§6.4 的 CLI 实测）**既是当时的、也是今天的**，不存在第 16/17 节那样的等价性保留。

---

## 0. 回填方法与证据强度

| 证据类型 | 说明 |
|---|---|
| **实测（今天重跑，强）** | 门禁、测试条数、**CLI 真跑**（`init` / `profile list` / `serve`）、H4 grep——在 `HEAD` 上重跑得出 |
| **据 commit / 文档重建（弱）** | 任务完成度、harness 对号——来自 `da37705` 的 commit 内容与 `tasks.md` 勾选状态 |

**等价性证明（本节独有，一句话）**：

```text
$ git diff --stat da37705..HEAD
（无输出）
```

**无输出 = 本节 commit 之后没有任何提交改动过任何文件**。因此今天的测试运行与 CLI 实测**逐字等于**第 18 节当时的运行。

**本节反向改动了前序节**（详见 §6.1）：第 18 节是 16~18 三节里唯一**主动修改前序节文件**的一节（`ProfileLoader` +47 行、`ProviderService` +3 行、三个第 17 节测试类的签名）。这些改动已逐条记入第 16 / 17 节的回填报告。

---

## 1. 证据一：全量门禁 `mvn clean verify` 全绿

```text
[INFO] BUILD SUCCESS
```

静态检查逐模块执行并全过：`spotless:check` / `pmd:check`（阿里 P3C）/ `checkstyle:check` / `spotbugs:check`（含 FindSecBugs），根模块聚合跑 `dependency-check:aggregate`（`failBuildOnCVSS=7`）。

---

## 2. 证据二：课件 harness 映射表逐类对号

课件 harness 共 **2 个单测类、15 条**，全部落地且非空：

| 课件测试类 | 落地测试类 | 条数 | 说明 |
|---|---|---|---|
| `SessionManagerTest` | **`SessionServiceTest`**（⚠️ 改名，见 §3） | 11 | 会话身份、持久化接缝、序列化往返 |
| `SessionRepositoryTest` | ✅ 同名 | 4 | 建表脚本列齐备、存取往返、模拟重启、查不到返回空 |

**`SessionServiceTest` 11 条逐条**（今天从代码读出，`@DisplayName` 原文 + 落地方法名）：

| # | `@DisplayName` | 方法名 |
|---|---|---|
| 1 | **★ 同一三元组_历次 getOrCreate 都是同一个 Session** | `sameTriple` |
| 2 | 三元组任一不同即不同会话：channel / user / profile 各改一处 | `anyDifferentSegmentYieldsDifferentSession` |
| 3 | **会话标识的拼接只此一处**：形如 `channel:user:profile`，三段逐字来自入参 | `sessionIdIsJoinedFromTripleInOnePlace` |
| 4 | 已落库的会话：再取回来带历史，仍是同一个 | `existingSessionIsRestoredWithHistory` |
| 5 | 未落库的会话：历次 `getOrCreate` 拿到的是空会话，不会凭空长出历史 | `unsavedSessionStartsEmpty` |
| 6 | 按标识取：查不到得 `Optional.empty()`，不抛异常；查得到则原样返回 | `getReturnsEmptyWhenAbsent` |
| 7 | 序列化往返：条数、顺序、角色、内容、工具调用与工具调用标识逐条一致 | `roundTripPreservesEveryMessageField` |
| 8 | 含中文 / 换行 / 引号 / emoji 的内容往返后逐字一致 | `roundTripPreservesTrickyContent` |
| 9 | 空历史：往返后得空列表，不报错 | `emptyHistoryReadsBackAsEmptyList` |
| 10 | `save` 两次：`created_at` 不被覆盖，status 仍是首次写入的 active | `repeatedSaveKeepsCreatedAtAndStatus` |
| 11 | 更新路径原样保留 status / `archived_at`：已归档的会话不会被下一次 `save` 悄悄复活 | `updatePathPreservesArchivedState` |

**`SessionRepositoryTest` 4 条逐条**：

| # | `@DisplayName` | 方法名 |
|---|---|---|
| 1 | 手工脚本建出的 `sessions` 表列齐备：A1 的九列一个不少、一个不多 | `schemaScriptCreatesSessionColumns` |
| 2 | 经 Repository 存取往返：九列逐字保真 | `persistsAndReadsBackEveryColumn` |
| 3 | 模拟重启：独立 JDBC 连接直读，`session_id` 与 `messages_json` 与写入时逐字一致 | `survivesRestartAndKeepsRawColumns` |
| 4 | 按标识查不到：得 `Optional.empty()`，不抛异常 | `findByIdReturnsEmptyWhenAbsent` |

**课件「关键回归测试」对号**：

| 课件守点 | 落地方法名 | 断言保真度 |
|---|---|---|
| `同一三元组_历次getOrCreate都是同一个Session` | `SessionServiceTest#sameTriple` | ✅ 逐条保真：`@DisplayName` 连课件原文的下划线风格都保留了 |
| 会话标识的拼接只此一处 | `SessionServiceTest#sessionIdIsJoinedFromTripleInOnePlace` | ✅ 断言 `session_id` 形如 `channel:user:profile`、三段逐字来自入参 |

---

## 3. 证据三：「本节交付物」逐项存在性核对 + 一处改名披露

| 课件交付物 | 状态 | 落地路径 / 证据 |
|---|---|---|
| 代码 `OryxOsCli` 主入口 | ✅ | `oryxos-cli/.../cli/OryxOsCli.java`（fat JAR 主类） |
| 代码 12 个 `@Command` 子命令类 | ✅ | `oryxos-cli/src/main` 下共 **17** 个命令相关类 = `OryxOsCli` + 16 个 `@Command` 类；`--help` 暴露 9 项（5 leaf + 4 group），叶命令合计 12 |
| 代码 `CliChannel` | ✅ | `oryxos-channel-cli/.../CliChannel.java` |
| 代码 `Session` 实体 | ✅ | `oryxos-core/.../core/session/Session.java`（第 17 节创建，本节扩展 +59 行） |
| 代码 `SessionRepository` | ✅ | `oryxos-storage/.../repository/SessionRepository.java` |
| 代码 持久化实现 | ✅ | `oryxos-core/.../core/session/JpaSessionService.java`（新增 109 行，命名对齐第 16 节 `JpaLlmCallRecorder`） |
| 代码 会话标识 | ✅ | `oryxos-core/.../core/session/SessionId.java`（新增 53 行） |
| 代码 JSON 序列化 | ✅ | `oryxos-core/.../core/session/MessageJson.java`（新增 189 行） |
| 测试 `SessionServiceTest` | ✅ | 11 条，见 §2 |
| 测试 `SessionRepositoryTest` | ✅ | 4 条，见 §2 |
| 表 `sessions` | ✅ | `oryxos-storage/src/main/resources/db/schema.sql`（九列，A1 口径） |

### ⚠️ 一处如实披露：`SessionManager` → `SessionService` 改名

课件 harness 里的测试类名是 `SessionManagerTest`，落地为 **`SessionServiceTest`**。这不是随手改名，而是**沿用第 17 节已报批的裁决**——留痕于 [spec.md:23](spec.md)：

> 持久化接缝的接口名是 `SessionService`，不是课件里的 `SessionManager`：沿用第 17 节已报批裁决（上游 `specs/000-runtime-kernel/tasks.md` T036/T045 用 `SessionService`，T077 明写「统一 `SessionManager`→`SessionService` 命名」）。……课件原文的 `SessionManager` 只作历史措辞保留在本条记录里。

**反作弊自查**：这是**接口命名**的对齐，不是断言削弱；`SessionServiceTest` 的 11 条断言与课件 harness 逐条对应，无一条被删或放宽。

---

## 4. 证据四：前序节全部测试回归绿

以当前 `HEAD` 重跑全量：**87 条测试全绿**。其中本节 15 条、第 16 节 14 条、第 17 节 29 条、第 19 节 29 条。

**跨节契约的关键证据**：`./mvnw -o clean verify` 离线全绿——本节引入的 `sessions` 表是在既有 `schema.sql` 上**追加**（逐条 `CREATE TABLE IF NOT EXISTS`），未影响第 16 节的 `llm_calls` 与第 17 节的 `tool_invocations`，由 `LlmCallRepositoryTest` 2 条 + `ToolExecutorTest` 5 条回归覆盖。

---

## 5. 证据五：H4 六条全局不变量逐条自查

| # | 不变量 | 结论 | 证据（今天在 `HEAD` 上 grep） |
|---|---|---|---|
| ① | 涉外 IO 首行过 `Sandbox.enforce` | **N/A** | 本节不产生涉外 IO |
| ② | LLM / 工具执行成败都落审计 | ✅ | 本节不新增审计逻辑；`session` 包不写 `tool_invocations` / `llm_calls` |
| ③ | 无明文 key | ✅ | 本节不引入凭证；`ConfigLoader` 统一加载，占位走 `${ENV_VAR}` |
| ④ | `session_id` 只在 `SessionManager`（落地名 `SessionService` / `SessionId`）内拼接 | ✅ | **本节的核心不变量**：公式只在 `SessionId.of` 一处——`SessionId.java:37` `channel + SEPARATOR + userId + SEPARATOR + profileName`，且 `:34-36` 对三段逐一 `requireSegment` 非空校验。由 `sessionIdIsJoinedFromTripleInOnePlace` 钉死 |
| ⑤ | 无 Reactor / `CompletableFuture` / 自建线程池 | ✅ | `chat` / `serve` / `gateway` 均为同步阻塞 + Java 21 virtual thread（`application.yaml` 开启），不自建线程池 |
| ⑥ | 无 Spring AI 自动工具执行路径 | ✅ | 本节不接触 Spring AI 工具面 |

### 附加：装配纪律（第 18 节确立，全项目后续节沿用）

本节确立的**装配纪律**是此后各节的硬约束：**非 boot 模块不带 Spring 装配注解，Bean 一律在 `CliAssemblyConfiguration` 显式声明**。

- ✅ 实测：`serve` 启动**无 `BeanDefinitionOverrideException`**（§6.4）。
- ✅ 让位机制：`CliAssemblyConfiguration.java:235` `@ConditionalOnMissingBean(ToolCatalog.class)`、`:246` `@ConditionalOnMissingBean(MemorySource.class)` —— 第 20 节注册真 `ToolRegistry`、第 22 节注册真记忆实现后，本节的空实现**自动让位**，无需回头改代码。此挂账留痕于 [plan.md](plan.md) 与 [research.md](research.md)。

---

## 6. 证据六：变更总结

### 6.1 改动点（据 `da37705` 的 commit 内容）

**新增 —— `oryxos-cli`**（本节主体）
- `cli/OryxOsCli.java` —— Picocli 主入口（fat JAR 主类）。
- 16 个 `@Command` 子命令类 —— `init` / `status` / `chat` / `serve` / `gateway` / `profile`(4 子) / `provider` / `tool` / `session`。
- `cli/CliAssemblyConfiguration.java` —— **本节的核心架构产物**：所有非 boot 模块 Bean 的显式声明处 + `@ConditionalOnMissingBean` 让位接缝。
- `cli/ConfigLoader.java` —— 敏感配置统一加载（章程「配置加载规则」）。

**新增 —— `oryxos-channel-cli`**
- `CliChannel.java` —— CLI 入站渠道。

**新增 —— `oryxos-core` · `session` 包**（在第 17 节 `Session`/`SessionService` 之上扩持久化）
- `JpaSessionService.java`（109 行）、`SessionId.java`（53 行）、`MessageJson.java`（189 行）；`Session.java` +59、`SessionService.java` +31。

**新增 —— `oryxos-storage`**
- `entity/Session.java`、`repository/SessionRepository.java`；`db/schema.sql` 追加 `sessions` 表（九列）。

**新增 —— 测试**：`SessionServiceTest`(11)、`SessionRepositoryTest`(4)。

**★ 修改 —— 前序节文件（本节是本项目第一个「回头改前序节」的节，逐条标出）**

| 前序节文件 | 改动 | 动机 |
|---|---|---|
| `oryxos-core/.../profile/ProfileLoader.java` | **+47 行** | 新增 `loadAllDetailed()` + public `LoadResult` 类型——CLI 要拿到「哪些 Agent 加载失败」的明细，原 `loadAll()` 只返回成功的 |
| `oryxos-provider/.../ProviderService.java` | **+3 行** | `implements LlmClient` + `@Override`——第 17 节留白、本节接线（`ReActLoop` 需要 `LlmClient` 接缝） |
| `oryxos-core/.../engine/ReActLoopTest.java` | 2 行 | `Session.create` 签名机械更新 |
| `oryxos-core/.../engine/PromptBuilderTest.java` | 14 行 | 同上 |
| `oryxos-core/.../engine/AgentServiceTest.java` | 4 行 | 同上 |
| `specs/002-react-loop/contracts/session-model.md` | +3 / −1 行 | 会话标识口径补全 |

**修改 —— 构建**
- `pom.xml`、各模块 `pom.xml` —— 依赖调整；`oryxos-boot` 主类由 `OryxosApplication` 改为 `OryxOsCli`（`oryxos-cli` 的 maven-shade-plugin 移除，退回普通库模块）。

### 6.2 重点 review 清单（按风险排序）

1. **★ `session_id` 的拼接只此一处** —— `SessionId.java:37`。这是 H4-④ 的守点；散落拼接会在多入口（CLI/Web/定时）下产生不一致的会话键。回归测试 `sessionIdIsJoinedFromTripleInOnePlace`。
2. **★ 装配纪律：Bean 只在 `CliAssemblyConfiguration` 声明** —— `CliAssemblyConfiguration.java`。第 18 节实测踩过 `BeanDefinitionOverrideException`（只跑 `--help` 扫不出来，必须真跑重命令），故该纪律写进 CLAUDE.md「最易跑偏的点」。**验证**：`serve` 真跑无撞车（§6.4）。
3. **★ `@ConditionalOnMissingBean` 让位接缝** —— `CliAssemblyConfiguration.java:235,246`。第 20/22 节落地真实现时靠它自动让位；若被误删，届时会 Bean 撞车。
4. **`created_at` / `status` / `archived_at` 的更新语义** —— `JpaSessionService`。`save` 两次不得覆盖 `created_at`，已归档会话不得被下一次 `save` 复活。回归测试 `repeatedSaveKeepsCreatedAtAndStatus` + `updatePathPreservesArchivedState`。
5. **序列化往返逐字保真** —— `MessageJson.java`（189 行）。中文/换行/引号/emoji 不得被转义破坏。回归测试 `roundTripPreservesTrickyContent`。
6. **静态门禁妥协点** —— **无**。

### 6.3 轻 / 重命令时延对比（SC-001，今天实测）

| 命令 | 实测耗时 | 说明 |
|---|---|---|
| `oryxos init`（首次） | **0.869 s** | 不启 Spring 上下文，纯文件操作 |
| `oryxos init`（幂等重跑） | **0.732 s** | 已存在目录/文件一律不覆盖 |
| `oryxos profile list` | **0.523 s** | 同上，纯文件操作 |
| `oryxos serve` | 常驻（20 s 后强杀，退出码 124） | 启 Spring 上下文 |

轻命令亚秒级、重命令才启 Spring —— SC-001 达成。

### 6.4 端到端实测（今天重跑，全部真实输出）

```text
$ java -jar oryxos-boot/target/oryxos.jar --version
0.1.0-SNAPSHOT

$ java -jar oryxos-boot/target/oryxos.jar --help
Commands:
  init      在当前目录创建工作区 .oryxos/（幂等：已存在的目录与文件一律不覆盖）
  status    输出工作区概览（路径 / Agent 数 / 活跃会话数 / 数据库可达性 / 支持的厂商）
  chat      在终端里和 Agent 对话（交互式；--message 走单条模式）
  serve     以 HTTP 接入模式启动（前台常驻；REST 端点在第 26 节接入）
  gateway   以守护进程多通道模式启动（前台常驻；通道接入在扩展阶段）
  profile   Agent 管理（一个 Agent = .oryxos/agents/<name>/ 一个目录）
  provider  模型厂商相关操作
  tool      Tool 相关操作
  session   会话相关操作

$ cd /tmp/oryxos-l18 && java -jar ... init && ls .oryxos/
AGENTS.md  SOUL.md  USER.md  agents/  logs/  mcp_servers.yaml
memory/  oryxos.db  output/  sessions/  skills/

$ ls .oryxos/notify_channels.yaml .oryxos/sandbox.yaml
ls: cannot access '.oryxos/notify_channels.yaml': No such file or directory
ls: cannot access '.oryxos/sandbox.yaml': No such file or directory
  ← ✅ 仲裁 7 达成：init 不生成这两个文件

$ java -jar ... profile list
Agent（共 0 个，目录 .oryxos/agents/）:
  （无：先运行 `oryxos profile create <name>` 建一个）

$ timeout 20 java -jar ... serve          # 退出码 124 = 前台常驻，符合预期
... Finished Spring Data repository scanning in 109 ms. Found 4 JPA repository interfaces.
本模式将提供 HTTP 接入，REST 端点在第 26 节接入
已接受 --port 8080；端口在第 26 节启用
前台常驻中，Ctrl+C 退出。
  ← ✅ 无 BeanDefinitionOverrideException；✅ JPA repository 数 = 4 > 0
```

### 6.5 如何验证

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64

# 1) 全量门禁 —— 预期 BUILD SUCCESS
./mvnw clean verify

# 2) 离线全量回归（87 条测试）—— 预期 BUILD SUCCESS
./mvnw -o clean verify

# 3) 本节 2 个测试类（15 条）—— 预期全绿
./mvnw -o -pl oryxos-core -am test -Dtest=SessionServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -o -pl oryxos-storage -am test -Dtest=SessionRepositoryTest \
  -Dsurefire.failIfNoSpecifiedTests=false

# 4) ★ 关键回归：同一三元组得同一 Session —— 预期 1 条绿
./mvnw -o -pl oryxos-core -am test -Dtest='SessionServiceTest#sameTriple' \
  -Dsurefire.failIfNoSpecifiedTests=false

# 5) H4-④ 会话标识拼接只此一处 —— 预期命中 1 行
grep -n "SEPARATOR" oryxos-core/src/main/java/io/oryxlabs/oryxos/core/session/SessionId.java

# 6) ★ 重命令真跑（只跑 --help 扫不出 Bean 撞车）—— 预期退出码 124、无 Bean 撞车
timeout 20 java -jar oryxos-boot/target/oryxos.jar serve 2>&1 | grep -c "BeanDefinitionOverrideException"
# 预期输出 0
```

---

## 7. 剩余人工项（课件「做完怎么验」，harness 判不了）

1. **`oryxos chat` 多轮对话 + `/quit`** —— ⚠️ **未做**。需要真 key（真模型）。`serve` 已证明 Spring 上下文能起、无 Bean 撞车；`chat` 的交互循环本身未人工跑过。
2. **轻命令秒回、重命令才启动 Spring** —— ✅ 已由 §6.3 实测打勾。
3. **「Found N JPA repository interfaces」的 N > 0** —— ✅ 实测 N = 4。
4. **三种运行模式共享同一份 Profile 和 Session 存储** —— ✅ 结构上成立（`ConfigLoader` + `CliAssemblyConfiguration` 唯一装配点）；`serve` 实测读到同一 `oryxos.db`。三模式各自真跑一次仍属人工项。
5. **12 个子命令都能跑、`--help` 正常** —— ✅ `--help` 实测正常（§6.4）；`init` / `profile list` / `serve` 已真跑，其余命令待人工逐个过。

---

## 8. 备注

- ℹ️ `docs/class/`（课件目录）**已随仓库跟踪**——2026-10-14 由仓库所有者决定入库（本条原写「未入库」，据此更正）。
- 本报告为**回填**，写入时**未 commit / 未 push**。
- **本节是 16~18 三节中唯一零漂移的一节**（`git diff --stat da37705..HEAD` 为空），故其证据强度最高——全部实测即当时快照。
- **跨节挂账**：`OryxosApplication` 的去留归第 26 节；两个空接缝（`ToolCatalog` / `MemorySource`）让位归第 20 / 22 节。
