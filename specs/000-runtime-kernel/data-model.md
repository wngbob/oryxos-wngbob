# Data Model: OryxOS 核心阶段运行时内核

**来源**: [spec.md](spec.md) Key Entities + `docs/TechnicalSolution.md` §8.5/§9.2 + 已落地的 `oryxos-storage/src/main/resources/db/schema.sql` 与契约接口。分三种存储形态:SQLite 表(关系数据)、文件系统(用户可维护数据)、内存派生(运行态索引)。

## A. SQLite 表(`.oryxos/oryxos.db`,Spring Data JPA)

建表一律走手工维护的 `schema.sql`(`ddl-auto=none`,不依赖 update 自动迁移)。核心阶段结束时应达 6 张表:骨架期已建 2 张审计表,本特性新增 4 张(`sessions`、`scheduled_tasks`、`task_executions`、`notify_channels`)。

### A1. `sessions`(新增)

会话元数据 + JSON 序列化对话历史。

| 字段 | 类型 | 说明 |
| ------ | ------ | ------ |
| `session_id` | VARCHAR(128) PK | `channel:user:profile` 三段联合生成(如 `cli:local:daily-weather`);钟推固定 `scheduler:scheduler:<profile>` |
| `profile_name` | VARCHAR(128) | 关联 Profile,必须命中 ProfileRegistry |
| `channel` | VARCHAR(32) | `cli` / `api` / `scheduler` / `gateway` |
| `user_id` | VARCHAR(128) | 用户标识;CLI 固定 `local`,钟推固定 `scheduler`,REST 由调用方传(默认 `default`) |
| `messages_json` | TEXT | JSON 数组,元素 `{role, content, toolCalls?, toolCallId?, timestamp}`,`role ∈ system/user/assistant/tool`;LLM 响应与 Tool 结果按序累积(FR-023) |
| `status` | VARCHAR(16) | `active` / `archived` |
| `created_at` / `last_active_at` / `archived_at` | TIMESTAMP | 创建 / 最后活跃 / 归档时间 |

**状态机**: `active` → `archived`(单向终态)。归档会话可查历史,但 MUST NOT 再接收消息(发消息返回 400)。**校验**:写入前 `profile_name` 必须存在;`messages_json` 序列化失败视为内部错误不落库。

### A2. `tool_invocations`(已建,骨架期)

每次 Tool 调用的审计留痕(FR-071)。字段:`id` AI PK、`session_id`、`tool_name`、`input_json`、`result_json`、`success`、`error_message`、`duration_ms`、`created_at`。

**本特性新增规则(Clarification Q3)**: 写入前对 `input_json` / `result_json` 做敏感字段脱敏——字段名命中 `authorization`、`token`、`api_key`、`password`、`secret`(大小写不敏感,JSON 递归遍历)的值替换为掩码 `***`,其余原样。Sandbox 拒绝复用本路径(`success=false` + `error_message`),不新增表或字段。

### A3. `llm_calls`(已建,骨架期)

每次 LLM 调用的审计留痕(FR-072)。字段:`id` AI PK、`session_id`、`provider`、`model`、`prompt_tokens`、`completion_tokens`、`total_tokens`、`duration_ms`、`created_at`。

### A4. `scheduled_tasks`(新增)

定时任务登记信息与运行状态(FR-053);**定义源仍是 AGENT.md 的 `schedules`,本表只存状态**。

| 字段 | 说明 |
| ------ | ------ |
| `schedule_id` | PK,由 `(profile_name, schedule_key)` 生成并保持稳定(reconcile 复用) |
| `profile_name` / `schedule_key` | 归属 Profile / Agent 内配置键;联合唯一 |
| `display_name` | 展示名称,不参与定位 |
| `cron` / `zone` / `message` | 触发表达式 / 时区 / 到点发给 Agent 的消息 |
| `enabled` | 启停开关(默认启用) |
| `retired` | 配置删除或改 key 后标记退役;不参与调度,状态与历史保留 |
| `next_run_at` / `last_run_at` / `last_status` / `run_count` / `updated_at` | 运行态字段(`last_status ∈ success/failed`) |

**状态机**: 活动(`enabled=true, retired=false`)⇄ 停用(`enabled=false`)→ 退役(`retired=true`,终态)。启动时按 frontmatter `schedules` reconcile:同 `(profile, key)` 复用既有 `schedule_id` 并更新定义字段;消失的 key 置 `retired` 而非删除。

### A5. `task_executions`(新增)

定时任务每次执行历史(FR-053),成功失败都记:`id` AI PK、`schedule_id`(关联 A4)、`session_id`(本次钟推会话)、`started_at`、`success`、`error_message`(可空)、`duration_ms`。

### A6. `notify_channels`(新增)

通知渠道全局注册表(FR-046)。字段:`name` PK、`type`(核心阶段仅 `webhook`)、`url`、`config_json`(类型相关键值)、`description`。

**定义源**: `.oryxos/notify_channels.yaml`;启动时以文件为准全量重建(核心阶段无其他写入路径,最直白口径)。`url` 支持 `${ENV_VAR}` 占位,加载时解析。

## B. 文件系统(`.oryxos/`,用户可维护、git 可跟踪)

| 数据 | 位置 | 关键规则 |
| ------ | ------ | --------- |
| 长期记忆(默认后端) | `memory/MEMORY.md` | `## 核心记忆` / `## 归档记忆` 两个 header 分区,每条带日期 header;核心区永远全量注入、不截断、不参与检索;归档区 >4000 字截断,关键词检索不区分大小写、只命中归档区;`append(content, scope)` 默认 ARCHIVAL;每次组装 prompt 重新读、不缓存(契约一) |
| 用户初始设定 | `USER.md` | OryxOS 只读;与 MEMORY.md 都进 system prompt |
| Bootstrap | `AGENTS.md` / `SOUL.md` / `USER.md` | 每次组装 prompt 重新读取,不缓存,改完下轮生效(FR-007) |
| Agent 目录 | `agents/<name>/` | `AGENT.md`(frontmatter=配置,正文=任务指令)+ 可选 `skills/` 相对软连接、`scripts/`、`REFERENCE.md` |
| Skill 公共实体 | `skills/<name>/` | `SKILL.md`(frontmatter 含 name/description)+ 可选附属资源;Agent 软连接目标 MUST 解析到本目录内(防越界) |
| MCP 配置 | `mcp_servers.yaml` | 每条 `name` / `transport`(stdio / sse)/ `command` / `env` |
| 通知渠道定义源 | `notify_channels.yaml` | 见 A6 |
| 沙箱白名单 | `sandbox.yaml` | 管理员全局配置,启动加载、缺省全拒;Agent MUST NOT 自声明;契约见 contracts/sandbox-config.md |
| 产出物 / 日志 | `output/` / `logs/` | 结构化 JSON 日志,与审计表同一套脱敏规则(FR-074) |

## C. 内存派生(不落盘,启动/运行时构建)

- **Profile**:`AgentLoader.deriveProfile(agentDir)` 从 `AGENT.md` frontmatter 派生,注册进 `ProfileRegistry`(按 name 索引)。字段:`name` / `description` / `identity{agent_name, prompt}` / `provider{name, model, temperature?}` / `tools[]` / `mcp_servers[]` / `channels[]` / `schedules[]` / `bootstrap[]` / `settings{max_iterations=10, max_history_turns=20}`。**校验**(FR-004):Provider 存在、Tool 已注册、Channel 受支持、Bootstrap 文件存在;失败不阻断启动、记错误日志、该 Agent 不可用。**`notify_channels` 不是 Profile 字段**。
- **Skill 绑定视图**:每次组装 prompt 现扫 Agent `skills/` 相对软连接(唯一绑定真相源,FR-006),验证真实目标位于公共 Skill 根内,只注入 frontmatter 的 name/description + Agent 本地绝对读取路径;正文不预载。
- **Provider 映射表**:provider name → `ChatModel` 显式映射(原则 III),启动时按配置构建,多 Provider 并存无歧义。
- **ProfileContext**:ThreadLocal 承载当前请求 Profile;`AgentService.process` 入口放置、`finally` 清除;`OryxTool.execute` 签名不带 Profile,工具从 ProfileContext 读当前 Agent。

## 关系与一致性

```text
Profile(内存) 1───* Session(A1) 1───* tool_invocations / llm_calls(A2/A3,按 session_id 归集)
Profile(内存) 1───* scheduled_tasks(A4) 1───* task_executions(A5)
notify_channels.yaml(B) ──启动导入──> notify_channels(A6) <──按名引用── NotifyTools
Agent 目录(B) ──deriveProfile──> Profile(C) ──schedules──> AgentScheduler ──reconcile──> scheduled_tasks(A4)
MEMORY.md / USER.md / Bootstrap(B) ──ContextLoader/MemoryService 每轮现读──> system prompt
```

- `session_id` 全局公式统一:`channel:user:profile`,人推与钟推同一公式(FR-052)。
- 审计表 `session_id` 可空(骨架 schema 如此),但 AgentService 链路内的调用一律有会话。
- 所有 TIMESTAMP 存 ISO-8601 本地时区;`duration_ms` 为整型毫秒。
- 表结构演进:改表必须同步改 `schema.sql` 与实体,不写依赖 `ddl-auto` 的隐式迁移。
