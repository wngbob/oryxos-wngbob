# Phase 0 Research: Provider —— 对接大模型的统一入口

本节记录实现前必须定下的技术决策。**凡涉及第三方 API 的结论，均已在本机依赖中核实过签名**（核实方法：`javap` 直接读 1.1.8 的 jar），不是照抄教程。

---

## D1. Profile 的来源：`AGENT.md` frontmatter，而非独立 YAML 目录

**Decision**: `ProfileLoader` 扫描 `.oryxos/agents/<name>/AGENT.md`，解析 `---` 包围的 frontmatter，派生出 `Profile`。不引入 `.oryxos/profiles/` 目录。

**Rationale**: 课件第 16 节说 `ProfileLoader` 扫 `.oryxos/profiles/` 下的 YAML，与技术方案 §8.2（`AgentLoader` 扫 `.oryxos/agents/`、由 `AGENT.md` frontmatter `deriveProfile`）以及 CLAUDE.md「Profile 不手写独立 YAML」冲突。按 CLAUDE.md 的仲裁规则「文档间冲突时，以最新技术方案为准」，取 frontmatter 方案。工作区目录树里本来也没有 `profiles/`。

**Alternatives considered**:
- 按课件做独立 YAML 目录 —— 被否：与 §8.2 冲突，且第 17 节引入 `AgentLoader.deriveProfile` 时本节要返工改造 loader。
- 两个来源都支持 —— 被否：两套来源的优先级与冲突规则课件和技术方案都没定义，凭空造规则违背 YAGNI。

**迁移路径**：本节的 `ProfileLoader` 负责"扫描 + 解析 + 校验"这一段；第 17 节的 `AgentLoader.deriveProfile` 复用它，不重复实现解析逻辑。

---

## D2. `Profile` 字段清单：不含 `skills`、不含 `notify_channels`

**Decision**: `Profile` 承载 `name` / `description` / `identity`（`agent_name`、`prompt`）/ `provider`（`name`、`model`、`temperature`）/ `tools` / `mcp_servers` / `channels` / `schedules` / `bootstrap` / `settings`（`max_iterations`、`max_history_turns`）。

**Rationale**: 课件要求把 `skills` 与 `notify_channels` 也建进 `Profile`。但章程原则 IV 规定 Skill 绑定的唯一真相源是 Agent 目录内的相对软连接、MUST NOT 写进 frontmatter；技术方案 §8.2 明文「`notify_channels` 不属于 Profile 或 frontmatter；通知渠道由 SQLite 全局注册表管理」。上游契约 `specs/000-runtime-kernel/contracts/agent-directory.md` §2 已把两者列为 frontmatter **禁止项**。

**Alternatives considered**: 照课件全字段建 —— 被否：与章程直接矛盾，且第 19 / 29 节大概率要删字段，属于明知会返工还先写。

---

## D3. 依赖选择：`spring-ai-deepseek`（模型实现）+ `spring-ai-model`（抽象），不用 starter

**Decision**: `oryxos-provider` 依赖 `org.springframework.ai:spring-ai-model` 与 `org.springframework.ai:spring-ai-deepseek`，版本由已导入的 `spring-ai-bom` 1.1.8 托管。**不引** `spring-ai-starter-model-deepseek`。

**Rationale（已在本地核实）**:
- `spring-ai-bom` 1.1.8 的 `<dependencyManagement>` 中确实托管了 `spring-ai-model`、`spring-ai-deepseek`、`spring-ai-starter-model-deepseek`、`spring-ai-autoconfigure-model-deepseek`（课件坑三的依赖核实项，已通过）。
- starter 的价值是自动配置，而 `spring-ai-autoconfigure-model-deepseek` 会产出**按类型注入**的 `ChatModel` Bean；本节的核心架构约束恰是"显式映射、禁止按类型扫描"（章程原则 III）。不引 starter 就从源头上不给框架留下"自动装配出类型 Bean"的机会。

**Alternatives considered**:
- 用 starter + 排除其自动配置类 —— 被否：排除配置是隐式的、容易被后人删掉，且 starter jar 成为死重量。
- 自己写 HTTP 客户端直连各家 API —— 被否：等于重造协议转换，违背章程原则 III「Spring AI 只用一半」的用意（协议转换正是允许用的那一半）。

**已核实的关键签名（`spring-ai-deepseek` 1.1.8 / `spring-ai-model` 1.1.8）**:
- `DeepSeekChatModel.builder().deepSeekApi(DeepSeekApi).defaultOptions(DeepSeekChatOptions).build()`
- `DeepSeekApi.builder().apiKey(String).baseUrl(String).build()`
- `DeepSeekChatOptions.builder().model(String).temperature(Double).build()`

---

## D4. 关闭框架自动工具执行的确切写法

**Decision**: 构造 `ToolCallingChatOptions` 时显式 `internalToolExecutionEnabled(false)`，并把它作为 `Prompt` 的 options 传给 `ChatModel.call(Prompt)`。

**Rationale（已在本地核实）**: `spring-ai-model` 1.1.8 中：
- `org.springframework.ai.model.tool.ToolCallingChatOptions` 有 `getInternalToolExecutionEnabled()` / `setInternalToolExecutionEnabled(Boolean)`，并有静态常量 `DEFAULT_TOOL_EXECUTION_ENABLED`；
- `DefaultToolCallingChatOptions.Builder` 提供 `internalToolExecutionEnabled(Boolean)` 与 `toolCallbacks(List<ToolCallback>)`；
- `ToolCallingChatOptions` 另有静态方法 `isInternalToolExecutionEnabled(ChatOptions)`，可直接用于断言。

课件示例代码里的 `request(..., autoExecuteTools=false)` 是示意写法，真实 API 即上述 `internalToolExecutionEnabled(false)`。

**Alternatives considered**: 靠"不给 `ToolCallingManager`"来间接关闭 —— 被否：不显式，读代码的人看不出意图；显式置 `false` 才是可被回归测试钉死的写法。

---

## D5. 工具适配器只翻译、不执行

**Decision**: `ToolSchemaAdapter.toToolCallbacks(List<OryxTool>)` 返回 `List<ToolCallback>`，其中每个 `ToolCallback` 的 `getToolDefinition()` 由 `OryxTool` 的 name / description / inputSchema 一一对应构造（`DefaultToolDefinition.builder().name().description().inputSchema().build()`）；其 `call(String)` 实现**直接抛出 `UnsupportedOperationException`**，不做任何执行。

**Rationale**: 课件要求"只翻译、产物里不含任何执行逻辑"。`ToolCallback` 接口强制要求实现 `call(String)`，用一个明确抛错的实现来占位，既满足接口，又把"本模块不执行工具"这条约束变成**代码里看得见、测试里断言得到**的事实（`ToolSchemaAdapterTest` 可直接断言调用 `call()` 抛错）。

**已核实签名**: `org.springframework.ai.tool.ToolCallback`（`getToolDefinition()` / `call(String)`）、`org.springframework.ai.tool.definition.ToolDefinition`（`name()` / `description()` / `inputSchema()`）、`DefaultToolDefinition.builder()`。

**Alternatives considered**: 让 `call()` 返回空串 —— 被否：静默吞掉反而掩盖问题；抛错更诚实。

---

## D6. `llm_calls` 表补 `success` / `error_message` 两列

**Decision**: 修改 `oryxos-storage` 的 `LlmCall` 实体与 `db/schema.sql`，补上 `success`（布尔）与 `error_message`（文本）两列。

**Rationale**: 骨架期已落的 `llm_calls` 只有用量与耗时，缺成功标识与失败原因。课件明确指出：模型调用失败时若没有这两列，"这次事故在数据库里完全没有痕迹"，与 `tool_invocations` 表不对称。章程原则 VI 要求审计 Day One 落库、成败都写。

**Alternatives considered**: 只记成功 —— 被否：直接违背原则 VI 与 FR-008/009。

**注意**: 建表仍走手工脚本（`ddl-auto=none`），不用 `update` 自动迁移（章程附加约束）。

---

## D7. 审计协作件做成接口 `LlmCallRecorder`

**Decision**: `ProviderService` 依赖一个 `LlmCallRecorder` 接口（方法形如 `record(sessionId, provider, model, usage, success, errorMessage, durationMs)`），生产实现 `JpaLlmCallRecorder` 包装既有的 `LlmCallRepository`。

**Rationale**: 课件的关键回归测试之一要验证"失败也落审计"，做法是 mock 掉审计协作件、断言 `record(...)` 收到 `success=false` 与失败原因。要 mock 就得是接口。课件把"一个写审计日志的"明确列为本节配角。

**Alternatives considered**: `ProviderService` 直接用 `LlmCallRepository` —— 被否：`record(...)` 的语义（把 `Usage` 摊平、决定成功标识）需要一个收口点，直接调 repository 会把这层逻辑散进 `chat()` 方法。

---

## D8. 测试策略：mock `ChatModel` 做单测，真 SQLite 做建表验证

**Decision**:
- `ProviderServiceTest` / `ToolSchemaAdapterTest`：把 `ChatModel` mock 掉，不联网、不花钱、秒级跑完。
- `ProfileLoaderTest`：在临时目录里造 Agent 目录与 `AGENT.md`（含一个坏文件），验证解析、校验与容错。
- `LlmCallRepositoryTest`：用**真实 SQLite 临时库**，先执行手工建表脚本 `db/schema.sql` 再存取，验证 `success` / `error_message` 两列真实存在。因此需要 `@AutoConfigureTestDatabase(replace = NONE)` 关掉 Spring 的嵌入式库替换，并显式指向临时 SQLite 文件。
- `ProviderSmokeIT`：`@Tag("integration")`，读环境变量真 key 真调一次，断言拿到非空响应且 `llm_calls` 多了一条成功记录。CI 默认跳过。

**Rationale**: 课件明确规定分层标准就是"要不要碰真实网络"。`LlmCallRepositoryTest` 坚持走手工脚本而非让 Hibernate 建表，否则测试绿了、生产跑真脚本时列名对不上。

**Alternatives considered**: 用 H2 内存库跑 `LlmCallRepositoryTest` —— 被否：H2 与 SQLite 的列类型/方言不同，验不到"手工脚本建出的表能存能读"这件事本身。

---

## D9. 配置分两层，凭证只走环境变量

**Decision**: 全局层在 `application.yaml` 声明 `oryxos.providers`（每项含 `name` 与 `api-key: ${XXX_API_KEY}`）；Profile 层（`AGENT.md` frontmatter 的 `provider` 段）只声明用哪个 name、哪个 model、什么温度。Profile 点名的 name 不在全局层清单中时直接报错。

**Rationale**: 课件明确的两层划分——全局层管"连不连得上"，Profile 层管"这个 Agent 怎么用"。章程原则 VIII 要求凭证经环境变量注入、MUST NOT 明文写死在 `AGENT.md` frontmatter。因此 `${ENV}` 占位的解析责任落在配置加载层；`ProfileLoader` 对 frontmatter 中出现的 `${ENV}` 占位做同样的通用解析（上游契约 FR-012 允许 frontmatter 用 `${ENV_VAR}` 占位以避免明文）。

**Alternatives considered**: 把 api-key 写进 Profile —— 被否：违反原则 VIII，且会让"换模型只改配置"变成"改配置还得管密钥"。

---

## D10. 厂商名唯一性

**Decision**: 全局层 `oryxos.providers` 中同一 `name` 出现多次时，启动加载期即报错并指明重复的名字。

**Rationale**: 由 FR-005「显式映射不得有歧义」推导——映射表若被后一项覆盖，等于映射关系不可见，与"写死、看得见"的原则相悖。

---

## 未决项

无。Technical Context 中不存在 `NEEDS CLARIFICATION`。
