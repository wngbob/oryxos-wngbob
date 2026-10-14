# 契约：零装配的交接清单（给第 24 节）

**Feature**: [spec.md](../spec.md) | **Plan**: [plan.md](../plan.md) | **Created**: 2026-10-14

本节**不装配任何 Spring Bean**（FR-016 / 已报批的仲裁 4）。本文件是交给第 24 节的**交接契约**：第 24 节需要声明哪些 Bean、每个 Bean 的构造依赖是什么、有什么前置条件；以及本节**刻意留白**、第 24 节要评估的两处。

---

## 1. 本节的装配状态：零

| 事项 | 状态 |
| --- | --- |
| `CliAssemblyConfiguration`（`oryxos-cli`） | **一行不改**——既有 13 个 `@Bean` 方法保持原样 |
| `WebhookNotifyAdapter` | **不带** `@Component` / `@Service` / 任何 Spring 注解 |
| `NotifyTools` | **不带** 任何 Spring 注解 |
| `JpaNotifyChannelRegistry` | **不带** 任何 Spring 注解 |
| `NotifyChannelRepository` | 不带注解——`oryxos-storage` 的 `@EnableJpaRepositories` 已覆盖其包（既有 `SessionRepository` 同款，由 `oryxos-boot` 的 `@EntityScan` + `@EnableJpaRepositories` 扫到） |
| `NotifyChannelEntity` | 不带额外注解——同理由 `oryxos-boot` 的 `@EntityScan` 覆盖 |
| 新增配置文件 / 配置项 | **无** |
| 新增 CLI 命令 / 参数 / REST 路径 | **无**（命令面零变化） |

**为什么**（两条，第二条是硬的）：

1. **装配纪律**（第 18 节定）：非 boot 模块不带 Spring 装配注解，Bean 一律在 `CliAssemblyConfiguration` 里显式声明。课件的 `@Component` 与之冲突。
2. **没有可注入的沙箱**：`NotifyTools` 的构造依赖 `Sandbox`，而 `Sandbox` 的**唯一实现** `WhitelistSandbox` 归第 23/24 节。本节加 `@Component` 只会让重命令启动时报 `NoSuchBeanDefinitionException`（或被某个 `@ConditionalOnMissingBean` 悄悄吞掉——第 18 节已实测过 `BeanDefinitionOverrideException` 这类「只跑 `--help` 扫不出来」的坑）。

**★ MUST NOT 造「全放行」的占位沙箱。** 一个 `PermissiveSandbox`（`enforce` 直接返回）会把 FR-007 的核心安全不变量变成一句空话，且一旦进了主源码树，就可能在后续节被误当作可用实现接上去。测试需要替身时用 **Mockito mock**（在测试源码树里，不落主源码）。

---

## 2. 交接给第 24 节的 Bean 清单

第 24 节（`WhitelistSandbox` 就位后）需要在 `CliAssemblyConfiguration` 里补上的声明。**构造签名已在本节定死，第 24 节按此声明即可**：

| # | Bean | 类型 | 构造依赖 | 前置条件 |
| --- | --- | --- | --- | --- |
| 2.1 | `notifyChannelRegistry` | `NotifyChannelRegistry`（实现 `JpaNotifyChannelRegistry`） | `NotifyChannelRepository`（Spring Data 自动装配） | 无——本节已可用，只是没人注入它 |
| 2.2 | `notifyChannelAdapter` | `NotifyChannelAdapter`（实现 `WebhookNotifyAdapter`） | **无参构造**（超时内置 10s，见 [notify-outbound.md](notify-outbound.md) §2.1） | 无 |
| 2.3 | `notifyTools` | `NotifyTools` | `Sandbox` + `NotifyChannelAdapter` + `NotifyChannelRegistry` | **`Sandbox` 实现必须已就位**（第 24 节），否则 2.3 无法声明 |

**顺序**：2.1 / 2.2 可独立声明；2.3 依赖 2.1 + 2.2 + `Sandbox`。

**第 24 节还需要做的两件事**（不在本节范围，仅列出以免遗漏）：

- 把 `notify` 工具纳入 `ToolRegistry` 的可选子集（第 20 节交付注册机制；`@Tool` 注解本节已加上，是惰性的）。
- 决定 `notify` 是否进内置工具的**默认可用集合**——渠道未配置时它只会报错（[data-model.md](../data-model.md) §7），默认开启会让未配渠道的 Agent 看到一个「总是失败的工具」。

---

## 3. 本节交付物的可测性（不依赖装配）

本节交付的库代码全部**可用替身驱动单测**，不需要 Spring 上下文：

| 交付物 | 测试方式 | 依赖 |
| --- | --- | --- |
| `WebhookNotifyAdapter` | 真实实例 + MockWebServer（本地假 webhook） | 无——适配器不依赖 `Sandbox`（`enforce` 在工具边界，见 [notify-outbound.md](notify-outbound.md) §3.3） |
| `NotifyTools` | 实例 + Mockito mock 三个接口 | `Sandbox` / `NotifyChannelAdapter` / `NotifyChannelRegistry` 全部 mock |
| `JpaNotifyChannelRegistry` | 实例 + mock `NotifyChannelRepository` | 无 Spring 上下文 |
| `NotifyChannelRepository` | `@DataJpaTest` + 真 SQLite 临时文件 | 最小 Spring 切片（既有 `SessionRepositoryTest` 同款） |

**这四类测试合起来覆盖了本节全部 FR**（除 FR-008 的审计落库——那条由第 17 节的 `ToolExecutor` 承载，本节不新增审计逻辑）。

---

## 4. 本节留白、第 24 节需评估的两处

| # | 留白 | 本节的做法 | 第 24 节可考虑 |
| --- | --- | --- | --- |
| 4.1 | **重定向的逐跳白名单复检** | `Redirect.NEVER` 显式不跟随（[research.md](../research.md) D5）——3xx 即失败 | 上游参考实现的 `NotifyPoster` 每跳调一次 `enforce`，比「不跟随」更宽容（允许重定向但仍受白名单约束）。若采纳，需把 `enforce` 移入适配器或让适配器持有 `Sandbox`——**会改变本节的构造契约**，故留待有 `Sandbox` 实现时评估 |
| 4.2 | **`url` 的 `${ENV_VAR}` 占位解析** | **不做**（[data-model.md](../data-model.md) §5 末段）——A6 末段那段口径随第 26 节的写入路径一并定 | 随第 26 节定；第 24 节若需要，应连同写入路径一起考虑（占位符是**写入时**校验还是**读取时**解析，是个设计选择） |

---

## 5. 已知接缝总表（与 [plan.md](../plan.md) 「已知接缝」一致）

| 留白 | 由谁补 | 本节已就位的部分 |
| --- | --- | --- |
| `@Tool` 的注册机制（谁扫描、谁包装） | 第 20 节 | 注解已加（惰性）；方法签名已按可包装的形状定死 |
| `Sandbox` 的实现（`WhitelistSandbox`） | 第 23 / 24 节 | 调用位已就位：`enforce` 先于 `send`，顺序已用 `InOrder` 钉死 |
| `notify_channels` 的写入路径（CRUD / 管理台） | 第 26 节 | 表与存取已就位（只读）；运行期表恒为空是预期行为 |
| 通知相关 Bean 的 Spring 装配 | 第 24 节 | 本文件 §2 的清单（构造签名 + 依赖 + 前置条件） |
| `url` 的 `${ENV_VAR}` 占位解析 | 第 26 节 | 本节不假装实现了它 |

**不在接缝清单里的两处**（本节明确不做、非接缝）：逐跳白名单复检（§4.1）、对端业务错误码解析（[research.md](../research.md) D4，属扩展阶段的专用渠道对接）。
