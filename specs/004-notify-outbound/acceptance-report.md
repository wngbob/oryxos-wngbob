# 第 19 节验收报告：Notify 出站推送

**Feature**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md) | **Tasks**: [tasks.md](tasks.md) | **Quickstart**: [quickstart.md](quickstart.md)
**Branch**: `class-19` | **日期**: 2026-10-14 | **任务完成度**: 19 / 19
**结论**: ✅ 节级 DoD 六项全部满足；harness 已判卷，剩余人工项见 §4

---

## 1. 证据一：全量门禁 `mvn clean verify` 全绿

```text
[INFO] BUILD SUCCESS
[INFO] Total time:  53.072 s
```

静态检查逐模块执行并全过：`spotless:check` / `pmd:check`（阿里 P3C）/ `checkstyle:check` / `spotbugs:check`（含 FindSecBugs），根模块聚合跑 `dependency-check:aggregate`。

### ★ 本轮真实抓到一个红（已修）

首跑 `oryxos-storage` PMD 报 1 条违规：

```text
rule="AbstractMethodOrInterfaceMethodMustUseJavadocRule"
class="NotifyChannelRepository" method="findFirstByOrderByNameAsc"
方法【findFirstByOrderByNameAsc】的返回值缺少javadoc注释
```

**修的是实现**——补 `@return` javadoc（与既有 `LlmCallRepository` / `ToolInvocationRepository` 的派生查询写法一致），**未用 `@SuppressWarnings` / `@SuppressFBWarnings` 压制任何告警**。

### OWASP 如实交代

- 配置为 `failBuildOnCVSS=7`（根 pom），报告命中的 CVE 全部来自**既有依赖**（snakeyaml / sqlite-jdbc / angus-mail / jackson-modules-java8 等），CVSS 均 < 7，故不阻断构建。
- **本节新增依赖零 CVE**：逐名 grep 报告确认 `okhttp` / `kotlin-stdlib` / `mockwebserver3` / `okio` **0 命中**（它们在报告里根本不出现）。
- 离线模式下 `dependency-check` 不执行（需联网更新 NVD），故 OWASP 证据取自本次联网全量门禁。

---

## 2. 证据二：课件 harness 映射表逐类对号

| 课件 harness | 落地测试类 | 条数 | 课件守点 → 落地测试方法 |
|---|---|---|---|
| 第一批 `WebhookNotifyAdapterTest` | ✅ 同名 | 8 | body 带 `content` → `postBodyCarriesContent`；URL 来自 config 非硬编码 → `sendsToTheUrlFromTargetConfigNotHardcoded`；5xx 上抛不静默吞 → `throwsOnServerErrorWithoutRetrying` |
| 第二批 `NotifyToolsTest` | ✅ 同名 | 8 | 未配置 → 明确报错 → `failsWhenNoChannelConfigured`；`channel` 缺省 → 取第一个 → `defaultChannelWhenChannelNameIsMissing`；**★ `enforce` 先于 `send`** → `enforceSandboxBeforeSending` |
| （超出课件；本节新增接缝的测试） | `JpaNotifyChannelRegistryTest` | 10 | data-model §5 六步逐条 |
| （同上） | `NotifyChannelRepositoryTest` | 3 | 建表脚本 + 存取 + 默认渠道确定性 |

**★ 关键回归测试原样落地**（课件里写死了代码的那条），方法名译英、课件原文进 `@DisplayName`：

```java
// oryxos-tool/src/test/java/io/oryxlabs/oryxos/tool/notify/NotifyToolsTest.java:60-72
@Test
@DisplayName("发送前必须先过白名单校验")
void enforceSandboxBeforeSending() {
  when(registry.resolve("team-lark")).thenReturn(targetWithUrl(SAMPLE_URL));
  notifyTools().notify("测试消息", "team-lark");
  InOrder inOrder = inOrder(sandbox, adapter);
  inOrder.verify(sandbox).enforce(any(SandboxAction.class));
  inOrder.verify(adapter).send(any(NotifyTarget.class), anyString());
  inOrder.verifyNoMoreInteractions();
}
```

**两处如实披露（均为「更严」而非「放宽」）**：

1. 课件用 `argThat(a -> a.type() == ActionType.HTTP_REQUEST)` 内联断言类型；落地时**拆成独立测试** `enforceChecksHttpRequestAgainstTheResolvedUrl`（`ArgumentCaptor` 断言 `type` **与** `target`）——课件只验 `type`，落地还验了 `target` 是解析出来的地址，断言**未削弱**。
2. 渠道名用 `team-lark` 而非课件的 `default`（课件里 `"default"` 是渠道名；本节的默认渠道口径是「按注册名升序第一个」，用具体名更能证「按名解析」）。这是名字选择，**不是断言削弱**。

---

## 3. 证据三：「本节交付物」逐项存在性核对

| 课件交付物 | 状态 | 证据 |
|---|---|---|
| 代码 `NotifyChannelAdapter` | ✅ 存在，**零改动** | `git status --short <该文件>` 无输出 |
| 代码 `NotifyTarget` | ✅ 存在，**零改动** | 同上 |
| 代码 `WebhookNotifyAdapter` | ✅ 新增 87 行 | `oryxos-tool/.../notify/WebhookNotifyAdapter.java` |
| 代码 `NotifyTools` | ✅ 新增 62 行 | `oryxos-tool/.../notify/NotifyTools.java` |
| 测试 `WebhookNotifyAdapterTest` | ✅ 新增，8 条 `@Test` | 非空 |
| 测试 `NotifyToolsTest` | ✅ 新增，8 条 `@Test` | 非空 |
| 配置「Profile 新增 `notify_channels` 字段」 | ⚠️ **按仲裁 1 判为旧口径**，替换为 SQLite 全局注册表 | `schema.sql:68` 的 `CREATE TABLE IF NOT EXISTS notify_channels`；`ProfileLoader.java:54` 的 `FORBIDDEN_KEYS = Set.of("skills", "notify_channels")` 已在代码层钉死「它不得进 frontmatter」 |

---

## 4. 证据四：前序节全部测试回归绿

`./mvnw -o clean verify` → **BUILD SUCCESS**，87 条测试全绿。

| 模块 | 条数 | 测试类明细 |
|---|---|---|
| `oryxos-storage` | 9 | **`SessionRepositoryTest` 4 ✅**（`schema.sql` 追加未影响既有三表——脚本是逐条 `CREATE TABLE IF NOT EXISTS`）、`LlmCallRepositoryTest` 2、`NotifyChannelRepositoryTest` 3 |
| `oryxos-core` | 45 | `ReActLoopTest` 7 / `PromptBuilderTest` 8 / `ToolExecutorTest` 5 / `AgentServiceTest` 5 / `SessionServiceTest` 11 / `ProfileLoaderTest` 5 / `ContextLoaderTest` 4 |
| `oryxos-provider` | 7 | `ToolSchemaAdapterTest` 3 / `ProviderServiceTest` 4 |
| `oryxos-tool` | 26 | 本节三个测试类 8 + 8 + 10 |

本节新增测试合计 **29 条**（storage 3 + tool 26）。

---

## 5. 证据五：H4 六条全局不变量逐条自查

| # | 不变量 | 结论 | 证据 |
|---|---|---|---|
| ① | 涉外 IO 首行过 `Sandbox.enforce` | ✅ | `NotifyTools.java:58-59` —— `enforce`(58) 在 `send`(59) 之前，顺序由 `InOrder` 钉死。**本节 `Sandbox` 实现未就位（`WhitelistSandbox` 归第 24 节），如实标注归第 24 节接线**：调用位已就位，只等注入 |
| ② | LLM 调用成败落 `llm_calls`、工具执行成败落 `tool_invocations` | ✅ | 本节不新增审计逻辑；`notify` 包内无任何 `tool_invocations` / `llm_calls` / `EntityManager` / JDBC 写入代码（grep 0 命中）。工具与其他工具同处第 17 节 `ToolExecutor` 入口之下 |
| ③ | grep 无明文 key | ✅ | 主代码 URL 字面量 **0** 个；`sk-` / `Bearer` / `password=` / `secret=` 字面量 **0** 命中；`WebhookNotifyAdapter` 全程不打日志（url 含 token），报错只带渠道名不带地址 |
| ④ | `session_id` 只在 `SessionManager` 内拼接 | ✅ | `notify` 包内 `session_id` / `sessionId` grep 0 命中——本节不接触 session 身份 |
| ⑤ | 无 Reactor / `CompletableFuture` / 自建线程池 | ✅ | grep 0 命中 |
| ⑥ | 无 Spring AI 自动工具执行路径 | ✅ | grep 0 命中 `ChatClient` / `ChatModel` / `ToolCallingManager` / `.tools(`；本节用到的 Spring AI 面**只有** `org.springframework.ai.tool.annotation.Tool` 一个 import（惰性注解，注册机制归第 20 节） |

### T014 契约核对（US3 场景 1 / 2，机器可判）

| 判据 | 结果 |
|---|---|
| ① 中立接口签名无某一档实现特有的词（对 `NotifyChannelAdapter` / `NotifyTarget`） | ✅ 0 命中 |
| ② `NotifyTools` 代码行不依赖具体实现 | ✅ 0 命中 |

**两处判据均需先排除注释**（`grep -vE '^[[:space:]]*(\*\|/\*\|//)'`）：两份契约的 javadoc 里合法出现取值示例「webhook / email」与实现名「WebhookNotifyAdapter」；`NotifyTools` 的 javadoc 第 17 行合法提到 `{@code ProfileContext}`，那句正是在解释**为什么不用它**。FR-001 / FR-015 约束的是**签名**，不是散文。口径已同步进 [quickstart.md §3.4](quickstart.md)。

### T019 跨节挂账（US3 场景 4）

入站 `ChannelAdapter` 抽象**尚未交付**（全仓 grep 仅命中 `NotifyChannelAdapter` 的 javadoc 散文；`CliChannel` 是 CLI 渠道直连实现、非抽象类型），**完整判定挂到入站抽象落地的那一节**。本节可判定部分已验：`NotifyChannelAdapter` 不与任何既有类型合并或互相继承。已留痕于 [tasks.md](tasks.md) T013 与 `CLAUDE.md`「当前状态」。

---

## 6. 证据六：变更总结

### 6.1 改动点

**`oryxos-storage`**

- `entity/NotifyChannelEntity.java`（新增 99 行）—— `notify_channels` 表实体；全 `String` 字段（不认识 core/tool 类型，否则成环）、无 `@GeneratedValue`（`name` 是业务键）、`config_json` 保持原始串不做 `@Convert`。
- `repository/NotifyChannelRepository.java`（新增 26 行）—— 只读接缝；新增派生查询 `findFirstByOrderByNameAsc()` 下推成 `ORDER BY name ASC LIMIT 1`。
- `resources/db/schema.sql`（**修改 +15 行**）—— 追加 `notify_channels` 建表片段；注释无分号（脚本按 `;` 切分逐条执行）。
- `test/.../NotifyChannelRepositoryTest.java`（新增 187 行）—— 建表脚本 + 存取 + 默认渠道确定性。

**`oryxos-tool`**

- `notify/WebhookNotifyAdapter.java`（新增 87 行）—— `RestClient` + `JdkClientHttpRequestFactory`；连接/读取各 10 秒、`Redirect.NEVER`、不重试、不打日志。
- `notify/NotifyChannelRegistry.java`（新增 27 行）—— 只读接缝（单个 `resolve` 方法，无 save / delete / list）。
- `notify/JpaNotifyChannelRegistry.java`（新增 107 行）—— 行 → 通知目标映射，data-model §5 六步。
- `notify/NotifyTools.java`（新增 62 行）—— `@Tool` 通知工具；`enforce` 严格先于 `send`。
- `pom.xml`（**修改 +65 行**）—— 新增 `spring-web`、`oryxos-storage` 两条显式依赖边（既有传递边的显式化，发行物不新增工件），外加模块级 `dependencyManagement` 把 `kotlin-stdlib` 钉回 2.1.21。
- `test/.../notify/`（新增 3 个测试类，26 条测试）。

**仓库根**

- `CLAUDE.md`（修改）—— 当前状态推进到第 19 节，记下本节三处特征 + 跨节挂账。
- `specs/004-notify-outbound/`（新增 10 个文件）—— spec / plan / tasks / research / data-model / quickstart / contracts ×3 / checklists。

### 6.2 重点 review 清单（按风险排序）

1. **架构决策：接缝放在 `oryxos-tool` 而非给 `ProfileContext` 加方法** —— `NotifyChannelRegistry.java`。`NotifyTarget` 契约在 tool、`ProfileContext` 在 core，给 core 加 `resolveNotifyChannel` 就是 core 反向依赖能力模块。**验证**：`grep -n "oryxos-tool" oryxos-core/pom.xml` → 无输出。
2. **新增依赖边无环** —— `oryxos-tool/pom.xml:47` 新增 `oryxos-tool → oryxos-storage`。**验证**：`grep -nE "oryxos-core|oryxos-tool" oryxos-storage/pom.xml` → 无输出（storage 不依赖任何内部模块）。注意 `oryxos-core → oryxos-storage` 是第 17 节既定的共享基础设施边（core 写审计），**非本节引入**。
3. **★ 顺序不可颠倒：`enforce` 先于 `send`** —— `NotifyTools.java:58-59`。两条独立 `verify` 在顺序写反时**照样通过**，必须靠 `InOrder`。这是本节最容易被写错又最看不出来的地方。
4. **`kotlin-stdlib` 钉版是「看着冗余、删了构建会红」的配置** —— `oryxos-tool/pom.xml` 的模块级 `<dependencyManagement>`。Spring Boot 3.5.16 BOM 把 `kotlin.version` 钉在 1.9.25 并托管 `kotlin-stdlib`，会把 okhttp-jvm 5.5.0 声明的 2.1.21 降级到本机仓库**不存在**的版本 → 离线构建直接失败。必须落在 `dependencyManagement` 层（只写直接依赖会让依赖图里残留 1.9.25 节点、每次构建刷警告）。文件头有完整注释。
5. **`WebhookNotifyAdapter` 超时语义** —— `WebhookNotifyAdapter.java:40-48`。`JdkClientHttpRequest` 用 `ofInputStream` + `TimeoutHandler`，读取上限量的是**派发 → 响应头到达**；`toBodilessEntity()` 不读 body。故 `connectTimeout(10s)` + `readTimeout(10s)` ≈ 从派发起 10 秒有界，与 FR-021「连接 + 读取合计 10 秒」一致。测试必须用 `headersDelay` 而非 `bodyDelay`（用 `bodyDelay` 连拖都拖不住——实测请求 0.3 秒就返回）。
6. **静态门禁妥协点** —— **无**。本节未为过 P3C 改名或压制任何告警；唯一一条 PMD 违规是补 javadoc 修掉的。

### 6.3 文档口径修正（实现期发现）

`data-model.md §3` 初稿有两处与既有实体不一致，落地时对齐 house style 并回改了文档：

1. 五个字段**一律显式 `@Column`**（VARCHAR 给 `length`、TEXT 给 `columnDefinition`，`@Id` 也不例外）——原稿写的是「`type` / `url` / `description` 直接同名列、无需 `@Column`」。
2. **不实现 `equals` / `hashCode`** —— 原稿要求「按 `name` 实现」，但既有三个实体都没有，且本节无调用方（YAGNI，章程原则 4）。

### 6.4 如何验证

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64

# 1) 全量门禁（含 OWASP，需联网）—— 预期 BUILD SUCCESS
./mvnw clean verify

# 2) 离线全量回归（87 条测试，前序节契约证据）—— 预期 BUILD SUCCESS
./mvnw -o clean verify

# 3) 只跑本节测试类 —— 预期 26 + 3 全绿
./mvnw -o -pl oryxos-tool -am test \
  -Dtest='NotifyToolsTest,WebhookNotifyAdapterTest,JpaNotifyChannelRegistryTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -o -pl oryxos-storage -am test \
  -Dtest=NotifyChannelRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false

# 4) ★ 关键回归：顺序断言单独跑 —— 预期 1 条绿
./mvnw -o -pl oryxos-tool -am test -Dtest='NotifyToolsTest#enforceSandboxBeforeSending' \
  -Dsurefire.failIfNoSpecifiedTests=false

# 5) 依赖方向：core 不得依赖 tool —— 预期无输出
grep -n "oryxos-tool" oryxos-core/pom.xml

# 6) 依赖无环：storage 不得依赖 core/tool —— 预期无输出
grep -nE "oryxos-core|oryxos-tool" oryxos-storage/pom.xml

# 7) 建表脚本注释无分号 —— 预期 1
awk '/notify_channels/,/^\);/' oryxos-storage/src/main/resources/db/schema.sql | grep -c ";"
```

---

## 7. 剩余人工项（harness 判不了，等人工过）

1. **真实 webhook 端到端** —— 假 webhook 验的是协议，真 webhook 验的是配置。配好一个真实群 webhook 后，`notify` 真能把消息送进群。
   - ⚠️ **本节做不到，属预期**：`notify_channels` 表**运行期恒为空**（本节只读，写入路径归第 26 节），且工具**进不了容器**（零装配，Bean 声明归第 24 节）。故此刻真跑 `notify` 必然报「未配置任何通知渠道」——这是设计状态，不是缺陷。**端到端真跑要等第 24 节接线 + 第 26 节写入路径。**
2. **接口中立性思维练习** —— 换成企业微信官方 SDK 的实现，`NotifyChannelAdapter.send(NotifyTarget, String)` 签名需要改吗？答案应是不需要。（`NotifyToolsTest.acceptsAnotherAdapterImplementationWithoutAnyChange` 已用替身实现做了可判定部分）
3. **前两项已由 harness 覆盖**：渠道未配置报错、白名单先行、回归不破 —— `mvn test` 绿即打勾。

---

## 8. 备注

- ℹ️ `docs/class/`（2.3MB 课件目录）**已随仓库跟踪**——2026-10-14 由仓库所有者决定入库（本条原写「未入库，提交时不要 `git add`」，据此更正）。
- 本轮**未 commit / 未 push / 未跑 `package.sh`**——同步时机由用户决定。
