# Specification Quality Checklist: Memory —— 给 Agent 装上记得住事的仓库

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-10-15
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

**校验记录（2026-10-15）**：

- **实现细节**：本规约在「范围说明 / 仲裁记录 / Assumptions」里出现了类名与配置键——这是**本仓既定的 house style**（第 19/20 节 spec 同款），用途是给 plan / tasks 定死落位与字面量，不是把 HOW 混进 WHAT。User Scenarios / Requirements / Success Criteria 三段**一律写行为与可判定结果**，不写类名。
- **仲裁记录共 11 条**（编号 1~9、11、12），其中 6 条是 2026-10-15 用户已报批的裁决（仲裁 1/2/3/4/11 + `InMemoryMemoryStore` 的软门禁 ①），其余是本规约新增的口径，已逐条写明依据与代价。**仲裁 4（全局单份 vs 按 Agent 隔离）**与**仲裁 5（`HYBRID_BUILTIN` 无实现方）**是两处请 reviewer 重点看的地方，已在 spec 里显式标注。
- **无 [NEEDS CLARIFICATION] 标记**：三处原本可能开澄清的问题（接口形状 / 门面签名 / 记忆 IO 是否过沙箱）已在 H0 阶段作为软门禁报批并取得用户裁决，直接落成仲裁 1/2/3。
- **上游同节复核（2026-10-15）**：用户要求以 `/home/wngbob/oryxos`（上游仓库）复核"全局单份 vs 按 Agent 隔离"。定位到上游同节交付提交 `aa781d28`（`specs/006-memory-pluggable/`），**三条证据一致指向全局单份**，裁决 4 由"按三份文档推断"升级为"有上游同节实证"；同时把四处欠定项中的三处（截断阈值 / 注入条数上限 / 日期标记形式）由上游定死，并记下四处与上游的**有意分歧**（配置键前缀、补做 `SqliteMemoryStoreTest`、`InMemoryMemoryStore` 落成 public 类、core 接缝用本仓的 `MemorySource` 而非上游的 `MemoryService.buildContext(Session)`）。
- **欠定项处理**：四处"课件与技术方案都没给数"的数值（截断阈值、注入条数上限、日期标记形式、配置键名）按「有依据的推断 + 显式记入口径」处理，全部进 Assumptions 与第 5 步固定停点的确认清单，不新开澄清问题。其中前三条已由上游同节实证，第四条（配置键前缀）是本节唯一的有意分歧，已在 Assumptions 里写明理由与连带影响。
