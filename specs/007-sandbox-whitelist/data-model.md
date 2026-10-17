# Phase 1 数据模型：Sandbox 白名单

**Feature**: `specs/007-sandbox-whitelist` | **Branch**: `class-24` | **Date**: 2026-10-17

**本文件没有数据库部分**——本节**不新增表、不新增实体、不改 `schema.sql`、不改任何 Repository**。白名单是启动时从 `application.yaml` 一次性读入、运行期只读的**进程内配置**（章程原则 VIII 的「状态外置」管的是状态，配置不是状态）。

下面描述的是**内存里的模型**：三个配置值对象、一个实现类的内部状态、以及三类校验的判定规则。

---

## §1 实体总览

```text
application.yaml
├── file.allowed_paths:    List<String>  ──► FileSandboxProperties  ─┐
├── shell.allowed_commands: List<String> ──► ShellSandboxProperties ─┼──► WhitelistSandbox
└── http.allowed_domains:   List<String> ──► HttpSandboxProperties  ─┘        │
                                                                              │ implements
                                                                              ▼
                                                                        Sandbox（骨架期接口，不改）
                                                                              ▲
                                        SandboxAction{ActionType, String} ────┘ 每次调用传入
```

| 实体 | 类型 | 新增/既有 | 生命周期 |
| --- | --- | --- | --- |
| `FileSandboxProperties` | `record`（`@ConfigurationProperties(prefix = "file")`） | **新增** | 启动时绑定一次，运行期只读 |
| `ShellSandboxProperties` | `record`（`@ConfigurationProperties(prefix = "shell")`） | **新增** | 同上 |
| `HttpSandboxProperties` | `record`（`@ConfigurationProperties(prefix = "http")`） | **新增** | 同上 |
| `WhitelistSandbox` | `class implements Sandbox` | **新增** | 装配时构造一次，运行期只读；**无状态、无可变字段** |
| `Sandbox` / `SandboxAction` / `ActionType` / `SandboxViolationException` | 接口 / 记录 / 枚举 / 异常 | **既有（骨架期）** | **本节一行不改**（FR-011） |
| `PermissiveSandbox` | `class implements Sandbox` | **删除** | — |

---

## §2 三个配置值对象

### §2.1 字段与绑定

| 记录组件 | 配置键（**逐字**） | 类型 | 缺省 |
| --- | --- | --- | --- |
| `FileSandboxProperties.allowedPaths` | `file.allowed_paths` | `List<String>` | 空表 |
| `ShellSandboxProperties.allowedCommands` | `shell.allowed_commands` | `List<String>` | 空表 |
| `HttpSandboxProperties.allowedDomains` | `http.allowed_domains` | `List<String>` | 空表 |

**语义**：`allowed_paths` = 允许访问的**目录前缀**集合（不是精确文件）；`allowed_commands` = 允许执行的**可执行文件名**集合（精确名，不含路径也不含参数）；`allowed_domains` = 允许访问的**域名**集合（精确名或 `*.` 通配模式）。

### §2.2 不变式

| # | 不变式 | 判定方式 |
| --- | --- | --- |
| N1 | **配置块整个缺失时归一化为空表，不是 `null`** | 紧凑构造器里 `x == null ? List.of() : List.copyOf(x)`（与 `ProviderProperties` 逐字同形）。`CliTestContext.boot()` 就不给这三块配置，故这不是理论情形 |
| N2 | **值不可变** | `List.copyOf` 产出不可变副本 |
| N3 | **元素不保证非空** | 空/纯空白条目的过滤发生在 `WhitelistSandbox` 构造时（§3.2），**不在**值对象里——值对象只做绑定，不做语义解释 |
| N4 | **不含任何凭证** | 三个键都是白名单条目，不是密钥；`application.yaml` 里也不出现任何 token（域名不是凭证） |

**为什么过滤不放在值对象里**：值对象是「配置的形状」，`WhitelistSandbox` 是「配置的语义」。把语义塞进值对象会让「读配置」和「解释配置」两件事混在一起，且 `WhitelistSandbox` 单测就必须绕一层构造值对象才能验过滤。

---

## §3 实现类：`WhitelistSandbox`

### §3.1 对外面（FR-005）

| 成员 | 可见性 | 说明 |
| --- | --- | --- |
| `WhitelistSandbox(FileSandboxProperties, ShellSandboxProperties, HttpSandboxProperties)` | `public` | 唯一构造器；三个值对象由装配处注入 |
| `enforce(SandboxAction)` | `public` | **唯一**的对外方法（`@Override`） |
| `checkFilePath` / `checkShellCommand` / `checkHttpUrl` | `private` | 三个校验；由反射测试钉死「对外只有 `enforce` 一个入口」 |

**不变式 N5**：类上**不带** `@Component` / `@Configuration`（第 20 节契约 §6.3）；它由 `CliAssemblyConfiguration` 显式 `new` 出来声明为 Bean。

### §3.2 内部状态（构造时一次性标准化，运行期只读）

| 字段 | 类型 | 标准化规则 |
| --- | --- | --- |
| `allowedRoots` | `List<Path>` | 逐条 `Path::of → normalize → toAbsolutePath`；**丢弃空/纯空白条目**；不可变副本 |
| `allowedCommands` | `Set<String>` | 逐条 `trim()` 后**丢弃空串**；**不做**大小写折叠（那是文件名）；不可变副本 |
| `allowedDomainPatterns` | `List<String>` | 逐条 `trim()` → `toLowerCase(Locale.ROOT)`；**丢弃空串**；不可变副本 |

**为什么 `allowedRoots` 必须转绝对路径**：默认配置值是 `.`（相对路径），而校验目标被转成绝对路径——相对与绝对 `startsWith` 恒为 false，不转会让默认配置**全拒**（D4①）。

**为什么空条目要丢**：spec Edge Case 要求「空串或纯空白条目 MUST NOT 变成匹配一切的通配符」。逐条看：路径条目 `""` 会归一成 CWD（等价于放行整个工作目录）、命令条目 `""` 会与「空白命令切出的空 token」**正好配上**（D4④）。丢弃是唯一一处同时堵住这两个口的做法。

**为什么域名折叠大小写、命令不折叠**：域名本身大小写不敏感（`API.Example.COM` 与 `api.example.com` 是同一台主机）；命令名是文件名，`ls` 与 `LS` 在 Linux 上是两个不同的可执行文件——折叠会**凭空放行**一个白名单里没有的程序（spec Edge Case 明写）。

### §3.3 `enforce` 的分派

| `ActionType` | 路由 | 校验方法 |
| --- | --- | --- |
| `FILE_READ` | ↓ 同一路 | `checkFilePath` |
| `FILE_WRITE` | ↑ 同一路 | `checkFilePath` |
| `SHELL_COMMAND` | | `checkShellCommand` |
| `HTTP_REQUEST` | | `checkHttpUrl` |
| （未来新增的值） | | `default` 分支抛 `IllegalStateException`——**不静默放行** |

**不变式 N6**：`FILE_READ` 与 `FILE_WRITE` **同路由**到同一份路径白名单（FR-002 / spec 仲裁 5）。两类在 `ActionType` 里分开建模是留给未来分权的接缝，不是本节的行为差异。

### §3.4 三类校验的判定规则

#### 路径（`checkFilePath`）

```text
target = Path.of(rawPath).normalize().toAbsolutePath()
放行 ⟺ ∃ root ∈ allowedRoots : target.startsWith(root)     # Path.startsWith 是组件级比对
```

| 输入 | 白名单 | 判定 | 依据 |
| --- | --- | --- | --- |
| `/workspace/notes.txt` | `/workspace` | 放行 | 前缀命中（目录前缀不是精确文件） |
| `/workspace/a/b.txt` | `/workspace` | 放行 | 前缀比对覆盖子目录 |
| `/etc/passwd` | `/workspace` | 拒 | 不在任何 root 下 |
| `/workspace/../../outside/secret.txt` | `/workspace` | 拒 | `normalize()` 后等价于 `/outside/secret.txt` |
| `/workspace-evil/x` | `/workspace` | 拒 | **组件级**比对：`workspace-evil` 不是 `workspace` 的子路径（D10） |
| `/workspace` 自身 | `/workspace` | 放行 | `startsWith` 含相等情形 |

#### 可执行文件（`checkShellCommand`）

```text
firstToken = command.trim().split("\\s+")[0]
放行 ⟺ firstToken 非空 且 firstToken ∈ allowedCommands        # 精确比对，不折叠大小写
```

| 输入 | 白名单 | 判定 | 依据 |
| --- | --- | --- | --- |
| `ls -la` | `ls` | 放行 | 只比首 token，参数不参与 |
| `"  ls -la"` | `ls` | 放行 | `trim()` 先跑——前导空白 MUST NOT 成为绕过手段 |
| `rm -rf /` | `ls` | 拒 | 首 token `rm` 不在表内 |
| `LS` | `ls` | 拒 | 精确比对（那是文件名） |
| `"   "` / `""` | 任意（含 `""` 条目） | 拒 | 切出的 token 为空即拒——不能因为「拿不到首个 token」就放行（D4④） |

**与「argv 直传、不经 Shell 解释」的关系**：`ShellTools` 把命令拆成 argv 直接交给 `ProcessBuilder`，不经 Shell，因此没有 `&&` / `;` / `$()` 这类解释层；本校验只认 `argv[0]`，与那条口径一致（FR-003）。**这不代表命令本身安全**——`ls --help` 无害不代表所有白名单命令都无害，白名单是**劝阻级**防线，配置权归管理员。

#### 域名（`checkHttpUrl`）

```text
host = URI.create(url).getHost()          # 解析失败或 host 为 null → 直接拒
放行 ⟺ ∃ pattern ∈ allowedDomainPatterns :
         pattern 以 "*." 开头 → host.endsWith(pattern.substring(1))   # substring(1) 保留了那个点号 = 边界
         否则               → host.equals(pattern)
（host 与 pattern 都已折叠为小写）
```

| URL | 白名单 | 判定 | 依据 |
| --- | --- | --- | --- |
| `https://api.example.com/x` | `*.example.com` | 放行 | 子域命中 |
| `https://evil-example.com/x` | `*.example.com` | 拒 | `"evil-example.com".endsWith("example.com")` 为真，但 `.endsWith(".example.com")` 为假——**点号边界**（D4 的核心理由） |
| `https://example.com/x` | `*.example.com` | 拒 | 通配符只覆盖子域，裸域不命中 |
| `https://api.example.com/x` | `api.example.com` | 放行 | 精确相等 |
| `https://other.example.com/x` | `api.example.com` | 拒 | 精确相等 |
| `https://API.Example.COM/x` | `api.example.com` | 放行 | 两侧折叠小写（域名大小写不敏感） |
| `file:/etc/passwd` / `mailto:a@b` | 任意 | 拒 | host 为 null（D4②） |
| `不是 URL` | 任意 | 拒 | `URI.create` 抛 `IllegalArgumentException` → 包成 `SandboxViolationException`（D4②） |

**通配符的层级语义**：`*.example.com` 覆盖**任意层级**子域（`a.b.example.com` 也命中）——这是 `endsWith(".example.com")` 的自然语义，与主流 web 服务器 `ServerAlias` 同款。spec 只规定了单层，故**不写测试钉死多层级**（D9），只在 javadoc 写明。

### §3.5 空配置语义（FR-006 / US4）

**不变式 N7**：三块白名单都为空时，**四类动作全部被拒**。实现上这不是一条分支，而是三条规则的自然结果：`∃ root ∈ {}` 恒假、`token ∈ {}` 恒假、`∃ pattern ∈ {}` 恒假。

**这条口径 MUST 写进 `application.yaml` 的注释**（SC-006）：运维不需要读源码就知道「忘了配 = 什么都跑不了」（可见、可查），而不是「什么都放行」（静默、危险）。

---

## §4 跨模块不变式（本节最该被 review 的部分）

| # | 不变式 | 可判定形态 |
| --- | --- | --- |
| N8 | **校验先于任何真实 IO**（FR-013） | 接线回归测试断言「底层执行器一次都没被调用」：文件没被创建 / 进程没被创建 / 请求计数为 0 / 通知适配器没被调 |
| N9 | **校验失败 = 一次普通的工具执行失败**（FR-010 / 章程原则 VI） | `SandboxViolationException` 是普通 `RuntimeException`；`ToolExecutor` 的既有 try/catch 接住它；`ToolExecutor.java` 的 `git diff` 为空；既有 `ToolExecutorTest.recordsFailedInvocationWithReason` 覆盖该路径 |
| N10 | **`Sandbox` 接口与三个骨架期值对象零改动**（FR-011 / 章程原则 VII） | 四个文件 `git diff` 为空 |
| N11 | **五个内置工具零改动**（FR-009 / SC-004） | `FileTools` / `ShellTools` / `HttpTools` / `NotifyTools` / `WebSearchTools` 五个文件 `git diff` 为空 |
| N12 | **容器里 `Sandbox` 类型的 Bean 恰好一个**（SC-005） | `ToolAssemblyTest` 断言 `hasSize(1)` **且** `isInstanceOf(WhitelistSandbox.class)`——只断言后者会在「两个都在」时假绿 |
| N13 | **白名单不下放到 Agent** | `AGENT.md` frontmatter 里没有白名单字段（既有禁止项，本节不新增）；配置只从 `application.yaml` 读 |

---

## §5 与存储层的边界（本节明确「不做什么」）

| 事项 | 本节 | 说明 |
| --- | --- | --- |
| 新表 / 新实体 / `schema.sql` | **不动** | 白名单是配置，不是状态 |
| `tool_invocations` 的写入 | **不动** | 复用既有路径（N9） |
| 白名单的审计（谁改了白名单） | **不做** | 核心阶段白名单是随包发的配置文件，改动由 git / 部署流程留痕；配置变更审计归扩展阶段 |
| 白名单的热更新 | **不做** | 改配置重启生效（spec Assumptions） |
