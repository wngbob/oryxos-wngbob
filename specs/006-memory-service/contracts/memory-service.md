# 契约：`MemoryService` 门面 + `MemoryTools` 对外面

**Feature**: `specs/006-memory-service` | **Date**: 2026-10-15

> 本文件定死**两件对外的事**：门面对上（`PromptBuilder` / `MemoryTools`）的三个方法，以及两个内置工具**对模型可见**的面（工具名 / 入参 Schema / 返回文案）。工具名一旦交付就不得改——它是模型引用它的**唯一凭据**。

---

## 1. 门面：`MemoryService`

**落点**：`oryxos-memory` 的 `io.oryxlabs.oryxos.memory.MemoryService`（接口）+ `MemoryServiceImpl`（唯一实现）

```java
public interface MemoryService extends MemorySource {   // core 的接缝（第 17 节交付，一行不改）
  void remember(String content, MemoryScope scope);     // MemoryTools 的 save_memory 调它
  List<String> recall(String keyword);                  // MemoryTools 的 recall_memory 调它
  // Optional<String> longTermMemory(Profile profile);  ← 从 MemorySource 继承；PromptBuilder 调它
}
```

**为什么 `extends MemorySource` 而不是新造一个方法**（D1 / 仲裁 2）：core 的接缝是**骨架期自造**的（上游全仓从未有过 `MemorySource`），且 FR-025 明令 `oryxos-core` 一行不改。实现既有接缝即可让 `PromptBuilder` 零改动地用上真实记忆。

**三类操作的行为契约**：

| 方法 | 契约 |
| --- | --- |
| `longTermMemory(Profile)` | 取长期记忆文本。**`profile` 参数本节不使用**（全局单份，仲裁 4）——签名照旧。`load()` 为空白时返回 `Optional.empty()`，让 `PromptBuilder` 把 prompt 第二部分**整条跳过**（FR-014 / SC-007）。**MUST NOT 混入会话历史**（FR-015） |
| `remember(content, scope)` | 转发给 `store.append`。**`scope` 要求非 null**（`Objects.requireNonNull`）——缺省 `ARCHIVAL` 的落点在 `MemoryTools`，门面不猜（FR-006） |
| `recall(keyword)` | **按 `store.capabilities()` 路由**：`KEYWORD` / `DELEGATED` → `store.recallByKeyword`；`HYBRID_BUILTIN` → **抛异常**，MUST NOT 静默降级（FR-010 / 仲裁 5） |

**门面 MUST NOT 持有 `Session`**（FR-015）：长期记忆与会话历史是两件生命周期完全不同的事，混在一个方法里返回会把它们焊死（课件 `buildContext(Session)` 记为已知偏差，进验收报告）。

**`HYBRID_BUILTIN` 的报错文案**：点名"核心阶段无实现方"，并说明它需要什么（内置向量检索路径）——**MUST NOT 静默降级成关键词检索**，那会让运维以为"语义检索已生效"。

---

## 2. 两个内置工具的对外面

**落点**：`oryxos-memory` 的 `io.oryxlabs.oryxos.memory.builtin.MemoryTools`（与上游同包同名）

**返回类型是 `ToolResult`**（D3）——与其余 13 个内置工具同形。

### 2.1 `save_memory`

```java
@Tool(name = "save_memory", description = "记住一件值得长期记住的事")
public ToolResult saveMemory(
    @ToolParam(description = "要记住的内容") String content,
    @ToolParam(description = "core 或 archival，不确定就填 archival", required = false) String scope)
```

| 项 | 值 | 依据 |
| --- | --- | --- |
| **工具名** | `save_memory`（`@Tool(name = ...)` **显式钉死**） | 模型引用它的唯一凭据，交付后不得改。显式钉名还有一层：方法名与工具名解耦（第 20 节既有做法） |
| **`content`** | **必填**（不加 `required`，默认即 true） | — |
| **`scope`** | **可选**（`@ToolParam(required = false)`，**必须有**） | D5：Spring AI 1.1.8 的 `JsonSchemaGenerator` 对无注解参数一律判 required；不声明则模型可见的 Schema 会把 `scope` 列进 `required`，"缺省归档"在模型可见层不成立 |
| **scope 取值解析** | `null` / 空白 → `ARCHIVAL`；否则 `MemoryScope.valueOf(scope.toUpperCase(Locale.ROOT))` | FR-006 缺省归档。**MUST NOT `trim()`**——`"CORE "`（带尾空格）是**非法值**，MUST 报错（Edge Case） |
| **非法 scope** | **抛 `IllegalArgumentException`**，消息点名合法取值 | D4：抛异常才让这条调用在 `tool_invocations` 里被记成**失败**并把原因回灌模型；返回错误文案会被记成 `success=true`，审计说谎 |
| **成功返回** | `ToolResult.success("已记住")` | 与课件文案一致 |

### 2.2 `recall_memory`

```java
@Tool(name = "recall_memory", description = "按关键词检索长期记忆")
public ToolResult recallMemory(@ToolParam(description = "检索关键词") String keyword)
```

| 项 | 值 | 依据 |
| --- | --- | --- |
| **工具名** | `recall_memory`（显式钉死） | 同上 |
| **`keyword`** | 必填 | — |
| **命中** | `ToolResult.success(String.join("\n", hits))` | 与课件一致 |
| **未命中** | `ToolResult.success("没有找到相关记忆")` | FR-013：MUST NOT 抛异常、MUST NOT 让这一轮对话中断。**文案逐字钉死**，测试断言这个字面量 |

### 2.3 工具的注册（零改动面）

两个工具经**既有** `ToolRegistry.registerAnnotated(memoryTools, "builtin")` 进注册表——`ToolRegistry` / `AnnotatedToolAdapter` / 安全校验位 / 审计落库 / 按 Agent 过滤**四处一行不改**（FR-012 / SC-008，第 20 节「加工具成本恒定」的**第二次**真实验证）。

`oryxos tool list` 的默认输出因此从 `builtin (13)` 变成 `builtin (15)`——**已有测试不需要改**：`OryxToolContractTest` 用的是 `hasSizeGreaterThanOrEqualTo(13)`（下限断言，第 20 节交付时就是这么写的）。

---

## 3. 门面与工具的分工（一张图说清）

```
PromptBuilder ──(MemorySource.longTermMemory)──┐
                                               ├──> MemoryServiceImpl ──> LongTermMemoryStore
MemoryTools ──(remember / recall)──────────────┘        （门面）              （可插拔后端）
```

- `PromptBuilder` **只看得见"读"**：它的构造器收的是 `MemorySource`（core 的窄接缝），拿不到 `remember` / `recall`——**这是有意的**：给 prompt 组装器两个写入方法等于给它越界的能力。
- `MemoryTools` 收的是 `MemoryService`（宽门面），因为工具本来就要读写。
- 两侧都**不感知底下是哪一档后端**——SC-004 的"改动行数为 0"就靠这条。

---

## 4. 装配契约（`CliAssemblyConfiguration`）

| 项 | 契约 |
| --- | --- |
| `memoryService()` 的返回类型 | `MemoryService`（既是门面、也是 `MemorySource`） |
| **声明顺序** | `memoryService()` **MUST** 声明在 `memorySource()`（那个带 `@ConditionalOnMissingBean(MemorySource.class)` 的空实现）**之前**——同一个 `@Configuration` 类里 `@ConditionalOnMissingBean` 按 `@Bean` 方法**声明顺序**求值（第 20 节 `toolCatalog()` 踩过的坑，其 javadoc 已把这条写成契约） |
| `EmptyMemorySource` | **MUST NOT 手工删除**、**MUST NOT 加 `@Primary`**——让位机制自动生效（仲裁 9） |
| `memoryTools()` | 新增 `@Bean`，参数是 `MemoryService`；在 `toolRegistry(...)` 里 `registerAnnotated(memoryTools, "builtin")`，**排在内置工具那一段的末尾、`connectAll` 之前**（保持"先内置、后 MCP"的既有顺序） |
| `InMemoryMemoryStore` | 装配处 **MUST NOT** 能选中它——它不在 `oryxos.memory.backend` 的取值里，也没有任何 `@Bean` 声明它 |
