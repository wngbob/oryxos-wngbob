# Contract: Agent 目录与工作区文件格式

**来源**: spec.md FR-001~007、FR-031/034、FR-044/046;`docs/TechnicalSolution.md` §8.1~8.3、§11.1~11.2;CLAUDE.md「核心数据模型」「工作区目录」。这是业务方直接手写的"语法契约",字段名与语义变更必须走修订程序。

## 1. Agent 目录 `.oryxos/agents/<name>/`

```text
agents/<name>/
├── AGENT.md        # 必需:frontmatter(运行配置)+ 正文(任务指令)
├── skills/         # 可选:指向 ../../skills/<skill>/ 的相对软连接集合
├── scripts/        # 可选:捆绑脚本(经白名单解释器或专用 Tool 使用)
└── REFERENCE.md    # 可选:参考资料,经 read_file 按需读取
```

- `<name>` = Agent 名 = Profile `name`,目录名即真相,不在 frontmatter 重复声明。
- 一个 Agent 目录**不是 Tool**,不注册进 ToolRegistry;正文由 ContextLoader 注入 system prompt(原则 IV)。
- 加载校验(FR-004):Provider 存在、Tool 已注册、Channel 受支持、Bootstrap 文件存在;失败不阻断启动,记错误日志,该 Agent 不可用。

## 2. `AGENT.md` frontmatter(YAML,`---` 包围)

| 字段 | 类型 | 必填 | 说明 |
| ------ | ------ | ------ | ------ |
| `description` | string | 是 | 一句话说明 Agent 干什么 |
| `identity.agent_name` | string | 否 | 人格名(展示用) |
| `identity.prompt` | string | 否 | 人格/系统提示词;可引用 SOUL.md |
| `provider.name` | string | 是 | 引用 Provider 映射表的 name(如 `deepseek` / `kimi`) |
| `provider.model` | string | 是 | 模型名 |
| `provider.temperature` | float | 否 | 采样温度 |
| `tools` | string[] | 否 | 可用 Tool 子集(缺省 = 空,极简按需给) |
| `mcp_servers` | string[] | 否 | 引用 `mcp_servers.yaml` 的 name |
| `channels` | object[] | 否 | `[{name, config}]`,gateway 模式挂载依据 |
| `schedules` | object[] | 否 | `[{cron, zone, message}]`,钟推定义源(唯一) |
| `bootstrap` | string[] | 否 | 额外 Bootstrap 文件列表(三个默认文件之外) |
| `settings.max_iterations` | int | 否 | 最大 ReAct 迭代,默认 10 |
| `settings.max_history_turns` | int | 否 | 对话历史截断轮数,默认 20 |

**禁止项**: frontmatter MUST NOT 含 `notify_channels`(渠道归全局注册表)、`skills`(绑定真相源是软连接)、明文密钥(用 `${ENV_VAR}` 占位,FR-012)。

**正文**: frontmatter 之后的 Markdown 全文 = 该 Agent 的任务指令,每轮全量注入 system prompt;可用自然语言按名引用通知渠道与 Skill。

## 3. Skill 绑定(软连接,唯一真相源)

- 公共实体:`.oryxos/skills/<skill>/SKILL.md`(frontmatter 至少含 `name`、`description`)+ 可选附属资源。
- 绑定:Agent 目录内 `skills/<skill>` 为指向 `../../skills/<skill>` 的**相对软连接**;每次组装 prompt 现扫,校验真实目标必须解析到公共 Skill 根内(防越界),只注入 `name` / `description` / Agent 本地绝对读取路径;Skill 正文与附属资源经 `read_file` 按需取用,不预载、不注册为 Tool。

## 4. `mcp_servers.yaml`

```yaml
servers:
  - name: github-mcp          # 注册名,AGENT.md 按名引用
    transport: stdio          # stdio | sse
    command: [npx, "-y", "@modelcontextprotocol/server-github"]   # stdio: argv 直传
    env: { GITHUB_TOKEN: "${GITHUB_TOKEN}" }                       # 凭证占位
  - name: news-hub
    transport: sse
    url: "http://127.0.0.1:9000/sse"                              # sse 用 url
```

- 启动时逐个连接、拉 `tools/list` 注册;单个 server 失败不阻断,记错误,其工具不可用(FR-044 边界情形)。

## 5. `notify_channels.yaml`(定义源,启动全量导入注册表)

```yaml
channels:
  - name: team-lark           # 注册名,AGENT.md 正文按名引用
    type: webhook             # 核心阶段仅 webhook(企微/飞书/钉钉群机器人通用)
    url: "${LARK_WEBHOOK_URL}"
    description: 研发团队群
```

- 启动时以文件为准全量重建 `notify_channels` 表;`url` 支持 `${ENV_VAR}` 占位;webhook 地址 MUST NOT 出现在对话内容中(FR-046)。

## 6. Bootstrap 文件与长期记忆

- `AGENTS.md`(项目级行为说明)/ `SOUL.md`(默认人格)/ `USER.md`(用户偏好,只读):每次组装 prompt 重新读取注入,不缓存,改完下轮生效(FR-007)。
- `memory/MEMORY.md`:两个一级分区 `## 核心记忆` / `## 归档记忆`,每条记忆带日期 header(如 `### 2026-10-10`);核心区永不截断/不检索,归档区 >4000 字截断、仅归档区参与关键词检索(FR-031)。

## 7. 工作区骨架(`oryxos init` 产物,幂等)

```text
.oryxos/
├── agents/  skills/  memory/MEMORY.md  sessions/  logs/  output/
├── mcp_servers.yaml  notify_channels.yaml  sandbox.yaml
├── AGENTS.md  SOUL.md  USER.md
└── oryxos.db
```

`sandbox.yaml` 为沙箱白名单配置(管理员全局、缺省全拒),契约见 [sandbox-config.md](sandbox-config.md)。
