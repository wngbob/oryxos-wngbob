# Quickstart: 核心阶段运行时内核端到端验证指南

**用途**: 证明五大核心能力全部跑通的可执行验证脚本(人工逐步执行)。覆盖 spec.md 五个 user story 的 Independent Test 与三个每日验收 Demo(SC-001~003 的直接证据)。实现细节见 [plan.md](plan.md) 与后续 tasks.md;接口口径见 [contracts/](contracts/);数据结构见 [data-model.md](data-model.md)。

## 0. 前置条件

| 项 | 要求 |
| ---- | ------ |
| JDK | 21+(`java -version` 验证) |
| 构建 | 仓库根 `mvn clean package` 出 fat JAR |
| 密钥 | `DEEPSEEK_API_KEY`、`KIMI_API_KEY` 环境变量(至少一个,SC-010 要求两家都配) |
| webhook | 一个可达的 webhook 端点(真实 IM 群机器人或等价 HTTP 端点),URL 放入 `LARK_WEBHOOK_URL` 环境变量 |
| MCP(验证 V4 用) | Node.js 可跑 `npx`,或任意一个 stdio MCP server |

## 1. 初始化与配置(5 分钟)

```bash
java -jar oryxos-cli/target/oryxos-cli-*.jar init        # 幂等,重复跑不覆盖
cd .oryxos
# 编辑 notify_channels.yaml:加 name=team-lark, type=webhook, url=${LARK_WEBHOOK_URL}
# 编辑 mcp_servers.yaml(V4 用):配一个社区 MCP server
# 编辑 sandbox.yaml:按需放行文件/Shell/HTTP 白名单(V2 起用,契约见 contracts/sandbox-config.md)
```

**预期**: 工作区产物齐全(见 contracts/agent-directory.md §7),`oryxos.db` 内含 6 张表。

## 2. 验证场景(对应 user story)

### V1 — 对接 LLM(US-1,SC-010)

1. `oryxos profile create demo && oryxos chat --profile demo --message "你好"`(provider 配 deepseek)。
2. 把 frontmatter `provider.name` 改为 `kimi` 再发同一条消息。
3. 临时unset一个密钥再发消息。

**预期**: 两家都返回正常响应;缺 key 时报错指明缺失的环境变量名、不打印密钥;`sqlite3 .oryxos/oryxos.db 'select provider,model,total_tokens,duration_ms from llm_calls'` 每次调用各有一行。

### V2 — ReAct 循环(US-2)

1. 给 demo Agent 配 `tools: [http_get]`,并在 `.oryxos/sandbox.yaml` 的 `http.domains` 放行天气 API 域名。
2. `oryxos chat` 输入"查一下北京天气并告诉我穿什么"。
3. `GET /api/v1/sessions/{id}`(或 `oryxos session list` 找到后查库)看对话历史。

**预期**: 至少一轮"LLM 决策 → http_get → 结果回填 → 穿衣建议";历史按序含 assistant/tool 记录;连续对话超过 `max_history_turns` 后早期轮次被截断;迭代上限生效(可把 `max_iterations` 调成 1 验证强停说明)。

### V3 — Memory(US-3)

1. 对话:"我项目用 Spring Boot,部署在 K8s 上,记住这个。"(确认 Agent 调 `save_memory`)
2. **重启进程**,新开会话:"我的项目能用什么数据库?"
3. `cat .oryxos/memory/MEMORY.md`;再改 MEMORY.md 手工加一条,下轮对话验证立即可见。

**预期**: 第二次回答引用此前偏好;MEMORY.md 归档区有该条;手工修改不重启即生效(不缓存);归档区超 4000 字截断、核心区始终完整。

### V4 — Plugin Tool(US-4,SC-002)

1. 创建 `.oryxos/agents/daily-pr-digest/`:`AGENT.md` 正文写"拉取昨日 PR 评审进度并汇总简报",frontmatter `mcp_servers: [github-mcp]`、`tools` 按需。
2. `oryxos chat --profile daily-pr-digest --message "开始"`。
3. 另验证:`read_file` 读白名单外路径(含 `../` 穿越)、`shell` 跑白名单外可执行文件。

**预期**: 零 Java 代码跑通 MCP 工具调用;越界调用被拒且 `tool_invocations` 有 `success=false` 记录;`oryxos tool list` 同时含内置、MCP、注解三类来源。

### V5 — Web Service(US-5)

`oryxos serve` 后依次:

```bash
curl -X POST localhost:8080/api/v1/sessions -d '{"profile":"demo"}'
curl -X POST localhost:8080/api/v1/sessions/<id>/messages -d '{"message":"你好"}'
curl localhost:8080/api/v1/sessions/<id>
curl -X DELETE localhost:8080/api/v1/sessions/<id>
curl localhost:8080/api/v1/health; curl localhost:8080/api/v1/info
curl localhost:8080/api/v1/profiles; curl localhost:8080/api/v1/tools; curl localhost:8080/api/v1/memory
curl -X POST localhost:8080/api/v1/agents/demo/invoke -d '{"message":"1+1=?"}'
```

**预期**: 全链路成功且所有响应同一信封;发 >32KB 消息返回 400;乱造 sessionId 返回 404;杀掉 Provider 网络返回 503;重启服务后历史会话完整可查(SC-006);`/swagger-ui` 可打开。

## 3. 三个每日验收 Demo(SC-001)

按 contracts/agent-directory.md 建三个 Agent 目录(配置细节见技术方案 §12.1~12.3):

| Demo | 目录要点 | 验证要点 |
| ------ | --------- | --------- |
| D1 每日天气 | 光杆 AGENT.md,`tools:[http_get,notify]`,cron 每天 08:00 | `serve` 或 `gateway` 常驻,到点(或临时改 cron 到 2 分钟后)自动跑;群/webhook 收到穿搭建议;两条涉外调用都有审计;同 Agent 用 `chat`/`invoke` 手动补跑一次走同一链路 |
| D2 每日科技日报 | AGENT.md + `skills/digest-format` 软连接 + 新闻 MCP;先告诉它"关注 AI 和芯片" | prompt 只注入 Skill 元数据(日志可见);`tool_invocations` 有对软连接路径的 `read_file`;日报侧重记忆里的偏好方向;全程零 Java 代码 |
| D3 每日 GitHub 日报 | AGENT.md + `scripts/github_trending.py`(专用 Tool/MCP 封装) | 审计里有脚本 Tool 调用;脚本产出 JSON 进上下文、代码不进;AI 段体现记忆偏好;改 AGENT.md 正文不重启下轮生效 |

## 4. 成功标准对照

| SC | 验证入口 |
| ---- | --------- |
| SC-001/002/003 | 第 3 节三个 Demo + 审计表查询 |
| SC-004 | `profile create` 造 10 个 Agent 并存,`info` 端点与 4h 常驻观察 |
| SC-005 | `sessions` 创建 P99 ≤200ms(日志/计时),内部转发开销 ≤50ms |
| SC-006 | V5 重启恢复验证 |
| SC-007 | V3 |
| SC-008 | 第 2 节全量 + 12 命令逐一 `--help`/执行 |
| SC-009 | 新机器按第 0~1 节 30 分钟内跑通 |
| SC-010 | V1 |
