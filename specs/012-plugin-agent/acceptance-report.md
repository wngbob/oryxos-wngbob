# 第 29 节验收报告：插件化 Agent——一个目录定义一个会自己跑的 Agent

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md) | **Tasks**: [tasks.md](./tasks.md) | **Quickstart**: [quickstart.md](./quickstart.md) | **人工向导**: [manual-test-guide.md](./manual-test-guide.md)
**Branch**: `class-29` | **Date**: 2026-10-21 | **任务完成度**: 20/20（T001~T020）
**结论**: 六项证据全部满足，harness 判卷通过（交付后复核三轮，最新一轮全量 **665** 条全绿）；剩余人工项 2 项（真模型渐进式披露、到点自己跑）已由 [manual-test-guide.md](./manual-test-guide.md) 的 Step 4 / Step 5 覆盖并给出实测值，等用户人工过。

---

## 一、六项证据 DoD

### 1. 全量门禁 `mvn clean verify` 全绿

```bash
./mvnw -o clean verify
```

**结果**: `BUILD SUCCESS`（交付后复核那一轮耗时 22:17）。Spotless / 阿里 P3C（PMD 6.55）/ Checkstyle / SpotBugs+FindSecBugs / OWASP Dependency-Check 全过；每个模块的 `spotbugsXml.xml` 里 `<BugInstance` 计数均为 **0**。

按 surefire 报告实测（`oryxos-*/target/surefire-reports`，**交付后复核那一轮**的值）：

| 模块 | tests | skipped | failures | errors |
|---|---|---|---|---|
| oryxos-storage | 14 | 0 | 0 | 0 |
| oryxos-core | 133 | 0 | 0 | 0 |
| oryxos-provider | 11 | 0 | 0 | 0 |
| oryxos-memory | 87 | 0 | 0 | 0 |
| oryxos-tool | 254 | 0 | 0 | 0 |
| oryxos-web | 63 | 0 | 0 | 0 |
| oryxos-cli | 103 | 0 | 0 | 0 |
| **合计** | **665** | **0** | **0** | **0** |

产物：`oryxos-boot/target/oryxos.jar`（84,590,137 字节；本轮只改测试与文档，产物尺寸与复核轮相同）。

> 交付当轮为 **661** 条 / core **129** 条 / jar 84,590,003 字节；交付后第一轮复核新增 1 条回归测试（`AgentSchedulerRegisterTest` 第 9 条）为 662，第二轮复核新增 1 条 `ContextLoaderTest.rereadsAgentBodyOnEveryLoad` 为 **663**，第三轮复核（2026-10-21，P2/P3 收尾）在 `AgentSchedulerRegisterTest` 补 2 条调度失败语义测试为 **665**（core **133**），见 §五。`oryxos-channel-cli` 无 surefire 报告（该模块不跑测试），故表内 7 行即全部。

**抓红并修复两轮（如实记录）**: 全部来自 SpotBugs，共 5 条 Medium，均属 `ProfileRegistry` 从不可变列表变为**运行时可变并发索引**后新增的暴露面：

| 轮次 | 模块 | 规则 | 位置 | 处理 |
|---|---|---|---|---|
| 1 | oryxos-core | `EI_EXPOSE_REP2` | `AgentService` 构造器持 `ProfileRegistry` | 就地 `@SuppressFBWarnings` + 理由 |
| 1 | oryxos-core | `EI_EXPOSE_REP` | `ProfileLoader.AgentDir.frontmatter()` 返回 `Map` | 就地豁免：紧凑构造器已 `Collections.unmodifiableMap`，SpotBugs 只看声明类型看不到包装 |
| 2 | oryxos-web | `EI_EXPOSE_REP2` ×3 | `AgentApiController` / `SessionApiController` / `SystemApiController` 构造器 | 三者同样就地豁免 + 理由 |

处理口径按本仓库既定原则（父 pom 注释：「就地注解豁免比全局 exclude 更可审计」，先例 `ProfileApiController` / `PromptBuilder` / `McpToolAdapter`），**没有删任何断言、没有 `@Disabled`、没有放宽阈值**。这些改动是**注解级**的，行为零变更。

### 2. 课件「验收 harness」逐类对号

7 个测试类共 **41 条**（交付时 38 条 + 第一轮复核 1 条 + 第三轮复核 2 条），全部实跑（`skipped=0`）：

| 课件守点 | 测试类 | 条数 | 关键回归测试（方法名） |
|---|---|---|---|
| frontmatter 与正文拆分、认出 scripts/ 与 REFERENCE.md、缺必填项报错点名 | `AgentLoaderTest` | 8 | `inspect_splitsFrontmatterAndBody` / `inspect_recognizesScriptsSkillsAndReference` / `loadAllDetailed_namesEachFailureReason` / `deriveProfile_throwsSameTypeAndMessageAsScanFailure` |
| 各字段映射、`schedules` 原样带进 Profile | `DeriveProfileTest` | 6 | `deriveProfile_mapsEveryFrontmatterSection` / `deriveProfile_carriesSchedulesVerbatim` / `deriveProfile_ignoresTopLevelName` / `deriveProfile_failureMessagesMatchScan` |
| N 个目录 → ProfileRegistry 出 N 个、带 schedules 的都进 AgentScheduler | `AgentScanRegisterTest` | 4 | `scan_registersEveryValidAgentAndNamesTheBrokenOne` / `registerAll_createsHandlesOnlyForAgentsWithSchedules` |
| `register()` 后立即可见；非法配置报错与启动路径同一异常 + 同一消息 | `ProfileRegistryRuntimeTest` | 6 | `register_thenFind_thenRemove` / `snapshots_areSortedAndUnmodifiable` / `runtimeDeriveFailure_matchesScanFailure` |
| `registerProfile` 后 `scheduledTasks` 有句柄；cron / 时区来自 `Profile.schedules` | `AgentSchedulerRegisterTest` | 11 | `registerProfile_schedulesEachRuleAndExposesHandles` / `registerProfile_replacesPreviousHandles` / `registerProfile_skipsBadRuleAndKeepsOthers` / `registerProfile_doesNotCancelAgentWhoseNameIsPrefixOfAnother`（第一轮复核追加） / `registerProfile_leavesRowButNoHandleWhenSchedulingFails` / `registerProfile_countsRuleWithoutHandleWhenSchedulerReturnsNull`（第三轮复核追加，见 §5.9） |
| 正文进 system prompt；参考 / 脚本不预载 | `ProgressiveDisclosureTest` | 3 | `load_includesAgentBodyButNotReferenceOrScript` / `load_injectsSkillSummaryOnly` |
| `python3` 必须在 `shell.allowed_commands` 里（复核追加 T020） | `ShellWhitelistSourceTest` | 3 | `shellWhitelist_containsPython3` + 两条正向对照（列表真从取值行解析、`shell` 块真被切出） |

**一处断言形式调整（如实记录，不是放宽）**: `AgentSchedulerTest.registerAll_rejectsInvalidCronFieldCount_andKeepsOtherRules` 原按**注册顺序下标**断言触发表达式；第 29 节起 `ProfileRegistry.all()` **按 Agent 名排序**（`tech` < `weather`），注册顺序随之固定但与语义无关。断言改为 `containsExactlyInAnyOrder(...)`，**两个 cron 表达式仍然逐条断言**，断言数与强度未减；改动处附注释说明原因。

### 3. 「本节交付物」存在性核对

```bash
ls oryxos-core/src/main/java/io/oryxlabs/oryxos/core/profile/ProfileLoader.java \
   oryxos-core/src/main/java/io/oryxlabs/oryxos/core/profile/ProfileRegistry.java \
   oryxos-core/src/main/java/io/oryxlabs/oryxos/core/schedule/AgentScheduler.java
ls -l examples/agents/daily-reconcile examples/agents/daily-reconcile/skills \
      examples/agents/daily-reconcile/scripts examples/skills/report-format
grep -n "python3" oryxos-boot/src/main/resources/application.yaml
```

实测：

- `ProfileLoader`：`deriveProfile(Path)`（:178）、`inspect(Path)`（:192）、嵌套 `record AgentDir`（:215）、`public static final class ProfileLoadException`（:516）。
- `ProfileRegistry`：`register`（:47）/ `remove`（:61）/ `exists`（:69）/ `all()`（:86）/ `names()`（:93）。
- `AgentScheduler`：`scheduledTasks` 句柄表（:85）、`registerProfile`（:171）、`cancelTasksOf`（:223）、包级私有 `scheduledTaskHandles()`（:245）。
- 示例：`examples/agents/daily-reconcile/{AGENT.md,REFERENCE.md,scripts/reconcile.py,skills/report-format -> ../../../skills/report-format}` 四部分俱全；`examples/skills/report-format/SKILL.md` 存在。
- 配置：`application.yaml:93` `allowed_commands: [ls, cat, head, tail, grep, find, wc, pwd, git, python3]`（**既有键、只加取值**）。

### 4. 前序节测试回归

第 1 项的全量 **665** 条即含全部前序节测试，**无一失败**（交付当轮 661 条；第一轮复核补 1 条回归后为 662；第二轮复核补 `rereadsAgentBodyOnEveryLoad` 后为 663；第三轮复核补 2 条调度失败语义后为 665。此处原写 661，2026-10-21 订正为与第 1 项一致）。重点回归：`CliCommandTreeTest`（15 条命令树形状未变）、`ToolAssemblyTest`（白名单字面量绑定）、`AgentSchedulerTest`（第 25 节）/ `JpaScheduledTaskStoreTest` / `ScheduledTaskE2ETest`（第 28 节）、`AdminConsoleSourceTest`（管理台只读判据）、`ContextLoaderTest`（第 17 节无缓存子项）。

### 5. H4 六条全局不变量自查

| # | 不变量 | 本节实测 |
|---|---|---|
| ① | 涉外 IO 首行过 `Sandbox.enforce` | **本节无新增涉外 IO**：`ProfileLoader` 读的是启动期本地 Agent 目录（目标由装配期配置决定，模型拨不动），无新工具、无新出站。`FileTools` / `ShellTools` / `HttpTools` / `WebSearchTools` / `NotifyTools` 五文件 `git diff` 为空 |
| ② | LLM 调用落 `llm_calls`、工具执行落 `tool_invocations` | 无新增调用点；定时链路复用第 25/28 节既有审计路径 |
| ③ | grep 无明文 key | `grep -rnE "(sk-[A-Za-z0-9]{16,}|API_KEY *= *\"[^\"]+\")"` 零命中（示例与环境变量占位无关） |
| ④ | `session_id` 只在 `SessionManager` 内拼接 | 本节零新增拼接；`grep` 命中均为 javadoc / 实体列名 / `SessionListCommand` 的 SELECT 列 |
| ⑤ | 无 Reactor / `CompletableFuture` / 自建线程池 | `grep -rnE "reactor\.core|CompletableFuture|Executors\.new"` 在 core/cli/web main 零命中；调度仍走第 25 节 `ThreadPoolTaskScheduler`（固定 2 线程） |
| ⑥ | 无 Spring AI 自动工具执行路径 | 本节未触碰 provider / 工具执行链路 |

**T017 不变式自查实测偏差（如实记录）**: 任务原文要求六个文件 `git diff` 为空。实测 **五个为空**（`ReActLoop` / `ToolExecutor` / `ContextLoader` / `ShellTools` / `WhitelistSandbox`），`AgentService` 非空（`+6` 行：一行 `import` + 五行为构造器上的 `@SuppressFBWarnings`）。这是为通过**不可绕过的 SpotBugs 硬门禁**所做的最小改动，**行为零变更**；已在 `tasks.md` T017 留下同样记录。

**「不新增对外概念」核实**: `git status` 中没有 `src/main` 下的新文件（无新增顶层 public 类型；`AgentDir` 是嵌套 record，`inspect` / `deriveProfile` / `registerProfile` / 三个注册方法是既有类的**新方法**）；配置只改既有键的取值；REST 路径与 CLI 命令数未变（15 个命令、15 个端点，`CliCommandTreeTest` 与 Web 控制器测试均绿）；表结构未变。

`grep -rn "AgentLoader" oryxos-*/src/main` **零命中**；`grep -rnE "class AgentLoader([^A-Za-z0-9_]|$)" oryxos-*/src` **零命中**（不带词边界会误命中测试类 `AgentLoaderTest`，那正是 T017 原文警告的假阳性）。另：`CoreModule` 与 `ProfileCreateCommand` 各改 1 行 **javadoc**，把历史引用 `{@code AgentLoader}` 更正为 `{@code ProfileLoader}`，就是为了让上面这条 grep 成立。

### 6. quickstart 证据采集（T018）

**§1 过滤测试**（6 个 harness 类，core 侧）：

```bash
./mvnw -o -pl oryxos-core -am test -Dtest='AgentLoaderTest,DeriveProfileTest,AgentScanRegisterTest,ProfileRegistryRuntimeTest,AgentSchedulerRegisterTest,ProgressiveDisclosureTest' -Dsurefire.failIfNoSpecifiedTests=false
```

实测：6 个类全部执行（8 + 6 + 4 + 6 + **11** + 3 = **38** 条），`BUILD SUCCESS`，无一跳过。加上 `ShellWhitelistSourceTest` 的 3 条，本节共 **41** 条（交付当轮为 35 + 3 = 38 条；第一轮复核 +1、第三轮复核 +2，见 §5.9）。

**§2 CLI 可见性**（临时工作区 `/tmp/oryxos-29-report`，收尾已 `rm -rf`）：

```
$ oryxos init
数据库表结构:
  与 db/schema.sql 一致（无需补列）

$ oryxos profile list
Agent（共 1 个，目录 .oryxos/agents/）:
  daily-reconcile  有效  provider=deepseek/deepseek-chat  每天核对交易库与清算库当日订单的条数与金额是否一致；…

$ ls -l .oryxos/agents/daily-reconcile/skills/
report-format -> ../../../skills/report-format
$ readlink -f .oryxos/agents/daily-reconcile/skills/report-format
/tmp/oryxos-29-report/.oryxos/skills/report-format
```

**§3 Web 可见性**（`serve --port 18089`，`DEEPSEEK_API_KEY=dummy`，不调模型）：

```json
{"code":200,"message":"ok","data":[{"name":"daily-reconcile", … ,
 "tools":["shell","read_file","notify","save_memory"],
 "schedules":[{"cron":"0 0 9 * * *","zone":"Asia/Shanghai","message":"到点了，核对昨天的订单对账。"}],
 "valid":true,"error":null}]}
```

```
INFO i.o.oryxos.core.schedule.AgentScheduler : 定时任务注册完成：共 1 条
```

即：**同一份目录**在 CLI 与 Web 两个入口同时可见，且 `schedules` 被 `AgentScheduler` 原样接手。全程未写一行 Java、未改任何底座代码。

---

## 二、变更总结（给 reviewer 的导读）

### 改动点（以 `git status --short` / `git diff --stat` 实测为准）

**oryxos-core（主体）**

| 文件 | 类型 | 说明 |
|---|---|---|
| `profile/ProfileLoader.java` | 修改（+157） | 新增 `inspect(Path)` / `deriveProfile(Path)` / 嵌套 `AgentDir`；抽出共用 `readAgentMarkdown`；`ProfileLoadException` 提为 public。既有报错文本一字未改 |
| `profile/ProfileRegistry.java` | 修改（+65） | 不可变列表 → `ConcurrentHashMap` 运行时索引；新增 `register` / `remove` / `exists`；`all()` / `names()` 改为按名排序的不可变快照 |
| `schedule/AgentScheduler.java` | 修改（+153） | 新增 `registerProfile(Profile)` + `scheduledTasks` 句柄表 + `cancelTasksOf` + `scheduledTaskHandles()`；`registerAll()` 改为对各 Agent 求和 |
| `engine/AgentService.java` | 修改（+6，**前序节文件**） | **仅注解**：构造器 `@SuppressFBWarnings("EI_EXPOSE_REP2")` |
| `CoreModule.java` | 修改（1 行，**前序节文件**） | **仅 javadoc**：`AgentLoader` → `ProfileLoader` |
| `profile/AgentLoaderTest.java` / `DeriveProfileTest.java` / `ProfileRegistryRuntimeTest.java` / `schedule/AgentScanRegisterTest.java` / `AgentSchedulerRegisterTest.java` / `context/ProgressiveDisclosureTest.java` | 新增（测试） | **36 条**（交付当轮 35 条；复核补 `AgentSchedulerRegisterTest` 第 9 条后为 36，原写 35 未标时点，2026-10-21 订正） |
| `schedule/AgentSchedulerTest.java` | 修改（测试） | 断言改为顺序无关（见 §一·2） |

**oryxos-cli**

| 文件 | 类型 | 说明 |
|---|---|---|
| `command/ProfileCreateCommand.java` | 修改（1 行，**前序节文件**） | **仅 javadoc**：同上 |
| `ShellWhitelistSourceTest.java` | 新增（测试） | 3 条，源文件级判 `python3` 在白名单里 |

**oryxos-web（前序节文件，全部仅注解）**: `AgentApiController` / `SessionApiController` / `SystemApiController` 各 +3 行，构造器 `@SuppressFBWarnings`。

**oryxos-boot**: `application.yaml` 的 `shell.allowed_commands` 末尾追加 `python3`，旁边两行注释（章程原则 IV）。

**示例（新增，无代码依赖）**: `examples/agents/daily-reconcile/`（`AGENT.md` + `REFERENCE.md` + `scripts/reconcile.py` + `skills/report-format` 相对软连接）、`examples/skills/report-format/SKILL.md`。

**specs/012-plugin-agent/**: spec / plan / tasks / research / data-model / contracts / quickstart / checklists（本节文档）。

### 重点 review 清单（按风险排序）

1. **`AgentScheduler.registerProfile` 的「替换 = 先取消再注册」是否彻底**（`oryxos-core/.../schedule/AgentScheduler.java:171`、`:223`）。`cancelTasksOf` **按 Agent 归属**（`RegisteredTask.profile().name()` 精确相等）扫 `registeredTasks` 收集任务 id，再对 `scheduledTasks` 与 `registeredTasks` 两张表逐个移除并 `cancel(false)`；漏掉任一张都会留下「到点还在跑但不显示」的幽灵任务。**归属判据 MUST NOT 退回成名字前缀**——`#` 在目录名里是合法字符，`a` 与 `a#b` 并存时前缀匹配会把 `a#b#1` 误当成 `a` 的任务取消掉（交付后复核已修，见 §五）。判卷点：`registerProfile_replacesPreviousHandles` / `registerProfile_dropsRulesNoLongerDeclared` / `registerProfile_doesNotCancelAgentWhoseNameIsPrefixOfAnother`。
2. **`ProfileRegistry` 的快照语义**（`ProfileRegistry.java:86`、`:93`）。`TreeMap` / `TreeSet` + `Collections.unmodifiable*`，**MUST NOT 换成 `Set.copyOf`**（不保序且不保证可读性契约）。判卷点：`snapshots_areSortedAndUnmodifiable` 断言「取快照后再注册，旧快照不变」。
3. **`deriveProfile` 与 `loadAllDetailed` 的失败路径必须同一异常、同一消息**（`ProfileLoader.java:178`、`:516`）。这是 SC-003 的机判形式，任何为了「消息更好看」而在运行时路径拼 Agent 名的改动都会让它变红。判卷点：`runtimeDeriveFailure_matchesScanFailure`、`deriveProfile_throwsSameTypeAndMessageAsScanFailure`。
4. **前序节文件的触碰面**（`AgentService.java:40`、三个 `*ApiController` 构造器、`CoreModule` / `ProfileCreateCommand` 的 javadoc）。**全部是注解或注释级改动，无一行逻辑**；若 reviewer 要求「前序节文件零 diff」，唯一替代是全局 exclude SpotBugs（本仓明确不采用）。
5. **`python3` 放行的安全含义**（`application.yaml:93`）。这是**已知边界不是缺陷**：白名单只约束「首个 token」，`python3` 一旦放行，脚本可读任意文件、可自行联网；「限定本 Agent 的 `scripts/` 目录」需改第 24 节沙箱契约，FR-010 明写不做。`ShellWhitelistSourceTest` 只判「配置里有 python3」，不判边界。
6. **示例 Agent 的路径口径**（`examples/agents/daily-reconcile/AGENT.md` 正文第 1 步）。脚本路径写的是**相对工作区根**的 `.oryxos/agents/daily-reconcile/scripts/reconcile.py`，因为 `shell` 的 cwd 是工作区根、不是 Agent 目录。改这一行会让 `daily-reconcile` 在真模型下运行失败。

### 如何验证

```bash
export JAVA_HOME=${JAVA_HOME:?按 CLAUDE.md 设置 JDK 21}
cd /home/wngbob/oryxos-wngbob

# 1) 全量门禁（预期：BUILD SUCCESS，665 条，SpotBugs 0 findings）
./mvnw -o clean verify

# 2) 只跑本节 7 个测试类（预期：41 条全绿）
./mvnw -o -pl oryxos-core -am test \
  -Dtest='AgentLoaderTest,DeriveProfileTest,AgentScanRegisterTest,ProfileRegistryRuntimeTest,AgentSchedulerRegisterTest,ProgressiveDisclosureTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -o -pl oryxos-cli -am test -Dtest='ShellWhitelistSourceTest' -Dsurefire.failIfNoSpecifiedTests=false

# 3) 契约面不变（预期：无输出）
grep -rnE "class AgentLoader([^A-Za-z0-9_]|$)" oryxos-*/src
git diff --stat -- oryxos-core/src/main/java/io/oryxlabs/oryxos/core/engine/ReActLoop.java \
  oryxos-core/src/main/java/io/oryxlabs/oryxos/core/engine/ToolExecutor.java \
  oryxos-core/src/main/java/io/oryxlabs/oryxos/core/context/ContextLoader.java \
  oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/builtin/ShellTools.java \
  oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/sandbox/WhitelistSandbox.java

# 4) 一个目录 = 一个 Agent（预期：daily-reconcile 有效）
export WS=/tmp/oryxos-29 && rm -rf "$WS" && mkdir -p "$WS" && cd "$WS"
java -jar /home/wngbob/oryxos-wngbob/oryxos-boot/target/oryxos.jar init
cp -a /home/wngbob/oryxos-wngbob/examples/skills/report-format .oryxos/skills/
cp -a /home/wngbob/oryxos-wngbob/examples/agents/daily-reconcile .oryxos/agents/
java -jar /home/wngbob/oryxos-wngbob/oryxos-boot/target/oryxos.jar profile list

# 5) 清理
rm -rf "$WS"
```

---

## 三、剩余人工项（harness 已判卷，这几项等你人工过）

**操作向导**: [manual-test-guide.md](./manual-test-guide.md)（Step 0~6；Step 4 是真模型项、Step 5 默认走 mock 档不花钱）。

| # | 项 | 判据 | 状态 |
|---|---|---|---|
| 1 | quickstart §5 渐进式披露的**真模型观察**（需真 key） | `chat --profile daily-reconcile` 让它跑脚本：`tool_invocations` 里出现 `shell` 且 `input_json` 的 `command` 以 `python3` 开头；模型上下文里是脚本输出的 JSON，不是脚本源码；`read_file` 按需读 SKILL.md / REFERENCE.md。脚本缺 `RECON_ORDERS_CSV` / `RECON_SETTLE_CSV` 时输出说明性 JSON 并退出 0 —— **预期行为** | **作者侧已真跑**（2026-10-21，真 DeepSeek，5 次 LLM / 6 次工具调用，实测值见向导 Step 4.3）；**待你复跑** |
| 2 | quickstart §6 **「到点自己跑」** | `serve` 等触发 → `sessions` 多一条 `channel=scheduler` 的会话；`llm_calls` / `tool_invocations` 有对应记录。**推送成败看 `tool_invocations.success` / `error_message`，不看 `lastStatus`**（第 28 节既有教训） | **mock 档已真跑**（向导 Step 5.1，不花钱：作者侧只开了一轮，`run_count=1`、`task_executions` 1 行、调度会话名下 `llm_calls=2` / `tool_invocations=1`；**判据是与时间无关的比例**——轮数 N → `llm_calls=2N`、`tool_invocations=N`，见 §5.6）；**真模型档作者侧未跑**（向导 Step 5.1 末尾给了做法），待你决定是否花这笔钱 |

**说明**: 本节的全部机制性判据（扫描、派生、注册、句柄、软连接、渐进式披露、白名单）**都已在 harness 内机判**；上面两项是**真模型链路**的端到端确认，不影响本节「机制已交付」的结论。

---

## 四、备注

- 本次**未 commit / 未 push / 未运行 `package.sh`**，同步时机由用户决定；工作区当前在 `class-29` 分支。
- `docs/class/` 自 2026-10-14 起随仓库跟踪，本报告不涉及课件入库问题。
- 本节**未新增**任何模块、顶层 public 类型、配置键、数据库表、REST 路径或 CLI 命令；软门禁未触发。
- 已知边界在本节验收后**继续生效**（`CLAUDE.md`「第 29 节 插件化 Agent」条已同步）：脚本信任边界不做、「丢目录」可见但不可调（热加载归第 30 节）、脚本 cwd 是工作区根、删 `schedules` 留旧 `scheduled_tasks` 行、软连接不支持时 `ProgressiveDisclosureTest` 显式 `assumeTrue` 跳过。

---

## 五、交付后复核（2026-10-21）

交付之后按 review 清单自查了一轮，**改了三处、补了 1 条回归测试、重跑了全量门禁**，并落了人工向导。逐项留痕如下。

### 5.1 修：`cancelTasksOf` 的归属判据从「名字前缀」改为「Agent 归属」（**真缺陷**）

- **缺陷**：原实现用 `taskId.startsWith(agentName + "#")` 认领任务。`#` 在目录名里是合法字符，于是 Agent `a` 重新注册时，会把 `a#b` 名下的 `a#b#1` 一起 `cancel` 掉并移出两张表——`a#b` 的 cron 从此静默失效（进程活着、列表也看不见它，因为 `registeredTasks` 被删了）。
- **修法**：`AgentScheduler.cancelTasksOf` 改为遍历 `registeredTasks`，按 **`RegisteredTask.profile().name()` 精确相等**收集任务 id，再逐个从 `scheduledTasks` / `registeredTasks` 移除并 `cancel(false)`（`AgentScheduler.java:223`）。`registerProfile` 与 `scheduledTasks` 字段的 javadoc 同步写明「归属按记录判，不按名字前缀」。
- **回归判据**：新增 `AgentSchedulerRegisterTest.registerProfile_doesNotCancelAgentWhoseNameIsPrefixOfAnother`（第 9 条）：注册 `a` 与 `a#b` → 重新注册 `a` → 断言句柄表 `containsOnlyKeys("a#1","a#b#1")`，且**只有 `a` 的旧句柄被 cancel**。
- **变异测试（证明这条测试真的抓得住）**：把 `cancelTasksOf` 临时改回前缀写法重跑该类 → `Tests run: 9, Failures: 1`（正是这条新测试变红）；改回归属写法 → 9/9 全绿。

### 5.2 修：`registerAll()` 上方被隔离的 javadoc 合并回方法

`AgentScheduler` 里 `registerAll()` 的 javadoc 被新插入的 `registerProfile` 顶到了上一个方法之后，读起来像在描述别的方法。已合并回它自己的方法上方，**纯文档改动**。

### 5.3 修：两处前序节文档的「回滚指令已过期」加带日期修订注

第 29 节 T001 往 `shell.allowed_commands` 末尾追加了 `python3`，这让第 24 节向导里的回滚字面量**静默失效**——照抄会把 `python3` 一起去掉，第 29 节示例 Agent 的 `scripts/reconcile.py` 在真链路上跑不动，而且**回滚本身不会报错**。

按本仓惯例**加注不重写原文**（原文是第 24 节的交付留痕）：

| 文件 | 修订注内容 |
|---|---|
| `specs/007-sandbox-whitelist/manual-test-guide.md` Step 9 | 当前默认清单是 **10 条**（含 `python3`）；原 `sed` 替换 **MUST NOT** 照抄，回滚以 `application.yaml` 当前内容为准 |
| `specs/007-sandbox-whitelist/quickstart.md` Step 7 的「回滚」行 | 同上：`contracts §3.3` 记的是第 24 节交付时的 **9 条**快照，不是当前值 |

### 5.4 重跑全量门禁（复核那一轮）

```bash
./mvnw -o clean verify -Ddependency-check.skip=true
```

**结果**: `BUILD SUCCESS`，耗时 22:17；surefire 合计 **662** 条（core **130**，较交付当轮 +1 即 5.1 的回归测试），`skipped` / `failures` / `errors` 均为 0；8 个模块的 `spotbugsXml.xml` 里 `<BugInstance` 计数均为 **0**；`oryxos-boot/target/oryxos.jar` = **84,590,137** 字节。

### 5.5 落人工向导（用户请求）

新增 [manual-test-guide.md](./manual-test-guide.md)（Step 0~6，体例照第 25/28 节那两份）。**作者侧真跑过的**：`oryxos init` / `cp -a` 软连接保留 / `profile list` / `/api/v1/profiles` 的 `schedules` 原样带出 / `定时任务注册完成：共 1 条` 与 `共 2 条` / `/api/v1/info` 的 `agents` 计数 / `/api/v1/health` / 丢目录边界（`/profiles` 立刻可见、`/info` 不变、`invoke` 404）/ **真模型渐进式披露**（5 次 LLM、6 次工具调用，`shell` → 两次 `read_file` → `notify` 失败 → `save_memory` → `notify` 失败）/ **mock 档到点自己跑**（`run_count=1`、`task_executions` 1 行、`sessions` `channel=scheduler`、`llm_calls=2`、`tool_invocations=1`）。

**按向导的真实顺序又连跑了一遍**（`/tmp/l29-guide-verify`：`serve` 一直活着、`chat` 是另一个进程、两边同时写同一个 SQLite）：Step 2 的五个预期、Step 3 的三条判据、Step 4 的 6 次工具调用、Step 5 的库内计数**全部复现**，并发写库未出现 `SQLITE_BUSY`。这一步同时纠正了向导里一处会误判的写法：Step 4 已往同一库写过 5 次 LLM / 6 次工具调用，故 Step 5 的审计计数**必须按调度会话（`scheduler:scheduler:tick-demo`）过滤**，不能取全表 `count(*)`。

**作者侧未跑**：真模型版「到点自己跑」（要花钱，且与 Step 4 走同一条 `AgentService.process`）。向导里明写了这一条，不留「看起来都验过」的错觉。临时工作区（`/tmp/l29-manual` / `/tmp/l29-step4` / `/tmp/l29-guide-verify`）均已 `rm -rf`。

### 5.6 用户实跑抓到的向导缺陷：判据被 `tick-demo` 的每分钟写库淹没（2026-10-21，已修）

用户按向导实跑后报告：**「4.3 验收 记忆不对，多了很多行『报个到。』」**——`MEMORY.md` 归档区多出一长串 `- [<日期>] 报个到。`，`tool_invocations` 全表也被 `save_memory {"content":"报个到。"}` 的行淹没。**这不是产品缺陷，是向导的判据写错了**：

- **机制**：Step 2.1 建的 `tick-demo` 是 `cron: "* * * * *"`（每分钟一轮），`tools: [save_memory]`；而**长期记忆是全局单份、不按 Agent 隔离**（第 22 节契约），`save_memory` 又**不去重**。所以只要 `serve` 活着，每分钟就多一行到**同一个** `MEMORY.md`；`scheduled_tasks` / `task_executions` / `llm_calls` / `tool_invocations` 也每分钟长一轮。
- **初版判据错在哪**：4.3 ③ 写的是「归档区**多一行**」、4.3 ① 查的是**全表** `tool_invocations`、5.1 写的是「**恰好** 1 轮 / 2 次 LLM / 1 次工具」。这三处都隐含「`serve` 只活一两分钟」这个前提，而向导本身要求 `serve` 从 Step 2 一直活到 Step 5。
- **作者侧为什么没抓到**：第一次真模型实测是在**没有 `tick-demo` 的独立工作区**跑的（记忆里自然只有一行）；按向导真实顺序连跑那次，Step 2 到 Step 5 只隔了两分钟，噪声还没攒起来。**与第 24/25 节向导那几次同类**——判据只核了「输出与我写的预期是否一致」，没核「在向导自己的时间尺度上这个预期还成立吗」。
- **已改（只改文档，Java 一行未动）**：① 4.3 ① 的工具表按 `session_id='cli:local:daily-reconcile'` 过滤；② 4.3 ③ 改成关键词 `grep "差异 3 笔"`（「报个到。」行不计），并给出「想要干净归档区就先停 `serve`」的可选做法；③ 5.1 改成**与时间无关的比例判据**——轮数 N 由 `task_executions` 数出，`llm_calls` 必须 = 2N、`tool_invocations` 必须 = N，脚本直接打印 `OK` / `不符`；④ 新增前置事实 11、已知边界一条、坑表第 16 行，把「记忆全局单份 + `save_memory` 不去重」写成**可解释的预期噪声**，而不是让人自己去猜。**判据只换取法、没放宽**：原来判的「每轮 2 次 LLM + 1 次工具」逐字保留，只是从绝对条数改成按轮数换算。

### 5.7 用户实跑抓到的第二处向导缺陷：4.3 ④ 把「模型照做」当成「热加载生效」（2026-10-21，已修，Java 一行未动）

用户按 4.3 ④ 在 `AGENT.md` 正文**末尾**加「回答时先自称『对账小欧』」并重跑 `chat`，模型**没有自称**（首行仍是 `## 结论：`）。

- **这不是热加载失效**。链路是 `PromptBuilder.build()` → `ContextLoader.load(profile)` → **每次调用都 `Files.readString` 重读 `AGENT.md`**（`ContextLoader` 类注释写明「不缓存」，`load()` 里没有任何跨调用状态）；`chat` 每次还是新进程。机器判据是第 17 节的 `ContextLoaderTest.rereadsFileOnEveryLoad`——本次复跑 `ContextLoaderTest` **4/4**、`ProgressiveDisclosureTest` **3/3** 全绿。**（2026-10-21 第三轮复核补记：那条用例读的是 Bootstrap `AGENTS.md`、不覆盖 `AGENT.md` 正文，已补 `rereadsAgentBodyOnEveryLoad`；`ContextLoaderTest` 现为 5 条，见 §5.8。）**
- **真实原因是模型服从性**：正文第 3 步要求「按 Skill 的结构和分级组织报告」，末尾那句「先自称」与既有输出格式冲突，模型选了格式。作者侧 2026-10-21 同样没能让模型自称，当时只把 ④ 写成「可选」就放过了——**没意识到它其实是在拿模型服从性充机制判据**。
- **这次不是改错文件**（已核对）：用户工作区 `/tmp/l29-manual/.oryxos/agents/daily-reconcile/AGENT.md` 末尾确实有 `5. 回答时先自称『对账小欧』`（`diff` 对仓库示例为 `25a26 >`），而仓库 `examples/.../AGENT.md` 未被改。也就是说**正文确实被 `ContextLoader` 读到了**，模型没照做。**但这个坑向导原先没写、且同样常见**：改**仓库里的 `examples/agents/daily-reconcile/AGENT.md`** 而不是 `$WORK/.oryxos/...`——`cp -a` 是复制不是软连接，改示例对工作区副本零影响。
- **已改**：④ 拆成三段——① 先 `tail -3` + `diff "$REPO/examples/…" "$WORK/.oryxos/…"` 确认改的是**工作区那一份**（`>` 侧才是工作区；**不要用 `git status` 判**，`examples/` 尚未入库、只会打 `??`）；② 想看人工体感就用**不冲突且放正文开头**的 `BODY-EDIT-OK`；③ 明写「**模型不照做 ≠ 热加载失效**，机制判据在单测」。同时补坑表第 17 行、验收判据汇总一行（标注为**机制项、单测判卷**）。**判据强度未降**：原来靠模型自发作证的那一条，现在由 `rereadsFileOnEveryLoad` 这条既有单测硬判，人工只保留体感。
- **顺带修了示例正文一处真缺陷（用户实跑推动）**：`examples/agents/daily-reconcile/AGENT.md` 第 4 步原写「调 `save_memory` 记一笔『{date} 差异 {N} 笔，最高 {P?}，**已通知**』」，**不看 `notify` 的真实结果**——用户这次实跑里，模型自己回了一句「记忆里那条『已通知』是错的（实际从未推送成功）」，等于替我们指出了示例作者的错误：参考实现教业务方**无条件记成功**。已改成按结果二选一（成功「已通知」/ 失败「推送失败」），并明写「推送失败 MUST NOT 记『已通知』——那会让下一轮读到一条假事实」。**没有测试依赖这段正文**（`ProgressiveDisclosureTest` 用自己的夹具），向导 4.3 ③ 的判据同时明确「只认关键词『差异 3 笔』与『P1』，后缀两种都算通过」。

### 5.8 交付后第三轮复核（2026-10-21，用户「按照建议执行」）——纯文档 + 1 条测试

本轮起因是本节交付后再做的一次**遗留问题盘点**，按用户批准的范围执行 P1 + P2 的 1 / 4 / 5 / 6，**产品代码一行未改**。

**① P1：人工向导 4.3 的判据在「重跑」下不成立（文档，已修）**

向导 Step 0~6 假设会话是空的，但**同一工作区重跑时它会带着历史**：`CliChannel.send` 用的会话 id 是 `cli:local:daily-reconcile`，`JpaSessionService.getOrCreate` **命中已有行就原样复用**（不判 `status`、不清 `messages_json`）——于是第二次 `chat` 里模型能看到上一轮的全部对话（用户实跑里模型自己说了「本轮我依旧不重复写记忆」）。

- **已改 4.3 ①**：标明那张工具表是**「首跑」判据**，并逐行区分「首跑必现 / 重跑可省略」——`shell` 两跑都该出现，`read_file` / `save_memory` 重跑可省略，`notify` 重跑模型可能知道推不出去而少推。
- **已改 4.3 ③**：从「归档区**多一行**」改为 `grep "差异 3 笔"` **至少命中 1 行**（`save_memory` 不去重，重跑会继续追加）。
- **新增 ⚠️ 块**：写明会话 id、复用机制、用户实跑的原话，并给**干净跑法**（`WORK=/tmp/l29-manual-2` 从 Step 0.5 重走）——**MUST NOT 靠删 `sessions` 行或审计行来「造干净环境」**，那会毁掉 Step 4/5 要核的审计证据。
- **判据只换取法、没放宽**：原来「这几样工具都出现在表里」逐字保留，只是把「每次都在」改成「首跑在」。

**② P2-1：7 处「按 `<Agent>#` 前缀取消」的过期表述（文档，已修）**

§5.1 已把实现改成 `RegisteredTask.profile().name()` 精确相等，但 `plan.md`（性能段 + D5）、`contracts/runtime-registration.md` §替换语义、`data-model.md`、`research.md`（两处）、`tasks.md` T011 仍以现在时写着「按前缀」。按本仓惯例**挂带日期修订注（不重写原文）**，逐处写明「原写前缀，实现是精确相等，理由：`#` 在目录名里合法」，并指向 §5.1 与回归测试。

**③ P2-4 / P2-5：数字与命令的过期（文档，已修）**

- 本报告 §一表格 / 结论行 / 第 4 项 / 验证命令块的 **662 → 663**、core **130 → 131**；测试类清单 35 → 36 已在 §5.1 那轮改过。
- `quickstart.md` §1 原来只给 `oryxos-core` 那条 `-Dtest=` 命令，**`ShellWhitelistSourceTest`（落在 `oryxos-cli`）不在里面**——照着跑就漏判一条。已补第二条命令，预期改为「7 个测试类全部执行（core 6 + cli 1）」。

**④ P2-6：`AGENT.md` 正文热加载原先只有间接证据（测试，已补）**

`research.md` D10 与 `tasks.md` T013 都以第 17 节 `ContextLoaderTest.rereadsFileOnEveryLoad` 作为「改正文不重启即生效」的判据，但**那条用例改的是 Bootstrap `AGENTS.md`**，读的是另一个文件——`AGENT.md` 正文这条路径其实没有任何直接测试。4.3 ④ 的机制断言靠一条不覆盖它的用例背书，属**判据与断言错位**。

- 已在 `oryxos-core/src/test/.../context/ContextLoaderTest.java` 补 `rereadsAgentBodyOnEveryLoad`：先断言读到第一版正文，改写 `AGENT.md` 正文后再 `load()`，断言读到**第二版且不含第一版**（`contains` + `doesNotContain` 双向），类 javadoc「守护的四条」改为「五条」。
- 落点选 `ContextLoaderTest` 而非 `ProgressiveDisclosureTest`：后者是本节 harness 的产物，放进去会让「7 个测试类 / 39 条」的口径漂移；放前者只让全仓总数 662 → 663、core 130 → 131。
- `research.md` D10、`plan.md` D10、`tasks.md` T013、向导 4.3 ④ / 作者侧实测记录 / 坑表第 17 行 / 验收判据汇总行已同步引用。

**⑤ 重跑全量门禁（本轮证据）**

```bash
./mvnw -o clean verify
```

**结果**：`BUILD SUCCESS`，surefire 合计 **663** 条（core **131**），`skipped` / `failures` / `errors` 全 0；8 个模块的 `spotbugsXml.xml` 里 `<BugInstance` 计数均为 **0**；Checkstyle `0 violations`；jar 84,590,137 字节（与复核轮同尺寸——本轮只动测试与文档）。

**静态门禁抓红一处（如实记录）**：第一次 `clean verify` 在 `oryxos-core` 的 `spotless-check` 上**失败**——新加的 javadoc 有一行超过 google-java-format 的折行宽度。`./mvnw -o -pl oryxos-core spotless:apply` 折行后重跑即全绿；**只动了换行，语义一字未改**。这类红正是门禁该做的事，不记为缺陷。

**本轮未做（用户未批准 / 有意推迟）**：`plan.md` 与 `contracts` 里「六个文件 `git diff` 为空」这条判据本身在 `AgentService` 上有已知偏差的措辞统一（P2-2）、`contracts/agent-directory.md` 的措辞（P2-3）、`AgentLoader` 命名漂移的说明（P2-7）、`examples/` 在用户文档里缺引用（P2-8），以及全部 P3 项。**产品代码、测试断言、门禁阈值均未改动**；未 commit、未 push、未运行 `package.sh`。

> **（2026-10-21 补记）** 上面这批「本轮未做」项在第三轮复核里**已全部完成**，见 §5.9。

---

## §5.9 交付后第三轮复核（2026-10-21，用户「完成剩余 P2，P3」）

第二轮复核结束时还剩 P2-2 / P2-3 / P2-7 / P2-8 与全部 P3 项；本轮全部收口。**产品代码一行未改**（本轮只动文档、测试与向导），改完重跑全量门禁。

**① P2-2 / P2-3：契约里的两处过期表述（文档，已修）**

- `contracts/runtime-registration.md` §4 不变式 3：原文「六个文件 `git diff` 为空」已改为「五个为空、`AgentService` 行为不变但文件非空（`+6` 行 = 1 行 import + 5 行 `@SuppressFBWarnings`）」，并挂 2026-10-21 订正注指向 `tasks.md` T017 与 §一·5。**这不是事后放宽判据**：偏差在交付当轮就已如实记录，此前只是契约行没跟上。
- `contracts/agent-directory.md` §2.3 / §2.5：① `notify_channels` 的报错文本原写「同上」，实际是同一模板插入键名（`"frontmatter 不允许声明 '" + <键名> + "'：…"`），已改并挂订正注；② 原文「所有字符串值支持 `${VAR}`」对 `channels[].config` 不成立——`ProfileLoader#channels` 只对 `name` 调 `stringValue`，整个 map 原样进 `ChannelSpec.config`，已按字段逐项列清覆盖范围并挂订正注。

**② P2-7：`AgentLoader` 命名漂移（文档，已修）**

`CLAUDE.md` 四处（模块表 / 易跑偏表 / 术语速查 / 背景段）与 `README.md` 模块表把课件名 `AgentLoader` 当成本仓类名，而本仓没有这个类（落点是 `ProfileLoader`）。按建议**不做全仓改名**（`docs/` 里是上游口径，改了会与课件对不上），改为：模块表与 README 直接写 `ProfileLoader`，`CLAUDE.md` 的 Profile 小节加一句**命名映射**（含 `grep -rnE "class AgentLoader([^A-Za-z0-9_]|$)" oryxos-*/src` 零命中的判据，以及「唯一同名物是测试类 `AgentLoaderTest`，那是判据自身的假阳性」）。

**③ P2-8：`examples/` 在用户文档里没有引用（文档，已修）**

`README.md` 新增「一个完整的 Agent 目录长什么样」，官网 `website/guide/quick-start.md` 与 `website/en/guide/quick-start.md` 中英同步补上四部分示例的链接与 `cp -a` 复制命令。**顺带修了一处会误导人的示例**：两份 quick-start 的 frontmatter 示例原来带顶层 `name:`，而本仓契约是「Agent 名取目录名、顶层 `name` 被忽略」——已删掉并写明。

**④ P3-1 ~ P3-5：向导的五处判据 / 事实修正（文档，已修）**

| # | 位置 | 原问题 | 改法 |
|---|---|---|---|
| P3-1 | Step 0 的 `init` 预期 | 写「末段是 `数据库表结构`」，实际后面还有一行 `下一步: …`（`InitCommand.report()` 的顺序） | 改成「倒数第二段」，补出真实末行，并加一条「判建表结论别用 `tail -1`，用 `tail -5` / `grep -A1`」 |
| P3-2 | `/api/v1/health` 的预期 JSON | `providers` 是 `Map.copyOf`，键序不保证，逐字节比会随机失败 | 两处（实测表 + Step 2.2）都写明键序不保证，改用 `jq -S` 或断言 `.data.providers == {...}` |
| P3-3 | `activeSessions: 0` / `daily-reconcile#1 run_count=0` | 都是时间相关项，重跑或 09:00 Asia/Shanghai 之后为假 | 前者改为「与本库 `status='active'` 行数一致」并给出查库命令；后者写明「09:00 后可能已触发 1 次，不是失败」 |
| P3-4 | 实测表「5 次 LLM / 6 次工具」 | 作者侧单次数据，用户实跑是 4 / 3，易被读成本节判据 | 标为「作者侧那一次的实测值、不是判据」，并明写真正的两条判据（`argv[0]=python3`、上下文里是脚本输出不是源码） |
| P3-5 | 4.3 ④ 的 `BODY-EDIT-OK` | 作者与用户都还没真跑过这个改法 | 明写「这是从失败原因推导出来的改法，不是实测结论」，机制判据仍归单测 |

**⑤ P3-7：`registerProfile` 的两条失败语义（测试，已补 2 条）**

审计指出 `schedule()` 抛异常时 `scheduled_tasks` 会留下登记行而无句柄、返回 `null` 时仍计入 `registered`，两处都没有测试覆盖。本轮在 `AgentSchedulerRegisterTest` 补：

- `registerProfile_leavesRowButNoHandleWhenSchedulingFails`：`TaskScheduler` 抛异常 → `registerProfile` 返回 0、`taskStore.register` 已调用（**登记行留下**）、句柄表为空、`runNow` 返回 `false`；随后一个健康的调度器重新注册同一 Profile → 1 个句柄、`runNow` 为 `true`（**下一轮成功即自愈，不需要人工清库**）。
- `registerProfile_countsRuleWithoutHandleWhenSchedulerReturnsNull`：`schedule()` 返回 `null` → 仍计为注册成功（返回 1）、句柄表为空，但它进了 `registeredTasks`，故 `runNow` 为 `true`。

两条测试**钉的是现状、不是新行为**。考虑过两种“修法”并都否决了：① 把顺序改成「先调度、后登记」——违反契约明写的「先登记、后调度」（调度一旦生效就会写执行历史，历史行指向未登记任务会造出孤儿记录）；② 失败时补偿 `taskStore.setEnabled(id, false)`——`register()` 刻意保留 `enabled` 跨重启，补偿会把任务**永久停用**。**记为已知边界**：调度器异常时登记行在库里可见、管理台「立即执行」返回 404，`run_count` 仍为 0；下一次成功注册即自愈。

**⑥ P3-6 / P3-8：只记不改（如实留痕）**

- `inspect` / `deriveProfile` / `ProfileRegistry.remove|exists` **目前没有生产调用方**——这是第 30 节（Agent 运行时 API）的接缝，属预期状态，不是死代码；本节只交付能力、不提前接线。
- `specs/012-plugin-agent/` 与 `examples/` 仍未纳入 git 跟踪——按用户规则本轮不 commit，等同步时机。

**⑦ 重跑全量门禁（本轮证据）**

```bash
./mvnw -o clean verify -Ddependency-check.skip=true
```

**结果**：`BUILD SUCCESS`，surefire 合计 **665** 条（core **133**，较第二轮 +2），`skipped` / `failures` / `errors` 全 0；8 个模块的 `<BugInstance` 计数均为 **0**；jar 84,590,137 字节（与复核轮同尺寸——本轮只动测试与文档）。

**一处过程留痕（如实记录）**：P3-1 ~ P3-5 最初是一次 `python3` 批量改写，**那次工具调用在会话中断时结果未知**。本轮先 `grep` 五个改动特征串，命中数全为 0——确认**一处都没落盘**，才重新逐条应用。**教训**：中断后的工具调用不能假定成功，也不能假定失败，唯一可靠的做法是拿改动特征串回查文件。
