# Web Service 与第一版管理台（第 26 节）验收报告

**Feature**: [spec.md](./spec.md) · [plan.md](./plan.md) · [tasks.md](./tasks.md) · [quickstart.md](./quickstart.md) · [data-model.md](./data-model.md) · [contracts/](./contracts/) · [人工向导](./manual-test-guide.md)

**分支**: `class-26` | **日期**: 2026-10-19（2026-10-20 四次交付后变更：`init` 漂移补齐、管理台外观改版、管理台 logo + 概览页、定时任务页，见 §三 ⑩/⑪/⑫/⑬；渠道列表页经用户裁决不做，仍挂扩展阶段） | **任务完成度**: 47/47

**结论**：第 26 节交付完成。`/api/v1` 六组 10 个端点、统一信封与异常出口、只读管理台、`/swagger-ui` 全部落地并通过全量门禁；**人工验收已通过（2026-10-20，未报告失败）**——判据清单见 §四，留痕见 §六。

---

## 一、六项证据 DoD

### 1. 全量门禁全绿

```bash
cd /home/wngbob/oryxos-wngbob
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
./mvnw clean verify -Ddependency-check.skip=true
```

**结果**：`BUILD SUCCESS`。surefire 汇总（由构建产物 `oryxos-*/target/surefire-reports/TEST-*.xml` 聚合）：

| 模块 | 测试数 |
| --- | --- |
| `oryxos-core` | 73 |
| `oryxos-provider` | 7 |
| `oryxos-storage` | 14 |
| `oryxos-memory` | 87 |
| `oryxos-tool` | 254 |
| `oryxos-cli` | 93（`WebSmokeIT` 默认按 `@Tag("integration")` 排除；2026-10-20 补列修复新增 8 条，原 85） |
| `oryxos-web` | 50（2026-10-20 管理台外观改造新增 `AdminThemeSourceTest` 3 条、logo + 概览页新增 2 条——`AdminThemeSourceTest` +1 / `AdminConsoleSourceTest` +1，原 45） |
| **合计** | **578，failures 0 / errors 0 / skipped 0**（交付时为 565；2026-10-20 的 `init` 漂移补齐新增 `SchemaDriftTest` 6 条 + `InitCommandTest` 2 条，管理台外观改造新增 3 条，logo + 概览页新增 2 条） |

集成冒烟单独跑（默认被 `excludedGroups=integration` 排除，故不在 578 内）：

```bash
./mvnw -pl oryxos-cli -am test -DexcludedGroups=none \
  -Dtest=WebSmokeIT -Dsurefire.failIfNoSpecifiedTests=false
```

**结果**：`WebSmokeIT` 4/4 绿（`/health`、`/info`、`/profiles`、`/tools` 真上下文可达）。

**静态门禁**（Spotless / P3C / Checkstyle / SpotBugs+FindSecBugs / OWASP）全部通过。**抓红并修复了一处**：
SpotBugs 首跑报 **11 条** bug —— 10 个 `SPRING_ENDPOINT`（6 个控制器里的 10 个 `@RequestMapping` 方法，FindSecBugs 对每个对外入口都报一条「值得人工复核」）+ 1 个 `EI_EXPOSE_REP2`（`ProfileApiController` 构造器持有注入的 `ProfileLoader`）。按父 pom 既定口径「**就地注解豁免比全局 exclude 更可审计**」，加 `@SuppressFBWarnings` 并写明理由，未改任何逻辑、未改排除清单。最终全书 **8 处**豁免：6 个控制器各一处 `SPRING_ENDPOINT`、`ProfileApiController` 构造器一处 `EI_EXPOSE_REP2`、`DatabaseHealthProbe` 构造器一处同形状的 `EI_EXPOSE_REP2`（后者属**预先**豁免——与前者同一形状，不等下次构建再冒红线）。**这是本节唯一的静态门禁妥协点**。（2026-10-20 交付后新增的 `SchemaDrift` 另有一处同类就地豁免，属交付后变更、见 §三 ⑩，不是本节交付物。）

### 2. 课件 harness 映射——每个测试类存在且非空

| 测试类 | 数量 | 守点（逐条对号） |
| --- | --- | --- |
| `SessionApiControllerTest` | 8 | 超 32KB → 400 且**零**模型调用；会话不存在 → 404；正常请求下 `AgentService.process` **恰好一次**（HTTP 层没夹带私货）；归档会话再发 → 400 且不复活；历史最多最近 100 条、按时间正序；归档幂等（两次 `archivedAt` 相同）；创建时 Agent 不存在 → 404；**创建当场落库**（`verify(sessionService).save(...)`，见 §三 缺陷②的回归判据） |
| `GlobalExceptionHandlerTest` | 9 | 每类异常 → 约定状态码；响应体都是统一信封；**500 响应体不含内部异常 message**；`ProviderUnavailableException` → 503 且对外话术只用厂商名 |
| `AgentApiControllerTest` | 4 | 一次性调用走 `processStateless`；Agent 不存在 → 404；空消息 → 400；响应 `{agent, reply}` |
| `ProfileApiControllerTest` | 2 | Profile 清单来自 `ProfileRegistry` 而非扫描；字段形状 |
| `MemoryApiControllerTest` | 3 | `{core, archival, truncated}` 三字段直出记忆门面的分区视图 |
| `ToolApiControllerTest` | 1 | 工具清单来自注册表、按来源标注 |
| `SystemApiControllerTest` | 3 | `/health` 的 `providers` 只表达「配置就绪」（裁决 ⑤）；`/info` 的计数 |
| `WebConfigTest` | 9 | `/admin/` 转发；SPA 回落；`assets` 长缓存；`assets` 未命中真 404；`/api` 不参与回落；CORS |
| `AdminConsoleSourceTest` | 7 | **源码级只读纪律**：七个页面只发 GET、无写操作控件/文案；`/api/v1` 路径白名单。2026-10-20 的两条概览判据（`overviewPageIsLandingPage`）：概览页排在其余**六**页之前、`App.vue` 已注册它、且它**必须经 `api.js` 读运行时数据**（同日用户澄清：它是系统状态总览，不是产品介绍页；`schedules` 页加入后判据由五页改为六页）；端点引用由 `VIEW_ENDPOINTS` 按契约 §3 映射表判 |
| `AdminThemeSourceTest`（2026-10-20 新增） | 4 | **与官网同源**：`index.html` 不含深色主题类；浅色 `:root` 与 `website/.vitepress/theme/custom.css` 的 `:root` 逐字相同；深色档与官网 `.dark` 逐字相同；`src/assets/logo-icon.svg` 与 `website/public/images/logo-icon.svg` **逐字节相同** |
| `WebSmokeIT`（`oryxos-cli`，`@Tag("integration")`） | 4 | 真上下文下 4 个查询端点真实链路可达（Bean 装配与扫描范围没炸） |

课件点名「写出代码的关键回归测试」全部原样落地；中文判据进 `@DisplayName`，方法名英文。

### 3. 「本节交付物」存在性核对

| 交付物 | 位置 | 核对 |
| --- | --- | --- |
| 6 个 `ApiController` | `oryxos-web/.../web/controller/` | 6 个文件：Session / Agent / Profile / Memory / Tool / System |
| 传输层 DTO | `oryxos-web/.../controller/dto/` | 13 个 record |
| 统一异常出口 | `oryxos-web/.../web/api/GlobalExceptionHandler.java` | 既有类扩映射（400/404/503/504/500 兜底） |
| 领域异常 | `oryxos-web/.../web/error/` | `SessionNotFoundException` / `ProfileNotFoundException` / `AgentTimeoutException` |
| Provider 故障异常 | `oryxos-provider/.../ProviderUnavailableException.java` | 新增 |
| 健康探针 | `oryxos-web/.../web/health/DatabaseHealthProbe.java` | 新增 |
| SPA 回落 / 缓存 / CORS | `oryxos-web/.../web/config/WebConfig.java` | 新增 |
| 管理台前端 | `oryxos-web/src/main/frontend/` | 5 个查询视图 + 2026-10-20 新增的**运行时状态总览** `views/OverviewView.vue`（读 4 个既有端点）与**定时任务页** `views/SchedulesView.vue`（读既有 `/profiles` 的 `schedules`）；`router.js` / `api.js` / `tokens.css` / `vite.config.js` / `package.json` / `src/assets/logo-icon.svg` |
| 管理台构建串联 | `oryxos-web/pom.xml` | `frontend-maven-plugin`（`generate-resources`：install-node → npm install → npm run build） |
| 记忆分区视图 | `oryxos-memory/.../store/MemoryPartition.java` + `MemoryService.partition()` | 新增（软门禁 ① 已报批） |
| 扫描根与依赖边 | `oryxos-cli`（`CliApplication` / `pom.xml`）、`ServeCommand` | `io.oryxlabs.oryxos.web` 入扫描根；`serve` 起 `WebApplicationType.SERVLET` |
| 删除 `OryxosApplication` | `oryxos-boot/...` | 类文件已删（裁决 ③）；`grep -rn OryxosApplication oryxos-*/src` 只剩 **3 处 javadoc 引用**（`CliApplication` 里讲「为什么不再复用那个启动类」的历史说明），无任何代码引用 |
| 设计规范 skill | `.claude/skills/oryxos-admin-ui/SKILL.md` | 已扩充为可复用的管理台设计规范（FR-010）；2026-10-20 按用户裁决改为「默认浅色、跟随系统深色偏好、琥珀色点缀」，并写明令牌逐字取自官网；同日补 logo 来源与「概览页是运行时状态总览（读既有四端点、不新增端点）」两条约定、导航改为六项；当日再补定时任务页后**导航现为七项** |

### 4. 前序节全部测试回归绿

578 条里包含前序各节全部测试，无一条红（交付时 565 条；2026-10-20 的 `init` 漂移补齐新增 8 条、管理台外观改造新增 3 条、logo + 概览页新增 2 条）。本节触碰的前序测试只有四个文件，均为**纯新增**、无断言删改（外观改造只新增 `AdminThemeSourceTest`；logo + 概览页只在 `AdminThemeSourceTest` 与 `AdminConsoleSourceTest` 各加一条，既有断言一条未改）：

- `AgentServiceTest`：新增 3 条（`processStateless` 每次调用互相隔离 / 不调 `save` / 正常与异常路径都清 `ProfileContext`）
- `SessionServiceTest`：新增 3 条（`archive` 幂等 / 归档不存在的会话返回 false / `countActive` 随归档递减）
- `MemoryServiceImplTest` / `MemoryToolsTest`：**未加新用例**，只给两个测试替身补了 `partition()` 桩（`LongTermMemoryStore` 新增了这一个方法，替身不实现就编不过）

### 5. H4 六条全局不变量自查

| # | 不变量 | 核查命令 | 结果 |
| --- | --- | --- | --- |
| ① | 涉外 IO 首行过 `Sandbox.enforce` | `grep -rn "Sandbox" oryxos-web/src --include=*.java \| wc -l` | `0`——Web 层不发起文件/命令/HTTP 涉外 IO；工具调用仍由既有 `ToolExecutor` 路径过沙箱 |
| ② | LLM 成败落 `llm_calls`、工具成败落 `tool_invocations` | 真链路实测（向导实测记录） | `/invoke` 一次性调用：`sessions` 表 0 行、`llm_calls` 1 行——审计路径未被无状态入口绕开 |
| ③ | 无明文 key | `grep -rnE "sk-[A-Za-z0-9]{10,}" oryxos-web/src/main/java oryxos-cli/src/main/java \| wc -l` | `0`；凭证仍只经环境变量占位 |
| ④ | `session_id` 只在会话服务内拼接 | `grep -rn "SessionId.of\|invoke-exec:" oryxos-*/src/main/java` | 持久化标识唯一拼接点 `JpaSessionService:43`；`invoke-exec:` 是**永不落库**的审计标识，已在 `AgentService.processStateless` javadoc 写明不构成第二处拼接 |
| ⑤ | 无 Reactor / `CompletableFuture` / 自建线程池 | `grep -rnE "reactor\.\|CompletableFuture\|Executors\.\|new Thread\(" oryxos-web/src/main/java oryxos-core/src/main/java \| wc -l` | `0`；虚拟线程由 Spring Boot 配置开启，执行全程同步阻塞（FR-012） |
| ⑥ | 无 Spring AI 自动工具执行路径 | `grep -rn "internalToolExecutionEnabled" oryxos-*/src/main/java` | 仅 `ProviderService:127` 一处 `internalToolExecutionEnabled(false)` |

### 6. 节级收尾

剩余人工项见 §四；逐步骤操作见 [manual-test-guide.md](./manual-test-guide.md)。

---

## 二、变更总结

### 2.1 改动点（按模块）

**`oryxos-web`（本节主战场）**

- 新增 6 个 `ApiController` + 13 个 DTO + 3 个领域异常 + `DatabaseHealthProbe` + `WebConfig`：10 个端点的参数校验/包装/错误处理，业务逻辑全部委托核心层（FR-002）。
- 修改 `GlobalExceptionHandler`：补齐 400 / 404 / 503 / 504 映射与 500 兜底脱敏；对外话术只用常量或厂商名，内部异常只进日志。
- 新增管理台前端 `src/main/frontend/`（Vue 3 + Vite，5 个视图）与 `frontend-maven-plugin` 构建串联；产物写 `src/main/resources/static/admin/`（已 gitignore）。
- 新增 9 个测试类（45 条）；2026-10-20 外观改造另加 `AdminThemeSourceTest`（3 条），logo + 概览页再加 2 条，共 50 条。
- **2026-10-20 外观改造（用户裁决，见 §三 ⑪）**：重写 `src/styles/tokens.css`（浅色 `:root` 逐字取自官网，深色档走 `prefers-color-scheme`）；`index.html` 去掉深色主题类；改 `src/App.vue`（白底侧栏 + 三级页头）、`src/router.js`（页头眉标）、五个视图与 `SessionsView` / `ToolsView` 的控件类，统一到官网首页的卡片/按钮/标签/输入框语言。**没有新增路由、端点或数据来源。**
- **2026-10-20 logo + 概览页（用户请求，见 §三 ⑫）**：新增 `src/assets/logo-icon.svg`（逐字节取自 `website/public/images/logo-icon.svg`，侧栏与 favicon 共用）；`index.html` 的 favicon 改为同一张图（Vite 按 `/admin/` 重写并加内容哈希）；`App.vue` 侧栏品牌块换成 `img` + 品牌名 + 「管理台 · 只读」；新增 `src/views/OverviewView.vue` 并在 `router.js` 里排到**第一项**、成为 `/admin/` 的落地页。**同日按用户澄清把它从静态产品介绍改成运行时状态总览**：读 `GET /api/v1/info`（Agent 数 / 活跃会话数 / 已注册工具数 / 运行时长与版本）、`/health`（整体 / DB / Provider 配置就绪）、`/profiles`（Agent 清单紧凑版）、`/tools`（工具来源构成）四个**既有**端点，四个请求分开报错、打开时读一次不做轮询。**没有新增任何 REST 路径**（软门禁 ① 未触发），端点清单与契约 §3 一致。

**`oryxos-cli`**

- `CliApplication` 扫描根加 `io.oryxlabs.oryxos.web`；`pom.xml` 加 `oryxos-web` 依赖边（**推翻**第 18 节「MUST NOT 依赖 oryxos-web」的节内表述，已在 javadoc 挂 2026-10-19 修订注）。
- `ServeCommand` 改为 `WebApplicationType.SERVLET`，`--port` 经 `System.setProperty("server.port", ...)` 传入（Picocli 先于 Spring 执行）。
- 新增 `WebSmokeIT`（`@Tag("integration")`）。

**`oryxos-core`**

- `AgentService.processStateless(String, String)`：一次性调用复用一个 ReAct 循环、不落会话（裁决 ⑫）。
- `SessionService` 加 `archive(String)` / `countActive()`；`JpaSessionService` 实现；`Session` 加归档态与 `archivedAt`。
- 既有 `process` / 三个会话操作签名**一字未改**。

**`oryxos-storage`**

- `SessionEntity.channel` javadoc 把 HTTP 入口渠道字面量由 `api` 更正为 `web`（仅注释）。
- `SessionRepository` 加派生查询 `countByStatus(String)`。

**`oryxos-memory`**

- `MemoryService` 加只读 `partition()`，新增 `MemoryPartition` record；三档 store 各实现自己的分区视图（Markdown 按标题分区、SQLite 按 `scope` 查询、Mem0 按作用域返回）。**召回与写入语义零改动。**

**`oryxos-provider`**

- `ProviderService` 在既有「先落审计再上抛」之后把异常包成 `ProviderUnavailableException`，供 503 映射；审计顺序不变。

**`oryxos-boot`**

- 删除 `OryxosApplication`（裁决 ③）；`pom.xml` 主类注释同步更新（主类仍是 `OryxOsCli`）。

**文档 / 工程**

- `specs/000-runtime-kernel/contracts/rest-api.md` 补第 26 节偏离记录；`specs/009-web-service/spec.md` US4 加修订注（「会话列表」→「按会话 ID 查询」）；`CLAUDE.md` 当前状态/待办/新挂账；`website/{guide,en/guide}/quick-start.md` 增加 HTTP 接入说明；`.claude/skills/oryxos-admin-ui/SKILL.md` 扩为设计规范；`.gitignore` 加前端产物。

### 2.2 重点 review 清单（按风险排序）

1. **`/admin/` 必须走 view controller 转发，不能交给资源处理器** —— [WebConfig.java:47](../../oryxos-web/src/main/java/io/oryxlabs/oryxos/web/config/WebConfig.java#L47)。`/admin/` 命中 `/admin/**` 时剩余资源路径是空串，`ResourceHttpRequestHandler` 在空路径上直接返回 null、进不到解析链，SPA 回落就没机会执行。删掉这一行会让 `/admin/` 变 404（子路由反而正常，很难一眼看出）。
2. **创建会话必须当场落库** —— [SessionApiController.java:97](../../oryxos-web/src/main/java/io/oryxlabs/oryxos/web/controller/SessionApiController.java#L97)。`SessionService.getOrCreate` 对新三元组只造对象不写行；少了 `save`，「先建会话、再发消息」第二步必 404。回归判据在 [SessionApiControllerTest.java:209](../../oryxos-web/src/test/java/io/oryxlabs/oryxos/web/controller/SessionApiControllerTest.java#L209)。
3. **一次性调用不得落 `sessions` 表，但审计照记** —— [AgentService.java:88-107](../../oryxos-core/src/main/java/io/oryxlabs/oryxos/core/engine/AgentService.java#L88-L107)。`processStateless` 刻意不调 `save`，且仍走同一个 `ReActLoop`；另起一条链路会让两个入口的行为悄悄分叉。
4. **500 响应体只出常量话术** —— [GlobalExceptionHandler.java:98-103](../../oryxos-web/src/main/java/io/oryxlabs/oryxos/web/api/GlobalExceptionHandler.java#L98-L103)。`INTERNAL_ERROR_MESSAGE = "Internal server error"` 是已定字面量；把 `ex.getMessage()` 拼进去会泄漏连接串/堆栈（FR-004 硬约束）。
5. **8 处静态门禁就地豁免** —— 6 个控制器各一处 `SPRING_ENDPOINT`、`ProfileApiController` 与 `DatabaseHealthProbe` 各一处 `EI_EXPOSE_REP2`。复核条件是「这些类的职责仍是『对外入口 / 依赖注入』」；若将来控制器开始承载业务逻辑，这条豁免的理由要跟着复核。
6. **前端产物不入库、构建默认全量** —— [oryxos-web/pom.xml](../../oryxos-web/pom.xml)。`frontend.skip` 默认 `false`；`-Dfrontend.skip=true` 打出的 jar **不含管理台**，浏览器验证必须用完整构建。
7. **设计令牌与 logo 都是「逐字复制 + 机器比对」，不是运行时引用** —— [tokens.css](../../oryxos-web/src/main/frontend/src/styles/tokens.css)、[logo-icon.svg](../../oryxos-web/src/main/frontend/src/assets/logo-icon.svg)。管理台与官网页在浏览器里是两个独立产物，CSS 变量与静态资源都不可能运行时共享，故只能复制；一致性由 [AdminThemeSourceTest.java](../../oryxos-web/src/test/java/io/oryxlabs/oryxos/web/AdminThemeSourceTest.java) 直接读 `../website/` 下两个源文件逐字/逐字节比对兜住。**改官网颜色或换 logo 后必须同步改管理台那一份**，否则该测试红。深色档嵌在 `@media` 里会多一层缩进，测试已按行归一缩进，**不是放宽比较**。
8. **概览页是运行时状态总览，且不新增协议面** —— [OverviewView.vue](../../oryxos-web/src/main/frontend/src/views/OverviewView.vue)。它回答「底座此刻什么状况」（Agent 数、活跃会话数、已注册工具数与来源、运行时长、健康三项），**不回答「OryxOS 是什么」**——那是官网首页的职责。数字全部来自 `info` / `health` / `profiles` / `tools` 四个**既有**端点，**没有新增 REST 路径**；`AdminConsoleSourceTest.overviewPageIsLandingPage` 断言它排在其余**六**页之前、`App.vue` 已注册、且**必须经 `api.js` 读数据**（防止它退化回静态介绍页；六页含同日新增的定时任务页）。若将来要给它加新的数字，先确认那个数字来自既有端点；确实需要新端点时，先改契约 §3 的页面 ↔ 端点映射与 `rest-endpoints.md`，**不能顺手加个请求了事**。

### 2.3 如何验证

```bash
cd /home/wngbob/oryxos-wngbob
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64

# ① 全量门禁（唯一判据）
./mvnw clean verify -Ddependency-check.skip=true
# 预期：BUILD SUCCESS；surefire 合计 578，failures/errors/skipped 全 0

# ①b 库结构漂移补齐（2026-10-20 新增）
./mvnw -pl oryxos-cli -am test -Dtest=SchemaDriftTest,InitCommandTest
# 预期：Tests run: 8, Failures: 0 —— 补列 / 幂等 / 只增不删 / 类型只报不改 / 缺主键硬失败 / init 真接上

# ①c 管理台外观与 logo 与官网同源 + 概览页口径（2026-10-20 新增）
./mvnw -pl oryxos-web -am -Dfrontend.skip=true test \
  -Dtest=AdminThemeSourceTest,AdminConsoleSourceTest -Dsurefire.failIfNoSpecifiedTests=false
# 预期：Tests run: 11, Failures: 0 —— index.html 不挂深色类 / 浅色 :root 与深色档逐字等于
#       website/.vitepress/theme/custom.css / logo-icon.svg 与官网逐字节相同 /
#       概览页是落地页且经 api.js 读运行时数据（四个既有端点与契约 §3 一致）

# ② 只跑本节单测
./mvnw -pl oryxos-web -am test
# 预期：Tests run: 50, Failures: 0, Errors: 0

# ③ 关键回归单测（创建当场落库）
./mvnw -pl oryxos-web -am test -Dtest=SessionApiControllerTest
# 预期：Tests run: 8, Failures: 0（含 create_defaultsUserIdAndFixesChannel）

# ④ 集成冒烟（真上下文）
./mvnw -pl oryxos-cli -am test -DexcludedGroups=none \
  -Dtest=WebSmokeIT -Dsurefire.failIfNoSpecifiedTests=false
# 预期：Tests run: 4, Failures: 0, Errors: 0

# ⑤ 依赖方向：只有 oryxos-cli 与 oryxos-boot 引用 oryxos-web，没有反向边
grep -rln "oryxos-web" oryxos-*/pom.xml
# 预期：恰好 3 个文件 —— oryxos-web/pom.xml（它自己）、oryxos-cli/pom.xml、
#       oryxos-boot/pom.xml（聚合）。core / provider / storage / memory / tool / channel-cli 都不出现

# ⑥ 无状态入口不落会话（真链路）
#    按 manual-test-guide.md Step 4 跑完后：
python3 -c "import sqlite3;c=sqlite3.connect('.oryxos/oryxos.db');print(c.execute(\"select count(*) from sessions where session_id like 'invoke-exec:%'\").fetchone())"
# 预期：(0,)，而 llm_calls 有对应行
```

**剩余人工项**见 §四。

---

## 三、本节中途抓红并修复的记录

| # | 抓红 | 根因 | 修法 |
| --- | --- | --- | --- |
| ① | `WebConfigTest` 红：`/admin/` 打开 404 | `/admin/**` 交给 `ResourceHttpRequestHandler` 时剩余路径为空串，处理器直接返回 null，进不到 `SpaFallbackResolver` | `WebConfig` 加 view controller `forward:/admin/index.html`；测试拆成「断言 forward」与「断言转发目标内容」两条（MockMvc 不重新分发 forward） |
| ② | 真链路跑 quickstart：`POST /sessions` 200，紧接 `POST /sessions/{id}/messages` 404 | `getOrCreate` 对新三元组只造对象、不写行；`@WebMvcTest` 里 `SessionService` 是 mock，照不到 | `create` 里补 `sessionService.save(session)`；测试补 `verify(sessionService).save(created)` |
| ③ | 增量 `mvn package` 打出旧字节码 | `maven-jar-plugin` 判定本模块 classes 未变就跳过重打 jar（依赖变化不算输入），`spring-boot:repackage` 包进旧嵌套 jar | 改为 `clean package`；向导 Step 0 与 `CLAUDE.md` 均记录「BUILD SUCCESS ≠ 产物是新的」，判据是嵌套 jar 字节数与模块产物一致 |
| ④ | SpotBugs 11 个 bug | 10 × `SPRING_ENDPOINT` + 1 × `EI_EXPOSE_REP2` | 就地 `@SuppressFBWarnings` + 理由（见 §一.1） |

四处的共同教训：**「命令跑通了」不等于「我做的那件事发生了」** —— 200 的路由不等于完整路由、mock 绿不等于链路通、BUILD SUCCESS 不等于产物是新的。

### 交付后人工验收抓到的一处（2026-10-20，已修向导）

| # | 抓红 | 根因 | 修法 |
| --- | --- | --- | --- |
| ⑤ | 用户照向导 Step 2 实跑：`/health` 200，`GET /api/v1/info` **500**（`no such table: sessions`） | **向导没让人先初始化工作区**。`serve` 不建表（Hikari 只建空库文件），`oryxos init` 才跑 `schema.sql`；用户那份旧 `.oryxos/oryxos.db` 只有第 17 节的两张表。`info()` 调 `sessionService.countActive()` → SQL 报错；其余四条 Step 2 端点恰好不碰 `sessions` 表，故只有 `/info` 暴露 | 向导新增 **Step 0.5**（`oryxos init` + 查缺失表）、Step 2 加定位提示、坑表加第 11 行。作者侧已用用户旧库副本在 `/tmp/l26-repro` 复现（init 前 500、init 后 200） |
| ⑥ | 同一缺口的第二处：主工作区的测试 Agent `smoke` 从未让使用者创建 | 向导只在「写向导时的实测记录」里提过 `profile create smoke`，Step 3/4/6 直接引用它；空工作区照做会在 Step 3 得 `404 agent not found: smoke` | Step 0.5 一并补 `[ -d .oryxos/agents/smoke ] || profile create smoke`（已存在会报错不覆盖），并注明缺它的后果 |
| ⑦ | 重跑 Step 3：3.1 返回 `status:"archived"`，3.2 回 400 `session is archived` | 契约 §1.1 的 create 是「创建（或取回）」：同一 `(profile,userId)` 取回同一个会话；上一次 3.4 已归档且归档不复活。**产品行为正确**，是向导漏了「重跑须换 `userId`」 | Step 3 加「重跑须知」、坑表加第 12 行；不改进产品 |
| ⑧ | 用户发消息回 **500**，日志 `table llm_calls has no column named error_message` | 旧工作区（2026-10-10 建库、早于第 16 节）`llm_calls` 缺 `success` / `error_message` 两列；`oryxos init` 用的是 `CREATE TABLE IF NOT EXISTS`，**只建缺的表、不迁移已存在表的列**。审计写入失败 → `JpaSystemException` → 500；**有效 key 也一样** | 向导 Step 0.5 加列级自检 + 幂等 `ALTER TABLE` 补列脚本、坑表加第 13 行。作者侧用用户库副本实测：补列前 500，补列后变 **503** 且 `llm_calls` 落 `success=0` 行 |
| ⑨ | Step 5.6 报 `Error: Unable to access jarfile` + 空字符串 | 换终端后 `$JAR` 未设，而 Step 5.6 的命令块直接引用了它（第 25 节已出现过同一类） | Step 5.6 命令块开头补 `JAR=` 与 `echo`/`ls` 自查；坑表第 1 行补指向该段 |

**教训与前四处同类但多一层**：前四处是「命令跑通了 ≠ 事情发生了」，这一处是「**作者自己做过准备 ≠ 这步写在流程里了**」——向导的「环境来源」被写成了背景信息，而不是使用者要执行的步骤。

### 交付后产品变更：`oryxos init` 加 schema 漂移检测（2026-10-20，用户批准）

| # | 触发 | 交付 | 判据 |
| --- | --- | --- | --- |
| ⑩ | 人工验收 ⑧ 暴露的**产品缺口**：`init` 只有 `CREATE TABLE IF NOT EXISTS`，旧表缺列永远补不上，而输出看不出异常。向导的「手工 `ALTER TABLE`」只是把缺口推给使用者 | `WorkspaceDb.applySchemaDrift()` + 包级私有 `SchemaDrift`（`oryxos-cli`）；`init` 先补列、再跑建表脚本；输出新增「数据库表结构」段 | `SchemaDriftTest`（6）+ `InitCommandTest`（2）钉死：补列且旧行不动 / 幂等 / 新库不插手 / 类型不一致只报不改 / 多余列不碰 / 缺主键硬失败 / `init` 真接上。真链路：合成旧库 `init` 前发消息 500，`init` 后变 503（无效 key 的预期），且旧行 `provider=deepseek` 原样在 |

**边界与折中（如实记录）**：**只增不删、只补不改**——缺表仍交给建表脚本，类型不一致只打印不改（SQLite 转列类型只能重建表，属破坏性操作），库里多出来的列不碰；**缺主键列直接报错**（`ALTER TABLE` 加不了主键，猜补法等于制造半个主键）；**无默认值的 `NOT NULL` 列按可空补**并在输出里写明「NOT NULL 约束未加」——SQLite 无法给非空表补无默认值的非空列，这是硬限制。**新增一处静态门禁豁免**：`SchemaDrift.readColumns` 的 `PRAGMA` 表名拼串（`SQL_INJECTION_JDBC` / `SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE`），理由是 `PRAGMA` 不支持参数化标识符、表名来自 `sqlite_master` 且先过标识符白名单——与 `WorkspaceDb.runSchemaScript` 同一口径。

### 交付后产品变更：管理台外观改为与官网首页同源（2026-10-20，用户裁决）

| # | 触发 | 交付 | 判据 |
| --- | --- | --- | --- |
| ⑪ | 用户裁决：「不再要求『深色底 + 橙色强调』，整个风格请**严格跟项目主页**（`website/`）的风格保持一致」。原 FR-014 / 契约 §5 / `oryxos-admin-ui` skill 三处都写死了深色底 | `tokens.css` 重写为**浅色优先**：第一段 `:root` 逐字取自 `website/.vitepress/theme/custom.css`，深色档用 `@media (prefers-color-scheme: dark)` 应用官网 `.dark` 那一份（与 VitePress 默认「跟随系统」相同）；`index.html` 去掉深色主题类；`App.vue` / 五个视图 / `router.js` 换成官网首页的语言——眉标（`WHY` / `CAPABILITIES` 那种小号色块字）+ 标题 + 提示的三级页头、白底侧栏、卡片 14px 圆角与悬停描边、琥珀色只做点缀、`Inter` + `JetBrains Mono`。**管理台仍然只读**：端点、路由、只读纪律一行未改 | `AdminThemeSourceTest`（3）：「`index.html` 不含深色主题类」/「浅色 `:root` 与官网 `:root` **逐字相同**」/「深色档与官网 `.dark` **逐字相同**」——**官网改了管理台不跟会红**。全量 `mvn clean verify` 全绿，**576** 条 |

**兼容性说明（如实记录）**：

- **不是新增外观概念，是把已定外观换成另一个已定来源**：官网 `custom.css` 是本仓既有的首屏真相源（第 26 节 research D8 早已指定它为准），本次只是把「取官网的哪一档」从 `.dark` 改成「`index.html` 那种跟随系统的取法」。修订注以带日期形式挂在 `spec.md` FR-014、`contracts/admin-console.md` §5、`.claude/skills/oryxos-admin-ui/SKILL.md` 三处，**未重写原文**（本仓惯例）。
- **令牌仍是单向引用、不建第二真相源**：管理台只逐字复制，不反向影响官网；`AdminThemeSourceTest` 读的是 `../website/.vitepress/theme/custom.css`，把它钉成「改一边另一边必须跟」。
- **渲染面零改动**：没有改任何视图的数据来源、没有加路由、没有加端点；`AdminConsoleSourceTest`（只 GET / 全路径 `/api/v1` 下 / 五视图各引用自己的端点 / 无写操作字样）在改动后照常全绿。
- **人工验收与本次改动的先后关系**：§六 的 Step 7 是 **2026-10-20 在旧深色外观下**走的；本次改版后的视觉复核**已于同日由用户另行完成并确认通过**（默认浅色 / 系统深色偏好下切深色档 / 页面视觉正常），此前只到源码断言与前端构建层（产物里实测含 `prefers-color-scheme`、`eyebrow`，且不含 `class="dark"`）。见 §四 第 6 项。

### 交付后产品变更：管理台加 logo + 概览页（2026-10-20，用户请求）

| # | 触发 | 交付 | 判据 |
| --- | --- | --- | --- |
| ⑫ | 用户请求：「① 参考项目首页把 logo 加上；② 顶部增加一个 overview 页面，展示 OryxOS 的预览信息，**当前为静态数据，慢慢完善为动态数据**」；**同日澄清**：「OVERVIEW 页面不是讲『OryxOS 是什么』，这个在官网上已经说得很清楚，不用在这里重复。OVERVIEW 页面应该是整个系统状态的一个概览，包括：有多少个 agent，多少个 tool，多少个 session 等等」 | ① **logo**：`src/assets/logo-icon.svg` **逐字节**取自 `website/public/images/logo-icon.svg`（`cmp` 校验一致）；`App.vue` 侧栏品牌块改为 `img` + 品牌名 + 「管理台 · 只读」；`index.html` 的 favicon 换成同一张图（Vite 按 `base: '/admin/'` 重写为 `/admin/assets/logo-icon-<hash>.svg`）。② **概览页**：新增 `src/views/OverviewView.vue`，`router.js` 里排**第一项**并成为 `/admin/` 的落点（原来落 `/sessions`），`App.vue` 注册视图。初版是静态产品介绍（北极星公式 / 五大能力 / 架构 / 快速开始），**同日按用户澄清改为运行时状态总览**：四张计数卡（Agent 可用/共、活跃会话、已注册工具数与来源数、运行时长与版本）+ 健康卡（整体 / DB / Provider 配置就绪）+ 两栏（Agent 清单紧凑版、工具来源构成），读 `/api/v1/info`、`/health`、`/profiles`、`/tools` 四个**既有**端点；四个请求分开报错、打开时读一次不轮询；叙述性产品文案已移除。**没有新增任何 REST 路径**（软门禁 ① 未触发） | `AdminThemeSourceTest` +1：`adminLogoMirrorsSiteIcon`——**logo 与官网逐字节相同，换官网 logo 不跟会红**。`AdminConsoleSourceTest` +1：`overviewPageIsLandingPage`——概览页排在其余五页之前（⑬ 加定时任务页后为六页）、`App.vue` 已注册、且**必须经 `api.js` 读数据**；四个端点引用由 `VIEW_ENDPOINTS` 按契约 §3 判（同日澄清后由「断言不引入 `api.js`」反转为「断言必须引入 `api.js`」，与判据方向一致地改，不是放宽）。前端构建实测：`npm run build` 通过（构建产物 `index.html` 的 favicon 指向 `/admin/assets/logo-icon-CE7qF0FD.svg`）。`oryxos-web` 48 → **50**，全量 **576 → 578** |
| ⑬ | 用户请求：「左侧功能列表加上：定时任务，**渠道列表**」 | **定时任务页**：新增 `src/views/SchedulesView.vue`、`router.js` 加 `/schedules`（导航第 5 项）、`App.vue` 注册；数据源是**既有的** `GET /api/v1/profiles`——定时任务的定义源是 `AGENT.md` frontmatter 的 `schedules`，`ProfileView.schedules` 自交付起就带着它，故**没有新增 REST 路径**（软门禁 ① 未触发，端点总数仍是 10）。页面列出任务 id（`<Agent>#<序号>`，与 `AgentScheduler` 同源）、cron、时区、消息、Agent 配置是否有效，并明写「执行历史不在这里」（`scheduled_tasks` / `task_executions` 属第 28 节、本节无端点），不虚报运行态。**渠道列表页本轮不做（用户裁决）**：`notify_channels` 至今没有查询端点，加它必须新造 `GET /api/v1/notify-channels` 这一对外概念；选项与裁决已记录在契约 `contracts/admin-console.md` §3 的修订注里，届时还要一并落 CLI 同款脱敏纪律 | `AdminConsoleSourceTest` 的 `VIEW_ENDPOINTS` 增 `SchedulesView.vue → /api/v1/profiles`（测试条数不变，仍是 7 条；「各页引用自己的端点」「不出现写词」「只发 GET」三条对新增页同样生效）；`overviewPageIsLandingPage` 的先后判据把 `'/schedules'` 也纳入（概览须排在它之前） |

**兼容性说明（如实记录）**：

- **新增两页都只用既有端点**：契约 `contracts/admin-console.md` §3 的页面 ↔ 端点映射现为**七页**（概览 + 会话 / Agent / 工具 / 定时任务 / 长期记忆 / 运行状态），概览页登记为「`/info` + `/health` + `/profiles` + `/tools`」、定时任务页登记为「`/profiles` 的 `schedules`」，`AdminConsoleSourceTest.VIEW_ENDPOINTS` 与之一一对应。**没有新增 REST 路径**——用到的都是本节已交付的只读端点，故软门禁 ① 未触发。
- **只读纪律不变，且新页也被判卷**：两个新页都只有 GET 与只读展示，不出现「新建 / 删除 / 编辑 / 保存 / 提交」，也不出现写方法词——`onlyGetMethodIsUsed` / `noWriteEntryPointText` 两条对全部源码文件（含新页）同样生效，改动后全绿。
- **落地页变动是显式的**：`currentRoute` 初值与 `normalize()` 的兜底都指向 `ROUTES[0].path`，故 `/admin/` 现在直接进概览；旧书签 `/admin/sessions` 仍照旧可用。
- **logo 与令牌同一口径（逐字复制 + 机器比对）**：管理台与官网是两份独立产物，静态资源不可能运行时共享；`AdminThemeSourceTest` 直接读 `../website/public/images/logo-icon.svg` 比对，**改官网 logo 不跟会红**。
- **判据随需求同向修改，不是放宽**：初版判据是「概览页**不引入** `api.js`」（静态快照），澄清后反转为「**必须引入** `api.js` 且引用四个端点」——断言数量与强度均未减少，只是方向跟着用户裁决走；`AdminConsoleSourceTest` 仍是 7 条。
- **构建期抓红一处（如实记录）**：`mvn clean verify` 的 Spotless 判了 `AdminConsoleSourceTest` 的 javadoc 换行不合 google-java-format（手动折行的两行该合成一行）。按门禁口径 `./mvnw -pl oryxos-web spotless:apply` 修正后重跑全绿——**没有放宽规则，也没有删断言**。前端侧无告警（Vite 构建通过，产物里能 grep 到「定时任务」与 `/api/v1/profiles`）。
- **人工目视复核已于 2026-10-20 由用户完成并确认通过**：概览页计数卡与两栏版式、logo 在侧栏里的实际大小与留白均正常；此前只到源码断言、前端构建与哈希产物层。见 §四 第 6 项。

---

## 四、剩余人工项清单

harness 已判卷的部分不再重复；以下是**单测/源码断言判不了、必须人看**的项，操作步骤见 [manual-test-guide.md](./manual-test-guide.md)。**用户已于 2026-10-20 按向导 Step 0~9 全程执行、整体通过**（见 §六）；下表保留为判据清单，不是待办。

1. **管理台各页面在浏览器里渲染真实数据**（Step 7）：`/admin/` 打开后先落在**运行时概览页**（四张计数卡与健康三项应与同端口 `/api/v1/info`、`/health`、`/profiles`、`/tools` 的返回一致），再依次看会话（按会话 ID 查询）、Profile、工具、**定时任务**、长期记忆、运行状态六页；各页数据应与同端口 `/api/v1/*` 的返回一致，**定时任务页**的任务条数应与 `/profiles` 里 `schedules` 的条数一致。**2026-10-20 起导航是七项、概览在第一位且读四个既有端点**（见 §三 ⑫/⑬），§六 的 Step 7 是在**这两页存在之前**走的、两页本身未做人工目视复核。
2. **管理台无写操作入口**（Step 7）：逐页目视确认没有新建/编辑/删除/归档按钮。源码层由 `AdminConsoleSourceTest` 判，视觉层只能人看。
3. **刷新子路由不 404**（Step 7）：在 `/admin/tools` 等子路由上按 F5，页面应正常加载（curl 层已实测返回与 `/admin/` 逐字节相同的 index）。
4. **`/swagger-ui` 交互式文档齐全**（Step 8）：浏览器打开 `/swagger-ui`，应看到恰好 10 个 operation、六组分组正确（curl 层已实测 `/v3/api-docs` 恰好 10 个）。
5. **断掉 Provider 拿 503**（Step 5.6）：把 key 设为**无效值**（不能删——`${DEEPSEEK_API_KEY}` 无默认值，缺失会让启动失败）后发一条消息，应得 `503 {"code":503,"message":"provider unavailable: <name>"}`。**作者侧已于 2026-10-19 真链路实测**：`{"code":503,"message":"provider unavailable: deepseek","data":null,...}`，同一请求 `llm_calls` 落 `success=0` 行（401 原文只进审计/日志、不进响应体），且此时 `/health` 仍报 `deepseek:"UP"`（配置就绪 ≠ 可达）。
6. **管理台新版外观 + 概览页 + 定时任务页 + logo 的目视复核**（2026-10-20 三次改动后合并为一次，见 §三 ⑪/⑫/⑬）：打开 `/admin/`，在**系统浅色偏好**下应看到浅色底 + 琥珀色点缀（与官网首页同款）；把系统切到深色偏好后刷新，应自动切到深色档、**页面结构不变**；**侧栏左上角是官网同一枚 oryx 徽标**（不是色块或文字 logo），浏览器标签页图标也是它；概览页应显示四张计数卡（Agent 可用/共、活跃会话、已注册工具、运行时长）与健康卡，数字与四个端点 curl 结果一致，四栏在窄屏下依次折成两栏 / 单栏；**定时任务页**应列出各 Agent 声明的 cron 任务（任务 id `Agent#序号`、cron、时区、消息），条数与 `/api/v1/profiles` 一致，无声明时是空态而非白屏；七个页面的页头、卡片、表格、标签、输入框与按钮形态应与官网首页一致。源码层一致性（令牌逐字、logo 逐字节、概览页落地且读数据、各页端点引用）由 `AdminThemeSourceTest` / `AdminConsoleSourceTest` 判，**视觉层只能人看**；§六 Step 7 是在**这三次改动之前**执行的，不覆盖这一项。

> **2026-10-20 修订注（第 27 节）**：① 本清单第 1 / 4 项的数量口径已变——第 27 节新增 `GET /api/v1/sessions`，端点总数为 **11**、Swagger operation 数为 **11**，管理台第 1 页由「按会话 ID 查询」改为「列表 + 点一行看详情」。② 第 6 项起初只被**部分覆盖**（Step 7 不含深色偏好切换与 logo 目视）；**2026-10-20 用户已另行确认这两条通过，第 6 项全部收口**。留痕与抓到的问题见 `specs/010-human-trigger-flow/manual-test-guide.md`。

**已知边界（不是「没验」，是产品层面就不可达或有意不做）**：

| 边界 | 说明 |
| --- | --- |
| **504 真链路抓不到** | 60 秒强制掐断核心阶段不做（裁决 ②，会撞「全程同步阻塞」硬门禁）；`AgentTimeoutException` 本节**无抛出点**，504 映射由 `GlobalExceptionHandlerTest` 判卷 |
| **`/health` 的 `providers` 不是「可达」** | 只表达「配置就绪 / 未配置」（裁决 ⑤），不做真实网络探测；声明了凭证即 `UP` |
| **`/health` 的 `db` 只验「连接可用」** | `DatabaseHealthProbe` 只做 `Connection.isValid(1)`，**不校验表结构**（契约 §4.1）。工作区没初始化（`sessions` 等表不存在）时它仍报 `db:"UP"`，而 `/info` 会 500——2026-10-20 实跑命中。本节不改探针语义（契约已定字面量与判据）；使用者须先 `oryxos init`（向导 Step 0.5） |
| **`oryxos init` 的漂移补齐只覆盖「缺列」** | **2026-10-20 起 `init` 会做加性补列**（用户批准，见 §三 ⑩）：缺表仍交给建表脚本，缺列 `ALTER TABLE ADD COLUMN`，类型不一致只报告不改，多余列不碰，缺主键列直接报错。**仍未覆盖**：类型演进（SQLite 转列类型只能重建表）、主键变更、数据回填——核心阶段没有 Flyway/Liquibase，这三类仍需手工迁移。另外无默认值的 `NOT NULL` 列按可空补（SQLite 硬限制，输出里会写明） |
| **`/invoke` 的 60 秒上限未强制** | 同上，只有异常类型与映射，无计时器 |
| **并发压测未做** | spec 人工项里有「并发压一把」；本节未做系统性压测，只有「虚拟线程 + 同步阻塞」的结构性保证（无共享可变状态、无自建线程池）。**如实记为未覆盖** |
| **`mem0` 记忆档** | 沿用第 22 节永久已知边界，本节 `/memory` 只读视图在该档同样未与真实服务对过 |
| **管理台无前端测试基建** | 纯展示台，只读纪律由源码断言判；视觉回归不在核心阶段 |

---

## 五、备注

- **未 commit、未 push、未运行 `scripts/package.sh`**——同步时机由你决定。
- `specs/009-web-service/` 整个目录当前为 untracked；`docs/class/` 自 2026-10-14 起随仓库跟踪。
- **新增挂账**：`/api/v1/notify-channels` 的 REST CRUD 与管理台渠道管理**本节未交付**（spec 未列入 FR），归扩展阶段；`NotifyChannelRegistry` 接缝仍无写入方法（写入仍走 CLI 三条轻命令）。
- **被推翻的旧表述已挂带日期修订注、未重写原文**：第 18 节「`oryxos-cli` MUST NOT 依赖 `oryxos-web`」「`serve` 不起端口」（`CliApplication` / `ServeCommand` javadoc、`oryxos-cli/pom.xml` 注释）。
- **analyze 三项留档**：U2（控制器测试 `@MockitoBean` 掉核心服务，判不了「链路真通」——由 `WebSmokeIT` + 人工向导补）；D1（`spec.md` 原写「会话列表」，实际无列表端点，已加修订注）；R1（`.gitignore` 的一条死模式有意不列，避免误导产物位置）。

---

## 六、人工验收状态（2026-10-20）

**执行方式**：用户按 [manual-test-guide.md](./manual-test-guide.md) **Step 0~9 全程人工执行**，真 DeepSeek 模型、真 `oryxos.jar`（`oryxos-boot/target/oryxos.jar`）。

**结论**：**通过**，用户未报告任何一步失败。

**留痕口径**（照第 19 / 22 / 25 节）：**只记整体结论、不写步骤级细节**——用户未逐步说明各步输出，故本报告不虚构步骤级证据。

**执行中命中的五处（全部是向导或环境问题，产品代码一行未改；已逐条修进向导与 §三，见 ⑤~⑨）**：

| # | 现象 | 性质 | 落点 |
| --- | --- | --- | --- |
| ⑤ | `GET /api/v1/info` → 500 `no such table: sessions` | 向导缺「先 `oryxos init`」这一步 | 向导 Step 0.5 + Step 2 提示 + 坑表 11 |
| ⑥ | Step 3 → `404 agent not found: smoke` | 向导从未让使用者创建 `smoke` Agent | 向导 Step 0.5 |
| ⑦ | 重跑 Step 3 → `status:"archived"`、发消息 400 | **产品行为正确**（契约 §1.1 创建或取回、归档不复活）；向导缺重跑说明 | 向导「重跑须知」+ 坑表 12 |
| ⑧ | 发消息 → 500 `table llm_calls has no column named error_message` | 旧工作区库缺第 16 节加的两列；`init` 只建表不补列 | 向导列自检 + `ALTER TABLE` 段 + 坑表 13 |
| ⑨ | Step 5.6 → `Error: Unable to access jarfile` | 换终端丢 `$JAR`（与第 25 节同类） | 向导 Step 5.6 命令块补 export + 自查 |

**覆盖面（别把「跑完了」读成「每条判据都被独立证过」）**：

| 项 | 状态 |
| --- | --- |
| Step 0~4、6~9 | 用户执行，整体通过 |
| Step 5.1~5.5（错误码映射，不依赖模型） | 用户执行，整体通过 |
| Step 5.6（无效 key → 503） | 用户执行到该步；**用的是无效 key**，模型侧真实失败与 `llm_calls.success=0` 由作者侧 2026-10-19 另行真链路实测（§四 第 5 条） |
| 「并发压一把」（spec 人工项） | **未做**——向导无对应步骤，如实记为未覆盖（§四 已知边界） |
| 504 真链路 | **结构上不可达**（裁决 ②，`AgentTimeoutException` 无抛出点），判卷在 `GlobalExceptionHandlerTest` |
| 管理台视觉层（五页渲染 / 无写入口 / 刷新子路由） | 用户执行 Step 7，整体通过；源码层另有 `AdminConsoleSourceTest` 判卷 |
| 管理台**改版后**的外观（默认浅色 / 跟随系统深色 / 与官网同款） | **2026-10-20 用户已目视确认通过**（深色偏好切换正常、与官网同款）；另有源码断言（`AdminThemeSourceTest`）与前端构建产物核对 |
| 概览页 / 定时任务页与 logo（2026-10-20 新增的第六、七页） | 概览页与定时任务页已随第 27 节 Step 7.1~7.9 逐页走过；**logo 目视于 2026-10-20 另行确认通过**（侧栏与标签页图标均为官网同一枚徽标）。源码层另有 `AdminConsoleSourceTest.overviewPageIsLandingPage` + `VIEW_ENDPOINTS` / `AdminThemeSourceTest.adminLogoMirrorsSiteIcon` |
| `mem0` 记忆档 | 沿用第 22 节永久已知边界，本节 `/memory` 只读视图在该档同样未与真实服务对过 |

**与第 22 节的区别**：第 22 节有一档（`mem0`）经裁决**有意不验收**、成为**永久已知边界**；本节没有这类「该验没验」的档——两处未覆盖（并发压测、504 真链路）都已作为**已知边界**显式登记在 §四，不是遗漏。
