# Contract: CLI(12 命令)

**来源**: spec.md FR-080/081、FR-025、FR-050;`docs/TechnicalSolution.md` §8.6/§8.7。Picocli 实现,主入口 `OryxOsCli`;`java -jar oryxos-cli-*.jar <command>` 或脚本 `oryxos <command>`。

**轻/重命令分工**(FR-081):轻命令(`init`、`profile list/create/show/delete`、`provider list`、`tool list`、`session list`、`status`)直接走文件 + SQLite 操作,不启动 Spring 上下文,保证启动速度;重命令(`chat`、`serve`、`gateway` 等需要 LLM 或完整运行时的)启动 Spring 上下文。

## 启动和状态

### `oryxos init`

在当前目录创建 `.oryxos/` 工作区:子目录 `agents/ skills/ memory/ sessions/ logs/ output/`、Bootstrap 文件 `AGENTS.md SOUL.md USER.md`、配置模板 `mcp_servers.yaml notify_channels.yaml`、空 `MEMORY.md`(含两个分区 header),并内嵌执行建表脚本创建 `oryxos.db`。**幂等**:已存在的目录与文件一律不覆盖,重复执行输出已有项与新建项清单。

### `oryxos status`

输出工作区概览:工作区路径、已注册 Agent 数(含校验失败数)、Provider 映射表及密钥就绪状态(只报 ready/missing,不打印密钥)、活跃会话数、定时任务数、数据库可达性。

### `oryxos chat [--profile <name>] [--message "<text>"]`

交互式对话(CliChannel):多轮对话,`/quit` 退出;`--profile` 指定 Agent(缺省 = 工作区唯一 Agent;多于一个且未指定时清晰报错并列出可选——frontmatter 无默认字段,唯一即默认);`--message` 单条消息模式,输出响应后正常退出码 0。对话完整累积到 Session(渠道 `cli`,用户 `local`)。

### `oryxos serve [--port <8080>]`

启动 Web Service(契约见 [rest-api.md](rest-api.md));定时任务随进程常驻调度。前台运行,Ctrl+C 退出。

### `oryxos gateway`

守护进程模式(Clarification Q2):挂载已配置的全部入站 Channel(核心阶段仅 CLI Channel 可配,以非交互方式挂载)+ 常驻定时调度,不启 REST。渠道配置错误清晰报错(哪个 Agent、哪个 channel、缺什么);无可用渠道时明确提示但仍常驻调度。扩展阶段 IM Channel 即插即用,语义不变。

## Profile 管理

### `oryxos profile list`

列出 `.oryxos/agents/` 全部 Agent:name、description、provider、有效/无效(无效附原因)。轻命令,直接扫目录。

### `oryxos profile create <name>`

生成 `.oryxos/agents/<name>/AGENT.md` 最小模板(合法 frontmatter 占位 + 正文引导注释),已存在则报错不覆盖。

### `oryxos profile show <name>`

展示该 Agent 派生后的完整 Profile(frontmatter 解析结果)+ 校验结果。

### `oryxos profile delete <name>`

删除 Agent 目录(需确认;`--force` 跳过确认)。核心阶段物理删除,归档式删除属扩展阶段 Agent 生命周期。

## 查询

### `oryxos provider list`

列出 provider name → 模型/端点/密钥就绪状态的显式映射表(不打印密钥本体)。

### `oryxos tool list`

列出 ToolRegistry 全量 Tool:name、description、来源(builtin / annotation / `mcp:<server>`)。

### `oryxos session list`

列出会话:sessionId、profile、channel、user、status、lastActiveAt(按最近活跃排序)。

## 通用约定

- 全部命令 `--help` 可用;参数错误退出码 2 并打印用法;运行错误退出码 1 并给清晰中文报错;成功退出码 0。
- 需要工作区的命令在非工作区目录执行时,报错提示先跑 `oryxos init`。
- 三种运行模式(`chat`/`serve`/`gateway`)共享同一份 Profile 配置与 Session 存储(FR-050)。
