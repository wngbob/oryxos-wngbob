# Contract: 引擎六个类的对外 API

**来源**：[spec.md](../spec.md) FR-001~FR-012；`docs/TechnicalSolution.md` §4.1/§4.2/§4.3/§8.3；课件第 17 节「三、代码怎么写」。六个类全部落在 `oryxos-core`，是本节交付物的主体。

**包结构**：

```text
io.oryxlabs.oryxos.core.engine/     ReActLoop、PromptBuilder、ToolExecutor、AgentService、ProfileContext
io.oryxlabs.oryxos.core.context/    ContextLoader
io.oryxlabs.oryxos.core.session/    Session、SessionMessage、SessionService
io.oryxlabs.oryxos.core.llm/        LlmClient
io.oryxlabs.oryxos.core.memory/     MemorySource
io.oryxlabs.oryxos.core.tool/       ToolCatalog（与既有的 OryxTool / ToolResult 同包）
```

---

## 1. `ReActLoop` —— 调度者，循环本体

```java
public class ReActLoop {
  public ReActLoop(PromptBuilder promptBuilder, LlmClient llmClient, ToolExecutor toolExecutor);
  public String run(Session session, String userMessage, Profile profile);
}
```

**算法**（FR-001/FR-002，与课件骨架同构）：

```text
session.appendUserMessage(userMessage)
for (i = 0; i < profile.settings().maxIterations(); i++) {     ← 轮数判断在循环头（见下）
    Prompt prompt = promptBuilder.build(session, profile)
    ChatResponse response = llmClient.chat(session.id(), profile, prompt)
    AssistantMessage output = response.getResult().getOutput()
    session.appendAssistantMessage(output.getText(), output.getToolCalls())   ← 先累积
    if (!response.hasToolCalls()) return output.getText()                     ← 收尾
    for (ToolCall call : output.getToolCalls())                               ← 顺序执行，不并行
        session.appendToolResult(call.id(), toolExecutor.execute(session.id(), call))
}
return "达到最大轮数，已停止"
```

**四条行为约定**：

1. **轮数判断在循环头**（FR-002）：第 N 轮的模型响应照常累积、该轮提出的工具**照常执行**，会话历史以「工具请求 + 工具结果」成对收尾，不留悬空请求。上限取 `profile.settings().maxIterations()`，默认 10。
2. **`max_iterations` 的语义是「模型调用次数上限」**：配 3 就恰好调 3 次模型，一轮不多。
3. **工具顺序执行**：一次响应里多个工具调用逐个执行，MUST NOT 并行（课件「有几样先别做」）。
4. **本类不感知消息来源**：不认识 channel、不认识 CLI/Web/定时（FR-008、章程原则 IX）。它只认识 `Session` + 用户消息 + `Profile`。

**异常**：模型响应没有 generation（`getResult() == null`）时抛 `IllegalStateException`——这不是「工具失败」，是 Provider 侧故障，不该被伪装成一次正常的空答复。

**上限答复的字面量**：`"达到最大轮数，已停止"`，**私有常量**。课件 harness 断言 `reply.contains("达到最大轮数")`，措辞逐字保真；但它不需要成为对外常量（FR-002 约束的是答复文本，不是类型）。

---

## 2. `PromptBuilder` —— 四部分组装

```java
public class PromptBuilder {
  public PromptBuilder(ContextLoader contextLoader, MemorySource memorySource, ToolCatalog toolCatalog);
  public Prompt build(Session session, Profile profile);
}
```

**四部分落到 `Prompt` 的位置**（FR-004）：

| # | 部分 | 落点 | 缺省行为 |
| --- | --- | --- | --- |
| ① | system prompt = `identity.prompt` + `ContextLoader.load(profile)` + 末尾当前日期时间 | `getInstructions()` 第 1 条 `SystemMessage` | `identity.prompt` 为空则不占位；日期时间行**必在末尾** |
| ② | 长期记忆 | 第 2 条 `SystemMessage` | `MemorySource` 返回空 → **整条不出现**，不塞占位文本 |
| ③ | 会话历史（截断后） | 其后的对话消息 | 会话为空时只剩本轮用户消息 |
| ④ | 当前可用工具列表 | `getOptions()`（`ToolCallingChatOptions`）的 `toolCallbacks` | 无可用工具 → 空列表 |

**③ 的截断规则**（FR-005）：保留**最后 N 个用户往返**（N = `profile.settings().maxHistoryTurns()`，默认 20），从**第 N 条用户消息**起截到末尾；不足 N 轮不截断。

- 「一轮」= 一条用户消息 + 它引发的全部 assistant / tool 消息（含工具往返）；
- 截断只切在**用户消息边界**上 → 结果必然以用户消息开头（SC-005）、工具请求与结果必然同段（FR-005）；
- **截断不改动 `Session` 本身**——只影响本次发给模型的内容，历史照旧完整可审计（SC-004）。

**③ 的角色映射**（`SessionMessage` → Spring AI 消息）：

| `SessionMessage.Role` | Spring AI 消息 |
| --- | --- |
| `USER` | `new UserMessage(content)` |
| `ASSISTANT` | `AssistantMessage.builder().content(text).toolCalls(toolCalls).build()` |
| `TOOL` | `ToolResponseMessage.builder().responses(List.of(new ToolResponse(toolCallId, name, content))).build()` |
| `SYSTEM` | 本节不产生；若历史里出现，映射为 `SystemMessage`（与 A1 的 `role` 口径一致） |

`TOOL` 的 `name` 由同一段历史里 assistant 消息的 `toolCalls` 反查（`Map<toolCallId, toolName>`）——A1 的元素形状没有 `toolName` 字段，见 [research.md](../research.md) D3。

**④ 的翻译**（[research.md](../research.md) D7）：`OryxTool` → `ToolCallback` 由本类**私有静态方法**完成，产物 `call(String)` 抛 `UnsupportedOperationException`（只翻译不执行，章程原则 III）。**不新增 public 类型**。

**日期时间格式**：一行 `当前时间：<LocalDateTime.now()>`，追加在 system prompt 文本的最末尾。用 `LocalDate.now().toString()` 做包含断言即可判定（[research.md](../research.md) D12）。

**语法约束**：角色映射用传统 `switch` + `case ...: break;` 或 if/else 链，**不用**箭头形式的增强 switch（P3C/ASM 解析不了，[research.md](../research.md) D16）。

---

## 3. `ToolExecutor` —— 执行权唯一落点

```java
public class ToolExecutor {
  public ToolExecutor(ToolCatalog toolCatalog, ToolInvocationRepository repository);
  public ToolResult execute(String sessionId, AssistantMessage.ToolCall call);
}
```

**三种情形的统一处理**（FR-006/FR-007）：

| 情形 | 落 `tool_invocations` | 返回 |
| --- | --- | --- |
| `find` 查不到 | `success=false`、`error_message="工具不存在: <name>"` | `ToolResult.failure(...)` |
| 工具正常返回 | `success = result.success()`、`result_json = result.content()` | 原样返回 `result` |
| 工具抛 `RuntimeException` | `success=false`、`error_message = e.getMessage()` | `ToolResult.failure(e.getMessage())` |

**三条不变量**：

1. **每一次模型请求的工具调用都恰好产生一行审计**（成功 / 失败 / 查不到工具都算），SC-006 覆盖率 100%；
2. **每一次工具调用都恰好输出一条结构化 JSON 日志（成败都写）**——章程附加约束「可观测」要求日志覆盖**每一次** Tool 调用，只记失败不满足该条；口径与第 16 节 `ProviderService`（成功一条 INFO、失败一条 WARN）逐字对齐。**失败 MUST NOT 被静默吞掉**：原因进审计、进日志、也回灌给模型（下一轮的 tool 消息）；
3. **工具异常 MUST NOT 打断循环**：一律转成失败结果返回，不上抛。捕获类型是 `RuntimeException`——第 24 节的 `SandboxViolationException` 继承它，core 因此不需要 import 任何能力模块的异常类。

**计时口径**：`duration_ms` 是 `execute` 首尾的毫秒差，含查找工具的时间（与第 16 节 `llm_calls` 一致）。

**本节不做**：失败重试（`ToolResult.retryable` 原样透传、不消费）、入参/结果脱敏（见 [tool-invocations-table.md](tool-invocations-table.md) §4）。

**依赖说明**：直接依赖 `oryxos-storage` 的 `ToolInvocationRepository`，不另造 Recorder 接口（[research.md](../research.md) D8）。storage 是共享基础设施模块，core 依赖它不构成反向依赖。

---

## 4. `AgentService` —— 三触发源的唯一入口

```java
public class AgentService {
  public AgentService(ProfileRegistry profileRegistry, ReActLoop reActLoop, SessionService sessionService);
  public String process(Session session, String userMessage);
}
```

**算法**（FR-008/FR-009/FR-010）：

```text
Profile profile = profileRegistry.find(session.profileName()).orElseThrow(IllegalStateException)
ProfileContext.set(profile)
try {
    String reply = reActLoop.run(session, userMessage, profile)
    sessionService.save(session)
    return reply
} finally {
    ProfileContext.clear()
}
```

**行为约定**：

1. **唯一入口**：CLI、Web Service、`AgentScheduler`（钟推）三种触发源都汇入 `process`；`ReActLoop` 不感知来源（章程原则 IX）；
2. **`finally` 清上下文**：正常与异常路径都必须清（FR-010，SC-007）；
3. **持久化在循环之后**：`save` 拿到的必然是累积完的会话历史；
4. **Profile 查不到**：抛 `IllegalStateException`（消息含 Agent 名）。这是配置/数据错误，不是业务失败；不新增异常类型（章程原则 II）。

**为什么不在这里 try-catch 业务异常**：处理失败该让调用方知道（第 26 节 Web 层映射成 500/503），本类只负责「无论怎么退出都把 ThreadLocal 清干净」。

---

## 5. `ProfileContext` —— 当前 Agent 的 ThreadLocal

```java
public final class ProfileContext {
  public static void set(Profile profile);
  public static Profile current();   // 未设置时返回 null
  public static void clear();
}
```

- **只被 `AgentService` 放置与清除**；其他类只读。
- `current()` 未设置时返回 `null`（不是 `Optional`）——课件 harness 用 `assertNull(ProfileContext.current())` 逐字钉死这一条（[research.md](../research.md) D10）。
- 放在 `core.engine` 而非 `core.profile`：它是**逐请求运行态**，生命周期归 `AgentService`；`core.profile` 装的是 `Profile` / `ProfileLoader` / `ProfileRegistry` 这组静态配置。

---

## 6. `ContextLoader` —— 现读现拼，不缓存

```java
public class ContextLoader {
  public ContextLoader(Path oryxosDir);   // 工作区的 .oryxos/ 目录
  public String load(Profile profile);
}
```

**产出**：system prompt ① 里的**文件部分**（`PromptBuilder` 会在其后追加日期时间行，并在其前放 `identity.prompt`）。

| 内容 | 路径 | 缺失时 |
| --- | --- | --- |
| Bootstrap 三件套 | `<oryxosDir>/AGENTS.md`、`SOUL.md`、`USER.md` | **WARN**，跳过该文件，不阻断 |
| Profile 显式引用的 Bootstrap | `<oryxosDir>/<profile.bootstrap()[i]>`（绝对路径原样） | **抛异常**，消息**指明是哪个文件** |
| Agent 正文 | `<oryxosDir>/agents/<profile.name()>/AGENT.md` frontmatter 之后的部分 | **抛异常**（Agent 的自身定义文件缺失） |
| Skill 绑定摘要 | `<oryxosDir>/agents/<name>/skills/*` 相对软连接 | 悬空软连接、或真实目标落在 `<oryxosDir>/skills/` 之外 → **抛异常** |

**两条铁律**（FR-011/FR-012）：

1. **不缓存**：每次 `load` 都重新读文件、重新扫 `skills/` 目录，不持有任何跨调用的缓存——改完文件**下一轮**即生效（SC-008，生效延迟 = 0 轮）；
2. **不静默跳过**：显式引用缺失必须报错并指名文件；隐式默认缺失至少 WARN。「人格悄悄丢了」是最难查的软故障（课件原话）。

**Skill 只注入 name/description/本地绝对路径**，正文与附属资源**不预载**——由模型经 `read_file` / `shell` 按需取（`docs/TechnicalSolution.md` §8.3 的渐进式披露）。Skill **不是 Tool**，MUST NOT 注册进 `ToolCatalog`（章程原则 IV）。

**异常类型**：用 `java.io.UncheckedIOException`（IO 失败）与 `IllegalStateException`（内容缺失/越界），**不新增异常类型**（章程原则 II）。

**路径口径与 `ProfileLoader` 一致**：注入具体目录（`ProfileLoader` 收 `.oryxos/agents`，本类收 `.oryxos/`），不注入仓库根——两个类对「工作区长什么样」的理解必须一致，否则第 18 节 `oryxos init` 建出的目录结构会对不上。
