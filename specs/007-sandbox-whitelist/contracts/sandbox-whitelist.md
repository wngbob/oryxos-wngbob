# 契约：Sandbox 白名单

**Feature**: `specs/007-sandbox-whitelist` | **Branch**: `class-24` | **Date**: 2026-10-17

本文件冻结本节对**对外字面量**、**校验语义边界**、**配置载体**、**装配与删除面**的承诺。后续 tasks / 实现 / 验收不得偏离；要偏离先走软门禁（停下报告）。

**上游契约**：第 20 节的 [`specs/005-tool-registry/contracts/assembly.md`](../../005-tool-registry/contracts/assembly.md) §3 是本节装配改动的**逐字依据**；骨架期的 [`specs/000-runtime-kernel/contracts/sandbox-config.md`](../../000-runtime-kernel/contracts/sandbox-config.md) 被本节**取代**（§7①）。

---

## §1 冻结字面量

### §1.1 本节新增（交付即冻结）

| 类型 | 全限定名 | 形态 |
| --- | --- | --- |
| `WhitelistSandbox` | `io.oryxlabs.oryxos.tool.sandbox.WhitelistSandbox` | `public class implements Sandbox`；**不带** `@Component` / `@Configuration` |
| `FileSandboxProperties` | `io.oryxlabs.oryxos.tool.sandbox.FileSandboxProperties` | `public record`，`@ConfigurationProperties(prefix = "file")` |
| `ShellSandboxProperties` | `io.oryxlabs.oryxos.tool.sandbox.ShellSandboxProperties` | `public record`，`@ConfigurationProperties(prefix = "shell")` |
| `HttpSandboxProperties` | `io.oryxlabs.oryxos.tool.sandbox.HttpSandboxProperties` | `public record`，`@ConfigurationProperties(prefix = "http")` |

**唯一构造器签名（冻结）**：

```java
public WhitelistSandbox(FileSandboxProperties fileProps,
                        ShellSandboxProperties shellProps,
                        HttpSandboxProperties httpProps)
```

**唯一对外方法（冻结）**：`public void enforce(SandboxAction action)`。三个校验方法 MUST 是 `private`（FR-005）——用反射测试钉死「除 `enforce` 外没有别的 public 方法」。

### §1.2 骨架期既有（**MUST NOT 修改**，FR-011）

| 类型 | 冻结内容 |
| --- | --- |
| `Sandbox` | `void enforce(SandboxAction action);` —— 签名逐字不动 |
| `SandboxAction` | `public record SandboxAction(ActionType type, String target) {}` |
| `ActionType` | `FILE_READ` / `FILE_WRITE` / `SHELL_COMMAND` / `HTTP_REQUEST` **四个值，MUST NOT 增删**（D6：SMTP 不新增第五值） |
| `SandboxViolationException` | `extends RuntimeException`，`public SandboxViolationException(String message)` |

**判定方式**：这四个文件 `git diff` 为空。

### §1.3 异常消息口径（FR-001）

消息 MUST 同时带上**哪一类**与**被拒的目标**，因为这条消息会原样进 `tool_invocations.error_message`，模型下一轮与运维都会读它。三条固定前缀（**逐字**）：

| 校验 | 消息形态 |
| --- | --- |
| 路径 | `路径不在白名单内: <原始输入路径>` |
| 命令 | `命令不在白名单内: <首 token>` |
| 域名 | `域名不在白名单内: <host 或原始输入>` |

- 路径那条带**原始输入**（不是标准化后的路径）——运维看到的是模型给的那串，便于对号。
- 命令那条带**首 token**（不是整条命令）——判定对象就是它，带上整条会让人以为整条都被比过。
- 域名那条：host 解析失败（无 host / URL 不合法）时带**原始输入**（此时没有 host 可带）。
- **MUST NOT 带任何凭证**：URL 里可能嵌 token（`https://user:pass@host/`），故域名那条**只带 host 或原始 URL 的字符串形式**，不带其它上下文；本节的三个白名单键本身不含凭证。

---

## §2 三类校验语义

判定表逐条见 [data-model.md](../data-model.md) §3.4。本节约定的**边界规则**（每一条都有测试钉死）：

| # | 规则 | 为什么 |
| --- | --- | --- |
| 2.1 | 路径 MUST `normalize()` **且** `toAbsolutePath()` 后再比对；白名单侧同样处理 | 相对与绝对 `startsWith` 恒为 false；默认值 `.` 不转绝对会让默认配置全拒 |
| 2.2 | 前缀比对 MUST 是**路径组件级**（`Path.startsWith`），MUST NOT 是字符串前缀 | `/workspace-evil/x` 不得被 `/workspace` 放行 |
| 2.3 | `FILE_READ` 与 `FILE_WRITE` MUST 同路由到同一个路径校验 | 本节读写共用一份白名单（仲裁 5） |
| 2.4 | 命令 MUST 只比对 `trim()` 后的**第一个 token**；MUST NOT 折叠大小写 | 与「argv 直传、不经 Shell」一致；命令名是文件名 |
| 2.5 | 首 token 为**空**即拒（含全空白命令） | `"".split("\\s+")` 返回 `[""]`，不加判断会在「空条目」组合下正好放行 |
| 2.6 | 域名通配 `*.x.com` MUST 以**点号边界**匹配（`.x.com`），MUST NOT 裸用 `endsWith("x.com")` | `evil-example.com` 的经典漏洞 |
| 2.7 | host 比对 MUST 不区分大小写（两侧 `toLowerCase(Locale.ROOT)`） | 域名大小写不敏感 |
| 2.8 | host 为 null 或 URL 解析失败 MUST 被拒（转成 `SandboxViolationException`） | 不得因 null 比较的意外结果放行，也不得抛 NPE 让运维读到看不懂的失败 |
| 2.9 | 空 / 纯空白条目 MUST 在构造时丢弃 | 空条目不得变成「匹配一切」的通配符 |
| 2.10 | 三块白名单都为空 = **全拒**，MUST NOT 是「不校验」 | FR-006；这是三条规则的自然结果，不是一条分支 |
| 2.11 | `enforce` 的 `default` 分支 MUST 抛异常，MUST NOT 静默放行 | 将来加第五个 `ActionType` 忘了加路由时当场炸 |

---

## §3 配置载体与键名

### §3.1 载体

`oryxos-boot/src/main/resources/application.yaml`（唯一一份 `application.yaml`；重命令与 fat JAR 都从 `oryxos-boot` 的 classpath 读）。

### §3.2 键名（**逐字冻结**，snake_case）

```yaml
file:
  allowed_paths: [...]
shell:
  allowed_commands: [...]
http:
  allowed_domains: [...]
```

- 三个键**不带 `oryxos.` 前缀**（§6.7 原文即如此）。
- 形态是 **snake_case**（课件与 §6.7 逐字），不是本仓别处惯用的 kebab-case。Spring 的 relaxed binding 让 `allowed-paths` 等形态也能绑上，**但文档与随包默认只认这一种形态**——两处真相会让运维不知道以哪份为准。
- 改键名 = 改已定字面量 → 软门禁 ②。`ToolAssemblyTest` 有一条用例把这三个字面量钉死（D8）。

### §3.3 随包默认（**逐条冻结，MUST NOT 自行增删**，仲裁 4）

```yaml
file:
  allowed_paths: ["."]              # 当前工作目录（启动时标准化为绝对路径）
shell:
  allowed_commands: [ls, cat, head, tail, grep, find, wc, pwd, git]
http:
  allowed_domains:
    - api.duckduckgo.com            # 免 key 搜索档的端点
    - api.tavily.com                # 带 key 搜索档的端点
    - open.feishu.cn                # 飞书群机器人
    - open.larksuite.com            # Lark（与飞书同一套协议）
    - qyapi.weixin.qq.com           # 企业微信群机器人
```

**这是「可用默认」不是「安全默认」**：随包发出来只为开箱能跑通演示；生产部署 MUST 由运维按最小权限重新收紧。

### §3.4 注释口径（**两条都 MUST 写进配置注释**，SC-006）

1. **「留空 = 全拒」**：白名单为空 = 什么都不允许，**不是**「不校验」。忘了配的后果是「什么都跑不了」（可见、可查），而不是「什么都放行」（静默、危险）。
2. **「劝阻级防线」**：应用层白名单防的是模型犯傻与提示注入导致的越权，**防不住蓄意绕过**（命令拼接、软链接、编码绕过）。核心阶段 MUST NOT 用它跑完全不可信的代码、MUST NOT 对外做多租户。

---

## §4 装配改动与删除面

### §4.1 `CliAssemblyConfiguration` 的两处改动

| # | 改动 | 形态 |
| --- | --- | --- |
| 1 | 类上补 `@EnableConfigurationProperties({FileSandboxProperties.class, ShellSandboxProperties.class, HttpSandboxProperties.class})` | 三个值对象由此注册；**不新建 `@Configuration` 类**（第 20 节契约 §6.3） |
| 2 | `sandbox()` 方法体 | `return new WhitelistSandbox(fileProps, shellProps, httpProps);` |

**方法签名**（冻结，与第 20 节契约 §3 逐字一致）：

```java
@Bean
@ConditionalOnMissingBean(Sandbox.class)
public Sandbox sandbox(FileSandboxProperties fileProps,
                       ShellSandboxProperties shellProps,
                       HttpSandboxProperties httpProps) {
  return new WhitelistSandbox(fileProps, shellProps, httpProps);
}
```

- 返回类型取**接缝类型** `Sandbox`，不取具体类。
- 保留 `@ConditionalOnMissingBean(Sandbox.class)`：这是**有意留的覆盖接缝**——部署方自建 `Sandbox` Bean（例如未来的容器档）即接管。
- 声明位置不变（`toolRegistry()` 之前那一带），**不引入任何新的声明顺序约束**：`sandbox()` 不参与让位链，它的位置不影响任何 `@ConditionalOnMissingBean` 的求值。

### §4.2 删除面（仲裁 3）

| # | 删除对象 | 为什么 |
| --- | --- | --- |
| 1 | `oryxos-tool/.../tool/sandbox/PermissiveSandbox.java` | **fail-open** 组件：留在树里，Bean 声明一旦被误改（顺序 / 条件注解 / 被别处覆盖）就静默回到「全放行」，兜底的只有一条 WARN 日志 |
| 2 | `oryxos-tool/src/test/.../tool/sandbox/PermissiveSandboxTest.java` | 守的对象已不存在 |
| 3 | 装配处 `sandbox()` 的方法体（`return new PermissiveSandbox();`） | 见 §4.1 |

**与 `EmptyToolCatalog` / `EmptyMemorySource` 的区别**（**别把两者混为一谈**）：那两个让位后是 no-op（空目录 / 无记忆），**这个让位后是「什么都放行」**。所以那两个「不回头删代码」，这个必须整类删除。

### §4.3 连带触碰（只去掉对已删类的引用，其余一字不动）

| 文件 | 触碰 | 为什么 |
| --- | --- | --- |
| `oryxos-tool/src/test/.../registry/AnnotatedToolAdapterTest.java` | `new PermissiveSandbox()` → 真 `WhitelistSandbox`（三个空表） | **编译必需**。该用例只读 `notify` 的 JSON Schema、不执行工具，故空白名单不影响它；用真实现而不是再造一个全放行替身 |
| `oryxos-tool/src/test/.../builtin/RecordingSandbox.java` | javadoc 里「为什么不直接注入 `PermissiveSandbox`」一句改写 | 该替身的存在理由正是「记录型 vs 全放行」，类名没了这句话悬空 |
| `oryxos-cli/src/test/.../LogStreamSeparationTest.java` | 一条 `@DisplayName` / 注释里的引用改写 | 那条断言守的是「dev 档根级别不被压到 ERROR」这个**通用**日志契约，理由句里引的类已删 |
| `oryxos-boot/src/main/resources/logback-spring.xml` | 第 14 行 XML 注释里「含 `PermissiveSandbox` 那条**故意**打的 WARN」那一处括号引用改写（删去该引用即可——窗口期已关闭，那条 WARN 不会再打） | 该注释解释的是「为什么 dev 档 `ConsoleAppender` 走 stderr」；引用句里点名的类已删，留着会让人以为还有一条沙箱 WARN 在生效。**`<target>System.err</target>` 那一行与 stdout/stderr 分流的理由 MUST NOT 改动**（CLAUDE.md「这行不要删」仍有效）；`LogStreamSeparationTest` 的断言只针对 `<target>` 行与结构标记、**不 grep 注释文本**，故改注释不破测试 |

**删除面完整性判定**（2026-10-17 全仓核实）：`grep -rln "PermissiveSandbox" oryxos-*/src` 共 **8** 个文件命中——本表 4 个 + §4.2 的 2 个删除对象 + `CliAssemblyConfiguration`（§4.1）+ `ToolAssemblyTest`（T007 改写）。**没有第 9 个**；Phase 2 的 checkpoint 用同一条 grep 兜底。

**MUST NOT 触碰** `specs/005-tool-registry/` 下的历史文档——它们记录第 20 节当时的形态，改它们等于篡改历史留痕。

---

## §5 测试契约（harness 映射）

| 测试类 | 位置 | 覆盖 | 关键回归点 |
| --- | --- | --- | --- |
| `WhitelistSandboxTest` | `oryxos-tool/src/test/.../tool/sandbox/` | 三类校验「允许 + 拒绝」成对 + 绕过场景 + 空配置全拒 + 入口唯一 | 路径穿越被拦 / 前导空白与大小写变体 / `evil-example.com` 被拒 / 反射断言对外只有 `enforce` |
| `SandboxToolWiringTest` | `oryxos-tool/src/test/.../tool/sandbox/` | **真** `WhitelistSandbox` 接 `FileTools` / `ShellTools` / `HttpTools` / `NotifyTools` 各一条 | **真正的 IO 没有发生**（文件没被创建 / 进程没起 / 请求计数 0 / 适配器没被调）——不是「抛了异常」 |
| `ToolAssemblyTest`（改写） | `oryxos-cli/src/test/.../cli/` | 容器里 `Sandbox` Bean **恰好一个**且是 `WhitelistSandbox`；容器里已有别的 `Sandbox` Bean 时白名单实现让位；三个配置键字面量真的绑得上 | 「恰好一个」而不是「存在一个是」（后者在「两个都在」时假绿） |

**测试方法名 MUST 是英文**（驼峰或 snake_case）。课件 harness 里给出的中文方法名翻译成语义等价的英文名，**课件原文进 `@DisplayName`** 以便对号。

**接线回归为什么必须证明「IO 没有发生」**：只断言「抛了异常」是**自证**——「先做 IO 再校验」的实现同样会抛异常，测试照样绿（第 20 节的 `RecordingSandbox` 就是为这件事造的，其 javadoc 原文已写明）。

---

## §6 本节 MUST NOT

| # | 禁止 | 依据 |
| --- | --- | --- |
| 6.1 | 改 `Sandbox` / `SandboxAction` / `ActionType` / `SandboxViolationException` 四个文件的任何一个字节 | FR-011 / 章程原则 VII |
| 6.2 | 改五个内置工具（`FileTools` / `ShellTools` / `HttpTools` / `NotifyTools` / `WebSearchTools`） | FR-009 / SC-004 |
| 6.3 | 改 `ToolExecutor`，或为 Sandbox 造独立审计路径 | FR-010 / 章程原则 VI |
| 6.4 | 给 `ActionType` 加第五个值（SMTP 等） | D6：核心阶段无消费者，且破坏接口中立性论证 |
| 6.5 | 在非 boot 模块新增 `@Component` / `@Configuration` 类 | 第 20 节契约 §6.3 |
| 6.6 | 用匿名类 / lambda 充当装配处的沙箱 Bean | 第 20 节契约 §6.4（无法让位、无法断言、无法写 javadoc） |
| 6.7 | 在 `WhitelistSandbox` 上暴露除 `enforce` 之外的 public 方法 | FR-005 / 第 23 节「别让接口被第一档带偏」 |
| 6.8 | 用 Java `SecurityManager` | 章程原则 VII（JDK 17 起废弃、JDK 21 已不可用） |
| 6.9 | 把白名单下放到 `AGENT.md` frontmatter | spec Assumptions：配置权归部署管理员 |
| 6.10 | 加进程内白名单缓存 / 热加载 | spec Assumptions：改配置重启生效；缓存只会制造「改了没生效」的假象 |
| 6.11 | 把三个配置键改成 kebab-case 或加 `oryxos.` 前缀 | §3.2：已定字面量，改即软门禁 ② |
| 6.12 | 在验收报告 / 配置说明里把应用层白名单表述为「安全隔离」 | spec「诚实标注」：它是**劝阻级**防线 |
| 6.13 | 改写 `specs/005-tool-registry/` 的历史文档以「保持一致性」 | 那是第 20 节的留痕，改了等于篡改历史 |

---

## §7 文档连带修订

| # | 文件 | 修订 | 依据 |
| --- | --- | --- | --- |
| ① | `specs/000-runtime-kernel/contracts/sandbox-config.md` | **加**带日期的修订注（2026-10-17 被 `specs/007-sandbox-whitelist` 取代 + 理由 + 读写分权推迟），**不改历史正文** | 仲裁 1 连带 |
| ② | `docs/TechnicalSolution.md` §6.7 | 「四个校验」→ 三个；SMTP 记扩展阶段（挂日期修订注） | 仲裁 2 连带 |
| ③ | `CLAUDE.md` | ① 沙箱窗口期条目改为「已关闭」；② 「四个校验」→ 三个；③ 缓解③「自动让位、不回头删代码」按裁决 3 回修；④ 「当前状态」「待办」「Tool 体系 → Sandbox」三处更新 | 仲裁 1/2/3 连带 + 节级收尾 |

**MUST NOT 触碰 `website/`**：本次不涉及对外定位 / 能力 / 路线图表述（`CLAUDE.md`「对外内容三处同步」那条规则不触发）。
