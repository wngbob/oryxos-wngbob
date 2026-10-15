# Phase 1 Data Model: Tool 体系

**Feature**: `specs/005-tool-registry` | **Branch**: `class-20` | **Date**: 2026-10-14

> **本文件的第一条结论**：本节**不新增任何数据表、不新增任何列、不新增任何 Repository 查询方法**。工具调用的审计落在第 17 节已交付的 `tool_invocations` 表上（章程原则 VI 的"Day One 落库"在第 17 节已兑现），本节只**写**它，不建它、不改它。本文件描述的是**内存中的类型关系**，不是数据库模式。

---

## 0. 持久化边界（先划清）

| 数据 | 存哪 | 本节动作 |
| --- | --- | --- |
| 工具调用记录 | `tool_invocations`（第 17 节既有表，`oryxos-storage`） | **只写**。由 `ToolExecutor` 写，本节一行写入代码都不加 |
| 工具定义（内置 / 注解 / MCP） | **内存**，`ToolRegistry` 持有 | 新建内存结构，不落库 |
| MCP server 声明 | `.oryxos/mcp_servers.yaml`（第 18 节既有文件，模板冻结） | 只读 |
| 通知渠道 | `notify_channels`（第 19 节既有表） | 本节只加 Bean 声明，代码不改 |
| Agent 的可用工具清单 | `AGENT.md` frontmatter 的 `tools` 字段（第 16/17 节既有） | 只读（经 `Profile.tools`） |

**为什么工具定义不落库**：工具定义是**代码与配置的派生物**（`@Tool` 注解扫描 + YAML 读取），启动时重建成本可忽略，落库会引入"库里与代码不一致"的经典故障。章程原则 VIII 的"状态外置"针对的是**会话与审计**这类跨重启必须保留的状态，不针对可重建的派生物。

---

## 1. 实体

### 1.1 `OryxTool`（工具）——骨架期已交付，本节签名一行不改

**位置**：`oryxos-core/.../tool/OryxTool.java`

| 字段（方法） | 类型 | 约束 |
| --- | --- | --- |
| `getName()` | `String` | **非空、非空白**；注册表内唯一（重名保留先注册者） |
| `getDescription()` | `String` | **非空、非空白**——模型靠它决定调不调这个工具 |
| `getInputSchema()` | `String` | **非空、非空白**；MUST 是合法 JSON 字符串（FR-002） |
| `execute(String inputJson)` | `ToolResult` | 入参是 JSON 字符串；实现 MUST NOT 返回 `null` |

**校验规则**：三件套非空由 `OryxToolContractTest` 参数化遍历注册表**逐个**断言（SC-001）。任何工具漏实现 schema → 单测红，而不是运行时模型报错。

**设计约束**：`getInputSchema()` 返回 `String` 而非结构化对象（仲裁 2，已报批）。全部转换成本收在 `AnnotatedToolAdapter` 与 `McpToolAdapter` 两处适配器内部，工具实现与调用方均不感知。

---

### 1.2 `ToolResult`（执行结果）——骨架期已交付，本节签名一行不改

**位置**：`oryxos-core/.../tool/ToolResult.java`

```java
public record ToolResult(boolean success, String content, String errorMessage, boolean retryable)
```

| 字段 | 类型 | 约束 |
| --- | --- | --- |
| `success` | `boolean` | 成功时 `content` 非空、`errorMessage` 为 `null`；失败时相反 |
| `content` | `String` | 成功时的结果正文 |
| `errorMessage` | `String` | 失败时的原因 |
| `retryable` | `boolean` | **"这错值不值得再调一次"**——循环据此决定要不要重试（FR-003） |

**三个静态工厂**（既有）：`success(content)` / `failure(errorMessage)` / `retryableFailure(errorMessage)`。

**状态转换**：

```text
工具被调用 ──┬─ 沙箱拦截 ──→ SandboxViolationException（上抛，不在工具内 catch）
             ├─ 执行成功 ──→ ToolResult.success(content)
             └─ 执行失败 ──┬─ 可重试（如网络超时）──→ ToolResult.retryableFailure(msg)
                           └─ 不可重试（如文件不存在）→ ToolResult.failure(msg)
```

**关键约束**：`SandboxViolationException` **不转成** `ToolResult.failure`——它直接冒泡到 `ToolExecutor` 的既有 `catch (RuntimeException)`，由那里统一转失败 + 落审计。在工具内 catch 会让失败在审计表里消失（违反 FR-021）。

---

### 1.3 `RegisteredTool`（注册表条目）——**本节新增**

**位置**：`oryxos-tool/.../registry/ToolRegistry.java`（**嵌套 record**）

```java
public record RegisteredTool(OryxTool tool, String source)
```

| 字段 | 类型 | 约束 |
| --- | --- | --- |
| `tool` | `OryxTool` | 非空 |
| `source` | `String` | 三档取值之一：`builtin` / `annotation` / `mcp:<server名>` |

**为什么嵌套**：把新增的 public 顶层类型压到 0 个（`ToolRegistry` 本身已在本节交付物清单里）。语义上它是"注册表里的一条记录"，不是独立概念。**软门禁 ① 报备项**，见 spec 仲裁 6 与 [research.md](research.md) D6。

**`source` 为什么是 `String` 而不是 enum**：`mcp:<server名>` 带动态后缀，enum 表达不了。

---

### 1.4 `ToolCatalog`（可用工具集合接缝）——骨架期已交付

**位置**：`oryxos-core/.../tool/ToolCatalog.java`

| 方法 | 语义 |
| --- | --- |
| `Optional<OryxTool> find(String name)` | 按名取工具，无则 `Optional.empty()` |
| `List<OryxTool> availableFor(Profile profile)` | 该 Profile 可用的工具子集 |

**契约（FR-005）**：`availableFor` 的结果 MUST **恰好等于** `profile.tools()` 里能解析到的名字集合——多一个（没过滤干净）和少一个（过滤过头）都是错。声明了但注册表里没有的名字 → WARN + 跳过（不抛异常，不塞空占位）。

**实现方**：`oryxos-tool` 的 `ToolRegistry`（core 的 javadoc 早已写明）。装配处的 `EmptyToolCatalog` 自动让位。

---

### 1.5 `AnnotatedMethod`（注解方法映射）——`AnnotatedToolAdapter` 内部

**位置**：`oryxos-tool/.../registry/AnnotatedToolAdapter.java`（内部状态）

| 字段 | 类型 | 来源 |
| --- | --- | --- |
| `bean` | `Object` | 构造参数 |
| `method` | `Method` | 扫描 `bean.getClass()` 上带 `@Tool` 的方法 |
| `toolName` | `String` | `@Tool.name()` 非空则用它，否则 `ToolUtils.getToolName(method)` |
| `description` | `String` | `@Tool.description()` |
| `inputSchema` | `String` | `JsonSchemaGenerator.generateForMethodInput(method)` |

**校验规则**：`method.getParameterTypes()` 与 JSON 入参的键必须能一一对应；缺键时**不静默补 `null`**，而是明确报错（让模型看到"参数错了"，而不是拿到一个参数为 null 的诡异结果）。

**关键前置条件（D2a）**：`inputSchema` 里的参数名来自 `Parameter.getName()`，**要求父 pom 打开 `-parameters`**。否则参数名退化成 `arg0` / `arg1`，模型无法构造调用。这条由单测钉死（断言 `notify` 的 schema 含 `content` / `channel` 且不含 `arg0`）。

---

### 1.6 `McpServerConfig`（MCP server 声明）——**本节新增**

**位置**：`oryxos-tool/.../mcp/McpServerConfig.java`

| 字段 | 类型 | 来源键（第 18 节模板，**冻结**） | 约束 |
| --- | --- | --- | --- |
| `name` | `String` | `name` | 非空；用于 `mcp:<name>` 来源标签 |
| `transport` | `String` | `transport` | `stdio` 或 `sse` |
| `command` | `List<String>` | `command` | `stdio` 时必填 |
| `env` | `Map<String,String>` | `env` | 值支持 `${ENV_VAR}` 占位 |
| `url` | `String` | `url` | `sse` 时必填 |

**校验规则**（FR-024，模板键名软门禁 ② 不得改）：

- `transport: stdio` 而 `command` 缺失/为空 → WARN + **跳过该 server**（不抛异常，避免一个配错的 server 拖垮启动）；
- `transport: sse` 而 `url` 缺失 → 同上；
- `env` 里的 `${ENV_VAR}` 解析不到环境变量 → WARN + 跳过该 server（凭证缺失时**不落明文、不猜测**）；
- 文件不存在 / 为空 / 没有 `servers` 键 → 视为空清单，**不报错**。

**默认状态**：模板末尾是 `servers: []`（示例全在注释里），故本节交付后**默认不连接任何 MCP server**——预期状态，非未完成（spec 仲裁 7）。

---

### 1.7 `SandboxAction`（沙箱动作）——骨架期已交付

**位置**：`oryxos-tool/.../sandbox/SandboxAction.java`

```java
public record SandboxAction(ActionType type, String target)
```

| 字段 | 类型 | 取值 |
| --- | --- | --- |
| `type` | `ActionType` | `FILE_READ` / `FILE_WRITE` / `SHELL_COMMAND` / `HTTP_REQUEST` |
| `target` | `String` | 路径 / 可执行文件名 / URL |

**工具 → 动作映射**：

| 工具 | `ActionType` | `target` |
| --- | --- | --- |
| `read_file` / `list_dir` / `grep` / `glob` | `FILE_READ` | 目标路径 |
| `write_file` / `edit_file` | `FILE_WRITE` | 目标路径 |
| `shell` | `SHELL_COMMAND` | 可执行文件名 |
| `http_get` / `http_post` / `web_search` | `HTTP_REQUEST` | 目标 URL |
| `notify` | `HTTP_REQUEST` | 渠道 URL（第 19 节已交付，**不改**） |

**本节状态**：`PermissiveSandbox.enforce` 是**空实现**（D1，已报批）→ 第 20~23 节之间校验不生效。**调用位已按最终形态写好**，第 24 节换上 `WhitelistSandbox` 后工具代码零改动（SC-009）。

---

### 1.8 两个接缝：`UserInteraction` / `SearchProvider`——**本节新增**

```java
public interface UserInteraction {
  Optional<String> ask(String question);   // 拿不到回答返回 empty，绝不阻塞
}

public interface SearchProvider {
  List<SearchResult> search(String query, int limit);
  String endpoint();                        // 目标 URL——web_search 的沙箱动作要用
}
```

| 接缝 | 两档/一档实现 | 约束 |
| --- | --- | --- |
| `UserInteraction` | `ConsoleUserInteraction`（读终端一行）/ `UnsupportedUserInteraction`（无人值守渠道明确报不支持） | 拿不到回答 MUST **明确报错**，绝不静默卡住（FR-017） |
| `SearchProvider` | `DuckDuckGoSearchProvider`（免 key） | 换别的只加实现类，不改工具与调用方（FR-018） |

**为什么 `ask` 返回 `Optional`**：接缝必须能表达"问不到"，否则实现方只能靠抛异常或返回空串——空串会被下游当成"用户回答了空内容"，语义错。

**为什么 `SearchProvider` 多一个 `endpoint()`**（**复核补漏**）：`web_search` 的首行沙箱动作是 `enforce(HTTP_REQUEST, <目标 URL>)`，这个 URL 必须来自 provider 本身。若由装配处另传一个 URL 字符串，换搜索引擎时就**既要加实现类、又要改装配**——直接违反 FR-018「换别的只加实现类、不改工具与调用方」。URL 是 provider 的属性，必须长在接缝上。

---

## 2. 关系图

```text
                    ┌──────────────────────────────────────┐
                    │           ToolRegistry               │
                    │  (implements core 的 ToolCatalog)    │
                    └──────────────────────────────────────┘
                       │ register(tool, source)   ▲ 三源汇聚
        ┌──────────────┼──────────────────────────┼──────────────┐
        │              │                          │              │
   [builtin]      [annotation]                [mcp:<server>]     │
        │              │                          │              │
  FileTools        AnnotatedToolAdapter     McpToolAdapter       │
  ShellTools            │ 扫描                 │ 包装             │
  HttpTools        @Tool 方法                McpSchema$Tool      │
  NotifyTools(19节)     │                        │               │
  InteractionTools  ToolUtils +           McpClientService       │
  WebSearchTools    JsonSchemaGenerator    ├ 读 McpServerConfig  │
        │                                   ├ McpClient.sync()    │
        │ 每个 @Tool 方法首行                └ listTools()         │
        ▼                                                         │
   Sandbox.enforce(SandboxAction)                                 │
        │                                                         │
        ▼                                                         │
     ToolResult ──→（上抛异常或返回结果）                          │
        │                                                         │
        ▼                                                         │
  ┌───────────────────────────────────────────────────────────┐  │
  │ ToolExecutor (第 17 节，本节不改)                          │  │
  │   ├ find(name) ────────────────────────────────────────────┘  │
  │   ├ 执行                                                    │
  │   └ 写 tool_invocations（成功失败都写）                     │
  └───────────────────────────────────────────────────────────┘
        ▲
        │ availableFor(profile)
  ┌─────────────────┐
  │ PromptBuilder   │ ── toolCallbacks(...) ──→ ProviderService ──→ LLM
  │ (第 17 节)      │                          (第 16 节)
  └─────────────────┘
```

**关键读法**：注册表**只出现在最上面**——它是三源的汇聚点，也是 `PromptBuilder`（决定模型看得见哪些工具）与 `ToolExecutor`（决定实际调哪个）的共同上游。**本节改动的只有上游的供给**，ReAct 循环与执行器一行不改（章程原则 IX）。

---

## 3. 不变量（可机器判定）

| # | 不变量 | 判定方式 |
| --- | --- | --- |
| 1 | 注册表里每个工具的 name / description / inputSchema 都非空非空白 | `OryxToolContractTest` 参数化遍历 |
| 2 | 注册表里工具名唯一 | 重名保留先注册者 + WARN；`ToolRegistryTest` 断言先注册者胜出 |
| 3 | `availableFor(profile)` 结果**恰好等于**能解析到的声明名集合 | `ToolRegistryTest` 断言集合相等（不是 `containsAll`） |
| 4 | 每个涉外工具的每个 `@Tool` 方法，`enforce` 是**第一行** | 越界用例断言"目标一次都没被碰过" |
| 5 | 每次工具调用（成功/失败/沙箱拦截）都在 `tool_invocations` 里恰好一行 | 复用第 17 节测试路径 |
| 6 | 装配后容器里 `ToolCatalog` 类型的 Bean **恰好一个**，且是 `ToolRegistry` | 上下文测试断言 bean 名集合 |
| 7 | `source` 只有 `builtin` / `annotation` / `mcp:<server>` 三档 | `ToolRegistryTest` 断言三源各自注册后的 source 取值 |

---

## 4. 明确不建的

| 项 | 为什么 |
| --- | --- |
| 工具定义的持久化表 | 工具定义是可重建的派生物（见 §0） |
| `tool_invocations` 的新列 | 本节只写不建（FR-021 明令不新增第二条写入路径） |
| 工具调用结果缓存 | YAGNI；课件与需求文档均未要求 |
| 工具版本 / 依赖关系建模 | 扩展阶段的 Tool Policy 范畴（spec「明确不做」已列） |
| `WhitelistSandbox` 的白名单配置属性类 | 第 24 节交付物，本节硬写等于自造契约 |
