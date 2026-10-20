# Web Service 与第一版管理台（第 26 节）人工验收向导（Step by Step）

**Feature**: `specs/009-web-service` | **分支**: `class-26` | **Date**: 2026-10-19

**上游文档**：[spec.md](spec.md) · [plan.md](plan.md) · [tasks.md](tasks.md) · [quickstart.md](quickstart.md) · [acceptance-report.md](acceptance-report.md) §四

**适用对象**：第 26 节的最终形态（10 个 REST 端点 + 统一信封与异常出口 + 只读管理台 + `/swagger-ui`）

**目的**：覆盖单测判不了的那两段——**真实 HTTP 链路**（容器起来了、路由通了、真模型回话了、CLI 与 HTTP 共用同一份存储）与**浏览器里的七个页面**（`.vue` 源码只能判「只发 GET、没有写入口文字」，判不了「渲染出来是不是真实数据、刷新子路由 404 不 404」；2026-10-20 新增的概览页是运行时状态总览，判据是「它的数字与四个查询端点一致」；同日再新增**定时任务页**，判据是「条数与 `/profiles` 的 `schedules` 一致」）。

**预计耗时**：Step 0~9 约 20~25 分钟（Step 0 全量构建 5~15 分钟；Step 4/5.6/7 各触发一次模型调用，其中 5.6 是**失败**调用）。

---

## 实测记录

### 写向导时已实测（2026-10-19，jar = `oryxos-boot/target/oryxos.jar`）

工作区 `/tmp/l26`（`oryxos init` 现造），Agent = `profile create smoke` 生成的默认模板（`provider: deepseek/deepseek-chat`）。**凡是「不需要浏览器」的步骤都真跑过**：

| 已实测 | 实测结果 |
| --- | --- |
| `oryxos init` + `profile create smoke` | 正常；`AGENT.md` 是模板原样 |
| `serve --port 18080` 启动 | `Tomcat started on port 18080 (http) with context path '/'`；`定时任务注册完成：共 0 条`（第 25 节的注册仍在）；**只靠 `DEEPSEEK_API_KEY` 一个环境变量就起来了** |
| `GET /api/v1/health` | `{"code":200,...,"data":{"status":"UP","db":"UP","providers":{"deepseek":"UP"}}}` |
| `GET /api/v1/info` | `registeredTools=15`、`agents=1`、`activeSessions=0`、`javaVersion=21.0.12.1` |
| `GET /api/v1/profiles` | 数组 1 项，`valid:true`、`error:null` |
| `GET /api/v1/memory` | `{"core":"","archival":"","truncated":false}`（空工作区，预期） |
| `GET /api/v1/tools` | 15 项，`source` 全是 `builtin` |
| `POST /api/v1/sessions` | `sessionId=web:alice:smoke`、`channel:"web"`、`status:"active"` |
| `POST /api/v1/sessions/{id}/messages`（真模型） | `{"sessionId":"web:alice:smoke","reply":"我是 OryxOS 的默认助手，务实直接，先给结论再给依据。"}` |
| `GET /api/v1/sessions/{id}` | `messages` 两条（user / assistant，按时间正序） |
| `DELETE` 连做两次 | 两次 `archivedAt` **完全相同**（`2026-10-19T11:48:53.603Z`） |
| 归档后再发消息 | **400** `session is archived: web:alice:smoke`，历史仍可查（2 条） |
| `POST /api/v1/agents/smoke/invoke`（真模型） | `{"agent":"smoke","reply":"2"}`；`sessions` 表 `invoke-exec:%` **0 行**，而 `llm_calls` 有 1 行——一次性调用不建会话、但审计照记 |
| 404 / 404 / 400 / 400 | `session not found: no-such-session` / `agent not found: no-such-agent` / `message exceeds 32KB limit` / `content is required`，四条都是统一信封且 `code` = HTTP 状态码 |
| **断掉 Provider → 503**（2026-10-19 补测） | 独立工作区 `/tmp/l26-503`、`DEEPSEEK_API_KEY=sk-invalid-for-503-test`：发消息 → **HTTP 503** `{"code":503,"message":"provider unavailable: deepseek","data":null,...}`（对端 401 原文**不进响应体**）；同一请求在 `llm_calls` 落 **success=0** 行（`error_message` 保留 401 原文，供排障）；**同一时刻 `/health` 仍报 `deepseek:"UP"`**——这正是前置事实 ⑨「配置就绪 ≠ 可达」的实证 |
| CLI ↔ API 共享存储 | `printf '你好\n/quit\n' \| java -jar $JAR chat --profile smoke` 之后 `GET /api/v1/sessions/cli:local:smoke` → 200，两条消息都在 |
| `/admin/` | 200 `text/html`，内容含 `<html lang="zh-CN">`（**不带**深色主题类） |
| `/admin`（无斜杠） | **302 → `/admin/`** |
| `/admin/tools`（刷新子路由） | 200，返回与 `/admin/` **逐字节相同**的 index，`Cache-Control: no-cache` |
| `/admin/assets/index-*.js` | 200，`Cache-Control: max-age=31536000, public, immutable` |
| `/admin/assets/nope.js` | **404**（真 404，不回落成 index） |
| `/api/v1/nope` | **404** 统一信封（API 路径不参与 SPA 回落） |
| `/swagger-ui` | 302 → `/swagger-ui/index.html`（200）；`/v3/api-docs` 里**恰好 10 个** operation（6 组，逐条对得上契约） |
| 本机 `sqlite3` CLI | **不存在**。查库统一走 `python3`（`sqlite3` 模块） |

**没实测的是浏览器里那两件事**：七个页面的渲染效果（含 2026-10-20 新增的概览页、定时任务页与徽标）、页面上确实没有写操作入口（源码层由 `AdminConsoleSourceTest` 判，视觉层要人看）。**其余步骤你只需要照做一遍确认「在我这台机器上也一样」**。

### 写向导时抓到并修掉的三处（如实留痕）

**① `/admin/` 打开就 404（`WebConfigTest` 抓红，已修）**：`/admin/**` 交给 `ResourceHttpRequestHandler` 时，`/admin/` 剩给它的资源路径是**空串**，而它在空路径上**直接返回 null、根本进不到解析链**，于是 `SpaFallbackResolver` 没机会回落——实测现象是「`/admin/sessions` 正常、`/admin/` 却 404」。**修法**：`WebConfig` 里加一个 view controller 显式 `forward:/admin/index.html`（契约 `contracts/admin-console.md` §2 本来写的就是 forward）。这也解释了为什么单测要拆成两条：`adminRoot_forwardsToSpaIndex` 断言 `forwardedUrl`，`adminIndexHtml_servesSpaIndex` 断言回落到的那份内容——MockMvc **不重新分发 forward**，一条断言看不全。

**② 「建会话 → 发消息」第二步 404（真链路跑 quickstart 时抓到，已修）**：`POST /api/v1/sessions` 回 200，紧接着 `POST /api/v1/sessions/{id}/messages` 回 `404 session not found`。根因：`SessionService.getOrCreate` **对新三元组只造对象、不写行**（行的落库原先只发生在 `AgentService.process` 的末尾），而 `send` 第一步就是 `get(id)`——**契约 §1.1 的「创建会话」语义上必须让会话当场存在**。**修法**：`SessionApiController.create` 在 `getOrCreate` 之后调 `sessionService.save(session)`（已存在的会话走更新路径、写回同一份内容，幂等），并在 `SessionApiControllerTest` 里补 `verify(sessionService).save(created)` 作回归判据。**这条是「单测全绿但主流程断」的典型**：`@WebMvcTest` 里 `SessionService` 是 mock，mock 不落库这件事它照不到。

**③ 增量 `mvn package` 会把旧字节码打进 fat JAR（构建工具坑，已绕开）**：改了 Java 之后跑 `./mvnw package -DskipTests`，输出 `BUILD SUCCESS`、`Replacing main artifact`，但 `oryxos-boot/target/oryxos.jar` 的 mtime 没变、`BOOT-INF/lib/oryxos-web-*.jar` 还是**上一版**（77900 → 77872 字节之差）。根因：`maven-jar-plugin` 判定「本模块 classes 没变」就跳过重打 jar（依赖变了不算它的输入），`spring-boot:repackage` 于是把**旧嵌套 jar** 原样再包一遍。**判据**：`unzip -l oryxos-boot/target/oryxos.jar | grep oryxos-web-` 的字节数/timestamp 应与 `oryxos-web/target/oryxos-web-*.jar` 一致。**做法**：本节及以后改了任何模块的 Java，一律 `./mvnw clean package`；本向导 Step 0 因此写的是 `clean`。

**三处的根因同一句话**：**「命令跑通了」不等于「我做的那件事发生了」**——① 是「200 的路由不等于完整路由」、② 是「mock 绿不等于链路通」、③ 是「BUILD SUCCESS 不等于产物是新的」。

### 用户实跑时抓到的五处（2026-10-20，已修向导）

**现象**：照 Step 2 执行，`/health` 200，但 `GET /api/v1/info` → **500** 统一信封，`serve` 的 stderr 里是 `JpaSystemException: ... (no such table: sessions) [select count(se1_0.session_id) from sessions se1_0 where se1_0.status=?]`。

**根因**：向导**从来没有让人先初始化工作区**。`serve` 本身能起来（Hikari 会建出空的库文件），但 `oryxos init` 才跑 `schema.sql`；用户那份 `.oryxos/oryxos.db` 是 2026-10-10 的旧库，只有 `tool_invocations` / `llm_calls` 两张表（第 17 节时期），`sessions` 不存在 → `info()` 里的 `sessionService.countActive()` 报 SQL 错。Step 2 的另外四条（`health` / `profiles` / `tools` / `memory`）恰好都不碰 `sessions` 表，所以只有 `/info` 暴露。

**同一缺口还有第二处**：主工作区的 `smoke` Agent 也从未让使用者创建（只在「写向导时」的实测记录里出现过），Step 3 的 `POST /sessions` 会在下一步报 `404 agent not found: smoke`。已一并补进 Step 0.5。

**重跑 Step 3 撞上上一次的归档态（2026-10-20，用户实跑命中，已修向导）**：重跑时 3.1 返回 `status:"archived"`（`archivedAt` 是上一次 3.4 的时刻），3.2 会直接 400 `session is archived`。这是契约 §1.1「创建（或取回）」的**预期行为**——同一 `(profile, userId)` 取回同一个会话，归档不复活；向导原先没写重跑该换 `userId`，已补「重跑须知」与坑表第 12 行。

**Step 5.6 换终端丢 `$JAR`（2026-10-20，用户实跑命中，已修向导）**：`Error: Unable to access jarfile` 后跟空字符串。与第 25 节向导那次同一类——`JAR` 只在前面的 shell 里 export 过。Step 5.6 的命令块原先直接用 `$JAR` 却没重设，已把 export + `echo`/`ls` 自查补进该块开头。

**旧库缺列导致发消息 500（2026-10-20，用户实跑命中；同日经用户批准改为 `init` 自动补列）**：同一份旧库里 `llm_calls` 还缺 `success` / `error_message` 两列（第 16 节才加），`init` 的 `CREATE TABLE IF NOT EXISTS` 对已存在的表直接跳过、**当时不补列**。于是 3.2 发消息回 **500**，日志是 `table llm_calls has no column named error_message`——模型调用已经发生，审计写入才炸。作者侧复现：把用户库复制到 `/tmp/l26-repro2`，`serve` 后发消息 500；`ALTER TABLE` 补两列后同一请求变 **503**（无效 key 的预期结果），且 `llm_calls` 落下一行 `success=0`。**注意：换成有效 key 也一样 500**（成功路径的 insert 同样带这两列），与模型无关。**2026-10-20 修订**：已按用户批准给 `oryxos init` 加上漂移检测与加性补列（见 Step 0.5），此处的手工脚本仅对旧 jar 仍有意义。

**为什么写向导时没抓到**：作者的 `/tmp/l26` 是 `oryxos init` 现造的（见上一小节表格第一行），而**那一行是「验证环境怎么来的」、不是「使用者要执行的步骤」**——写的人自己做过，就默认读的人也做过了。同类错误第三次出现在这份向导里，判据要再加一条：**「环境准备」不是背景信息，必须作为可执行步骤出现在流程里**。

**修法**：新增 **Step 0.5**（`oryxos init` + 查缺失表）、Step 2 加「只有 `/info` 500」的定位提示、坑表加第 11 行。

**实测复现与修复验证**（作者侧，2026-10-20）：把用户那份旧 `.oryxos/` 复制到 `/tmp/l26-repro`，`serve --port 18083` → `/health` 200 `db:"UP"`、`/info` **500**（日志同一条 `no such table: sessions`）；在 `/tmp/l26-repro` 跑 `oryxos init`（输出「已有（未覆盖）: .oryxos/oryxos.db」，随后六张表齐）→ 重启 `serve` → `/info` **200**，`agents:0`、`activeSessions:0`、`registeredTools:15`。

### 用户验收状态（2026-10-20）

**用户按 Step 0~9 全程人工执行（真 DeepSeek 模型、真 `oryxos.jar`），结论：通过，未报告任何一步失败。** 执行过程中命中的五处问题（Step 0.5 缺口 / `smoke` Agent 缺口 / Step 3 重跑归档 / 旧库缺列 500 / Step 5.6 丢 `$JAR`）中**四处是向导或环境问题**，已逐条落档并修进上文；**第五处（旧库缺列）是产品缺口**——验收当时只修了向导，随后经用户批准改成产品修复（`init` 自动补列，见 Step 0.5 与验收报告 §三 ⑩）。

按既定留痕口径（照第 19 / 22 / 25 节）：**只记整体结论、不写步骤级细节**（用户未逐步说明各步输出）。

**覆盖面（别把「跑完了」读成「每条判据都被独立证过」）**：

| 项 | 状态 |
| --- | --- |
| Step 0~4、6~9（构建、初始化、状态、会话、一次性调用、CLI 共享存储、管理台、文档、收尾） | 用户执行，整体通过 |
| Step 5.1~5.5（错误码映射，不依赖模型） | 用户执行，整体通过 |
| Step 5.6（无效 key → 503） | 用户执行到该步；**用的是无效 key**，模型侧真实失败、审计落 `success=0` 由作者侧另行实测（§四 第 5 条） |
| 「并发压一把」（spec 人工项） | **未做**——本向导没有对应步骤，如实记为未覆盖（§四 已知边界） |
| 504 真链路 | **结构上不可达**（裁决 ②，无抛出点），判卷在 `GlobalExceptionHandlerTest` |

---

## 前置事实（跑之前先认这些）

| # | 事实 |
| --- | --- |
| ① | 唯一发行物 `oryxos-boot/target/oryxos.jar`，主类 `io.oryxlabs.oryxos.cli.OryxOsCli`；`serve` 起真 SERVLET 容器 |
| ② | **只有 `serve` 起 HTTP**；`chat` / `gateway` 不起（`gateway` 只跑定时任务） |
| ③ | 端点恒在 `/api/v1/**`；管理台在 `/admin/`；文档在 `/swagger-ui`（302 到 `/swagger-ui/index.html`） |
| ④ | 信封恒为 `{code, message, data, timestamp}`，**`code` 与 HTTP 状态码恒等**，没有 `errorCode` 字段 |
| ⑤ | 请求体字段是 **`content`**，成功响应字段是 **`reply`**（不是 `message` / `response`） |
| ⑥ | 创建会话的渠道是 **`web`**，会话 id 形如 `web:<userId>:<agent>`；`userId` 缺省 `default` |
| ⑦ | 归档会话保留历史、可查；发消息回 400，**不复活** |
| ⑧ | 一次性调用（`/invoke`）**不落 `sessions` 表**，但 `llm_calls` 照记（`session_id` 形如 `invoke-exec:<uuid>`） |
| ⑨ | `/health` 的 `providers` 表达的是「**配置就绪 / 未配置**」，不是「可达」——**不做真实网络探测**（裁决 ⑤）。本仓 `SUPPORTED_PROVIDERS = {deepseek}`，声明了凭证即 `UP` |
| ⑩ | 60 秒超时**只落异常类型与 504 映射**，`AgentTimeoutException` 本节**无抛出点**（裁决 ②，真链路抓不到，判卷在 `GlobalExceptionHandlerTest`） |
| ⑪ | 本机**没有 `sqlite3` CLI**，查库用 `python3 -c "import sqlite3..."` |
| ⑫ | 管理台静态资源由 `frontend-maven-plugin` 在 `generate-resources` 构建，**`-Dfrontend.skip=true` 打出的 jar 不含管理台** |

---

## Step 0：构建（必须 `clean`）

```bash
cd /home/wngbob/oryxos-wngbob
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
JAR=/home/wngbob/oryxos-wngbob/oryxos-boot/target/oryxos.jar
./mvnw clean package -Ddependency-check.skip=true

ls -la "$JAR"
unzip -l "$JAR" | grep "oryxos-web-0.1"        # 嵌套 jar 必须与模块产物同尺寸
ls -la oryxos-web/target/oryxos-web-0.1.0-SNAPSHOT.jar
unzip -p "$JAR" "BOOT-INF/lib/oryxos-web-*.jar" > /tmp/l26-web.jar
unzip -l /tmp/l26-web.jar | grep -c "static/admin/"     # 预期 ≥ 3
```

**预期**：构建 `BUILD SUCCESS`；两条 `ls` 的字节数**一致**（不一致就是踩了上面「实测记录 ③」的坑，回 Step 0 重来）；`static/admin/` 命中至少 3 行（`index.html` + 两个 assets）。

> ⚠️ **别用 `./mvnw package`（不带 `clean`）**：`maven-jar-plugin` 会在「本模块 classes 没变」时跳过重打 jar，`spring-boot:repackage` 于是把**旧嵌套 jar** 再包一遍，`BUILD SUCCESS` 但产物是旧的。

---

## Step 0.5：初始化工作区（`serve` 的前置条件，MUST）

`serve` **不建表、也不建 `agents/`**——`sessions` 等五张表由 `oryxos init` 跑 `schema.sql` 建立。**在仓库根目录跑一次**（幂等，已存在的文件一律不覆盖；**唯一例外是库结构**：旧表缺的列会被加性补上，见下）：

```bash
cd /home/wngbob/oryxos-wngbob
java -jar "$JAR" init

# 主工作区的测试 Agent（Step 3/4/6 都用它）；已存在会报错不覆盖，故先判目录
[ -d .oryxos/agents/smoke ] || java -jar "$JAR" profile create smoke

python3 - <<'PY'
import sqlite3
db = sqlite3.connect('.oryxos/oryxos.db')
need = {'sessions', 'tool_invocations', 'llm_calls', 'notify_channels', 'memory_entries'}
have = {r[0] for r in db.execute("select name from sqlite_master where type='table'")}
print('缺失表:', (need - have) or '无')
# 列由 init 的漂移补齐负责（2026-10-20 起），这里只是复核
for table, cols in {'llm_calls': {'success', 'error_message'}}.items():
    if table in have:
        got = {r[1] for r in db.execute(f'pragma table_info({table})')}
        print(f'{table} 缺失列:', (cols - got) or '无')
PY
```

**预期**：`init` 打出 `工作区: .../.oryxos`、新建/保留清单，以及 **`数据库表结构:`** 一段——旧工作区会列出 `补列: llm_calls.success BOOLEAN` 与 `补列: llm_calls.error_message TEXT`，新库则是 `与 db/schema.sql 一致（无需补列）`；`profile create smoke` 打出创建路径（若 `smoke` 已在，这行不执行）；最后两行 **`缺失表: 无`**、**`llm_calls 缺失列: 无`**。

> **旧工作区的列由 `init` 自动补**（2026-10-20 起）：`schema.sql` 的 `CREATE TABLE IF NOT EXISTS` 进不了已存在的表，所以 `init` 会先把脚本跑进一个内存库、读出「应有形状」，再对旧表做 `ALTER TABLE ADD COLUMN`。**只增不删、只补不改**：多出来的列不碰，类型不一致只打印、不修改；缺**主键列**则直接报错（SQLite 加不了主键，需手工迁移）。无默认值的 `NOT NULL` 列按可空补，输出里会写明「NOT NULL 约束未加」——这是 SQLite 的硬限制，不是漏配。
>
> **只有旧 jar 才需要手工补列**：如果你手上的 `oryxos.jar` 构建于 2026-10-20 之前，`init` 不补列，缺列的后果是**发消息 500**（不是 503、也不是 400），日志为 `table llm_calls has no column named error_message`。重新 `clean package` 最省事；确要用旧 jar，则先停 `serve` 再手工补：
>
> ```bash
> python3 - <<'PY'
> import sqlite3
> c = sqlite3.connect('.oryxos/oryxos.db')
> have = {r[1] for r in c.execute('pragma table_info(llm_calls)')}
> for col, typ in (('success', 'BOOLEAN'), ('error_message', 'TEXT')):
>     if col not in have:
>         c.execute(f'alter table llm_calls add column {col} {typ}')
> c.commit()
> print([r[1] for r in c.execute('pragma table_info(llm_calls)')])
> PY
> ```
>
> 不想迁移的话，也可以 `mv .oryxos/oryxos.db .oryxos/oryxos.db.bak && java -jar "$JAR" init` 现造一个新库（旧审计数据留在备份里）。

> **没有 `smoke` 这个 Agent 的后果**：Step 3 的 `POST /sessions` 回 `404 agent not found: smoke`，Step 4 的 `/invoke` 同理。

> ⚠️ 跳过这一步的后果是**静默的**：`serve` 照样起得来，`/health` 照样报 `db:"UP"`（探活只做 `Connection.isValid(1)`、不看表），但 `GET /api/v1/info` 会 **500**，日志里是 `no such table: sessions`——`info()` 要调 `sessionService.countActive()`。**「服务起来了」不等于「工作区初始化了」**（2026-10-20 用户实跑命中，见「实测记录」）。

---

## Step 1：起服务

```bash
export DEEPSEEK_API_KEY=<你的 key>
java -jar "$JAR" serve --port 8080
```

**预期**：

- `Tomcat started on port 8080 (http) with context path '/'`；
- `定时任务注册完成：共 N 条`（第 25 节的钟推仍在，N = 你工作区里声明了 `schedules` 的 Agent 数）；
- 启动过程中**没有第二个 key 被索要**（FR-007）；
- 日志走 **stderr**、命令输出走 stdout（第 20 节的流分离约定未被破坏）。

**另开一个终端**做下面的验证，并重新导出（`JAR` 只在上面那个终端里设过）：

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
JAR=/home/wngbob/oryxos-wngbob/oryxos-boot/target/oryxos.jar
echo "$JAR"; ls -la "$JAR"
```

> ⚠️ 如果后面报 `Error: Unable to access jarfile`，末尾跟一个空字符串——那是 `$JAR` **在新终端里为空**，回这一段重设，**不要重新构建**。

---

## Step 2：状态与查询端点（不依赖模型）

```bash
curl -s localhost:8080/api/v1/health | python3 -m json.tool
curl -s localhost:8080/api/v1/info   | python3 -m json.tool
curl -s localhost:8080/api/v1/profiles | python3 -m json.tool
curl -s localhost:8080/api/v1/tools  | python3 -m json.tool
curl -s localhost:8080/api/v1/memory | python3 -m json.tool
```

**判据**：

1. 五条都 `code:200`、都是四字段信封；
2. `/health` → `status:"UP"`、`db:"UP"`、`providers` 里 `deepseek:"UP"`；
3. `/info` → `registeredTools` = **15**（第 22 节后的内置工具数，几轮补强后以实际输出为准）；
4. `/profiles` 每项含 `name/description/provider{name,model}/tools/schedules/valid/error`；写坏的 Agent 应出现且 `valid:false` + `error` 非空；
5. `/tools` 15 项、`source` 为 `builtin`（没有 MCP server 时）；
6. `/memory` → `{core, archival, truncated}`，`core` 与 `.oryxos/memory/MEMORY.md` 的核心段一致。

> ⚠️ **只有 `/info` 500、其余四条 200**：工作区没初始化（`sessions` 表不存在），回 Step 0.5 跑 `oryxos init` 后**重启 `serve`**（`serve` 启动时不会补表）。其余四条恰好都不碰 `sessions` 表，所以这个缺口只在 `/info` 上暴露。

---

## Step 3：会话全流程（依赖真模型）

> **重跑须知（重要）**：会话标识 = `web:<userId>:smoke`，同一 `(profile, userId)` **重复创建会取回同一个会话**（契约 §1.1 的「创建（或取回）」语义），而 3.4 会把它归档、归档**不复活**（3.5 那条 400 正是要验的）。所以**第二次跑 Step 3 必须换一个 `userId`**（例如 `alice2`），并把下面所有 `web:alice:smoke` 一并替换；否则 3.1 会返回旧会话（`status:"archived"`、`archivedAt` 非空），3.2 直接 400 `session is archived`。这不是产品缺陷，是重跑撞上了上一次的归档态。

```bash
# 3.1 建会话
curl -s -X POST localhost:8080/api/v1/sessions \
  -H 'Content-Type: application/json' \
  -d '{"profile":"smoke","userId":"alice"}' | python3 -m json.tool

# 3.2 发消息（把上一步的 sessionId 填进来）
curl -s -X POST localhost:8080/api/v1/sessions/web:alice:smoke/messages \
  -H 'Content-Type: application/json' \
  -d '{"content":"你好，用一句话介绍你自己"}' | python3 -m json.tool

# 3.3 查历史
curl -s localhost:8080/api/v1/sessions/web:alice:smoke | python3 -m json.tool

# 3.4 归档两次（验幂等）
curl -s -X DELETE localhost:8080/api/v1/sessions/web:alice:smoke | python3 -m json.tool
curl -s -X DELETE localhost:8080/api/v1/sessions/web:alice:smoke | python3 -m json.tool

# 3.5 归档后再发
curl -s -w '\nHTTP %{http_code}\n' -X POST localhost:8080/api/v1/sessions/web:alice:smoke/messages \
  -H 'Content-Type: application/json' -d '{"content":"还在吗"}'
```

**判据**：

1. 3.1 → `sessionId = web:alice:smoke`、`channel:"web"`、`status:"active"`（`messages` 为 `null`）；
2. 3.2 → `data.reply` 是**真模型**的答复（不是模板、不是空串）；
3. 3.3 → `messages` 至少 2 条（user / assistant），按时间正序；
4. 3.4 两次 → `archivedAt` **完全相同**（幂等的判据是库里那一列，不是本次请求的 now）；
5. 3.5 → **HTTP 400** + `session is archived: web:alice:smoke`，且 3.3 再查历史仍在。

> **3.2 若回 404 `session not found`**：说明手上的 jar 是**修之前**的版本（实测记录 ②），回 Step 0 用 `clean package` 重打。

---

## Step 4：一次性调用（依赖真模型）

```bash
curl -s -X POST localhost:8080/api/v1/agents/smoke/invoke \
  -H 'Content-Type: application/json' \
  -d '{"content":"1+1 等于几？只回数字"}' | python3 -m json.tool

python3 - <<'PY'
import sqlite3
c = sqlite3.connect('.oryxos/oryxos.db')
print("invoke-exec rows:",
      c.execute("select count(*) from sessions where session_id like 'invoke-exec:%'").fetchone()[0])
print("llm_calls from invoke:",
      c.execute("select count(*) from llm_calls where session_id like 'invoke-exec:%'").fetchone()[0])
PY
```

**判据**：`data.agent:"smoke"`、`data.reply` 是真答复（如 `2`）；`sessions` 里 `invoke-exec:%` **0 行**；`llm_calls` 对应行数 **≥ 1**（不落会话 ≠ 不落审计）。

---

## Step 5：错误码映射（5.1~5.5 不依赖模型；5.6 走真调用）

```bash
curl -s -w ' HTTP %{http_code}\n' localhost:8080/api/v1/sessions/no-such-session
curl -s -w ' HTTP %{http_code}\n' -X POST localhost:8080/api/v1/agents/no-such-agent/invoke \
  -H 'Content-Type: application/json' -d '{"content":"hi"}'
curl -s -w ' HTTP %{http_code}\n' -X POST localhost:8080/api/v1/sessions -H 'Content-Type: application/json' -d '{"profile":"ghost"}'
python3 -c "print('{\"content\":\"' + 'x'*33000 + '\"}')" > /tmp/big.json
curl -s -w ' HTTP %{http_code}\n' -X POST localhost:8080/api/v1/sessions/web:alice:smoke/messages \
  -H 'Content-Type: application/json' --data @/tmp/big.json
curl -s -w ' HTTP %{http_code}\n' -X POST localhost:8080/api/v1/sessions/web:alice:smoke/messages \
  -H 'Content-Type: application/json' -d '{}'
```

**判据**：依次 `404 session not found: ...` / `404 agent not found: ...` / `404 agent not found: ghost` / `400 message exceeds 32KB limit` / `400 content is required`；**每条都是四字段信封、`code` 与 HTTP 状态码一致**；32KB 那条**零模型调用**（服务端日志里不应出现新的 `LLM 调用`）。

### 5.6 断掉 Provider → 503（真链路，需另起一个服务）

> **用「无效 key」而不是「删掉 key」**：`application.yaml` 里 `api-key` 是 `${DEEPSEEK_API_KEY}`（**无默认值**），key 缺失时 Spring 启动直接失败，根本到不了 503；要验的是「调了、但对端拒绝」。
>
> **起在独立工作区**（`/tmp/l26-503`）：第二个 `serve` 若与主服务共用 `.oryxos/`，两个进程会写同一个 SQLite 库，查审计时容易和主服务的行混在一起。

```bash
# 换终端 / 新 shell 后 $JAR 是空的——先设再验证（否则报 Error: Unable to access jarfile + 空字符串）
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
JAR=/home/wngbob/oryxos-wngbob/oryxos-boot/target/oryxos.jar
echo "$JAR"; ls -la "$JAR"

mkdir -p /tmp/l26-503 && cd /tmp/l26-503
java -jar "$JAR" init
java -jar "$JAR" profile create smoke

# key 存在但无效；端口用 18081，避免和主服务冲突
DEEPSEEK_API_KEY=sk-invalid-for-503-test java -jar "$JAR" serve --port 18081
```

**另开一个终端**（只用 `curl` 与 `python3`，不需要 `$JAR`）：

```bash
curl -s -X POST localhost:18081/api/v1/sessions \
  -H 'Content-Type: application/json' -d '{"profile":"smoke","userId":"bob"}' | python3 -m json.tool

curl -s --max-time 90 -w '\nHTTP %{http_code}\n' \
  -X POST localhost:18081/api/v1/sessions/web:bob:smoke/messages \
  -H 'Content-Type: application/json' -d '{"content":"hi"}'

cd /tmp/l26-503 && python3 - <<'PY'
import sqlite3
c = sqlite3.connect('.oryxos/oryxos.db')
for row in c.execute(
    "select session_id, provider, model, success, substr(error_message,1,60)"
    " from llm_calls order by id desc limit 3"):
    print(row)
PY
```

**判据**：

1. 发消息 → **HTTP 503**，响应体是四字段信封：`{"code":503,"message":"provider unavailable: deepseek","data":null,...}`；
2. **响应体里不含对端的报错原文**（如 `401 ... Authentication Fails`）——那句只进日志与 `llm_calls`（FR-004 不泄漏）；
3. `llm_calls` 里对应会话有一行 **`success=0`**（`error_message` 含 401 原文）——「失败也要落审计」（章程原则 6）；
4. 这一刻 `curl -s localhost:18081/api/v1/health` **仍报 `deepseek:"UP"`**——`/health` 只表达「配置就绪 / 未配置」，**不做真实网络探测**（裁决 ⑤，前置事实 ⑨）；
5. 验完 `Ctrl+C` 停掉第二个服务，回主服务终端继续。

> 若对端因网络不通而不是 401 失败，仍是 `503 provider unavailable: deepseek`（`ProviderService` 把该次调用的任何 `RuntimeException` 都包装成同一语义）；判据 3 的 `error_message` 会换成网络错误原文——**两条都算通过**。

---

## Step 6：CLI 与 HTTP 共用同一份存储（依赖真模型）

```bash
printf '你好\n/quit\n' | java -jar "$JAR" chat --profile smoke
curl -s localhost:8080/api/v1/sessions/cli:local:smoke | python3 -m json.tool
```

**判据**：CLI 那一轮的消息出现在 API 查到的历史里（渠道段 `cli`、用户段 `local`），说明两个推入口共享同一个 `SessionService`、同一张 `sessions` 表。

---

## Step 7：管理台（浏览器）

打开 `http://localhost:8080/admin/`，**先落在概览页**，再依次点左侧其余六项：**会话 / Agent / 工具 / 定时任务 / 长期记忆 / 运行状态**。

**判据**：

1. 打开 `/admin/` 直接是**概览页**（导航第一项），展示**运行时状态总览**：Agent 数（可用 / 共）、活跃会话数、已注册工具数与来源构成、运行时长与版本、健康三项（整体 / 数据库 / Provider 配置就绪）、Agent 清单紧凑版；这些数字应与 `curl /api/v1/info`、`/health`、`/profiles`、`/tools` 的返回一致（**不是**产品介绍页——「OryxOS 是什么」在官网上）；
2. 其余六个页面都能打开、**渲染的是真实数据**（Agent 页能看见你的 Agent；工具页 15 条；状态页 UP；记忆页与 `/memory` 一致）；**定时任务页**列出各 Agent 在 `AGENT.md` 里声明的 cron 任务（任务 id `Agent#序号`、cron、时区、消息），条数与 `/api/v1/profiles` 里 `schedules` 的条数一致，没有声明时是**空态**而不是白屏；
3. 会话页是**按会话 ID 查询**（**没有**会话列表端点，也没有「全部会话」这种暗示不存在的功能的入口）；把 Step 3 的 `web:alice:smoke` 粘进去能查到；
4. 直接访问子路由（如 `http://localhost:8080/admin/tools`、`http://localhost:8080/admin/overview` 回车刷新）**不 404**；
5. 页面上**没有任何新建 / 编辑 / 删除 / 保存 / 提交**按钮或字样；
6. 与官网首页**同一套外观**：默认浅色（白卡片、`--ox-bg-soft` 页底、琥珀色只做点缀），系统偏好深色时自动切到官网深色档——`<html>` **不带**深色主题类；令牌逐字取自 `website/.vitepress/theme/custom.css`（`AdminThemeSourceTest` 判卷）；
7. **侧栏左上角是官网同一枚 oryx 徽标**（不是文字或色块），概览页 hero 右侧是同一张图，浏览器标签页图标也是它（`AdminThemeSourceTest.adminLogoMirrorsSiteIcon` 判逐字节一致）；
8. 出错时显示**错误态**而不是空列表（如把服务停了再点刷新）。

> **2026-10-20 修订注（同日三次）**：上一版这一节是「依次点左侧五项、`/admin/` 落在会话页」。① 先加**概览页**并置为第一项（落地页 `/admin/overview`）；② 随后按用户澄清把概览页从「静态介绍」改成**运行时状态总览**，判据相应从「它不发请求」改为「它的数字与四个查询端点一致」；③ 当日再加**定时任务页**（导航第 5 项，判据见上面第 2 条），导航现为**七项**。用户同日要求的「渠道列表」页**本轮不做**——`notify_channels` 没有查询端点，加它要新造 REST 路径，经用户裁决改挂扩展阶段。新增第 7 条徽标判据。**本节尚未按新版人工走一遍**——用户 2026-10-20 执行 Step 0~9 是在概览页存在之前，留痕见 `acceptance-report.md` §四 第 6 项。

---

## Step 8：交互式文档（浏览器）

打开 `http://localhost:8080/swagger-ui`（会自动跳到 `/swagger-ui/index.html`）。

**判据**：**10 个**操作全部列出（会话 4 / Agent 调用 1 / 信息查询 3 / 系统状态 2），分组清晰；随便挑一个 GET 点 `Try it out` 能返回真实数据。

---

## Step 9：收尾

```bash
# Ctrl+C 停掉 serve（若 5.6 起的第二个服务还在，也一并停掉）
rm -f /tmp/big.json /tmp/l26-web.jar
rm -rf /tmp/l26-503
```

---

## 已知边界（本节不验，如实记录）

| 项 | 为什么 |
| --- | --- |
| **60 秒超时 → 504 的真链路** | 裁决 ②：强制掐断会撞「全程同步阻塞」硬门禁，只落异常类型与映射；`AgentTimeoutException` **本节无抛出点**，判卷在 `GlobalExceptionHandlerTest` |
| **`/health` 的 `providers` 报 `"DOWN"`** | `SUPPORTED_PROVIDERS = {deepseek}`，声明了即凭证非空；`DOWN` 只在「支持但未声明」时出现，判卷在单测 |
| **500 响应体不含内部异常 message 的真链路** | 需要造一个真实的内部错误；判卷在 `GlobalExceptionHandlerTest`（mock 一个带敏感 message 的异常），真链路不必为它专门造故障 |
| **`mem0` 记忆档的 `/memory`** | 该档经第 22 节裁决**永久不验收**；本节 `/memory` 的实测只覆盖 `markdown` / `sqlite` 两档 |
| **并发压测** | 课件验收标准里提到「并发压一把」；本节 harness 未建压测脚本，`mvn clean verify` 不覆盖它。单次请求的正确性由 Step 3~5 覆盖 |

---

## 最容易踩的坑

| # | 现象 | 原因 / 处理 |
| --- | --- | --- |
| 1 | `Error: Unable to access jarfile` + 空字符串 | 新终端 / 新 shell 里 `$JAR` 没设；回 Step 1 的「另开终端」段或 Step 5.6 的开头 export 段重设 |
| 2 | `BUILD SUCCESS` 但改动没生效 | 没 `clean`；`maven-jar-plugin` 跳过重打 → fat JAR 里是旧嵌套 jar。用 Step 0 的两条 `ls` 对字节数 |
| 3 | `POST /sessions` 200，接着 `POST .../messages` 404 | 手上的 jar 早于 2026-10-19 的修复（创建未落库）；`clean package` 重打 |
| 4 | `/admin/` 404 而 `/admin/sessions` 正常 | 手上是修复前的 jar（`/admin/` 空资源路径不进解析链）；`clean package` 重打 |
| 5 | `/admin/` 打开是 JSON 的 404 信封 | 用了 `-Dfrontend.skip=true` 打的 jar（不含管理台静态资源）；重打 |
| 6 | `sqlite3: command not found` | 本机没有该 CLI；用 `python3` 的 `sqlite3` 模块（前置事实 ⑪） |
| 7 | 断网/无 key 时 `serve` 起不来 | 不是网络问题——检查 `DEEPSEEK_API_KEY` 是否 export 到**当前**终端 |
| 8 | `curl` 中文乱码 | `python3 -m json.tool` 默认转义非 ASCII，属正常；要看中文用 `python3 -c "import json,sys;print(json.dumps(json.load(sys.stdin),ensure_ascii=False,indent=2))"` |
| 9 | Step 5.6 里服务**起不来**，报 `Could not resolve placeholder 'DEEPSEEK_API_KEY'` | 你「删掉」了 key 而不是「填错」——删掉会让启动失败；要验 503 必须给一个**存在但无效**的值 |
| 10 | Step 5.6 查 `llm_calls` 时混进主服务的行 | 第二个 `serve` 和主服务用了**同一个工作区**；第二个服务要起在 `/tmp/l26-503` 这类独立目录 |
| 11 | `GET /api/v1/info` **500**（`Internal server error`），日志有 `no such table: sessions` | 工作区没初始化：`serve` 不建表，`oryxos init` 才建。回 Step 0.5 跑 `init` 并**重启** `serve`。`/health` 此时仍报 `db:"UP"`，别拿它当反证 |
| 12 | **重跑 Step 3**，3.1 返回 `status:"archived"`、3.2 回 400 `session is archived` | 上一次跑完 3.4 已把 `web:alice:smoke` 归档，而同一 `(profile,userId)` 重复创建是「取回同一个会话」（契约 §1.1）、归档不复活。换 `userId`（`alice2`）并替换所有 URL；不是 bug |
| 13 | 发消息回 **500**，日志 `table llm_calls has no column named error_message` | 旧工作区（第 16 节之前建的库）`llm_calls` 缺 `success` / `error_message` 两列。**2026-10-20 起跑一次 `init` 即自动补上**（输出 `补列: ...`）；旧 jar 才需跑 Step 0.5 的手工脚本或换新库。换成有效 key 也照样 500 |
