# Specification Quality Checklist: ReAct 循环 —— Agent 的「想—做—看」主循环

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-10-13
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs)
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders
- [x] All mandatory sections completed

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain
- [x] Requirements are testable and unambiguous
- [x] Success criteria are measurable
- [x] Success criteria are technology-agnostic (no implementation details)
- [x] All acceptance scenarios are defined
- [x] Edge cases are identified
- [x] Scope is clearly bounded
- [x] Dependencies and assumptions identified

## Feature Readiness

- [x] All functional requirements have clear acceptance criteria
- [x] User scenarios cover primary flows
- [x] Feature meets measurable outcomes defined in Success Criteria
- [x] No implementation details leak into specification

## Notes

- Items marked incomplete require spec updates before `/speckit-clarify` or `/speckit-plan`

### 校验记录（2026-10-13）

- **实现细节**：正文（User Scenarios / Requirements / Success Criteria）未出现类名、方法签名、框架 API。仅三处提及具体标识，均属必要且非设计泄漏：
  - `http_get`——课件用户场景里的工具名，是场景本身的一部分。
  - 冲突仲裁记录与 Assumptions 里的 `LlmClient` / `ToolCatalog` / `MemorySource` / `SessionService` / `spring-ai-model`——记录**已报批的架构裁决**与**外部依赖前提**，是假设而非设计（与 001 spec 记录 BOM 依赖可用性的做法同口径）。
  - 「达到最大轮数」——课件 harness 明确断言的字面量，属验收口径，必须逐字固定。
- **技术无关性**：SC-001~SC-009 全部以「恰好调用 1 次 / 一轮不多 / 覆盖率 100% / 痕迹为 0 / 生效延迟 = 0 轮」这类可度量结果表述，未绑定任何技术栈。
- **无澄清标记**：本规约无 `[NEEDS CLARIFICATION]`。三处冲突（core 跨界接缝、`Session` 归属、core 新增 `spring-ai-model`）已通过软门禁向用户报批并裁决，记入 spec 的「冲突仲裁记录」与「Clarifications」；工具失败语义（不静默吞、不打断循环）已依技术方案 §4.2「失败时返回错误信息」与 `specs/000-runtime-kernel/tasks.md` T033「按通用异常捕获、复用既有失败审计路径」裁定，不留待 clarify。
- **clarify 轮次（2026-10-13）**：`/speckit-clarify` 共问 2 题、答 2 题，均已落入 spec 的「Clarifications」并同步到对应章节：
  1. 「一轮」的口径 → 一轮 = 一条用户消息 + 它引发的全部 assistant/tool 消息，截断只切用户消息边界（落到 FR-005、SC-005、Edge Cases）。
  2. 转满最大轮数时最后一轮的工具是否执行 → 执行，轮数判断在循环头，历史成对收尾（落到 FR-002、SC-003、Edge Cases）。
- **覆盖度**：分类扫描中 Functional Scope / Interaction Flow / Non-Functional / Integration / Constraints / Terminology / Completion Signals 均为 Clear；Domain & Data Model 与 Edge Cases 为 Partial，已由上述第 1 题解决。Deferred 1 项：审计脱敏与工具重试策略均不在本节范围（三处权威文档均未提出），已记入 Assumptions 而非留作歧义。
- **测试可判定性**：4 个 user story 的 Independent Test 均可由单测直接判定，一一对应课件「四、验收 harness」的 5 个测试类（US-2 覆盖 `PromptBuilderTest` + `ContextLoaderTest`）。全部不碰网络，`mvn test` 秒级跑完。
- **与课件的偏差（已在 spec 内显式记录，非遗漏）**：
  - 课件骨架的 `session.append(resp)` / `resp.hasToolCalls()` 用自造 `Response` 类型；本节按「只创建交付物点名的对外概念」纪律，直接用第 16 节已有的 `ChatResponse` + Spring AI 的 `AssistantMessage.ToolCall`，**不新增** `Response` / `ToolCall` 包装类型。
  - 课件骨架的 `profile.maxIterations()`；第 16 节已把 Profile 定为 `settings().maxIterations()`，本节沿用既有访问路径，不改 Profile。
