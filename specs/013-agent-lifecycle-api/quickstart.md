# Quickstart: 动态管理 Agent 的人工验收向导

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md) | **Date**: 2026-10-21

> 自动判卷由 `mvn clean verify` 承担（harness 五个测试类）。本向导只列**机器判不了**的人工项。全部命令在仓库根执行；临时工作区一律放 `/tmp`，收尾 `rm -rf`（本仓惯例）。

---

## Step 0：构建与准备

```bash
./mvnw clean verify
JAR="$PWD/oryxos-boot/target/oryxos.jar"
ls -l "$JAR"                     # 必须有产物
WORK=$(mktemp -d /tmp/oryxos-l30-XXXX)
cd "$WORK"
java -jar "$JAR" init
ls -d .oryxos/agents .oryxos/archive   # archive/ 本节新增
```

预期：`verify` 全绿；`init` 输出含「数据库表结构」；`archive/` 存在。

> ⚠️ 换终端会丢 `$JAR` / `$WORK`；看到 `Unable to access jarfile` 后面跟空字符串，回 Step 0 重新 export，**别重新构建**。

---

## Step 1：API 建 Agent 即上线（mock 档，不花钱）

启动服务（另开终端也行，注意 `cd "$WORK"`）：

```bash
cd "$WORK"
java -jar "$JAR" serve --port 18080
```

另开一个终端：

```bash
BASE=http://localhost:18080/api/v1
curl -s -X POST "$BASE/agents" -H 'Content-Type: application/json' \
  -d '{"name":"weather-demo","description":"每天早上九点查北京天气并推送"}' | python3 -m json.tool

curl -s "$BASE/agents" | python3 -m json.tool
ls -R .oryxos/agents/weather-demo
```

预期：

- POST 返回 `code:200`、`data.name=weather-demo`；
- **不重启**，`GET /agents` 里立刻有它；
- 目录里有 `AGENT.md` / `REFERENCE.md` / `scripts/` / `skills/`；
- `AGENT.md` 的 frontmatter provider 是 `deepseek`（作者 provider 缺省取 `oryxos.providers` 第一个）；`tools: []`。

重名再建一次 → 400 `Agent 已存在`；名称写 `../evil` → 400 名称非法。

---

## Step 2：更新成「会自己跑」的 Agent

`POST /agents` 的模板不含 `schedules`，用 `PUT` 覆写完整 `AGENT.md`（mock 厂商，把 cron 设为每分钟）：

```bash
cd "$WORK"
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
  -d "$(python3 -c 'import json,sys; print(json.dumps({"agentMarkdown": open("/tmp/weather-agent.md").read()}))')" \
  | python3 -m json.tool

curl -s "$BASE/schedules" | python3 -m json.tool
```

预期：

- PUT 返回的 `data.providerName=mock`、`schedules` 一条；
- `GET /schedules` 出现 `weather-demo#1`，`nextRunAt` 在下一分钟；
- 等 1~2 分钟，`GET /schedules` 的 `runCount` 增长、`lastStatus=success`；
- `GET /sessions?status=active` 里有 `scheduler:...:weather-demo` 的会话；
- 该 Agent 目录下出现 `MEMORY.md`（Step 5 再看内容）。

> 旧句柄必须先注销：连续两次 PUT 改 cron，`/schedules` 里不应出现两条同名任务，`runCount` 也不应双倍增长。

---

## Step 3：丢目录也上线（免重启）

```bash
cd "$WORK"
cp -a "$OLDPWD/examples/agents/daily-reconcile" .oryxos/agents/   # 路径按你的仓库位置调整
sleep 5
curl -s "$BASE/agents" | python3 -m json.tool
curl -s "$BASE/agents/daily-reconcile" | python3 -m json.tool
```

预期：**不重启 `serve`**，5 秒内 `daily-reconcile` 出现在列表里；`/schedules` 里出现它的任务（示例 `AGENT.md` 自带 `schedules`）。

> `cp -a` 会先建目录、后落文件，watcher 对「目录已出现但 `AGENT.md` 还没写完」的首次注册失败会做有界重试（10 次 × 300ms，**最多耗 3 秒**）；5 秒后仍不出现，看日志里是否有重试耗尽的 WARN（那才是真失败）。**别在 3 秒整点判「没出现」**——那正好卡在重试上限上。

再删目录验证注销：

```bash
rm -rf .oryxos/agents/daily-reconcile
sleep 3
curl -s "$BASE/agents" | python3 -m json.tool     # 不再包含 daily-reconcile
```

---

## Step 4：工作区文件浏览器

```bash
curl -s "$BASE/workspace/tree" | python3 -m json.tool
curl -s "$BASE/workspace/file?path=agents/weather-demo/AGENT.md" | python3 -m json.tool

# 关键回归：目录穿越必须 400
curl -s -o /tmp/out.json -w '%{http_code}\n' \
  "$BASE/workspace/file?path=../../etc/passwd"
cat /tmp/out.json | python3 -m json.tool
```

预期：tree 根只有 `agents` / `archive`；读 `AGENT.md` 返回完整文本；穿越请求 **400** 且响应里不含 `/etc/passwd` 的内容。

页面编辑：

```bash
curl -s -X POST "$BASE/workspace/file" -H 'Content-Type: application/json' \
  -d '{"path":"agents/weather-demo/REFERENCE.md","content":"# 参考资料\n\n手动编辑测试\n"}'
curl -s "$BASE/workspace/file?path=agents/weather-demo/REFERENCE.md" | python3 -m json.tool
```

写入 `agents/weather-demo/AGENT.md` 时走的是 update 路径（校验 + 重注册）；故意写坏 frontmatter → 400，且文件**保持不变**。

---

## Step 5：固定管理台会话与专属记忆

```bash
curl -s "$BASE/agents/weather-demo/session" | python3 -m json.tool

curl -s -X POST "$BASE/agents/weather-demo/session/messages" \
  -H 'Content-Type: application/json' \
  -d '{"content":"你好，记一下：今天北京晴。"}' | python3 -m json.tool

curl -s "$BASE/agents/weather-demo/session" | python3 -m json.tool   # 消息累积在同一条
curl -s "$BASE/agents/weather-demo/memory" | python3 -m json.tool
cat .oryxos/agents/weather-demo/MEMORY.md
```

预期：

- 两次 `GET session` 的 `sessionId` 恒为 `admin:console:weather-demo`；
- 发消息后 `messages` 条数增长、`reply` 非空；
- 记忆端点与 `MEMORY.md` 文件内容一致（mock 模型每轮会调 `save_memory`）；
- **另一个 Agent 的 `MEMORY.md` 是独立文件**：再建一个 Agent 发一轮消息，两份内容互不串。

> 全局 `GET /api/v1/memory` 已移除：请求它应得 404。

---

## Step 6：一句话生成（真模型，花钱）

只在配了 `DEEPSEEK_API_KEY` 时做。在 `application.yaml` 或启动参数里设：

```yaml
oryxos:
  author:
    provider: deepseek
    model: deepseek-chat
```

```bash
curl -s -X POST "$BASE/agents/weather-demo/generate-files" \
  -H 'Content-Type: application/json' \
  -d '{"description":"每天早上八点查上海天气，把穿衣建议用 notify 推到团队群"}' \
  | python3 -m json.tool
```

预期：

- 返回 `{"AGENT.md": "..."}`，frontmatter 可解析、provider 仍是 `deepseek/deepseek-chat`（沿用现有 Agent）；
- **磁盘上的 `AGENT.md` 没变**（不落盘）；
- `GET /agents/weather-demo` 的 `schedules` 没变（不注册）；
- 查库有一次新的 `llm_calls` 行：`session_id` 以 `agent-author:` 开头。

把 `oryxos.author.model` 留空重启后再调 → **503**，消息「未配置作者模型」，且**没有**发出任何模型请求（查 `llm_calls` 无新增）。

---

## Step 7：删除可追溯

```bash
curl -s -X DELETE "$BASE/agents/weather-demo" | python3 -m json.tool
curl -s "$BASE/agents" | python3 -m json.tool
ls -R .oryxos/archive
curl -s "$BASE/schedules" | python3 -m json.tool    # weather-demo#1 不应再触发（行可能仍在，v1 无 retired 字段）
```

预期：

- DELETE 200；
- 列表里没有 `weather-demo`，`GET /agents/weather-demo` → 404；
- 目录在 `.oryxos/archive/weather-demo/`（不是被物理删除）；
- 历史 `llm_calls` / `tool_invocations` 仍在库里。

---

## Step 8：管理台全流程

浏览器打开 `http://localhost:18080/admin/`，导航应有 **6 项**（概览 / 会话 / Agent / 工具 / 定时任务 / 运行状态），**没有「长期记忆」**。

1. Agent 页 → 「新建 Agent」→ 只填 name + description → 列表出现；
2. 点详情 → 五个页签：基本信息 / 生成 / 文件 / 会话 / 记忆；
3. 「生成」页填描述 → 用大模型生成 → 每个文件可编辑 → 保存并生效；
4. 「文件」页左侧树、右侧可编辑并保存；保存 `AGENT.md` 后列表里的 provider/tools 跟着变；
5. 「会话」页发一条消息，气泡显示，刷新后仍在；
6. 「记忆」页只读展示该 Agent 的 `MEMORY.md`；
7. 回列表删除 → 目录进 `archive/`。

---

## 收尾

```bash
# 停 serve（Ctrl+C 或 kill 对应进程）
cd /
rm -rf "$WORK"
```

---

## 已知边界（不是缺陷）

- `WatchService` 只监听 `agents/` 这一层；改 `AGENT.md` 正文即时生效（每轮重读），但改 `schedules` / `tools` 需走 `PUT` 或整目录替换。
- `POST /workspace/file` 的根是 `.oryxos/`，理论上可读写库文件；本节按课件口径不做更细白名单，扩展阶段随鉴权收。
- SQLite / Mem0 记忆后端仍是全局，per-agent 只对 Markdown 档生效。
- 详情页不占独立 URL，刷新回到 Agent 列表。
- 删除 Agent 后 `scheduled_tasks` 旧行保留（第 28 节 v1 无 `retired` 字段）。
