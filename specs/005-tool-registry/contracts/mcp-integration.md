# 契约：MCP 接入

**Feature**: `specs/005-tool-registry` | **Branch**: `class-20` | **Date**: 2026-10-14

本文件冻结三件事：**配置文件的读取口径**（§1，第 18 节模板，键名不得改）、**依赖坐标与发行物代价**（§2）、**失败模式**（§3）。

---

## §1 配置文件：`.oryxos/mcp_servers.yaml`

### §1.1 结构（第 18 节已交付，本节**一字不改**）

`oryxos-cli/.../command/InitCommand.java` 交付的模板：

```yaml
# servers:
#   - name: github-mcp
#     transport: stdio
#     command: [npx, "-y", "@modelcontextprotocol/server-github"]
#     env: { GITHUB_TOKEN: "${GITHUB_TOKEN}" }
#   - name: news-hub
#     transport: sse
#     url: "http://127.0.0.1:9000/sse"
servers: []
```

**软门禁 ②**：模板结构与键名（`name` / `transport` / `command` / `env` + sse 的 `url`）本节 MUST NOT 修改。

**默认状态**：示例全在注释里、末尾是 `servers: []`，故本节交付后**默认不连接任何 MCP server**——这是**预期状态**，不是没做完（spec 仲裁 7）。

### §1.2 键名 → `McpServerConfig` 映射

| YAML 键 | 类型 | 必填条件 | 说明 |
| --- | --- | --- | --- |
| `name` | String | 恒必填 | 用于来源标签 `mcp:<name>`；MUST 在文件内唯一 |
| `transport` | String | 恒必填 | `stdio` 或 `sse` |
| `command` | List\<String\> | `stdio` 时必填 | 可执行文件 + 参数，**逐元素作为 argv**，不经 Shell |
| `env` | Map\<String,String\> | 可选 | 值支持 `${ENV_VAR}` 占位 |
| `url` | String | `sse` 时必填 | SSE 端点 |

### §1.3 读取规则

| 情况 | 行为 |
| --- | --- |
| 文件不存在 | 视为空清单，**不报错**（未初始化 MCP 是正常状态） |
| 文件为空 / 只有注释 | 同上 |
| 没有 `servers` 键 | 同上 |
| `servers: []` | 空清单 |
| `transport` 值既不是 `stdio` 也不是 `sse` | WARN + **跳过该 server** |
| `stdio` 但 `command` 缺失/为空 | WARN + 跳过 |
| `sse` 但 `url` 缺失 | WARN + 跳过 |
| `name` 重复 | WARN + 保留先出现的 |
| `env` 值里的 `${VAR}` 环境变量不存在 | WARN + **跳过该 server**（凭证缺失时不猜测、不落明文） |
| YAML 语法错误 | WARN + 视为空清单（**不拖垮启动**） |

**统一原则**：**任何一条 server 的问题都只影响它自己**。MCP 是外部依赖，它的配置错误不该让 OryxOS 起不来。

**凭证纪律（FR-027）**：`${ENV_VAR}` 在加载时从环境变量解析；**解析出的值 MUST NOT 出现在任何日志、异常消息或工具返回里**。日志里只能记"server X 的 GITHUB_TOKEN 未设置"，不能记它的值。

---

## §2 依赖坐标与发行物代价

### §2.1 坐标（**两个**，均写死版本号，不由 BOM 托管）

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

### §2.2 为什么必须是两个

已用 `unzip -l` 实测：`mcp-json-jackson2` 的 jar 内含

```
META-INF/services/io.modelcontextprotocol.json.McpJsonMapperSupplier
META-INF/services/io.modelcontextprotocol.json.schema.JsonSchemaValidatorSupplier
```

而 `mcp-core` 里**没有**这两个文件。`McpJsonDefaults.getMapper()` 是 `static synchronized` 且通过 ServiceLoader 查找——**找不到抛 `ServiceConfigurationError`（`Error`，不是降级）**。同时 `StdioClientTransport` 的**唯一公开构造器**要求传入 `McpJsonMapper`。

**结论**：只声明 `mcp-core` 会让**启动直接失败**。这条已在 spec 仲裁 3 报批。

### §2.3 发行物代价（如实记录）

fat JAR 新增**五个**工件：

| 工件 | 备注 |
| --- | --- |
| `mcp-core` | 协议与客户端 |
| `mcp-json-jackson2` | mapper + ServiceLoader 注册 |
| `json-schema-validator` | 入参校验 |
| `com.ethlo.time:itu` | **groupId 是 `com.ethlo.time`，不是 `com.networknt`**（踩过） |
| `jackson-dataformat-yaml` | YAML 支持 |

五件均已核实存在于本地仓库，**离线构建不受影响**。

### §2.4 响应式依赖边

`mcp-core` 内部依赖 `reactor-core`，但 `reactor-core` **自第 16 节就在 classpath 上**（经 Spring AI 传入）→ **本节不新增响应式依赖边**。

FR-026 的判定口径是**本项目源码**：本仓 MUST NOT 引入响应式编程、MUST NOT 自建线程池、MUST NOT 引入异步工具调用。第三方库内部用响应式不构成违反——H4 不变量 ⑤ 的扫描只覆盖本仓源码。

---

## §3 连接与失败模式

### §3.1 真实 API（课件示意 API 的偏差已记入 spec 课件偏差表）

```java
McpSyncClient client = McpClient.sync(transport)          // 课件写 McpClient.connect(cfg)，不存在
    .requestTimeout(Duration.ofSeconds(10))
    .initializationTimeout(Duration.ofSeconds(10))
    .build();                                             // → McpSyncClient
client.initialize();
McpSchema.ListToolsResult result = client.listTools();
McpSchema.CallToolResult r = client.callTool(new McpSchema.CallToolRequest(name, arguments));
```

**传输构造**：

| transport | 构造 |
| --- | --- |
| `stdio` | `new StdioClientTransport(ServerParameters.builder(cmd).args(args).env(env).build(), McpJsonDefaults.getMapper())` |
| `sse` | `HttpClientSseClientTransport.builder(url).build()` |

### §3.2 有界等待（FR-009）

**10 秒 / server**（连接 + 初始化 + 列举合计上限）。这是**本规约新增的字面量**（技术方案只写"有界"，未给数），可调，已记入 spec 的 Assumptions。

**为什么必须有界**：一个配错地址的 server 会让 `McpClientService.connectAll()` 无限等待，进而让**每一次 `chat` / `serve` 启动都挂死**。这是"外部依赖拖垮自身"的典型形态。

### §3.3 失联隔离（FR-008 / SC-004，**课件 harness 的守点**）

```java
@Test
@DisplayName("某个 MCP server 失联，不能拖垮启动和其他工具")
void whenOneMcpServerIsUnreachable_otherToolsStillRegistered() {
  // server A 正常、server B 失联
  service.connectAll();
  assertThat(registry.contains("a_tool")).isTrue();
  assertThat(registry.contains("b_tool")).isFalse();   // ← 用 contains 断言"没注册进来"
  // 且不抛异常
}
```

**规则**：

| 阶段 | 失败 | 行为 |
| --- | --- | --- |
| 连接 | 连不上 / 超时 | WARN（含 server 名与原因，**不含凭证**）+ 跳过该 server |
| 初始化 | 握手失败 | 同上 |
| 列举 | `listTools()` 失败 | 同上 |
| 列举 | 成功但工具列表为空 | 不算失败，不 WARN（该 server 就是没工具） |
| 调用 | `callTool` 失败 | 该次调用返回 `ToolResult.retryableFailure`（server 是**运行中**挂的，值得重试） |
| 调用 | `CallToolResult.isError()` 为 `true` | `ToolResult.failure`（业务错误，重试无意义） |

**MUST NOT**：任何一个阶段抛异常出 `connectAll()`。WARN 之后继续下一个 server。

### §3.4 `McpToolAdapter` 的两条实现约束（由实测形状推出）

#### 约束 1：`inputSchema` 必须序列化回字符串

`McpSchema.Tool.inputSchema()` 返回 `McpSchema.JsonSchema`（一个 **record**，有 `type()` / `properties()` / `required()` / `additionalProperties()` / `defs()` / `definitions()`），**不是 String**。而 `OryxTool.getInputSchema()` 要 String（仲裁 2）。

```java
// 正确：走 SDK 自己的 mapper，保持 JSON 契约在 SDK 内部
String schema = McpJsonDefaults.getMapper().writeValueAsString(tool.inputSchema());

// 错误：toString() 拿到的是 record 的调试串，不是合法 JSON
String schema = tool.inputSchema().toString();
```

**这样写还有一个好处**：本仓源码**不需要 import Jackson**——JSON 序列化的细节留在 SDK 里。

#### 约束 2：`isError()` 是 `Boolean`，可能为 `null`

```java
// 正确
boolean failed = Boolean.TRUE.equals(result.isError());

// 错误：null 时拆箱 NPE
boolean failed = result.isError();

// 错误：null 时 !null 抛 NPE
boolean failed = !result.isError();
```

### §3.5 工具名冲突

MCP 工具与内置工具**重名** → 保留先注册者（内置，因为内置先注册）+ WARN，MCP 的那个丢弃。规则与 [tool-abstraction.md](tool-abstraction.md) §2.1 一致。

### §3.6 客户端生命周期

`McpSyncClient implements AutoCloseable`。**本节不治理生命周期**——进程退出即结束（核心阶段单机、同步阻塞模型）。

重启 / 健康检查 / 重连退避 / MCP 认证 → **扩展阶段**（spec「明确不做」已列）。本节只做"连不上就 WARN 跳过"。

---

## §4 本节 MUST NOT

| # | 禁止 | 依据 |
| --- | --- | --- |
| 4.1 | 改 `mcp_servers.yaml` 的模板结构或键名 | 软门禁 ②；第 18 节已交付 |
| 4.2 | 只声明 `mcp-core` | 实测启动抛 `ServiceConfigurationError` |
| 4.3 | 让连接失败抛异常出 `connectAll()` | FR-008 / SC-004 |
| 4.4 | 把 MCP 凭证写进日志、异常消息或工具返回 | FR-027 |
| 4.5 | 在本仓源码里 import `reactor` | FR-026；H4 不变量 ⑤ |
| 4.6 | 用 `McpJsonMapper` 之外的方式解析 MCP 的 JSON | 保持 SDK 的 JSON 契约在 SDK 内部；避免版本打架 |
| 4.7 | 给 `Workspace` 加 `mcpServersFile()` 方法 | 第 18 节交付物（软门禁 ④）；路径在 `McpServerConfigLoader` 内部解析 |
