# 第 27 节验收报告 · 全流程串联（一）打通 Agent 主流程

| 项 | 值 |
|---|---|
| 课型 | **串联课**（不开新 feature，不产 spec/plan/tasks——对账清单本身就是交付物） |
| 课件 | [`docs/class/第27节：全流程串联（一）打通 Agent 主流程.md`](../../docs/class/第27节：全流程串联（一）打通%20Agent%20主流程.md) |
| 技术方案 | `docs/TechnicalSolution.md` §12（关键流程） |
| 分支 | `class-27` |
| 日期 | 2026-10-20 |
| 任务完成度 | 课件「本节交付物」**15 / 15** 项落地（逐项见 §二·3） |
| 结论 | **全绿**：`mvn clean verify` **591** 条通过；`HumanTriggerFlowIT` 在真 DeepSeek 上 **5 / 5** 通过 |

> 串联课没有 `spec.md` / `plan.md` / `tasks.md`——按 skill 第 0 步的分流，27 / 28 是「逐条执行对账清单 + 固化成 `@Tag("integration")` 测试类」。本报告即是该课的落盘产物。

---

## 一、这一节到底做了什么

课件要的是把第 16~26 节的能力**串成一条能对账的人推链路**：用户说一句话 → 模型想 → 调工具 → 回答 → 全过程记账。它给出的判据是三根支柱加两条兜底：

| 判据 | 含义 |
|---|---|
| 支柱一 · 对话答得出 | 真模型、真工具、真审计；一次问答的账目**不多不少** |
| 支柱二 · 记忆查得到 | 写得进 `MEMORY.md`，而且**跨会话**读得出 |
| 支柱三 · 工具查得到 | 对外工具清单与 Agent 声明的工具集一致 |
| 兜底 ① · 失败也记账 | Sandbox 拦截 / 工具自身失败 / Provider 故障，都落 `success=false`，系统不崩 |
| 兜底 ② · 三面同源 | CLI 入口写的数据，REST 列表与详情读回来是同一份 |

为此本节补了三块**缺失的零件**（课件点名、此前确实不存在）：

1. **会话列表端点** `GET /api/v1/sessions` —— 第 26 节的 10 个端点里只能按 id 查，管理台「会话」页只能做成「粘贴会话 ID」。本节补 `SessionService.listRecent(int)` + 摘要 DTO，端点与页面同时改。
2. **无凭证可复现的 mock Provider** —— 真 key 的验收在 CI / 别人机器上跑不了。`MockChatModel` 顶掉真厂商，其余（ReActLoop / ToolExecutor / MemoryService / 审计）全是真的，于是整条链路**在任何机器上都能秒级重演**。
3. **工作区根可被测试指定** —— 整机测试要 hermetic，不能读写开发者机器上的真实 `.oryxos/`。`Workspace.current()` 支持 `oryxos.root` 系统属性覆盖，**默认行为一字未改**。

---

## 二、六项证据（DoD）

### 1. `mvn clean verify` 全绿

```bash
./mvnw clean verify -Ddependency-check.skip=true
```

```text
[INFO] Reactor Summary for OryxOS Parent 0.1.0-SNAPSHOT:
[INFO] oryxos-storage ..................................... SUCCESS
[INFO] oryxos-core ........................................ SUCCESS
[INFO] oryxos-provider .................................... SUCCESS
[INFO] oryxos-memory ...................................... SUCCESS
[INFO] oryxos-tool ........................................ SUCCESS
[INFO] oryxos-channel-cli ................................. SUCCESS
[INFO] oryxos-web ........................................ SUCCESS
[INFO] oryxos-cli ......................................... SUCCESS
[INFO] oryxos-boot ........................................ SUCCESS
[INFO] BUILD SUCCESS
[INFO] Total time:  01:50 min
```

测试总数（surefire 报告汇总，`integration` 组按根 pom 配置排除）：

```text
total=591  failures=0  errors=0  skipped=0
oryxos-storage: 14   oryxos-core: 75   oryxos-provider: 11   oryxos-memory: 87
oryxos-tool: 254     oryxos-channel-cli: 0   oryxos-web: 54   oryxos-cli: 96   oryxos-boot: 0
```

与第 26 节结束时的 **578** 条相比 **+13**，逐条可对：`MockChatModelTest` 4、`MockProviderFlowTest` 2、`MockAgentE2ETest` 1、`SessionServiceTest` +2、`SessionApiControllerTest` +3、`AdminConsoleSourceTest` +1。`HumanTriggerFlowIT` 打 `@Tag("integration")`，不进这个数（它单独真跑，见 §二·2）。

### 2. 课件 harness 逐项对号

| 课件要求 | 落点 | 状态 |
|---|---|---|
| `HumanTriggerFlowIT`（`@Tag("integration")`、真 key） | `oryxos-cli/src/test/java/io/oryxlabs/oryxos/cli/HumanTriggerFlowIT.java` | **真跑 5/5 通过** |
| `MockProviderFlowTest`（gate 内、无 key、手工装配） | 同目录 `MockProviderFlowTest.java` | 2 条，`mvn verify` 内 |
| `MockAgentE2ETest`（gate 内、无 key、`@SpringBootTest`） | 同目录 `MockAgentE2ETest.java` | 1 条，`mvn verify` 内 |
| `MockChatModelTest` | `oryxos-provider/src/test/.../MockChatModelTest.java` | 4 条，`mvn verify` 内 |

`HumanTriggerFlowIT` 的手动跑法（真 key + 能出网）：

```bash
DEEPSEEK_API_KEY=sk-... ./mvnw -pl oryxos-cli -am test \
    -DexcludedGroups=none -Dtest=HumanTriggerFlowIT -Dsurefire.failIfNoSpecifiedTests=false
```

```text
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 19.22 s
```

五个方法逐一对应判据：

| 方法 | 判据 | 关键断言 |
|---|---|---|
| `dialogAnswersWithExactlyOneToolCall` | 支柱一 | 历史角色恰为 `user → assistant → tool → assistant`；`llm_calls` **恰 2 条**且都成功；`tool_invocations` **恰 1 条**、`tool_name = http_get`、成功 |
| `memoryIsWrittenAndReadableAcrossSessions` | 支柱二 | `MEMORY.md` 含「北京」；`GET /api/v1/memory` 读得到；**换一个会话**问「我在哪个城市」答复含「北京」 |
| `registeredToolsMatchProfileDeclaration` | 支柱三 | `GET /api/v1/tools` 含 `http_get` / `save_memory` / `recall_memory`，且 `containsAll(Profile.tools())` |
| `failurePathsAreAudited` | 兜底① | `/etc/passwd` 被 Sandbox 拦（`success=0` 一条）；白名单内不存在的文件失败（第二条，`error_message` 非空）；坏模型名 → HTTP **503** 且 `llm_calls` 多出一条 `success=0`；随后 `/health` 仍 200 |
| `cliAndRestSeeTheSameSession` | 兜底② | `CliChannel.send(...)` 写出 `cli:local:weather-agent`；REST 详情里 `channel = cli`、首条消息含「北京」；**会话列表里同一条摘要**且 `messageCount` 与详情条数相等 |

> **支柱一的对账是这一节最硬的一条**：课件写「`llm_calls` 恰 2 条、`tool_invocations` 恰 1 条、全部成功——不多不少」。真模型实测**一次就对上**，没有出现「模型多调一次工具」这类漂移。

### 3. 本节交付物逐项存在性核对

| 交付物（课件原文） | 落点 | 核对 |
|---|---|---|
| `SessionManager.listRecent(int)` + 实现 | `SessionService.listRecent` / `JpaSessionService.listRecent`（本仓 `SessionService` 即课件的 `SessionManager`） | `grep -rn listRecent` 3 处命中（接口 / 实现 / 调用方） |
| `SessionApiController` 新增 `GET /api/v1/sessions` + 摘要 DTO | `SessionApiController.java:118`、`dto/SessionSummaryView.java` | ✅ |
| `MockChatModel`（`mock` 厂商、无 key）+ 工厂接线 + 配置校验放行 | `MockChatModel.java`、`ProviderConfiguration`、`ProviderProperties.requiresApiKey` | ✅ |
| `OryxOsRuntime` 工作区根支持 `oryxos.root` 覆盖 | `Workspace.current()`（本仓无 `OryxOsRuntime`，等价物是 `Workspace` + `CliAssemblyConfiguration.workspace()`） | ✅ 默认路径不变 |
| `HumanTriggerFlowIT` | 见上 | ✅ |
| `MockProviderFlowTest` / `MockAgentE2ETest` / `MockChatModelTest` | 见上 | ✅ |
| 管理台「会话」页接 `GET /api/v1/sessions` | `views/SessionsView.vue`（列表 + 点开看详情） | ✅ 仍只读（`AdminConsoleSourceTest` 8 条全绿） |
| mock provider 示例进配置样板 | `oryxos-boot/src/main/resources/application.yaml`（随包声明 `mock`）+ `bin/config.yaml.example`（用法说明；该文件是「两级键 + 标量值」子集，表达不了列表，故厂商清单的唯一真相源仍是 `application.yaml`） | ✅ |
| README 端点表加 `GET /api/v1/sessions` | `README.md`：10 → 11，新增端点表 + 无凭证 walkthrough | ✅ |

```text
=== 交付物存在性（ls）===
oryxos-provider/src/main/java/io/oryxlabs/oryxos/provider/MockChatModel.java
oryxos-provider/src/test/java/io/oryxlabs/oryxos/provider/MockChatModelTest.java
oryxos-cli/src/test/java/io/oryxlabs/oryxos/cli/MockProviderFlowTest.java
oryxos-cli/src/test/java/io/oryxlabs/oryxos/cli/MockAgentE2ETest.java
oryxos-cli/src/test/java/io/oryxlabs/oryxos/cli/HumanTriggerFlowIT.java
oryxos-web/src/main/java/io/oryxlabs/oryxos/web/controller/dto/SessionSummaryView.java

=== listRecent ===
oryxos-core/src/main/java/io/oryxlabs/oryxos/core/session/SessionService.java:75
oryxos-core/src/main/java/io/oryxlabs/oryxos/core/session/JpaSessionService.java:95
oryxos-web/src/main/java/io/oryxlabs/oryxos/web/controller/SessionApiController.java:122
```

### 4. 前序节全部测试回归绿

上表 9 个模块全部 `SUCCESS`，含第 16~26 节的全部存量用例（`oryxos-tool` 254 条、`oryxos-cli` 96 条）。**没有删过任何断言、没有 `@Disabled`、没有放宽阈值**——本节唯一被修改的存量测试是 `AdminConsoleSourceTest`（新增一条判据，不改原判据）与 `SessionApiControllerTest` / `SessionServiceTest`（只新增用例）。

### 5. H4 六条全局不变量自查

| 不变量 | 自查结果 |
|---|---|
| ① 涉外 IO 首行过 `Sandbox.enforce` | 本节**没有新增**涉外 IO；`failurePathsAreAudited` 正是走 `ToolExecutor → FileTools.enforce` 这条既有路径判的 |
| ② 成败都落审计 | 真链路实测：success 路径（支柱一）与 failure 路径（兜底①）双向验证 |
| ③ 无明文 key | `git grep "sk-[A-Za-z0-9]\{20,\}"` 于已跟踪文件**零命中**。（本机 `bin/config.yaml` 有真实 key，但该文件已 gitignore、`git ls-files` 确认未跟踪） |
| ④ `session_id` 只在会话服务内拼 | 拼接公式仍只有 `SessionId` 一处；本节新增的列表/摘要只是**读取** `session.id()` |
| ⑤ 无 Reactor / `CompletableFuture` / 自建线程池 | `grep -rn "reactor.core\|CompletableFuture\|Executors.new\|new Thread("` 于 `src/main` **零命中** |
| ⑥ 无 Spring AI 自动工具执行路径 | `internalToolExecutionEnabled(false)` 仍是唯一设置点（`ProviderService:127`） |

### 6. 静态门禁与测试抓红（如实留痕）

| # | 抓到什么 | 怎么修的 |
|---|---|---|
| 1 | **P3C `AvoidComplexConditionRule`**：`ProviderProperties:68` 的 `!CREDENTIAL_FREE_PROVIDER.equals(name) && (entry.apiKey() == null \|\| entry.apiKey().isBlank())` 被判「条件太复杂」 | 拆成 `requiresApiKey(name)` + `isBlank(value)` 两个具名判定，语义不变（`mvn verify` 第一次就是栽在这条） |
| 2 | **FindSecBugs `IMPROPER_UNICODE`**：`SessionApiController.normalizeStatus` 里的 `toLowerCase(Locale.ROOT)` | 就地 `@SuppressFBWarnings` + 三条理由（ROOT 口径 / 目标值只有两个 ASCII 字面量 / 不一致的方向是拒绝即 fail-closed），与本仓 `WhitelistSandbox.checkHttpUrl` 的既有豁免同型 |
| 3 | **`AdminConsoleSourceTest.onlyGetMethodIsUsed`**：我写的 `SessionsView.vue` 注释里出现「`DELETE`」字样 | 改注释措辞。**这是判据在起作用**——它不在乎你是不是真的要发写请求，只要源码里出现写动词就红 |
| 4 | **自己抓到自己**：`HumanTriggerFlowIT` 初稿用 `http.allowed_domains[5]` 往随包列表尾部**追加**一项，Spring Binder 报 `The elements [http.alloweddomains[5]] were left unbound`、上下文起不来 | 改成整表替换（`List.of(五个默认域名 + wttr.in)`）。对 YAML 里已有的列表只补一个更大的下标绑不上——这条已写进测试注释，免得下次再踩 |

---

## 三、变更总结（给 reviewer 的导读）

### 3.1 改动点

**oryxos-core（+2 个文件改动）**
- `session/SessionService.java`：新增 `List<Session> listRecent(int limit)` 接缝方法（第 26 节后第一个新增的会话读方法）。
- `session/JpaSessionService.java`：实现它——排序交给数据库（`lastActiveAt` 走索引），截断在应用层（Spring Data 的 `findAll(Sort)` 没有 limit）。
- `session/SessionServiceTest.java`：+2 条（倒序 + 限量、非正数返回空且不查库）。

**oryxos-provider（+2 新文件、2 处改动）**
- `MockChatModel.java`（新）：实现 `ChatModel`，按脚本走「先要一次 `save_memory`、拿到工具结果后给最终答复」。**轮次判定只看最后一条 `UserMessage` 之后有没有 `ToolResponseMessage`**——否则同一会话的第二轮用户消息会被上一轮的工具结果骗过去、跳过工具。
- `ProviderConfiguration.java`：显式映射表加一行 `mock → new MockChatModel()`（仍**不按类型扫描 Bean**）。
- `ProviderProperties.java`：`SUPPORTED_PROVIDERS` 加 `mock`，并把「api-key 非空」这条校验对 `mock` 豁免（`requiresApiKey` 具名化，见 §二·6）。
- `MockChatModelTest.java`（新）：4 条。

**oryxos-cli（+3 新测试文件、1 处改动）**
- `workspace/Workspace.java`：`current()` 支持系统属性 `oryxos.root` 覆盖工作区**根本身**；不设置时仍是当前目录下的 `.oryxos/`。类 javadoc 里把第 18 节「不引入可配置根」的结论挂了带日期的修订注（原文不重写）。
- `HumanTriggerFlowIT.java`（新）：见 §二·2。
- `MockProviderFlowTest.java`（新）：手工装配整条链路（真实 `ReActLoop` / `ToolExecutor` / `MemoryServiceImpl` / `JpaSessionService`，只有模型是假的），2 条。
- `MockAgentE2ETest.java`（新）：真 `@SpringBootTest`（RANDOM_PORT）+ 真 SQLite + `oryxos.root` 临时工作区，1 条。

**oryxos-web（+1 新 DTO、3 处改动）**
- `controller/dto/SessionSummaryView.java`（新）：列表摘要，**刻意不带 `messages`**。
- `controller/SessionApiController.java`：新增 `GET /api/v1/sessions`（`?status=` 可选）+ `normalizeStatus` / `toSummary` 两个私有方法。
- `controller/SessionApiControllerTest.java`：+3 条（摘要不含 messages、状态过滤、非法状态 400）。
- `src/main/frontend/src/views/SessionsView.vue`：从「粘贴会话 ID 查询」改成「读列表 → 点一行看详情」；仍只读。
- `AdminConsoleSourceTest.java`：+1 条 `sessionsViewReadsTheListEndpoint`（见 §三·2 第 5 条）。

**oryxos-boot / 文档 / 脚本**
- `application.yaml`：`oryxos.providers` 增加 `mock` 一项（无 key）。
- `bin/config.yaml.example`：新增「没有凭证也想跑一遍」段（说明 mock 用法，并说明 `start.sh` 恒要求真实 key、mock 自测不走它）。
- `README.md`：10 → 11 个端点（两处），新增端点表，新增「不用任何模型凭证跑通全链路」小节。

### 3.2 重点 review 清单（按风险排序）

1. **`Workspace.current()` 的系统属性覆盖是全局的**（`Workspace.java:63-69`）。`-Doryxos.root=…` 一旦出现在生产启动参数里，工作区会被整体换个地方——**不报错、静默生效**。这是测试要的（进程级隔离），但也是本节唯一一个「能改变运行期落点」的开关。判据：它只被测试设置（`grep -rn "oryxos.root"` 只有两个测试类 + 本类），且不设置时行为与第 18 节完全一致。
2. **`mock` 被豁免 api-key 校验**（`ProviderProperties.validate` / `requiresApiKey`）。厂商清单是**编译期常量**，所以这个豁免不会因为配置文件写错而扩散到别的厂商；但它是「校验规则里的一个洞」，改它时请连同 `MockChatModelTest` 一起看。
3. **会话列表的 `status` 过滤作用在「最近 100 条」窗口之内**（`SessionApiController.list` 的 javadoc 已写明）。先取 100 再筛，而不是先筛全表——`listRecent(int)` 是课件给的那个接缝，它不带状态参数。核心阶段单机够用，全量分页是扩展阶段的事。**这是有意的语义选择，不是遗漏。**
4. **`MockChatModel` 的轮次判定**（`MockChatModel.call`）：必须只看「最后一条用户消息之后」。写错成「整段历史里有没有 tool 消息」会让同一会话的第二轮用户消息直接拿到最终答复、不再调工具——`MockChatModelTest` 第 3 条钉的正是这个。
5. **`AdminConsoleSourceTest.sessionsViewReadsTheListEndpoint` 是新增判据，不是改判据**：映射表用的是 `contains`，而详情路径 `/api/v1/sessions/{id}` 本身包含列表路径前缀——只把它加进映射表等于没判，所以另写一条卡调用字面形状（列表调用不带 id、详情调用带 id）。
6. **静态门禁的两处妥协**：`ProviderProperties` 的条件拆分（纯重构）、`SessionApiController.normalizeStatus` 的 `IMPROPER_UNICODE` 就地豁免（附理由）。按父 pom 口径「就地注解比全局 exclude 更可审计」。

### 3.3 如何验证

```bash
# ① 全量门禁（含 Spotless / P3C / Checkstyle / SpotBugs+FindSecBugs）
./mvnw clean verify -Ddependency-check.skip=true
# 预期：BUILD SUCCESS，591 条测试全绿

# ② 本节的无 key harness（10 秒级）
./mvnw -pl oryxos-cli -am test -Dtest='MockProviderFlowTest+MockAgentE2ETest' \
    -Dsurefire.failIfNoSpecifiedTests=false
# 预期：3 条通过

# ③ 真模型的人推链路对账（需要 key + 能出网）
DEEPSEEK_API_KEY=sk-... ./mvnw -pl oryxos-cli -am test \
    -DexcludedGroups=none -Dtest=HumanTriggerFlowIT \
    -Dsurefire.failIfNoSpecifiedTests=false
# 预期：5 条通过；支柱一的对账是 llm_calls=2 / tool_invocations=1

# ④ 只读纪律的机器判卷
./mvnw -pl oryxos-web test -Dtest=AdminConsoleSourceTest
# 预期：8 条通过（含新增的 sessionsViewReadsTheListEndpoint）

# ⑤ 不变量复查
grep -rn "internalToolExecutionEnabled" --include=*.java oryxos-*/src/main   # 只有 ProviderService 一处
git grep -n "sk-[A-Za-z0-9]\{20,\}"                                          # 零命中
grep -rn "oryxos.root" --include=*.java oryxos-*/src                        # 只有 Workspace + 两个测试类
```

### 3.4 剩余人工项（harness 判不了的部分）

1. **管理台会话页的目视复核**（浏览器）。判据：`serve` 后打开 `/admin/`，「会话」页能看到会话列表（会话 ID / Agent / 状态 / 渠道·用户 / 消息数 / 最后活跃），点一行能看到历史。**注意这条与第 26 节 §四·第 6 项合并**——概览页、定时任务页、本次的会话页改动都还没有人工目视过。**2026-10-20 已执行**：用户按向导 Step 7.1~7.9 逐页走查（概览 / 会话列表 + 详情 / 工具 / Agent / 定时任务 / 长期记忆 / 运行状态 / 只读 / 子路由直开），未报告页面级失败；抓到两处向导判据与页面字面不符（7.3 的 `truncated`、7.6 的页面文案），是**向导写错**、非产品缺陷，已修。**第 26 节 §四·第 6 项里的深色偏好切换与 logo 目视已于同日另行确认通过**，本项全部收口。
2. **无凭证 walkthrough 的手工走查**（照 README「不用任何模型凭证跑通全链路」一节）：`chat --profile mock-demo` → `MEMORY.md` 有那行 → `serve` 后管理台看得到。**2026-10-20 已执行**：用户按向导 Step 0~6 走完（mock 会话、记忆文件、审计账目、`serve` 后的 11 个端点），未报告失败；Step 9 收尾时发现向导的 `serve.pid` 相对路径问题（第三节已记），残留进程已停。

> **2026-10-20 交付后人工验收留痕**：向导落点 [`manual-test-guide.md`](./manual-test-guide.md)（Step 0~9）。用户实跑抓到**三处向导问题**，均已修：① 7.3 写「`truncated` 为否」——页面未截断时**什么都不渲染**；② 7.6 引用的「执行历史不在这里」是 `CLAUDE.md` 的转述，页面原文是「运行时状态不在这里」「本页只呈现配置面」；③ Step 9 的 `serve.pid` 是相对路径，在工作区被删后找不到、进程残留占着 8080。**Step 8 真链路已重跑成功**（2026-10-20，补 `cd` 到仓库根之后）：`HumanTriggerFlowIT` 5/5 通过，真 DeepSeek 模型、真会话/审计落库；第 26 节 §四·第 6 项遗留的**深色偏好切换与 logo 目视**同日均已由用户确认通过。至此本节人工项全部收口。
3. **Lark 档、Mem0 档**：这两档的状态与本节无关，仍是第 19 / 22 节登记的已知边界（Lark 有意推迟、Mem0 永久不验）。
4. **验收 Demo**（第 31 节）：本节只是把链路打通并对账，三个 Demo Agent 归第 31 节。

---

## 四、备注

- **未 commit / 未 push / 未运行 `package.sh`**——按 skill 纪律，同步时机由用户决定。
- **交付后对账（2026-10-20，交付当天）**：检查「本节还有哪些遗留问题」时发现端点总数「10 → 11」在多处仍是现在时表述，已按本仓惯例处理——**外部三处同步**（`README.md` 三行、`website/` 四页中英、`Home.vue` 中英）直接改为 11；**历史 / 规划文档一律挂带日期修订注、不重写原文**：`docs/TechnicalSolution.md`（§7.2 已有注，本次补第三周计划与第 12 章总述两处）、`specs/000-runtime-kernel/contracts/rest-api.md`（已有注）、`specs/009-web-service/{spec.md, contracts/admin-console.md, contracts/rest-endpoints.md, quickstart.md, manual-test-guide.md, acceptance-report.md}`；`.claude/skills/oryxos-admin-ui/SKILL.md` 补 `GET /api/v1/sessions` 一行并修正页序号。`docs/DemandAnalysis.md` 与 `docs/AiProgrammingGuide.md` 里的「10 个端点」**有意保留**——它们是需求分析与实施指南的成文口径，当前真相源是 `docs/TechnicalSolution.md` §7.2 与 `CLAUDE.md`。
- `docs/class/` 自 2026-10-14 起随仓库跟踪，本报告引用的课件路径可直接点开。
- 本节没有新增任何 REST 路径以外的对外概念：新增端点 `GET /api/v1/sessions`、新增系统属性 `oryxos.root`、新增厂商名 `mock`、新增接缝方法 `listRecent(int)`——**四项全部在课件「本节交付物」里点了名**，未触发软门禁 ①。
- 选择「`mock` 进随包 `application.yaml`」而不是只让测试自己注入：无凭证尝试是这个 Provider 存在的唯一理由，藏在测试里等于没交付。代价是随包配置多一行（无凭证、无副作用），已在文件里写明它是「接线自测」用的固定脚本。
