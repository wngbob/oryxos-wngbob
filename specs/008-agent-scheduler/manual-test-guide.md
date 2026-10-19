# 定时任务（第 25 节）人工验收向导（Step by Step）

**Feature**: `specs/008-agent-scheduler` | **分支**: `class-25` | **Date**: 2026-10-19

**上游文档**：[spec.md](spec.md) · [plan.md](plan.md) · [tasks.md](tasks.md) · [quickstart.md](quickstart.md) · [acceptance-report.md](acceptance-report.md) §四

**适用对象**：第 25 节定时任务的最终形态（`AgentScheduler` + `ScheduleConfig` + 装配处两个 `@Bean` + `serve`/`gateway` 各一行注册）

**目的**：覆盖单测判不了的那一段——**到点了，系统真的自己发起了一次调用，账记在人推同一张表上，失败之后任务还活着**。单测能证明「锁被占时零调用」「异常不外抛且锁被释放」，但「调度线程真的到点了」「真链路失败之后下一次真的还进得来」「`llm_calls` 里那行真的与 `chat` 同形」这三段拼接只在真链路里成立。

**预计耗时**：Step 0~7 约 15 分钟（Step 0 构建 5~10 分钟），另加等待触发的 1~3 分钟 × 若干步。

---

## 实测记录

### 写向导时已实测（2026-10-19）

**不需要模型的每一步都真跑过了**（工作区 `/tmp/oryxos-l25-check`，jar = `oryxos-boot/target/oryxos.jar`，2026-10-19 09:32 那次 `mvn clean verify` 的产物）。命令与输出都对得上：

| 已实测 | 实测结果 |
| --- | --- |
| `oryxos init` | 12 个「新建:」条目 + 「已有（未覆盖）: （无）」，与下文 Step 1 预期一致 |
| Step 1 的 `AGENT.md`（`cron: "* * * * *"` / `zone: Asia/Shanghai`）能否被装载 | `profile list` → `tick-demo  有效  provider=deepseek/deepseek-chat`；`profile show tick-demo` → `schedules: * * * * * @ Asia/Shanghai → 用一句话报一下现在几点` |
| `serve` 启动输出 | `定时任务注册完成：共 1 条`（stderr）→ `serve 模式已启动（Bean 数 328）` → 三行模式说明 → `前台常驻中，Ctrl+C 退出。` |
| **无 `DEEPSEEK_API_KEY` 时 `serve` 能不能起** | **能起、也照常注册**（`共 1 条`）。key 只在**真调模型那一刻**用——所以「key 缺失 / key 错」就是本向导 Step 4 的故障注入手法 |
| **Step 4 的失败路径（真链路，无模型）** | 跑 135 秒抓到 **3 条** ERROR：`14:39:01` / `14:39:59` / `14:40:59`，线程名 `xos-scheduler-1`，报文 `定时任务 tick-demo#1 执行失败：401 - {"error":{"message":"Authentication Fails, Your api key: ****KEY} is invalid ..."}`；进程全程活着（被 `timeout` 杀掉，退出码 124）。**「每分钟一次、连出三次」就是锁被释放的实证**——这本来是 Step 4 第 3 条要人工等的那件事 |
| **失败路径下审计表的样子** | `llm_calls` 3 行，`session_id = scheduler:scheduler:tick-demo`、`provider=deepseek`、`model=deepseek-chat`、`success=0`、`error_message` 非空；`tool_invocations` **0 行**（这次没调工具）；**`sessions` 表 0 行、`session list` 显示「共 0 条」**——见下文「前置事实 ⑦」，这是**预期**不是缺陷 |
| **单条失败隔离**（注册期） | 造 4 个 Agent 一次起：坏 cron → `定时任务注册失败（Agent badcron，任务 badcron#1）：cron 表达式段数非法：'0 9 * *'（支持 5 段或 6 段，实际 4 段）`；坏时区 → `定时任务注册失败（Agent badzone，任务 badzone#1）：时区非法：'Mars/Olympus'`；**同一轮里**另外两条照常注册 → `定时任务注册完成：共 3 条` |
| **Step 2 的多时区注册** | 同一 cron（6 段 `0 9 * * *`）、`zone` 分别为 `UTC` / `Asia/Shanghai`，**两条都注册成功、无报错** |
| 本机 `sqlite3` CLI | **不存在**（`command -v sqlite3` 空）。查库一律走 `python3`（本机 Python 3.12.3 / SQLite 3.45.1） |
| **Step 6 的 FR-012（真链路）** | `printf '/quit\n' \| chat --profile tick-demo` → 退出码 **0**，日志里 `grep -c 定时任务注册完成` = **0**；**同一工作区**起 `serve` 对照 → `grep -c` = **1**。`chat` 起的是同一套 Spring 上下文、却一条都没注册 |
| **Step 3 的重叠（真链路，真模型）** | 6 段 cron `"* * * * * *"`（**每秒**触发）、单次执行约 **1.5 秒**（跑得比触发间隔慢），跑 60 秒 → 33 次调用、**WARN 计数 0**、`tool_invocations` **0 行**。日志里 33 条 `LLM 调用完成` 的间隔恒在 1.1~2.4 秒、两条线程交替——**串行**。**结论：那行 WARN 在真链路上结构上不可达**（原因见 Step 3 三条证据），不是「竞态没抓到」 |
| 表清单（`init` 之后） | `llm_calls / memory_entries / notify_channels / sessions / tool_invocations`——**没有 `scheduled_tasks` / `task_executions`**，那是第 28 节的表（FR-016） |

**没实测的是「成功那一路」**——真模型真调用、真会话落库（Step 1 / 2 / 5）。那正是要你跑的部分。

### 写向导时抓到的四处错（都已改）

**① 判据口径错（初稿）**：初稿把 Step 1 的判据写成「`session list` 里一定多出一条钟推会话」，**这条在失败路径下是假的**：`AgentService.process` 的 `sessionService.save(session)` **在 `reActLoop.run(...)` 之后、且不在 `finally` 里**（`AgentService.java:60-67`），所以这一轮抛异常时**会话一行都不落**——上面那次 135 秒实测里 `llm_calls` 有 3 行而 `sessions` 是 0 行，就是这个原因。

**改法**：Step 1（成功路径）才断言 `sessions` 有行；Step 4（失败注入）明确写「`sessions` 里没有它是**预期**，判据看 `llm_calls`」。

**② Step 0 的 jar 自检命令恒得 0（2026-10-19 复核时实测）**：初稿写的是 `unzip -l "$JAR" | grep -c "core/schedule/..."`，实测**恒返回 `0`**——因为 `oryxos-core` 是**依赖模块**，在 fat JAR 里是 `BOOT-INF/lib/oryxos-core-*.jar` 这个**嵌套 jar**，不是 `BOOT-INF/classes` 下的散装 class。**这条最阴**：命令本身能跑、退出码 0、输出是个干净的 `0`，照初稿的「预期 ≥ 2」判就是「手上是旧 jar / 本节没构建」——一个**假警报**，而它指向的动作是「重新构建」，会白烧十分钟并让人开始怀疑实现。**已改成先 `unzip -p` 抽出嵌套 jar 再查**，实测得 3 行。

**③ Step 2 的 `sed` 会静默产出空文件（同上）**：初稿的 `sed "s/^    zone: .*/    zone: $2/"` 用 `/` 当分隔符，而 `$2` 里第二个值就是 `Asia/Shanghai`——**含斜杠**，`sed` 报 `unknown option to \`s'`，但重定向已经先建好了 `/tmp/ag.md`，于是**留下一个空文件**，再 `cp` 出去就是一个空 `AGENT.md`。实测现象是 `profile list` 里 `tz-sh  无效 ... frontmatter 缺失：文件未以 --- 开头`。**已改成用 `|` 当分隔符**，并在片段后加了一段 `profile list` + `grep` 自查（三个 Agent 必须全部「有效」）。

**④ Step 3 的判据本身不可达（2026-10-19 用户实跑时命中）**：初稿让人用 `http_get https://example.com` 把一次执行拖慢，然后等那行 WARN。**用户重启 `serve` 后始终没等到**。查下来三层，逐层都是初稿的错：**（a）`example.com` 不在 `http.allowed_domains` 里**——模型真去抓会被 `WhitelistSandbox` 立刻拦下，不产生延迟；**（b）那次 `tool_invocations` 是 0 行**——`AGENT.md` 正文写着「直接用一句话回答」，模型听了正文、根本没调工具；**（c）最根本的一层**：`taskScheduler.schedule(task, CronTrigger)` 是**自重新调度**、且 Spring 的 `CronTrigger` 以**上一次的完成时刻**为基准算下一次——**同一任务结构上不会自重叠**，所以那行 WARN 无论怎么调都到不了。**已把 Step 3 整节改成「反面判据 + 说明为什么抓不到」**，并写明三条证据（含反编译 jar 里实际打包的 `spring-context-6.2.19`）。

**前三处的根因与第 24 节向导那两次同类**：只核了「命令能跑」，没核「输出与我写的预期是否一致」。**② 还多一层**：预期写错了方向——我把「命令返回 0」当成了「jar 里没有」，而真因是「命令找错了地方」。**④ 是另一类**：命令能跑、预期也自洽，**但那条路径在产品里根本到不了**——「能跑通」之外还得问一句「这条路径真的会被走到吗」。

### 用户执行（2026-10-19）

**用户按 Step 0~7 全程人工执行**（真 DeepSeek 模型、真 `oryxos.jar`），执行完毕、**未报告任何一步失败**。**留痕口径照 [006 向导](../006-memory-service/manual-test-guide.md#用户执行2026-10-16) / [007 向导](../007-sandbox-whitelist/manual-test-guide.md#用户执行2026-10-17)：只记整体结论、不写步骤级细节**——用户未逐步说明各步输出。

**执行过程中实跑命中两处，均已当场修**：① Step 2 之后**换终端**跑 `profile list` 得到 `Error: Unable to access jarfile `（`$JAR` 为空）→ 「准备」段已补 `echo`/`ls` 自查 + ⚠️ 框，坑表加一行；② Step 3 **始终等不到**那行 WARN → 查下来是**判据本身不可达**（`schedule(task, CronTrigger)` 是自重新调度、以完成时刻为基准算下一次），Step 3 整节已改判为反面判据。两处细节见上文「写向导时抓到的四处错」④ 与「最容易踩的坑」表。

**别把「跑完了」读成「每条判据都被独立证过」**：Step 3 **没有真链路判据**（结构上不可达），它的判卷在单测 `AgentSchedulerTest.runOnce_skipsWhenTaskLockHeld`；Step 0 的 jar 自检、Step 2 的多时区注册、Step 4 的失败恢复二进宫、Step 6 的 FR-012 另有向导作者侧的真链路实测记录（见上表）。

---

## 这份向导要证明什么

[验收报告](acceptance-report.md) §四 把人工项收敛成 **8 条**。本向导把它们拆成可执行步骤，并尽量给每一条配一个**对照**——没有对照的「跑起来了」证明不了任何事（把 `chat` 的会话误读成钟推的会话，同样「跑起来了」）。

| # | 要证明的 | 本向导的步骤 | 通过判据 |
| --- | --- | --- | --- |
| 1 | **到点了真的自己跑了一次**（没人推） | Step 1 | 注册日志 `共 1 条` + `sessions` 里出现 `scheduler:scheduler:tick-demo` + **全程没有人向它发过消息** |
| 2 | **审计与人推同形** | Step 1 尾 | `llm_calls` 里 `session_id = scheduler:scheduler:tick-demo` 的行，**字段与 `chat` 跑出来的行没有任何区别** |
| 3 | **时区按声明的跑**（真链路侧） | Step 2 | 两个不同 `zone` 的 Agent **各自注册成功**（`共 3 条` = `tick-demo` + `tz-utc` + `tz-sh`），不因时区不同而报错或跳过。**落点断言在单测**，人工只确认「注册不失败」 |
| 4 | **重叠触发不会并发改写同一会话**（真链路侧） | Step 3 | 真链路**不出现**「同一 session 被并发写」的迹象。**「上一次尚未结束，本次触发跳过」那行 WARN 在真链路上抓不到**——Spring 的 cron 调度是「跑完再排下一次」，同一任务**结构上不会自重叠**（三条证据见 Step 3）；该分支由单测判卷 |
| 5 | **失败之后任务还能继续跑**（真链路侧） | Step 4 | ① `执行失败` ERROR ② **进程没退出** ③ **下一分钟再次出现同一条 ERROR**（= 锁被释放）④ `llm_calls` 那几行 `success = 0`。**只出现一次之后再无动静 = 永久卡死 = 不通过** |
| 6 | **改 cron 不用重新编译** | Step 5 | 改 `AGENT.md` 的 cron 后**不 `mvn package`**，重启 `serve` 即按新间隔触发 |
| 7 | **`chat` 不注册定时任务** | Step 6 | `chat` 的日志里**不出现** `定时任务注册完成：共 N 条` |
| 8 | **清理** | Step 7 | 只删本向导造的 Agent，工作区本身不动 |

---

## 前置事实（开跑前必读）

1. **底座只实现了 `deepseek` 一个 provider**。`provider.name` 必须落在它里面。**但 `serve` 不需要 key 就能起、也照常注册定时任务**（`${DEEPSEEK_API_KEY:}` 带空默认值）——key 缺失或写错，只在**真调模型那一刻**以 `401 ... Authentication Fails` 的形式失败。Step 4 正是拿这个当故障注入。
2. **`serve` / `gateway` 是重命令**，要起 Spring 上下文；`serve` 只起上下文 + 前台常驻，**不开端口**（REST 在第 26 节）。
3. **日志走 stderr、命令输出走 stdout**（第 20 节的分流）。`定时任务注册完成` 那类日志在 **stderr** 上：`2>/dev/null` 能静音，`| grep` 不会被污染。本向导要抓的日志都按 `2>&1 | grep` 或先重定向到文件再 `grep` 的写法给。
4. **工作区根 = 进程当前目录**。库文件是相对路径 `.oryxos/oryxos.db`，所以**必须 `cd` 进工作区再跑命令**。
5. **没有配置热加载**（与第 24 节白名单同口径）：改 `AGENT.md` 的 `schedules` 要**重启 `serve`** 才生效。Step 5 验的是「**不用重新编译 / 不用重新打包**」，**不是**「不用重启」——这两件事别混。
6. **cron 契约以 5 段 Unix cron 为准**（`分 时 日 月 周`），兼容 6 段 Spring 原生方言。所以：`"* * * * *"` = 每分钟；`"0 9 * * *"` = **每天 09:00**（不是「9 点那一分钟」）；`"*/2 * * * *"` = 每两分钟。段数不是 5 或 6 → 注册失败（报错指名 Agent 与任务，其它规则不受影响）。
7. **`sessions` 行只在「成功那一轮」落库**：`AgentService.process` 的 `save` 在循环之后、**不在 `finally`**（`AgentService.java:60-67`）。所以**失败注入时 `sessions` 里没有钟推会话是预期**，失败的唯一判据是 `llm_calls` 里那几行（`success = 0`）。成功之后 `sessions` 才有行，`session list` 才看得到它。
8. **会话身份固定**：渠道与用户标识恒为字面量 `scheduler`，故 `sessionId = scheduler:scheduler:<profileName>`。同一个 Agent 的历次触发**复用同一个会话**（不是每次新建）。
9. **池固定 2 线程**（FR-019，不做配置键）。语义要分清：**同一个任务**重叠 → 直接跳过（不排队）；**不同任务**同时到点 → 最多 2 个并发，超出的在池里**排队等待**（不是失败、不是丢弃）。
10. **本机没有 `sqlite3` CLI**。查库用 `python3` 的 `sqlite3` 模块（本机 3.12.3 / SQLite 3.45.1）。
11. **⚠️ 本节尚未 commit**：`specs/008-agent-scheduler/`、`oryxos-core/.../schedule/`、`SchedulerAssemblyTest.java` 都是**工作区里的新交付物**，`CLAUDE.md` / `TechnicalSolution.md` / `CliAssemblyConfiguration.java` / 两个命令类是**未提交的改动**。**MUST NOT 用 `git checkout --` 回滚任何文件**——那会直接删掉本节的交付物（第 24 节向导的 Step 9 已经真实造成过一次损失，见 [007 向导](../007-sandbox-whitelist/manual-test-guide.md) 的「抓红留痕」）。本向导的收尾（Step 7）只删**造出来的 Agent 目录**。
12. **`.oryxos/` 是跨升级保留的用户数据**：`init` 幂等、不覆盖已存在文件；Step 7 只删本向导造的 Agent。

---

## 先排除已被单测覆盖的（手工再验一遍不是测试，是重做）

| 已经被单测证明的事 | 谁证明的 |
| --- | --- |
| 注册时 `CronTrigger` 带上了配置的 cron **和时区**（落点在配置时区下是 09:00，且**不等于**错时区那次） | `AgentSchedulerTest.registerAll_passesConfiguredCronAndZone`（`:281`） |
| 锁被占时本次触发**零调用**（`never()`，不是「抛了异常」） | `AgentSchedulerTest.runOnce_skipsWhenTaskLockHeld`（`:352`） |
| 异常不外抛（调度器不死） | `AgentSchedulerTest.runOnce_swallowsExceptionFromProcess`（`:426`） |
| **异常之后锁真的被释放**（二进宫：再触发一次能进来，`times(2)`） | `AgentSchedulerTest.runOnce_releasesLockAfterFailure_secondRunProceeds`（`:436`） |
| 会话三元组固定 `("scheduler","scheduler",profileName)`、两次触发同一 Session | `AgentSchedulerTest:258` / `:267` |
| cron 归一化（5 段前补秒位 / 6 段透传 / 其余报错且**报错里带用户原文**） | `AgentSchedulerTest`（`normalizeCron` 一组） |
| 单条注册失败**不影响同 Agent 其它规则 / 其它 Agent** | `AgentSchedulerTest`（`registerAll_*` 一组） |
| 任务 id 确定性派生（`<profileName>#<序号>`，从 1 起） | `AgentSchedulerTest` |
| 装配面：`ThreadPoolTaskScheduler` 池大小**恰好 2**（`getCorePoolSize()`）、`AgentScheduler` **恰好一个 Bean** | `SchedulerAssemblyTest`（`oryxos-cli`） |
| `chat` **不**注册定时任务（读源码断 `.registerAll()` 只出现在两个常驻命令里） | `SchedulerAssemblyTest.scheduledRegistration_happensOnlyInResidentCommands` |
| 核心引擎零改动（`AgentService` / `ReActLoop` / `ToolExecutor` / `SessionService` / `ProfileLoader` / `Profile.ScheduleSpec`） | `git diff` 为空（报告 §2.3 第 4 条） |

**关键前提**：上表之外，写向导时**已经在真链路上跑过**的还有五条——**注册失败隔离**（`共 3 条` + 两条 `注册失败`）、**失败恢复 / 锁释放**（连出 3 条 ERROR，间隔整分钟）、**多时区各自注册成功**（Step 2）、**`chat` 零注册 + `serve` 恰好 1 条**（Step 6，FR-012）、**重叠不可能发生**（每秒 cron + 1.5 秒执行，60 秒内 33 次调用、0 条 WARN——见 Step 3）。**剩下真正没被证明的是三段**：① **成功那一路**——真模型真调用、真会话落库（Step 1 / 2 / 5）；② **「到点了」这件事本身**（单测里触发是手工调的，没人真等过一分钟）；③ **改 cron 不用重新编译**（Step 5，需要一次真重启）。这三段就是本向导的全部内容。**Step 3 不是「没证明」，是「证明不了、也不该证明」**——那行 WARN 在当前调度形态下结构上不可达，判卷在单测（见 Step 3）。

---

## 准备

**在你要跑下面所有步骤的那个 shell 里执行一次**（本节每个片段都假设 `$JAVA_HOME` / `$JAR` 已在当前 shell 里）：

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export JAR=/home/wngbob/oryxos-wngbob/oryxos-boot/target/oryxos.jar

# 立刻自查（下面这行必须在下面任何一步之前打出真实路径）
echo "JAVA_HOME=$JAVA_HOME"; echo "JAR=$JAR"; ls -l "$JAR"

# API key 不要进 shell 历史、不要粘进任何文档：
read -rsp 'DEEPSEEK_API_KEY: ' DEEPSEEK_API_KEY && export DEEPSEEK_API_KEY && echo
```

> **⚠️ `$JAR` 是本节唯一会丢的环境变量，而且丢得很像「路径写错」**（2026-10-19 用户实跑时命中）：`JAVA_HOME` 在 gitignore 的 `local-env.sh` 里、新开终端也还在，**但 `JAR` 不在**——它只在上面这一段里 `export` 过。换终端 / 新开标签页 / 重连 WSL 之后再跑任何一步，都会得到：
>
> ```text
> Error: Unable to access jarfile
> ```
>
> **末尾那个空字符串就是 `$JAR` 本身**（变量为空 → 展开成空），不是路径拼错。**看到这行就回来重跑「准备」段**，别去查 jar 在不在、更别重新构建。
> 一个更省事的习惯：每个片段都**从「准备」段所在的那个 shell 里连着跑**，别中途换窗口。

---

## Step 0 — 构建，并确认你手上这份 jar 是对的

```bash
cd /home/wngbob/oryxos-wngbob

# 全量门禁（含 Spotless / P3C / Checkstyle / SpotBugs / FindSecBugs / OWASP）
./mvnw -o clean verify
# 预期：BUILD SUCCESS；9 个模块全 SUCCESS；514 条测试 0 failures / 0 errors / 0 skipped

ls -l --time-style=long-iso oryxos-boot/target/oryxos.jar
# 预期：时间戳 = 刚才那次 verify 的时间

"$JAVA_HOME/bin/java" -jar "$JAR" --version
# 预期：版本行正常打印
```

**jar 里真有本节的类**（避免「手上是旧 jar」这类误判）：

```bash
# 注意：oryxos-core 是「依赖模块」，在 fat JAR 里是 BOOT-INF/lib 下的一个嵌套 jar，
# 不是 BOOT-INF/classes 里的散装 class——直接对 fat JAR 跑 unzip -l | grep core/schedule 恒得 0，
# 那是命令写错，不是 jar 没构建（2026-10-19 实测确认）
unzip -p "$JAR" "BOOT-INF/lib/oryxos-core-*.jar" > /tmp/l25-core.jar
unzip -l /tmp/l25-core.jar | grep "core/schedule"
# 预期：3 行——目录一行 + ScheduleConfig.class + AgentScheduler.class
#   0  ... io/oryxlabs/oryxos/core/schedule/
#   1918 ... io/oryxlabs/oryxos/core/schedule/ScheduleConfig.class
#   8618 ... io/oryxlabs/oryxos/core/schedule/AgentScheduler.class
```

---

## Step 1（US1）写一条定时规则，到点自己跑

**造一个 Agent**——只有一条规则：**每分钟**说一句话（每分钟是为了不用等到第二天）。

```bash
cd /tmp && rm -rf oryxos-l25-run && mkdir oryxos-l25-run && cd oryxos-l25-run
"$JAVA_HOME/bin/java" -jar "$JAR" init

mkdir -p .oryxos/agents/tick-demo
cat > .oryxos/agents/tick-demo/AGENT.md <<'YAML'
---
description: 定时任务最小验证 Agent
provider:
  name: deepseek
  model: deepseek-chat
schedules:
  - cron: "* * * * *"
    zone: Asia/Shanghai
    message: 用一句话报一下现在几点
---

你是一个定时播报助手。收到消息后直接用一句话回答，不要反问。
YAML
```

**先确认它被装载了**（轻命令，秒回）：

```bash
"$JAVA_HOME/bin/java" -jar "$JAR" profile list
# 预期：tick-demo  有效  provider=deepseek/deepseek-chat  定时任务最小验证 Agent

"$JAVA_HOME/bin/java" -jar "$JAR" profile show tick-demo
# 预期（关键行）：schedules: * * * * * @ Asia/Shanghai → 用一句话报一下现在几点
```

**起常驻进程**（前台，日志同时到终端）：

```bash
"$JAVA_HOME/bin/java" -jar "$JAR" serve 2>&1 | tee /tmp/l25-serve.log
```

**预期**（顺序与内容）：

```text
... INFO ... i.o.oryxos.core.schedule.AgentScheduler : [] 定时任务注册完成：共 1 条
serve 模式已启动（Bean 数 N）
本模式将提供 HTTP 接入，REST 端点在第 26 节接入
已接受 --port 8080；端口在第 26 节启用
前台常驻中，Ctrl+C 退出。
```

**关键**：出现 `定时任务注册完成：共 1 条`。**没有这一行 = 注册没发生**——回查上面的 `profile list`，以及你是不是在**工作区目录里**起的 `serve`。

**等 1~2 分钟**，然后**另开一个终端**：

```bash
cd /tmp/oryxos-l25-run
"$JAVA_HOME/bin/java" -jar "$JAR" session list
# 预期：多出一条 sessionId = scheduler:scheduler:tick-demo、channel = scheduler、
#       user = scheduler、profile = tick-demo、lastActiveAt = 刚刚

python3 - <<'PY'
import sqlite3
c = sqlite3.connect('.oryxos/oryxos.db')
print("llm_calls（钟推那几行）:")
for r in c.execute("select id, session_id, provider, model, success, created_at from llm_calls order by id desc limit 5"):
    print("  ", r)
PY
# 预期：session_id = scheduler:scheduler:tick-demo 的行；success = 1；字段与 chat 跑出来的行没有区别
```

**US1 通过判据**：① `sessions` 里出现钟推会话；② `llm_calls` 里有它的行；③ **全程没有人向它发过消息**。

> **为什么第 ③ 条才是要害**：前两条在「有人推过它」时同样成立。只有「没人推」才能证明消息是**到点自己拼出来的**。本步全程没跑过 `chat`。

> **若 `sessions` 是空的而 `llm_calls` 有行**：说明那一轮**失败了**（key 错 / 网络不通）——失败路径不落会话（前置事实 ⑦）。先看 `llm_calls` 的 `error_message`。

---

## Step 2（US2）时区按声明的跑

**同一 cron、不同时区**：

```bash
cd /tmp/oryxos-l25-run
for pair in "tz-utc UTC" "tz-sh Asia/Shanghai"; do
  set -- $pair
  # 分隔符必须用 | 不能用 / —— $2 里的 Asia/Shanghai 含斜杠，s/.../.../ 会报
  # "unknown option to `s'"，而报错后 /tmp/ag.md 是空的，会静默产出一个空 AGENT.md
  sed "s|^    zone: .*|    zone: $2|" .oryxos/agents/tick-demo/AGENT.md > /tmp/ag.md
  sed -i 's|^  - cron: .*|  - cron: "0 9 * * *"|' /tmp/ag.md
  mkdir -p ".oryxos/agents/$1" && cp /tmp/ag.md ".oryxos/agents/$1/AGENT.md"
done

# 自查：两个 Agent 都必须「有效」，且 zone 各自不同（tz-demo 的 zone 未被改动）
"$JAVA_HOME/bin/java" -jar "$JAR" profile list
grep -E "cron|zone" .oryxos/agents/tz-utc/AGENT.md .oryxos/agents/tz-sh/AGENT.md
# 预期：tz-utc → cron "0 9 * * *" / zone UTC；tz-sh → cron "0 9 * * *" / zone Asia/Shanghai
```

**重启 `serve`**（无热加载，前置事实 ⑤）：

```bash
"$JAVA_HOME/bin/java" -jar "$JAR" serve 2>&1 | tee /tmp/l25-serve2.log
```

**人工只需确认一件事**：日志里 `定时任务注册完成：共 3 条`——`tick-demo`（每分钟，**Step 2 没有动它**，改的只是它的两份副本）+ `tz-utc` + `tz-sh`，**没有**任何 `注册失败`，两个时区不同的规则**各自注册成功**。（`profile list` 应显示 3 个 Agent 全部「有效」——若某个显示「无效」，回到上面的自查，多半是 `AGENT.md` 被 sed 写空了。）

> **落点断言不在这里**：「`Asia/Shanghai` 的 09:00」与「`UTC` 的 09:00」是**两个不同的时刻**，这件事由单测判（对固定 `Clock` 求 `nextExecution`）。人工等它到点要等到第二天，且**等到了也证明不了**——你看到「它跑了」，但看不出它跑在哪个时区。

> **这一条为什么重要**：时区错了，任务**看起来是成功的**——它确实跑了、也留了审计，只是跑在错误的时刻。没有报错、没有异常，只有「用户以为的早上 9 点」和「实际跑的时刻」对不上。这是最难被发现的一类。

---

## Step 3（US3）上一次没跑完，这次直接跳过

> **⚠️ 这一步没有真链路判据可做——不是向导偷懒，是这条路径在当前实现下到不了。** 下面讲清「为什么」，你只需确认**反面**（真链路里不出现同一会话被并发改写）。

**机器判据**由单测覆盖（占住锁 → 调 `runOnce` → 断言 `process` **零次调用**，`AgentSchedulerTest.runOnce_skipsWhenTaskLockHeld`）。人工要确认的是**真链路上不出现「同一会话被两个线程同时改写」**。

**为什么真链路上抓不到那行 WARN**（2026-10-19 实测 + 反编译核实，三条独立证据）：

1. **注册用的是「跑完再排下一次」的调度形态**：`AgentScheduler:95` 调的是 `taskScheduler.schedule(() -> runOnce(...), new CronTrigger(...))`——**不是** `scheduleAtFixedRate`。
2. **Spring 这一支是自重新调度、且以「完成时刻」为基准**：反编译 fat JAR 里实际打包的 `spring-context-6.2.19` 可见——`ReschedulingRunnable.run()` 的顺序是 `执行任务 → 取 completionTime → triggerContext.update(..., completionTime) → schedule()`；而 `CronTrigger.determineLatestTimestamp()` 取的正是 `lastCompletion()`。
3. **于是下一次触发必然排在上一次跑完之后**——不管 cron 多密、任务多慢，**同一个任务结构上不可能与自己重叠**。

**实测确认**（`/tmp/oryxos-l25-overlap`，真模型、真 jar，跑 60 秒）：cron 取 6 段 `"* * * * * *"`（**每秒**触发）、单次执行约 1.5 秒（**跑得比触发间隔还慢**）、共 33 次调用——**WARN 计数 0**。33 条 `LLM 调用完成` 的间隔恒在 1.1~2.4 秒之间，是**串行**而非并发。

**所以 `tryLock()` 那条 WARN 是「纵深防御」，不是当前唯一防线**：今天框架层面已经保证不重叠，锁保证的是**换一种调度形态也仍然不重叠**——例如将来有人把它改成 `CronTrigger.forFixedExecution(...)`（`spring-context-6.2.19` 里确有此静态工厂）或 `scheduleAtFixedRate`，那两种语义下重叠会真的发生。单测直接调 `runOnce`，判的正是这个「框架不兜底」的层面。

**人工只需确认反面**（不必单独跑，用 Step 1 的 `/tmp/l25-serve.log` 即可）：真链路日志里**不应**出现「同一 session 被并发写」的迹象——同一条 `llm_calls` 时间戳成对出现、或 `tool_invocations` 里同一次调用被记两遍。

> **初稿给的做法已删，两处硬伤**（2026-10-19）：初稿让人把 `message` 改成「先调用 `http_get` 抓取 `https://example.com`，再总结一句」，指望把一次执行拖慢到跨过下一个整分钟。① **`example.com` 不在 `http.allowed_domains` 里**（随包默认只有 `api.duckduckgo.com` / `api.tavily.com` / `open.feishu.cn` / `open.larksuite.com` / `qyapi.weixin.qq.com`），模型真去抓也会被 `WhitelistSandbox` **立刻拦下**，不产生任何延迟；② 实测那次 `tool_invocations` 是 **0 行**——`AGENT.md` 正文写着「直接用一句话回答」，模型听了正文、根本没调工具。**但即便这两点都修好，也仍然抓不到 WARN**——原因就是上面第 3 条。

---

## Step 4（US4）一次跑挂了，调度器不死、锁不放死

**机器判据**由「二进宫」断言覆盖（异常后再触发一次、断言这次能进来）。人工要确认的是**真链路里失败之后任务还能继续跑**。

**做法**（把 key 换成错的，或干脆不设 key——前置事实 ① 已说明 `serve` 照样能起）：

```bash
cd /tmp/oryxos-l25-run
export DEEPSEEK_API_KEY=sk-wrong-on-purpose
"$JAVA_HOME/bin/java" -jar "$JAR" serve 2>&1 | tee /tmp/l25-fail.log
# 等 2~3 分钟再 Ctrl+C
```

**预期**（四条，与写向导时的实测逐条对应）：

1. 日志出现 `定时任务 tick-demo#1 执行失败：401 - {"error":{"message":"Authentication Fails, Your api key: ****KEY} is invalid ...`（**ERROR**）——**失败不静默**；
2. **进程没有退出**（`serve` 还活着，Ctrl+C 才结束）；
3. **等下一分钟**，日志里**再次**出现同一条 ERROR（实测连出 3 条，间隔整分钟）——**这说明锁被释放了**。**只出现一次之后就再无动静 = 任务永久卡死 = 不通过**；
4. `llm_calls` 里那几行 `success = 0`、`error_message` 非空——**失败与人推失败同形**。

```bash
python3 - <<'PY'
import sqlite3
c = sqlite3.connect('.oryxos/oryxos.db')
for r in c.execute("select id, session_id, success, substr(coalesce(error_message,''),1,50) from llm_calls order by id desc limit 5"):
    print("  ", r)
print("sessions 行数:", list(c.execute("select count(*) from sessions"))[0][0])
PY
```

**预期**：`success = 0` 的行在；**`sessions` 行数是 0**——**这是预期，不是缺陷**（前置事实 ⑦：失败路径不落会话）。

> **第 3 条是本节最容易漏的一条**。漏了 `finally unlock()` 的实现：不抛异常（看起来正常）、日志也照打，但**这个任务从此永久卡死**，再也不触发。只有「下一次还进得来」能抓住它。

**恢复**：`export DEEPSEEK_API_KEY=sk-正确值`，重启 `serve`，确认 `llm_calls` 里出现 `success = 1` 的行、且 `sessions` 开始有行。

---

## Step 5（SC-001）改 cron 不用重新编译

```bash
cd /tmp/oryxos-l25-run
# 1) 把 tick-demo 的 cron 改成每两分钟
python3 - <<'PY'
import pathlib
p = pathlib.Path('.oryxos/agents/tick-demo/AGENT.md')
p.write_text(p.read_text().replace('cron: "* * * * *"', 'cron: "*/2 * * * *"'))
print(p.read_text())
PY

# 2) 不重新编译、不重新打包，直接重启（重启是必须的，无热加载）
"$JAVA_HOME/bin/java" -jar "$JAR" serve 2>&1 | tee /tmp/l25-cron.log
```

**通过判据**：触发间隔变成 2 分钟——**没有任何一行 Java 代码被改过、没有任何一次 `mvn package`**。

> **别把它读成「不用重启」**：本节没有配置热加载（前置事实 ⑤）。这条验的是「规则来自配置、不是编译期写死」（FR-003）。

---

## Step 6（FR-012）确认 `chat` 不注册定时任务

```bash
cd /tmp/oryxos-l25-run
"$JAVA_HOME/bin/java" -jar "$JAR" chat --profile tick-demo 2>&1 | tee /tmp/l25-chat.log
# 随便聊一句，然后 /quit 退出

grep -c "定时任务注册完成" /tmp/l25-chat.log
# 预期：0
```

**通过判据**：`chat` 的日志里**不出现** `定时任务注册完成：共 N 条`。`chat` 是交互式短命进程，**不该**注册任何定时任务。

> 顺带对照：`serve` 的日志里 `grep -c` 应当是 **1**（上面 Step 1 的 `tee` 文件）。

---

## Step 7 清理

```bash
cd /tmp/oryxos-l25-run
# 只删本向导造出来的 Agent，不动工作区本身
rm -rf .oryxos/agents/tick-demo .oryxos/agents/tz-utc .oryxos/agents/tz-sh
# 停掉 serve（Ctrl+C）；整个临时工作区可一并删掉
cd /tmp && rm -rf oryxos-l25-run
```

**MUST NOT** 用 `git checkout --` 回滚仓库里的任何文件（前置事实 ⑪）。

---

## 最容易踩的坑

| 现象 | 大概率原因 | 怎么查 |
| --- | --- | --- |
| `Error: Unable to access jarfile`（**末尾什么都没有**） | `$JAR` 为空——**换了终端 / 重连 WSL**，而 `JAR` 只在「准备」段里 `export` 过（`JAVA_HOME` 在 `local-env.sh` 里所以还在，`JAR` 不在） | `echo "JAR=$JAR"`——空的就是它；重跑「准备」段，**别重新构建** |
| 日志里**没有** `定时任务注册完成` | `serve` / `gateway` 的命令体没调到 `registerAll()`；或**不在工作区目录里**起的 `serve` | `profile list` 看 Agent 在不在；确认当前目录（工作区根 = 进程当前目录） |
| 注册完成但 `共 0 条` | 该 Agent 没被装载（frontmatter 坏 / `provider` 段缺失 / 厂商名不在清单） | `profile list` 会把它标成无效并给出原因 |
| `定时任务注册失败（Agent X，任务 X#1）：cron 表达式段数非法：...` | 段数不是 5 或 6 | 报错里带**你写的原文**；5 段 = `分 时 日 月 周` |
| `定时任务注册失败（Agent X，任务 X#1）：时区非法：'Mars/Olympus'` | `zone` 不是合法 `ZoneId` | 用 `Asia/Shanghai` / `UTC` 这类标准名 |
| 到点了但**什么都没发生** | cron 段数或时区理解偏差 | `"0 9 * * *"` 是**每天 9:00**，不是「9 点那一分钟」；每分钟是 `"* * * * *"` |
| `session list` 里**看不到**钟推会话，但 `llm_calls` 有行 | 那一轮**失败了**，失败路径不落会话（前置事实 ⑦） | 看 `llm_calls.error_message`；key 对不对 |
| `llm_calls` 里**没有**钟推的行 | 模型调用根本没发生 | 先看日志里有没有 `定时任务 X#1 执行失败`；有则看它带的原因 |
| 任务只跑了一次就再无动静 | 锁没释放（`finally` 缺失） | **这是缺陷**，不是配置问题——单测的「二进宫」断言本该抓住它 |
| **一直等不到** `上一次尚未结束，本次触发跳过` 这行 WARN | **正常**，不是缺陷——`schedule(task, CronTrigger)` 是「跑完再排下一次」，同一任务结构上不会自重叠 | 不用查。那行是纵深防御分支，判卷在单测；真链路只需确认**没有**「同一 session 被并发写」的迹象（Step 3） |
| 长任务把**其它**任务也推迟了 | 池被占满 | 池固定 2（FR-019）；同时到点的不同任务最多 2 个并发，超出的**排队等待**——**这是预期行为** |
| `oryxos chat` 里也看到定时任务注册日志 | **缺陷**（撞 FR-012） | 源码断言 `SchedulerAssemblyTest` 本该抓住它 |
| 改了 `AGENT.md` 但触发时间没变 | 没重启 `serve` | 无热加载；改配置必须重启（但**不需要重新编译**） |

---

## 附：不跑真模型的那几条（纯单测，秒级）

```bash
cd /home/wngbob/oryxos-wngbob

# 本节两个测试类
./mvnw -o -pl oryxos-core -am test -Dtest=AgentSchedulerTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -o -pl oryxos-cli  -am test -Dtest=SchedulerAssemblyTest -Dsurefire.failIfNoSpecifiedTests=false

# 前序节回归（跨节契约证据）
./mvnw -o clean verify
```

**预期**：全绿（22 + 4 条；全量 514 条）。`AgentSchedulerTest` 覆盖课件 harness 的四条关键回归点 + cron 归一化 + 单条失败隔离 + id 确定性；`SchedulerAssemblyTest` 覆盖装配面（池大小 2）与 FR-012 的源码断言。
