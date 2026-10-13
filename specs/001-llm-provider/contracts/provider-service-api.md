# Contract: Provider 内部 API

**来源**: spec.md FR-004~FR-009；`docs/TechnicalSolution.md` §3.1/§3.3；课件第 16 节「第三步：写 chat 方法」「第四步：工具适配和审计」。这是**模块间的 Java 契约**（第 17 节的 ReAct 循环是下游消费者），签名变更必须走修订程序。

## 1. `ProviderService`（`oryxos-provider`）

对外只露一个方法：

```java
ChatResponse chat(String sessionId, Profile profile, Prompt prompt)
```

| 参数 | 说明 |
| --- | --- |
| `sessionId` | 会话标识。审计要落到 `llm_calls` 并按 session 关联，签名不带它审计就写不了 |
| `profile` | 决定用哪家厂商、哪个模型、什么温度 |
| `prompt` | 要发给模型的内容 |

**返回值**：Spring AI 的 `ChatResponse`，**原样**返回。响应里可能带着模型"想调某个工具"的请求，但执行不在本模块。

> 课件示例代码里写的 `Response` 是示意写法，实际类型以本契约为准（`org.springframework.ai.chat.model.ChatResponse`）——不是笔误。

**行为约定**：

1. **按名路由**：用 `profile` 点名的厂商名去显式映射表取模型；取不到则抛 `ProviderNotFoundException`（含该名字），MUST NOT 静默换一家、MUST NOT 留空跑过。
2. **工具只翻译不执行**：把本次可用工具翻译成 Spring AI 的 `ToolCallback` 随请求发出；请求上 `internalToolExecutionEnabled` MUST 为 `false`（[research.md](../research.md) D4）。
3. **成败都审计**：成功写 `success=true`；失败**先**写 `success=false` + 原因，**再**把异常继续上抛（[llm-calls-table.md](llm-calls-table.md) §4）。
4. **同步阻塞**：不返回 `CompletableFuture`、不返回 `Flux`、不自建线程池（章程原则 I、H4 不变量⑤）。

## 2. `ToolSchemaAdapter`（`oryxos-provider`）

```java
List<ToolCallback> toToolCallbacks(List<OryxTool> tools)
```

- 把每个 `OryxTool` 的 `getName()` / `getDescription()` / `getInputSchema()` 一一对应地构造为 `ToolDefinition`（`DefaultToolDefinition.builder().name().description().inputSchema().build()`），再包成 `ToolCallback`。
- **产物中 MUST NOT 含任何执行逻辑**：`ToolCallback.call(String)` 直接抛 `UnsupportedOperationException`（[research.md](../research.md) D5）。真正执行工具的是第 17 / 20 节的 `ToolExecutor`。
- 空输入返回空列表，不报错。

## 3. `LlmCallRecorder`（接口，`oryxos-provider`）

```java
void record(String sessionId, String provider, String model,
            Usage usage, boolean success, String errorMessage, long durationMs)
```

- `usage` 为 Spring AI 的 `org.springframework.ai.chat.metadata.Usage`；失败时为 `null`。
- 生产实现 `JpaLlmCallRecorder` 包装 `oryxos-storage` 的 `LlmCallRepository`。
- 抽成接口是为了让"失败也落审计"这条回归测试能 mock 掉它并断言参数。

## 4. `ProviderNotFoundException`（`oryxos-provider`）

- 继承 `RuntimeException`（不强制调用方 try-catch，由上层统一处理）。
- 消息 MUST 含 Profile 点名的**原始厂商名**（大小写与空格按原样回显，不做静默容错）。

## 5. 已知接缝（留给第 17 节）

章程要求 `oryxos-core` MUST NOT 反向依赖能力模块，而上游 plan 已定规则：**core 需消费能力模块服务时，接口（契约）放 core、实现放能力模块、Spring 注入**（同 `ToolCatalog` / `MemoryService` 的模式）。

第 17 节的 `ReActLoop` 落在 `oryxos-core`，届时需要把本节的 `ProviderService` 抽象出一个 core 侧接口并让 `ProviderService` 实现它。**本节不预先引入该接口**——本节没有任何消费者，凭空造接口违背章程原则 II（YAGNI）。第 17 节只需让 `ProviderService` 增加一个 `implements`，方法签名沿用本契约，不构成返工。
