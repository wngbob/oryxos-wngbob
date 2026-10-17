# Implementation Plan: Sandbox 白名单 —— 把「受控执行」这道墙后面的第一档实现填上

**Branch**: `class-24` | **Date**: 2026-10-17 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/007-sandbox-whitelist/spec.md`

**Note**: 本文件是 `/speckit-plan` 的输出。技术口径以本文件为准，**冲突时以 `spec.md` 的「冲突仲裁记录」为准**（8 条，其中 4 条是 2026-10-17 用户已报批的裁决）。

---

## Summary

第 23 节把方向定成「接口先定、墙焊死、实现只做第一档」。本节把第一档填上——**量很小，但它是「Tool 治理从没有到有第一档」的那一步**。

一句话：**一个实现类（`WhitelistSandbox`，三类校验）+ 三个配置值对象（`file` / `shell` / `http`）+ 一处装配替换（删掉临时的 `PermissiveSandbox`）+ 三块 `application.yaml` 配置**。

三件事值得单独说：

1. **`Sandbox` / `SandboxAction` / `ActionType` / `SandboxViolationException` 四个骨架期契约一行不改**（FR-011）。本节只新增实现类与值对象，接口签名逐字保真——这正是「接口先行」那句纪律在本节的兑现形态。
2. **五个内置工具的 `enforce` 首行零改动**（FR-009）。前序节就已经把 `sandbox.enforce(...)` 写在每个涉外 `@Tool` 方法的第一行了（`NotifyTools` 第 19 节随类创建，`FileTools` / `ShellTools` / `HttpTools` / `WebSearchTools` 第 20 节），本节只是把墙后面的 `PermissiveSandbox` 换成真实现。`git diff` 对那五个文件为空，是这条的机器可判定证据（SC-004）。
3. **`PermissiveSandbox` 整类删除**（裁决 3）。它是个 fail-open 组件，留在代码树里一旦 Bean 声明被误改（顺序、条件注解、被别处覆盖）就静默回到「全放行」。它跟 `EmptyToolCatalog` / `EmptyMemorySource` 那两个先例**不同类**：那两个让位后是 no-op，这个是「什么都放行」。

窗口期就此关闭：第 20~23 节里 `read_file /etc/passwd` 会真的成功（2026-10-14 端到端实测），本节之后必须变成「拦下 + 落 `tool_invocations`（`success=false`）」。

---

## Technical Context

**Language/Version**: JDK 21 + Spring Boot 3.x + Spring AI Alibaba（动手前先跑 `mvn dependency:tree` 确认锁定 BOM 里目标依赖存在）、SQLite + Spring Data JPA。凭证走环境变量占位，不落明文。SQLite 用手工建表脚本，不依赖 `hibernate.ddl-auto=update`。

> 本节**不新增任何第三方依赖**，也不新增任何依赖边：`@ConfigurationProperties` / `@EnableConfigurationProperties` 来自 `oryxos-tool` 与 `oryxos-cli` 已有的 `spring-boot-starter`，`Path` / `URI` / `Pattern` 全在 JDK 内。因此 `mvn dependency:tree` 的核对对象是「**确认没有新坐标**」，而不是「确认某坐标存在」。

**Primary Dependencies**（全部已在本仓，`oryxos-tool/pom.xml` 现状即覆盖本节需要）：
- `org.springframework.boot:spring-boot-starter` —— `@ConfigurationProperties`（`oryxos-tool` 已直接依赖，第 20 节加的）；`@EnableConfigurationProperties`（`oryxos-cli`，第 18 节起就在用 `@Value` / `@ConditionalOnMissingBean`）
- `org.springframework.boot:spring-boot-starter-test`（test scope）—— JUnit 5 / AssertJ / `@TempDir`
- `com.squareup.okhttp3:mockwebserver3:5.5.0`（test scope）—— `HttpTools` 接线回归的本地假 HTTP 端点（**第 20 节已交付**，本节复用，不新增坐标）
- **不引入**：任何沙箱/容器/microVM 库、`SecurityManager` 相关 API、任何新的配置框架

**Storage**: **本节不碰任何存储**。白名单是启动时从 `application.yaml` 一次性读入、运行期只读的进程内状态；不新增表、不新增实体、不改 `schema.sql`、不改任何 Repository。`tool_invocations` 的写入路径本节也**一行不改**（FR-010：校验失败走 `ToolExecutor` 既有 try/catch）。

**Testing**:
- `WhitelistSandboxTest`（`oryxos-tool`，纯单测，**不起 Spring 上下文**）——三类校验「允许 + 拒绝」成对 + 绕过场景（路径穿越 / 前导空白 / 形似域名）+ 空配置全拒 + 对外只有一个入口的反射断言
- `SandboxToolWiringTest`（`oryxos-tool`，纯单测）——**真 `WhitelistSandbox`** 接进 `FileTools` / `ShellTools` / `HttpTools` / `NotifyTools` 各一条，断言「被拦 + 真正的 IO 没有发生」
- `ToolAssemblyTest`（`oryxos-cli`，起**真**上下文）——两条沙箱断言改写为「恰好一个 `Sandbox` Bean 且是 `WhitelistSandbox`」+「容器里已有别的 `Sandbox` Bean 时白名单实现让位」，另加一条**配置键字面量绑定**用例（见 D8）
- 单测默认跑，**无 `@Tag("integration")` 项**（本节无真外部依赖；真模型真链路的集成验证是人工项）
- 实现完成的定义是 `mvn clean verify` 全绿（含 Spotless / P3C / Checkstyle / SpotBugs+FindSecBugs / OWASP）

**Target Platform**: Linux 服务器（开发机为 WSL2）；单 fat JAR（`oryxos-boot/target/oryxos.jar`）

**Project Type**: Maven 多模块库 + CLI（核心阶段 9 模块，**本节不新增模块**）

**Performance Goals**:
- 三类校验都是**纯内存比对**，零 IO、零网络、零反射：路径 `Path.startsWith`（组件级）、命令 `Set.contains`、域名逐条 `equals` / `endsWith`。白名单条目数以「人能维护的规模」为界（几十条量级），线性扫描足够，**不引入索引或缓存**
- 三个值对象在**启动时一次性标准化**（`Path` 归一为绝对路径、命令收成 `Set`、域名折叠大小写），运行期只读——因此 `enforce` 的每一次调用成本与配置规模无关的常数开销可忽略
- **MUST NOT 加进程内缓存**：白名单是「改配置重启生效」的语义（spec Assumptions），缓存只会制造「改了没生效」的假象

**Constraints**:
- **`Sandbox` / `SandboxAction` / `ActionType` / `SandboxViolationException` 四个文件 `git diff` 为空**（FR-011）
- **五个内置工具（`FileTools` / `ShellTools` / `HttpTools` / `NotifyTools` / `WebSearchTools`）`git diff` 为空**（FR-009 / SC-004）
- **`ToolExecutor` 一行不改**（FR-010 / 章程原则 VI：MUST NOT 为 Sandbox 造独立审计路径）
- **MUST NOT 新增 `@Configuration` / `@Component` 类**（第 20 节契约 `contracts/assembly.md` §6.3）：三个值对象是纯记录类，注册靠既有 `CliAssemblyConfiguration` 上的 `@EnableConfigurationProperties`
- **MUST NOT 用 Java `SecurityManager`**（章程原则 VII：JDK 17 起废弃、JDK 21 已不可用）
- **MUST NOT 做 SMTP 第四类校验**（裁决 2：`ActionType` 无 SMTP 路由、核心阶段无消费者）
- **默认清单逐条钉死**（裁决 4）：条目 MUST NOT 自行增删；「留空 = 全拒」与「劝阻级防线，防不住蓄意绕过」两条口径 MUST 写进配置注释
- **避开 P3C/ASM 解析不了的 Java 18+ 语法形态（如增强 switch 的 `default ->` 写法），静态检查是构建门禁。** 全仓 main 源码至今零箭头 switch，本节保持这个保守度：课件的 `case FILE_READ, FILE_WRITE ->` 落地为**经典冒号 switch**（见 D5）

**Scale/Scope**:
- 新增 main 代码 **4 个文件**（3 个值对象 + 1 个实现类），约 150 行
- 删除 **3 处**（`PermissiveSandbox` 类 / `PermissiveSandboxTest` / 装配处 `sandbox()` 方法体）
- 改动装配 **1 处**（`CliAssemblyConfiguration`：加 `@EnableConfigurationProperties` + 改 `sandbox()` 方法体）
- 改动配置 **1 处**（`oryxos-boot/src/main/resources/application.yaml`：三块白名单 + 注释）
- 触碰前序节测试 **3 个文件**（仅去掉对已删类的引用，断言逻辑一字不动，见 D7）
- 新增测试 **2 个类**（`WhitelistSandboxTest` / `SandboxToolWiringTest`）+ 改写 **1 个类**（`ToolAssemblyTest`）
- 文档连带修订 **3 处**（见 D11）

---

## Constitution Check

| 原则 | 本节如何满足 | 判定 |
| --- | --- | --- |
| **I. JDK 21 + Spring Boot 3.x 单体、单二进制** | 不新增模块、不新增发行工件；`@ConfigurationProperties` 来自既有 `spring-boot-starter` | ✅ |
| **II. 核心阶段只交付运行时内核** | 只做应用层白名单（第一档），容器 / microVM 归扩展阶段 | ✅ |
| **III. Agent 引擎自实现，Spring AI 只用一半** | 本节完全不碰 LLM 侧；不改 `ReActLoop` / `ToolExecutor` / `ProviderService` | ✅ |
| **IV. Agent 目录不是 Tool；白名单由管理员显式列出** | 白名单**不**下放给 `AGENT.md`（spec Assumptions：Agent MUST NOT 自声明）；`ActionType` 不新增值 | ✅ |
| **V. Tool 体系统一抽象、单一模块** | 全部落在 `oryxos-tool` 的 `sandbox` 包，与 `FileTools` 等挨在一起；不拆模块 | ✅ |
| **VI. 审计数据 Day One 落库** | 校验失败作为**普通工具执行失败**进入既有路径，`MUST NOT` 新增审计代码、`MUST NOT` 造独立审计路径；`ToolExecutor` `git diff` 为空 | ✅ |
| **VII. 接口先行，实现按档演进** | 四个骨架期契约文件零改动；只新增实现类与值对象；用 microVM 反套签名不需要加方法（`enforce(SandboxAction)` 已足够） | ✅ |
| **VIII. 数据私有、状态外置、存储从简** | 白名单是**启动期配置**不是状态，不需要外置；无凭证、无落库、无明文密钥；`application.yaml` 里不出现任何 token | ✅ |
| **IX. 三触发源复用同一条链路** | 白名单在 `Sandbox` 内，与触发源无关——CLI / Web / 定时任务三条路进来都过同一个 `enforce` | ✅ |
| **X. 每个 user story 有可演示 Demo** | 4 条 user story 各有独立可跑的验证：越界路径被拦（US1）/ 越界命令一次没跑（US2）/ 形似域名被拒（US3）/ 空配置全拒（US4） | ✅ |

**初判：无违规项，无需在 Complexity Tracking 里辩解。**

---

## 需要说明的设计偏离

本节共 11 条偏离/抉择，**全部有 spec 裁决或机器可判据支撑**，逐条落档：

### D1 配置载体与键名：`application.yaml` + 三个 snake_case 键【已报批】

**抉择**：`file.allowed_paths` / `shell.allowed_commands` / `http.allowed_domains` 三块写在 `oryxos-boot/src/main/resources/application.yaml`，**键名逐字照课件与 `docs/TechnicalSolution.md` §6.7**（snake_case，不是本仓其他地方惯用的 kebab-case）。

**理由**：① 这三个键是课件「本节交付物」与 §6.7 双重写定的**已定字面量**，改形态属软门禁 ②；② Spring 的 relaxed binding 让 `allowed_paths` 与 `allowed-paths` 都能绑到 `allowedPaths`，运维写哪种都能用——**但文档与默认配置只认课件那一种形态**，避免两处真相；③ 三个键**不带 `oryxos.` 前缀**（与 `oryxos.memory.backend` 那批不同），因为它们不是「OryxOS 的配置」，而是沙箱这个子系统的配置，§6.7 原文即如此。

**连带（spec 裁决 1）**：骨架期冻结的 `specs/000-runtime-kernel/contracts/sandbox-config.md` 那份 `.oryxos/sandbox.yaml` + `file.read` / `file.write` 拆分方案被本节取代，须**挂带日期的修订注**（不改它的历史正文）；读写分权随之推迟，本节 `FILE_READ` / `FILE_WRITE` 共用同一份路径白名单（FR-002）。

**备选与否决**：`.oryxos/sandbox.yaml`（否决：与课件冲突；且 `oryxos init` 明确 MUST NOT 创建 `sandbox.yaml`，见 `specs/003-cli-session/contracts/cli-surface.md`）。

### D2 三个值对象落在 `oryxos-tool`，注册靠既有装配类【依赖方向强制】

**抉择**：`FileSandboxProperties` / `ShellSandboxProperties` / `HttpSandboxProperties` 三个 `record` 放在 `oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/sandbox/`；注册方式是在**既有** `CliAssemblyConfiguration` 上补 `@EnableConfigurationProperties({...})`，**不新建 `@Configuration` 类**。

**理由**：① **依赖方向强制**——`WhitelistSandbox` 的构造器收这三个对象，若把它们放进 `oryxos-cli` 就会造出 `oryxos-tool → oryxos-cli` 的反向依赖边；② 第 20 节契约 §6.3 明令 `MUST NOT` 在非 boot 模块新增 `@Component` / `@Configuration`，而 `CliAssemblyConfiguration` 正是「全部 `@Bean` 的唯一落点」，在它身上加一行注解属本节权限内；③ 记录类带 `@ConfigurationProperties` 而**不带** `@Component` / `@Configuration`，不违反 §6.3。

**备选与否决**：① 在 `oryxos-tool` 新建 `SandboxConfiguration`（否决：撞 §6.3；`oryxos-provider` 的 `ProviderConfiguration` 是第 16 节交付形态，§6.3 是其后才立的纪律）；② `@ConfigurationPropertiesScan`（否决：引入整包扫描语义，且与「装配点只有一处」相冲）。

### D3 Bean 方法名 `sandbox()`、返回类型 `Sandbox`、带 `@ConditionalOnMissingBean(Sandbox.class)`【前序契约逐字要求】

**抉择**：装配处方法签名保持 `@Bean @ConditionalOnMissingBean(Sandbox.class) public Sandbox sandbox(...)`，只把方法体从 `new PermissiveSandbox()` 换成 `new WhitelistSandbox(...)`。

**理由**：① 第 20 节契约 §3 逐字要求第 24 节「加一行 `@Bean @ConditionalOnMissingBean(Sandbox.class) … sandbox(...)` 并**删掉**本节那行」；② 返回类型取**接缝类型** `Sandbox` 而非具体类——接缝是 `Sandbox`，装配点只承诺「提供一个沙箱」，换成容器/microVM 实现时这里改的是方法体；③ 保留 `@ConditionalOnMissingBean` 是**有意留的覆盖接缝**：部署方若自建一个 `Sandbox` Bean（例如未来的容器档）即接管，这正是「加实现类 + 装配处换一行」那条升级路径的可执行形态。

**连带**：`ToolAssemblyTest` 的让位用例**改写而非删除**——它断言的不再是「临时档让位」，而是「白名单档让位给容器里已有的同类型 Bean」（SC-005 的新形态）。

### D4 课件 §3.2 示例代码的四处硬化【spec Edge Cases 要求，课件样例未覆盖】

课件给的是**示意实现**，逐行照抄会在 spec 明确要求的四条边界上出错。四处硬化，每处都能指回 spec 的一条 Edge Case：

| # | 课件写法 | 本节写法 | 为什么（spec 依据） |
| --- | --- | --- | --- |
| ① | `allowedRoots` 只 `normalize()`，**不** `toAbsolutePath()` | `Path::of → normalize → toAbsolutePath` | 配置里的默认值是 `.`（相对路径），而 `checkFilePath` 把目标转成了绝对路径——**相对与绝对路径 `startsWith` 恒为 false**，照抄会让默认配置全拒。spec Edge Case：「判定基准是进程当前工作目录，配置在启动时一次性标准化为绝对路径」 |
| ② | `URI.create(url).getHost()` 直接用，无 null / 异常处理 | host 为 null 或 `URI.create` 抛 `IllegalArgumentException` 时，转成 `SandboxViolationException` | spec Edge Case：「URL 没有 host 时 MUST 被拒，MUST NOT 因为 null 比较的意外结果放行」（照抄会 NPE，被 `ToolExecutor` 记成一条**看不懂**的失败） |
| ③ | 域名按原样比对 | host 与 pattern 都 `toLowerCase(Locale.ROOT)` 后比对 | spec Edge Case：「host 比对 MUST 不区分大小写」；同一条 Edge Case 也要求 **shell 命令比对区分大小写**（那是文件名），故大小写折叠只加在域名这一路 |
| ④ | 三个集合直接 `Set.copyOf` / `List.copyOf` | 构造时**丢弃空/纯空白条目**；`checkShellCommand` 另加一条「首 token 为空即拒」 | spec Edge Case：「配置里写了空串或纯空白的条目 MUST NOT 变成匹配一切的通配符」+「命令里只有空白：切不出 token，MUST 被拒」。注意 `"".split("\\s+")` 返回 `[""]`（长度 1），照抄会让「空白命令 + 空条目」这条组合**正好放行** |

**这四处不是「自行发挥」**：每一条都对应 spec 里已写死的一条 Edge Case，且都有测试钉死（`WhitelistSandboxTest`）。课件的示例代码在 plan 里作为**对照物**保留，便于 reviewer 逐行比对。

### D5 箭头 switch → 经典冒号 switch + 抛异常的 `default`【构建门禁】

**抉择**：课件的

```java
switch (action.type()) {
    case FILE_READ, FILE_WRITE -> checkFilePath(action.target());
    ...
}
```

落地为经典冒号形态，并补一个抛 `IllegalStateException` 的 `default`。

**理由**：① 语法禁区句（P3C/PMD 6.55 + ASM 9.7.1 的类型解析）——全仓 main 源码零箭头 switch，本节不破例；② 仓库既有形态同向：`MemoryServiceImpl.recall` 对枚举 `capabilities()` 就是「经典冒号 + 抛异常的 `default`」，本节与它同形；③ `default` 抛异常而不是静默——将来若给 `ActionType` 加第五值（例如 SMTP），忘了加路由会**当场炸**而不是静默放行。

### D6 不做 SMTP 第四类校验【已报批】

**抉择**：只做文件路径 / 可执行文件 / HTTP 域名三类。`docs/TechnicalSolution.md` §6.7 与 `CLAUDE.md` 记的「四个校验（含 `checkSmtpEndpoint` + `smtp.allowed_endpoints`）」按本节口径修订为**三个**，SMTP 记扩展阶段。

**理由**：① `ActionType` 只有四值、**没有 SMTP 的路由**——`checkSmtpEndpoint` 在核心阶段无路可走；② 没有消费者（`EmailNotifyAdapter` 归扩展阶段，§6.8）；③ 加第五个 `ActionType` 是改第 20 节定死的接口字面量（软门禁 ②），且会破坏「用 microVM 反套接口签名」的中立性论证。

### D7 删除面与连带触碰的三个前序节测试文件

**删除**：`PermissiveSandbox.java`、`PermissiveSandboxTest.java`、装配处 `sandbox()` 的方法体（方法本身保留，见 D3）。

**连带触碰（只去掉对已删类的引用，断言逻辑一字不动）**：

| 文件 | 触碰内容 | 为什么必须动 |
| --- | --- | --- |
| `oryxos-tool/.../registry/AnnotatedToolAdapterTest.java` | `new PermissiveSandbox()` → 真 `WhitelistSandbox`（空白名单） | **编译不过**（引用了已删类）。该用例只读 `notify` 的 JSON Schema、不执行工具，故空白名单不影响它；用真实现而不是再造一个全放行替身 |
| `oryxos-tool/.../builtin/RecordingSandbox.java` | javadoc 里「为什么不直接注入 `PermissiveSandbox`」一句改写 | 该替身的**存在理由**就是「记录型 vs 全放行」，类名没了这句话就悬空 |
| `oryxos-cli/.../LogStreamSeparationTest.java` | 一条 `@DisplayName` / 注释里的 `PermissiveSandbox` 引用改写（断言不动） | 那条断言守的是「dev 档根级别不被压到 ERROR」这个**通用**日志契约，理由句里引的类已删 |

**MUST NOT 触碰**：`specs/005-tool-registry/` 下的任何历史文档——它们记录的是第 20 节当时的形态，改它们等于篡改历史留痕。

### D8 新增一条课件 harness 之外的测试：配置键字面量绑定【能机器判的不留给人】

**抉择**：`ToolAssemblyTest` 加一条用例——用属性源传 `file.allowed_paths[0]` / `shell.allowed_commands[0]` / `http.allowed_domains[0]`，起**真**上下文，断言从容器里取出的 `Sandbox` Bean 对白名单内目标放行、对白名单外目标拒绝。

**理由**：键名写错（`allowedPaths` 写成 `allowed_path`、`file` 写成 `files`）的后果是**白名单静默为空 = 全拒**：装上跑不了、日志里没有一行异常、要人去翻配置类才看得出来。这是「能机器判」的典型坏法。这条用例同时把 D1 的 snake_case 字面量钉死——**改键名即变红**。

**代价**：多一条起上下文的用例（约 1 秒）。与「能机器判的绝不留给人」相比可忽略。

### D9 通配符域名覆盖任意层级子域（写进 javadoc，不加测试）

**抉择**：`*.example.com` 命中 `api.example.com` **也命中** `a.b.example.com`；裸域 `example.com` **不**命中。

**理由**：spec 只规定了「`*.example.com` 命中 `api.example.com`、不命中 `evil-example.com`」，**没有**规定多层级语义。点号边界实现（`host.endsWith(".example.com")`）天然覆盖任意层级，这是主流 web 服务器 `ServerAlias` 的同款语义。按「能机器判的绝不留给人，机器判不了的绝不自行发挥」——这条**不是** spec 定的要求，故**不写测试去钉死它**（写了就等于自行发明一条要求），只在 javadoc 里写明实际语义，让 reviewer 一眼看到。

### D10 前缀比对用 `Path.startsWith`（组件级），不是 `String.startsWith`【spec Edge Case 的机器可判定形态】

**抉择**：`checkFilePath` 用 `Path::startsWith` 判前缀。

**理由**：spec Edge Case 要求「`/workspace-evil/x` MUST NOT 被 `/workspace` 放行——前缀必须落在**路径分隔符**边界上」。`Path.startsWith` 是**按路径组件**比对的，这条边界天然成立；换成字符串 `startsWith` 就会把 `workspace-evil` 判成命中。**这一条是「选对 API 即满足要求」的形态**，不需要额外写边界判断代码，但要有一条测试钉死（`WhitelistSandboxTest` 的「假朋友」用例）。

### D11 文档连带修订清单

| # | 文件 | 修订内容 | 依据 |
| --- | --- | --- | --- |
| ① | `specs/000-runtime-kernel/contracts/sandbox-config.md` | **加**带日期的修订注（「2026-10-17 被 `specs/007-sandbox-whitelist` 取代」+ 取代理由），**不改历史正文** | 裁决 1 连带 |
| ② | `docs/TechnicalSolution.md` §6.7 | 「四个校验」→ 三个；SMTP 记扩展阶段（挂日期修订注） | 裁决 2 连带 |
| ③ | `CLAUDE.md` | ① 沙箱窗口期条目改为「已关闭」（第 24 节已交付）；② 「四个校验」→ 三个；③ 缓解③「自动让位、不回头删代码」的表述按裁决 3 回修为「整类删除」；④ 「当前状态」与「待办」里的第 24 节条目更新；⑤ 「Tool 体系 → Sandbox」条目补白名单语义要点 | 裁决 1/2/3 连带 + 节级收尾 |

**MUST NOT 触碰**：`website/`（本次不涉及对外定位 / 能力 / 路线图表述——沙箱是内核实现细节，不是对外承诺面）。

---

## Project Structure

### Documentation (this feature)

```text
specs/007-sandbox-whitelist/
├── spec.md              # 需求规约（已定稿，8 条冲突仲裁记录）
├── plan.md              # 本文件（/speckit-plan 输出）
├── research.md          # Phase 0：D1~D11 的决策、理由与否决的备选
├── data-model.md        # Phase 1：值对象与实现类的字段/校验规则/不变式
├── quickstart.md        # Phase 1：可跑的验证向导（含真链路人工项）
├── contracts/
│   └── sandbox-whitelist.md   # Phase 1：本节冻结的字面量、语义边界表、装配改动、MUST NOT
├── checklists/
│   └── requirements.md  # /speckit-specify 产出（16/16）
└── tasks.md             # /speckit-tasks 产出（下一步）
```

### Source Code (repository root)

```text
oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/sandbox/
├── Sandbox.java                     # 既有，MUST NOT 改（FR-011）
├── SandboxAction.java               # 既有，MUST NOT 改
├── ActionType.java                  # 既有，MUST NOT 改
├── SandboxViolationException.java   # 既有，MUST NOT 改
├── PermissiveSandbox.java           # 【删除】整类
├── WhitelistSandbox.java            # 【新增】唯一实现：三类校验 + 三个私有方法
├── FileSandboxProperties.java       # 【新增】@ConfigurationProperties(prefix = "file")
├── ShellSandboxProperties.java      # 【新增】@ConfigurationProperties(prefix = "shell")
└── HttpSandboxProperties.java       # 【新增】@ConfigurationProperties(prefix = "http")

oryxos-tool/src/test/java/io/oryxlabs/oryxos/tool/sandbox/
├── PermissiveSandboxTest.java       # 【删除】随实现类一起走
├── WhitelistSandboxTest.java        # 【新增】harness：三类成对 + 绕过 + 空配置 + 入口唯一
└── SandboxToolWiringTest.java       # 【新增】harness：真沙箱接四个工具，IO 没发生

oryxos-tool/src/test/java/io/oryxlabs/oryxos/tool/builtin/
└── RecordingSandbox.java            # 【改注释】去掉对已删类的引用（D7）

oryxos-tool/src/test/java/io/oryxlabs/oryxos/tool/registry/
└── AnnotatedToolAdapterTest.java    # 【改一行】dummy 沙箱改用真实现（D7，编译必需）

oryxos-cli/src/main/java/io/oryxlabs/oryxos/cli/
└── CliAssemblyConfiguration.java    # 【改】+ @EnableConfigurationProperties；sandbox() 方法体换成 WhitelistSandbox

oryxos-cli/src/test/java/io/oryxlabs/oryxos/cli/
├── ToolAssemblyTest.java            # 【改】两条沙箱断言改写 + 新增配置键绑定用例（D8）
└── LogStreamSeparationTest.java     # 【改注释】去掉对已删类的引用（D7）

oryxos-boot/src/main/resources/
└── application.yaml                 # 【改】三块白名单 + 「空 = 全拒」「劝阻级防线」两条注释

specs/000-runtime-kernel/contracts/
└── sandbox-config.md                # 【加修订注】被本节取代（D11①）

docs/
└── TechnicalSolution.md             # 【加修订注】§6.7 三个校验（D11②）

CLAUDE.md                            # 【改】窗口期关闭 / 三个校验 / 缓解③ 回修（D11③）
```

**不被触碰的**（本节最该被 review 的「零改动面」）：

```text
oryxos-tool/.../builtin/FileTools.java       # FR-009：enforce 首行前序节已交付（NotifyTools 第 19 节，其余四个第 20 节）
oryxos-tool/.../builtin/ShellTools.java
oryxos-tool/.../builtin/HttpTools.java
oryxos-tool/.../notify/NotifyTools.java
oryxos-tool/.../search/WebSearchTools.java
oryxos-core/.../engine/ToolExecutor.java     # FR-010：失败审计路径复用
oryxos-storage/src/main/resources/db/schema.sql   # 本节无存储改动
```

---

## Complexity Tracking

**无违规项。** 本节没有需要向章程辩解的复杂度：不新增模块、不新增依赖、不新增表、不改任何前序节公共接口、不新增对外概念（唯一的对外概念是课件「本节交付物」逐字点名的 `WhitelistSandbox` 与三个值对象）。

---

## 已知接缝

| 接缝 | 现状 | 何时收 |
| --- | --- | --- |
| 读写分权 | 本节 `FILE_READ` / `FILE_WRITE` 共用一份路径白名单（`ActionType` 早已把两类分开建模，接缝在） | 需要时按 `specs/000-.../sandbox-config.md` 的 `file.read` / `file.write` 拆分复活（该契约本节取代，但拆分思路保留） |
| SMTP 第四类校验 | 不做（`ActionType` 无 SMTP 路由） | `EmailNotifyAdapter` 落地的那一节，连同第五个 `ActionType` 一起评估 |
| 软链接真实目标解析 | 不做（`toAbsolutePath()` 而非 `toRealPath()`） | 属「防蓄意绕过」；本章程定位的白名单是**劝阻级**防线 |
| Profile 级 Tool Policy | 不做 | 扩展阶段；核心阶段 Profile 的 `tools` 字段算是雏形 |
| 白名单热更新 | 不做（改配置重启生效） | 核心阶段不做配置热加载 |
| 危险操作审批层 | 不做 | 第 24 节之后评估（白名单之外的中间层） |
| 容器 / microVM 档 | 不做 | 扩展阶段，信号驱动；`@ConditionalOnMissingBean(Sandbox.class)` 就是它的入口（D3） |
| MCP 子进程 / 代码执行等暴露面 | 本节只覆盖「工具执行」这一条路径 | 第 23 节结语点名的 Hermes 坑：关住 shell 与文件工具 ≠ 关住整个 Agent；`enforce(SandboxAction)` 已为扩调用点留好位 |

---

## Constitution Check（Phase 1 复核）

设计产出（`data-model.md` / `contracts/sandbox-whitelist.md` / `quickstart.md`）写完后按原则逐条复核：

| 原则 | Phase 1 复核结论 |
| --- | --- |
| I | ✅ 无新增坐标、无新增工件（`contracts/sandbox-whitelist.md` §4 装配改动只有一行注解 + 一个方法体） |
| II | ✅ `contracts/sandbox-whitelist.md` §6 把容器 / microVM / SMTP / 审批层 / 热更新逐条列为 MUST NOT |
| III | ✅ 不涉及 |
| IV | ✅ `contracts/sandbox-whitelist.md` §3 明确白名单**不下放**到 `AGENT.md`，`ActionType` 不新增值 |
| V | ✅ 全部落在 `oryxos-tool` 的 `sandbox` 包 |
| VI | ✅ `data-model.md` §4 把「校验失败 = 一次普通工具执行失败」写成不变式，且零新增审计代码 |
| VII | ✅ `contracts/sandbox-whitelist.md` §1 把四个骨架期文件列为「MUST NOT 修改」 |
| VIII | ✅ 无存储改动、无凭证、`application.yaml` 里只有白名单条目（域名不是凭证） |
| IX | ✅ 白名单在 `Sandbox` 内，与触发源无关 |
| X | ✅ `quickstart.md` 四节分别对应 US1~US4，每节都有可复制执行的命令 |

**Phase 1 复核：无新增违规项。**

---

## Phase 0 产出

[research.md](./research.md) —— D1~D11 逐条的「决策 / 理由 / 否决的备选」，含课件示例代码与本节实现的逐行对照表（D4 / D5 / D10）。**零 `NEEDS CLARIFICATION`**（四处软门禁冲突已在 `/speckit-specify` 阶段报用户裁决并落进 spec 的 Clarifications）。

## Phase 1 产出

- [data-model.md](./data-model.md) —— 三个值对象 + 实现类的字段、标准化规则、三类校验的判定表、不变式（含「校验先于 IO」与「空 = 全拒」）
- [contracts/sandbox-whitelist.md](./contracts/sandbox-whitelist.md) —— 冻结字面量 / 语义边界表 / 配置载体与默认清单 / 装配改动与删除面 / MUST NOT 清单
- [quickstart.md](./quickstart.md) —— 四节验证向导（US1~US4）+ 真链路人工项 + 排障对照表

## 下一步

`/speckit-tasks` → 任务清单与课件「本节交付物」逐项比对 → **停下等用户确认**（流程唯一的固定停点）。
