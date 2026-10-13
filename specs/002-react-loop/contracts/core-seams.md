# Contract: core 侧四个接缝接口

**来源**：[spec.md](../spec.md) FR-013 + 「冲突仲裁记录 1」；章程附加约束「契约在 core、实现在能力模块」；`specs/000-runtime-kernel/plan.md` §「跨模块消费接缝」。这是**模块间的 Java 契约**：签名变更必须走修订程序。

## 0. 为什么有这四个接口

课件骨架里 `ReActLoop` 直接调 `providerService.chat(...)`、`ToolExecutor` 直接查工具表、`PromptBuilder` 直接读记忆模块。这在 OryxOS 的模块结构里行不通——章程硬约束 `oryxos-core` MUST NOT 反向依赖任何能力模块（`oryxos-provider` / `oryxos-tool` / `oryxos-memory` 全部依赖 core，反向依赖即成环）。

裁决：**接口放 core、实现放能力模块、Spring 注入**。本节只定义与调用，**不提供任何实现**；五个测试类全部用替身。

| 接口 | 包 | 实现方 | 落地节 |
| --- | --- | --- | --- |
| `LlmClient` | `io.oryxlabs.oryxos.core.llm` | `oryxos-provider.ProviderService` | 装配在第 18 节（见 §5） |
| `ToolCatalog` | `io.oryxlabs.oryxos.core.tool` | `oryxos-tool.ToolRegistry` | 第 20 节 |
| `MemorySource` | `io.oryxlabs.oryxos.core.memory` | `oryxos-memory.MemoryService` | 第 22 节 |
| `SessionService` | `io.oryxlabs.oryxos.core.session` | 第 18 节的 `sessions` 表 + Repository | 第 18 节 |

---

## 1. `LlmClient`（core 契约）

```java
public interface LlmClient {
  ChatResponse chat(String sessionId, Profile profile, Prompt prompt);
}
```

| 参数 | 说明 |
| --- | --- |
| `sessionId` | 会话标识。实现方要把它写进 `llm_calls.session_id`，签名不带它就写不了审计（FR-014） |
| `profile` | 决定用哪家厂商、哪个模型、什么温度 |
| `prompt` | 本轮发给模型的内容（四部分，见 [engine-api.md](engine-api.md) §2） |

**返回值**：Spring AI 的 `org.springframework.ai.chat.model.ChatResponse`，**原样**返回——响应里可能带着模型「想调某个工具」的请求，但执行不在实现方，也不在 core 的调度逻辑里。

**签名与第 16 节逐字一致**：`contracts/provider-service-api.md` §1 已把 `ChatResponse chat(String, Profile, Prompt)` 定为契约。本节新增的是**接口**，不是新签名——实现方加一个 `implements` 即可，不改方法体。

**行为约定**（由实现方承担，本节只依赖）：

1. 按 `profile` 点名的厂商**显式映射**取模型，MUST NOT 按类型扫描容器 Bean（章程原则 III）；
2. 请求上 `internalToolExecutionEnabled` MUST 为 `false`——工具调度完全归 `ToolExecutor`（章程原则 III，最易写错的一条）；
3. 成败都落 `llm_calls`；失败**先落账再上抛**（章程原则 VI）；
4. 同步阻塞：不返回 `CompletableFuture`、不返回 `Flux`（章程原则 I）。

---

## 2. `ToolCatalog`（core 契约）

```java
public interface ToolCatalog {
  Optional<OryxTool> find(String name);
  List<OryxTool> availableFor(Profile profile);
}
```

| 方法 | 调用方 | 语义 |
| --- | --- | --- |
| `find(String name)` | `ToolExecutor` | 按名取工具。**查不到返回空**，由 `ToolExecutor` 转成「失败结果 + 落审计」，MUST NOT 抛异常打断循环 |
| `availableFor(Profile)` | `PromptBuilder` | 本次可用工具子集（按 Profile 的 `tools` 字段过滤后的结果）。空 Profile 工具清单返回空表 |

**为什么是两个方法而不是一个**：两处调用的过滤口径不同——执行时按模型给的名字精确命中，组装 prompt 时按 Profile 声明的子集列全量说明。合成一个方法会让两边各带一半参数。

**实现方的约束**（第 20 节）：`oryxos-tool` 的 `ToolRegistry` 汇聚内置 Tool / MCP Tool / `@Tool` Bean 三源，全部包装成统一的 `OryxTool`（章程原则 V）；`AGENT.md` MUST NOT 注册进来（章程原则 IV）。**core MUST NOT import `oryxos-tool`**。

---

## 3. `MemorySource`（core 契约）

```java
public interface MemorySource {
  Optional<String> longTermMemory(Profile profile);
}
```

- 返回**长期记忆的文本**（跨会话的那份），未启用/为空时返回 `Optional.empty()`——`PromptBuilder` 据此**跳过** prompt 的第二部分，不塞占位文本（FR-004、spec Edge Cases）。
- 返回的是**长期记忆**，不是会话历史：会话历史由 `Session` 直接提供，两者 MUST NOT 混在一起（课件原话：「长期记忆是跨会话都在的东西，会话历史只是这一次对话到目前为止的往来记录」）。
- 三层门面 `MemoryService` 的完整形态属第 22 节；本节只消费「有就读、没有就跳过」这一个语义。

---

## 4. `SessionService`（core 契约）

```java
public interface SessionService {
  void save(Session session);
}
```

- **本节只声明 `save`**：`AgentService.process` 在循环跑完后调一次，把累积完的历史持久化（FR-003 的落盘侧）。
- `load` / `create` / 会话标识生成规则**不在本节范围**——没有调用方的抽象就是凭空造接口（章程原则 II）。第 18 节按 `sessions` 表（`specs/000-runtime-kernel/data-model.md` A1）补齐。
- 本节对「持久化」的验收口径是**「经接缝被调用一次」**（SC-004 的落盘侧），真实落库由第 18 节的实现负责。

---

## 5. 本节**不**给 `ProviderService` 加 `implements`

spec 的 Assumptions 明写「本节只定义与调用，不实现；测试一律用替身」，故本节不动第 16 节交付的 `ProviderService`。装配留到第 18 节 CLI 接线时一并做。

**与第 16 节 `contracts/provider-service-api.md` §5 的出入**（该处写「第 17 节只需让 `ProviderService` 增加一个 `implements`」）：本节按更新且经本轮批准的 spec 顺延。影响面为零——`ProviderService.chat` 的签名与本契约 §1 逐字一致，加 `implements` 是一行、不改方法体，本节代码与测试全部面向 `LlmClient` 编程，接线时不需要动本节任何一行。此项已在 Step 5 的固定停点提请用户确认。

**代价（如实记录）**：本节交付后 `LlmClient` 在**生产装配**里暂无实现方，`oryxos-boot` 的 Spring 上下文不会构造 `ReActLoop`。这是 spec 认可的形态（本节无独立交互入口，端到端 Demo 是人工项），且不产生死代码——四个引擎类全部被单测覆盖。
