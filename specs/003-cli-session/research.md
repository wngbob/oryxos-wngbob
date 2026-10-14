# Phase 0 Research: CLI 与会话持久化

**来源**：[spec.md](spec.md)（尤其「冲突仲裁记录」15 条与 `## Clarifications`）；`docs/class/第18节：CLI 功能概述、实现思路与代码讲解.md`；`docs/TechnicalSolution.md` §8.1 / §8.4 / §8.6 / §8.7 / §9.2；`specs/000-runtime-kernel/data-model.md` A1 与 `contracts/cli.md`。

本文件只记**需要决策**的点。已在 spec 的「冲突仲裁记录」里定过的（接口名、字段增补、模块归属、init 产物、serve 范围等）不重复论证，只在其影响面延伸处补一条。

---

## D1. `SessionService` 的实现落 `oryxos-core`（`JpaSessionService`）

- **Decision**：`SessionService` 接口在 `oryxos-core`（第 17 节已交付），本节补 `getOrCreate` / `get` 两个操作；实现类 `JpaSessionService` 同样放 `oryxos-core` 的 `io.oryxlabs.oryxos.core.session` 包，直接注入 `oryxos-storage` 的 `SessionRepository`。
- **Rationale**：`oryxos-core → oryxos-storage` 是既定已报批边（`specs/000-runtime-kernel/plan.md`：「oryxos-storage 是共享基础设施模块：core 与各能力模块可直接依赖其实体与 Repository」）。第 17 节的 `ToolExecutor` 已经这么干了——它直接 import `io.oryxlabs.oryxos.storage.entity.ToolInvocation` 与 `ToolInvocationRepository`。本节沿用同一模式，不是新开一条边。
- **Alternatives considered**：
  - 放 `oryxos-storage` —— `oryxos-storage` MUST NOT 依赖 `oryxos-core`（反向即成环），而实现类必须实现 core 的 `SessionService` 接口、必须认识 core 的 `Session` / `SessionMessage`。**直接排除**。
  - 在 `oryxos-storage` 再放一个不依赖 core 的「纯持久化」实现，core 里再包一层适配 —— 多一层没有调用方的抽象（章程原则 II），且第 17 节已用 `ToolExecutor` 定下「core 直连 Repository」的先例。

## D2. `Session` 增 `channel` / `userId`，工厂签名变更

- **Decision**：`Session.create(String id, String channel, String userId, String profileName)`，新增 `channel()` / `userId()` 两个访问器；`id` 仍由调用方传入。
- **Rationale**：`sessions` 表有 `channel` 与 `user_id` 两列（A1），领域类型没有这两个字段就落不了库。这是**改第 17 节交付的公共接口**，属软门禁 ④，已报批。
- **Alternatives considered**：
  - 只落 `id`、把 `channel`/`user_id` 从 `id` 里切出来 —— 解析字符串反推字段（`id.split(":")`）在 profile 名含分隔符时立刻出错，且把「标识格式」变成隐式契约。**排除**。
  - 给 `Session` 加 `status` / `archivedAt` —— 本节没有任何调用方（归档是第 26 节的 `DELETE` 端点），违反章程原则 II。**排除**（spec 仲裁 4）。
- **影响面**：第 17 节契约 `specs/002-react-loop/contracts/session-model.md` §2 的「五个字段齐备」断言改为七项；该文件同步修订（属本次变更的一部分，不是遗漏）。

## D3. `sessions` 的实体与 Repository 落 `oryxos-storage`，实体只持 `messagesJson` 字符串

- **Decision**：新增 `io.oryxlabs.oryxos.storage.entity.SessionEntity`（`@Table(name = "sessions")`，`@Id String sessionId`，**不用** `@GeneratedValue`）与 `io.oryxlabs.oryxos.storage.repository.SessionRepository extends JpaRepository<SessionEntity, String>`；实体字段全部是基本类型与 `String`，**不认识** core 的 `Session` / `SessionMessage`。
- **Rationale**：`oryxos-storage` 不依赖 `oryxos-core`（D1），故实体不能出现 core 的类型。`messages_json` 对 storage 而言就是一个 `String` 列——它只负责「原样存、原样取」，不理解里面的 JSON。这正好把**序列化口径**（core 的事）与**存储口径**（storage 的事）分开，谁改谁负责。
- **Alternatives considered**：
  - 实体放 core（与 `JpaSessionService` 同处） —— 实体属于持久化层，放 core 会让 core 同时承担领域与持久化两种职责，且与 `ToolInvocation` / `LlmCall` 的既有归位不一致。**排除**。
  - 实体持结构化字段（每列一个属性） —— A1 定的是 `messages_json TEXT` 一列，核心阶段不按条拆表。**排除**。

## D4. `messages_json` 用**专用 JSON DTO** 序列化，不直接序列化 `SessionMessage`

- **Decision**：在 core 的 `session` 包内放一个**包级私有**的记录类型（暂名 `MessageJson`，含 `role` / `content` / `toolCalls` / `toolCallId` / `timestamp`），作为落盘形状的唯一映射；`role` 落**小写**字符串（`system` / `user` / `assistant` / `tool`），`timestamp` 落 ISO-8601；工具调用元素只取 `id` / `type` / `name` / `arguments` 四个字段。
- **Rationale**：
  1. **`SessionMessage.Role` 的枚举名是大写**（`USER` / `ASSISTANT`），而 A1 要求小写。若直接序列化 `SessionMessage`，就得给 `Role` 加 Jackson 注解——那是改第 17 节交付的公共类型（软门禁 ④），且把**磁盘格式**绑死在 Java 枚举命名上（将来改枚举名就会悄悄改磁盘格式）。用一层 DTO 隔开，磁盘格式由 DTO 单独钉住。
  2. DTO 是**包级私有**，不是对外概念，不触发软门禁 ①。
  3. 落盘格式是 A1 定的契约，应当独立于内存类型演进。
- **Alternatives considered**：
  - 直接序列化 `SessionMessage` + 给 `Role` 加 `@JsonValue` —— 见上，改前序节公共类型且耦合磁盘格式。**排除**。
  - 用 `Map<String,Object>` 手工拼 —— 丢掉编译期字段校验，键名拼错在运行期才炸。**排除**。
- **序列化器落位**：与 DTO 一起放 core 的 `session` 包（包级私有），由 `JpaSessionService` 使用；**不引入新依赖**（Jackson 已在 core 的编译类路径上，见 D19）。

## D5. 「模拟重启」的判定口径：**全新 JDBC 连接直读**

- **Decision**：`SessionRepositoryTest` 里「重启后历史还在」这条断言，做法是——经 Repository 存一条会话后，**新建一条独立的 JDBC 连接**（`DriverManager.getConnection(jdbcUrl())`，与写入侧不共享任何缓存、EntityManager 或 Spring 上下文）直接 `SELECT messages_json FROM sessions WHERE session_id = ?`，断言取到的 JSON 文本与写入时一致、且能被独立反序列化回等价消息列表。
- **Rationale**：这条测试要防的是「数据其实只在内存里、换个进程就没了」。同一条持久化上下文里 `save` 完再 `findById`，走的是 Hibernate 的一级缓存，**证明不了落盘**。新建连接绕开一切缓存，直读文件，才是「进程重启」的有效模拟。
- **Alternatives considered**：
  - `@DirtiesContext(AFTER_EACH_TEST_METHOD)` + `@TestMethodOrder` 让「存」与「读」落在两个 Spring 上下文 —— 依赖测试方法执行顺序，顺序一变就假绿，且多起一次上下文拖慢测试。**排除**。
  - 手工 `new SpringApplicationBuilder(...).run()` 起第二个上下文 —— 为一条断言引入一整套上下文装配，成本与收益不成比例。**排除**。
  - `EntityManager.clear()` 后重查 —— 只清了 JPA 缓存，SQLite 里到底有没有那一行仍未被证明。**排除**。

## D6. 两个测试类的职责切分（与课件 harness 的落位有出入，理由如下）

- **Decision**：
  - `SessionServiceTest`（`oryxos-core/src/test/.../core/session/`，纯单测，Mockito + `Map` 支撑的 Repository 替身）：幂等（同一三元组历次 `getOrCreate` 同一标识）、隔离（三元组任一不同即不同会话）、标识生成只此一处（断言 `cli:local:daily-weather` 这个形状）、序列化往返（含工具调用与工具结果的消息存取后逐条一致）。
  - `SessionRepositoryTest`（`oryxos-storage/src/test/.../storage/repository/`，`@DataJpaTest` + 真 SQLite + 手工脚本）：`sessions` 表由脚本建得出来且列齐备、经 Repository 存取往返保真、「重启」重查历史还在（D5）。
- **Rationale**：课件把「`messages_json` 序列化回读后消息完整」列在 `SessionRepositoryTest`，但**序列化器在 core**（D4），而 `oryxos-storage` 的**测试**也不能反向依赖 core（Maven 会让 core(compile)→storage 与 storage(test)→core 形成回边，破坏反应堆构建顺序）。故把「序列化往返」放到序列化器所在的 core，「表往返」留在 storage；两条合起来覆盖课件那条 harness 的完整意图，且各自守在自己能看见的边界内。
- **Alternatives considered**：给 storage 加 test 作用域的 core 依赖以照抄课件落位 —— 为一条断言引入回边依赖，得不偿失。**排除**。
- **对课件 harness 的保真**：课件写出代码的那条关键回归测试（`同一三元组_历次getOrCreate都是同一个Session`）**逐条断言原样落地**在 `SessionServiceTest`，方法名译成英文 `sameTriple_getOrCreateAlwaysReturnsSameSession`，课件原文进 `@DisplayName`。断言三条（同一三元组两次同 id / channel 不同则 id 不同）一条不少。

## D7. 轻命令访问 SQLite 走**原生 JDBC**，不启动 Spring

- **Decision**：`session list`（列会话）、`status`（活跃会话数、数据库可达性）、`init`（执行建表脚本）三条轻命令直接用 `DriverManager` 开 `jdbc:sqlite:<工作区>/oryxos.db` 执行 SQL，全部经由一个包内小工具类（暂名 `WorkspaceDb`）收口，不由各命令各写一份连接代码。
- **Rationale**：FR-003 要求轻命令不启动 Spring 上下文（启动一次 2~4 秒，与「秒回」直接冲突）。而这些命令要读库——所以必须有一条不依赖 Spring 的读库路径。`sqlite-jdbc` 经 `oryxos-storage` 传递到 `oryxos-cli` 的编译类路径上（D17），不需要新依赖。
- **Alternatives considered**：
  - 轻命令也起 Spring、只把 `init` / `profile list` 留作轻命令 —— 与上游 `contracts/cli.md` 明列的轻命令清单冲突（`status` / `session list` 都在里面）。**排除**。
  - 用 `DataSource` 但不启动上下文 —— 仍然是 Spring 的对象，且要手工装配 HikariCP 配置。**排除**。

## D8. `init` 内嵌执行建表脚本的方式

- **Decision**：从 classpath 读 `/db/schema.sql`（`oryxos-storage` 的 main resources，经模块依赖传递到 `oryxos-cli`），按 `;` 切分逐句 `Statement.execute`，连同一个 JDBC 连接一次跑完；脚本内已全是 `CREATE TABLE IF NOT EXISTS`，天然幂等，与 FR-001 的幂等要求一致。
- **Rationale**：`LlmCallRepositoryTest` 已经用同一手法（读 classpath 脚本 + `split(";")` + `Statement.execute`）跑通过，是**已被验证可行**的既有做法，本节复用而不是另发明一种脚本执行器。脚本头注释本就写着「或由后续 `oryxos init` 命令内嵌执行（US 落地时接入）」——本节正是那次接入。
- **Alternatives considered**：
  - 引入 Flyway / Liquibase —— 新第三方依赖（软门禁 ⑥），且章程与技术方案都把「手工建表脚本」定为核心阶段口径。**排除**。
  - `ddl-auto=update` 建表 —— 章程与技术方案双重点名禁止依赖它做建表/迁移。**排除**。
- **注意**：脚本按 `;` 切分的前提是**脚本里不出现分号字符串字面量**。本节往 `schema.sql` 追加 `sessions` 建表语句时 MUST 保持这一前提（不加含分号的 `DEFAULT` / 注释内分号会破坏切分——注释里的分号也会被切，故新表语句不写含分号的注释）。

## D9. 重命令的 Spring 启动类 `CliApplication` 落 `oryxos-cli`

- **Decision**：`oryxos-cli` 新增 `io.oryxlabs.oryxos.cli.CliApplication`，标注 `@SpringBootApplication(scanBasePackages = "io.oryxlabs.oryxos")` + `@EntityScan("io.oryxlabs.oryxos.storage.entity")` + `@EnableJpaRepositories("io.oryxlabs.oryxos.storage.repository")`；`chat` / `serve` / `gateway` 三条重命令通过它起上下文。
- **Rationale**：
  1. `oryxos-boot` 聚合 `oryxos-cli`（`boot → cli`），故 `cli → boot` 会成环——CLI 不能复用 `OryxosApplication`。
  2. `@SpringBootApplication` 的 `scanBasePackages` **带不动** `@EntityScan` / `@EnableJpaRepositories`：后两者的默认扫描根是**主类自己的包**。第 17 节 `OryxosApplication` 已经踩过这个坑（实体在兄弟包 → 「Found 0 JPA repository interfaces」），本节按课件「重命令启动类显式声明 `@EnableJpaRepositories`/`@EntityScan` 的 `basePackages`」逐字照办。这条也是课件「五、做完怎么验」里的人工项（启动日志里 `Found N JPA repository interfaces` 的 N > 0）。
- **Alternatives considered**：
  - 把 `OryxosApplication` 从 `oryxos-boot` 搬到 `oryxos-cli` —— 改前序节交付的类名与位置（软门禁 ②），且 `CLAUDE.md` 明写「`oryxos-boot` 的 `OryxosApplication` 是 fat JAR 主类」。**排除**。
  - 在 `oryxos-cli` 用裸 `@Configuration` + `@EnableAutoConfiguration` 避开第二个 `@SpringBootApplication` —— 见 D10，实测没有必要，反而丢掉 `@SpringBootConfiguration` 语义（`@SpringBootTest` 之类会找不到配置类）。**排除**。

## D10. 两个 `@SpringBootApplication` 并存：打包无歧义，但**扫描会撞车**（结论已修订）

- **Decision**：接受 `oryxos-boot` 与 `oryxos-cli` 各有一个 `@SpringBootApplication` 类并存于 fat JAR 内；但 `CliApplication` 的 `scanBasePackages` **MUST 收窄**到 `{"io.oryxlabs.oryxos.cli", "io.oryxlabs.oryxos.provider"}`，MUST NOT 放宽到 `io.oryxlabs.oryxos`。
- **Rationale 之一（打包，成立）**：`MainClassFinder`（`repackage` 目标的自动探测）只扫**本项目自己的** `target/classes`（`oryxos-boot/target/classes` 里只有 `OryxosApplication`），依赖 jar 里的类不在扫描范围内——所以 `CliApplication` 加在 `oryxos-cli`（依赖 jar）里不会让 `repackage` 变成「找到多个主类」而失败。**D11 之后更彻底**：`oryxos-boot/pom.xml` 的 `spring-boot-maven-plugin` 显式写了 `<mainClass>io.oryxlabs.oryxos.cli.OryxOsCli</mainClass>`，自动探测根本不再运行。
- **Rationale 之二（扫描，原判断被实测推翻）**：本条**原写「已核实无害……按类名去重，无实际影响」，是错的**。第 7 步跑重命令冒烟时，`java -jar oryxos-boot/target/oryxos.jar chat --profile weather --message "你好"` 100% 报 `BeanDefinitionOverrideException`（`llmCallRepository` 被 `@EnableJpaRepositories declared on CliApplication` 与 `... declared on OryxosApplication` 注册两遍）后 `APPLICATION FAILED TO START`。根因：`scanBasePackages = "io.oryxlabs.oryxos"` 会把同根下的 `OryxosApplication` 当配置类注册，而它自带**同一套** `@EntityScan` / `@EnableJpaRepositories`；Spring Data 的仓库注册是**每个 `@EnableJpaRepositories` 各跑一遍**，不是「按类名去重」，第二次注册即撞车（Spring Boot 2.1 起 Bean 覆盖默认关闭）。修法见 [contracts/cli-assembly.md](contracts/cli-assembly.md) §2。
- **原「核实方式」为什么没发现**：原写的是「`java -jar ...jar --version` 与 `--help` 可用」——这两条都是**轻命令，不启动 Spring 上下文**，根本走不到仓库注册。**教训：核实重命令 MUST 真跑一次重命令**，`--help` 只能证明命令树挂对了。
- **代价（如实记录）**：`@SpringBootTest` 若不显式指定配置类，会因「找到多个 `@SpringBootConfiguration`」而报错。本节的测试全部不用 `@SpringBootTest`（`SessionServiceTest` 是纯单测，`SessionRepositoryTest` 用 `@DataJpaTest` + `oryxos-storage` 自己的 `StorageTestApplication`），故不受影响。将来若要用，MUST 显式传 `classes = ...`。
- **复核留痕（2026-10-14）——修法的不对称性**：`CliApplication` 收窄后，正向（CLI 入口）已安全；但 `OryxosApplication` 的宽扫描根未动（仲裁 17「本节不改该类」），**反向撞车处于潜伏期**——谁以它为入口启动，它会扫到 `CliApplication`，`BeanDefinitionOverrideException` 反向上演。当前无触发点（fat JAR 主类是 `OryxOsCli`；`oryxos-boot` 无 `src/test`）。**第 26 节定 `OryxosApplication` 去留时 MUST 一并处理**（收窄它的扫描根，或删除该类）。

## D11. 发行入口：`oryxos-boot` 的 fat JAR 就是 CLI，`oryxos-cli` 的 shade 插件移除

- **Decision**：`oryxos-boot/pom.xml` 的 `spring-boot-maven-plugin` 增显式 `<mainClass>io.oryxlabs.oryxos.cli.OryxOsCli</mainClass>`；`oryxos-cli/pom.xml` **移除 `maven-shade-plugin`**（保留 `<resources>` 的 `oryxos-version.properties` 过滤）。唯一发行物 = `oryxos-boot/target/oryxos.jar`，`java -jar oryxos.jar <command>` 支持全部 12 条命令。
- **Rationale**：
  1. **`application.yaml` 只在 `oryxos-boot`**（spec 仲裁 5，已报批）。重命令要读 `spring.datasource.*`（否则 DataSource 自动装配失败）与 `oryxos.providers`（否则 `ProviderProperties.validate()` 报错）。CLI 与 `application.yaml` 必须在**同一个 classpath** 上，而 CLI 的类打进 boot fat JAR 的 `BOOT-INF/lib/` 后，`BOOT-INF/classes/application.yaml` 正是同一个 classpath——**零配置重复**。
  2. **这是本节自己制造的陷阱，不是既有问题**：本节之前 `oryxos-cli` 只依赖 picocli，它那个 shaded jar 只跑 `--version`，没有 Spring，看不出问题。本节给它加 6 个模块依赖 + `spring-boot-starter` 后，shaded jar 里有了 Spring 却**没有 `application.yaml`**，表现是「轻命令正常、重命令静默炸」——正是最难查的一类。既然要改，就改到没有这个 jar。
  3. **参考实现（`/home/wngbob/oryxos`，用户已授权参考）就是这么做的**：`oryxos-cli` 无 `<build>` 段、`src/main/resources/` 为空；`oryxos-boot/pom.xml` 的 `spring-boot-maven-plugin` 的 `<mainClass>` 是 `io.oryxos.cli.OryxOsCli`；发行脚本 `bin/oryx-server` 里就是 `java -jar libs/oryxos-boot-*.jar serve --port "$port"`，运维侧外部配置经 `-Dspring.config.additional-location="optional:file:$CONFIG_DIR/"` 叠加。本节照搬「一个发行 jar」这一层，外部 `config/` 约定属发行形态，归后续节。
- **Alternatives considered**：
  - **保留 `oryxos-cli` 的 shade，补 `ServicesResourceTransformer` + 两个 `AppendingTransformer`** —— 修的是「资源没合并」，**修不了「classpath 上没有 `application.yaml`」**，重命令照样炸。**排除**（这是本节最初的计划，被 D11 取代）。
  - **保留 shade，另给 `oryxos-cli` 放一份 `application.yaml`** —— 两处配置必然漂移，正是参考实现头部注释点名要避免的（「框架内部管道不在此重复…避免与 jar 漂移」）。**排除**。
  - **把 `application.yaml` 从 `oryxos-boot` 搬到 `oryxos-cli`** —— 改骨架期已交付的配置位置（软门禁 ②），且与仲裁 5「留在 `oryxos-boot` 不动」冲突。**排除**。
  - **给 CLI 单独做「只跑轻命令」的瘦 jar** —— 一个 jar 只支持 12 条命令里的 6 条，且失败模式是运行期才暴露，比没有更糟。**排除**。
- **连带影响（同一节内一并完成）**：
  - `CLAUDE.md` 中「`oryxos-cli` 的 `OryxOsCli` 是 Picocli 主入口，已由 maven-shade-plugin 打成可执行 fat JAR，`java -jar oryxos-cli/target/oryxos-cli-0.1.0-SNAPSHOT.jar --version` 可直接运行」→ 改为 `java -jar oryxos-boot/target/oryxos.jar --version`。
  - 上游 `specs/000-runtime-kernel/contracts/cli.md` 第 3 行 `java -jar oryxos-cli-*.jar <command>` 的 **jar 名**被细化（命令面逐字不变）。该骨架契约文件**不修改**——supersession 记在 spec 冲突仲裁记录 17。
  - `OryxosApplication` 不再是 fat JAR 主类；本节**不改该类**（第 26 节 `serve` 落地时再定去留）。
- **验证方式**（第 7 步证据）：`java -jar oryxos-boot/target/oryxos.jar --help` 列出 12 条命令；`... init` / `... status` 秒回；`... chat`（无 key 时应给出清晰报错而非 `Failed to configure a DataSource`）证明 `application.yaml` 已被读到；启动日志 `Found N JPA repository interfaces` 的 N > 0。

## D12. Picocli 子命令的组织：12 个叶子命令类 + 4 个命名空间组命令类

- **Decision**：12 个叶子 `@Command` 类（`InitCommand` / `StatusCommand` / `ChatCommand` / `ServeCommand` / `GatewayCommand` / `ProfileListCommand` / `ProfileCreateCommand` / `ProfileShowCommand` / `ProfileDeleteCommand` / `ProviderListCommand` / `ToolListCommand` / `SessionListCommand`），外加 4 个只做分组的 `@Command` 类（`ProfileCommand` / `ProviderCommand` / `ToolCommand` / `SessionCommand`），由 `OryxOsCli` 的 `subcommands` 挂上。
- **Rationale**：对外契约是**空格分隔**的多级命令（`oryxos profile list`，上游 `contracts/cli.md` 逐条如此）。Picocli 的子命令匹配按**首个 token** 走，名为 `"profile list"` 的单个子命令永远匹配不上；多级命令只能用「组命令 + 子命令」表达。课件的「12 个 `@Command` 子命令类」指的正是 12 个**叶子**命令（其骨架片段给的就是 `ProfileListCommand` 这类名字），组命令不改变对外命令面。
- **Alternatives considered**：把 `provider list` / `tool list` / `session list` 也做成叶子组 —— 三个组命令各只有一个子命令，看着多余，但「多级命令必须有组」是 Picocli 的硬约束；若改成 `oryxos provider-list` 就是改对外契约（软禁 ②）。**排除**。

## D13. 两个空接缝用 `@ConditionalOnMissingBean` 声明

- **Decision**：`ToolCatalog` 的空实现（`find` 恒返回 `Optional.empty()`、`availableFor` 恒返回空表）与 `MemorySource` 的空实现（`longTermMemory` 恒返回 `Optional.empty()`）以 `@Bean` + `@ConditionalOnMissingBean` 声明在 `oryxos-cli` 的装配配置里。
- **Rationale**：spec 仲裁 8 要求「真实实现一落地即自动让位，MUST NOT 形成第二处真相」。`@ConditionalOnMissingBean` 是 Spring 原生表达的「有真的就用真的」——第 20 / 22 节把 `ToolRegistry` / `MemoryService` 注册成 `ToolCatalog` / `MemorySource` 后，这两个空 Bean 自动不再创建，**不需要回头删代码**。
- **Alternatives considered**：
  - 用 `@Primary` 标在空实现上 —— 那是「永远优先用假的」，正好反过来，真实实现会被永久遮蔽。**排除**。
  - 直接写死 `new EmptyToolCatalog()` 塞进构造函数 —— 第 20 节落地时要手改这行，容易漏。**排除**。

## D14. 轻命令的「已知厂商集合」取自 `ProviderProperties.SUPPORTED_PROVIDERS`

- **Decision**：轻命令构造 `ProfileLoader(agentsDir, knownProviders)` 时，`knownProviders` 直接传 `ProviderProperties.SUPPORTED_PROVIDERS`；`provider list` 的「底座支持的厂商全集」同样取自它。
- **Rationale**：`ProviderProperties.validate()` 判定「厂商名是否合法」用的**就是**这个集合，用它做轻命令的校验口径，与重命令路径**逐字一致**；它已是 `oryxos-provider` 里的 `public static final`，`oryxos-cli` 本就依赖该模块（D17）。在 CLI 侧再写一份同名集合就是第二处真相，加厂商时必漏改一处。
- **Alternatives considered**：
  - 轻命令读 `application.yaml` 拿 `oryxos.providers` —— 轻命令**不启动 Spring 上下文**（FR-003），不加载任何 Spring 配置；即便手工用 SnakeYAML 读，拿到的也只是 `${DEEPSEEK_API_KEY}` 这个**占位符本身**，拿不到「环境变量到底设没设」——而后者才是「就绪」的定义。要凑出就绪状态只能去猜环境变量名，正是 `cli-surface.md` §2.9 明令禁止的。**排除**（spec 仲裁 6，已报批不动）。
  - 把厂商集合硬编码进 CLI —— 第二处真相。**排除**。

## D15. `serve` / `gateway` 只启动 Spring 上下文并前台常驻

- **Decision**：两条命令起上下文后打印「本模式待接入什么、归哪一节」，然后阻塞（`Thread.currentThread().join()` 或等价方式）直到 Ctrl+C；`serve` 的 `--port` 选项接受解析但只回显「端口在第 26 节启用」。
- **Rationale**：spec 仲裁 15（已报批）。课件明写「本节只把『模式』与『三种模式共享同一份 Profile 配置和同一套会话存储』立起来；它们各自接入什么由后续节补齐」。
- **Alternatives considered**：见 spec 仲裁 15 的三个选项。

## D16. 退出码交给 Picocli 默认约定

- **Decision**：不自定义退出码映射。参数错误走 `CommandLine.ExitCode.USAGE`（2）、执行抛异常走 `SOFTWARE`（1）、成功 0；`OryxOsCli.main` 既有的 `System.exit(new CommandLine(...).execute(args))` 保持不变。
- **Rationale**：FR-017 的三个值恰好是 Picocli 的默认约定，**已经实现**，本节只需确认并加断言，不写多余代码（章程原则 II）。
- **需要补的**：`serve` / `gateway` 的「前台常驻」不能被当成异常——它们的 `run()` 正常返回 0 是错的（那是「启动完就退出」），必须阻塞；而阻塞本身不抛异常，退出码自然由 Ctrl+C 决定。
- **「运行错误退出码 1」的落地**：命令实现里抛出带清晰中文消息的 `RuntimeException`（Picocli 默认把执行期异常打成 1），不手工 `System.exit(1)`——手工 exit 会跳过 Picocli 的异常处理与用法打印。

## D17. 依赖变更清单

| 模块 | 变更 | 理由 |
| --- | --- | --- |
| `oryxos-core` | 无新增依赖 | Jackson 已在编译类路径（D19）；`oryxos-storage` 第 17 节已加 |
| `oryxos-storage` | 无新增依赖 | 只用已有的 `spring-boot-starter-data-jpa` + `sqlite-jdbc` |
| `oryxos-cli` | **新增依赖**：`oryxos-core` / `oryxos-provider` / `oryxos-tool` / `oryxos-memory` / `oryxos-storage` / `oryxos-channel-cli` + `spring-boot-starter`；**移除**：`maven-shade-plugin`（D11） | 重命令要起上下文、要接 `ProviderService`、要挂 `CliChannel`、要写库（spec 仲裁 5）。**不加 `oryxos-web`**（spec 仲裁 15）。shade 移除后本模块是普通库模块 |
| `oryxos-channel-cli` | **新增**：`oryxos-core` | `CliChannel` 要调 `AgentService.process`（章程原则 IX 的唯一入口） |
| `oryxos-boot` | **无依赖变更**；**修改**：`spring-boot-maven-plugin` 增显式 `<mainClass>io.oryxlabs.oryxos.cli.OryxOsCli</mainClass>`（D11） | 它已依赖全部八个模块，传递拿到新增的一切；显式 mainClass 让本模块的 fat JAR 成为**唯一发行入口**，且与 `application.yaml` 同 classpath |

**版本全部由父 pom 的 `dependencyManagement` 统一管理**（`picocli` 4.7.7、六个内部模块 `${project.version}`，见 `pom.xml`），各模块 pom 里不写 `<version>`。

## D18. 语法禁区

- 避开 P3C / ASM 解析不了的 Java 18+ 语法形态（增强 `switch` 的 `default ->` 写法等），静态检查是构建门禁。
- 构造函数前若加 `//` 行注释会触发 `CommentsMustHaveJavadocFormatRule`——用 Javadoc 或把注释挪进方法体。
- 中文字符串常量没问题（项目已大量使用），但**测试方法名必须英文**（驼峰或 snake_case），课件中文名译成英文并用 `@DisplayName` 保留原文。

## D19. 本地依赖核实记录（写前门禁 H3）

| 待核实项 | 核实方式 | 结论 |
| --- | --- | --- |
| Jackson（`ObjectMapper` / `JsonNode` / `jackson-datatype-jsr310`）在 `oryxos-core` 编译类路径上 | `mvn dependency:tree -pl oryxos-core` | ✅ `jackson-databind:2.21.4:compile`、`jackson-datatype-jsr310:2.21.4:compile`（经 `spring-ai-model:1.1.8` 传递）。**不新增第三方依赖**，软门禁 ⑥ 不触发 |
| `AssistantMessage.ToolCall` 能否被 Jackson 直接读写 | `javap -p org.springframework.ai.chat.messages.AssistantMessage$ToolCall`（从 `spring-ai-model-1.1.8.jar` 解出） | ✅ 是 `public final class ... extends java.lang.Record`，四个组件 `id`/`type`/`name`/`arguments`，无自定义 Jackson 注解。Jackson 2.21 原生支持 record 的读写，**不需要额外模块** |
| `picocli` 版本可用 | 读 `pom.xml` | ✅ `info.picocli:picocli:4.7.7`，已声明在 `oryxos-cli` 且版本由父 pom 管理 |
| 六个内部模块能否被 `oryxos-cli` 直接依赖 | 读 `pom.xml` 的 `dependencyManagement` | ✅ `oryxos-core` / `oryxos-provider` / `oryxos-memory` / `oryxos-tool` / `oryxos-channel-cli` / `oryxos-web` / `oryxos-storage` 均已统一管理版本 |
| `spring-boot-starter` 可用 | 读 `oryxos-channel-cli/pom.xml` | ✅ 该模块已在用 |
| `sqlite-jdbc` 能否传递到 `oryxos-cli` | 读 `oryxos-storage/pom.xml`（compile 作用域） | ✅ 经 `oryxos-storage` 传递到 `oryxos-cli` 编译类路径 |
| 手工建表脚本能否从 classpath 读到并执行 | 读 `oryxos-storage` 的 `LlmCallRepositoryTest` | ✅ 既有测试已用「读 `/db/schema.sql` + `split(";")` + `Statement.execute`」跑通 |
| `@DataJpaTest` 在本项目的可行配置 | 读 `LlmCallRepositoryTest` + `StorageTestApplication` | ✅ 既有测试已用「真 SQLite 临时文件 + `@DynamicPropertySource` + `ddl-auto=none` + `Replace.NONE`」跑通 |

## D20. 「无效 Agent 的原因」由 `ProfileLoader` 交出，CLI 不重写校验

- **Decision**：`ProfileLoader` 新增 `loadAllDetailed()`，返回「装载成功的 Profile 清单 + 失败明细（Agent 名 + 原因）」；`loadAll()` 的签名与行为**保持不动**。`profile list` / `profile show` 经新方法取原因。
- **Rationale**：上游 `contracts/cli.md` 要求 `profile list` 标「有效/无效（无效附原因）」，而 `loadAll()` 把每条失败吞成 ERROR 日志（`ProfileLoader.java:104-110`）后只返回成功的。**唯一知道「什么叫有效」的组件就是 `ProfileLoader`**，原因只能由它交出；CLI 侧重写一套 frontmatter 校验就是第二处真相（加字段 / 改规则时必漏改一处），正是本节要避免的那类问题。
- **Alternatives considered**：
  - `profile list` 只报「有效/无效」，原因统一写成「AGENT.md 缺失或 frontmatter 非法（详见日志）」——零改动前序节接口，但用户拿不到具体错在哪，「附原因」的调试价值打折。**已提请用户裁决，未采纳**。
  - `profile list` 自己解析 `AGENT.md` 生成原因 —— 第二处真相。**排除**。
  - 改 `loadAll()` 的返回类型 —— 破坏既有调用方（第 16 节测试与 `oryxos-boot` 装配），比新增方法代价大得多。**排除**。
  - 在 CLI 侧挂 logback `ListAppender` 捕获 `ProfileLoader` 的 ERROR 事件 —— 靠日志反解析结构化数据，脆弱且让轻命令依赖日志框架内部。**排除**。
- **代价**：`ProfileLoader` 是第 16 节交付的公共类型，本次是**纯 additive 扩展**（新增方法 + 一个公开记录类型），不改任何既有签名语义、不破坏既有调用方。已报批（spec 冲突仲裁记录 16）。
- **顺带定下的落点**：`profile show <name>` 的「校验结果」段同样走 `loadAllDetailed()` 的失败明细——同一个原因源，两条命令不各写一份。

## 未决项

无。本节的 15 条仲裁已在 spec 里定案（其中 3 条经用户裁决），D1~D19 全部给出决策与理由，没有留给实现期临场发挥的口子。
