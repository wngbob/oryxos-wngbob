# Quickstart: 验证 CLI 与会话持久化

本节交付物的**可执行验证指引**。契约细节不在此重复：[cli-surface.md](contracts/cli-surface.md)（12 条命令面）、[session-persistence.md](contracts/session-persistence.md)（会话持久化）、[cli-assembly.md](contracts/cli-assembly.md)（发行入口与装配）、[data-model.md](data-model.md)（字段与表结构）。

---

## 1. 前置

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64   # JDK 21
cd /home/wngbob/oryxos-wngbob
```

本节**不新增任何第三方依赖**（[research.md](research.md) D19 已核实 Jackson 经 `spring-ai-model` 传递到 core）。若本机首次构建，先跑一次 `./mvnw -q dependency:resolve` 拉依赖。

---

## 2. 自动化验证（本节 harness）

课件「验收 harness」的测试套件全部落在两个类里（落位理由见 [research.md](research.md) D6 / D7）：

| 测试类 | 模块 | 守什么 |
| --- | --- | --- |
| `SessionServiceTest` | `oryxos-core` | 同一三元组幂等 / 三元组任一不同即不同会话 / 标识生成只此一处 / `messages_json` 序列化往返（含工具调用与工具结果的消息） |
| `SessionRepositoryTest` | `oryxos-storage` | 手工脚本建得出 `sessions` 表且列齐备 / 经 Repository 存取往返保真 / **模拟「重启」**（新建独立 JDBC 连接）重查历史还在 |

```bash
# 只跑本节两个测试类
./mvnw -pl oryxos-core    -am test -Dtest=SessionServiceTest    -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -pl oryxos-storage -am test -Dtest=SessionRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false

# 全量门禁（Spotless / P3C / Checkstyle / SpotBugs+FindSecBugs / OWASP）
./mvnw clean verify -Ddependency-check.skip=true     # 本地跳过 NVD 下载；CI 不跳
```

**预期**：全绿。`mvn clean package` **不会**跑这些门禁——它们绑在 `verify` 上。

**关键回归测试对号**（课件 harness 里写出代码的那条，方法名按纪律译成英文、原文进 `@DisplayName`）：

```text
SessionServiceTest#sameTriple_getOrCreateAlwaysReturnsSameSession
  @DisplayName("同一三元组_历次getOrCreate都是同一个Session")
  三条断言逐条保真:同一三元组两次 getOrCreate 的 id 相等、
                    channel 不同则 id 不等（见 contracts/session-persistence.md §6）
```

---

## 3. 依赖方向核查（可 grep，机器判）

章程附加约束：`oryxos-core` **不反向依赖**任何能力模块；`oryxos-storage` **不依赖** `oryxos-core`。

```bash
# 期望:全部 0 行(本节不得新增任何反向边)
grep -rn "oryxos-provider\|oryxos-tool\|oryxos-memory\|oryxos-web" \
  oryxos-core/src/main/java oryxos-core/pom.xml
grep -rn "oryxos-core" oryxos-storage/pom.xml oryxos-storage/src/main/java

# 期望:本节新增的边都在这里
grep -n "artifactId" oryxos-cli/pom.xml
grep -n "oryxos-core" oryxos-channel-cli/pom.xml
```

**会话标识拼接只此一处**（FR-006 / SC-003 / H4 不变量 ④）：

```bash
# 期望:只有 core 的 session 包内一处,且 CLI 侧不出现手拼
grep -rn "channel + \":\"\|:\" + userId\|+ \":\" + profile" --include=*.java . | grep -v "/target/"
```

---

## 4. 发行入口核查（本节特有）

```bash
grep -c "maven-shade-plugin" oryxos-cli/pom.xml    # 期望 0
grep -n "mainClass" oryxos-boot/pom.xml            # 期望 1 行:io.oryxlabs.oryxos.cli.OryxOsCli

./mvnw -q clean package -DskipTests -Ddependency-check.skip=true
ls -lh oryxos-boot/target/oryxos.jar
java -jar oryxos-boot/target/oryxos.jar --help      # 期望列出 12 条命令 + 4 个分组
```

**期望**：`oryxos-boot/target/oryxos.jar` 是**唯一**发行物；`oryxos-cli/target/` 下不再有可执行 fat JAR。理由见 [contracts/cli-assembly.md](contracts/cli-assembly.md) §1。

---

## 5. 端到端人工 Demo（需真模型，人工项）

**这是本节「把 Agent 从能跑变成能用」的证明**（章程原则 X）。在**空目录**里跑，不要在本仓库根目录跑（会在仓库里生成 `.oryxos/`）。

```bash
export DEEPSEEK_API_KEY=<你的 key>          # 只经环境变量注入,不落任何文件
mkdir -p /tmp/oryxos-demo && cd /tmp/oryxos-demo

# ① 起工作区(幂等:再跑一次应输出「已有」清单,不覆盖任何文件)
java -jar /home/wngbob/oryxos-wngbob/oryxos-boot/target/oryxos.jar init
java -jar /home/wngbob/oryxos-wngbob/oryxos-boot/target/oryxos.jar init
ls -a .oryxos/                               # 期望:agents/ skills/ memory/ sessions/ logs/ output/
                                             #       AGENTS.md SOUL.md USER.md mcp_servers.yaml oryxos.db
ls .oryxos/agents/                           # 期望:空(本节不生成默认 Agent)

# ② 造一个 Agent
java -jar .../oryxos-boot/target/oryxos.jar profile create weather
java -jar .../oryxos-boot/target/oryxos.jar profile list      # 期望:weather,有效

# ③ 对话(多轮),/quit 退出
java -jar .../oryxos-boot/target/oryxos.jar chat --profile weather

# ④ 重启后历史还在
java -jar .../oryxos-wngbob/oryxos-boot/target/oryxos.jar session list   # 期望:cli:local:weather,active,时间戳
java -jar .../oryxos-boot/target/oryxos.jar chat --profile weather       # 上一轮内容仍在上下文里
```

**逐项判定**：

| 人工项 | 期望 |
| --- | --- |
| `init` 幂等 | 第二次输出「已有」清单，`.oryxos/` 内文件时间戳不变 |
| `chat` 交互 | 能连续多轮；`/quit` 正常退出，退出码 0；**输入流结束（Ctrl+D）等同 `/quit`** |
| `chat --message "..."` | 输出响应后直接退出，**不进交互循环** |
| `session list` | 出现 `cli:local:weather`，按最近活跃排序 |
| 「重启」历史还在 | 第二次 `chat` 时上一轮对话仍被引用（不是从零开始） |
| 启动日志 | `chat` 的日志里 `Found N JPA repository interfaces` 的 **N > 0**（`@EntityScan` / `@EnableJpaRepositories` 生效的机器可判信号） |
| 三种模式共享存储 | `chat` / `serve` / `gateway` 读同一份 `.oryxos/agents/`、写同一个 `.oryxos/oryxos.db` |

**退出码**（`echo $?`）：

```bash
java -jar .../oryxos.jar profile list   # 非工作区目录执行 → 提示先跑 oryxos init,退出码 1
java -jar .../oryxos.jar chat --bogus   # 参数错误 → 打印用法,退出码 2
java -jar .../oryxos.jar chat           # 工作区里有两个 Agent 且未 --profile → 报错并列出可选名,退出码 1
```

---

## 6. 轻 / 重命令时延对比（SC-001）

```bash
time java -jar .../oryxos-boot/target/oryxos.jar profile list   # 轻:秒级
time java -jar .../oryxos-boot/target/oryxos.jar chat --help   # 轻:秒级(--help 不起上下文)
time java -jar .../oryxos-boot/target/oryxos.jar chat           # 重:2~4 秒(起 Spring 上下文)
```

**期望**：轻命令与重命令有肉眼可辨的量级差。若轻命令也要等数秒，说明它误启动了 Spring 上下文（FR-003 被破坏）。

---

## 7. 已知「未接入」项（人工项清单）

本节有 5 处输出指向尚未落地的基础设施，**MUST 明确标注、MUST NOT 编造数值**（[cli-surface.md](contracts/cli-surface.md) §4）。人工核对时看到占位说明是**预期的**，不是 bug：

| 命令 | 输出段 | 期望文案 |
| --- | --- | --- |
| `provider list` | 密钥就绪状态 | 未接入（第 26 节） |
| `status` | 密钥就绪状态 | 未接入（第 26 节） |
| `status` | 定时任务数 | 未接入（第 28 节） |
| `tool list` | 工具清单 | 空表 + 「第 20 节接入 ToolRegistry」 |
| `serve` | HTTP 端口 | 端口在第 26 节启用 |

**同时**：`serve` / `gateway` 本节只启动上下文并**前台常驻**（Ctrl+C 退出）——**启动完立刻退出是错的**，退出码 0 的正常结束在这里意味着实现有误。

**凭证红线**：`provider list` / `status` 的输出、结构化日志、报错三处**都不得出现任何密钥值**（章程原则 VIII / SC-006）。核对时若看到形如 `sk-...` 的串，即为缺陷。
