# 第 20 节人工验收向导（step by step）

| 项 | 值 |
| --- | --- |
| 对应 feature | `specs/005-tool-registry/` |
| 分支 | `class-20` |
| 编写日期 | 2026-10-15 |
| 前置 | `./mvnw -o clean verify -Ddependency-check.skip=true` 全绿（harness 已判卷）；`oryxos-boot/target/oryxos.jar` 已按当前源码打好 |
| 覆盖 | 课件「五、做完怎么验」的 4 条 + 验收报告「剩余人工项」两表共 10 条，去重后 **12 项** |
| 本向导的口径 | **机器判得了的一律已在 harness 里判掉**（§2），本向导只留机器判不了的；每条都给「命令 → 预期 → 判据」 |

> **本向导里 M1~M9 的输出是 2026-10-15 在本机真实跑出来的转录，不是示意。** M10 也真跑过（跑完已回退）。**M11 / M12 已于 2026-10-15 由用户人工执行完毕**——本节 12 项人工项至此全部验过。

---

## §0 先读这段：这次要验的是什么

第 20 节交付了 **13 个内置工具 + 三档来源的注册表 + MCP 客户端 + 临时沙箱**。`mvn clean verify` 全绿说明 **238 条测试**都过了，但它证明不了这几件事——所以才有这份向导：

1. **真模型会不会正确构造工具调用**（测试里模型是替身）；
2. **工具在真链路里好不好用**（分页、超时、去标签这些"深度"只有真跑才看得见）；
3. **真 MCP server 连不连得上、工具进不进得来**（测试里 MCP 客户端是替身）；
4. **审计表里有没有留下痕迹**（测试里断言的是调用发生，不是库里的行）。

> **⚠️ 沙箱窗口期（读代码前必知）**：第 20~23 节挂的是临时实现 `PermissiveSandbox`——它**不做任何白名单校验**。所以本向导里 `read_file /etc/passwd`、`shell rm -rf` 这类越界调用**会真的成功**，这是**已报批的取舍**，不是 bug。**MUST NOT 把「越界没被拦」当成沙箱已就位**；白名单本体归第 24 节。

> **⚠️ 课件的「方式一」有一条本节覆盖不到，别当成漏做**：课件「五、做完怎么验」第 1 条是「方式一真跑一次：写一份 SKILL.md + 连一个真实 MCP server」。**本节只到「配置里声明 MCP server」这一步**——SKILL.md 与 `skills/` 软连接属 **Skill 绑定**，归**第 29 节**（`spec.md` 冲突仲裁 8；`ProfileLoader` 的既有禁令已把 `skills` 钉死为不可出现在 `AGENT.md` frontmatter，与 `notify_channels` 同一条）。所以本向导只验**方式二（真 MCP server，M8）**与**方式三（`@Tool` Bean，M10）**，方式一不在此列，**不是漏验**。

---

## §1 一次性准备（约 3 分钟）

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64     # 按本机实际
JAR=/home/wngbob/oryxos-wngbob/oryxos-boot/target/oryxos.jar

# 1) 干净的工作区（不要拿你日常在用的那个，本向导会往里塞文件）
rm -rf /tmp/oryxos-l20-e2e && mkdir -p /tmp/oryxos-l20-e2e && cd /tmp/oryxos-l20-e2e
$JAVA_HOME/bin/java -jar $JAR init

# 2) 一个只负责调工具的演示 Agent
$JAVA_HOME/bin/java -jar $JAR profile create tool-demo

# 3) 把它的 AGENT.md 换成下面这份（frontmatter 的 tools 列表要与实际存在的工具名一致）
cat > .oryxos/agents/tool-demo/AGENT.md <<'EOF'
---
description: "第 20 节人工验收用的演示 Agent：按指令调用工具并把结果原样汇报"
identity:
  agent_name: "tool-demo"
  prompt: |
    你是一个工具调用演示助手。用户让你做什么，你就用工具做完，然后原样汇报工具返回的内容。
    不要自己编造文件内容或命令输出；不要省略工具返回里的「已截断」这类提示。
    如果一次读不全，就用 offset 参数继续读，直到拿到用户要的那一段。
provider:
  name: "deepseek"
  model: "deepseek-chat"
tools: [read_file, write_file, list_dir, shell, http_get, http_post, notify, edit_file, grep, glob, ask_user, web_search, web_fetch]
mcp_servers: []
settings:
  max_iterations: 10
  max_history_turns: 20
---

## 你负责什么

按用户指令调用工具，把工具的真实返回如实转述。

## 你可以怎么做

读文件用 read_file（可带 offset/limit）；跑命令用 shell（可带 timeoutSeconds）；抓网页用 web_fetch；搜索用 web_search。
EOF

# 4) 造一个 3000 行的文件，用来验 read_file 的分页与截断提示
python3 -c "
with open('/tmp/oryxos-l20-e2e/big.txt','w') as f:
    for i in range(1, 3001):
        f.write('line %d: the quick brown fox jumps over the lazy dog\n' % i)
"
wc -l big.txt      # 预期 3000 big.txt
```

**凭证检查**（只看存在性，不要打印值）：

```bash
[ -n "$DEEPSEEK_API_KEY" ] && echo "DEEPSEEK_API_KEY 已设置 (len=${#DEEPSEEK_API_KEY})" || echo "DEEPSEEK_API_KEY 未设置 —— M2~M5、M7、M10 跑不了"
[ -n "$TAVILY_API_KEY" ]   && echo "TAVILY_API_KEY 已设置 (len=${#TAVILY_API_KEY})"   || echo "TAVILY_API_KEY 未设置 —— M5 会回退到免 key 档，M6 照样能跑"
```

**网络**：本机 WSL 里 `api.deepseek.com` / `example.com` / `docs.spring.io` / `api.tavily.com` 都**直连可达**；`api.duckduckgo.com` **直连超时、必须给 JVM 指定代理**（M6 的命令里已带上）。

---

## §2 已被 harness 覆盖、**不要**手工重复的项

课件与验收报告里点名"已由 harness 覆盖"的，跑 `mvn test` 绿即打勾：

| 课件/报告里的说法 | 由谁覆盖 |
| --- | --- |
| 契约三件套（名 / 描述 / Schema）都非空，**每个**工具都要过 | `OryxToolContractTest`（参数化遍历注册表，新工具自动纳入） |
| 三种来源的工具都以统一抽象身份注册 | `ToolRegistryTest` |
| 按 Agent 声明的清单过滤后**精确匹配、不多不少** | `ToolRegistryTest` |
| 每个内置工具"正常能跑通 + 越界会被拦"两条 | `FileToolsTest` / `ShellToolsTest` / `HttpToolsTest` 等 |
| MCP 失联隔离（不抛异常、好的 server 照常注册） | `McpClientServiceTest` |
| 工具调用成功失败都落 `tool_invocations` | `ToolExecutorTest`（第 17 节） |
| 加工具的三处横切关注点零改动 | `ToolOnboardingCostTest` |

> **注意**：上面"越界会被拦"那两条在**运行期拦不住**——拦它们的 `WhitelistSandbox` 归第 24 节，现在挂的是 `PermissiveSandbox`。单测里注入的是测试替身沙箱，所以那两条**测试绿 ≠ 运行时被拦**。§0 的窗口期提醒说的就是这件事。

---

## §3 逐项手工验证

### M1 · 工具清单目检 —— `oryxos tool list`

**目的**：13 个内置工具真的在册；`annotation` / `mcp` 空组按契约照样打印并注明原因；沙箱窗口期的 WARN 真的打出来。

```bash
cd /tmp/oryxos-l20-e2e
$JAVA_HOME/bin/java -jar $JAR tool list
```

**预期**（本机实测，已删去日志行）：

```text
WARN ... i.o.o.tool.sandbox.PermissiveSandbox : 沙箱当前挂的是临时实现（PermissiveSandbox）：白名单校验不生效。第 24 节落地 WhitelistSandbox 后自动替换。
Tool（共 13 个）:
  builtin (13):
    notify / edit_file / glob / grep / list_dir / read_file / write_file / shell
    / http_get / http_post / web_fetch / ask_user / web_search
  annotation (0):
    （无）
  mcp (0):
    （无：.oryxos/mcp_servers.yaml 里没有声明任何 server）
```

**判据**：① 总数 13；② 三行组头都在；③ 两个空组各有说明行；④ **`PermissiveSandbox` 的 WARN 在**（这是窗口期的留痕，不是噪音）；⑤ 退出码 0。

---

### M2 · `read_file` 分页与截断 —— 真模型必须自己续读

**目的**：单次上限 2000 行真的生效；截断说明真的带续读指令；模型看到说明后**真的会用 `offset` 续读**。

```bash
cd /tmp/oryxos-l20-e2e
timeout 300 $JAVA_HOME/bin/java -jar $JAR chat --profile tool-demo \
  --message "用 read_file 读 /tmp/oryxos-l20-e2e/big.txt，告诉我这个文件一共有多少行、以及第 2500 行写了什么。如果一次读不全就继续读。" \
  < /dev/null
```

**预期**（本机实测转录）：

```text
读完了，两次读到了完整文件。
- 总行数：3000 行（第一次读返回的截断提示写明「共 3000 行」；第二次读以 line 3000 结束，无截断提示）
- 第 2500 行：line 2500: the quick brown fox jumps over the lazy dog
依据：第一次 read_file 返回第 1~2000 行，末尾提示
「（已截断：共 3000 行，本次显示第 1~2000 行；用 offset=2001 继续）」；我用 offset=2001 续读……
```

**判据**：模型**两次**调 `read_file`（第二次带 `offset: 2001`），且它引用了截断提示原文。只调一次就说"读完了"= 不合格。

---

### M3 · `shell` 的 `timeoutSeconds` 覆盖默认值

**目的**：默认 30 秒可被参数覆盖；超时被**判为失败**并走失败审计路径。

```bash
timeout 300 $JAVA_HOME/bin/java -jar $JAR chat --profile tool-demo \
  --message "用 shell 工具执行 sleep 40，并把 timeoutSeconds 参数设为 10。执行完把工具的返回原样告诉我，包括是否超时、报的什么错。" \
  < /dev/null
```

**预期**（本机实测转录）：

```text
WARN ... i.o.oryxos.core.engine.ToolExecutor : 工具调用失败
已执行 `sleep 40`，`timeoutSeconds=10`。工具原样返回：
  命令超时（10 秒）: sleep
```

**判据**：① 10 秒左右返回（不是 30 秒、更不是 40 秒）；② 返回文本是「命令超时（10 秒）」；③ 日志里出现 `ToolExecutor` 的「工具调用失败」WARN——说明失败走了既有审计路径（与 M7 互相印证）。

**对照组（可选）**：把 `timeoutSeconds` 去掉再跑一次 `sleep 40` → 应等到约 30 秒才超时。

---

### M4 · `web_fetch` 抓网页并去标签

```bash
timeout 300 $JAVA_HOME/bin/java -jar $JAR chat --profile tool-demo \
  --message "用 web_fetch 抓 https://example.com/ ，把工具返回的正文原样贴出来（不要自己总结）。" \
  < /dev/null
```

**预期**（本机实测转录）：

```text
Example Domain
This domain is for use in documentation examples without needing permission. This is not a service; avoid relying on it for testing and monitoring purposes.
```

**判据**：返回里**没有** `<html>` / `<div>` / `<a href=...>` 这类标签，只剩正文；实体（`&amp;` / `&nbsp;`）已解码。

**顺带验截断**（可选）：换一个长文档页（如 `https://docs.spring.io/spring-boot/index.html`）→ 应在 20000 字符处截断并注明。

---

### M5 · `web_search` —— **带 key 档**（Tavily）

```bash
timeout 300 $JAVA_HOME/bin/java -jar $JAR chat --profile tool-demo \
  --message "用 web_search 搜「spring boot 3.5 release notes」，把工具返回的前 3 条结果的标题和链接原样列出来。" \
  < /dev/null
```

**预期**（本机实测转录，注意这是**长尾查询也能出真结果**的那一档）：

```text
1. Spring Boot Release Notes | HeroDevs Docs
   https://docs.herodevs.com/spring/release-notes/spring-boot-3-5
2. Upgrading to Spring Boot 3.5: What's Worth the Effort? | Medium
   https://medium.com/devdomain/upgrading-to-spring-boot-3-5-whats-worth-the-effort-00d12fe9d6d5
3. Spring Boot 3.5 Release Notes · spring-projects/spring-boot Wiki · GitHub
   https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-3.5-Release-Notes
```

**判据**：有 3 条真链接、结果条数远大于 3（本机返回 20 条）。**若返回 0 条**，先确认 `TAVILY_API_KEY` 在不在——不在就是走了免 key 档（见 M6），长尾查询返 0 条是那一档的已知性质。

---

### M6 · `web_search` —— **免 key 档**（DuckDuckGo，含 UA 修复的端到端确认）

**目的**：不配任何 key 的机器上这一档**仍然可用**（`DuckDuckGoSearchProvider` 的 `User-Agent` 修复在真 jar 里生效）。

```bash
cd /tmp/oryxos-l20-e2e
timeout 300 env -u TAVILY_API_KEY $JAVA_HOME/bin/java \
  -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897 \
  -jar $JAR chat --profile tool-demo \
  --message "用 web_search 搜「Alan Turing」，把工具返回的条目原样列出来（有几条列几条）。" \
  < /dev/null
```

**预期**（本机实测转录）：返回 **28 条**，首条是 `Alan Turing | https://en.wikipedia.org/wiki/Alan_Turing`。

**判据**：① 条数明显大于 0；② 没有 `HTTP 403`。**403 就是 UA 修复没生效**（修复前 JDK 默认 UA 一律被回 403）。

> **两个必须分清的边界**：
> ① **代理**：本机 `api.duckduckgo.com` 直连超时，必须带 `-Dhttps.proxyHost/-Dhttps.proxyPort`（换机器视网络而定，不加就是 `搜索服务不可达`）。
> ② **长尾查询仍会返 0 条**——这一档打的是 Instant Answer「问答」接口，不是检索接口。`Alan Turing` 有词条所以有结果，`spring boot 3.5 release notes` 会 0 条。**这与 UA 无关**，正是 M5 那一档存在的理由。

---

### M7 · 审计落库目检 —— `tool_invocations` / `llm_calls`

**目的**：上面每一次工具调用都落了库，**成功失败都落**（章程原则 6）。

```bash
cd /tmp/oryxos-l20-e2e
python3 -c "
import sqlite3
c = sqlite3.connect('.oryxos/oryxos.db')
rows = list(c.execute('select tool_name, success, duration_ms, substr(input_json,1,80), substr(coalesce(error_message,\"\"),1,36) from tool_invocations order by id'))
for r in rows:
    print('%-17s success=%-6s %7sms  in=%s  err=%s' % r)
print()
print('tool_invocations:', len(rows), '条（失败', sum(1 for r in rows if not r[1]), '条）')
print('llm_calls:', list(c.execute('select count(*) from llm_calls'))[0][0], '条')
"
```

**预期**（本机实测转录，跑完 M1~M6 之后）：

```text
read_file         success=1           28ms  in={"path": "/tmp/oryxos-l20-e2e/big.txt"}  err=
read_file         success=1            2ms  in={"path": "/tmp/oryxos-l20-e2e/big.txt", "offset": 2001}  err=
shell             success=0        10024ms  in={"command": "sleep 40", "timeoutSeconds": 10}  err=命令超时（10 秒）: sleep
web_fetch         success=1          773ms  in={"url": "https://example.com/"}  err=
web_search        success=1         2580ms  in={"query": "spring boot 3.5 release notes"}  err=
web_search        success=1         3362ms  in={"query": "Alan Turing"}  err=
```

**判据**：① 每条工具调用一行，`input_json` 是模型真传的参数（含 `offset` / `timeoutSeconds` 这类可选参数）；② **超时那次是 `success=0` 且 `error_message` 有内容**——失败也落库；③ `duration_ms` 与实际耗时对得上（`sleep 40` 那次约 10000ms）。

---

### M8 · 真实 MCP server 连上并列出工具

**目的**：FR-009——真 server 连得上、`listTools()` 拉得到、每个工具被包成统一抽象注册进注册表；并且**声明了才可用**（FR-011 的过滤不多不少）。

```bash
cd /tmp/oryxos-l20-e2e
# 用官方参考实现里的 Python 版 time server（不需要任何凭证）
cat > .oryxos/mcp_servers.yaml <<'EOF'
servers:
  - name: time
    transport: stdio
    command: ["/home/wngbob/.local/bin/uvx", "mcp-server-time"]
EOF

$JAVA_HOME/bin/java -jar $JAR tool list
```

**预期**（本机实测转录）：

```text
Tool（共 15 个）:
  builtin (13): ...
  annotation (0):
    （无）
  mcp (2):
    mcp (2):
      get_current_time  Get current time in a specific timezone      mcp:time
      convert_time      Convert time between timezones               mcp:time
```

> **`mcp (2):` 出现了两次、内层看不到 server 名——这是本次手工测试抓到的一处缺陷**，见 §4。

**再验"声明了才能用"**：`AGENT.md` 的 `tools` 列表此时**不含** `get_current_time`，直接问模型"现在几点"它**调不到**（FR-011 的过滤精确匹配）。把 `get_current_time, convert_time` 加进 `tools:` 再跑：

```bash
timeout 300 $JAVA_HOME/bin/java -jar $JAR chat --profile tool-demo \
  --message "用 get_current_time 工具查一下 Asia/Shanghai 现在几点，把工具返回原样告诉我。" \
  < /dev/null
```

**预期**（本机实测转录）：

```text
工具 `get_current_time`（timezone=Asia/Shanghai）原样返回：
{ "timezone": "Asia/Shanghai", "datetime": "2026-10-15T09:29:54+08:00", "day_of_week": "Thursday", "is_dst": false }
```

**判据**：真 JSON 回来了（不是模型编的时间）；`tool_invocations` 里多一行 `get_current_time`。

**踩坑提示**：`uvx` 首次要联网装包（本机已缓存，装包约 40ms）；`command` 写绝对路径最稳。

---

### M9 · MCP 失联隔离（FR-010）—— 坏的 server 不能拖垮启动

```bash
cd /tmp/oryxos-l20-e2e
cat > .oryxos/mcp_servers.yaml <<'EOF'
servers:
  - name: time
    transport: stdio
    command: ["/home/wngbob/.local/bin/uvx", "mcp-server-time"]
  - name: ghost
    transport: stdio
    command: ["/nonexistent/definitely-not-a-real-command"]
EOF
$JAVA_HOME/bin/java -jar $JAR tool list
```

**预期**（本机实测转录）：

```text
WARN ... i.o.oryxos.tool.mcp.McpClientService : MCP server 连接或初始化失败，跳过它的工具
Tool（共 15 个）:
  builtin (13): ...
  mcp (2):
    ... get_current_time / convert_time   （time 的工具照常注册）
```

**判据**：① 进程**照常起**、退出码 0；② 坏 server 只 WARN + 跳过，**不是异常**；③ 好 server 的工具一个不少。多等约 10 秒是 FR-009 的有界等待，属预期。

---

### M10 · 方式三（重代码 `@Tool` Bean）真跑一次

**目的**：课件「五」的第一条人工项——业务方写一个带注解的 Java 方法，`tool list` 里看得见、Agent 调得通。

**这一步要改仓库代码 + 重新打包**（属于"业务方接入动作"，不是本节交付物），跑完请**按 §6 回退**。

```bash
cd /home/wngbob/oryxos-wngbob

# (1) 写一个带 @Tool 的普通类（注意：没有 Spring 注解）
cat > oryxos-cli/src/main/java/io/oryxlabs/oryxos/cli/DemoWeatherTools.java <<'EOF'
package io.oryxlabs.oryxos.cli;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/** 【人工验收临时夹具，跑完请删除】方式三的演示工具。 */
public class DemoWeatherTools {

  @Tool(name = "demo_weather", description = "查一个城市的天气（人工验收用的演示工具，返回固定数据）")
  public String demoWeather(@ToolParam(description = "城市名，如 杭州") String city) {
    return "演示数据：" + city + " 今天晴，25℃，东南风 2 级。";
  }
}
EOF
```

再手工改 `oryxos-cli/src/main/java/io/oryxlabs/oryxos/cli/CliAssemblyConfiguration.java` **三处**：

```java
// ① 在 toolRegistry() 的参数表末尾（mcpClientService 之前）加一个参数
      WebSearchTools webSearchTools,
      DemoWeatherTools demoWeatherTools,        // ← 新增
      McpClientService mcpClientService) {

// ② 在方法体里登记（放在内置之后、connectAll 之前）
    registry.registerAnnotated(webSearchTools, "builtin");
    registry.registerAnnotated(demoWeatherTools, "annotation");   // ← 新增
    // 最后才连 MCP
    mcpClientService.connectAll(registry);

// ③ 加一个 @Bean 方法（放在 toolRegistry() 之后、toolCatalog() 之前）
  @Bean
  public DemoWeatherTools demoWeatherTools() {
    return new DemoWeatherTools();
  }
```

```bash
# (2) 重新打包
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
./mvnw -o -q clean package -DskipTests -Ddependency-check.skip=true

# (3) 看清单
cd /tmp/oryxos-l20-e2e
$JAVA_HOME/bin/java -jar $JAR tool list
```

**预期**（本机实测转录）：

```text
Tool（共 16 个）:
  builtin (13): ...
  annotation (1):
    demo_weather      查一个城市的天气（人工验收用的演示工具，返回固定数据）      annotation
  mcp (2): ...
```

```bash
# (4) 把 demo_weather 加进 AGENT.md 的 tools 列表，再让 Agent 调它
timeout 300 $JAVA_HOME/bin/java -jar $JAR chat --profile tool-demo \
  --message "用 demo_weather 工具查一下杭州的天气，把工具返回原样告诉我。" \
  < /dev/null
```

**预期**（本机实测转录）：

```text
工具 `demo_weather`（city=杭州）原样返回：
  演示数据：杭州 今天晴，25℃，东南风 2 级。
```

**判据**：① `annotation (1)` 出现且来源列是 `annotation`；② 模型能调通、拿回工具的真返回。

> **一个与课件的口径差异，如实说明**：课件写的是"写一个带 `@Tool` 注解的 Spring Bean，放进工程，**启动自动注册**"。本实现里**没有 `@Tool` 扫描逻辑**——`ToolRegistry` 里一行扫描代码都没有，被包装的 Bean 由 `CliAssemblyConfiguration` **显式传入**（工具来源是装配期事实，不是扫描期猜测；见 `specs/005-tool-registry/plan.md` 与 `contracts/assembly.md`）。所以方式三的真实成本是**「写一个类 + 装配处加一行」**（FR-016 说的"成本恒定在写一个类"），不是"扔进工程就自动生效"。这是**有意为之**，不是没做完。

---

### M11 · `ask_user` 在真实终端里的交互体验

**目的**：FR-013——人在另一端时能真的问到；**没有人在时明确报错、不静默卡住**。

```bash
cd /tmp/oryxos-l20-e2e
$JAVA_HOME/bin/java -jar $JAR chat --profile tool-demo      # 注意：交互模式，不要加 --message
```

> **怎么退出这个交互模式**：输入 **`/quit`**（带斜杠），或按 **Ctrl+D**（输入流结束，等同 `/quit`，退出码 0）。
> **裸 `quit` / `exit` 不是退出指令**——它们会被当成一句普通消息发给模型，模型多半回一句「已退出」之类的告别语，**但进程仍在 `>` 提示符前等着你**（2026-10-15 人工验收时踩过这个坑）。判断依据很简单：退出前若出现了一行 `ProviderService : LLM 调用完成`，说明它走了一次模型调用，那就没退。`/quit` 是第 18 节契约钉住的字面量（`specs/003-cli-session/contracts/cli-surface.md` §2.10）。

然后输入一句**信息不全**的话，逼模型反问，例如：

```text
帮我查一下明天的天气，然后推给群里。
```

**预期**：终端上出现 `ask_user` 抛出的问题并**等你敲键盘**；你回答后对话继续。

**对照组（验"不静默卡住"）**：单发模式没有人在另一端，`ConsoleUserInteraction` 拿到空回答 → 工具**明确报错**而不是挂住：

```bash
timeout 120 $JAVA_HOME/bin/java -jar $JAR chat --profile tool-demo \
  --message "用 ask_user 工具问我一个问题。" < /dev/null
```

**判据**：① 交互模式下真的阻塞等输入、输入后继续；② `< /dev/null` 时**明确报错**（`tool_invocations` 里是 `success=0`），进程在超时前正常退出。

---

### M12 · 沙箱窗口期团队确认（人工裁决，不是测试）

**这不是一条"跑一下"的测试**，是要你**确认并留痕**的一件事：

- 第 20~23 节交付的运行时**没有 Tool 治理**——`PermissiveSandbox` 不做任何白名单校验；
- 缓解四条：① 类名与 javadoc 明示临时 ② 装配时打 WARN（M1 已见）③ `@ConditionalOnMissingBean(Sandbox.class)`，第 24 节 `WhitelistSandbox` 一落地就自动让位 ④ CLAUDE.md 与验收报告双处留痕；
- **要你确认的是**：这个窗口期在团队内部**已知悉并接受**，且**验收时不把「越界没被拦」当成沙箱已就位**。

**⚠️ 工具不能从命令行直接调**（2026-10-15 人工验收时试过 `oryxos tool read_file '/etc/passwd'`，报 `Unmatched arguments`）：`oryxos tool` **只有 `list` 一个子命令**，没有 `oryxos tool <工具名> <参数>` 这种用法。这不是没实现——**工具是给模型调的，不是给人敲的**（FR-001：LLM 决定调哪个工具、传什么参数，OryxOS 负责执行；真正调用入口在 ReAct 循环里）。要手工验一个工具，**让 Agent 去调**：写一句 `--message` 或交互模式里说一句，模型自己会选工具。`oryxos tool list` 是本节唯一的工具相关命令，且是只读的清单查询。

**顺带自测一条**（预期会**成功**，这就是窗口期的证据）：

```bash
$JAVA_HOME/bin/java -jar $JAR chat --profile tool-demo \
  --message "用 read_file 读 /etc/passwd，把前 3 行原样告诉我。" < /dev/null
```

**预期**：**读成功**。这不是 bug、不是本节验收失败项——它是窗口期的实测证据。第 24 节之后同一条命令应变成被拦 + `SandboxViolationException`。

---

## §4 手工测试抓到的一处缺陷（**已修 + 已补测试**）

**`oryxos tool list` 的 MCP 子分组头部丢了 server 名。**

- **现象**：接上一个真实 MCP server 后，输出是
  ```text
  mcp (2):
    mcp (2):                       ← 这里应当是 mcp:time (2):
      get_current_time  ...        mcp:time
      convert_time      ...        mcp:time
  ```
- **契约要求**：`specs/005-tool-registry/contracts/assembly.md` §5.2 第二条原文——「分组键 = `source` 的前缀（`builtin` / `annotation` / `mcp`），**`mcp` 组内按 `mcp:<server名>` 再分组**」；`ToolListCommand` 自己的 javadoc（`:40` / `:99`）也这么写。
- **根因**：`ToolListCommand` 的内层分组复用了外层那个 `group()`，而 `group()` 按 `prefix(entry.source())` 取键——`mcp:time` 被削成 `mcp`，于是打印出来的组名是 `mcp` 而不是 `mcp:time`。**一个 server 时表现为多余的一行；两个以上 server 时，多个 server 的工具会被并进同一个头，完全分不出谁是谁。**
- **为什么没被测试挡住**：`oryxos-cli/src/test` 下当时只有 3 个文件（`CliTestContext` / `OryxToolContractTest` / `ToolAssemblyTest`），**没有 `ToolListCommandTest`**——§5.2 的输出格式整段没有测试覆盖。
- **影响面**：仅输出可读性，不影响工具的注册与可用性（行尾的「来源」列仍正确显示 `mcp:time`）。
- **处置（2026-10-15，用户批准后动手）**：
  1. **修**：新增私有方法 `bySource(...)`（键 = `source` 全串），内层分组改调它；`group()` 保留原样只服务顶层三组，javadoc 补上「顶层用 `group`、组内用 `bySource`」的分工说明。**对外行为零变化**，命令名 / 参数 / 退出码一个没碰。
  2. **补测试**：新增 `oryxos-cli/src/test/java/io/oryxlabs/oryxos/cli/command/ToolListCommandTest.java`（**5 条**），把 §5.2 的输出格式钉住——两个 server 各占一个表头、单 server 也带 server 名、`builtin` 组不分子表头、空组照样打说明行、首行总数。
  3. **验证测试有牙齿**：把修复临时回退后重跑，**3 条断言变红**（`print_mcpToolsFromTwoServers_headsEachServerByName` / `print_mcpToolsFromOneServer_headsByServerName` / `print_header_showsTotalCount`），恢复后 5 条全绿。不这么验一遍，无法排除「测试写得刚好也通过」。
  4. **测试怎么调私有方法**：`print` 是私有的纯格式化函数，用反射调（本仓已有先例：`ToolOnboardingCostTest` 用 `getDeclaredMethods()`）。**刻意不把 `print` 放宽成包级私有**——那会动已交付类的成员可见性，本节的取舍是「对已交付类的触碰越小越好」。代价：重命名 `print` 时测试会以 `NoSuchMethodException` 报错而非编译失败。

---

## §5 实测记录表

**12 项已全部跑完（2026-10-15）。** M1~M9 的实测转录见上文各条；M10 跑完已回退夹具；M11 / M12 由用户人工执行。

| # | 项 | 预期 | 实测 | 判定 |
| --- | --- | --- | --- | --- |
| M1 | `tool list` = 13 个 + 三组头 + 沙箱 WARN | 13 / `builtin (13)` / 两空组带说明 / WARN 在 / 退出码 0 | 全部相符 | ✅ |
| M2 | `read_file` 截断提示 + 模型用 `offset` 续读 | 模型调两次、引述截断说明 | 相符 | ✅ |
| M3 | `shell` `timeoutSeconds=10` 覆盖默认、判失败 | 约 10 秒返回 + 超时文案 | 相符 | ✅ |
| M4 | `web_fetch` 去标签、实体解码 | 正文纯文本 | 相符 | ✅ |
| M5 | 带 key 档 `web_search` 出真结果 | 3 条真链接 | 相符（20 条） | ✅ |
| M6 | 免 key 档可用、无 403 | 条数 > 0、无 403 | 相符（28 条，首条 Wikipedia） | ✅ |
| M7 | `tool_invocations` 成功失败都落库 | 成功与失败行都在 | 相符（`shell success=0` 那条在） | ✅ |
| M8 | 真 MCP server 连上、工具进注册表、Agent 调得通 | `mcp (2)` + Agent 拿到真 JSON | 相符（`uvx mcp-server-time`） | ✅ |
| M9 | MCP 失联隔离：只 WARN、启动不炸 | 坏 server 跳过、好工具一个不少 | 相符 | ✅ |
| M10 | 方式三 `@Tool` Bean：`annotation (1)` + Agent 调得通 | 16 个工具、`annotation (1): demo_weather` | 相符（跑完已回退） | ✅ |
| M11 | `ask_user` 交互可用 / 无人时明确报错 | 交互阻塞等输入；`< /dev/null` 明确报错 | 相符（用户人工执行） | ✅ |
| M12 | 沙箱窗口期团队确认 | `/etc/passwd` 读成功 = 窗口期证据；团队已知悉并接受 | 相符（用户人工执行 + 自测） | ✅ |

**已知与本机相关的两点**（换机器可能不同）：
- `api.duckduckgo.com` 本机直连超时，M6 必须带 `-Dhttps.proxyHost/-Dhttps.proxyPort`；
- `api.deepseek.com` 本次实测期间多次回 `503 Service is too busy`，`RetryUtils` 自动重试后成功——**这是 provider 侧的抖动，不是本节的缺陷**，遇到时重跑即可。

---

## §6 收尾

```bash
# 1) 回退 M10 的临时夹具（三处改动 + 一个新文件）—— 2026-10-15 已执行完毕
cd /home/wngbob/oryxos-wngbob
rm -f oryxos-cli/src/main/java/io/oryxlabs/oryxos/cli/DemoWeatherTools.java
#    然后手工撤销 CliAssemblyConfiguration.java 的三处改动（参数、registerAnnotated 行、@Bean 方法）
grep -rn "DemoWeatherTools\|demo_weather" oryxos-cli/src/ && echo "!! 还有残留" || echo "已清干净"

# 2) 重新打包，恢复与源码一致的 jar
./mvnw -o -q clean package -DskipTests -Ddependency-check.skip=true

# 3) 删掉临时工作区
rm -rf /tmp/oryxos-l20-e2e
```

**已执行情况（2026-10-15）**：① M10 夹具已回退并核过零残留；② jar 已按当前源码重打；③ 临时工作区待清理。

**收尾动作**：本节的人工验收到此结束，`package.sh` / commit / merge / push 由用户决定并已授权执行——**这三件事不在本向导的范围内**，本向导只管「跑起来看对不对」。
