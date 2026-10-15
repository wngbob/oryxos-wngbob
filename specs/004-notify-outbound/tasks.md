---
description: "Task list for Notify 出站推送（第 19 节）"
---

# Tasks: Notify 出站推送 —— 给 Agent 补上「结果主动送出去」的出口

**Input**: Design documents from `/home/wngbob/oryxos-wngbob/specs/004-notify-outbound/`

**Prerequisites**: [plan.md](plan.md)（必需）、[spec.md](spec.md)（必需，含 8 条冲突仲裁）、[research.md](research.md)（D1~D15）、[data-model.md](data-model.md)、[contracts/](contracts/)（三份）、[quickstart.md](quickstart.md)

**Tests**: 本节**明确要求**测试（课件「四、验收 harness」）——4 个测试类，测试任务先于或伴随对应实现任务。

**Organization**: 按 user story 分组。US1 / US2 为 P1，US3 为 P2。US1（适配器）与 US2（工具 + 注册表 + 表）**互不依赖**，可并行；US3 是**核对型 story**，不产生产代码。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无依赖）
- **[Story]**: 所属 user story（US1 / US2 / US3）
- 每个任务带**确切文件路径**

## Path Conventions

Maven 多模块，路径相对仓库根。本节涉及模块：`oryxos-tool`（主体）、`oryxos-storage`（表与存取）。**`oryxos-core` / `oryxos-cli` / `oryxos-boot` 零改动**（FR-015 / FR-016）。

**全局纪律（贯穿全部任务）**：

- 测试方法名**必须英文**（驼峰或 snake_case）；课件中文名译成语义等价的英文名，课件原文进 `@DisplayName`
- 只创建交付物点名的对外概念；已定字面量逐字保真；异常不吞；不建文档外抽象层；注释只写「为什么」
- 避开 P3C/ASM 解析不了的 Java 18+ 语法（增强 `switch` 的 `default ->`）；构造函数前不放 `//` 行注释（触发 `CommentsMustHaveJavadocFormatRule`）
- **MUST NOT 出现任何明文凭证**；渠道地址（含 URL 里的 token）在对话 / 日志 / 报错 / 命令输出四处都不得回显（FR-019）
- 实现与测试一起落地，跑该模块测试，红了当场修
- **单模块 Maven 命令一律带 `-am`**（否则静默取 `~/.m2` 里的旧字节码）

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 依赖显式化——先让 `oryxos-tool` 能看见 `RestClient`、`@Tool` 与渠道表

- [X] T001 改 `oryxos-tool/pom.xml`：新增三个 compile 依赖 `org.springframework:spring-web`（`RestClient`，仲裁 6）、`org.springframework.ai:spring-ai-model`（`@Tool`，仲裁 5）、`io.oryxlabs:oryxos-storage`（`NotifyChannelRepository`，[research.md](research.md) D1）——**三者都不写 `<version>`**（前两者由 BOM 托管，第三者由父 pom 的 `dependencyManagement` 托管）；再新增两个 test 依赖 `org.springframework.boot:spring-boot-starter-test`（**不写版本**）与 `com.squareup.okhttp3:mockwebserver3:5.5.0`（**MUST 写版本号**，无 BOM 托管，仲裁 7）；**外加 `org.jetbrains.kotlin:kotlin-stdlib`（test scope）+ 模块级 `dependencyManagement` 把它钉在 `2.1.21`**——实测踩坑：Boot BOM 的 `kotlin.version=1.9.25` 覆盖了 okhttp-jvm 声明的 `2.1.21` 而本机无 `1.9.25`，离线构建直接 FAILURE（**软门禁 ⑥ 报批项，用户裁决「钉 2.1.21」**，[research.md](research.md) 依赖核实记录）

**Checkpoint**: `./mvnw -o -pl oryxos-tool -am dependency:tree | grep -E "spring-web|spring-ai-model|oryxos-storage|mockwebserver3|kotlin-stdlib"` 五条边全部命中

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 渠道的持久化地基——表 + 实体 + 存取。**US2 强依赖本阶段**；US1 不依赖（适配器不碰表，它的目标由测试手工构造）。

**⚠️ 说明**：本节把这一层放在 Foundational 而非 US2 的 phase 里，理由是它是一刀自包含的存储切片（不依赖任何 user story 的代码），且被 US2 的实现与 `oryxos-storage` 的测试类共同引用。**US1 可不等本阶段完成就开工**（见 §Dependencies）。

- [X] T002 [P] 新增 `oryxos-storage/src/main/java/io/oryxlabs/oryxos/storage/entity/NotifyChannelEntity.java`：`@Entity @Table(name = "notify_channels")`；字段 `name`（`@Id`，**无 `@GeneratedValue`**，主键是人指定的注册名）/ `type` / `url` / `configJson`（`@Column(name = "config_json")`）/ `description`；**全部字段是 `String`**，MUST NOT 出现 `NotifyTarget` / `NotifyChannelDef` / `Profile` 等 core 或 tool 类型（[data-model.md](data-model.md) §3、[contracts/notify-channel-registry.md](contracts/notify-channel-registry.md) §3.1）；`configJson` 保持**原始字符串**，MUST NOT 用 `@Convert` 或在此层解析 JSON（§3.3）；无参构造 + getter/setter；`equals`/`hashCode` 按 `name`
- [X] T003 [P] 新增 `oryxos-storage/src/main/java/io/oryxlabs/oryxos/storage/repository/NotifyChannelRepository.java`：`extends JpaRepository<NotifyChannelEntity, String>`；**只新增一个派生查询** `Optional<NotifyChannelEntity> findFirstByOrderByNameAsc()`（默认渠道，[research.md](research.md) D6）；**MUST NOT 新增任何写入方法**（FR-014）
- [X] T004 改 `oryxos-storage/src/main/resources/db/schema.sql`：**追加**（既有三表不动）`notify_channels` 建表语句，列名与语义**逐字照 A6**——`name VARCHAR(128) PRIMARY KEY`、`type VARCHAR(32)`、`url VARCHAR(1024)`、`config_json TEXT`、`description VARCHAR(512)`；用 `CREATE TABLE IF NOT EXISTS`；**MUST NOT 加 `NOT NULL`**（与既有三表风格一致）；**MUST NOT 写含分号的注释**（脚本按 `;` 切分逐条执行，既有文件头已写明这条踩坑记录）（FR-012、[data-model.md](data-model.md) §2、[contracts/notify-channel-registry.md](contracts/notify-channel-registry.md) §4）
- [X] T005 新增 `oryxos-storage/src/test/java/io/oryxlabs/oryxos/storage/repository/NotifyChannelRepositoryTest.java`：沿用 `SessionRepositoryTest` 的既有基建（`@DataJpaTest` + `@AutoConfigureTestDatabase(replace = NONE)` + `@Transactional(propagation = NOT_SUPPORTED)` + `@DynamicPropertySource` 指向 SQLite 临时文件 + 读 classpath `db/schema.sql` 后 `split(";")` 逐条执行）；三组断言——① 建表列齐备（`PRAGMA table_info(notify_channels)` 得到五列且列名逐字正确）② 存取往返（存一行 → 按 `name` 读回，五个字段值逐字一致）③ **默认渠道不依赖插入顺序**（乱序插入三行，`findFirstByOrderByNameAsc()` 始终返回 `name` 最小者）

**Checkpoint**: `./mvnw -o -pl oryxos-storage -am test -Dtest=NotifyChannelRepositoryTest` 全绿

---

## Phase 3: User Story 1 - 说一声，消息真的进了群 (Priority: P1) 🎯 MVP

**Goal**: 给一个地址发一次 HTTP POST，内容按 JSON 送出；失败明确上抛、不静默吞。

**Independent Test**: 本地起假 webhook，对一个指向它的通知目标发消息，断言 POST 体里带 `content`、URL 来自配置而非硬编码；让假 webhook 返回 5xx，断言异常上抛。全程不访问外网。

### Tests for User Story 1 ⚠️

- [X] T006 [US1] 新增 `oryxos-tool/src/test/java/io/oryxlabs/oryxos/tool/notify/WebhookNotifyAdapterTest.java`（MockWebServer，**用真实 `WebhookNotifyAdapter`，不 mock**）：八组断言——① POST 体里带 `content`（解析 `RecordedRequest.getBody()` 断言字段值相等）② **URL 来自 `NotifyTarget.config` 而非硬编码**（两个不同端口的 MockWebServer，请求分别落在各自服务上）③ 内容含中文 / 换行 / 引号时**逐字一致** ④ 5xx → 异常上抛且 `getRequestCount() == 1`（**不重试**）⑤ 4xx → 同样上抛 ⑥ 不可达（连接被拒）→ 上抛 ⑦ **超时 → 上抛**（用包级私有构造 `new WebhookNotifyAdapter(Duration.ofMillis(200))` + `bodyDelay` 远大于超时的 `MockResponse`，[research.md](research.md) D13）⑧ `url` 为空 / 空白 → `IllegalArgumentException` 且 `getRequestCount() == 0`（一次请求都不发）。**MUST NOT 断言响应体的业务错误码**（2xx 即送达，[research.md](research.md) D4）

### Implementation for User Story 1

- [X] T007 [US1] 新增 `oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/notify/WebhookNotifyAdapter.java`：`implements NotifyChannelAdapter`；`public WebhookNotifyAdapter()` 内部构造 `RestClient`——`JdkClientHttpRequestFactory` + `HttpClient.newBuilder().connectTimeout(10s).followRedirects(Redirect.NEVER).build()` + `factory.setReadTimeout(10s)`（**`Redirect.NEVER` MUST 显式写出**，[research.md](research.md) D5）；另有一个**包级私有**构造 `WebhookNotifyAdapter(Duration pushTimeout)` 供同包测试注入短超时（**MUST NOT 是 public/protected**，[research.md](research.md) D2）；`send` 从 `target.config().get("url")` 取地址（**空白即 `IllegalArgumentException`，且一次请求都不发**）；POST 一次，body 为含 `content` 字段的 JSON；**非 2xx 与连接/读取异常原样上抛，MUST NOT 包装、MUST NOT 重试、MUST NOT 吞**；**MUST NOT 把 `url` 写进日志或异常消息**；**MUST NOT 带任何 Spring 注解**（FR-016）

**Checkpoint**: `./mvnw -o -pl oryxos-tool -am test -Dtest=WebhookNotifyAdapterTest` 全绿

---

## Phase 4: User Story 2 - 推到哪是配置，不是对话内容；出站不能绕过白名单 (Priority: P1)

**Goal**: 模型只说内容（+ 可选渠道名）；渠道按注册名从全局注册表解析；解析不到明确报错；**`enforce` 先于 `send`**。

**Independent Test**: 用替身注册表、替身沙箱、替身适配器驱动通知工具，断言不传渠道名时取默认渠道、渠道未配置或名字解析不到时报明确错误、且 `enforce` 先于 `send` 被调用（`InOrder` 顺序断言）。

### Tests for User Story 2 ⚠️

- [X] T008 [US2] 新增 `oryxos-tool/src/test/java/io/oryxlabs/oryxos/tool/notify/NotifyToolsTest.java`（Mockito mock `Sandbox` / `NotifyChannelAdapter` / `NotifyChannelRegistry`）：六组断言——① **★课件关键回归**：`@DisplayName("发送前必须先过白名单校验")` 的 **`InOrder` 顺序断言**（`inOrder.verify(sandbox).enforce(any(SandboxAction.class))` → `inOrder.verify(adapter).send(any(), anyString())` → `verifyNoMoreInteractions()`，断言骨架见 [contracts/notify-outbound.md](contracts/notify-outbound.md) §3.3）② `enforce` 抛异常时 `verify(adapter, never()).send(any(), any())` ③ 渠道未配置 → `IllegalStateException`（明确报错，非静默失败）④ 渠道名缺省（`null` / `""` / `"   "` 三种）→ 送到默认渠道 ⑤ 渠道名解析不到 → `IllegalStateException` 且**未退回默认渠道** ⑥ 成功返回 `ToolResult.success(...)`、**MUST NOT 返回 `retryableFailure`**，且返回的 content 里**不含渠道地址**（FR-019）；另含 US3 的替身接入断言（见 T012）
- [X] T009 [P] [US2] 新增 `oryxos-tool/src/test/java/io/oryxlabs/oryxos/tool/notify/JpaNotifyChannelRegistryTest.java`（Mockito mock `NotifyChannelRepository`）：逐条覆盖 [data-model.md](data-model.md) §5 的六步映射与 [contracts/notify-channel-registry.md](contracts/notify-channel-registry.md) §1.1 的六行输入——① `null` / 空串 / 纯空白 → 走 `findFirstByOrderByNameAsc()` ② 表为空 → `IllegalStateException`（文案含「未配置任何通知渠道」，**与「名字打错」的文案可区分**）③ 名字存在 → 按 `findById` 取到该行 ④ 名字打错 → `IllegalStateException` 且**未退回默认渠道**、文案含该名字 ⑤ `config` 恒含 `url` 键（取值来自 `url` 列；`url` 列为 `null` 时为 `""`；`config_json` 里的同名键被覆盖）⑥ `config_json` 为空 / 空串 → 空配置（不报错）；非 JSON 对象或值非字符串 → `IllegalArgumentException`（不静默转字符串）；`type` 为空白 → `IllegalArgumentException`

### Implementation for User Story 2

- [X] T010 [P] [US2] 新增 `oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/notify/NotifyChannelRegistry.java`：`public interface`，**只有一个方法** `NotifyTarget resolve(String channelName)`；**MUST NOT 出现写入方法**（`save`/`delete`/`add`/`remove`/`import`）、**MUST NOT 出现** `list`/`find`/`exists`；返回类型是 `NotifyTarget` 而非 `Optional`（「解析不到」是**错误**，FR-006）；**MUST NOT 带 Spring 注解**（FR-014、[contracts/notify-channel-registry.md](contracts/notify-channel-registry.md) §1）
- [X] T011 [US2] 新增 `oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/notify/JpaNotifyChannelRegistry.java`：`implements NotifyChannelRegistry`；构造只接受 `NotifyChannelRepository` 一个接口；**默认渠道 MUST 用 `findFirstByOrderByNameAsc()`**（排序下推 SQL，MUST NOT `findAll()` 后内存排序）；入参先 `trim`，trim 后为空等同不传；按名解析用 `findById`；映射逐条实现 [data-model.md](data-model.md) §5 六步（`channelType ← type`、`config ← config_json` 键值 + `url` 键恒定叠加、`config_json` 用 Jackson `readTree` 解析且值非字符串即报错）；**MUST NOT 做 `${ENV_VAR}` 占位解析**（仲裁 2）；**MUST NOT 把 `url` 值写进日志或异常消息**（报错只提渠道名，FR-019）；**MUST NOT 带 Spring 注解**
- [X] T012 [US2] 新增 `oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/notify/NotifyTools.java`（**包是 `notify` 而非课件的 `tool.builtin`**，[plan.md](plan.md) 偏离 ⑥）：构造 `NotifyTools(Sandbox, NotifyChannelAdapter, NotifyChannelRegistry)`——**三个参数全部是接口类型**，MUST NOT 出现 `WebhookNotifyAdapter` / `WhitelistSandbox` / `JpaNotifyChannelRegistry` / `NotifyChannelRepository` / `ProfileContext` 任何具体或越界类型（FR-015 / FR-017）；`@Tool(description = "把一条消息推送到已注册的通知渠道（不传渠道名时用默认渠道）")`（**MUST NOT 沿用课件的「当前 Agent 配置好的通知渠道」旧口径**，[plan.md](plan.md) 偏离 ⑦）；`public ToolResult notify(String content, String channel)`——**方法名与参数名与课件逐字一致**（参数名进 JSON Schema，模型可见）；执行顺序**必须是** ① `registry.resolve(channel)` → ② `sandbox.enforce(new SandboxAction(ActionType.HTTP_REQUEST, target.config().get("url")))` → ③ `adapter.send(target, content)`（**顺序不可颠倒**，FR-007）；成功返回 `ToolResult.success(...)`，**MUST NOT 自己 catch 异常后返回 `failure(...)`**（失败 MUST 上抛给 `ToolExecutor` 落审计，FR-008）；**MUST NOT 输出到 `System.out`/`System.err`**；**MUST NOT 带任何 Spring 注解**（FR-016）

**Checkpoint**: `./mvnw -o -pl oryxos-tool -am test -Dtest=NotifyToolsTest,JpaNotifyChannelRegistryTest` 全绿

---

## Phase 5: User Story 3 - 换渠道只加实现类，不动接口和调用方 (Priority: P2)

**Goal**: 核对「接口先行」这条设计承诺成立——本 story **不产生产代码**，只产核对证据。

**Independent Test**: 契约核对 + 思维练习——接口签名里无任何一档实现特有的词；通知工具的依赖只到接口；以一个测试替身实现类接入，断言调用方零改动。

### Verification for User Story 3

- [X] T013 [US3] 在 `oryxos-tool/src/test/java/io/oryxlabs/oryxos/tool/notify/NotifyToolsTest.java` 内**追加**一个内联的替身适配器实现（`implements NotifyChannelAdapter`，**MUST NOT 新建独立测试类**，保持本节 4 个测试类的形状），用它替换 `WebhookNotifyAdapter` 驱动 `NotifyTools`，断言**通知工具与接口签名零改动即可接入**（US3 场景 3）；US3 场景 4 **如实记为跨节挂账**——入站 `ChannelAdapter` 抽象类型**尚未交付**（全仓 grep 该名仅命中 `NotifyChannelAdapter` 的 javadoc 散文；`CliChannel` 是 CLI 渠道的直连实现、非抽象类型），本节可判定的部分是「`NotifyChannelAdapter` 不与任何既有类型合并或互相继承」（`grep "extends\|implements"` 对 `NotifyChannelAdapter.java` 应只见 `interface` 声明），**完整判定挂到入站抽象落地的那一节**，并在 T015 的 `CLAUDE.md` 更新里留痕
- [X] T014 [US3] 执行 US3 场景 1 / 2 的契约核对并记录结论（判定命令见 [quickstart.md](quickstart.md) §3.4）：① **只查代码行**（`grep -vE '^[[:space:]]*(\*|/\*|//)'` 先排除注释）再 `grep -in "wecom\|feishu\|dingtalk\|企业微信\|飞书\|钉钉\|webhook"` 对 `NotifyChannelAdapter.java` 与 `NotifyTarget.java` → **命中数 0**（**必须排除注释**：两份契约的 javadoc 里合法出现取值示例「webhook / email」与实现名「WebhookNotifyAdapter」，全文 grep 会误报；FR-001 约束的是签名，且这两个文件 MUST NOT 修改）② **同一口径**：先排除注释，再 `grep -n "WebhookNotifyAdapter\|WhitelistSandbox\|JpaNotifyChannelRegistry\|NotifyChannelRepository\|ProfileContext"` 对 `NotifyTools.java` → **命中数 0**（全文 grep 会命中 javadoc 第 17 行的 `{@code ProfileContext}`，那句正是在解释「为什么不用它」——判据约束的是代码依赖而非散文，故与 ① 同样豁免注释；quickstart.md §3.4 已同步补上该口径）。两项都属**机器可判**，核对结果写入节级验收报告

**Checkpoint**: T013 的断言全绿；T014 两条 grep 均为零命中

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: 节级收尾——状态记录、全量门禁、不变量自查、交付物核对

- [X] T015 改 `CLAUDE.md`「当前状态」段：第 19 节完成（交付物一句话 + 测试数）、下一步改为第 20 节；**如实记下**本节的三处特征——① 零装配（`CliAssemblyConfiguration` 一行不改，通知相关 Bean 归第 24 节）② 本节交付库代码 + 单测、无可演示的端到端功能（工具进不了容器、注册表运行期恒为空）③ `oryxos-tool` 新增两条显式依赖边（`spring-web` / `oryxos-storage`，均为既有传递边的显式化，发行物不新增工件）
- [X] T016 跑全量门禁 `./mvnw clean verify`（**不跳 OWASP**）并留证据：BUILD SUCCESS + 静态检查（Spotless / P3C / Checkstyle / SpotBugs+FindSecBugs / OWASP）全过；若 `oryxos-tool` 出现 `EI_EXPOSE_REP2` 之类的告警，**先确认是不是又持有了外部传入的对象引用**（本节的设计已规避，[research.md](research.md) D2），**MUST NOT 用 `@SuppressFBWarnings` 压过去**
- [X] T017 跑前序节回归 `./mvnw -o clean verify`，确认第 16 / 17 / 18 节全部测试绿（跨节契约证据）；**特别确认** `SessionRepositoryTest` 未受 `schema.sql` 追加影响（它只断言既有三表的列，且脚本是逐条 `CREATE TABLE IF NOT EXISTS`）
- [X] T018 H4 六条全局不变量逐条自查并留证据（命令块见 [quickstart.md](quickstart.md) §3.7）：① 涉外 IO 的 `enforce` 调用位在 `NotifyTools`（顺序由 `InOrder` 钉死；本节 `Sandbox` 实现未就位，**如实标注归第 24 节接线**）② 本节不新增审计逻辑，通知工具与其他工具同处第 17 节 `ToolExecutor` 入口之下 ③ 无明文凭证 / 无硬编码渠道地址 ④ 本节不接触 session 身份 ⑤ 无 Reactor / `CompletableFuture` / 自建线程池 ⑥ 无 Spring AI 自动工具执行路径
- [X] T019 课件「本节交付物」逐项存在性核对（逐项 `ls` / `grep`，结果写入验收报告）：代码 `NotifyChannelAdapter` / `NotifyTarget`（**既有，须确认零改动**）、`WebhookNotifyAdapter`、`NotifyTools`；测试 `WebhookNotifyAdapterTest` / `NotifyToolsTest`；配置项按仲裁 1 替换为「SQLite 全局注册表 + 建表脚本」

---

## Phase 7: 交付后补强 —— 飞书 / Lark 方言适配（2026-10-15，已报批）

**Goal**: 兑现跨节挂账②的 thin slice——把「IM 群机器人用 HTTP 200 承载业务错误 → `notify` 假报成功」这条实测抓到的缺陷修掉，范围限飞书 + Lark（同一套协议）。企业微信 / 钉钉**不在本次范围**。

**为什么这不算推翻 FR-003**: FR-003 对**通用档**继续成立（其代码一行未改）。被修订的是「唯一」二字——方言知识与业务码判据全部收在**某一家自己的实现类**内，MUST NOT 回流到通用档或接口。那正是「只新增实现类、不改接口与调用方」这条升级路径本身。

**Input**: [plan.md](plan.md)（本节）、[spec.md](spec.md) FR-022~FR-027 与 SC-011~SC-013、[contracts/notify-outbound.md](contracts/notify-outbound.md) §2.4 / §2.5、[manual-test-guide.md](manual-test-guide.md) 附录 A.2（2026-10-14 实测证据）

### Tests for 补强 ⚠️

- [X] T020 在 `oryxos-tool/src/test/java/io/oryxlabs/oryxos/tool/notify/FeishuNotifyAdapterTest.java` 写方言档测试（**20 条**）：报文逐字、中文/换行/引号/反斜杠保真、`code:0` 通过、★ `200 + code:19002` 抛、`{"data":{}}` 抛（钉死 `path().asInt()` 陷阱）、`code` 优先于 `StatusCode`、仅 `StatusCode` 形态接受、业务码非整数（`"0"` / `0.5`）抛、空体与纯空白体抛、非 JSON 抛且消息不回显 body、5xx 抛、3xx 不跟随（第二个假服务收 **0** 次请求）、`url` 缺失/空白抛且零请求、异常消息不含地址与 token、签名存在且 `sign` 等于**离线用 Python `hmac` 算出的黄金值**（不是自证）、未配 `secret` 时 `timestamp`/`sign` **不出现**、响应头超时抛 `ResourceAccessException`、**响应体读取超时抛 `RestClientException`**（实测确认，与通用档"不能用 bodyDelay"的注释方向相反，因为本档真读 body）
- [X] T021 在 `oryxos-tool/src/test/java/io/oryxlabs/oryxos/tool/notify/RoutingNotifyChannelAdapterTest.java` 写路由接缝测试（**10 条**）：`feishu`/`lark` 命中同一档且通用档零调用、`webhook` 命中通用档、`"  Feishu  "` / `"LARK"` 归一、未知 `type` 抛且消息含排序后的 `"feishu, lark, webhook"`、未知 `type` 零委派、`null`/空白 `type` 抛 `IllegalArgumentException` 而非 NPE、空 Map 抛、每次 `send` 恰好委派一次、构造防御性拷贝、**源码不含厂商字面量**（读源文件断言无带引号的 `feishu`/`lark`/`webhook`/`wecom`/`dingtalk`）
- [X] T022 ★ 在 `oryxos-tool/src/test/java/io/oryxlabs/oryxos/tool/notify/FeishuNotifyToolTest.java` 写**端到端回归**（**4 条**）：`NotifyTools` + **真** `FeishuNotifyAdapter` + 假注册表 + 本地假 webhook。核心断言——对端回 `200 + code:19002` 时 `notify(...)` **抛异常、MUST NOT 返回 `ToolResult.success`**（`getRequestCount() == 1`，不重试）；`code:0` 时 `success()` 为 true 且 `content()` 为「已推送」；经路由发出的报文**真的是飞书方言**；`type` 拼错时零请求。**既有 `NotifyToolsTest` 注入的是 mock 适配器，抓不到这个缺陷——故必须单独有本类**
- [X] T023 在 `oryxos-cli/src/test/java/io/oryxlabs/oryxos/cli/NotifyAssemblyTest.java` 写装配测试（**1 条**，照同模块既有 `ToolAssemblyTest` 的 `CliTestContext.boot(...)` 写法）：容器里 `NotifyChannelAdapter` **恰好一个** Bean 且 `isInstanceOf(RoutingNotifyChannelAdapter.class)`。判据是「恰好一个」而非「存在一个是」——后者在「两档都被声明成 Bean」时照样绿，而那正是最危险的坏法；且必须起**真上下文**，手工 `new` 一个路由会把这条绕过去

### Implementation for 补强

- [X] T024 在 `oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/notify/FeishuNotifyAdapter.java` 写方言档实现（FR-022 / FR-023 / FR-024）：`{"msg_type":"text","content":{"text":...}}`；`secret` 非空白时附 `timestamp` + `sign`（`javax.crypto` + `java.util.Base64`，**不新增依赖**）；**fail-closed 判据**用 `has("code")` + `isIntegralNumber()`，**MUST NOT** 写 `path("code").asInt() != 0`；响应体读成 `byte[]`（`StringHttpMessageConverter` 无 charset 时可能按 ISO-8859-1 解码，会弄乱要放进异常消息的中文 `msg`）；超时与 `Redirect.NEVER` 与通用档同口径；失败抛 `IllegalStateException`（**不新增自定义异常类型**）；非 JSON 体的异常**刻意不挂 cause**（Jackson 解析异常的 message 会带 body 片段，而网关错误页常回显带 token 的 URL）；不打日志
- [X] T025 在 `oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/notify/RoutingNotifyChannelAdapter.java` 写路由接缝（FR-025 / FR-026）：构造收 `Map<String, NotifyChannelAdapter>` 并 `Map.copyOf` 防御性拷贝；`trim().toLowerCase(Locale.ROOT)` 后查表；未命中 / `null` / 空白一律抛 `IllegalArgumentException` 并**按字典序**列出已知类型（直接拼 `keySet()` 迭代顺序未指定，会让测试 flaky）；**厂商无关**——「type → 实现」的映射与 `lark` 别名写在装配处。形状照抄既有 `ProviderService` 的「显式 Map + 未命中抛明确异常」惯用法（章程原则 10 禁止按类型扫描容器）
- [X] T026 改 `oryxos-cli/src/main/java/io/oryxlabs/oryxos/cli/CliAssemblyConfiguration.java` 的 `notifyChannelAdapter()`：在方法体内**就地构造** `LinkedHashMap`（`webhook` → `WebhookNotifyAdapter`；`feishu` / `lark` → **同一个** `FeishuNotifyAdapter` 实例）并返回路由实例。**`FeishuNotifyAdapter` MUST NOT 单独声明成 `@Bean`**（否则 `NotifyChannelAdapter` 类型有两个候选，`NotifyTools` 注入歧义）；映射表 MUST NOT 写成 `@Bean` 方法参数（那是既有 `providerMap` 用 `@Qualifier` 绕开的坑）。同步更新方法 javadoc
- [X] T027 改 `oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/notify/WebhookNotifyAdapter.java` 的**类 javadoc**：首句「核心阶段唯一实现」→「通用档实现」，补一句指向同包方言档。**代码一行不动**（`git diff` 应只见注释行）

### 文档同步与收尾

- [X] T028 契约与文档同步：`spec.md`（FR-003 修订注 + FR-022~FR-027 + SC-011~SC-013 + Edge Cases / Assumptions 四处失效表述回填）、`contracts/notify-outbound.md`（§2 标题修订注 + §1.1 判定口径澄清 + §2.4 / §2.5 新增）、`tasks.md`（本 Phase）、`acceptance-report.md`（补强一节）、`manual-test-guide.md`（手工 INSERT 向导 + 真飞书群验证步骤）、`CLAUDE.md`（挂账② 状态 + Tool 体系 notify 描述 + `type` 列取值口径）
- [X] T029 跑全量门禁 `./mvnw clean verify -Ddependency-check.skip=true`（本地省时；OWASP 已在本节交付时跑过，本次改动不涉及新依赖）并留证据：**BUILD SUCCESS + 273 条测试全绿**（238 基线 + 35 新增）+ Spotless / P3C / Checkstyle / SpotBugs+FindSecBugs 全过。**首轮实际抓到 Spotless 格式违规**（新代码未按 google-java-format 折行），按 CLAUDE.md 既有做法跑 `./mvnw spotless:apply` 后复跑通过——**如实记录，不是一次过**

**Checkpoint**: T020~T023 的 35 条断言全绿；T029 全量门禁 BUILD SUCCESS；两个接口文件 `git diff` 为空

---

## Phase 8: 交付后补强② —— 通知渠道写入面（2026-10-15，已报批）

**为什么做**：第 19 节交付的 `notify_channels` 全局注册表**只有读半边**——表建好了、`resolve()` 能按名解析、`notify` 工具能推，但**没有任何路径能往表里写一行**。写入路径被挂到「第 26 节」，而这个挂账是错的：第 26 节的管理台明确「这一版只读」，10 个端点里没有一个与渠道写入相关。后果是 `notify` 在任何真实部署里 100% 失败，却被 FR-014 / FR-015 记成「预期状态，不是缺陷」。**用户裁决（2026-10-15）：保留全局注册表，补最小写入面**——CLI 三条轻命令，REST + 管理台仍归第 26 节。

### Tests for 补强② ⚠️

- [X] T030 在 `oryxos-cli/src/test/java/io/oryxlabs/oryxos/cli/workspace/WorkspaceDbTest.java` 写 `execute` 的守点（**5 条**）：值里的单引号 / 双引号 / 反斜杠 / 换行 / 中文**原样进出**（证明走的是 `PreparedStatement` 绑定而非字符串拼接——拼接的实现在这条上必然失败）、返回受影响行数（INSERT 1 / DELETE 命中 1、未命中 0）、参数按 `?` 顺序绑定、无参数时也能执行、连续两次执行不串值。用**本类自建**的 `probe` 表，不碰 `notify_channels`（验的是通用能力，用业务表会把两件事的失败混在一起）
- [X] T031 在 `oryxos-tool/src/test/java/io/oryxlabs/oryxos/tool/notify/JpaNotifyChannelRegistryEnvTest.java` 写读时解析（**8 条**，`@ExtendWith(MockitoExtension.class)` + 注入 `UnaryOperator<String>`，**不读真环境变量**，照 `ProfileLoader` 既有测试手法）：`url` 占位替换、`config` 值占位替换、一条里多个占位混排（`${A}-${B}`）、无占位原样通过、**变量缺失 → 抛异常且消息含变量名与渠道名、不含变量值**、形状不合法（`${A B}`）原样保留不报错、默认渠道也解析、生产构造器走 `System::getenv`
- [X] T032 在 `oryxos-cli/src/test/java/io/oryxlabs/oryxos/cli/command/NotifyChannelAddCommandTest.java` 写 `add` 的守点（**14 条**）：重名不覆盖（`INSERT OR IGNORE` + 受影响行数 0 → 报错并提示 `remove`）、`--url` 与 `--url-env` 互斥 / 必居其一、`--url-env` 名字形状校验（`'FOO BAR'` 被拒）、`--config` 只在**第一个** `=` 处切分（base64 secret 以 `=` 结尾）、键重复报错、`--config url=...` 被拒、`--config` 缺 `=` 报错、键为空报错、名含空白被拒、`name`/`type` 空被拒、空 `--config` 存 `NULL`、**输出 `doesNotContain` 真 url 与 config 值**、`--url-env` 时输出含 `${VAR}` 占位符（变量名不是凭证）
- [X] T033 在 `oryxos-cli/src/test/java/io/oryxlabs/oryxos/cli/command/NotifyChannelListCommandTest.java` 写 `list` 的守点（**8 条**）：表头五列、按 `name ASC` 排序（第一行 = 默认渠道，与 `findFirstByOrderByNameAsc` 同序）、纯占位符原样打印 / 否则打 `<已配置>`、`config` 列**只打键名不打值**、空表输出、`config_json` 脏数据打 `<无法解析>` 而不炸整张表、`description` 空值占位、**输出 `doesNotContain` 真 url**
- [X] T034 在 `oryxos-cli/src/test/java/io/oryxlabs/oryxos/cli/command/NotifyChannelRemoveCommandTest.java` 写 `remove` 的守点（**7 条**）：`--force` 直接删、未确认时不删且行还在、确认 `y` 删、名字不存在报错并提示 `list`、删不存在的名字退出码语义、`--force` 下不读 stdin、删除后 `list` 里没有了
- [X] T035 ★ 在 `oryxos-cli/src/test/java/io/oryxlabs/oryxos/cli/NotifyChannelRoundTripTest.java` 写**端到端往返**（**3 条，本补强最高价值**）：跑真 `NotifyChannelAddCommand` 写真 SQLite → `CliTestContext.boot(dbFile)` 起**真** Spring 上下文 → 取 `NotifyChannelRegistry` Bean → `resolve()`。字面量那条断言解析出完整目标；**占位符那条断言的是抛异常**（读路径没接占位解析就会把 `${VAR}` 当普通地址返回——测试看着「成功」，运行时却把字面占位符发给对端；接了解析则因变量没设而抛，**两种实现只有后一种会让这条变红**）；`config` 里的占位同样被解析
- [X] T036 在 `oryxos-cli/src/test/java/io/oryxlabs/oryxos/cli/CliCommandTreeTest.java` 写命令树守点（**3 条**）：叶子命令**恰好**是契约 §1 的那 15 条（只断言「新增的三条在」而不钉总数，漏挂或误挂都能溜过去）、`notify-channel` 三个叶子都挂上、三个叶子都拿得到 `--help`

### Implementation for 补强②

- [X] T037 改 `oryxos-cli/src/main/java/io/oryxlabs/oryxos/cli/workspace/WorkspaceDb.java`：新增 `public int execute(String sql, Object... params)`（`PreparedStatement` + 位置绑定，返回受影响行数）。**MUST NOT 拼字符串**——`name`/`type`/`url`/`description`/`config_json` 全部来自命令行输入。加 `@SuppressFBWarnings` 并写清理由（照既有 `query` / `runSchemaScript` 的模式：SQL 由调用命令写死为常量字面量）。类 javadoc 的「收口什么、不收口什么」补一句：SQL 与行形状仍留各命令
- [X] T038 改 `oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/notify/JpaNotifyChannelRegistry.java`：新增 `ENV_PLACEHOLDER` 正则 + `UnaryOperator<String> envLookup` 字段 + **包级私有**二参构造（**public 一参构造保留**，故 `CliAssemblyConfiguration.notifyChannelRegistry()` 一行未改）；`toTarget` / `parseConfig` 从 `static` 改为实例方法，`url` 与每个 `config` 值各跑一遍 `resolveEnv`。**失败语义刻意与 `McpServerConfigLoader` 不同**——那里是「跳过整个 server + WARN」（一个 server 配错不该拖垮别的 server），这里变量缺失**抛异常**（一个渠道配错必须让 `notify` 失败落审计，FR-006 的同一精神），口径对齐 `ProfileLoader.resolveEnv`；异常消息只带**变量名**与渠道名，MUST NOT 带凭证值
- [X] T039 改 `JpaNotifyChannelRegistry.defaultRow()` 的报错文案：从「请先在通知渠道注册表里添加一个渠道」改为**指向具体命令**（`oryxos notify-channel add <name> --type <type> --url-env <ENV_VAR>`）——第 26 节的管理台是只读的，指过去等于没指。**断言只钉 `hasMessageContaining("未配置任何通知渠道")`，改尾句不动判据**
- [X] T040 新增 `oryxos-cli/src/main/java/io/oryxlabs/oryxos/cli/command/NotifyChannelDb.java`（包级私有）：打开库前先 `workspace.requireExists()` 与「库文件在不在」检查，打开后跑一次 `runSchemaScript()`。**为什么必须建表**：`notify_channels` 是第 19 节才有的表，**老工作区的库文件里没有它**（我们自己的开发工作区就是），而 `oryxos init` 是幂等的、不追溯加表；`.oryxos/` 又是跨升级保留的用户数据。`session list` 没这步是因为 `sessions` 表与工作区同龄
- [X] T041 新增 `NotifyChannelCommand`（分组，照 `ProfileCommand` 的 `@Spec` + `usage(System.out)` 写法）、`NotifyChannelAddCommand`、`NotifyChannelListCommand`、`NotifyChannelRemoveCommand`（四条，`oryxos-cli/.../command/`）。`add` 的取值与校验见 T032；`list` 的输出纪律见 T033；`remove` 照 `ProfileDeleteCommand` 的 `--force` / 确认模式（未确认时不删且退出码 0）。`--config` 的键值对用 `ObjectMapper.writeValueAsString(LinkedHashMap)` 序列化，**不手写 JSON 转义**（那是必然写错一处的那类代码）
- [X] T042 改 `oryxos-cli/src/main/java/io/oryxlabs/oryxos/cli/OryxOsCli.java`：加 `notifyChannelGroup(Workspace)` 工厂并在 `buildCommandLine()` 里挂 `notify-channel`；类 javadoc 的「12 个子命令」改 15，轻命令清单加 `notify-channel *`
- [X] T043 改 `oryxos-cli/pom.xml`：补一条**显式** `jackson-databind` 依赖边。`oryxos-cli/src/main` 此前没有 Jackson import，但它在编译类路径上（经 `oryxos-tool` 传递）——本次把它显式化，与 `oryxos-tool` 把 `spring-web` / `oryxos-storage` 显式化是同一条纪律（**既有传递边的显式化，发行物不新增工件**）。⚠️ **XML 注释里 MUST NOT 出现 `--`**（实测：写了 `--config` 导致 POM 解析失败）

### 文档同步与收尾（补强②）

- [X] T044 改前序交付物的 javadoc / 散文（**代码零改动，属软门禁②**，按报批口径记在验收报告里）：`NotifyChannelRegistry.java`、`JpaNotifyChannelRegistry.java`（「本节只读」+「不做 `${ENV_VAR}` 解析」）、`NotifyChannelRepository.java`（「增删改归第 26 节」→「写入面走 CLI 的原生 JDBC（轻命令不启 Spring），本 Repository 仍只读」——这**恰好自洽**，`JpaRepository` 继承来的 `save` 依旧无调用方）、`CliAssemblyConfiguration.java`（「注册表在运行期恒为空」）。**四处都加带日期的修订注**，否则它们变成假话
- [X] T045 契约与 spec 同步（走修订程序）：`specs/003-cli-session/contracts/cli-surface.md`（§1 表格补三行 + 新增 §2.12 + 文件头修订说明；**既有 12 条的命令名/参数/退出码一字未动**，本次是**命令集扩充**）、`specs/003-cli-session/spec.md`（FR-002 / FR-003 / SC-001 / SC-006 的 12 → 15，各带日期修订注）、`specs/003-cli-session/contracts/cli-assembly.md`（三处「12」）、`specs/000-runtime-kernel/spec.md`（FR-080 / SC-008）
- [X] T046 `specs/004-notify-outbound/*` 全面回填：`spec.md`（**仲裁 2 加修订注**——写入面不再归第 26 节，三个来源的论证写进去；FR-014 部分失效；新增 FR-028~FR-031 / SC-014~SC-016；Edge Cases / Key Entities / Assumptions / 「明确不做」四处失效表述回填）、`contracts/notify-channel-registry.md`（§1.1 错误消息指向新命令 + §1.2 / §2.1 / §2.5 / §3.5 修订注 + §5 测试契约补四个新测试类）、`data-model.md` / `plan.md` / `quickstart.md` / `contracts/assembly-handoff.md`（凡称「写入归第 26 节」「表恒为空」处同步）、`tasks.md`（本 Phase）、`acceptance-report.md`（补强② 一节）、`manual-test-guide.md`（**Step 5 从手工 `sqlite3 INSERT` 改写为三条 CLI 命令**，原手工写法降级为对照；§0 / §6 / §8 / §10 四处失效结论回填）
- [X] T047 改对外/实现描述：`CLAUDE.md`（「当前状态」的挂账② 与「写入路径归第 26 节」表述、「命令行工具」表 12 → 15 并说明新三条是轻命令、「Tool 体系」Notify 段、「SQLite 核心表」`notify_channels` 段、`oryxos-cli` 模块行）、`README.md`（模块表 `oryxos-cli` 行的「12 个子命令」）、`docs/TechnicalSolution.md`（§6.8 通知渠道段 + §546 命令清单各加**一句带日期的补注**——设计文档是被本次实现直接推翻的两处，留注而不改写；`docs/DemandAnalysis.md` / `docs/AiProgrammingGuide.md` / `docs/class/*` 是课件原文与历史需求文档，**不动**）
- [X] T048 跑全量门禁 `./mvnw clean verify` 并留证据：**BUILD SUCCESS + 325 条测试全绿** + Spotless / P3C / Checkstyle / SpotBugs+FindSecBugs / OWASP 全过。**如实记录两次不过**：① 首轮 Spotless 格式违规（跑 `spotless:apply` 后过）② 次轮 SpotBugs `REC_CATCH_EXCEPTION`（`NotifyChannelListCommand.renderConfig` 里 `catch (Exception)` 收窄为 `catch (JsonProcessingException)`）——**都不是一次过**
- [X] T049 端到端手工验收（`/tmp` 下，不在 home）：`init` → `notify-channel add team-lark --type feishu --url-env FEISHU_HOOK_URL --config 'secret=${FEISHU_SECRET}'` → `notify-channel list`（断言打印 `${FEISHU_HOOK_URL}`、不打 token）→ `sqlite3` 查库（断言 `url` 列是字面量占位符、不是真 token）→ `notify-channel remove team-lark --force` → `rm -rf`

**Checkpoint**: T030~T036 的 48 条断言全绿；T048 全量门禁 BUILD SUCCESS；两个接口文件 `git diff` 仍为空（本补强**没碰** `NotifyChannelAdapter` / `NotifyTarget`）；`CliAssemblyConfiguration.notifyChannelRegistry()` 一行未改

---

## Phase 9: 交付后补强③ —— 企业微信群机器人方言档（2026-10-15，已报批）

**为什么做**：补强①（Phase 7）只清了飞书 / Lark，把**企业微信 / 钉钉**记成「挂账② 的剩余部分」。而企微与飞书是**同一类缺陷**——企微群机器人同样**用 HTTP 200 承载业务错误**（报文写错时回 `HTTP 200` + `{"errcode":40008,"errmsg":"invalid message type"}`，2026-10-14 实测），通用档照样判成成功、`notify` 照样假报「已推送」。**用户裁决（2026-10-15）**：① 排期——**现在就做**，插在第 22 节（Memory）之前；② 范围——**只做企业微信**，钉钉本次不做（用户中途从「企微 + 钉钉」收窄）；③ `type` 取值 ——`wecom`；④ 签名——**不适用**，企微群机器人**没有签名机制**（凭证就是 URL 里的 `key`，可选 IP 白名单），故本档**不注入 `Clock`**，比飞书档少一层。

**关键技术事实**：企微与飞书的报文**只差字段名，是镜像不是同一份**——企微 `{"msgtype":"text","text":{"content":"..."}}`（`msgtype` **无下划线**、正文在 `text.content`）vs 飞书 `{"msg_type":"text","content":{"text":"..."}}`（**有下划线**、正文在 `content.text`）。业务码字段：企微 `errcode` / `errmsg`，飞书 `code` / `msg`。企微**没有**飞书那个 `StatusCode` 兼容字段。

### Tests for 补强③ ⚠️

- [X] T050 新增 `oryxos-tool/src/test/java/io/oryxlabs/oryxos/tool/notify/WeComNotifyAdapterTest.java`（**19 条**，照 `FeishuNotifyAdapterTest` 的形状用**真 `MockWebServer`** 打本地假 webhook，减去签名相关用例）：报文方言（`msgtype` **无下划线** + `text.content`，且 `bodyUsesMsgtypeWithoutUnderscoreUnlikeFeishu` 同时断言 `has("msgtype")` 为真与 `has("msg_type")` 为假）、中文 / 换行 / 引号逐字保真、URL 取自 `target.config` 不硬编码、`errcode:0` 成功、**`HTTP 200` + `errcode:40008` → 抛 `IllegalStateException`**（★ 本类存在的全部理由）、失败文案带对端 `errmsg`、无 `errcode` 字段 / `errcode` 非整数（null / `"0"` / `0.5`）/ 空体 / 纯空白体 / 非 JSON / 非对象一律失败、**不认飞书的 `StatusCode`**、非 JSON 时**不回显 body**、5xx / 4xx 上抛不重试、`Redirect.NEVER`（302 时目标 `getRequestCount()==0`）、空 / 缺 url → `IllegalArgumentException` 且**一次请求都不发**、异常消息**不含 url**、响应头超时 → `ResourceAccessException`、响应体读取超时 → `RestClientException`
- [X] T051 新增 `oryxos-tool/src/test/java/io/oryxlabs/oryxos/tool/notify/WeComNotifyToolTest.java`（**4 条**，照 `FeishuNotifyToolTest`：真 `WeComNotifyAdapter` + 真 `RoutingNotifyChannelAdapter` + 假注册表 + mock `Sandbox`，端到端走一遍 `NotifyTools.notify`）。`router()` 建的是**完整四档映射表**（`webhook` / `feishu` / `lark` / `wecom`），这样「发出去的是企微方言」才是有意义的断言。含 ★ `notify` 在企微回 `200 + 40008` 时**抛异常而非返回「已推送」**（消灭假成功的最终断言）、企微接受时返回成功、经路由发出的 body 确为企微方言、`type` 拼错（`wecomx`）→ 报错且**请求数 0**
- [X] T052 补 `oryxos-cli/src/test/java/io/oryxlabs/oryxos/cli/NotifyAssemblyTest.java`（1 → **2 条**）：起**真** Spring 上下文，拿一个不存在的 type 去 `send`，断言报错文案里列出的已知类型**含 `wecom`**（`"feishu, lark, webhook, wecom"`）。方言档是 `new` 在方法体里的，容器看不见，故映射表只能**通过路由行为**观察——这条同时钉住「映射表真的挂上了」与「`wecom` 没被写成别的拼法」

### Implementation for 补强③

- [X] T053 新增 `oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/notify/WeComNotifyAdapter.java`：形状照抄 `FeishuNotifyAdapter`（239 行）**减去签名那一层**——两个构造器（**不是三个**，没有签名就没有 `Clock`）、`RestClient` 自建（`connectTimeout` + `setReadTimeout` + `Redirect.NEVER`，超时收口在实现内）、`send()` 先判 `config.url` 非空白再 POST 再 `toEntity(byte[].class)` 再 `verifyDelivered(body)`、`payload()` 用 `LinkedHashMap`（`Map.of` 遇 null 抛 NPE，报错会指向与真实原因无关的一行）。**fail-closed 三道判据 MUST 用 `has(...)` + `isIntegralNumber()`，MUST NOT 写成 `path("errcode").asInt() != 0`**——Jackson 在字段**缺失**时也返回 0，那个写法把「缺失」判成成功，正好与 fail-closed 相反。`parseObject()` 显式判 `isObject()`（Jackson 对空体返回 `MissingNode` 而非抛异常）。失败一律抛 `IllegalStateException`（**不新增自定义异常类型**）；**无任何 logger 语句**，异常消息含 `errcode` / `errmsg`、**MUST NOT 含 `url`**；非 JSON 响应体**刻意不挂 cause**（Jackson 解析异常的 message 会带 body 片段，而 body 可能来自网关，错误页常回显带 key 的 URL）
- [X] T054 改 `oryxos-cli/src/main/java/io/oryxlabs/oryxos/cli/CliAssemblyConfiguration.java` 的 `notifyChannelAdapter()`：加 import + 映射表加一行 `adapterByType.put("wecom", new WeComNotifyAdapter())`。**`WeComNotifyAdapter` MUST NOT 单独声明成 `@Bean`**（否则容器里 `NotifyChannelAdapter` 有两个候选 → `NotifyTools` 注入歧义，T052 的「恰好一个 Bean」当场红）。**企微不与飞书共用实例**——两者报文只差一个字段名，是镜像不是同一份。注意 `wecom` **没有别名**（`lark` 之于 `feishu` 那种）

> **不抽公共基类（明确决策）**：企微档与飞书档的 `send()` 骨架 + `parseObject()` + 构造器约 60 行同形，看着该抽。**本次不抽**，三条理由：① 抽 = 改动**已交付且被 19 条测试钉死**的 `FeishuNotifyAdapter`，属软门禁②事件，收益（省 60 行）小于风险；② 项目既有裁决同向——`ProfileLoader` / `McpServerConfigLoader` / `JpaNotifyChannelRegistry` 三处 `envLookup` 刻意不合并；③ 目前只有**两档**方言。**第三次出现（钉钉）才是抽的时机**——届时抽的是 `send()` 模板方法 + `parseObject()`，各档只留 `payload()` 与「业务码字段名 + 错误文案」。

### 文档同步与收尾（补强③）

- [X] T055 改前序交付物的散文（**代码零改动，属软门禁②**，按报批口径记在验收报告里）：`CliAssemblyConfiguration.notifyChannelAdapter()` 的 javadoc（「将来加第三家（企微 / 钉钉）只动这个方法」→ 记 2026-10-15 已按此路径兑现一半；「`FeishuNotifyAdapter` 刻意不单独声明 Bean」→ 点名两档方言档）、`WebhookNotifyAdapter.java` 的 javadoc（「不逐家对接企业微信 / 飞书 / 钉钉的专用 API」——这句的主语是**通用档**、依旧成立，但需补注：群机器人 webhook 现由兄弟类覆盖，**专用 API**（AccessToken 刷新、企微「应用消息」）仍不在范围内）。**两处都加带日期的修订注**，否则它们变成假话
- [X] T056 契约与 spec 同步（走修订程序）：`spec.md`（L55「明确不做」清单加补强③、「企业微信 / 钉钉仍不做」→「**钉钉仍不做**」；新增 **FR-032~FR-035**——FR-032 方言与报文形状 / FR-033 业务码字段只认 `errcode` 且**不引入 `StatusCode` 兜底** / FR-034 无签名、不注入 `Clock`、不读 `secret`、`key` 是凭证 / FR-035 **MUST NOT 改动六个文件**；Assumptions 与 `type` / `config_json` 修订块同步）、`contracts/notify-outbound.md`（§2.5.4 断言改四值、映射表加 `"wecom" → WeComNotifyAdapter` 一行、装配硬约束 #1 点名 `WeComNotifyAdapter`、**新增 §2.6 十二行契约**——含 §2.6.5 记录**变异测试结果**：把判据改成 `path("errcode").asInt() != 0` 会让 **3 条测试变红**）、`tasks.md`（本 Phase）、`acceptance-report.md`（补强③ 一节）、`manual-test-guide.md`（前置事实 ③ 四值、Step 1 加企微取地址、**新增 Step 5b**、Step 8 已知类型四值、附录 B 企微从「未做」改为「已做」并把钉钉的差异与「抽基类时机」记进去、单测覆盖表补两行企微、`qyapi.weixin.qq.com` 域名归第 24 节）
- [X] T057 改对外/实现描述：`CLAUDE.md`（L35 挂账② 企微已清 / 钉钉仍挂；L137 Notify 适配器三档 → 四档；L183 `type` 合法取值加 `wecom`）、`docs/TechnicalSolution.md`（§6.8 通知渠道段 `type` 取值清单补 `wecom` 带日期补注——设计文档是被实现直接推翻的一处，留注而不改写；`docs/DemandAnalysis.md` / `docs/AiProgrammingGuide.md` / `docs/class/*` 是课件原文与历史需求文档，**不动**）
- [X] T058 跑全量门禁 `./mvnw clean verify` 并留证据
- [X] T059 端到端手工验收（`/tmp` 下，不在 home）：`init` → `notify-channel add team-wecom --type wecom --url-env WECOM_HOOK_URL` → `notify-channel list`（断言打印 `${WECOM_HOOK_URL}`、不打 key）→ `sqlite3` 查库（断言 `url` 列是字面量占位符、`config` 为空）→ **真企微群送达**（`chat` 里让 Agent 调 `notify`，确认消息进群）→ 反面：`--type wecomx` 报错列已知类型且零请求 → `rm -rf`

**Checkpoint**: T050~T052 的 24 条断言全绿；T058 全量门禁 BUILD SUCCESS；**`git diff --stat` 显示 `RoutingNotifyChannelAdapter.java` / `NotifyChannelAdapter.java` / `NotifyTarget.java` / `NotifyTools.java` / `FeishuNotifyAdapter.java` 五个文件无改动**（`WebhookNotifyAdapter.java` 只有 javadoc 变，属 T055 的报批范围）——这是「接口先行」升级路径的**第二次**实际兑现

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (Setup)**：无依赖，先行
- **Phase 2 (Foundational)**：依赖 Phase 1 的 pom 改动（`oryxos-storage` 依赖）
- **Phase 3 (US1)**：依赖 Phase 1；**不依赖 Phase 2**（适配器不碰表）
- **Phase 4 (US2)**：依赖 Phase 1 + **Phase 2**（`JpaNotifyChannelRegistry` 消费 `NotifyChannelRepository`）
- **Phase 5 (US3)**：依赖 Phase 4（要驱动 `NotifyTools`）与 Phase 3（要替换 `WebhookNotifyAdapter`）
- **Phase 6 (Polish)**：依赖全部

### User Story Dependencies

- **US1（P1，适配器）** 与 **US2（P1，工具 + 注册表 + 表）** **互不依赖**，可并行开发——这是本节最主要的并行机会。两者的接缝只有一个：US2 的测试里 mock 了 `NotifyChannelAdapter` 接口，不引用 US1 的具体实现
- **US3（P2）** 依赖 US1 + US2 的产物（它是核对型 story）

### Within Each User Story

- 测试任务先于或伴随对应实现任务（课件 harness 先行）；Java 里测试与实现一起编译，故两者一起落地
- 实现与测试一起落地，跑该模块测试，红了当场修

### Parallel Opportunities

- **T002 / T003**：不同文件（实体 / Repository），可并行
- **T006（US1 测试）与 T008（US2 测试）**：不同文件、不同替身策略（MockWebServer vs Mockito），可并行
- **T009（US2 的注册表测试）与 T006**：可并行
- **T010（注册表接口）** 与 T002/T003 之后的一切：接口是纯声明，可先落地
- **US1 整条链（T006 → T007）与 US2 的 T010/T011**：可并行（US1 只要 pom）

## Parallel Example: US1 与 US2 并行开工

```bash
# 轨道 A（US1：适配器）——只需 Phase 1 完成
#   T006 写 WebhookNotifyAdapterTest（MockWebServer 八组断言）
#   T007 写 WebhookNotifyAdapter

# 轨道 B（US2：注册表 + 表）——需 Phase 1 + Phase 2 完成
#   T002 实体 / T003 Repository / T004 DDL / T005 存储测试
#   T010 注册表接口 → T011 注册表实现 → T012 通知工具
```

## Implementation Strategy

### MVP First

**US1 单独就是可演示的 MVP**：`WebhookNotifyAdapter` + `WebhookNotifyAdapterTest` 跑绿，即证明「给一个地址发一次 POST、内容真送出去了、失败明确上抛」这件事成立——这是本节唯一有真实外部效果的部分。**US1 不依赖 Phase 2**，可最先完成。

### Incremental Delivery

1. Phase 1（pom）→ 2. Phase 2（表/实体/Repository）→ 3. **US1（适配器，MVP）** → 4. **US2（工具 + 注册表，本节的完整交付）** → 5. US3（核对）→ 6. Phase 6（收尾）

### 关于「本节没有可演示的端到端功能」

这是 [plan.md](plan.md) 仲裁 4 与仲裁 2 的**必然结果**，不是实现偷懒：工具进不了 Spring 容器（`Sandbox` 实现归第 23/24 节）、注册表运行期恒为空（写入路径归第 26 节）。本节的完成判据因此是**4 个测试类全绿 + 静态门禁全过 + 契约存在性核对**，可演示的那一天是第 24 节接线之后。人工验收项清单见 [quickstart.md](quickstart.md) §5。

> **2026-10-15 补强② 后**：两个堵点**都已解开**——工具自第 20 节进容器（临时沙箱 `PermissiveSandbox`），渠道写入面由本补强补上（Phase 8）。**端到端可演示了**：`notify-channel add` 配渠道 → `chat` 里让 Agent 调 `notify` → 消息进群，见 [manual-test-guide.md](manual-test-guide.md) 正文 Step 5~8。注意解开它们的节号与原文预计的不同（工具是第 20 节不是第 24 节；写入是 CLI 不是第 26 节）。

## Notes

- 全程**不自动 commit / push / 运行 `package.sh`**，同步时机由用户决定
- **`docs/class/` 已入库**（2.3MB 课件目录；2026-10-14 由仓库所有者决定随仓库跟踪，此前为不入库）
- ~~**跨节挂账：IM 群机器人方言适配**（2026-10-14 人工实测发现）~~ → **2026-10-15 部分兑现（Phase 7）**：通用 webhook 报文 `{"content":"..."}` 不被 IM 群机器人接受（实测飞书回 **`HTTP 200` + `code:19002` "msg_type need"**；企微同类失败走 `HTTP 200` + `errcode:40008`），而 `WebhookNotifyAdapter` 按 HTTP 状态判成功 → `NotifyTools.notify` **假报「已推送」**。本节交付时按 FR-003 边界**记为已知边界、代码一行不改**（软门禁 ③ 已报批）；**该挂账已于 Phase 7 兑现为 thin slice**——飞书 / Lark 方言档 `FeishuNotifyAdapter` + 路由接缝 `RoutingNotifyChannelAdapter`，接口与调用方零改动，FR-003 的「唯一」二字经用户裁决被修订（通用档代码一行未改）。**企业微信已于 Phase 9（补强③）清掉**（走的正是原先记下的那条路：映射表加一行 + 加一个类），**钉钉仍未清**——但路由接缝已立起来，加它同样是「往映射表加一行 + 加一个类」，且钉钉与企微**报文完全相同**、差异只在业务码语义与**加签**（钉钉的 `timestamp` + HMAC-SHA256 拼进 **URL 查询串**，飞书是放**请求体**）。实测证据与可复现命令见 [manual-test-guide.md](manual-test-guide.md) 附录 A.2 与正文 Step 5~8、[acceptance-report.md](acceptance-report.md) §7 与补强节
- **归第 20 节的改造点：`@ToolParam(required = false)`**（2026-10-14 反编译 Spring AI 1.1.8 核实）——`NotifyTools.notify` 的 `channel` 参数未带 `@ToolParam`，而 `JsonSchemaGenerator.isMethodParameterRequired` 对无注解参数**一律判 required**（常量 `PROPERTY_REQUIRED_BY_DEFAULT` = 1）→ 生成的 JSON Schema 把 `channel` 列进 `required`，FR-004「渠道名可选」在模型可见层不成立。运行期行为正确（空值 → 默认渠道，已有单测），故**第 19 节不改**——`@Tool` 此刻惰性，改了无从验证；第 20 节写 `ToolRegistry` 注册时补注解即可
- 反作弊：MUST NOT 删断言 / `@Disabled` / 放宽阈值让测试变绿；实现错修实现，认为测试错就停下报告；**未全绿不得宣称完成**
- 每个 user story 完成打 git commit（commit message 末尾带 `Co-Authored-By: Claude Code <noreply@anthropic.com>`）
