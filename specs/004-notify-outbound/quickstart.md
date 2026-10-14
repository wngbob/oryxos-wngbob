# Quickstart：Notify 出站推送的验证指引

**Feature**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md) | **Created**: 2026-10-14

本节交付的是**库代码 + 单测**，不是可运行的功能（工具进不了容器、注册表运行期恒为空——见 [plan.md](plan.md) 的「三处有界的新增」）。所以本文件的验证路径与前面几节不同：**主路径是「跑测试 + 核对契约存在性」**，端到端的手工验收要等第 24 节接线（§5 给出那一项）。

---

## 0. 环境准备

```bash
cd /home/wngbob/oryxos-wngbob
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
```

本节所有命令在 WSL2 + JDK 21 + 仓库自带 `./mvnw` 下可直接执行；离线构建可加 `-o`。

---

## 1. 全量门禁（本节完成判据）

```bash
./mvnw clean verify
```

**预期**：`BUILD SUCCESS`。这是**唯一**的「实现完成」定义——包含 Spotless / P3C（PMD 6.55）/ Checkstyle / SpotBugs+FindSecBugs / OWASP，以及全部单测。

**跑得慢**时本地可跳 OWASP（不改变其余门禁）：

```bash
./mvnw clean verify -Ddependency-check.skip=true
```

> ⚠️ 跳 OWASP 只用于本地迭代；**宣布本节完成时的证据必须是不跳的全量 `mvn clean verify`**。

---

## 2. 只跑本节新增的测试

**单模块命令一律带 `-am`**（否则会静默取 `~/.m2` 里的旧字节码）：

```bash
# 课件 harness 第一批：假 webhook 收 POST（MockWebServer）
./mvnw -o -pl oryxos-tool -am test -Dtest=WebhookNotifyAdapterTest

# 课件 harness 第二批：工具的三条守点，含 enforce 先于 send 的 InOrder 断言
./mvnw -o -pl oryxos-tool -am test -Dtest=NotifyToolsTest

# 本节追加：行→目标映射的确定性口径
./mvnw -o -pl oryxos-tool -am test -Dtest=JpaNotifyChannelRegistryTest

# 本节追加：建表列齐备 / 存取往返 / 默认渠道不依赖插入顺序
./mvnw -o -pl oryxos-storage -am test -Dtest=NotifyChannelRepositoryTest
```

**预期**：四个类全绿，且**没有任何测试访问外网**（MockWebServer 起在 `localhost`）。

### 2.1 关键回归测试逐个对号（课件 harness 写出代码的那几条）

| 守点（课件原文） | 落在哪个类 | 断言本质 |
| --- | --- | --- |
| 发送后收到的 POST 请求 body 里带 `content` | `WebhookNotifyAdapterTest` | 解析 `RecordedRequest.getBody()`，断言 `content` 字段值相等 |
| URL 来自通知目标配置、**不是硬编码** | `WebhookNotifyAdapterTest` | 两个不同端口的 MockWebServer，请求分别落在各自服务上 |
| webhook 返回 5xx 时异常向上抛、**不静默吞** | `WebhookNotifyAdapterTest` | `assertThatThrownBy` + `getRequestCount() == 1`（不重试） |
| 渠道未配置时**明确报错** | `NotifyToolsTest` | `assertThatThrownBy(...).isInstanceOf(IllegalStateException.class)` |
| 渠道名缺省时取**第一个**渠道 | `NotifyToolsTest` | 断言 `adapter.send` 收到的是默认渠道解析出的 target |
| **白名单校验先于发送被调用**（顺序反了就是漏洞） | `NotifyToolsTest` | **`InOrder` 顺序断言**（见 [contracts/notify-outbound.md](contracts/notify-outbound.md) §3.3） |

---

## 3. 契约存在性核对（机器可判，别只看测试绿）

### 3.1 依赖方向：`oryxos-core` 必须零改动、零反向依赖

```bash
# 本节对 core 的改动必须为空
git diff --stat -- oryxos-core
# 预期：无输出（本节不碰 core 一行）

# core 里不得出现 tool 的 notify 类型
grep -rn "NotifyTarget\|NotifyChannelAdapter\|NotifyChannelRegistry" oryxos-core/src/ || echo "OK: core 不认识 notify 类型"
# 预期：OK

# storage 里不得出现 core / tool 的 notify 类型（实体字段全 String）
grep -rn "NotifyTarget\|NotifyChannelAdapter\|NotifyChannelRegistry" oryxos-storage/src/ || echo "OK: storage 不认识 notify 类型"
# 预期：OK
```

### 3.2 零装配：装配点一行不改、新类无 Spring 注解

```bash
# 装配配置必须无改动
git diff --stat -- oryxos-cli/src/main/java/io/oryxlabs/oryxos/cli/CliAssemblyConfiguration.java
# 预期：无输出

# 三个新类不得带 Spring 装配注解
grep -n "@Component\|@Service\|@Configuration\|@Bean" \
  oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/notify/WebhookNotifyAdapter.java \
  oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/notify/NotifyTools.java \
  oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/notify/JpaNotifyChannelRegistry.java \
  || echo "OK: 零 Spring 注解"
# 预期：OK

# 不得存在任何"全放行"的占位沙箱
grep -rn "Permissive\|AllowAll\|NoOpSandbox" oryxos-tool/src/main/ || echo "OK: 无占位沙箱"
# 预期：OK
```

### 3.3 契约未被改动（骨架期两份 + 前序节的公共接口）

```bash
# 两份 notify 契约与 ProfileContext / Profile / ProfileLoader 必须无改动
git diff --stat -- \
  oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/notify/NotifyChannelAdapter.java \
  oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/notify/NotifyTarget.java \
  oryxos-core/src/main/java/io/oryxlabs/oryxos/core/engine/ProfileContext.java \
  oryxos-core/src/main/java/io/oryxlabs/oryxos/core/profile/Profile.java \
  oryxos-core/src/main/java/io/oryxlabs/oryxos/core/profile/ProfileLoader.java
# 预期：无输出
```

### 3.4 接口中立性（FR-001 / SC-007）

```bash
# 中立接口的【签名】里不得出现某一档实现特有的词
# （只查代码行、排除注释：两份契约的 javadoc 里合法出现了取值示例「webhook / email」
#  与实现名「WebhookNotifyAdapter」，FR-001 约束的是签名，不是 javadoc 散文；
#  且这两个文件属骨架期契约、MUST NOT 修改，所以判据必须豁免注释）
grep -vE '^[[:space:]]*(\*|/\*|//)' \
  oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/notify/NotifyChannelAdapter.java \
  oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/notify/NotifyTarget.java \
  | grep -in "wecom\|feishu\|dingtalk\|企业微信\|飞书\|钉钉\|webhook" \
  || echo "OK: 签名中立"
# 预期：OK（若去掉注释过滤直接 grep 全文会命中 javadoc，属误报，不代表违规）

# 通知工具只依赖接口，不依赖具体实现
# （同样只查代码行、排除注释：NotifyTools 的 javadoc 里合法提到 {@code ProfileContext}，
#  那句正是在解释【为什么不用它】——给 core 加 resolveNotifyChannel 就是 core 反向依赖能力模块。
#  判据约束的是代码依赖，不是散文；删掉这句解释反而是损失。实测全文 grep 只命中这一行 javadoc。）
grep -vE '^[[:space:]]*(\*|/\*|//)' \
  oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/notify/NotifyTools.java \
  | grep -n "WebhookNotifyAdapter\|WhitelistSandbox\|JpaNotifyChannelRegistry\|NotifyChannelRepository\|ProfileContext" \
  || echo "OK: 只依赖接口"
# 预期：OK（构造参数是 Sandbox / NotifyChannelAdapter / NotifyChannelRegistry；
#  若去掉注释过滤会命中 javadoc 第 17 行，属误报）
```

### 3.5 注册表接缝是只读的（FR-014）

```bash
# 接缝里不得出现写入方法
grep -n "save\|delete\|add\|remove\|import" \
  oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/notify/NotifyChannelRegistry.java \
  || echo "OK: 只读接缝"
# 预期：OK
```

### 3.6 建表脚本

```bash
# 表已追加，且注释里没有分号（脚本按分号切分执行）
grep -n "notify_channels" oryxos-storage/src/main/resources/db/schema.sql
# 预期：命中 CREATE TABLE IF NOT EXISTS notify_channels (...)

awk '/notify_channels/,/^\);/' oryxos-storage/src/main/resources/db/schema.sql | grep -c ";"
# 预期：1（只有语句末尾那个分号，注释里没有）
```

### 3.7 H4 六条全局不变量

```bash
# ⑤ 无响应式 / 无自建线程池 / 无 CompletableFuture
grep -rn "reactor\|Flux\|Mono\|CompletableFuture\|Executors\.\|new Thread" \
  oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/notify/ \
  oryxos-storage/src/main/java/io/oryxlabs/oryxos/storage/entity/NotifyChannelEntity.java \
  || echo "OK: 同步阻塞"
# 预期：OK

# ⑥ 无 Spring AI 自动工具执行路径（本节 @Tool 是惰性的）
grep -rn "ToolCallingManager\|ToolCallbackProvider\|ChatClient.*tools\|@EnableTool" \
  oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/notify/ || echo "OK: 无自动执行路径"
# 预期：OK

# ③ 无明文凭证（渠道地址含 token，不得写死在代码里）
grep -rniE "https?://[a-z0-9.-]+/(cgi-bin|open-apis|robot)" oryxos-tool/src/ oryxos-storage/src/ || echo "OK: 无硬编码地址"
# 预期：OK

# ① 涉外 IO 的 enforce 调用位（本节由工具边界调用，顺序由 InOrder 钉死）
grep -n "enforce" oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/notify/NotifyTools.java
# 预期：命中 sandbox.enforce(new SandboxAction(ActionType.HTTP_REQUEST, ...))
```

**② 审计落库**：本节不新增审计逻辑；通知工具与其他工具同处第 17 节 `ToolExecutor` 的执行入口之下，成功失败都由**那一处**落 `tool_invocations`（第 24 节接线后可端到端观测）。

**④ `session_id` 只在 `SessionManager` 内拼接**：本节不接触 session 身份，`grep` 判定同第 18 节。

---

## 4. 前序节回归（跨节契约证据）

```bash
./mvnw -o clean verify
```

**预期**：全绿。本节的改动（`oryxos-tool` 新增 7 个源文件——4 个主代码 + 3 个测试，外加 pom 依赖；`oryxos-storage` 新增 3 个文件 + DDL 追加）不影响任何既有测试——特别是：

- `SessionRepositoryTest`：`schema.sql` 追加了一张表，但它只断言既有三表的列，且脚本执行是逐条 `CREATE TABLE IF NOT EXISTS`，追加不改变既有行为。
- 第 18 节的 CLI 测试：本节**零装配**，`CliAssemblyConfiguration` 一行不改，重命令启动行为不变。

**顺带验证「本节没有把功能做假」**：跑一次重命令，确认渠道未配置时行为符合预期（本节工具还没进容器，故这一步是**确认它确实进不去**，不是确认它能用）：

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
java -jar oryxos-boot/target/oryxos.jar --version   # 预期：正常打印版本
```

---

## 5. 人工验收项（harness 判不了卷的，等你过）

本节 harness 已判卷的：全部 4 个测试类 + 静态门禁。**剩下的四项人工**：

| # | 人工项 | 为什么机器判不了 | 怎么做 |
| --- | --- | --- | --- |
| 5.1 | **真实 webhook 送达** | 需要企业真实的群机器人地址，且是**外呼**——单测纪律禁止访问外网 | 第 24 节接线后：注册表插一行（`name` / `type=webhook` / `url=<真地址>`），跑一次 `notify`。**⚠️ 已人工实测（2026-10-14）**：对**通用 webhook 接收端**（webhook.site / 自建端点）送达成立；对 **IM 群机器人不成立**——它们要 `msg_type`/`msgtype` 方言，收到通用报文回 `HTTP 200` + 业务错误码，而适配器按状态码判成功 → 假报「已推送」。已按 FR-003 边界记为已知边界，详见 [manual-test-guide.md](manual-test-guide.md) §4 与 [acceptance-report.md](acceptance-report.md) §7。**本节的替代证据**：MockWebServer 已证明「POST 发出去了、body 里有 content、地址来自配置」 |
| 5.2 | **接口中立性思想实验** | 属设计判断，非可断言事实 | 读 [contracts/notify-outbound.md](contracts/notify-outbound.md) §1，自问：**若现在要加一个飞书专用渠道，要不要改 `NotifyChannelAdapter` 或 `NotifyTools`？** 正确答案是「都不用，只新增一个 `implements NotifyChannelAdapter` 的类 + 在注册表里配 `type`」。若你的答案是需要改接口，说明接口漏了某一档实现的细节 |
| 5.3 | **前序节回归绿** | 机器可判（§4 的命令），但**要你确认这是全量而非抽样** | 跑 §4 的 `./mvnw -o clean verify`，确认是全量 |
| 5.4 | **顺序不变量的可信度** | `InOrder` 断言已机器化，但「这条断言本身写对了没」要人看一眼 | 读 `NotifyToolsTest` 里那条 `@DisplayName("发送前必须先过白名单校验")`，确认用的是 `InOrder` 而非两条独立的 `verify`——**两条独立 `verify` 在顺序写反时也会通过**（[contracts/notify-outbound.md](contracts/notify-outbound.md) §3.3） |

---

## 6. 常见问题

**Q：为什么跑完本节，`notify` 工具在 `oryxos chat` 里还是用不了？**
A：本节**零装配**——工具没进 Spring 容器，也没有 `ToolRegistry` 消费它的 `@Tool` 注解（注册机制归第 20 节、`Sandbox` 实现归第 23/24 节）。这是 [plan.md](plan.md) 仲裁 4 的直接结果，不是缺陷。可演示的那一天是第 24 节接线之后。

**Q：为什么注册表运行期恒为空？**
A：本节不引入写入路径（FR-014 / 仲裁 2）——A6 的 `notify_channels.yaml` 启动导入判为旧口径，写入路径归第 26 节。所以「渠道未配置 → 明确报错」在任何真实运行里都会走到，这是**预期行为**，也让 FR-006 在本节就有了真实的可观测证据。

**Q：`WebhookNotifyAdapter` 为什么不是 `WebhookNotifyAdapter(RestClient)`？**
A：FR-021 要求推送**本身**有界，把超时责任推给注入方会让它退化成「谁装配谁负责」。详见 [plan.md](plan.md) 偏离 ③ 与 [research.md](research.md) D2。

**Q：为什么 `NotifyTools` 在 `notify` 包而不是课件的 `tool.builtin`？**
A：模块落位表把本节这一刀切片整体归 `notify` 包，让接口 / 实现 / 工具 / 注册表接缝在同一包内闭合。见 [plan.md](plan.md) 偏离 ⑥。
