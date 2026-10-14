# Contract: CLI 对外命令面（12 条）

**来源**：[spec.md](../spec.md) FR-002 / FR-003 / FR-010 / FR-015 / FR-016 / FR-021；上游 `specs/000-runtime-kernel/contracts/cli.md`（**本节是它的落地版**，冲突处以本文件为准并已记入 spec 的冲突仲裁记录）。这是**用户可见的对外契约**：命令名、参数、退出码的变更必须走修订程序。

---

## 1. 命令清单与轻 / 重归属

**判断标准只有一条**（课件原话）：这条命令要不要调模型、跑引擎？要 → 重命令，启动 Spring 上下文；不要 → 轻命令，直接走文件与 SQLite，秒回。

| # | 命令 | 轻重 | 实现类 | 数据来源 |
| --- | --- | --- | --- | --- |
| 1 | `oryxos init` | 轻 | `InitCommand` | 文件系统 + 原生 JDBC（建表） |
| 2 | `oryxos status` | 轻 | `StatusCommand` | 文件系统 + 原生 JDBC（计数） |
| 3 | `oryxos chat [--profile <name>] [--message <text>]` | **重** | `ChatCommand` | Spring 上下文 |
| 4 | `oryxos serve [--port <8080>]` | **重** | `ServeCommand` | Spring 上下文 |
| 5 | `oryxos gateway` | **重** | `GatewayCommand` | Spring 上下文 |
| 6 | `oryxos profile list` | 轻 | `ProfileListCommand` | 文件系统（`ProfileLoader.loadAllDetailed()`） |
| 7 | `oryxos profile create <name>` | 轻 | `ProfileCreateCommand` | 文件系统 |
| 8 | `oryxos profile show <name>` | 轻 | `ProfileShowCommand` | 文件系统（`ProfileLoader.loadAllDetailed()`） |
| 9 | `oryxos profile delete <name> [--force]` | 轻 | `ProfileDeleteCommand` | 文件系统 |
| 10 | `oryxos provider list` | 轻 | `ProviderListCommand` | `ProviderProperties.SUPPORTED_PROVIDERS` + 文件系统 |
| 11 | `oryxos tool list` | 轻 | `ToolListCommand` | `ToolCatalog` 接缝（本节空实现） |
| 12 | `oryxos session list` | 轻 | `SessionListCommand` | 原生 JDBC（`sessions` 表） |

**Picocli 组织**：12 个叶子命令类 + 4 个只做分组的类（`ProfileCommand` / `ProviderCommand` / `ToolCommand` / `SessionCommand`），挂在 `OryxOsCli` 的 `subcommands` 上。对外是**空格分隔的多级命令**（`oryxos profile list`）——Picocli 的子命令按首个 token 匹配，名为 `"profile list"` 的单个子命令永远匹配不上，多级命令只能用「组 + 子命令」表达（[research.md](../research.md) D12）。

**全部 12 条 MUST 有可用的 `--help`**（FR-002），包括 4 个分组命令。

---

## 2. 逐条行为契约

### 2.1 `oryxos init`

在当前目录创建 `.oryxos/` 工作区，产物清单见 [data-model.md](../data-model.md) §7。

- **幂等**：已存在的目录与文件**一律不覆盖**；重复执行输出「已有」与「新建」两份清单（FR-001）。
- 建出 `oryxos.db` 与**全部表**：内嵌执行 `oryxos-storage` 的 `db/schema.sql`（classpath 读取 + `;` 切分 + `Statement.execute`，沿用 `LlmCallRepositoryTest` 的既有做法）。
- **MUST NOT 生成任何默认 Agent**（spec 仲裁 14）——`agents/` 建出来是空目录。
- **MUST NOT 创建** `notify_channels.yaml` / `sandbox.yaml`（spec 仲裁 7）。

### 2.2 `oryxos status`

输出工作区概览。**三段必须标注未接入，MUST NOT 编造数值**：

| 输出段 | 本节的数据来源 | 未接入部分 |
| --- | --- | --- |
| 工作区路径 | 文件系统 | — |
| Agent 数（含无效数） | `ProfileLoader.loadAllDetailed()` | — |
| 活跃会话数 | 原生 JDBC 查 `sessions` 表 `status = 'active'` | — |
| 数据库可达性 | 原生 JDBC 试连 | — |
| 底座支持的厂商全集 | `ProviderProperties.SUPPORTED_PROVIDERS` | 密钥就绪状态标「未接入（第 26 节）」 |
| 定时任务数 | — | 标「未接入（第 28 节）」 |

### 2.3 `oryxos chat [--profile <name>] [--message <text>]`

- **交互式**（无 `--message`）：读一行 → 交给引擎 → 打印结果，循环往复；`/quit` 退出；**输入流结束（Ctrl+D / 管道读完）等同 `/quit`**，退出码 0（FR-004、Edge Cases）。
- **单条模式**（`--message "<text>"`）：输出响应后正常退出，退出码 0，**不进入交互循环**（FR-005）。
- `--profile` 缺省时取工作区**唯一**的 Agent；**多于一个且未指定 MUST 清晰报错并列出可选名**（FR-004）。
- 渠道固定 `cli`、用户固定 `local`（§3 的三元组）；对话完整累积到 `Session` 并落库。
- **`chat` 本身不碰模型调用与工具调度**——它经 `CliChannel` → `AgentService.process`（章程原则 IX）。

### 2.4 `oryxos serve [--port <8080>]` / `oryxos gateway`

**本节只启动 Spring 上下文并前台常驻（Ctrl+C 退出）**（spec 仲裁 15）。

- `serve`：打印「本模式将提供 HTTP 接入，REST 端点在第 26 节接入」。
- `gateway`：打印「本模式将挂载入站 Channel，通道接入在扩展阶段」。
- 两者**都 MUST NOT 起 HTTP 端口、MUST NOT 挂载任何 Channel、MUST NOT 引入 `oryxos-web`**。
- `serve --port` **MUST 出现在 `--help` 中并接受解析**，但只回显「端口在第 26 节启用」——上游 `contracts/cli.md` 已把它定为对外契约，MUST NOT 静默吞掉一个已定参数。
- 两条命令的 `run()` MUST **阻塞**（前台常驻），MUST NOT 启动完就退出——「启动完就退」等于退出码 0 的正常结束，是错的。

### 2.5 `oryxos profile list`

列出 `.oryxos/agents/` 全部 Agent：**name、description、provider、有效/无效（无效附原因）**。

- 原因经 `ProfileLoader.loadAllDetailed()` 取（[research.md](../research.md) D20）——**MUST NOT 在 CLI 侧重写校验规则**。
- 目录名即 Agent 名。**注意**：课件片段读的是 `.oryxos/profiles/`，那是示意错误（spec 仲裁 10），本节按 `.oryxos/agents/` 落地。

### 2.6 `oryxos profile create <name>`

生成 `.oryxos/agents/<name>/AGENT.md`：**合法 frontmatter 占位 + 正文引导注释**。

- **已存在则报错不覆盖**（FR-013）。
- 模板的 `provider.name` 占位必须落在 `ProviderProperties.SUPPORTED_PROVIDERS` 内，否则生成的模板一建出来就是「无效 Agent」。

### 2.7 `oryxos profile show <name>`

展示该 Agent **派生后的完整 Profile**（frontmatter 解析结果）+ **校验结果**。

- 无效时输出失败原因，来源同 §2.5（同一个原因源，两条命令不各写一份）。

### 2.8 `oryxos profile delete <name> [--force]`

删除 Agent 目录。**需确认**；`--force` 跳过确认。未确认时不删除、正常退出（退出码 0）。核心阶段为**物理删除**。

### 2.9 `oryxos provider list`

列出**底座支持的厂商全集**（`ProviderProperties.SUPPORTED_PROVIDERS`）+ **工作区各 Agent 实际引用的厂商名**。

- 密钥就绪状态一律标「**未接入（第 26 节）**」（spec 仲裁 6 / 11）。
- **MUST NOT 回显任何密钥值**——命令输出、结构化日志、报错三处都不得出现（章程原则 VIII / SC-006）。
- **MUST NOT 编造 ready / missing**——配置文件不在 `oryxos-cli` 的 classpath 上，读不到就是读不到，不许「猜一个环境变量名」凑出就绪状态。

### 2.10 `oryxos tool list`

列出 `ToolRegistry` 全量 Tool：name、description、来源（`builtin` / `annotation` / `mcp:<server>`）。

- 本节经 `ToolCatalog` 接缝取（空实现），**输出空表并注明「第 20 节接入 ToolRegistry」**（spec 仲裁 9）。MUST NOT 编造工具。
- 命令本身已按最终形态写（经接缝取、按来源分组打印），第 20 节只需换实现，**不改本命令**。

### 2.11 `oryxos session list`

列出会话：`sessionId`、`profile`、`channel`、`user`、`status`、`lastActiveAt`，**按最近活跃排序**（FR-012）。

- 走原生 JDBC 直读 `sessions` 表（轻命令，不启 Spring）。

---

## 3. 通用约定（12 条命令全部适用）

| 情形 | 退出码 | 行为 |
| --- | --- | --- |
| 成功 | **0** | 正常输出 |
| 参数错误 | **2** | 打印用法（`--help` 内容） |
| 运行错误 | **1** | 清晰中文报错，不打印 Java 堆栈给用户 |

**退出码交给 Picocli 的默认约定**（`CommandLine.ExitCode.USAGE` = 2 / `SOFTWARE` = 1 / 成功 0），`OryxOsCli.main` 既有的 `System.exit(new CommandLine(...).execute(args))` 保持不变。运行错误由命令实现抛带中文消息的异常，**MUST NOT 手工 `System.exit(1)`**——手工 exit 会跳过 Picocli 的异常处理与用法打印（[research.md](../research.md) D16）。

**工作区前置检查**：需要工作区的命令（除 `init` 外的全部）在非工作区目录执行时，MUST 报错并提示先跑 `oryxos init`。判定 = 当前目录存在 `.oryxos/` 子目录。

**三种运行模式共享同一份配置与存储**（FR-021）：`chat` / `serve` / `gateway` 读同一份 `.oryxos/agents/`、写同一个 `.oryxos/oryxos.db`。

---

## 4. 「未接入」的标注口径（本节特有，MUST 逐字遵守）

本节有 5 条命令的部分输出指向尚未落地的基础设施。**MUST 明确输出「未接入」并注明接入节号，MUST NOT 造一个假返回值把命令糊过去**（spec 范围说明末段）：

| 命令 | 未接入的输出段 | 标注文案 |
| --- | --- | --- |
| `provider list` | 密钥就绪状态 | 未接入（第 26 节） |
| `status` | 密钥就绪状态 | 未接入（第 26 节） |
| `status` | 定时任务数 | 未接入（第 28 节） |
| `tool list` | 工具清单 | 第 20 节接入 ToolRegistry |
| `serve` | HTTP 端口 | 端口在第 26 节启用 |

**为什么这条要写进契约**：编造一个「就绪」比留白更糟——用户会据此以为某个 Provider 可用，然后在 `chat` 时撞上失败，且不知道该信谁。留白 + 注明节号是**唯一诚实**的做法。
