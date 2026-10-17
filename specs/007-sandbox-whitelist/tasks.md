# Tasks: Sandbox 白名单 —— 把「受控执行」这道墙后面的第一档实现填上

**Feature**: `specs/007-sandbox-whitelist` | **Branch**: `class-24` | **Date**: 2026-10-17

**Input**: Design documents from `specs/007-sandbox-whitelist/`

**Prerequisites**: [plan.md](plan.md) / [spec.md](spec.md) / [research.md](research.md) / [data-model.md](data-model.md) / [contracts/sandbox-whitelist.md](contracts/sandbox-whitelist.md) / [quickstart.md](quickstart.md)

**测试纪律**：课件「验收 harness」里**写出代码**的两个关键回归用例（相对路径穿越必须被拦 / 通配符域名不能被形似域名绕过）MUST 原样落地，断言逻辑逐条保真；方法名译成英文，**课件中文原名进 `@DisplayName`**。测试方法名一律英文（驼峰 / snake_case），MUST NOT 用中文方法名。

**路径约定**：以下 `…/sandbox/` = `oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/sandbox/`；`…/sandbox-test/` = `oryxos-tool/src/test/java/io/oryxlabs/oryxos/tool/sandbox/`；`…/cli/` = `oryxos-cli/src/main/java/io/oryxlabs/oryxos/cli/`。

**「零改动面」（SC-004 / FR-009 / FR-011，本节最该被 review 的地方）**：`Sandbox.java` / `SandboxAction.java` / `ActionType.java` / `SandboxViolationException.java` 四个骨架期契约、五个内置工具（`FileTools` / `ShellTools` / `HttpTools` / `NotifyTools` / `WebSearchTools`）、`oryxos-core/` 的 `ToolExecutor`——**本节一行不改**，由 T016 的 `git diff` 自查钉死。

**本节的形状（先说清，免得读起来像偷懒）**：三类校验是**同一个类的三个私有方法**，拆不成三个可独立发布的增量。因此 **Phase 2 一次交付 `WhitelistSandbox` 的实现与它的单测**（测试先行，见 T003 → T004），**Phase 3~6 承载四条 user story 各自的接线回归与独立验证**——每个 story 仍有自己的可判定判据，只是「实现」这一层是共享的。这与课件自己的拆法一致：课件「动手前先拆任务」列的五步（定接口 → 写实现 → 接进 Tool → 确认失败路径 → 自查中立性）就不是按 user story 分的。

---

## Phase 1: Setup（配置与值对象落位）

**Goal**: 三块白名单配置与三个绑定它们的值对象就位。没有这一步，`WhitelistSandbox` 的构造器收不到参数。

- [X] T001 [P] `oryxos-boot/src/main/resources/application.yaml` 末尾追加三块白名单，**逐字**照 [contracts/sandbox-whitelist.md](contracts/sandbox-whitelist.md) §3.3（条目 MUST NOT 自行增删）：`file.allowed_paths: ["."]` / `shell.allowed_commands: [ls, cat, head, tail, grep, find, wc, pwd, git]` / `http.allowed_domains: [api.duckduckgo.com, api.tavily.com, open.feishu.cn, open.larksuite.com, qyapi.weixin.qq.com]`。**键名是 snake_case**（课件与 `docs/TechnicalSolution.md` §6.7 逐字，不是本仓别处惯用的 kebab-case），**不带 `oryxos.` 前缀**。每块的注释 MUST 写明两条口径（SC-006）：①「留空 = 全拒，不是不校验」②「劝阻级防线，防不住蓄意绕过（命令拼接 / 软链接 / 编码绕过）」。另加一句「这份是**可用默认**不是安全默认，生产部署 MUST 按最小权限收紧」。**配置文件唯一落点是本文件**——`oryxos-cli` 的 pom 明令 MUST NOT 放第二份
- [X] T002 [P] 新增三个 `@ConfigurationProperties` 值对象 → `…/sandbox/FileSandboxProperties.java`（`@ConfigurationProperties(prefix = "file")` + `record FileSandboxProperties(List<String> allowedPaths)`）、`…/sandbox/ShellSandboxProperties.java`（`prefix = "shell"` + `List<String> allowedCommands`）、`…/sandbox/HttpSandboxProperties.java`（`prefix = "http"` + `List<String> allowedDomains`）。**每个 record MUST 带紧凑构造器做 null 归一化**：`x = x == null ? List.of() : List.copyOf(x)`（与 `ProviderProperties` 逐字同形）——`CliTestContext.boot()` 就不给这三块配置，配置块缺失时 `List` 字段绑成 `null`，不归一化会让后续 `Set.copyOf` 直接 NPE。**MUST NOT** 在这三个类上加 `@Component`（第 20 节契约 §6.3）。**MUST NOT** 在这里过滤空条目——那是 `WhitelistSandbox` 的语义（值对象只管「配置的形状」）

**Checkpoint**: `./mvnw -pl oryxos-tool -am test-compile` 过。

---

## Phase 2: Foundational（所有 user story 的阻塞前置）

**Goal**: `WhitelistSandbox` 落地、临时沙箱退场、装配层与新契约对齐。

- [X] T003 `…/sandbox-test/WhitelistSandboxTest.java`（**harness 先行**，纯单测、**不起 Spring 上下文**：直接 `new WhitelistSandbox(new FileSandboxProperties(List.of(...)), ...)`）。本任务交付**三类校验的成对用例与绕过场景**，逐条覆盖 [data-model.md](data-model.md) §3.4 的判定表与 [contracts/sandbox-whitelist.md](contracts/sandbox-whitelist.md) §2 的边界规则 2.1~2.9、2.11：
  - **路径组**：`/workspace/notes.txt` 放行 / `/workspace/a/b.txt` 放行（目录前缀不是精确文件）/ `/etc/passwd` 拒 / `/workspace/../../outside/secret.txt` 拒 / `/workspace-evil/x` 拒（组件级前缀，D10）/ `/workspace` 自身放行
  - **命令组**：`ls -la` 放行 / `"  ls -la"` 放行（前导空白不是绕过手段）/ `rm -rf /` 拒（消息带首 token `rm`）/ `LS` 拒（精确比对，不折叠大小写）/ `"   "` 拒（切不出 token 即拒）/ 白名单里含 `""` 条目时空白命令**仍拒**（2.5 + 2.9 的组合口）
  - **域名组**：`*.example.com` → `https://api.example.com/x` 放行、`https://evil-example.com/x` **拒**、`https://example.com/x` 拒（裸域）；精确 `api.example.com` → 同域放行、`https://other.example.com/x` 拒；`https://API.Example.COM/x` 放行（2.7）；`file:/etc/passwd` 拒（无 host，**断言不是 NPE**，2.8）；`不是 URL` 拒（解析失败，2.8）
  - **入口唯一（FR-005）**：反射断言 `WhitelistSandbox.class.getDeclaredMethods()` 里除 `enforce` 外的**声明方法全部 `private`**，且 `getMethods()`（含继承）里除 `Object` 的方法外只有 `enforce`
  - **两条课件原文用例 MUST 保真**（方法名英文 + `@DisplayName` 带课件中文原名）：`relativePathTraversal_isBlocked`（`@DisplayName("相对路径穿越必须被拦")`）与 `wildcardDomain_isNotBypassedByLookalikeDomain`（`@DisplayName("通配符域名_不能被形似域名绕过")`）
  - **此刻必然红**（类还不存在，连测试编译都过不了）——这是 harness 先行的**预期状态**，T004 落地后转绿，MUST NOT 为了让模块编过而先删用例或加 `@Disabled`
- [X] T004 `…/sandbox/WhitelistSandbox.java`（**与 T003 成对**）：`public class WhitelistSandbox implements Sandbox`，**类上不带 `@Component` / `@Configuration`**（§6.3）。唯一构造器 `public WhitelistSandbox(FileSandboxProperties, ShellSandboxProperties, HttpSandboxProperties)`。内部三个字段在构造时一次性标准化（[data-model.md](data-model.md) §3.2）：`allowedRoots` = 逐条 `Path::of → normalize → **toAbsolutePath**`（D4①：默认值 `.` 是相对路径，不转绝对会让默认配置全拒）、**丢弃空/纯空白条目**；`allowedCommands` = 逐条 `trim()` 后丢弃空串、**不折叠大小写**；`allowedDomainPatterns` = 逐条 `trim()` → `toLowerCase(Locale.ROOT)`、丢弃空串。`enforce` 用**经典冒号 switch**（D5，MUST NOT 箭头 switch）+ 抛 `IllegalStateException` 的 `default`：`FILE_READ` / `FILE_WRITE` 同路由 `checkFilePath`（2.3），`SHELL_COMMAND` → `checkShellCommand`，`HTTP_REQUEST` → `checkHttpUrl`。三个校验方法 MUST 是 `private`（FR-005），实现逐条照 [data-model.md](data-model.md) §3.4：路径用 **`Path::startsWith`（组件级）**；命令取 `trim().split("\\s+")[0]` 且**首 token 为空即拒**（2.5）；域名先解析 host（null / `IllegalArgumentException` 都转成 `SandboxViolationException`，D4②）、两侧折叠小写后按「`*.` 前缀 → `endsWith(pattern.substring(1))`，否则 `equals`」比对（2.6 / 2.7）。异常消息逐字照 [contracts/sandbox-whitelist.md](contracts/sandbox-whitelist.md) §1.3 三条前缀，**MUST NOT 带任何凭证**。javadoc 写明：`*.example.com` 覆盖任意层级子域（D9，spec 未规定故不加测试）、白名单是**劝阻级**防线、空表 = 全拒，以及**命令条目的双向陷阱**——`allowedCommands` 的条目是**可执行文件名**：配置侧写 `/bin/ls` 会与首 token `ls` 永不匹配（静默全拒），模型侧写 `/bin/ls` 也会被拒（`ShellTools` 传的就是 `argv.get(0)`，不做 basename 归一——**MUST NOT 做 basename 归一**，那会让 `/tmp/evil` 改名成 `ls` 就通过）。两个方向都是 fail-closed，但都该在 javadoc 里说清，免得运维以为「配了却不生效」是 bug
- [X] T005 装配改动 + 删除面 → `…/cli/CliAssemblyConfiguration.java`：① 类上补 `@EnableConfigurationProperties({FileSandboxProperties.class, ShellSandboxProperties.class, HttpSandboxProperties.class})`；② `sandbox()` 方法体换成 `return new WhitelistSandbox(fileProps, shellProps, httpProps);`，**签名逐字保持**（`@Bean` + `@ConditionalOnMissingBean(Sandbox.class)` + `public Sandbox sandbox(FileSandboxProperties, ShellSandboxProperties, HttpSandboxProperties)`，[contracts/sandbox-whitelist.md](contracts/sandbox-whitelist.md) §4.1）；③ javadoc 改写——原文写的是「临时实现 / 第 24 节让位」，现在是**真实现 + 有意留的覆盖接缝**（部署方自建 `Sandbox` Bean 即接管）。**同一任务内删除**：`…/sandbox/PermissiveSandbox.java` 与 `…/sandbox-test/PermissiveSandboxTest.java`（裁决 3：fail-open 组件不留树）。删除后 `…/cli/` 里对 `PermissiveSandbox` 的 import 一并去掉
- [X] T006 连带触碰**四个**文件（**只去掉对已删类的引用，其余一字不动**，[contracts/sandbox-whitelist.md](contracts/sandbox-whitelist.md) §4.3）：① `oryxos-tool/src/test/.../registry/AnnotatedToolAdapterTest.java` —— `new PermissiveSandbox()` 换成真 `WhitelistSandbox`（三个空表），**编译必需**（该用例只读 `notify` 的 JSON Schema、不执行工具，空白名单不影响它；加一行注释说明「用真实现而不是再造一个全放行替身」）；② `oryxos-tool/src/test/.../builtin/RecordingSandbox.java` —— javadoc 里「为什么不直接注入 `PermissiveSandbox`」一句改写（该替身的存在理由是「记录型 vs 全放行」，类名没了这句话悬空）；③ `oryxos-cli/src/test/.../LogStreamSeparationTest.java` —— 一条 `@DisplayName` / 注释里的引用改写（那条断言守的是「dev 档根级别不被压到 ERROR」这个**通用**日志契约，与沙箱无关）；④ **`oryxos-boot/src/main/resources/logback-spring.xml`** —— 第 14 行 XML 注释里「含 PermissiveSandbox 那条**故意**打的 WARN，它 MUST NOT 被静音」那一处括号引用改写（删去该引用即可，窗口期已关闭、那条 WARN 不会再打）。**`<target>System.err</target>` 那一行与 stdout/stderr 分流的理由 MUST NOT 改动**（CLAUDE.md「这行不要删」的约束仍然有效；已验证 `LogStreamSeparationTest` 的断言只针对 `<target>` 行与结构标记，**不 grep 注释文本**，改注释不破测试）。**MUST NOT 触碰** `specs/005-tool-registry/` 下的历史文档
- [X] T007 改写 `oryxos-cli/src/test/.../ToolAssemblyTest.java` 的沙箱部分（SC-005，spec SC-007 允许的唯一测试变更）：① `sandbox_hasExactlyOneBean_andItIsThePermissiveStandIn` → `sandbox_hasExactlyOneBean_andItIsTheWhitelistSandbox`，断言 `hasSize(1)` **且** `isInstanceOf(WhitelistSandbox.class)`（**MUST NOT** 放宽成「存在一个是」——那在「两个都在」时假绿，而「两个都在」只在重命令起上下文时才炸成 `NoUniqueBeanDefinitionException`，`--help` 扫不出来）；② 让位用例改语义（方法名与 `@DisplayName` 一并改）：不再是「临时档让位」，而是「容器里已有别的 `Sandbox` Bean 时白名单实现让位」——断言 `hasSize(1)` 且是那个替身，替身类 `RealSandboxStandIn` 相应改名；③ **新增配置键字面量绑定用例**（D8，课件 harness 之外、[plan.md](plan.md) D8 已落档）：用 `CliTestContext.boot(dbFile, Map.of("file.allowed_paths[0]", <临时目录>, "shell.allowed_commands[0]", "ls", "http.allowed_domains[0]", "api.example.com"), ...)` 起真上下文，断言从容器取出的 `Sandbox` Bean 对白名单内目标放行、白名单外拒绝——**这条把三个 snake_case 字面量钉死，改键名即变红**（键名写错的后果是白名单静默为空 = 全拒：装上跑不了、日志无异常）

**Checkpoint**: `./mvnw -pl oryxos-tool -am test` 与 `./mvnw -pl oryxos-cli -am test` 双绿；`grep -rn "PermissiveSandbox" oryxos-*/src` 零命中。

---

## Phase 3: User Story 1 — 白名单外的文件路径读不到（Priority: P1）

**Goal**: 真 `WhitelistSandbox` 接上真 `FileTools` 之后，越界路径在**真正读文件之前**被拦，且**真正的 IO 没有发生**。

**Independent Test**: 纯单测——白名单只允许一个 `@TempDir`，对一个白名单外的目标发 `read_file` / `write_file`，断言被拦且底层 IO 一次都没发生。

- [X] T008 [US1] 新增 `…/sandbox-test/SandboxToolWiringTest.java` 的 **FileTools 两条**：用**真** `WhitelistSandbox`（白名单只含一个 `@TempDir`）构造 `FileTools`（构造器签名照 `oryxos-tool/src/main/.../builtin/FileTools.java` 现状，**MUST NOT 改它**）。① `read_file` 白名单外 → 抛 `SandboxViolationException`，且断言**异常类型不是「文件不存在」那类**（若先做 IO 再校验，目标不存在时报的会是别的错——这是「校验先于 IO」的可判定形态）；② `write_file` 白名单外 → 断言 **`Files.notExists(目标)`**（文件根本没被创建）。两条都断言异常消息是 §1.3 的路径前缀、人能读懂（这条消息会原样进 `tool_invocations.error_message`）

**Checkpoint**: `./mvnw -pl oryxos-tool -am test -Dtest=SandboxToolWiringTest` 绿。

---

## Phase 4: User Story 2 — 白名单外的命令一次都没被执行（Priority: P1）

**Goal**: 拦在 `ProcessBuilder` 起来之前——**进程根本没起**。

**Independent Test**: 白名单里没有 `touch`，跑一条会创建标记文件的命令，断言标记文件不存在。

- [X] T009 [US2] `…/sandbox-test/SandboxToolWiringTest.java` 补 **ShellTools 条**：白名单只允许 `ls`，构造 `ShellTools`，跑 `touch <@TempDir 下的标记文件>`，断言 ① 抛 `SandboxViolationException` ② **`Files.notExists(marker)`**——这是「进程真没起」的可判定形态（课件 harness 说的「mock 底层执行器 + `verify(never())`」在本仓的等价物：不去 mock `ProcessBuilder`，而是让命令**留下可观测的痕迹**，痕迹不在即证明它没跑）

**Checkpoint**: 同 T008 的命令，绿。

---

## Phase 5: User Story 3 — 通配符域名拦住形似域名（Priority: P2）

**Goal**: 白名单外的域名**请求根本没发出去**，包括 `notify` 的推送。

**Independent Test**: 本地假 HTTP 端点（`mockwebserver3`）断言请求计数为 0；记录型 `NotifyChannelAdapter` 断言没被调用。

- [X] T010 [US3] `…/sandbox-test/SandboxToolWiringTest.java` 补两条：① **HttpTools 条**——`mockwebserver3` 起本地端点，白名单里**不含**该端点 host，调 `http_get`，断言抛 `SandboxViolationException` **且 `server.getRequestCount() == 0`**（`mockwebserver3` 5.5.0 的 `getRequestCount()` / `enqueue` / `url` / `start` 已在本地 jar 实证存在）；② **NotifyTools 条**——`NotifyTools` 构造器是 `(Sandbox, NotifyChannelAdapter, NotifyChannelRegistry)`。**注册表替身 MUST NOT 返回 `null`**：`NotifyTools.notify` 的顺序是 `registry.resolve(channel)` → `enforce` → `adapter.send`（[NotifyTools.java:67-70](oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/notify/NotifyTools.java#L67-L70)），返回 `null` 会在 `target.config()` 抛 **NPE**、而不是 `SandboxViolationException`，测试必红且红因与白名单无关。故注册表替身返回**真** `NotifyTarget`：`new NotifyTarget("webhook", Map.of("url", "<白名单外的 URL>"))`，调 `notify` 后断言 ① 抛 `SandboxViolationException` ② **`adapter` 一次都没被调**——用 Mockito `verify(adapter, never()).send(any(), any())`（`NotifyToolsTest` 已在用 Mockito，依赖现成；这也是课件「mock 底层执行器 + `verify(never())`」那手法的直译）。**注册表本身会被调**（把渠道名解析成 URL 不是危险动作，设计如此），故断言对象 MUST 是 `adapter`、MUST NOT 写成「替身一次都没被调」

**Checkpoint**: 同 T008 的命令，绿。

---

## Phase 6: User Story 4 — 空配置 = 全拒，不是「不校验」（Priority: P3）

**Goal**: 「忘了配」的后果是「什么都跑不了」（可见、可查），不是「什么都放行」（静默、危险）；这条口径能从配置里读到。

**Independent Test**: 三个空表构造沙箱，四类动作各校验一次全部抛异常；并断言 `application.yaml` 里能 grep 到那两条口径。

- [X] T011 [US4] `…/sandbox-test/WhitelistSandboxTest.java` **补空配置组**：① 三块白名单全空 → `FILE_READ` / `FILE_WRITE` / `SHELL_COMMAND` / `HTTP_REQUEST` **逐类**各校验一次，全部抛 `SandboxViolationException`（逐类型过一遍，只测一种会漏掉「按 type 分支写错」）；② 白名单**非空但都不匹配** → 同样抛（**不是**「表非空就放行」）；③ 与 T002 的 null 归一化联动：`new FileSandboxProperties(null)` 等三个值对象构造出的沙箱，行为与空表**完全一致**（`CliTestContext` 就是这种情形）
- [X] T012 [US4] 核对随包默认与配置说明：① `grep -n "全拒\|劝阻级" oryxos-boot/src/main/resources/application.yaml` 能命中三条块各自的注释（SC-006）；② 三块清单与 [contracts/sandbox-whitelist.md](contracts/sandbox-whitelist.md) §3.3 **逐条一致**（条数、顺序、内容；`git diff` 该文件核对，MUST NOT 自行增删条目）；③ 默认配置下的可用性：`file.allowed_paths: ["."]` 经 `toAbsolutePath()` 后能放行工作目录下的文件（D4① 的兑现——**这一条若不成立，默认配置就是全拒**，故必须实测）

**Checkpoint**: `./mvnw -pl oryxos-tool -am test -Dtest=WhitelistSandboxTest` 绿。

---

## Phase 7: Polish & 跨切面

**Goal**: 文档连带修订、全量门禁、节级收尾。

- [X] T013 [P] 文档连带修订①：`specs/000-runtime-kernel/contracts/sandbox-config.md` **加**带日期的修订注（「2026-10-17 被 `specs/007-sandbox-whitelist` 取代」+ 取代理由三条 + 「读写分权推迟，`ActionType` 已把 `FILE_READ` / `FILE_WRITE` 分开建模，接缝留着」），**MUST NOT 改它的历史正文**
- [X] T014 [P] 文档连带修订②：`docs/TechnicalSolution.md` §6.7 —— 「四个校验」→ 三个，删去 `checkSmtpEndpoint` / `smtp.allowed_endpoints` 的现时表述并改为「SMTP 记扩展阶段（`ActionType` 无 SMTP 路由、`EmailNotifyAdapter` 归扩展阶段）」；挂带日期的修订注
- [X] T015 [P] 文档连带修订③：`CLAUDE.md` 五处——① 「⚠️ 沙箱窗口期（第 20~23 节）」条目改为**已关闭**（第 24 节已交付，`read_file /etc/passwd` 现在被拦）；② 「四个校验」→ 三个（两处：Sandbox 条目 + 待办里的 `api.tavily.com` 域名白名单挂账改为已清）；③ 缓解③「自动让位、不回头删代码」按裁决 3 回修为「整类删除」并写明理由（fail-open 组件不留树）；④ 「当前状态」节补第 24 节（`specs/007-sandbox-whitelist`）；⑤ 「Tool 体系 → Sandbox」条目补白名单语义要点（空 = 全拒 / 劝阻级 / 三类校验 / 配置键）。**MUST NOT 触碰 `website/`**（本次不涉及对外定位 / 能力 / 路线图）
- [X] T016 全量门禁 + 零改动面自查：① 先跑 `./mvnw spotless:apply`（静态检查是构建门禁，改代码后必须先格式化）；② `./mvnw clean verify` 全绿（含 P3C / Checkstyle / SpotBugs+FindSecBugs / OWASP，本地可 `-Ddependency-check.skip=true`）；③ 跑 [quickstart.md](quickstart.md) Step 6 的两段 `git diff --stat` / `grep -c` 自查——骨架期四个契约文件、五个内置工具、`ToolExecutor` **空输出**（SC-004 / FR-009 / FR-011）；④ 前序节全部测试回归绿（SC-007）
- [X] T017 落盘验收报告 `specs/007-sandbox-whitelist/acceptance-report.md`（节级 DoD）：① 头部元信息（feature / plan / tasks / quickstart 链接、分支 `class-24`、日期、任务完成度 N/N、结论一行）② **六项证据**（`mvn clean verify` 关键输出 / harness 映射表逐类对号 / 「本节交付物」逐项 `ls`·`grep` 存在性核对 / 前序节测试回归绿 / **H4 六条全局不变量逐条自查** / 剩余人工项清单）③ **变更总结三段**（改动点按模块分组 + 重点 review 清单按风险排序 + 如何验证的可复制命令块）④ 剩余人工项（真模型真链路越界被拦 + 接口中立性自查，[quickstart.md](quickstart.md) Step 7~8）⑤ 备注（未 commit / push / `package.sh`）⑥ **「已知边界与证据口径」一节**，四条 MUST 写全（不写等于把边界藏起来）：**(a)** FR-010 / SC-001 的「落 `tool_invocations`」证据是**间接**的——既有 `ToolExecutorTest.recordsFailedInvocationWithReason`（通用路径）+ `ToolExecutor` 的 `catch (RuntimeException)` 无特判 + `SandboxViolationException extends RuntimeException` + `AnnotatedToolAdapter` 剥壳上抛，四环链齐；**MUST NOT** 写成「有测试直接证明」，直接证据是人工项 Step 7；**(b)** MCP 工具的出站**不过沙箱**（`McpToolAdapter` 零 `enforce` 调用：stdio 子进程在 `shell.allowed_commands` 之外、SSE/HTTP 网络在 `http.allowed_domains` 之外）——课件结语已点名为预期范围，但**MUST NOT** 让「五个工具零改动 + 白名单生效」被误读成「所有工具出站都受控」；**(c)** `Path.of` 遇非法路径字符抛 `InvalidPathException`（未包成 `SandboxViolationException`）：fail-closed 保住了、消息可读、走同一条审计路径，但消息口径与契约 §1.3 三条前缀不一致（spec 未要求，按 YAGNI 不处理）；**(d)** `*.example.com` 覆盖**任意层级**子域（`a.b.example.com` 也命中，D9 裁决），spec 只规定单层故未写测试钉死。**对话里仍要完整输出一遍报告正文**。中途若抓红并修复（例如 P3C 违规、Spotless 格式），**如实记下「抓到了什么、怎么修的」**

---

## Dependencies（user story 完成顺序）

```text
T001 ─┐
T002 ─┴─► T003 ─► T004 ─► T005 ─► T006 ─► T007 ─┬─► T008 [US1] ─┐
                                                 ├─► T009 [US2] ─┼─► T011 ─► T012 ─► T016 ─► T017
                                                 └─► T010 [US3] ─┘        ▲
                                                                          │
                                          T013 / T014 / T015 [P] ─────────┘
```

- **T001 / T002 可并行**（不同文件、无依赖）
- **T003 → T004 是 harness 先行的一对**：T003 写完后模块测试**编译不过**（类还不存在），这是预期状态；T004 落地后转绿
- **T005 依赖 T004**（装配要 `new WhitelistSandbox(...)`）；**T005 与 T006 必须一起落地**（T005 删了 `PermissiveSandbox`，T006 才能让 `AnnotatedToolAdapterTest` 重新编过——顺序反了模块测试编译不过）
- **T008 / T009 / T010 互相独立**（都往 `SandboxToolWiringTest` 里加用例，但彼此不依赖），可在 T007 之后任选顺序
- **T013 / T014 / T015 完全独立**，可与 T008~T012 并行
- **T011 / T012 依赖 T004**（空配置行为要有类才能验）

## Parallel Execution Examples

```bash
# 第一批（Phase 1，两个文件互不相干）
T001: 编辑 oryxos-boot/src/main/resources/application.yaml
T002: 新建三个 …/sandbox/*SandboxProperties.java

# Phase 7 的三处文档修订（不同文件，可并行）
T013: specs/000-runtime-kernel/contracts/sandbox-config.md
T014: docs/TechnicalSolution.md
T015: CLAUDE.md
```

## MVP 范围

**T001~T004 + T008**（配置 + 值对象 + 沙箱实现与其单测 + 路径类的接线回归）即构成一个可演示的最小增量：**白名单外的文件路径读不到、真正的 IO 没发生**——这正是本节存在的理由（US1 是 P1，且它是第 20~23 节窗口期里唯一被实测确认过会「真的成功」的越界路径）。

`PermissiveSandbox` 的退场（T005）建议**紧随 MVP** 落地而不是拖到最后——它是个 fail-open 组件，只要它还在树里，装配一旦被误改就会静默回到「全放行」。

## Format Validation

- 全部 17 条任务均为 `- [ ]` 复选框 + 顺序 TaskID（T001~T017）+ 明确文件路径
- `[P]` 只标在真正可并行且不依赖未完成任务的条目上（T001 / T002 / T013 / T014 / T015）
- `[US1]`~`[US4]` 只标在 Phase 3~6 的条目上；Setup / Foundational / Polish 三阶段**不带** story 标签
- 每条任务都可直接执行：文件路径、字面量、断言形态、边界规则编号均已写进描述，不留给实现期自由裁量
