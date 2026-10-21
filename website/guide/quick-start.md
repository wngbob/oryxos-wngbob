# 快速开始

> ✅ **运行时内核已跑通**：五大核心能力已交付，以下命令均已在真实构建上验证；验收 Demo 与跨节串联验收正按[路线图](/guide/roadmap)推进。

## 环境要求

- JDK 21+
- Maven 3.8+（仓库自带 `./mvnw`）

## 构建与运行

```bash
# 构建（必须带 clean；产出可执行 fat JAR：oryxos-boot/target/oryxos.jar）
./mvnw clean package -Ddependency-check.skip=true

# 初始化工作区（创建 .oryxos/ 目录，幂等；同时建表并补齐 schema 漂移）
java -jar oryxos-boot/target/oryxos.jar init

# 创建一个 Agent（生成 .oryxos/agents/<name>/AGENT.md 最小模板）
java -jar oryxos-boot/target/oryxos.jar profile create my-assistant

# 交互式对话（需要模型凭证）
export DEEPSEEK_API_KEY=sk-...
java -jar oryxos-boot/target/oryxos.jar chat

# 或常驻：REST API + 管理台（定时任务页可写）+ OpenAPI 文档，同一个进程、同一个端口
java -jar oryxos-boot/target/oryxos.jar serve
#   REST      http://localhost:8080/api/v1
#   管理台    http://localhost:8080/admin/
#   接口文档  http://localhost:8080/swagger-ui
```

也可以用仓库自带的后台启动脚本，凭证从 YAML 配置文件读取（`bin/config.yaml` 不入库）：

```bash
cp bin/config.yaml.example bin/config.yaml   # 填入 deepseek.api-key
bin/start.sh                                 # 后台启动（含 init 与健康检查）
bin/stop.sh                                  # 停止
```

## 一个可执行文件，多个子命令

OryxOS 只有一个交付物：fat JAR `oryxos-boot/target/oryxos.jar`，主类是 `io.oryxlabs.oryxos.cli.OryxOsCli`（Picocli）。`java -jar oryxos.jar <子命令>` 走同一个入口，按子命令决定起不起 Spring 上下文：

| 子命令 | 用途 |
|------|------|
| `--version` / `status` / `profile …` 等轻命令 | 直接读文件，不启动 Spring（启动快） |
| `chat` | 交互式对话 |
| `serve` | 启动 Spring Boot + Web 容器，对外提供 REST API（`/api/v1`）、管理台（`/admin/`）、OpenAPI 文档（`/swagger-ui`）与定时任务 |
| `gateway` | 常驻模式，只跑定时任务、不对外提供 HTTP |

> 第 18 节之前还有一个 `OryxosApplication` 启动类，它已随第 26 节删除——宽扫描根会与 CLI 的装配相互撞车，且 `serve` 已经能起 Web 容器，它没有存在的理由。

## 一个目录 = 一个 Agent

定义一个每日天气 Agent，只需要在 `.oryxos/agents/daily-weather/AGENT.md` 里写：

```markdown
---
provider: { name: deepseek, model: deepseek-chat }
tools: [http_get, notify]
schedules: [{ cron: "0 0 8 * * *", message: "查一下今天天气并生成穿搭建议" }]
---

每天早上自动查天气、生成穿搭建议，通过 notify 推送到团队群（渠道名 team-lark）。
```

不写一行代码，到点自动运行，每次调用都有审计记录。Agent 名取自**目录名**，frontmatter 里不需要（也不生效）写 `name`。

仓库里还有一份**四部分俱全**的完整示例，可以直接复制进工作区：

- [`examples/agents/daily-reconcile/`](https://github.com/wngbob/oryxos-wngbob/tree/main/examples/agents/daily-reconcile)：`AGENT.md`（frontmatter + 四步正文）、`REFERENCE.md`、`scripts/reconcile.py`、`skills/report-format` 相对软连接
- [`examples/skills/report-format/SKILL.md`](https://github.com/wngbob/oryxos-wngbob/tree/main/examples/skills/report-format)：公共 Skill 实体

```bash
cp -a examples/skills/report-format   .oryxos/skills/
cp -a examples/agents/daily-reconcile .oryxos/agents/
```

渐进式披露在这份示例里可以直接看到：正文常驻 system prompt，`REFERENCE.md` 与脚本**不预载**，由模型按需用 `read_file` / `shell` 取——脚本产出进上下文，源码不进。

::: tip 安全提示
凭证（如 LLM API Key）不要明文写进 `AGENT.md` 的 frontmatter，用 `${ENV_VAR}` 形式从环境变量注入。
:::
