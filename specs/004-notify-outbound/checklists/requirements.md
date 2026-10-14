# Specification Quality Checklist: Notify 出站推送 —— 给 Agent 补上「结果主动送出去」的出口

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-10-14
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

### 校验记录（2026-10-14）

**迭代 1（首轮）—— 1 项未通过，已修订：**

- ✗ **Scope is clearly bounded** → 原稿的边界散落在范围说明与 FR-014 / FR-016 里，没有可逐项对号的清单。已在范围说明下新增 `### 明确不做（边界）` 小节，把课件边界与本次追加的三条（yaml 导入、工具入容器、重试退避）逐条列出。**修订后通过。**

**迭代 2（复审）—— 16/16 项通过**（Content Quality 4 + Requirement Completeness 8 + Feature Readiness 4）。

**实现细节的判定口径（逐项说明，避免「全打勾但没真看」）：**

- **正文（User Scenarios / Requirements / Success Criteria）未出现类名与方法签名**。User Story 与 FR-001~FR-021 一律用「通知接口 / 通知目标 / 通知适配器 / 渠道注册表接缝 / 通知工具」这类角色词表述，`NotifyChannelAdapter` / `NotifyTarget` / `WebhookNotifyAdapter` / `NotifyTools` 只出现在「冲突仲裁记录」「Key Entities」「Assumptions」三处——它们是**骨架期已交付的契约名**与**本节落地物的点名**，属假设与约束而非设计（与 001 / 002 / 003 spec 记录前序节交付物同口径）。
- **技术无关性的边界**：SC-008 的「不访问网络」是**部署前提出现在成功判据里**，看似违反该项。保留的理由：这条判据的**被测对象就是「有没有外呼」**——本项目把「单测不许碰外网」定为纪律（第 18 节 SC-009 同款），换成「不依赖外部服务」会退化成不可判定的形容词。其余 SC-001~SC-007 / SC-009 / SC-010 全部以「比例 100% / 出现次数 0 / 静默失败次数 0 / 顺序颠倒次数 0 / 无限期挂住次数 0」这类可度量结果表述。
- **无澄清标记**：本规约无 `[NEEDS CLARIFICATION]`。八条冲突裁决全部记入「冲突仲裁记录」：其中 1 条是**上游已有裁决的落地执行**（仲裁 1，`specs/000-runtime-kernel/plan.md` 仲裁项 4 + 代码里 `ProfileLoader.FORBIDDEN_KEYS` 已钉死）、2 条是**依据权威文档直接裁定**（仲裁 2 / 5）、4 条是**已报批的用户裁决**（仲裁 4 的「零装配」、7 的 `mockwebserver3`，以及上一轮报批的「建表 + 注册表解析」「做 `NotifyTools` + 留沙箱调用位」）、1 条是**对课件代码的有意偏离并已在 spec 内写明理由**（仲裁 3）。软门禁 ① 的报备项（仲裁 8）已显式标注。
- **测试可判定性**：3 个 user story 的 Independent Test 均可由单测直接判定，一一对应课件「四、验收 harness」的两批测试——US-1 → `WebhookNotifyAdapterTest`（本地假 webhook 收 POST、body 带内容、地址来自配置、5xx 上抛）；US-2 → `NotifyToolsTest`（渠道未配置报错、渠道名缺省取第一个、`enforce` 先于 `send` 的 `InOrder` 顺序断言）；US-3 无课件单测承载，以契约核对（签名里无专有词、依赖里无具体实现类型）与替身接入判定。US-2 另含渠道注册表与建表脚本的可测断言。全部不碰网络，`mvn test` 秒级跑完。
- **与课件的偏差（已在 spec 内显式记录，非遗漏）**：
  - 课件把 `notify_channels` 写成 Profile frontmatter 字段 → 本节按 SQLite 全局注册表（仲裁 1，三处权威文档 + 代码禁令一致反对）。
  - 课件 `profileContext.resolveNotifyChannel(channel)` → 本节改由通知工具自持渠道注册表接缝（仲裁 3；课件该片段按依赖方向不可行，且 `ProfileContext` 是静态类，片段本身编译不过）。
  - 课件 `@Component` 装配 → 本节零装配、Bean 声明归第 24 节（仲裁 4；且本节无 `Sandbox` 实现，工具根本进不了容器）。
  - 课件 `mockwebserver:4.12.0` → 本节 `mockwebserver3:5.5.0`（仲裁 7，4.x 硬耦合 JUnit 4）。
  - 课件 `A6` 的 `notify_channels.yaml` 定义源 → 本节不引入导入路径（仲裁 2，技术方案 §6.8 + §9.3 + 第 18 节仲裁 7 一致反对）。
- **仲裁 3 / 4 已于 2026-10-14 报批（软门禁 ①/④）**：两处「偏离课件代码」的落位决定经用户裁决通过——① 渠道解析由**通知工具自持注册表接缝**（不在 `oryxos-core` 的 `ProfileContext` 上加方法、不挪动骨架期契约位置）；② 本节**零装配**（Bean 声明全归第 24 节 `WhitelistSandbox` 就位时，`CliAssemblyConfiguration` 一行不改）。仲裁 8（注册表接缝是超出课件交付物清单的新 public 类型）随①一并报备通过，其**名称与形状留待 plan 阶段定稿**。三者均不影响本规约的 WHAT/WHY。
- **四处「课件与技术方案都查不到」的欠定项，按「有依据的推断 + 显式记入假设」处理，不新开澄清问题**（均在 spec 内写明理由与口径，一并进第 5 步固定停点的确认清单）：
  1. **默认渠道 = 注册名升序的第一个**（FR-011）——A6 无排序或默认标志列，取不依赖插入顺序的确定性口径；
  2. **`config_json` 形状 = JSON 对象、值均为字符串**（Assumptions）——对齐通知目标的配置类型；
  3. **`type` 列不做取值校验**（Assumptions）——本节无「按 type 选适配器」的调用方，只要求非空；
  4. **推送等待上限 10 秒**（FR-021 + Assumptions）——本规约**新增的唯一字面量**；不设上限则「对端黑洞」会无限期挂住定时任务。口径（必须有界、超时即失败、不重试）不可省，数值可调。

### clarify 轮次（2026-10-14）—— 0 题 0 答，checklist 16/16 无变化

按 taxonomy 逐类扫描后**未提澄清问题**，理由是：本规约的歧义已在 Step 2 内消化完毕——八条冲突裁决把「课件 vs 权威文档 / 依赖方向 / 装配纪律」三类分歧全部就地记明，四处欠定项按「有依据的推断 + 显式记入假设」处理并给出可测口径。逐类状态见 spec 的覆盖情况：

| 类别 | 状态 | 说明 |
|---|---|---|
| Functional Scope & Behavior | Clear | FR-001~FR-021 + SC-001~SC-010 齐备；「明确不做」11 条逐项列出；actor（业务方 / 开发者 / 模型）在三个 user story 里分清 |
| Domain & Data Model | Resolved | 列口径照 A6 逐字（FR-012）；主键 `name`；行→目标的映射规则（FR-013）；「第一个渠道」的确定性口径（FR-011）；`config_json` 形状与 `type` 不校验（Assumptions） |
| Interaction & UX Flow | Clear | 三条旅程 + 10 条 Edge Cases 覆盖错误 / 空 / 不可达三类状态；无 a11y / i18n 面 |
| Non-Functional Quality Attributes | Resolved | 安全：FR-007 顺序不变量 + FR-019 不泄漏地址凭证；可观测：FR-008 审计复用；性能/可靠性：FR-021 + SC-010 给出有界等待 |
| Integration & External Dependencies | Clear | 对端失败模式（5xx / 4xx / 不可达 / 2xx 但业务错误）逐条定义；协议为 HTTP POST + `{"content": ...}`；无导入导出 |
| Edge Cases & Failure Handling | Clear | 10 条，含「空注册表」「名字打错不退回默认」「url 为空」「校验失败一次都不发」 |
| Constraints & Tradeoffs | Clear | 仲裁 1~8 逐条给出理由与被否方案；「明确不做」11 条 |
| Terminology & Consistency | Clear | 「渠道注册名 / 渠道名」同指一名；「通知目标 / 通知适配器 / 通知工具 / 渠道注册表接缝」四词各指一物，未混用 |
| Completion Signals | Clear | 每条 FR 都能对到 SC 或 user story 的验收场景 |
| Misc / Placeholders | Clear | 无 TODO / 占位符；无未量化的形容词 |

**checklist 状态：16/16 → 16/16，无新增通过项、无回归。**

**两处由 clarify 阶段（而非用户回答）驱动的修订**：① 新增 FR-021 + SC-010——章程附加约束要求「与某功能相关的性能目标 MUST 以可度量验收标准的形式写入该功能的规格说明」，原稿的「对端不可达即失败」缺一个有界等待的可度量判据；② 把四处欠定项从「散在 Assumptions 里」提为显式清单，进第 5 步固定停点的确认议程。
