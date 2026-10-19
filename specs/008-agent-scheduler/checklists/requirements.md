# Specification Quality Checklist: 定时任务 —— 给 Agent 补上第三种触发源

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-10-18
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

### 校验记录（2026-10-18）

**第 1 轮**（specify 后）：全部通过，无需返工。

**第 2 轮**（clarify 后）：全部通过。clarify 只问出 **1 个**高影响空白（跨任务并发度，见软门禁记录第 5 条），已集成进 spec 的 Clarifications / 冲突仲裁记录 / FR-019 / Edge Cases / SC-009 五处；其余 taxonomy 类别全部 Clear。检查项状态**无变化**（12/12 → 12/12），无新增未通过项、无回退。

关于「No implementation details」一项的说明：本 spec 里确实出现了 `AgentService` / `CronTrigger` / `Profile.ScheduleSpec` 等类型名。这是**本仓既有的规约体例**（对照 `specs/007-sandbox-whitelist/spec.md`）——它们出现在 **Clarifications**（冲突仲裁记录，必须写明冲突在哪两个具体载体之间）、**本节交付物范围**（课件逐字点名的交付物清单）与 **Assumptions**（前序节已交付物的就位声明）三处，是**跨节契约的可判定依据**，不是设计细节。User Scenarios / Functional Requirements / Success Criteria 三节本身保持技术无关。

### 软门禁触发记录（本节开工前，已全部报批）

| # | 冲突 | 裁决 |
| --- | --- | --- |
| 1 | 课件把配置值原样透传给 Spring 的 cron 触发器，但该触发器要求 6 段（含秒），而本仓既有测试与两份契约文档一律是 5 段 Unix cron —— 课件代码在本仓会当场抛 `IllegalArgumentException` | 5 段为准，兼容 6 段；其它段数报错并指名 Agent 与任务 |
| 2 | 课件交付物点名 `ScheduleConfig`，但第 16 节已交付形状相近的 `Profile.ScheduleSpec` | 新建 `ScheduleConfig(id/cron/zone/message)`；`ScheduleSpec` 一字不改；id 由 Profile 名 + 序号派生 |
| 3 | 课件用 `@Component` + `@PostConstruct`（起上下文即注册，`chat` 也会注册），与技术方案 §8.6 / FR-050 的「随 serve/gateway 常驻调度」冲突 | 只在 `serve` / `gateway` 命令体内显式注册 |
| 4 | `CliAssemblyConfiguration` 的 javadoc 把「定时任务的 ask_user 分档」挂在第 25 节，但课件零提及，且按触发源分档需引入新概念 | 本节不做，记为已知边界；该 javadoc 的挂账指向不成立，须挂带日期的修订注 |
| 5 | 课件只定「同一任务不重叠」，对「不同任务之间怎么并发」零表述；调度器线程池大小直接决定长任务会不会推迟其它任务的触发点 | 固定小池 2 线程，不新增配置键 |

另有**一处实测发现**（不改契约，改的是测试断言手法）：课件的时区断言（抓注册参数比对触发器）在本仓**写不出来**——该触发器的 `equals` 不比较时区、也没有时区 getter。替代手法是「抓触发器 → 求下一次执行时刻 → 断言落点在配置时区下是 09:00 且与错时区不等」，比原手法**更强**（证明时区真的生效，而不只是构造参数里传了个值）。已记入 spec 的 Clarifications。
