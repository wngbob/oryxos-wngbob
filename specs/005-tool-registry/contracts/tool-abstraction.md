# 契约：统一工具抽象、注册表与注解管道

**Feature**: `specs/005-tool-registry` | **Branch**: `class-20` | **Date**: 2026-10-14

本文件是本节对**后续节与业务方**的公开承诺。三件既有契约（§1）**逐字冻结、本节一行不改**；两件新增契约（§2、§3）是本节的交付物。

---

## §1 冻结的三件既有契约（`oryxos-core`，本节零改动）

### §1.1 `OryxTool` —— 统一工具抽象

```java
package io.oryxlabs.oryxos.core.tool;

public interface OryxTool {
  String getName();
  String getDescription();
  String getInputSchema();
  ToolResult execute(String inputJson);
}
```

**承诺**：

| # | 承诺 | 违反的后果 |
| --- | --- | --- |
| 1.1.1 | **签名逐字不变**。本节不把 `getInputSchema()` 改成结构化对象、不把 `execute` 的入参改成 `JsonNode` | 全部工具实现与两处适配器返工；骨架期契约 javadoc 已写明设计意图 |
| 1.1.2 | `getInputSchema()` 返回的是**合法 JSON 字符串**，不是"描述 schema 的一段文字" | `ToolSchemaAdapter` 把它直传给 Provider，非法 JSON 会让 Function Calling 构造失败 |
| 1.1.3 | `execute` 的入参是**合法 JSON 字符串**；实现 MUST NOT 假设它非空 | 模型可能对无参工具传 `{}`，也可能传空串 |

### §1.2 `ToolResult` —— 执行结果

```java
public record ToolResult(boolean success, String content, String errorMessage, boolean retryable) {
  public static ToolResult success(String content);
  public static ToolResult failure(String errorMessage);
  public static ToolResult retryableFailure(String errorMessage);
}
```

**承诺**：`retryable` 是**给循环看的**（FR-003），不是给日志看的。任何工具实现 MUST NOT 一律返回 `false` ——那会让"网络超时"这类明确可重试的失败丢掉重试机会。

### §1.3 `ToolCatalog` —— 可用工具集合接缝

```java
public interface ToolCatalog {
  Optional<OryxTool> find(String name);
  List<OryxTool> availableFor(Profile profile);
}
```

**承诺**：`availableFor` 的结果**恰好等于** `profile.tools()` 里能解析到的名字集合（FR-005）。多一个和少一个都是错。

**本节不新增接缝方法**——第 26 节的 REST 端点需要的"来源"信息由 `ToolRegistry.registered()` 提供（§2），**不扩 `ToolCatalog`**。

---

## §2 新增契约：`ToolRegistry`

**位置**：`oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/registry/ToolRegistry.java`

```java
public class ToolRegistry implements ToolCatalog {

  public void register(OryxTool tool, String source);
  public void registerAnnotated(Object bean, String source);
  public boolean contains(String name);
  public List<RegisteredTool> registered();

  @Override public Optional<OryxTool> find(String name);
  @Override public List<OryxTool> availableFor(Profile profile);

  public record RegisteredTool(OryxTool tool, String source) {}
}
```

### §2.1 `register(OryxTool tool, String source)`

| 前置 | 行为 |
| --- | --- |
| `tool` 为 `null` | 抛 `NullPointerException`（编程错误，不静默） |
| `tool.getName()` 为空白 | 抛 `IllegalArgumentException`（契约三件套之一，注册时就该拦住） |
| 名字**未**注册过 | 登记，`source` 原样保存 |
| 名字**已**注册过 | **保留先注册者 + WARN**，后到者丢弃（FR-023，安全侧优先） |

**注册顺序确定（FR-023 配套）**：内置工具与注解 Bean **先**、MCP 工具**后**。理由：内置工具的名字是 OryxOS 自己定的、稳定；MCP server 的名字来自外部、可能撞车。撞车时保住内置的那个更可预期。

### §2.2 `registerAnnotated(Object bean, String source)`

扫描 `bean.getClass()` 上所有带 `@Tool` 的方法，每个方法生成一个 `OryxTool` 并 `register(..., source)`。

| 前置 | 行为 |
| --- | --- |
| `bean` 为 `null` | 抛 `NullPointerException` |
| 类上**没有**任何 `@Tool` 方法 | 不报错、不注册（允许"扫到空"是正常情况——业务方可能还没写工具） |
| 方法返回类型不是 `ToolResult` | 返回值包成 `ToolResult.success(String.valueOf(result))` |
| 方法返回类型是 `ToolResult` | **原样返回**（保真 `retryable`） |
| 方法抛异常 | **上抛**，不在适配器内 catch（让 `ToolExecutor` 落失败审计） |

### §2.3 `contains(String name)`

`true` 表示注册表里有这个名字。**课件那条 MCP 失联回归测试就是用它**断言"坏 server 的工具没注册进来"。

### §2.4 `registered()`

返回**全量**条目（工具 + 来源），顺序 = 注册顺序。消费方：
- `ToolListCommand`（本节，按来源分组输出，§见 [assembly.md](assembly.md) §5）；
- 第 26 节的 `/api/v1/tools` REST 端点。

**`source` 的三档取值（冻结）**：

| 取值 | 含义 |
| --- | --- |
| `builtin` | 内置工具类里的 `@Tool` 方法 |
| `annotation` | 业务方自己的 `@Tool` Bean |
| `mcp:<server名>` | 来自名为 `<server名>` 的 MCP server |

### §2.5 `find` / `availableFor`

`find` 委托内部按名索引；`availableFor` 按 `profile.tools()` 过滤。

**`availableFor` 的三条硬规则**：

1. 结果为 `List<OryxTool>`，顺序 = `profile.tools()` 里的声明顺序（**不**按注册顺序），便于对账；
2. `profile.tools()` 里**声明了但注册表没有**的名字 → WARN + **跳过**，不抛异常、不塞空占位（Edge Case）；
3. `profile.tools()` 为空或 `null` → 返回**空列表**（"这个 Agent 不用任何工具"是合法配置，不是错误）。

---

## §3 新增契约：`AnnotatedToolAdapter`

**位置**：`oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/registry/AnnotatedToolAdapter.java`

```java
public class AnnotatedToolAdapter implements OryxTool {
  public AnnotatedToolAdapter(Object bean, Method method);
}
```

### §3.1 元数据来源

| 字段 | 来源 | 兜底 |
| --- | --- | --- |
| `getName()` | `@Tool.name()` | 为空时用 `ToolUtils.getToolName(method)`（Spring AI 的命名规则） |
| `getDescription()` | `@Tool.description()` | 无兜底——**空描述是业务方的错，由契约测试抓** |
| `getInputSchema()` | `JsonSchemaGenerator.generateForMethodInput(method)` | 无兜底（FR-002） |

### §3.2 执行路径（**不用 Spring AI 的 `MethodToolCallback`**）

```text
execute(inputJson)
  ├ 1. JsonParser.fromJson(inputJson, Map.class)          ← 解析 JSON 入参
  ├ 2. 逐个 Parameter：
  │      raw = map.get(param.getName())
  │      缺键 且 @ToolParam(required=true)  → 报错（明确告诉模型参数错了）
  │      缺键 且 非必填                     → null
  │      raw != null → JsonParser.getObjectMapper().convertValue(raw, param.getType())
  ├ 3. method.invoke(bean, args)                          ← 反射调用
  └ 4. 返回值处理：
        是 ToolResult → 原样返回
        其他类型      → ToolResult.success(String.valueOf(r))
        抛异常        → 上抛（不 catch）
```

**为什么不用 `MethodToolCallback.call()`**（三条硬理由，见 [research.md](research.md) D2）：

1. 它把返回值字符串化，`ToolResult.retryable` 会丢；
2. 第 19 节已交付的 `NotifyTools.notify` 返回 `ToolResult`，迁就它就得改前序节公共接口（软门禁 ④）；
3. 它把异常包成错误文本返回，失败落不进 `tool_invocations`（违反 FR-021 / 章程原则 VI）。

**章程原则 III 的符合性**：Spring AI 在本节的用途被**收窄到**"`@Tool` 注解元数据 + JSON Schema 生成"——比第 16 节更窄。自动 tool 执行路径的禁用（`ProviderService` 的 `internalToolExecutionEnabled(false)`）一行不改。

### §3.3 前置条件：编译期必须打开 `-parameters`

**父 `pom.xml` 的 `maven-compiler-plugin` MUST 加 `<parameters>true</parameters>`。**

理由：`@ToolParam` **没有 `name()` 元素**（已用 `javap` 核实），Spring AI 只能从 `Parameter.getName()` 取参数名。未开 `-parameters` 时它返回 `arg0` / `arg1` → schema 里的参数名是 `arg0` → **模型无法构造正确的工具调用**。

这是"编译通过、门禁全绿、运行时功能坏掉"的静默失效，**由单测钉死**（见 [builtin-tools.md](builtin-tools.md) §3）。

---

## §4 本节 MUST NOT

| # | 禁止 | 依据 |
| --- | --- | --- |
| 4.1 | 修改 `OryxTool` / `ToolResult` / `ToolCatalog` 的任何签名 | 软门禁 ④；仲裁 2 |
| 4.2 | 给 `OryxTool` 加 `getSource()` 之类的来源访问器 | 来源是注册关系的属性，不是工具的属性 |
| 4.3 | 把 Tool 拆成 builtin / mcp / skill 多个模块 | 章程原则 V；`oryxos-tool` 单模块 |
| 4.4 | 启用 Spring AI 的自动 tool 执行 | 章程原则 III |
| 4.5 | 在适配器或注册表内 catch 异常转成 `ToolResult.failure` | FR-021——失败必须在 `ToolExecutor` 落审计 |
| 4.6 | 在 `ToolRegistry` 里持有跨调用的可变业务状态 | 章程原则 VIII（状态外置）；注册表须可重建 |
