# Phase 0 Research: ReAct 循环 —— Agent 的「想—做—看」主循环

**输入**：[spec.md](spec.md)（含 3 条冲突仲裁记录 + 2 条 clarify 结论）、`docs/TechnicalSolution.md` §4/§8.3/§9.2、课件 `docs/class/第17节：ReAct 原理解析、实现与代码讲解.md`、前序节已交付代码。

**本文件的作用**：把 spec 里已裁决的架构方向，落成**具体到签名与文件路径**的技术决策，供 Phase 1 的 data-model / contracts 直接引用。凡涉及第三方 API 的决策，均在本地 `spring-ai-model` 1.1.8 jar 上用 `javap` 核实过（见 D17）。

---

## D1. core 侧四个接缝接口：形态与放置

**决策**：`oryxos-core` 新增四个接口，本节只定义与调用、不提供实现：

| 接口 | 包 | 方法 | 后续实现方 |
| --- | --- | --- | --- |
| `LlmClient` | `io.oryxlabs.oryxos.core.llm` | `ChatResponse chat(String sessionId, Profile profile, Prompt prompt)` | `oryxos-provider` 的 `ProviderService` |
| `ToolCatalog` | `io.oryxlabs.oryxos.core.tool` | `Optional<OryxTool> find(String name)`；`List<OryxTool> availableFor(Profile profile)` | `oryxos-tool` 的 `ToolRegistry`（第 20 节） |
| `MemorySource` | `io.oryxlabs.oryxos.core.memory` | `Optional<String> longTermMemory(Profile profile)` | `oryxos-memory` 的 `MemoryService`（第 22 节） |
| `SessionService` | `io.oryxlabs.oryxos.core.session` | `void save(Session session)` | 第 18 节（`sessions` 表 + Repository） |

**理由**：章程附加约束「契约在 core、实现在能力模块，`oryxos-core` MUST NOT 反向依赖任何能力模块」是硬约束；`specs/000-runtime-kernel/plan.md` 已把「core 消费能力模块服务 = 接口放 core + 实现放能力模块 + Spring 注入」定为通用模式，`ToolCatalog` 是它已点名的契约。课件骨架里 `ReActLoop` 直接调 `providerService.chat(...)` 的写法会让 core 反向依赖 provider，故按 spec「冲突仲裁记录 1」改写为面向接口。

**备选方案**：
- *把 `ProviderService` 等能力类下沉到 core*：否决。core 会因此绑定具体厂商实现，且 `oryxos-provider` 将失去存在意义。
- *用 Spring 的 `ObjectProvider` 延迟取 Bean*：否决。仍是按类型从容器里捞，与章程原则 III 禁止的类型扫描同性质，且 core 编译期仍需认识实现类型。
- *本节连接口一起省掉，先写死具体类*：否决。spec 已裁决「契约先行」，且第 18/20/22 节都会消费同一批接缝。

**边界**：接口方法**只建本节调用得到的**。`SessionService` 只声明 `save`——`load` / `create` 是第 18 节的需求，现在写就是章程原则 II 禁止的凭空抽象。

---

## D2. `Session` 与 `SessionMessage`：core 内存态领域类型

**决策**：`oryxos-core` 新增 `io.oryxlabs.oryxos.core.session.Session`（可变类）与 `SessionMessage`（record）：

```java
public record SessionMessage(
    Role role, String content, List<AssistantMessage.ToolCall> toolCalls,
    String toolCallId, Instant timestamp) {
  public enum Role { SYSTEM, USER, ASSISTANT, TOOL }
}

public final class Session {
  public static Session create(String id, String profileName);
  public String id();                 public String profileName();
  public List<SessionMessage> messages();
  public Instant createdAt();         public Instant lastActiveAt();
  public SessionMessage appendUserMessage(String text);
  public SessionMessage appendAssistantMessage(String text, List<AssistantMessage.ToolCall> toolCalls);
  public SessionMessage appendToolResult(String toolCallId, ToolResult result);
}
```

**理由**：字段口径逐条对齐 `specs/000-runtime-kernel/data-model.md` A1 的 `messages_json` 元素 `{role, content, toolCalls?, toolCallId?, timestamp}`——第 18 节要把它序列化进 `sessions` 表，现在对齐，第 18 节就只需加序列化器，不用改领域类型（这正是 spec「冲突仲裁记录 2」的用意）。三种追加操作对应 FR-003 的「模型响应与工具结果按序累积」，也是课件骨架 `session.append(resp)` / `session.appendToolResult(result)` 的落地形态。

**备选方案**：
- *直接用 Spring AI 的 `Message` 四件套当会话历史*：否决。Spring AI 的消息没有时间戳，A1 的元素形状表达不出来；且第 18 节序列化 `messages_json` 时要在框架类型上做映射，领域模型会绑死在框架上。
- *把 `ToolResult` 整个塞进消息*：否决。A1 元素没有 `success` / `retryable` 字段。失败信息按 D9 写进 `content`，成败与否由审计表 `tool_invocations` 负责——两处职责不重叠。

**为什么不给 `Session` 加同步**：一次处理独占一个虚拟线程（章程原则 I 的同步阻塞模型），会话不会跨线程共享。加锁是无需求支撑的复杂度。

**`Role.SYSTEM` 保留但本节不写入**：A1 允许 `role ∈ system/user/assistant/tool`，故枚举建全；但 system prompt 是**每轮现拼**的（FR-004），不属于会话历史，本节任何路径都不会追加 SYSTEM 消息。保留枚举值是为了第 18 节的序列化口径不用改。

---

## D3. tool 消息重建 `ToolResponseMessage` 时的工具名反查

**决策**：`SessionMessage` 的 tool 分支**只存 `toolCallId` + `content`**（严格照 A1）。`PromptBuilder` 把历史转成 Spring AI 消息时，边遍历边维护 `Map<toolCallId, toolName>`（来源是同一段历史里 assistant 消息的 `toolCalls`），用它在构造 `ToolResponse(id, name, responseData)` 时补上工具名。

**理由**：Spring AI 的 `ToolResponse` 是三元组 `(id, name, responseData)`，`name` 不可省（已在 jar 上核实，见 D17）。而 A1 的元素形状里**没有** `toolName` 字段——往 `SessionMessage` 上加一个 A1 没有的字段，等于让第 18 节的表结构跟着本节走，属于对已定数据形状的单方面变更。反查代价是 4 行，且**必然命中**：FR-005 保证截断只切在用户消息边界上，assistant 工具请求与对应 tool 结果永远同段出现，不会出现「有结果、没请求」的悬空片段。

**备选方案**：
- *给 `SessionMessage` 加 `toolName`*：否决（理由见上，改动 A1 已定形状）。
- *`ToolResponse.name` 传空串*：否决。OpenAI 兼容协议里 `name` 虽非必填，但把它填成空串会丢失可读性，也让「哪个工具返回的」在 prompt 里消失。

---

## D4. 四部分怎么落到 Spring AI 的 `Prompt`

**决策**：`PromptBuilder.build(Session, Profile)` 返回的 `Prompt` 由两部分承载四段内容：

| spec FR-004 的四部分 | 落到 `Prompt` 的哪里 |
| --- | --- |
| ① system prompt（人格/指令 + 启动文件 + 末尾日期时间） | `getInstructions()` 的第 1 条 `SystemMessage` |
| ② 长期记忆（未启用则跳过） | 第 2 条 `SystemMessage`——**仅在非空时出现** |
| ③ 会话历史（截断后） | 其后按序排列的 `UserMessage` / `AssistantMessage` / `ToolResponseMessage` |
| ④ 当前可用工具列表 | `getOptions()`（`ToolCallingChatOptions`）的 `toolCallbacks` |

**理由**：第 16 节的 `ProviderService.request()` 已明确「工具说明由调用方随 `Prompt` 的 options 携带，本服务只负责原样转发」（该文件第 38-40 行注释 + `contracts/provider-service-api.md` §1 行为约定 2）。四部分必须能在这一个 `Prompt` 对象上**可断言地**看出顺序，否则课件 harness 的「四部分顺序正确」无从判定。②③ 分别用独立 `SystemMessage` 与对话消息表达，是 Spring AI 里语义最直白的映射，不需要额外约定。

**备选方案**：
- *把 ② 长期记忆拼进 ① 的同一段文本*：否决。四部分顺序就退化成「一段文本里的隐含顺序」，测试只能靠字符串包含关系判，弱得多；且 FR-004 把长期记忆与会话历史列为两个独立部分。
- *把 ④ 也写进文本*：否决。见 D7——模型必须走原生 Function Calling，工具得是 `toolCallbacks`。

---

## D5. 轮数判断在循环头 + 最后一轮工具照常执行

**决策**：循环写成 `for (int i = 0; i < maxIterations; i++) { 调模型 → 累积 → 无工具调用则收尾 → 逐个执行工具 }`，**轮数判断在循环头**。转满后返回私有常量 `"达到最大轮数，已停止"`。

**理由**：这是 spec clarify 第 2 题的裁决（答 A）。循环头判断意味着第 N 轮的模型响应照常累积、该轮提出的工具**照常执行**，会话历史以「工具请求 + 工具结果」成对收尾，不留悬空请求（FR-002）。若把判断挪到循环尾，第 10 轮的工具请求会落进历史却没有对应结果——正好是 FR-005 明令禁止的悬空片段。

**备选方案**：*判断放循环尾、到顶就不再执行工具*：否决。会在会话历史里留下悬空的工具请求，下一轮（第 18 节 CLI 的后续对话）把它发给模型时，模型会看到「要了工具但没结果」，行为不可预期。

**"达到最大轮数，已停止" 是字面量**：课件 harness 断言 `reply.contains("达到最大轮数")`，故措辞逐字保真，且**私有**——它不需要成为对外常量（FR-002 只约束答复文本，不约束类型）。

---

## D6. 历史截断：以用户消息边界切

**决策**：`PromptBuilder` 保留**最后 N 个用户往返**：从历史末尾向前数到第 N 条用户消息，从**那条用户消息**起截到末尾。不足 N 轮则不截断。

**理由**：spec clarify 第 1 题的裁决（答 A）把「一轮」定义为「一条用户消息 + 它引发的全部 assistant / tool 消息（含工具往返）」。按用户消息边界切，天然满足两条硬约束：截断后的片段**必然以用户消息开头**（SC-005：从不以工具消息开头）、assistant 的工具请求与对应结果**必然同段**（FR-005：无悬空片段）。「恰好 N 轮不截断」是边界值，用 `>` 而非 `>=` 判断。

**备选方案**：
- *按消息条数截断（保留最近 K 条消息）*：否决。会把一条 assistant 的工具请求和它的 tool 结果切散，产生悬空片段。
- *按 token 数截断*：否决。核心阶段明确「上下文压缩留到扩展阶段」（课件「有几样先别做」），token 估算要引依赖、要维护模型侧口径，是没有需求支撑的复杂度。

---

## D7. `OryxTool` → `ToolCallback` 的翻译：core 内私有实现，接受一处小重复

**决策**：`PromptBuilder` 用**私有静态方法**把 `List<OryxTool>` 翻成 `List<ToolCallback>`（`DefaultToolDefinition.builder()` + 一个只带说明、`call()` 直接抛 `UnsupportedOperationException` 的 record 实现），**不新增任何 public 类型**。

**理由**：翻译动作 `OryxTool.getName/getDescription/getInputSchema` → `ToolDefinition` 是纯函数，但**已有一份**实现——第 16 节的 `oryxos-provider.ToolSchemaAdapter`。`oryxos-core` MUST NOT 依赖 `oryxos-provider`（章程附加约束），所以 core 用不到它。三条出路里：
- 把 `ToolSchemaAdapter` 挪到 core —— 改动前序节交付的公共接口（软门禁 ④），且它被 `ProviderService` 持有，改动面不止一处；
- 让 core 的接缝直接暴露 `ToolCallback` 列表 —— 重复只是换个模块发生（`oryxos-tool` 同样看不到 provider 的实现）；
- **core 内私有翻译**（本决策）——重复被压到 8 行、不可对外见、不引入新概念。

选第三条，并把重复**记在这里**：第 20 节 `ToolRegistry` 落地后，工具说明翻译的自然归属是 `oryxos-tool`（它拥有 `OryxTool` 的全部来源），届时可把翻译收口到 `ToolCatalog` 契约的一个方法上，core 侧这份私有实现随之删除。现在不提前做——那是章程原则 II 禁止的凭空抽象。

**顺带确认「只翻译不执行」这条纪律在 core 侧同样成立**：产物 `ToolCallback.call(String)` 抛 `UnsupportedOperationException`，与第 16 节同一口径。执行权唯一落在 `ToolExecutor`（章程原则 III）。

---

## D8. `ToolExecutor` 直接依赖 `ToolInvocationRepository`，不再造一层 Recorder

**决策**：`ToolExecutor` 的构造参数是 `ToolCatalog` + `oryxos-storage` 的 `ToolInvocationRepository`，直接 `new ToolInvocation()` 逐列 set 后 `repository.save(...)`。**不新增** `ToolInvocationRecorder` 接口。

**理由**：第 16 节造 `LlmCallRecorder` 接口，是因为 `Usage` 是 Spring AI 类型、需要一层摊平，且 `ProviderServiceTest` 要断言「失败也落审计」的参数。`ToolInvocation` 这一侧没有这层需要——实体字段与调用点一一对应，测试直接 mock `ToolInvocationRepository` 并用 `ArgumentCaptor` 断言实体即可。再造一个接口，是只有一层实现的空壳（章程原则 II）。

**依赖方向不因此破例**：`specs/000-runtime-kernel/plan.md` 已把 `oryxos-storage` 定为**共享基础设施模块**（U4），core 与各能力模块可直接依赖其实体与 Repository，不构成反向依赖。`ToolInvocation` 实体的无参构造注释本身就写着「供写入方（第 17 节的工具执行审计，在 `oryxos-core`）跨包构造后逐列 set」——本节正是它的预期调用方。已核实 `oryxos-storage` 不依赖 `oryxos-core`，加此依赖不成环。

---

## D9. 工具失败：捕获 `RuntimeException` → 落审计 + 返回失败 `ToolResult`

**决策**：

| 情形 | 审计 | 返回给循环 |
| --- | --- | --- |
| `ToolCatalog.find` 查不到 | `success=false`，`error_message` 写明工具名 | `ToolResult.failure("工具不存在: <name>")` |
| 工具正常返回 | `success = result.success()`，`result_json = result.content()` | 原样返回 |
| 工具抛 `RuntimeException` | `success=false`，`error_message = e.getMessage()` | `ToolResult.failure(e.getMessage())` |

三种情形都**不上抛**，循环继续。

**理由**：FR-007 要求「失败原因落审计并回灌给模型」「异常不打断整个循环」——回灌只能通过返回一个失败结果实现，上抛就回灌不了。捕获类型选 `RuntimeException` 而非 `Exception`：第 24 节的 `SandboxViolationException` 继承 `RuntimeException`，而 `specs/000-runtime-kernel` T033 要求「Sandbox 拒绝按通用异常捕获，core MUST NOT import `oryxos-tool` 的异常类」——捕 `RuntimeException` 正好一条规则覆盖两条来源，不需要 import 任何能力模块的异常。`Error` 不捕（JVM 级故障不该被伪装成工具失败）。

**为什么「不吞」和「不上抛」不矛盾**：吞掉 = 既不记录也不让上层知道；这里失败原因进了 `tool_invocations.error_message`、进了结构化日志、也进了模型的下一轮上下文——三处留痕。这正是 `specs/000-runtime-kernel` T033 说的「复用既有失败审计路径」。

**重试不在本节范围**：`ToolResult` 已有 `retryable` 标记，但「按指数退避最多重试 3 次」是 `000-runtime-kernel` T033 的内容，本节 spec 的 Assumptions 已把它列为不做。`retryable` 原样透传、不消费。

---

## D10. `ProfileContext`：ThreadLocal，`current()` 未设置时返回 `null`

**决策**：`io.oryxlabs.oryxos.core.engine.ProfileContext`，静态 `set(Profile)` / `Profile current()` / `clear()`；`current()` 在未设置时返回 `null`。

**理由**：课件 harness 的第二个关键回归测试逐字写着 `assertNull(ProfileContext.current())`。返回 `Optional` 会让这条断言无法原样落地（要改成 `assertTrue(...isEmpty())`），而本节 DoD 要求「课件写出代码的关键回归测试原样落地、断言逻辑逐条保真」。ThreadLocal 访问器「未设置即 null」本身也是 JDK 的既有语义（`ThreadLocal.get()`），不构成额外认知负担。

**为什么必须是 ThreadLocal**：`OryxTool.execute` 的签名不带 Profile（章程原则 V / 课件），但第 19 节的 `notify` 要读当前 Agent 的配置。虚拟线程每请求独占一个线程，ThreadLocal 天然不串号；代价是**必须**在出口清除——否则下一个复用该线程的请求会读到上一个请求的 Profile（SC-007）。清理由放 `finally`（FR-010）。

**放置位置的取舍**：`ProfileContext` 是**运行态**，生命周期归 `AgentService`；`core.profile` 包目前装的是 `Profile` / `ProfileLoader` / `ProfileRegistry` 这组**静态配置**，把逐请求状态混进去会糊掉包的语义。故放 `core.engine`（与 `AgentService` 同包）。

---

## D11. `ContextLoader` 的输入与路径口径

**决策**：`ContextLoader(Path oryxosDir)`，`oryxosDir` 即工作区的 `.oryxos/` 目录（口径与 `ProfileLoader(Path agentsDir)` 一致——注入具体目录，不注入仓库根）。`String load(Profile profile)` 现读现拼，产出 system prompt ① 里的**文件部分**：

| 内容 | 路径 | 缺失时 |
| --- | --- | --- |
| Bootstrap 三件套 | `<oryxosDir>/AGENTS.md`、`SOUL.md`、`USER.md` | **WARN**（隐式默认，缺失不阻断） |
| Profile 显式引用的 Bootstrap | `<oryxosDir>/<bootstrap[i]>`（绝对路径原样） | **报错并指明是哪个文件** |
| Agent 正文 | `<oryxosDir>/agents/<profile.name()>/AGENT.md` 的 frontmatter 之后部分 | 报错 |
| Skill 绑定摘要 | `<oryxosDir>/agents/<name>/skills/*` 相对软连接 → 解析真实目标，必须落在 `<oryxosDir>/skills/` 内；只注入 SKILL.md frontmatter 的 `name` / `description` 与 Agent 本地绝对读取路径 | 悬空软连接或目标越出公共 Skill 根 → **报错** |

**理由**：FR-011/FR-012 的落点；Skill 只注入 name/description/路径、正文不预载，是 `docs/TechnicalSolution.md` §8.3 的调整说明（渐进式披露）。路径口径照搬 `ProfileLoader` 的既有做法，两个类对「工作区长什么样」的理解必须一致，否则第 18 节 `oryxos init` 建出的目录结构会对不上。

**为什么「显式引用报错、隐式默认 WARN」**：显式引用是业务方的明确意图，静默跳过等于业务方以为配上了、其实没生效；三件套是底座给的默认，业务方没配过，缺了就缺了——但**必须留下 WARN**，因为「人格悄悄丢了」是最难查的软故障（课件原话）。

**无缓存**：每次 `load` 都重新 `Files.readString` + 重新扫软连接目录，不持有任何 `Map` 缓存、不用 `Files.walk` 的缓存型遍历。SC-008 要求「生效延迟 = 0 轮」。

**日期时间不归它管**：`PromptBuilder` 在 system prompt 末尾追加当前日期时间（课件把「末尾附当前日期时间」写在 `PromptBuilder` 那一段），`ContextLoader` 只负责读文件。

---

## D12. 当前日期时间：直接用 `LocalDateTime.now()`，不注入 `Clock`

**决策**：`PromptBuilder` 在 system prompt 末尾追加一行当前日期时间（`LocalDateTime.now()`），不引入 `Clock` 构造参数。

**理由**：为可测试性注入 `Clock` 是常见做法，但本节只要求断言「system prompt 末尾含当前日期时间」，用 `LocalDate.now().toString()` 做包含判断即可判定，且**不会因为注入点而多出一个构造参数**。多一个参数就多一份装配负担，第 18 节 CLI 装配时要多传一次——没有被证实的需求之前不加（章程原则 II）。跨零点竞态对断言无影响（断言的是「含今天」，不是「等于某一时刻」）。

---

## D13. 测试策略：5 个单测类，零网络、零真实凭证

**决策**：严格照课件「四、验收 harness」的五个类，全部用替身与 JUnit 临时目录：

| 测试类 | 模块 | 替身/夹具 | 覆盖的关键回归点 |
| --- | --- | --- | --- |
| `ReActLoopTest` | `oryxos-core` | `LlmClient` 替身 + `ToolExecutor` 替身 + `PromptBuilder` 替身 | 无工具调用一轮收尾；有工具调用则执行并回填进下一轮；**转满最大轮数强制停**（恰好 10 次、一轮不多、答复含「达到最大轮数」）；每轮响应与工具结果按序累积 |
| `PromptBuilderTest` | `oryxos-core` | `MemorySource` / `ToolCatalog` 替身 + 临时目录 | 四部分顺序；历史超 N 轮被截断且不以工具消息开头；system prompt 末尾含当前日期时间 |
| `ToolExecutorTest` | `oryxos-core` | `ToolCatalog` 替身 + mock `ToolInvocationRepository` | 成功写 `success=true`；失败也写 `success=false` 带原因；异常不吞（返回失败结果而非上抛） |
| `AgentServiceTest` | `oryxos-core` | `ProfileRegistry` / `ReActLoop` / `SessionService` 替身 | 处理期间 `ProfileContext` 可取到当前 Profile；**抛异常时 finally 也清掉**；结束后 Session 被持久化 |
| `ContextLoaderTest` | `oryxos-core` | JUnit `@TempDir` 造 `.oryxos/` 目录树 | 改文件后下一次 load 立即读到新内容（无缓存）；Skill 引用缺失报错；Bootstrap 缺失 WARN |

**理由**：本节全部逻辑都在 `oryxos-core`，五个类同模块，`mvn test` 秒级跑完（SC-009）。**本节没有集成冒烟**——不碰网络、没有真实凭证可消耗，端到端证据是课件「五、做完怎么验」的人工项（真模型跑通 Demo 一对话版），它要等第 18 节 CLI 与第 20 节 `http_get` 就位，spec 的 Assumptions 已把这条记明。

**方法名英文、课件原文进 `@DisplayName`**：课件 harness 给的是中文方法名（`模型一直要调工具_转满最大轮数强制停`），按项目纪律译成语义等价的英文名落地，`@DisplayName` 保留课件原文以便对号；断言逻辑逐条保真（`verify(providerService, times(10))` 的位置换成 `verify(llmClient, times(10))`，断言的**条数与语义**不变）。

**日志断言**：`ContextLoaderTest` 的「Bootstrap 缺失至少 WARN」需要断言日志。做法是把 `ContextLoader` 的日志输出接进内存 appender（Logback `ListAppender`）后断言 WARN 条数，不引入新依赖（`logback-classic` 已由 `spring-boot-starter` 传递带入）。

---

## D14. 本节**不**给 `ProviderService` 加 `implements LlmClient`

**决策**：本节只定义 `LlmClient`，`ProviderService` 保持原样（第 16 节交付的公共类不动）。装配留到第 18 节 CLI 接线时一并做。

**理由**：spec 的 Assumptions 明写「本节只定义与调用，**不实现**；测试一律用替身」——这是本轮刚获批准的裁决。给 `ProviderService` 加 `implements` 属于改动前序节交付的公共接口，按软门禁 ④ 默认不做。

**这里有一处需要向用户说明的出入**：第 16 节的 `contracts/provider-service-api.md` §5 写的是「第 17 节只需让 `ProviderService` 增加一个 `implements`」。本节按 spec（更新、且经本轮批准）顺延到第 18 节。**影响面为零**：`ProviderService.chat` 的签名与 §1 契约逐字一致，加 `implements` 是一行、不改方法体、不构成返工；本节代码与测试全部面向 `LlmClient` 接口编程，接线时不需要动本节任何一行。此项已提请用户在 Step 5 的固定停点确认。

**代价（如实记录）**：本节交付后，`LlmClient` 在**生产装配**里暂时没有实现方——`oryxos-boot` 的 Spring 上下文不会构造 `ReActLoop`。这是 spec 认可的形态（本节无独立入口、端到端 Demo 是人工项），且不引入任何死代码：`ReActLoop` / `PromptBuilder` / `ToolExecutor` / `AgentService` 全部被单测覆盖，`LlmClient` 是它们公开的构造依赖。

---

## D15. 依赖变更清单

| 模块 | 变更 | 理由 |
| --- | --- | --- |
| `oryxos-core` | **新增** `spring-ai-model`（版本由根 pom 的 `spring-ai-bom` 托管） | spec「冲突仲裁记录 3」：第 16 节已把 `chat(sessionId, Profile, Prompt)` 钉成字面量，core 必须能构造 `Prompt`、读 `ChatResponse`。是**依赖类型**、不是依赖模块，不违反依赖方向 |
| `oryxos-core` | **新增** `oryxos-storage` | D8：`ToolExecutor` 要写 `tool_invocations`。storage 是共享基础设施模块（`000-runtime-kernel` plan U4），已核实它不依赖 core，不成环 |
| 其余模块 | **不变** | 本节不新增第三方依赖，不改任何模块的既有依赖 |

`oryxos-core` 的 `spring-boot-starter-test` 已在（第 16 节补），`snakeyaml` 已在（`ContextLoader` 解析 SKILL.md frontmatter 要用），不需要新增测试依赖。

---

## D16. 语法禁区

**决策**：不写 Java 18+ 的语法形态，特别是**增强 switch 的 `default ->` 箭头形式**。

**理由**：门禁链上的阿里 P3C（PMD 6.55）用 ASM 做类型解析，父 pom 已把 ASM 覆盖到 9.7.1 以读 Java 21 字节码，但语法形态仍有限制；静态检查是构建门禁，写了就得返工。`PromptBuilder` 把 `SessionMessage.Role` 映射成 Spring AI 消息类型时最容易顺手写成箭头 switch——**用传统 `switch` + `case ...: ... break;`**，或改用 if/else 链。

---

## D17. 本地依赖核实记录（写前门禁 H3）

全部在 `~/.m2/repository/org/springframework/ai/spring-ai-model/1.1.8/spring-ai-model-1.1.8.jar` 上用 `javap` 核实，本节要用到的每一个第三方成员都存在：

| 类型 | 核实到的成员 | 本节用途 |
| --- | --- | --- |
| `ChatResponse` | `getResult()`、`hasToolCalls()` | 取本轮输出、判断是否收尾 |
| `Generation` | `getOutput()` → `AssistantMessage` | 取模型输出消息 |
| `AssistantMessage` | `getText()`（继承 `AbstractMessage`）、`getToolCalls()`、`hasToolCalls()`、`builder().content().toolCalls().build()` | 读文本与工具请求 |
| `AssistantMessage.ToolCall` | record `(id, type, name, arguments)` | 工具请求的四个字段 |
| `ToolResponseMessage` | `builder().responses(List<ToolResponse>).build()` | 重建 tool 角色消息 |
| `ToolResponseMessage.ToolResponse` | record `(id, name, responseData)` | 同上（D3 的 `name` 反查由此而来） |
| `Prompt` | `Prompt(List<Message>)`、`Prompt(List<Message>, ChatOptions)`、`getInstructions()`、`getOptions()` | 组装四部分 |
| `DefaultToolCallingChatOptions` | `builder().toolCallbacks(List<ToolCallback>).build()` | 承载 ④ 工具列表 |
| `ToolCallback` / `ToolDefinition` | `getToolDefinition()`、`call(String)`；`DefaultToolDefinition.builder().name().description().inputSchema().build()` | D7 的私有翻译 |
| `MessageType` | `USER` / `ASSISTANT` / `SYSTEM` / `TOOL` | 角色映射的对照 |
| `UserMessage(String)` / `SystemMessage(String)` | 单参构造 | 重建历史与 system prompt |

**未核实到、因此不用的**：`org.springframework.ai.content.Content` 不在 `spring-ai-model` 里（在 `spring-ai-commons`）——本节不需要引用它，`getText()` 经 `AbstractMessage` 已可直接调用。

---

## 未决项

- **`LlmClient` 的生产实现方**（D14）：本节不接，第 18 节装配时给 `ProviderService` 加 `implements`。已在 plan 的「已知接缝」节登记。
- **工具说明翻译的单一收口点**（D7）：第 20 节 `ToolRegistry` 落地后，可把翻译收口进 `ToolCatalog` 契约，届时删除 core 侧的私有实现。
- **`SessionService` 的完整形态**（D1）：本节只有 `save`；`load` / `create` / 会话标识生成规则（`channel:user:profile`）是第 18 节的内容。
- **审计入参/结果的敏感字段脱敏**：不在本节范围（三处权威文档均未提出），第 18 节或统一脱敏单点落地时回改写入路径。已记入 spec 的 Assumptions。
- **工具失败重试**：不在本节范围（D9）。
