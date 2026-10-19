# 验证向导：定时任务（第 25 节）

**Feature**: `specs/008-agent-scheduler` | **Branch**: `class-25` | **Date**: 2026-10-18

本向导分两半：**上半是自动化判卷**（`mvn test` 全绿即通过，不需要模型、不需要网络），**下半是真链路人工项**（需要真模型 + 真 `oryxos.jar`）。

**先说清楚哪一半能自动判**：

| 判据 | 谁判 | 需要什么 |
| --- | --- | --- |
| 注册参数（cron + 时区）正确 | 单测 | 无 |
| 锁被占时**零调用**、不排队 | 单测 | 无 |
| 异常不外抛 + 锁真的释放（二进宫） | 单测 | 无 |
| 会话三元组固定、两次触发同一会话 | 单测 | 无 |
| cron 归一化 / 单条失败隔离 / id 确定性 | 单测 | 无 |
| 装配面：池大小 2、`chat` 不注册 | 单测 + 源码断言 | 无 |
| **到点真的自己跑了** | **人工** | 真模型 + `DEEPSEEK_API_KEY` |
| **改 cron 不用重新编译** | **人工** | 同上 |
| **审计表里留下与人推同形的账** | **人工** | 同上 |

---

## Step 0 准备：构建 + 工作区 + 凭证

```bash
# 1) 全量门禁（含 P3C / SpotBugs / FindSecBugs / OWASP）
./mvnw clean verify

# 2) 打包 fat JAR
./mvnw clean package -DskipTests

# 3) 建工作区（幂等）
java -jar oryxos-boot/target/oryxos.jar init

# 4) 凭证（真链路才需要；纯单测不需要）
export DEEPSEEK_API_KEY=sk-xxxx
```

**预期**：`verify` 全绿；`init` 之后 `.oryxos/` 下出现 `agents/` / `memory/` / `oryxos.db` 等。

---

## Step 1（US1）写一条定时规则，到点自己跑

**造一个 Agent**——它只要一条规则：**每分钟**说一句话（每分钟是为了不用等到第二天）。

```bash
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

**先看它有没有被装载**（轻命令，秒回）：

```bash
java -jar oryxos-boot/target/oryxos.jar profile list
java -jar oryxos-boot/target/oryxos.jar profile show tick-demo
```

**预期**：`tick-demo` 出现在列表里；`profile show` 能打出它的 provider 与 schedules。

**起常驻进程**：

```bash
java -jar oryxos-boot/target/oryxos.jar serve
```

**预期启动输出**（顺序与内容）：

```text
serve 模式已启动（Bean 数 N）
本模式将提供 HTTP 接入，REST 端点在第 26 节接入
已接受 --port 8080；端口在第 26 节启用
前台常驻中，Ctrl+C 退出。
```

**关键**：日志里（**stderr** 上）应出现 `定时任务注册完成：共 1 条`。**没有这一行 = 注册没发生**，回查 Step 0 的 `profile list`。

**等 1~2 分钟**，然后**另开一个终端**：

```bash
java -jar oryxos-boot/target/oryxos.jar session list
```

**预期**：多出一条 `channel = scheduler`、`user = scheduler`、`profile = tick-demo` 的会话，`sessionId` 形如 `scheduler:scheduler:tick-demo`，`lastActiveAt` 是**刚刚**。

**再看审计**（人推与钟推**同形**的证据）：

```bash
sqlite3 .oryxos/oryxos.db \
  "select session_id, provider, model, success, created_at from llm_calls order by id desc limit 5;"
```

**预期**：`session_id` 是 `scheduler:scheduler:tick-demo` 的那几行——**与 `chat` 跑出来的行在字段上没有任何区别**（同一张表、同一套字段、同一条写入路径）。

> 没有 `sqlite3` 命令时的替代：`python3 -c "import sqlite3;print(*sqlite3.connect('.oryxos/oryxos.db').execute('select session_id,provider,model,success from llm_calls order by id desc limit 5'),sep='\n')"`

**US1 通过判据**：① 会话表里出现钟推会话；② `llm_calls` 里有它的行；③ **全程没有人向它发过消息**。

---

## Step 2（US2）时区按声明的跑

**改时区，看落点变**。把 Step 1 的 Agent 复制一份，**同一 cron 表达式**、**不同时区**：

```bash
mkdir -p .oryxos/agents/tz-utc .oryxos/agents/tz-sh
for pair in "tz-utc UTC" "tz-sh Asia/Shanghai"; do
  set -- $pair
  # 分隔符用 | 不能用 /：$2 里的 Asia/Shanghai 含斜杠，s/.../.../ 会报
  # "unknown option to `s'"，而重定向已先建好目标文件 → 静默留下一个空 AGENT.md
  # （2026-10-19 复核实测：`profile list` 显示 tz-sh 无效「frontmatter 缺失」）
  sed "s|^    zone: .*|    zone: $2|" .oryxos/agents/tick-demo/AGENT.md \
    > .oryxos/agents/$1/AGENT.md
done
```

把两条规则都改成**每天 09:00**（`0 9 * * *`），重启 `serve`：

```bash
java -jar oryxos-boot/target/oryxos.jar serve
```

**US2 的机器判据不在这里**——「落点在配置时区下是 09:00 且与错时区不等」由单测 `AgentSchedulerTest` 判（§0.2 实测：课件的原断言手法在本仓写不出来，替代手法更强）。

**人工只需确认一件事**：两条规则的 `zone` 不同，**各自注册成功**（日志里 `共 3 条` = `tick-demo` 的每分钟那条仍在 + `tz-utc` + `tz-sh`），且**没有**因为时区不同而报错或跳过。

> **这一条为什么重要**：时区错了，任务**看起来是成功的**——它确实跑了、也留了审计记录，只是跑在错误的时刻。没有报错、没有异常，只有「用户以为的早上 9 点」和「实际跑的时刻」对不上。这是最难被发现的一类。

---

## Step 3（US3）上一次没跑完，这次直接跳过

**机器判据**由单测覆盖（占住锁 → 调 `runOnce` → 断言 `process` **零次调用**，`AgentSchedulerTest.runOnce_skipsWhenTaskLockHeld`）。人工要确认的是**真链路上不出现「同一会话被两个线程同时改写」**。

> **⚠️ 那行 WARN 在真链路上抓不到**（2026-10-19 实测 + 反编译核实）：`AgentScheduler` 调的是 `taskScheduler.schedule(() -> runOnce(...), new CronTrigger(...))`——Spring 这一支是**自重新调度**，`ReschedulingRunnable.run()` 是「执行任务 → 取完成时刻 → `triggerContext.update(..., completionTime)` → `schedule()`」，而 `CronTrigger.determineLatestTimestamp()` 取的正是 `lastCompletion()`。**下一次触发必然排在上一次跑完之后**，同一任务结构上不可能自重叠。
>
> **实测**：6 段 cron `"* * * * * *"`（每秒触发）+ 单次执行约 1.5 秒（跑得比触发间隔慢），跑 60 秒 → 33 次调用、**WARN 计数 0**、日志间隔恒 1.1~2.4 秒且串行。
>
> 所以 `tryLock()` 那条 WARN 是**纵深防御**（换 `CronTrigger.forFixedExecution(...)` 或 `scheduleAtFixedRate` 时才会真发生重叠），判卷在单测。

**人工只需确认反面**（用 Step 1 的日志即可）：**不应**出现「同一 session 被并发写」的迹象，例如同一条 `llm_calls` 时间戳成对出现、或 `tool_invocations` 里同一次调用被记两遍。

---

## Step 4（US4）一次跑挂了，调度器不死、锁不放死

**机器判据**由「二进宫」断言覆盖（异常后再触发一次、断言这次能进来）。人工要确认的是**真链路里失败之后任务还能继续跑**。

**做法**：把凭证改成一个**错的 key**，重启 `serve`，等两次触发：

```bash
export DEEPSEEK_API_KEY=sk-wrong-on-purpose
java -jar oryxos-boot/target/oryxos.jar serve
```

**预期**：

1. 日志里出现 `定时任务 tick-demo#1 执行失败：...`（ERROR）——**失败不静默**；
2. **进程没有退出**（`serve` 还活着，Ctrl+C 才结束）；
3. **等下一分钟**，日志里**再次**出现同一条 ERROR——**这说明锁被释放了**。只出现一次之后就再无动静 = 任务永久卡死 = **不通过**；
4. `llm_calls` 里那几行的 `success = 0`，`error_message` 非空——**失败与人推失败同形**。

> **第 3 条是本节最容易漏的一条**。漏了 `finally unlock()` 的实现：不抛异常（看起来正常）、日志也照打，但**这个任务从此永久卡死**，再也不触发。只有「下一次还进得来」能抓住它。

**恢复**：改回正确 key，重启。

---

## Step 5（SC-001）改 cron 不用重新编译

```bash
# 1) 把 tick-demo 的 cron 从每分钟改成每两分钟
sed -i 's|^  - cron: .*|  - cron: "*/2 * * * *"|' .oryxos/agents/tick-demo/AGENT.md

# 2) 不重新编译、不重新打包，直接重启
java -jar oryxos-boot/target/oryxos.jar serve
```

**预期**：触发间隔变成 2 分钟——**没有任何一行 Java 代码被改过、没有任何一次 `mvn package`**。

---

## Step 6（FR-012）确认 `chat` 不注册定时任务

```bash
java -jar oryxos-boot/target/oryxos.jar chat --profile tick-demo
# 随便聊一句，然后 /quit 退出
```

**预期**：**日志里不出现** `定时任务注册完成：共 N 条`。`chat` 是交互式短命进程，**不该**注册任何定时任务。

---

## Step 7 清理

```bash
# 只删本向导造出来的 Agent，不动工作区本身
rm -rf .oryxos/agents/tick-demo .oryxos/agents/tz-utc .oryxos/agents/tz-sh
# 停掉 serve（Ctrl+C）
```

---

## 排障对照表

| 现象 | 大概率原因 | 怎么查 |
| --- | --- | --- |
| 日志里**没有** `定时任务注册完成` | `serve` / `gateway` 的命令体没调到 `registerAll()`；或工作区不对 | `profile list` 看 Agent 在不在；确认是在**哪个目录**起的 `serve`（`Workspace.current()` 取的是**进程当前目录**） |
| 注册完成但 `共 0 条` | 该 Agent 没被装载（frontmatter 坏 / `provider` 段缺失 / 厂商名不在全局清单） | `profile list` 会把它标成无效并给出原因；`serve` 启动日志里也有 `ProfileLoader` 的 ERROR |
| 日志里 `定时任务注册失败（Agent X，任务 X#1）` | 该条规则的 cron 或 zone 非法 | 报错消息里已经指名道姓；对照 §3.1 / §3.2 的判定表 |
| 到点了但**什么都没发生** | cron 段数或时区理解偏差 | 5 段是 `分 时 日 月 周`——`"0 9 * * *"` 是**每天 9:00**，不是「9 点那一分钟」；每分钟是 `"* * * * *"` |
| 任务只跑了一次就再无动静 | 锁没释放（`finally` 缺失） | 这是**缺陷**，不是配置问题——单测的「二进宫」断言本该抓住它 |
| **一直等不到** `上一次尚未结束，本次触发跳过` 这行 WARN | **正常**，不是缺陷——`schedule(task, CronTrigger)` 是「跑完再排下一次」，同一任务结构上不会自重叠（2026-10-19 实测 + 反编译核实，见 Step 3） | 不用查。那行是纵深防御分支，判卷在单测；真链路只需确认**没有**「同一 session 被并发写」的迹象（Step 3） |
| 长任务把**其它**任务也推迟了 | 池被占满 | 池是固定 2（FR-019）；同时到点的不同任务最多 2 个并发，超出的排队等待——**这是预期行为** |
| `llm_calls` 里没有钟推的行 | 模型调用没发生（key 错 / Provider 配错） | 先看 `serve` 日志里有没有 `定时任务 X#1 执行失败`；有则看它带的原因 |
| `oryxos chat` 里也看到定时任务注册日志 | **缺陷**（撞 FR-012） | 源码断言 `SchedulerAssemblyTest` 本该抓住它 |

---

## 附：不跑真模型的那几条（纯单测，秒级）

```bash
# 本节两个测试类
./mvnw -pl oryxos-core -am test -Dtest=AgentSchedulerTest
./mvnw -pl oryxos-cli   -am test -Dtest=SchedulerAssemblyTest

# 前序节回归（跨节契约证据）
./mvnw clean verify
```

**预期**：全绿。`AgentSchedulerTest` 覆盖课件 harness 的四条关键回归点 + cron 归一化 + 单条失败隔离 + id 确定性；`SchedulerAssemblyTest` 覆盖装配面（池大小 2）与 FR-012 的源码断言。
