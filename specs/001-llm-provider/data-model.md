# Phase 1 Data Model: Provider —— 对接大模型的统一入口

本节涉及三个实体：两个内存实体（`Profile`、Provider 声明）与一个持久化实体（`LlmCall`）。

---

## 1. `Profile`（内存实体，`oryxos-core`）

一个 Agent 的运行时配置。**来源是 `.oryxos/agents/<name>/AGENT.md` 的 frontmatter**，不是独立手写的 YAML 文件（依据：技术方案 §8.2、CLAUDE.md；见 [research.md](research.md) D1）。

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `name` | string | 是 | Agent 名，取自目录名（目录名即真相，不在 frontmatter 重复声明） |
| `description` | string | 是 | 一句话说明这个 Agent 干什么 |
| `identity.agent_name` | string | 否 | 人格名（展示用） |
| `identity.prompt` | string | 否 | 人格 / 系统提示词 |
| `provider.name` | string | 是 | 引用全局层厂商清单的 name（如 `deepseek`） |
| `provider.model` | string | 是 | 模型名 |
| `provider.temperature` | double | 否 | 采样温度；未声明时不显式传给模型，用模型自身默认 |
| `tools` | string[] | 否 | 可用 Tool 子集；缺省 = 空 |
| `mcp_servers` | string[] | 否 | 引用 `mcp_servers.yaml` 的 name |
| `channels` | object[] | 否 | `[{name, config}]`，gateway 模式挂载依据 |
| `schedules` | object[] | 否 | `[{cron, zone, message}]`，钟推定义源 |
| `bootstrap` | string[] | 否 | 额外 Bootstrap 文件列表 |
| `settings.max_iterations` | int | 否 | 最大 ReAct 迭代，默认 10 |
| `settings.max_history_turns` | int | 否 | 对话历史截断轮数，默认 20 |

**本节实际消费的字段**：`name`、`provider.*`（第 17 节的 ReAct 循环才用到 `identity` / `tools` / `settings` 等）。类本身**本节就建全字段**，后续各节取用哪个是哪个，不反复改类。

**显式禁止的字段**：`skills`、`notify_channels`——见 [research.md](research.md) D2。frontmatter 中出现它们属于配置错误。

**校验规则（本节）**：
1. `provider.name` 必须能在全局层厂商清单中找到同名项——找不到则**该 Profile 判为不合法**，记错误日志、跳过该 Agent，不阻断其余 Agent 加载。
2. `provider.model` 缺失即不合法（同上述处理）。
3. frontmatter 无法解析、或 `AGENT.md` 缺失——按"坏文件"处理：记错误日志、跳过，不阻断启动。
4. frontmatter 中的 `${ENV_VAR}` 占位在加载时从环境变量解析；环境变量缺失时，报错须指明变量名，且**不得回显任何凭证值**。

**状态**：无状态机。Profile 一经加载即不可变，第 29 节才会补运行时注册路径。

---

## 2. Provider 声明（内存实体，全局层配置项）

实例级"本机接了哪些厂商、凭证从哪来"的清单项。来源是 `application.yaml` 的 `oryxos.providers`。

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `name` | string | 是 | 厂商名，全局唯一；Profile 通过它引用 |
| `api-key` | string | 是 | 凭证，**必须**写成 `${ENV_VAR}` 占位 |

**校验规则**：
1. 同一 `name` 在清单中出现多次 → 启动加载期报错，指明重复的名字（见 [research.md](research.md) D10）。
2. `api-key` 对应的环境变量缺失 → 报错指明变量名，不打印值。
3. 清单中声明了当前实现不支持的厂商名 → 报错指明该名字（本节只实现 `deepseek`）。

**关系**：Provider 声明 1 : N Profile——多个 Agent 可共用同一厂商。

**运行期形态**：启动时按清单逐条构造出 `Map<String, ChatModel>`，键即厂商名。这是"显式映射"的落地物，**不是**从容器里扫描 `ChatModel` Bean 得来的（章程原则 III）。

---

## 3. `LlmCall`（持久化实体，`oryxos-storage`）

每次 LLM 调用的审计留痕。表名 `llm_calls`，建表走手工脚本 `oryxos-storage/src/main/resources/db/schema.sql`。

| 列 | 类型 | 说明 | 本节变化 |
| --- | --- | --- | --- |
| `id` | INTEGER PK AUTOINCREMENT | 主键 | 既有 |
| `session_id` | VARCHAR(128) | 会话标识，按此关联查询 | 既有 |
| `provider` | VARCHAR(64) | 厂商名 | 既有 |
| `model` | VARCHAR(128) | 模型名 | 既有 |
| `prompt_tokens` | INTEGER | 输入 token 数，可空 | 既有 |
| `completion_tokens` | INTEGER | 输出 token 数，可空 | 既有 |
| `total_tokens` | INTEGER | 总 token 数，可空 | 既有 |
| `duration_ms` | BIGINT | 调用耗时（毫秒） | 既有 |
| `success` | BOOLEAN | 是否成功 | **新增** |
| `error_message` | TEXT | 失败原因，成功时为空 | **新增** |
| `created_at` | TIMESTAMP | 调用发生时间 | 既有 |

**写入规则**：
- 成功：`success = true`，`error_message` 空，token 用量取自 `ChatResponse` 的 metadata。
- 失败：先写 `success = false` + `error_message = 异常消息`，**再**把异常上抛；不得吞异常。
- 用量缺失（部分厂商不返回 token 统计）：仍落库，用量列留空，不整条丢弃。

**查询**：既有 `LlmCallRepository.findBySessionIdOrderByCreatedAtDesc(String)`，本节不改。

**关联**：`session_id` 指向会话；本节不建外键约束（会话表在第 18 节落地）。

---

## 实体关系图

```text
application.yaml (oryxos.providers)          .oryxos/agents/<name>/AGENT.md
        │                                                  │
        │ 解析                                              │ 解析 frontmatter
        ▼                                                  ▼
  Provider 声明 ──── name 引用 ────►  Profile ──── provider.name/model/temperature
   (全局层, 1..N)                     (内存索引 ProfileRegistry)
        │                                                  │
        │ 启动时显式构造                                     │
        ▼                                                  │
  Map<String, ChatModel> ──── ProviderService.chat() ◄─────┘
                                     │
                                     │ 成败都写
                                     ▼
                              llm_calls (SQLite)
```
