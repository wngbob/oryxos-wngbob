# 第 19 节人工测试向导（Step by Step）

**适用对象**: 已交付的第 19 节 Notify 出站推送（commit `213d686`）
**目的**: 覆盖单测判不了的部分——真实外网出口、真实 IM 群 webhook 语义
**预计耗时**: 约 20 分钟

> **本向导不要求你写任何 Java 代码。** 需要手工验的东西，`curl` 与 `jshell` 就够了；已有单测覆盖的东西，不要手工再验一遍（§1 给了清单）。若某一步让你去改生产代码或新建测试类，那一步就是设计错了。

---

## 0. 先读这段：今天哪些能测、哪些不能

三条硬事实决定了本向导的形状，**先接受它们，否则后面每一步都会以为是环境坏了**：

| # | 事实 | 出处 | 后果 |
|---|---|---|---|
| ① | **零装配**——`CliAssemblyConfiguration` 一行未改，`NotifyTools` / `WebhookNotifyAdapter` / 渠道注册表实现都没有 `@Bean` 声明 | 本节裁决 4 | `notify` 工具**进不了 Spring 容器** |
| ② | **表只读**——`notify_channels` 的写入路径归第 26 节 | 本节裁决 2 | 运行期该表**恒为空** |
| ③ | ①+② 的合成 | — | `oryxos chat` → `notify` **必然报「未配置任何通知渠道」** |

①的根因是硬约束：`NotifyTools` 的构造依赖 `Sandbox`，而 `Sandbox` 的唯一实现 `WhitelistSandbox` 第 24 节才落地。当时明确 **MUST NOT 造「全放行」占位沙箱**，所以宁可不装配。

> **结论**：课件的用户场景 1（「配好群 webhook 后在对话里说推送，消息真的出现在群里」）**今天走不通，这是设计状态，不是缺陷**。
>
> **另有一条独立的、更持久的原因**（2026-10-14 实测确认，见 §4）：即使第 24/26 节把装配和写入路径接上，**通用 webhook 报文也送不进 IM 群机器人**——它们要 `msg_type`/`msgtype` 方言。这一条不会因为接线而消失，已按 FR-003 边界**记为已知边界**，适配归扩展阶段。

---

## 1. 什么才算「人工测试」——先排除已被单测覆盖的

**单测已经证明的事，手工再验一遍不是测试，是重做。** 下面是完整清单，**看一遍就行，不用跑**：

| 单测要证的事 | 由哪条断言钉死 |
|---|---|
| body **只有** `content` 一个字段 | `WebhookNotifyAdapterTest#postBodyCarriesContent`（断言 `body.size() == 1`） |
| 地址来自 `NotifyTarget` 配置、不是硬编码 | `WebhookNotifyAdapterTest#sendsToTheUrlFromTargetConfigNotHardcoded` |
| 中文 / 换行 / 引号 / 反斜杠逐字保真 | `WebhookNotifyAdapterTest#keepsContentVerbatimForChineseNewlinesAndQuotes` |
| 5xx 上抛、**不重试** | `WebhookNotifyAdapterTest#throwsOnServerErrorWithoutRetrying`（还断言 `getRequestCount() == 1`） |
| 4xx 上抛 | `WebhookNotifyAdapterTest#throwsOnClientError` |
| 地址不可达时上抛、不静默吞 | `WebhookNotifyAdapterTest#throwsWhenHostUnreachable` |
| 对端迟迟不响应时按超时上抛 | `WebhookNotifyAdapterTest#throwsWhenServerExceedsPushTimeout` |
| 配置里没有 url 或 url 空白时**一次请求都不发** | `WebhookNotifyAdapterTest#rejectsBlankUrlWithoutSendingAnyRequest` |
| **★ 白名单先于发送**（顺序写反照样过两条独立 verify，故必须 `InOrder`） | `NotifyToolsTest#enforceSandboxBeforeSending` |
| 校验动作是 `HTTP_REQUEST`、目标是解析出的地址 | `NotifyToolsTest#enforceChecksHttpRequestAgainstTheResolvedUrl` |
| **★ 白名单拒绝时一次都不发** | `NotifyToolsTest#doesNotSendWhenSandboxRejects` |
| 渠道未配置 / 名字写错 → 明确报错且不发 | `NotifyToolsTest#failsWhenNoChannelConfigured` / `#failsWhenChannelNameUnknown` |
| 渠道名缺省 → 取默认渠道 | `NotifyToolsTest#defaultChannelWhenChannelNameIsMissing` |
| 工具返回里**不含**渠道地址 | `NotifyToolsTest#returnsSuccessWithoutLeakingChannelAddress` |
| 换一档渠道实现，接口与调用方零改动 | `NotifyToolsTest#acceptsAnotherAdapterImplementationWithoutAnyChange` |

**关键前提**：`WebhookNotifyAdapterTest` 用的 `MockWebServer` **不是 mock 掉 HTTP**——它是在真实 localhost socket 上跑的真实 HTTP 服务，`takeRequest()` 拿到的是真报文。所以「HTTP 真的发出去了、body 真的长这样」**已经被证明了**，不需要再手工复现。

### 单测覆盖不到、只剩两件事

1. **真实外网出口** —— MockWebServer 在 `localhost`，从不出网。TLS、DNS、代理、企业出口策略，单测全都碰不到。
2. **真实 IM 群 webhook 的语义** —— 对端是不是真的收下并投递了。

后面就测这两件。

---

## 2. 准备

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
cd /home/wngbob/oryxos-wngbob
```

（可选）先把上面那张表对应的单测跑一遍，确认基线是绿的：

```bash
./mvnw -o -pl oryxos-tool -am test \
  -Dtest='WebhookNotifyAdapterTest,NotifyToolsTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

预期：**16 条全绿**（8 + 8）。

---

## 3. 测试 A：真实外网出口（`jshell`，零新文件）

**验什么**：这台机器上，适配器用的那套传输（JDK `HttpClient`）**能不能真的把 POST 发出去**。

**为什么它不冗余**：`WebhookNotifyAdapter` 自建 `HttpClient`（[WebhookNotifyAdapter.java:51-58](oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/notify/WebhookNotifyAdapter.java#L51-L58)），不注入 `RestClient`。而 JDK `HttpClient` 的代理走 `ProxySelector.getDefault()`，默认读的是 **JVM 系统属性**（`-Dhttps.proxyHost`），**不是** `https_proxy` 环境变量。所以「curl 能通」不等于「这个适配器能通」——尤其在一个挂着代理的 WSL 里，或企业内网出口有策略的机器上。

### Step A1 — 用 jshell 直接试 JDK HttpClient

**⚠️ jshell 从文件 / stdin 读时，每行是一段独立代码，不续行**——所有链式调用必须压在一行里，否则第二行起会被当成新表达式，报一堆 `illegal start of expression`。所以下面写成脚本文件，而不是多行 heredoc。

```bash
cat > /tmp/egress_probe.jsh <<'EOF'
var uri = java.net.URI.create("https://webhook.site/<你的 uuid>");
var req = java.net.http.HttpRequest.newBuilder(uri).timeout(java.time.Duration.ofSeconds(10)).header("Content-Type", "application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"content\":\"jshell egress probe\"}")).build();
var client = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).followRedirects(java.net.http.HttpClient.Redirect.NEVER).build();
try { var resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString()); System.out.println("HTTP " + resp.statusCode() + " | " + resp.body()); } catch (Exception e) { System.out.println("失败: " + e.getClass().getName() + ": " + e.getMessage()); }
/exit
EOF

jshell -q /tmp/egress_probe.jsh
```

地址从 <https://webhook.site> 拿（打开页面就给你一个唯一 URL）。

（想交互式逐行敲也行——交互模式下 jshell 会续行，但粘贴多行容易乱，脚本文件更稳。）

### Step A2 — 判读

| 看到 | 含义 |
|---|---|
| `HTTP 200 \| ...` 且 webhook.site 页面上出现那条 POST | ✅ 出口通，JVM 不需要额外代理参数 |
| `失败: ...ConnectException / HttpConnectTimeoutException...`，但 `curl` 打同一个地址是通的 | ⚠️ **这就是单测永远发现不了的那类问题**：这台机器上 JVM 走代理需要显式 `-Dhttps.proxyHost=`。记下来——它是部署期问题，不是本节代码问题 |
| `HTTP 3xx` | 符合 `Redirect.NEVER` 的设计（3xx 按失败处理） |
| `curl` 报 `HTTP 000` 但 JVM 通 | ⚠️ **反向的那一类**：curl 读 `https_proxy` 环境变量，JVM `HttpClient` **不读**。见下方实测 |

**顺手对照**（分离「机器的问题」和「JVM 的问题」）：

```bash
curl -sS -o /dev/null -w 'curl: HTTP %{http_code}\n' \
  -H 'Content-Type: application/json' \
  -d '{"content":"curl egress probe"}' \
  'https://webhook.site/<你的 uuid>'
```

- [ ] 记下 JVM 与 curl 的结果是否一致

#### 实测补充（2026-10-14，本机 WSL）

**A1 结果**（`jshell -q /tmp/egress_probe.jsh`，目标 webhook.site）：

```text
HTTP 200 | This URL has no default content configured. <a href="https://webhook.site/#!/edit/2942ff82-...">Change response in Webhook.site</a>.
```

✅ **JVM 出口通** —— JDK `HttpClient` 直连外网成功，**没有加任何 `-Dhttps.proxyHost` 系统属性**。也就是说 `WebhookNotifyAdapter` 在这台机器上能把请求发出去，**出口不是障碍**。

**而同一轮里 curl 却先失败了**：

```text
curl → HTTP 000（无任何响应，-o 指定的文件也没生成）
```

根因不是网络，是 shell 里设了 `https_proxy` 之类的代理变量，把本该直连的域名劫持到了一个走不通的节点。`unset http_proxy https_proxy all_proxy HTTP_PROXY HTTPS_PROXY ALL_PROXY` 之后立刻 `HTTP 200`。

**这一对结果恰好把测试 A 的核心命题演了一遍**：同一个地址、同一台机器、同一时刻，**curl 不通而 JVM 通**。因为两者的代理来源不同——curl 读环境变量，JDK `HttpClient` 读 `-Dhttps.proxyHost` 这类 JVM 系统属性。所以「curl 通/不通」**推不出**「适配器通/不通」，两者必须各测各的。

另外 `-s` 会把 curl 自己的错误一起吞掉，失败时你只看得到 `HTTP 000`——**排查时用 `-sS`**。

---

## 4. 测试 B：真实 IM 群 webhook ★ 已实测确认（结论：记为已知边界）

**验什么**：课件用户场景 1 承诺的「消息真的出现在群里」。

> **⚠️ 前置条件（实测踩到的第一个坑）**：本机 curl 之前一直返回 `HTTP 000`，根因是 shell 里设了 `https_proxy` 之类代理变量，把本该直连的国内域名劫持到了一个走不通的节点。**跑本节任何 curl 之前先 `unset` 掉它们**：
>
> ```bash
> unset http_proxy https_proxy all_proxy HTTP_PROXY HTTPS_PROXY ALL_PROXY
> ```
>
> 另注：`WebhookNotifyAdapter` 自建的 JDK `HttpClient` **只读 JVM 系统属性、不读这些环境变量**，所以它从来不受此坑影响——这也是「curl 通 ≠ 适配器通」的反向例子。

### Step B1 — 拿一个群机器人 webhook

- **飞书**：群设置 → 群机器人 → 添加自定义机器人 → 复制 Webhook 地址（形如 `https://open.feishu.cn/open-apis/bot/v2/hook/<uuid>`）
- **企业微信**：群 → 右上角 `...` → 群机器人 → 添加 → 复制 Webhook 地址（形如 `https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=<uuid>`）

（实测用的是飞书。）

### Step B2 — 把**适配器实际送出的报文**原样打过去

报文形状不用猜——`WebhookNotifyAdapterTest#postBodyCarriesContent` 已经断言了它**只有 `content` 一个字段**。所以：

```bash
curl -sS -o /tmp/wx_resp.txt -w 'HTTP %{http_code}\n' \
  -H 'Content-Type: application/json' \
  -d '{"content":"OryxOS 第19节人工测试"}' \
  'https://open.feishu.cn/open-apis/bot/v2/hook/<你的 key>'
cat /tmp/wx_resp.txt
```

（用 `-sS` 而不是 `-s`：`-s` 会把 curl 自己的错误一起吞掉，失败时你只看到 `HTTP 000` 和「文件不存在」，查不出原因。）

### Step B3 — 实测输出（2026-10-14）

```text
HTTP 200
{"code":19002,"data":{},"msg":"params error, msg_type need"}
```

**群里什么都不会出现。** 问题成立。

#### 为什么会这样

两件事叠加：

1. **我们的报文是通用的**：body 是 `{"content":"..."}`，只有一个字段。代码注释写得很直白——「加上 `msgtype` 之类就成了某一家 webhook 的方言」（FR-003 的刻意边界）。
2. **IM 群机器人不认这个形状**：飞书要 `{"msg_type":"text","content":{"text":"..."}}`，企微要 `{"msgtype":"text","text":{"content":"..."}}`。收到 `{"content":...}` 时，飞书回 `code:19002 "msg_type need"`，企微回 `errcode:40008 "invalid message type"`。

**致命的是第 3 件事**：IM 群机器人的**业务错误走 HTTP 200**——HTTP 层成功、`code`/`errcode` 非 0 才表示业务失败。而 `WebhookNotifyAdapter.send()` 用的是 `.retrieve().toBodilessEntity()`（[WebhookNotifyAdapter.java:69-75](oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/notify/WebhookNotifyAdapter.java#L69-L75)），**只看 HTTP 状态码，不解析响应体**。于是：

```text
飞书: HTTP 200 + {"code":19002,"msg":"params error, msg_type need"}
                ↓
WebhookNotifyAdapter: 看到 2xx → 不抛异常
                ↓
NotifyTools.notify: return ToolResult.success("已推送")
                ↓
Agent 以为发出去了 —— 实际一条都没到
```

失败信号藏在响应体里，通用的「2xx 即送达」口径看不见它。

#### 这意味着什么

- **不是实现写错了**：`{"content": ...}` + 「2xx 即送达」对**通用 webhook 接收端**是正确口径，且是 FR-003 明确要求的边界。课件验收标准里那条「5xx 上抛、不静默吞」也是真过的（MockWebServer 真返回 5xx）——**只是真实 IM 机器人不返回 5xx**。
- **是验收报告 §7.1 写得过于乐观**：它承诺「配好一个真实群 webhook 后，`notify` 真能把消息送进群」——对通用接收端成立，对 **IM 群机器人不成立**。
- **是课件用户场景 1 与 FR-003 之间的一处真实张力**：前者要「消息出现在群里」，后者只要「通用 webhook」。

#### 处置：已定 —— 选项 A（记为已知边界），本节代码一行不改

| 选项 | 做法 | 代价 | 裁决 |
|---|---|---|---|
| **A. 记为已知边界** | 在验收报告与 CLAUDE.md 里写明「通用 webhook ≠ IM 群机器人，对接 IM 要等扩展阶段的专用实现」 | 零成本，但课件场景 1 在核心阶段无法演示 | ✅ **采纳**（2026-10-14 软门禁 ③ 报批后定） |
| B. 收紧送达判定 | `send()` 解析响应体，`code`/`errcode` 非 0 即抛 | 需要引入「哪些键算错误」的判据——那本身就是某家方言，撞 FR-003 | ❌ 不采纳 |
| C. 报文加方言适配 | 按 `NotifyTarget.channelType` 选报文形状 | 把「某一家方言」写进核心阶段，撞 FR-001 + FR-003 | ❌ 不采纳 |

**B 和 C 看着更「对」，其实都违反本节自己定的边界。** 这正是「接口先行」的代价：通用接口 + 一档实现，代价就是通用实现不认识具体方言。要真适配，那是**扩展阶段加第二档实现**（如 `FeishuNotifyAdapter`）——**不改 `NotifyChannelAdapter` 接口、不改调用方**，这才是这套设计预留的升级路径。

已回修的三处：`acceptance-report.md` §7、`tasks.md` Notes、`CLAUDE.md`「当前状态」跨节挂账②。

#### 对照组（把结论钉死）—— ✅ 已跑，结论证死

只把报文换成**飞书的正确方言**，地址、网络、key 全部不变：

```bash
curl -sS -o /tmp/wx_resp2.txt -w 'HTTP %{http_code}\n' \
  -H 'Content-Type: application/json' \
  -d '{"msg_type":"text","content":{"text":"OryxOS 第19节对照测试"}}' \
  'https://open.feishu.cn/open-apis/bot/v2/hook/<你的 key>'
cat /tmp/wx_resp2.txt
```

**实测输出**（2026-10-14）：

```text
HTTP 200
{"StatusCode":0,"StatusMessage":"success","code":0,"data":{},"msg":"success"}
```

`code:0` + `StatusMessage:"success"`。

**这一条把结论钉死了**：

| | 报文 | 飞书响应 |
|---|---|---|
| B2 | `{"content":"..."}`（适配器实际送的） | `HTTP 200` + `code:19002` "msg_type need" |
| B-对照 | `{"msg_type":"text","content":{"text":"..."}}` | `HTTP 200` + `code:0` "success" |

**地址、网络、key、机器人、群，全部相同，只有报文形状不同——一个失败一个成功。** 所以失败原因**就是报文形状**，不再是推断。

而且这一对结果本身就是那个致命点的完整演示：**两条都是 `HTTP 200`**。只看状态码的 `WebhookNotifyAdapter` 对这两次投递会给出**完全相同**的判断——「成功」。

#### 适配器视角的探针 —— 可跳过

原计划用 `/tmp/feishu_probe.jsh`（JDK `HttpClient` 打飞书）拿"适配器视角"的同一份证据。**A1 与 B2 已经分别覆盖了它的两个变量**（A1 证 JVM 能出网、B2 证飞书对这个报文的响应），所以这一枪的边际价值已经很低，可跳过。

---

## 5. 测试 C：真实 SQLite 表确实建出来了

**验什么**：`oryxos init` 产出的真库里，`notify_channels` 表真的存在（单测用的是临时库 + 自己跑脚本）。

```bash
cd /tmp && rm -rf oryxos-l19-check && mkdir oryxos-l19-check && cd oryxos-l19-check
java -jar /home/wngbob/oryxos-wngbob/oryxos-boot/target/oryxos.jar init
python3 - <<'PY'
import sqlite3
con = sqlite3.connect('.oryxos/oryxos.db')
print(con.execute(
    "SELECT sql FROM sqlite_master WHERE name='notify_channels'").fetchone()[0])
PY
```

（**用 `python3` 而不是 `sqlite3`**：本机没装 sqlite3 CLI，而 Python 标准库自带 `sqlite3` 模块，读同一个库文件，零安装。要 CLI 也行：`sudo apt install sqlite3`。）

**实测输出**（2026-10-14）：

```text
表: llm_calls, notify_channels, sessions, sqlite_sequence, tool_invocations

CREATE TABLE notify_channels (
    name        VARCHAR(128) PRIMARY KEY,
    type        VARCHAR(32),
    url         VARCHAR(1024),
    config_json TEXT,
    description VARCHAR(512)
)
```

> **别被 `CREATE TABLE`（没有 `IF NOT EXISTS`）吓到**：`schema.sql` 里写的是 `CREATE TABLE IF NOT EXISTS`，而 **SQLite 存进 `sqlite_master` 时会把这个子句剥掉**。所以这是正常的，不是脚本写错了。
>
> `sqlite_sequence` 是 `sessions` 等表用了 `AUTOINCREMENT` 自动产生的，与 `notify_channels` 无关——**`notify_channels` 的 DDL 里没有 `AUTOINCREMENT`，符合预期**。
>
> `scheduled_tasks` / `task_executions` 此刻**还不存在**，是正常的：那两张表归第 25 节（定时任务）建。

- [ ] 五列齐备，与 `specs/000-runtime-kernel/data-model.md` A6 逐字一致
- [ ] `name` 是 PRIMARY KEY，且**没有** `AUTOINCREMENT`（它是业务键）

**再插一行，然后观察一件事**：

```bash
python3 - <<'PY'
import sqlite3
con = sqlite3.connect('.oryxos/oryxos.db')
con.execute("INSERT INTO notify_channels(name,type,url,description) VALUES(?,?,?,?)",
            ('manual', 'webhook', 'https://example.com/hook', '人工测试渠道'))
con.commit()
print(list(con.execute("SELECT name,type,url FROM notify_channels")))
PY
```

**现在表里有数据了**。但用 CLI 跑 `notify` 仍然会失败——因为**工具根本不在容器里**（§0 事实①）。这一步的意义正是把 §0 的结论钉实：**表空不空不是障碍，装配才是**。

---

## 6. 测试 D：CLI 全链路（今天跑不了）

课件用户场景 1 的完整形态：`oryxos chat` → 模型决定调 `notify` → 消息进群。

**今天跑不通，两处都堵着**：

| 堵点 | 挡住它的 | 解开它的 |
|---|---|---|
| `notify` 工具不在容器里 | 零装配（无 `Sandbox` 实现可注入） | 第 24 节 |
| `notify_channels` 没有写入路径 | 本节只读 | 第 26 节 |

**等第 24 + 26 节落地后**，本测试应变成：

```bash
cd /tmp/oryxos-l19-check
java -jar /home/wngbob/oryxos-wngbob/oryxos-boot/target/oryxos.jar chat --profile <某 Agent>
> 把测试消息推送到 manual 渠道
```

**预期**：Agent 调用 `notify`，消息出现在目标群；`tool_invocations` 表多一条记录。

**今天不要做这一步**——做了只会看到「未配置任何通知渠道」，那是预期状态，不是线索。

> 注：届时若仍只配通用 webhook，测试 B 的结论依然适用——**接线不会改变报文形状**。要走 IM 群机器人，得先处置 §4 的选项（**已定：A，方言适配作为新增实现类归扩展阶段**）。

---

## 7. 思维练习：接口中立性（不用运行）

课件的人工项之一，**没有可执行形态**，但有可判定的部分：

> 如果要把 `WebhookNotifyAdapter` 换成「企业微信官方 SDK 实现」，`NotifyChannelAdapter.send(NotifyTarget, String)` 这个签名需要改吗？

**答案应是不需要**。验证方法（注意先排除注释——两份契约的 javadoc 里合法出现取值示例与实现名，它们正是在解释「为什么这样设计」）：

```bash
# ① 接口签名里不得出现任何一档实现特有的词
grep -nE '企业微信|飞书|钉钉|WeCom|Feishu|DingTalk|Webhook|OkHttp|RestClient' \
  oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/notify/NotifyChannelAdapter.java \
  | grep -vE '^[^:]+:[0-9]+:\s*(\*|/\*|//)'
# 预期：0 命中

# ② NotifyTools 的代码行不得依赖具体实现
grep -nE 'WebhookNotifyAdapter|JpaNotifyChannelRegistry' \
  oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/notify/NotifyTools.java \
  | grep -vE '^[^:]+:[0-9]+:\s*(\*|/\*|//)'
# 预期：0 命中
```

已由单测 `NotifyToolsTest#acceptsAnotherAdapterImplementationWithoutAnyChange` 覆盖可判定部分。

---

## 8. 实测记录表（2026-10-14 实测）

| # | 测试 | 手段 | 实际结果 | 状态 |
|---|---|---|---|---|
| 基线 | 已有单测 26 条 + 全量门禁 | `./mvnw -o clean verify` | **BUILD SUCCESS**（57s），87 条测试全绿（storage 9 / core 45 / provider 7 / tool 26） | ✅ 已跑 |
| A1 | JVM 外网出口 | `jshell` | **`HTTP 200`** —— JVM 直连外网成功，无需任何代理系统属性 | ✅ 已跑，**出口通** |
| A2 | curl 对照 | `curl` | **`HTTP 000`** → `unset` 代理变量后 **`HTTP 200`** | ✅ 已跑，结论：代理变量劫持 |
| B2 | 通用报文打 IM 群（飞书） | `curl` | **`HTTP 200` + `{"code":19002,"msg":"params error, msg_type need"}`**，群里无消息 | ✅ 已跑，**问题成立** |
| B-对照 | 正确方言打 IM 群 | `curl` | **`HTTP 200` + `{"code":0,"msg":"success"}`** | ✅ 已跑，**结论证死** |
| B-探针 | 适配器视角同款 HttpClient | `jshell` | 跳过（A1 + B2 已覆盖其两个变量） | ⏭️ 跳过 |
| C | 真实 SQLite 建表 | `python3` | **`notify_channels` 建出，五列齐备，`name` 为 PK 且无 `AUTOINCREMENT`**；既有表 `sessions` / `tool_invocations` / `llm_calls` 俱在 | ✅ 已跑 |
| D | CLI 全链路 | — | **今天跑不了** | ⬜ 等第 24/26 节 |

**A1 与 A2 是一对**：同一地址、同一机器、同一时刻，**curl 被代理变量挡住而 JVM 直连通过**——正好把「curl 通不通推不出适配器通不通」这条命题实测演示了一遍。

**剩下唯一没跑的只有 D**，它阻塞于第 24/26 节的接线，属合理推迟。

**已定结论**：测试 B 命中的问题**成立**，处置采纳 **选项 A（记为已知边界）**，本节代码一行不改。

---

## 9. 做完之后（本次已完成 1~3）

1. ✅ **测试 B 的处置已定**：选项 A（§4）——软门禁 ③ 已报批。
2. ✅ **三处过度乐观的表述已回修**：
   - `specs/004-notify-outbound/acceptance-report.md` §7 第 1 条 —— 已改为「只对通用 webhook 接收端成立」，并附实测证据；
   - `specs/004-notify-outbound/tasks.md` Notes —— 已加跨节挂账「IM 群机器人方言适配」；
   - `CLAUDE.md`「当前状态」—— 已加「第 19 节跨节挂账②」。
3. ✅ **实测结果已回填**验收报告 §7，人工项从「待做」变成「已做，结论是……」。

**已跑完**：基线门禁（87 绿）、A1、A2、B2、B-对照、C —— 见 §8 状态列。**B-对照成立**，所以「失败原因是报文形状」这个结论已经**证死**，不再是推断。

**仍待跑**：只有 **D（CLI 全链路）**，阻塞于第 24/26 节的接线，属合理推迟。B-探针已判定跳过。

---

## 参考来源

- [飞书开放平台 — 自定义机器人使用指南（`msg_type` / `content.text`）](https://open.feishu.cn/document/client-docs/bot-v3/add-custom-bot)
- [飞书开放平台 — 自定义机器人错误码](https://open.feishu.cn/document/client-docs/bot-v3/add-custom-bot#%E9%94%99%E8%AF%AF%E7%A0%81)
- [企业微信 API — 消息格式](https://qiyeweixin.apifox.cn/doc-417805)
- [企业微信 API — 消息类型](https://qiyeweixin.apifox.cn/doc-1803587)
- [微信开放社区 — `errcode:40008`（invalid message type）案例](https://developers.weixin.qq.com/community/develop/doc/0002ae37beca30fab9ba807d951000)
