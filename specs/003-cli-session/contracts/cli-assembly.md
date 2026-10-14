# Contract: 发行入口与重命令的 Spring 装配

**来源**：[spec.md](../spec.md) FR-003 / FR-011 + 冲突仲裁记录 4 / 5 / 8 / 17；第 17 节契约 `specs/002-react-loop/contracts/core-seams.md` §5（四个 core 接缝的接线约定）。这是**发行形态 + 装配契约**：`<mainClass>`、装配类位置、Bean 让位机制的变更必须走修订程序。

---

## 0. 本节新增 / 修改的装配件

| 件 | 模块 | 动作 | 职责 |
| --- | --- | --- | --- |
| `oryxos-boot/pom.xml` 的 `<mainClass>` | `oryxos-boot` | **改** | 把发行入口指向 `OryxOsCli`（§1） |
| `maven-shade-plugin` | `oryxos-cli` | **删** | 该模块退回普通库模块（§1） |
| `CliApplication` | `oryxos-cli` | **新增** | 重命令的 Spring 启动类（§2） |
| `CliAssemblyConfiguration` | `oryxos-cli` | **新增** | 全部 `@Bean` 显式装配的唯一落点（§3 / §4 / §5） |
| `ProviderService implements LlmClient` | `oryxos-provider` | **改一行** | 第 17 节刻意留白的接线（§4） |

**MUST NOT 新增第二个装配配置类**——装配只此一处，第 20 / 22 / 26 节加 Bean 时改这一个类。

---

## 1. 发行入口：`oryxos-boot` 的 fat JAR 就是 CLI

**唯一发行物 = `oryxos-boot/target/oryxos.jar`**，`java -jar oryxos-boot/target/oryxos.jar <command>` 支持全部 12 条命令。

| 落地项 | 内容 |
| --- | --- |
| `oryxos-boot/pom.xml` | `spring-boot-maven-plugin` 增**显式** `<mainClass>io.oryxlabs.oryxos.cli.OryxOsCli</mainClass>` |
| `oryxos-cli/pom.xml` | **移除** `maven-shade-plugin`（保留 `oryxos-version.properties` 的 `<resources>` 过滤）；`src/main/resources/` 仍只有这一个文件，**MUST NOT 放 `application.yaml`** |

**为什么**（详见 [research.md](../research.md) D11）：

1. `application.yaml` 只在 `oryxos-boot`（仲裁 5，已报批）。重命令要读 `spring.datasource.*` 与 `oryxos.providers`，二者必须与 CLI 的类**同 classpath**。CLI 的类打进 boot fat JAR 的 `BOOT-INF/lib/` 后，`BOOT-INF/classes/application.yaml` 正是同一个 classpath——**零配置重复**。
2. `oryxos-cli` 若自打 fat JAR，那个 jar 的 classpath 上**没有** `application.yaml`，重命令会因 `spring.datasource.url` 缺失而在 DataSource 自动装配阶段失败。本节之前该 jar 只依赖 picocli、跑 `--version`，看不出问题；本节给它加 6 个模块依赖与 `spring-boot-starter` 之后，它就变成「轻命令能跑、重命令静默炸」——**这个陷阱是本节自己制造的**。
3. 参考实现 `/home/wngbob/oryxos`（用户已授权参考）正是如此：`oryxos-cli` 无 `<build>` 段、`src/main/resources/` 为空；`oryxos-boot` 的 `spring-boot-maven-plugin` 的 `<mainClass>` 是 `io.oryxos.cli.OryxOsCli`；发行脚本里就是 `java -jar libs/oryxos-boot-*.jar serve --port "$port"`。

**已定字面量的细化（已报批）**：上游 `specs/000-runtime-kernel/contracts/cli.md` 第 3 行的 `java -jar oryxos-cli-*.jar <command>` 中的 **jar 名**被细化为 `oryxos-boot/target/oryxos.jar`；**12 条命令面逐字不变**。该骨架契约文件**不修改**——supersession 记在 spec 冲突仲裁记录 17。

**连带影响（同一节内完成）**：`CLAUDE.md` 中「`oryxos-cli` 的 `OryxOsCli` 已由 maven-shade-plugin 打成可执行 fat JAR，`java -jar oryxos-cli/target/oryxos-cli-0.1.0-SNAPSHOT.jar --version` 可直接运行」一句改为 `java -jar oryxos-boot/target/oryxos.jar --version`。

**MUST NOT**：
- 在 `oryxos-cli` 恢复 shade 插件，或在其中放第二份 `application.yaml`（两处配置必然漂移）；
- 把 `application.yaml` 从 `oryxos-boot` 搬走（改骨架期已交付的配置位置）。

**核查命令**（第 7 步证据）：

```bash
grep -c "maven-shade-plugin" oryxos-cli/pom.xml        # 期望 0
grep -n "mainClass" oryxos-boot/pom.xml                # 期望 1 行,值为 io.oryxlabs.oryxos.cli.OryxOsCli
java -jar oryxos-boot/target/oryxos.jar --help          # 期望列出 12 条命令
```

---

## 2. `CliApplication`（重命令的启动类）

```java
@SpringBootApplication(
    scanBasePackages = {
      "io.oryxlabs.oryxos.cli", // CliApplication + CliAssemblyConfiguration
      "io.oryxlabs.oryxos.provider" // ProviderConfiguration
    })
@EntityScan("io.oryxlabs.oryxos.storage.entity")
@EnableJpaRepositories("io.oryxlabs.oryxos.storage.repository")
public class CliApplication {}
```

- **`scanBasePackages` MUST NOT 放宽到 `io.oryxlabs.oryxos`**（第 7 步重命令冒烟实测后修订，见下「与 `OryxosApplication` 的关系」）：放宽会扫到同根下的 `io.oryxlabs.oryxos.boot.OryxosApplication`，重命令直接起不来。**只列这两个包的理由**：本上下文需要的配置类只有 `CliAssemblyConfiguration`（`cli`）与 `ProviderConfiguration`（`provider`）；core / tool / memory / storage / channel-cli 的类刻意不带 Spring 注解，Bean 一律在 `CliAssemblyConfiguration` 里显式声明（§3）。**将来若在第三个包里新增了带注解的类，MUST 在本行补上**——不补不会被装配，且不报错。

- **位置**：`oryxos-cli`（`io.oryxlabs.oryxos.cli`）。放这里的理由：`oryxos-boot` 聚合 `oryxos-cli`（`boot → cli`），反向即成环，CLI **不能**复用 `OryxosApplication`（[research.md](../research.md) D9）。
- **`@EntityScan` / `@EnableJpaRepositories` MUST 显式写**：`@SpringBootApplication` 的 `scanBasePackages` **带不动**这两个——它们的默认扫描根是**主类自己的包**。第 17 节 `OryxosApplication` 已经踩过这个坑（实体在兄弟包 → 日志报 `Found 0 JPA repository interfaces`）。这条也是课件「做完怎么验」的人工项。
- **重命令怎么用它**（`chat` / `serve` / `gateway` 三条一致）：

  ```java
  try (ConfigurableApplicationContext ctx =
      new SpringApplicationBuilder(CliApplication.class)
          .web(WebApplicationType.NONE)   // 不起 Web 容器
          .bannerMode(Banner.Mode.OFF)
          .run()) { ... }
  ```

  `try-with-resources` 保证命令返回时上下文关闭（`chat` 的 `/quit` 后进程能正常退出）。`WebApplicationType.NONE` 是硬要求——本节**不引入 `oryxos-web`**（仲裁 15）。

**与 `OryxosApplication` 的关系**：两个 `@SpringBootApplication` 并存于 fat JAR 内，**要分开看**（[research.md](../research.md) D10）：

- **「打包无歧义」成立**：`oryxos-boot` 的插件显式写了 `<mainClass>`，`MainClassFinder` 的自动探测根本不运行。
- **「扫描无害」不成立**（第 7 步重命令冒烟实测推翻原判断，已就地修订）：`CliApplication` 的 `scanBasePackages` 若放宽到 `io.oryxlabs.oryxos`，会扫到同根下的 `OryxosApplication`，把它当配置类注册。它自己也是 `@SpringBootApplication` 且带**同一套** `@EntityScan` / `@EnableJpaRepositories`，于是 JPA 仓库 Bean 被**注册两遍**——第二次注册撞 `BeanDefinitionOverrideException`（Spring Boot 2.1 起默认关闭 Bean 覆盖），重命令在 fat JAR 上 100% 起不来：

  ```text
  Bootstrapping Spring Data JPA repositories in DEFAULT mode.   ← 第二遍
  BeanDefinitionOverrideException: Invalid bean definition with name 'llmCallRepository'
    defined in @EnableJpaRepositories declared on CliApplication ...
    since there is already [...] defined in @EnableJpaRepositories declared on OryxosApplication
  APPLICATION FAILED TO START
  ```

  **为什么此前没发现**：`oryxos-cli` 的 classpath 上没有 `oryxos-boot`（`boot → cli`，反向不存在），所以模块内测试全绿；只有发行物里两者同处一个 classpath 才撞。**轻命令不启动 Spring 上下文，所以只跑 `--help` / `--version` 永远看不出来**——核实「重命令起得来」MUST 真跑一次重命令。

**本节不改 `OryxosApplication`**：它自本节起不再是 fat JAR 主类，去留由第 26 节 `serve` 落地时定。

**复核留痕（2026-10-14）——「不改」留下的不对称**：`OryxosApplication` 自己的 `scanBasePackages = "io.oryxlabs.oryxos"` 仍是宽根。以它为入口启动时，它会扫到 `CliApplication`，上文的 `BeanDefinitionOverrideException` **反向上演**。当前无触发点（fat JAR 主类是 `OryxOsCli`；`oryxos-boot` 无 `src/test`），但**第 26 节定其去留时 MUST 一并处理：收窄它的扫描根，或删除该类**。

**MUST NOT**：给 `CliApplication` 加 `@EnableWebMvc` / 起 `WebApplicationType.SERVLET`；在 `oryxos-cli` 加 `oryxos-web` 依赖。

---

## 3. 两个空接缝：`@ConditionalOnMissingBean`

第 17 节定义的四个 core 接缝里，`LlmClient` 与 `SessionService` 本节接真实实现（§4 / §5），**`ToolCatalog` 与 `MemorySource` 本节挂空实现**——工具与记忆尚未就位时也要能跑通对话（FR-011）。

| 接缝 | 空实现的行为 | 由谁在后续节替换 |
| --- | --- | --- |
| `ToolCatalog` | `find(name)` 恒 `Optional.empty()`；`availableFor(profile)` 恒空表 | 第 20 节 `oryxos-tool.ToolRegistry` |
| `MemorySource` | `longTermMemory(profile)` 恒 `Optional.empty()` | 第 22 节 `oryxos-memory.MemoryService` |

**让位机制**：两者以 `@Bean` + `@ConditionalOnMissingBean` 声明在 `CliAssemblyConfiguration`。第 20 / 22 节把真实实现注册成同类型 Bean 后，这两个空 Bean **自动不再创建**，**不需要回头删代码**（仲裁 8）。

**MUST NOT**：
- 用 `@Primary` 标在空实现上——那是「永远优先用假的」，正好反过来，真实实现会被永久遮蔽；
- 在命令类里硬写 `new EmptyToolCatalog()`——第 20 节落地时要手改，容易漏。

**空实现类的可见性**：包级私有嵌套在 `CliAssemblyConfiguration` 内即可，**不进 public 面**（不触发软门禁 ①）。

---

## 4. `ProviderService implements LlmClient`（一行接线）

第 17 节把 `LlmClient` 的实现方刻意留白，约定在本节装配时一并接上（`specs/002-react-loop/contracts/core-seams.md` §5）。

- **改动内容**：`oryxos-provider` 的 `ProviderService` 类声明加 `implements LlmClient`，方法上加 `@Override`。**MUST NOT 改方法体、改签名、改可见性。**
- **已核实签名逐字一致**：`ProviderService` 现有 `public ChatResponse chat(String sessionId, Profile profile, Prompt prompt)`，与 `LlmClient.chat(...)` 完全相同（[research.md](../research.md) D19）——只加 `implements` 即可编译通过；若不匹配，编译期即报错，不靠人工判断。
- **自动工具执行仍然禁用**：`ProviderService` 内部关闭 Spring AI 自动工具执行的开关由第 16 节单点控制，**本节一行不动**（章程原则 III）。本节**不新增任何** Spring AI 自动执行路径。

---

## 5. `CliAssemblyConfiguration` 的其余 Bean

全部 `@Bean` 显式装配，收在 `oryxos-cli` 的 `CliAssemblyConfiguration` 一处：

| Bean | 类型 | 说明 |
| --- | --- | --- |
| 工作区 | `Workspace` | 工作区根路径 + 「是否在工作区内」判定（`workspace/` 子包） |
| Agent 解析 | `AgentResolver` | **唯一闸门**：按名解析 Agent，或在工作区唯一 Agent 时取缺省；多于一个且未指定时抛带可选清单的清晰错误。**`profile_name` 存在性校验落在这里**（仲裁 11 / 12） |
| 会话持久化 | `SessionService` → `JpaSessionService` | 注入 `SessionRepository`（§6） |
| 模型调用 | `LlmClient` | 即 `ProviderService`（§4） |
| 入站渠道 | `CliChannel` | `oryxos-channel-cli`，`channel = cli`；`chat` 经它调 `AgentService.process` |
| 引擎 | `AgentService` 及其依赖 | 第 17 节交付，本节首次真正装配 |
| 空接缝 ×2 | `ToolCatalog` / `MemorySource` | §3 |

**装配只此一处**：12 个命令类**MUST NOT** 自己 `new` 服务——它们从上下文取 Bean，或（轻命令）只用 `workspace/` 子包里的文件 / JDBC 辅助类。

**`JpaSessionService` 不加 `@Component`**：它由 `CliAssemblyConfiguration` 以 `@Bean` 声明。理由：core 的类型不带 Spring 装配注解，「谁装配、装配成什么」只在装配类一处可见；也避免与第 26 节的装配点形成第二处真相（第 26 节需要时**复用同一个 `@Bean` 方法或把它提到共享配置**，不得复制一份）。

---

## 6. `SessionService` 的实现放 core

`JpaSessionService` 在 `oryxos-core` 的 `io.oryxlabs.oryxos.core.session` 包，注入 `oryxos-storage` 的 `SessionRepository`。

- **依赖方向**：`oryxos-core → oryxos-storage` 是**已报批的既定边**（`specs/000-runtime-kernel` plan：「storage 是共享基础设施模块，core 与各能力模块可直接依赖其实体与 Repository」），与第 17 节 `ToolExecutor` 直接用 `ToolInvocationRepository` 是**同一模式**。放 storage 会成环（`storage → core` 被禁止）。
- **反向边一条都不新增**：`oryxos-storage → oryxos-core` 的依赖**MUST NOT** 出现（[quickstart.md](../quickstart.md) §3 有可 grep 的核查命令）。

---

## 7. 本节**不**做的装配

| 不做 | 归哪一节 | 理由 |
| --- | --- | --- |
| 起 Web 容器、装 REST 端点 | 第 26 节 | 仲裁 15；本节**不引入 `oryxos-web`** |
| 挂载入站 IM Channel | 扩展阶段 | 课件明写的边界 |
| 注册与调度定时任务 | 第 25 / 28 节 | 同上 |
| 发行侧的 `config/application.yaml` 外部配置约定 | 后续节 | 参考实现经 `-Dspring.config.additional-location` 叠加；本节的 `.oryxos/` 工作区是**另一层**概念，两者不混 |
| `ConfigLoader`（CLAUDE.md 模块表列在 `oryxos-cli` 职责里） | 无节号，**本节无调用方** | 本节没有任何命令需要加载凭证：重命令经 Spring 读 `oryxos.providers` 的 `${ENV_VAR}` 占位，轻命令一律标「未接入」（仲裁 6 / 11）。没有调用方的类不提前造（章程原则 II）。**此条在 Step 5 固定停点提请用户确认** |
