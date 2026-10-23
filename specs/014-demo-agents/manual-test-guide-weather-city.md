# 简要向导：weather-daily 换城市到「成都」（真飞书群档）

> 目的只有一个：验证**把城市从北京改成成都之后，一轮钟推真的查成都、推成都**，并且**真的进飞书群**。
> 完整版见同目录 `manual-test-guide.md`（Step 5.1 / 5.2），本文只把这段抽出来、补上判据与排查。
> 向导日期：2026-10-23。**2026-10-23 修订②**（本次）：
> ① 推送对端改为**真实飞书群**（`export TEAM_WEBHOOK_URL='https://open.feishu.cn/open-apis/bot/v2/hook/...'`），
>    渠道 `--type` 必须是 **`feishu`**，不能是 `webhook`；
> ② 新增 **Step 0 环境重建**与 **Step 0.5 渠道重建**——工作区被清理过，本文从零走到群里有消息，不用回忆上轮做过什么；
> ③ J4 的判定手段从「看本机 hook 日志」改为「群里看消息 + 审计表 `notify.error_message`」。
> 上一轮的事故（`type=webhook` 发给飞书 → 群里没消息但 `notify success=1`）见本文末尾「事故留痕」。
> 跑完请把每步的「判定」告诉我，不要只说「看起来对」。

---

## 判据是什么（先说结论，后面每一步都对着它打勾）

一次成功的换城市触发，必须**同时**满足下面五条——少一条都不算通过：

| # | 判据 | 机器判定方式 |
|---|------|--------------|
| J1 | 工作区那份正文里 `成都` 出现 **1** 次、`北京` 出现 **0** 次 | `grep -c` |
| J2 | 改完 1~3 秒内，`serve` 日志出现 `AGENT.md 已变更，已重新登记并重置调度会话：weather-daily` | `grep` 日志 |
| J3 | 这次触发的 `tool_invocations` 是**两次** `http_get`：先 `geocoding-api.open-meteo.com/v1/search?name=成都`，再 `api.open-meteo.com/v1/forecast?latitude=30.66667&longitude=104.06667`（**不是** `39.9/116.4`） | 查库 |
| J4 | 这次触发的 `notify` 行是 `type=feishu` 的渠道、`success=1`、`error_message` 为空 | 查库 |
| J5 | **飞书群里**真的收到一条写**成都**天气的消息 | 看群 |

> J3 是这一段最硬的一条：它直接证明模型**没有照抄**上一轮北京的坐标。
> **J4 与 J5 必须分开看**——上一轮的事故正是「J4 绿、J5 红」：通用档把飞书的 `HTTP 200 + 业务码 19002` 判成了成功。
> 所以**J4 绿不等于 J5 绿**；J5 才是这条向导的终点，只有你能判。

---

## 准备（每个新终端都要跑）

```bash
cd /home/wngbob/oryxos-wngbob
export JAR="$PWD/oryxos-boot/target/oryxos.jar"
export WS=/tmp/oryxos-l31-city
ls -l "$JAR" && echo "JAR=$JAR"
```

看不到文件就别往下走：`$JAR` 会报 "Unable to access jarfile" 加空串（换终端丢了 export）。
**这次不要改成你真实在跑的那个工作区**——下面 Step 0 会把 `/tmp/oryxos-l31-city` 整个重建成干净状态。

**推送地址（真实飞书群机器人）**：

```bash
export TEAM_WEBHOOK_URL='https://open.feishu.cn/open-apis/bot/v2/hook/<你的 key>'
test -n "$TEAM_WEBHOOK_URL" && echo "webhook 已就绪"
echo "${TEAM_WEBHOOK_URL:0:48}…"        # 只回显前缀，确认换对了；MUST NOT 整串打印
```

> ⚠️ **这个 export 必须发生在启动 `serve` 之前、且在同一个终端里**。库里存的是 `${TEAM_WEBHOOK_URL}` 字面量，
> 占位符在 **serve 进程发送时**解析，而环境变量是 serve **启动时**继承的快照。
> 先起 serve 再 export、或换个终端 export，serve 都读不到（报「引用了未设置的环境变量」）。

---

## Step 0：从零重建工作区并启动 serve（8099）

工作区被清理过就整段跑；**若 `$WS/.oryxos/agents/weather-daily` 已存在且 `serve` 活着，可跳过本步直接 Step 0.5**。

```bash
# 0.1 先停掉上一轮残留（有就停，没有就跳过）；核 cmdline 防 PID 复用误杀
for p in /tmp/oryxos-l31-city-serve.pid; do
  pid=$(cat "$p" 2>/dev/null)
  [ -n "$pid" ] && [ -d /proc/$pid ] && tr '\0' ' ' < /proc/$pid/cmdline | grep -q 'oryxos.jar.*serve' \
    && kill "$pid" && echo "已停 serve $pid"
done
sleep 2

# 0.2 新工作区
rm -rf "$WS" && mkdir -p "$WS" && cd "$WS"
java -jar "$JAR" init | tail -20        # 预期：7 张表

# 0.3 DeepSeek key（从 gitignore 的 bin/config.yaml 取）
export DEEPSEEK_API_KEY="$(python3 -c 'import yaml;print(yaml.safe_load(open("/home/wngbob/oryxos-wngbob/bin/config.yaml"))["deepseek"]["api-key"])')"
test -n "$DEEPSEEK_API_KEY" && echo "key 已就绪"

# 0.4 起 serve（8099）。真实飞书域 open.feishu.cn 随包已在白名单，
#     这里覆盖白名单只是为了把 127.0.0.1 也留着（方便以后回本机 hook 对照）
SPRING_APPLICATION_JSON='{"http":{"allowed_domains":["127.0.0.1","api.open-meteo.com","geocoding-api.open-meteo.com","hn.algolia.com","api.github.com","api.duckduckgo.com","api.tavily.com","open.feishu.cn","open.larksuite.com","qyapi.weixin.qq.com"]}}' \
  java -jar "$JAR" serve --port 8099 > /tmp/oryxos-l31-city-serve.log 2>&1 &
echo $! > /tmp/oryxos-l31-city-serve.pid

for i in $(seq 1 60); do curl -sf localhost:8099/api/v1/health >/dev/null && break; sleep 1; done
curl -s localhost:8099/api/v1/health | python3 -m json.tool

# 0.5 硬性自检：serve 进程里**必须有** TEAM_WEBHOOK_URL
tr '\0' '\n' < /proc/$(cat /tmp/oryxos-l31-city-serve.pid)/environ | grep -c '^TEAM_WEBHOOK_URL='
```

**判定**：`/health` 是 `"status":"UP"`；最后一条输出 **`1`**。
输出 `0` → 回「准备」段 export，然后 `kill` 掉 serve 重跑 0.4。**别跳过这步**，否则 J4 必然红、而且看不出是城市的问题。

---

## Step 0.5：注册飞书渠道（`--type feishu`）

**这一段上一轮做错了，是本轮最要紧的一步。**

```bash
cd /home/wngbob/oryxos-wngbob

# 0.5.1 先把库里可能存在的旧渠道清掉（CLI 没有 update，改 type 只能 remove 后重加）
java -jar "$JAR" notify-channel remove team || true

# 0.5.2 按**对端类型**注册：飞书群机器人 = feishu
java -jar "$JAR" notify-channel add team --type feishu --url-env TEAM_WEBHOOK_URL \
     --description "第 31 节 Demo 群"

# 0.5.3 列表与库里各核一遍
java -jar "$JAR" notify-channel list
python3 - <<'EOF'
import sqlite3
db = sqlite3.connect("/tmp/oryxos-l31-city/.oryxos/oryxos.db")
print(db.execute("select name, type, url, config_json from notify_channels").fetchall())
EOF
```

**判定**：

- `list` 的 `type` 列是 **`feishu`**，`url` 列显示 `${TEAM_WEBHOOK_URL}`（占位符本身，不是地址），`config` 列为空。
- 库里那行是 `('team', 'feishu', '${TEAM_WEBHOOK_URL}', None)`——**`type` 必须是 `feishu`**。
- `add` 的**任何输出里都不出现真实地址**（凭证不落库、不回显）。

`--type` 对照表（存错了只会「群里没消息」，不会报错）：

| 对端 | `--type` | 报文 |
|---|---|---|
| 飞书群机器人 | **`feishu`** | `{"msg_type":"text","content":{"text":...}}` |
| Lark 群机器人 | `lark` | 同上（同一套协议） |
| 企业微信群机器人 | `wecom` | `{"msgtype":"text","text":{"content":...}}` |
| 本地 hook / n8n / 自建接收端 | `webhook` | `{"content":"..."}`，**2xx 即算成功** |

> 渠道在库里，**serve 每次发送时重读**，所以 0.5 做完**不用重启 serve**。
> 但如果 0.5 之前改过 `TEAM_WEBHOOK_URL` 的**值**，那还是得按 0.4 重启——那是环境变量，不是库。

---

## Step 1：把 Agent 装进工作区、改城市（只改一处）

```bash
cd "$WS"
cp -a /home/wngbob/oryxos-wngbob/examples/agents/weather-daily .oryxos/agents/
sleep 3     # Watcher 拾取目录（cp -a 的目录先到、AGENT.md 后到，靠有界重试兜住）

curl -s localhost:8099/api/v1/schedules | python3 -c "
import json,sys
for s in json.load(sys.stdin)['data']:
    if s['taskId']=='weather-daily#1': print(s['cron'], s['zone'], s['enabled'], s['nextRunAt'])
"
```

**判定**：输出 `0 0 8 * * * Asia/Shanghai True …`。
`/schedules` 里没有它 → 看 serve 日志里有没有 `重新登记失败`，或确认 `cp -a` 落在 `$WS` 而不是仓库目录。

然后改城市：

```bash
cp .oryxos/agents/weather-daily/AGENT.md /tmp/weather-AGENT.md.bak
sed -i 's|name=北京&|name=成都\&|' .oryxos/agents/weather-daily/AGENT.md
grep -c '成都' .oryxos/agents/weather-daily/AGENT.md    # 期望 1
grep -c '北京' .oryxos/agents/weather-daily/AGENT.md    # 期望 0
```

**判定 J1**：两行输出分别是 `1` 和 `0`。
只改第 1 步 geocoding URL 里 `name=` 的那一处——正文第 13 行明写「城市名只出现这一次」，第 2 步的 forecast URL 里是 `<lat>` / `<lon>` 占位、没有坐标字面量。**不要去改第 2 步**，也不要手动填经纬度。

---

## Step 2：确认热加载 + 自动重置都发生了

等 2 秒后查日志：

```bash
grep -E '已重新登记并重置调度会话|重新登记失败|重置调度会话失败' /tmp/oryxos-l31-city-serve.log | tail -5
```

**判定 J2**：最后一行是 `AGENT.md 已变更，已重新登记并重置调度会话：weather-daily`（紧跟着还会有一条 `已重置调度会话历史：weather-daily`）。

- 只有 `AGENT.md 变更后重新登记失败，保留原运行态：…` → 正文写得不对（多半是 frontmatter 缩进被 sed 破坏了），改回来再来；旧 cron 还在跑，不会下线。
- 只有 `已重新登记` 没有 `重置调度会话` → 报出来，这是缺陷。
- 一行都没有 → 改的多半不是工作区那一份（`cp -a` 是复制不是软链接），或者 `serve` 不是从 `$WS` 起的。

---

## Step 3：触发一次，查审计表（J3 / J4 的主证据）

```bash
curl -s -X POST localhost:8099/api/v1/schedules/weather-daily%231/run | python3 -m json.tool
```

等它返回（真模型一般 5~15 秒），然后查库里这次调了什么：

```bash
python3 - <<'EOF'
import sqlite3
db = sqlite3.connect("/tmp/oryxos-l31-city/.oryxos/oryxos.db")
rows = db.execute("""
  select id, tool_name, success, coalesce(error_message,''), input_json
  from tool_invocations
  where session_id = 'scheduler:scheduler:weather-daily'
  order by rowid desc limit 6
""").fetchall()
for i, name, ok, err, inp in reversed(rows):
    print(i, name, "success=%d" % ok, err[:100], inp[:150])
EOF
```

**判定 J3**：最近这一轮里能看到两条 `http_get`，URL 依次是

1. `…/v1/search?name=成都&count=1&language=zh&format=json`
2. `…/v1/forecast?latitude=30.66…&longitude=104.06…&current=…`（成都的真实坐标，**不是** `39.9/116.4`）

**判定 J4**：`notify` 那一行 `success=1`、`error_message` **为空**；`input_json` 里的 `content` 写的是成都天气。

再确认这次用的确实是飞书档（渠道配置决定，Step 0.5 已核过；这一步看的是**运行期实际读到的那一行**）：

```bash
python3 -c "
import sqlite3
db=sqlite3.connect('/tmp/oryxos-l31-city/.oryxos/oryxos.db')
print(db.execute(\"select name,type from notify_channels\").fetchall())
"
```

**失败对照**：

| 看到什么 | 含义 |
|---|---|
| forecast 那条是 `latitude=39.9&longitude=116.4` | 模型照抄了旧坐标——Step 2 的「重置」没生效，报出来，别自己改库 |
| `notify success=0`，错误里有「引用了未设置的环境变量」 | `serve` 进程缺 `TEAM_WEBHOOK_URL`，回「准备」段 + Step 0.5 |
| `notify success=0`，错误里有 **`19002` / `msg_type`** | 渠道 `type` 还是 `webhook`（Step 0.5 没做或做错了）——飞书要 `feishu` |
| `notify success=0`，错误里有 **`19024` / Key Words** | 群机器人开了「自定义关键词」，消息里没有该关键词；改关键词，或机器人改签名后给渠道加 `--config secret=...` |
| `notify success=0`，错误里有 **`19021` / 签名** | 机器人开了签名校验但渠道没配 `secret` |
| `notify success=1` 但群里没消息 | **上一轮的事故形态**：`type=webhook` + 飞书，HTTP 200 + 业务码被通用档当成成功。查 `notify_channels.type`，按 Step 0.5 改成 `feishu` 后重跑 |
| 只有一条 `http_get` | 模型跳过了 geocoding 直接凭记忆写坐标——这也算没通过，报出来 |

---

## Step 4：看群（**唯一的终点判据**）

**判定 J5**：飞书群里新到一条消息，内容是**成都**的今日天气 + 穿搭建议。

请只回我这三样：群里收到了 / 没收到；消息里的城市是成都还是北京；消息大致什么时间到的。

> J4 绿而 J5 红，就是「审计说推了、群里没有」——按上面失败对照表的最后两行查。
> 作者侧只验到本机 webhook（`127.0.0.1:9099`）；**真实飞书群这一条已于 2026-10-23 由用户实跑通过**（见文末「用户实测记录」）。

---

## 收尾

```bash
# 还原成北京（可选）
cp /tmp/weather-AGENT.md.bak "$WS/.oryxos/agents/weather-daily/AGENT.md" && rm -f /tmp/weather-AGENT.md.bak

# 停掉 serve（核 cmdline 防误杀）
pid=$(cat /tmp/oryxos-l31-city-serve.pid); tr '\0' ' ' < /proc/$pid/cmdline | grep -q 'oryxos.jar.*serve' && kill "$pid"
```

改回后同样会触发一次重新登记 + 重置，日志里应再出现一行 J2。
工作区 `/tmp/oryxos-l31-city` 想留着就留着（下次可从 Step 0.5 起），不想留 `rm -rf` 即可。

---

## 坑表

| # | 现象 | 真因 / 处理 |
|---|------|-------------|
| 1 | **`notify success=1` 但飞书群里没消息** | 渠道 `type` 存成了 `webhook`。飞书群机器人用通用档报文回 `HTTP 200` + 业务码 `19002`，而通用档只判 HTTP 状态码 → 假成功。`notify-channel remove team` 后 `--type feishu` 重加（Step 0.5） |
| 2 | `notify` 报「引用了未设置的环境变量」 | `serve` 启动时没带着 `TEAM_WEBHOOK_URL`。占位符是**读时**解析、但环境变量是**启动时**继承的——先 export 再起 serve（Step 0.4） |
| 3 | 改了正文但日志完全没反应 | 改的是 `examples/agents/weather-daily/`（仓库里那份）而不是 `$WS/.oryxos/agents/weather-daily/`。`cp -a` 是复制，两份互不影响；`git status` 也判不出来（`examples/` 未入库只会打 `??`） |
| 4 | `sed` 报 `unknown option to 's'` | 分隔符用了 `/` 而路径里有斜杠。本文用 `|`，照抄即可 |
| 5 | `grep -c 成都` 得到 2 | 你把城市名写进了第 2 步的 URL；第 2 步只能是 `<lat>` / `<lon>` 占位 |
| 6 | 群里仍是北京天气，但 J3 的 URL 是对的 | 看的是旧消息；确认群里最新一条的时间戳 |
| 7 | `curl .../run` 返回 404 | 任务已不在已注册列表（`AGENT.md` 里 `schedules` 被删掉或改坏）。先 `GET /api/v1/schedules` 看 `weather-daily#1` 在不在 |
| 8 | 查库时 `sqlite3` 命令不存在 | 本机没有 sqlite3 CLI，用 `python3` 内置的 `sqlite3` 模块（Step 3 已这么写） |
| 9 | `curl localhost:8080` 连不上 | 临时工作区的 `serve` 在 **8099**；8080 是另一套环境（`manual-test-guide.md` 那份） |
| 10 | 群里没消息、错误码 `19024` / `19021` | 群机器人的「自定义关键词 / 签名」安全设置。改关键词，或机器人改签名后 `--config secret=<你的密钥>` 重加渠道 |
| 11 | `add` 报「已存在」 | CLI 没有 update，先 `remove team` 再按正确 `--type` 重加 |
| 12 | 环境变量改了值但推送仍失败 | 值和 `type` 是两件事：值在 serve 进程环境里（改值**要重启 serve**），`type` 在库里（改完**不用**重启） |

---

## 作者侧实测记录（2026-10-23，真 DeepSeek + 本机 webhook）

> **这是上一轮（对端=本机 `127.0.0.1:9099` 通用 webhook）的证据，不是本向导当前形态的证据。**

| # | 结果 | 证据 |
|---|------|------|
| 基线（改之前） | — | `lastStatus=success`；`geocoding?name=北京` → `forecast?latitude=39.9075&longitude=116.39723`；`notify success=1`，内容写北京 |
| J1 | **通过** | `成都=1`、`北京=0` |
| J2 | **通过** | 日志 `AGENT.md 已变更，已重新登记并重置调度会话：weather-daily` + `已重置调度会话历史：weather-daily`；cron 仍 `0 0 8 * * *`、`enabled=True` |
| J3 | **通过** | `tool_invocations`：`http_get …/v1/search?name=成都&count=1&language=zh&format=json` success=1；`http_get …/v1/forecast?latitude=30.66667&longitude=104.06667&current=…` success=1；`notify` success=1，无 `error_message` |
| J4/J5 | **通过（本机 webhook）** | hook 最新一条 `body`：`{"channel":"team","content":"【成都今日天气】\n• 气温：16.1 ℃ …【穿搭建议】…"}` |

~~**剩余人工项**：真实飞书群里的 J4 / J5（作者侧只验到 `127.0.0.1:9099`）。~~
**2026-10-23 已由用户实跑通过**，见下节「用户实测记录」。作者侧的上述证据仍只对应本机 webhook 形态，保留作对照。

---

## 事故留痕（2026-10-23，用户实跑命中）

| 项 | 内容 |
|---|---|
| 现象 | 走到 Step 3，J1 / J2 / J3 全对，**飞书群没收到消息**；`lastStatus=success`、`runCount=1`；三条 `tool_invocations` 全 `success=1`，`notify` 的 result 是 `已推送` |
| 根因 | 渠道 `team` 的 `type` 是 **`webhook`**，地址却是 `https://open.feishu.cn/...`。通用档发 `{"content":"..."}`，飞书回 `HTTP 200` + 业务码 `19002`（msg_type need），通用档只判 HTTP 状态码 → **假成功** |
| 来源 | 作者侧上一轮给的重建命令沿用了**本机 hook** 档的 `--type webhook`，用户只替换了地址、没换类型——向导当时也没写「真实群必须换 `--type`」 |
| 产品行为 | **没有缺陷**：路由器按 `type` 显式分派、未知类型报错不发请求；缺的是**配置选错**，而这类错对通用档无法从 HTTP 层察觉（设计如此，已记录为已知边界） |
| 修复 | `notify-channel remove team` → `add team --type feishu --url-env TEAM_WEBHOOK_URL`（Step 0.5），无需重启 serve |
| 向导改动 | 本文本次全面重写（Step 0 / 0.5 / J4+J5 拆分 / 事故对照）；同类写法也在 `manual-test-guide.md` 第 6 行与坑表 #16 |

**教训（与第 24 / 25 / 31 节向导那几次同类）**：环境准备「改了什么」必须逐项写进步骤序列——换地址 ≠ 只换地址。

---

## 用户实测记录（2026-10-23，真 DeepSeek + **真实飞书群**）

| 项 | 结论 |
|---|---|
| 判据 J1~J5 | **整体通过**（用户原话「实测已经通过」） |
| 留痕口径 | 用户只给整体结论、未逐步说明各步输出，故按第 19 / 22 / 25 / 26 / 28 节口径**只记整体结论、不写步骤级细节**，不代填 J1~J5 的具体数值 |
| 覆盖面 | 只覆盖 `weather-daily` 换城市到成都这一条，且是**手动 `POST /run` 触发**；`daily-tech-digest` / `github-daily` 未在真实群里验过 |

---

## 要我回填的内容

跑完告诉我下面这几行就够了，我会据此更新 `acceptance-report.md`：

```text
J1 J2 J3 J4 J5 =
Step 3 里 forecast URL 实际是：
Step 3 里 notify 的 success 与 error_message：
群消息里的城市与大致时间：
```
