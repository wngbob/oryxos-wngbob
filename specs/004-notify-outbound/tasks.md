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

## Notes

- 全程**不自动 commit / push / 运行 `package.sh`**，同步时机由用户决定
- **`docs/class/` 已入库**（2.3MB 课件目录；2026-10-14 由仓库所有者决定随仓库跟踪，此前为不入库）
- 反作弊：MUST NOT 删断言 / `@Disabled` / 放宽阈值让测试变绿；实现错修实现，认为测试错就停下报告；**未全绿不得宣称完成**
- 每个 user story 完成打 git commit（commit message 末尾带 `Co-Authored-By: Claude Code <noreply@anthropic.com>`）
