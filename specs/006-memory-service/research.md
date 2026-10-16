# Phase 0 Research: Memory（第 22 节）

**Feature**: `specs/006-memory-service` | **Date**: 2026-10-15 | **Plan**: [plan.md](./plan.md)

> 本文件只记**动手前实测核实过**的东西与**有依据的设计决策**。凡"我记得"而没跑过命令的，一律不写进来。

---

## 1. 依赖核实（`mvn dependency:tree` 实测，非记忆）

**命令**：

```bash
./mvnw -o -pl oryxos-tool  dependency:tree -Dincludes='com.fasterxml.jackson.core:*'
./mvnw -o -pl oryxos-tool  dependency:tree | grep -E "spring-ai-model|spring-web:jar"
./mvnw -o -pl oryxos-storage dependency:tree -Dincludes='org.springframework.data:spring-data-jpa'
```

**结论**：

| 坐标 | 版本 | 在哪个模块已验证 | 本节用途 |
| --- | --- | --- | --- |
| `org.springframework.ai:spring-ai-model` | `1.1.8` | `oryxos-tool`（compile） | `@Tool` / `@ToolParam` 注解 + schema 生成 |
| `org.springframework:spring-web` | `6.2.19` | `oryxos-tool`（compile） | `RestClient`（Mem0 档 HTTP 出口） |
| `com.fasterxml.jackson.core:jackson-databind` | `2.21.4` | `oryxos-tool`（**传递边**：`spring-ai-model` → `com.github.victools:jsonschema-module-jackson`）；`oryxos-cli` 已**显式声明** | Mem0 响应体解析 |
| `org.springframework.data:spring-data-jpa` | `3.5.13` | `oryxos-storage`（compile） | `MemoryEntryRepository` |
| `org.sqlite:sqlite-jdbc`、`org.hibernate.orm:hibernate-community-dialects` | BOM 托管 | `oryxos-storage`（compile） | 测试期真 SQLite |
| `org.springframework.boot:spring-boot-starter-test` | BOM 托管 | `oryxos-storage`（test） | JUnit 5 / AssertJ / `MockRestServiceServer` |

**决策**：
- `oryxos-memory` 的 `pom.xml` 新增 **4 条 compile 依赖**（`oryxos-storage` / `spring-ai-model` / `spring-web` / `jackson-databind`）+ **1 条 test 依赖**（`spring-boot-starter-test`）。
- **`jackson-databind` 显式化**而不是靠传递边。备选：不声明，靠 `victools` 那条链。**否决理由**：那条链不是契约，上游换一个依赖就断；本仓已有明确先例与纪律（`oryxos-cli/pom.xml` 的注释逐字写着「直接 import 的类型就该有直接的依赖边，发行物不新增任何工件」）。
- **不新增任何第三方依赖**。备选：用 `mockwebserver3`（`oryxos-tool` 的既有测试依赖）给 Mem0 档起一个真 HTTP server。**否决理由**：会往 `oryxos-memory` 引入 `okhttp` + `kotlin-stdlib` 的整套连带（还要复制 `oryxos-tool/pom.xml` 里那段把 `kotlin-stdlib` 钉回 2.1.21 的模块级 `<dependencyManagement>`，以及它「看着冗余但删了构建会红」的注释）。而 `MockRestServiceServer` 随 `spring-boot-starter-test` 已就位，且**课件明确要求"mock RestClient"**。

## 2. API 核实（`javap` 实测）

```bash
javap -cp ~/.m2/.../spring-web-7.0.9.jar org.springframework.web.client.RestClient
javap -cp ~/.m2/.../spring-web-7.0.9.jar 'org.springframework.web.client.RestClient$Builder'
javap -cp ~/.m2/.../spring-test-7.0.9.jar org.springframework.test.web.client.MockRestServiceServer
javap -cp ~/.m2/.../spring-data-commons-*.jar org.springframework.data.domain.PageRequest
```

| 待核实 | 实测结果 | 本节怎么用 |
| --- | --- | --- |
| `RestClient` 的请求链 | `post()` → `RequestBodyUriSpec`、`get()` → `RequestHeadersUriSpec`、`mutate()`、`builder()` | `Mem0MemoryStore` 的 POST/GET 调用 |
| `RestClient.Builder` | `baseUrl(String)` / `baseUrl(URI)` / `requestFactory(ClientHttpRequestFactory)` / `build()` | 生产构造器造带超时的客户端 |
| `MockRestServiceServer.bindTo` | `static MockRestServiceServerBuilder bindTo(RestClient.Builder)` **存在**；另有 `expect(...)` / `verify()` | `Mem0MemoryStoreTest` 的 mock 接缝 |
| `PageRequest.of` | `of(int, int)` **存在** | SQLite 档归档 `LIMIT`：`PageRequest.of(0, MAX_ARCHIVE_ROWS)` |

**由 `bindTo(RestClient.Builder)` 推出的一条设计约束**（D9 的依据）：`bindTo` 与 `.build()` 必须是**同一个 builder 实例**。若 `Mem0MemoryStore` 拿到 builder 之后再自己调 `requestFactory(...)` 装超时，会把 mock server 装的请求工厂**覆盖掉**，mock 静默失效（测试照样绿，但一个请求都没被断言）。故测试接缝收在**已完成**的 `RestClient` 上，生产路径的超时责任留在实现内。

**语法保守度实测**（避开 P3C/PMD 6.55 解析不了的形态）：

```bash
grep -rn "switch (" --include=*.java oryxos-*/src          # 命中 1 处：WebPageText.java:104，经典冒号 switch
grep -rn -- "case .* ->" --include=*.java oryxos-*/src      # 主源码 0 处
```

→ 本节的门面路由用**经典冒号 switch**，不用箭头 switch / switch 表达式 / 模式匹配。

## 3. 前序节既有事实的核实

| 事实 | 怎么核实的 | 结论 |
| --- | --- | --- |
| `MemorySource` 是本仓**自造**的接缝，上游没有 | `git grep -l "MemorySource" HEAD`（上游仓库）= 空 | 上游 core 侧是 `MemoryService.buildContext(Session)`；本仓按 D1 实现既有接缝 |
| `EmptyMemorySource` 的让位机制 | 读 `CliAssemblyConfiguration.java:541-545` | `@ConditionalOnMissingBean(MemorySource.class)`，声明在类**末尾** |
| 让位机制**按 `@Bean` 声明顺序**求值 | 读 `toolCatalog()` 的 javadoc（第 20 节踩过 `BeanDefinitionOverrideException`） | `memoryService()` MUST 声明在 `memorySource()` **之前** |
| `PromptBuilder` 怎么消费接缝 | 读 `PromptBuilder.build` | `memorySource.longTermMemory(profile).filter(m -> !m.isBlank()).ifPresent(...)`——**空串自动被滤掉**，故 D6 的 `""` 返回值能直接达成 FR-014 |
| 内置工具返回 `ToolResult` | grep 六个既有工具类 | **全部** `ToolResult`（D3 的依据） |
| `ToolExecutor` 的失败路径 | 读 `ToolExecutor.java:84-89` | 「不打断循环：转成失败结果返回，原因进审计、进日志、也回灌给模型」——**D4 的硬依据** |
| `AnnotatedToolAdapter` 的返回类型处理 | 读 `toResult` | `ToolResult` 原样返回；其它类型 `String.valueOf` 包成成功结果（`retryable` 丢失） |
| `oryxos tool list` 的数量断言会不会被本节打红 | 读 `OryxToolContractTest.java:65` | `hasSizeGreaterThanOrEqualTo(13)`——**已经是下限断言**，15 个工具仍然成立，**不需要改测试** |
| `MemoryModule.java` 该不该删 | `oryxos-tool` 在第 20 节交付完整工具体系后 `ToolModule.java` **仍在** | 同款保留，不删 |
| 测试期 Spring 上下文怎么起 | 读 `StorageTestApplication` / `CliTestContext` | `oryxos-memory` 需要自己的 `MemoryTestApplication`（`@SpringBootConfiguration` + `@EnableAutoConfiguration`） |
| 建表脚本的执行路径 | 读 `InitCommand.run()` | `database.runSchemaScript()` **无条件**执行，脚本内全是 `CREATE TABLE IF NOT EXISTS` → 老工作区跑一次 `oryxos init` 即补齐（仲裁 11） |

## 4. Mem0 报文口径

**来源**：课件 §三 第四步的 REST 示意 + 上游同节 `Mem0MemoryStore`（两者一致）。**本仓照抄**：

| 操作 | 方法 + 路径 | 请求体 |
| --- | --- | --- |
| `append` | `POST /v1/memories/` | `{"messages":[{"role":"user","content":...}], "user_id":..., "metadata":{"scope":...}}` |
| `load`（按区取） | `GET /v1/memories/?user_id={u}&scope={s}` | — |
| `recallByKeyword` | `POST /v1/memories/search/` | `{"query":..., "user_id":...}` |

响应解析：Jackson `JsonNode`，兼容 `results[].memory` 与根节点 `memory` 两种形状。

**如实记录的局限**：**本节不真连 Mem0**（spec Assumptions 已定）。上述报文格式**未经真 server 验证**，只由 `Mem0MemoryStoreTest` 的 mock 断言"我们发出去的是这个形状"。真连是人工项（课件「五、做完怎么验」第 2 条，可选）。

## 5. 设计决策与备选

> D 编号与 [plan.md](./plan.md)「需要说明的设计偏离」一一对应，此处只补**备选方案**。

| D | 选定 | 备选（否决理由） |
| --- | --- | --- |
| D1 | 门面落 `oryxos-memory` | 落 `oryxos-core`（上游那样）→ 违反 FR-025「core 一行不改」，且本仓 core 的接缝本来就只有"读"这一半 |
| D2 | 保留 5 方法 | 裁回 3 方法 → 改骨架期已交付的公共接口（软门禁 ②）；`capabilities()` 正是"接口墙预留升级空间"的载体 |
| D3 | 工具返回 `ToolResult` | 返回 `String`（课件/上游）→ 与其余 13 个内置工具不同形，且 `retryable` 丢失 |
| D4 | 非法 scope **抛异常** | 返回错误文案（上游）→ `tool_invocations` 会把这次失败记成 `success=true`，审计说谎；且与"失败一律上抛"的既有约定相悖 |
| D5 | `@ToolParam(required = false)` | 不声明（课件/上游）→ 模型可见的 JSON Schema 会把 `scope` 列进 `required`，"缺省归档"不成立 |
| D6 | 两区都空 → `load()` 返回 `""` | 无条件带 header（上游）→ prompt 里出现只有标题的残渣，违反 FR-014 / SC-007 |
| D7 | 三档统一带 header | 照课件的 Mem0 示意不带 → 契约测试无法对 `load()` 做统一断言（FR-024） |
| D8 | `oryxos.memory.backend` | 裸键 `memory.backend`（课件/上游）→ 与本仓「配置键一律带 `oryxos.` 前缀」的口径不符（**唯一有意分歧**，已报批） |
| D9 | 双构造器（地址版 + `RestClient` 版） | ① 只留地址版 → 测试无法绑 `MockRestServiceServer`；② 只留 `RestClient.Builder` 版 → 实现装超时会覆盖 mock 的请求工厂（§2）；③ 装配处造 `RestClient` Bean → 超时责任推给最易漏的装配方 |
| D10 | 三档检索都大小写不敏感 | 照上游 `InMemoryMemoryStore` 的大小写敏感 → 替身与三档行为不一致，契约测试就会红 |
| D11 | `InMemoryMemoryStore` 落 public 类 | 落包级私有内部类 → 与上游不同名同形；契约测试跨包用它（`store` 包内可访问，但 `MemoryServiceImplTest` 未必）——**已报批为 public** |
| D12 | 补 `SqliteMemoryStoreTest` | 照上游不做 → 直接违反课件的验收 harness 清单 |
| D13 | `MemorySource` javadoc 不动 | 顺手把那句失效括注改掉 → 改第 17 节交付的公共文件，属独立收口 |

## 6. 三条被明确"不做"的方案

1. **不给 `SqliteMemoryStore` 内置建表**（仲裁 11）——与 `sessions` / 审计表同口径，表由 `oryxos init` 补；避免 Spring 路径的原生 JDBC 与 HikariCP `pool=1` 争库文件。
2. **不引入 `ToolExecutionContext` / 按 Agent 隔离**（仲裁 4）——那是第 30 节的概念；上游同节实证了这一点（"Agent 专属（30 节）"逐字写在其 `MarkdownMemoryStore` 的 javadoc 里）。
3. **不实现 `HYBRID_BUILTIN`**（仲裁 5）——本仓不存在 `oryxos-core/retrieval` 包，也没有 `embedding.*` 配置键（已 grep 核实）。门面对它的处理是**明确报错**，MUST NOT 静默降级。

## 7. 上游同节对照（`aa781d28` = `specs/006-memory-pluggable/`）

**照上游的**：`MemoryEntry` / `MemoryEntryRepository` 落 `oryxos-storage`；`memory_entries` 的 DDL 逐字相同；`MemoryTools` 落 `builtin/` 子包且工具名 `save_memory` / `recall_memory` 相同；`MAX_ARCHIVE_CHARS = 4000` / `MAX_ARCHIVE_ROWS = 100` / 日期标记 `- [YYYY-MM-DD]`（只到日）三项数值相同；`append` 的"读—抽区—拼接—整文件重写"结构相同。

**有意不照上游的（4 处，均已在上表 D 中给出理由）**：D3（返回类型）、D4（非法 scope 的载体）、D6（空记忆的形状）、D8（配置键前缀）、D10（大小写口径）、D12（补做 SQLite 档测试）。
