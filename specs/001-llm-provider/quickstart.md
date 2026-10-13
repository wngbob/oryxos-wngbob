# Quickstart: 验证 Provider 切片

本文件是**验证指引**，不是实现说明。实现细节见 [plan.md](plan.md)、[data-model.md](data-model.md) 与 [contracts/](contracts/)。

## 前置

- JDK 21（本机：`export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`）
- 仓库自带 `./mvnw`；首次构建需能访问 Maven 镜像
- 无需任何真实凭证即可跑完全部单测（`ChatModel` 被 mock）

## 场景 1：单测全绿（机器判卷）

```bash
./mvnw clean test
```

**预期**：4 个单测类全绿，无网络访问、无真实凭证消耗。

| 测试类 | 所在模块 | 它守的是什么 |
| --- | --- | --- |
| `ProfileLoaderTest` | `oryxos-core` | 合法 frontmatter 全字段解析；引用不存在的厂商报错清晰；坏文件不阻断其余加载；`${ENV}` 占位从环境变量解析 |
| `ProviderServiceTest` | `oryxos-provider` | **双厂商按名路由不串台**；未知名抛异常；成功/失败都落审计；**自动执行关闭** |
| `ToolSchemaAdapterTest` | `oryxos-provider` | schema 字段一一对齐；产物里不含执行逻辑 |
| `LlmCallRepositoryTest` | `oryxos-storage` | 手工脚本建出的表能存能读，`success` / `error_message` 两列真实存在 |

## 场景 2：全量门禁（完成定义）

```bash
./mvnw clean verify
```

**预期**：除单测外，Spotless / P3C / Checkstyle / SpotBugs+FindSecBugs / OWASP Dependency-Check 全过，BUILD SUCCESS。

> 本地想跳过耗时的 CVE 扫描：追加 `-Ddependency-check.skip=true`。**CI 必须全量跑**。

## 场景 3：集成冒烟（真调一次模型，本地手动）

```bash
DEEPSEEK_API_KEY=<你的 key> ./mvnw -pl oryxos-provider -am test \
    -DexcludedGroups=none -Dtest=ProviderSmokeIT -Dsurefire.failIfNoSpecifiedTests=false
```

**预期**：`ProviderSmokeIT` 通过——拿到非空响应，且 `llm_calls` 表多出一条 `success = true` 的记录。

**三个参数都必须写，少一个就失败**：

1. **`-am`**（also make）：把 `oryxos-core` / `oryxos-storage` 一并拉进反应堆。**漏了它会取到 `~/.m2` 里的旧快照 jar**——只要曾经跑过 `mvn install`，兄弟模块的旧字节码就在本地仓库躺着，而 `-pl` 单模块构建会优先用它而不是源码。本节实施期实测：旧 jar 里 `LlmCall()` 还是 `protected`，冒烟报 `IllegalAccessError`，而源码与 `target/classes` 里早已是 `public`。**全量 `mvn clean verify` 不会暴露这个坑**（那时兄弟模块走反应堆），只有这条单模块命令会——所以 `-am` 不是可选项，它让命令自洽、不依赖本地仓库的偶然状态。
2. **`-DexcludedGroups=none`**：该用例打 `@Tag("integration")`，根 pom 的 surefire 已把该组**默认排除**（不能让外部 API 的可用性变成自己流水线的可用性）。默认值走 `pom.xml` 的 `<excludedGroups>` 属性——**不能写成插件配置里的字面量**，Maven 的 `-D` 覆盖不了显式写死的插件参数（本节实施期实测踩过）。
3. **`-Dtest=ProviderSmokeIT`**：类名以 `IT` 结尾，不在 surefire 的默认 includes（`*Test` / `Test*` / `*Tests` / `*TestCase`）里，必须显式点名。**但 `-am` 之后这条过滤会在 `oryxos-core` / `oryxos-storage` 里匹配不到任何测试**，surefire 默认把"点名了却一个没跑到"判为失败，所以还要配 `-Dsurefire.failIfNoSpecifiedTests=false`。

没配 `DEEPSEEK_API_KEY` 时即使点名也只是 **skipped 而非 failed**（`@EnabledIfEnvironmentVariable` 兜底），已实测。

## 场景 4：人工抽查项（自动化覆盖不到或不值得自动化）

对应课件「五、做完怎么验」：

1. **依赖可用性已核实**（本节已做，可复核）：
   ```bash
   ./mvnw -pl oryxos-provider -am dependency:tree | grep -i spring-ai
   ```
   预期：看到 `spring-ai-model` 与 `spring-ai-deepseek`，版本由 BOM 托管为 `1.1.8`。

2. **真 key 冒烟跑过一次**：即场景 3。

3. **无明文凭证**：
   ```bash
   grep -rn "sk-" --include="*.java" --include="*.yaml" --include="*.yml" . | grep -v "^./docs/"
   ```
   预期：**无输出**（配置里只有 `${DEEPSEEK_API_KEY}` 这样的占位）。

4. **架构约束抽查**（可 grep 判定，属机器可判项）：
   ```bash
   # 不应出现按类型扫描 ChatModel Bean 的写法：拿类型对象去容器里捞（getBean / getBeansOfType），
   # 或让 Spring 把同类型 Bean 收集成 Map / ObjectProvider 注入。
   # 注意：不能只 grep `Map<String, ChatModel>`——显式映射表自身的字段类型与 Bean 返回类型就是这个
   # 字面量，那样会把唯一正确的写法也报出来（本节实施期实测：该写法必然误报）。
   grep -rn "ChatModel.class\|getBeansOfType\|ObjectProvider<ChatModel>" --include="*.java" oryxos-provider/src/main
   # 不应出现响应式/自建线程池
   grep -rn "Flux\|Mono\|CompletableFuture\|Executors\." --include="*.java" oryxos-provider/src/main oryxos-core/src/main
   ```
   预期：两条均无输出。

## 场景 5：端到端演示（本节自身能力的最小可演示形态）

第 16 节的 Provider 没有独立入口——它要与第 17 节的 ReAct 循环一起才能撑起 Demo 一（每日天气）的对话版。本节跑通的标准因此很直接：

- 一个 Profile 点名的厂商能取到模型；
- 一次 `chat(...)` 调用能返回响应；
- 这次调用在 `llm_calls` 里留了痕（成功与失败都算）。

`ProviderSmokeIT` 就是这三条的端到端证据。
