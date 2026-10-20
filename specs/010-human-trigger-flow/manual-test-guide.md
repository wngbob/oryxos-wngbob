# 人推全流程（第 27 节）人工验收向导（Step by Step）

**Feature**: `specs/010-human-trigger-flow` | **分支**: `class-27` | **Date**: 2026-10-20

**上游文档**：[acceptance-report.md](acceptance-report.md) §三·3 / §三·4 · [README](../../README.md)「不用任何模型凭证跑通全链路」

**适用对象**：第 27 节的最终形态（内置 `mock` Provider + 会话列表端点 + 管理台「会话」页）

**目的**：覆盖 harness 判不了的两件事——

1. **无 key 的端到端手工走查**：CLI 对话 → `save_memory` 写文件 → 审计落库 → REST 读回 → 管理台看到同一份数据。`HumanTriggerFlowIT` 要真 key + 出网，只能手动跑；本向导用 `mock` Provider 把同一条链路搬到**任何一台机器**上，不联网、不烧钱。
2. **浏览器里的管理台目视复核**（**与第 26 节 §四 第 6 项的遗留目视项合并**）：概览 / 会话 / Agent / 工具 / 定时任务 / 长期记忆 / 运行状态七页，判据是「页面上的数字与同端口 `/api/v1/*` 的返回一致」「会话页能看到 CLI 写出的会话并点开看历史」——`.vue` 源码只能判「只发 GET、没有写入口文字」，判不了渲染。

**不在本向导范围**（不是遗漏）：Lark 档 / Mem0 档的已知边界（第 19 / 22 节登记），以及三个验收 Demo（第 31 节）。

**预计耗时**：Step 1~9 约 10~15 分钟。**不需要任何 API key，不联网。**

---

## 实测记录

### 写向导时已实测（2026-10-20，jar = `oryxos-boot/target/oryxos.jar`，84,561,395 字节 / 14:11）

工作区 `/tmp/l27-demo`、`/tmp/l27-guide-check`（`oryxos init` 现造），Agent = 本向导 Step 2 那份 `mock-demo`。**凡是「不需要浏览器」的步骤都真跑过**（服务用 `--port 18090` / `18091` 避开本机可能占用的 8080，命令与下面完全同形）：

| 已实测 | 实测结果 |
| --- | --- |
| `oryxos init`（新目录） | 退出码 0；输出末尾「数据库表结构: 与 db/schema.sql 一致（无需补列）」+「下一步」两行 |
| `profile list` | `Agent（共 1 个，目录 .oryxos/agents/）:` / `mock-demo  有效  provider=mock/mock-model  无凭证全链路自测` |
| **无 key** `chat --profile mock-demo`（`env -u DEEPSEEK_API_KEY`） | 退出码 **0**；stdout 四行（见 Step 3）；答复**逐字**是「好的，我记住了。」；stderr 里`LLM 调用完成` **2 条**、`工具调用完成` **1 条** |
| `MEMORY.md` | 归档区出现 `- [2026-10-20] 记住：我喜欢喝美式咖啡` |
| 审计（python3 sqlite3） | `llm_calls (2, 2)`；`tool_invocations [('save_memory', 1)]`；`sessions [('cli:local:mock-demo', 'active')]` |
| 会话历史（直接读库） | 四条：`user '记住：我喜欢喝美式咖啡'` / `assistant ''`（tool call）/ `tool '已记住'` / `assistant '好的，我记住了。'` |
| **无 key** `serve` | `Tomcat started on port 18090 (http)`；`定时任务注册完成：共 0 条`；`/api/v1/health` 200，`providers {"deepseek":"UP","mock":"UP"}` |
| `GET /api/v1/sessions` | 1 条 `cli:local:mock-demo`，`profile:"mock-demo"`、`status:"active"`、`messageCount:4` |
| `GET /api/v1/sessions/cli:local:mock-demo` | 四条消息，角色 `user / assistant / tool / assistant` |
| `GET /api/v1/memory` | `archival` 含「美式咖啡」、`truncated:false` |
| `GET /api/v1/tools` | 15 个，含 `save_memory` |
| `POST /api/v1/sessions`（`profile=mock-demo,userId=alice`） | `sessionId=web:alice:mock-demo`、`channel:"web"`、`messages:null` |
| `POST /api/v1/sessions/web:alice:mock-demo/messages` | `{"sessionId":"web:alice:mock-demo","reply":"好的，我记住了。"}` |
| 列表（两条会话后） | `web:alice:mock-demo 4 active` / `cli:local:mock-demo 4 active`，按最后活跃倒序 |
| `?status=active` / `?status=ARCHIVED` / `?status=bogus` | 2 条 / 200（空列表，大小写不敏感）/ **400** `status must be one of: active, archived` |
| `GET /api/v1/info` | `agents:1`、`activeSessions:2`、`registeredTools:15`、`version:"unknown"`、`javaVersion:"21.0.12.1"` |
| `/admin/` | 200 `text/html`，`<html lang="zh-CN">`；`/admin/sessions`（子路由刷新）200 |

**没实测的是浏览器里的渲染**（Step 7 的七个页面）——那正是要你人工过的部分。

### 写向导时抓到并修掉的两处（如实留痕）

**① README「不用任何模型凭证跑通全链路」缺了 `oryxos init`**（已修）。照原文在全新目录执行，`chat` **退出码 1**、stderr 里是 `[SQLITE_ERROR] ... (no such table: sessions)`——工作区没建表，而 `chat` 不会自己建。已在 README 段首补 `java -jar oryxos-boot/target/oryxos.jar init` 一行，Step 1 也把它列为**必做**。**根因与第 26 节向导第三次抓红同类**：作者自己跑时工作区早就 init 过，「我做过的准备」没有变成「读的人要执行的步骤」。

**② 管理台导航「会话」的副标题已过期**（已修）。`router.js` 里还写着「按会话 ID 查询」——那是第 26 节没有列表端点时的形态；本节改成列表 + 点开看详情后，副标题已改成「最近活跃的会话与历史」。这是**界面文案**问题，不影响端点，但会误导目视复核（`AdminConsoleSourceTest` 不判副标题）。

**两处的根因同一句话**：「命令跑通了」不等于「输出与我写的一致」；**「我做过的准备」不是「你的步骤」**——这正是每次向导都要过一遍 Step 的原因。

### 用户实跑抓到三处（2026-10-20，已修向导）

**① Step 7.3 写「`truncated` 为否」，页面上根本找不到这个字。** `MemoryView.vue` 只在 `memory.truncated === true` 时渲染「归档区已截断」徽标；未截断时**什么都不渲染**。向导的判据应写成「**没有**那个徽标」，而不是让人找一个不存在的「否」。已改。

**② Step 7.6 写「页面明写『执行历史不在这里』」，页面原文不是这句。** `SchedulesView.vue` 的原文是「**运行时状态不在这里**：最近执行、成功失败、下次触发时刻属于调度运行态……本页只呈现配置面」。「执行历史不在这里」是 `CLAUDE.md` 的**转述**，我抄转述当成了原文。已改向导，并同步订正 `CLAUDE.md` 那句转述。

**③ Step 9 写 `kill "$(cat serve.pid)"`，在仓库根执行报 `No such file or directory`。** `serve.pid` 是 Step 5 用 `echo $! > serve.pid` 写的**相对路径**，落在 serve 的工作目录 `/tmp/l27-demo`；Step 9 却让人在任意目录下执行，且同一块里紧接着 `rm -rf /tmp/l27-demo`——目录一删、pid 文件跟着没，**进程「无家可归但还在跑」继续占着 8080**（用户实跑时确实如此，实测残留 PID 的工作目录显示为 `/tmp/l27-demo (deleted)`）。已把 Step 9 改成「有 pid 文件就按它杀、没有就按端口定位，等 8080 真的不再监听后再删工作区」，并加坑表第 14 行；残留进程已停。

**根因**：①、② 不是命令或预期值错，而是**我照着代码注释 / 文档转述写判据，没有逐字核对「页面上到底渲染了哪些字」**；③ 是**相对路径跨目录失效**——写向导的判据时，引用必须是**模板里的字面量**、路径必须是**不依赖当前目录的写法**（或明确写清「在哪个目录执行」）。

---

## 前置事实（跑之前先认这些）

| # | 事实 |
| --- | --- |
| ① | 唯一发行物 `oryxos-boot/target/oryxos.jar`，主类 `io.oryxlabs.oryxos.cli.OryxOsCli` |
| ② | `mock` Provider 随包声明在 `application.yaml`（唯一不要求 `api-key` 的厂商）；**只有「模型」是假的**，ReActLoop / ToolExecutor / Memory / Session / 审计全部走真实路径 |
| ③ | **无 key 也能启动**（实测）：`${DEEPSEEK_API_KEY}` 占位不阻塞上下文；但 `/health` 仍报 `deepseek:"UP"`——那是「配置就绪」语义，**不做可达性探测**（第 26 节前置事实 ⑨ 同一条） |
| ④ | `bin/start.sh` **恒要求真实 key**（`bin/config.yaml`），mock 自测**不走它**，直接 `java -jar` |
| ⑤ | mock 脚本是固定的：**每一个用户回合** → 1 次 `save_memory` + 1 次最终答复 → 会话 4 条消息、`llm_calls +2`、`tool_invocations +1`。消息数不会因模型发挥而变 |
| ⑥ | 会话 id 公式 `channel:userId:profile`：CLI = `cli:local:<agent>`，Web = `web:<userId>:<agent>` |
| ⑦ | 管理台只读；`GET /api/v1/sessions` 返回**最近 100 条摘要**（不含 `messages`），`?status=` 过滤作用于该窗口内、大小写不敏感、非法值 **400**（不静默忽略） |
| ⑧ | 诊断日志走 **stderr**、命令输出走 **stdout**（第 20 节的流分离）；`2>chat.err` 后 stdout 只剩对话内容 |
| ⑨ | 管理台静态资源长缓存；升级 jar 后首次打开请**硬刷新**（`Cmd/Ctrl+Shift+R`） |
| ⑩ | 本机**没有 `sqlite3` CLI**，查库用 `python3`（`sqlite3` 模块） |

---

## Step 0：构建（改了 Java 必须 `clean`）

```bash
cd /home/wngbob/oryxos-wngbob
./mvnw clean package -DskipTests -Ddependency-check.skip=true
ls -l oryxos-boot/target/oryxos.jar
```

预期：`BUILD SUCCESS`，jar 在 80MB 量级、mtime 是刚才。

> **为什么必须 `clean`**：`maven-jar-plugin` 在「本模块 classes 没变」时会跳过重打 jar（依赖变了不算它的输入），`spring-boot:repackage` 于是把**旧嵌套 jar** 原样再包一遍——`BUILD SUCCESS` 但产物是旧的（第 26 节实测过）。本节改了 `oryxos-web` 的 Java 与管理台前端，务必 `clean`。

若你确定 jar 是刚构建的、且之后没改过任何模块，可跳过本步（Step 1 会顺带验证 jar 可用）。

---

## Step 1：准备工作区（**必须** `oryxos init`）

```bash
rm -rf /tmp/l27-demo && mkdir -p /tmp/l27-demo && cd /tmp/l27-demo
export JAR=/home/wngbob/oryxos-wngbob/oryxos-boot/target/oryxos.jar
java -jar "$JAR" init
```

预期输出（末尾两段）：

```text
数据库表结构:
  与 db/schema.sql 一致（无需补列）

下一步: `oryxos profile create <name>` 建一个 Agent，再 `oryxos chat --profile <name>` 开始对话
```

> ⚠️ **不跑这步的后果是实测过的**：`chat` 退出码 **1**，stderr 报 `no such table: sessions`。`serve` 也不会替你建表。

> ⚠️ 换了终端先确认 `$JAR` 还在：`echo "$JAR"`。末尾是空字符串就回本步重新 `export`（**别重新构建**）。

---

## Step 2：建一个 `mock` Agent（不写 Java）

```bash
mkdir -p .oryxos/agents/mock-demo
cat > .oryxos/agents/mock-demo/AGENT.md <<'EOF'
---
description: 无凭证全链路自测
identity:
  agent_name: 小助手
  prompt: 你是 OryxOS 的全链路自测助手。
provider: { name: mock, model: mock-model }
tools: [save_memory]
---

按脚本把用户说的话记进长期记忆。
EOF

java -jar "$JAR" profile list
```

预期：

```text
Agent（共 1 个，目录 .oryxos/agents/）:
  mock-demo  有效  provider=mock/mock-model  无凭证全链路自测
```

判据：`有效` 两个字必须出现。`无效` 说明 frontmatter 写坏了（多半是缩进或手误）。

---

## Step 3：CLI 对话（**无 key**）

```bash
unset DEEPSEEK_API_KEY   # 证明真的不需要凭证；本机 profile 里 export 过的话这行不能省
printf '记住：我喜欢喝美式咖啡\n/quit\n' | java -jar "$JAR" chat --profile mock-demo 2>chat.err
echo "exit=$?"
```

预期 **stdout**（数据流）：

```text
已连接 Agent 'mock-demo'（channel=cli, user=local）
输入内容开始对话，输入 /quit 退出。
> 好的，我记住了。
> 
```

判据：

```bash
grep -c 'LLM 调用完成' chat.err     # 预期：2
grep -c '工具调用完成' chat.err     # 预期：1
```

答复**必须逐字**是「好的，我记住了。」——mock 的固定脚本（`MockChatModel.FINAL_REPLY`），不是模型发挥。看到别的措辞说明你用的不是 `mock`（检查 AGENT.md 的 `provider`）。

> 这 2 次 LLM 调用与 1 次工具调用就是一次完整 ReAct：第一轮模型请求 `save_memory`，工具真的执行并写文件，第二轮模型拿到工具结果给最终答复。

---

## Step 4：查记忆与审计（不启 Spring）

```bash
cat .oryxos/memory/MEMORY.md

python3 - <<'PY'
import sqlite3
db = sqlite3.connect('.oryxos/oryxos.db')
print("llm_calls:", db.execute("select count(*), sum(success) from llm_calls").fetchall())
print("tool_invocations:", db.execute("select tool_name, success from tool_invocations").fetchall())
print("sessions:", db.execute("select session_id, status from sessions").fetchall())
PY
```

预期：

```text
## 核心记忆

## 归档记忆
- [2026-10-20] 记住：我喜欢喝美式咖啡

llm_calls: [(2, 2)]
tool_invocations: [('save_memory', 1)]
sessions: [('cli:local:mock-demo', 'active')]
```

判据：**三处账对得上**——记忆文件里有一条、`llm_calls` 两条且都成功、`tool_invocations` 一条成功。（日期是你跑的那天。）

---

## Step 5：起服务（**无 key**）

```bash
java -jar "$JAR" serve --port 8080 > serve.log 2>&1 &
echo $! > serve.pid

for i in $(seq 1 60); do
  curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/api/v1/health 2>/dev/null | grep -q 200 && break
  sleep 1
done

curl -s http://127.0.0.1:8080/api/v1/health
```

预期：

```json
{"code":200,"message":"ok","data":{"status":"UP","db":"UP","providers":{"deepseek":"UP","mock":"UP"}},"timestamp":"..."}
```

日志里应有 `Tomcat started on port 8080 (http)` 与 `定时任务注册完成：共 0 条`。

> ⚠️ 端口被占就换一个（本节实测用的是 18090/18091）：`--port 18090`，后面所有 URL 跟着换。
>
> ⚠️ `providers.deepseek:"UP"` **不代表你有 key**——那是「配置就绪」语义（前置事实 ③）。

---

## Step 6：REST 对账（与 Step 3/4 是同一份数据）

### 6.1 会话列表（本节新增的端点）

```bash
curl -s http://127.0.0.1:8080/api/v1/sessions | python3 -m json.tool
```

判据：`data` 里 1 条，`sessionId:"cli:local:mock-demo"`、`messageCount:4`、`status:"active"`。

### 6.2 会话详情

```bash
curl -s "http://127.0.0.1:8080/api/v1/sessions/cli:local:mock-demo" \
  | python3 -c "import json,sys; d=json.load(sys.stdin)['data']; print([m['role'] for m in d['messages']]); print(d['messages'][0]['content'])"
```

预期：`['user', 'assistant', 'tool', 'assistant']` 与 `记住：我喜欢喝美式咖啡`。

### 6.3 长期记忆 / 工具

```bash
curl -s http://127.0.0.1:8080/api/v1/memory
curl -s http://127.0.0.1:8080/api/v1/tools | python3 -c "import json,sys; n=[t['name'] for t in json.load(sys.stdin)['data']]; print(len(n), 'save_memory' in n)"
```

预期：`archival` 含「美式咖啡」；`15 True`。

### 6.4 再走一遍 REST 人推链（顺便给管理台多一行）

```bash
B=http://127.0.0.1:8080/api/v1
curl -s -X POST $B/sessions -H 'Content-Type: application/json' -d '{"profile":"mock-demo","userId":"alice"}'; echo
curl -s -X POST $B/sessions/web:alice:mock-demo/messages -H 'Content-Type: application/json' -d '{"content":"记住：我住在杭州"}'; echo
curl -s $B/sessions | python3 -c "import json,sys; [print(s['sessionId'], s['messageCount']) for s in json.load(sys.stdin)['data']]"
```

预期：`sessionId:"web:alice:mock-demo"`；`reply:"好的，我记住了。"`；列表两行：

```text
web:alice:mock-demo 4
cli:local:mock-demo 4
```

### 6.5 状态过滤

```bash
curl -s "$B/sessions?status=active"   | python3 -c "import json,sys; print('active:', len(json.load(sys.stdin)['data']))"
curl -s "$B/sessions?status=ARCHIVED" | python3 -c "import json,sys; print('archived:', len(json.load(sys.stdin)['data']))"
curl -s -w '\nHTTP %{http_code}\n' "$B/sessions?status=bogus"
```

预期：`active: 2` / `archived: 0` / **`HTTP 400`** `status must be one of: active, archived`。

> 拼错的状态**报错**而不是被忽略——静默忽略会返回一份「看起来对」的混合列表。

### 6.6 概览页的数字来源

```bash
curl -s $B/info | python3 -m json.tool
```

预期：`agents:1`、`activeSessions:2`、`registeredTools:15`。**Step 7 概览页要核对的就是这三个数。**

---

## Step 7：浏览器——管理台目视复核（**harness 判不了的唯一一段**）

打开 **<http://localhost:8080/admin/>**（结尾斜杠；`/admin` 会 302 过去），应落在 **`/overview`**。

### 7.1 概览页（导航第 1 项）

| 看什么 | 预期 |
| --- | --- |
| 四张计数卡 | Agent **1**（脚注「可用 1 / 共 1 个」）、活跃会话 **2**、已注册工具 **15**（脚注「1 种来源」）、已运行 **x 秒**（脚注 `vunknown`——本机构建未注入版本号，`/info` 同值，不是 bug） |
| 健康卡 | 整体 `UP`、DB `UP`、Provider 两行 `deepseek UP` / `mock UP` |
| Agent 清单（紧凑版） | `mock-demo` 一行、有效 |
| 工具构成 | `builtin` 15 |

### 7.2 会话页（导航第 2 项，**本节改版的那一页**）

1. 默认「全部」→ 表格**两行**：`web:alice:mock-demo`、`cli:local:mock-demo`；列是 会话 ID / Agent / 状态 / 渠道·用户 / 消息数 / 最后活跃；两行消息数都是 **4**，状态徽标 `active`（绿色）。
2. 状态切「active」→ 仍两行；切「archived」→ 0 行（空态文案）。再切回「全部」。
3. **点任意一行** → 下方出现「会话概览」卡（会话 ID / Agent / 状态 / 渠道·用户 / 创建·最后活跃 / 归档时刻）与「历史消息（最近 100 条，按时间正序）」：
   - 四条：`user`（你输入的那句）、`assistant`（内容为空——这一轮只发起了工具调用）、`tool`（`已记住`）、`assistant`（`好的，我记住了。`）；
   - 选中行有高亮底色。
4. 点另一行 → 详情跟着换。

### 7.3 长期记忆页

归档区应有**两条**：`- [日期] 记住：我喜欢喝美式咖啡`、`- [日期] 记住：我住在杭州`；核心区为空；归档卡标题旁**没有**「归档区已截断」徽标——该徽标只在 `truncated=true` 时渲染，未截断时页面上**不出现任何 truncated 字样**（别去找「否」）。

> 以上是 **mock 链路**的预期（mock 只传 `content`、不传 `scope`，故一律落归档区、且原文照录）。**Step 8 换成真模型后不适用**：模型会自选分区、改写措辞（实测会把稳定偏好写进核心区）——见 Step 8 的说明。

### 7.4 工具页

标题「共 15 个工具」；「来源」列全是 `builtin`（`save_memory` / `recall_memory` / `http_get` 都在）。

### 7.5 Agent 页

`mock-demo` 一行、有效。

### 7.6 定时任务页

0 条（`mock-demo` 没写 `schedules`），空态文案是「没有 Agent 声明定时任务。在 `AGENT.md` 的 frontmatter 里写 `schedules: [{cron, zone, message}]` 即可。」；下方「这页能回答什么、不能回答什么」卡的第四条写的是「**运行时状态不在这里**：最近执行、成功失败、下次触发时刻属于调度运行态……本页只呈现配置面」。

### 7.7 运行状态页

「健康检查」卡的三行（整体状态 / 数据库 / Provider 配置就绪）与 7.1 一致；「运行信息」卡里 Java 版本是 `21.0.12.1`（以你机器为准）。

### 7.8 只读确认（目视）

全站没有「新建 / 删除 / 编辑 / 保存 / 提交」按钮或表单（源码层由 `AdminConsoleSourceTest` 判死，这里补人眼）。

### 7.9 子路由直开

新标签页直接打开 `http://localhost:8080/admin/sessions` → 正常渲染（不是 404，也不是空白）。

---

## Step 8（可选）：真模型对照

有 key 且能出网时，把 Step 2 的 AGENT.md 改成 `provider: { name: deepseek, model: deepseek-chat }`、`tools: [http_get, save_memory, recall_memory]`，重跑 Step 3~7；或直接跑本节的真链路 harness：

```bash
cd /home/wngbob/oryxos-wngbob   # mvnw 在仓库根，不在 /tmp/l27-demo
# Step 3 若执行过 unset，这里要重新给 key（本机 profile 里已 export 的话可省略这一行）
export DEEPSEEK_API_KEY=sk-...

./mvnw -pl oryxos-cli -am test \
    -DexcludedGroups=none -Dtest=HumanTriggerFlowIT \
    -Dsurefire.failIfNoSpecifiedTests=false
```

> 跑完 `cd` 回工作区不影响任何东西；这条测试自带临时工作区（`oryxos.root` 指向 `/tmp/oryxos-it-<uuid>`，跑完自动删）。

预期：`Tests run: 5, Failures: 0`。**它会真花钱、真出网**（DeepSeek + wttr.in），且机器上需能访问这两处。

> **用真模型跑时，不要拿 Step 3 / 7.3 的字面值去对**：`scope` 是模型可显式选择的参数（`save_memory` 的 schema 里写着「core 或 archival，不确定就填 archival」，缺省才是 archival），真模型会自己判断分区、自己改写措辞。**实测（2026-10-20）**：DeepSeek 把「喜欢喝美式咖啡」判成稳定偏好、显式传 `scope=core`，写进 `MEMORY.md` 的「核心记忆」区——**这是预期行为**（core 永远全量注入、不截断，正适合稳定偏好），不是 bug；它的回复里说「核心记忆里本来就有这条」也是正常的：ReAct 第二轮会重新读取记忆（第 22 节「调完 `save_memory` 下一轮就能看见」），第二次模型调用看到的 prompt 里已经包含它刚写的那条。所以真模型对照时，判据只看**三面同源**（文件 / REST / 管理台一致）与**账目条数**（每轮 2 次 LLM 调用），分区与措辞以实际为准。

---

## Step 9：收尾

```bash
# 1) 停服务：serve.pid 写在 serve 的工作目录（/tmp/l27-demo），不在仓库根；
#    若已经换过目录或先删了工作区，按端口找 PID 更稳。
if [ -f /tmp/l27-demo/serve.pid ]; then
  kill "$(cat /tmp/l27-demo/serve.pid)" 2>/dev/null
else
  PID=$(ss -tlnp 2>/dev/null | grep ':8080 ' | grep -o 'pid=[0-9]*' | cut -d= -f2 | head -1)
  [ -n "$PID" ] && kill "$PID"
fi

# 2) 等它真正退出（8080 不再监听）
for i in $(seq 1 15); do
  ss -tln 2>/dev/null | grep -q ':8080 ' || break
  sleep 1
done
ss -tln 2>/dev/null | grep ':8080 ' && echo "⚠️ 8080 仍在监听" || echo "serve 已停止"

# 3) 删演示工作区
rm -rf /tmp/l27-demo
```

（管理台没有独立进程，`serve` 一停就全停。**顺序不能反**：先停服务再删工作区——`serve` 的工作目录就是 `/tmp/l27-demo`，目录先没了，里面的 `serve.pid` 也跟着没了，进程会变成「无家可归但还在跑」，继续占着 8080。）

---

## 坑表（照第 19 / 22 / 24 / 25 / 26 节体例）

| # | 坑 | 对策 |
| --- | --- | --- |
| 1 | 不跑 `oryxos init` 直接 `chat` → 退出码 1、`no such table: sessions` | 先 Step 1（README 的旧写法就缺这步，已修） |
| 2 | 用 `bin/start.sh` 跑 mock → 它恒要求真实 key | mock 自测直接 `java -jar`（前置事实 ④） |
| 3 | 新终端里 `java -jar "$JAR"` 报 `Unable to access jarfile` 后跟空串 | `$JAR` 只在当前 shell export 过；回 Step 1 重新 export |
| 4 | 无 key 时 `/health` 仍报 `deepseek:"UP"`，以为 key 生效了 | 那是配置就绪语义；真调用才会失败（前置事实 ③） |
| 5 | 改了 Java 后 `package` 不带 `clean` → 产物是旧的 | 一律 `clean package`（Step 0） |
| 6 | 8080 被占，`serve` 起不来 | `--port <别的>`，后面 URL 跟着换 |
| 7 | 浏览器还显示旧页面 / 旧副标题 | 硬刷新（前置事实 ⑨） |
| 8 | 会话「消息数 4」以为多了 | mock 每轮固定 4 条（前置事实 ⑤） |
| 9 | `?status=bogus` 回 400 以为是 bug | 这是刻意的（静默忽略才是 bug） |
| 10 | `chat` 的输出里混着日志 | 日志在 stderr；要干净输出就 `2>/dev/null`（前置事实 ⑧） |
| 11 | 重跑 Step 6.4 撞上上一轮的会话 | `web:alice:mock-demo` 会取回既有会话并**继续追加**消息；想从头来就换 `userId`（如 `bob`） |
| 12 | Step 8 换成真模型后，记忆跑进了「核心记忆」区、措辞也和 Step 3 不一样，以为写错了 | `scope` 是模型自选的（缺省才是 archival）；真模型把稳定偏好写进 core 是预期行为，见 Step 8 说明 |
| 13 | 照 Step 8 敲 `./mvnw` 报 `No such file or directory` | 你还站在 `/tmp/l27-demo`；`./mvnw` 在仓库根——先 `cd /home/wngbob/oryxos-wngbob`（Step 8 已补） |
| 14 | Step 9 敲 `kill "$(cat serve.pid)"` 报 `No such file or directory` | `serve.pid` 写在 serve 的工作目录 `/tmp/l27-demo`，不在仓库根；换过目录或先删了工作区都会找不到。按端口定位：`PID=$(ss -tlnp \| grep ':8080 ' \| grep -o 'pid=[0-9]*' \| cut -d= -f2)`，再 `kill "$PID"`（Step 9 已改） |

---

## 收尾判据（全部打勾才算人工验收通过）

- [ ] `chat` 的答复**逐字**是「好的，我记住了。」，退出码 0
- [ ] `MEMORY.md` 归档区有「美式咖啡」那一行
- [ ] 审计对账：`llm_calls` 2 条全成功、`tool_invocations` 1 条 `save_memory` 成功
- [ ] REST 四查（列表 / 详情 / 记忆 / 工具）与 Step 4 是同一份数据
- [ ] 管理台七页目视通过，概览三个数与 `/info` 一致
- [ ] 会话页两行、点开能看四条历史，状态过滤行为正确
- [ ] 全站没有写操作入口
