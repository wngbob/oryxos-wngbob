# 定时任务子系统（第 28 节）人工验收向导（Step by Step）

**Feature**: `specs/011-scheduler-runtime` | **分支**: `class-28` | **Date**: 2026-10-20

**上游文档**：[acceptance-report.md](acceptance-report.md) §四（剩余人工项）

**适用对象**：第 28 节的最终形态（`scheduled_tasks` / `task_executions` 两张表 + 四个 `/api/v1/schedules` 端点 + 管理台第一页写操作）

**目的**：覆盖单测判不了的那两段——

1. **管理台的真实交互**：`.vue` 源码只能判「写动作只有两个、路径受限」（`AdminConsoleSourceTest`），判不了「按钮点下去、计数与历史真的刷新、停用标签真的翻转」；
2. **真实 IM 群推送**：`SchedulerFlowIT` 用的是 IT 内起的 HTTP 接收端（真出网、真 POST），但**不是**真实群机器人；FS/企微的报文方言、业务码、加签只有真群能验。

顺带把本节的核心主张——**进程重启不丢运行状态**——真看一遍（Step 5，不花钱）。

**预计耗时**：Step 0~5（mock 档，不调真模型）约 10~15 分钟；Step 6（真群推送）另需 5~10 分钟。

---

## 实测记录

### 写向导时已真跑过的（2026-10-20，jar = `oryxos-boot/target/oryxos.jar`）

工作区 `/tmp/l28-manual`（`oryxos init` 现造），Agent = 本向导 Step 1 给的 `sched-console`（`provider: mock`）。**Step 0.5 ~ Step 5 的每条命令与预期输出都是实测值**，不是照抄课件：

| 已实测 | 实测结果 |
| --- | --- |
| `oryxos init`（空目录） | 建出 `.oryxos/` 全套；输出末段「数据库表结构: 与 db/schema.sql 一致（无需补列）」 |
| `profile list` | `sched-console 有效 provider=mock/mock-model` |
| `DEEPSEEK_API_KEY=unused` + `serve --port 18080` | 起得来（**mock 档也不允许 key 完全为空**——`application.yaml` 的 `${DEEPSEEK_API_KEY}` 没有默认值，见坑表第 9 条）；`/health` 报 `deepseek:"UP", mock:"UP"`（deepseek 只是配置就绪，不代表可达） |
| 启动日志 | 单 Agent 时 `定时任务注册完成：共 1 条`；建齐 Step 1 的两个 Agent 后复测为 **`共 2 条`**、列表 `length=2` |
| `GET /api/v1/schedules` | 1 条，`taskId:"sched-console#1"`、`enabled:true`、`runCount:0`、`lastStatus:null`、`nextRunAt:"2027-01-01T01:00:00Z"` |
| `POST .../sched-console%231/run` | `runCount:1`、`lastStatus:"success"`、`lastRunAt` 非空；同步返回，无需轮询 |
| `GET .../executions` | 1 行：`sessionId:"scheduler:scheduler:sched-console"`、`success:true`、`errorMessage:null`、`durationMs:117` |
| `GET /api/v1/memory` | 归档区含本轮 message 原文（mock 脚本会把它交给 `save_memory`） |
| `PUT ... {"enabled":false}` | `enabled:false`；**停用后 `POST /run` 仍执行**（`runCount` 1 → 2）——这是设计行为 |
| 重启 `serve`（同一工作区） | `enabled:false`、`runCount:2`、执行历史 2 行**原样保留**；新日志再次 `定时任务注册完成：共 1 条` |
| `/admin/` 与 `/admin/schedules` | 均 HTTP 200，`<html lang="zh-CN"` |
| `notify-channel add/list/remove`（用占位 URL） | add 只回 `name` / `type` 与占位符 `${FEISHU_HOOK_URL}`；list 的 url 列原样打印占位符；remove `--force` 直接生效 |

**写向导时没实跑 Step 6（真实 IM 群推送）**：它要真实群机器人地址 + 真实模型调用（真花钱），只能由你执行。Step 6 的命令按同一套已验过的机制（`notify-channel` 读时解析、`runNow` 同步执行）写成，但没有真实群的回执作证。

### 用户实跑（2026-10-20）——Step 6 首跑命中 19024，修复后重跑通过

用户按本向导执行：**Step 0~5 与 6.1 / 6.2 的判据均成立**（管理台目视项 Step 4 也在内），但 **6.3 执行后群里没有收到消息**。查库后定位到确切原因与两处向导缺陷：

| 事实 | 证据 |
| --- | --- |
| 任务本身跑完了 | `task_executions` 第 5~7 行 `success=1`、`error_message=NULL`；`scheduled_tasks.last_status='success'`、`run_count=3` |
| **推送三次全部失败** | `tool_invocations` 第 5~7 行 `tool_name='notify'`、`success=0`、`error_message='飞书推送失败：业务码 19024，msg=Key Words Not Found'` |
| 渠道与环境变量都没问题 | `notify_channels`：`type=feishu`、`url` 是占位符 `${FEISHU_HOOK_URL}`（不是字面量）、`config` 无键；若变量缺失，错误会是「引用了未设置的环境变量」而不是 19024 |
| 飞书业务码 19024 | 群机器人开了「自定义关键词」安全设置，消息正文必须命中其中一个关键词，否则 HTTP 200 + 业务码非 0 拒收 |

**向导缺陷（已修，见 Step 6.3 / 6.4 / 坑表）**：

1. **把「任务成功」当成了「推送成功」**：原 6.3 判据只有 `lastStatus:"success"` + 群里收到。真实语义是 `lastStatus` 只表示 ReAct 跑完没抛异常——工具失败后模型拿到错误继续组织答复，任务照样成功。推送成败的唯一权威证据在 `tool_invocations.success` / `error_message`，**而 15 个 REST 端点里没有任何一个暴露它**。
2. **「本机没有 sqlite3 CLI，查库一律走 REST」这条前置事实在 Step 6 不成立**：该判据没有 REST 通路，必须查库。已改为用 `python3` 内置 `sqlite3`（不需要 `sqlite3` CLI）。

> **最终结论（2026-10-20，用户确认）：人工验收通过。** 首跑的 19024 在修复飞书机器人关键词设置后重跑成功，群里收到消息；Step 4 目视项与其余判据同样通过。按既定留痕口径只记整体结论、不虚构步骤级输出（具体修复操作未逐步说明）；首跑失败与两处向导缺陷保留在上一段，作为判据设计的过程留痕。

### 写向导时核过但未逐条实跑的

- 管理台按钮的行为是从 `SchedulesView.vue` 源码核的（「立即执行」→ `POST .../run`；「停用 / 启用」→ `PUT ...`；状态标签 `成功` / `失败` / `未执行`、`启用` / `停用`）。视觉判据要你亲眼看（Step 4）。
- ~~真实群的失败路径（对端回业务码 → `lastStatus:"failed"` + 历史里 `errorMessage`）~~ **该推断已被 2026-10-20 实跑推翻**：工具失败时任务级状态仍是 `success`，失败只落 `tool_invocations`。Step 6.5 已按实测改写。

---

## 前置事实（跑之前先认这些）

1. **本向导用一个临时工作区 `/tmp/l28-manual`**，不碰仓库根目录的 `.oryxos/`。想改用 `bin/start.sh` 也可以，但它固定以仓库根为工作区、且恒要求真实 key——与本向导的「mock 档不花钱」冲突，故不用它。
2. **`serve` 不建表**：建表与补列由 `oryxos init` 完成（Step 0.5）。缺表时 `GET /api/v1/schedules` 会 500。
3. **mock 档仍然要求 `DEEPSEEK_API_KEY` 非空**：`application.yaml` 里 deepseek 的占位没有默认值。mock Agent 不会调用真模型，所以给个假值 `unused` 即可（Step 2）。**真实群推送那一步（Step 6）才需要你的真 key**。
4. **改 `AGENT.md` 后必须重启 `serve`**：`registerAll()` 只在 `serve` / `gateway` 启动时跑一次。重启**不会**清掉运行状态（这正是本节要验的）。
5. **任务 id 是 `<Agent名>#<序号>`**：`#` 在 URL 里必须写成 `%23`（curl 直接用 `%23`；管理台内部已 `encodeURIComponent`，点按钮不用管）。
6. **停用只管到点触发**：「立即执行」无视启用状态，这是设计行为（FR 口径），不是 bug。
7. **REST 查不到推送成败**：15 个端点里没有 `tool_invocations`，`task_executions` 只记任务级成功（工具失败后 ReAct 会继续，任务照样成功）。推送成败的唯一权威证据在库里，故 Step 6.3 给一条只读查询，用 **`python3` 内置 `sqlite3`**（本机没有 `sqlite3` CLI；`jq` 已装，REST 查询照旧用它）。
8. **`notify-channel add` 是轻命令**（不启 Spring），可以在 `serve` 停着时跑；但 `${ENV_VAR}` 是**读时解析**——变量必须存在于 **`serve` 进程**的环境里，不是 `add` 那一刻的 shell。
9. **v1 没有 `retired`**：从 `AGENT.md` 删掉的 schedule 会在库里留下旧行（列表可见、`POST /run` 返回 404）。这是已知边界，不是缺陷。

---

## Step 0：构建（必须 `clean`，且不能跳前端）

```bash
cd /home/wngbob/oryxos-wngbob
./mvnw clean package -DskipTests -Ddependency-check.skip=true
```

**预期**：`BUILD SUCCESS`。前端（Vue 管理台）由 `frontend-maven-plugin` 串在 `package` 里，**不要**加 `-Dfrontend.skip=true`——那样打出来的 jar 缺管理台，Step 4 会打不开。

构建后做两个自查（第二个最容易漏）：

```bash
ls -l oryxos-boot/target/oryxos.jar

# 管理台在嵌套 jar 里（oryxos-web 是依赖模块，不在 BOOT-INF/classes）
unzip -p oryxos-boot/target/oryxos.jar "BOOT-INF/lib/oryxos-web-*.jar" > /tmp/l28-web.jar
unzip -l /tmp/l28-web.jar | grep "static/admin/index.html"
rm -f /tmp/l28-web.jar
```

**预期**：第一行文件存在；第二个命令打出 `static/admin/index.html` 一行。**打不出来**说明这次构建跳了前端，回去重打。

> ⚠️ 增量 `mvn package`（不带 `clean`）可能把旧字节码打进 fat JAR——`BUILD SUCCESS` 但产物是旧的（第 26 节实测坑）。本向导一律 `clean`。

---

## Step 0.5：准备临时工作区

```bash
export REPO=/home/wngbob/oryxos-wngbob
export JAR=$REPO/oryxos-boot/target/oryxos.jar
export WORK=/tmp/l28-manual

rm -rf "$WORK" && mkdir -p "$WORK" && cd "$WORK"
java -jar "$JAR" init
```

**预期**：列出 `.oryxos/` 下新建的目录与文件，末段是：

```text
数据库表结构:
  与 db/schema.sql 一致（无需补列）
```

> ⚠️ **换终端**：`REPO` / `JAR` / `WORK` 只在当前 shell 有效。新开终端后如果命令报 `Unable to access jarfile`（后面跟个空字符串），**回本段重新 export**，别重新构建。

---

## Step 1：建两个 Agent

### 1.1 `sched-console`（mock 档，管理台验收用；不花钱）

```bash
mkdir -p "$WORK/.oryxos/agents/sched-console"
cat > "$WORK/.oryxos/agents/sched-console/AGENT.md" <<'EOF'
---
description: 第 28 节管理台人工验收用 Agent（mock 模型，不花钱）
identity:
  agent_name: 定时验收助手
  prompt: 你是 OryxOS 的定时任务验收助手，按指令把内容记进长期记忆。
provider:
  name: mock
  model: mock-model
tools:
  - save_memory
schedules:
  - cron: "0 0 9 1 1 *"
    zone: Asia/Shanghai
    message: "第 28 节管理台人工验收：把这句话存进长期记忆。"
---

到点把消息内容记进长期记忆。
EOF
cd "$WORK" && java -jar "$JAR" profile list
```

**预期**：

```text
  sched-console  有效  provider=mock/mock-model  第 28 节管理台人工验收用 Agent（mock 模型，不花钱）
```

cron 是 6 段、`0 0 9 1 1 *` = 每年 1 月 1 日 09:00（Asia/Shanghai）——**故意排到远期**，这样测试期间不会自动触发，干扰计数。本节验的是「立即执行」与「停用」，到点触发是第 25 节的人工项。

### 1.2 `sched-notify`（真模型档，Step 6 用；现在只建不跑）

```bash
mkdir -p "$WORK/.oryxos/agents/sched-notify"
cat > "$WORK/.oryxos/agents/sched-notify/AGENT.md" <<'EOF'
---
description: 第 28 节真实群推送人工验收用 Agent（真模型 + notify）
identity:
  agent_name: 群推送验收助手
  prompt: 你是 OryxOS 的推送验收助手。收到指令后只做一件事：调用 notify 工具推送指定内容。
provider:
  name: deepseek
  model: deepseek-chat
tools:
  - notify
schedules:
  - cron: "0 0 9 1 1 *"
    zone: Asia/Shanghai
    message: "调用 notify，channel 参数传 team-demo，content 参数写「第 28 节定时任务人工验收：这条消息来自管理台「立即执行」」。只调用一次 notify，不要调用其它工具。"
---

收到消息就把内容推到 team-demo 渠道。
EOF
cd "$WORK" && java -jar "$JAR" profile list
```

**预期**：两个 Agent 都显示「有效」，`sched-notify` 的 provider 是 `deepseek/deepseek-chat`。

> 这一步不花钱（`profile list` 不连模型）。

---

## Step 2：起服务（mock 档）

```bash
cd "$WORK"
export DEEPSEEK_API_KEY=unused        # 假值即可：本轮只有 mock Agent 会跑
nohup java -jar "$JAR" serve --port 18080 > "$WORK/serve.log" 2>&1 &
echo $! > "$WORK/serve.pid"

# 等就绪（最多 60 秒）
for i in $(seq 1 60); do
  curl -fsS --max-time 2 http://127.0.0.1:18080/api/v1/health >/dev/null 2>&1 && break
  sleep 1
done

curl -fsS http://127.0.0.1:18080/api/v1/health | jq '.data'
grep "定时任务注册完成" "$WORK/serve.log"
```

**预期**：

```json
{
  "status": "UP",
  "db": "UP",
  "providers": { "deepseek": "UP", "mock": "UP" }
}
```

```text
定时任务注册完成：共 2 条
```

> `共 2 条`——`sched-console#1` 与 `sched-notify#1` 都已登记（注册不执行、不调模型，`sched-notify` 此刻不花钱）。如果只看到 1 条，检查两个 `AGENT.md` 的 `schedules` 缩进。

---

## Step 3：REST 对账（不依赖浏览器）

### 3.1 列表：登记即落库，未跑过

```bash
curl -fsS http://127.0.0.1:18080/api/v1/schedules | jq '.data'
```

**判据**：能看到 `sched-console#1` 与 `sched-notify#1` 两条；都 `enabled:true`、`runCount:0`、`lastStatus:null`、`nextRunAt` 非空（`2027-01-01T01:00:00Z` 附近）。

### 3.2 立即执行：同步返回执行后的状态

```bash
curl -fsS -X POST 'http://127.0.0.1:18080/api/v1/schedules/sched-console%231/run' \
  | jq '.data | {taskId, runCount, lastStatus, lastRunAt}'
```

**判据**：`runCount:1`、`lastStatus:"success"`、`lastRunAt` 非空。命令返回时执行已经结束（**不需要 sleep / 轮询**）。

### 3.3 执行历史 + 记忆：三面一致

```bash
curl -fsS 'http://127.0.0.1:18080/api/v1/schedules/sched-console%231/executions' | jq '.data'
curl -fsS http://127.0.0.1:18080/api/v1/memory | jq -r '.data.archival' | grep -c "第 28 节管理台人工验收"
```

**判据**：第一条返回 1 行，`success:true`、`sessionId:"scheduler:scheduler:sched-console"`、`errorMessage:null`；第二条输出 `1`（mock 脚本把 message 交给了 `save_memory`，归档区里能查到原文）。

---

## Step 4：管理台目视（人工项 ①）

浏览器打开：

```text
http://localhost:18080/admin/schedules
```

左侧导航第 5 项「定时任务」（共 7 项）。逐条看：

| # | 操作 | 预期 |
| --- | --- | --- |
| 4.1 | 打开页面 | 顶部汇总「共 2 条定时任务，其中 2 条启用」；表格两行：`sched-console#1`、`sched-notify#1`。`sched-console` 行：次数 **1**、上次结果 **成功**（绿色标签）、状态 **启用** |
| 4.2 | 点 `sched-console` 行的「立即执行」 | 按钮短暂显示「执行中…」，然后本行**原地刷新**：次数变 **2**、上次结果仍「成功」、上次时间更新。**页面不会自动轮询**，刷新来自这次请求的返回体 |
| 4.3 | 展开该行的执行历史 | 出现 **2** 行记录：时间、成功、耗时（毫秒）。第二行是刚才那次 |
| 4.4 | 点该行「停用」 | 状态标签变 **停用**（灰色）；顶部「其中 N 条启用」减 1 |
| 4.5 | **停用状态下**再点「立即执行」 | **仍然执行**：次数变 **3**、结果成功。这是设计行为——「停用只管到点触发」，手动触发无视启用状态 |
| 4.6 | 点「启用」 | 状态标签变回 **启用**；顶部计数加 1 |

> 判据就是「你看到的」与上表一致。源码层判据（写方法只有 `post(` / `put(`、只打 `/api/v1/schedules/**`）已由 `AdminConsoleSourceTest` 机器判过，本步只判视觉与交互。

---

## Step 5：重启恢复（本节核心主张，不花钱）

**先把 `sched-console` 停在「停用」**（Step 4.4 / 4.6 之后再点一次「停用」），然后重启：

```bash
kill "$(cat "$WORK/serve.pid")"; sleep 3

cd "$WORK"
export DEEPSEEK_API_KEY=unused
nohup java -jar "$JAR" serve --port 18080 > "$WORK/serve2.log" 2>&1 &
echo $! > "$WORK/serve.pid"

for i in $(seq 1 60); do
  curl -fsS --max-time 2 http://127.0.0.1:18080/api/v1/health >/dev/null 2>&1 && break
  sleep 1
done

curl -fsS http://127.0.0.1:18080/api/v1/schedules | jq '.data[] | {taskId, enabled, runCount, lastStatus}'
curl -fsS 'http://127.0.0.1:18080/api/v1/schedules/sched-console%231/executions' | jq '.data | length'
```

**判据**：

- `sched-console#1`：`enabled:false`（停用状态**没被重启冲掉**）、`runCount:3`（历史**没归零**）、`lastStatus:"success"`；
- 执行历史长度 **3**；
- 新日志里仍有 `定时任务注册完成：共 2 条`。

刷新管理台页面：仍是「停用」与次数 3。然后点「启用」恢复。

> 这一条是第 28 节相对第 25 节的全部意义所在：第 25 节的注册只在内存里，一重启全归零；现在的状态与历史在 `scheduled_tasks` / `task_executions` 里。

---

## Step 6：真实 IM 群推送（人工项 ②，真 key + 真群机器人）

> 需要：① 你的真实 `DEEPSEEK_API_KEY`；② 一个真实群机器人 webhook（飞书 / Lark / 企微均可；第 19 节验收时用过的那种）。**没有真群就跳过本步**，Step 0~5 已覆盖除「真群回执」外的全部机制。

### 6.1 注册通知渠道（轻命令，不启 Spring）

```bash
cd "$WORK"

# 用 read -s 输入，地址不进 shell 历史、不上屏
read -r -s -p "群机器人 Webhook URL: " FEISHU_HOOK_URL; echo
export FEISHU_HOOK_URL

java -jar "$JAR" notify-channel add team-demo --type feishu --url-env FEISHU_HOOK_URL
java -jar "$JAR" notify-channel list
```

**预期**：

```text
已添加通知渠道 'team-demo'（type=feishu）
  地址：${FEISHU_HOOK_URL}——发送时从环境变量解析，凭证未落库
通知渠道（共 1 条，按注册名升序，首行为默认渠道）:
  name  type  url  config  description
  team-demo  feishu  ${FEISHU_HOOK_URL}  —  —
```

**判据**：`add` 的返回里**只有占位符、没有 URL 值**；`list` 的 url 列原样打印 `${FEISHU_HOOK_URL}`。任何一行出现完整 URL 都是缺陷。

类型按你的机器人改：飞书 `--type feishu`、Lark `--type lark`（域名 `open.larksuite.com`）、企业微信 `--type wecom`。**拼错 type 会在发送时路由处报错并列出已知类型，一次请求都不发**。

### 6.2 以真实 key 重启服务

`${FEISHU_HOOK_URL}` 是**读时解析**，必须存在于 **`serve` 进程**的环境里：

```bash
kill "$(cat "$WORK/serve.pid")"; sleep 3

cd "$WORK"
export DEEPSEEK_API_KEY='<你的真实 DeepSeek key>'   # 手填；命令前加空格可避免进 history（HISTCONTROL=ignorespace）
export FEISHU_HOOK_URL                              # 与 6.1 同一个 shell 里，仍有效

nohup java -jar "$JAR" serve --port 18080 > "$WORK/serve3.log" 2>&1 &
echo $! > "$WORK/serve.pid"

for i in $(seq 1 60); do
  curl -fsS --max-time 2 http://127.0.0.1:18080/api/v1/health >/dev/null 2>&1 && break
  sleep 1
done
grep "定时任务注册完成" "$WORK/serve3.log"
```

**预期**：`定时任务注册完成：共 2 条`。

> 如果想确认「渠道真的被读到了」，不用等模型：`sched-notify#1` 的 `runCount` 应为 0，且 `GET /api/v1/schedules` 正常返回两条——渠道在发送那一刻才解析，此步不验证它。

### 6.3 触发一次真实推送

管理台点 `sched-notify#1` 的「立即执行」（或命令行）：

```bash
curl -fsS -X POST 'http://127.0.0.1:18080/api/v1/schedules/sched-notify%231/run' \
  | jq '.data | {taskId, runCount, lastStatus, lastRunAt}'
```

这是一次**真模型调用**：模型先决定调 `notify`，拿到推送结果后组织最终答复，大约几秒到十几秒（HTTP 同步等待，**超时属正常排查范围**，见坑表）。

**判据（四条都要成立）**：

1. **群里真的收到消息**，内容含「第 28 节定时任务人工验收：这条消息来自管理台「立即执行」」——**这是唯一能证明推送成功的判据**；
2. 返回体 `runCount` 比上一次 +1、`lastStatus:"success"`——**注意：这只说明任务跑完了，不说明推送成功**（工具失败后 ReAct 继续，任务照常 `success`）；
3. 管理台展开该行历史：多一行 `success`，耗时非零——同上，只是任务级；
4. **查库确认 `notify` 这次真是成功的**：

```bash
python3 - "$WORK/.oryxos/oryxos.db" <<'PY'
import sqlite3, sys
db = sqlite3.connect(sys.argv[1]); db.row_factory = sqlite3.Row
for r in db.execute(
    "select id, tool_name, success, error_message, duration_ms "
    "from tool_invocations order by id desc limit 5"):
    print(dict(r))
PY
```

**预期**：最新一行 `tool_name='notify'`、`success=1`、`error_message=None`。

**若 `success=0`**：群没收到是**真的失败**，不是「网络慢」或「群消息被折叠」。错误原因就在 `error_message` 里（`serve` 日志只有一行不带原因的 `WARN 工具调用失败`，查不到根因）。按 6.4 排障。

### 6.4 排障：群里没收到消息

> 首跑最可能命中这里（2026-10-20 用户实跑就是 19024）。6.5 是可选演练，6.4 才是真出问题时的入口。

先跑 6.3 第 4 条拿到 `error_message`，再按下表对号：

| `error_message` 里出现 | 根因 | 处置 |
| --- | --- | --- |
| `业务码 19024` + `Key Words Not Found` | 飞书群机器人开了**自定义关键词**安全设置，消息正文没命中关键词 | 飞书群 →「设置」→「群机器人」→ 该机器人「安全设置」：**要么关掉自定义关键词，要么把关键词原样写进 `AGENT.md` 的 `schedules[].message`**。改了 `AGENT.md` **必须重启 `serve`**（`registerAll()` 才重新登记）再重跑；只改飞书后台设置则不用重启 |
| `业务码 19021` + `sign match fail` | 开了**签名校验** | 注册渠道时补 `--config secret=<签名密钥>`（凭证，别进 shell 历史），或关掉签名校验 |
| `业务码 19002` + `msg_type need` | **`type` 注册错了**（最常见：飞书地址用了 `type=webhook`） | `notify-channel list` 看 `type`；删掉重建 `--type feishu`。通用 `webhook` 档会把这判成成功（HTTP 200），但消息进不了群——第 19 节记录过的坑 |
| `未知的通知渠道类型` | `type` 拼错 / CLI 写入时不做取值校验 | 同上看 `type`；错误由路由处抛出，**一次请求都没发** |
| `引用了未设置的环境变量 FEISHU_HOOK_URL`（报错只带变量名） | `serve` 进程环境里没有这个变量 | 变量必须给 **`serve` 进程**：重启 `serve` 的同一个 shell 里 `export FEISHU_HOOK_URL`（坑表第 10 条） |
| 业务成功（`success=1`）但群里还是没有 | ① 看错群（webhook 绑定的那个群）② 机器人被移出群 / 被停用 ③ 群消息被折叠 | 用该 webhook 直接 POST 一条最小报文（`{"msg_type":"text","content":{"text":"..."}}`）对照；仍收不到就在飞书后台检查机器人归属 |

**常见误判**：`task_executions.success=1` / `lastStatus:"success"` **不能**用来判推送成功；`serve.log` 的 `WARN 工具调用失败` 不带原因。两者都会让「群里没消息」看起来像「应用侧一切正常」。

### 6.5 失败路径（可选，2 分钟）

把渠道类型故意写错，再触发一次（同名渠道要先删再建）：

```bash
cd "$WORK"
java -jar "$JAR" notify-channel remove team-demo --force
java -jar "$JAR" notify-channel add team-demo --type feishu-x --url-env FEISHU_HOOK_URL

curl -fsS -X POST 'http://127.0.0.1:18080/api/v1/schedules/sched-notify%231/run' \
  | jq '.data | {taskId, runCount, lastStatus}'
```

**预期**：`runCount` 照常递增、**`lastStatus` 仍是 `"success"`**（任务级；路由错误没有逃出 ReAct）；`tool_invocations` 最新一行 `notify` `success=0`、`error_message` 为「未知的通知渠道类型 'feishu-x'；已知类型：…」；**调度器不死**——再点一次「立即执行」照常进入执行入口。

验完把类型改回来，否则 Step 7 前的群推送会一直失败：

```bash
java -jar "$JAR" notify-channel remove team-demo --force
java -jar "$JAR" notify-channel add team-demo --type feishu --url-env FEISHU_HOOK_URL
```

> 真渠道的反面（对端回业务错误时 `notify` 是否报错）第 19 节已有单测与飞书真群验收覆盖；本步只是在你自己的群里再看一眼。

---

## Step 7：收尾

```bash
kill "$(cat "$WORK/serve.pid")" 2>/dev/null

# 可选：把演示渠道从临时工作区移除（只影响 /tmp/l28-manual 的库）
cd "$WORK" && java -jar "$JAR" notify-channel remove team-demo --force

rm -rf "$WORK"
```

**预期**：无残留进程；`/tmp/l28-manual` 删净。仓库根目录的 `.oryxos/` 全程未被触碰。

---

## 附录 A：复跑两条真 key 集成测试（可选）

`SchedulerFlowIT` / `RestartRecoveryIT` 作者已在真 key 上各跑过 1/1；想在你自己机器上确认，用：

```bash
cd /home/wngbob/oryxos-wngbob
DEEPSEEK_API_KEY='<你的真实 key>' ./mvnw -pl oryxos-cli -am test \
  -DexcludedGroups=none -Dtest=SchedulerFlowIT -Dsurefire.failIfNoSpecifiedTests=false
DEEPSEEK_API_KEY='<你的真实 key>' ./mvnw -pl oryxos-cli -am test \
  -DexcludedGroups=none -Dtest=RestartRecoveryIT -Dsurefire.failIfNoSpecifiedTests=false
```

**预期**：各 `Tests run: 1, Failures: 0, Errors: 0`。两条都会真调模型、真出网（`SchedulerFlowIT` 的 webhook 是 IT 内起的本地接收端，不是真群）。

> `-am` 不能省（单模块命令不带 `-am` 会静默取 `~/.m2` 里的旧字节码）。

---

## 已知边界（本节不验，如实记录）

- **v1 没有 `retired`**：从 `AGENT.md` 删掉的 schedule 留下旧行——列表可见、`POST /run` 返回 404。退役语义归扩展阶段。
- **一轮真链路是 3 次模型调用，不是课件写的 2 次**（`notify` 的 content 依赖 `http_get` 的结果，两个工具无法并排调用）。本向导的 `sched-notify` 只调 `notify`，不涉及这条。
- **到点自动触发不在本向导的判据里**：cron 被故意排到 2027 年；「到点了」由第 25 节向导与 `AgentSchedulerTest` 覆盖。若想看，把 cron 改成 `* * * * *`、重启 `serve`、等 70 秒，看 `runCount` 自增（mock 档不花钱）。
- **60 秒整体超时仍未强制**：第 26 节裁决 ② 的已知边界，本节未改。
- **`ask_user` 在钟推里的分档**：第 25 节裁决 4 的已知边界，本节未动。

---

## 最容易踩的坑

| # | 现象 | 根因 / 处置 |
| --- | --- | --- |
| 1 | `Error: Unable to access jarfile` 后跟空字符串 | 换了终端，`$JAR` 丢了 → 回 Step 0.5 重新 export，**别重新构建** |
| 2 | `GET /api/v1/schedules` 返回 500（`no such table: scheduled_tasks`） | 没跑 `oryxos init` → Step 0.5。`serve` 不建表 |
| 3 | `serve` 起不来，报 `Could not resolve placeholder 'DEEPSEEK_API_KEY'` | 环境变量完全为空。mock 档也给个假值：`export DEEPSEEK_API_KEY=unused`（坑 9 同源） |
| 4 | `BUILD SUCCESS` 但管理台是旧的 / `/admin/` 404 | 增量 `package` 打了旧嵌套 jar，或加了 `-Dfrontend.skip=true` → `clean package` 重打，跑 Step 0 的嵌套 jar 自查 |
| 5 | 18080 端口被占 | 换 `--port 18081`，后面所有 URL 跟着换；或 `kill` 掉占用者 |
| 6 | `curl .../sched-console#1/run` 返回 404 或只请求了 `/schedules/sched-console` | `#` 未编码 → 用 `%23`（本向导所有 curl 已写好） |
| 7 | 改了 `AGENT.md`，列表里还是旧的 cron / message | `registerAll()` 只在启动时跑 → 重启 `serve`。重启**不会**清运行状态（Step 5 正好验它） |
| 8 | 从 `AGENT.md` 删了 schedule，列表里还在 | v1 已知边界：旧行保留、`run` 404。不是缺陷 |
| 9 | mock Agent 也想零成本跑，但 `serve` 非要 key | `application.yaml` 的 `${DEEPSEEK_API_KEY}` 无默认值 → `export DEEPSEEK_API_KEY=unused`（deepseek 厂商配置就绪即可，不会真调） |
| 10 | `notify-channel add` 成功，但真群收不到、`tool_invocations` 报「引用了未设置的环境变量」 | `${ENV_VAR}` 是**读时解析**，变量必须给 **`serve` 进程**——`add` 所在 shell 里 export 了不算，重启 `serve` 时也要在 |
| 11 | 停了任务，点「立即执行」还在跑 | 预期行为：停用只管到点触发，手动触发无视启用状态（FR 口径） |
| 12 | 管理台数字不自动变 | 页面只在打开与按钮返回时刷新，没有轮询 → 手动刷新页面 |
| 13 | 群里没收到，但 `lastStatus:"success"`、执行历史也是一行成功 | 任务成功 ≠ 推送成功：工具失败后 ReAct 继续。查 `tool_invocations.error_message`（Step 6.3 第 4 条） |
| 14 | `tool_invocations` 报 `业务码 19024 Key Words Not Found` | 飞书机器人开了自定义关键词：关掉它，或把关键词写进 `AGENT.md` 的 schedule message，重启 `serve` 后重跑（Step 6.4） |

---

## 验收判据汇总

| 人工项 | 步骤 | 通过判据 |
| --- | --- | --- |
| 管理台「立即执行」 | Step 4.2 / 4.3 | 计数原地 +1、状态「成功」、历史多一行 |
| 管理台「启用 / 停用」 | Step 4.4 / 4.6 | 标签与顶部计数翻转；停用后手动执行仍可跑（4.5） |
| 重启不丢状态（本节核心） | Step 5 | `enabled` / `runCount` / 执行历史原样恢复 |
| 真实 IM 群推送 | Step 6.3 | **群里收到消息** + `tool_invocations` 最新 `notify.success=1`（`lastStatus` 只是任务级，不作判据） |
| 失败不拖死调度器（可选） | Step 6.5 | `tool_invocations` 有错因、`lastStatus` 仍是 `success`（任务级）、下一次照常进入执行 |
