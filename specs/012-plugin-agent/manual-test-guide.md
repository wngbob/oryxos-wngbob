# 插件化 Agent（第 29 节）人工验收向导（Step by Step）

**Feature**: `specs/012-plugin-agent` | **分支**: `class-29` | **Date**: 2026-10-21

**上游文档**：[acceptance-report.md](acceptance-report.md) §四（剩余人工项）｜验证场景全文见 [quickstart.md](quickstart.md)

**适用对象**：第 29 节的最终形态（`ProfileLoader.inspect/deriveProfile` + 运行时可变 `ProfileRegistry` + `AgentScheduler.registerProfile` + `examples/` 示例目录 + `shell.allowed_commands` 放行 `python3`）

**目的**：覆盖单测判不了的这三段——

1. **「一个目录就是一个 Agent」的真身体感**：`AgentLoaderTest` / `DeriveProfileTest` 判的是解析结果，判不了「复制一个目录进去、`profile list` 与 `/api/v1/profiles` 真的同时看得见、全程没碰 Java」；
2. **渐进式披露在真模型上的可观察行为**（Step 4）：`ProgressiveDisclosureTest` 只判「system prompt 里没有 REFERENCE.md 正文与脚本源码」，判不了「模型真的会先跑脚本、再按 Skill 路径读规范、最后按 REFERENCE.md 定级」——**这需要真模型 + 真 `shell`/`read_file`**；
3. **「到点自己跑」确实落到库里**（Step 5）：`AgentScanRegisterTest` 判的是内存里的注册行为，判不了「cron 到点 → 会话落 `sessions`、审计落 `llm_calls`/`tool_invocations`」——这条用 `mock` 厂商跑，**不花钱**。

**预计耗时**：Step 0~3 + Step 5 约 15 分钟（不调真模型）；Step 4 一次真模型对话约 10 秒、5 次调用（约 1.1 万 tokens）。

---

## 实测记录

### 写向导时已真跑过的（2026-10-21，jar = `oryxos-boot/target/oryxos.jar`，84,590,137 字节）

工作区 `/tmp/l29-manual` 与 `/tmp/l29-step4`（均 `oryxos init` 现造，收尾已 `rm -rf`）。**除 Step 0 的构建命令外，下文每条命令与预期输出都是实测值**，不是照抄课件：

| 已实测 | 实测结果 |
| --- | --- |
| `oryxos init`（空目录） | 建出 `.oryxos/` 全套 12 项；**倒数第二段**是「数据库表结构: 与 db/schema.sql 一致（无需补列）」，**末行**是 `下一步: \`oryxos profile create <name>\` 建一个 Agent，再 \`oryxos chat --profile <name>\` 开始对话` |
| `cp -a examples/skills/report-format .oryxos/skills/` + `cp -a examples/agents/daily-reconcile .oryxos/agents/` | 软连接**完整保留**：`ls -l` 显示 `report-format -> ../../../skills/report-format`；`readlink -f` 落在 `$WORK/.oryxos/skills/report-format` |
| `profile list`（只 daily-reconcile） | `daily-reconcile  有效  provider=deepseek/deepseek-chat  <description>` |
| `profile list`（再加 tick-demo） | `Agent（共 2 个…）`；`tick-demo  有效  provider=mock/mock-model  定时触发演示（mock 模型，不花钱）` |
| `serve --port 18091` + `/api/v1/profiles` | `daily-reconcile` 的 `tools` / `schedules` 原样带出（`cron "0 0 9 * * *"`、`zone Asia/Shanghai`）、`"valid": true` |
| 启动日志 | 单 Agent 时 `定时任务注册完成：共 1 条`；建齐两个 Agent 后 **`共 2 条`** |
| `GET /api/v1/info` | 单 Agent `{"agents":1,…,"registeredTools":15}`；两个 Agent `{"agents":2,…,"registeredTools":15}` |
| `GET /api/v1/health` | `{"status":"UP","db":"UP","providers":{"mock":"UP","deepseek":"UP"}}`（`DEEPSEEK_API_KEY=unused`，deepseek 只表示配置就绪）。**`providers` 的键序不保证**——服务端是 `Map.copyOf`，两次请求可能给出不同顺序；比对时用 `jq -S` 排序后比，或按键断言，**别逐字节比这一行** |
| **丢目录边界**（Step 3） | 丢 `dropped-agent` 后 `/api/v1/profiles` 立刻出现它；`/api/v1/info` 的 `agents` **不变**；`POST /api/v1/agents/dropped-agent/invoke` → `HTTP 404 {"code":404,"message":"agent not found: dropped-agent"}` |
| **真模型渐进式披露**（Step 4，真 DeepSeek） | **上列次数是作者侧那一次的实测值、不是判据**（用户实跑是 4 次 LLM / 3 次工具；模型多调或少调一次 `read_file` 属服从性差异）。**本节真正的判据只有两条**：`shell` 的 `argv[0]=python3`、且模型上下文里是脚本输出的 JSON 而不是脚本源码。作者侧那一次的序列是 5 次 LLM / 6 次工具：`shell`（`argv[0]=python3`）→ `read_file`（SKILL.md，**绝对路径**，经软连接解析）→ `read_file`（REFERENCE.md）→ `notify`（失败：渠道 `ops` 不存在）→ `save_memory` → `notify`（失败：未配置任何渠道） |
| **mock 档到点自己跑**（Step 5，**1 轮**时的实测值） | `scheduled_tasks.run_count=1` / `last_status=success`；`task_executions` 1 行 `success=1`；`sessions` 1 行 `channel=scheduler`、`session_id=scheduler:scheduler:tick-demo`；**该会话名下** `llm_calls` **2 行**、`tool_invocations` **1 行**（`save_memory`），与第 27 节 mock 固定脚本一致。**判据用 5.1 的比例式**（轮数 N → `2N` / `N`），`serve` 开得久就走多轮 |
| **按向导顺序连跑**（Step 0.5 → 4） | 作者侧又按本向导的**真实顺序**跑了一遍（`serve` 一直活着、`chat` 是另一个进程、两边同时写同一个 SQLite）：Step 2 的五个预期、Step 3 的三条判据、Step 4 的 6 次工具调用与 Step 5 的库内计数**全部复现**，两个进程并发写库没有出现 `SQLITE_BUSY` |
| **用户实跑命中的第二处判据缺陷：4.3 ④**（2026-10-21，已改） | 用户按初版 ④ 在 `AGENT.md` 正文末尾加「回答时先自称『对账小欧』」，重跑 `chat` 后模型**没有自称**。**这不是热加载失效**：机制由第 17 节的 `ContextLoaderTest` 判（现由 2026-10-21 补的 `rereadsAgentBodyOnEveryLoad` 直接判 `AGENT.md` 正文；`rereadsFileOnEveryLoad` 判同一机制、读的是 Bootstrap 文件），`PromptBuilder` 每轮都调 `ContextLoader.load()`。真实原因是**模型服从性**——正文第 3 步要求按 Skill 的格式输出，末尾那句与格式指令冲突时模型选了格式（输出首行是 `## 结论：`）。初版 ④ 把「模型照做」当成「热加载生效」的判据，跟 4.3 ③ 数「报个到。」行数是同一类错。**已改**：④ 拆成「先确认改的是 `$WORK` 里那一份（不是仓库 `examples/`）」+「想看体感就用**不冲突且放正文开头**的 `BODY-EDIT-OK`」+「**别用模型服从性判机制**」三段 |
| **用户实跑命中的向导缺陷**（2026-10-21，已修） | 初版 Step 4.3 判「归档区多一行」、Step 5.1 判「恰好 1 轮 / 2 次 LLM / 1 次工具」——用户把 `serve` 从 Step 2 一路开到 Step 4 结束，`tick-demo` 每分钟写一行 `save_memory`，于是两处判据**同时失真**（归档区一串「报个到。」、全表 `tool_invocations` 被 `save_memory` 淹没）。根因是作者侧第一次实测用的是**没有 `tick-demo` 的独立工作区**，按真实顺序连跑那次又只跑了两分钟，噪声还没攒起来。**已改**：4.3 的工具表按 `cli:local:daily-reconcile` 过滤、记忆判据改关键词 `grep`；5.1 改成**与时间无关的比例判据**（轮数 N → `llm_calls=2N`、`tool_invocations=N`）；前置事实 11 / 已知边界 / 坑表第 16 行写清「记忆全局单份 + `save_memory` 不去重」 |

**写向导时没做的**：Step 5.2 的「真模型定时触发」只给了做法、没实跑（要花真钱且与 Step 4 走的是同一条 `AgentService.process`，Step 4 已在真模型上验过链路）。其余各步均为实测。

> **2026-10-21 示例正文修正（用户实跑推动）**：`examples/agents/daily-reconcile/AGENT.md` 第 4 步原写「调 `save_memory` 记一笔『{date} 差异 {N} 笔，最高 {P?}，**已通知**』」，**不看 `notify` 的真实结果**——用户实跑时模型自己指出「记忆里那条『已通知』是错的（实际从未推送成功）」。已改成按结果二选一（成功「已通知」/ 失败「推送失败」），并明写**推送失败 MUST NOT 记「已通知」**。**已经 `cp -a` 到工作区的旧副本不会自动更新**；要用新措辞就重新复制一次示例目录。

---

## 前置事实（跑之前先认这些）

1. **本向导用一个临时工作区**（`/tmp/l29-manual`），不碰仓库根目录的 `.oryxos/`。仓库根的 `.oryxos/agents/` 里若有你自己的 Agent，不参与本向导。
2. **复制示例必须用 `cp -a`**，不能用 `cp -r`：`skills/report-format` 是指向 `.oryxos/skills/report-format` 的**相对软连接**（章程原则 13 的唯一真相源），`cp -r` 会把它解引用成普通目录，`ContextLoader` 判越界、Skill 就不再以「路径可见、正文按需读」的形态出现。
3. **`serve` 不建表**：建表与补列由 `oryxos init` 完成（Step 0.5）。缺表时 `/api/v1/info` 会 500（它读 `sessions` 表）。
4. **mock 档也要求 `DEEPSEEK_API_KEY` 非空**：`application.yaml` 的 `${DEEPSEEK_API_KEY}` 没有默认值。Step 2 / 3 / 5 用假值 `unused` 即可；**只有 Step 4 需要你的真 key**。
5. **frontmatter 只在启动时派生一次**：改 `AGENT.md` 的 frontmatter（provider / tools / schedules）必须重启 `serve`；改**正文**不用重启——`ContextLoader` 每轮重读（`chat` 每次是独立进程，天然是新的）。
6. **`Profile` 名取自目录名**，`AGENT.md` 里没有 `name` 字段（本仓契约）；`schedules` 没有 `id`，任务 id = `<Agent>#<序号>`（从 1 起）。
7. **通知渠道不在 Agent 目录里**：`notify` 解析的是 SQLite 全局注册表 `notify_channels`。示例 Agent 正文写的是「渠道名 `ops`」，但**没有哪个目录文件能创建它**——Step 4 因此**预期**看到 `notify` 失败。这不是缺陷，是 FR 口径（`notify_channels` 不进 frontmatter）。
8. **脚本的 cwd 是工作区根**（不是 Agent 目录）：所以示例正文里的脚本路径写成相对工作区根的 `.oryxos/agents/daily-reconcile/scripts/reconcile.py`。
9. **`RECON_ORDERS_CSV` / `RECON_SETTLE_CSV` 必须是 `chat` 进程的环境变量**：`shell` 工具起子进程时继承 JVM 环境，在另一个终端 export 不算。
10. **本机没有 `sqlite3` CLI**：查库用 `python3` 内置 `sqlite3`（第 28 节向导同款），`jq` 已装、REST 查询照旧用它。
11. **长期记忆是全局单份、不按 Agent 隔离，且 `save_memory` 不去重**（第 22 节的既定契约）：Markdown 档只有一个 `.oryxos/memory/MEMORY.md`，所有 Agent 写同一份；模型每写一次就多一行。本向导的 `tick-demo` **每分钟触发一次、每次都 `save_memory`**，所以 `serve` 活着的每一分钟都会往归档区加一行 `- [<日期>] 报个到。`——**这是预期噪声，不是本节缺陷**。因此 Step 4.3 的记忆判据按**关键词**取、Step 5 的审计计数按**会话过滤 + 每轮比例**判，两者都不看总数。

---

## Step 0：构建（必须 `clean`）

```bash
cd /home/wngbob/oryxos-wngbob
./mvnw clean package -DskipTests -Ddependency-check.skip=true
ls -l oryxos-boot/target/oryxos.jar
```

**预期**：`BUILD SUCCESS`；jar 存在（作者侧 84,590,137 字节，字节数会随构建略有变化，只判「存在且不是几十 KB」）。

> ⚠️ **增量 `mvn package`（不带 `clean`）会打出旧字节码**——`maven-jar-plugin` 在「本模块 classes 没变」时跳过重打，`BUILD SUCCESS` 但产物是旧的（第 26 节实测坑）。本向导一律 `clean`。

想确认本节改动真的进了 jar（`python3` 白名单 + 三个运行时方法）：

```bash
unzip -p oryxos-boot/target/oryxos.jar BOOT-INF/classes/application.yaml | grep -n "allowed_commands"
unzip -p oryxos-boot/target/oryxos.jar "BOOT-INF/lib/oryxos-core-*.jar" > /tmp/l29-core.jar
unzip -l /tmp/l29-core.jar | grep -E "profile/ProfileLoader|profile/ProfileRegistry|schedule/AgentScheduler"
rm -f /tmp/l29-core.jar
```

**预期**：第一行打出含 `python3` 的 `allowed_commands`；`oryxos-core` 的 class 清单里有这三个类。**注意**：`oryxos-core` 在 fat JAR 里是 `BOOT-INF/lib/` 下的**嵌套 jar**，不是 `BOOT-INF/classes` 里的散装 class——直接 `unzip -l | grep core/profile` 会是 0 行（第 25 节向导踩过的假警报）。

---

## Step 0.5：准备临时工作区

```bash
export REPO=/home/wngbob/oryxos-wngbob
export JAR=$REPO/oryxos-boot/target/oryxos.jar
export WORK=/tmp/l29-manual

rm -rf "$WORK" && mkdir -p "$WORK" && cd "$WORK"
java -jar "$JAR" init
```

**预期**：倒数第二段是

```text
数据库表结构:
  与 db/schema.sql 一致（无需补列）
```

**末行**是：

```text
下一步: `oryxos profile create <name>` 建一个 Agent，再 `oryxos chat --profile <name>` 开始对话
```

> ⚠️ 判「建表结论」别用 `| tail -1`——末行是那条「下一步」提示，`tail -1` 会把真正要看的结论挤出去。用 `| tail -5` 或 `| grep -A1 数据库表结构`。

> ⚠️ **换终端**：`REPO` / `JAR` / `WORK` 只在当前 shell 有效。新开终端后如果命令报 `Unable to access jarfile`（后面跟个空字符串），**回本段重新 export**，别重新构建。

---

## Step 1：一个目录就是一个 Agent（CLI 入口）

### 1.1 复制示例（四部分俱全）

```bash
cd "$WORK"
cp -a "$REPO/examples/skills/report-format"   .oryxos/skills/
cp -a "$REPO/examples/agents/daily-reconcile" .oryxos/agents/

find .oryxos/agents/daily-reconcile -maxdepth 2 | sort
```

**预期**：`AGENT.md` / `REFERENCE.md` / `scripts/reconcile.py` / `skills/report-format` 四部分都在。

**软连接自检**（最容易漏的一步）：

```bash
ls -l .oryxos/agents/daily-reconcile/skills/
readlink -f .oryxos/agents/daily-reconcile/skills/report-format
```

**预期**：

```text
lrwxrwxrwx … report-format -> ../../../skills/report-format
```

第二行落在 `$WORK/.oryxos/skills/report-format` **之内**。如果 `ls -l` 那一行以 `d` 开头（普通目录）而不是 `l`，说明用了 `cp -r`——删掉重来。

### 1.2 CLI 立刻可见（**全程没碰 Java**）

```bash
cd "$WORK" && java -jar "$JAR" profile list
```

**预期**：

```text
Agent（共 1 个，目录 .oryxos/agents/）:
  daily-reconcile  有效  provider=deepseek/deepseek-chat  每天核对交易库与清算库当日订单的条数与金额是否一致；有差异就按规范生成分级报告并推送
```

**判据**：本节的全部意义就是这一行——**没有改任何 Java、没有重新编译、没有重启任何常驻进程**。

---

## Step 2：同一个 Agent 从 Web 入口可见 + 定时任务已登记

### 2.1 再建一个 mock 档 Agent（Step 5 用它验「到点自己跑」，不花钱）

```bash
mkdir -p "$WORK/.oryxos/agents/tick-demo"
cat > "$WORK/.oryxos/agents/tick-demo/AGENT.md" <<'EOF'
---
description: 定时触发演示（mock 模型，不花钱）
identity:
  agent_name: 定时演示
  prompt: 你只负责确认自己到点醒来了。
provider:
  name: mock
  model: mock-model
tools: [save_memory]
schedules:
  - cron: "* * * * *"
    zone: Asia/Shanghai
    message: 报个到。
---

到点被触发时，简短回应即可。
EOF
cd "$WORK" && java -jar "$JAR" profile list
```

**预期**：共 **2** 个；`tick-demo  有效  provider=mock/mock-model  定时触发演示（mock 模型，不花钱）`。

> `cron: "* * * * *"` 是 **5 段**（分钟粒度，由 `AgentScheduler.normalizeCron` 前补一个 `0`）。这会**每分钟触发一次**，每触发一次就写一次 `save_memory`；而长期记忆**全局单份**（前置事实 11），所以 `serve` 活着的每一分钟都会往 `MEMORY.md` 归档区加一行 `- [<日期>] 报个到。`。**从这里起就会开始攒**——Step 4.3 判记忆时按关键词取、别数行数，Step 5 判审计时按比例取、别对数条数（两处都已按此写好）。

### 2.2 起服务并核对三个证据

```bash
cd "$WORK"
export DEEPSEEK_API_KEY=unused        # 假值即可：本轮没有 Agent 会真调 deepseek
nohup java -jar "$JAR" serve --port 18091 > "$WORK/serve.log" 2>&1 &
echo $! > "$WORK/serve.pid"

for i in $(seq 1 60); do
  curl -fsS --max-time 2 http://127.0.0.1:18091/api/v1/health >/dev/null 2>&1 && break
  sleep 1
done

curl -fsS http://127.0.0.1:18091/api/v1/health | jq '.data'
grep "定时任务注册完成" "$WORK/serve.log"
curl -fsS http://127.0.0.1:18091/api/v1/info | jq '.data | {agents, activeSessions, registeredTools}'
```

**预期**：

```json
{ "status": "UP", "db": "UP", "providers": { "mock": "UP", "deepseek": "UP" } }
```

> **`providers` 的键序不保证**（服务端 `Map.copyOf`，两次请求可能给不同顺序）。别逐字节比这一行：用 `curl … | jq -S '.data'` 排序后再比，或直接断言 `.data.providers == {"mock":"UP","deepseek":"UP"}`。

```text
定时任务注册完成：共 2 条
```

```json
{ "agents": 2, "activeSessions": 0, "registeredTools": 15 }
```

> **`activeSessions` 是时间相关项**：它等于 `sessions` 表里 `status='active'` 的行数，不是你此刻「有没有在聊」的实时读数。空工作区首个 `serve` 时是 `0`；重跑向导（同一个 `$WORK`）时 Step 4 会留下会话，它就是 `1` 或更多。**判据是「与你库里 active 行数一致」，不是「必须是 0」**——用下面的命令对一下：`python3 -c "import sqlite3;print(sqlite3.connect('$WORK/.oryxos/oryxos.db').execute(\"select count(*) from sessions where status='active'\").fetchone()[0])"`。

### 2.3 `/api/v1/profiles` 里 frontmatter 原样带出

```bash
curl -fsS http://127.0.0.1:18091/api/v1/profiles \
  | jq '.data[] | select(.name=="daily-reconcile")'
```

**判据**（作者侧实测值）：

```json
{
  "name": "daily-reconcile",
  "description": "每天核对交易库与清算库当日订单的条数与金额是否一致；有差异就按规范生成分级报告并推送",
  "provider": { "name": "deepseek", "model": "deepseek-chat" },
  "tools": ["shell", "read_file", "notify", "save_memory"],
  "schedules": [
    { "cron": "0 0 9 * * *", "zone": "Asia/Shanghai", "message": "到点了，核对昨天的订单对账。" }
  ],
  "valid": true,
  "error": null
}
```

> `schedules` 原样带进派生出的 `Profile`——这就是 `AgentScheduler` 拿到 cron 的唯一来源（`AGENT.md` 是定义源，`scheduled_tasks` 只存状态）。

---

## Step 3：丢目录边界——「可见」不等于「可调」（已知边界，机器判不了）

> 这一步验的是**已知边界**（[quickstart.md](quickstart.md) §8 最后一行），不是缺陷。判据是「现象与解释一致」。

```bash
cd "$WORK"
mkdir -p .oryxos/agents/dropped-agent
cat > .oryxos/agents/dropped-agent/AGENT.md <<'EOF'
---
description: 丢目录边界演示
identity:
  agent_name: Dropped
  prompt: 你是临时 Agent。
provider:
  name: deepseek
  model: deepseek-chat
tools: [read_file]
---

临时出现的 Agent。
EOF

# ① /profiles 每次请求实时扫描磁盘 → 立刻可见
curl -fsS http://127.0.0.1:18091/api/v1/profiles | jq -c '[.data[].name]'

# ② /info 的 agents 读的是启动期快照 → 不跟着变
curl -fsS http://127.0.0.1:18091/api/v1/info | jq '.data.agents'

# ③ invoke 也读启动期快照 → 404
curl -s -o /tmp/inv.json -w "HTTP %{http_code}\n" \
  -X POST http://127.0.0.1:18091/api/v1/agents/dropped-agent/invoke \
  -H 'Content-Type: application/json' -d '{"content":"你好"}'
cat /tmp/inv.json; echo
```

**预期**（作者侧实测）：

1. `["daily-reconcile","dropped-agent","tick-demo"]`——**立刻可见**；
2. `2`——**没变**（不是 3）；
3. `HTTP 404` + `{"code":404,"message":"agent not found: dropped-agent","data":null,…}`。

**解释**：扫描发生在**装配期**（`serve` 启动时构建 `ProfileRegistry`），`/api/v1/profiles` 走的是每次请求实时扫描磁盘的另一条路径。运行时注册 / 热加载是第 30 节的交付物（`AgentLifecycleService` + `POST /api/v1/agents`）——本节留下的 `scheduledTasks` 句柄表就是为它铺的路。

清理：

```bash
rm -rf .oryxos/agents/dropped-agent
```

---

## Step 4：渐进式披露（人工项 ①，**真模型，花钱**）

> 需要你的真实 `DEEPSEEK_API_KEY`。这次对话作者侧实测 **5 次 LLM 调用、约 1.1 万 tokens**，耗时约 10 秒。

### 4.1 造两份「昨日导出」

```bash
cd "$WORK"
printf 'order_id,amount\nORD-001,100.00\nORD-002,250.50\nORD-003,80.00\n' > orders.csv
printf 'order_id,amount\nORD-001,100.00\nORD-002,300.50\nORD-004,20.00\n' > settle.csv

# 变量必须给 chat 进程（坑表第 9 条）
export RECON_ORDERS_CSV="$WORK/orders.csv"
export RECON_SETTLE_CSV="$WORK/settle.csv"
```

这份数据刻意造出**三种差异**，逼模型走完「脚本 → Skill 规范 → REFERENCE 定级」三步：

- `ORD-002` 金额 250.50 vs 300.50 → `amount_mismatch`
- `ORD-003` 只在交易库 → `missing_in_settle`（规范里直接定 **P1**）
- `ORD-004` 只在清算库 → `missing_in_orders`

### 4.2 单条模式跑一次

```bash
export DEEPSEEK_API_KEY='<你的真实 key>'    # 手填；命令前加空格可避免进 history（HISTCONTROL=ignorespace）
java -jar "$JAR" chat --profile daily-reconcile --message "到点了，核对昨天的订单对账。"
```

### 4.3 判据

**① 模型上下文里是脚本的产出，不是脚本源码**——查 `tool_invocations`（作者侧实测 6 行），**按本次 chat 会话过滤**：

```bash
python3 - "$WORK/.oryxos/oryxos.db" <<'PY'
import sqlite3, sys
db = sqlite3.connect(sys.argv[1]); db.row_factory = sqlite3.Row
for r in db.execute(
    "select id, tool_name, success, substr(input_json,1,90) inp, "
    "substr(coalesce(error_message,''),1,60) err from tool_invocations "
    "where session_id='cli:local:daily-reconcile' order by id"):
    print(dict(r))
PY
```

> **`where session_id=…` 不能省**：`tick-demo` 从 Step 2 起每分钟往同一个库里写一行 `save_memory {"content":"报个到。"}`（前置事实 11）。不过滤的话，这张表里会混进一长串与本次对话无关的行——用户 2026-10-21 实跑时正是被它带偏的。

**预期形态**（顺序可以不同，条数可以对不上——真模型不保证调用序列）。**这张表是「首跑」判据**——`cli:local:daily-reconcile` 这个会话**第一次建立**时的形态：

| 应出现 | 判据 |
| --- | --- |
| `shell` 1 行、`success=1` | `input_json` 里 `command` 是 `python3 .oryxos/agents/daily-reconcile/scripts/reconcile.py`——**只跑脚本，没有把源码读进上下文**。首跑、重跑都应出现（不跑脚本就没有确定性数据） |
| `read_file` ≥1 行、`success=1` | `input_json` 的 `path` 指向 `SKILL.md`（经软连接解析成绝对路径也算正常）；出现 `REFERENCE.md` 更好，说明它去核了定级依据。**首跑必现；重跑可省略**（见下） |
| `notify` 行、`success=0` | `error_message` 是「未找到名为 'ops' 的通知渠道…」或「未配置任何通知渠道…」——**预期**（前置事实 7）。首跑必现；重跑模型可能知道推不出去而少推一次 |
| `save_memory` 1 行、`success=1` | 落进归档区。**首跑必现；重跑可省略**（见下） |

> ⚠️ **重跑同一会话时，`read_file` / `save_memory` 缺失不是失败**（2026-10-21 实跑命中）：会话身份是 `cli:local:daily-reconcile`，`JpaSessionService.getOrCreate` **命中已有行就复用历史**，上一轮的 Skill 格式、"差异 3 笔"的结论、写过的记忆都还在上下文里，模型完全可以不重复读、不重复写（实测答复原话：「本轮我依旧不重复写记忆」）。**判据要的证据是「模型按需取了 Skill 与参考文档、并落了记忆」**，这件事首跑已经证过；重跑只证「同一会话可连续对话」。
>
> **想要一份首跑语义的干净证据**：换一个全新的工作区（`WORK=/tmp/l29-manual-2`，从 Step 0.5 重走），**不要**删 `sessions` 行或审计行——那会破坏审计完整性，而这个判据本来就不需要动库。

**② 报告分级正确**：终端最终答复里应出现 `【对账 P1】`（`ORD-003` 是 `missing_in_settle`，按 Skill 规范 ≥1 笔即 P1），并列出三条差异。**只看结论，不粘脚本代码或原始 JSON**——这是 Skill 规范里明写的格式要求。

> 判据只判**级别与三条差异**，**不判模型自己算的算术**：作者侧两次真跑里，一次把 `250.50 vs 300.50` 算成「差 50.00」、一次算成「差 200.00」，两次都算错了——那是模型的算术能力，不是本节机制。机制要证明的是「脚本的确定性数字进了上下文、按 Skill 的规则定了级」。

**③ 记忆真的落了**——**按关键词取，不数行数**：

```bash
grep -n "差异 3 笔" "$WORK/.oryxos/memory/MEMORY.md"
sed -n '/^## 归档记忆/,$p' "$WORK/.oryxos/memory/MEMORY.md"
```

**预期**：`grep` **至少命中 1 行**，形如 `- [2026-10-21] 2026-10-20 差异 3 笔，最高 P1，推送失败`（本向导里 `notify` 预期失败）。**判据只认关键词「差异 3 笔」与「P1」**，后缀是「已通知」还是「推送失败」由模型按 `notify` 的真实结果写——示例正文自 2026-10-21 起明确要求**推送失败 MUST NOT 记「已通知」**（更早拷贝下来的工作区副本还是旧措辞，会写「已通知」，同样算通过）。

> **为什么是「至少 1 行」而不是「恰好 1 行」**：`save_memory` 不去重（前置事实 11），同一会话重跑一次就多一行。首跑的正常形态是 1 行；多出来的是重跑留下的，同样是成功的证据。

> ⚠️ **归档区里还会有一长串 `- [<日期>] 报个到。`**——那是 `tick-demo` 每分钟的 `save_memory`（前置事实 11），`serve` 开得越久攒得越多。**这不是失败**：记忆全局单份，mock Agent 与 `daily-reconcile` 写的是同一个文件。判据只认 `daily-reconcile` 写进去的那一行。
>
> **想要一份干净的归档区（可选）**：Step 4 之前先 `kill "$(cat "$WORK/serve.pid")"` 停掉钟推（`chat` 不需要 `serve`），Step 5 再按 5.1 开头那三行重新起。作者侧「两个进程并发写同一个 SQLite 无 `SQLITE_BUSY`」的证据靠的正是**不停服务**——两种跑法都成立，只是判据的取法不同。

**④ 改正文不重启即生效（机制项——别拿模型的服从性当证据）**

**先确认改的是工作区那一份**——不是仓库里的示例：

```bash
tail -3 "$WORK/.oryxos/agents/daily-reconcile/AGENT.md"
diff "$REPO/examples/agents/daily-reconcile/AGENT.md" \
     "$WORK/.oryxos/agents/daily-reconcile/AGENT.md"
```

判据：`tail` 看得到你加的那句；`diff` 里你加的那行应出现在 **`>` 侧**（工作区副本）。若出现在 `<` 侧，说明改的是**仓库示例**——`cp -a` 是复制不是软连接，改仓库里的 `examples/...` 对 `$WORK` 里的副本**毫无影响**，这是「改了没生效」最常见的原因之一。

**想看人工体感**：在正文**开头**（不是末尾）加一句，且**不与正文既有的输出格式冲突**：

```text
【硬性要求】你的最终答复的第一行必须原样输出 BODY-EDIT-OK，然后照常给报告。
```

然后重跑 4.2，`| head -1` 应看到 `BODY-EDIT-OK`。**不用重启任何东西**（`chat` 每次是新进程；`serve` 场景下 `ContextLoader` 每轮重读）。

> **「`BODY-EDIT-OK` 这个改法还没真跑过」**（作者侧与用户侧都还没跑）。它是**从失败原因推导出来的改法**——把指令放到正文开头、且不与既有格式冲突，应当能提高模型照做的概率——**不是实测结论**。别把它当成必须出现的判据；它出现不了也不代表热加载坏了，机制的判据始终在下面那条单测。

> ⚠️ **这条判据本身不可靠，别用它断定热加载失效**。作者侧与用户侧 2026-10-21 都实测过：在正文**末尾**加「回答时先自称『对账小欧』」，模型**没有照做**——它按正文第 3 步的 Skill 格式直接输出了 `## 结论：`（格式指令与末尾那一句冲突时，模型选了格式）。这说明的是**模型服从性**，不是机制。
>
> **机制的机器判据在单测**：`ContextLoaderTest.rereadsAgentBodyOnEveryLoad`（**2026-10-21 新增，判的就是这条**：改 `AGENT.md` 正文 → 下一次 `load()` 立即读到新内容）与 `rereadsFileOnEveryLoad`（同一无缓存机制，读的是 Bootstrap 文件）。`ContextLoader.load()` 每次 `Files.readString` 重读、不持跨调用状态。本次复跑 `ContextLoaderTest` **5/5** + `ProgressiveDisclosureTest` 3/3 全绿。人工这一步只能算体感，且必须用「不冲突 + 放开头」的指令才有观测价值。

> **不要**指望这一步推送成功。要真推到群里，得先 `oryxos notify-channel add ops --type feishu --url-env YOUR_VAR`（第 19/28 节向导的 Step 6 有全套做法）；那是「渠道注册表」的事，不属于本节的验收面。

---

## Step 5：「到点自己跑」（人工项 ②，**mock 档，不花钱**）

### 5.1 等一分钟，看库里有没有账

`tick-demo#1` 在下一分钟整点就会触发。**如果你按 Step 4.3 的「干净跑法」停过服务**，先按 2.2 那三段重新起一次（`export DEEPSEEK_API_KEY=unused` + `nohup … serve --port 18091` + 轮询 `health`），再等一分钟。

查库：

```bash
cd "$WORK"
python3 - "$WORK/.oryxos/oryxos.db" <<'PY'
import sqlite3, sys
db = sqlite3.connect(sys.argv[1]); db.row_factory = sqlite3.Row
print("=== scheduled_tasks ===")
for r in db.execute("select task_id, cron, zone, enabled, run_count, last_status "
                    "from scheduled_tasks order by task_id"):
    print(dict(r))
print("=== task_executions ===")
for r in db.execute("select id, task_id, session_id, success, error_message, duration_ms "
                    "from task_executions order by id"):
    print(dict(r))
print("=== sessions ===")
for r in db.execute("select session_id, profile_name, channel from sessions"):
    print(dict(r))
SID = "scheduler:scheduler:tick-demo"
runs = db.execute("select count(*) from task_executions where task_id='tick-demo#1'").fetchone()[0]
llm = db.execute("select count(*) from llm_calls where session_id=?", (SID,)).fetchone()[0]
tools = db.execute("select count(*) from tool_invocations where session_id=?", (SID,)).fetchone()[0]
print("=== 按轮数校验（每轮 2 次 LLM + 1 次工具，第 27 节 mock 固定脚本）===")
print("轮数 =", runs, " llm_calls =", llm, "（期望", 2 * runs, "）  tool_invocations =", tools, "（期望", runs, "）")
print("比例判据 →", "OK" if (runs >= 1 and llm == 2 * runs and tools == runs) else "不符，别急着当缺陷：先看轮数与上面两张表的行数对不对")
PY
```

**预期**（作者侧 1 轮时的实测值）：

| 表 | 预期 |
| --- | --- |
| `scheduled_tasks` | `tick-demo#1`：`last_status=success`、`enabled=1`、`run_count` = 下面的轮数；`daily-reconcile#1`：`run_count=0`（它的 cron 是每天 **09:00 Asia/Shanghai**，测试期间不会触发）。**这一条是时间相关项**：若你在当天 09:00 之后才跑、且 `serve` 一直活着，它可能已经触发过 1 次（`run_count>0`）——那**不是失败**，按「`run_count` 与本机时钟对得上」判，别照抄 `=0` |
| `task_executions` | `tick-demo#1` 的**行数 = 轮数**：`session_id=scheduler:scheduler:tick-demo`、`success=1`、`error_message=NULL`（作者侧 1 行） |
| `sessions` | 必须有 **1 行** `session_id=scheduler:scheduler:tick-demo`、`profile_name=tick-demo`、`channel=scheduler`（会话按三元组复用，跑多少轮都是同一行）；Step 4 跑过的话还会看到 `cli:local:daily-reconcile` 那一行，**正常** |
| 审计（**按调度会话过滤 + 按比例判**） | 该会话名下 `llm_calls = 2 × 轮数`、`tool_invocations = 1 × 轮数`（工具是 `save_memory`；作者侧 1 轮 → 2 / 1） |

> **为什么是「比例」而不是「恰好 1 / 2 / 1」**：`tick-demo` 每分钟一轮，`serve` 开着多久就走多少轮。用户 2026-10-21 实跑时把 `serve` 从 Step 2 一路开到 Step 4 结束，库里于是有十几轮、`MEMORY.md` 也多了十几行「报个到。」——那不是钟推跑重了，是它**按时跑了每一次**。判据锚在「每一轮 = 2 次 LLM + 1 次工具」这条与时间无关的不变量上。
>
> **为什么还要按会话过滤**：Step 4 已经往同一个库里写过 5 次 LLM 调用 / 6 次工具调用（会话是 `cli:local:daily-reconcile`）。全表 `count(*)` 会把它们混进来，比例立刻对不上（`llm_calls` 多 5、`tool_invocations` 多 6），看起来像「钟推跑重了」。调度会话的 id 是**确定性**的 `<channel>:<user>:<profile>` → `scheduler:scheduler:tick-demo`。

**这四张表同时有账**就是本节要的证据：钟推走的是与 CLI / Web **完全相同**的 `AgentService.process`（章程原则 14），`llm_calls` / `tool_invocations` 一行新审计代码都没有。

也可以从 REST 侧看同一件事：

```bash
curl -fsS http://127.0.0.1:18091/api/v1/schedules | jq -c '.data[] | {taskId, runCount, lastStatus}'
curl -fsS 'http://127.0.0.1:18091/api/v1/schedules/tick-demo%231/executions' | jq -c '.data'
```

**如果 `run_count` 还是 0**：等到下一个整分钟（5 段 cron 是分钟粒度），或看 `serve.log` 里是否每分钟左右出现一串 `LLM 调用完成`。**别重启服务**——重启会让下一轮重新排 cron，但历史不会丢（第 28 节验过的事）。

> 想验「真模型版到点自己跑」：把 `daily-reconcile` 的 `schedules[0].cron` 改成 `"* * * * *"`、重启 `serve`（frontmatter 只在启动时派生）、配好真 key，等一分钟。它走的是与 Step 4 完全相同的链路，**作者侧未实跑**（要花钱）。

---

## Step 6：收尾

```bash
kill "$(cat "$WORK/serve.pid")" 2>/dev/null; sleep 2
rm -f /tmp/inv.json
rm -rf "$WORK"
```

**预期**：无残留进程；`/tmp/l29-manual` 删净；仓库根目录的 `.oryxos/` 全程未被触碰（`git status --short` 里不应出现 `.oryxos/`）。

---

## 已知边界（本节不验，如实记录）

- **脚本的信任边界没做**：`python3` 一进白名单，模型就能跑**工作区任意路径**下的 `.py`，不只本 Agent 的 `scripts/`。要收窄得改第 20/24 节沙箱契约（FR-010 明写不做），归扩展阶段。
- **「丢目录 = 可见但不可调」**：Step 3 验的就是它。运行时注册 / 注销 / 更新 = 第 30 节。
- **脚本的 cwd 是工作区根**，不是 Agent 目录（所以示例正文里脚本路径相对工作区根写）。
- **从 `AGENT.md` 删掉一条 `schedules` 会留下旧行**：第 28 节的 v1 没有 `retired` 字段——重启后不再触发，但 `scheduled_tasks` 里的旧行会留下（列表可见、`runNow` 返回 404）。
- **`tools` 里写了底座没有的工具名**：Agent 照样加载成功，WARN 只在运行期 `ToolRegistry.availableFor` 打出来并跳过。
- **长期记忆全局单份、`save_memory` 不去重**：所有 Agent 共写一份 `MEMORY.md`（第 22 节契约），一个每分钟触发的 Agent 会把归档区刷成一串同一句话。演示里这是**预期噪声**（前置事实 11）；它同时暴露一条真实性质——**记忆的去重与提炼不在核心阶段**（第 22 节：写入时机永远由 Agent 判断，不做自动提炼）。生产环境里让一个高频定时任务无脑 `save_memory`，归档区同样会膨胀：那是 Agent 配置问题，不是底座缺陷。
- **`ProgressiveDisclosureTest` 在文件系统不支持软连接时 `assumeTrue` 跳过**：Linux/WSL 上是真跑（本节实测已跑）。
- **Agent 版本管理 / Agent 市场 / 跨 Agent 能力复用**：本节明确不做（[spec.md](spec.md) 的「明确不做」段）。

---

## 最容易踩的坑

| # | 现象 | 根因 / 处置 |
| --- | --- | --- |
| 1 | `Error: Unable to access jarfile` 后跟空字符串 | 换了终端，`$JAR` 丢了 → 回 Step 0.5 重新 export，**别重新构建** |
| 2 | `ls -l .oryxos/agents/daily-reconcile/skills/` 那一行以 `d` 开头 | 用了 `cp -r`，软连接被解引用成普通目录 → 删掉用 **`cp -a`** 重来（前置事实 2） |
| 3 | `profile list` 里没有 `daily-reconcile`，或显示「无效 … frontmatter 缺失」 | `AGENT.md` 没复制对，或 YAML 缩进坏了 → `find .oryxos/agents -name AGENT.md` 看有没有、`profile list` 的错误会点名原因 |
| 4 | `/api/v1/info` 返回 500（`no such table: sessions`） | 没跑 `oryxos init` → Step 0.5。`serve` 不建表 |
| 5 | `serve` 起不来：`Could not resolve placeholder 'DEEPSEEK_API_KEY'` | 环境变量完全为空 → `export DEEPSEEK_API_KEY=unused`（mock 档也要，坑同第 28 节） |
| 6 | `POST /api/v1/agents/x/invoke` 返回 400 `content is required` | 请求体字段是 **`content`**，不是 `message` |
| 7 | 刚丢进去的目录 `invoke` 404，`/profiles` 却看得见 | **预期**（Step 3 的已知边界）：扫描在装配期，重启后两者一致 |
| 8 | 改了 `AGENT.md` 的 cron / tools，`/profiles` 还是旧值 | 改了**正文**不用重启；改了 **frontmatter** 要重启 `serve`（前置事实 5） |
| 9 | 脚本输出 `{"error": "请设置 RECON_ORDERS_CSV 与 RECON_SETTLE_CSV…"}` | 变量没给到 **`chat` 进程**：`shell` 子进程继承 JVM 环境，另开终端 export 不算 |
| 10 | `shell` 报「命令不在白名单内: python3」 | `application.yaml` 的 `shell.allowed_commands` 里没有 `python3` → 说明 jar 是旧的或配置被改过，回 Step 0 的自查 |
| 11 | `notify` 失败：「未找到名为 'ops' 的通知渠道」/「未配置任何通知渠道」 | **预期**（Step 4）：渠道在 SQLite 全局注册表里，不在 Agent 目录里。要用就 `oryxos notify-channel add ops …`（第 19 节） |
| 12 | `run_count` 一直是 0 | 5 段 cron 是**分钟粒度**，要等到整分钟；且 `serve` 必须活着。改 cron 后要重启 `serve` |
| 13 | `nextRunAt` 显示成 `…T01:00:00Z` 这种 UTC 时间 | 正常：REST 按 ISO-8601 UTC 返回，`zone` 字段才是 `Asia/Shanghai` |
| 14 | `read_file` 的 path 是 `/tmp/l29-manual/.oryxos/skills/…` 绝对路径 | 正常：模型顺着软连接读到了公共实体；`file.allowed_paths: ["."]` 启动时标准化成工作区根，仍在白名单内 |
| 15 | Step 4 工具调用条数和上表不一致 | 正常：真模型不保证调用序列与条数；判的是**每一类必须出现**，不是精确条数 |
| 16 | `MEMORY.md` 归档区一长串「报个到。」；`tool_invocations` 里一堆 `save_memory`；`run_count` / `llm_calls` 不是 1 / 2 | **预期**：Step 2.1 的 `tick-demo` 每分钟一轮、每轮都 `save_memory`，记忆又是全局单份（前置事实 11）。判 Step 4 的记忆用关键词 `grep`（4.3 ③），判 Step 5 的审计用**会话过滤 + 每轮 2:1 比例**（5.1） |
| 17 | 改了 `AGENT.md` 正文，模型行为没变（例如让它「先自称某某」，它没自称） | **先查改的是哪个文件**：`tail -3 "$WORK/.oryxos/agents/daily-reconcile/AGENT.md"` 看得到才算数；再 `diff "$REPO/examples/agents/daily-reconcile/AGENT.md" "$WORK/.oryxos/agents/daily-reconcile/AGENT.md"`——你加的那行若在 **`<` 侧**，说明改的是仓库示例，**对 `$WORK` 副本没有影响**。文件对了还不照做 → **模型服从性**（指令与正文既有格式冲突、或只是位次太靠后），**不是热加载失效**；机制判据是 `ContextLoaderTest.rereadsAgentBodyOnEveryLoad`（2026-10-21 补，直接判 `AGENT.md` 正文；4.3 ④） |

---

## 验收判据汇总

| 人工项 | 步骤 | 通过判据 |
| --- | --- | --- |
| 一个目录 = 一个 Agent（CLI） | Step 1.2 | `profile list` 里有 `daily-reconcile 有效`，全程未改 Java、未重新编译 |
| 同一个 Agent 从 Web 可见 | Step 2.3 | `/api/v1/profiles` 里 `tools` / `schedules` / `provider` 原样带出，`valid:true` |
| 定时任务被登记 | Step 2.2 | 启动日志 `定时任务注册完成：共 2 条` |
| 渐进式披露（真模型） | Step 4.3 ①②③ | `shell` 真跑了 `python3` 且**源码没进上下文**；`read_file` 按需读了 SKILL/REFERENCE（**首跑**必现；同会话重跑可省略）；报告分级正确；`grep "差异 3 笔"` 在 `MEMORY.md` **至少命中 1 行**（归档区里的「报个到。」不算） |
| 到点自己跑（mock，不花钱） | Step 5.1 | `scheduled_tasks.run_count` = `task_executions` 轮数；`sessions` 有 `channel=scheduler` 一行；该调度会话名下 `llm_calls = 2×轮数`、`tool_invocations = 轮数`——四处同时有账 |
| 丢目录边界的现象与解释一致 | Step 3 | `/profiles` 立即可见、`/info` 计数不变、`invoke` 404 —— 与「可见不可调」的解释吻合 |
| 改正文不重启即生效（**机制项**） | Step 4.3 ④ | 单测判卷：`ContextLoaderTest.rereadsAgentBodyOnEveryLoad` 绿（改 `AGENT.md` 正文 → 下一次 `load()` 即见；2026-10-21 补的直接判据），`rereadsFileOnEveryLoad` 判同一无缓存机制。人工体感只在「指令与正文格式不冲突 + 放正文开头」时有观测价值；**模型不照做 ≠ 热加载失效** |
