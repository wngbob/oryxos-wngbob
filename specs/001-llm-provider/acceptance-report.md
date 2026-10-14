# 第 16 节验收报告：Provider —— 对接大模型的统一入口

**Feature**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md) | **Tasks**: [tasks.md](tasks.md) | **Quickstart**: [quickstart.md](quickstart.md)
**Branch**: `main`（当时）| **完成 commit**: `91476a1`（2026-10-13）| **任务完成度**: 33 / 33
**结论**: ✅ 节级 DoD 六项满足；harness 已判卷，剩余人工项见 §7

> ⚠️ **本文件是回填报告（2026-10-14 补写）**。第 16 节完成时尚未要求落盘验收报告；本报告按 [`.claude/skills/oryxos-lesson-dev`](../../.claude/skills/oryxos-lesson-dev/SKILL.md) 第 7 步的固定结构重建。**证据强度声明见 §0**——本节的实现文件在此后被动过，故部分证据是「今天重跑」而非「当时快照」。

---

## 0. 回填方法与证据强度

| 证据类型 | 说明 |
|---|---|
| **实测（今天重跑，强）** | 门禁、测试条数、H4 grep、存在性核对——在 `HEAD` 上重跑得出 |
| **据 commit / 文档重建（弱）** | 任务完成度、当时的门禁结论、harness 对号——来自 `91476a1` 的 commit 内容与 `tasks.md` 勾选状态 |

**等价性边界**：本节文件在此后被后续节改动过，故「今天的测试运行」**不完全等于**「当时的测试运行」。已核实的差异如下（用 `git log 91476a1..HEAD -- <file>` 逐文件确认）：

| 本节文件 | 此后由谁改动 | 改了什么 |
|---|---|---|
| `oryxos-core/pom.xml` | 第 17 节 | 补 `spring-ai-model` 等依赖 |
| `pom.xml` | 第 17 节 | 依赖/插件调整 |
| `oryxos-core/.../profile/ProfileLoader.java` | 第 18 节（+47 行） | 新增 `loadAllDetailed()` + `LoadResult`（CLI 要拿到失败明细） |
| `oryxos-provider/.../ProviderService.java` | 第 18 节（3 行） | `implements LlmClient` + `@Override`（第 17 节留白的接线） |
| `oryxos-storage/.../db/schema.sql` | 第 18 节 | 追加 `sessions` 表 |
| **本节 4 个测试类** | **均未被后续节改动** | ✅ 测试用例本身是当时的 |

**结论**：测试用例是当时的原样；被测实现有两处小改（均为**增量**：`ProfileLoader` 加方法、`ProviderService` 加接口实现），**未删除也未修改本节任何既有行为**。因此「今天全绿」对第 16 节的验收点构成有效证据。

---

## 1. 证据一：全量门禁 `mvn clean verify` 全绿

```text
[INFO] BUILD SUCCESS
```

静态检查逐模块执行并全过：`spotless:check` / `pmd:check`（阿里 P3C）/ `checkstyle:check` / `spotbugs:check`（含 FindSecBugs），根模块聚合跑 `dependency-check:aggregate`（`failBuildOnCVSS=7`，命中项全部为既有依赖且 CVSS < 7，不阻断）。

**本节涉及的第三方依赖核实**（课件人工项点名要求 `mvn dependency:tree` 看一眼）：`spring-ai-model` / `spring-ai-alibaba` 相关坐标在锁定的 BOM 里可解析、可下载——`./mvnw -o clean verify` 离线全绿即为证据。

---

## 2. 证据二：课件 harness 映射表逐类对号

课件 harness 共 **4 个单测类 + 1 个集成冒烟**，全部落地且非空：

| 课件测试类 | 落地 | 条数 | 课件覆盖的验收点 → 落地测试方法 |
|---|---|---|---|
| `ProfileLoaderTest` | ✅ | 5 | 全字段解析 → `loadsAllFrontmatterFields`；引用不存在的 provider 报错清晰 → `rejectsProviderNotInGlobalList`；坏文件不阻断其余加载 → `brokenFileDoesNotBlockOthers`；`${ENV}` 占位从环境变量解析 → `resolvesEnvPlaceholders`；另加 `missingAgentMdTreatedAsBroken` |
| `ProviderServiceTest` | ✅ | 4 | 双 provider 按名路由不串台 → `routesToNamedProviderOnly`；未知名抛异常 → `unknownProviderNameThrowsWithOriginalName`；成功/失败都落审计 → `recordsAuditBeforeRethrowingOnFailure`；**自动执行关闭** → `callWithToolSchema_disablesAutoExecution` |
| `ToolSchemaAdapterTest` | ✅ | 3 | schema 字段一一对齐 → `translatesSchemaFieldsVerbatim`；**只翻译、产物不含执行逻辑** → `callbacksCarryNoExecution`；另加 `emptyToolsYieldEmptyList` |
| `LlmCallRepositoryTest` | ✅ | 2 | 手工脚本建出的表含 `success` / `error_message` 两列 → `schemaScriptCreatesAuditColumns`；写入读回成败两条记录一致 → `persistsAndReadsBackAuditColumns` |
| `ProviderSmokeIT`（集成冒烟） | ✅ | 1 | `@Tag("integration")`，CI 默认跳过，本地手动跑真调一次模型 |

**课件「最值钱的三个测试方法」逐个对号**：

| 课件方法（中文名） | 落地方法名 | 断言保真度 |
|---|---|---|
| `按名路由_两个provider不串台` | `routesToNamedProviderOnly` | ✅ 逐条保真：mock 两个 `ChatModel`，断言目标被调 1 次、另一个 `never()` |
| `调用失败_审计必须留下success为false的记录` | `recordsAuditBeforeRethrowingOnFailure` | ✅ 逐条保真：断言**异常继续上抛**「并且」审计先落了 `success=false` + 原因 |
| `带工具schema调用_请求里关闭了自动执行` | `callWithToolSchema_disablesAutoExecution` | ✅ 逐条保真：`ArgumentCaptor` 抓 `Request`，断言自动执行关闭、schema 确实带上 |

方法名按纪律译成英文，课件中文原文进 `@DisplayName`（上表左列即原文）。

---

## 3. 证据三：「本节交付物」逐项存在性核对

课件交付物清单逐项 `ls` 核对（今天实测，文件均仍在）：

| 课件交付物 | 状态 | 落地路径 |
|---|---|---|
| 代码 `Profile` | ✅ | `oryxos-core/.../core/profile/Profile.java` |
| 代码 `ProfileLoader` | ✅ | `oryxos-core/.../core/profile/ProfileLoader.java`（第 18 节加过 `loadAllDetailed()`） |
| 代码 `ProfileRegistry` | ✅ | `oryxos-core/.../core/profile/ProfileRegistry.java` |
| 代码 `ProviderService`（含 `chat(sessionId, Profile, Prompt)`） | ✅ | `oryxos-provider/.../provider/ProviderService.java` |
| 代码 工具格式适配器 | ✅ | `oryxos-provider/.../provider/ToolSchemaAdapter.java` |
| 代码 `LlmCall` 实体 + `LlmCallRepository` | ✅ | `oryxos-storage/.../entity/LlmCall.java`、`.../repository/LlmCallRepository.java` |
| 代码 审计落库实现 | ✅ | `oryxos-provider/.../provider/LlmCallRecorder.java` + `JpaLlmCallRecorder.java` |
| 测试 4 个单测类 + `ProviderSmokeIT` | ✅ | 见 §2 |
| 配置 `application.yaml` 的 `oryxos.providers` 全局层 | ✅ | `oryxos-boot/src/main/resources/application.yaml` |
| 配置 Profile YAML 的 `provider` 段 | ✅ | 由 `Profile.ProviderSpec` 承载，`ProfileLoaderTest` 覆盖 |
| 表 `llm_calls`（含 `success` / `error_message`） | ✅ | `oryxos-storage/src/main/resources/db/schema.sql` |

---

## 4. 证据四：前序节回归

第 16 节是**第一个代码课**，前序是骨架期的「契约接口 + 审计地基」（无测试）。本节的可回归面是**审计表地基**：`llm_calls` 表由手工脚本建出、`tool_invocations` 表未被本节破坏——由 `LlmCallRepositoryTest` 的两条（建表脚本 + 读写往返）直接覆盖。

以当前 `HEAD` 重跑全量：**87 条测试全绿**（含本节 14 条）。

---

## 5. 证据五：H4 六条全局不变量逐条自查

| # | 不变量 | 结论 | 证据（今天在 `HEAD` 上 grep） |
|---|---|---|---|
| ① | 涉外 IO 首行过 `Sandbox.enforce` | **N/A** | 本节不产生涉外 IO（`ProviderService` 调的是 `ChatModel` 抽象，无文件/HTTP/Shell） |
| ② | LLM 调用成败都落 `llm_calls` | ✅ | `ProviderService.java:82` 成功路径 `audit.record(..., true, null, ...)`；`:95` 失败路径 `audit.record(..., false, e.getMessage(), ...)`——**先落账再上抛** |
| ③ | 无明文 key | ✅ | `grep -rniE "sk-[a-z0-9]{8,}\|api[_-]?key\s*[:=]\s*\"[^\"]+\""` 对 `oryxos-provider/src/main`、`oryxos-core/src/main`、`oryxos-boot/src/main/resources` → **0 命中**；凭证走 `${ENV_VAR}` 占位 |
| ④ | `session_id` 只在 `SessionManager` 内拼接 | ✅ | 本节只**接收** `sessionId` 入参（`chat(String sessionId, ...)`），不构造标识 |
| ⑤ | 无 Reactor / `CompletableFuture` / 自建线程池 | ✅ | 同步阻塞实现；类 javadoc 明写「不引入响应式编程、不返回异步句柄、不自建线程池（章程原则 I）」 |
| ⑥ | 无 Spring AI 自动工具执行路径 | ✅ | **本节的核心不变量**：`ProviderService.java:125` `options.internalToolExecutionEnabled(false)`；回归测试 `callWithToolSchema_disablesAutoExecution` 钉死——**一旦有人改回自动执行，这里立刻红** |

---

## 6. 证据六：变更总结

### 6.1 改动点（据 `91476a1` 的 commit 内容）

**新增 —— `oryxos-core`**（契约与 Profile 模型）
- `core/profile/Profile.java`、`ProfileLoader.java`、`ProfileRegistry.java` —— `AGENT.md` frontmatter → `Profile` 的派生与注册。

**新增 —— `oryxos-provider`**（本节主体，10 个文件）
- `ProviderService.java` —— **显式 provider name → `ChatModel` 映射表**（章程原则 10：禁止按类型扫描容器 Bean）；成败双路审计。
- `ToolSchemaAdapter.java` —— `OryxTool` → Spring AI 工具格式，**只翻译不执行**。
- `LlmCallRecorder.java` / `JpaLlmCallRecorder.java` —— 审计落库接缝 + 实现。
- `ProviderConfiguration.java` / `ProviderProperties.java` / `ProviderNotFoundException.java` —— 装配、配置绑定、专用异常。

**修改 —— `oryxos-storage`**
- `db/schema.sql` —— 追加 `llm_calls` 表；`entity/LlmCall.java`、`entity/ToolInvocation.java` 补审计列。

**新增 —— 测试**
- `ProfileLoaderTest`(5)、`ProviderServiceTest`(4)、`ToolSchemaAdapterTest`(3)、`LlmCallRepositoryTest`(2)、`ProviderSmokeIT`(`@Tag("integration")`)，外加 `StorageTestApplication`（`@DataJpaTest` 的测试配置）。

**修改 —— 构建**
- `pom.xml`、`oryxos-core/pom.xml`、`oryxos-provider/pom.xml`、`oryxos-storage/pom.xml` —— 补 test scope 与模块依赖。
- `oryxos-boot/src/main/resources/application.yaml` —— `oryxos.providers` 全局层。
- `dependency-check-suppressions.xml` —— CVE 抑制清单（每条附理由）。

### 6.2 重点 review 清单（按风险排序）

1. **★ 显式 provider 映射表，禁止类型扫描** —— `ProviderService.java`。多 Provider 并存时按类型扫描容器会有歧义（章程原则 10）。回归测试 `routesToNamedProviderOnly` 用「另一个 `never()`」直接钉死。
2. **★ 自动工具执行必须关闭** —— `ProviderService.java:125`。这是全项目最易写错的一条（章程原则 4）；开启会导致 tool 被调两次。回归测试 `callWithToolSchema_disablesAutoExecution`。
3. **失败审计先落账再上抛** —— `ProviderService.java:95`。顺序反了会丢审计。回归测试 `recordsAuditBeforeRethrowingOnFailure` 断言的是「抛了异常**并且**审计先落了账」。
4. **`ToolSchemaAdapter` 只翻译不执行** —— 产物 `call()` 抛 `UnsupportedOperationException`，回归测试 `callbacksCarryNoExecution`。
5. **测试走手工建表脚本而非 Hibernate 自动建** —— `LlmCallRepositoryTest` 读 `schema.sql` 逐条执行；否则测试绿了、生产跑真脚本时列名对不上。

### 6.3 如何验证

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64

# 1) 全量门禁 —— 预期 BUILD SUCCESS
./mvnw clean verify

# 2) 本节 4 个单测类（14 条）—— 预期全绿
./mvnw -o -pl oryxos-provider -am test \
  -Dtest='ProviderServiceTest,ToolSchemaAdapterTest' -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -o -pl oryxos-core -am test \
  -Dtest=ProfileLoaderTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -o -pl oryxos-storage -am test \
  -Dtest=LlmCallRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false

# 3) ★ 关键回归：自动执行关闭 —— 预期 1 条绿
./mvnw -o -pl oryxos-provider -am test \
  -Dtest='ProviderServiceTest#callWithToolSchema_disablesAutoExecution' \
  -Dsurefire.failIfNoSpecifiedTests=false

# 4) ★ 关键回归：双 provider 不串台 —— 预期 1 条绿
./mvnw -o -pl oryxos-provider -am test \
  -Dtest='ProviderServiceTest#routesToNamedProviderOnly' \
  -Dsurefire.failIfNoSpecifiedTests=false

# 5) H4-⑥ 自动执行关闭的代码位 —— 预期命中 1 行
grep -n "internalToolExecutionEnabled(false)" \
  oryxos-provider/src/main/java/io/oryxlabs/oryxos/provider/ProviderService.java

# 6) H4-③ 无明文 key —— 预期无输出
grep -rniE "sk-[a-z0-9]{8,}|api[_-]?key\s*[:=]\s*\"[^\"]+\"" \
  oryxos-provider/src/main oryxos-core/src/main oryxos-boot/src/main/resources

# 7) 集成冒烟（需真 key，人工）—— 预期 ProviderSmokeIT 通过
DEEPSEEK_API_KEY=<你的 key> ./mvnw -o -pl oryxos-provider -am test -Dgroups=integration
```

---

## 7. 剩余人工项（课件「五、做完怎么验」，harness 判不了）

1. **provider 依赖已在锁定 BOM 里可下载可解析** —— 已由 `./mvnw -o clean verify` 离线全绿间接证明（离线解析成功 = 本地仓库齐备）。若要按课件字面确认，跑 `./mvnw -pl oryxos-provider dependency:tree`。
2. **集成冒烟真跑过一次** —— ⚠️ **未做**（需真 key，且本项目纪律要求凭证只走环境变量、不落明文）。`ProviderSmokeIT` 已就位并打 `@Tag("integration")`，CI 默认跳过；需要时按 §6.3 第 7 条手动跑。
3. **key 无明文** —— ✅ 已由 §5-③ 的 grep 打勾（课件此条属机器可判，已自动覆盖）。
4. **其余验收点**（双 provider 路由、错误名报错、成功/失败审计、自动执行关闭、只翻译不执行）—— ✅ 已由 §2 的 4 个单测类覆盖。

---

## 8. 备注

- ⚠️ `docs/class/`（课件目录）**未入库**——仓库是 Public。
- 本报告为**回填**，写入时**未 commit / 未 push**。
- 后续节对本节文件的改动已列于 §0；其中 `ProfileLoader.loadAllDetailed()` / `LoadResult` 与 `ProviderService implements LlmClient` 是第 18 节按报批裁决补的，**属增量、非破坏性**。
