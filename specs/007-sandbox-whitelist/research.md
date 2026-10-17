# Phase 0 研究：Sandbox 白名单

**Feature**: `specs/007-sandbox-whitelist` | **Branch**: `class-24` | **Date**: 2026-10-17

本文件是 `/speckit-plan` Phase 0 的产出：把 Technical Context 里每一个「需要定下来」的点，落成「决策 / 理由 / 否决的备选」。

**零 `NEEDS CLARIFICATION`**——四处会触发软门禁的冲突（配置载体 / SMTP / `PermissiveSandbox` 处置 / 默认值）已在 `/speckit-specify` 阶段报用户裁决，结论落在 [spec.md](./spec.md) 的 `Clarifications` 与「冲突仲裁记录」里。本文件只处理**裁决之后仍要定的技术细节**。

---

## §0 前序依赖的本地核实（H3：动手前先核）

| 依赖 | 核实方式 | 结论 |
| --- | --- | --- |
| `Sandbox` / `SandboxAction` / `ActionType` / `SandboxViolationException` | `ls oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/sandbox/` | ✅ 四个文件齐（骨架期交付），本节一行不改 |
| 五个工具的 `enforce` 首行 | `grep -rn "sandbox.enforce" oryxos-tool/src/main` | ✅ 前序节已交付（`NotifyTools` 第 19 节、其余四个第 20 节）；本节**不碰**（FR-009） |
| `@ConfigurationProperties` / `@EnableConfigurationProperties` 可用性 | `oryxos-tool/pom.xml` 有 `spring-boot-starter`；`oryxos-provider/.../ProviderConfiguration.java:27` 是既有用法 | ✅ 可用，**无需新增依赖** |
| `ToolExecutor` 的失败审计路径 | `oryxos-core/src/test/.../ToolExecutorTest.java` 的 `recordsFailedInvocationWithReason`（第 17 节交付） | ✅ 既有路径覆盖「工具抛异常 → `tool_invocations` 落 `success=false` + 原因文本」；`SandboxViolationException` 是普通 `RuntimeException`，天然走同一条 |
| `@TempDir` / AssertJ / `mockwebserver3` | `oryxos-tool/pom.xml` test scope | ✅ 齐（`mockwebserver3` 是第 20 节为 `HttpToolsTest` 加的） |
| 新增第三方依赖 | — | **无**。本节只用 JDK（`Path` / `URI` / `Locale`）+ 既有 `spring-boot-starter` |

**没有一条落在「第三方 API 在本地依赖中核实不到」这条软门禁上。**

---

## §1 决策清单

### D1 配置载体与键名

- **决策**：`oryxos-boot/src/main/resources/application.yaml` 里三块——`file.allowed_paths` / `shell.allowed_commands` / `http.allowed_domains`，**snake_case 逐字照课件与 `docs/TechnicalSolution.md` §6.7**；三个键**不带 `oryxos.` 前缀**。
- **理由**：① 它们是课件「本节交付物」+ §6.7 双重写定的已定字面量（改形态属软门禁 ②）；② Spring relaxed binding 让 `allowed_paths` 与 `allowed-paths` 都能绑到记录组件的 `allowedPaths`，运维写哪种都能用——**但文档与随包默认只认课件那一种**，不留两处真相；③ 不带 `oryxos.` 前缀是因为它们描述的是沙箱子系统而不是「OryxOS 的配置」，§6.7 原文即如此。
- **否决的备选**：`specs/000-runtime-kernel/contracts/sandbox-config.md` 的 `.oryxos/sandbox.yaml` + `file.read` / `file.write` 拆分。否决理由：① 与课件正面冲突；② `oryxos init` 明确 MUST NOT 创建 `sandbox.yaml`（`specs/003-cli-session/contracts/cli-surface.md` 与 `data-model.md` 都这么写），该契约与已交付行为本就对不上；③ 读写分权在核心阶段没有驱动需求（同一个 Agent 同一份工作目录）。
- **连带**：那份 000 契约挂**带日期的修订注**（不改历史正文）；读写分权推迟（`ActionType` 已把 `FILE_READ` / `FILE_WRITE` 分开建模，接缝留着）。

### D2 三个值对象的落位与注册方式

- **决策**：`FileSandboxProperties` / `ShellSandboxProperties` / `HttpSandboxProperties` 三个 `record` 放 `oryxos-tool/.../tool/sandbox/`；注册靠**既有** `CliAssemblyConfiguration` 上补 `@EnableConfigurationProperties({...})`，**不新建任何 `@Configuration` / `@Component` 类**。
- **理由**：① **依赖方向强制**——`WhitelistSandbox` 的构造器收这三个对象，放进 `oryxos-cli` 会造出 `oryxos-tool → oryxos-cli` 的反向边（`oryxos-cli` 依赖各能力模块，反向即成环）；② 第 20 节契约 `contracts/assembly.md` §6.3 明令 `MUST NOT` 在非 boot 模块加 `@Component` / `@Configuration`，而 `CliAssemblyConfiguration` 正是「全部 `@Bean` 的唯一落点」；③ 记录类带 `@ConfigurationProperties` 但**不带** `@Component`，本身不构成「新配置类」。
- **否决的备选**：① 在 `oryxos-tool` 新建 `SandboxConfiguration`——撞 §6.3（`oryxos-provider` 的 `ProviderConfiguration` 是第 16 节交付形态，§6.3 是其后的纪律）；② `@ConfigurationPropertiesScan`——引入整包扫描语义，与「装配点只有一处、一眼看得见谁被装配」相冲；③ 把三个值对象换成 `@Value` 拼装——三个集合键用 `@Value` 表达要写 `@Value("${file.allowed_paths:}") String` 再自己切分，等于手写一遍绑定，且丢掉类型校验。
- **空值处理**：`@ConfigurationProperties` 的记录组件在**配置块整个缺失**时，`List` 字段绑成 `null`。`CliTestContext.boot()` 就不给这三块配置（`oryxos-cli` 的测试 classpath 上没有 `application.yaml`），所以这不是理论情形。**归一化放记录组件的紧凑构造器**（null → `List.of()`），与 `ProviderProperties` 的既有做法逐字同形。

### D3 装配处的 Bean 形态

- **决策**：`@Bean @ConditionalOnMissingBean(Sandbox.class) public Sandbox sandbox(FileSandboxProperties, ShellSandboxProperties, HttpSandboxProperties)`，方法体 `return new WhitelistSandbox(...)`。方法名、返回类型、条件注解三者都保持第 20 节契约 §3 写定的形态。
- **理由**：① §3 逐字要求第 24 节「加一行 `@Bean @ConditionalOnMissingBean(Sandbox.class) … sandbox(...)` 并**删掉**本节那行」；② 返回类型取**接缝类型** `Sandbox`——装配点只承诺「提供一个沙箱」，换容器/microVM 档时改的是方法体；③ `@ConditionalOnMissingBean` 是**有意留的覆盖接缝**：部署方自建 `Sandbox` Bean 即接管，这正是「加实现类 + 装配处换一行」升级路径的可执行形态。
- **连带**：`ToolAssemblyTest` 的让位用例**改写而非删除**——它现在断言「容器里已有同类型 Bean 时，白名单实现让位」（SC-005 的新形态）。
- **否决的备选**：① 去掉 `@ConditionalOnMissingBean`（否决：违反 §3 逐字要求，且删掉升级入口）；② 返回类型写 `WhitelistSandbox`（否决：接缝是 `Sandbox`，装配点不该承诺具体档）。

### D4 课件 §3.2 示例代码的四处硬化

课件给的是**示意实现**。逐行照抄会在 spec 明确要求的四条边界上出错，因此四处硬化，每处都能指回 spec 的一条 Edge Case：

| # | 课件写法 | 本节写法 | 依据（spec Edge Case） |
| --- | --- | --- | --- |
| ① | `allowedRoots` 只 `normalize()` | `Path::of → normalize → toAbsolutePath` | 「相对路径：配置在启动时一次性标准化为绝对路径」。**照抄会让默认配置全拒**——配置里的默认值是 `.`，而目标路径被转成了绝对路径，相对与绝对 `startsWith` 恒为 false |
| ② | `URI.create(url).getHost()` 直接用 | host 为 null → 抛 `SandboxViolationException`；`URI.create` 抛 `IllegalArgumentException` → 包成 `SandboxViolationException` | 「URL 没有 host 时 MUST 被拒，MUST NOT 因为 null 比较的意外结果放行」。照抄会 NPE，被 `ToolExecutor` 记成一条**看不懂**的失败（运维看到 `NullPointerException` 而不是「域名不在白名单内」） |
| ③ | 域名原样比对 | host 与 pattern 都 `toLowerCase(Locale.ROOT)` | 「域名大小写：host 比对 MUST 不区分大小写」；同一条 Edge Case 也要求 **shell 命令比对区分大小写**（那是文件名），故折叠只加在域名这一路 |
| ④ | 三个集合直接 `Set.copyOf` / `List.copyOf` | 构造时丢弃空/纯空白条目；`checkShellCommand` 另加「首 token 为空即拒」 | 「配置里写了空串或纯空白的条目 MUST NOT 变成匹配一切的通配符」+「命令里只有空白：`trim()` 后切不出 token，MUST 被拒」。注意 `"".split("\\s+")` 返回 `[""]`（长度 1）——照抄会让「空白命令 + 空条目」这条组合**正好放行** |

**这四处不是「自行发挥」**：每条都对应 spec 里已写死的 Edge Case，且都有测试钉死。

### D5 箭头 switch → 经典冒号 switch + 抛异常的 `default`

- **决策**：课件的 `switch (action.type()) { case FILE_READ, FILE_WRITE -> ... }` 落地为经典冒号形态 + 抛 `IllegalStateException` 的 `default`。
- **理由**：① 语法禁区——P3C（PMD 6.55）+ ASM 9.7.1 的静态门禁；全仓 main 源码零箭头 switch，本节不破例；② 仓库既有形态同向：`MemoryServiceImpl.recall` 对枚举就是「经典冒号 + 抛异常的 `default`」；③ `default` 抛异常而不是静默：将来给 `ActionType` 加第五值（例如 SMTP）而忘了加路由，会**当场炸**而不是静默放行。
- **否决的备选**：`if / else if` 链（否决：枚举分支用 switch 更清楚，且 `MemoryServiceImpl` 已立了同形先例）。

### D6 不做 SMTP 第四类校验

- **决策**：只做文件路径 / 可执行文件 / HTTP 域名三类；`docs/TechnicalSolution.md` §6.7 与 `CLAUDE.md` 的「四个校验」修订为三个，SMTP 记扩展阶段。
- **理由**：① `ActionType` 只有四值、**没有 SMTP 的路由**——`checkSmtpEndpoint` 在核心阶段无路可走；② 无消费者（`EmailNotifyAdapter` 归扩展阶段，§6.8）；③ 加第五个 `ActionType` 是改第 20 节定死的接口字面量（软门禁 ②），且会破坏「用 microVM 反套接口签名」的中立性论证。
- **否决的备选**：`enforce` 里加一个 `target` 形状嗅探（形如 `host:port` 就走 SMTP 校验）——否决：那是**按字符串猜类型**，与 `SandboxAction` 「类型决定 target 语义」的契约正面冲突。

### D7 删除面与连带触碰

- **删除**：`PermissiveSandbox.java`、`PermissiveSandboxTest.java`、装配处 `sandbox()` 的方法体（方法本身保留，见 D3）。
- **理由**：fail-open 组件留在树里，一旦 Bean 声明被误改（顺序、条件注解、被别处覆盖）就静默回到「全放行」，兜底的只有一条 WARN 日志。与 `EmptyToolCatalog` / `EmptyMemorySource` 那两个先例**不同类**：那两个让位后是 no-op（空目录 / 无记忆），这个是「什么都放行」。
- **连带触碰三个前序节测试文件**（只去掉对已删类的引用，断言逻辑一字不动）：`AnnotatedToolAdapterTest`（**编译必需**：它 `new PermissiveSandbox()` 当 dummy；改用真 `WhitelistSandbox` + 空白名单，该用例只读 JSON Schema 不执行工具）、`RecordingSandbox`（javadoc 里「为什么不直接注入 `PermissiveSandbox`」一句悬空）、`LogStreamSeparationTest`（一条 `@DisplayName` 引用已删类；断言守的是「dev 档根级别不被压到 ERROR」这个通用日志契约，与沙箱无关）。
- **MUST NOT 触碰**：`specs/005-tool-registry/` 下的历史文档——它们记录第 20 节当时的形态，改它们等于篡改历史留痕。

### D8 新增一条课件 harness 之外的测试：配置键字面量绑定

- **决策**：`ToolAssemblyTest` 加一条用例——用属性源传 `file.allowed_paths[0]` / `shell.allowed_commands[0]` / `http.allowed_domains[0]`，起**真**上下文，断言容器里的 `Sandbox` Bean 对白名单内目标放行、白名单外拒绝。
- **理由**：键名写错（`allowed_paths` 写成 `allowed_path`、`file` 写成 `files`）的后果是**白名单静默为空 = 全拒**：装上跑不了、日志没有一行异常、要人去翻配置类才看得出来。这是「能机器判」的典型坏法。这条同时把 D1 的 snake_case 字面量钉死——改键名即变红。
- **否决的备选**：只靠人工验收（否决：这正是「能机器判却留给人工」）。多起一次上下文约 1 秒，可忽略。
- **实现提示**：`CliTestContext.boot(dbFile, Map<String,Object> extraProperties, ...)` 已支持额外配置项（第 22 节为记忆切档加的）；列表值用**带下标**的键（`file.allowed_paths[0]`）传字符串，避免 `Map<String,Object>` 里塞 `List` 的展开歧义。

### D9 通配符域名覆盖任意层级子域

- **决策**：`*.example.com` 命中 `api.example.com` **也命中** `a.b.example.com`；裸域 `example.com` **不**命中。
- **理由**：spec 只规定了单层语义，**没有**规定多层级。点号边界实现（`host.endsWith(".example.com")`）天然覆盖任意层级，与主流 web 服务器 `ServerAlias` 同款。按「机器判不了的绝不自行发挥」——这不是 spec 定的要求，故**不写测试钉死它**，只在 javadoc 写明实际语义供 reviewer 一眼看到。
- **否决的备选**：把「恰好一层」也写进实现（否决：spec 未要求，且会让 `*.example.com` 不覆盖 `a.b.example.com` 这种常见期望失效，属自行发明约束）。

### D10 前缀比对用 `Path.startsWith`

- **决策**：`checkFilePath` 用 `Path::startsWith`（**组件级**比对），不用 `String.startsWith`。
- **理由**：spec Edge Case 要求「`/workspace-evil/x` MUST NOT 被 `/workspace` 放行——前缀必须落在**路径分隔符**边界上」。`Path.startsWith` 按路径组件比对，这条边界天然成立；字符串比对会把 `workspace-evil` 判成命中。**这是「选对 API 即满足要求」的形态**，不需要额外边界判断代码，但要有测试钉死。
- **否决的备选**：字符串前缀 + 手工补分隔符判断（否决：把 JDK 已经做对的事重做一遍，且要处理 `\` / `/`、末尾斜杠、根路径等一串边界）。

### D11 文档连带修订

| # | 文件 | 修订 |
| --- | --- | --- |
| ① | `specs/000-runtime-kernel/contracts/sandbox-config.md` | 加带日期的修订注（被 `specs/007-sandbox-whitelist` 取代 + 理由），不改历史正文 |
| ② | `docs/TechnicalSolution.md` §6.7 | 「四个校验」→ 三个；SMTP 记扩展阶段 |
| ③ | `CLAUDE.md` | 窗口期关闭 / 「四个校验」→ 三个 / 缓解③ 按裁决 3 回修 / 第 24 节条目更新 / 「Tool 体系 → Sandbox」补白名单语义要点 |

**MUST NOT 触碰 `website/`**：本次不涉及对外定位、能力、路线图表述——沙箱是内核实现细节，不是对外承诺面（`CLAUDE.md`「对外内容三处同步」那条规则不触发）。

---

## §2 课件示例代码 ↔ 本节实现的逐行对照

给 reviewer 的比对表。**「本节写法」列的每一处差异都能指回上表的一条决策**。

| 课件 §3.2 原文 | 本节写法 | 差异原因 |
| --- | --- | --- |
| `@Component public class WhitelistSandbox` | 装配处 `@Bean`，类上无 `@Component` | 第 20 节契约 §6.3 + 仓库一贯做法（课件第 20 节同样给工具画了 `@Component`，本仓改为装配处声明）——spec 仲裁 7 |
| `allowedRoots = ...map(Path::normalize)` | `...map(Path::normalize).map(Path::toAbsolutePath)` | D4① —— 默认值 `.` 是相对路径，不转绝对会让默认配置全拒 |
| `Set.copyOf(shellProps.allowedCommands())` | 过滤空/空白条目后再收成不可变 `Set` | D4④ —— spec Edge Case「空条目 MUST NOT 变成通配符」 |
| `switch (...) { case A, B -> ... }` | 经典冒号 switch + 抛异常的 `default` | D5 —— P3C/ASM 门禁 + 仓库既有形态 |
| `command.trim().split("\\s+")[0]` | 同写法 + 「首 token 为空即拒」的前置判断 | D4④ —— `"".split("\\s+")` 返回 `[""]`，不加判断则空白命令在「空条目」组合下正好放行 |
| `URI.create(url).getHost()` 直接用 | null host / `IllegalArgumentException` 都转成 `SandboxViolationException` | D4② —— spec Edge Case「没有 host MUST 被拒」，照抄会 NPE |
| `host.equals(pattern)` / `host.endsWith(pattern.substring(1))` | 两侧先 `toLowerCase(Locale.ROOT)`，比对逻辑不变 | D4③ —— spec Edge Case「host 比对不区分大小写」 |
| `matchesDomain` 无点号边界注释 | 同实现 + javadoc 写明「`substring(1)` 保留的那个点号就是边界」 | 课件把这一点留在了讲义文字里，代码里留一句更耐读 |
| 三个校验方法 `private` | 同（FR-005），并有反射测试钉死 | 课件原文要求，spec 仲裁 6 |

**刻意没做的「改写」**：`matchesDomain` 的 `pattern.substring(1)` 写法**保留原样**——它看起来像取巧，实际上正确（`*.example.com`.substring(1) 得到 `.example.com`，前导点号正是那条边界），且比手写 `"." + pattern.substring(2)` 更短更清楚。**不为了「看起来更明白」去改一段已经正确的代码。**

---

## §3 反作弊自查（写代码前先立规矩）

| 诱惑 | 为什么不能做 |
| --- | --- |
| 把「路径穿越」用例的白名单设成 `/workspace` 但断言目标写成相对路径 | 相对路径会因 CWD 不同而结果不同——测试变脆且守不住 normalize 这条回归。用例必须用**绝对路径**构造穿越 |
| 接线回归只断言「抛了异常」 | 先做 IO 再校验同样会抛异常——测试变成自证。必须断言**底层执行器一次都没被调用**（进程没起 / 文件没建 / 请求计数为 0 / 适配器没被调） |
| 给 `WhitelistSandboxTest` 的某个用例加 `@Disabled`（比如 `a.b.example.com` 那条边界想不清） | 禁止。想不清就不写那条用例（D9 已经决定不发明这条要求），而不是写了再禁用 |
| 为了让 `ToolAssemblyTest` 变绿而把断言从「恰好一个」放宽成「存在一个是」 | 后者在「两个都在」时假绿，而「两个都在」正是本节最危险的坏法（重命令起上下文才炸 `NoUniqueBeanDefinitionException`，`--help` 扫不出来） |
| 用匿名类 / lambda 当装配处的沙箱 | 第 20 节契约 §6.4 明令禁止（无法让位、无法断言、无法写 javadoc 说明形态）。本节装配处必须是具名类 |
| 把 `PermissiveSandbox` 留着「万一要用」 | fail-open 组件不留树（裁决 3）。要用就重新声明一个具名实现类 |

---

## §4 结论

技术路径无阻塞：**零新增依赖、零新增模块、零新增表、零新增对外概念**（唯一的对外概念是课件「本节交付物」逐字点名的 `WhitelistSandbox` 与三个值对象），四处软门禁冲突已报批并落进 spec，四处课件样例的硬化逐条对应 spec Edge Case。

进入 Phase 1：`data-model.md` / `contracts/sandbox-whitelist.md` / `quickstart.md`。
