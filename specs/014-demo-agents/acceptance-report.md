# 第 31 节验收报告 · 三个验收 Demo 与第一个版本

| 项 | 值 |
|---|---|
| 课型 | **Demo 课**（不做常规模块开发；不产 spec/plan/tasks——Agent 目录、验收报告、人工向导就是交付物） |
| 课件 | [`docs/class/第31节：天气、科技日报、GitHub 日报 Agent 开发与演示 发布、打包、第一个版本.md`](../../docs/class/第31节：天气、科技日报、GitHub 日报 Agent 开发与演示 发布、打包、第一个版本.md) |
| 技术方案 | `docs/TechnicalSolution.md` §12.1 ~ §12.3（关键流程 / 两个 Demo）、§6.7（沙箱范围） |
| 分支 | `class-31` |
| 日期 | 2026-10-22 |
| 交付物完成度 | 三个 Agent 目录 **3 / 3**；机检 **1 个测试类 / 5 条**；发布清单 **1 / 1**（tag 按裁决由用户执行） |
| 结论 | **全绿**：`mvn clean verify` **738** 条通过（含静态门禁；交付当轮 732，其后两次交付后修复 +6，见 §七）；三个 Demo 已在**真 DeepSeek + 本机临时 webhook** 上跑通人推与钟推；剩余四项为人工项（连续两天、干净机器、真群推送、`v0.1.0` tag） |

> 按 skill 第 0 步的课型分流，31 是 **Demo 课**：「按课件定义 Agent、完成调试对账与发布清单，不做常规模块开发」。本报告是该课的落盘产物；操作步骤见同目录 [`manual-test-guide.md`](manual-test-guide.md)。

---

## 一、这一节到底做了什么

前 15 节把底座能力一个一个做出来，第 27 / 28 节证明了人推与钟推两条主干能串起来，第 29 / 30 节把「一个目录 = 一个 Agent」做成了可实时生效的机制。第 31 节是第一阶段的总验收：**写三个真实的业务 Agent，让它们每天自己按点干活**，再把整个东西整理成可交付的第一版。

三个 Demo 不是随便挑的，它们刻意覆盖 Agent 目录的三种丰富度：

| | 每日天气 `weather-daily` | 每日科技日报 `daily-tech-digest` | 每日 GitHub 日报 `github-daily` |
|---|---|---|---|
| 目录形态 | **光杆 `AGENT.md`** | `AGENT.md` + **Skill 软连接** | `AGENT.md` + **scripts/ 脚本** |
| 到点时间 | 08:00 | 09:00 | 09:30 |
| 工具 | `http_get` + `notify` | `http_get` + `read_file` + `notify` + `save_memory` | `shell` + `notify` + `save_memory` |
| 数据源 | open-meteo（免 key 天气） | HN Algolia front page（免 key 新闻） | GitHub Search API（脚本内，免 token） |
| 各证一件事 | 一份 `AGENT.md` 就能定义一个会自己跑的 Agent | 子指令用 `read_file` 按需读，不进 system prompt 全文 | 确定性数据交给脚本，**只有 JSON 进上下文、源码不进** |

**本节新增/修改的零件（全部在交付物清单内）**：

1. `examples/agents/weather-daily/AGENT.md`（21 行）
   ——**2026-10-22 修订注**：现为 **25 行**（两步查坐标 + 「城市名只出现这一次」的说明），见 §五 末条与 §七
2. `examples/agents/daily-tech-digest/AGENT.md`（25 行）+ `skills/digest-format -> ../../../skills/digest-format`（相对软连接）
3. `examples/skills/digest-format/SKILL.md`（11 行）
4. `examples/agents/github-daily/AGENT.md`（25 行）+ `scripts/github_trending.py`（42 行）
5. `oryxos-boot/src/main/resources/application.yaml`：`http.allowed_domains` **只加一行** `hn.algolia.com`
   ——**2026-10-22 修订注**：交付后又加了一行 `geocoding-api.open-meteo.com`（天气 Demo 改为两步查坐标，见 §五 末条），本节交付时确实只有一行
6. `oryxos-cli/src/test/java/io/oryxlabs/oryxos/cli/DemoAgentsSourceTest.java`：**5 条**机检
7. `specs/014-demo-agents/` 下的本报告与人工向导
8. README / 官网三处 / `CLAUDE.md` 的对外表述同步

**生产 Java 代码零改动**（`git diff --stat` 只有 `README.md` 与 `application.yaml` 两个已跟踪文件，新增的都是示例目录、一个测试类与文档）。

> **2026-10-22 修订注**：这两句只对**交付当轮**成立。其后三次交付后修复（两步查坐标 / 直接编辑重注册 / 编辑时自动重置）改了 `application.yaml`、`AgentScheduler` / `AgentLifecycleService` / `WorkspaceWatcher` 三个 core 类与对应测试，当前 `git status --short` 里有 **16 个已跟踪文件**被修改（外加示例目录、`DemoAgentsSourceTest` 与 `specs/014-demo-agents/` 等新增），`git diff --stat -- '*.java'` **不再为空**。明细见 §五 末两条、§六 与 §七，以及 `specs/013-agent-lifecycle-api/acceptance-report.md` §八。

---

## 二、六项证据（DoD）

### 1. `mvn clean verify` 全绿

```bash
./mvnw clean verify -Ddependency-check.skip=true
```

关键输出：

```text
[INFO] Reactor Summary for OryxOS Parent 0.1.0-SNAPSHOT:
[INFO] oryxos-storage ..................................... SUCCESS
[INFO] oryxos-core ........................................ SUCCESS
[INFO] oryxos-provider .................................... SUCCESS
[INFO] oryxos-memory ...................................... SUCCESS
[INFO] oryxos-tool ........................................ SUCCESS
[INFO] oryxos-channel-cli ................................. SUCCESS
[INFO] oryxos-web ......................................... SUCCESS
[INFO] oryxos-cli ......................................... SUCCESS
[INFO] oryxos-boot ........................................ SUCCESS
[INFO] BUILD SUCCESS
```

逐模块测试合计 **732** 条，`Failures: 0, Errors: 0, Skipped: 0`（第 30 节 727 → **+5**，全部来自 `DemoAgentsSourceTest`）。Spotless / P3C / Checkstyle / SpotBugs / FindSecBugs 全绿；**本节没有新增抓红**（没有新增主代码）。

> **2026-10-22 修订注**：这是**交付当轮**的快照。交付后两次修复又加了 6 条（两步查坐标 +0、编辑时自动重置 +5、直接编辑重注册 +1），当前应为 **738** 条，见 §六 与 §七。

### 2. 课件 harness 映射（Demo 课没有课件 harness，机检点是本节自定并落地的）

| 测试方法 | 钉住的事实 | 对应课件要求 |
|---|---|---|
| `threeDemoAgentDirectoriesExist` | 三个 `AGENT.md`、公共 `SKILL.md`、GitHub 脚本都在；`skills/digest-format` **是软连接** | 「三个 Agent 目录」交付物 |
| `allAgentMarkdownParsesWithTheRealLoader` | 三份 frontmatter 过**真实** `ProfileLoader`；`tools` 与 cron / 时区逐项对得上 | 「三种目录形态各演一个」 |
| `techDigestSkillBindingIsARelativeSymlink` | 软连接目标是 `../../../skills/digest-format`，`toRealPath()` 落在 `examples/skills/digest-format` | 章程原则 13（Skill 唯一真相源是软连接） |
| `forbiddenFrontmatterKeysAreAbsent` | 三份 `AGENT.md` 都没有 `notify_channels:` / `skills:` | `ProfileLoader.FORBIDDEN_KEYS` |
| `sandboxDefaultsCoverDemoDependencies` | 随包 `application.yaml` 源文件含 `api.open-meteo.com` / `hn.algolia.com` / `api.github.com` / `python3` / `.` | 「外部依赖与白名单」交付物 |

> 为什么不跑真模型：真实链路归人工向导；这里只钉「改一行就会静默失效」的事实。这个分工与第 24 / 29 节那两次「`ToolAssemblyTest` 自己注入属性源、守不了配置块被删」的教训同向——**读源文件**而不是读注入后的属性。

### 3. 「本节交付物」逐项存在性核对

```text
$ find examples/agents/weather-daily examples/agents/daily-tech-digest \
       examples/agents/github-daily examples/skills/digest-format -type f -o -type l | sort
examples/agents/daily-tech-digest/AGENT.md
examples/agents/daily-tech-digest/skills/digest-format        <- 软连接
examples/agents/github-daily/AGENT.md
examples/agents/github-daily/scripts/github_trending.py
examples/agents/weather-daily/AGENT.md
examples/skills/digest-format/SKILL.md
```

```text
$ readlink examples/agents/daily-tech-digest/skills/digest-format
../../../skills/digest-format
$ readlink -f examples/agents/daily-tech-digest/skills/digest-format
/home/wngbob/oryxos-wngbob/examples/skills/digest-format
```

课件清单对照：

| 课件交付物 | 落地 | 状态 |
|---|---|---|
| `weather-daily/`（光杆 `AGENT.md`、API 创建） | `examples/agents/weather-daily/AGENT.md`；作者侧已用 `POST` + `PUT` 创建并跑通 | ✅ |
| `daily-tech-digest/`（`AGENT.md` + `skills/digest-format.md`、手写目录） | `AGENT.md` + **软连接** + `examples/skills/digest-format/SKILL.md` | ✅（形状按本仓章程修正，见 §三·2） |
| `github-daily/`（`AGENT.md` + `scripts/github_trending.py`、手写目录） | 同名两文件 | ✅ |
| 外部依赖与白名单：天气 / webhook / 新闻源 / `api.github.com` / `python3` / `file.allowed_paths` | 随包白名单：`api.open-meteo.com`、`api.github.com`、`hn.algolia.com`、`python3`、`.`；webhook 对端域名由部署方按渠道加入 | ✅ |
| 发布物：fat JAR、`v0.1.0` tag、README 快速开始（30 分钟标准） | JAR 由 `mvn clean package` 产出并有实测；README 已增「三个验收 Demo」段与状态更新；**tag 按用户裁决只出清单不代打** | ✅（tag 留人工） |

### 4. 前序节全部测试回归绿

`mvn clean verify` 是聚合构建，**732** 条包含第 16~30 节全部测试（含 `AdminConsoleSourceTest` 10、`AgentSchedulerRegisterTest` 12、`ScheduleApiControllerTest` 12、`AgentApiControllerTest` 20、`MemoryStoreContractTest` 三档参数化 28 等），无失败、无跳过。跨节契约未受影响：本节没有改任何 `oryxos-*` 的生产代码。

> **2026-10-22 修订注**：数字为交付当轮；当前 **738**（见 §七）。「本节没有改任何 `oryxos-*` 生产代码」同样只对交付当轮成立——交付后的两次修复改了 `AgentScheduler` / `AgentLifecycleService` / `WorkspaceWatcher` 三个 core 类，明细则在 `specs/013-agent-lifecycle-api/acceptance-report.md` §八。

### 5. H4 六条全局不变量自查

| # | 不变量 | 本节结论 |
|---|---|---|
| ① | 涉外 IO 首行过 `Sandbox.enforce` | 三个 Demo 只经既有工具涉外：`http_get` / `notify` 过 `HTTP_REQUEST`，`shell` 过 `SHELL_COMMAND`，`read_file` 过 `FILE_READ`。**如实记一条边界**：`github_trending.py` 作为子进程自己访问 `api.github.com`，**其出站不过沙箱**——这是第 29 节已记录的「脚本信任边界没做」（FR-010 明写不做），白名单列 `api.github.com` 是为了审计意图、不是执行拦截 |
| ② | LLM 成败落 `llm_calls`、工具成败落 `tool_invocations` | 实测：三个调度会话各 3 条 `llm_calls`（全 success）、`tool_invocations` 全 success；人推会话同样逐条落库 |
| ③ | 无明文 key | `grep -rnE "sk-[A-Za-z0-9]{10,}" examples/ specs/014-demo-agents/` 零命中；凭证走 `${DEEPSEEK_API_KEY}` / `${TEAM_WEBHOOK_URL}` 占位 |
| ④ | `session_id` 只在 `SessionManager` 内拼接 | 本节未改会话代码；调度会话 id 仍是 `scheduler:scheduler:<profile>` |
| ⑤ | 无 Reactor / `CompletableFuture` / 自建线程池 | 本节无 Java 主代码；脚本是 `shell` 同步子进程 |
| ⑥ | 无 Spring AI 自动工具执行 | 本节未触碰 `ReActLoop` / `ToolExecutor`；工具仍由 `ToolExecutor` 调度 |

### 6. 真模型端到端实测（作者侧，验完已删临时工作区）

环境：WSL2、真 DeepSeek（`deepseek-chat`）、本机 webhook `127.0.0.1:9099`、临时工作区 `/tmp/oryxos-l31`（正常 `<cwd>/.oryxos` 布局）。

**Demo 一 · 天气（走 30 节 API）**

```text
POST /api/v1/agents           -> 200（脚手架）
PUT  /api/v1/agents/weather-daily -> 200（覆写 AGENT.md，即时生效）
POST /api/v1/schedules/weather-daily%231/run
  -> lastStatus: "success", runCount: 1, 5439 ms
```

| 证据 | 值 |
|---|---|
| `llm_calls`（`scheduler:scheduler:weather-daily`） | 3 条，全部 `success=1`；prompt 834 / 1077 / 1265，completion 84 / 174 / 179 |
| `tool_invocations` | id1 `http_get` success=1（1001 ms，open-meteo JSON）；id2 `notify` success=1（16 ms） |
| 推送内容 | 「今日天气 + 穿搭建议」，温度 / 降水 / 风速取自接口，未编造 |
| `task_executions` | `weather-daily#1` success=1 |

**Demo 二 · 科技日报（Skill + 记忆偏好）**

```text
POST /api/v1/agents/daily-tech-digest/session/messages  {"content":"…更关注 AI 和芯片方向。"}
  -> 模型调用 save_memory（scope=archival），并完成一次完整日报
POST /api/v1/schedules/daily-tech-digest%231/run
  -> lastStatus: "success", runCount: 1, 8097 ms
```

| 证据 | 值 |
|---|---|
| `MEMORY.md` | `- [2026-10-22] 用户长期偏好：科技日报中更关注 AI 与芯片（半导体）方向的新闻，组稿时应优先挑选并排在前面。` |
| `tool_invocations`（调度会话） | id7 `http_get`（HN Algolia）→ id8 `read_file`（路径 `/tmp/oryxos-l31/.oryxos/skills/digest-format/SKILL.md`）→ id9 `notify`，全 success |
| 渐进式披露 | system prompt 只注入 Skill 的 name / description / 路径；正文由 `read_file` 在组稿那一步才读 |
| 偏好生效 | 日报把 AI 相关条目排最前，并写明「与你关注的领域直接相关」 |
| `llm_calls` | 调度会话 3/3 success；人推会话 4/4 success |

**Demo 三 · GitHub 日报（脚本 + 记忆偏好 + 信任边界）**

```text
POST /api/v1/agents/github-daily/session/messages  {"content":"我更关注 Agent 框架和推理引擎方向…"}
POST /api/v1/schedules/github-daily%231/run
  -> lastStatus: "success", runCount: 1, 10675 ms
```

| 证据 | 值 |
|---|---|
| `shell` 调用 | `{"command":"python3 .oryxos/agents/github-daily/scripts/github_trending.py"}`，退出码 0（1932 / 2211 ms） |
| 数据进上下文 | `tool_invocations.result_json` 是脚本 stdout 的 JSON（`today` / `month` / `ai` 三段） |
| **源码不进上下文** | 会话 `messages_json`（7269 字符）中 `urlopen` / `def search` / `import json` / `stargazers_count` **全部不存在**（只有命令里的脚本路径） |
| 偏好生效 | AI 段把 `deepseek-ai/DeepInfer`（推理引擎）排第一，`wheresryan22/anatomy`（Agent skill）第二，并逐条点明相关性 |
| `notify` | success=1，webhook 收到三段式日报 |
| 三轮执行耗时 | 5.4 s / 8.1 s / 10.7 s，`task_executions` 三行全 success |

**审计对账（全库）**

```text
llm_calls by session:
  admin:console:daily-tech-digest      4 / 4
  admin:console:github-daily           3 / 3
  scheduler:scheduler:daily-tech-digest 3 / 3
  scheduler:scheduler:github-daily      3 / 3
  scheduler:scheduler:weather-daily     3 / 3
task_executions:
  weather-daily#1      success=1   5439 ms
  daily-tech-digest#1  success=1   8097 ms
  github-daily#1       success=1  10675 ms
```

> 开发期抓到的两处**测试工具**问题（不是产品缺陷，但如实记）：① 本机 webhook 接收器初版只读 `Content-Length`，而 JDK HttpClient 发的是 chunked，导致第一条消息 body 为空——补 chunked 解码后重建；② 首次把 `-Doryxos.root=/tmp/oryxos-l31` 当 workspace 根，`init` 把 `agents/` 建在根目录而不是 `.oryxos/` 下，脚本相对路径对不上——改成 `cd /tmp/oryxos-l31` 后正常初始化，`serve` 同 cwd 启动。

---

## 三、变更总结（给 reviewer 的导读）

### 1. 改动点

**新增（示例 / Demo 交付物）**

| 文件 | 动机 |
|---|---|
| `examples/agents/weather-daily/AGENT.md` | Demo 一：光杆 `AGENT.md`，`http_get` + `notify`，08:00 |
| `examples/agents/daily-tech-digest/AGENT.md` | Demo 二：拉新闻 + 按需读 Skill + 记忆偏好，09:00 |
| `examples/agents/daily-tech-digest/skills/digest-format` | Skill 绑定，**相对软连接**（章程原则 13） |
| `examples/skills/digest-format/SKILL.md` | 科技日报组稿规范（公共 Skill 实体） |
| `examples/agents/github-daily/AGENT.md` | Demo 三：`shell` 跑脚本 + 记忆偏好，09:30 |
| `examples/agents/github-daily/scripts/github_trending.py` | 免 token 抓 GitHub 今日 / 本月 / AI 热门，stdout 输出 JSON |

**新增（测试与文档）**

| 文件 | 动机 |
|---|---|
| `oryxos-cli/src/test/java/io/oryxlabs/oryxos/cli/DemoAgentsSourceTest.java` | 5 条源码级守点：目录、Profile 解析、软连接、禁用键、白名单源文件 |
| `specs/014-demo-agents/acceptance-report.md` / `manual-test-guide.md` | Demo 课的落盘产物与人工向导 |

**修改（配置 / 文档）**

| 文件 | 动机 |
|---|---|
| `oryxos-boot/src/main/resources/application.yaml`（+1 行） | `http.allowed_domains` 加 `hn.algolia.com`（科技日报新闻源） ——**2026-10-22 修订注**：交付后又加了一行 `geocoding-api.open-meteo.com`，现为 **+2 行**（见 §五 天气 Demo 条） |
| `README.md` | 新增「三个验收 Demo」安装段；状态 723 → 732，勾掉 Demo 项；修正「管理台只读」的过期表述 ——**2026-10-22 修订注**：状态现为 **738**（见 §七） |
| `website/.vitepress/theme/components/Home.vue`（中英） | 首页 note 与阶段一描述同步 |
| `website/guide/roadmap.md`、`website/en/guide/roadmap.md` | 状态、勾选项、阶段一描述同步 |
| `website/guide/quick-start.md`、`website/en/guide/quick-start.md` | 顶部状态 note 同步 |
| `CLAUDE.md` | 「当前状态」新增第 31 节条；推进节号 30 → 31；待办改为第 32 节 |

**前序节文件被本节触碰的**：`application.yaml`（+1 行，纯追加取值）与上述对外文档。**没有**改任何 `oryxos-*` 生产 Java 文件。

### 2. 重点 review 清单（按风险排序）

1. **Skill 绑定的形状**（`examples/agents/daily-tech-digest/skills/digest-format`）：评审时确认它是**相对软连接**、目标是 `../../../skills/digest-format`，且 `git` 入库模式是 `120000`。本机 `core.symlinks` 必须为 `true`，否则签出成 29 字节文本文件（第 30 节已踩过）。判据：`DemoAgentsSourceTest.techDigestSkillBindingIsARelativeSymlink`。
2. **frontmatter 形状与禁用键**（三份 `AGENT.md:1-12`）：`provider` 是嵌套 `{name, model, temperature}`；没有 `skills:` / `notify_channels:`。判据：`allAgentMarkdownParsesWithTheRealLoader` + `forbiddenFrontmatterKeysAreAbsent`。
3. **脚本路径以工作区根为基准**（`examples/agents/github-daily/AGENT.md:16-17`）：`shell` 的 cwd 是进程 cwd，不是 Agent 目录；写成 `.oryxos/agents/github-daily/scripts/...`。改路径前先读第 29 节已知边界③。
4. **白名单只加一行**（`application.yaml:111`）：`hn.algolia.com`。`api.open-meteo.com` / `api.github.com` / `python3` 都是前序节已加的，本次**没有重复添加**。判据：`sandboxDefaultsCoverDemoDependencies`。**2026-10-22 修订注**：交付后按天气 Demo 的两步查坐标又加了一行 `geocoding-api.open-meteo.com`，同一判据的断言已同步扩到该域（见 §五 末条）。
5. **脚本信任边界**（`github_trending.py`）：脚本内 `urlopen` 的地址不受沙箱拦截——这是第 29 节 FR-010 明写不做的已知边界。评审时不要把它当成本节引入的缺陷。
6. **README 的 API 创建说明**（`README.md`「三个验收 Demo」段）：`POST /api/v1/agents` 只收 `name` / `description`，完整定义必须再 `PUT` 一次；照课件「表单一次填完」会缺 tools / cron。

### 3. 如何验证

```bash
# 全量门禁（预期 BUILD SUCCESS，738 tests，0 failure）
./mvnw clean verify -Ddependency-check.skip=true

# 只跑本节的机检（预期 Tests run: 5, Failures: 0）
./mvnw -pl oryxos-cli -am test \
  -Dtest=DemoAgentsSourceTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Ddependency-check.skip=true

# 交付物存在性（预期 6 行，其中 daily-tech-digest/skills/digest-format 是软连接）
find examples/agents/weather-daily examples/agents/daily-tech-digest \
     examples/agents/github-daily examples/skills/digest-format -type f -o -type l | sort

# 软连接形状（预期输出相对路径 + 解析到 examples/skills/digest-format）
readlink examples/agents/daily-tech-digest/skills/digest-format
readlink -f examples/agents/daily-tech-digest/skills/digest-format

# 白名单（预期命中 3 个域名 + python3）
grep -nE "open-meteo|algolia|api.github.com" oryxos-boot/src/main/resources/application.yaml
grep -n "python3" oryxos-boot/src/main/resources/application.yaml

# 凭证不落地（预期零命中）
grep -rnE "sk-[A-Za-z0-9]{10,}" examples/ specs/014-demo-agents/ || echo "no plaintext credentials"

# 变化面（预期只有 README.md 与 application.yaml 两个已跟踪文件被修改）
git status --short
git diff --stat -- '*.java'     # 预期空
```

**剩余人工项见 §四。**

---

## 四、剩余人工项（作者侧未做，MUST NOT 记成已验证）

1. **连续两天到点自动跑**：`serve` 不重启，三个 Agent 在 08:00 / 09:00 / 09:30 各推送一次；两天后 `runCount=2`、`task_executions` 六行、调度会话仍是各自那一个。作者侧只做了 `runNow` 立即执行（证明链路），**没有等真实时间**。
2. **干净机器 30 分钟部署**：删掉 `.oryxos/`（或换一台机器），严格照 README「快速开始」从零走一遍。这是课件定义的发布标准；作者侧是在已有构建与临时工作区上验的。
3. ~~**真群推送**：作者侧只验到本机 `127.0.0.1:9099` webhook（通用 `webhook` 档，`{"content": ...}`）。飞书 / Lark / 企微的方言档与业务码 fail-closed 已在第 19 节验过，但**本节三个 Agent 没有往真实群发过**。~~
   > **2026-10-23 用户实测通过（部分兑现）**：用户按 [`manual-test-guide-weather-city.md`](manual-test-guide-weather-city.md)（`--type feishu` + 真实飞书群）跑完换城市到成都这一条，**整体通过**。用户只给了整体结论、未逐步说明各步输出，故按第 19 / 22 / 25 / 26 / 28 节的留痕口径**只记整体结论、不写步骤级细节**（不代填 J1~J5 的具体值）。**覆盖面**：只覆盖 `weather-daily` 一个 Agent、且是**手动 `POST /run` 触发**；`daily-tech-digest` / `github-daily` 未往真实群发过，也**没有**在任何真实群环境下到点自动推过——所以「真群推送」这项只是部分兑现，余下部分随第 1 项（连续两天）一并验。
4. **`v0.1.0` tag**：按用户裁决**只出清单、不代打**。命令在 `manual-test-guide.md` Step 7。

---

## 五、备注

- **未 commit / push / 运行 `package.sh`**：按 skill 总纪律，同步时机由用户决定；当前改动留在 `class-31` 工作区。
- **临时工作区已清理**：`/tmp/oryxos-l31`、`/tmp/l31-tools`、webhook 日志与服务进程均已删除 / 停止；仓库内没有留下 `/tmp` 路径的运行时依赖。
- **本节没有新增抓红**：静态门禁一次通过；抓到的两处问题都在测试工具侧（webhook chunked 解码、临时工作区布局），产品代码一行未改。
- **2026-10-22 人工实跑抓到向导两处缺陷（已修，Java 一行未动）**：用户按 `manual-test-guide.md` 走真实飞书群，Step 4.2 群没收到消息。查库 `tool_invocations` 的 `notify` 全部 `success=0`、`error_message` 为「通知渠道 'team' 的 url 引用了未设置的环境变量 TEAM_WEBHOOK_URL」；同一终端的 `echo $TEAM_WEBHOOK_URL` 正常。**根因是向导顺序**：Step 0 先启动 `serve`、Step 1 才 `export TEAM_WEBHOOK_URL`，而占位符由 `serve` 进程在**发送时**解析、继承的是启动时的环境——产品 fail-closed 行为正确，模型也如实回了「推送失败」。**同处还查出第二个缺陷**：向导顶部写「真实群把地址一换、命令一模一样」，但飞书群机器人必须 `--type feishu`，用 `--type webhook` 会回 `HTTP 200` + 业务码 `19002`（第 19 节已知坑），用户当前注册的正是 `webhook` 档、只修环境变量还会撞第二个坑。**已改**：Step 0 在启动 `serve` 之前 `export` 并加警示；Step 1 加 `--type` 选择表（`webhook` / `feishu` / `lark` / `wecom`）、存错类型的 `remove` + 重加办法、改值后必须重启 `serve` 的说明；Step 4.2 补四层排查（进程环境 → 业务码 → 沙箱域名 → 群机器人关键词/签名）；坑表第 6 行收紧、新增第 16 行；README 演示段的 export / add / 启动顺序与 `--type` 同步改正。**同类缺陷第三次出现**（第 24 / 25 节向导之后）：作者侧自己的环境准备顺序没有写进步骤序列。
- **2026-10-22 天气 Demo 改为「两步查坐标」（用户裁决，`AGENT.md` 与白名单变更）**——用户把 `weather-daily` 的城市从北京改成成都后实测仍出北京结果。**不是热加载失效**（第 30 节 + 当日扩展已验「直接编辑 `AGENT.md` 免重启生效」）：旧版正文里城市名出现**两次**——一处是散文里的「城市是北京」，另一处是写进 forecast URL 的坐标字面量 `latitude=39.9&longitude=116.4`；模型改了散文、URL 仍照抄正文里的旧坐标，`tool_invocations` 的 `http_get` 入参全是北京。**做法**：城市名只写一次，第 1 步 `http_get` 打 `https://geocoding-api.open-meteo.com/v1/search?name=<城市>&count=1&language=zh&format=json` 取 `results[0].latitude/longitude`，第 2 步再用该坐标拼 Forecast URL，正文明写「必须用接口返回的坐标，不要凭记忆写经纬度」。**连带改动**：① `application.yaml` 的 `http.allowed_domains` 加 `geocoding-api.open-meteo.com`（**新增一个白名单域，属配置取值扩大、非新概念**，随包默认随之变化，部署方沿用旧 `SPRING_APPLICATION_JSON` 覆盖时须把该域一并列出——覆盖是整表替换）；② `DemoAgentsSourceTest` 的 `sandboxDefaultsCoverDemoDependencies` 断言扩到四域，并新增一条读 `weather-daily/AGENT.md` 正文的断言（必须同时出现两个端点），**类仍是 5 条测试、断言只增不减**；③ `manual-test-guide.md` Step 0 的覆盖示例、Step 4.2 的调用次数说明（一轮由 1 次 `http_get` 变 **2 次**）、坑表第 7 行（白名单清单）同步。**真链路实测**（本机 `/tmp` 工作区、真 DeepSeek）：Chengdu 查询链 `geocoding` → lat `30.66667` / lon `104.06667` → forecast → `notify`，推送内容为成都天气。**全量 `mvn clean verify` 733 条全绿**（本次 +0 条测试方法，只扩断言）。**作者侧原先的双步证据（3 次 LLM 调用 / 1 次 `http_get`）对应的是改动前的单步形态**，现在是 4 次 LLM 调用 / 2 次 `http_get`，`manual-test-guide.md` 已按新形态写。
  > **2026-10-22 修订注**：`733` 是「两步查坐标」那一轮的历史快照，**当前是 738**（其后「直接编辑重注册」+1、「编辑时自动重置」+5，见 §七）。本节其余历史段落里的 `732` / `733` 同理，只作留痕。
- **课件与仓库的五处不一致**已在上文 §三·2 与 `CLAUDE.md` 第 31 节条逐条记录；其中「Skill 用软连接」「`python3`」「记忆按 Agent 隔离」是**本仓章程 / 前序节裁决的必然结果**，不是本节可以自行放宽的地方。
- `docs/class/` 自 2026-10-14 起随仓库跟踪，本报告引用课件路径按实际相对路径书写。

---

## 六、直达根因的一次现场排查与「编辑时自动重置」（2026-10-22，用户实跑第二轮）

上一条把「改成成都仍出北京」归因于正文里城市写了两处。两步查坐标上线后，用户在同一工作区再做一次（正文只剩一处城市名、watcher 已重新登记、cron 已更新、jar 含 geocoding 白名单），`POST /api/v1/schedules/weather-daily%231/run` 后**群里仍是北京天气**。

- **排除项（都有现场证据）**：`serve` 的 cwd 与工作区一致；磁盘上的 `AGENT.md` 是成都两步版；`/schedules` 的 cron 是刚改的值（说明 watcher 重新登记成功）；`geocoding-api.open-meteo.com` 在 jar 内的白名单里；`ContextLoader` / `PromptBuilder` 每轮重读 `AGENT.md`（无缓存）。
- **真因**：`scheduler:scheduler:weather-daily` 已累积 **48 条**会话消息。模型看得到上一轮那次北京 `http_get` 的完整参数，直接照抄——`tool_invocations` 连续 8 次触发（id 29~36）都只请求 `latitude=39.9&longitude=116.4`，**一次 geocoding 都没发**。system prompt 已是新的，但压不过同一条 user 消息 + 48 条历史。
- **隔离验证**：把工作区整个复制到 `/tmp`（不带 `TEAM_WEBHOOK_URL`，**不发真实群**），只清掉该会话历史，同一份 `AGENT.md` 立刻走 `geocoding?name=成都` → `forecast?latitude=30.66667&longitude=104.06667` → `notify`（只在环境变量缺失那一步失败）。结论：**热加载没问题，问题在历史**。
- **用户裁决**：「**编辑时自动重置**」——watcher 检测到 `AGENT.md` 变更并重新登记时，清空该 Agent 的调度会话历史；启动扫描与 API 保存不动历史。
- **改动与测试**：`AgentScheduler.resetScheduleSession` + `AgentLifecycleService` 转发 + `WorkspaceWatcher.processDueEdits()` 调用（失败只 WARN）；`AgentSchedulerTest` +2、`WorkspaceWatcherTest` +2、`AgentLifecycleServiceTest` +1，全量门禁 **738** 条全绿。细节与逐条边界见 `specs/013-agent-lifecycle-api/acceptance-report.md` §八。向导同步：Step 5.1 判据含「已重置调度会话」日志行、新增 Step 5.2（换城市 + 重置）、坑表第 17 行。
- **操作提醒（现场遗留）**：为排查曾用一条过宽的 `pkill` 误杀了用户的 `serve`（已重启，PID 578401），**新进程没有 `TEAM_WEBHOOK_URL`**——该变量只活在原进程环境里、工作区与 shell 配置中都查不到，需要重新 export 再启动才可能推送到真实群。

---

## 七、第二次复核（2026-10-22，用户要求复核最近 5 小时的修改）

复核范围：第 30 节扩展（直接编辑 `AGENT.md` 免重启）→ 天气 Demo 两步查坐标 → 编辑时自动重置，以及随之而来的文档计数漂移。抓到并修了两处，**Java 产品代码一行未动**。

### 1. 缺陷一：城市字面量仍有两处读法（`weather-daily/AGENT.md`）

「两步查坐标」只把 city 从 **URL 字面量**里挪走了一半：正文里仍可能再出现一次城市名（散文 + geocoding URL），模型改了散文、URL 照抄旧值——这正是 2026-10-22 两次实跑的共同形态。**修法**：正文里城市名**只出现一次**（只出现在第 1 步 geocoding URL），并在正文写明「城市名只出现这一次」。文件由 21 行 → **25 行**。

**机检（新增断言，类仍是 5 条测试）**——`DemoAgentsSourceTest.sandboxDefaultsCoverDemoDependencies` 内：

```java
assertThat(countOccurrences(weather, "北京"))
    .as("weather-daily/AGENT.md 正文里城市字面量「北京」的个数")
    .isEqualTo(1);
```

判据强度只增不减（原有四域白名单断言、两个端点断言均保留）。运行结果：

```text
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
```

### 2. 缺陷二：文档里的当前状态计数过期（`732` → `738`）

| 文件 | 改动 |
|---|---|
| `README.md` | 状态里的测试计数 732 → **738** |
| `website/guide/roadmap.md` | 同上（738 条测试） |
| `website/en/guide/roadmap.md` | 同上（738 tests） |
| `specs/014-demo-agents/acceptance-report.md` | 结论行、§二·1、§二·4、§三·3 的当前值改为 738；**历史留痕（`732` / `733` / `723`）一律不覆盖**，只挂带日期的修订注并指向本节 |

### 3. 复核中一并更正的过期表述（只加修订注，不改原文）

- §一 交付物第 1 项「`weather-daily/AGENT.md`（21 行）」→ 修订注：现为 **25 行**。
- §一 末尾「**生产 Java 代码零改动**（`git diff --stat` 只有 README 与 application.yaml）」→ 修订注：只对交付当轮成立；当前 **16 个已跟踪文件**被修改，`git diff --stat -- '*.java'` 不再为空。
- §二·4「本节没有改任何 `oryxos-*` 生产代码」→ 修订注：交付后修复改了 `AgentScheduler` / `AgentLifecycleService` / `WorkspaceWatcher`。
- §三·1「状态 723 → 732」「`application.yaml`（+1 行）」→ 修订注：现为 738；白名单实际加了两行（`hn.algolia.com` + `geocoding-api.open-meteo.com`）。
- §五 天气 Demo 条「733 条全绿」→ 修订注：历史快照，当前 738。

### 4. 复核结论

- 两处缺陷均**已修且有机器判据**；`DemoAgentsSourceTest` 5/5 绿。
- 全量门禁仍为 **738** 条（本次未新增测试方法，只扩断言；计数未变即与 §六 的实测一致）。
- **未 commit / push / 运行 `package.sh`**。~~`serve`（PID 578401）仍缺 `TEAM_WEBHOOK_URL`，推真实群前需重新 export 并重启。~~ **2026-10-23 已过时**：用户已按 [`manual-test-guide-weather-city.md`](manual-test-guide-weather-city.md) 重建环境（Step 0 的 `/tmp/oryxos-l31-city` + 8099 端口，`serve` 启动前 export）并实跑通过，那套 PID / 端口与本条无关。

---

## 八、修订注（2026-10-23）

**真实飞书群推送已由用户实测通过（部分兑现 §四·3）**：用户按 [`manual-test-guide-weather-city.md`](manual-test-guide-weather-city.md)（`--type feishu` + 真实飞书群）跑完 `weather-daily` 换城市到成都这一条，结论**整体通过**（用户原话「实测已经通过」）。留痕口径照第 19 / 22 / 25 / 26 / 28 节：**只记整体结论、不写步骤级细节**，J1~J5 的具体数值**未回填、也不代填**（向导「用户实测记录」节同口径）。

**覆盖面（别把一条读成三条）**：只覆盖 `weather-daily` 一个 Agent、且是**手动 `POST /run` 触发**；`daily-tech-digest` / `github-daily` 未往真实群发过，也没有任何真实群环境下**到点自动**推过。余下部分随 §四·1（连续两天）一并验。

**跑这一条前用户实跑命中过两处（均为向导 / 配置问题，产品无缺陷，已修）**：① 渠道 `team` 曾以 `--type webhook` 对飞书群注册 → 飞书回 `HTTP 200` + 业务码 `19002`，`notify` 记成 `success=1` 而群里无消息；改为 `feishu` 后通过。② 向导 Step 0 先起 `serve`、Step 1 才 export `TEAM_WEBHOOK_URL`，而占位符在 serve 进程内读时解析、环境是启动时继承的快照——顺序已前移并加硬性自检（`/proc/<pid>/environ | grep -c '^TEAM_WEBHOOK_URL='` 必须是 `1`）。两处细节见向导「事故留痕」与第 31 节 CLAUDE.md 条目。

**仍待人工（截至本条，MUST NOT 记成已验证）**：§四·1 连续两天到点自动跑、§四·2 干净机器 30 分钟部署、§四·4 `v0.1.0` tag（按用户裁决只出清单、不代打）。
