# Feature Specification: Sandbox 白名单 —— 把「受控执行」这道墙后面的第一档实现填上

**Feature Directory**: `specs/007-sandbox-whitelist`

**Git Branch**: `class-24`

**Created**: 2026-10-17

**Status**: Draft

**Input**: User description: "第24节需求：Sandbox 白名单——把「在受控环境里执行动作」这道墙后面的第一档实现填上，并接进已有的内置 Tool"（课件：`docs/class/第24节：Sandbox 实现与代码讲解.md`；评审依据 `docs/class/第23节：Sandbox 原理解析、业界方案与 OryxOS 设计评审.md`；章节依据 `docs/TechnicalSolution.md` §6.7）

## Clarifications

### Session 2026-10-17

本节开工前有四处冲突/抉择触发了软门禁，已全部报用户裁决。四条裁决是后续 plan / tasks / 实现的硬约束。

- Q: 沙箱白名单的配置载体与键名，以哪份文档为准？仓库里有两份互斥的写法——课件 24 + 技术方案 §6.7 写 `application.yaml` 的 `file.allowed_paths` / `shell.allowed_commands` / `http.allowed_domains`；骨架期冻结的 `specs/000-runtime-kernel/contracts/sandbox-config.md` 写 `.oryxos/sandbox.yaml` 的 `file.read` / `file.write` / `shell.executables` / `http.domains`。 → A: **以课件 24 + 技术方案 §6.7 为准**（`application.yaml`，三个键）。判据：① 课件「本节交付物」逐字列出这三个键；② `CLAUDE.md`「工作区目录」一节列 `.oryxos/` 产物时本来就没有 `sandbox.yaml`；③ `specs/003-cli-session/contracts/cli-surface.md` 与 `data-model.md` 都写着 `oryxos init` **MUST NOT** 创建 `sandbox.yaml`。**连带**：`specs/000-runtime-kernel/contracts/sandbox-config.md` 那份契约被本节取代，须挂带日期的修订注（不改它的历史正文，只加注）。**连带**：`file.read` / `file.write` 的读写分权设计随之推迟——本节按课件口径读写共用同一份路径白名单（见 FR-002）。

- Q: `WhitelistSandbox` 要不要做第四类 SMTP `host:port` 校验？技术方案 §6.7 与 `CLAUDE.md` 记的是「四个校验」（含 `checkSmtpEndpoint` + `smtp.allowed_endpoints`），课件 24 的「本节交付物」只列三个 `@ConfigurationProperties`（file / shell / http）。 → A: **只做三类**。判据：① `ActionType` 只有四值、**没有 SMTP 的路由**，`checkSmtpEndpoint` 在核心阶段无路可走；② 它没有消费者——`EmailNotifyAdapter` 明确归扩展阶段（技术方案 §6.8）；③ 加第五个 `ActionType` 是改第 20 节定死的接口字面量（软门禁 ②），且会破坏「用 microVM 反套接口签名」的中立性论证。**连带**：技术方案 §6.7 与 `CLAUDE.md` 里「四个校验」的表述按本节口径修订为三个（挂带日期的修订注），SMTP 记为扩展阶段。

- Q: 临时的 `PermissiveSandbox` 怎么处置？`CLAUDE.md` 缓解③写「自动让位，**不回头删代码**」，第 20 节契约 `contracts/assembly.md` §3 写「第 24 节在装配处加一行 … 并**删掉**本节那行 `sandbox()`」。 → A: **整类删除**。删除范围：`PermissiveSandbox` 类、`PermissiveSandboxTest`、装配处 `sandbox()` Bean 方法；装配处改为声明 `whitelistSandbox()`。判据：这是一个 **fail-open** 组件——留在代码树里，一旦 Bean 声明被误改（顺序、条件注解、被别处覆盖）就会静默回到「全放行」，兜底的只有一条 WARN 日志。与 `EmptyToolCatalog` / `EmptyMemorySource` 那两个先例**不同类**：那两个让位后是 no-op（空目录 / 无记忆），这个是「什么都放行」。**连带**：`ToolAssemblyTest` 的两条沙箱断言须改写（其中「让位」那条失去意义，见 FR-008）；`CLAUDE.md` 缓解③ 的表述须回修。

- Q: `application.yaml` 里的白名单默认值给什么？课件只说「留空 = 什么都不允许」要写进配置说明，没说随包发什么。 → A: **给一份能开箱用的默认**（文件 = 当前工作目录；命令 = 一小撮只读命令；域名 = 免 key 搜索档 + 带 key 搜索档 + 三家 IM 群机器人）。判据：项目既有取舍同向（搜索 key 那个占位**带空默认值**，理由是「装上就能跑」），且第 31 节三个验收 Demo 不必先改配置。**连带**：「留空 = 全拒」这条口径仍 MUST 写进配置说明（FR-006）——默认给的是「一份具体的清单」，不是「空 = 不校验」。

  **默认清单逐条钉死**（用户报批时已确认的具体取值，plan / tasks 照此落地，MUST NOT 自行增删条目）：

  ```yaml
  file:
    allowed_paths: ["."]              # 当前工作目录（启动时标准化为绝对路径）
  shell:
    allowed_commands: [ls, cat, head, tail, grep, find, wc, pwd, git]
  http:
    allowed_domains:
      - api.duckduckgo.com            # 免 key 搜索档的端点
      - api.tavily.com                # 带 key 搜索档的端点（CLAUDE.md 明记其白名单归本节）
      - open.feishu.cn                # 飞书群机器人
      - open.larksuite.com            # Lark（与飞书同一套协议）
      - qyapi.weixin.qq.com           # 企业微信群机器人
  ```

  这份清单**不是**「安全默认」，是「可用默认」——它随包发出来只是为了开箱能跑通演示；生产部署 MUST 由运维按最小权限重新收紧（章程「安全基线」：默认收紧、按需放开）。

## 范围说明

第 20 节起工具已经进了容器、`chat` 里真能调，但沙箱挂的是临时实现 `PermissiveSandbox`：它只打一条启动 WARN、**不做任何白名单校验**。2026-10-14 端到端实测确认过后果——`read_file /etc/passwd` 会真的成功（`specs/005-tool-registry/acceptance-report.md` §2 证据 5）。这段窗口期是**已报批的取舍**，不是 bug：第 20 节必须让工具能跑起来才能交付「Agent 能动手」这件事，白名单本体归本节。

第 23 节把方向定死了：**接口先定、墙焊死、实现只做第一档**。本节就是那句话的兑现——把 `Sandbox` 接口后面的唯一实现 `WhitelistSandbox` 填上。做完之后，章程原则 VII 里「核心阶段的沙箱 = `Sandbox` 接口 + `WhitelistSandbox`」这句表述**第一次成立**，Tool 治理从「没有」变成「有第一档」。

**本节做的三件事，量很小**：一个实现类、三个配置值对象、一处装配替换。这正是第 23 节反复讲的那点——**当接口设计对了，接入成本小到不成比例**。三个已经写好的 Tool 加 `enforce` 只用了一行（那行还是第 20 节就加好的），审计不用碰，`ToolExecutor` 不用碰。

**诚实标注（MUST 出现在配置说明与验收报告里）**：应用层白名单是**劝阻级**防线，防的是模型犯傻误操作，防不住蓄意绕过（命令拼接、软链接、编码绕过……）。核心阶段 MUST NOT 用它跑完全不可信的代码、MUST NOT 对外做多租户（章程「安全基线」与原则 IV）。

**本节交付物范围**（课件「本节交付物」逐项）：

- **代码**：`WhitelistSandbox`（唯一实现）+ 三个 `@ConfigurationProperties` 值对象（file / shell / http）。`Sandbox` 接口、`SandboxAction`、`ActionType`、`SandboxViolationException` 四个是骨架期已交付的**墙**，本节一行不改
- **测试**：`WhitelistSandboxTest`（三类校验成对 + 绕过场景）+ 接线回归（白名单外的输入被拦、真正的 IO 没有发生）+ 装配层「容器里 `Sandbox` 类型的 Bean 恰好一个且是 `WhitelistSandbox`」
- **配置**：`application.yaml` 的 `file.allowed_paths` / `shell.allowed_commands` / `http.allowed_domains` 三块
- **改造点**：`FileTools` / `ShellTools` / `HttpTools` / `NotifyTools` 各在执行首行加 `sandbox.enforce(...)`——**前序节已经加好了**（`NotifyTools` 在第 19 节随类创建，其余四个在第 20 节），本节验收的是「换成真实现之后它们仍然零改动即生效」

### 明确不做（边界）

课件「不做的事」逐项照搬，另加本节裁决带出的两项：

- 不写**容器实现**、不写 **microVM 实现**（归扩展阶段，信号驱动升级；信号见第 23 节）
- 不引入 Java `SecurityManager`（JDK 17 起废弃、JDK 21 已不可用，章程原则 VII 明令）
- **不改 `ToolExecutor` 的审计逻辑**（它已经有失败路径，Sandbox 拒绝就是一次普通的工具执行失败，直接复用）
- 不做第四类 **SMTP** `host:port` 校验（裁决 2；`ActionType` 无 SMTP 路由、核心阶段无消费者）
- 不做 **Profile 级 Tool Policy**（哪个 Agent 能用哪些 Tool，扩展阶段；核心阶段 Profile 的 `tools` 字段算是雏形）
- 不做**热更新**：白名单改后重启生效（核心阶段不做配置热加载）
- 不做**危险操作审批层**（Claude Code permission mode / Codex approval mode 那类白名单之外的中间层，第 24 节之后评估）
- 不把白名单**下放到 Agent**：配置权归部署管理员，Agent MUST NOT 自声明（`AGENT.md` frontmatter 禁止项，见 `specs/000-runtime-kernel/contracts/agent-directory.md` §2）

## 冲突仲裁记录（本规约据此撰写，后续 plan / tasks 不得偏离）

| # | 事项 | 裁决 | 依据 |
| --- | --- | --- | --- |
| 1 | 配置载体与键名 | `application.yaml` + `file.allowed_paths` / `shell.allowed_commands` / `http.allowed_domains` | 裁决 1（2026-10-17 用户）；课件「本节交付物」原文 |
| 2 | SMTP 第四类校验 | **不做**，记扩展阶段 | 裁决 2；`ActionType` 只有四值，无 SMTP 路由 |
| 3 | `PermissiveSandbox` | **整类删除**（类 + 测试 + `sandbox()` Bean 方法） | 裁决 3；fail-open 组件不留树 |
| 4 | 白名单默认值 | 随包发一份**可开箱用**的清单；「留空 = 全拒」仍写进说明 | 裁决 4；项目「装上就能跑」既有取舍 |
| 5 | 读写是否分权 | 本节 `FILE_READ` / `FILE_WRITE` **共用同一份**路径白名单 | 课件 3.1 说明原文；`specs/000` 的 `file.read` / `file.write` 拆分随裁决 1 推迟 |
| 6 | 校验方法的可见性 | 三个校验方法**私有**，`WhitelistSandbox` 对外只有 `enforce` 一个入口 | 课件 3.2；第 23 节「别让接口被第一档带偏」 |
| 7 | `WhitelistSandbox` 的声明方式 | 装配处 `@Bean`（**不带** `@Component`） | 第 20 节契约 §6.3：`MUST NOT` 在非 boot 模块加 `@Component` / `@Configuration`；全部 `@Bean` 唯一落点是 `CliAssemblyConfiguration`。课件 3.2 的 `@Component` 是示意写法（第 20 节课件同样给工具画了 `@Component`，本仓一贯改为装配处声明） |
| 8 | 五个工具的 `enforce` 首行 | **本节零改动**（`NotifyTools` 第 19 节、其余四个第 20 节已交付） | FR-009；`git diff` 为空是可判定证据 |

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 白名单外的文件路径读不到：模型在动手之前就被拦 (Priority: P1)

运维在一台企业内网机器上部署 OryxOS，把 `file.allowed_paths` 收紧到工作目录。模型在 ReAct 循环里生成 `read_file`，参数是 `/etc/passwd`（可能是模型犯傻，也可能是被一段提示注入骗的）。工具在**真正读文件之前**就被拦下，文件一个字节都没被读到；模型下一轮看到「路径不在白名单内: /etc/passwd」这条失败原因，知道这条路走不通，换一条路。

**Why this priority**: 这是本节存在的理由。第 20~23 节窗口期里这条路径会真的成功（实测已确认），本节之后必须变成「读不到 + 留痕」。

**Independent Test**: 用一个只允许 `/workspace` 的白名单构造沙箱，对 `/etc/passwd` 与 `/workspace/notes.txt` 各发一次 `enforce`——前者抛异常、后者不抛。再用真 `WhitelistSandbox` 接上 `FileTools`，断言 `read_file /etc/passwd` 被拦且底层读文件一次都没发生。

**Acceptance Scenarios**:

1. **Given** 白名单只允许 `/workspace`，**When** 校验 `/etc/passwd`，**Then** 抛 `SandboxViolationException`，消息里带原始路径
2. **Given** 白名单只允许 `/workspace`，**When** 校验 `/workspace/notes.txt`，**Then** 放行，不抛异常
3. **Given** 白名单只允许 `/workspace`，**When** 校验 `/workspace/../../outside/secret.txt`（用 `..` 爬到白名单之外），**Then** 抛异常——标准化之后它不在白名单里
4. **Given** 白名单只允许 `/workspace`，**When** 模型发起一次 `read_file /etc/passwd` 的工具调用，**Then** 落一条 `tool_invocations`（`success=false`、`error_message` 是校验失败原因），且真实 IO 没有发生

---

### User Story 2 - 白名单外的命令一次都没被执行：拦在进程起来之前 (Priority: P1)

白名单配了只允许 `ls`。模型生成 `rm -rf /`——工具在 `ProcessBuilder` 起来之前就被拦下，**进程根本没起**，磁盘一个字没动；`tool_invocations` 里落一条 `success=false`。

**Why this priority**: 与 US1 同为 P1。命令执行的后果不可逆（删了就是删了），这是「劝阻级防线」里最该拦住的一类。

**Independent Test**: 用只允许 `ls` 的白名单构造沙箱，校验 `rm -rf /`（抛）与 `ls -la`（不抛）；再断言 `ls` 带前导空白（`"  ls"`）与大小写变体（`LS`）都不会因为这两个花样被误放行。

**Acceptance Scenarios**:

1. **Given** 白名单只允许 `ls`，**When** 校验 `ls -la`，**Then** 放行（只比对首个 token，参数不参与判定）
2. **Given** 白名单只允许 `ls`，**When** 校验 `rm -rf /`，**Then** 抛异常，消息里带首个 token（`rm`）
3. **Given** 白名单只允许 `ls`，**When** 校验带前导空白的 `"  ls -la"`，**Then** 放行——前导空白 MUST NOT 变成绕过手段
4. **Given** 白名单只允许 `ls`，**When** 校验 `LS`，**Then** 抛异常——比对是精确比对，大小写不折叠
5. **Given** 白名单只允许 `ls`，**When** 模型发起一次 `shell` 工具调用跑 `rm -rf /`，**Then** 进程一次都没被创建，且 `tool_invocations` 有 `success=false`

---

### User Story 3 - 通配符域名拦住形似域名：注入诱导访问不到 (Priority: P2)

白名单配了 `*.example.com`。模型被提示注入诱导去访问 `https://evil-example.com/x`——它以 `example.com` 结尾，但**不是**子域，必须被拦下；而 `https://api.example.com/x` 正常放行。

**Why this priority**: P2。这是 `endsWith` 实现的经典漏洞（`"evil-example.com".endsWith("example.com")` 为真），是最容易写错、也最容易在评审里被挑出来的一处。它不改变交付的骨架，但决定 `matchesDomain` 该怎么写。

**Independent Test**: 用 `*.example.com` 的白名单构造沙箱，对 `https://api.example.com/x` 断言放行、对 `https://evil-example.com/x` 断言抛异常；另加一条精确匹配（白名单写 `api.example.com`，则 `example.com` 本身与 `api.example.com` 的判定各按相等）。

**Acceptance Scenarios**:

1. **Given** 白名单是 `*.example.com`，**When** 校验 `https://api.example.com/x`，**Then** 放行
2. **Given** 白名单是 `*.example.com`，**When** 校验 `https://evil-example.com/x`，**Then** 抛异常
3. **Given** 白名单是 `*.example.com`，**When** 校验 `https://example.com/x`（裸域，不是子域），**Then** 抛异常——通配符只覆盖子域
4. **Given** 白名单是 `api.example.com`（精确），**When** 校验 `https://api.example.com/x`，**Then** 放行；**When** 校验 `https://other.example.com/x`，**Then** 抛异常

---

### User Story 4 - 空配置 = 全拒，不是「不校验」 (Priority: P3)

新运维手上有一份白名单全空的配置。他的第一反应可能是「没配 = 不限制」。系统必须让他当场看到相反的事实：**空 = 什么都不允许**，任何动作都被拒；这条口径写在配置说明里，而不是靠他去猜。

**Why this priority**: P3。它是安全默认值的正确性，而不是新能力；但它决定了运维第一次上手时的心智模型——「忘了配」的后果是「什么都跑不了」（可见、可查），而不是「什么都放行」（静默、危险）。

**Independent Test**: 三块白名单都为空构造沙箱，四类动作各校验一次，全部抛异常；并断言配置说明文件里有这条口径。

**Acceptance Scenarios**:

1. **Given** 三块白名单都为空，**When** 校验任意文件路径 / 任意命令 / 任意域名，**Then** 一律抛 `SandboxViolationException`
2. **Given** 白名单里有条目但都不匹配，**When** 校验一个不在其中的目标，**Then** 抛异常（不是「表非空就放行」）

---

### Edge Cases

- **路径穿越的多种写法**：`..` 序列、`./` 前缀、重复分隔符——标准化之后一律按最终路径判定（`/workspace/../../etc/passwd` 与 `/etc/passwd` 等价）
- **相对路径**：白名单配的是相对路径（如 `.`）时，判定基准是**进程当前工作目录**（配置在启动时一次性标准化为绝对路径）
- **`file` 白名单是目录前缀而不是精确文件**：`/workspace` 应当放行 `/workspace/a/b.txt`（前缀比对，不是字符串相等）
- **前缀比对的假朋友**：`/workspace-evil/x` MUST NOT 被 `/workspace` 放行——前缀必须落在**路径分隔符**边界上
- **命令里只有空白**：`trim()` 后切不出 token，MUST 被拒（不能因为「拿不到首个 token」就放行）
- **URL 没有 host**：`URI.create(url).getHost()` 返回 null 时 MUST 被拒，MUST NOT 因为 null 比较的意外结果放行
- **域名大小写**：host 比对 MUST 不区分大小写（域名本身大小写不敏感），但 shell 命令比对**区分**大小写（那是文件名）
- **配置里写了空串或纯空白的条目**：MUST NOT 变成「匹配一切」的通配符

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 三类校验各成对——白名单内放行、白名单外拒绝并抛 `SandboxViolationException`；异常消息 MUST 带上是哪一类、以及被拒的目标（人能读懂，因为这条消息会原样进 `tool_invocations.error_message`）
- **FR-002**: 文件路径校验 MUST 先标准化（`normalize()` + 转绝对路径）再做前缀比对，使 `../` 路径穿越爬不出白名单目录；`FILE_READ` 与 `FILE_WRITE` 两类动作 MUST 同路由到同一个路径校验（读写共用同一份白名单）
- **FR-003**: 可执行文件校验 MUST 只比对命令的**第一个 token**（`trim()` 后按空白切分），与「argv 直传、不经 Shell 解释」的口径一致；带前导空白的输入 MUST NOT 因此绕过；MUST NOT 做大小写折叠
- **FR-004**: HTTP 域名校验 MUST 解析 host 后匹配：精确条目按相等；通配符 `*.example.com` MUST 命中 `api.example.com`，MUST NOT 命中 `evil-example.com`（必须以点号边界匹配，MUST NOT 裸用 `endsWith("example.com")`）；host 比对不区分大小写
- **FR-005**: 三个校验方法 MUST 是**私有**方法，`WhitelistSandbox` 对外只暴露 `enforce(SandboxAction)` 一个入口（接口 MUST NOT 被第一档实现带偏；第 23 节的自查手段）
- **FR-006**: 白名单为空 = **什么都不允许**（全拒），MUST NOT 是「不校验」；这条口径 MUST 写进配置说明
- **FR-007**: 三类白名单 MUST 各有独立的 `@ConfigurationProperties` 值对象（file / shell / http），配置落在 `application.yaml`
- **FR-008**: 装配处 MUST 把 `WhitelistSandbox` 声明为 `Sandbox` 类型的 Bean，并**替换掉**临时的 `PermissiveSandbox`：类、它的测试、装配处那个 `sandbox()` Bean 方法一并删除。替换后容器里 `Sandbox` 类型的 Bean **恰好一个**且是 `WhitelistSandbox`
- **FR-009**: 五个已经接了 `enforce` 的内置工具（`FileTools` / `ShellTools` / `HttpTools` / `NotifyTools` / `WebSearchTools`）MUST 保持**零改动**——它们的 `enforce` 首行是前序节交付的（`NotifyTools` 第 19 节、其余四个第 20 节），本节只换墙后面的实现
- **FR-010**: 校验失败 MUST 从 Tool 抛出并被 `ToolExecutor` 既有 try/catch 接住，落 `tool_invocations`（`success=false`、`error_message` 为校验失败原因），MUST NOT 新增任何审计代码、MUST NOT 为 Sandbox 造独立审计路径（章程原则 VI）
- **FR-011**: `Sandbox` / `SandboxAction` / `ActionType` / `SandboxViolationException` 四个骨架期契约 MUST NOT 被修改（章程原则 VII：升级只新增实现类，MUST NOT 改接口、MUST NOT 改调用方）
- **FR-012**: 三类白名单的配置项 MUST 在 `application.yaml` 里给出可开箱用的默认值（裁决 4 的默认清单逐条钉死，MUST NOT 自行增删条目），且每块的注释 MUST 写明「留空 = 全拒」与「劝阻级防线，防不住蓄意绕过」两条口径
- **FR-013**: 校验 MUST 发生在**任何真实 IO 之前**（读文件 / 写文件 / 起进程 / 发请求），顺序颠倒 MUST 被测试钉死——「先做 IO 再校验」同样会抛异常，只断言「抛了异常」是自证

### Key Entities *(include if feature involves data)*

- **受控动作（`SandboxAction`）**：一次待校验的动作，含「类型」与「目标」两项。目标是纯字符串，具体是路径、可执行文件还是 URL 由类型决定——**这是既有的墙，本节不改**
- **动作类型（`ActionType`）**：四值——文件读 / 文件写 / Shell 命令 / HTTP 请求。文件读写分开建模便于未来按读/写分权限；本节两类同路由到同一份路径白名单
- **三类白名单（配置值对象）**：路径白名单（目录前缀集合）、可执行文件白名单（精确名字集合）、域名白名单（精确或通配模式集合）。三者都是**启动时一次性读入**、运行期只读
- **沙箱违规（`SandboxViolationException`）**：校验失败的信号，作为一次普通的工具执行失败进入既有审计路径——**不是**独立的一类审计事件

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 第 20~23 节窗口期里能成功的越界动作（`read_file /etc/passwd` 这类）在装配后的运行时里 **100% 被拦**，且每次被拦都在 `tool_invocations` 留一条 `success=false`
- **SC-002**: 三类校验的「放行 / 拒绝」成对用例**全部**通过，且每类的绕过场景（路径穿越、前导空白、形似域名）各有一条独立用例把守
- **SC-003**: 白名单之外的**危险动作实际没有发生**——文件没被读写、进程没被创建、请求没被发出。判据不是「抛了异常」，而是「底层执行器一次都没被调用」
- **SC-004**: 换成另一档实现（容器 / microVM）**不需要给 `Sandbox` 接口加方法、不需要改任何调用方**；五个工具的代码改动行数为 **0**
- **SC-005**: 容器里 `Sandbox` 类型的 Bean **恰好一个**，且是 `WhitelistSandbox`（不是「存在一个是它」——后者在「两个都在」时会假绿）
- **SC-006**: 配置说明里能读到两条口径：「空 = 全拒」与「劝阻级防线，防不住蓄意绕过」——运维不需要读源码就能建立正确心智模型
- **SC-007**: 既有测试全绿（前 20 节共 451 条），唯一允许变更的是那两条为窗口期写的沙箱断言（SC-005 是它们的新形态）

## Assumptions

- **配置作用域是全局**：一份白名单对所有 Agent 生效，无 per-Agent 白名单（需要差异化放行 = 拆 Agent、拆部署，扩展阶段再议）
- **配置权归部署管理员**：Agent MUST NOT 自声明白名单（`AGENT.md` frontmatter 禁止项）
- **生效时机是启动时**：改白名单后重启生效，核心阶段不做热更新
- **软连接的越界判定**：Skill 软连接指向的公共实体库在核心阶段由部署方保证落在白名单内；软连接真实目标的解析（防软链接绕过）**不在本节范围**——它是「防蓄意绕过」那一类，而本节白名单按章程定位是劝阻级
- **威胁模型**：跑的是企业自己配的、相对可信的 Agent，防的是模型误操作与提示注入导致的越权，不是「跑陌生人上传的代码」
- **前序节交付物已就位**：`Sandbox` 接口与三个值对象（骨架期）、五个工具的 `enforce` 首行接线（`NotifyTools` 第 19 节、其余四个第 20 节）、`ToolExecutor` 的失败审计路径（第 17 节）、`CliAssemblyConfiguration` 作为全部 `@Bean` 的唯一落点（第 18 节）
- **外部依赖**：无新增
