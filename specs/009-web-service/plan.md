# Implementation Plan: Web Service 与第一版管理平台（第 26 节）

**Branch**: `class-26`（feature 目录 `specs/009-web-service`） | **Date**: 2026-10-19 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/009-web-service/spec.md`；设计依据 `docs/class/第26节：Web Service 与第一版管理平台 实现与代码讲解.md` + `docs/TechnicalSolution.md` §7

**Phase 0**: [research.md](./research.md)（D1~D27） | **Phase 1**: [data-model.md](./data-model.md) · [contracts/rest-endpoints.md](./contracts/rest-endpoints.md) · [contracts/admin-console.md](./contracts/admin-console.md) · [quickstart.md](./quickstart.md)

---

## Summary

给底座补上**对外 HTTP 门面**（能力五），并顺带做出**第一版只读管理台**。业务系统不改 OryxOS 一行代码、不装 SDK，靠 `/api/v1` 的 10 个端点就能把 Agent 接进自己的流程；运维打开同一端口的 `/admin/` 就能看底座状态。

技术路线上，本节**几乎没有新机制**，全是「把既有能力接出去」：

| 要做的事 | 接的是哪个既有能力 | 本节新增 |
|---|---|---|
| 会话四端点 | `SessionService`（第 18 节）+ `AgentService.process`（第 17 节） | 归档态读写口（第 18 节预先挂给本节的账） |
| 无状态调用 | `AgentService.process` 的同一链路 | `processStateless` 一个方法 |
| Profile / Tool 查询 | `ProfileLoader`（第 16 节）+ `ToolRegistry.registered()`（第 20 节） | 零 |
| 长期记忆查询 | `MemoryService`（第 22 节） | 只读分区方法 `partition()` |
| 健康 / 信息 | `DataSource` + `ProviderProperties` + `RuntimeMXBean` | 一个薄探针 |
| 只读管理台 | 上面这批端点 | Vue 3 + Vite 静态产物 + `WebConfig` 的 SPA 回落 |

**关键约束**：`oryxos-core` 已有的处理链路**一行不改**（`ReActLoop` / `PromptBuilder` / `ToolExecutor` / `Profile` / `ProfileLoader` 的既有方法全部零改动），REST 只是第二个**触发源**（章程原则 IX）——发消息必须汇入与 CLI 完全相同的 `AgentService.process`，MUST NOT 在 HTTP 层另起链路。

---

## Technical Context

**Language/Version**: JDK 21（`maven.compiler.release=21`）；前端构建期用 Node v20.18.0（`frontend-maven-plugin` 自动下载，**运行期不需要 Node**）

**Primary Dependencies**: Spring Boot 3.5.16（`spring-boot-starter-web` 已在 `oryxos-web`）、springdoc-openapi（`springdoc-openapi-starter-webmvc-ui`，已在）、Spring AI Alibaba（既有，**本节不新增**）；前端 `vue ^3.5.13` / `vite ^6.0.7` / `@vitejs/plugin-vue ^5.2.1`（构建期依赖，产物是静态文件）

**Storage**: SQLite（`sessions` / `tool_invocations` / `llm_calls` / `memory_entries`），**本节不新建表、不改 `schema.sql`**；长期记忆默认后端是 `.oryxos/memory/MEMORY.md` 文件

**Testing**: JUnit 5 + Spring Boot Test + MockMvc（`@WebMvcTest` 切片）；集成冒烟 `@SpringBootTest(webEnvironment=RANDOM_PORT)` 打 `@Tag("integration")`（根 pom 的 `<excludedGroups>integration</excludedGroups>` 默认跳过，`-DexcludedGroups=none` 显式放开）；静态门禁随 `mvn verify`（Spotless / P3C-PMD / Checkstyle / SpotBugs+FindSecBugs / OWASP）

**Target Platform**: Linux 服务器 / WSL2 开发机；单进程 fat JAR（`oryxos-boot/target/oryxos.jar`）

**Project Type**: Maven 多模块单体（9 模块），本节新增的是 `oryxos-web` 模块内的 REST 层 + 一个内嵌静态管理台

**Performance Goals**: 查询类端点本地毫秒级；发消息端点耗时由模型决定（同步阻塞，虚拟线程承载并发）；SC-008 要求「并发请求全部得到响应」，核心阶段靠 virtual thread + SQLite WAL 承载，不做压测指标

**Constraints**:
- 单条消息 ≤ 32KB（FR-008）；历史返回 ≤ 100 条（FR-009）
- 500 响应体 MUST NOT 含内部异常描述（连接串、堆栈），完整信息只进日志（FR-005）
- 全程同步阻塞，MUST NOT 出现 Reactor / `CompletableFuture` / 自建线程池（FR-017，H4 ⑤）
- 凭证只走环境变量，`${ENV_VAR}` 读时解析，不明文落库（H4 ③）
- 无认证（内网假设，扩展阶段补 API Key + JWT）
- 前端 MUST NOT 引外部 CDN（字体 / 图标）——内网部署必须能离线跑
- 不新增任何 Maven 第三方依赖（`spring-boot-starter-test` 是 test 作用域的既有测试基建）

**Scale/Scope**: 10 个 REST 端点 / 6 个 Controller / 1 个管理台（5 页）；约 30 个新增 Java 文件（含 DTO 与测试）+ 1 个前端工程

---

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| # | 原则 | 本节是否触碰 | 判定 |
|---|---|---|---|
| I | JDK 21 + Spring Boot 3.x 单体、单二进制、同步阻塞 + virtual thread | 是——`serve` 起 servlet 容器；新增前端构建期工具链 | **PASS**：仍是单个 fat JAR、单进程；`spring.threads.virtual.enabled=true` 已在（FR-017 满足）；无响应式类型引入。`frontend-maven-plugin` 只**构建期**跑，运行期零 Node 依赖 |
| II | 核心阶段只交付运行时内核；新依赖须给理由 | 是——管理台是「能力五」的交付物之一（技术方案 §7） | **PASS**：Web Service 是五大核心能力之一；管理台由技术方案 §7 明确列入本节交付。**Maven 侧零新第三方依赖**；前端 npm 依赖是**构建期**依赖、产物是静态文件，对照原则 II 的理由：课件/技术方案把管理台定为本节交付物，且它验证了「API 是完备的对外通道」 |
| III | 自实现 ReAct loop；Spring AI 只用一半；禁用自动 tool 执行 | 否（不触碰） | **PASS**：REST 走 `AgentService.process` → `ReActLoop` → `ToolExecutor`，自动执行路径第 17 节就已禁用，本节零改动。`mvn dependency:tree` 实测 `spring-ai-autoconfigure-*` 工件数 = **0**（research D5） |
| IV | Agent 目录不是 Tool | 否 | **PASS**：`/profiles` 只读 `ProfileRegistry`，不进 `ToolRegistry` |
| V | Tool 体系统一抽象、单一模块 | 否（只消费） | **PASS**：`/tools` 读 `ToolRegistry.registered()`，不新增 Tool 来源、不拆模块 |
| VI | 审计 Day One 落库；钟推/人推同路径 | 是——REST 是新的**人推入口** | **PASS**：发消息汇入 `AgentService.process`，`llm_calls` / `tool_invocations` 由链路内部写；`/invoke` 走 `processStateless`，**同一 `reActLoop.run` 路径**，审计照落。本节**新增审计代码 0 行** |
| VII | 接口先行，升级只新增实现类、不改接口 | 是——`LongTermMemoryStore` 加 `partition()` | **⚠ 需正当理由**（见 Complexity Tracking C1）：这是**新增读取能力**而非实现档升级，纯新增方法、不改任何既有签名、四个实现类全补齐、`MemorySource`（core 接缝）不动。契约兑现不了才是更大的问题 |
| VIII | SQLite + JPA；状态外置；凭证环境变量；不外发数据 | 是 | **PASS**：不新建表；会话/记忆/审计全在 SQLite 与文件系统，实例无不可重建状态；凭证仍走 `${ENV_VAR}`；管理台由本进程同源提供，**MUST NOT 引外部 CDN**（离线内网可跑） |
| IX | 三触发源复用同一条链路 | 是——这是本节的核心 | **PASS**：FR-002 明令「发消息端点 MUST 汇入与 CLI 完全相同的处理入口」；判卷在 `SessionApiControllerTest` 的「处理入口恰被调用一次」 |
| X | 每个 user story 有可演示 Demo | 是 | **PASS**：US1~US4 各有 Independent Test；US1 单跑即完整可演示（建会话→发消息→查历史→归档） |
| 附加 | 依赖方向：core 不反向依赖能力模块 | 是——新增 5 条模块边 | **PASS**：`oryxos-web → {core, provider, memory, tool}`、`oryxos-cli → oryxos-web`，**全部单向**，四个能力模块都不依赖 `oryxos-web`（见 §Project Structure 的依赖边表） |
| 附加 | 安全基线：最小权限白名单 | 否（沙箱不动） | **PASS**：`WhitelistSandbox` 第 24 节已就位、本节零改动。**新增的暴露面**是无认证 HTTP 端点——这是 spec 的明确边界（内网假设），已在 §Complexity Tracking C2 记录 |

**Gate 结论（Phase 0 前）**：无 CRITICAL 违规。两处需正当理由（C1 / C2），均已在 Complexity Tracking 给出。

**Gate 结论（Phase 1 后复检）**：设计未引入新违规。逐条复核：

- 依赖边复检：`oryxos-web` 的四个依赖全部指向 core / 能力模块，**无环**（四个模块都不依赖 `oryxos-web`）；`oryxos-cli → oryxos-web` 是新边，`oryxos-web` 不反向依赖 cli。
- 契约复检：新增的对外概念只有 `MemoryPartition`（1 个 public 类型）与三个异常类；**零新配置键、零新表、零新 Profile 字段**。
- 副作用复检：`application.yaml` **零改动**；`schema.sql` **零改动**；`ReActLoop` / `PromptBuilder` / `ToolExecutor` / `ProfileLoader` / `Profile` **零改动**。
- H4 六条不变量：①涉外 IO 过沙箱——本节不新增涉外 IO（记忆读走 `partition()`，与第 22 节「记忆文件 IO 不过沙箱」同一条已裁决理由）；②审计落库——发消息与 `/invoke` 都走既有链路；③凭证——零明文，前端不接触任何凭证；④`session_id` 只在 `SessionId` / `SessionService` 内拼接——REST 层只提供三元组；⑤同步阻塞——无 Reactor / `CompletableFuture` / 自建线程池；⑥无 Spring AI 自动 tool 执行路径。

---

## Project Structure

### Documentation (this feature)

```text
specs/009-web-service/
├── plan.md                                  # 本文件
├── spec.md                                  # /speckit-specify 输出
├── research.md                              # Phase 0 输出（D1~D27）
├── data-model.md                            # Phase 1 输出
├── quickstart.md                            # Phase 1 输出
├── contracts/
│   ├── rest-endpoints.md                    # 10 个端点的逐字契约
│   └── admin-console.md                     # 只读管理台的静态/路由/只读纪律契约
├── checklists/requirements.md               # 规格质量清单
├── tasks.md                                 # /speckit-tasks 输出（本命令不创建）
├── manual-test-guide.md                     # 节级收尾时单独立卷（真人工验收向导）
└── acceptance-report.md                     # 节级收尾产物
```

### Source Code (repository root)

```text
oryxos-web/                                    ← 本节主战场
├── pom.xml                                    [修改] +4 条模块依赖边、+frontend-maven-plugin、+starter-test(test)
└── src/
    ├── main/
    │   ├── frontend/                          [新增] 管理台前端工程（Vue 3 + Vite）
    │   │   ├── package.json
    │   │   ├── vite.config.js                 base:'/admin/'，outDir '../resources/static/admin'，dev 代理 /api
    │   │   ├── index.html
    │   │   └── src/
    │   │       ├── main.js  App.vue  router.js  api.js
    │   │       ├── styles/tokens.css           设计令牌（抄 website/.vitepress/theme/custom.css）
    │   │       └── views/{SessionsView,ProfilesView,ToolsView,MemoryView,StatusView}.vue
    │   ├── java/io/oryxlabs/oryxos/web/
    │   │   ├── WebModule.java                   [既有] 占位类，不动
    │   │   ├── api/
    │   │   │   ├── ApiResponse.java             [既有] 信封，不动
    │   │   │   └── GlobalExceptionHandler.java  [修改] 补四类异常映射（404/503/504）
    │   │   ├── config/WebConfig.java            [新增] SPA 回落 + 静态资源缓存 + CORS
    │   │   ├── controller/
    │   │   │   ├── SessionApiController.java    [新增] 4 个端点
    │   │   │   ├── AgentApiController.java      [新增] 1 个端点
    │   │   │   ├── ProfileApiController.java    [新增] 1 个端点
    │   │   │   ├── MemoryApiController.java     [新增] 1 个端点
    │   │   │   ├── ToolApiController.java       [新增] 1 个端点
    │   │   │   ├── SystemApiController.java     [新增] 2 个端点
    │   │   │   └── dto/                         [新增] CreateSessionRequest / MessageRequest /
    │   │   │                                          InvokeRequest / SessionView / MessageView /
    │   │   │                                          MessageResponse / InvokeResponse / ArchiveResponse /
    │   │   │                                          ProfileView / MemoryView / ToolView /
    │   │   │                                          HealthView / InfoView
    │   │   ├── error/
    │   │   │   ├── SessionNotFoundException.java     [新增]
    │   │   │   ├── ProfileNotFoundException.java     [新增]
    │   │   │   └── AgentTimeoutException.java        [新增] 本节无抛出点（裁决 ②）
    │   │   └── health/DatabaseHealthProbe.java       [新增] DataSource.isValid(1)
    │   └── resources/static/admin/                   [构建产物，.gitignore]
    └── test/
        ├── java/io/oryxlabs/oryxos/web/
        │   ├── controller/{SessionApiControllerTest,AgentApiControllerTest,ProfileApiControllerTest,
        │   │               MemoryApiControllerTest,ToolApiControllerTest,SystemApiControllerTest}.java
        │   ├── api/GlobalExceptionHandlerTest.java
        │   ├── config/WebConfigTest.java
        │   └── AdminConsoleSourceTest.java           [新增] 只读纪律源码断言
        └── resources/static/admin/index.html         [新增] SPA 回落测试夹具——`-Dfrontend.skip=true`
                                                       时主资源目录为空，夹具只在测试 classpath 上，
                                                       永不进发行物（WebSmokeIT 只打 /api/**，不依赖它）

oryxos-core/                                   ← 会话归档态 + 无状态入口
└── src/
    ├── main/java/io/oryxlabs/oryxos/core/
    │   ├── session/Session.java               [修改] +status +archivedAt；restore 7→9 参
    │   ├── session/SessionService.java        [修改] +archive +countActive
    │   ├── session/JpaSessionService.java     [修改] 实现上述两方法；save 的更新列集合不动
    │   └── engine/AgentService.java           [修改] +processStateless（process 不动）
    └── test/java/io/oryxlabs/oryxos/core/
        ├── session/SessionServiceTest.java    [修改] 补归档幂等 / countActive
        └── engine/AgentServiceTest.java       [修改] 补 processStateless 不落库

oryxos-memory/                                 ← 只读分区
└── src/
    ├── main/java/io/oryxlabs/oryxos/memory/
    │   ├── MemoryService.java                 [修改] +partition()
    │   ├── MemoryServiceImpl.java             [修改] 实现（不缓存）
    │   └── store/
    │       ├── MemoryPartition.java           [新增] record(core, archival, truncated)
    │       ├── LongTermMemoryStore.java       [修改] +partition()
    │       ├── MarkdownMemoryStore.java       [修改] 与 load() 共用私有截断助手
    │       ├── SqliteMemoryStore.java         [修改] 同上
    │       ├── Mem0MemoryStore.java           [修改] truncated 恒 false
    │       └── InMemoryMemoryStore.java       [修改] 测试替身补齐
    └── test/java/io/oryxlabs/oryxos/memory/
        ├── MemoryStoreContractTest.java       [修改] 三档统一跑 partition 断言
        └── MemoryServiceImplTest.java         [修改] 门面不缓存 + **内联匿名实现**（接口加抽象方法
                                                  的第 5 处受影响点）补 `partition()` 桩

oryxos-storage/
└── src/main/java/io/oryxlabs/oryxos/storage/repository/SessionRepository.java
                                               [修改] +countByStatus（派生查询）

oryxos-cli/                                    ← 起容器 + 集成冒烟
├── pom.xml                                    [修改] +oryxos-web 依赖边
└── src/
    ├── main/java/io/oryxlabs/oryxos/cli/
    │   ├── CliApplication.java                [修改] scanBasePackages +web；javadoc 修订注
    │   ├── CliAssemblyConfiguration.java      [修改] +`@Bean ProfileLoader`（research D27，FR-018 的
    │   │                                          结构性前提），`profileRegistry(...)` 复用同一实例
    │   └── command/ServeCommand.java          [修改] .web(SERVLET) + System.setProperty("server.port", …)；
    │                                              连带修订——类 javadoc「不起 HTTP 端口 / MUST NOT 依赖
    │                                              oryxos-web」段挂 2026-10-19 修订注改写、`--port` 的
    │                                              description「端口在第 26 节启用」兑现、三条占位
    │                                              println 改为真实启动提示（`keepAlive()` 保留）
    └── test/java/io/oryxlabs/oryxos/cli/
        └── WebSmokeIT.java                    [新增] @Tag("integration") 真上下文冒烟

oryxos-boot/
├── pom.xml                                    [修改] <mainClass> 注释（去掉「并存两个 @SpringBootApplication」）
└── src/main/java/io/oryxlabs/oryxos/boot/OryxosApplication.java
                                               [删除] 裁决 ③

.github / 根目录
├── .gitignore                                 [修改] +**/node_modules/ +frontend/dist/ +resources/static/admin/
├── CLAUDE.md                                  [修改] OryxosApplication「去留由第 26 节定」→ 事实陈述
├── .claude/skills/oryxos-admin-ui/SKILL.md    [修改] 回修过期令牌 / 信封 / 「会话列表」
├── website/guide/quick-start.md               [修改] fat JAR 主类（已过期）
├── website/en/guide/quick-start.md            [修改] 同上
└── specs/000-runtime-kernel/contracts/rest-api.md
                                               [修改] 挂 2026-10-19 修订注（字段名 / health 措辞 / 两条新错误文案）
```

**模块依赖边（本节新增 5 条，全部单向）**

```text
oryxos-web  ──→ oryxos-core      (AgentService / SessionService / ProfileRegistry / Session)
oryxos-web  ──→ oryxos-provider  (ProviderProperties：/health 的 providers 键集合)
oryxos-web  ──→ oryxos-memory    (MemoryService：/memory)
oryxos-web  ──→ oryxos-tool      (ToolRegistry.registered()：/tools 的 source / origin)
oryxos-cli  ──→ oryxos-web       (serve 起 servlet 容器后要扫到 @RestController)
```

`oryxos-web` **MUST NOT** 依赖 `oryxos-storage`（会话数走 `SessionService.countActive()`，不让「读会话」出现两个入口）。

**包结构的一处有意分歧**：`ApiResponse` / `GlobalExceptionHandler` 保留在既有的 `web/api/` 包（第 18 节骨架期就已入库），新增的 Controller / DTO / error / config 用上游同名的 `web/controller` · `web/controller/dto` · `web/error` · `web/config`。**不为包名对齐而搬动已交付文件**——那是一次纯 churn 的 diff，收益只是好看。

**Structure Decision**：本节的产物集中在 `oryxos-web`（技术方案 §7.1 的落位表：Controller / 异常处理 / static-admin → `oryxos-web`），加上 `oryxos-core` / `oryxos-memory` / `oryxos-storage` / `oryxos-cli` 的**最小必要**改造。`oryxos-boot` 只删一个文件、改一句注释。

---

## Complexity Tracking

> 仅记录 Constitution Check 里需要正当理由的项。

| # | 项 | 为什么需要 | 更简单的替代为什么被否决 |
|---|---|---|---|
| **C1** | 给 `LongTermMemoryStore` / `MemoryService` 加 `partition()`（原则 VII 说「升级 MUST NOT 改接口」） | 已入库契约 `rest-api.md` 定的响应形状是 `{core, archival, truncated}`，而既有读记忆接缝只返回**一整段文本**。不新增这个只读方法，契约就兑现不了 | ①**解析 `load()` 的分区标题**——脆弱（标题是各档内部格式，改标题就静默拆错），且 `checklists/requirements.md:60` 记的「sqlite 档没有标题」**与事实不符**（实测 `SqliteMemoryStore.load()` 同款标题），照它选会建立在错误前提上；②**在 `MemorySource`（core 接缝）上加**——那会让 core 知道 `oryxos-memory` 的内部概念（记忆分核心/归档区）。**缓解**：纯新增方法、零既有签名改动、四个实现类全补齐、`MemorySource` 不动；`partition()` 与 `load()` 共用同一个私有截断助手，两处口径不可能漂移 |
| **C2** | 新增的 HTTP 端点**无认证** | spec 明确把认证列为边界（「假设内网部署，扩展阶段补 API Key + JWT」），FR-001~FR-024 里没有任何鉴权要求 | 核心阶段做鉴权 = 撞原则 II（企业级治理层不在核心阶段）。**缓解**：默认只监听本机可达的部署形态由部署方决定；`/admin/` 与 `/api/v1` 同源同端口；响应体不含内部异常描述（FR-005）；**验收报告 MUST 把这个暴露面写成已知边界**，MUST NOT 说成「已安全」 |
| **C3** | 构建期引入 Node 工具链（`frontend-maven-plugin` 下载 Node v20.18.0） | 管理台是本节交付物，要让它随 `mvn package` 一起进 fat JAR；上游同款做法 | ①**手动 `npm run build` 后再打包**——会随「改了前端忘了 build」静默产出旧页面（与第 24 节向导抓红同类：看起来成功的错误）；②**产物入库**——造出「源码改了、产物没跟着改」的第二真相。**缓解**：`-Dfrontend.skip=true` 提供纯 Java 快构建；运行期**零** Node 依赖；产物与 `node_modules` 均 gitignore |

---

## 需要说明的设计偏离（D-items）

> 完整论证见 [research.md](./research.md)，此处列**结论与落点**，供 reviewer 对照。

| # | 偏离 | 落点 | 性质 |
|---|---|---|---|
| **P1** | **课件 §二.4 的 `spring.autoconfigure.exclude` 不适用、MUST NOT 加** | `application.yaml` **零改动** | 课件与事实冲突。实测：classpath 上 `spring-ai-autoconfigure-*` 工件数 = **0**；反编译 `spring-boot-autoconfigure-3.5.16.jar` 核实（2026-10-19）：`checkExcludedClasses` 先过 `ClassUtils.isPresent`，**类不存在时该项被静默忽略**——照抄不会炸，但是一段**死配置**（让人误以为有急切自动装配在跑），不照抄的理由是「不适用 + 误导」。FR-010 靠结构满足，判卷在 `WebSmokeIT`（只给 `DEEPSEEK_API_KEY` 起真上下文）。（*修订注，2026-10-19：本行初稿写「照抄会把服务起不来」，复核被反编译证据推翻，见 research D5。*） |
| **P2** | **`/health` 的 providers 语义收窄为「配置就绪 / 未配置」**（字面量仍是 `UP`/`DOWN`） | 契约 `rest-api.md` 挂 2026-10-19 修订注 | 裁决 ⑤。取 `ProviderProperties.SUPPORTED_PROVIDERS` 作键集合，是为了让 `"DOWN"` **真的可达**（否则它是结构上不可达的死分支，与第 25 节 Step 3 那行 WARN 同类） |
| **P3** | **四个字段名对齐上游**：`user`→`userId`、`response`→`reply`、请求 `message`→`content`、`channel:"api"`→`"web"` | 契约 `rest-api.md` 挂修订注；`Session.channel()` / `SessionEntity.channel` 的 javadoc 示例同步 | 裁决 ⑪「只对齐冲突字段名，保留本仓更丰富的形状」。连带两处已交付 javadoc 失效 |
| **P4** | **新增两条错误文案**：`session is archived: <id>`（400）、`agent not found: <name>`（404） | 契约 `rest-api.md` 错误表 | spec 的 Edge Case 与 US2 场景 3 要求这两个语义，契约里原本没有对应文案 |
| **P5** | **`ProviderUnavailableException` 放 `oryxos-provider`**（上游放 `oryxos-web/error/`） | `oryxos-provider` | 我们的抛出方是 `ProviderService`（provider 模块）；上游的 503 异常只在模型列举路径抛，LLM 调用链**从不抛它**——放 web 会让「谁抛」与「谁定义」分家。**约束**：包装后的消息 MUST 保留 cause 的 message（既有 `ProviderServiceTest` 断言 `hasMessageContaining("timeout")`，反作弊纪律下不得放宽） |
| **P6** | **不绑 `npm test` execution**（上游有第四个 execution） | `oryxos-web/pom.xml` | 本节前端无测试文件；挂一个空 `npm test` 要么无意义、要么因无测试而非零退出**红掉全量门禁**。只读纪律的判卷改由 `AdminConsoleSourceTest` 承担 |
| **P7** | **管理台设计令牌以 `website/.vitepress/theme/custom.css` 为准**（课件 §三.5 那份是过期值） | `.claude/skills/oryxos-admin-ui/SKILL.md` 回修 | 课件自己写着「值直接抄首页 token，一个字别自创」，而它给的清单与首页实际值不符 |
| **P8** | **会话页定为「按会话 ID 查询」**（skill 原写「会话列表」调 `GET /sessions`） | 同上 skill 回修 | 本节的 10 端点契约（`rest-api.md`）里**没有** `GET /api/v1/sessions`，新增即契约外 REST 路径（软门禁 ①）；上游虽有该列表端点，本仓按既定契约不新增（裁决 ⑧）。照 skill 写会造出契约外端点。（*修订注，2026-10-19：本行初稿写「列表端点归 29/30 节」，无依据——spec「明确不做」未派给它，且上游已有该端点。*） |
| **P9** | **`serve` 用 `System.setProperty("server.port", …)`** 而非 builder 的 `properties(...)` | `ServeCommand` | `properties(...)` 落的是 **defaultProperties（优先级最低）**，会被 `application.yaml` 的 `server.port: 8080` 盖掉 → `--port` 静默无效。上游同款 |
| **P10** | **`DELETE` 归档幂等**（上游对已归档返回 404） | `SessionService.archive` + 契约 | FR-021 明令幂等；且契约已定 `{sessionId, status, archivedAt}` 形状。会话**从未存在**才 404——与「重复归档」是两回事 |
| **P11** | **`oryxos-web` 依赖 `oryxos-tool`**（上游不依赖） | `oryxos-web/pom.xml` | `/tools` 要 `source` / `origin`，而 core 的 `ToolCatalog` 接缝刻意只有 `find` / `availableFor`，**没有**「列全量带来源」的形状；为一个 REST 视图往 core 接缝加方法是让 core 知道装配期概念 |
| **P12** | **`ProcessStateless` 的瞬时会话 id 不走 `SessionId`** | `AgentService.processStateless` | 无状态调用**不写 `sessions` 表**，故不参与持久会话的拼接口径（H4 ④ 指的是持久会话）。id 形如 `invoke-exec:<uuid>`，与上游同款 |
| **P13** | **删除 `OryxosApplication`** + 三处连带 | 裁决 ③ | 宽扫描根是反向撞车的潜伏来源；自第 18 节起已不是 fat JAR 主类。连带修 `oryxos-boot/pom.xml` 注释、两份 `website/*/guide/quick-start.md:35`、`CLAUDE.md` |
| **P14** | **`scanBasePackages` 加 `io.oryxlabs.oryxos.web`，不放宽到 `io.oryxlabs.oryxos`** | `CliApplication` | 宽根会把 `OryxosApplication` 扫进来 → Repository Bean 注册两遍 → `BeanDefinitionOverrideException`（第 18 节实测过） |
| **P15** | **契约里两处失效表述的留痕**：`checklists/requirements.md:60`「sqlite 档没有标题」与 `JpaSessionService`/`SessionRepository` 的「本节不做 / 本节不新增」 | 三处挂修订注 | 前者的**前提是错的**（实测 sqlite 档发同样的标题）；后两者是第 18 节明确挂给本节的账，属**已列明的改造点**，不是软门禁 ④ 违规 |

### 软门禁披露（Step 4 完成报告里同步报出，等用户确认）

| 软门禁 | 事项 | 处置 |
|---|---|---|
| ④ 改前序节公共接口 | `LongTermMemoryStore` + `partition()`、`MemoryService` + `partition()`、`AgentService` + `processStateless`、`Session` + `status`/`archivedAt`、`SessionService` + `archive`/`countActive`、`SessionRepository` + `countByStatus` | **全部纯新增、零既有签名改动**；`Session.restore` 7→9 参（实测无参数个数门禁）。`AgentService.process` / `MemorySource` / `LongTermMemoryStore` 既有方法 / `SessionService` 既有方法**一行不改** |
| ④（已列明改造点） | `Session` 归档态、`SessionService.archive` | 第 18 节 javadoc **逐字写着**「归档（`status` / `archived_at` 的第二态归第 26 节）」与「否则第 26 节一归档就被下一次 save 悄悄复活」→ 属本节改造点，不构成 ④ 违规 |
| ④（已列明改造点） | `oryxos-cli/pom.xml` 的「MUST NOT 加 `oryxos-web`」、`CliApplication` javadoc 的「MUST NOT 起 SERVLET 容器 / MUST NOT 依赖 `oryxos-web`」 | 那是**第 18 节的节内范围表述**，本节正是那个「第 26 节」；挂带日期（2026-10-19）修订注后改写 |
| ① 新增对外概念 | `MemoryPartition`（1 个 public record）+ 三个异常类 + 13 个 DTO | 全部在 plan/tasks 的交付物清单内；**零新配置键、零新表、零新 Profile 字段** |
| ③ 课件 vs 技术方案冲突 | **未发现**。唯一冲突是课件 vs **本仓事实**（`spring.autoconfigure.exclude`，P1） | 已按本仓纪律以事实为准并如实记录 |

---

## 验证策略

| 层 | 判据 | 落点 |
|---|---|---|
| 契约单测 | 每个端点的形状、状态码、边界（32KB / ≤100 条 / 幂等） | `oryxos-web` 六个 `*ApiControllerTest` + `GlobalExceptionHandlerTest` + `WebConfigTest` |
| 只读纪律 | 管理台源码里只出现 GET、路径都在 `/api/v1` 下、五页存在且各引其端点 | `AdminConsoleSourceTest` |
| 领域单测 | 归档幂等 / countActive / processStateless 不落库 / 三档 `partition()` 一致 | `SessionServiceTest` / `AgentServiceTest` / `MemoryStoreContractTest` |
| 集成冒烟 | 真 SERVLET 上下文起得来、四个查询端点真链路可达（Bean 装配 + 扫描范围没炸） | `WebSmokeIT`（`@Tag("integration")`，CI 默认跳过） |
| 静态门禁 | Spotless / P3C / Checkstyle / SpotBugs+FindSecBugs / OWASP | `mvn clean verify` |
| 真人工项 | 10 端点 curl 真链路（含真模型）、CLI↔API 共享存储、断 Provider 拿 503、并发压一把、管理台五页渲染真实数据、文档页齐全 | 节级收尾的 `manual-test-guide.md` |

**实现完成的定义**：`mvn clean verify` 全绿。

---

## 已知边界（写进验收报告，MUST NOT 假装验过）

| 项 | 为什么不可达 |
|---|---|
| **60 秒超时 → 504 的真链路** | 裁决 ②：强制掐断会撞 H4 ⑤（同步阻塞）。`AgentTimeoutException` 本节**无抛出点**，只落类型与映射；判卷在 `GlobalExceptionHandlerTest`。与第 25 节 Step 3「结构上不可达」同类 |
| **`/health` 的 providers 报 `"DOWN"`** | 本仓 `SUPPORTED_PROVIDERS = {deepseek}`，声明了即 `validate()` 保证凭证非空；`"DOWN"` 只在「支持但未声明」时出现，判卷在 `SystemApiControllerTest` |
| **`mem0` 记忆档的 `/memory`** | 该档经第 22 节裁决**永久不验收**；`/memory` 的真链路实测只覆盖 `markdown` / `sqlite` 两档 |
| **并发压测的量化指标** | SC-008 只要求「请求全部得到响应」，不设 QPS 阈值；核心阶段不引压测基建 |
| **无认证** | spec 的明确边界（内网假设），扩展阶段补 API Key + JWT |
