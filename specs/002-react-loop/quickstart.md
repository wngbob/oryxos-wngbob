# Quickstart: 验证 ReAct 循环切片

本文件是**验证指引**，不是实现说明。实现细节见 [plan.md](plan.md)、[data-model.md](data-model.md) 与 [contracts/](contracts/)。

## 前置

- JDK 21（本机：`export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`）
- 仓库自带 `./mvnw`；首次构建需能访问 Maven 镜像
- **无需任何凭证、无需网络**——本节所有测试的模型调用与工具执行都是替身，文件系统走 JUnit 临时目录

## 场景 1：单测全绿（机器判卷）

```bash
./mvnw clean test
```

**预期**：5 个测试类全绿，无网络访问、无真实凭证消耗。全部在 `oryxos-core`，秒级跑完。

| 测试类 | 所在模块 | 它守的是什么 |
| --- | --- | --- |
| `ReActLoopTest` | `oryxos-core` | 无工具调用一轮收尾（模型恰好被调 1 次）；有工具调用则执行并回填进下一轮；**转满最大轮数强制停**（模型恰好被调 10 次、一轮不多，答复含「达到最大轮数」）；每轮响应与工具结果按序累积 |
| `PromptBuilderTest` | `oryxos-core` | 四部分顺序与来源一一对应；历史超 N 轮被截断且**不以工具消息开头**；system prompt 末尾含当前日期时间 |
| `ToolExecutorTest` | `oryxos-core` | 成功写 `success=true`；失败也写 `success=false` 带原因；**异常不吞**（返回失败结果而非上抛）；查不到工具同样留痕；**成败两条路径各输出一条结构化日志**（章程附加约束「可观测」要求日志覆盖每一次 Tool 调用） |
| `AgentServiceTest` | `oryxos-core` | 处理期间 `ProfileContext` 可取到当前 Profile；**抛异常时 finally 也把它清掉**；结束后 Session 经接缝被持久化 |
| `ContextLoaderTest` | `oryxos-core` | 改文件后下一次 build 立即读到新内容（无缓存）；Skill 引用缺失报错、Bootstrap 缺失 WARN |

**课件 harness 里写出代码的两个关键回归测试**（课件用中文方法名，落地时译为英文 + `@DisplayName` 保留原文，断言逻辑逐条保真）：

1. 「模型一直要调工具 → 转满最大轮数强制停」：`verify(llmClient, times(10)).chat(...)` + `assertTrue(reply.contains("达到最大轮数"))`。
2. 「处理中抛异常 → ProfileContext 也必须被清掉」：`assertThrows(RuntimeException.class, ...)` + `assertNull(ProfileContext.current())`。

第二个守的是最阴险的一类 bug：ThreadLocal 泄漏在单请求测试里永远不报错，只在并发复用时串号。

## 场景 2：全量门禁（完成定义）

```bash
./mvnw clean verify
```

**预期**：除单测外，Spotless / P3C / Checkstyle / SpotBugs+FindSecBugs / OWASP Dependency-Check 全过，BUILD SUCCESS。

> 本地想跳过耗时的 CVE 扫描：追加 `-Ddependency-check.skip=true`。**CI 必须全量跑**。

## 场景 3：架构约束抽查（可 grep 判定，属机器可判项）

```bash
# ① core MUST NOT 依赖任何能力模块（storage 是共享基础设施，允许）
grep -rn "import io\.oryxlabs\.oryxos\.\(provider\|memory\|tool\|web\|channel\|cli\|boot\)" \
    --include="*.java" oryxos-core/src/main
# 预期：无输出

# ② 依赖树里不出现能力模块
./mvnw -pl oryxos-core -am dependency:tree | grep -E "oryxos-(provider|tool|memory|web|channel-cli|cli)"
# 预期：无输出（oryxos-storage 会出现，它是允许的）

# ③ 无响应式 / 自建线程池（H4 全局不变量⑤）
grep -rn "Flux\|Mono\|CompletableFuture\|Executors\." --include="*.java" \
    oryxos-core/src/main oryxos-provider/src/main
# 预期：无输出

# ④ 无 Spring AI 自动工具执行路径（章程原则 III，最易写错的一条）
grep -rn "internalToolExecutionEnabled" --include="*.java" oryxos-core/src/main
# 预期：无输出（core 只构造 toolCallbacks，开关由 ProviderService 单点关闭）

# ⑤ 无明文凭证
grep -rn "sk-" --include="*.java" --include="*.yaml" --include="*.yml" . | grep -v "^./docs/"
# 预期：无输出
```

**② 的参数说明**：`-am`（also make）不能省——单模块 `-pl` 命令会把兄弟模块从 `~/.m2` 解析，本地只要跑过一次 `mvn install`，那里就躺着旧快照。**但 `-am` 之后配 `-Dtest=X` 时，还要加 `-Dsurefire.failIfNoSpecifiedTests=false`**，否则在无匹配测试的模块上 surefire 会判失败。

**只跑本节测试**：

```bash
./mvnw -pl oryxos-core -am test -Dsurefire.failIfNoSpecifiedTests=false
```

## 场景 4：人工抽查项（自动化覆盖不到）

对应课件「五、做完怎么验」：

1. **循环是自己实现的，没用框架现成的 Agent 封装**（code review 确认，测不出来）：
   ```bash
   grep -rn "spring-ai.*Agent\|ChatClient\|Advisor" --include="*.java" oryxos-core/src/main
   # 预期：无输出——核心循环是手写的 for + 四个类，不碰 Spring AI 的 Agent 抽象
   ```

2. **真模型跑通 Demo 一（每日天气）的对话版**——问天气、Agent 调 `http_get`、拿到数据、给出穿搭建议，一次对话里循环不止一轮。

   ⚠️ **本节做完时这一步还跑不了，如实说明**：本节没有独立交互入口，端到端 Demo 需要第 18 节的 `oryxos chat`（CLI）与第 20 节的 `http_get`（内置 Tool）就位。本节交付后的端到端路径是断的——**这是 spec 认可的形态**（spec 的 Assumptions 已把 Demo 列为人工项，完成判据以 harness 为准）。第 18 节装配时接上 `LlmClient`（给 `ProviderService` 加 `implements`）与 `ToolCatalog` 之后，这条链路才完整。

3. **确认四个接缝接口没有任何实现方**（本节只定义与调用，不实现）：
   ```bash
   grep -rn "implements LlmClient\|implements ToolCatalog\|implements MemorySource\|implements SessionService" --include="*.java" .
   # 预期：无输出
   ```

## 场景 5：本节自身能力的最小可演示形态

ReAct 循环没有独立入口——它要与第 16 节的 Provider、第 18 节的 CLI 一起才撑得起 Demo 一。所以本节的「跑通」标准落在 harness 上，具体是这几条：

- 一次处理能**收尾**（模型不要求工具时恰好调 1 次、返回模型文本）；
- 一次处理能**转起来**（模型要求工具时执行、结果回灌进下一轮）；
- 一次处理**不会挂死**（转满上限强制收尾，恰好 N 轮）；
- 每一次工具调用**都留痕**（成败都进 `tool_invocations`）；
- 处理无论怎么退出，**当前 Agent 的痕迹都被清掉**。

`ReActLoopTest` + `ToolExecutorTest` + `AgentServiceTest` 就是这五条的机器证据。
