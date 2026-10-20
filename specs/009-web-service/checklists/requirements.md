# Specification Quality Checklist: Web Service 与第一版管理平台

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-10-19
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

### 校验记录（2026-10-19）

**第 1 轮**（specify 收尾）：16/16 全过。

**第 2 轮**（clarify 收尾，`/speckit-clarify` 后重跑）：仍 16/16，无状态变化（无新增未决项、无回归）。本轮对 spec 的改动有两类：

1. **补三处课件/技术方案里有、首稿漏掉的缺口**（不属澄清问答，属规格补全）：
   - FR-001 明确「只有 `serve` 起 HTTP 服务」（技术方案 §8.6 的运行模式表：`gateway` 是挂入站通道，归扩展阶段）；
   - FR-023 CORS 核心阶段全开放（技术方案 §7.4）；
   - FR-024 `serve` 的端口参数必须**真的生效**（第 18 节交付的 `serve` 只回显，其输出里那句「端口在第 26 节启用」到此兑现）。
2. **一处澄清问答**（软门禁裁决 ⑤）：Provider 状态只报「配置就绪」、不发真实探测 → FR-020 改写、US3 场景 4 改写、SC-010 新增。

逐项核对结论：

- **无实现细节**：spec 正文不出现类名、模块名、框架名。端点路径（`/api/v1/...`、`/admin`）与信封字段名（`code`/`message`/`data`/`timestamp`）**保留**——它们是**对外契约**（业务系统按它对接），不是实现细节；且已由骨架期契约 `specs/000-runtime-kernel/contracts/rest-api.md` 定稿。响应信封的字段名是本节软门禁裁决 ① 的结论，属「已定字面量」而非实现选择。
- **可测试且无歧义**：24 条 FR 全部写成「MUST + 可观测结果」。最容易含糊的已量化：32KB（FR-008）、100 条（FR-009）、10 个端点（FR-001）。
- **成功标准可度量**：SC-001~SC-010 全部可验证，其中 SC-003 / SC-006 / SC-007 / SC-010 给的是「零 / 100% / 不随体积增长」这类可判定量。
- **成功标准不绑实现**：SC 里不出现框架、语言、数据库；SC-008 用「请求全部得到响应」而非具体 QPS 数字。
- **边界清晰**：明确不做六项逐项照搬课件；另在 Clarifications 与 Assumptions 里标明两处**已知边界**（60 秒强制掐断不做、`未接入（第 26 节）` 占位不纳入本节）。
- **依赖与假设齐备**：Assumptions 段逐条列了目标用户、部署形态、凭证来源、构建产物、前序依赖、不新增第三方依赖、超时语义。

### 已知未决（不阻塞，但进 implement 前必须落实）

- **管理台构建串联方式**（构建期绑定进 Maven 打包 vs 手动构建后打包）——课件明说「plan 里二选一说明」，故此处**有意留白**，归 `/speckit-plan`。
- **长期记忆查询端点怎么拆出「核心区 / 归档区」**——已入库契约 `rest-api.md` 定的响应形状是 `{core, archival, truncated}`，而 core 侧既有的读记忆接缝 `MemorySource.longTermMemory(Profile)` 只返回**一整段文本**，没有分区。要兑现契约形状，要么在 `oryxos-memory` 的门面上加一个只读分区方法（**新增对外概念，软门禁 ①**），要么按 Markdown 档的分区标题解析（脆弱、且 `sqlite` 档没有标题）。**归 `/speckit-plan` 的 research 定案**，实现前必须定，否则契约兑现不了。
  > **修订注（2026-10-19，实现后复核更正）**：本句的前提「`sqlite` 档没有标题」**是错的**——`SqliteMemoryStore.load()` 拼出的正是一份带 `## 核心记忆` / `## 归档记忆` 分段标题的 Markdown（实测 `SqliteMemoryStore.java:77,81`，与 `MarkdownMemoryStore` 同形），所以「解析标题」这条路在两档上本是通的。定案仍然选了**门面加只读分区方法**（软门禁 ①，用户的裁决 ⑦）：标题解析靠的是内容形状而非类型契约，`mem0` 档返回的是外部服务的文本、标题不在我们的控制里，按标题切分会静默切错。**前提写错、结论未变**，原文保留以免抹掉当时的推理链。
- **`未接入（第 26 节）` 两处占位**（`StatusCommand` / `ProviderListCommand`）——不在本节交付物里，本 spec 不纳入范围。
- **`/health` 的 Provider 字段措辞须回修契约**——本节裁决 ⑤ 把「连通」收窄为「配置就绪」，`specs/000-runtime-kernel/contracts/rest-api.md` 里「不可达」二字须挂带日期修订注（归 tasks 的一个文档任务）。
