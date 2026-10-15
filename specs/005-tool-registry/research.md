# Phase 0 Research: Tool 体系

**Feature**: `specs/005-tool-registry` | **Branch**: `class-20` | **Date**: 2026-10-14

本文件解决 plan 的 Technical Context 里所有 NEEDS CLARIFICATION，并记录**本节全部第三方 API 的实测证据**。

**H3 纪律**：本节涉及第三方 API 的每一处，均在动手前用 `javap` / `unzip -l` 在**本地仓库的实际 jar** 上核实过方法存在与签名形状，**无一条凭记忆或凭课件示意**。命令与输出摘要逐条附在对应 Decision 下。

---

## D1 装配解冻与临时沙箱

**Decision**：`oryxos-tool` 的 `sandbox` 包新增 `PermissiveSandbox`（`implements Sandbox`，`enforce` 空实现），在 `CliAssemblyConfiguration` 以 `@ConditionalOnMissingBean(Sandbox.class)` 声明为沙箱 Bean；连带把第 19 节挂账的 `RestClient` / `WebhookNotifyAdapter` / `JpaNotifyChannelRegistry` / `NotifyTools` 四个 Bean 声明一并落地。

**Rationale**：第 19 节"零装配"的**唯一根因**是"没有可注入的沙箱 Bean"（`NotifyTools` 构造依赖 `Sandbox`，而唯一实现 `WhitelistSandbox` 归第 24 节）。课件「本节交付物」明写 `Sandbox` 前向接口五件**含临时装配实现**、且明写「`NotifyTools`（19 节）在此完成注册」——根因在本节解开。不装配则"Agent 真能调工具"无从演示，违反章程原则 X。用户 2026-10-14 已报批（spec 仲裁 1），**有意推翻**第 19 节裁决 4。

**Alternatives considered**：

| 方案 | 否掉的理由 |
| --- | --- |
| 本节继续零装配，等第 24 节 | 交付物残缺：注册表装进容器但没有沙箱、没有工具 Bean，`tool list` 永远空表，原则 X 的可演示 Demo 落空 |
| 把 `WhitelistSandbox` 提前到本节 | 跨节抢工，第 24 节只剩空壳；且白名单的**配置属性类**是第 24 节交付物，接口此刻定不下来，硬写等于自造契约 |
| 造一个"全放行"匿名类塞进装配 | 匿名类无法被测试断言、无法写 javadoc 说明临时性，且第 24 节无法用 `@ConditionalOnMissingBean` 精确让位（`@ConditionalOnMissingBean` 认类型，匿名类类型无意义） |

**残余风险与缓解**：第 20~23 节之间白名单校验不生效。四条缓解见 [contracts/assembly.md](contracts/assembly.md) §4。**本节 MUST NOT** 把该窗口期表述为"沙箱已就位"。

---

## D2 `AnnotatedToolAdapter` 的分工：Spring AI 只做 schema，调用自己做

**Decision**：`AnnotatedToolAdapter` 对每个 `@Tool` 方法生成一个 `OryxTool`：

| 职责 | 实现 |
| --- | --- |
| 工具名 | `@Tool.name()` 非空则用它，否则 `ToolUtils.getToolName(Method)` |
| 用途说明 | `@Tool.description()` |
| 入参 schema | `JsonSchemaGenerator.generateForMethodInput(Method)` → `String` |
| 执行 | **自己做**：`JsonParser.fromJson(inputJson, Map.class)` 取参数表 → 逐参数 `JsonParser.getObjectMapper().convertValue(raw, param.getType())` → `method.invoke(bean, args)` → 结果包成 `ToolResult` |

**Rationale**：章程原则 III 允许的 Spring AI 用途是"`@Tool` 注解的 JSON Schema 生成"，**不含自动 tool 执行**。不用 `MethodToolCallback.call()` 有两个硬理由：

1. **语义会丢**：`MethodToolCallback` 走 `ToolCallResultConverter` 把返回值转成 `String`，`ToolResult` 的四要素（尤其 `retryable`）会被抹平成一段文本，而 `ToolResult` 正是骨架期定的执行结果契约（FR-003 / 章程原则 V）。
2. **第 19 节已交付的 `NotifyTools.notify` 返回 `ToolResult`**（`oryxos-tool/.../notify/NotifyTools.java:41`）。要迁就 `MethodToolCallback` 就得改它的返回类型——属软门禁 ④（修改前序节公共接口），且第 19 节 javadoc 已写明它"顺序钉死：先过白名单，再发""失败一律上抛"。

自己 invoke 还顺带解决第三件事：**异常上抛**。`MethodToolCallback` 会把方法抛的异常包成 `ToolExecutionException` 再转成错误文本返回；本节让异常**直接冒泡**到 `ToolExecutor` 既有的 catch（`oryxos-core/.../engine/ToolExecutor.java`），失败才落得进 `tool_invocations`（FR-021 / 章程原则 VI）。

**Alternatives considered**：

| 方案 | 否掉的理由 |
| --- | --- |
| 用 `MethodToolCallback`（builder + `call(String)`） | 上述三条：`ToolResult` 语义丢失、要改第 19 节已交付类、异常被吞导致失败审计缺失 |
| 自己写 JSON Schema 反射生成器 | 违反章程原则 III"Spring AI 只用一半"里**该用的那一半**——schema 生成正是它的职责，自造等于重复造轮子且要自己处理 `@ToolParam` / 嵌套类型 / 必填推断 |
| 用 Jackson 的 `jackson-module-jsonSchema` 直接生成 | 它不认 `@ToolParam(description)`，生成的 schema 缺参数说明，FR-002 当场不满足 |

**实测证据**（`javap -cp ~/.m2/repository/org/springframework/ai/spring-ai-model/1.1.8/spring-ai-model-1.1.8.jar`）：

```
public final class org.springframework.ai.util.json.schema.JsonSchemaGenerator {
  public static java.lang.String generateForMethodInput(java.lang.reflect.Method, ...SchemaOption...);
  public static java.lang.String generateForType(java.lang.reflect.Type, ...SchemaOption...);
}

public final class org.springframework.ai.util.json.JsonParser {
  public static com.fasterxml.jackson.databind.ObjectMapper getObjectMapper();
  public static <T> T fromJson(java.lang.String, java.lang.Class<T>);
  public static java.lang.String toJson(java.lang.Object);
}

public final class org.springframework.ai.tool.support.ToolUtils {     // 注意：在 .support 包，不是 .util
  public static java.lang.String getToolName(java.lang.reflect.Method);
  public static java.lang.String getToolDescription(java.lang.reflect.Method);
}

public interface org.springframework.ai.tool.annotation.Tool {
  public abstract java.lang.String name();          // AnnotationDefault: ""
  public abstract java.lang.String description();   // AnnotationDefault: ""
  public abstract boolean returnDirect();           // AnnotationDefault: false
  public abstract java.lang.Class<? extends ...ToolCallResultConverter> resultConverter();
}

public interface org.springframework.ai.tool.annotation.ToolParam {
  public abstract boolean required();
  public abstract java.lang.String description();
  // 无 name() 元素 ← 见 D2a
}
```

### D2a 【关键发现】父 pom 未开 `-parameters`，`@Tool` 的 schema 参数名会退化成 `arg0`

**Decision**：本节 MUST 在父 `pom.xml` 的 `maven-compiler-plugin` 打开 `<parameters>true</parameters>`。

**Rationale**：`@ToolParam` **没有 `name()` 元素**（见上，只有 `required()` / `description()`），所以 Spring AI 只能从 `Parameter.getName()` 取参数名。而 `Parameter.getName()` 在未开 `-parameters` 时返回 `arg0` / `arg1`。

已核实现状：`pom.xml` 第 217~220 行的 `maven-compiler-plugin` **只有** `<release>${maven.compiler.release}</release>`，**没有** `<parameters>`。

后果：`NotifyTools.notify(String content, String channel)` 生成的 schema 里参数名会是 `arg0` / `arg1`，模型看不到 `content` / `channel` 这两个名字 → **模型根本没法正确构造工具调用**。这是"编译通过、单测可能也过、但运行时功能坏掉"的静默失效，必须在 plan 里钉死。

**验证方式（机器判定）**：一条单测断言 `notify` 的 `getInputSchema()` 里同时出现 `content` 与 `channel`，且**不出现** `arg0`。见 [contracts/builtin-tools.md](contracts/builtin-tools.md) §3。

**Alternatives considered**：

| 方案 | 否掉的理由 |
| --- | --- |
| 在 `@ToolParam` 上硬写名字 | `@ToolParam` 没有 `name()` 元素，写不出来 |
| 给每个参数加自定义注解再反射读 | 自造一套注解体系，与"Spring AI 只用一半"的边界冲突，且要重写 schema 生成 |
| 不改 pom，接受 `arg0` | 功能直接坏掉，不是取舍 |

---

## D3 MCP 依赖坐标与真实 API

**Decision**：`oryxos-tool/pom.xml` 显式声明**两个**坐标，均写死版本号、均不由 BOM 托管：

```xml
<dependency>
  <groupId>io.modelcontextprotocol.sdk</groupId>
  <artifactId>mcp-core</artifactId>
  <version>0.18.3</version>
</dependency>
<dependency>
  <groupId>io.modelcontextprotocol.sdk</groupId>
  <artifactId>mcp-json-jackson2</artifactId>
  <version>0.18.3</version>
</dependency>
```

**Rationale（为什么必须是两个，不是"顺手多写一个"）**：

已实测 `mcp-json-jackson2` 的 jar 内含 ServiceLoader 注册文件：

```
META-INF/services/io.modelcontextprotocol.json.McpJsonMapperSupplier
META-INF/services/io.modelcontextprotocol.json.schema.JsonSchemaValidatorSupplier
```

而 `mcp-core` 里**没有**这两个文件。`McpJsonDefaults.getMapper()` 是 `static synchronized` 且通过 ServiceLoader 找实现——**找不到抛 `ServiceConfigurationError`（`Error`，不是降级、不是 null）**。同时 `StdioClientTransport` 的**唯一公开构造器**要求传入 `McpJsonMapper`：

```
public io.modelcontextprotocol.client.transport.StdioClientTransport(ServerParameters, McpJsonMapper);
```

即：只声明 `mcp-core` 会让**启动直接失败**，而不是"少了点功能"。这条已在 spec 仲裁 3 报批。

**发行物代价（如实记录）**：fat JAR 新增**五个**工件——`mcp-core`、`mcp-json-jackson2`、`json-schema-validator`、`com.ethlo.time:itu`（groupId 是 `com.ethlo.time`，**不是** `com.networknt`）、`jackson-dataformat-yaml`。五件均已核实存在于本地仓库，离线构建不受影响。

**响应式依赖边**：`mcp-core` 内部依赖 `reactor-core`，但 `reactor-core` **自第 16 节就在 classpath 上**（经 Spring AI 传入），本节**不新增响应式依赖边**。FR-026 的判定口径是**本项目源码**——H4 不变量 ⑤ 的扫描只覆盖本仓源码，第三方库内部用响应式不构成违反。

**真实 API 落地**（与课件示意 API 的差异已记入 spec 的课件偏差表）：

```
public interface io.modelcontextprotocol.client.McpClient {
  public static McpClient$SyncSpec sync(McpClientTransport);      // 课件写 McpClient.connect(cfg)，不存在
}

public class io.modelcontextprotocol.client.McpClient$SyncSpec {
  public SyncSpec requestTimeout(java.time.Duration);
  public SyncSpec initializationTimeout(java.time.Duration);
  public McpSyncClient build();
}

public class io.modelcontextprotocol.client.McpSyncClient implements AutoCloseable {
  public McpSchema$ListToolsResult listTools();
  public McpSchema$CallToolResult callTool(McpSchema$CallToolRequest);
  public void close();
}

public final class io.modelcontextprotocol.spec.McpSchema$Tool extends Record {
  public java.lang.String name();
  public java.lang.String title();
  public java.lang.String description();
  public McpSchema$JsonSchema inputSchema();        // ← 是 record，不是 String
}

public final class io.modelcontextprotocol.spec.McpSchema$JsonSchema extends Record {
  public java.lang.String type();
  public java.util.Map<java.lang.String, java.lang.Object> properties();
  public java.util.List<java.lang.String> required();
  public java.lang.Boolean additionalProperties();
  public java.util.Map<java.lang.String, java.lang.Object> defs();
  public java.util.Map<java.lang.String, java.lang.Object> definitions();
}

public final class io.modelcontextprotocol.spec.McpSchema$CallToolRequest extends Record {
  public CallToolRequest(java.lang.String, java.util.Map<java.lang.String, java.lang.Object>);   // (name, arguments)
}

public final class io.modelcontextprotocol.spec.McpSchema$CallToolResult extends Record {
  public java.util.List<McpSchema$Content> content();    // 具体子类只有 TextContent / ImageContent
  public java.lang.Boolean isError();                    // 注意是 Boolean，可能为 null
  public java.lang.Object structuredContent();
}

public class io.modelcontextprotocol.json.McpJsonDefaults {
  public static synchronized McpJsonMapper getMapper();
}

public interface io.modelcontextprotocol.json.McpJsonMapper {
  public java.lang.String writeValueAsString(java.lang.Object) throws java.io.IOException;
  public <T> T readValue(java.lang.String, java.lang.Class<T>) throws java.io.IOException;
}
```

**由实测形状推出的两条实现约束**：

1. **`McpToolAdapter.getInputSchema()` 必须把 `JsonSchema` record 序列化回字符串**（`OryxTool` 的 String 口径，D2/仲裁 2）。走 `McpJsonDefaults.getMapper().writeValueAsString(tool.inputSchema())`——**保持 SDK 的 JSON 契约在 SDK 内部**，本仓源码不需要 import Jackson。若直接 `toString()`，拿到的是 record 的调试串而不是合法 JSON，模型侧当场解析失败。
2. **`isError()` 是 `Boolean`，可能为 `null`**。判成功必须写 `Boolean.TRUE.equals(result.isError())`，不能写 `result.isError()`（拆箱 NPE）也不能写 `!result.isError()`（null 时抛 NPE）。

**Alternatives considered**：

| 方案 | 否掉的理由 |
| --- | --- |
| 只加 `mcp-core` | 实测启动抛 `ServiceConfigurationError`，直接失败 |
| 自写 MCP 协议客户端 | 违反章程附加约束"开放标准优先，MUST NOT 另立协议"的精神；要自持 JSON-RPC 2.0 + stdio/SSE 双传输 + 初始化握手 |
| 加 `mcp` 聚合坐标（若存在） | 会连带引入 Spring 自动配置，可能触发 tool 自动执行的 Bean 装配——与章程原则 III 的禁用要求正面对撞 |
| 用 SSE 传输而不做 stdio | `mcp_servers.yaml` 模板（第 18 节已交付、软门禁 ② 不得改）里 `stdio` 是**首个示例**，不支持 stdio 等于模板第一条就是废的 |

---

## D4 注册表实现 core 的既有接缝 + `@Bean` 声明顺序

**Decision**：`ToolRegistry implements ToolCatalog`，实现 `find(String)` / `availableFor(Profile)`；在 `CliAssemblyConfiguration` 里 `toolRegistry()` 的 `@Bean` 方法**声明在 `toolCatalog()` 之前**。

**Rationale**：`oryxos-core` 零改动是本节最硬的卖点（章程原则"契约在 core、实现在各模块"）。`ToolCatalog` 的 javadoc 已明写"实现方：`oryxos-tool` 的 `ToolRegistry`（第 20 节）"——core 早就把接缝留好了。装配处既有的 `EmptyToolCatalog`（`@ConditionalOnMissingBean(ToolCatalog.class)`）会自动让位，**不回头删代码**。

**声明顺序为什么是硬约束**：`@ConditionalOnMissingBean` 在同一个 `@Configuration` 类里**按 `@Bean` 方法的声明顺序求值**。若 `toolCatalog()`（空实现）在前，条件求值时容器里还没有任何 `ToolCatalog`，于是空实现被注册；随后 `toolRegistry()` 无条件注册 → 容器里出现**两个** `ToolCatalog` → `PromptBuilder` / `ToolExecutor` 注入时抛 `NoUniqueBeanDefinitionException`。

这个错误**只在重命令（起 Spring 上下文）时才炸**，`--help` / `--version` 扫不出来——与第 18 节实测的 `BeanDefinitionOverrideException` 同一类坑（`CLAUDE.md`「AI agent 最易跑偏的点」表末行已记该教训）。

**验证方式（机器判定，不留给人）**：一条 Spring 上下文测试，断言容器里 `ToolCatalog` 类型的 Bean **恰好一个**，且其运行时类型是 `ToolRegistry`。断言"恰好一个"就能同时抓住"空实现没让位"和"两个都在"两种坏法。

**Alternatives considered**：

| 方案 | 否掉的理由 |
| --- | --- |
| 给空实现加 `@Primary` | 明令禁止（第 18/19 节既定纪律）：`@Primary` 只是让注入不报错，容器里仍有两个同类型 Bean，"让位"变成"并存"，后续按类型取全部 Bean 的代码会拿到脏数据 |
| 删掉 `EmptyToolCatalog` | 破坏第 18 节交付物；且第 26 节的 Web 侧与轻命令路径仍可能需要它 |
| 把空实现挪到另一个 `@Configuration` 类 | 条件求值顺序变成跨类的隐式依赖，更难保证；单文件内"声明顺序"是可 grep 的显式约束 |
| 不用 `@ConditionalOnMissingBean`，改用 `@Profile` | 与既有装配纪律不一致，且 profile 是粗粒度开关，不能表达"有真实现就让位" |

---

## D5 `oryxos tool list` 改重命令

**Decision**：`ToolListCommand` 从**轻命令**改为**重命令**（与 `chat` / `serve` / `gateway` 同列，起 Spring 上下文后经 `ToolCatalog` 接缝取数），输出按来源分组。

**Rationale**：第 18 节交付该命令时留下**已报批的实施期偏离**（`specs/003-cli-session/tasks.md` T033 注），明确写"**第 20 节需要改本命令，不是「只换实现」**"，并把原因记死：轻命令够不着 `CliAssemblyConfiguration` 里的 Bean。`specs/003-cli-session/contracts/cli-surface.md` §2.10 冻结的输出口径是"name、description、**来源**（`builtin` / `annotation` / `mcp:<server>`）"。

**判据来自契约自己**：§1 对"轻重"的判定标准是"这条命令**要不要调模型、跑引擎**"。列出 MCP 工具**必须连接 server**（MCP 工具的清单只有连上才知道），这已经是"跑引擎"。第 18 节把它归轻，是因为当时**没有注册表可查**——§1 第 11 行的"数据来源"列原文就是"`ToolCatalog` 接缝（本节空实现）"，数据源当时是空的。

**代价如实记录**：
1. `tool list` 从"秒回"变成"起上下文"；有 MCP server 声明时最多等待 `10 秒 × server 数`（FR-009 的有界等待）。
2. `contracts/cli-surface.md` §1 第 11 行的"轻重"列由**轻**改**重**。**命令名、参数、退出码三项均不变**——§1 要求走修订程序的正是这三项，故本改动在节内权限范围内。

**本节 MUST NOT** 为保住"轻"而另造一个静态工具清单——那是第二处真相，加一个内置工具就要手改两处，直接违反 FR-022（"加工具成本恒定在写一个类"）。

**Alternatives considered**：

| 方案 | 否掉的理由 |
| --- | --- |
| 保持轻命令，输出静态清单 | 第二处真相，FR-022 当场不满足；且"来源"里的 `mcp:<server>` 静态写不出来 |
| 保持轻命令，只列 `builtin` 与 `annotation` | §2.10 要求"全量"，少一类就是残缺；且 `builtin` 的清单同样只有注册表知道 |
| 拆成 `tool list`（轻，静态）+ `tool list --all`（重） | 一个命令两种语义、两处输出格式，用户要记两套；YAGNI |

---

## D6 新增的对外名字与报备

**Decision**：除 spec「本节交付物」清单点名的类之外，本节新增/取用以下名字：

| 名字 | 性质 | 理由 |
| --- | --- | --- |
| `PermissiveSandbox` | 课件「本节交付物」原文 | D1 |
| `ConsoleUserInteraction` / `UnsupportedUserInteraction` | 课件 §6.3 正文 | FR-017 的两档交互实现 |
| `DuckDuckGoSearchProvider` | 课件 §6.4 正文 | FR-018 的一档免 key 搜索实现 |
| `McpServerConfig` | 课件 §三代码（`loadConfigs()` 的返回元素） | 读 `mcp_servers.yaml` 的载体 |
| `ToolRegistry.RegisteredTool`（嵌套 record） | **本节新增** | `cli-surface.md` §2.10 冻结的"按来源分组"输出需要"工具 + 来源"配对；`OryxTool` 接口不带来源，其 javadoc 亦未承诺 |

**Rationale**：前四行是**抄课件正文/交付物**而非自创（软门禁 ① 要求报备，已记入 spec 仲裁 6 与 plan 的 D6 表）。第五行是**兑现前序节已冻结的契约**所必需——**嵌套在注册表内部**，把新增的 public 类型压到 1 个，且语义上明确是注册表的伴随物（"注册表里的一条记录"），不是独立概念。

**Alternatives considered**：

| 方案 | 否掉的理由 |
| --- | --- |
| 给 `OryxTool` 加 `getSource()` | 改骨架期已交付的公共接口（软门禁 ④）；且来源不是"工具"的属性而是"注册关系"的属性——同一个工具理论上可从不同来源进来 |
| 用 `Map<OryxTool, String>` 表达 | 丢掉类型信息，调用方要自己记 key 是什么 |
| 顶层类 `RegisteredTool` | 凭空多一个顶层 public 概念，软门禁 ① 的成本更高；嵌套已足够 |
| `source` 用 enum | `mcp:<server>` 带动态后缀，enum 表达不了（要么退化成 `MCP` + 另存 server 名，要么给 enum 加字段，都比 String 绕） |

---

## D7 MCP 配置加载

**Decision**：`McpServerConfigLoader` 用 **SnakeYAML**（经 `oryxos-core` 传递，版本 2.4）读 `<workspace>/.oryxos/mcp_servers.yaml`，映射到 `List<McpServerConfig>`。

**Rationale**：
- `oryxos-core` 已在用 SnakeYAML（`ProfileLoader` 读 `AGENT.md` frontmatter），本节沿用同一库，**不新增依赖**。
- 文件路径：`Workspace` 目前**没有** `mcpServersFile()` 方法（已核实 `oryxos-cli/.../workspace/Workspace.java` 只有 `root()` / `agentsDir()` / `dbFile()` / `exists()` / `requireExists()`）。本节在 `McpServerConfigLoader` 内部定位，**不给 `Workspace` 加方法**（那是第 18 节交付物，加方法属软门禁 ④）。
  - **⚠️ 定位链必须写对（复核修正，原稿写错）**：`Workspace.root()` 返回的是 **`.oryxos` 目录本身**（`Workspace.java:36`：`this.root = baseDir.toAbsolutePath().normalize().resolve(WORKSPACE_DIR)`；旁证：`agentsDir()` = `root.resolve("agents")` = `.oryxos/agents`、`dbFile()` = `root.resolve("oryxos.db")` = `.oryxos/oryxos.db`）。故正确定位是 **`workspace.root().resolve("mcp_servers.yaml")`**。**多写一层 `.resolve(".oryxos")` 会得到 `.oryxos/.oryxos/mcp_servers.yaml`——文件永远不存在，而「文件不存在 → 空清单」的既定规则会让它静默通过：MCP 永远连不上任何一个 server，且零报错**。这是"配置写了却不生效"型静默失效，必须由 `McpServerConfigLoaderTest` 的一条「真读到一个 server」的正向用例钉死（只测异常分支测不出这个错）。
- **键名冻结**（软门禁 ②）：第 18 节已交付模板，键为 `name` / `transport` / `command`（数组）/ `env`（映射）+ `sse` 的 `url`。本节 MUST NOT 改模板结构与键名。
- **模板现状**：示例全在注释里、末尾是 `servers: []`，故**默认配置下不连接任何 MCP server**——这是**预期状态**，不是没做完（spec 仲裁 7）。
- **缺文件 / 空文件 / `servers` 缺失**一律视为空清单，不报错（Edge Case）。
- **凭证**：`env` 的值支持 `${ENV_VAR}` 占位，加载时从环境变量解析（FR-027）；解析不到时 WARN 并**跳过该 server**，不抛异常（避免一个没配 token 的 server 拖垮启动）。

**Alternatives considered**：

| 方案 | 否掉的理由 |
| --- | --- |
| 引入 Jackson YAML | 已有 SnakeYAML，重复引入；且 Jackson YAML 会跟着 `mcp-json-jackson2` 进来但版本由 SDK 管，显式再声明一份会打架 |
| 用 Spring Boot 的 `@ConfigurationProperties` 绑定 | 该文件在**工作区**（运行期数据目录）而不是 classpath，`@ConfigurationProperties` 面向 classpath 配置；且本节纪律是"非 boot 模块不带装配注解" |
| 用 properties 格式 | 第 18 节模板已是 YAML，改格式属软门禁 ② |

---

## D8 "校验先于 IO" 的落地形态

**Decision**：每个涉外工具类的每个 `@Tool` 方法，**第一行**是 `sandbox.enforce(new SandboxAction(类型, 目标))`，然后才做 IO。

**Rationale**：FR-015 要求"执行的第一步"，FR-021 要求"校验失败复用既有失败审计路径"。`SandboxViolationException extends RuntimeException`（已核实），`ToolExecutor` 已有 `catch (RuntimeException)` 并写 `tool_invocations`，故**本节一行审计代码都不写**——只要异常从 `execute` 冒出去，审计自动落库。

**顺序由测试钉死**：每个内置工具的单测里，"越界会被拦"那一条 MUST 同时断言**目标文件/进程/网络一次都没被碰过**（例如 `read_file` 越界时断言目标路径不存在于文件系统、`shell` 越界时断言进程未被创建）。只断言"抛了异常"是不够的——先 IO 再校验也会抛异常，测试会假绿。

**Alternatives considered**：

| 方案 | 否掉的理由 |
| --- | --- |
| 在 `AnnotatedToolAdapter` 里统一 `enforce` | 目标类型（路径/域名/可执行文件）取决于**具体工具**，适配器无从判断；且 MCP 工具的目标是远端 server，语义不同 |
| 在 `ToolExecutor` 里统一 `enforce` | 同上，且 `oryxos-core` 全程不引用 `Sandbox`（依赖方向约定，见 CLAUDE.md 模块表），会成环 |
| 用 AOP 切面 | 引入文档外的抽象层（H1 纪律禁止），且 `SandboxAction` 的构造参数仍要按工具定 |

---

## D9 测试策略

**Decision**：按课件「验收 harness」落地，**课件写出代码的关键回归测试原样保真**，方法名译成英文、`@DisplayName` 保留课件原文。

| 测试类 | 守点 | 对应 FR / SC |
| --- | --- | --- |
| `OryxToolContractTest` | `@ParameterizedTest @MethodSource` 遍历注册表里**每个**工具，断言 name / description / inputSchema 三件套非空且非空白——新工具自动纳入检查 | FR-002 / SC-001 |
| `ToolRegistryTest` | 三种来源都以统一抽象身份注册；按 Agent 声明清单过滤后子集**精确匹配、不多不少**；重名保留先注册者 + WARN；声明了但没注册的名字 WARN + 跳过 | FR-005 / FR-022 / FR-023 |
| `FileToolsTest` / `ShellToolsTest` / `HttpToolsTest` | 每个工具"正常能跑通 + 越界会被拦"两条，且**校验必须先于 IO**（D8） | FR-015 / SC-003 |
| `McpToolAdapterTest` | 工具被包装注册、执行时参数原样转发、结果包成统一结果；`inputSchema` 是合法 JSON（D3 约束 1）；`isError` 为 `null` 时不 NPE（D3 约束 2） | FR-007 / SC-001 |
| `McpClientServiceTest` | **某个 server 失联只 WARN、其余工具照常注册、启动不炸**（用替身客户端） | FR-008 / SC-004 |
| `AnnotatedToolAdapterTest` | schema 参数名是真实参数名而非 `arg0`（D2a）；`ToolResult` 返回值语义保真；异常上抛不吞 | FR-001 / SC-001 |
| `PermissiveSandboxTest` | 构造时 WARN 恰好一条（logback `ListAppender`）；`enforce` 不抛 | SC-010 |
| 扩展五工具单测 | `edit_file` 的旧文本唯一匹配（找不到 / 出现多次都报错且**文件一字不动**）、`grep` 跳过二进制文件、`glob` 上限 200、`ask_user` 无交互时明确报错不静默卡住、`web_search` 经接缝 | FR-011~FR-014 / FR-017 / FR-018 |
| 装配测试 | 容器里 `ToolCatalog` Bean **恰好一个**且是 `ToolRegistry`（D4）；第 24 节式让位可复现 | SC-009 |

**Rationale**：契约三件套那条参数化测试是**最值钱的**——它把"新工具漏实现 schema"从"运行时模型报错"提前到"单测红"，且**新工具自动纳入**，不需要每加一个工具就补一条测试（这正是 FR-022 的机器化验证）。

**单测不访问外网**（SC-008）：`HttpTools` 用已在 test scope 的 `mockwebserver3:5.5.0`；`web_search` 用替身 `SearchProvider`；MCP 用替身客户端。集成冒烟打 `@Tag("integration")`，CI 跳过。

**Alternatives considered**：

| 方案 | 否掉的理由 |
| --- | --- |
| 逐个工具手写契约断言 | 加工具必须记得补测试，正是 FR-022 要消灭的成本 |
| 用 Mockito 深度打桩 `McpSyncClient` | `McpSyncClient` 是 `class` 且构造器包私有，Mockito 可以 mock 但它**不是接口**；本节把它藏在 `McpClientService` 的**接缝**后面（一个本仓自有的小接口），替身更干净、也不把第三方类型泄进测试 |

---

## D10 `ToolRegistry` 的公开 API

**Decision**：

```java
public class ToolRegistry implements ToolCatalog {
  public void register(OryxTool tool, String source);
  public void registerAnnotated(Object bean, String source);
  public boolean contains(String name);
  public List<RegisteredTool> registered();          // 全量 + 来源，供 tool list / 第 26 节 REST 用
  @Override public Optional<OryxTool> find(String name);
  @Override public List<OryxTool> availableFor(Profile profile);

  public record RegisteredTool(OryxTool tool, String source) {}
}
```

`source` 的三档取值：`builtin` / `annotation` / `mcp:<server名>`。

**Rationale**：前三个方法名取自**课件**（spec 仲裁 5 要求"按课件给的名字取"）——课件用 `register` / `registerAnnotated` / `contains`（`contains` 出现在课件那条"某个 MCP server 失联不能拖垮启动和其他工具"的回归测试里，用它断言坏 server 的工具**没**注册进来）。`registered()` 与 `RegisteredTool` 是兑现 `cli-surface.md` §2.10 的"按来源分组"所必需（D6）。

`find` / `availableFor` 是 `ToolCatalog` 接口要求的 `@Override`。

**Alternatives considered**：

| 方案 | 否掉的理由 |
| --- | --- |
| `register(OryxTool)` 不带 source，另开 `registerBuiltin` / `registerMcp` | 三档来源各一个方法，加第四档（如 Skill）就要加方法；带 String 参数更稳 |
| 把 source 编进工具名（`mcp:github:search`） | 名字是给模型看的，掺来源会污染 Function Calling 的工具名 |
| `registered()` 返回 `Map<String, String>`（名字→来源） | 调用方要再 `find(name)` 一次拿工具，两次查找且可能不一致 |

---

## 未决项 / 留给后续节

| 项 | 归属 |
| --- | --- |
| `WhitelistSandbox` 白名单校验本体 + 白名单配置属性类 | 第 24 节 |
| `save_memory` / `recall_memory` 两个内置工具 | 第 22 节（`oryxos-memory`） |
| `/api/v1/tools` REST 端点（消费 `registered()`） | 第 26 节 |
| Skill 绑定（方式一完整形态） | 第 29 节 |
| MCP 进程生命周期治理（重启 / 健康检查 / 重连退避）、MCP 认证 | 扩展阶段 |
| 入站 `ChannelAdapter` 抽象 | 入站抽象落地的那一节 |
