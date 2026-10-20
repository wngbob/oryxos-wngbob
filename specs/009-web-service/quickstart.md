# Quickstart: Web Service 与第一版管理平台（第 26 节）

**Feature**: `specs/009-web-service` | **Date**: 2026-10-19 | **Plan**: [plan.md](./plan.md)

本文件是**可执行的验证向导**——每步给命令与**预期结果**。契约细节见 [contracts/rest-endpoints.md](./contracts/rest-endpoints.md)，数据形状见 [data-model.md](./data-model.md)。真人工验收向导（含逐条判据与坑表）在节级收尾时单独立卷 `manual-test-guide.md`。

---

## 0. 前置准备

```bash
cd /home/wngbob/oryxos-wngbob
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
MVN=/home/wngbob/tools/apache-maven-3.9.16/bin/mvn

# 一次干净构建（含前端构建；首次会下载 Node v20.18.0，耗时较长）
$MVN clean package
ls -la oryxos-boot/target/oryxos.jar
```

**预期**：`oryxos.jar` 存在；`oryxos-web/src/main/resources/static/admin/index.html` 被构建出来；`target/` 下有 Node 发行包。

**快构建（跳过前端，只验 Java）**：

```bash
$MVN clean package -Dfrontend.skip=true
```

> ⚠️ 这样打出的 jar **不含管理台静态资源**（`static/admin/` 为空）——Step 8 的管理台验证必须用完整构建的 jar。单测不受影响：SPA 回落的判卷靠 `oryxos-web/src/test/resources/static/admin/index.html` **测试夹具**（只在测试 classpath 上，永不进发行物）。

---

## 1. 起服务

```bash
export DEEPSEEK_API_KEY=<你的 key>
java -jar oryxos-boot/target/oryxos.jar serve --port 8080
```

**预期**：

- 日志出现 `Tomcat started on port 8080` 一类的 servlet 容器启动行（**这是本节与第 25 节最直观的差别**——第 25 节的 `serve` 只起 Spring 上下文、不起 Web 容器）；
- 出现 `定时任务注册完成` 那一行（第 25 节的注册仍在 `serve` 里，未被本节破坏）；
- **只靠 `DEEPSEEK_API_KEY` 一个环境变量就起得来**（FR-010，research D5）——没有第二个 key 被索要。

**另开一个终端**做下面的验证（`$JAR` / 环境变量在新终端里要重设）。

---

## 2. 系统状态端点（不依赖模型，先验这条）

```bash
curl -s localhost:8080/api/v1/health | python3 -m json.tool
curl -s localhost:8080/api/v1/info   | python3 -m json.tool
```

**预期**：

- `/health` → `{"code":200,"message":"ok","data":{"status":"UP","db":"UP","providers":{"deepseek":"UP"}}}`；
- `/info` → `data` 里有 `name` / `version` / `javaVersion` / `agents` / `activeSessions` / `registeredTools` / `uptimeSeconds`，且 `registeredTools` = **15**（第 22 节后的内置工具数）。

## 3. 查询端点（不依赖模型）

```bash
curl -s localhost:8080/api/v1/profiles | python3 -m json.tool
curl -s localhost:8080/api/v1/tools    | python3 -m json.tool
curl -s localhost:8080/api/v1/memory   | python3 -m json.tool
```

**预期**：

- `/profiles` → 数组，每项含 `name` / `description` / `provider{name,model}` / `tools` / `schedules` / `valid` / `error`；
- `/tools` → 数组，含 `inputSchema` / `source`（`builtin` / `annotation` / `mcp:<server>`）/ `origin`；
- `/memory` → `{"core":"...","archival":"...","truncated":false}`，且 `core` 与 `.oryxos/memory/MEMORY.md` 的「## 核心记忆」段一致。

## 4. 会话全流程（依赖模型）

```bash
# 4.1 建会话
curl -s -X POST localhost:8080/api/v1/sessions \
  -H 'Content-Type: application/json' \
  -d '{"profile":"<你的 Agent 名>","userId":"alice"}' | python3 -m json.tool

# 4.2 发消息（把上一步的 sessionId 填进来）
curl -s -X POST localhost:8080/api/v1/sessions/<sessionId>/messages \
  -H 'Content-Type: application/json' \
  -d '{"content":"你好，用一句话介绍你自己"}' | python3 -m json.tool

# 4.3 查历史
curl -s localhost:8080/api/v1/sessions/<sessionId> | python3 -m json.tool

# 4.4 归档（连做两次，验幂等）
curl -s -X DELETE localhost:8080/api/v1/sessions/<sessionId> | python3 -m json.tool
curl -s -X DELETE localhost:8080/api/v1/sessions/<sessionId> | python3 -m json.tool
```

**预期**：

- 4.1 → `channel:"web"`、`status:"active"`、`sessionId` 形如 `web:alice:<agent>`；
- 4.2 → `data.reply` 是真模型答复；
- 4.3 → `messages` 里能看到刚发的消息与答复（**这与 4.2 的入参/出参字段名不同：请求 `content`、响应 `reply`**）；
- 4.4 → 两次返回**完全相同**（`status:"archived"`，`archivedAt` 两次一致）；
- 归档后再发消息 → `400` + `session is archived: <id>`，**不复活会话**。

## 5. 无状态调用（依赖模型）

```bash
curl -s -X POST localhost:8080/api/v1/agents/<agent名>/invoke \
  -H 'Content-Type: application/json' \
  -d '{"content":"1+1 等于几？只回数字"}' | python3 -m json.tool

# 确认没有落 sessions 表
sqlite3 .oryxos/oryxos.db "select count(*) from sessions where session_id like 'invoke-exec:%';"
```

**预期**：`data.reply` 有答复；**计数 = 0**（不创建持久会话）。

## 6. 错误码映射（不依赖模型）

```bash
# 404 会话不存在
curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/api/v1/sessions/no-such-session

# 404 Agent 不存在
curl -s -o /dev/null -w '%{http_code}\n' -X POST localhost:8080/api/v1/agents/no-such-agent/invoke \
  -H 'Content-Type: application/json' -d '{"content":"hi"}'

# 400 消息超 32KB
python3 -c "print('{\"content\":\"' + 'x'*33000 + '\"}')" > /tmp/big.json
curl -s -o /dev/null -w '%{http_code}\n' -X POST localhost:8080/api/v1/sessions/<sessionId>/messages \
  -H 'Content-Type: application/json' --data @/tmp/big.json

# 500 响应体不含内部异常 message（把某个内部错误触发后核这条）
```

**预期**：`404` / `404` / `400`，且每个响应体都是统一信封（`code` 与 HTTP 状态码一致）。

## 7. 跨入口共享存储（人推两条入口同源）

```bash
# 在服务运行期间，另一个终端用 CLI 聊一句
printf '你好\n/quit\n' | java -jar oryxos-boot/target/oryxos.jar chat --profile <agent名>

# 回到 API 侧查同一个会话（CLI 会话 id 的渠道段是 cli——CliChannel.CHANNEL="cli"、LOCAL_USER="local"）
curl -s localhost:8080/api/v1/sessions/cli:local:<agent名> | python3 -m json.tool
```

**预期**：CLI 侧的消息出现在 API 查到的历史里（两条入口共享同一份会话存储）。

## 8. 管理台（浏览器）

打开 `http://localhost:8080/admin/`。

**预期**：

- 打开即落在**概览页**（导航第一项，2026-10-20 起）——它是**运行时状态总览**：Agent 数（可用 / 共）、活跃会话数、已注册工具数与来源构成、运行时长与版本、健康三项（整体 / 数据库 / Provider 配置就绪）、Agent 清单紧凑版；数字应与 `/api/v1/info`、`/api/v1/health`、`/api/v1/profiles`、`/api/v1/tools` 的返回一致；
- 其余六个页面（会话 / Agent / 工具 / **定时任务** / 长期记忆 / 运行状态）都能打开，**渲染真实数据**；
- **定时任务页**（`/admin/schedules`，2026-10-20 新增）列出各 Agent 在 `AGENT.md` 里声明的 cron 任务（任务 id `Agent#序号`、cron、时区、消息），条数与 `/api/v1/profiles` 里 `schedules` 的条数一致；一条都没声明时显示空态、不是白屏；
- 刷新任意子路由（如 `/admin/tools`、`/admin/overview` 直接回车）**不 404**（FR-016 回落生效）；
- 页面上**没有任何新建 / 编辑 / 删除按钮**；
- 侧栏左上角与浏览器标签页是**官网同一枚徽标**（`oryxos` 羚羊徽章，不是文字或色块）；
- 与官网首页同一套外观：默认浅色、琥珀色只做点缀（系统深色偏好下自动切深色档），令牌逐字取自 `website/.vitepress/theme/custom.css`。

> **2026-10-20 修订注（同日三次）**：上一版这里只写「五个页面」，且 `/admin/` 落在会话页。① 先新增概览页并置为第一项（落地页 `/admin/overview`）；② 随后按用户澄清把概览页从「静态产品介绍」改成**运行时状态总览**（读 `/info`、`/health`、`/profiles`、`/tools` 四个既有端点，未新增 REST 路径）；③ 当日再新增**定时任务页**（导航第 5 项，`/admin/schedules`，数据来自既有 `/api/v1/profiles` 的 `schedules`，同样未新增端点）——导航现为**七项**。用户同日要求的「渠道列表」页**本轮不做**（`notify_channels` 无查询端点，加它要新造 REST 路径，经用户裁决改挂扩展阶段）。判卷：概览页的「落地页 + 必须读数据」由 `AdminConsoleSourceTest.overviewPageIsLandingPage`，各页端点引用由 §3 映射表，徽标逐字节一致由 `AdminThemeSourceTest.adminLogoMirrorsSiteIcon`。

## 9. 交互式文档页（浏览器）

打开 `http://localhost:8080/swagger-ui`。

**预期**：10 个端点全部列出，分组清晰，可直接在页面上试调。

## 10. 自动化门禁

```bash
# 全量门禁（含 P3C / SpotBugs / FindSecBugs / PMD）
$MVN clean verify

# 只跑本节新增单测
$MVN -pl oryxos-web -am test

# 集成冒烟（默认被 excludedGroups 跳过，需显式放开）
$MVN -pl oryxos-cli -am test -DexcludedGroups=none -Dtest=WebSmokeIT -Dsurefire.failIfNoSpecifiedTests=false
```

**预期**：全绿；`WebSmokeIT` 起真实上下文（SERVLET）后四个查询端点可达——这是「Bean 装配与扫描范围没炸」的判卷。

---

## 已知边界（本节不验，如实记录）

| 项 | 为什么 |
|---|---|
| **60 秒超时 → 504 的真链路** | 裁决 ②：强制掐断会撞「全程同步阻塞」硬门禁，只落异常类型与映射；`AgentTimeoutException` 本节**无抛出点**，判卷在 `GlobalExceptionHandlerTest` |
| **`/health` 的 `providers` 报 `"DOWN"`** | 本仓 `SUPPORTED_PROVIDERS = {deepseek}`，声明了即凭证非空；`"DOWN"` 只在「支持但未声明」时出现，判卷在单测 |
| **`mem0` 记忆档的 `/memory`** | 该档经第 22 节裁决**永久不验收**（与真实 Mem0 服务从未对话过）；本节 `/memory` 的实测只覆盖 `markdown` / `sqlite` 两档 |
