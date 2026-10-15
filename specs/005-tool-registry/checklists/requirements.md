# Specification Quality Checklist: Tool 体系 —— 给 Agent 装上能真正动手的手

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

- ✗ **Scope is clearly bounded** → 原稿把边界散在「范围说明」的散文里，没有可逐项对号的清单。已在范围说明下新增 `### 明确不做（边界）` 小节，把课件边界与本节追加的项（Memory 两件、Skill 绑定、Web 侧工具端点、MCP 生命周期治理、MCP 认证）逐条列出。**修订后通过。**

**迭代 2（复审）—— 16/16 项通过**（Content Quality 4 + Requirement Completeness 8 + Feature Readiness 4）。

**实现细节的判定口径（逐项说明，避免「全打勾但没真看」）：**

- **三个 mandatory 正文段（User Scenarios / Success Criteria / Assumptions）未出现类名与方法签名**。四个 User Story 与 FR-001~FR-028 一律用「统一抽象 / 注册表 / 注解管道 / 内置工具 / 交互接缝 / 搜索接缝 / 沙箱动作 / 临时装配」这类角色词表述。类名只出现在**四处**：①「范围说明」的交付物清单；②「冲突仲裁记录」；③ FR-024 的**禁令清单**（逐字点名「不得修改」的既有契约）；④「Key Entities」。前两处与第四处是**骨架期已交付契约名**与**本节落地物点名**，属假设与约束而非设计（与 001 / 002 / 003 / 004 spec 记录前序节交付物同口径）；第三处的判定与 004 的 FR-015（同样逐字点名 `Profile` / `ProfileLoader` / `ProfileContext`）一致——**「不许改哪些既有契约」这条要求本身无法在不点名的情况下判定**。
- **技术无关性的边界**：SC-008 的「不访问外网」是**部署纪律出现在成功判据里**，看似违反该项。保留的理由与 004 的 SC-008 同款：这条判据的**被测对象就是「有没有外呼」**——本项目把「单测不许碰外网」定为纪律（第 18 / 19 节同款），换成「不依赖外部服务」会退化成不可判定的形容词。SC-010 的「启动日志中可见次数 ≥ 1」是**对已报批取舍的可观测性要求**（见下条），不是实现细节。其余 SC-001~SC-007 / SC-009 全部以「缺项数 0 / 差异条数 0 / 未覆盖数 0 / 改动行数 0 / 注册率 100%」这类可度量结果表述。
- **无澄清标记**：本规约无 `[NEEDS CLARIFICATION]`。九条冲突裁决全部记入「冲突仲裁记录」：其中 **4 条是已报批的用户裁决**（仲裁 1 装配解冻 + 临时沙箱、2 保留 String 口径、3 显式声明 MCP 依赖并按真实 API 落地、6 三个实现类名取自课件正文），**4 条是依据权威文档 / 既有代码直接裁定**（仲裁 4 前四件已交付、5 注册表实现 core 既有接缝、7 模板结构不改、8 Skill 归第 29 节、9 Memory 两件归第 22 节），**0 条是对课件代码的未报批偏离**——本节所有与课件的差异都已在开工前报批（这是与第 19 节的最大不同：第 19 节的仲裁 3 是「有意偏离 + 事后报批」，本节四条全部**事前报批**）。

**本节与课件的主要偏差（已在 spec 内显式记录，非遗漏）：**

| # | 课件写法 | 本节口径 | 依据 |
|---|---|---|---|
| 1 | 第 19 节裁决 4 的「零装配 + MUST NOT 造全放行占位沙箱」 | **推翻**：本节装配，沙箱挂临时实现 | 仲裁 1（已报批）；课件「本节交付物」明写临时装配 + 「`NotifyTools` 在此完成注册」 |
| 2 | `JsonSchema getInputSchema()` / `execute(JsonNode)` | 保留 String 口径 | 仲裁 2（已报批）；骨架期契约 javadoc 已写明设计意图 |
| 3 | `McpClient.connect(cfg)` / `client.callTool(name, input)` | 按真实 SDK API 落地 | 仲裁 3（已报批）；课件自述「下面的代码是示意」 |
| 4 | `Profile` 里 `skills` 引用 Skill | 本节不碰 Skill 绑定 | 仲裁 8；`ProfileLoader` 既有禁令 + 章程原则 13 |
| 5 | 「九个内置 Tool」 | 本节交付七件 + 扩展五件，Memory 两件归第 22 节 | 仲裁 9；技术方案 §6.2 明写 Memory 组「归 Memory 模块」 |
| 6 | `@Component` 装配 | 装配仍在 `CliAssemblyConfiguration` 显式声明 | 第 18 节既定装配纪律（非 boot 模块不带装配注解），本节沿用 |

**四处「课件与技术方案都查不到数」的欠定项，按「有依据的推断 + 显式记入口径」处理，不新开澄清问题**（均在 spec 的 Assumptions 内写明理由与口径，一并进第 5 步固定停点的确认清单）：

1. **搜内容 / 找文件返回上限 200 条**（FR-013）——课件明写，**非本节新增**；
2. **MCP 连接与列举等待上限 10 秒 / 个**（FR-009）——本规约**新增的字面量**，数值可调；
3. **命令工具超时上限 30 秒**（FR-020）——本规约**新增的字面量**，技术方案只写「带超时」未给数，数值可调；
4. **工具重名保留先注册者**（FR-023）——课件未定，取安全侧优先口径。

**一处需要用户特别确认的取舍（本规约的核心风险点）：**

- **仲裁 1 的临时沙箱**让第 20~23 节之间**白名单校验不生效**，即**本节交付的运行时没有 Tool 治理**。这不是疏漏而是**已报批的取舍**（用户裁决「造 PermissiveSandbox（按课件）」），且本节用四条缓解措施把它钉住（类名/javadoc 明示临时、装配时 WARN、第 24 节同类型 Bean 自动让位、`CLAUDE.md` 与验收报告记明窗口期）。SC-010 把「警告可见」写成了可度量判据，就是为了让这个窗口期**不可能被误认为"沙箱已就位"**。此条须在第 5 步固定停点与验收报告两处**重复声明**。

### clarify 轮次（2026-10-14）—— 0 题 0 答，checklist 16/16 无变化

按 taxonomy 逐类扫描后**未提澄清问题**，理由是：本规约的歧义已在 Step 2 内消化完毕——九条冲突裁决把「课件 vs 权威文档 / 依赖方向 / 装配纪律 / 交付时点」四类分歧全部就地记明，四处欠定项按「有依据的推断 + 显式记入假设」处理并给出可测数值。逐类状态：

| 类别 | 状态 | 说明 |
|---|---|---|
| Functional Scope & Behavior | Clear | FR-001~FR-028 + SC-001~SC-010；「明确不做」12 条；actor（业务方 / 开发者 / 模型 / 运维）在四个 user story 里分清 |
| Domain & Data Model | Clear | 8 个实体已定义；本节**不新增任何表或列**（`tool_invocations` 是第 17 节既有表，本节只写不建） |
| Interaction & UX Flow | Clear | 15 条 Edge Cases 覆盖错误 / 空 / 不可达三类状态；无 a11y / i18n 面 |
| Non-Functional Quality Attributes | Resolved | 安全：FR-015 顺序不变量 + FR-016 临时装配三条约束 + FR-027 凭证不落明文；性能 / 可靠性：FR-009 / FR-020 有界等待；执行模型：FR-026 |
| Integration & External Dependencies | Clear | MCP 失败模式（失联 / 超时 / 重名 / 空配置）逐条定义；配置模板结构与键名冻结（FR-024） |
| Edge Cases & Failure Handling | Clear | 15 条，含「模型点了不存在的工具名」「声明了但没注册的名字」「两源重名」三个易漏项 |
| Constraints & Tradeoffs | Clear | 九条仲裁逐条给出理由与被否方案；六处课件偏差列成表 |
| Terminology & Consistency | Clear | 「统一抽象 / 注册表 / 注解管道 / 内置工具 / 交互接缝 / 搜索接缝 / 临时装配」各指一物，未混用 |
| Completion Signals | Clear | 每条 FR 都能对到 SC 或某个 user story 的验收场景 |
| Misc / Placeholders | Clear | 无 TODO / 占位符；无未量化的形容词 |

**checklist 状态：16/16 → 16/16，无新增通过项、无回归。**

**本轮零改动说明**：无澄清答案被写回 spec，故未新增 `## Clarifications` 小节（与 004 同款）；四处欠定项与一条核心取舍**留在 Assumptions / 仲裁记录**里，随第 5 步固定停点一次性确认，不在 clarify 阶段单独开问——避免同一批问题被问两遍。

**流程偏差备注（须进验收报告）**：WSL 侧**没有 PowerShell**（`.specify/scripts/` 只装了 `powershell/` 子目录，`pwsh` 不存在），Spec-Kit 的 setup / check 脚本无法执行。脚本职责是**纯路径解析 + 模板拷贝**，已按其 `common.ps1` 的实际逻辑（`SPECIFY_FEATURE_DIRECTORY` → `.specify/feature.json` 的 `feature_directory` → `FEATURE_DIR/spec.md|plan.md|tasks.md`，分支取当前 git 分支）等价解出，未改变任何产物内容。
