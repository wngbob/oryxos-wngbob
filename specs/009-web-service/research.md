# Phase 0 Research: Web Service 与第一版管理平台（第 26 节）

**Feature**: `specs/009-web-service` | **Date**: 2026-10-19 | **Plan**: [plan.md](./plan.md)

本文件把 spec 的 `## Clarifications`（5 条）与 `checklists/requirements.md` 的「已知未决」逐条兑现成可执行决策。每条给**决策 / 依据 / 备选与否决理由**，依据一律来自**本仓实测**或**本仓已入库契约**，不凭记忆。

参考实现（只读）：上游 `/home/wngbob/oryxos`，分支 `main`，HEAD `3edb6f02`。用户口径「尽量跟上游一致」——凡本仓与上游冲突处，本文件显式记下分歧与理由。

---

## D1 管理台构建串联：`frontend-maven-plugin` 绑 `generate-resources`

**决策**：在 `oryxos-web/pom.xml` 引入 `com.github.eirslett:frontend-maven-plugin:2.0.2`，把 `install-node-and-npm` + `npm install` + `npm run build` 三个 execution 绑到 `generate-resources`；Node 版本 `v20.18.0`；`workingDirectory=src/main/frontend`、`installDirectory=target`；用属性 `frontend.skip` 提供纯 Java 快构建（`-Dfrontend.skip=true`）。一条 `mvn clean package` 出全量 fat JAR。

**依据**：
- 用户裁决（本轮之前）：先选「手动 build、产物入库」，在我报告上游做法后改为「尽量跟上游一致」→ 取上游形态。
- 上游 `oryxos-web/pom.xml` 逐字是这套配置（同插件、同版本、同 Node 版本、同绑定相位）。
- 可行性已实测（2026-10-19）：`https://nodejs.org/dist/v20.18.0/...` 直连与经代理 `http://127.0.0.1:7890` 均 **HTTP 200**；`registry.npmjs.org` 经代理 **200**；`~/.npmrc` 只有一行 `prefix=...`，无镜像/代理干扰。
- 产物落点 `oryxos-web/src/main/resources/static/admin/`，随 `process-resources` 进 fat JAR——与上游 `vite.config.js` 的 `build.outDir: '../resources/static/admin'` 一致。

**备选与否决**：
- *前端单独 `npm run build` 后再 `mvn package`*：CI 更简单，但用户已改口径取上游形态；且手动步骤会随「改了前端忘了 build」静默产出旧页面（与第 24 节向导抓红同一类：**看起来成功的错误**）。
- *`npm ci` 代替 `npm install`*：`npm ci` 要求 `package-lock.json` 已入库且与 `package.json` 严格一致；上游用的是 `install`。跟上游。

## D2 前端产物与依赖不入库

**决策**：`.gitignore` 补上游那三行：`**/node_modules/`、`oryxos-web/src/main/frontend/dist/`、`oryxos-web/src/main/resources/static/admin/`。

**依据**：本仓 `.gitignore` 现有内容里这三条**一条都没有**（实测），而 `website/node_modules/` 与 `website/.vitepress/dist/` 已单独列过——上游是把管理台这三条一并列出的。构建产物由 D1 的插件在打包时重建，入库会造出「源码改了、产物没跟着改」的第二真相。

## D3 不绑 `npm test` execution

**决策**：不照抄上游的第四个 execution（`npm-test`）。

**依据**：本节前端是**只读展示台**，课件「验收 harness」里没有任何前端测试类；本仓核心阶段的测试纪律是「能机器判的绝不留给人」，前端页面的判卷落在 `AdminConsoleSourceTest`（读源码断言：只发 GET、路径前缀 `/api/v1`、五页文件存在、无写操作动词）与人工项（五页渲染真实数据）。挂一个没有测试文件的 `npm test` 只会让 `mvn verify` 多一个空步骤，或更糟——`npm test` 在无测试时非零退出，直接红掉全量门禁。

**分歧记录**：与上游有差异，**有意**，理由如上。

## D4 `GET /memory` 的 `{core, archival, truncated}`：给记忆接缝加只读分区方法

**决策**：新增 `oryxos-memory` 的 `MemoryPartition`（`record MemoryPartition(String core, String archival, boolean truncated)`，与 `MemoryScope` 同包 `store/`），并在两个既有接缝上**纯新增**一个方法：

```java
// LongTermMemoryStore
MemoryPartition partition();

// MemoryService（门面）
MemoryPartition partition();
```

四个实现类各自实现（`MarkdownMemoryStore` / `SqliteMemoryStore` / `Mem0MemoryStore` / 测试替身 `InMemoryMemoryStore`）；`MemorySource`（core 侧既有接缝）**一个字不改**。

**依据**：
- 已入库契约 `specs/000-runtime-kernel/contracts/rest-api.md` 定的响应形状是 `{core, archival, truncated}`，而 core 侧既有读记忆接缝 `MemorySource.longTermMemory(Profile)` **只返回一整段文本、没有分区**——契约兑现不了。
- 上游**没有** `/api/v1/memory` 端点（只有 `AgentApiController` 的 `/{name}/memory`，属第 30 节范围），上游 `LongTermMemoryStore` 也**没有**核心区访问器（同为 5 方法形态）→ **本形状无上游先例**，只来自本仓契约。
- 选**字符串**而非列表：三档实现的 `load()` 内部本来就已经把两区拼好（三档都发同样的 `## 核心记忆` / `## 归档记忆` 标题，实测 `MarkdownMemoryStore:77/81`、`SqliteMemoryStore:77/81`、`Mem0MemoryStore`、`InMemoryMemoryStore` 四处同款）。`partition()` 因此是把 `load()` 已有的分区逻辑**机械抽出**，与 `load()` 共用同一个私有截断助手——两处口径必然一致，不会漂移。
- `truncated` 的语义：归档区**是否被截断过**（`markdown` 档 `> 4000` 字符、`sqlite` 档 `> 100` 行；`mem0` 档交给对端作用域机制，恒 `false`）。核心区**永不被截断**（第 22 节四个行为契约之一），故 `truncated` 只描述归档区。

**备选与否决**：
- *解析 `load()` 返回文本里的 `## 核心记忆` / `## 归档记忆` 标题*：**脆弱**——标题是各档实现的内部格式，任何一档改标题就会静默拆错区；且 `checklists/requirements.md:60` 记的「sqlite 档没有标题」**与事实不符**（实测 `SqliteMemoryStore.load()` 同样发这两个标题），照那条理由选解析会建立在错误前提上。
- *在 `MemorySource`（core 接缝）上加方法*：`MemorySource` 是第 17 节骨架期自造的 core 接缝，语义是「有就读、没有就跳过」的**一段文本**；给它加分区是让 core 反向知道「记忆有核心区/归档区」这个 `oryxos-memory` 的内部概念。**不改**。

**软门禁 ④ 披露**：这是对**前序节公共接口**（`LongTermMemoryStore` / `MemoryService`）加方法，**纯新增、不改任何既有签名**，四个实现类全部补齐；已在 Step-4 完成报告里单独列出等确认。

## D5 课件 §二.4 的 `spring.autoconfigure.exclude` **不适用，MUST NOT 加**

**决策**：`application.yaml` **不加** `spring.autoconfigure.exclude`；`application.yaml` 本节**一个字不改**。

**依据（两条实测，缺一不可）**：
1. `mvn dependency:tree` 实测：`oryxos-boot` 的 classpath 上 `spring-ai-autoconfigure-*` 工件数 = **0**（只有 `spring-ai-model` 1.1.8、`spring-ai-commons`、`spring-ai-template-st`、`spring-ai-deepseek`、`spring-ai-retry`，外加传递的 `io.projectreactor:reactor-core:3.7.19` 与 `org.springframework:spring-webflux:6.2.19`）。课件点名要排除的 `com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeAutoConfiguration` 与 `org.springframework.ai.autoconfigure.openai.OpenAiAutoConfiguration` **在本仓 classpath 上根本不存在**——它们属于 `spring-ai-*-spring-boot-starter` 那类工件，本仓从未引入。课件的因果链（「deepseek 走 spring-ai-openai starter，`OpenAiAutoConfiguration` 会急切实例化 `OpenAiChatModel` 并索要 `spring.ai.openai.api-key`」）**对本仓不成立**。
2. **照抄不会炸，但也没用**（2026-10-19 反编译 `spring-boot-autoconfigure-3.5.16.jar` 的 `AutoConfigurationImportSelector.checkExcludedClasses` 字节码核实，非记忆）：该方法的逻辑是「排除项**在 classpath 上存在**（`ClassUtils.isPresent` 为真）、且不在自动配置候选清单里 → 启动抛 `IllegalStateException`」；类**不存在**时 `isPresent` 返回 false，该项被**静默忽略**——不报错、也不排除任何东西。本仓那两个类不存在，故照抄是一段**死配置**。（*修订注，2026-10-19：本段初稿曾写「照抄会把服务起不来」，复核时被反编译证据推翻——`isPresent` 前置判断决定了不存在的类名走不到抛异常分支。原结论「不照抄」不变，理由以本条为准。*）
3. 不照抄的真正理由：**不适用 + 误导**。留着那两行会让后来的读者以为「有一个急切自动装配在跑、被我们按住了」，而事实是该机制从未在本仓 classpath 上存在过；将来真引入 `spring-ai-starter-model-*` 那类工件时，要排除什么应届时按 `dependency:tree` 实况重议，而不是现在埋一段没人验证过的配置。

**FR-010 怎么被真正守住**：靠结构——classpath 上没有任何会自动索要第二把 key 的自动配置类。判卷落在 `WebSmokeIT`：**只注入 `DEEPSEEK_API_KEY` 一个凭证环境变量**起真实上下文，上下文起得来即证。

**口径**：这是「课件与事实冲突」，按本仓纪律以事实为准，且**不照抄**；如实记进验收报告。

## D6 `serve` 端口生效方式：`System.setProperty`，不是 builder 的 `properties(...)`

**决策**：`ServeCommand` 用 `System.setProperty("server.port", String.valueOf(port))` 让 `--port` 真正生效。

**依据**：`SpringApplicationBuilder.properties(Map)` 落到 `SpringApplication.setDefaultProperties(...)`，而 **default properties 是优先级最低的一档**——`application.yaml` 里的 `server.port: 8080` 会把它盖掉，`--port 9090` 静默无效。这正是 FR-024 要防的那类「看起来生效的错误」。上游 `ServeCommand` 逐字用的也是 `System.setProperty`。

**已知边界（如实记）**：`--port` 有 Picocli 默认值 8080，无条件 `setProperty` 意味着**用户改 `application.yaml` 的 `server.port` 会被 `--port` 的缺省值盖掉**。上游同款写法，本节保持与上游一致、不另造「仅显式传参才 set」的分支；此边界记进验收报告。

**备选**：`.run("--server.port=" + port)`（命令行 args 优先级最高，同样正确）。取 `System.setProperty` 是为了与上游同构。

## D7 Web 容器类型：`serve` 显式 `SERVLET`，`chat` / `gateway` 保持 `NONE`

**决策**：`ServeCommand` 的 `SpringApplicationBuilder` 显式 `.web(WebApplicationType.SERVLET)`；`ChatCommand` / `GatewayCommand` 的 `.web(WebApplicationType.NONE)` **不动**。

**依据**：`spring-boot-starter-web` 在 classpath 上时，Spring Boot 的类型推断本就会选 `SERVLET`（`DispatcherServlet` 在场即 SERVLET，即使 `spring-webflux` 也在 classpath 上）。**显式写出**是为了让「哪条命令起容器」在源码里一眼可见，而不是靠推断——第 18 节那次 `BeanDefinitionOverrideException` 就是「只跑 `--help`、没真跑重命令」漏掉的。`chat` 是交互式对话，起一个 HTTP 端口是纯副作用，`NONE` 必须保留。

## D8 管理台设计令牌的真相源是 `website/.vitepress/theme/custom.css`

**决策**：管理台样式直接取 `website/.vitepress/theme/custom.css` 的 `:root` / `.dark` 变量值；并回修已入库的 `.claude/skills/oryxos-admin-ui/SKILL.md`。

**依据**：课件 §三.5 的令牌清单是**过期值**（`#000000 / #111111 / #1a1a1a / #222222 / #f97316 / #ea6a00 / #c2550a / #f5f5f5 / #a3a3a3 / #666666`），与官网首页实际值不符；而课件自己写着「值直接抄首页 token，一个字别自创」。实测首页真值：

```css
:root { --ox-bg:#ffffff; --ox-bg-soft:#f7f8fa; --ox-card:#ffffff; --ox-border:#e6e8eb;
        --ox-text:#0f172a; --ox-text-2:#52606d;
        --ox-brand:#f59e0b; --ox-brand-deep:#d97706; --ox-navy:#0c1424; --ox-navy-2:#16213a;
        --ox-glow:rgba(245,158,11,.12);
        --vp-c-brand-1:#d97706; --vp-c-brand-2:#f59e0b; --vp-c-brand-3:#fbbf24; }
.dark { --ox-bg:#0b1220; --ox-bg-soft:#0e1626; --ox-card:#111c33; --ox-border:#1f2c47;
        --ox-text:#e5e7eb; --ox-text-2:#94a3b8;
        --ox-brand:#fbbf24; --ox-brand-deep:#f59e0b; --ox-navy:#070d1a; --ox-navy-2:#0c1424; }
字体：--vp-font-family-base: 'Inter','PingFang SC',...；--vp-font-family-mono: 'JetBrains Mono',...
```

**已入库 skill 的三处失效**（本次一并回修）：① 携带课件那套过期令牌；② 声称信封是 `ErrorBody(errorCode/message/timestamp)` —— 与裁决 ①（`{code, message, data, timestamp}`，`code` = HTTP 状态码）**直接冲突**；③ 导航项写「会话列表」调 `GET /sessions` —— 本仓契约里**没有** `GET /api/v1/sessions`（10 个端点里没有它），照 skill 写会造出一个契约外端点。会话页因此定为「**按会话 ID 查询**」页，调 `GET /api/v1/sessions/{id}`。

## D9 会话归档接缝：第 18 节已把这两态明确挂给本节

**决策**：`SessionService` 加 `boolean archive(String sessionId)`；`Session` 加 `status()` / `archivedAt()`（`create` 造出的会话 `status="active"`、`archivedAt=null`；`restore(...)` 多收这两个参数）。`SessionRepository` 加 `long countByStatus(String status)`。

**依据**：这不是本节新开的口子，而是**第 18 节自己挂的账**，`JpaSessionService` 的 javadoc 逐字写着：
- 「**本节不做**：归档（`status` / `archived_at` 的第二态归第 26 节）」
- `save()` 更新路径「只动这两列：`created_at` 与 `status` / `archived_at` 原样保留（不变量 ① / ②），**否则第 26 节一归档就被下一次 save 悄悄复活**」

`sessions` 表两列（`status` / `archived_at`）与 `SessionEntity` 的两个字段**都已就位**，缺的只是 core 侧的读写口。归档幂等（对已归档会话再 DELETE 返回同样结果）由 `archive()` 内部「已是 archived 就直接返回」实现，不改 `archived_at`。

**归档会话再发消息 → 400**（spec Edge Case）：由 `SessionApiController` 在调 `AgentService` **之前**判 `status`，抛 `IllegalArgumentException`——已由既有 `GlobalExceptionHandler` 映射 400，且**零模型调用**（FR-022）。

## D10 `/info` 的 `activeSessions`：`SessionService.countActive()`

**决策**：`SessionService` 加 `long countActive()`，`JpaSessionService` 实现为 `repository.countByStatus("active")`。

**依据**：契约 `/info` 要求 `activeSessions`。`oryxos-web` MUST NOT 直接依赖 `oryxos-storage` 去数表——那会让「读会话」这件事出现两个入口（`SessionService` 与 `SessionRepository`），与「契约在 core、实现在各模块」的既定方向相反。上游 `oryxos-web` 确实依赖 `oryxos-storage`，但那是它后来的鉴权需求（`WebUserService`），不是这条。

## D11 `/invoke` 不落库：`AgentService.processStateless(String, String)`

**决策**：在 `oryxos-core` 的 `AgentService` 上**新增**（不改既有 `process`）：

```java
public String processStateless(String agentName, String userMessage)
```

内部造一个**瞬时** `Session`（id = `"invoke-exec:" + UUID.randomUUID()`，channel `"web"`，userId `"console"`），跑 `reActLoop.run(...)`，**MUST NOT 调 `sessionService.save(...)`**；`ProfileContext` 仍在 `finally` 里清。

**依据**：
- 契约 `rest-api.md`：`POST /agents/{name}/invoke` → 「**不创建持久会话、不携带历史**」。
- 用户裁决（本轮之前）：取上游的方法名与做法。
- 上游 `AgentService.processStateless(String agentName, String userMessage)` 逐字同形：`STATELESS_EXECUTION_TAG = "invoke-exec"`，delegate 到 `processStateless(agentName, userMessage, "invoke-exec:" + UUID.randomUUID())`；javadoc「使用带唯一执行标识的**内存** Session，**不创建或更新持久会话**」「审计沿用 Session 标识关联到本次执行，请求结束后临时消息丢弃」。
- 上游 `AgentApiController` 另用 `CONSOLE_CHANNEL="admin"` / `CONSOLE_USER="console"` 生成会话 id；本仓的会话 id 由 `SessionId.of(channel,userId,profileName)` 拼接，而**无状态调用不该落 `sessions` 表**，故这里直接给字面 id、不经过 `SessionId`——`session_id` 只在 `SessionId` / `SessionService` 内拼接这条不变量（H4 ④）**指的是持久会话**，瞬时会话不写库、不参与拼接口径。

**软门禁 ④ 披露**：对前序节公共类 `AgentService` 加方法，**纯新增**，`process` 一行不改。已进 Step-4 完成报告。

## D12 503：`ProviderUnavailableException` 放 `oryxos-provider`，由 `ProviderService` 包装

**决策**：`ProviderUnavailableException(String providerName, Throwable cause)` 放 `oryxos-provider`（与 `ProviderNotFoundException` 同包）；`ProviderService.chat` 的 `catch (RuntimeException e)` 分支改为：

- `e instanceof ProviderNotFoundException` → 原样上抛（配置错误，裁决 ④ 定 500）
- 其余 → `throw new ProviderUnavailableException(providerName, e)`

异常消息形如 `provider 'deepseek' 调用失败: <cause.getMessage()>`；响应体由 `GlobalExceptionHandler` 用契约字面量拼 `provider unavailable: <name>`。

**依据**：
- 裁决 ④：Provider 故障用**专用异常**映射 503，MUST NOT 让 `ProviderNotFoundException`（配置错误）混进 503。
- spec 人工项：「断掉 Provider 拿 503」——必须有真实抛出点，否则 503 映射是死代码。
- **消息必须含 cause 的 message**：既有单测 `ProviderServiceTest.recordsAuditBeforeRethrowingOnFailure`（`oryxos-provider/src/test/.../ProviderServiceTest.java:135`）断言 `isInstanceOf(RuntimeException.class)` **且** `hasMessageContaining("timeout")`。反作弊纪律下不得放宽该断言，故包装后的消息保留原 message。503 的**响应体**另按契约字面量拼，不把内部 message 放进对外响应。
- 该文件同时断言「审计先落账再上抛」，包装后审计调用点不变、断言全绿。

**与上游的分歧**：上游把 `ProviderUnavailableException` 放 `oryxos-web/error/`，且**只在 `ProviderModelsService`（模型列举，属后续节）里抛**，LLM 调用链**从不抛它**——即上游的 503 映射在本节语义下够不到。本仓把类放 `oryxos-provider`（抛出方所在模块），与既有 `ProviderNotFoundException` 同处。

## D13 504：`AgentTimeoutException` 放 `oryxos-web/error/`，本节**不抛**

**决策**：新建 `oryxos-web` 的 `error/AgentTimeoutException`，`GlobalExceptionHandler` 映射 504；**代码里没有任何抛出点**。

**依据**：裁决 ②（spec Clarifications 第 21 行逐字）——强制掐断会撞 H4 ⑤（全程同步阻塞，出现 Reactor / `CompletableFuture` / 自建线程池即违规）；只落异常类型与 504 映射，课件「构造超 60 秒调用拿 504」这条人工项**本节不可达**，验收报告 MUST 如实记为已知边界，MUST NOT 假装验过。判卷落在 `GlobalExceptionHandlerTest`（构造该异常、断言映射到 504）。上游同款（`oryxos-web/error/AgentTimeoutException.java` + `GlobalExceptionHandler:184`）。

## D14 404：`SessionNotFoundException` / `ProfileNotFoundException` 放 `oryxos-web/error/`

**决策**：两个专用异常放 `oryxos-web` 的 `error/` 包（照上游 `SessionNotFoundException` 的落点）。抛出点**只在 controller 的参数校验**。

- `SessionApiController`：`sessionService.get(id)` 空 → `SessionNotFoundException(id)`（消息照上游：`会话不存在: <id>`）
- 三个需要 Profile 的入口（`POST /sessions`、`POST /sessions/{id}/messages`、`POST /agents/{name}/invoke`）：`profileRegistry.find(name)` 空 → `ProfileNotFoundException(name)`

**依据与关键取舍**：spec 第 87 行要求「**会话所属 Agent 的配置已经不可用** → 发消息拿到 **404**，不是 503」，而 `AgentService.process` 在 Profile 查不到时抛的是**裸 `IllegalStateException`**（`oryxos-core/.../engine/AgentService.java:63`）。

- **不**把 `IllegalStateException` 整类映射成 404：那会让 `MemoryServiceImpl` 的 `HYBRID_BUILTIN` 异常、`Mem0MemoryStore` 的后端异常、`SqliteMemoryStore` 的「表不存在」全都变成 404——正是裁决 ④ 反对的那类「排障方向被带偏」。
- **不**改 `AgentService.process` 的抛出类型：那是改前序节已交付类的**行为**，且本节没有别的调用方需要它。
- **改在 controller 预校验**：`ProfileRegistry` 是启动期快照、不可变，controller 校验与 `AgentService` 查的是**同一个实例**，两次查询之间不可能变——所以预校验把 FR-007 的场景**确定性**地覆盖了，不存在 TOCTOU 窗口。controller 只多一个私有 `requireProfile(String)` 助手，仍属 FR-002 允许的「参数校验」。
- **已知边界（如实记录）**：若真出现「预校验通过、`process` 仍抛 `IllegalStateException`」的内部错误，走 catch-all → **500**（正确语义），不会伪装成 404。

## D15 `/health` 的 `db` 探测：注入 `javax.sql.DataSource`，`Connection.isValid(1)`

**决策**：`oryxos-web` 内新建一个薄探针组件（`health/DatabaseHealthProbe`），持 `javax.sql.DataSource`，`try (Connection c = ds.getConnection()) { return c.isValid(1); }`，异常即判不可达。`SystemApiController` 只做响应包装。

**依据**：
- `javax.sql.DataSource` 是 **JDK 类型**，`oryxos-web` **零新增依赖**即可注入容器里已有的 Hikari `DataSource` Bean。这是「不新增第三方依赖」约束下最省的路径。
- 走 actuator 的 `HealthEndpoint`（另一个候选）需要给 `oryxos-web` 加 `spring-boot-actuator` 依赖边，并把 `/api/v1/health` 的形状绑到 actuator 的 `HealthComponent` 上——多一条耦合、多一个可变量，收益只是复用，不值。
- 本地 SQLite 的 `SELECT`/`isValid` 是**廉价且稳定**的健康检查（技术方案 §7.4 的要求）；它不是「发真实请求去探测 Provider」，与 FR-020 不冲突。
- FR-020「**未就绪的 Provider MUST NOT 拉低整体状态**，但数据库不可达 MUST 拉低整体状态」→ `status` = `db` 就绪 ? `"UP"` : `"DOWN"`，providers 不参与。

## D16 `/health` 的 `providers` 取值：字面量 `"UP"` / `"DOWN"`，语义是「配置就绪 / 未配置」

**决策**：`providers` 的键集合取 `ProviderProperties.SUPPORTED_PROVIDERS`（本仓为 `Set.of("deepseek")`）；某个名字**在 `oryxos.providers` 里已声明** → `"UP"`，否则 `"DOWN"`。JSON 字面量保持契约已定的 `"UP"` / `"DOWN"`。

**依据**：
- 契约 `rest-api.md` 定死 `providers:{name:"UP"|"DOWN"}`（已定字面量，不得改）。
- 裁决 ⑤：语义收窄为「配置就绪」，**MUST NOT 发真实探测**，措辞 MUST NOT 用「连通 / 不可达」。
- 用 `SUPPORTED_PROVIDERS` 作键集合，是为了让 `"DOWN"` **真的可达**：`ProviderProperties.validate()` 在「已声明的厂商 api-key 为空」时**启动即失败**（`oryxos-provider/.../ProviderProperties.java`），所以「已声明」蕴含「凭证在场」→ `"UP"`；「底座支持但本实例没声明」才落在 `"DOWN"`。若改取「已声明清单」作键集合，`"DOWN"` 会成为**结构上不可达**的死分支（与第 25 节 Step 3 那行 WARN 同类），既测不到也说不清。
- **契约需回修**：`rest-api.md` 该行原写「不可达」二字，挂带日期（2026-10-19）修订注改为「未就绪 / 未配置」，字面量 `UP`/`DOWN` 不动。

## D17 模块依赖边：`oryxos-web` 四条 + `oryxos-cli` 一条

**决策**：

| 边 | 用途 | 上游对照 |
|---|---|---|
| `oryxos-web → oryxos-core` | `AgentService` / `SessionService` / `ProfileRegistry` / `ProfileLoader` / `Session` / `ToolCatalog` | 上游有 ✓ |
| `oryxos-web → oryxos-provider` | `ProviderProperties`（`/health` 的 providers） | 上游有 ✓ |
| `oryxos-web → oryxos-memory` | `MemoryService`（`/memory`） | 上游**无此端点**，无对照 |
| `oryxos-web → oryxos-tool` | `ToolRegistry.registered()`（`/tools` 的 `source` / `origin`） | 上游**无**（上游 `/tools` 只回 name+description，用 `@Qualifier("tools") Map<String,OryxTool>`） |
| `oryxos-cli → oryxos-web` | `serve` 起 servlet 容器后要扫到 `@RestController` | 上游同向 ✓ |

`oryxos-web` 另加 `spring-boot-starter-test`（`test` 作用域，本模块此前**没有** `src/test` 目录）。

**依据**：
- `ToolRegistry.RegisteredTool(OryxTool tool, String source)`（`oryxos-tool/.../registry/ToolRegistry.java:169`）是 `source`/`origin` 的**唯一来源**；core 的 `ToolCatalog` 接缝刻意只有 `find` / `availableFor` 两个方法，**没有**「列全量并带来源」的形状。为了一个 REST 视图往 core 接缝上加方法，是让 core 知道装配期的来源概念——不做。`ToolRegistry` 本身就是一个既有服务，注入它是「委托核心层既有服务」。
- 四条边全部**单向、无环**：四个模块都不依赖 `oryxos-web`。
- `oryxos-cli/pom.xml` 现有注释逐字写着「**MUST NOT 加 `oryxos-web`** —— 本节不起 Web 容器（仲裁 15）」——那是**第 18 节的节内范围表述**，本节正是那个「第 26 节」，挂带日期（2026-10-19）修订注后改写。同理 `CliApplication` javadoc 里的「MUST NOT 起 SERVLET 容器 / MUST NOT 依赖 `oryxos-web`」。

## D18 `CliApplication` 的扫描根：加 `io.oryxlabs.oryxos.web`，**不放宽**到 `io.oryxlabs.oryxos`

**决策**：`scanBasePackages` 由两项变三项：

```java
@SpringBootApplication(scanBasePackages = {
  "io.oryxlabs.oryxos.cli",       // CliApplication + CliAssemblyConfiguration
  "io.oryxlabs.oryxos.provider",  // ProviderConfiguration
  "io.oryxlabs.oryxos.web"        // 第 26 节：六个 ApiController + WebConfig + GlobalExceptionHandler
})
```

**依据**：`CliApplication` 的 javadoc 已写明「扫描根 MUST NOT 放宽到 `io.oryxlabs.oryxos`」——宽根会把 `oryxos-boot` 的 `OryxosApplication` 一起扫进来，两个 `@SpringBootApplication` 各自 `@EnableJpaRepositories` → Repository Bean 注册两遍 → `BeanDefinitionOverrideException`（第 18 节实测过）。加一个**具体子包**不触这条。

## D19 删除 `OryxosApplication`（裁决 ③）+ 三处连带

**决策**：删除 `oryxos-boot/src/main/java/io/oryxlabs/oryxos/boot/OryxosApplication.java`。

**依据**：裁决 ③。它的 `scanBasePackages = "io.oryxlabs.oryxos"` 是宽扫描根，与 `CliApplication` 撞车的**潜伏**来源；自第 18 节起它已不是 fat JAR 主类（`oryxos-boot/pom.xml` 的 `<mainClass>` 显式指向 `OryxOsCli`）。

**三处连带**（同一提交内做）：
1. `oryxos-boot/pom.xml` 的 `<mainClass>` 注释写「JAR 内并存两个 `@SpringBootApplication`（`OryxosApplication` 与 `CliApplication`），探测会有歧义」——删了之后这句失效，改为「显式写死而非靠 `MainClassFinder` 自动探测」的其余理由。
2. `website/guide/quick-start.md:35` 与 `website/en/guide/quick-start.md:35` 逐字写着「**fat JAR** | `io.oryxlabs.oryxos.boot.OryxosApplication`」——**已经过期**（第 18 节起主类就是 `OryxOsCli`），删除后更加错。按 CLAUDE.md「对外内容三处同步」纪律回修这两处。
3. `CLAUDE.md` 里「`OryxosApplication` 自第 18 节起不再是 fat JAR 主类，**去留由第 26 节定**」——本节定完了，改为事实陈述。

## D20 报文字面量对齐（裁决 ⑪）：只改冲突的四个字段名

**决策**：本仓契约里与课件/上游冲突的字段名改掉，其余更丰富的形状**保留**：

| 位置 | 本仓契约原文 | 改为 | 理由 |
|---|---|---|---|
| `POST /sessions` 请求 | `{"profile", "user"}` | `{"profile", "userId"}` | 对齐上游 `CreateSessionRequest(String profile, String userId)` |
| `POST /sessions` 响应 | `channel:"api"`, `user` | `channel:"web"`, `userId` | 对齐上游 `WEB_CHANNEL="web"`；`userId` 同上 |
| `POST /sessions/{id}/messages` 请求 | `{"message"}` | `{"content"}` | 对齐课件 §三.2 的 `req.content()` 与上游 `MessageRequest(String content)` |
| `POST /sessions/{id}/messages` 响应 | `{sessionId, response}` | `{sessionId, reply}` | 对齐上游 `MessageResponse(String reply, ...)` |
| `POST /agents/{name}/invoke` 响应 | `{agent, response}` | `{agent, reply}` | 同上 |

**保留不动**（本仓契约更丰富，且有真实内容）：`/tools` 的 `inputSchema` / `source` / `origin`；`/profiles` 的嵌套 `provider{name,model}` + `schedules` + `valid` + `error`；`/health` 的 `db` + `providers`；`/info` 的 `version` / `javaVersion` / `agents` / `activeSessions` / `registeredTools` / `uptimeSeconds`；`GET /sessions/{id}` 的 `status` / `archivedAt` / `messages[≤100]`。

**连带**：`specs/000-runtime-kernel/contracts/rest-api.md` 上述四处挂**带日期（2026-10-19）修订注**（原文不重写，本仓惯例）。另两处**已交付文件**的 javadoc 提到 `api` 渠道，一并回修：`SessionEntity.channel`（「`cli` / `api` / `scheduler` / `gateway`」）与 `Session.channel()` 的 `@param`（「如 `cli` / `api` / `scheduler`」）——改 `api` → `web`。

## D21 CORS：`/api/**` 核心阶段全开放（FR-023）

**决策**：`WebConfig implements WebMvcConfigurer` 的 `addCorsMappings` 里对 `/api/**` 放开（`allowedOriginPatterns("*")`、全方法、全请求头、`allowCredentials(false)`）。

**依据**：FR-023 + 技术方案 §7.4「CORS 核心阶段全开放方便调试」。`allowCredentials(false)` 是因为 `allowedOrigins("*")` 与 `allowCredentials(true)` 不能并存（Spring 会抛异常）。上游**没有 CORS**（实测上游 `WebConfig` 只做 SPA 回落与静态缓存）——这是**有意分歧**，spec 明确要求。

## D22 SPA 回落：照上游 `WebConfig` 的形态

**决策**：`/` → redirect `/admin/`；`/admin` → redirect `/admin/`；`/admin/` → forward `/admin/index.html`；`/admin/assets/**` → `classpath:/static/admin/assets/`，`CacheControl.maxAge(365,DAYS).cachePublic().immutable()`；`/admin/**` → `classpath:/static/admin/`，`noCache().resourceChain(true)` + 自定义 `PathResourceResolver` 回落 `index.html`。

**依据**：FR-016（`/admin/**` 未命中回落 `index.html`，`/api/v1/**` 不受影响）。上游 `oryxos-web/.../config/WebConfig.java` 逐字是这套（常量 `INDEX="index.html"` / `SLASH="/"` / `STATIC_CACHE_DAYS=365`）。**路径域天然隔离**：`/api/**` 不在任何 `/admin/**` 规则内，故回落不会吞掉 API 的 404。

## D23 `uptimeSeconds`：取 JVM 运行时长

**决策**：`/info` 的 `uptimeSeconds` = `ManagementFactory.getRuntimeMXBean().getUptime() / 1000`。

**依据**：对 `serve` 这种**前台常驻**进程，「服务时长」与「JVM 运行时长」是同一件事（进程就是服务，容器内一进程一服务）。不为此新造一个「启动时刻」Bean——那是为一个数字引入一个新概念。字段语义在 OpenAPI 描述里写明。

## D24 `WebSmokeIT` 落 `oryxos-cli`，`@Tag("integration")`，工作区用 `user.dir` 指向临时目录

**决策**：`WebSmokeIT` 放 `oryxos-cli/src/test/java/io/oryxlabs/oryxos/cli/WebSmokeIT.java`（`oryxos-web` 侧**不放**），打 `@Tag("integration")`；`@SpringBootTest(webEnvironment = RANDOM_PORT)`，静态初始化块里把 `user.dir` 指向临时工作区。

**依据**：
- 课件 harness 要它验证「**Bean 装配和扫描范围没炸**」。只有 `oryxos-cli` 持有 `CliApplication`（唯一 `@SpringBootApplication`），只有在这里起上下文才真的测到 D17/D18 那两条改造。放 `oryxos-web` 侧起的是另一个上下文，测不到「扫描范围」。
- `CliTestContext` **硬编码 `.web(WebApplicationType.NONE)`**，本节需要 SERVLET → `WebSmokeIT` 自己构造 builder，不复用 `CliTestContext`（也不改它，避免动第 20~25 节的装配测试基建）。
- 工作区问题：`CliAssemblyConfiguration.workspace()` = `Workspace.current()` = `new Workspace(Paths.get(""))`，`Paths.get("").toAbsolutePath()` 取的是**调用时刻**的 `user.dir`。故静态块里 `System.setProperty("user.dir", <临时目录父级>)`，并先造出 `.oryxos/` + 库文件。**依赖注入**：`DEEPSEEK_API_KEY` 用 `@DynamicPropertySource` / `System.setProperty` 给一个假值即可（`ProviderProperties.validate()` 只校验非空，**不发请求**）——这同时是 D5「只有一个凭证」的判卷。
- `@Tag("integration")` + 根 pom 的 `<excludedGroups>integration</excludedGroups>`：与既有 `ProviderSmokeIT` 同款，`mvn clean verify` 默认跳过，`-DexcludedGroups=none -Dtest=WebSmokeIT -Dsurefire.failIfNoSpecifiedTests=false -am` 单跑。
- **`user.dir` 卫生**：静态块改的是整个 surefire fork 的 JVM 属性，须在 `@AfterAll` 里**还原原值**——默认 verify 跳过 IT、显式单跑时 fork 内只有它，实际风险低，但还原是零成本的对称操作，不留给后来者踩。

## D25 `application.yaml` 本节零改动

**决策**：不改 `oryxos-boot/src/main/resources/application.yaml`。

**依据**：逐项核过——虚拟线程 `spring.threads.virtual.enabled: true` **已在**（FR-017 满足）；`server.port: 8080` **已在**（FR-024 的缺省值满足，`--port` 走 D6 覆盖）；`springdoc.swagger-ui.path: /swagger-ui` **已在**（FR-011 的 UI 落点满足）；`spring.autoconfigure.exclude` **不需要**（D5）。本节**不新增任何配置键**。

## D26 静态门禁注意点（写代码前先知道）

**决策**：三处按本仓既有口径处理。

1. **P3C `AvoidCommentBehindStatementRule`（行尾注释）**：第 25 节在 `CliAssemblyConfiguration.java` 的 `scheduler.setPoolSize(2); // FR-019…` 上被抓过——所有注释写**独立成行**。
2. **FindSecBugs `CRLF_INJECTION_LOGS`（CWE-117）**：`GlobalExceptionHandler` 里凡是把**异常对象**当变参打进日志的地方都可能被抓（第 25 节实测：常量格式串 + 已 sanitize 的字符串变参不带 `e` → 绿；带上 `e` → 红）。既有 `GlobalExceptionHandler` 的 catch-all 是 `log.error("internal error", ex)`，**目前绿**——新增的 handler 方法沿用既有写法（`log.error(<常量格式串>, ex)`），若被抓红就按父 pom 的既定口径**就地** `@SuppressFBWarnings("CRLF_INJECTION_LOGS")` 并附理由，不做全局 exclude。
3. **P3C `UndefineMagicConstantRule`（魔法值）**：`32 * 1024`、`100`、`365`、`1` 这类抽成具名常量（`MAX_MESSAGE_LENGTH` / `MAX_HISTORY` / `STATIC_CACHE_DAYS` / `VALIDATION_TIMEOUT_SECONDS`），与上游同款。
4. **FindSecBugs `SPRING_ENDPOINT` / `EI_EXPOSE_REP2`**：本节的六个 Controller 是**无认证 HTTP 端点**（spec「明确不做」认证）且构造器持有可变服务对象引用，两条规则大概率双红。上游 `SessionApiController` 的既定口径是**就地** `@SuppressFBWarnings({"SPRING_ENDPOINT","EI_EXPOSE_REP2"})` 并附理由（无认证是 spec 裁决、不是疏忽；引用不可变拷贝对服务层无意义）——本节沿用同款，**不做全局 exclude**。

**另**：`mvn -pl <module> <goal>` 一律带 `-am`（本仓纪律）；`spotless:apply` 改完代码必跑。

## D27 `ProfileLoader` 需要成为 Bean（FR-018 的结构性前提）

**决策**：`CliAssemblyConfiguration` 新增一个 `@Bean ProfileLoader profileLoader(Workspace)`，并让既有 `profileRegistry(Workspace)` **复用同一实例**（把现在方法体内联的 `new ProfileLoader(agentsDir, SUPPORTED_PROVIDERS)` 改为注入该 Bean）；`ProfileApiController` 注入 `ProfileLoader`，每次请求实时调 `loadAllDetailed()`。

**依据**：FR-018 要求 Agent 列表**含加载失败项与原因**（`valid:false` + `error`），而失败项只存在于 `ProfileLoader.loadAllDetailed()` 的 `LoadResult(loaded, failures)` 里——`ProfileRegistry` 只持**加载成功**的 Profile，拿不到失败原因。实测 `CliAssemblyConfiguration.java:130-132`：当前 `ProfileLoader` 是在 `profileRegistry(...)` 方法体里 `new` 出来的**局部变量**，容器里没有这个 Bean，Controller 无从注入。新增 Bean **不是新的对外概念**（`ProfileLoader` 是 core 的既有 public 类，Bean 声明是装配细节），不触发软门禁 ①。实时读文件、不缓存，与 `ContextLoader` / 记忆门面的「不缓存」哲学一致；`/profiles` 是低频管理面端点，每次读盘代价可忽略。

---

## 未决项

无。`checklists/requirements.md` 的「已知未决」三项全部落定：

| 未决项 | 落点 |
|---|---|
| 管理台构建串联方式 | **D1 / D2 / D3** |
| `/memory` 的 `{core, archival, truncated}` 怎么兑现 | **D4** |
| `/health` 的 Provider 字段措辞须回修契约 | **D16**（连带 D20 的四处字段名回修） |

「`未接入（第 26 节）` 两处占位」按 spec 明确**不在本节范围**（`StatusCommand` / `ProviderListCommand`），不产生任务。
