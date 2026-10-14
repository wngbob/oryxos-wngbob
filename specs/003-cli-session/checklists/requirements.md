# Specification Quality Checklist: CLI 与会话持久化 —— 把 Agent 从「能跑」变成「能用」

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

**迭代 1（首轮）—— 1 项未通过，已修订：**

- ✗ **Scope is clearly bounded** → 原稿只在范围说明里散着写了边界（FR-021 与 Assumptions 各一段），没有课件要求「逐项照搬」的清单。已在范围说明下新增 `### 明确不做（边界）` 小节，把课件「有几样先别做」的 10 项逐条照搬。**修订后通过。**

**迭代 2（复审）—— 13/13 项通过。**

**实现细节的判定口径（逐项说明，避免「全打勾但没真看」）：**

- **正文（User Scenarios / Requirements / Success Criteria）未出现类名、方法签名**。出现的具体标识仅四类，均属必要且非设计泄漏：
  - `oryxos init` / `oryxos chat` / `--profile` / `/quit` / `--force` / `--message` / `--help`——**CLI 命令与参数本身就是用户界面**，不是实现细节；上游 `specs/000-runtime-kernel/contracts/cli.md` 已把它们钉成对外契约。
  - 退出码 2 / 1 / 0——**用户可见行为**，同属界面契约（上游 T013）。
  - 冲突仲裁记录里的 `Session` / `SessionService` / `JpaSessionService` / `CliApplication` / `application.yaml`——记录**已报批的架构裁决**与**前序节交付物**，是假设与约束而非设计（与 001 spec 记录 BOM 依赖、002 spec 记录四个接缝接口同口径）。
  - Assumptions 里的 Picocli / SQLite / Jackson——**外部依赖前提**，用于坐实「不新增第三方依赖」这一约束。
- **技术无关性的边界**：SC-001 的「不启动 Spring 上下文」是**框架名出现在成功判据里**，看似违反该项。保留的理由：这条判据的**被测对象就是「启动了什么」**，换成「不初始化重量级运行环境」会退化成不可判定的形容词，反而违反「Success criteria are measurable」。同理 FR-003。这是刻意的权衡，不是漏改——课件「五、做完怎么验」把「轻命令秒回、重命令才启动 Spring」列为人工验收项，本条只是把它机器化。其余 SC-002~SC-009 全部以「不同标识数 = 1 / 一致率 100% / 覆盖率 12/12 / 泄露次数 = 0 / 丢失率 0」这类可度量结果表述。
- **无澄清标记**：本规约无 `[NEEDS CLARIFICATION]`。四处冲突已通过软门禁向用户报批并裁决（`Session` 增 `channel`/`userId` 两字段；工具与记忆两个接缝本节挂空实现；接口名 `SessionService`；实现放 `oryxos-core`），另有七处依据 `CLAUDE.md` / `docs/TechnicalSolution.md` / 上游 `contracts/` 直接裁定，全部记入「冲突仲裁记录」共 11 条，不留待 clarify。
- **测试可判定性**：4 个 user story 的 Independent Test 均可由单测直接判定，一一对应课件「四、验收 harness」的两个测试类（US-1 → `SessionServiceTest` 的幂等与隔离两组断言；US-2 → `SessionRepositoryTest` 的建表、读写、重启重查三组断言）。US-3 / US-4 无课件单测承载，其 Independent Test 以进程级断言（有无上下文启动痕迹）与临时目录端到端跑通判定。全部不碰网络，`mvn test` 秒级跑完。
- **与课件的偏差（已在 spec 内显式记录，非遗漏）**：
  - 课件 `SessionManagerTest` → 本节 `SessionServiceTest`（仲裁 1，沿用第 17 节已报批的命名裁决）。
  - 课件 `SessionManager` → 本节 `SessionService`（同上）。
  - 课件 `ProfileListCommand` 片段读 `.oryxos/profiles/` → 本节读 `.oryxos/agents/`（仲裁 10，课件自称该片段「是示意」）。
  - 课件把 `notify_channels.yaml` 列为 `init` 产出 → 本节不建（仲裁 7，`CLAUDE.md` 工作区目录权威）。
- **未决但不阻塞**：仲裁 5（重命令的 Spring 启动类 `CliApplication` 落 `oryxos-cli`）是**跨模块落位决定**，已记入 spec，按流程留到第 5 步固定停点一并提请用户确认——它不影响本规约的 WHAT/WHY，只影响 plan 的模块归属。

### clarify 轮次（2026-10-13）—— 3 题 3 答，checklist 13/13 无变化

`/speckit-clarify` 共问 3 题、答 3 题（三题均为**软门禁 ③**「课件与 `docs/TechnicalSolution.md` 冲突」或跨模块落位，按纪律停下提请用户裁决，未自行发挥）：

1. **`init` 是否生成默认 Agent** → 不生成。落到仲裁 14 + FR-001 + US-4 场景 1/2。
2. **`serve` / `gateway` 本节做到哪一步** → 都只启动 Spring 上下文并前台常驻。落到仲裁 15 + FR-021，并连带把 `oryxos-cli` 的模块依赖从七个收窄为六个（去掉 `oryxos-web`）。
3. **`application.yaml` 放哪** → 留在 `oryxos-boot` 不动。落到仲裁 6 / 11 + FR-015 重写 + 新增仲裁 13（轻命令的已知厂商集合取自 `ProviderProperties.SUPPORTED_PROVIDERS`，不新造常量、不复制副本）。

**checklist 状态：13/13 → 13/13，无新增通过项、无回归。** 三处答案都是把「原先按推断写下的做法」改成用户裁定，spec 的 WHAT/WHY 结构未变；FR-015 由「报就绪状态」改写为「报厂商全集 + 引用情况，就绪标未接入」后**仍然可测**（断言输出含「未接入（第 26 节）」且不含任何凭证值），故「Requirements are testable and unambiguous」与「Success criteria are measurable」两项保持通过。
