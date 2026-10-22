# 第 30 节 人工验收向导：动态管理 Agent

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md) | **Tasks**: [tasks.md](./tasks.md) | **Quickstart**: [quickstart.md](./quickstart.md)
**日期**: 2026-10-21 | **体例**: 照第 19 / 22 / 24 / 25 / 26 / 28 / 29 节那几份

> **自动判卷已经做完**：`mvn clean verify` 723 条全绿，harness 六个测试类覆盖了生命周期编排、监听、防穿越、端点语义与生成。本向导只做两件事：① 把**机器判不了**的两项（真模型一句话生成、浏览器目视走查）跑掉；② 用 mock 档把主干端到端再走一遍，确认「我看到的」和「测试断言的」是同一件事。
>
> **Step 0~5、7 用 mock 档，不花钱、不需要 key。Step 6 与 Step 8.3 是真模型项，要 `DEEPSEEK_API_KEY`、会花钱。**

---

## 前置事实（开跑前先读）

| # | 事实 | 为什么重要 |
|---|---|---|
| 1 | 当前分支 `class-30`，所有命令的起点是**仓库根** `/home/wngbob/oryxos-wngbob` | 下面所有路径都按这个根写 |
| 2 | `JAVA_HOME` 在 WSL 上是 `/usr/lib/jvm/java-21-openjdk-amd64` | 本仓不把 JDK 放系统 PATH |
| 3 | **构建不能加 `-Dfrontend.skip=true`** | 加了 jar 里就没有 `static/admin/`，Step 8 的浏览器页直接白屏/404（`oryxos-web/pom.xml:20` 写明） |
| 4 | 管理台在 **嵌套 jar** `BOOT-INF/lib/oryxos-web-*.jar` 里，不是 `BOOT-INF/classes` | 直接 `unzip -l \| grep static/admin` 恒为 0，会误判成「没打进去」（第 25 节向导踩过同款） |
| 5 | 工作区**必须先 `oryxos init`**；`serve` 自己不建表 | 不 init 时 `/api/v1/info` 会 500 `no such table: sessions`（第 26 节） |
| 6 | SQLite 路径是**相对 cwd** 的 `.oryxos/oryxos.db` | 必须在 `$WORK` 里起 `serve`，否则连到别的库、查不到你刚写的数据 |
| 7 | `serve` **不需要** `DEEPSEEK_API_KEY` 也能起（实测），mock 档全程不碰 key | 只有 Step 6 / 8.3 需要真 key |
| 8 | 真模型项需要**两个**环境变量：`DEEPSEEK_API_KEY` 与 `ORYXOS_AUTHOR_MODEL=deepseek-chat` | `oryxos.author.provider` 留空会自动取 `oryxos.providers` 第一个（= deepseek）；`model` 留空 → 503 |
| 9 | 定时任务 id 形如 `weather-demo#1`，**URL 里 `#` 必须写成 `%23`** | 直接写 `#1` 会被 curl/浏览器当成 URL 片段，请求实际打到 `/schedules/weather-demo` → 404 |
| 10 | `$JAR` / `$WORK` / `$BASE` 只在当前终端有效 | 换终端会得到 `Unable to access jarfile` 后面跟空字符串；回 Step 0 重 export，**别重新构建** |

---

## 本向导覆盖的人工项

| 课件验收项 | 本向导位置 | 性质 |
|---|---|---|
| 一句话生成（真模型） | **Step 6** | **正式人工项 ①**，花钱 |
| 管理台全流程走一遍 | **Step 8** | **正式人工项 ②**，其中 8.3 花钱 |
| 建 Agent 即上线 / 丢目录即上线 / 文件浏览器 / 删除可追溯 | Step 1~4、7 | 已由 mock 档作者侧跑过，人工复跑只为体感 |
| 固定会话 + 专属记忆隔离 | Step 5 | 同上 |

---

## Step 0：构建与准备（一个新终端）

```bash
cd /home/wngbob/oryxos-wngbob
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export REPO="$PWD"
export JAR="$REPO/oryxos-boot/target/oryxos.jar"
export WORK=/tmp/oryxos-l30
export BASE=http://localhost:18080/api/v1

# 0.1 停掉上一轮可能残留的 serve（没有输出 = 干净）
pgrep -af "[o]ryxos.jar serve" || echo "没有残留 serve"

# 0.2 完整构建（含管理台前端；不要把 -Dfrontend.skip=true 加进来）
./mvnw clean package -DskipTests -Ddependency-check.skip=true

# 0.3 自检①：jar 存在
ls -l "$JAR"

# 0.4 自检②：管理台确实在 jar 里（解嵌套 jar 再看）
unzip -p "$JAR" "BOOT-INF/lib/oryxos-web-*.jar" > /tmp/l30-web.jar
unzip -l /tmp/l30-web.jar | grep -c "static/admin/"
rm -f /tmp/l30-web.jar
# 预期 >= 6；若是 0，说明用了 frontend.skip 的 jar，回 0.2 重建

# 0.5 建临时工作区并初始化
rm -rf "$WORK"
mkdir -p "$WORK"
cd "$WORK"
java -jar "$JAR" init
ls -d .oryxos/agents .oryxos/archive
```

**判据**：0.4 计数 ≥ 6；`init` 输出含「数据库表结构」段；`.oryxos/agents` 与 `.oryxos/archive` 都存在。

> ⚠️ **从这里开始，所有命令都假设 cwd = `$WORK`**。换终端先跑（**`REPO` 不能漏**——Step 3 的 `cp -a "$REPO/examples/..."` 就靠它）：
>
> ```bash
> export REPO=/home/wngbob/oryxos-wngbob
> export JAR="$REPO/oryxos-boot/target/oryxos.jar"
> export WORK=/tmp/oryxos-l30
> export BASE=http://localhost:18080/api/v1
> cd "$WORK"
> ```
>
> 自查（三条都应打印非空）：`echo "REPO=$REPO"; ls -d "$REPO/examples/agents/daily-reconcile"; ls -l "$JAR"`

---

## Step 1：API 建 Agent，不重启即上线（mock）

**终端 A** 起服务（留着看日志）：

```bash
cd "$WORK"
java -jar "$JAR" serve --port 18080 > "$WORK/serve.log" 2>&1 &
echo $! > "$WORK/serve.pid"
sleep 1
until curl -sf "$BASE/health" >/dev/null; do sleep 1; done
echo "serve ready: $(cat "$WORK/serve.pid")"
```

**终端 B** 或同终端继续：

```bash
curl -s -X POST "$BASE/agents" -H 'Content-Type: application/json' \
  -d '{"name":"weather-demo","description":"每天早上九点查北京天气并推送"}' | python3 -m json.tool

curl -s "$BASE/agents" | python3 -m json.tool
ls -R .oryxos/agents/weather-demo
sed -n '1,8p' .oryxos/agents/weather-demo/AGENT.md
```

**判据**：

- POST 返回 `code:200`、`data.name=weather-demo`、**`data.providerName=deepseek` 且 `data.providerModel=deepseek`**——作者 `model` 留空时按实现取 provider 名（不是空串）；
- **不重启**，`GET /agents` 里立刻有它；
- 目录里有 `AGENT.md` / `REFERENCE.md` / `scripts/` / `skills/`；
- `AGENT.md` frontmatter 是 `tools: []`。

反例（都应 400，且**磁盘上一个目录都不该多出来**）：

```bash
curl -s -X POST "$BASE/agents" -H 'Content-Type: application/json' \
  -d '{"name":"weather-demo","description":"重名"}' | python3 -m json.tool          # Agent 已存在
curl -s -X POST "$BASE/agents" -H 'Content-Type: application/json' \
  -d '{"name":"../evil","description":"越界"}' | python3 -m json.tool               # 名称非法
ls .oryxos/agents
```

---

## Step 2：更新成「会自己跑」的 Agent（改 schedules，先注销旧句柄）

```bash
cat > /tmp/weather-agent.md <<'EOF'
---
description: 每天早上九点查北京天气并推送
provider:
  name: mock
  model: mock
tools: [save_memory]
schedules:
  - cron: "* * * * *"
    zone: Asia/Shanghai
    message: 查一下北京天气，把结果记到记忆里。
---

# weather-demo

## 任务

收到消息后，调用 save_memory 记一笔天气结论。
EOF

curl -s -X PUT "$BASE/agents/weather-demo" -H 'Content-Type: application/json' \
  -d "$(python3 -c 'import json; print(json.dumps({"agentMarkdown": open("/tmp/weather-agent.md").read()}))')" \
  | python3 -m json.tool

curl -s "$BASE/schedules" | python3 -m json.tool
```

**判据**：

- PUT 返回 `data.providerName=mock`、`data.schedules` 一条；
- `GET /schedules` 出现 `taskId=weather-demo#1`、`enabled=true`、`nextRunAt` 在下一分钟；
- `cron` 列显示的是**你写的 5 段形态**（`* * * * *`）——归一化成 6 段只在 `AgentScheduler` 内部传给 `CronTrigger`，不回写视图，别把「没变成 6 段」当失败。

### 2.1 手动触发一次（确定性，不等钟）

```bash
curl -s -X POST "$BASE/schedules/weather-demo%231/run" | python3 -m json.tool
```

**判据**：同步返回，`runCount=1`、`lastStatus=success`（mock 模型，秒级返回）。

### 2.2 等一次真 cron tick（证明「到点自己跑」）

```bash
sleep 70
curl -s "$BASE/schedules" | python3 -m json.tool
```

**判据**：`runCount` 从 1 变 2、`lastRunAt` 刚更新。

### 2.3 降噪：停用这条每分钟任务

```bash
curl -s -X PUT "$BASE/schedules/weather-demo%231" \
  -H 'Content-Type: application/json' -d '{"enabled":false}' | python3 -m json.tool
```

**判据**：`enabled=false`。**后面所有步骤都在这个状态下做**，避免 MINUTE tick 一直往记忆里写——第 29 节向导就是被「向导自己的时间尺度」坑过一次。

> 可选（多花 1 分钟）：再 `sleep 65` 后查一次，`runCount` 应**不再增长**——停用是到点跳过、不执行不记历史。

### 2.4 会话与专属记忆已落盘

```bash
curl -s "$BASE/sessions?status=active" \
  | python3 -c 'import json,sys; [print(s["sessionId"], "|", s["profile"]) for s in json.load(sys.stdin)["data"]]'

ls -l .oryxos/agents/weather-demo/
cat .oryxos/agents/weather-demo/MEMORY.md
```

**判据**：

- 列表里有 **`scheduler:scheduler:weather-demo`**（channel:user:profile 三段拼接，与 `AgentScheduler` 同源）；
- Agent 目录下出现 **`MEMORY.md`**，内容含 mock 脚本写入的那笔记忆；
- 全局 `.oryxos/memory/MEMORY.md` **不应**被这个 Agent 写（有 Agent 上下文时落 per-agent）。

---

## Step 3：丢一个目录也上线（免重启）；删目录即注销

```bash
cp -a "$REPO/examples/agents/daily-reconcile" .oryxos/agents/
sleep 5
curl -s "$BASE/agents" | python3 -m json.tool
curl -s "$BASE/agents/daily-reconcile" | python3 -m json.tool
curl -s "$BASE/schedules" | python3 -m json.tool
```

**判据**：

- **不重启 `serve`**，5 秒内列表里出现 `daily-reconcile`；
- `/schedules` 里多出 `daily-reconcile#1`（示例 `AGENT.md` 自带 cron `0 0 9 * * *`，明天 9 点才跑，测试期间不会响）；
- 示例里的 `skills/report-format` 在仓库里是**普通文本文件**（29 字节），`cp -a` 照原样复制——这不影响注册，本节不看渐进式披露（那是第 29 节的事）。

> 若 5 秒没出现：`cp -a` 是「先建目录、后落文件」，watcher 对首次注册失败做 **10 次 × 300ms** 的有界重试（最多 3 秒）。**别在 3 秒整点判「没出现」**；超过 5 秒仍没有，再看 `serve.log` 里有没有「重试耗尽」的 WARN——那才是真失败。

删目录验证注销：

```bash
rm -rf .oryxos/agents/daily-reconcile
sleep 3
curl -s "$BASE/agents" | python3 -m json.tool                        # 不再包含
curl -s -o /dev/null -w '%{http_code}\n' "$BASE/agents/daily-reconcile"   # 404
```

`/schedules` 里 **不再出现** `daily-reconcile#1`（2026-10-22 修复：列表与其余三个端点都只认「当前已注册」的任务）；`POST /schedules/daily-reconcile%231/run` 返回 **404**。库里的旧行仍保留（v1 无 `retired` 字段），只是不再对外可见——可以理解为「历史行留库、定义已不存在」。

---

## Step 4：工作区浏览与编辑（含目录穿越回归）

```bash
curl -s "$BASE/workspace/tree" | python3 -m json.tool
curl -s "$BASE/workspace/file?path=agents/weather-demo/AGENT.md" | python3 -m json.tool

# 关键回归：穿越必须 400，且响应里不含 /etc/passwd 内容
curl -s -o /tmp/l30-out.json -w '%{http_code}\n' "$BASE/workspace/file?path=../../etc/passwd"
cat /tmp/l30-out.json | python3 -m json.tool

# 写一个普通文件
curl -s -X POST "$BASE/workspace/file" -H 'Content-Type: application/json' \
  -d '{"path":"agents/weather-demo/REFERENCE.md","content":"# 参考资料\n\n手动编辑测试\n"}'
curl -s "$BASE/workspace/file?path=agents/weather-demo/REFERENCE.md" | python3 -m json.tool
```

**判据**：

- tree 根只有 `agents` / `archive` 两项；
- 穿越请求 **400**，响应体不含 `root:` 之类 `/etc/passwd` 的特征；
- REFERENCE.md 读回与写入一致。

### 4.1 写坏 `AGENT.md`：400 且文件不动

```bash
md5sum .oryxos/agents/weather-demo/AGENT.md
curl -s -X POST "$BASE/workspace/file" -H 'Content-Type: application/json' \
  -d '{"path":"agents/weather-demo/AGENT.md","content":"这不是合法的 frontmatter\n"}' | python3 -m json.tool
curl -s -o /dev/null -w '%{http_code}\n' "$BASE/workspace/file?path=agents/weather-demo/AGENT.md"
md5sum .oryxos/agents/weather-demo/AGENT.md
```

**判据**：写请求 400；**两次 md5 完全一致**（写 `AGENT.md` 走的是 update 路径：先校验、后落盘，校验失败一个字都不写）。

---

## Step 5：固定管理台会话 + 专属记忆隔离

```bash
curl -s "$BASE/agents/weather-demo/session" | python3 -m json.tool

curl -s -X POST "$BASE/agents/weather-demo/session/messages" \
  -H 'Content-Type: application/json' \
  -d '{"content":"你好，记一下：今天北京晴。"}' | python3 -m json.tool

curl -s "$BASE/agents/weather-demo/session" | python3 -m json.tool
curl -s "$BASE/agents/weather-demo/memory" | python3 -m json.tool
cat .oryxos/agents/weather-demo/MEMORY.md
```

**判据**：

- 两次 `GET session` 的 `sessionId` **恒为 `admin:console:weather-demo`**；
- 发消息后 `messages` 条数增长、发消息响应里 `reply` 非空；
- 记忆端点返回的 `core` / `archival` 与 `MEMORY.md` 文件内容一致。

### 5.1 另一个 Agent 的记忆是独立文件

```bash
curl -s -X POST "$BASE/agents" -H 'Content-Type: application/json' \
  -d '{"name":"other-demo","description":"陪跑用"}' >/dev/null

# 模板默认是 deepseek；发消息前先切到 mock（否则会去连真模型）
cat > /tmp/other-agent.md <<'EOF'
---
description: 陪跑用
provider:
  name: mock
  model: mock
tools: [save_memory]
---

# other-demo

收到消息后，调用 save_memory 记一笔。
EOF
curl -s -X PUT "$BASE/agents/other-demo" -H 'Content-Type: application/json' \
  -d "$(python3 -c 'import json; print(json.dumps({"agentMarkdown": open("/tmp/other-agent.md").read()}))')" >/dev/null

curl -s -X POST "$BASE/agents/other-demo/session/messages" \
  -H 'Content-Type: application/json' -d '{"content":"记一下：我是另一个 Agent。"}' >/dev/null

cat .oryxos/agents/weather-demo/MEMORY.md
cat .oryxos/agents/other-demo/MEMORY.md
```

**判据**：两份文件**互不包含对方的内容**；`ls .oryxos/agents/*/MEMORY.md` 恰好两份。

### 5.2 全局记忆端点已移除

```bash
curl -s -o /dev/null -w '%{http_code}\n' "$BASE/memory"
```

**判据**：**404**（这是已裁决的破坏性变更，不是缺陷）。

---

## Step 6：一句话生成（真模型，花钱）——正式人工项 ①

### 6.1 重启 serve，带上作者模型配置

```bash
# 先停掉 mock 档的 serve
kill "$(cat "$WORK/serve.pid")" 2>/dev/null; sleep 2
pgrep -af "[o]ryxos.jar serve" || echo "已停止"

cd "$WORK"
# key 用 read -s 输入，不进 shell 历史、不回显
read -rsp 'DEEPSEEK_API_KEY: ' DEEPSEEK_API_KEY; echo
export DEEPSEEK_API_KEY
export ORYXOS_AUTHOR_MODEL=deepseek-chat

java -jar "$JAR" serve --port 18080 > "$WORK/serve.log" 2>&1 &
echo $! > "$WORK/serve.pid"
until curl -sf "$BASE/health" >/dev/null; do sleep 1; done
echo ready
```

### 6.2 正向：生成草稿，不落盘、不注册

```bash
md5sum .oryxos/agents/weather-demo/AGENT.md
curl -s "$BASE/agents/weather-demo" | python3 -m json.tool > /tmp/l30-before.json

curl -s -X POST "$BASE/agents/weather-demo/generate-files" \
  -H 'Content-Type: application/json' \
  -d '{"description":"每天早上八点查上海天气，把穿衣建议用 notify 推到团队群"}' \
  | python3 -c 'import json,sys; d=json.load(sys.stdin); print("code:", d["code"]); print(d["data"]["AGENT.md"])'

md5sum .oryxos/agents/weather-demo/AGENT.md
curl -s "$BASE/agents/weather-demo" | python3 -m json.tool > /tmp/l30-after.json
diff /tmp/l30-before.json /tmp/l30-after.json && echo "Agent 视图未变（不落盘、不注册）"
```

**判据**：

- 返回 `code:200`，`data` 的键恰好是 `AGENT.md`，内容以 `---` 开头（**没有 ``` 代码围栏**——模型多吐的围栏会被剥掉）；
- 草稿 frontmatter 可解析，**provider 沿用现有 Agent**——本向导里 `weather-demo` 在第 2 步已切到 `mock`，所以草稿里也是 `name: mock`（`alignProvider` 有意为之；若你的 Agent 是 deepseek，草稿就是 deepseek）；
- **两次 md5 一致**、`GET /agents/weather-demo` 前后 diff 为空。

### 6.3 审计：确实发生了一次 LLM 调用

```bash
python3 - "$WORK/.oryxos/oryxos.db" <<'PY'
import sqlite3, sys
con = sqlite3.connect(f"file:{sys.argv[1]}?mode=ro", uri=True)
rows = con.execute(
    "SELECT id, session_id, provider, model, success "
    "FROM llm_calls WHERE session_id LIKE 'agent-author:%' ORDER BY id DESC LIMIT 5"
).fetchall()
print("agent-author 调用数:", len(rows))
for r in rows:
    print(r)
PY
```

**判据**：至少 1 行，`session_id` 以 **`agent-author:`** 开头，`provider=deepseek`、`model=deepseek-chat`、`success=1`。

### 6.4 反向：`ORYXOS_AUTHOR_MODEL` 留空 → 503 且**零请求**

```bash
kill "$(cat "$WORK/serve.pid")"; sleep 2
cd "$WORK"
ORYXOS_AUTHOR_MODEL= java -jar "$JAR" serve --port 18080 > "$WORK/serve.log" 2>&1 &
echo $! > "$WORK/serve.pid"
until curl -sf "$BASE/health" >/dev/null; do sleep 1; done

# 记下当前 agent-author 调用数
BEFORE=$(curl -s -X POST "$BASE/agents/weather-demo/generate-files" \
  -H 'Content-Type: application/json' -d '{"description":"x"}' -o /tmp/l30-503.json -w '%{http_code}')
echo "HTTP: $BEFORE"; cat /tmp/l30-503.json | python3 -m json.tool
```

**判据**：HTTP **503**，消息含「未配置作者模型」；**再跑一遍 6.3 的查询，`agent-author` 行数与 6.3 相同**（一次模型请求都没发）。

### 6.5（可选）把草稿保存并生效

先把 serve 恢复成带作者模型的档（重跑 6.1 的启动块），然后：

```bash
# 用 6.2 的输出构造请求；也可在 Step 8 的「生成」页签点「保存并生效」
curl -s -X POST "$BASE/agents/weather-demo/files" -H 'Content-Type: application/json' \
  -d "$(python3 - <<'PY'
import json
draft = """---
description: 每天早上八点查上海天气
provider:
  name: mock
  model: mock
tools: [save_memory]
---

# weather-demo

每天八点查上海天气，把穿衣建议推送到团队群。
"""
print(json.dumps({"files": {"AGENT.md": draft}}))
PY
)" | python3 -m json.tool
```

**判据**：返回更新后的 `AgentView`；磁盘 `AGENT.md` 已变；改 `schedules` 时旧句柄先注销（`/schedules` 不应出现两条同名任务）。

---

## Step 7：删除可追溯（注销定时 → 移出索引 → 归档）

```bash
curl -s -X DELETE "$BASE/agents/weather-demo" | python3 -m json.tool
curl -s "$BASE/agents" | python3 -m json.tool
curl -s -o /dev/null -w '%{http_code}\n' "$BASE/agents/weather-demo"
ls -R .oryxos/archive
```

**判据**：

- DELETE 200；列表里没有 `weather-demo`；详情 **404**；
- 目录在 `.oryxos/archive/weather-demo/`（**不是被物理删除**）；
- 历史 `llm_calls` / `tool_invocations` 仍在库里：

```bash
python3 - "$WORK/.oryxos/oryxos.db" <<'PY'
import sqlite3, sys
con = sqlite3.connect(f"file:{sys.argv[1]}?mode=ro", uri=True)
print("llm_calls:", con.execute("SELECT count(*) FROM llm_calls").fetchone()[0])
print("tool_invocations:", con.execute("SELECT count(*) FROM tool_invocations").fetchone()[0])
PY
```

---

## Step 8：管理台全流程——正式人工项 ②

> 8.3「生成」页签需要真模型。先把 serve 停在**带作者模型**的档（重跑 6.1 的启动块）。
> 只验只读页面的话，mock 档就够——8.3 标「可选（花钱）」。

浏览器打开 **<http://localhost:18080/admin/>**。

### 8.1 全局外观与导航

- 左侧导航**恰好 6 项**：概览 / 会话 / Agent / 工具 / 定时任务 / 运行状态；**没有「长期记忆」**（第 30 节已裁决移除）；
- 落地页是「概览」：四张计数卡（Agent 可用/共、活跃会话、已注册工具数与来源数、运行时长与版本）+ 健康卡（整体 / DB / Provider 配置就绪）+ 两栏（Agent 清单、工具来源构成）；
- 「已运行」卡的脚注是 **`v0.1.0-SNAPSHOT`**（读构建期注入的 `oryxos-version.properties`，与 `oryxos --version` 同源）；**MUST NOT 出现 `vunknown`**（2026-10-22 修复：原实现只读 JAR manifest，而本仓没开 `addDefaultImplementationEntries`）；
- 打开 `/admin/agents` 直接刷新，**不应 404**（SPA 回落）。

### 8.2 Agent 列表与「新建 Agent」

1. 进「Agent」页，列表应有 `other-demo`（Step 5 建的）；`weather-demo` 已在 Step 7 归档，**不应出现**；
2. 点「新建 Agent」，**只填 name + description**（如 `ui-demo` / 「页面创建的演示 Agent」），提交；
3. 列表**立刻**出现 `ui-demo`，磁盘上 `.oryxos/agents/ui-demo/` 四件套齐全；
4. 新建的 Agent 模板 provider 是 `deepseek`（作者 model 留空时取 provider 名），若要用 mock 发消息，用 API `PUT` 切成 mock（见 Step 5.1 的做法）。

### 8.3 Agent 详情：五个页签

点 `ui-demo` 一行进入详情，页签**恰好五个**：**基本信息 / 生成 / 文件 / 会话 / 记忆**。

| 页签 | 操作 | 预期 |
|---|---|---|
| 基本信息 | 看字段 | name / description / provider / tools / schedules 与 `GET /agents/ui-demo` 一致 |
| 生成 | 填一句描述 → 点生成 | 返回草稿、**每个文件可编辑**；此时磁盘未变（页面提示「草稿已生成，还没有落盘」）；点「保存并生效」后才落盘、列表字段跟着变。**需作者模型；没配时该操作应报 503 而不是白屏**（可选，花钱） |
| 文件 | 左侧文件树 → 点 `AGENT.md` → 右侧编辑 → 保存 | 保存成功；回列表看 provider/tools 已变；故意写坏 frontmatter → 报错且文件不变 |
| 会话 | 输入框发一条消息 | 气泡显示在页面；**刷新页面后仍在**（固定会话 `admin:console:ui-demo`）。Agent 需配 mock 或有效 key |
| 记忆 | 打开 | **只读**展示该 Agent 的 `MEMORY.md`；与 `.oryxos/agents/ui-demo/MEMORY.md` 一致；页面上**没有**「编辑 / 删除」入口 |

### 8.4 删除可追溯（页面侧）

1. 回 Agent 列表，删 `ui-demo`；
2. 列表里消失；`.oryxos/archive/ui-demo/` 出现。

### 8.5 其它页签抽查

- 「定时任务」页：能看到 `weather-demo#1`（Step 2.3 已停用），**看不到 `daily-reconcile#1`**（Step 3 删目录后已从列表消失——2026-10-22 修复）；有「立即执行 / 启用停用」按钮且可点；
- 「工具」页：来源构成里有 `builtin (15)`（第 22 节起内置 15 个）；
- 「运行状态」页：健康检查三项为 UP；
- 「概览」页刷新一次，Agent 计数与列表一致。

---

## 收尾

```bash
cd /
kill "$(cat /tmp/oryxos-l30/serve.pid)" 2>/dev/null
sleep 2
pgrep -af "[o]ryxos.jar serve" || echo "serve 已停止"
rm -rf /tmp/oryxos-l30
rm -f /tmp/weather-agent.md /tmp/other-agent.md /tmp/l30-*.json
```

> ⚠️ **先 `cd /` 再删工作区**。在已删除的目录里跑着的进程会让 shell 报 `getcwd` 错误，也可能继续占着 8080/18080（第 27 节踩过）。

---

## 坑表（症状 → 原因 → 处置）

| # | 症状 | 原因 | 处置 |
|---|---|---|---|
| 1 | `Unable to access jarfile` 后面跟**空字符串** | 换终端丢了 `$JAR` | 回 Step 0 重 export，**别重新构建** |
| 2 | `/admin/` 404 或白屏 | jar 是用 `-Dfrontend.skip=true` 打的 | 不带该参数 `clean package`，并按 0.4 解嵌套 jar 自检 |
| 3 | `0.4` 计数恒为 0，但明明构建了前端 | 管理台在**嵌套 jar** 里，散装 class 里没有 | 按 0.4 的命令先 `unzip -p` 出 `oryxos-web-*.jar` 再看 |
| 4 | `/api/v1/info` 500 `no such table: sessions` | 没跑 `oryxos init` | Step 0.5 |
| 5 | 请求 `/schedules/weather-demo#1` 得 404 | `#` 被当 URL 片段 | 写成 `%23` |
| 6 | `mvn package` 不带 `clean`，改动不生效 | 依赖变了不算 `maven-jar-plugin` 的输入 | 一律 `clean package` |
| 7 | `generate-files` 返回 503「未配置作者模型」 | `ORYXOS_AUTHOR_MODEL` 为空 | **这是 6.4 的预期**；要正向生成就先 export 再重启 |
| 8 | 草稿 provider 是 `mock` 而不是 deepseek | `alignProvider` 沿用现有 Agent 的 provider | 预期行为，不是缺陷 |
| 9 | 记忆内容对不上 | 看错文件 | 有 Agent 上下文时落 `.oryxos/agents/<name>/MEMORY.md`；`.oryxos/memory/MEMORY.md` 只是无上下文时的兜底 |
| 10 | `MEMORY.md` 每分钟多一行 | 每钟任务一直在跑 | Step 2.3 已停用；判据用关键词 `grep`，别数行数 |
| 11 | 删了 Agent，`/schedules` 里还有行 | 用的是 2026-10-22 修复前的旧 jar | 重新 `clean package` 并重启 `serve`；当前行为是列表只返回当前已注册任务 |
| 12 | `cp -a` 后 3 秒内没出现 | 重试上限 10×300ms | 等 5 秒再判；超时看 `serve.log` 的 WARN |
| 13 | `GET /api/v1/memory` 404 | 全局记忆端点已删除 | 预期；用 `/agents/{name}/memory` |
| 14 | sqlite 查询查不到刚写的数据 | `serve` 不在 `$WORK` 里起的，连了别的库 | 起 serve 前 `cd "$WORK"` |
| 15 | `pkill -f "oryxos.jar serve"` 把自己也杀了 | 模式串出现在调用 shell 的 argv 里 | 用 `pgrep -f "[o]ryxos.jar serve" \| xargs -r kill`（方括号防自匹配） |
| 16 | 浏览器「会话」页发消息报错 | 新 Agent 的 provider 是 deepseek，但没有有效 key | 用 API `PUT` 切成 mock（Step 5.1），或配真 key（花钱） |

---

## 已知边界（不是缺陷）

- `WatchService` 只监听 `agents/` 这一层；改 `AGENT.md` 正文即时生效（每轮重读），改 `schedules` / `tools` 需走 `PUT` 或整目录替换。
- `POST /workspace/file` 的根是 `.oryxos/`，理论上可读写库文件；本节按课件口径不做更细白名单，扩展阶段随鉴权收。
- SQLite / Mem0 记忆后端仍是全局；per-agent 只对 Markdown 档生效。
- 详情页不占独立 URL，刷新回到 Agent 列表（页面内路由是页面状态，不是浏览器路由）。
- 删除 Agent 后 `scheduled_tasks` 旧行**仍在库里**（v1 无 `retired` 字段），但自 2026-10-22 起 `GET /schedules` 只返回当前已注册任务——列表不显示，`run` / `executions` / `PUT` 一律 404；历史行不物理删除。重启 `serve` 时注册表按 `.oryxos/agents/` 重建，口径一致。
- 没有认证鉴权（内网假设，`docs/TechnicalSolution.md` 与本文都按此口径）。

---

## 实测记录

| 项 | 谁跑的 | 结果 |
|---|---|---|
| Step 0~5、7（mock 档，真 jar） | 作者侧（2026-10-21，`/tmp/oryxos-l30-pZKY`，已清理） | 全通过：建 Agent 不重启可见、重名/非法名 400、PUT 后手动触发与 cron tick 均执行、`cp -a` 6 秒内注册、`rm -rf` 后注销且 `runCount` 冻结、穿越 400、坏 frontmatter 400 且 md5 不变、固定会话与会话 ID 恒定、两个 Agent 记忆互不串、全局记忆 404、删除进 `archive/` 且审计留存、`/v3/api-docs` 27 operations |
| Step 6、8 | **用户**（2026-10-22，真 DeepSeek 模型 + 真浏览器） | **已跑，验证通过**。用户在 Step 8 实跑中命中两处产品缺陷（8.1 的 `vunknown`、8.5 的「删了 Agent 定时任务还在」），均已修复并复验（见 [acceptance-report.md](./acceptance-report.md) §五）；修复后 8.1 / 8.5 的判据已按新口径更新。留痕口径同前序各节：只记整体结论、不逐步罗列各步输出 |
| Step 2.2 的 cron tick | 作者侧跑过（第 28 节 SchedulerFlowIT 也钉过） | 本次向导里保留一次真 tick，作为体感 |
| 管理台静态资源在 jar 内 | 已实测：解 `BOOT-INF/lib/oryxos-web-*.jar` 后 `static/admin/` 命中 6 项 | 故 0.4 的判据必须解嵌套 jar |
