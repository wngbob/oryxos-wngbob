# 第 22 节验收报告：Memory（记忆）

| 项 | 值 |
| --- | --- |
| Feature | `specs/006-memory-service/`（spec / plan / tasks / quickstart / research / data-model / contracts×3 / checklists） |
| Spec | [spec.md](spec.md) |
| Plan | [plan.md](plan.md) |
| Tasks | [tasks.md](tasks.md)（**37/37 完成**） |
| Quickstart | [quickstart.md](quickstart.md) |
| 分支 | `class-22`（未 commit、未 push） |
| 日期 | 2026-10-15 |
| 任务完成度 | **37 / 37** |
| 结论 | **通过**——`mvn clean verify` 全绿（451 条测试，含 P3C / SpotBugs / FindSecBugs / Checkstyle / Spotless）；课件 harness 六个测试类逐个对号；本节交付物 19 个文件存在且非空；前序五节测试全绿；H4 六条不变量逐条自查完毕，其中 ① 的两条涉外 IO 路径**均已有裁决**（记忆文件 IO 交付时裁决；Mem0 出站 HTTP 于 2026-10-15 裁决，见 §5.1）。剩余人工项 4 条，见 §6.4——**其中 3 条已于 2026-10-16 由用户人工跑过并通过，1 条（Mem0 真连）经裁决有意不做**，见 §6.5。 |

---

## 一、`mvn clean verify` 全绿

命令（本机 WSL，JDK 21；`dependency-check` 因需联网单独跳过，其余门禁全开）：

```bash
export JAVA_HOME=/usr/lib/jvm/java-1.21.0-openjdk-amd64
./mvnw -o clean verify -Ddependency-check.skip=true
```

关键输出（完整日志留档于 `/tmp/verify-22c.log`）：

```
[INFO] Reactor Summary for OryxOS Parent 0.1.0-SNAPSHOT:
[INFO] OryxOS Parent ...................................... SUCCESS [  2.618 s]
[INFO] oryxos-storage ..................................... SUCCESS [ 12.519 s]
[INFO] oryxos-core ........................................ SUCCESS [  8.369 s]
[INFO] oryxos-provider .................................... SUCCESS [  5.097 s]
[INFO] oryxos-memory ...................................... SUCCESS [ 11.046 s]
[INFO] oryxos-tool ........................................ SUCCESS [ 14.791 s]
[INFO] oryxos-channel-cli ................................. SUCCESS [  1.906 s]
[INFO] oryxos-web ......................................... SUCCESS [  3.021 s]
[INFO] oryxos-cli ......................................... SUCCESS [ 19.143 s]
[INFO] oryxos-boot ........................................ SUCCESS [  2.723 s]
[INFO] BUILD SUCCESS
[INFO] Total time:  01:21 min
```

各模块测试条数（每模块末尾的聚合行）：

| 模块 | 条数 | 说明 |
| --- | --- | --- |
| `oryxos-storage` | 14 | 本节 +5（`MemoryEntryRepositoryTest`） |
| `oryxos-core` | 45 | 本节 0 改动 |
| `oryxos-provider` | 7 | 本节 0 改动 |
| **`oryxos-memory`** | **87** | **本节从 0 到 87** |
| `oryxos-tool` | 218 | 本节 0 改动 |
| `oryxos-cli` | 80 | 本节 +8（`MemoryAssemblyTest`） |
| **合计** | **451** | 上一节 349 → 本节 **451**（+102） |

`oryxos-memory` 内部逐类：

```
Tests run: 28 ... in io.oryxlabs.oryxos.memory.store.MemoryStoreContractTest
Tests run: 19 ... in io.oryxlabs.oryxos.memory.store.Mem0MemoryStoreTest
Tests run: 12 ... in io.oryxlabs.oryxos.memory.store.MarkdownMemoryStoreTest
Tests run:  9 ... in io.oryxlabs.oryxos.memory.store.SqliteMemoryStoreTest
Tests run: 10 ... in io.oryxlabs.oryxos.memory.MemoryServiceImplTest
Tests run:  9 ... in io.oryxlabs.oryxos.memory.builtin.MemoryToolsTest
Tests run: 87, Failures: 0, Errors: 0, Skipped: 0
```

静态门禁全部生效：Spotless（google-java-format）、阿里 P3C（PMD 6.55，`skipPmdError=false`）、Checkstyle、SpotBugs（`effort=Max` / `threshold=Low`）+ FindSecBugs、OWASP Dependency-Check（本机跳过）。本节为过门禁共**红了 8 次**，逐条记在 §7。

---

## 二、课件 harness 映射表：六个测试类逐个对号

课件 `docs/class/第22节：Memory 实现与代码讲解.md` §四给出的 harness 六个测试类，全部存在且非空：

| 课件测试类 | 本仓落地 | 条数 | 课件守点 | 对号 |
| --- | --- | --- | --- | --- |
| `MemoryStoreContractTest` | [MemoryStoreContractTest.java](../../oryxos-memory/src/test/java/io/oryxlabs/oryxos/memory/store/MemoryStoreContractTest.java) | 28 | 参数化遍历三档，同一套断言全过；契约一~四 | ✅ |
| `MarkdownMemoryStoreTest` | [MarkdownMemoryStoreTest.java](../../oryxos-memory/src/test/java/io/oryxlabs/oryxos/memory/store/MarkdownMemoryStoreTest.java) | 12 | 字符串截断边界、区块 header 解析 | ✅ |
| `SqliteMemoryStoreTest` | [SqliteMemoryStoreTest.java](../../oryxos-memory/src/test/java/io/oryxlabs/oryxos/memory/store/SqliteMemoryStoreTest.java) | 9 | 手工建表脚本能建能读、归档 `LIMIT` 生效、`LIKE` 检索 | ✅ |
| `Mem0MemoryStoreTest` | [Mem0MemoryStoreTest.java](../../oryxos-memory/src/test/java/io/oryxlabs/oryxos/memory/store/Mem0MemoryStoreTest.java) | 19 | mock `RestClient`：`append` 请求体带 scope、`recall` 转发查询 | ✅ |
| `MemoryToolsTest` | [MemoryToolsTest.java](../../oryxos-memory/src/test/java/io/oryxlabs/oryxos/memory/builtin/MemoryToolsTest.java) | 9 | scope 缺省写归档；未命中返回「没有找到相关记忆」不抛异常 | ✅ |
| `MemoryServiceTest` | [MemoryServiceImplTest.java](../../oryxos-memory/src/test/java/io/oryxlabs/oryxos/memory/MemoryServiceImplTest.java) | 10 | 取长期记忆时核心记忆完整在内 | ✅（**类名不同，见下**） |

### 2.1 关键回归点（课件写出代码的两个参数化测试）

课件 §四末尾用代码块给出的两条，**断言逻辑逐条保真**，方法名按本仓纪律译为英文、课件原文进 `@DisplayName`：

| 课件方法名（中文） | 本仓方法名 | `@DisplayName` |
| --- | --- | --- |
| `截断只裁归档区_核心记忆一字不能少` | `truncation_onlyTrimsArchival_coreMemorySurvivesIntact` | 截断只裁归档区_核心记忆一字不能少 |
| `写入后立刻可读_不允许有缓存` | `append_isImmediatelyVisible_noCaching` | 写入后立刻可读_不允许有缓存 |

两条都是 `@ParameterizedTest` + `@MethodSource("allStores")`，**三档后端统一跑**（`markdown` / `sqlite` / `mem0-替身`），断言逐条对应课件：

```java
// 截断只裁归档区_核心记忆一字不能少
memory.append(CORE_MEMORY, MemoryScope.CORE);
for (int i = 0; i < ARCHIVE_ROWS; i++) { memory.append(ARCHIVE_PREFIX + i, MemoryScope.ARCHIVAL); }
assertThat(loaded).contains(CORE_MEMORY);              // 课件：核心区完整
assertThat(loaded).doesNotContain(ARCHIVE_PREFIX + 0); // 课件：归档区最早的被裁掉
assertThat(loaded).contains(ARCHIVE_PREFIX + 499);     // 课件：保留的是最近的

// 写入后立刻可读_不允许有缓存
memory.append("刚记的事", MemoryScope.ARCHIVAL);
assertThat(memory.load()).contains("刚记的事");           // 课件：下一次 load 立即可见
assertThat(memory.recallByKeyword("刚记的事")).isNotEmpty(); // 课件：检索同样立即命中
```

`ARCHIVE_ROWS = 500`，与课件的 `for (int i = 0; i < 500; i++)` 一致；灌入量对 Markdown 档 4000 字符阈值与另两档 100 条上限都远超，故同一条断言对三档都成立。

### 2.2 契约测试的「参数源恰好三档」自守

参数化测试对「参数少了一个」天然沉默——删掉一行参数不会有任何断言变红，那正是契约测试最容易悄悄退化成「只测了一档」的地方。故补了一条普通 `@Test` 把参数源本身钉死：

```java
@Test @DisplayName("参数源恰好三档_少一档或多一档都要红")
void allStoresCoversExactlyThreeTiers() {
  assertThat(names).containsExactlyElementsOf(TIER_NAMES);  // markdown / sqlite / mem0-替身
}
```

### 2.3 一处类名与课件不同（已报批，见 §7 备注）

课件写 `MemoryServiceTest`，本仓落地为 `MemoryServiceImplTest`。**同一件事**：课件那张表里描述的是「验 `MemoryService` 这一层的行为」，本仓的命名纪律是「测试类名 = 被测实现类名 + `Test`」，而接口 `MemoryService` 没有可实例化的行为、被测的是 `MemoryServiceImpl`。`MemoryService` 接口本身的行为由 `MemoryAssemblyTest`（装配面）与 `MemoryToolsTest`（消费面）间接覆盖。

### 2.4 课件交付物清单与 harness 之间的一处口径差异（已处置）

课件 §三「本节交付物」把 `MemoryScope` 枚举列为本节代码交付物，但 harness 六个测试类**没有一个**直接断言它——它是骨架期（000-runtime-kernel，commit `2655d61`）已交付的既有类型（本仓 `MemoryScope.java` 的 `git diff` 为空，见 §6.1 未触碰）。故本节**未新建**该文件，只在契约测试与三档实现里消费它。

---

## 三、本节交付物逐项存在性核对

命令：

```bash
for f in <下列清单>; do [ -s "$f" ] && echo OK || echo MISSING; done
```

| # | 交付物（课件 §三 + plan 落位） | 文件 | 行数 | 状态 |
| --- | --- | --- | --- | --- |
| 1 | `MemoryService` 接口 | `oryxos-memory/.../memory/MemoryService.java` | 36 | ✅ |
| 2 | `MemoryServiceImpl` 门面实现 | `oryxos-memory/.../memory/MemoryServiceImpl.java` | 78 | ✅ |
| 3 | `MemoryTools`（save_memory / recall_memory） | `oryxos-memory/.../memory/builtin/MemoryTools.java` | 97 | ✅ |
| 4 | `LongTermMemoryStore` 后端接口 | `oryxos-memory/.../memory/store/LongTermMemoryStore.java` | 59 | ✅（骨架期已有，本节一字未改） |
| 5 | `MemoryScope` 枚举 | `oryxos-memory/.../memory/store/MemoryScope.java` | 15 | ✅（骨架期已有，本节一字未改） |
| 6 | `MemoryCapability` 三态枚举 | `oryxos-memory/.../memory/store/MemoryCapability.java` | 18 | ✅（骨架期已有，本节一字未改） |
| 7 | `MarkdownMemoryStore`（默认档） | `oryxos-memory/.../memory/store/MarkdownMemoryStore.java` | 187 | ✅ |
| 8 | `SqliteMemoryStore` | `oryxos-memory/.../memory/store/SqliteMemoryStore.java` | 147 | ✅ |
| 9 | `Mem0MemoryStore` | `oryxos-memory/.../memory/store/Mem0MemoryStore.java` | 280 | ✅ |
| 10 | `InMemoryMemoryStore`（测试期替身 / 内存假 Mem0） | `oryxos-memory/.../memory/store/InMemoryMemoryStore.java` | 84 | ✅ |
| 11 | `MemoryEntry` 实体 | `oryxos-storage/.../storage/entity/MemoryEntry.java` | 93 | ✅ |
| 12 | `MemoryEntryRepository` | `oryxos-storage/.../storage/repository/MemoryEntryRepository.java` | 66 | ✅ |
| 13 | `MemoryStoreContractTest` | `oryxos-memory/src/test/.../store/MemoryStoreContractTest.java` | 311 | ✅ |
| 14 | `MarkdownMemoryStoreTest` | `oryxos-memory/src/test/.../store/MarkdownMemoryStoreTest.java` | 211 | ✅ |
| 15 | `SqliteMemoryStoreTest` | `oryxos-memory/src/test/.../store/SqliteMemoryStoreTest.java` | 207 | ✅ |
| 16 | `Mem0MemoryStoreTest` | `oryxos-memory/src/test/.../store/Mem0MemoryStoreTest.java` | 306 | ✅ |
| 17 | `MemoryToolsTest` | `oryxos-memory/src/test/.../builtin/MemoryToolsTest.java` | 178 | ✅ |
| 18 | `MemoryServiceImplTest` | `oryxos-memory/src/test/.../MemoryServiceImplTest.java` | 261 | ✅ |
| 19 | `MemoryAssemblyTest`（装配六条硬约束） | `oryxos-cli/src/test/.../cli/MemoryAssemblyTest.java` | 217 | ✅ |

**表**：`memory_entries`（`oryxos-storage/src/main/resources/db/schema.sql:83`）+ 索引 `idx_memory_scope`（:91）：

```sql
CREATE TABLE IF NOT EXISTS memory_entries (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    scope      VARCHAR(16) NOT NULL,
    content    TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_memory_scope ON memory_entries (scope);
```

**文件**：`.oryxos/memory/MEMORY.md`（`oryxos init` 幂等创建，内容为 `## 核心记忆\n\n## 归档记忆\n`）。

**配置**：`oryxos.memory.backend`（`markdown` | `sqlite` | `mem0`，默认 `markdown`）+ `oryxos.memory.mem0.base-url`（`${MEM0_BASE_URL:}`）+ `oryxos.memory.mem0.user-id`（`${MEM0_USER_ID:oryxos}`），落 `oryxos-boot/src/main/resources/application.yaml:61-73`。

**端到端实测**（`/tmp/oryxos22` 临时工作区，用刚构建的 `oryxos-boot/target/oryxos.jar`）：

```
$ java -jar oryxos-boot/target/oryxos.jar init
$ java -jar oryxos-boot/target/oryxos.jar tool list
Tool（共 15 个）: builtin (15):
  ...
  recall_memory  按关键词检索长期记忆
  save_memory    记住一件值得长期记住的事
annotation (0):
mcp (0):
```

`builtin` 由第 20 节的 **13** 变成本节的 **15**——两个新工具的**工具名与描述逐字等于契约**（`specs/006-memory-service/contracts/memory-service.md` §2.1 / §2.2），且**注册表与过滤逻辑一行未改**（经同一条 `ToolRegistry.registerAnnotated` 自动进注册表）。`oryxos init` 另确认写出 `.oryxos/memory/MEMORY.md`（两 header）与 `.oryxos/USER.md`（含「本文件由你手工维护，OryxOS 只读不写」注释）。临时工作区已 `rm -rf`。

---

## 四、前序节全部测试回归绿

`mvn clean verify` 是整仓 reactor 全量跑，不存在「只跑本节」的口径。逐模块数字见 §1，此处只记**跨节契约证据**：

| 前序节 | 受本节影响 | 回归结果 |
| --- | --- | --- |
| 16 Provider | 无 | 7 条全绿 |
| 17 ReAct / AgentService / `MemorySource` 接缝 | **接缝被实现**（`MemoryServiceImpl implements MemorySource`），`MemorySource.java` 本身 `git diff` 为空 | `oryxos-core` 45 条全绿 |
| 18 CLI / Session | `CliAssemblyConfiguration` 新增两个 `@Bean`，命令集与命令名一字未改（仍 15 条，`CliCommandTreeTest` 3 条钉死） | `oryxos-cli` 80 条全绿 |
| 19 Notify | 无（`NotifyChannelAdapter` / `NotifyTarget` / `NotifyTools` 均未触碰） | `oryxos-tool` 218 条全绿 |
| 20 Tool / MCP | `ToolRegistry` **零改动**，仅装配处多一行 `registerAnnotated(memoryTools, "builtin")`；`ToolOnboardingCostTest`（2 条）仍绿——它读三个横切关注点的源文件，断言其中不含任何新工具名，本节的 `save_memory` / `recall_memory` 同样不含 | 218 条全绿 |

**两个空接缝的让位**：`EmptyMemorySource` 带 `@ConditionalOnMissingBean(MemorySource.class)`，本节 `MemoryServiceImpl` 一落地就自动让位，**没有回头删代码**（`MemoryAssemblyTest.memorySource_hasExactlyOneBean_andItIsTheFacade` 用 `getBeansOfType(MemorySource.class)` `hasSize(1)` + `isInstanceOf(MemoryServiceImpl.class)` 双向钉死：空实现没让位会变 2、门面没装配上会变 0）。第 20 节的 `ToolCatalog` 空实现已于上一节同样让位给 `ToolRegistry`——**两个空接缝至此清空**。

---

## 五、H4 六条全局不变量逐条自查

### ① 涉外 IO 首行过 `Sandbox.enforce` —— ✅ **两条路径，均不走沙箱，均已有裁决**

| 涉外 IO | 处置 | 状态 |
| --- | --- | --- |
| 记忆**文件** IO（`MarkdownMemoryStore` 读/写 `MEMORY.md`） | **不走 `Sandbox.enforce`**（仲裁 3）：路径由本类构造时定死（`oryxosDir/memory/MEMORY.md`），模型无法把它指向别处，不构成越界面；`docs/TechnicalSolution.md` §6.7 点名的沙箱范围只有 FileTools / ShellTools / HttpTools（仲裁 3 时的原文；2026-10-15 新增要点三后，§6.7 另含 NotifyTools 与「配置驱动的出站不在范围内」的口径） | ✅ 已裁决（交付时） |
| Mem0 档**出站 HTTP**（`Mem0MemoryStore` 的 `POST /memories`、`GET /memories`、`POST /search`） | **不走 `Sandbox.enforce`**——结构上做不到：`Sandbox` 契约定义在 `oryxos-tool`，而 FR-021 / 仲裁 3 禁止 `oryxos-memory` 依赖 `oryxos-tool`。**用户裁决（2026-10-15）：不过沙箱是对的**——沙箱管的是模型可指定目标的工具调用，Mem0 地址来自 `application.yaml`、不来自模型 | ✅ **已裁决（2026-10-15）** |

**裁决依据（2026-10-15，用户裁决候选 ③）**：

1. **本仓内的既有裁决链**——仲裁 3（`spec.md:65`，交付时用户已裁决）早已定下原则：沙箱管的是「**模型可以指定目标**的工具」；`docs/TechnicalSolution.md` §6.7 点名的沙箱调用方从来只有 FileTools / ShellTools / HttpTools。Mem0 的地址是装配期常量（`application.yaml` + 环境变量），模型碰不到——这与「记忆文件 IO 不过沙箱」是**同一条理由**，只是换了个 IO 形态。旁证：**上游**章程 VI 的射程原文同为「**工具调用** MUST 经 `SandboxChecker` 白名单校验」（本仓章程 VI 是审计数据落库、并无此句——2026-10-15 复核更正，此处此前误记为本仓原文）。
2. **上游逐字裁决过同一件事**——`/home/wngbob/oryxos` 的 `specs/006-memory-pluggable/acceptance-report.md:39`：「Mem0 档是配置的外部服务（**非工具涉外 IO**）」；`plan.md:32` 在宪法 VI 检查项里记同一结论。且从 `class-22` 守到 `main` HEAD **一步没退**：`Mem0MemoryStore` 在两处 grep `OutboundGuard|Sandbox|enforce` **均零命中**（HEAD 上它已长了真集成测试 `Mem0FlowIT` 与 `apiKey` 构造器，**仍无守卫**），`oryxos-memory/pom.xml` **从未**长过 `oryxos-tool` 依赖边，上游 §6.7 的沙箱调用方名单里**从来没有 Memory**。

3. **如实记一笔差异**（2026-10-15 复核补记）：文件 IO 数据不出本机，而 Mem0 出站会把 Agent 生成的记忆内容**发往外部服务**——「模型拨不动指针」覆盖的是目标可控性，不覆盖误配 / 被篡改 base-url 的数据外泄面（域名白名单本来拦得住这类）。残余风险小：base-url 与白名单本是同一管理员配的、窗口期（`PermissiveSandbox`）内本来就没有白名单、装配期有 `requireMem0BaseUrl` 非空校验；真要管这类出站，形态就是 §6.7 要点三记下的 `OutboundGuard` 接缝。

**口径已落文档**：`docs/TechnicalSolution.md` §6.7 新增**要点三**——沙箱范围 = 模型可指定目标的工具调用；装配期配置决定的出站地址不在范围内。

**两条已否决的候选**：

- ① 把 `Sandbox` 契约下移到 `oryxos-core`——**未采纳，但记下形态**：上游给「模块自建 HTTP 出站」造的正解是 `oryxos-core/.../channel/OutboundGuard.java`（单方法 `@FunctionalInterface`，`void check(String url)`，装配层适配成 `url -> sandbox.enforce(new SandboxAction(HTTP_REQUEST, url))`，18 个 channel 模块消费、63 个文件命中）。这是「契约下移」的**外科手术版**，模块不依赖 `oryxos-tool`。**现在不建**——按本仓「第三次出现才抽象」的判据（三处 `envLookup` 不合并、两档方言 `send()` 骨架不抽基类），这会是第二个此类点，第二次就抽早了。
- ② 开一条 `oryxos-memory → oryxos-tool` 依赖边——**已明确否决**：上游零先例，且直接撞 FR-021。本节**未加任何依赖边**。

另注：第 20~23 节挂的沙箱仍是**临时实现 `PermissiveSandbox`**（只打 WARN、不做白名单校验），`WhitelistSandbox` 归第 24 节。故即使 Mem0 档接上 `enforce`，本节窗口期内也不会真拦——真正的判定点在第 24 节。

> **2026-10-17 修订注（沙箱窗口期已关闭）**：上面这条**已失效**。第 24 节（2026-10-17）已交付 `WhitelistSandbox`、`PermissiveSandbox` 已按裁决 3 **整类删除**，故那个「真正的判定点」**已经到过且已判完**——沙箱现在真在校验。**但本条要守的那件事本身不变**：本节对 Mem0 档的裁决是「**不过沙箱是对的**」（依据是 Mem0 地址来自 `application.yaml`、不来自模型，模型无法把它指向别处），这条裁决**不依赖沙箱是否已就位**，窗口期关闭**不改变**它。**原文保留以留痕**。

### ② LLM 调用成败都落 `llm_calls`、工具执行成败都落 `tool_invocations` —— ✅

```bash
grep -rn "tool_invocations\|llm_calls" oryxos-memory/src/main --include=*.java
```

仅两处命中，**都在 javadoc 散文里**（`MemoryTools.java:25`、`MemoryTools.java:63`，解释「为什么非法 scope 要抛异常而不是返回失败结果」），无任何审计落库代码。落库归 `ToolExecutor` 的既有路径（第 17 节），`MemoryTools` 只负责抛异常 / 返回 `ToolResult`，**不新增审计逻辑**——这正是 FR-016「加工具的恒定成本」的兑现。

### ③ grep 无明文 key —— ✅

`Mem0MemoryStore` 的 `baseUrl` 含凭证，本类三重防护：不打日志、异常消息不含它、`toString()` 返回固定文案 `"Mem0MemoryStore"`（显式覆写，防止有人后来加 IDE 生成的 `toString()` / `@Data` 把凭证打进日志）；异常**刻意不挂 cause**（`ResourceAccessException` 的 message 会把请求 URL 原样带出来）。`Mem0MemoryStoreTest` 与 `MemoryAssemblyTest.mem0WithoutBaseUrl_failsNamingOnlyTheVariable` 双向钉死「消息含变量名 `MEM0_BASE_URL`、不含 `${`、不含 `http`」。`application.yaml` 里两个 mem0 子键走 `${ENV_VAR}` 占位；`backend` 是字面量（D8 的刻意设计——切档走改配置，不走环境变量）。

### ④ `session_id` 只在 `SessionManager` 内拼接 —— ✅

```bash
grep -rn "session_id\|sessionId" oryxos-memory/src/main --include=*.java   # 空
```

`oryxos-memory` 全程不引用 `Session` 类型——长期记忆与会话历史是两件事（FR-013），本节刻意**不提供课件的 `buildContext(Session)`**（仲裁 2 / D1，见 §6.2 R2）。

### ⑤ 无 Reactor / `CompletableFuture` / 自建线程池 —— ✅

```bash
grep -rn "CompletableFuture\|reactor\|Executors\.\|new Thread" oryxos-memory/src/main --include=*.java   # 空
```

`Mem0MemoryStore` 的超时收口在实现内（`HttpClient.newBuilder().connectTimeout(10s)` + `requestFactory.setReadTimeout(10s)`），不推给注入方，也不用异步——同步阻塞模型（章程原则 8）。

### ⑥ 无 Spring AI 自动工具执行路径 —— ✅

```bash
grep -rn "ToolCallingManager\|ToolCallbackProvider\|ChatClient\|EnableToolCalling" oryxos-memory/src/main --include=*.java   # 空
```

`MemoryTools` 是普通 `@Tool` 注解 Bean，由 `CliAssemblyConfiguration` 显式 `registerAnnotated` 传入注册表；`ToolRegistry` 里**没有任何 `@Tool` 扫描逻辑**（第 20 节既定口径，本节未改）。工具调度仍完全归 `ReActLoop` + `ToolExecutor`。

---

## 六、变更总结（给 reviewer 的导读）

以 `git status --short` / `git diff --stat` 实测为准。**本节新增 28 个未跟踪文件（5047 行——代码与测试 17 个 / 3011 行 + specs 文档 11 个 / 2036 行）+ 修改 7 个已跟踪文件（+211 / −16）**。

### 6.1 改动点

#### 新增——`oryxos-memory`（本节主战场，模块从 0 到 87 条测试）

| 文件 | 行 | 动机 |
| --- | --- | --- |
| `memory/MemoryService.java` | 36 | 对上统一门面，三类操作：读长期记忆文本 / 记一条 / 按关键词查。**`extends MemorySource`**——即第 17 节 core 侧那道窄接缝本身 |
| `memory/MemoryServiceImpl.java` | 78 | 门面实现。`recall()` 按 `store.capabilities()` 用**经典冒号 `switch`** 路由（P3C/ASM 解析不了箭头 switch）；`HYBRID_BUILTIN` 走到时**明确报错**而不是静默降级 |
| `memory/builtin/MemoryTools.java` | 97 | `save_memory` / `recall_memory` 两个 `@Tool` Bean。工具名用 `@Tool(name = ...)` **显式钉住**（模型引用它的唯一凭据） |
| `memory/store/MarkdownMemoryStore.java` | 187 | 默认档。两个 header 切区；截断**物理上只碰归档段**（核心段的字符串根本不进截断函数）；`preamble` 原样写回 |
| `memory/store/SqliteMemoryStore.java` | 147 | 结构化档。核心区查询**不带任何分页参数**——「永不被截断」在 SQL 层是「那条语句里根本没有 LIMIT」；`no such table` 包成带指引的消息 |
| `memory/store/Mem0MemoryStore.java` | 280 | 外部集成档。REST 三调用；`final` 类（见 §6.2）；异常不挂 cause |
| `memory/store/InMemoryMemoryStore.java` | 84 | 测试期替身（课件的「内存假 Mem0」），同时被契约测试当第三档参数 |

测试（`oryxos-memory/src/test/`，87 条）：`MemoryStoreContractTest`（311 行，28 条）、`Mem0MemoryStoreTest`（306 行，19 条）、`MarkdownMemoryStoreTest`（211 行，12 条）、`SqliteMemoryStoreTest`（207 行，9 条）、`MemoryServiceImplTest`（261 行，10 条）、`MemoryToolsTest`（178 行，9 条）、`MemoryTestApplication.java`（19 行，测试用 Spring 引导）。

#### 新增——`oryxos-storage`（与既有表同口径）

| 文件 | 行 | 动机 |
| --- | --- | --- |
| `entity/MemoryEntry.java` | 93 | `memory_entries` 实体。**列名与语义逐字照本节 `specs/006-memory-service/data-model.md`，不新增列** |
| `repository/MemoryEntryRepository.java` | 66 | 三条查询：核心区升序全量 / 归档区降序取最近 N / 归档区 `LIKE` 检索 |
| `test/.../MemoryEntryRepositoryTest.java` | 233 | 5 条，`@DataJpaTest` + 真 SQLite |

#### 新增——`oryxos-cli`（测试）

`test/.../cli/MemoryAssemblyTest.java`（217 行，8 条）：装配六条硬约束 + 声明顺序源码扫描。

#### 修改——已跟踪文件（`git diff --stat`）

```
 CLAUDE.md                                          | 21 +++--
 docs/TechnicalSolution.md                          |  2 +
 oryxos-boot/src/main/resources/application.yaml    | 13 +++
 .../oryxos/cli/CliAssemblyConfiguration.java       | 97 ++++++++++++++++++++++
 .../io/oryxlabs/oryxos/cli/CliTestContext.java     | 34 ++++++--
 oryxos-memory/pom.xml                              | 41 ++++++++-
 oryxos-storage/src/main/resources/db/schema.sql    | 19 ++++-
 7 files changed, 211 insertions(+), 16 deletions(-)
```

| 文件 | 动机 |
| --- | --- |
| `CliAssemblyConfiguration.java`（+97） | **本节唯一的装配点**：新增 `memoryService()`（按 `oryxos.memory.backend` 造对应档）、`memoryTools()`、私有 `requireMem0BaseUrl()`，并在 `toolRegistry()` 里多一行 `registerAnnotated(memoryTools, "builtin")` |
| `application.yaml`（+13） | 三个配置键，全带注释说明「为什么带空默认值」「为什么环境变量名不带 `oryxos.` 前缀」 |
| `schema.sql`（+19） | 新增 `memory_entries` 表 + `idx_memory_scope` 索引；**另修一处过期注释**——`notify_channels` 那段原写「第 19 节只读，运行期本表恒为空是预期状态」，自 2026-10-15 补强② 起已不成立 |
| `oryxos-memory/pom.xml`（+41） | 新增对 `oryxos-storage` 的依赖边（`memory_entries` 的 Repository）；显式化 `spring-web` / `jackson-databind` 两条既有传递边（Mem0 的 RestClient 与报文解析） |
| `CliTestContext.java`（+34/−16） | **测试辅助文件，不在 `oryxos-memory` 内**：新增一个带 `Map<String,Object> extraProperties` 的 `boot()` 重载，原无参重载委托给它。用于把 `oryxos.memory.backend` 喂进真上下文 |
| `CLAUDE.md` | 记第 22 节状态：四个行为契约、三档递进、三处已报批偏离（含 Mem0 沙箱边界裁决）、配置键、`oryxos-memory → oryxos-storage` 依赖边、表数 6→7、内置工具 13→15 |
| `docs/TechnicalSolution.md`（+2） | **交付后补**（2026-10-15 裁决）：§6.7 新增**要点三**——沙箱范围 = 模型可指定目标的工具调用；装配期配置决定的出站地址（Mem0 档）不在范围内。这是本节唯一一处触碰设计文档的改动 |

#### 新增——`specs/006-memory-service/`（11 个文档）

`spec.md` / `plan.md` / `tasks.md` / `research.md` / `data-model.md` / `quickstart.md` / `contracts/{memory-store,memory-service,memory-config}.md` / `checklists/requirements.md` / 本报告。

#### 未触碰（受保护面）

`oryxos-memory/.../store/MemoryScope.java`、`LongTermMemoryStore.java`、`oryxos-core/.../memory/MemorySource.java` 三个文件 **`git diff` 为空**——契约先行那条路径的兑现。`oryxos-tool` / `oryxos-core` 模块**零源码改动**（`oryxos-core/pom.xml` 也 `git diff` 为空）。

### 6.2 重点 review 清单（按风险排序）

**R1（架构决策，最高风险）— Mem0 出站 HTTP 不过 `Sandbox.enforce`：已裁决为正确做法。**
`Mem0MemoryStore.java:123-131 / 137-146 / 174-184` 三处 REST 调用是本节第二个涉外 IO 点。`Sandbox` 契约在 `oryxos-tool`，`oryxos-memory` 不许依赖它（FR-021）——**结构上做不到**。**2026-10-15 用户裁决：不过沙箱是对的**，理由与上游一致（章程 VI 的射程是「工具调用」，Mem0 地址来自配置不来自模型），详见 §5.1。口径已写进 `docs/TechnicalSolution.md` §6.7 要点三。**请 reviewer 复核的是这条裁决本身**，不是「为什么没做」。相关：`spec.md` FR-021、`plan.md` 依赖方向段、上游 `specs/006-memory-pluggable/acceptance-report.md:39`。

**R2（架构决策）— `MemoryService implements MemorySource`，不提供课件的 `buildContext(Session)`。**
`MemoryService.java:1-36`。已报批（仲裁 2 / D1）：课件的 `buildContext(Session)` 会把「会话历史」和「长期记忆」混进同一个方法返回，直接违反本节自己的 FR-013（MUST NOT 由同一方法混合返回）；且 core 的 `MemorySource.longTermMemory(Profile)` 是既有接缝，实现它才叫「接口先行」。**代价**：`PromptBuilder` 组装时调的是 `longTermMemory(profile)` 而不是 `buildContext(session)`，课件那段「多一步 `memoryService.buildContext(session)`」的散文**在本仓不成立**，且 `MemoryService` 与 `PromptBuilder` 之间没有直接的类引用（靠 `MemorySource` 类型解耦）。

**R3（跨节契约兼容性）— `EmptyMemorySource` 的让位依赖 `@Bean` 声明顺序，是隐式契约。**
`CliAssemblyConfiguration.java` 中 `memoryService()` MUST 声明在 `memorySource()` 之前（`@ConditionalOnMissingBean(MemorySource.class)` 按声明顺序求值）。顺序反了不会编译失败，而是运行期 `NoUniqueBeanDefinitionException`，且错误信息指向 `PromptBuilder` 的注入点、**不指向这里**。故 `MemoryAssemblyTest.memoryService_isDeclaredBeforeTheEmptySource` 直接扫源文件比 `indexOf` 钉死，找不到方法签名时 `fail(...)` 而不是静默跳过。同理 `memoryTools()` 的注册 MUST 在 `connectAll(registry)` 之前（重名时保留先注册者）。

**R4（静态门禁妥协点）— 三处「为过门禁而写、看着多余」。**
① `Mem0MemoryStore` 声明 `final`（`Mem0MemoryStore.java:39`）：两个构造器都会在参数非法时抛异常，可被继承的类这么做会开 finalizer 攻击面（FindSecBugs `CT_CONSTRUCTOR_THROW`）；② `SqliteMemoryStore` 用**消息文本** `"no such table"` 认「表不存在」（`:42`，常量 `NO_SUCH_TABLE`）：SQLite JDBC 把「表不存在」和别的 SQL 错误包成同一类 `DataAccessException`，类型上分不开——**真换数据库要一起改**，故集中成常量；③ `MarkdownMemoryStore` 存 `memoryDir` 字段只为 `Files.createDirectories` 不用 `getParent()`（SpotBugs `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE`）。

**R5（课件骨架同构性）— 课件 harness 的中文测试方法名已译成英文。**
两个关键回归点见 §2.1。另有 `MemoryStoreContractTest` 里 5 条本节新增的契约四补充断言（大小写不敏感 / 截断无关 / 未命中形状 / 能力恒定 / 条目视图不受截断影响），课件未写代码，是本节按契约补的。

**R6（跨节契约兼容性）— `CliTestContext.java` 是被触碰的测试辅助文件。**
它不在 `oryxos-memory` 内，属第 18 节 `oryxos-cli` 的测试基建。改动是**纯增量**：新增带属性的重载，原无参重载委托给它，签名与行为不变（`ToolAssemblyTest` / `NotifyAssemblyTest` / `OryxToolContractTest` / `NotifyChannelRoundTripTest` 既有 4 个用它的测试类全绿；第 5 个用户是本节自己的 `MemoryAssemblyTest`）。请确认这个增量可接受。

**R7（口径核对）— `oryxos-core → oryxos-storage` 那条依赖边不是本节新增的。**
`oryxos-core/pom.xml:41` 确实依赖 `oryxos-storage`，`git diff HEAD` 为空——它是 000-runtime-kernel 骨架期的既成事实（core 直接依赖 storage 写审计）。**不要把它误读成本节开的新边**。

### 6.3 如何验证

```bash
export JAVA_HOME=/usr/lib/jvm/java-1.21.0-openjdk-amd64
cd /home/wngbob/oryxos-wngbob

# ① 全量门禁（本节 DoD 的那一条）
./mvnw -o clean verify -Ddependency-check.skip=true
#    预期：BUILD SUCCESS，10 个模块全 SUCCESS，合计 451 条测试 0 失败

# ② 只跑本节两个模块的测试（快）
./mvnw -o test -pl oryxos-memory -am -Dspotless.check.skip=true -Dpmd.skip=true \
       -Dcheckstyle.skip=true -Dspotbugs.skip=true -Ddependency-check.skip=true
#    预期：oryxos-memory 87 条全绿

# ③ 关键回归单测（三档参数化跑同一套断言）
./mvnw -o test -pl oryxos-memory -am -Dtest=MemoryStoreContractTest \
       -Dspotless.check.skip=true -Dpmd.skip=true -Dcheckstyle.skip=true \
       -Dspotbugs.skip=true -Ddependency-check.skip=true
#    预期：28 条全绿；失败报告里参数名会直接指出是哪一档破的契约

# ④ 装配六条硬约束（必须起真上下文）
./mvnw -o test -pl oryxos-cli -am -Dtest=MemoryAssemblyTest \
       -Dspotless.check.skip=true -Dpmd.skip=true -Dcheckstyle.skip=true \
       -Dspotbugs.skip=true -Ddependency-check.skip=true
#    预期：8 条全绿

# ⑤ 依赖方向：oryxos-memory 不得依赖 oryxos-tool（只应命中注释）
grep -n "oryxos-tool" oryxos-memory/pom.xml
#    预期：3 处命中，全部在 <!-- --> 注释里（第 24 / 32 / 47 行），无 <dependency> 声明

# ⑥ 受保护面一字未动
git diff HEAD --stat -- \
  oryxos-memory/src/main/java/io/oryxlabs/oryxos/memory/store/MemoryScope.java \
  oryxos-memory/src/main/java/io/oryxlabs/oryxos/memory/store/LongTermMemoryStore.java \
  oryxos-core/src/main/java/io/oryxlabs/oryxos/core/memory/MemorySource.java \
  oryxos-core/pom.xml
#    预期：空输出

# ⑦ H4 四条 grep 型不变量（②③④⑤⑥，全部应为空）
grep -rn "session_id\|sessionId\|CompletableFuture\|reactor\|Executors\.\|new Thread" \
     oryxos-memory/src/main --include=*.java
grep -rn "ToolCallingManager\|ToolCallbackProvider\|ChatClient\|EnableToolCalling" \
     oryxos-memory/src/main --include=*.java
#    预期：两条都空

# ⑧ 端到端：新工具进注册表
./mvnw -o clean package -DskipTests -Dspotless.check.skip=true -Dpmd.skip=true \
       -Dcheckstyle.skip=true -Dspotbugs.skip=true -Ddependency-check.skip=true
mkdir -p /tmp/oryxos22 && cd /tmp/oryxos22
java -jar /home/wngbob/oryxos-wngbob/oryxos-boot/target/oryxos.jar init
java -jar /home/wngbob/oryxos-wngbob/oryxos-boot/target/oryxos.jar tool list
#    预期：Tool（共 15 个）: builtin (15): ... recall_memory / save_memory ... annotation (0): mcp (0):
#    收尾：rm -rf /tmp/oryxos22
```

### 6.4 剩余人工项（harness 判不了）

> **逐步骤的向导见 [manual-test-guide.md](manual-test-guide.md)**（Step 0~9，含命令、判据与「最容易踩的八个坑」）。本节只列项与状态。
>
> **状态（2026-10-16）**：第 1 / 2 / 4 项**已由用户按向导 Step 0~8 全程人工跑过，结论「都通过了」**；第 3 项（Mem0 真连）经用户裁决**有意不做**。详见 §6.5。

1. **三档切换**：`oryxos.memory.backend` 分别设成 `markdown` / `sqlite`，各跑一次真对话，`save_memory` 写入、下一轮 `longTermMemory()` 带上——同一段对话、同一个体感，只是底下换了后端。这是「墙」最直观的人工证据。**✅ 已验（向导 Step 6）**。
2. **真模型完整走一遍**：对话里说一句值得记的话，Agent 主动调 `save_memory`；开新会话，system prompt 里带着核心记忆——「始终在场」在真实链路里的体感。~~本节从未接过真模型~~ → **✅ 已验（向导 Step 3 / 4 / 5），2026-10-16**。
3. **Mem0 档真连一次** —— **⛔ 有意不做（用户裁决，2026-10-16）**，不是遗漏、也不是待办。原措辞保留以留痕：本节只做 REST 集成与 mock 测试，**从未连过真 server**——报文（`/memories`、`/search`、`results` 字段名）是**我们自己定的最小契约，从未与真实 Mem0 服务对过**；真接上时第一个要核的就是 `results` 字段名（`Mem0MemoryStore.RESULTS_FIELD` 的 javadoc 已写明）。**连带后果如实记**：这一档的报文契约由「待验证」正式转为**永久记录的已知边界**——`Mem0MemoryStore` 与任何真实 Mem0 服务**一次都没对过话**，单测只证明「请求发得出去、body 形状对、响应按我们的假设解析得对」，不证明「真实 Mem0 会这样回」。将来接真服务时，**不要把它当成已验证过的档**。
4. **`MEMORY.md` 与 `USER.md` 的角色**：`USER.md` 全程只读（已 grep 确认——全仓只有 `ContextLoader.DEFAULT_BOOTSTRAP` 读它、`InitCommand` 幂等建模板，**无任何写入路径**），`MEMORY.md` 能被 Agent 写入。这条已机器核对，课件把它列为人工项，故一并列出供复核。**✅ 已复核（向导 Step 1 / 8）**。

### 6.5 人工验收记录（2026-10-16，用户执行）

**执行方式**：用户按 [manual-test-guide.md](manual-test-guide.md) 的 Step 0~8 全程人工执行（真 DeepSeek 模型、真 `oryxos.jar`、`/tmp` 下的临时工作区）。

**结论**：**Step 0~8 全部通过，未报告任何一步失败。**

**覆盖面（别把 9 步当成等价的「都验过了」）**：

| 步骤 | 验的是什么 | 状态 |
| --- | --- | --- |
| Step 0 | 构建产物与源码一致 | ✅ 过（`oryxos.jar` mtime 2026-10-16 15:24，晚于全部源码，无需重建） |
| Step 1 | `oryxos init` 建出的 `MEMORY.md` 初始形态 | ✅ 过 |
| Step 2 | 造出带 `save_memory` / `recall_memory` 的 Agent（`tools: []` 陷阱） | ✅ 过 |
| Step 3 | markdown 档：Agent **真去调** `save_memory`（审计表为凭据）+ 文件真有那条记忆 | ✅ 过 |
| Step 4 | 隔离「文件 → prompt」这一半（不依赖模型调工具） | ✅ 过 |
| Step 5 | **决定性证据**：删库后新会话仍答得出不可猜的事实，且 `messages_json` 里没有旧对话 | ✅ 过 |
| Step 6 | 切 `sqlite` 档，同一段对话再走一遍（记忆换地方） | ✅ 过 |
| Step 7 | 非法后端取值报错、不静默退回 | ✅ 过 |
| Step 8 | `USER.md` 只读（无写入路径）+ 依赖方向自查 | ✅ 过 |
| Step 9 | Mem0 档真连 | ⛔ **有意不做**（见 §6.4 第 3 项） |

**如实留痕**：用户未逐步说明各步的具体输出（例如 Step 6 的反向确认做没做），故本节**只记「整体通过」这个结论，不写步骤级细节**——与第 19 节对「用户未说明 Step 7 用的哪种故障注入」的处理同口径。

**这一步的实际分量**：它把本节从「harness 判卷 + 从未接过真模型」推进到**真链路已验证**——判据 2（Agent 主动调 `save_memory`）与判据 3（跨会话核心记忆在场）此前只有单测与代码推演支撑，现在有真人真模型的观察。**仍未验证的只有 Mem0 那一档**。

---

## 七、备注

- **本节未 commit、未 push、未跑 `package.sh`**——同步时机由你决定。`git status --short` 见 §6.1。
- **本节抓红并修复共 8 处**，如实记录：
  - **1 处测试自身写错**（`MemoryAssemblyTest.inMemoryStore_isNotSelectable`）：我原本断言 `hasMessageNotContaining("InMemory")`，但把 `"InMemoryMemoryStore"` 当 backend 值传了进去，而报错消息**正确地回显了调用方给的那个值**。改法：backend 值改 `"inmemory"`，断言改成 `hasMessageNotContaining(InMemoryMemoryStore.class.getSimpleName())`，并加注释说明「回显的那个值不是机密，笼统断言『消息不含 InMemory』没有意义——真正要钉的是合法取值清单里没有类名」。
  - **4 处 P3C / PMD 违规**（`oryxos-memory` 阻塞 `mvn clean verify`）：`Mem0MemoryStore:239` 与 `SqliteMemoryStore:120` 的 `UndefineMagicConstantRule`（`"results"` / `"no such table"` → 各抽成常量并写 javadoc 说明为什么是文本判据）；`Mem0MemoryStore$RestCall:262` 与 `SqliteMemoryStore$RepositoryCall:131` 的 `AbstractMethodOrInterfaceMethodMustUseJavadocRule`（给 `run()` 补 javadoc）。
  - **3 处 SpotBugs / FindSecBugs 命中**：`MarkdownMemoryStore.write:171` 的 `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE`（`memoryFile.getParent()` 可能为 null → 改存 `memoryDir` 字段并直接用）；`Mem0MemoryStore` 两个构造器的 `CT_CONSTRUCTOR_THROW`（类声明 `final` + javadoc 说明 finalizer 攻击面）。
  - **另修 1 处过期注释**（Step 5 软停点已报批）：`schema.sql` 里 `notify_channels` 的「第 19 节只读，运行期本表恒为空是预期状态」——该口径自 2026-10-15 补强② 起已失效。
- **三处已报批偏离**（MUST NOT 当成缺陷，详见 §6.2 R1 / R2 与 `CLAUDE.md` 对应条目）：① 记忆**文件** IO 不过 `Sandbox.enforce`（仲裁 3，交付时已裁决）；② `MemoryService implements MemorySource` 而非课件的 `buildContext(Session)`（仲裁 2 / D1，已裁决）；③ **Mem0 出站 HTTP 不过 `Sandbox.enforce`**（2026-10-15 用户裁决候选 ③，口径写进 `docs/TechnicalSolution.md` §6.7 要点三，见 §5.1）——交付时记为「未裁决的挂账」，**该挂账已清**。
- **交付后裁决（2026-10-15）：Mem0 出站 HTTP 挂账已清。** 交付时 §5.1 那条「未裁决的挂账」经用户裁决采纳**候选 ③**——明确「配置驱动的出站地址不归沙箱管」。裁决前先核了上游 `/home/wngbob/oryxos`，三条独立证据同向：① 章程 VI 的射程原文是「**工具调用** MUST 经 `SandboxChecker` 白名单校验」；② 上游 `specs/006-memory-pluggable/acceptance-report.md:39` 逐字裁决过同一件事，且从 `class-22` 守到 `main` HEAD 一步没退；③ 上游 §6.7 沙箱调用方名单里从来没有 Memory。**本次改动三处**：`docs/TechnicalSolution.md` §6.7 新增要点三（唯一一处触碰设计文档的改动）、`CLAUDE.md` 该条从「挂账」改写为「已裁决」、本报告 §1 结论 / §5.1 / §6.1 / §6.2 R1 / §7 同步。**未改任何代码**——`git status` 里 `oryxos-memory/` 的源码与测试一字未动，故 `mvn clean verify` 结论（451 条全绿）不受影响，**未重跑**。
- **报告复核与修订（2026-10-15）**：本报告经一轮不信任复核（重跑 verify + 全部引用回源核对），共修 16 处。**2 处事实错误**——① §5.1 裁决依据 1 误把**上游**章程 VI 的「工具调用 MUST 经 `SandboxChecker` 白名单校验」记成**本仓**章程原文（本仓章程 VI 是审计数据落库，全仓无 `SandboxChecker`；已改挂仲裁 3 + §6.7 既有范围，上游章程降为正确归属的旁证）；② §6.1 误称 `memory_entries` 列名「逐字照 `specs/000-runtime-kernel/data-model.md`」（该目录全目录无此表，实为照本节自己的 `data-model.md`）。**4 处数字/说法失实**——OutboundGuard 消费方为 18 个 channel 模块（非「约 60 个」）、装配新增 `@Bean` 为两个（非三个）、两个 mem0 子键走占位（非「三个配置键全部」）、抓红 8 处（非 7 处）。**10 处计数/交叉引用/笔误**——diff 总数 7 个文件 +211、未跟踪文件 5047 行、结论行人工项 §4→§6.4、四处失效的「见 §5.x」、契约节号 §2.3→§2.1/2.2、「既有 5 个」→4 个、文档 10→11 个、「第 17 节骨架期」→骨架期 000-runtime-kernel、「P3D」→「P3C」、§5.1 表 row 1 的 §6.7 范围描述补「仲裁 3 时的原文」限定（要点三增补后该节已另含 NotifyTools）。复核结论：**候选 ③ 方向合适**，并补记文件 IO 与 Mem0 出站的差异（§5.1 依据 3）；其余 25+ 项抽查（重跑 verify 451 条、19 个交付物行数、上游逐字引用、装配顺序、受保护面 diff 为空等）全部属实。`CLAUDE.md` 对应条目已同步更正。
- **一处类名与课件不同**（Step 5 软停点已报批）：课件 `MemoryServiceTest` → 本仓 `MemoryServiceImplTest`，同一件事，理由见 §2.3。
- **本机环境限制**：`pwsh` 在本机不可用，故 `.specify/scripts/powershell/*.ps1` 一个都没跑；`FEATURE_DIR` 由 `.specify/feature.json`（`{"feature_directory": "specs/006-memory-service"}`）直接给出。`.specify/extensions.yml` 不存在，所有 before/after hooks 静默跳过（`before_specify` 不建分支，分支 `class-22` 为手工 `git checkout -b`）。
- **`docs/class/` 自 2026-10-14 起随仓库跟踪**（commit `ae2ec23`），本节引用的课件正文即 `docs/class/第22节：Memory 实现与代码讲解.md`，已在库内。
- **下一节**：第 24 节（Sandbox 白名单，`WhitelistSandbox`）——`PermissiveSandbox` 的窗口期在那里关闭。本节 Mem0 出站那条**已裁决不走沙箱**（§5.1），故第 24 节**不需要**为它做任何事；若第 24 节顺手要管装配期出站，形态是 §6.7 要点三记的 `OutboundGuard` 接缝。
