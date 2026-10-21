# 契约：Agent 目录形态

**Feature**: `specs/012-plugin-agent` | **Date**: 2026-10-21

本契约定义「一个目录如何被认作一个 Agent」。**目录名 = Agent 名**，这是唯一真相源；不存在独立的 Profile 文件（`.oryxos/profiles/` 不存在）。

---

## §1 目录布局

```text
.oryxos/agents/<name>/          # 运行期位置（.oryxos/ 被 .gitignore 忽略）
├── AGENT.md                    # 必需：frontmatter + 正文
├── REFERENCE.md                # 可选：参考资料（按需 read_file）
├── skills/                     # 可选：子指令绑定目录
│   └── <link> -> ../../../skills/<entity>   # 相对软连接，指向公共实体
└── scripts/                    # 可选：脚本（按需 shell）
    └── <file>
```

仓库内的示例放在 `examples/agents/<name>/`，公共 Skill 实体放在 `examples/skills/<entity>/`；两者由验收向导复制进 `.oryxos/`。**布局内的相对路径关系完全一致**，同一条软连接在两种布局下都解析到各自的公共 Skill 根（research D6）。

---

## §2 `AGENT.md` 语法

### §2.1 结构

```markdown
---
<YAML frontmatter>
---

<正文：给模型的任务指令>
```

- 文件 MUST 以单独一行的 `---` 开头；MUST NOT 有前导空行（UTF-8 BOM 会被剥掉）。
- frontmatter MUST 以单独一行的 `---` 收尾；找不到 → `frontmatter 未闭合：找不到收尾的 ---`。
- frontmatter MUST 是 YAML 键值映射；用 `SafeConstructor` 解析（禁止 `!!` 标签实例化任意类）。
- 收尾 `---` 之后的全部文本是正文；正文可以为空。

### §2.2 允许的键

| 键 | 类型 | 必需 | 语义 |
|---|---|---|---|
| `description` | string | 否 | 一句话说明 |
| `identity.agent_name` | string | 否 | 人格名（展示用） |
| `identity.prompt` | string | 否 | 人格 / 系统提示词 |
| `provider.name` | string | **是** | 必须命中底座已知厂商清单 |
| `provider.model` | string | **是** | 模型名 |
| `provider.temperature` | number | 否 | 采样温度 |
| `tools` | string[] | 否 | 要用的底座能力；未注册的在运行期 WARN + 跳过 |
| `mcp_servers` | string[] | 否 | 引用的 MCP server |
| `channels` | map[] | 否 | 入站渠道（`{name, ...}`） |
| `schedules` | map[] | 否 | `{cron, zone, message}`；任务 id 由 `<Agent>#<序号>` 派生 |
| `bootstrap` | string[] | 否 | 额外 Bootstrap 文件 |
| `settings.max_iterations` | int | 否 | 默认 10 |
| `settings.max_history_turns` | int | 否 | 默认 20 |

### §2.3 禁止的键

| 键 | 报错文本 | 理由 |
|---|---|---|
| `skills` | `frontmatter 不允许声明 'skills'：Skill 归属由软连接表达、通知渠道归全局注册表` | 章程原则 IV：软连接是唯一真相源 |
| `notify_channels` | `frontmatter 不允许声明 'notify_channels'：Skill 归属由软连接表达、通知渠道归全局注册表` | 通知渠道归 SQLite 全局注册表（技术方案 §8.2） |

> **（2026-10-21 订正）** 两行的报错文本**不是**「同上」，而是同一条模板把键名插进去：`"frontmatter 不允许声明 '" + <键名> + "'：Skill 归属由软连接表达、通知渠道归全局注册表"`（`ProfileLoader#toProfile` 的 `FORBIDDEN_KEYS` 循环）。所以拿到 `notify_channels` 的报错时，消息里会明确出现那个键名，而不是一句含糊的「同上」。

### §2.4 被忽略的键

- `name`：不报错，但**不生效**——Agent 名取目录名。

### §2.5 环境变量占位

形状必须匹配 `\$\{([A-Za-z_][A-Za-z0-9_]*)}`。变量未设置 → 抛错，消息**只带变量名、不带值**。

**覆盖范围（逐字段，不是「所有字符串值」）**：`description`、`identity.*`、`provider.*`、`tools[]`、`mcp_servers[]`、`schedules[]` 的取值、`bootstrap[]`、以及 `channels[].name` 走 `stringValue`，即在加载时解析。**`channels[].config` 的其余值不解析**——`ProfileLoader#channels` 只对 `name` 调 `stringValue`，整个 map 原样放进 `ChannelSpec.config`；渠道凭证的解析在出站发送那一刻（`JpaNotifyChannelRegistry` / 各 Notify 适配器），且本仓的 `channels` 目前没有消费者。

> **（2026-10-21 订正）** 原文写「所有字符串值支持 `${VAR}`」，对 `channels[].config` 不成立——照原文写会把未解析的字面量当成已解析的凭证传下去。

---

## §3 资源识别

`ProfileLoader.inspect(Path)` 返回的 `resources` 是下列条目的**相对路径**、按字典序排序：

1. `scripts/` 的直接子**文件**，如 `scripts/reconcile.py`（不递归）；
2. `skills/` 的直接子**项**，如 `skills/report-format`（不跟随链接、不读内容）；
3. `REFERENCE.md`（存在时）。

**只反映存在性，MUST NOT 读取内容**——渐进式披露要求这些资源不被预载。

---

## §4 渐进式披露

| 资源 | 何时进上下文 | 由谁 |
|---|---|---|
| `AGENT.md` 正文 | 每次组装 prompt，**常驻** | `ContextLoader.appendAgentBody`（第 17 节，本节不改） |
| `skills/<link>` | 每次组装只注入 **name / description / 本地路径** | `ContextLoader.appendSkillBindings`（第 17 节，本节不改） |
| `REFERENCE.md` 正文 | 模型按需 `read_file` | 底座内置工具 |
| `scripts/<file>` 代码 | **永不进**；只有脚本产出进 | 底座 `shell` |

`ContextLoader` 不缓存：改完 `AGENT.md` 下一次组装即生效（第 17 节 `ContextLoaderTest` 已钉死，本节不重测）。

---

## §5 Skill 绑定

- 形态：`skills/<link>` MUST 是**相对软连接**；目标 MUST 解析到 `<oryxosDir>/skills/<entity>/` 之内。
- 悬空 → `Skill 绑定悬空：软连接 <link> 指向的目标不存在（<path>）`。
- 越界 → `Skill 绑定越出公共 Skill 目录: <link> → <target>`。
- 公共实体 MUST 含 `SKILL.md`；其 frontmatter 的 `name` / `description` 用于摘要注入，缺了只 WARN、不影响可用性。
- **MUST NOT 用普通文件代替软连接**：普通文件不是软连接，`toRealPath()` 落在 Agent 目录内，直接判越界。

---

## §6 安全边界（如实记录，不做缓解）

1. `scripts/` 里的脚本是**任意代码**。放行解释器后，`python3 scripts/x.py` 能读写文件、能自行发网络请求，**绕过 `http_get` 的域名白名单**（白名单只管内置工具，管不到子进程）。
2. **装一个带脚本的 Agent = 信任写它的人。** 核心阶段的沙箱是劝阻级防线，MUST NOT 用它跑完全不可信的代码、MUST NOT 对外做多租户。
3. 「脚本只能跑本 Agent 的 `scripts/` 目录」**本节不实现**——需要改第 20 / 24 节的沙箱契约（`SandboxAction` 只携带 `argv[0]`，看不到完整命令与 Agent 身份）。归扩展阶段的容器 / 网络隔离。

---

## §7 示例（`examples/agents/daily-reconcile/`）

```markdown
---
description: 每天核对交易库与清算库当日订单的条数与金额是否一致；有差异就按规范生成分级报告并推送
identity:
  agent_name: 对账小欧
  prompt: 你是一个严谨的对账助手，只根据脚本给出的确定性数据下结论，绝不臆测数字。
provider:
  name: deepseek
  model: deepseek-chat
  temperature: 0.2
tools: [shell, read_file, notify, save_memory]
schedules:
  - cron: "0 0 9 * * *"
    zone: Asia/Shanghai
    message: 到点了，核对昨天的订单对账。
---

你是每日订单对账助手。被触发时，严格按顺序做，不要跳步：
1. **拿数据（交给脚本）**：运行 `python3 .oryxos/agents/daily-reconcile/scripts/reconcile.py`，它返回一段 JSON：...
2. **判断**：...
3. **写报告**：按 system prompt 里注入的 report-format 子指令路径读 `SKILL.md` ...
4. **推送 + 留痕**：...
```

**正文路径口径（MUST）**：`shell` 工具以 JVM 进程 cwd 为工作目录（`ShellTools` 不调 `ProcessBuilder.directory()`），而两条生产启动路径（`cd` 到工作区根再跑 jar；`bin/start.sh` 的 `cd "$BASE_DIR"`）都使 **cwd = 工作区根**。因此正文引用本 Agent 目录内的资源 MUST 写**相对工作区根的路径**（`.oryxos/agents/<name>/...`），MUST NOT 写相对 Agent 目录的路径（`scripts/reconcile.py` 永远解析不到）。Skill 实体路径不手写——`ContextLoader` 每次组装已把**绝对路径**注入 system prompt，正文让模型「按注入的路径读」即可。

与课件原文的偏差（均已报批）：无 `name` / 无 `notify_channels` / `schedules` 无 `id` / `python` 写作 `python3` / `skills/report-format.md` 改为「公共实体 `SKILL.md` + 软连接」/ **脚本路径写作相对工作区根**（课件写 `scripts/reconcile.py`，照抄会解析不到文件）。
