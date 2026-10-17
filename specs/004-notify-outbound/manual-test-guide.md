# `notify` 真渠道验收向导（Step by Step）

**适用对象**：第 19 节 Notify 出站推送 + 其后三次补强（方言档 / 路由接缝、渠道写入面、企微方言档）的最终形态
**目的**：覆盖单测判不了的部分——真实外网出口、真实 IM 群 webhook 的语义、以及**推不进去时会不会假报成功**
**预计耗时**：约 30 分钟（含构建）；只跑企微那条路约 20 分钟
**已跑记录（2026-10-15）**：

- **飞书路径**：用户按 Step 0~9 **全程人工跑过一遍，未发现问题**，**并确认消息真的进了飞书群**（见 [acceptance-report.md](acceptance-report.md) 补强②·十四 与 补强③·十二）
- **企微路径（Step 5b）**：同日按该路径实测，**并确认消息真的进了企微群**（见补强③ §八）
- **Lark**：**未验收**——用户 2026-10-15 明确裁决**暂不验收**（不是遗漏，是有意推迟；Lark 与飞书同一套协议、同一个实现类，差异只在域名）

下面的步骤照旧可重跑——它是**可复现的过程**，不是一次性记录。**三档的验收覆盖面并不等价**，横向对照见 [acceptance-report.md](acceptance-report.md) 补强③·十二。

> **本文件 2026-10-15 重新生成。** 旧版是「2026-10-14 首版 + 两次补强」叠出来的修订注堆：同一件事有三处互相打脸的结论，命令里有 `java -jar .../oryxos.jar` 这种跑不通的省略号，配渠道的主路径还停在手工 `sqlite3 INSERT`。
>
> 现在重写成单一路径：**正文只讲今天该怎么做**；历史实测证据（当时的原始输出、当时的裁决）整段收进 **[附录 A](#附录-a--历史实测留痕2026-10-14-首版-3--5-收编)**。章节编号从 `§N` 改为 `A.N`——外部文档里引用「`manual-test-guide.md` §4」的地方，指的是**附录 A.2**。
>
> **本向导不要求你写任何 Java 代码。** 需要手工验的，`curl`、`python3` 标准库与 CLI 就够了；已有单测覆盖的东西不要手工再验一遍（见下方「先排除已被单测覆盖的」）。若某一步让你去改生产代码或新建测试类，那一步就是设计错了。

---

## 这份向导要证明什么

三条判据，全部围绕同一件事：**`notify` 说「已推送」的时候，是不是真的推到了。**

| # | 判据 | 在哪一步 |
| --- | --- | --- |
| 1 | **正向**：Agent 调 `notify`，消息**真的出现在飞书群里**，且逐字一致 | Step 6 ★ |
| 2 | **反向**：推不进去时**必须报错并落审计**，MUST NOT 假报「已推送」 | Step 7 ★★（**本次的核心**） |
| 3 | **路由**：`type` 写歪在**本地就拦下**，一次请求都不发 | Step 8 |

> **两条方言路径，同一组判据。** 正文以**飞书**为主线；**企业微信**是另一档方言（`type=wecom`，报文形状是飞书的镜像），
> 走 **Step 5b** 配置，其后 Step 6 / 7 / 8 **一字不改**照跑即可——它们验的是「有没有真送到、失败会不会假报成功」，
> 与用哪家方言无关。**换方言只换 `--type` 的取值和 webhook 地址，不换流程。**

判据 2 是整件事的来由：2026-10-14 实测发现，IM 群机器人**用 `HTTP 200` 承载业务错误**，而通用档只判 HTTP 状态码——于是失败被记成成功（原始证据见 [附录 A.2](#a2-测试-b真实-im-群-webhook-方言不匹配原-4)）。

### 今天的四条前置事实（先接受，否则后面每一步都会以为环境坏了）

| # | 事实 | 出处 |
| --- | --- | --- |
| ① | **13 个工具已在 Spring 容器里**，`notify` 可 `tool list` 出来、`chat` 里真能调 | 第 20 节装配 |
| ② | **渠道写入面 = CLI 三条轻命令** `notify-channel add\|list\|remove`（原生 JDBC，不启 Spring）。日常配渠道**只有这一条路**，不再需要手工 `INSERT` | 2026-10-15 补强② |
| ③ | **`type` 的合法取值由装配处的路由表决定**：`webhook` / `feishu` / `lark` / `wecom`（四个）。写歪的 `type` 在路由处报错，**一次请求都不发** | 2026-10-15 补强①，补强③ 加 `wecom` |
| ④ | ~~**沙箱挂的是临时实现 `PermissiveSandbox`**——它只打一条 WARN，**不拦任何东西**。`WhitelistSandbox` 本体归第 24 节~~ **← 2026-10-17 失效，见下方修订注** | 第 20 节（窗口期，见 `CLAUDE.md`） |

> **④ 有一条硬约束**：本向导跑通**不能**当作「沙箱已就位」的证据。终端上那条 `PermissiveSandbox` 的 **WARN 是故意打的，不是报错**，MUST NOT 被静音。
>
> **2026-10-17 修订注（沙箱窗口期已关闭）**：④ 与紧邻的这条硬约束**同时失效**。`PermissiveSandbox` 已按第 24 节裁决 3 **整类删除**（`grep -rn "PermissiveSandbox" oryxos-*/src` 零命中），现挂的是 `WhitelistSandbox`，三类白名单真在校验。故：**本向导跑通现在可以**当作「沙箱已就位」的证据；`open.feishu.cn` / `open.larksuite.com` / `qyapi.weixin.qq.com` 三个域名已进随包默认 `http.allowed_domains`（`oryxos-boot/src/main/resources/application.yaml`）。**原文保留以留痕**。另注意：「WARN 级诊断走 stderr、MUST NOT 被静音」这条**通用**日志契约**仍然成立**——它不随那条 WARN 消失而消失，只是那条 WARN 本身已不存在。
>
> **凭证纪律（贯穿全页）**：webhook 地址里的 token 与签名密钥是**同一等级的凭证**。本页所有命令里它们都写成 `${ENV_VAR}` 占位符。**MUST NOT** 把真实值粘进对话、日志、报错或命令输出（FR-019）。库文件里存的也是**占位符字面量**，真值只活在环境变量里。

---

## 先排除已被单测覆盖的（看一遍即可，不用跑）

**单测已经证明的事，手工再验一遍不是测试，是重做。**

| 单测要证的事 | 由哪条断言钉死 |
| --- | --- |
| body **只有** `content` 一个字段 | `WebhookNotifyAdapterTest#postBodyCarriesContent`（断言 `body.size() == 1`） |
| 地址来自 `NotifyTarget` 配置、不是硬编码 | `WebhookNotifyAdapterTest#sendsToTheUrlFromTargetConfigNotHardcoded` |
| 中文 / 换行 / 引号 / 反斜杠逐字保真 | `WebhookNotifyAdapterTest#keepsContentVerbatimForChineseNewlinesAndQuotes` |
| 5xx 上抛、**不重试**；4xx 上抛；地址不可达上抛；超时上抛 | `#throwsOnServerErrorWithoutRetrying` / `#throwsOnClientError` / `#throwsWhenHostUnreachable` / `#throwsWhenServerExceedsPushTimeout` |
| 配置里没有 url 或 url 空白时**一次请求都不发** | `WebhookNotifyAdapterTest#rejectsBlankUrlWithoutSendingAnyRequest` |
| **★ 白名单先于发送**（顺序写反照样过两条独立 verify，故必须 `InOrder`） | `NotifyToolsTest#enforceSandboxBeforeSending` |
| **★ 白名单拒绝时一次都不发** | `NotifyToolsTest#doesNotSendWhenSandboxRejects` |
| 渠道未配置 / 名字写错 → 明确报错且不发 | `NotifyToolsTest#failsWhenNoChannelConfigured` / `#failsWhenChannelNameUnknown` |
| 渠道名缺省 → 取默认渠道（按 `name` 升序第一条） | `NotifyToolsTest#defaultChannelWhenChannelNameIsMissing` |
| 工具返回里**不含**渠道地址 | `NotifyToolsTest#returnsSuccessWithoutLeakingChannelAddress` |
| 换一档渠道实现，接口与调用方零改动 | `NotifyToolsTest#acceptsAnotherAdapterImplementationWithoutAnyChange` |
| 飞书业务码非 0 时 fail-closed 判失败（含 `code` 缺失的写法陷阱） | `FeishuNotifyAdapterTest` |
| **企微**报文方言（`msgtype` **无下划线**、正文在 `text.content`，且**不含** `msg_type`） | `WeComNotifyAdapterTest#bodyUsesMsgtypeWithoutUnderscoreUnlikeFeishu` |
| **企微**业务码 `errcode` 非 0 时 fail-closed 判失败；**不认**飞书那个 `StatusCode` | `WeComNotifyAdapterTest#throwsWhenHttpOkButBusinessErrcodeIsNotZero` / `#doesNotAcceptFeishuLegacyStatusCodeField` |
| 未知 `type` 报错、列已知类型、**一次请求都不发** | `RoutingNotifyChannelAdapterTest` |
| `notify-channel` 的输出**不回显 url 与 config 值** | `NotifyChannelAddCommandTest` / `NotifyChannelListCommandTest` |
| CLI 写入 → 读路径解析 `${ENV_VAR}` 的**往返**（两半对行形状的理解一致） | `NotifyChannelRoundTripTest` |

**关键前提**：`WebhookNotifyAdapterTest` 用的 `MockWebServer` **不是 mock 掉 HTTP**——它是在真实 localhost socket 上跑的真实 HTTP 服务，`takeRequest()` 拿到的是真报文。所以「HTTP 真的发出去了、body 真的长这样」**已经被证明了**。

### 单测覆盖不到、只剩三件事

1. **真实外网出口** —— MockWebServer 在 `localhost`，从不出网。TLS、DNS、代理、企业出口策略，单测全都碰不到。
2. **真实 IM 群 webhook 的语义** —— 对端是不是真的收下并投递了。
3. **端到端：模型决定调工具 → 工具真的把消息推出去 → 失败真的落审计。** 单测全是各自 mock 一半，抓不到这类断裂。

---

## 准备

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
cd /home/wngbob/oryxos-wngbob
```

**LLM 凭证**（重命令 `chat` / `tool list` 启动 Spring 上下文时会解析它）：

```bash
export DEEPSEEK_API_KEY='<你的 key>'
```

> 没设的话，重命令**在启动期就失败**，报错形如 `Could not resolve placeholder 'DEEPSEEK_API_KEY'`——消息里**只有变量名，没有凭证值**，这是设计如此。`oryxos.providers[].api-key` 在 `application.yaml` 里写死了 `${DEEPSEEK_API_KEY}` 占位，**明文写进去是被禁止的**（章程 VIII / FR-010）。

（可选）先把基线跑一遍，确认是绿的：

```bash
./mvnw -o -pl oryxos-tool -am test \
  -Dtest='WebhookNotifyAdapterTest,NotifyToolsTest,FeishuNotifyAdapterTest,RoutingNotifyChannelAdapterTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

---

## Step 0 — 构建，并确认你手上这份 jar 是对的

```bash
cd /home/wngbob/oryxos-wngbob
./mvnw clean package -DskipTests -Ddependency-check.skip=true
export ORYXOS_JAR=/home/wngbob/oryxos-wngbob/oryxos-boot/target/oryxos.jar
echo "$ORYXOS_JAR"
```

**自检**（两个补强的新类必须已在发行物里；`oryxos.jar` 是 Spring Boot **嵌套 jar**，`oryxos-tool` 在 `BOOT-INF/lib/` 里，所以要看**嵌套层**而不是顶层）：

```bash
cd /tmp && rm -rf oryxos-jarcheck && mkdir oryxos-jarcheck && cd oryxos-jarcheck
unzip -o -q "$ORYXOS_JAR" 'BOOT-INF/lib/oryxos-tool-*.jar'
unzip -l BOOT-INF/lib/oryxos-tool-*.jar | grep -E "FeishuNotifyAdapter|RoutingNotifyChannelAdapter"
cd /tmp && rm -rf oryxos-jarcheck
```

> **顶层 `unzip -l "$ORYXOS_JAR" | grep Feishu` 查不到是正常的**，不是构建没生效——业务类在内层 jar 里。这条踩过一次。

> **⚠️ MUST 用 `$ORYXOS_JAR`（= `oryxos-boot/target/oryxos.jar`），不要用 `dist/oryxos.jar`。** `dist/` 是 `scripts/package.sh` 的产物，**只有跑过打包脚本才会更新**——仓库里现成那份可能是几天前的旧构建，缺了后续任何一次修复（2026-10-15 实测：`dist/oryxos.jar` 是当天 11:16 的，没有同日 14:23 的日志分流修复，于是「修了却没生效」）。**排查任何「改了没效果」之前，先 `echo "$ORYXOS_JAR"` 确认指的是哪个文件。**

---

## Step 1 — 拿一个群机器人 webhook

在飞书群里「设置 → 群机器人 → 添加机器人 → 自定义机器人」，拿到形如
`https://open.feishu.cn/open-apis/bot/v2/hook/<TOKEN>` 的地址。

**安全设置先按最简单的来**（本步只建一个「正常」渠道；故障注入在 Step 7 用**另建渠道**的办法做，不用改这个）：

```bash
export FEISHU_HOOK_URL='<你的 webhook 地址>'
```

> 地址里带 token，**只 export 到环境变量，别粘进对话、别写进文件**。
>
> **Lark（海外版）**同一步，域名是 `open.larksuite.com`，协议一致——`type` 写 `lark` 即可。
> **⚠️ Lark 档截至 2026-10-15 未验收**（用户裁决暂不验收，不是遗漏）。它与飞书**同一套协议、同一个实现类**，故本步及以下 Step 6~8 照跑即可，但**别把飞书的验收结论外推到 Lark**。
>
> **如果机器人开了「签名校验」**：飞书会再给一个 `secret`，同样只进环境变量：
> ```bash
> export FEISHU_BOT_SECRET='<你的 secret>'
> ```
> 下面的 Step 5 给了带 / 不带签名两种写法。

> **企业微信（另一档方言）**：在企微群里「群设置 → 群机器人 → 添加」拿到形如
> `https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=<KEY>` 的地址，配法与飞书**同流程、只换取值**——
> `--type wecom`、环境变量 `WECOM_HOOK_URL`，**且没有 `secret` 那一档**（企微群机器人**没有签名机制**，
> 凭证就是 URL 里的 `key`，可选的是 IP 白名单）。详见 **Step 5b**。
> 两条路径可以在同一个工作区里并存（渠道名不同即可），互不干扰。

---

## Step 2 — 初始化工作区

```bash
cd /tmp && rm -rf oryxos-feishu-check && mkdir oryxos-feishu-check && cd oryxos-feishu-check
java -jar "$ORYXOS_JAR" init
```

**为什么单独一个 `/tmp` 目录**：工作区根 = 进程当前目录，库文件是**相对路径** `.oryxos/oryxos.db`。在仓库根跑会污染仓库；在 home 下跑会攒出忘删的目录（**本机口径：临时测试一律放 `/tmp`，不放 home**）。Step 9 会把它删掉。

**注意 `agents/` 建出来是空的**——`init` 刻意不预置任何 Agent。

---

## Step 3 — 建 Agent 并加上 `notify` 工具

```bash
java -jar "$ORYXOS_JAR" profile create notifier
```

它生成 `.oryxos/agents/notifier/AGENT.md`，但 **`tools` 是空的**。用下面这份整份替换——措辞刻意写死，是为了让模型**稳定地调用 `notify`**，而不是礼貌地答应一句然后什么都不做：

```bash
cat > .oryxos/agents/notifier/AGENT.md <<'EOF'
---
description: "通知推送测试助手"
identity:
  agent_name: "notifier"
  prompt: "你是一个通知推送助手。当用户给你一段文字并要求推送时，你必须调用 notify 工具把它推到指定渠道，然后如实回报推送结果。不要改写文字，不要反问确认，不要使用其他工具。如果 notify 报错，把错误原文转述给用户，不要自己编造成功。"
provider:
  name: "deepseek"
  model: "deepseek-chat"
tools: [notify]
mcp_servers: []
settings:
  max_iterations: 10
  max_history_turns: 20
---

## 你负责什么

用户给你一段文字和一个渠道名，你调用 notify 把它推出去。

## 你可以怎么做

只用 notify 工具。推送失败时，把工具返回的错误信息原样告诉用户。
EOF
```

**`tools: [notify]` 这一行是硬前提**：`ToolRegistry.availableFor(profile)` 严格按 Profile 的 `tools` 列表过滤，列表为空 = 模型**看不见** `notify`，于是它会答应然后什么都不发生——这是**假失败**，会让人误判成底座有问题。

`provider.name` 必须是 `deepseek`（当前 `SUPPORTED_PROVIDERS` 里唯一一项），与 `application.yaml` 的 `oryxos.providers` 清单对得上，否则 `profile list` 会把它标成无效。

渠道名到 URL 的解析由注册表完成，**渠道不属于 Profile**——Agent 只按名引用。

---

## Step 4 — 确认工具真的在容器里

```bash
java -jar "$ORYXOS_JAR" tool list
```

预期看到 `builtin (13)` 分组且其中含 `notify`。**没有就先停下**——问题在装配，不在飞书。

> **⚠️ 终端上仍然看得见日志，这是对的——分流不是静音。** 日志走 **stderr**、表格走 **stdout**；终端把两股都渲染出来，所以肉眼看到的还是「日志 + 表格」。**区别只在管道 / 重定向时才显现。**
>
> ```bash
> java -jar "$ORYXOS_JAR" tool list 2>/dev/null | head -5   # → 只剩表格
> java -jar "$ORYXOS_JAR" tool list 2>/dev/null | wc -l     # → 19，正好是表格本身
> ```
>
> **第二条也是「修复在不在你手上这份 jar 里」的判据**：`19` = 修好了；`46` 左右 = 你跑的是旧 jar（回 Step 0 看 `dist/` 警告）。
>
> 日志里那条 `PermissiveSandbox` 的 **WARN 是故意打的**（沙箱窗口期），**不是报错**。
>
> **2026-10-17 修订注**：上面这条**已失效**——那条 WARN 自第 24 节起**根本不会再出现**（`PermissiveSandbox` 已整类删除）。看到它只可能是你在跑第 24 节之前的旧 jar。**原文保留以留痕**。
>
> **⚠️ 同段的 `wc -l` → `19` 也过期了（实测 `21`）**：那个数字数的是 `tool list` 的表格行数，会随内置工具数变化——第 22 节把内置工具从 13 个加到 15 个，故它不再是 `19`。**2026-10-17 在第 24 节后的 jar 上实测：`21`**（`builtin (15)` 一段 + `annotation (0)` / `mcp (0)` 各两行 + 首行「Tool（共 15 个）:」）。**本次只回修「沙箱窗口期」这一类失效；工具数变化引起的失效是另一笔账，未在此处理**——照抄前以实际输出为准。

---

## Step 5 — 配一个渠道（CLI，唯一路径）

```bash
# 机器人没开签名校验：
java -jar "$ORYXOS_JAR" notify-channel add team-feishu --type feishu \
    --url-env FEISHU_HOOK_URL --description '飞书团队群（人工验收）'

# 机器人开了签名校验：多带一个 --config
java -jar "$ORYXOS_JAR" notify-channel add team-feishu --type feishu \
    --url-env FEISHU_HOOK_URL --config 'secret=${FEISHU_BOT_SECRET}' \
    --description '飞书团队群（人工验收）'
```

**`--url-env FEISHU_HOOK_URL` 是 `--url '${FEISHU_HOOK_URL}'` 的糖，且是推荐路径**：入库的是**字面量占位符**，真值由读路径（`JpaNotifyChannelRegistry`）在解析渠道时从环境变量取。所以 **token 不进库、不进 shell 历史**。`--config secret=...` 同理——库里存的是占位符本身。

- **`--url` 与 `--url-env` 互斥**，都给会明确报错。
- **`--config` 只在第一个 `=` 处切分**（base64 的 secret 常以 `=` 结尾）；键重复报错；**`--config url=...` 会被拒**（地址只能走 `--url` / `--url-env`，否则会造出第二个真相源）。
- **重名不覆盖**：报错并提示先 `remove`。
- **老工作区也能直接 `add`**：命令自带幂等建表（`CREATE TABLE IF NOT EXISTS`），不需要先重建工作区。

### 验一下库里到底存了什么（本步的核心断言）

```bash
java -jar "$ORYXOS_JAR" notify-channel list
# 预期：url 列打印 ${FEISHU_HOOK_URL}（占位符是变量名、不是凭证，所以可以打）
#       config 列只打键名 secret，不打值
#       按注册名升序，第一行就是默认渠道
```

```bash
python3 - <<'PY'
import sqlite3, json
con = sqlite3.connect('.oryxos/oryxos.db')
url, cfg = con.execute(
    "SELECT url, config_json FROM notify_channels WHERE name='team-feishu'").fetchone()
print('url 是占位符字面量:', url == '${FEISHU_HOOK_URL}')
print('config 键:', sorted(json.loads(cfg)) if cfg else [])
PY
```

> 上面这段**故意不打印 url 与 config 的值**——只打印布尔判断与**键名**。这是本文件自己遵守的输出纪律：`url` 含 token、`config` 的值可能是密钥。

- [ ] `url` 列是 `${FEISHU_HOOK_URL}`，**不是**真 token
- [ ] `url 是占位符字面量: True`
- [ ] `config 键: ['secret']`（没开签名校验时是 `[]`）
- [ ] `list` 的输出里**没有**任何 token / 密钥

> **为什么不手工 `INSERT`**：手工写法（`INSERT OR REPLACE INTO notify_channels ... os.environ['FEISHU_HOOK_URL'] ...`）现在**仍然能用**（读路径读的是同一张表），但它有两个毛病：真值会**落库**，且 `INSERT OR REPLACE` 会**静默覆盖**同名行。CLI 那条路径不覆盖、且有输出纪律保证不回显凭证。**本页全程不需要手工改库**——正常配渠道走 CLI（本步），故障注入也走 CLI（Step 7 另建一个「坏渠道」，不碰这个好的）。

---

## Step 5b — 配一个企业微信渠道（方言档，2026-10-15 补强③）

**这一步是 Step 5 的镜像，不是新流程**——同样是 `notify-channel add` 一条命令，只是 `--type` 换成 `wecom`、
地址换成企微的。之所以单列，是因为企微与飞书**报文形状只差字段名**，是最容易互相抄错的一处：

| | 企业微信 | 飞书 / Lark |
| --- | --- | --- |
| `type` 取值 | `wecom` | `feishu` / `lark` |
| 报文 | `{"msgtype":"text","text":{"content":"..."}}` | `{"msg_type":"text","content":{"text":"..."}}` |
| 类型字段 | `msgtype`（**无下划线**） | `msg_type`（**有下划线**） |
| 正文位置 | `text.content` | `content.text` |
| 业务码字段 | `errcode` / `errmsg` | `code` / `msg` |
| 签名 | **没有** | 可选 `secret`（HMAC-SHA256） |
| 域名 | `qyapi.weixin.qq.com` | `open.feishu.cn` / `open.larksuite.com` |

```bash
export WECOM_HOOK_URL='<你的企微群机器人 webhook 地址>'

java -jar "$ORYXOS_JAR" notify-channel add team-wecom --type wecom \
    --url-env WECOM_HOOK_URL --description '企微团队群（人工验收）'
```

> **没有 `--config secret=...`**：企微群机器人**没有签名机制**，加签是**钉钉**那一档的事（钉钉本次不做）。
> 企微可选的安全设置是 **IP 白名单**，那是**对端**的校验，客户端这边没有对应参数。

### 验一下库里存了什么（与 Step 5 同一套断言）

```bash
java -jar "$ORYXOS_JAR" notify-channel list
# 预期：url 列打印 ${WECOM_HOOK_URL}，config 列为空（企微档没有 config 键）
```

```bash
python3 - <<'PY'
import sqlite3, json
con = sqlite3.connect('.oryxos/oryxos.db')
url, typ, cfg = con.execute(
    "SELECT url, type, config_json FROM notify_channels WHERE name='team-wecom'").fetchone()
print('type:', typ)
print('url 是占位符字面量:', url == '${WECOM_HOOK_URL}')
print('config 键:', sorted(json.loads(cfg)) if cfg else [])
PY
```

- [ ] `type: wecom`（**不是** `wecomx` / `wechat` / `qiye` 之类的想当然拼法）
- [ ] `url 是占位符字面量: True`
- [ ] `config 键: []`
- [ ] 输出里**没有**那个 `key`

### 从这里往下，Step 6 / 7 / 8 一字不改照跑

只把命令里的**渠道名**从 `team-feishu` 换成 `team-wecom` 即可：

```bash
java -jar "$ORYXOS_JAR" chat --profile notifier \
  --message "请把『企微验收测试 001』这句话推到 team-wecom 渠道。"
```

- [ ] 消息**真的出现在企微群里**，且逐字一致
- [ ] 把 `--type` 故意写歪（例如 `wecomx`）后 `notify` **报错并列出已知类型**、一次请求都不发

> **为什么这条路径值得单跑**：企微和飞书**都**是「`HTTP 200` 承载业务错误」的对端（企微报文写错时回
> `HTTP 200` + `{"errcode":40008,"errmsg":"invalid message type"}`）。所以**把飞书的报文发给企微**、
> 或**把企微的报文发给飞书**，两边都会回 `HTTP 200`——**通用档在这里必然假报成功**，这正是 `wecom` 档存在的理由。
> 单测已把两个方向的方言都钉死（`WeComNotifyAdapterTest` / `FeishuNotifyAdapterTest`），人工这一步验的是**真对端确实如此**。

---

## Step 6 — ★ 正向验收：消息真的进群

```bash
java -jar "$ORYXOS_JAR" chat --profile notifier \
  --message "请把『飞书验收测试 001』这句话推到 team-feishu 渠道。"
```

`--message` 是**单条模式**（处理一条就退出），比交互循环适合验收。

- [ ] 消息**真的出现在飞书群里**
- [ ] 群里那条文字与所发内容**逐字一致**（含中文、换行、引号、反斜杠）

**若模型没调用 `notify`**（只回了一句「好的」）：这是模型行为问题，不是底座问题。改用更硬的措辞重发，例如「调用 notify 工具，参数 content='飞书验收测试 001'，channel='team-feishu'」。

---

## Step 7 — ★★ 反向验收：推不进去时必须报错

**这是整件事唯一真正的验收点。** 它要防的形态是：对端回了 `HTTP 200` + 业务错误码，工具却报「已推送」（[附录 A.2](#a2-测试-b真实-im-群-webhook-方言不匹配原-4) 的原始缺陷）。

### 故障怎么注入：另建一个「坏渠道」，不动好的那个

三种做法，**都通过 CLI 建一个独立渠道**——不需要手工改库，也不需要改 Step 5 那个正常渠道：

| 做法 | 怎么注入 | 测到的是哪条路径 |
| --- | --- | --- |
| **A**（推荐） | 飞书机器人安全设置里打开**「关键词」**，然后推一条**不含关键词**的消息 | ★ **业务码路径**（补强的本命） |
| **B** | 另建一个渠道，`--config 'secret=wrong-secret-for-acceptance'`（**字面量假值**，不是凭证） | ★ **业务码路径** + 顺带证明签名真的发出去了 |
| **C**（兜底） | 另建一个渠道，`--url` 给一个**格式合法但 token 是假的**地址 | 取决于飞书对错 token 的响应；无论落在哪一层，判据都是「报错、不假报」 |

**做法 B / C 的命令**：

```bash
# B：签名密钥故意写错（这是字面量假值，不是真凭证，所以可以出现在命令行里）
java -jar "$ORYXOS_JAR" notify-channel add team-badsecret --type feishu \
    --url-env FEISHU_HOOK_URL --config 'secret=wrong-secret-for-acceptance' \
    --description '故障注入：错 secret'

# C：token 是假的（全 0 的 uuid，语法合法、必然不存在）
java -jar "$ORYXOS_JAR" notify-channel add team-badurl --type feishu \
    --url 'https://open.feishu.cn/open-apis/bot/v2/hook/00000000-0000-0000-0000-000000000000' \
    --description '故障注入：错 token'
```

**做法 A** 不建新渠道，改的是飞书侧的安全设置——把机器人改成「关键词」模式，关键词设成一个**你这次不会发**的词（如 `ORYXOS-OK`），然后**照常推 `team-feishu`**。

### 然后推一次

```bash
java -jar "$ORYXOS_JAR" chat --profile notifier \
  --message "请把『飞书验收测试 002』这句话推到 team-badsecret 渠道。"
```

（做法 A 换成 `team-feishu`；做法 C 换成 `team-badurl`。）

**预期（三种做法都必须满足前三条）**：

- [ ] 模型**报错**，**MUST NOT** 说「已推送」
- [ ] 报错文本里能看出**对端的业务码或 HTTP 状态**。飞书对「签名错」与「关键词不匹配」各自回一个非 0 的 `code`，**具体码值以实际返回为准**——本页不预写具体数字（写了就是一条没实测过的断言），把实际看到的记下来即可
- [ ] 报错文本里**没有**完整 webhook 地址、**没有** token、**没有** secret
- [ ] 群里**没有**出现这条消息

**做法 A / B 若报「已推送」= 验收失败**——说明业务码判据没生效，回到了附录 A.2 记录的原始缺陷。做法 A 的对照：**同一个机器人、同一条消息，加上关键词后应立刻送达**（`code:0`）。

### 落审计的证据（权威判据）

终端上模型怎么说只是二手信息，**以 `tool_invocations` 为准**：

```bash
python3 - <<'PY'
import sqlite3
con = sqlite3.connect('/tmp/oryxos-feishu-check/.oryxos/oryxos.db')
for row in con.execute(
        "SELECT tool_name, success, duration_ms, substr(error_message,1,160) "
        "FROM tool_invocations ORDER BY rowid DESC LIMIT 5"):
    print(row)
PY
```

- [ ] 失败那次的 `success = 0`
- [ ] `error_message` 非空，且含业务码（或 HTTP 状态）
- [ ] Step 6 成功那次的 `success = 1`
- [ ] `error_message` 里**没有** token / 地址全文

> 顺带这也验证了一条跨节不变量：**`notify` 的失败真的落进了审计表**——第 19 节交付时这条还只是「构造上成立」，现在有了真渠道就有了真证据。

### 收尾这次注入

```bash
java -jar "$ORYXOS_JAR" notify-channel remove team-badsecret --force
java -jar "$ORYXOS_JAR" notify-channel remove team-badurl --force
```

（做法 A 的话，把飞书机器人的安全设置改回原样。）

---

## Step 8 — 拼错 `type` 必须在路由处就拦下

```bash
java -jar "$ORYXOS_JAR" notify-channel add team-typo --type feshu \
    --url-env FEISHU_HOOK_URL --description '故障注入：type 拼错'
```

（`--type feshu` 是**故意拼错的**。CLI 不做 `type` 取值校验——合法取值由装配处的路由表决定，在 CLI 里再写一份清单会造出第二个真相源。**写歪的 `type` 在路由处被拦下**，见下。）

```bash
java -jar "$ORYXOS_JAR" chat --profile notifier \
  --message "请把『飞书验收测试 003』推到 team-typo 渠道。"
```

- [ ] **在发出任何 HTTP 请求之前**就报错（路由处拦截）
- [ ] 报错文本里列出已知类型 `feishu, lark, webhook, wecom`（四个，按字典序）
- [ ] 群里没有消息，对端**没收到任何请求**（可用飞书机器人后台的调用记录确认）
- [ ] `tool_invocations` 里留下 `success = 0` 的新行

```bash
java -jar "$ORYXOS_JAR" notify-channel remove team-typo --force
```

这条是**口径更新**：附录 A.2 那张决策表里写「`type` 写歪了会在对端以非 2xx 暴露」——**自 2026-10-15 起不再成立**。写歪的 `type` 现在**到不了对端**，在本地就被拦下。

---

## Step 9 — 收尾

```bash
java -jar "$ORYXOS_JAR" notify-channel list   # 最后看一眼，确认没有残留
cd /tmp && rm -rf oryxos-feishu-check
```

演示工作区**用完即删**，别留在磁盘上——里面既有库文件也有改过的渠道行。**临时测试一律放 `/tmp`，不放 home。**

```bash
unset FEISHU_HOOK_URL FEISHU_BOT_SECRET
```

---

## 最容易踩的六个坑

1. **`tools: [notify]` 忘了加** —— 模型看不见工具，于是它「礼貌地答应」然后什么都没发生。这是**假失败**，会让人误判成底座有问题。先跑 Step 4 确认。
2. **用了 `dist/oryxos.jar`** —— 那是打包脚本的产物，可能是几天前的旧构建。用 `"$ORYXOS_JAR"`，且 `echo` 一次确认。
3. **在错误的目录跑** —— 库文件是相对的 `.oryxos/oryxos.db`。在仓库根跑会用到仓库自己的库（而且会污染仓库）。
4. **把模型的措辞当证据** —— 模型可能把错误转述得不准确。**以 `tool_invocations` 为准。**
5. **把重命令的启动日志当成报错** —— `tool list` / `chat` 是重命令（起 Spring 上下文），终端上会先刷一段 Spring / Hibernate 的 INFO，**它们在 stderr 上**（2026-10-15 起与 stdout 分流）。其中 `PermissiveSandbox` 那条 **WARN 是故意**打的。
   > **2026-10-17 修订注**：本条第 2 句（`PermissiveSandbox` 那条 WARN）**已失效**——该类自第 24 节起已整类删除，启动时不会再打它。**第 1 句仍完全成立**（重命令启动日志走 stderr，别当报错），本条其余部分不受影响。**原文保留以留痕**。
6. **凭证泄进输出** —— 任何一步的输出里都不该出现 token / secret。Step 5 的核对脚本**故意只打布尔值与键名**；如果你自己写核对命令，也照这个来。

---

## 附录 A — 历史实测留痕（2026-10-14 首版 §3 ~ §5 收编）

> **这一节是留痕，不是操作步骤。** 它记录的是**当时的**原始输出与**当时的**裁决——其中一部分已被后续补强推翻。正文（上面各 Step）才是现状。
>
> 章节编号从 `§N` 改为 `A.N`。外部文档里引用「`manual-test-guide.md` §4」的地方，指的是 **A.2**。

### A.1 测试 A：真实外网出口（原 §3）

**验什么**：这台机器上，适配器用的那套传输（JDK `HttpClient`）**能不能真的把 POST 发出去**。

**为什么它不冗余**：`WebhookNotifyAdapter` 自建 `HttpClient`（[WebhookNotifyAdapter.java:51-58](oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/notify/WebhookNotifyAdapter.java#L51-L58)），不注入 `RestClient`。而 JDK `HttpClient` 的代理走 `ProxySelector.getDefault()`，默认读的是 **JVM 系统属性**（`-Dhttps.proxyHost`），**不是** `https_proxy` 环境变量。所以「curl 能通」不等于「这个适配器能通」——尤其在一个挂着代理的 WSL 里，或企业内网出口有策略的机器上。

**做法**：用 `jshell` 打一个 <https://webhook.site> 的地址（**⚠️ jshell 从文件 / stdin 读时每行是一段独立代码、不续行**，所以链式调用必须压在一行里）：

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

**实测输出**（2026-10-14，本机 WSL）：

```text
HTTP 200 | This URL has no default content configured. <a href="https://webhook.site/#!/edit/2942ff82-...">Change response in Webhook.site</a>.
```

✅ **JVM 出口通** —— JDK `HttpClient` 直连外网成功，**没有加任何 `-Dhttps.proxyHost` 系统属性**。出口不是障碍。

**而同一轮里 curl 却先失败了**：`curl → HTTP 000`（无任何响应）。根因不是网络，是 shell 里设了 `https_proxy` 之类的代理变量，把本该直连的域名劫持到了一个走不通的节点。`unset http_proxy https_proxy all_proxy HTTP_PROXY HTTPS_PROXY ALL_PROXY` 之后立刻 `HTTP 200`。

**这一对结果恰好把测试 A 的核心命题演了一遍**：同一个地址、同一台机器、同一时刻，**curl 不通而 JVM 通**——两者的代理来源不同（curl 读环境变量，JDK `HttpClient` 读 JVM 系统属性）。所以「curl 通/不通」**推不出**「适配器通/不通」，两者必须各测各的。

（另外 `-s` 会把 curl 自己的错误一起吞掉，失败时你只看得到 `HTTP 000`——排查时用 `-sS`。）

**判读表**：

| 看到 | 含义 |
| --- | --- |
| `HTTP 200` 且 webhook.site 页面上出现那条 POST | ✅ 出口通，JVM 不需要额外代理参数 |
| `ConnectException` / `HttpConnectTimeoutException`，但 curl 通 | ⚠️ 这台机器上 JVM 走代理需要显式 `-Dhttps.proxyHost=`。部署期问题，不是代码问题 |
| `HTTP 3xx` | 符合 `Redirect.NEVER` 的设计（3xx 按失败处理） |
| curl 报 `HTTP 000` 但 JVM 通 | ⚠️ 反向的那一类：curl 读 `https_proxy`，JVM `HttpClient` **不读** |

### A.2 测试 B：真实 IM 群 webhook 方言不匹配（原 §4）

**验什么**：课件用户场景 1 承诺的「消息真的出现在群里」。**结论：当时不成立，且是静默失败。**

**做法**：把**适配器实际送出的报文**原样打过去（报文形状不用猜——`WebhookNotifyAdapterTest#postBodyCarriesContent` 已经断言了它**只有 `content` 一个字段**）：

```bash
curl -sS -o /tmp/wx_resp.txt -w 'HTTP %{http_code}\n' \
  -H 'Content-Type: application/json' \
  -d '{"content":"OryxOS 第19节人工测试"}' \
  'https://open.feishu.cn/open-apis/bot/v2/hook/<你的 key>'
cat /tmp/wx_resp.txt
```

**实测输出**（2026-10-14）：

```text
HTTP 200
{"code":19002,"data":{},"msg":"params error, msg_type need"}
```

**群里什么都不会出现。问题成立。**

#### 三件事叠加

1. **我们的报文是通用的**：body 是 `{"content":"..."}`，只有一个字段。代码注释写得很直白——「加上 `msgtype` 之类就成了某一家 webhook 的方言」（FR-003 的刻意边界）。
2. **IM 群机器人不认这个形状**：飞书要 `{"msg_type":"text","content":{"text":"..."}}`，企微要 `{"msgtype":"text","text":{"content":"..."}}`。收到 `{"content":...}` 时，飞书回 `code:19002 "msg_type need"`，企微回 `errcode:40008 "invalid message type"`。
3. **致命的是这一条**：IM 群机器人的**业务错误走 HTTP 200**——HTTP 层成功、`code`/`errcode` 非 0 才表示业务失败。而 `WebhookNotifyAdapter.send()` 用的是 `.retrieve().toBodilessEntity()`（[WebhookNotifyAdapter.java:69-75](oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/notify/WebhookNotifyAdapter.java#L69-L75)），**只看 HTTP 状态码，不解析响应体**。于是：

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

#### 对照组（把结论钉死）—— ✅ 已跑

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

| | 报文 | 飞书响应 |
| --- | --- | --- |
| B2 | `{"content":"..."}`（适配器实际送的） | `HTTP 200` + `code:19002` "msg_type need" |
| B-对照 | `{"msg_type":"text","content":{"text":"..."}}` | `HTTP 200` + `code:0` "success" |

**地址、网络、key、机器人、群，全部相同，只有报文形状不同——一个失败一个成功。** 所以失败原因**就是报文形状**，不再是推断。

而且这一对结果本身就是那个致命点的完整演示：**两条都是 `HTTP 200`**。只看状态码的 `WebhookNotifyAdapter` 对这两次投递会给出**完全相同**的判断——「成功」。

#### 当时的裁决（**已被推翻，保留以留痕**）

| 选项 | 做法 | 代价 | 当时的裁决 |
| --- | --- | --- | --- |
| **A. 记为已知边界** | 在验收报告与 `CLAUDE.md` 里写明「通用 webhook ≠ IM 群机器人」 | 零成本，但课件场景 1 在核心阶段无法演示 | ✅ 当时采纳（2026-10-14 软门禁 ③ 报批后定） |
| B. 收紧送达判定 | `send()` 解析响应体，`code`/`errcode` 非 0 即抛 | 需要引入「哪些键算错误」的判据——那本身就是某家方言，撞 FR-003 | ❌ 当时不采纳 |
| C. 报文加方言适配 | 按 `NotifyTarget.channelType` 选报文形状 | 把「某一家方言」写进核心阶段，撞 FR-001 + FR-003 | ❌ 当时不采纳 |

> **2026-10-15 推翻记录**：B 与 C 的**否决理由本身是对的**——把方言知识塞进**通用档**确实撞 FR-003。但当时把「加方言」与「往通用档里加方言」当成了同一件事。正确的做法是**新增一个兄弟实现类**（`FeishuNotifyAdapter`），方言知识与业务码判据全收在它自己类内——那正是「接口先行」这条升级路径本身（**只新增实现类，不改接口与调用方**）。
>
> 于是：新增方言档 `FeishuNotifyAdapter`（覆盖飞书 + Lark，读响应体按业务码 fail-closed 判成败，可选 `secret` 签名）+ 路由接缝 `RoutingNotifyChannelAdapter`（按 `type` 查装配处显式给出的映射表分派）。**`NotifyChannelAdapter` / `NotifyTarget` 两个接口文件 `git diff` 为空，`NotifyTools` 与通用档代码零改动。** 被修订的只有 FR-003 里的「唯一」二字，经用户裁决并挂带日期修订注。
>
> **fail-closed 判据的两处硬约束**（写错就等于把缺陷重新引入）：MUST 用 `has("code")` + `isIntegralNumber()`，**MUST NOT 写 `path("code").asInt() != 0`**——Jackson 在字段**缺失**时也返回 0，那个写法把「缺失」判成成功。

#### 适配器视角的探针 —— 当时判定跳过

原计划用 `/tmp/feishu_probe.jsh`（JDK `HttpClient` 打飞书）拿「适配器视角」的同一份证据。A.1 与 A.2 已分别覆盖它的两个变量（JVM 能否出网、飞书对这个报文的响应），边际价值低，**已跳过**。

### A.3 测试 C：真实 SQLite 表确实建出来了（原 §5）

**验什么**：`oryxos init` 产出的真库里，`notify_channels` 表真的存在（单测用的是临时库 + 自己跑脚本）。

```bash
cd /tmp && rm -rf oryxos-l19-check && mkdir oryxos-l19-check && cd oryxos-l19-check
java -jar "$ORYXOS_JAR" init
python3 - <<'PY'
import sqlite3
con = sqlite3.connect('.oryxos/oryxos.db')
print(con.execute(
    "SELECT sql FROM sqlite_master WHERE name='notify_channels'").fetchone()[0])
PY
```

（**用 `python3` 而不是 `sqlite3`**：本机没装 sqlite3 CLI，而 Python 标准库自带 `sqlite3` 模块，读同一个库文件，零安装。）

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

> **别被 `CREATE TABLE`（没有 `IF NOT EXISTS`）吓到**：`schema.sql` 里写的是 `CREATE TABLE IF NOT EXISTS`，而 **SQLite 存进 `sqlite_master` 时会把这个子句剥掉**。这是正常的，不是脚本写错了。
>
> `sqlite_sequence` 是 `sessions` 等表用了 `AUTOINCREMENT` 自动产生的，与 `notify_channels` 无关——**`notify_channels` 的 DDL 里没有 `AUTOINCREMENT`，符合预期**（`name` 是业务主键）。
>
> `scheduled_tasks` / `task_executions` 此刻**还不存在**，是正常的：那两张表归第 25 节（定时任务）建。

- [ ] 五列齐备，与 `specs/000-runtime-kernel/data-model.md` A6 逐字一致
- [ ] `name` 是 PRIMARY KEY，且**没有** `AUTOINCREMENT`

### A.4 实测记录表（2026-10-14）

| # | 测试 | 手段 | 实际结果 | 状态 |
| --- | --- | --- | --- | --- |
| 基线 | 当时已有单测 + 全量门禁 | `./mvnw -o clean verify` | **BUILD SUCCESS**（57s），87 条测试全绿（storage 9 / core 45 / provider 7 / tool 26） | ✅ 已跑 |
| A1 | JVM 外网出口 | `jshell` | **`HTTP 200`** —— JVM 直连外网成功，无需任何代理系统属性 | ✅ 已跑，**出口通** |
| A2 | curl 对照 | `curl` | **`HTTP 000`** → `unset` 代理变量后 **`HTTP 200`** | ✅ 已跑，结论：代理变量劫持 |
| B2 | 通用报文打 IM 群（飞书） | `curl` | **`HTTP 200` + `{"code":19002,"msg":"params error, msg_type need"}`**，群里无消息 | ✅ 已跑，**问题成立** |
| B-对照 | 正确方言打 IM 群 | `curl` | **`HTTP 200` + `{"code":0,"msg":"success"}`** | ✅ 已跑，**结论证死** |
| B-探针 | 适配器视角同款 HttpClient | `jshell` | 跳过（A1 + B2 已覆盖其两个变量） | ⏭️ 跳过 |
| C | 真实 SQLite 建表 | `python3` | **`notify_channels` 建出，五列齐备，`name` 为 PK 且无 `AUTOINCREMENT`** | ✅ 已跑 |
| D | CLI 全链路 | — | **当时跑不了**（工具不在容器 + 渠道写不进去） | ⬜ 当时推迟 |

> **D 的两个堵点后来都解开了**，且解开它们的节号与当时预计的**不同**：工具进容器由**第 20 节**完成（临时沙箱 `PermissiveSandbox` 顶上了 `Sandbox` 这个缺口），**不是**第 24 节；渠道写入面由 **2026-10-15 补强②** 的 CLI 三条轻命令完成，**不是**第 26 节——第 26 节的管理台明确「这一版只读」，10 个端点里没有一个与渠道写入相关，指过去等于指到死胡同。
>
> **D 现在的做法就是本页正文 Step 5~8。**

### A.5 接口中立性检查（原 §7，不用运行）

课件的人工项之一，没有可执行形态，但有可判定的部分：

> 如果要把 `WebhookNotifyAdapter` 换成「企业微信官方 SDK 实现」，`NotifyChannelAdapter.send(NotifyTarget, String)` 这个签名需要改吗？

**答案应是不需要。** 验证方法（注意先排除注释——两份契约的 javadoc 里合法出现取值示例与实现名，它们正是在解释「为什么这样设计」）：

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

## 附录 B — 本次仍未做的（如实记）

- **~~企业微信~~**：**2026-10-15 补强③ 已做**（`WeComNotifyAdapter`，`type=wecom`，见 Step 5b）。走的正是原先记下的那条路——「往 `CliAssemblyConfiguration` 的映射表加一行 + 加一个实现类」，**接口与调用方零改动**。这是「接口先行」那条升级路径的**第二次**实际兑现（第一次是飞书档）。
- **钉钉**：协议已知，且**报文与企微完全相同**（`{"msgtype":"text","text":{"content":"..."}}`，课件 §6.2），差异只在**业务码语义**与**加签**——钉钉的加签是把 `timestamp` + HMAC-SHA256 拼进 **URL 查询串**（飞书是放**请求体**）。**本次仍未做**（用户 2026-10-15 裁决收窄到只做企微）。路由接缝已就位，加它仍是「映射表加一行 + 加一个实现类」，**接口与调用方零改动**。届时**也是抽公共基类的时机**——企微与飞书两档的 `send()` 骨架已刻意重复了一次，第三次出现按规则三该抽了。
- **~~`open.feishu.cn` / `open.larksuite.com` / `qyapi.weixin.qq.com` 的沙箱域名白名单~~**：~~归第 24 节。当前挂的是临时实现 `PermissiveSandbox`（**不拦任何东西**）——所以本页跑通**不能**当作「沙箱已就位」的证据。~~
  > **2026-10-17 修订注**：**本条已结项**。第 24 节已交付 `WhitelistSandbox`，三个域名**已进随包默认** `http.allowed_domains`（`oryxos-boot/src/main/resources/application.yaml`）；`PermissiveSandbox` 已按裁决 3 整类删除。故**本页跑通现在可以**当作「沙箱已就位」的证据。**原文保留以留痕**。
- **REST CRUD 与管理台**：`/api/v1/notify-channels` 的端点与 `NotifyChannelRegistry` 的写方法仍归第 26 节。写入面走 CLI 原生 JDBC，**没有动那个接缝**。
- **写入时的 `type` 取值校验**：CLI 不做（合法取值由装配处路由表决定，见 Step 8）。
- **`ConfigLoader` 收口**：`${ENV_VAR}` 解析目前有三处各持一份（`ProfileLoader` / `McpServerConfigLoader` / `JpaNotifyChannelRegistry`），**失败策略三处刻意不同**（抛 / 跳过整个 server / 抛）。合并是一次独立的收口，不该折进这一节。
- **重试 / 退避 / 投递回执**：不做。
- **富文本卡片等结构化消息**：走 Plugin Tool 方式二（MCP），不在范围内。

---

## 参考来源

- [飞书开放平台 — 自定义机器人使用指南（`msg_type` / `content.text`）](https://open.feishu.cn/document/client-docs/bot-v3/add-custom-bot)
- [飞书开放平台 — 自定义机器人错误码](https://open.feishu.cn/document/client-docs/bot-v3/add-custom-bot#%E9%94%99%E8%AF%AF%E7%A0%81)
- [企业微信 API — 消息格式](https://qiyeweixin.apifox.cn/doc-417805)
- [企业微信 API — 消息类型](https://qiyeweixin.apifox.cn/doc-1803587)
- [微信开放社区 — `errcode:40008`（invalid message type）案例](https://developers.weixin.qq.com/community/develop/doc/0002ae37beca30fab9ba807d951000)
