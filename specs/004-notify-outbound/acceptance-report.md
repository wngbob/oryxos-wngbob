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

1. **真实 webhook 端到端** —— 假 webhook 验的是协议，真 webhook 验的是配置。
   - ⚠️ **本节做不到，属预期**：`notify_channels` 表**运行期恒为空**（本节只读，写入路径归第 26 节），且工具**进不了容器**（零装配，Bean 声明归第 24 节）。故此刻真跑 `notify` 必然报「未配置任何通知渠道」——这是设计状态，不是缺陷。**端到端真跑要等第 24 节接线 + 第 26 节写入路径。**
   - ✅ **已人工实测（2026-10-14，真实飞书群机器人 webhook）**——结论**比原稿保守**。原稿写「配好一个真实群 webhook 后，`notify` 真能把消息送进群」，**只对通用 webhook 接收端（webhook.site / 自建端点）成立，对 IM 群机器人不成立**：
     - **实测（含对照组，结论已证死）**：
       | 报文 | 飞书响应 | 群里 |
       |---|---|---|
       | `{"content":"..."}`（适配器实际送的） | `HTTP 200` + `{"code":19002,"msg":"params error, msg_type need"}` | 无消息 |
       | `{"msg_type":"text","content":{"text":"..."}}`（飞书正确方言） | `HTTP 200` + `{"code":0,"msg":"success"}` | 出现消息 |

       地址、网络、key、机器人、群全部相同，**只有报文形状不同**——一失败一成功，故失败原因**就是报文形状**。且**两次都是 `HTTP 200`**：只看状态码的适配器对这两次投递会给出**完全相同**的判断「成功」。
     - **出口侧已排除**：JVM 出口实测通（JDK `HttpClient` 直连外网 `HTTP 200`，无需任何代理系统属性），故失败与网络无关。另注同一轮里 `curl` 曾被 shell 的 `https_proxy` 环境变量挡住（`HTTP 000`）而 JVM 不受影响——curl 与 JVM 代理来源不同，两者不可互相替代。
     - **根因**：IM 群机器人用 **HTTP 200 承载业务错误**（飞书 `code`、企微 `errcode`），而 `WebhookNotifyAdapter.send()` 走 `.retrieve().toBodilessEntity()`（`WebhookNotifyAdapter.java:69-75`）**只判 HTTP 状态码、不解析响应体** → 判成成功 → `NotifyTools.notify` 返回 `ToolResult.success("已推送")` → **Agent 以为发出去了，实际一条都没到**。失败信号藏在响应体里，通用的「2xx 即送达」口径看不见它。
     - **实现没有错**：通用报文 + 「2xx 即送达」**正是 FR-003「核心阶段只提供一档通用 webhook 实现、不逐家对接专用 API」明确要求的边界**；课件验收标准里那条「5xx 上抛」也是真过的（MockWebServer 真返回 5xx）。错的只是**原稿对「真实群 webhook」的措辞**——它把通用接收端与 IM 群机器人混为一谈。
     - **处置（软门禁 ③ 报批后定，2026-10-14）：记为已知边界，本节代码一行不改。** 备选 B（解析响应体识别 `errcode`/`code`）与 C（按 `NotifyTarget.channelType` 选报文方言）都会把「某一家方言」写进核心阶段，分别撞 FR-003 与 FR-001/FR-003，故不采纳。IM 群机器人方言适配作为**新增实现类**（如 `FeishuNotifyAdapter`）归扩展阶段，**`NotifyChannelAdapter` 接口与调用方零改动**——这正是「接口先行」预留的升级路径。挂账见 [tasks.md](tasks.md) Notes 与 `CLAUDE.md`「当前状态」。
     - **完整实测过程与可复现命令**见 [manual-test-guide.md](manual-test-guide.md) 附录 A.2。
2. **接口中立性思维练习** —— 换成企业微信官方 SDK 的实现，`NotifyChannelAdapter.send(NotifyTarget, String)` 签名需要改吗？答案应是不需要。（`NotifyToolsTest.acceptsAnotherAdapterImplementationWithoutAnyChange` 已用替身实现做了可判定部分）
3. **前两项已由 harness 覆盖**：渠道未配置报错、白名单先行、回归不破 —— `mvn test` 绿即打勾。

---

## 8. 备注

- ℹ️ `docs/class/`（2.3MB 课件目录）**已随仓库跟踪**——2026-10-14 由仓库所有者决定入库（本条原写「未入库，提交时不要 `git add`」，据此更正）。
- 本轮**未 commit / 未 push / 未跑 `package.sh`**——同步时机由用户决定。
- **2026-10-15 补强**见文末「交付后补强」一节（本节是 2026-10-14 交付时的原貌，未改动）。

---
---

# 交付后补强（2026-10-15）：飞书 / Lark 方言适配

> **本节是第 19 节交付之后的补强记录，不改动上文任何一条交付时的证据。** 上文是 2026-10-14 交付时的原貌（含当时「记为已知边界」的裁决），本文记 2026-10-15 的第二次交付。§7 第 1 条的结论**在当时是对的**，本补强是把它**兑现**，不是推翻它——被修订的只有 FR-003 里「唯一」二字。

| 项 | 值 |
| --- | --- |
| 触发 | 用户指令「19 节中企业微信、飞书、Lark、钉钉 channel 都实现了吗？如果没有你可以实现吗？帮我评估一下」 |
| 落位 | **折进第 19 节**（不新开 spec 目录），随第 19 节一次性提交 |
| 分支 | `class-19`（未 commit / 未 push） |
| 范围（用户裁决） | 只做**飞书 + Lark**（同一套协议，同一个类）；配置侧只写**手工 INSERT 向导**（写入路径仍归第 26 节）；**做**签名校验；`type` 未知值**报错** |
| 测试 | **238 → 273**（`oryxos-tool` 153 → 187，`oryxos-cli` 24 → 25，其余模块不变）；**新增 35 条** |
| 结论 | **`mvn clean verify` 全绿（10 模块）；2026-10-14 实测抓到的「`notify` 假报已推送」缺陷已有端到端回归钉死** |

## 补强·一、为什么做：把 §7 第 1 条的裁决兑现

§7 第 1 条记录了一次人工实测：通用 webhook 报文 `{"content":"..."}` 不被 IM 群机器人接受，飞书回 **`HTTP 200` + `code:19002`**，而 `WebhookNotifyAdapter` 只判状态码 → `NotifyTools.notify` 返回 `ToolResult.success("已推送")`，**群里一条消息都没有，失败连 `tool_invocations` 都进不去**。

当时的裁决（选项 A）是「记为已知边界、代码一行不改」，理由是：备选 B（解析响应体识别业务码）与 C（按 `channelType` 选报文方言）都会把「某一家方言」写进**核心阶段**，分别撞 FR-003 与 FR-001/FR-003。

**本次的处置是选项 B + C 合体，但不撞边界**——方言知识与业务码判据全部收在**某一家自己的实现类**（`FeishuNotifyAdapter`）内部，通用档与接口**一行未改**。这不是绕开原裁决，而是原裁决自己预留的路径：§7 第 1 条结尾原话就是「IM 群机器人方言适配作为**新增实现类**（如 `FeishuNotifyAdapter`）归扩展阶段，**`NotifyChannelAdapter` 接口与调用方零改动**——这正是『接口先行』预留的升级路径」。本次只是把这条路径**提前**走了一遍，用的还是它点名的那个类名。

**因此 FR-003 被修订的只有「唯一」二字**：对通用档（不逐家对接专用 API、`{"content":...}`、「2xx 即送达」）逐条继续成立。

## 补强·二、软门禁报备（本次触发三条，逐条留痕）

| 门禁 | 触发点 | 处置 |
| --- | --- | --- |
| ① 新增对外概念 | 新实现类 `FeishuNotifyAdapter` / `RoutingNotifyChannelAdapter`；`type` 新增取值 `feishu` / `lark`；`config_json` 新增可选键 `secret` | **已报批**（AskUserQuestion ×2，2026-10-15）：范围 = 飞书 + Lark thin slice；签名校验 = 做；未知 `type` = 报错 |
| ② 改已定字面量 | FR-003「核心阶段 MUST 只提供一档通用 webhook 实现」的**「唯一」二字** | **已报批**。经用户裁决修订，`spec.md` FR-003 下挂**带日期的修订注**，并说明原裁决的推翻理由与边界（通用档继续成立）。**MUST NOT 逐家对接专用 API** 这条一个字未动——飞书档不需要 AccessToken，只需 webhook 地址 + 可选签名 |
| ② 改前序交付物行为 | `WebhookNotifyAdapter` 的**类 javadoc**（第 19 节已交付并已验收的类） | **已报批**。改动**只有注释**：`git diff` 显示 4 处 `+` / 2 处 `-`，全在 `/** ... */` 内（首句「核心阶段唯一实现」→「通用档实现」+ 一段指向同包方言档的说明）。**代码零改动**，行为零变化。走「契约文档 + 验收报告双处留痕」 |

**未触发**：③ 跨节交付物（`NotifyTools` / `JpaNotifyChannelRegistry` / 建表脚本 / 表结构**全部零改动**）；④ 冻结的接口文件（`NotifyChannelAdapter.java` / `NotifyTarget.java` 的 `git status` **为空**，契约 §1.4 满足）。

## 补强·三、证据一：全量门禁 `mvn clean verify` 全绿

```bash
./mvnw clean verify -Ddependency-check.skip=true
```

```text
[INFO] Tests run: 187, Failures: 0, Errors: 0, Skipped: 0     ← oryxos-tool
[INFO] Tests run:  25, Failures: 0, Errors: 0, Skipped: 0     ← oryxos-cli
...
[INFO] BUILD SUCCESS                                          ← 10 模块
```

**273 条测试**（238 基线 + 35 新增），Failures / Errors 均为 0。Spotless / P3C（PMD 6.55）/ Checkstyle / SpotBugs+FindSecBugs 全过。

### ★ 本轮真实抓到一个红（已修）

**首轮 `mvn clean verify` 是 FAILURE，不是一次过**——如实记录：

```text
[ERROR] The following files had format violations:
[ERROR]     src/main/java/io/oryxlabs/oryxos/tool/notify/FeishuNotifyAdapter.java
[ERROR]     src/test/java/io/oryxlabs/oryxos/tool/notify/FeishuNotifyAdapterTest.java
[ERROR]     src/test/java/io/oryxlabs/oryxos/tool/notify/FeishuNotifyToolTest.java
[ERROR]     src/test/java/io/oryxlabs/oryxos/tool/notify/RoutingNotifyChannelAdapterTest.java
[ERROR] Run 'mvn spotless:apply' to fix these violations.
[ERROR] BUILD FAILURE
```

**性质**：纯格式（google-java-format 折行），**不是逻辑缺陷**——同一批测试在 `mvn test` 下已先跑绿。**修法**：按 CLAUDE.md 既有做法跑 `./mvnw spotless:apply` 后复跑，BUILD SUCCESS。

> **一处假信号值得记下**：首轮那次 verify 是**后台**跑的，包装命令是 `... > log 2>&1; echo "EXIT=$?"; grep ...`——任务通知报的 `exit code 0` 是**管道最后一条 `grep` 的退出码**，不是 `mvn` 的。差点据此误判为通过。**读 Maven 结果必须看 `BUILD SUCCESS`/`BUILD FAILURE` 字面，不能看包装命令的退出码。**

### OWASP 如实交代

本次 `-Ddependency-check.skip=true`。**依据**：本次改动**未新增任何依赖**（签名用 JDK 内置 `javax.crypto` + `java.util.Base64`；HTTP 用已在 classpath 上的 `spring-web`；测试用已在 test scope 的 `mockwebserver3:5.5.0`）。第 19 节交付时已跑过完整 OWASP。**这不是省略门禁，是依据「依赖未变」的判定跳过**——若本次引入了新依赖，则必须补跑。

## 补强·四、证据二：新增测试逐条对号（35 条）

| 测试类 | 条数 | 钉死什么 |
| --- | --- | --- |
| `FeishuNotifyAdapterTest` | **20** | 方言档的全部契约（报文形状、内容保真、fail-closed 判据、签名、超时、重定向、凭证不外泄） |
| `RoutingNotifyChannelAdapterTest` | **10** | 路由接缝的分派与错误口径 |
| `FeishuNotifyToolTest` | **4** | ★ **端到端回归**——真适配器 + 真 `NotifyTools` + 本地假 webhook |
| `NotifyAssemblyTest` | **1** | 装配后 `NotifyChannelAdapter` **恰好一个** Bean 且是路由实现 |

**逐条清单**：

**`FeishuNotifyAdapterTest`（20 条）**

1. 请求体形状逐字：`msg_type == "text"`、`content.text` 等于原串、恰好 2 个字段、`Content-Type` 为 JSON
2. 中文 / 换行 / 引号 / 反斜杠**逐字保真**（不被转义或截断）
3. 地址取自 `NotifyTarget.config().get("url")`
4. `code:0` → 正常返回
5. ★ **`HTTP 200` + `code:19002` → 抛 `IllegalStateException`**，消息含 `19002`，且 `getRequestCount() == 1`（不重试）
6. 异常消息含对端 `msg`
7. `{"data":{}}`（两个业务码字段都没有）→ 抛 —— **钉死 `path("code").asInt()` 陷阱**（Jackson 在字段缺失时也返回 0）
8. `{"StatusCode":0, ..., "code":19002}` → 抛（**`code` 优先于 `StatusCode`**）
9. 仅 `{"StatusCode":0, ...}`（无 `code`）→ 接受（兼容旧形态）
10. 业务码非整数（`"0"` / `0.5`）→ 抛
11. 空 body → 抛
12. 纯空白 body → 抛（Jackson 返回 `MissingNode` 而**不抛异常**，故必须显式判 `isObject()`）
13. 非 JSON body → 抛，且消息**不含 body 片段**（断言不含 `html` 与 token）
14. 配 `secret` → body 含 `timestamp`（固定时钟下的期望值）与 `sign`（**离线用 Python `hmac` 模块算出的黄金值**，非自证），解码后 32 字节
15. 未配 `secret` → body **不含** `timestamp`/`sign`（不是出现为 `null`）
16. `secret` 为纯空白 → 同「未配」处理
17. 5xx → 抛，不重试
18. 3xx → 抛且**不跟随**（第二个假服务收到 **0** 次请求）
19. `url` 缺失 / 空白 → `IllegalArgumentException` 且**零请求**
20. 超时两条：响应头超时 → `ResourceAccessException`；**响应体读取超时 → `RestClientException`（含 "extracting response"）** —— 见下

**关于第 20 条（一次被实测纠正的预期）**：本类必须读 body 才能看到业务码，故「给了响应头却拖着不给 body」也会被上限兜住——这与通用档（`toBodilessEntity()` 不读 body）**不同**。测试初版断言 `ResourceAccessException`，**实际跑出的是** `RestClientException: Error while extracting response for type [byte[]]`（耗时 0.315s ≈ 设定的 200ms 上限，证明确实是读取超时）。**改了断言、并把这个实测结论写进 `FeishuNotifyAdapter` 的 javadoc**——不是把测试改成迎合实现，是先验证「超时确实生效」再据实记录异常类型。两条路径都是 `RestClientException` 的实例，都原样上抛到 `ToolExecutor` 的失败审计路径，对调用方无差别。

**`RoutingNotifyChannelAdapterTest`（10 条）**

1. `feishu` / `lark` 都落到方言档，通用档**一次都不被调用**
2. `webhook` 落到通用档
3. `"  Feishu  "` / `"LARK"` 归一后命中（`trim()` + `toLowerCase(Locale.ROOT)`）
4. 未知 `type` 抛，消息含**排序后**的 `"feishu, lark, webhook"`（不随 `Map` 迭代顺序抖动）
5. 未知 `type` **零委派**（拼错不能悄悄退回某一档）
6. `null` / 纯空白 `type` → `IllegalArgumentException`（含「type 为空」），**不是 NPE**
7. 映射表为空 → 任何 `type` 都抛
8. 每次 `send` **恰好委派一次**
9. 构造**防御性拷贝**（事后改传入的 `Map` 不影响路由）
10. **源码不含任何厂商字面量**（读源文件断言无带引号的 `feishu`/`lark`/`webhook`/`wecom`/`dingtalk`）

**`FeishuNotifyToolTest`（4 条，★ 本节补强的核心）**

1. ★ 对端回 `200 + code:19002` → `notify(...)` **抛异常、MUST NOT 返回 `ToolResult.success`**，`getRequestCount() == 1`
2. `code:0` → `result.success()` 为 true 且 `content()` 为「已推送」
3. ★ 经路由发出的报文**真的是飞书方言**（`"msg_type":"text"` + `"text":"今天晴，26 度"`）
4. ★ `type` 拼错 → 抛 `IllegalArgumentException` 且**零请求**

> **为什么必须有第 1 条**：既有的 `NotifyToolsTest` 注入的是 **mock** 适配器，它只能证「工具把活交给了适配器」，**证不了「适配器有没有把 HTTP 200 + 业务错误码误判成成功」**——而那正是原始缺陷。这条链路上少任何一环，缺陷都能藏住。

**`NotifyAssemblyTest`（1 条）**

断言容器里 `NotifyChannelAdapter` 类型的 Bean **恰好一个**，且 `isInstanceOf(RoutingNotifyChannelAdapter.class)`。

> **判据是「恰好一个」而不是「存在一个是」**：后者在「两档都被声明成 `@Bean`」时照样绿，而那正是最危险的坏法（`NotifyTools` 注入当场变成 `NoUniqueBeanDefinitionException`）。**且必须起真上下文**——手工 `new` 一个路由实例来断言等于把这条绕过去。

## 补强·五、证据三：交付物与改动清单

**新增（6 个文件）**

| 文件 | 行数 | 说明 |
| --- | --- | --- |
| `oryxos-tool/.../notify/FeishuNotifyAdapter.java` | 239 | 方言档实现（飞书 + Lark） |
| `oryxos-tool/.../notify/RoutingNotifyChannelAdapter.java` | 63 | 路由接缝（厂商无关） |
| `oryxos-tool/.../notify/FeishuNotifyAdapterTest.java` | 385 | 20 条 |
| `oryxos-tool/.../notify/RoutingNotifyChannelAdapterTest.java` | 169 | 10 条 |
| `oryxos-tool/.../notify/FeishuNotifyToolTest.java` | 126 | 4 条（★ 端到端） |
| `oryxos-cli/.../cli/NotifyAssemblyTest.java` | 36 | 1 条（起真上下文） |

**修改（2 个代码文件）**

| 文件 | `+a/−d` | 性质 |
| --- | --- | --- |
| `oryxos-cli/.../cli/CliAssemblyConfiguration.java` | +27 / −6 | `notifyChannelAdapter()` 改为就地构造映射表并返回路由实例；**`FeishuNotifyAdapter` 不单独声明 Bean**（否则注入歧义）；同步更新 javadoc |
| `oryxos-tool/.../notify/WebhookNotifyAdapter.java` | +6 / −2 | **仅 javadoc**，代码零改动 |

**零改动（硬证据）**

```bash
git status --short -- .../notify/NotifyChannelAdapter.java .../notify/NotifyTarget.java
# 无输出 → 两个接口文件逐字未变（契约 §1.4 / SC-013）
```

`NotifyTools` / `JpaNotifyChannelRegistry` / `NotifyChannelRegistry` / 建表脚本 / 表结构 / `oryxos-core` / `oryxos-storage` / `oryxos-boot`：**全部零改动**。

## 补强·六、证据四：前序节回归

`mvn clean verify` 覆盖全部 10 模块，238 条基线测试**一条不红**（273 − 35 = 238 ✓）。特别确认：

- `WebhookNotifyAdapterTest`（8 条）全绿——通用档行为未变
- `NotifyToolsTest`（8 条）/ `JpaNotifyChannelRegistryTest`（10 条）全绿——调用方与注册表未变
- `ToolAssemblyTest`（5 条）全绿——装配改动未影响工具侧

## 补强·七、证据五：契约与文档同步（8 处）

| 文档 | 改了什么 |
| --- | --- |
| `spec.md` | FR-003 加**带日期的修订注**；新增 **FR-022~FR-027**（方言档 / fail-closed 判据 / 签名可选 / 路由接缝 / 未知 type 报错 / 不动接口）；新增 **SC-011~SC-013**；**回填 4 处失效表述**——§范围说明的「不逐家对接」边界、US3 描述、Edge Cases 的「2xx 但响应体写着业务错误」、Assumptions 的「核心阶段只有 webhook 一档实现」与「`type` 列本节不做取值校验…写歪了会在对端以非 2xx 暴露」 |
| `contracts/notify-outbound.md` | §2 标题「核心阶段唯一实现」→「通用档实现」+ 修订注；**澄清 §1.1 的判定口径**（原写「对源码 grep，命中数 = 0」——该口径本身有歧义且**实际不成立**，因为接口 javadoc 里为说明实现去向提到了 `WebhookNotifyAdapter`；改为「方法名 / 参数 / 返回类型 / record 组件中不出现」）；新增 **§2.4**（方言档契约 13 条）与 **§2.5**（路由契约 8 条 + 两条装配硬约束）；§2.2 与 §2.2.10 保持不动 |
| `tasks.md` | 新增 **Phase 7**（T020~T029）；Notes 里挂账② 标记为「部分兑现（飞书 / Lark 已清，企微 / 钉钉仍挂）」 |
| `manual-test-guide.md` | 新增**附页**——真飞书群验证向导（手工 INSERT + 7 个 Step，**Step 6 是核心验收点**）+ §10 状态更新 |
| `acceptance-report.md` | 本节 |
| `CLAUDE.md` | 见下条 |

**`CLAUDE.md` 的三处更新**：

1. 「当前状态」挂账② —— 从「未清」改为「**飞书 / Lark 已清，企微 / 钉钉仍挂**」，并记下路由接缝已就位（加企微/钉钉 = 一行映射 + 一个类）
2. 「Tool 体系」的 `notify` 描述 —— 从「一档通用 webhook」改为「通用档 + 方言档 + 路由接缝」
3. `notify_channels` 的 `type` 列取值口径 —— 增加 `feishu` / `lark`，并说明**未知值在路由处报错、不再到达对端**（原表述「写歪了会在对端以非 2xx 暴露」已失效）

## 补强·八、重点 review 清单（按风险排序）

| # | 风险点 | 为什么危险 | 怎么验 |
| --- | --- | --- | --- |
| R1 | **`path("code").asInt() != 0` 写法** | Jackson 在字段**缺失**时 `asInt()` 也返回 `0` → 把「缺失」判成**成功** → 正好把本补强要消灭的假成功**重新引入**，且形态更隐蔽 | `FeishuNotifyAdapterTest` 的 `{"data":{}}` 用例；源码人读确认用 `has()` + `isIntegralNumber()` |
| R2 | **`FeishuNotifyAdapter` 被声明成独立 `@Bean`** | 容器里出现两个 `NotifyChannelAdapter` 候选 → `NotifyTools` 注入当场 `NoUniqueBeanDefinitionException`，且**手工 new 一个路由来测会绕过它** | `NotifyAssemblyTest`（起**真** Spring 上下文，断言**恰好一个** Bean） |
| R3 | **映射表写成 `@Bean` 方法参数** | 那是既有 `providerMap` 用 `@Qualifier` 绕开的坑；按类型收集会多出候选 Bean | 同上；`CliAssemblyConfiguration` javadoc 已写明 |
| R4 | **厂商字面量漏进路由类** | 路由类一旦出现 `"feishu"`，方言知识就落错了层，加第三家时改的是它而不是装配处 | `RoutingNotifyChannelAdapterTest.sourceHasNoVendorStringLiterals`（读源文件断言） |
| R5 | **异常消息或日志带出 `url` / `secret`** | 两者都是凭证（`url` 里含 token） | `FeishuNotifyAdapterTest` 断言消息不含地址与 token；非 JSON 路径**刻意不挂 cause**（Jackson 解析异常的 message 会带 body 片段，网关错误页常回显带 token 的 URL）；类内**不打任何日志** |
| R6 | **未知 `type` 静默退回通用档** | 会把「type 拼错」变成「用错的方言发出去」——而错的方言在飞书那里正是 `HTTP 200` + 19002，即本补强要消灭的形态 | `RoutingNotifyChannelAdapterTest` 的零委派用例 + `FeishuNotifyToolTest` 的零请求用例 |
| R7 | **`Redirect.NEVER` 被漏掉** | 白名单校验在调用方（`NotifyTools`），本类看不到沙箱；跟随重定向 = 请求可能落到白名单之外 | `FeishuNotifyAdapterTest` 的 3xx 用例（第二个假服务收 **0** 次请求）——独立钉死，不复用通用档的测试 |
| R8 | **超时语义变化被忽略** | 本类真读 body，超时覆盖面比通用档宽，异常类型也不同 | 两条超时用例分别断言 `ResourceAccessException` 与 `RestClientException`；结论已写进 javadoc |

## 补强·九、如何验证

```bash
cd /home/wngbob/oryxos-wngbob

# 1) 只跑本次新增的 35 条（单模块命令一律带 -am，否则会静默取 ~/.m2 旧字节码）
./mvnw -am -pl oryxos-tool test -Dtest='FeishuNotifyAdapterTest,RoutingNotifyChannelAdapterTest,FeishuNotifyToolTest'
./mvnw -am -pl oryxos-cli  test -Dtest='NotifyAssemblyTest'
# 预期：20 + 10 + 4 = 34 条、1 条，全绿

# 2) 全量门禁（必须 BUILD SUCCESS 字面，别看包装命令的退出码）
./mvnw clean verify -Ddependency-check.skip=true

# 3) 接口文件零改动（预期无输出）
git status --short -- oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/notify/NotifyChannelAdapter.java \
                       oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/notify/NotifyTarget.java

# 4) 通用档代码零改动（预期只见 /** ... */ 内的注释行）
git diff -- oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/notify/WebhookNotifyAdapter.java
```

**真渠道人工验收**：见 [manual-test-guide.md](manual-test-guide.md) **正文 Step 5~8**——用 `notify-channel add` 配一行渠道（`type=feishu`，开签名校验时带 `--config 'secret=${FEISHU_BOT_SECRET}'`，凭证走 `${ENV_VAR}` 占位、不落库）→ `oryxos chat` 里让 Agent 调 `notify` → 确认消息**真的出现在飞书群**（**Step 6**）；再注入故障（打开关键词校验 / 另建一个错 `secret` 的渠道 / 把 `type` 拼错），确认**报错并落 `tool_invocations`**，而不是假报「已推送」（**Step 7**）；`type` 拼错另在 **Step 8** 单独验「路由处拦下、一次请求都不发」。

> **2026-10-15 修订注**：本段原写「手工 `INSERT` 一行渠道（Step 5）」与「反向验在 Step 6 / Step 7」——那是补强① 当时的形态。补强② 把 Step 5 换成 CLI 三条命令、反向验收挪到 Step 7，Step 8 单列 `type` 拼错。原句保留在此以留痕。

> **⚠️ 这次人工验收 MUST NOT 被当成「沙箱已就位」的证据**：第 20~23 节挂的是临时实现 `PermissiveSandbox`（**不拦任何东西**），`open.feishu.cn` 的域名白名单**归第 24 节**。向导已写明这一点（正文「前置事实 ④」与附录 B）。
>
> **2026-10-17 修订注（沙箱窗口期已关闭）**：上面这条**已失效**。第 24 节已交付 `WhitelistSandbox`，`open.feishu.cn` / `open.larksuite.com` / `qyapi.weixin.qq.com` 三个域名**已进随包默认** `http.allowed_domains`；`PermissiveSandbox` 已按裁决 3 **整类删除**。故**这次人工验收现在可以**当作「沙箱已就位」的证据。向导对应位置（正文「前置事实 ④」与附录 B）已各挂同日修订注。**原文保留以留痕**。

## 补强·十、明确不做（边界）

- **企业微信 / 钉钉适配器**——协议已知（企微同类失败走 `HTTP 200` + `errcode:40008`），属纯复制方言的活。路由接缝已立起来，加它们 = 往 `CliAssemblyConfiguration` 的映射表加一行 + 加一个实现类，**接口与调用方零改动**
- **渠道写入路径 / CRUD / `${ENV_VAR}` 占位解析**——**仍归第 26 节**，故附页演示靠手工 INSERT
  > **2026-10-15 补强② 修订注**：本条**已失效**。写入面由 CLI `notify-channel add|list|remove` 三条轻命令补上，`${ENV_VAR}` 兑现为**读时解析**；仍归第 26 节的只有 **REST CRUD 端点与管理台**。原句保留以留痕（详见补强② 各节）。
- **`open.feishu.cn` / `open.larksuite.com` 的沙箱域名白名单**——归第 24 节
- **富文本卡片等结构化消息**——走 Plugin Tool 方式二（MCP）
- **重试 / 退避 / 投递回执**——不做
- **`WebhookNotifyAdapter` 的行为与代码**——一行未改（仅 javadoc）

## 补强·十一、遗留与下一步

1. **企业微信 / 钉钉**：本次未做。挂账**仍在**，但已从「需要设计」降为「需要照抄」——路由接缝与 fail-closed 判据的形态都已确定，复制时只需换报文形状与业务码字段名。
2. **~~真渠道人工验收未跑~~**：附页的 Step 0~9 需真实飞书群机器人，**本次未执行**（无可用 webhook）。（该向导 2026-10-15 重新生成，**现为 `manual-test-guide.md` 正文**，Step 编号未变——见补强②·十三。）**Step 6（正向）/ Step 7（反向）是本补强的核心断言**——反向那次若报「已推送」，说明补强失败。
   > **2026-10-15 修订注**：本条**已失效**。用户已按重新生成后的向导 Step 0~9 **全程人工跑过一遍，未发现问题**（见补强②·十四）。原句保留以留痕。
3. **`docs/class/`**：仍随仓库跟踪（2026-10-14 由仓库所有者决定入库）。
4. 本轮**未 commit / 未 push / 未跑 `package.sh`**——同步时机由用户决定。

## 补强·十二、顺带修掉的 CLI 日志分流缺陷（2026-10-15，软门禁 ② 已报批）

**怎么发现的**：写附页向导时 Step 4 写的是 `tool list | head -20`，**实测一行工具都打不出来**。查下去发现是第 20 节的一个未记录副作用，不是本补强引入的。

**根因（三层，逐层实测）**：

| # | 事实 | 证据 |
| --- | --- | --- |
| ① | 日志与命令输出**同一条流** | logback `ConsoleAppender` 默认 target = `System.out`，`ToolListCommand.print()` 也是 `System.out.println`。实测 `tool list 2>/dev/null` 仍出日志、`2>&1 1>/dev/null` **为空** → 全在 stdout |
| ② | 没有任何级别/流配置 | 全仓 grep `logging.level` / `logging:` **零命中**；`logback-spring.xml` 只有 `!prod` / `prod` 两个 profile，root 挂 INFO |
| ③ | 是第 20 节改出来的 | 第 18 节 `tool list` 是**轻命令**（不起上下文）→ 干净；第 20 节改成**重命令**（列 MCP 工具必须连 server）→ 整套启动日志跟着上来。对照实测：轻命令 `profile list` 输出**零日志** |

**量化**：`tool list` 共 46 行，表格从第 **25** 行才开始（前 **24** 行是启动日志），表格后还有 3 行上下文关闭日志。

**改动**（一行 + 一段说明注释）：[`oryxos-boot/src/main/resources/logback-spring.xml`](../../oryxos-boot/src/main/resources/logback-spring.xml) 的 `!prod` 档 `CONSOLE` appender 加 `<target>System.err</target>`，按 Unix 惯例分流——**数据走 stdout、诊断走 stderr**。

**为什么只改 `!prod` 档**：`prod` 的 `JSON_CONSOLE` 必须留在 stdout，容器日志采集侧读的是 stdout。dev 档才是「人在终端前、要往管道里接命令输出」的场景。

**修后实测**（同一台机、同一 jar，`/tmp/oryxos-feishu-check`）：

```bash
java -jar "$ORYXOS_JAR" tool list 2>/dev/null | wc -l    # → 19，正好是表格本身
java -jar "$ORYXOS_JAR" tool list 2>&1 1>/dev/null | grep -c PermissiveSandbox   # → 1，故意打的 WARN 还在
java -jar "$ORYXOS_JAR" profile list                     # 轻命令，仍干净
```

> **2026-10-17 修订注（上面这个代码块里的第二条命令已失效，且是「命令与预期双双失效」）**：`PermissiveSandbox` 已按第 24 节裁决 3 **整类删除**，故 `grep -c PermissiveSandbox` 现在**恒得 `0`**，不再是 `# → 1`。两个连带的坑：① 该命令的**退出码也变成 `1`**（`grep -c` 无命中即非零），在 `set -e` 的脚本里会**直接中断**，不再像原来那样安静地打印一个数——**2026-10-17 实测确认：输出 `0`、退出码 `1`**；② 它旁边的 `wc -l # → 19` 数的是 `tool list` 的表格行数，会随内置工具数变化（第 22 节把内置工具从 13 个加到 15 个）——**2026-10-17 实测为 `21`**，**那属于另一笔账，本次未回修**，照抄前先以实际输出为准。**原文保留以留痕**；这条命令**现在不要再照抄**。

**保留项**：`PermissiveSandbox` 那条 WARN **仍在 stderr 上、未被静音**——它是「沙箱窗口期」的缓解措施 ②（见 CLAUDE.md），静音它等于拆掉一条留痕。

> **2026-10-17 修订注**：本条**已失效**——那条 WARN 自第 24 节起已不存在，故「保留项」无从谈起。**要保留的那条契约换了主语**：「**WARN 级诊断走 stderr、MUST NOT 被静音**」这条**通用**日志契约**仍然成立**（`LogStreamSeparationTest` 仍在守它，只是断言文案已从「PermissiveSandbox 那条 WARN」改为泛化的「WARN 级诊断」）。**原文保留以留痕**。

**影响面**：**所有重命令**（`tool list` / `chat` / `serve` / `gateway`）一并受益，不是 `tool list` 独有。`chat` 的终端首屏也不再被 24 行 Hibernate INFO 占满。

**为什么测试抓不到**：`ToolListCommandTest` 用**反射直接调私有的 `print`**，根本不进 Spring 上下文（它这么写是有意的，见该测试类 javadoc），所以 harness 全绿而终端噪音一直在。**这条缺陷只有肉眼看终端才现形。**

**门禁复核**（改完重跑）：

```
$ ./mvnw clean verify -Ddependency-check.skip=true
[INFO] BUILD SUCCESS
总计 Tests run=277  Failures=0  Errors=0  Skipped=0
```

**273 → 277**，多出来的 4 条是下面这个守卫测试。OWASP 仍按「依赖未变」跳过（本次一行 Java 未改、一个依赖未加），依据同 [补强·三 · OWASP 如实交代](#owasp-如实交代)。

**同步的文档**：附页 Step 4（改写为「已分流」+ 保留修前样貌）、附页「四个最容易踩的坑」第 4 条、`CLAUDE.md` 两处（「工程地基」加「这行不要删」子条 + 「命令行工具」`tool list` 代价行）。

> **2026-10-15 重新生成后的落点**：附页已并入正文——Step 4 **仍是 Step 4**；「启动日志当成报错」那条现为**「最容易踩的六个坑」第 5 条**（坑表另新增两条：第 2 条 `dist/oryxos.jar`、第 6 条凭证泄漏）。

**守卫测试**：[`LogStreamSeparationTest`](../../oryxos-cli/src/test/java/io/oryxlabs/oryxos/cli/LogStreamSeparationTest.java)（`oryxos-cli`，4 条）。读 `oryxos-boot` 那份 `logback-spring.xml` 的**源文件**，手法照既有 `ToolOnboardingCostTest`。

| 断言 | 护的是什么 |
| --- | --- |
| dev 档 CONSOLE appender 含 `<target>System.err</target>` | 本次修复的实体本身 |
| prod 档**不含** `System.err` | 防止有人「顺手统一」，把 JSON appender 也改到 stderr（容器采集侧读 stdout） |
| 两个 profile 块真的切出来了 | 正向对照——切分失败时前两条会以错误的方式成立（见下） |
| dev 档根级别不是 `ERROR`/`OFF` | `PermissiveSandbox` 那条**故意**打的 WARN MUST NOT 被静音 |

> **2026-10-17 修订注**：表格最后一行的**断言本身仍然成立**（`LogStreamSeparationTest` 至今仍断言「dev 档根级别不是 `ERROR`/`OFF`」，测试类仍在跑），但它的**理由已换**——`PermissiveSandbox` 那条 WARN 自第 24 节起已不存在，该断言的文案与 `@DisplayName` 已从「PermissiveSandbox 那条故意打的 WARN」改为泛化的「**WARN 级诊断 MUST NOT 被静音**」。护的东西没变，护它的那句话变了。**原文保留以留痕**。

**为什么断言的是配置文本而不是运行期行为**：能验行为的地方只有 `oryxos-boot` 自己的测试（只有它的 classpath 上有这份 `logback-spring.xml`；`oryxos-cli` 的测试 classpath 上**没有**，Spring Boot 会退回默认配置，那验的是**另一套**配置）。而 `oryxos-boot` 目前**零测试基建**（无 test 源根、无 test 依赖），为一个四条断言的守点给它新开 test 源根 + 加测试依赖，比被守护的改动本身还重——故落在已有测试基建、且正是这条契约受害方的 `oryxos-cli`。

**★ 变异测试（守卫测试有没有价值的唯一判据）**：逐条把被守护的东西改坏，确认对应的断言真的变红，然后还原。

| 变异 | 期望变红的那条 | 实测 |
| --- | --- | --- |
| 删掉 dev 档 `<target>System.err</target>` | `devProfile_writesLogsToStderr` | ✅ `:50` 红 |
| 给 prod 档也加上 `System.err` | `prodProfile_staysOnStdout` | ✅ `:56` 红 |
| 把 dev 档根级别压到 `ERROR` | `devRootLevel_keepsWarnVisible` | ✅ `:76` 红 |

三条各命中对应的那条，**没有一条是摆设**。还原后 `git diff --stat` 回到「10 insertions」，XML 良构复核通过。

**过程中的一次真红（如实记）**：正向对照那条一开始写的是 `contains("<encoder>")`，跑出来**红了**——prod 档的 encoder 是 `<encoder class="...LogstashEncoder">`，带属性，字面量闭合标签匹配不上。**这条红是断言写松了，不是配置有问题**；改成 `contains("<encoder")` 后绿。留着记录，因为它正好演示了正向对照的用处：它抓的是「断言本身对不对」，而前两条抓的是「配置对不对」。

### ★ 验收中反馈的一个假警报（已澄清，改的是文档不是代码）

用户按附页跑完 Step 1~4，报「`tool list` 还是有日志显示」。查下来是**两个不同的问题叠在一起**，都不是修复失效：

1. **终端上看得见日志是 A 方案的预期行为。** A 做的是**分流**（日志→stderr、数据→stdout），**不是静音**；终端把两股都渲染出来，肉眼看到的自然还是「日志 + 表格」。**用户读成了「改完终端就该干净」——这是本向导的表述问题**：原 Step 4 把「终端上两股都还看得见」埋在注释**末尾**，第一行写的是「已分流」，容易被读成「已经没日志了」。
2. **更硬的一条：`dist/oryxos.jar` 是旧产物。** 仓库里现成那份是当天 **11:16** 的 `package.sh` 产物，**没有同日 14:23 的这次修复**。拿它跑，`2>/dev/null` 也挡不住日志——症状和「没修」一模一样。实测对照：

| 命令 | 修好的 jar（`oryxos-boot/target/`） | `dist/oryxos.jar`（11:16） |
| --- | --- | --- |
| `tool list 2>/dev/null \| wc -l` | **19**（纯表格） | **46**（日志 + 表格） |

**处置（只改文档，一行代码没动）**：

- Step 4 的注释**重写**——第一行改成「⚠️ 终端上仍然看得见日志，这是对的——本修复是『分流』不是『静音』」，并把 `wc -l` 的 **19 vs 46** 写成「修复在不在你手上这份 jar 里」的判据
- Step 0 新增 `dist/oryxos.jar` 警告：`dist/` 是 `package.sh` 的产物、只有跑打包脚本才更新，**排查「改了没效果」之前先 `echo "$ORYXOS_JAR"` 确认指的是哪个文件**
- 两个数字都实测过（19 / 46），不是估的

**用户裁决**：保持分流，不做终端静音。故本节的日志行为定稿为「**dev 档日志走 stderr、命令输出走 stdout；终端上两股都可见**」，不引入按命令压日志级别的特例。

---

# 交付后补强（2026-10-15）②：通知渠道写入面

> 本节记录的是**第二次交付后补强**（第一次是上面的方言档 / 路由接缝）。第一次补强让 `notify` **能推**，这一次让它**有东西可推**。

| 项 | 内容 |
| --- | --- |
| 起因 | 第 19 节交付的 `notify_channels` 全局注册表**只有读半边**——表建好了、`resolve()` 能按名解析、`notify` 能推，但**没有任何路径能往表里写一行** |
| 用户裁决 | **保留全局注册表，补最小写入面**（选项 B）。已有的表与测试全部保留 |
| 交付 | CLI 三条**轻命令** `notify-channel add\|list\|remove` + `${ENV_VAR}` **读时解析** |
| 零改动面 | `NotifyChannelAdapter` / `NotifyTarget` **两文件 `git diff` 为空**；`NotifyChannelRegistry` 接缝**仍无写入方法**；装配面一行不改（一参构造保留） |
| 测试 | **273 → 325**（`oryxos-tool` 187 → 195，`oryxos-cli` 25 → 69，其余模块不变）；**新增 52 条**（48 属本次，另 4 是此前未入库的 `LogStreamSeparationTest`） |
| 结论 | **`mvn clean verify` 全绿（10 模块）**；`notify` 从「任何真实部署里 100% 失败」变成**配了渠道就真能推** |

## 补强②·一、为什么做：「写入路径归第 26 节」是一个**错的挂账**

第 19 节把写入路径挂到第 26 节，`spec.md` FR-014/FR-015 甚至把「运行期注册表恒为空」写成「**预期状态，不是缺陷**」。三个**互相独立**的来源同指一个结论：**这个挂账等于「永远不做」**。

| # | 来源 | 证据 |
| --- | --- | --- |
| 1 | **第 26 节是只读的** | `docs/class/第26节：Web Service 与第一版管理平台 实现与代码讲解.md` L33 明写「这一版只读」，L156 把「Profile 的增删改端点、Memory 的写入端点」全部列进扩展阶段；10 个端点里**没有一个**与 notify-channels 相关 |
| 2 | **上游 `class-19` 根本不是这个形态** | `git diff --stat origin/class-18 origin/class-19` 只新增 7 个主源文件；`origin/class-19` 的 `schema.sql` 里 `notify_channels` 出现 **0 次**。上游那一节走课件口径（`Profile.notify_channels` frontmatter，`Profile.java:20/43`），**没有表、也就没有这个缺口**。上游 HEAD 才演进到全局表 + REST CRUD（`/api/v1/notify-channels`），而且**写入面与迁移是同时交付的**——我们复刻时只搬了读半边 |
| 3 | **业界共识是有写入前端** | Hermes（`~/.hermes/config.yaml` + `hermes gateway setup`）与 OpenClaw（`~/.openclaw/openclaw.json` + `openclaw onboard` / Control UI）都以配置文件为唯一真相源，由向导 / CLI / UI 写入，凭证走 `${VAR}` 或 secret-ref。形态各异，**没有一家是「手工改数据库」** |

**后果的严重性在于它是静默的**：`notify` 在任何真实部署里 100% 失败，却被记成「预期状态」；唯一能演示的路径是 `manual-test-guide.md` 附页那套手工 `sqlite3 INSERT`。

## 补强②·二、软门禁报备（本次触发两条，逐条留痕）

| 门禁 | 触发点 | 处置 |
| --- | --- | --- |
| ① 新增对外概念 | 三条新命令 `notify-channel add\|list\|remove`（**命令名 / 参数 / 退出码三项即契约本体**）；`${ENV_VAR}` **读时解析**（新行为）；`WorkspaceDb.execute`（模块内新公开方法） | **已报批**：AskUserQuestion ×3（解析时机 = 读时解析；轻 / 重 = 轻；命令名 = `notify-channel add\|list\|remove`）+ ExitPlanMode 批准整份计划 |
| ② 改已交付行为 / 字面量 | **`JpaNotifyChannelRegistry`（第 19 节已交付并已验收的类）**：① 加一个**包级私有**二参构造（公开一参构造**保留**，故装配面零改动）② `toTarget` 由 `static` 改实例方法 ③ 新增 `${ENV_VAR}` 读时解析 ④ 「未配置任何通知渠道」那句的**尾巴**改成指向具体命令 | **已报批**。**行为变化只有第 ③④ 条**：③ 是本次的交付内容本身；④ 是错误消息的尾部文案——既有断言只钉 `hasMessageContaining("未配置任何通知渠道")`，改尾不影响任何测试。走「契约文档 + 验收报告双处留痕」 |
| ② 改前序交付物**注释** | 4 处 javadoc 里的「写入路径归第 26 节」（`NotifyChannelRegistry` / `NotifyChannelRepository` / `JpaNotifyChannelRegistry` / `CliAssemblyConfiguration`）+ 3 处「12 个子命令」（`OryxOsCli` / `CliModule` / `CliAssemblyConfiguration:69`） | **已报批**。**这一项本身只有注释**——但注意 `OryxOsCli` 与 `CliAssemblyConfiguration` 这两个文件本次**另有代码改动**：`OryxOsCli` 是**本次**挂 `notify-channel` 命令组（属补强②·五的「改动」清单）；`CliAssemblyConfiguration` 的代码改动（路由装配）属**补强①**，不在本次。这些注释原本已变成**假话**，不加修订注就是留着错误信息误导下一个读代码的人 |
| ③ 跨节交付物 | 第 18 节的 **CLI 命令集**（12 → 15） | **已走修订程序**：`specs/003-cli-session/contracts/cli-surface.md` 补 §2.12 + §1 表格三行；`spec.md` FR-002 / FR-003 / SC-001 / SC-006、`contracts/cli-assembly.md`、`specs/000-runtime-kernel/spec.md` FR-080 / SC-008 逐条回修。既有 12 条的**命令名 / 参数 / 退出码一字未动**，本次是**命令集扩充** |

**未触发**：④ 冻结的接口文件——`NotifyChannelAdapter.java` / `NotifyTarget.java` 的 `git status` **为空**，契约 §1.4 继续满足（见补强②·六）。

## 补强②·三、证据一：全量门禁 `mvn clean verify` 全绿

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
./mvnw -o clean verify -Ddependency-check.skip=true
```

```text
[INFO] Reactor Summary for OryxOS Parent 0.1.0-SNAPSHOT:
[INFO]
[INFO] OryxOS Parent ...................................... SUCCESS [  2.663 s]
[INFO] oryxos-storage ..................................... SUCCESS [ 10.860 s]
[INFO] oryxos-core ........................................ SUCCESS [  8.171 s]
[INFO] oryxos-provider .................................... SUCCESS [  4.579 s]
[INFO] oryxos-memory ...................................... SUCCESS [  2.556 s]
[INFO] oryxos-tool ........................................ SUCCESS [ 12.928 s]
[INFO] oryxos-channel-cli ................................. SUCCESS [  2.352 s]
[INFO] oryxos-web ......................................... SUCCESS [  2.193 s]
[INFO] oryxos-cli ......................................... SUCCESS [ 14.471 s]
[INFO] oryxos-boot ........................................ SUCCESS [  2.542 s]
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] Total time:  01:03 min
```

**测试条数（按模块实测，非估）**：

| 模块 | 本次补强前 | 本次补强后 | Δ |
| --- | --- | --- | --- |
| `oryxos-storage` | 9 | 9 | 0 |
| `oryxos-core` | 45 | 45 | 0 |
| `oryxos-provider` | 7 | 7 | 0 |
| `oryxos-tool` | 187 | **195** | +8 |
| `oryxos-cli` | 25 | **69** | +44 |
| **合计** | **273** | **325** | **+52** |

### ★ 本轮真实抓到一个红（已修）

第一次 `mvn clean verify` **在 `oryxos-cli` 红了**：

```text
[ERROR] Failed to execute goal com.diffplug.spotless:spotless-maven-plugin:2.44.5:check
        (spotless-check) on project oryxos-cli: The following files had format violations:
[ERROR]     src/main/java/io/oryxlabs/oryxos/cli/CliModule.java
[ERROR]         - * list|create|show|delete / notify-channel add|list|remove / provider list / tool list / session list）、
[ERROR]         + * list|create|show|delete / notify-channel add|list|remove / provider list / tool list / session
[ERROR]         + * list）、 {@code ConfigLoader}（统一加载敏感配置，{@code ${ENV_VAR}} 占位解析）。
```

**根因**：我手改 `CliModule` 的 javadoc 时按语义换的行，`google-java-format` 按 100 列重新折了行。**这条红是格式门禁在正常工作**，不是配置问题——`./mvnw -o -q spotless:apply` 修好，第二次全绿。留着记录：**改任何 javadoc 都要过 Spotless**，注释不是法外之地。

### OWASP 如实交代

本地迭代用 `-Ddependency-check.skip=true` 跳过 OWASP（与 quickstart §1 的既有口径一致）。**本次不新增任何第三方依赖边**（`oryxos-cli/pom.xml` 只把 `jackson-databind` 从**传递边**显式化——它本来就在编译类路径上经 `oryxos-tool` 传来，发行物不新增任何工件），故 OWASP 的输入集未变。

## 补强②·四、证据二：新增测试逐条对号（52 条）

| 测试类 | 条数 | 钉住什么 |
| --- | --- | --- |
| `WorkspaceDbTest`（`oryxos-cli`，新） | 5 | `execute` 的往返：INSERT 后 `query` 读得回；DELETE 返回受影响行数；参数里的**单引号 / 中文 / 换行**原样进出（**证明走的是绑定不是拼接**） |
| `NotifyChannelAddCommandTest`（`oryxos-cli`，新） | 14 | 重名**不覆盖**；`--config url=` 被拒；`--url` 与 `--url-env` 互斥；`--url-env` 形状非法被拒；`--config` 值含 `=`（base64）**切分正确**；键重复报错；**输出 `doesNotContain(真 url)` / `doesNotContain(config 值)`** |
| `NotifyChannelListCommandTest`（`oryxos-cli`，新） | 8 | 首行即默认渠道（与 `findFirstByOrderByNameAsc` 同序）；`url` 列「整串恰好是 `${VAR}` → 原样打印，否则 `<已配置>`」；`config` 列**只打键名**；`doesNotContain(真 url)` |
| `NotifyChannelRemoveCommandTest`（`oryxos-cli`，新） | 7 | 未确认时**不删且退出码 0**；`--force` 跳过；名字不存在**报错并指向 `list`**（不是静默成功）；输出不含地址 |
| `CliCommandTreeTest`（`oryxos-cli`，新） | 3 | 叶子集合**恰好**是契约里的 15 条（`TreeSet` 比较）；`notify-channel` 有 `add`/`list`/`remove`；每个叶子都有 `mixinStandardHelpOptions` |
| `NotifyChannelRoundTripTest`（`oryxos-cli`，新） | 3 | **最高价值的一条**：`WorkspaceDb.execute` 写一行（`${VAR}` 语义）→ 起**真** Spring 上下文 → 取 `NotifyChannelRegistry` Bean → `resolve()` → 断言 `config.url` **已是解析后的真值** |
| `LogStreamSeparationTest`（`oryxos-cli`，此前未入库） | 4 | dev 档日志走 stderr / prod 走 stdout（第一次补强的产物，本次一并入库） |
| `JpaNotifyChannelRegistryEnvTest`（`oryxos-tool`，新） | 8 | 读时解析：`${VAR}` 在 `url` **与 `config` 值**里都被替换；变量缺失**抛异常且消息只带变量名、不带值**；无占位原样通过；`${A}-${B}` 混排；`${A B}` 形状不合法**原样保留不报错**。**注入 `UnaryOperator` 而非读真环境变量**（照 `ProfileLoader` 既有测试手法） |

**为什么 `NotifyChannelRoundTripTest` 值这个价**：现有测试**全都是各自 mock 一半**——`oryxos-cli` 侧不知道实体形状，`oryxos-tool` 侧不知道写进去的是什么。这一条**同时**证明：两半对行形状的理解一致、表结构与实体对得上、`${ENV_VAR}` 读时解析真的接通了、**能力不再是死的**。这类断裂别的测试抓不到。

## 补强②·五、证据三：交付物与改动清单

**新增（`oryxos-cli`，5 个主源文件 + 6 个测试文件）**：

| 文件 | 职责 |
| --- | --- |
| `command/NotifyChannelCommand.java` | 分组命令（`@Spec` + `usage(System.out)`，照 `ProfileCommand`） |
| `command/NotifyChannelAddCommand.java` | `add <name> --type <type> (--url \| --url-env) [--description] [--config]...` |
| `command/NotifyChannelListCommand.java` | `list`（表格：`name type url config description`，按 `name ASC`） |
| `command/NotifyChannelRemoveCommand.java` | `remove <name> [--force]` |
| `command/NotifyChannelDb.java` | 三条命令共用的「开库 + 保证 `notify_channels` 表在」（包级私有） |
| `test/.../command/CommandTestSupport.java` | 轻命令测试脚手架（`parseArgs` + `getCommand().run()`，**不是 `execute()`**） |

**改动**：`WorkspaceDb`（+`execute`）、`OryxOsCli`（挂命令组）、`oryxos-cli/pom.xml`（显式 `jackson-databind`）、`JpaNotifyChannelRegistry`（读时解析）、4 处已交付 javadoc 加修订注。

**两处「看着多余、删了会错」的设计，不要清理**：

1. **`CommandTestSupport` 用 `parseArgs` + `getCommand().run()` 而不是 `execute()`**：Picocli 的 `execute()` **吞掉**命令抛出的异常（转成退出码），`assertThatThrownBy` 会得到「Expecting code to raise a throwable」。这是本次踩过的坑，不是风格偏好。
2. **两条命令自带 `runSchemaScript()`**：`notify_channels` 是第 19 节才有的表，**老工作区的库文件里没有它**，而 `.oryxos/` 是**跨升级保留的用户数据**、`oryxos init` 又是幂等的（不补建已存在工作区里的新表）。脚本全是 `CREATE TABLE IF NOT EXISTS`，天然幂等。`session list` 没这步是因为 `sessions` 表与工作区同龄——**那条命令不需要，不代表这一步多余**。

## 补强②·六、证据四：冻结接口与前序节回归

```bash
git diff --stat -- \
  oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/notify/NotifyChannelAdapter.java \
  oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/notify/NotifyTarget.java \
  oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/notify/NotifyTools.java \
  oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/notify/FeishuNotifyAdapter.java \
  oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/notify/RoutingNotifyChannelAdapter.java \
  oryxos-core/src/main/java/io/oryxlabs/oryxos/core/engine/ProfileContext.java \
  oryxos-core/src/main/java/io/oryxlabs/oryxos/core/profile/Profile.java
# 预期：无输出 —— 实测无输出
```

**接缝仍然只有读方法**（`NotifyChannelRegistry` 全文件只有 `resolve(String)` 一个方法）：

```bash
grep -nE "\b(save|delete|add|remove|insert|update)\b" \
  oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/notify/NotifyChannelRegistry.java
# 预期：只命中 javadoc 散文（它正在解释【为什么不加这些方法】），无代码行
```

这正是「接口先行」的**第二次**兑现：写入面**没有**走这个接缝——轻命令不启 Spring，看不到容器，因此拿不到 Bean，走的是原生 JDBC 直写表。第 26 节加 REST CRUD 时，**那一次**才是往接缝上加方法。

**前序节回归**：`mvn clean verify` 全绿，273 条一条不红（见补强②·三）。`oryxos-storage` / `oryxos-core` / `oryxos-provider` 三个模块测试条数**一个没变**。

## 补强②·七、证据五：H4 六条全局不变量逐条自查

```bash
# ③ 无明文凭证 / 无硬编码地址（本次新增文件）
grep -rniE "https?://[a-z0-9.-]+/(cgi-bin|open-apis|robot)|open\.feishu\.cn|qyapi\.weixin" \
  oryxos-cli/src/main/java/io/oryxlabs/oryxos/cli/command/NotifyChannel*.java \
  oryxos-cli/src/main/java/io/oryxlabs/oryxos/cli/workspace/WorkspaceDb.java
# 预期：OK: 无硬编码地址 —— 实测 OK

# ⑤ 无响应式 / 无自建线程池
grep -rn "reactor\|Flux\|Mono\|CompletableFuture\|Executors\.\|new Thread" \
  oryxos-cli/src/main/java/io/oryxlabs/oryxos/cli/command/NotifyChannel*.java \
  oryxos-cli/src/main/java/io/oryxlabs/oryxos/cli/workspace/WorkspaceDb.java \
  oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/notify/JpaNotifyChannelRegistry.java
# 预期：OK: 同步阻塞 —— 实测 OK

# ⑥ 无 Spring AI 自动执行路径
grep -rn "ToolCallingManager\|ToolCallbackProvider\|@EnableTool" \
  oryxos-cli/src/main/java/io/oryxlabs/oryxos/cli/command/NotifyChannel*.java
# 预期：OK —— 实测 OK

# 注入面：SQL 必须走绑定，命令里不得拼串
grep -n "PreparedStatement\|executeUpdate" oryxos-cli/src/main/java/io/oryxlabs/oryxos/cli/workspace/WorkspaceDb.java
# 实测：136/140 行 —— prepareStatement(sql) + setObject 位置绑定 + executeUpdate()
grep -nE '"(SELECT|INSERT|DELETE|UPDATE)[^"]*"\s*\+' \
  oryxos-cli/src/main/java/io/oryxlabs/oryxos/cli/command/NotifyChannel*.java
# 预期：OK: 无拼接 —— 实测 OK

# 依赖方向：core / storage 不得认识 notify 类型
grep -rn "NotifyTarget\|NotifyChannelAdapter\|NotifyChannelRegistry" oryxos-core/src/ oryxos-storage/src/
# 实测：只命中 3 处 javadoc 散文（其中两处正是在说【本类看不见 NotifyTarget】），无代码引用
```

**② 审计落库**：本次**不新增审计逻辑**。`notify` 仍与其他工具同处第 17 节 `ToolExecutor` 的执行入口之下，成功失败都由**那一处**落 `tool_invocations`。新增的 CLI 三条是**管理面**（配渠道），不是工具调用，**不进审计表**——这是对的：审计记录的是「Agent 干了什么」，不是「运维配了什么」。

**④ `session_id` 只在 `SessionManager` 内拼接**：本次不接触 session 身份。

## 补强②·八、证据六：端到端真跑（/tmp，已清理）

全程在 `/tmp/oryxos-notify-check`（**不在 home**），收尾 `rm -rf`。

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
JAR=/home/wngbob/oryxos-wngbob/oryxos-boot/target/oryxos.jar
rm -rf /tmp/oryxos-notify-check && mkdir -p /tmp/oryxos-notify-check && cd /tmp/oryxos-notify-check
java -jar $JAR init
```

**① `add` 走 `--url-env`（推荐路径）——凭证不进 shell 历史、不进库**

```text
$ java -jar $JAR notify-channel add team-lark --type feishu --url-env FEISHU_HOOK_URL \
      --config 'secret=${FEISHU_SECRET}' --description '研发群'
已添加通知渠道 'team-lark'（type=feishu）
  地址：${FEISHU_HOOK_URL}——发送时从环境变量解析，凭证未落库
exit=0
```

**② `add` 走字面量 `--url`——回显纪律：只给提示，MUST NOT 回显那个值**

```text
$ java -jar $JAR notify-channel add ops-hook --type webhook --url 'https://example.com/hook/abc123'
已添加通知渠道 'ops-hook'（type=webhook）
  提示：地址已按字面量入库。含 token 的地址建议改用 --url-env <ENV_VAR>，凭证不落库、也不进 shell 历史
exit=0
```

> 注意：上面**没有**出现 `https://example.com/hook/abc123`。

**③ `list`——`url` 列：占位符原样打印（用户得知道该设哪个变量），真地址打 `<已配置>`；`config` 列只打键名**

```text
$ java -jar $JAR notify-channel list
通知渠道（共 2 条，按注册名升序，首行为默认渠道）:
  name  type  url  config  description
  ops-hook  webhook  <已配置>  —  —
  team-lark  feishu  ${FEISHU_HOOK_URL}  secret  研发群
exit=0
```

**④ 库里的真实内容——`url` 列必须是字面量占位符，不是真 token**（本机无 `sqlite3`，改用 Python 标准库只读打开）

```text
$ python3 -I -c "import sqlite3; c=sqlite3.connect('file:.oryxos/oryxos.db?mode=ro',uri=True); \
    [print(r) for r in c.execute('select name,type,url,config_json,description from notify_channels order by name')]"
('ops-hook', 'webhook', 'https://example.com/hook/abc123', None, None)
('team-lark', 'feishu', '${FEISHU_HOOK_URL}', '{"secret":"${FEISHU_SECRET}"}', '研发群')
```

**`url` 列存的是 `${FEISHU_HOOK_URL}` 字面量**——凭证**没有**落库。这正是读时解析的直接证据。

**⑤ 五条拒绝路径（全部退出码 1，全部不打地址）**

```text
$ java -jar $JAR notify-channel add team-lark --type feishu --url-env X
错误: 通知渠道 'team-lark' 已存在，不覆盖；如需重建请先 `oryxos notify-channel remove team-lark`   exit=1

$ java -jar $JAR notify-channel add bad --type webhook --url-env X --config 'url=https://evil'
错误: --config 里不能写 url=...：地址请用 --url 或 --url-env 指定，不要写进 --config            exit=1

$ java -jar $JAR notify-channel add bad2 --type webhook --url a --url-env B
错误: --url 与 --url-env 只能给一个（--url-env 就是 --url '${VAR}' 的糖）                      exit=1

$ java -jar $JAR notify-channel add bad3 --type webhook --url-env 'not a var'
错误: --url-env 要的是环境变量名（字母或下划线开头，只含字母/数字/下划线），收到：not a var       exit=1

$ java -jar $JAR notify-channel remove nope --force
错误: 通知渠道 'nope' 不存在：`oryxos notify-channel list` 可看全部                              exit=1
```

> 第 4 条是**防静默失败**的：`--url-env` 写错成非变量名时，占位符**永远不会**被读侧正则匹配 → 会被当**字面量地址**发出去 → 一个 typo 变成一次奇怪的网络请求。故在**写入时**就按正则形状校验。

**⑥ `--config` 值含 `=`（base64 secret 的真实形状）切分正确**

```text
$ java -jar $JAR notify-channel add b64 --type feishu --url-env X --config 'secret=YWJj==='
已添加通知渠道 'b64'（type=feishu）
  地址：${X}——发送时从环境变量解析，凭证未落库
exit=0
```

**⑦ `remove`：未确认时退出码 0（用户按回车是「算了」，不是错误）；`--force` 跳过**

```text
$ java -jar $JAR notify-channel remove b64 < /dev/null
确认从注册表移除通知渠道 'b64'？[y/N] 已取消：未移除通知渠道 'b64'          exit=0

$ java -jar $JAR notify-channel remove b64 --force
已移除通知渠道 'b64'                                                       exit=0
```

**⑧ 老工作区自愈：手工 `drop table` 后 `list` 自动补建**

```text
$ python3 -I -c "import sqlite3; c=sqlite3.connect('.oryxos/oryxos.db'); c.execute('drop table notify_channels'); c.commit()"
  已 drop table
$ java -jar $JAR notify-channel list
通知渠道（共 0 条，按注册名升序，首行为默认渠道）:
  （无：用 `oryxos notify-channel add <name> --type <type> --url-env <ENV_VAR>` 添加一个）   exit=0
$ # 复查：sqlite_master 里 notify_channels 已回来 → True
```

## 补强②·九、重点 review 清单（按风险排序）

| # | 风险点 | 为什么是风险 | 现状 |
| --- | --- | --- | --- |
| 1 | **`${ENV_VAR}` 变量缺失时的失败语义** | 与 `McpServerConfigLoader` **刻意不同**（那里跳过整个 server + WARN，这里抛异常）。若哪天有人「统一」成一处，`notify` 的失败就可能从「落审计」退化成「WARN 一下继续」 | 抛异常 + 消息**只带变量名**；`JpaNotifyChannelRegistryEnvTest` 钉死「消息不含值」。三处 `envLookup` **没有**合并，理由写在 `CLAUDE.md`「配置加载规则」 |
| 2 | **写入面绕过接缝（原生 JDBC）** | 看起来像「抄近路」，实际是轻命令**看不到容器**的必然结果。若有人为「统一」把它改成走 `JpaNotifyChannelRegistry`，轻命令就变重命令（判据被破坏），且要往接缝加写方法（撞 FR-014） | `NotifyChannelRegistry` 全文件仍只有 `resolve`；理由写在 `NotifyChannelDb` 与 `NotifyChannelRegistry` 的 javadoc |
| 3 | **`add` / `list` 的输出纪律** | `url` 含 token，回显一次就进了终端 scrollback / CI 日志 | `doesNotContain(真 url)` / `doesNotContain(config 值)` 是本组的**核心断言**（不是补充）——`NotifyChannelAddCommandTest`（14 条）+ `NotifyChannelListCommandTest`（8 条）共 22 条里，**5 条直接断言「输出里没有那个值」**，其余各条也都带 `doesNotContain` 类的输出检查 |
| 4 | **`--url-env` 形状校验** | 不校验 → typo 静默变成「用字面量地址发请求」 | 写入时按 `[A-Za-z_][A-Za-z0-9_]*` 校验；实测拒绝 `not a var` |
| 5 | **命令树是显式装配的** | 注解表达不了构造器注入的实例，加 / 删命令**不会**被任何注解测试发现 | `CliCommandTreeTest` 断言叶子集合**恰好**是契约里的 15 条——加 / 删会红，逼你回来同步契约 |
| 6 | **`--config url=...` 被拒** | 读路径会往 `config` 里**强塞** `url` 键（`JpaNotifyChannelRegistry.java:106` 的 `config.put(URL_KEY, ...)`，契约要求该键恒定存在），从写侧也塞就造出**两个真相源** | 写入时明确拒绝并提示改用 `--url` / `--url-env` |
| 7 | **自带建表** | 看着像「多余的一步」，实则是老工作区的自愈路径 | 实测：手工 `drop table` 后 `list` 自动补建（补强②·八 ⑧） |

## 补强②·十、如何验证

```bash
cd /home/wngbob/oryxos-wngbob
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64

# 1) 只跑本次相关测试（单模块命令一律带 -am，否则静默取 ~/.m2 旧字节码）
./mvnw -o -am -pl oryxos-cli test -Dtest='WorkspaceDbTest,NotifyChannel*Test,CliCommandTreeTest'
./mvnw -o -am -pl oryxos-tool test -Dtest='JpaNotifyChannelRegistryEnvTest,JpaNotifyChannelRegistryTest'

# 2) 全量门禁（含 Spotless / P3C / Checkstyle / SpotBugs+FindSecBugs）
./mvnw -o clean verify -Ddependency-check.skip=true

# 3) 端到端（照补强②·八 逐步跑；/tmp 下，收尾 rm -rf）
```

**真渠道验收（harness 判不了，已由用户人工跑过）**：按 `manual-test-guide.md` **Step 5** 用 `notify-channel add` 配一个**真实飞书群** webhook（`type=feishu`，开签名校验时带 `--config secret=...`），`oryxos chat` 里让 Agent 调 `notify`，确认消息**真的出现在群里**——这是第 19 节附页那套手工 `sqlite3 INSERT` 流程的**正式替代**。再故意把 `--type` 写成 `feishuu`，确认 `notify` **报错并列出已知类型**（路由处的既有守卫，第 19 节已交付并有测试钉死）。

> **2026-10-15 修订注**：本节落笔时记为「仍待人工」；用户随后按 `manual-test-guide.md` **全程人工跑过一遍，未发现问题**（见补强②·十四）。原措辞保留以留痕。

## 补强②·十一、明确不做（边界）

| 不做 | 归属 | 理由 |
| --- | --- | --- |
| `/api/v1/notify-channels` 的 REST CRUD 与管理台 | **第 26 节** | 本次写入面走 CLI 原生 JDBC，**不动接缝**——契约 §1.2「只能新增方法」依旧成立 |
| **写入时的 `type` 取值校验** | 路由处（已交付） | 合法取值由装配处映射表决定，轻命令看不到容器。**在 CLI 里写死一份类型清单会造出第二真相源**；拼错的 `type` 在**路由处**报错并列出已知类型，那条路径第 19 节已交付且有测试钉死 |
| 企业微信 / 钉钉方言档 | 第 19 节挂账②剩余 | 路由接缝已就位，加它们是「装配处映射表加一行 + 一个实现类」，接口与调用方零改动 |
| 加密存储 / KMS | 扩展阶段 | `${ENV_VAR}` 读时解析已覆盖「**凭证不落库**」这个核心诉求 |
| **`ConfigLoader` 收口** | 独立一次改造 | `CLAUDE.md` 说敏感配置由 `ConfigLoader` 统一加载，实际是**三处各持 `envLookup`**（`ProfileLoader` / `McpServerConfigLoader` / `JpaNotifyChannelRegistry`），且**失败策略三处刻意不同**。合并是独立收口，不该折进这一节 |

## 补强②·十二、遗留与下一步

- **本节的读半边 + 写半边现已闭合**：`notify` 从「恒报未配置渠道」变成「配了就真能推」。
- **`manual-test-guide.md` Step 5** 保留手工 `sqlite3 INSERT` 的对照说明（降级为「为什么不用它」的参考），正式路径是那三条 CLI 命令。
- **~~仍待人工~~ 已人工跑过**：真实飞书群的端到端送达（见补强②·十）。**2026-10-15 修订注**：用户已按向导 Step 0~9 全程跑过一遍，**未发现问题**（见补强②·十四）。
- **跨节挂账变化**：`notify_channels` 写入路径**不再是第 26 节的挂账**（本次已清）；第 26 节仍归 REST CRUD 与管理台。
- **收尾流程**：本次**未** commit / push / 跑 `package.sh`——同步时机由用户决定。

---

## 补强②·十三、交付后补强：`manual-test-guide.md` 重新生成（2026-10-15）

**为什么做**：那份向导是**人工验收的唯一入口**，但到补强② 为止它已经叠成三层——2026-10-14 首版（§0~§9）+ 补强① 的附页 + 补强② 的 Step 5 改写与四处回填。后果是**同一件事有三处互相打脸的结论**：§0 的「三条硬事实」全部失效却仍留在正文、§8 的实测表说测试 D「今天跑不了」、§10 又回头更正 §9；配渠道的主路径停在手工 `sqlite3 INSERT`；命令里还有 `java -jar .../oryxos.jar` 这种**跑不通**的省略号。**一份要读三遍才能确定该照哪段做的向导，等于没有向导。**

**改了什么（实质修正，不是排版）**：

| # | 修正 | 为什么它不是排版问题 |
| --- | --- | --- |
| 1 | `java -jar .../oryxos.jar` → `java -jar "$ORYXOS_JAR"`（19 处） | 旧写法**跑不通**（`...` 是字面省略号），且 Step 0 那条 `dist/oryxos.jar` 警告失去了着落点 |
| 2 | Step 7 的故障注入从「直接 `UPDATE notify_channels`」改为「CLI 另建一个坏渠道」 | 旧写法正是本次写入面要消灭的**第二真相源**；改后**全页零次手工改库** |
| 3 | Step 8 改用 `notify-channel add team-typo --type feshu` | 这才是用户真实的犯错路径（`--type` 手滑）；旧版是去改库里的 `type` 列 |
| 4 | Step 5 的核对脚本改为只打布尔值与键名 | 旧版 `print(url, cfg)` 会把 token 打上终端，**与它自己声明的输出纪律矛盾** |
| 5 | 删掉做法 A / B 的飞书错误码 `19024` / `19021` | 全仓 grep 只在旧向导里出现（源码与测试里只有 `19002`）——那是**没实测过的断言**，改为「具体码值以实际返回为准」 |

**编号变更（会咬人的那一处）**：旧编号 `§N` 退役为 `A.N`（历史实测证据整段收进**附录 A**）。**指向本文件的 12 处引用已同步回修**：`CLAUDE.md`（挂账②）、`spec.md`（3 处）、`tasks.md`（3 处）、`acceptance-report.md`（3 处）、`quickstart.md`、`005-tool-registry/contracts/builtin-tools.md`。**`Step 5` 这个锚点故意保持不变**——`CLAUDE.md`「通知渠道」、`quickstart.md`、本文件补强②·十都指它，故 Step 编号原样沿用 0~9。

**没改什么（刻意）**：

- **历史实测证据一条没删**——原 §3 / §4 / §5 / §7 / §8 的**原始输出与当时裁决**整段进附录 A，含已被推翻的「选项 A 记为已知边界」（保留原文 + 带日期的推翻记录）。这是 `CLAUDE.md`「实测证据见附录 A.2」那条引用的落点。
- **描述「当时做了什么」的历史句没动**——T028 / T046 的任务记录、补强·九 的交付物表、补强·十二 的叙事，那些是留痕，改了就成假话。
- **软门禁口径**：本次是**已交付文档的重写**（撞软门禁 ②「改已交付字面量」），不新增公共概念、不改任何代码、不动任何接口文件。

**~~仍待人工（如实记）~~ 已于当日人工跑过（2026-10-15 补记）**：本节落笔时向导是文档、**没有被跑过**，Step 6 / 7 / 8 的端到端结论仍是 checklist——那正是这份向导存在的理由。**用户随后按向导 Step 0~9 全程人工跑过一遍，未发现问题**（见补强②·十四）。**本节其余部分保留原样**——那是落笔当时真实做过的事。当时已核对的只有**静态面**：CLI 的选项与校验（`--type` / `--url` 与 `--url-env` 互斥 / `--config url=` 被拒 / 重名不覆盖 / 自带幂等建表 / `remove --force`）与向导点名的 **7 个测试类、8 个测试方法全部存在**。`mvn clean verify` **未重跑**（本次一行 Java 未改，前一次全绿 **325** 条）。

---

## 补强②·十四、人工验收已跑（2026-10-15，用户执行）

**记录什么**：用户在补强②·十三 重新生成向导之后，**按 `manual-test-guide.md` 的 Step 0~9 全程人工跑过一遍**，结论：**未发现问题**。

**这一条清掉的是什么**：补强·十一 第 2 条（「真渠道人工验收未跑」）、补强②·十、·十二、·十三 四处都记着「未跑 / 仍待人工」。那四处是**落笔当时的事实**，现各挂带日期修订注作废——向导的三条判据（Step 6 正向 / Step 7 反向 / Step 8 路由）从 checklist 变成**已执行**。

**MUST NOT 据此补写的东西（如实记）**：

- 用户没有说明 Step 7 用的是三种故障注入做法中的哪一种（A 飞书侧开「关键词」/ B `--config secret=` 写错 / C 编造 url），故**本报告不写**。
- 用户没有给出飞书实际返回的业务码，故**本报告不写**——补强②·十三 已刻意删掉未实测的 `19024` / `19021`，不能从这里又把未实测的值放回去。
- 没有留下原始输出，故**不新增「实测记录表」**。需要逐条证据时请重跑向导并留输出。

**边界（仍成立）**：本次跑通**不构成**「沙箱已就位」的证据——挂的仍是 `PermissiveSandbox`，它不拦任何东西（见向导前置事实 ④ 与附录 B）。

> **2026-10-17 修订注**：上面这条**已失效**，且原文里「（仍成立）」四字现在正好说反了。第 24 节已交付 `WhitelistSandbox`、`PermissiveSandbox` 已整类删除，故**本次跑通现在构成**「沙箱已就位」的证据。向导「前置事实 ④」与附录 B 已各挂同日修订注。**原文保留以留痕**。

> **补充（2026-10-15 补强③ 收尾时用户明确）**：Step 6 的正向判据**确认成立**——**飞书群里收到了消息**。上面「MUST NOT 据此补写的东西」三条**仍然有效**（用户未说明 Step 7 的注入做法与对端业务码，故细节照旧不写）；本条只补上「正向那一条从『未发现问题』升格为『消息确实进群』」这一句。三档方言的真渠道验收覆盖面横向对照见补强③·十二。

**口径**：本条是**记录性补记**，不改代码、不改接口、不新增公共概念；只作废四处「未跑」表述并挂带日期修订注。

---

## 补强②·十五、测试临时目录不再泄漏（2026-10-15，软门禁 ② 已报批）

**怎么发现的**：复查遗留问题时顺手量了一次 `/tmp`，发现 5 个测试类用 `Files.createTempDirectory` / `Files.createTempFile` 自建临时路径——**建了没人删**。这是测试卫生问题，不是功能缺陷，但**它一直在漏**。

**实测证据（不是推断）**：

| 量 | 值 |
| --- | --- |
| `/tmp` 里的残留 | **135 个目录 + 20 个 `.db` = 155 项，4.7 MB** |
| 增长速率 | **每跑一次 `mvn clean verify` 稳定 +3 个目录**；`OryxToolContractTest` 每次 +1 个 `.db` |
| 会不会自己消失 | **不会**——`/tmp` 挂在根文件系统上（**不是 tmpfs**），重启不清 |
| 单次验证 | 跑一次 `SessionRepositoryTest`，残留从 46 → 47，**确认每次运行漏一个** |

**五处（4 处 `createTempDirectory` + 1 处 `createTempFile`）**：

| 文件 | 原泄漏物 | 残留 |
| --- | --- | --- |
| `SessionRepositoryTest.java:86` | 目录 | 46 |
| `NotifyChannelRepositoryTest.java:80` | 目录 | 45 |
| `LlmCallRepositoryTest.java:52` | 目录 | 44 |
| `OryxToolContractTest.java:66` | `.db` 文件 | 20 |
| `ProviderSmokeIT.java:93` | 目录 | **0**——`IT` 后缀不在 surefire 默认 includes 内（`pom.xml` 注释明写必须 `-Dtest=ProviderSmokeIT` 点名），**从不被 `mvn verify` 跑到**。是**潜在**泄漏，不是现行；一并改掉，因为点名跑一次就漏一个 |

**改法：四处（含潜在那处）换成 JUnit 5 `@TempDir`**。这不是新引入的写法——仓库里**已有 13 个测试类**在用（`WorkspaceDbTest` / `FileToolsTest` / `McpServerConfigLoaderTest` / `ToolAssemblyTest` / `NotifyChannelRoundTripTest` / 三条 `NotifyChannel*CommandTest` / `McpClientServiceTest` / `ProfileLoaderTest` / `ContextLoaderTest` / `NotifyAssemblyTest` / `ShellToolsTest`），这 5 处是少数派。改完**断言一行未动**，只是把「谁来删」从「没人」换成 JUnit（递归删除，含 SQLite 的 `-wal` / `-shm` 边车文件）。

**一处非显然的约束（已实测，写进 javadoc 防回退）**：这四处原本是 `private static final Path DB_FILE = createDatabaseFile();`。**MUST NOT** 直译成 `@TempDir static Path tempDir; private static final Path DB_FILE = tempDir.resolve(...)`——**静态初始化器在 `@BeforeAll` 之前执行，那时 `tempDir` 还是 null**。故派生挪进 `@BeforeAll` 期可见的惰性方法 `dbFile()`。

**顺序风险已排除（这是本次唯一不确定的点）**：`@DynamicPropertySource` 收的是方法引用 `SessionRepositoryTest::jdbcUrl`，若 Spring **早于** `@TempDir` 注入就求值属性，就会拿到 null。**实测结论：不会**——`@TempDir` 的静态注入在 `@BeforeAll` 回调里，早于上下文创建。四处改完各跑一遍，全绿。

**零改动面**：**没有动任何生产代码、任何接口文件、任何断言**。改的是 5 个测试类的**临时目录获取方式**与 import——每文件各 **−2 / +1**：删掉随之失效的 `java.nio.file.Files` 与 `java.io.UncheckedIOException`（`OryxToolContractTest` 那处是 `java.io.IOException`），加上 `org.junit.jupiter.api.io.TempDir`。

**证据**：`mvn clean verify` 全绿（**325** 条，与改动前同数），且**跑前跑后 `/tmp` 残留数完全相同**（这是本次的判据——不是「测试通过」，而是「测试通过**且不再漏**」）。随后清掉 155 项历史残留，`/tmp` 由 11 MB 降到 6.1 MB；用户自己的 9 个工作副本（`oryxos-l19-check` / `oryxos-demo` / `oryxos-e2e` 等）**未动**。

**口径**：撞软门禁 ②（改已交付字面量），已报批。不新增公共概念、不改接口、不动生产代码。

---

# 交付后补强（2026-10-15）③ —— 企业微信群机器人方言档

> 折进第 19 节，**不新开 spec**（与补强①/② 同例）。本节回答的是挂账② 的剩余部分里**企微那一半**：通用档对企微**也**会假报成功，补强① 只清了飞书 / Lark。

## 补强③·一、为什么做：企微与飞书是同一类缺陷，只清了一半

2026-10-14 人工实测发现的形态是**两家共有**的——IM 群机器人**用 `HTTP 200` 承载业务错误**：

| 渠道 | 报文写错时的实测响应 | 群里 |
| --- | --- | --- |
| 飞书 | `HTTP 200` + `{"code":19002,"msg":"msg_type need"}` | 无消息 |
| 企业微信 | `HTTP 200` + `{"errcode":40008,"errmsg":"invalid message type"}` | 无消息 |

而通用档 `WebhookNotifyAdapter.send()` 走 `.retrieve().toBodilessEntity()` **只判 HTTP 状态码** → 判成成功 → `notify` **假报「已推送」**，失败连 `tool_invocations` 都进不去。补强①（Phase 7）立起了路由接缝并清了飞书 / Lark，**企微被记成「挂账② 的剩余部分」**——但它与飞书是同一个缺陷，留着等于让企微渠道在任何真实部署里继续假报成功。

**用户裁决（2026-10-15，本次）**：

| 问题 | 裁决 |
| --- | --- |
| 排期 | **现在就做**，插在第 22 节（Memory）之前 |
| 范围 | **只做企业微信**；~~钉钉~~ **本次不实现**（用户中途从「企微 + 钉钉」收窄） |
| `type` 取值 | **`wecom`** |
| 签名 | **不适用**——企微群机器人**没有签名机制**（凭证就是 URL 里的 `key`，可选 IP 白名单），故本档**不注入 `Clock`**，比飞书档少一层 |

**目标**：`type=wecom` 的渠道推送**真的能送达**，送不到时**吵**（抛异常落审计），不再假报成功。

## 补强③·二、软门禁报备（本次触发一条，留痕）

| 软门禁 | 触发点 | 处置 |
| --- | --- | --- |
| ② 改已交付字面量 | `CliAssemblyConfiguration.notifyChannelAdapter()` 的 javadoc 原写「将来加第三家（企微 / 钉钉）只动这个方法」——本次兑现了一半，不回修它就成了假话；`WebhookNotifyAdapter.java` 的 javadoc 原写「不逐家对接企业微信 / 飞书 / 钉钉的专用 API」——这句的主语是**通用档**、至今仍成立，但容易被误读成「项目不接这几家」 | **两处都只改 javadoc，各加带日期修订注，生产代码一行未动**（`WebhookNotifyAdapter` 的 `git diff` 是 **+10 / −2，全部落在 javadoc 块内**）。已报批 |
| ② 改已交付字面量 | **收尾自查时补抓到的一处**：`NotifyChannelAddCommand` 的 `--type` 帮助文案原写「如 webhook / feishu / lark」，**漏了 `wecom`**——用户敲 `--help` 看不到 `wecom` 是合法取值，而 CLI **刻意不做**取值校验（避免造第二真相源），所以这行 `--help` 是用户唯一的提示。**这是补强③ 唯一的真遗漏**（全仓 grep 枚举 `type` 取值的地方逐处核过，其余要么是历史留痕、要么是路由测试自建的 3 条映射表 fixture） | 改**一个字符串字面量**（加 `/ wecom`），无逻辑改动；`CliCommandTreeTest` 只断言三个叶子拿得到 `--help`、不断言其内容，故无测试受影响。已报批，改后 `mvn clean verify` 仍全绿 **349** 条 |

**没有触发** ①（不改接口）与 ③（不改 FR 边界）——本次是纯**新增**，接口与调用方零改动。

## 补强③·三、证据一：全量门禁 `mvn clean verify` 全绿

```bash
./mvnw clean verify -Ddependency-check.skip=true
```

```text
[INFO] Reactor Summary for OryxOS Parent 0.1.0-SNAPSHOT:
[INFO]
[INFO] OryxOS Parent ...................................... SUCCESS [  3.694 s]
[INFO] oryxos-storage ..................................... SUCCESS [ 10.657 s]
[INFO] oryxos-core ........................................ SUCCESS [ 11.024 s]
[INFO] oryxos-provider .................................... SUCCESS [  6.314 s]
[INFO] oryxos-memory ...................................... SUCCESS [  2.821 s]
[INFO] oryxos-tool ........................................ SUCCESS [ 16.716 s]
[INFO] oryxos-channel-cli ................................. SUCCESS [  2.285 s]
[INFO] oryxos-web ......................................... SUCCESS [  2.330 s]
[INFO] oryxos-cli ......................................... SUCCESS [ 15.831 s]
[INFO] oryxos-boot ........................................ SUCCESS [  2.716 s]
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] Total time:  01:14 min
```

**测试条数（按模块实测，非估）**：

| 模块 | 本次补强前 | 本次补强后 | Δ |
| --- | --- | --- | --- |
| `oryxos-storage` | 9 | 9 | 0 |
| `oryxos-core` | 45 | 45 | 0 |
| `oryxos-provider` | 7 | 7 | 0 |
| `oryxos-tool` | 195 | **218** | **+23** |
| `oryxos-cli` | 69 | **70** | **+1** |
| **合计** | **325** | **349** | **+24** |

`Failures: 0, Errors: 0, Skipped: 0`（全仓聚合实测）。

### 本轮如实交代

**一次过，没有红。** 与补强② 不同（那轮 Spotless 先红过一次）——本次新写的两个文件在 `mvn clean verify` 之前已跑过 `spotless:apply`，且新代码**避开了 Java 18+ 增强 switch**（P3C / PMD 6.55 解析不了，属既有约束）。

### OWASP 如实交代

本地迭代用 `-Ddependency-check.skip=true`（与 quickstart §1 既有口径一致）。**本次不新增任何依赖边**——`mockwebserver3`（okhttp 5.5.0）已在 `oryxos-tool/pom.xml` 里（补强① 加的），**pom 零改动**；发行物不新增任何工件。

## 补强③·四、证据二：新增测试逐条对号（24 条）

| 测试类 | 条数 | 钉住什么 |
| --- | --- | --- |
| `WeComNotifyAdapterTest`（新） | **19** | 报文方言、fail-closed 判据、超时 / 重定向、凭证不外泄 |
| `WeComNotifyToolTest`（新） | **4** | 经真路由端到端走一遍 `NotifyTools.notify` |
| `NotifyAssemblyTest`（补） | **+1** | 真上下文里映射表覆盖四个取值 |

### 逐条对号（`WeComNotifyAdapterTest` 19 条）

| # | 用例 | 钉住什么 |
| --- | --- | --- |
| 1 | `postBodyCarriesWeComTextDialect` | body 是 `{"msgtype":"text","text":{"content":...}}`，且恰好 2 个顶层键 |
| 2 | `bodyUsesMsgtypeWithoutUnderscoreUnlikeFeishu` ★ | **同时**断言 `has("msgtype")` 为真与 `has("msg_type")` 为假——这是「镜像不是同一份」的判据 |
| 3 | `keepsContentVerbatimForChineseNewlinesAndQuotes` | 中文 / 换行 / 引号 / 反斜杠逐字保真 |
| 4 | `sendsToTheUrlFromTargetConfigNotHardcoded` | 地址来自 `NotifyTarget.config()`，不硬编码 |
| 5 | `acceptsResponseWithZeroErrcode` | `{"errcode":0,"errmsg":"ok"}` → 成功 |
| 6 | `throwsWhenHttpOkButBusinessErrcodeIsNotZero` ★★ | **`HTTP 200` + `errcode:40008` → 抛异常**（本类存在的全部理由） |
| 7 | `businessErrorCarriesVendorMessage` | 失败文案带对端 `errmsg`（`invalid message type`） |
| 8 | `throwsWhenNoBusinessCodeFieldAtAll` | 响应里没有 `errcode` → 判失败（**不是**判成功） |
| 9 | `doesNotAcceptFeishuLegacyStatusCodeField` | 只认 `errcode`；给飞书那个 `StatusCode:0` **不算成功** |
| 10 | `throwsWhenBusinessCodeIsNotAnInteger` | `null` / `"0"` / `0.5` 三态 → 判失败 |
| 11 | `throwsWhenResponseBodyIsEmptyOrBlank` | 空体 / 纯空白体 → 判失败（Jackson 对这两态返回 `MissingNode` 而非抛异常，故必须显式判 `isObject()`） |
| 12 | `throwsWhenResponseBodyIsNotJsonAndDoesNotEchoBody` | 非 JSON → 判失败，且**异常消息不回显 body**（网关错误页常回显带 key 的 URL） |
| 13 | `throwsWhenResponseBodyIsNotAJsonObject` | JSON 数组 / 标量 → 判失败 |
| 14 | `throwsOnServerErrorWithoutRetrying` | 5xx 原样上抛、**不重试**（请求数恰好 1） |
| 15 | `doesNotFollowRedirects` | 302 时重定向目标 `getRequestCount() == 0` |
| 16 | `rejectsBlankUrlWithoutSendingAnyRequest` | url 空 / 缺 → `IllegalArgumentException` 且**一次请求都不发** |
| 17 | `errorMessagesDoNotLeakUrl` | 异常消息**不含 url**（含 key） |
| 18 | `throwsWhenServerExceedsPushTimeout` | 响应头超时 → `ResourceAccessException` |
| 19 | `throwsWhenResponseBodyReadExceedsPushTimeout` | 响应体读取超时 → `RestClientException`（**读 body 改变了超时语义**，与通用档的 `toBodilessEntity()` 不同） |

### 逐条对号（`WeComNotifyToolTest` 4 条）

| # | 用例 | 钉住什么 |
| --- | --- | --- |
| 1 | `notifyThrowsInsteadOfClaimingSuccessWhenWeComRejectsDialect` ★★ | 企微回 `200 + 40008` 时 `notify` **抛异常而非返回「已推送」**——消灭假成功的**最终**断言 |
| 2 | `notifyReturnsSuccessWhenWeComAcceptsTheMessage` | 企微接受时返回成功 |
| 3 | `notifySendsWeComDialectThroughTheRouter` | 经路由发出去的 body 确为企微方言（含 `"msgtype":"text"`、**不含** `msg_type`） |
| 4 | `notifyFailsFastOnTypoInChannelTypeWithoutSendingAnyRequest` | `type=wecomx` → 报错且**请求数 0** |

> **`router()` 建的是完整四档映射表**（`webhook` / `feishu` / `lark` / `wecom`）——只有这样，「发出去的是企微方言」才是有意义的断言；若只挂 `wecom` 一档，这条测试对「路由分派对不对」是盲的。

### `NotifyAssemblyTest` 新增的那条

```java
  @Test
  @DisplayName("装配后的映射表覆盖四个取值：webhook / feishu / lark / wecom（顺序按已知类型排序）")
  void routerKnowsEveryDialectType(@TempDir Path tmp) {
    try (ConfigurableApplicationContext context = CliTestContext.boot(tmp.resolve("oryxos.db"))) {
      NotifyChannelAdapter adapter = context.getBean(NotifyChannelAdapter.class);
      assertThatThrownBy(() -> adapter.send(
              new NotifyTarget("no-such-type", Map.of("url", "https://example.invalid/x")), "内容"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("feishu, lark, webhook, wecom");
    }
  }
```

**为什么用「拿一个不存在的 type 去 send」这种绕法**：方言档是 `new` 在 `notifyChannelAdapter()` 方法体里的，**容器看不见它们**，映射表只能**通过路由行为**观察。这条同时钉住「映射表真的挂上了 `wecom`」与「`wecom` 没被写成别的拼法」。

## 补强③·五、证据三：★ 变异测试——判据不是空断言

**这一步是为了证明上面 19 条断言不空转。** 把 `verifyDelivered` 的三道守卫**临时**换成被明令禁止的那种写法：

```java
// 禁止写法：Jackson 在字段【缺失】时 path().asInt() 也返回 0
JsonNode codeNode = node.path(CODE_FIELD);
if (codeNode.asInt() != 0) { throw new IllegalStateException(...); }
```

**结果：3 条测试变红**——

| 变红的用例 | 为什么它抓得住 |
| --- | --- |
| `throwsWhenNoBusinessCodeFieldAtAll` | 字段缺失时 `path().asInt()` 返回 **0** → 判成成功 |
| `doesNotAcceptFeishuLegacyStatusCodeField` | 同上（响应里没有 `errcode`） |
| `throwsWhenBusinessCodeIsNotAnInteger` | `"0"` / `0.5` 经 `asInt()` 会被强转成 `0` → 判成成功 |

随后**已还原**（`git diff` 无该文件残留）。结论：**fail-closed 判据的三道守卫各自都有测试在守**，删掉任何一道都会有红。

## 补强③·六、证据四：交付物与改动清单

**新增（3 个文件）**：

| 文件 | 行数 | 说明 |
| --- | --- | --- |
| `oryxos-tool/.../notify/WeComNotifyAdapter.java` | 204 | 企微方言档（形状照抄 `FeishuNotifyAdapter` 减去签名层） |
| `oryxos-tool/src/test/.../notify/WeComNotifyAdapterTest.java` | — | 19 条 |
| `oryxos-tool/src/test/.../notify/WeComNotifyToolTest.java` | — | 4 条 |

**改动（4 个文件）**：

| 文件 | 改动 |
| --- | --- |
| `oryxos-cli/.../CliAssemblyConfiguration.java` | import + 映射表加一行 + javadoc 修订注 |
| `oryxos-tool/.../notify/WebhookNotifyAdapter.java` | **javadoc only**（+10 / −2，全在注释块内） |
| `oryxos-cli/src/test/.../NotifyAssemblyTest.java` | +1 条测试 |
| `specs/004-notify-outbound/*`、`CLAUDE.md`、`docs/TechnicalSolution.md` | 文档同步（见 §七） |

## 补强③·七、证据五：反作弊自查——五个受保护文件 `git diff` 为空

```bash
git diff --numstat -- oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/notify/
```

```text
81      7       .../notify/JpaNotifyChannelRegistry.java      ← 补强② 的改动，本次未碰
6       1       .../notify/NotifyChannelRegistry.java         ← 补强② 的改动，本次未碰
10      2       .../notify/WebhookNotifyAdapter.java          ← 本次，javadoc only
```

`RoutingNotifyChannelAdapter.java` / `NotifyChannelAdapter.java` / `NotifyTarget.java` / `NotifyTools.java` / `FeishuNotifyAdapter.java` **五个文件不出现在 diff 里**（`git diff --stat` 对它们输出为空）。

**这是「接口先行」那条升级路径的第二次实际兑现**——第一次是补强①（飞书 / Lark），当时 `NotifyChannelAdapter` / `NotifyTarget` 两文件 `git diff` 为空；本次受保护面扩到五个（加上路由接缝、工具类与已交付的方言档），仍全部为零。

**旁证**：`RoutingNotifyChannelAdapterTest#sourceHasNoVendorStringLiterals` 的厂商清单**本来就含 `"wecom"`**——本次若把 `"wecom"` 写进路由类会**当场变红**。加企微**只改了装配处的映射表**，与设计预期一字不差。

## 补强③·八、证据六：端到端真跑（`/tmp`，已清理）

**全程在 `/tmp/oryxos-wecom-check`，收尾 `rm -rf`，home 未落任何文件。**

```bash
cd /tmp && rm -rf oryxos-wecom-check && mkdir oryxos-wecom-check && cd oryxos-wecom-check
export ORYXOS_JAR=/home/wngbob/oryxos-wngbob/oryxos-boot/target/oryxos.jar
java -jar "$ORYXOS_JAR" init
java -jar "$ORYXOS_JAR" profile create notifier      # 再把 frontmatter 的 tools: [] 改成 tools: [notify]
export WECOM_HOOK_URL='<真实企微群机器人 webhook>'    # 只进环境变量，不落文件、不进输出
java -jar "$ORYXOS_JAR" notify-channel add team-wecom --type wecom \
    --url-env WECOM_HOOK_URL --description '企微团队群（人工验收）'
```

### ① 写入面与库内形态

```text
已添加通知渠道 'team-wecom'（type=wecom）
  地址：${WECOM_HOOK_URL}——发送时从环境变量解析，凭证未落库

通知渠道（共 1 条，按注册名升序，首行为默认渠道）:
  name  type  url  config  description
  team-wecom  wecom  ${WECOM_HOOK_URL}  —  企微团队群（人工验收）
```

库内断言（**只打布尔与键名，不打值**）：

```text
type: wecom
url 是占位符字面量: True
url 里含 key 字样: False
config 键: []
```

- [X] `type` 是 `wecom`（不是 `wecomx` / `wechat` / `qiye`）
- [X] `url` 列是**字面量占位符** `${WECOM_HOOK_URL}`，**真 key 未落库**
- [X] `config` 为空（**企微档没有 `secret`**——与飞书档的差别在此显形）

### ② ★ 正向：真企微群推送

```bash
java -jar "$ORYXOS_JAR" chat --profile notifier \
  --message "调用 notify 工具，参数 content='企微验收测试 001 补强③', channel='team-wecom'。只需调用工具，不要额外解释。"
```

```text
... ToolExecutor : 工具调用完成
... ProviderService : LLM 调用完成
已推送。
```

审计表（**这是判据所在**）：

```text
tool=notify success=1 duration_ms=370 error=None
  result: 已推送
llm_calls: ('deepseek','deepseek-chat',754,1495) / ('deepseek','deepseek-chat',771,566)
```

**`success=1` 在这里是强证据，不是弱证据**：本档的送达判据是 **fail-closed** 的——只有响应体是 JSON 对象、且 `errcode` 存在且为整数且等于 `0`，才返回成功。`370 ms` 是一次真实网络往返（`qyapi.weixin.qq.com`），**对端确实回了 `{"errcode":0}`**。

> **需要人工确认的那半**：`success=1` 证明「企微 API 收下了并判成功」，**不能**证明「消息渲染在了群里」。后者只有人能看。
>
> **✅ 人工确认（2026-10-15，用户执行）：群里收到了那条消息。** 至此正向链路全闭合——模型决定调工具 → 工具按企微方言组报文 → 真对端收下并投递 → **消息出现在真实的企微群里**。这也是 `type=wecom` 这一档的最终判据（前两档同理，见补强① 与补强②·十四）。

### ③ ★ 反向：`type` 拼错必须在本地拦下

```bash
java -jar "$ORYXOS_JAR" notify-channel add team-typo --type wecomx --url-env WECOM_HOOK_URL
java -jar "$ORYXOS_JAR" chat --profile notifier \
  --message "调用 notify 工具，参数 content='不该发出去', channel='team-typo'。"
```

```text
... ToolExecutor : 工具调用失败
推送失败。渠道 `team-typo` 不存在——系统解析出的渠道类型是 `wecomx`（疑似 `wecom` 的拼写错误），
可用的类型只有：feishu、lark、webhook、wecom。
```

审计表：

```text
tool=notify success=0 duration_ms=16
  error="未知的通知渠道类型 'wecomx'；已知类型：feishu, lark, webhook, wecom"
```

- [X] 报错并**列出四个已知类型**（含 `wecom`）
- [X] **`duration_ms=16`**——纯本地判定，**一次请求都没发**（对比正向那条 `370 ms`，这个差值是「零网络往返」的直接证据）
- [X] 失败**落进了 `tool_invocations`**（`success=0`），不是只写日志

### ④ 清理

```text
rm -rf /tmp/oryxos-wecom-check
ls: cannot access '/tmp/oryxos-wecom-check': No such file or directory
```

## 补强③·九、重点 review 清单（按风险排序）

| # | 风险点 | 本次怎么防的 |
| --- | --- | --- |
| 1 | **抄错镜像字段**（`msgtype` vs `msg_type`、`text.content` vs `content.text`） | `bodyUsesMsgtypeWithoutUnderscoreUnlikeFeishu` **双向**断言；`WeComNotifyToolTest#notifySendsWeComDialectThroughTheRouter` 断言 body 含 `"msgtype":"text"` 且**不含** `msg_type` |
| 2 | **判据写回被禁形态**（`path("errcode").asInt() != 0`） | **变异测试实测**：换成该形态会红 3 条（§五） |
| 3 | **`WeComNotifyAdapter` 被声明成 `@Bean`** → `NotifyTools` 注入歧义 | 只在方法体内 `new`；`NotifyAssemblyTest` 起真上下文断言「恰好一个 `NotifyChannelAdapter` Bean」 |
| 4 | **凭证泄漏**（`url` 含 `key`） | 本类**无任何 logger 语句**；异常消息不含 `url`；非 JSON 体**不挂 cause**（Jackson 异常的 message 会带 body 片段）；`errorMessagesDoNotLeakUrl` 钉死；实测输出无 key |
| 5 | **抄飞书的 `StatusCode` 兜底** | 企微无此历史包袱，多认一个字段就是多一处口径；`doesNotAcceptFeishuLegacyStatusCodeField` 钉死 |
| 6 | **空体被判成功** | Jackson 对空体返回 `MissingNode` **不抛异常**，只靠 try/catch 判不出来；故显式判 `isObject()`，两条测试钉死 |
| 7 | 抽公共基类带来的回归 | **刻意不抽**（见 §十），改动面因此只有「一个新增文件 + 装配处一行」 |
| 8 | 跟随重定向绕过白名单 | `Redirect.NEVER` + `doesNotFollowRedirects`（302 时目标请求数 0） |

## 补强③·十、明确不做（边界）

- **钉钉**：用户本次裁决不做。挂账② **不结项**，收窄为「钉钉仍挂」。届时仍是「装配处映射表加一行 + 加一个实现类」——且钉钉与企微**报文完全相同**（`{"msgtype":"text","text":{"content":"..."}}`），差异只在**业务码语义**与**加签**（钉钉把 `timestamp` + HMAC-SHA256 拼进 **URL 查询串**，飞书是放**请求体**）
- **抽公共基类**：企微档与飞书档的 `send()` 骨架 + `parseObject()` + 构造器约 60 行同形，看着该抽。**本次不抽**，三条理由：① 抽 = 改动**已交付且被 19 条测试钉死**的 `FeishuNotifyAdapter`，属软门禁②事件，收益（省 60 行）小于风险；② 项目既有裁决同向——`ProfileLoader` / `McpServerConfigLoader` / `JpaNotifyChannelRegistry` 三处 `envLookup` 刻意不合并；③ 目前只有**两档**方言。**第三次出现（钉钉）才是抽的时机**——届时抽的是 `send()` 模板方法 + `parseObject()`，各档只留 `payload()` 与「业务码字段名 + 错误文案」
- **~~`qyapi.weixin.qq.com` 的沙箱域名白名单~~**：~~归第 24 节。当前挂的是临时实现 `PermissiveSandbox`（**不拦任何东西**）——**本次跑通不能当作「沙箱已就位」的证据**~~
  > **2026-10-17 修订注**：**本条已结项**——第 24 节已交付 `WhitelistSandbox`，`qyapi.weixin.qq.com` **已进随包默认** `http.allowed_domains`（`oryxos-boot/src/main/resources/application.yaml`）；`PermissiveSandbox` 已按裁决 3 整类删除。故**本次跑通现在可以**当作「沙箱已就位」的证据。**原文保留以留痕**。
- **企微「应用消息」**（需 corpid / corpsecret 换 AccessToken）：扩展阶段的专用 Adapter，与「群机器人 webhook」不是一回事
- **企微 text 的 2048 字节上限**：不预校验——超限时企微回 `errcode` 非 0，fail-closed 判据会兜住并落审计。预校验等于在客户端维护一份对端常量
- **`ConfigLoader` 收口 / 加密存储 / KMS**：同补强② 的边界

## 补强③·十一、遗留与下一步

| 项 | 归属 |
| --- | --- |
| **钉钉方言档**（报文同企微，差异在业务码语义 + 加签拼 URL 查询串） | 挂账② 收窄后的剩余部分；届时**也是抽公共基类的时机** |
| `qyapi.weixin.qq.com` / `open.feishu.cn` / `open.larksuite.com` 的沙箱域名白名单 | 第 24 节（`WhitelistSandbox`） |
| `/api/v1/notify-channels` 的 REST CRUD 与管理台 | 第 26 节 |
| `ConfigLoader` 收口三处 `envLookup` | 独立收口，不折进任何一节 |
| ~~**人工确认**：企微群里是否真的出现了「企微验收测试 001 补强③」~~ | **✅ 已确认（2026-10-15，用户执行）**——群里收到了。见 §八 ② |
| **反面真渠道验收**（对端回业务错误时 `notify` 是否报错）：企微档**未在真渠道做** | 该判据由单测 `WeComNotifyToolTest` 覆盖；真渠道只验了**正向**与**路由**两条。见补强③·十二 |

## 补强③·十二、真渠道验收状态（2026-10-15，三档横向对齐）

**为什么单列这一节**：补强①②③ 各自记了「真渠道验收」的片段，但散在三处，容易被读成「三档都验过了」。**它们不等价**——横向对齐一次，免得日后把某一档的结论外推到另一档。

| 档 | `type` | 单测层 | 真渠道**正向**（消息进群） | 真渠道**反向**（对端回业务错误时是否报错） | 真渠道**路由**（`type` 拼错零请求） |
| --- | --- | --- | --- | --- | --- |
| 通用 | `webhook` | ✅ | ✅（2026-10-14，webhook.site 类接收端） | n/a（通用档口径就是「2xx 即送达」，无业务码） | ✅ |
| 飞书 | `feishu` | ✅ | **✅ 2026-10-15（用户确认群里收到消息）** | **✅ 2026-10-15（用户按向导 Step 0~9 全程跑过，结论未发现问题）** | ✅ |
| Lark | `lark` | ✅ | **❌ 未验收——用户 2026-10-15 明确裁决暂不验收** | ❌ 同上 | ✅（同一实现类，随飞书档） |
| 企业微信 | `wecom` | ✅ | **✅ 2026-10-15（用户确认群里收到消息）** | **❌ 未在真渠道做**——该判据由 `WeComNotifyToolTest` 覆盖 | ✅ 2026-10-15（`type=wecomx` → 报错列四个类型、`duration_ms=16` 零请求） |
| 钉钉 | `dingtalk` | — | — | — | — （**未实现**） |

**如实交代三处，别外推**：

1. **飞书的反向验收，细节不写**。用户按向导 Step 0~9 跑过（Step 7 即反向判据），结论是未发现问题；但用户**没有说明用的是三种故障注入做法中的哪一种**（A 飞书侧开「关键词」/ B `--config secret=` 写错 / C 编造 url），也**没有给出飞书实际返回的业务码**，故本报告**不写**——补强②·十三 已刻意删掉未实测的 `19024` / `19021`，不能从这里又把未实测的值放回去。
2. **企微的反向没在真渠道做**。企微档验的是**正向**（消息进群）与**路由**（拼错零请求）两条；「对端回 `errcode` 非 0 时 `notify` 是否真的报错」这条**只在单测层**（`WeComNotifyToolTest#notifyThrowsInsteadOfClaimingSuccessWhenWeComRejectsDialect` + `WeComNotifyAdapterTest` 19 条）验过。**这不等于企微档验完了**——若要在真渠道补，做法是向导 Step 7 的三种注入之一。
3. **Lark 未验收是有意推迟，不是遗漏**。Lark 与飞书**同一套协议、同一个实现类**（`FeishuNotifyAdapter`，装配处 `lark` 指向飞书那一个实例），差异只在域名 `open.larksuite.com`——故它未验收的风险面**等于**飞书那一档（飞书已验），但**不等于已验**。用户 2026-10-15 裁决暂不验收，据此记。

**口径**：本条是**记录性补记**，不改代码、不改接口、不新增公共概念。
