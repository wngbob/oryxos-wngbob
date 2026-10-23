# 第 31 节人工验收向导 · 三个验收 Demo 与第一个版本

> 体例照第 19 / 22 / 24 / 25 / 26 / 28 / 30 节那几份。
> **本向导的 Step 0~5 作者侧已用真 DeepSeek + 本机临时 webhook 跑过**（证据见 `acceptance-report.md` §二·5）；
> **Step 6（连续两天到点）与 Step 7（干净机器 30 分钟）只能由你人工做**——它们要的是真实时间流逝与一台干净环境。
> 用真实群推送时**有两处要改，不是只换地址**：① `TEAM_WEBHOOK_URL` 换成群机器人地址（且必须在**启动 serve 之前**、在同一个终端里 export）；② `notify-channel add` 的 `--type` 按对端改成 `feishu`（飞书）/ `lark` / `wecom`——用默认的 `webhook` 发给飞书，对端会回 `HTTP 200` + 业务码 `19002`，`notify` 会如实报失败、但群里没消息。

---

## 准备（每个新终端都要先跑这一段）

```bash
# 仓库根；`JAR` 只在当前终端有效——换终端后必须重新 export（第 25 节踩过：报 "Unable to access jarfile" 后跟空串）
cd /home/wngbob/oryxos-wngbob
export JAR="$PWD/oryxos-boot/target/oryxos.jar"
ls -l "$JAR" && echo "JAR=$JAR"        # 必须看到文件；看不到就回 Step 0 构建，别往下走

# 工作区：本向导用一个临时目录，不碰你现有的 .oryxos/
export WORK=/tmp/oryxos-l31-manual
rm -rf "$WORK" && mkdir -p "$WORK" && cd "$WORK"
```

**前置事实**

| # | 事实 | 说明 |
|---|------|------|
| 1 | 构建产物是**一个** fat JAR | `oryxos-boot/target/oryxos.jar`；REST、管理台、CLI 都在里面 |
| 2 | `serve` **不建表** | 必须先 `oryxos init`（Step 0）；否则 `/api/v1/info` 会 500 `no such table` |
| 3 | 启动只要 `DEEPSEEK_API_KEY` | 本仓从未引入 spring-ai autoconfigure，classpath 上没有 `OpenAiAutoConfiguration` |
| 4 | 通知渠道是**全局注册表** | 不在 `AGENT.md` frontmatter 里（`notify_channels` / `skills` 是禁用键，写了会被 `ProfileLoader` 拒绝） |
| 5 | 凭证用 `--url-env` | 库里只存 `${TEAM_WEBHOOK_URL}` 占位，发送时才从环境变量解析 |
| 6 | `python3` 已在 `shell.allowed_commands` | 本机没有 `python`；脚本路径以**工作区根**为基准，不是 Agent 目录 |
| 7 | 三个 Demo 依赖的域名已在随包白名单 | `api.open-meteo.com` / `geocoding-api.open-meteo.com` / `hn.algolia.com` / `api.github.com`；**webhook 对端域名要你自己加**（本地 `127.0.0.1` 也要加） |
| 8 | 长期记忆**按 Agent 隔离**（第 30 节起） | 偏好要在**同一个 Agent**的会话里说；「随便找个 Agent 聊一句」不会写进它的记忆 |

---

## Step 0：构建、初始化、启动

```bash
cd /home/wngbob/oryxos-wngbob
./mvnw clean verify -Ddependency-check.skip=true     # 最后一道门；应全绿
./mvnw clean package -Ddependency-check.skip=true    # 打 fat JAR（不要省 clean，否则可能打进旧字节码）

export JAR="$PWD/oryxos-boot/target/oryxos.jar"
cd "$WORK"
java -jar "$JAR" init
# 预期：创建 .oryxos/，输出「数据库表结构」段；应能看到 7 张表

# 凭证：从 bin/config.yaml 读（该文件已 gitignore），或直接 export 你自己的 key
export DEEPSEEK_API_KEY="$(python3 -c 'import yaml;print(yaml.safe_load(open("/home/wngbob/oryxos-wngbob/bin/config.yaml"))["deepseek"]["api-key"])')"
test -n "$DEEPSEEK_API_KEY" && echo "key 已就绪"

# ⚠️ 通知渠道凭证：**必须在启动 serve 的这个终端里、在 serve 之前 export**
# 库里存的是 ${TEAM_WEBHOOK_URL} 字面量，占位符在 serve 进程内「发送时」解析；
# 换个终端 export、或先启动 serve 再 export，serve 都读不到（报「引用了未设置的环境变量」）。
# 本地演示：export TEAM_WEBHOOK_URL=http://127.0.0.1:9099/hook
# 真实群：  export TEAM_WEBHOOK_URL=https://open.feishu.cn/open-apis/bot/v2/hook/<key>
export TEAM_WEBHOOK_URL=http://127.0.0.1:9099/hook       # 真实群换成群机器人地址
test -n "$TEAM_WEBHOOK_URL" && echo "webhook 已就绪"

# 本地演示 webhook（真实群可跳过这一步，直接用群地址）
cat > /tmp/l31-hook.py <<'PY'
import json, sys
from http.server import BaseHTTPRequestHandler, HTTPServer

class H(BaseHTTPRequestHandler):
    def do_POST(self):
        n = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(n).decode("utf-8", "replace") if n else ""
        with open("/tmp/l31-hook.log", "a") as f:
            f.write(json.dumps({"path": self.path, "body": body}, ensure_ascii=False) + "\n")
        self.send_response(200); self.end_headers(); self.wfile.write(b"ok")
    def log_message(self, *a): pass

HTTPServer(("127.0.0.1", 9099), H).serve_forever()
PY
python3 /tmp/l31-hook.py & echo $! > /tmp/l31-hook.pid

# 启动 serve：本机 webhook 是 http，需要把 127.0.0.1 加进白名单（不改 application.yaml，用属性覆盖）
SPRING_APPLICATION_JSON='{"http":{"allowed_domains":["127.0.0.1","api.open-meteo.com","geocoding-api.open-meteo.com","hn.algolia.com","api.github.com","api.duckduckgo.com","api.tavily.com","open.feishu.cn","open.larksuite.com","qyapi.weixin.qq.com"]}}' \
  java -jar "$JAR" serve > /tmp/l31-serve.log 2>&1 & echo $! > /tmp/l31-serve.pid

for i in $(seq 1 60); do curl -sf localhost:8080/api/v1/health >/dev/null && break; sleep 1; done
curl -s localhost:8080/api/v1/health | python3 -m json.tool
curl -s -o /dev/null -w "admin=%{http_code}\n" localhost:8080/admin/
```

**判据**：`/health` 返回 `UP`；`/admin/` 返回 200（若 404，说明 JAR 是 `-Dfrontend.skip=true` 打的，重打即可）。

---

## Step 1：注册通知渠道（全局，不属于任何 Agent）

```bash
# TEAM_WEBHOOK_URL 已在 Step 0（启动 serve 之前）export；这里只确认它在**当前终端**还在
test -n "$TEAM_WEBHOOK_URL" && echo "webhook 已就绪" || echo "回 Step 0 先 export"

# --type 必须按对端选，选错类型会「HTTP 200 但群里没消息」：
#   本地通用 webhook / n8n / 自建接收端  → webhook
#   飞书 / Lark 群机器人                → feishu / lark
#   企业微信群机器人                    → wecom
java -jar "$JAR" notify-channel add team --type webhook --url-env TEAM_WEBHOOK_URL \
     --description "第 31 节 Demo 群"
java -jar "$JAR" notify-channel list
```

> **存错 `type` 了怎么办**：CLI 没有 update，先 `java -jar "$JAR" notify-channel remove team` 再按正确 `--type` 重加。
> **只改了 `TEAM_WEBHOOK_URL` 的值？** 不用动渠道（库里存的是占位符）；但 `serve` 进程必须**重启**才能看到新值——它继承的是启动时的环境。

**判据**：

- `add` 的输出**不回显地址**，只打渠道名与类型（用 `--url-env` 时会另起一行打 `${TEAM_WEBHOOK_URL}` 这个占位符本身）。
- `list` 的 `url` 列显示 `${TEAM_WEBHOOK_URL}`，`config` 列只打键名；`type` 列与你选的对端一致。
- 重跑 `add` 同名 → 报「已存在」，不覆盖。

---

## Step 2：Demo 一 —— 走 API 创建天气 Agent

```bash
# 1) 建脚手架（30 节：写完目录即注册，免重启）
curl -s -X POST localhost:8080/api/v1/agents -H 'Content-Type: application/json' \
     -d '{"name":"weather-daily","description":"每天早上查北京天气并推送穿搭建议"}' | python3 -m json.tool

# 2) 用仓库里的完整 AGENT.md 覆写（PUT 即时生效）
python3 - <<'PY'
import json, urllib.request
body = open("/home/wngbob/oryxos-wngbob/examples/agents/weather-daily/AGENT.md").read()
req = urllib.request.Request(
    "http://localhost:8080/api/v1/agents/weather-daily",
    data=json.dumps({"agentMarkdown": body}).encode(),
    headers={"Content-Type": "application/json"}, method="PUT")
print(json.load(urllib.request.urlopen(req)))
PY

# 3) 三个入口都应看到它
curl -s localhost:8080/api/v1/profiles | python3 -m json.tool | grep -A3 weather-daily
curl -s localhost:8080/api/v1/schedules | python3 -m json.tool
```

**判据**：

- `POST` 返回 200；`PUT` 返回 200。
- `/profiles` 里有 `weather-daily`，`tools` = `["http_get","notify"]`。
- `/schedules` 里有 `weather-daily#1`，cron `0 0 8 * * *`，时区 `Asia/Shanghai`。
- 工作区里 `cat .oryxos/agents/weather-daily/AGENT.md` 与仓库那份**逐字相同**（API 创建路径与手写目录最终形态一致）。

> 说明：`POST /api/v1/agents` 的请求体只有 `name` / `description`，**不含 tools / cron**；完整定义靠第二步 `PUT` 覆写。
> 这与课件的「表单一次填完」不同，是本仓 30 节 API 的实际形状（已知偏差，见验收报告 §三·2）。

---

## Step 3：Demo 二 / 三 —— 复制手写目录

```bash
cd "$WORK"
cp -a /home/wngbob/oryxos-wngbob/examples/skills/digest-format     .oryxos/skills/
cp -a /home/wngbob/oryxos-wngbob/examples/agents/daily-tech-digest .oryxos/agents/
cp -a /home/wngbob/oryxos-wngbob/examples/agents/github-daily      .oryxos/agents/

# 软连接必须保留（-a 而不是 -r）；读到 SKILL.md 才算对
readlink .oryxos/agents/daily-tech-digest/skills/digest-format
readlink -f .oryxos/agents/daily-tech-digest/skills/digest-format
head -3 .oryxos/agents/daily-tech-digest/skills/digest-format/SKILL.md

# Watcher 应在数秒内拾取（不用重启 serve）
sleep 3
curl -s localhost:8080/api/v1/profiles | python3 -m json.tool | grep -E "daily-tech-digest|github-daily"
curl -s localhost:8080/api/v1/schedules | python3 -m json.tool | grep -E "daily-tech-digest#1|github-daily#1"
```

**判据**：

- `readlink` 输出 `../../../skills/digest-format`（相对软连接）。
- `readlink -f` 落在 `$WORK/.oryxos/skills/digest-format`。
- `/schedules` 共 **3 条**：weather-daily#1 / daily-tech-digest#1 / github-daily#1。

> 若软连接变成了普通文件：本仓 `.git/config` 的 `core.symlinks` 必须为 `true`，否则 git 会把软连接签出成文本文件
> （第 30 节已修并留痕）。

---

## Step 4：人推（先人推，再钟推）

### 4.1 给两个日报 Agent 种下长期偏好

长期记忆按 Agent 隔离，**必须在它自己的会话里说**。两条命令各会触发一次完整的 Agent 回合（会真的跑工具、真的推一条消息到 `team`），这是预期：

```bash
curl -s -X POST localhost:8080/api/v1/agents/daily-tech-digest/session/messages \
     -H 'Content-Type: application/json' \
     -d '{"content":"以后帮我关注科技新闻的话，我更关注 AI 和芯片方向。"}' | python3 -m json.tool

curl -s -X POST localhost:8080/api/v1/agents/github-daily/session/messages \
     -H 'Content-Type: application/json' \
     -d '{"content":"我更关注 Agent 框架和推理引擎方向的开源项目。"}' | python3 -m json.tool

cat .oryxos/agents/daily-tech-digest/MEMORY.md
cat .oryxos/agents/github-daily/MEMORY.md
```

**判据**：

- 两个 `MEMORY.md` 的「归档记忆」各多一行偏好（日期 + 方向）。
- `.oryxos/memory/MEMORY.md` **不变**——记忆没有落到全局兜底文件（per-agent 隔离成立）。

### 4.2 天气 Agent 人推补跑

```bash
curl -s -X POST localhost:8080/api/v1/agents/weather-daily/invoke \
     -H 'Content-Type: application/json' \
     -d '{"content":"到点了，按你的说明执行。"}' | python3 -m json.tool
```

**判据**：回复里有真实温度 / 降水 / 风速，且给穿搭建议；`/tmp/l31-hook.log`（或真实群）收到一条含天气与建议的消息。

**2026-10-22 起天气 Demo 是两步 `http_get`**：先 `geocoding-api.open-meteo.com` 按城市名取经纬度，再 `api.open-meteo.com` 取天气——所以审计里会有**两条** `http_get`，且 URL 里的坐标来自第一步的返回值，不是提示词里写死的。改城市只需改正文里那一处城市名。

**没收到？按顺序查这四层**（`notify` 的成败只看 `tool_invocations`，不看 `lastStatus`）：

1. `error_message` 是「引用了未设置的环境变量」→ `serve` 启动时没有该变量。`ps eww -p $(cat /tmp/l31-serve.pid) | tr ' ' '\n' | grep '^TEAM_WEBHOOK_URL='` 应能看到；看不到就 `kill` 掉 serve、export 后重启。
2. `error_message` 里带业务码 `19002` / `errcode 40008` → 渠道 `type` 选错了（飞书要 `feishu`、企微要 `wecom`）。`notify-channel remove team` 后按正确类型重加。
3. `error_message` 是沙箱 / 域名 → 真实群域名（`open.feishu.cn`）随包已在白名单；本地 `127.0.0.1` 需要 Step 0 的覆盖。
4. 全都没有报错、但群里仍无消息 → 群机器人的「自定义关键词 / 签名」安全设置把消息滤掉了（飞书回 `19024 Key Words Not Found`，第 28 节踩过）；改关键词或给渠道配 `--config secret=...`。

### 4.3 审计对账（人推）

```bash
python3 - <<'PY'
import sqlite3
con = sqlite3.connect("/tmp/oryxos-l31-manual/.oryxos/oryxos.db")
print("llm_calls by session:")
for r in con.execute("select session_id, count(*), sum(success) from llm_calls group by session_id"):
    print("  ", r)
print("tool_invocations:")
for r in con.execute("select id, session_id, tool_name, success, error_message from tool_invocations order by id"):
    print("  ", r)
PY
```

**判据**：

- 每次工具调用一行 `tool_invocations`，`success=1`；`notify` 的 `input_json.content` 是完整消息。
- 每次 LLM 调用一行 `llm_calls`，`success=1`。
- 科技日报的 `read_file` 路径是 `.../.oryxos/skills/digest-format/SKILL.md`（按需读，不在启动时预载）。
- GitHub 日报的 `shell` 命令是 `python3 .oryxos/agents/github-daily/scripts/github_trending.py`。

---

## Step 5：钟推（立即执行，别干等八点）

```bash
for id in "weather-daily%231" "daily-tech-digest%231" "github-daily%231"; do
  echo "=== $id ==="
  curl -s -X POST "localhost:8080/api/v1/schedules/$id/run" | python3 -m json.tool
done
```

**判据**：

- 三条都 `lastStatus: "success"`、`runCount` +1。
- `task_executions` 三行、`success=1`，各自 `session_id` 是 `scheduler:scheduler:<name>`。
- `sessions` 里三个调度会话各自有完整对话；**同一个任务重复跑复用同一个会话**。
- 科技日报的日报内容把 AI 相关条目排在最前，并点明「与你关注的领域相关」。
- GitHub 日报的 AI 段把 `deepseek-ai/DeepInfer` 之类排在前面，并说明「命中你的推理引擎方向」。
- **脚本源码不进上下文**（关键判据）：

```bash
python3 - <<'PY'
import sqlite3
con = sqlite3.connect("/tmp/oryxos-l31-manual/.oryxos/oryxos.db")
s = con.execute("select messages_json from sessions where session_id=?",
                ("scheduler:scheduler:github-daily",)).fetchone()[0]
for probe in ["urlopen", "def search", "import json", "stargazers_count"]:
    print(probe, "->", probe in s)
print("JSON 产出在上下文里 ->", "stargazers_count" in s or "stars" in s)
PY
```

预期：四个源码探针全部 `False`；`stars` 一类字段在（工具返回的 stdout JSON 进了上下文）。

**真实的群 / webhook 判据**：群里收到三条消息，或 `/tmp/l31-hook.log` 新增三条。
**注意**：任务 `lastStatus: "success"` 只表示 ReAct 跑完没抛异常——**推送成败要看 `tool_invocations` 里 `notify` 那一行的 `success` / `error_message`**（第 28 节向导踩过这个坑）。

### 5.1 改 cron：直接编辑 `AGENT.md` 即生效（2026-10-22 起，**不用重启**）

```bash
sed -i 's|^  - cron: .*|  - cron: "0 33 17 * * *"|' .oryxos/agents/weather-daily/AGENT.md
sleep 1
curl -s localhost:8080/api/v1/schedules | python3 -c "
import json,sys
for s in json.load(sys.stdin)['data']:
    if s['taskId']=='weather-daily#1': print(s['cron'], s['nextRunAt'])
"
```

**判据**：1 秒内 `cron` 变成 `0 33 17 * * *`，`serve` 日志出现 `AGENT.md 已变更，已重新登记并重置调度会话：weather-daily`；**不要重启**。
**边界**：保存了语法错误的内容时，日志只告警 `AGENT.md 变更后重新登记失败，保留原运行态`，旧 cron 继续跑；改好再保存即恢复。编辑器「写临时文件 → 改名覆盖」的删除事件不会误注销。
**调度会话历史会被一起清空**（2026-10-22 起）：编辑一次 `AGENT.md`，该 Agent 的 `scheduler:scheduler:<name>` 历史就清空——这正是下面 Step 5.2 的前提，否则模型会照着旧上下文里的城市 / URL / 工具调用原样重放，改了正文也不生效。只清会话消息，审计表与 `task_executions` 一行不动；重启与 `PUT /api/v1/agents/{name}` **不**清历史。

### 5.2 改正文（换城市）：旧上下文会锚定，重置后一次到位

编辑工作区那份的正文，把城市从 `北京` 改成 `成都`（`http://.../search?name=成都` 那一处，全文只出现一次），等 1 秒后 `POST /api/v1/schedules/weather-daily%231/run`。

**判据**：`tool_invocations` 里这次有两行 `http_get`——先 `geocoding-api.open-meteo.com/v1/search?name=成都`、再 `api.open-meteo.com/v1/forecast?latitude=30.66667&longitude=104.06667`；推送内容写的是成都。
**为什么需要重置**：2026-10-22 前没清历史时，模型会复制上一轮那次北京 `http_get` 的完整参数（实测连跑 8 轮、`tool_invocations` 里只有对 `latitude=39.9&longitude=116.4` 的请求），system prompt 已是新的却压不过 48 条会话历史。清理方式已内置，无需手改库；若线上仍是旧结果，先确认第 13 行（改的是不是工作区那份），再确认日志里有「已重置调度会话历史」。

---

## Step 6：连续两天自动跑（**只能人工，作者侧未做**）

前置：`serve` 保持运行、`TEAM_WEBHOOK_URL` 在**启动 serve 的那个终端**里已导出（读时解析发生在发送时）。

| 时间 | 判据 |
|------|------|
| 第 1 天 08:00 / 09:00 / 09:30 | 群里各收到一条：天气穿搭 / 科技日报 / GitHub 日报 |
| 第 1 天晚些时候 | `GET /api/v1/schedules` 三条 `lastStatus=success`、`runCount=1`；`task_executions` 三行 |
| 第 2 天同一时间 | `runCount=2`、`task_executions` 六行；三个调度会话仍是各自那一个（`sessions` 不新增调度会话） |
| 两天全程 | `llm_calls` / `tool_invocations` 只增不减、无 `success=0`；进程不重启、端口不变 |

> 不想等两天时，可用 Step 5 的 `run` 连续跑两轮代替，但那**证明不了 cron 到点触发**；
> 「到点了」这件事在第 25 / 28 节已由单测与真链路验过，本步验的是**这三个 Agent 连续两天无人值守**。
> 演示彩蛋：现场改 `github-daily/AGENT.md` 正文（例如「AI 段每条加一句一句话点评」），**不重启**，
> 下一次触发就拿新版（`AgentStore` / Watcher 或直接改工作区文件都即时生效）。

---

## Step 7：发布清单（**tag 由你决定，本向导不代打**）

```bash
cd /home/wngbob/oryxos-wngbob
./mvnw clean verify -Ddependency-check.skip=true      # 全绿
./mvnw clean package -Ddependency-check.skip=true     # 产出 oryxos-boot/target/oryxos.jar（含管理台）
java -jar oryxos-boot/target/oryxos.jar --version     # 0.1.0-SNAPSHOT
ls -lh oryxos-boot/target/oryxos.jar

# 发布 tag（由你执行，本向导不代跑）
# git tag -a v0.1.0 -m "OryxOS v0.1.0：运行时内核 + 三个验收 Demo" && git push --tags
```

**干净机器 30 分钟标准**（按 README「快速开始」从零走）：

1. 装 JDK 21，拿到 `oryxos.jar`；
2. `oryxos init`；
3. `export DEEPSEEK_API_KEY=...`；
4. `notify-channel add team --type <你的渠道> --url-env TEAM_WEBHOOK_URL`，导出地址；
5. `cp -a` 三个 Agent 目录（+ `digest-format` Skill）；
6. `serve`，`/admin/` 能开，`/schedules` 三条在；
7. 任选一条 `POST /run`，群收到消息。

**哪一步卡住，缺的是文档不是代码**——把卡点记下来，回修 README / 本向导。

---

## 坑表（按命中概率排序）

| # | 现象 | 原因 / 处理 |
|---|------|-------------|
| 1 | `Unable to access jarfile` 后跟空串 | 换了终端，`$JAR` 丢了。回「准备」段重新 export |
| 2 | `/api/v1/info` 500 `no such table: sessions` | 没跑 `oryxos init`；`serve` 不建表 |
| 3 | `/admin/` 404，其余端点正常 | JAR 用 `-Dfrontend.skip=true` 打的，缺管理台静态资源；重打 |
| 4 | `notify` 成功但群里没消息 | `lastStatus` 不等于推送成功；查 `tool_invocations` 里 `notify` 的 `success` / `error_message` |
| 5 | `notify` 报「未配置任何通知渠道」 | 渠道名对不上：Agent 正文写的是 `team`，注册时也必须是 `team` |
| 6 | `notify` 报环境变量缺失 | `--url-env` 存的是占位符，**发送进程**（`serve`）里必须有该变量；必须**先 export 再启动 serve**（换终端 export 不算，改值后要重启 serve） |
| 7 | 本地 webhook 被沙箱拦 | `127.0.0.1` 不在 `http.allowed_domains`；按 Step 0 的 `SPRING_APPLICATION_JSON` 覆盖 |
| 8 | `python3: command not found` / 被沙箱拦 | 本机没有 `python`；白名单里的是 `python3`（随包默认已含） |
| 9 | 脚本 `FileNotFoundError` | `shell` 的工作目录是**进程 cwd**，脚本路径必须相对工作区根写成 `.oryxos/agents/github-daily/scripts/...` |
| 10 | Skill 报「绑定越出公共 Skill 目录」 | `skills/<name>` 是实体文件而不是软连接；用 `cp -a`，并确认 `core.symlinks=true` |
| 11 | `ProfileLoader` 报禁用键 | frontmatter 里写了 `skills:` 或 `notify_channels:`；通知渠道走 `notify-channel add`，Skill 走软连接 |
| 12 | 记忆偏好不生效 | 偏好说给了别的 Agent（记忆按 Agent 隔离）；要在 `daily-tech-digest` / `github-daily` **自己的**会话里说，且它的 `tools` 要含 `save_memory` |
| 13 | 改了 `examples/...` 但行为没变 | Agent 跑的是 `$WORK/.oryxos/agents/...` 那一份，`cp -a` 是复制不是软连接；改工作区那份 |
| 14 | `github-daily` 的 `#` 让 curl 报错 | URL 里必须写成 `%23`（`github-daily%231`） |
| 15 | GitHub 脚本 403 / 空结果 | 未认证 GitHub Search API 限速 10 次/分钟；日跑一次没问题，连跑压测会 403 |
| 16 | 环境变量齐了、`notify` 仍失败，错误里带 `19002` / `errcode 40008` | 渠道 `type` 与对端不符（飞书必须 `feishu`、企微必须 `wecom`）；`notify-channel remove team` 后按正确 `--type` 重加 |
| 17 | 改了正文（如城市）但下次触发仍是旧结果 | 调度会话历史锚定：模型照抄旧上下文里的工具参数。2026-10-22 起**直接编辑 `AGENT.md` 会自动清空该 Agent 的调度会话历史**（日志 `已重置调度会话历史`）；仍复现先看第 13 行改的是不是工作区那份 |
