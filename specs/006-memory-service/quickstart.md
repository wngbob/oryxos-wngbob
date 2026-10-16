# Quickstart: Memory（第 22 节）验证向导

**Feature**: `specs/006-memory-service` | **Date**: 2026-10-15 | **Plan**: [plan.md](./plan.md)

> 本文件是**可复制执行的验证向导**。契约细节看 [contracts/](./contracts/)，数据形态看 [data-model.md](./data-model.md)，本文件不重复它们。

**环境前提**：JDK 21 + 仓库自带 `./mvnw`。构建前 `export JAVA_HOME=/usr/lib/jvm/java-1.21.0-openjdk-amd64`（WSL 侧）。临时工作区一律 `/tmp/<name>`，收尾 `rm -rf`。

---

## 0. 全量门禁（本节"完成"的定义）

```bash
./mvnw clean verify
```

**预期**：BUILD SUCCESS，**349 条旧测试 + 本节新增全绿**。这条命令同时跑 Spotless / P3C（PMD 6.55）/ Checkstyle / SpotBugs+FindSecBugs / OWASP Dependency-Check。本地想跳过 OWASP：`-Ddependency-check.skip=true`。

改完代码先跑 `./mvnw spotless:apply`（google-java-format）。

---

## 1. 只跑本节测试（快）

```bash
./mvnw -pl oryxos-memory -am test
./mvnw -pl oryxos-storage -am test -Dtest=MemoryEntryRepositoryTest
./mvnw -pl oryxos-cli     -am test -Dtest=MemoryAssemblyTest
```

> **`-am` 不能省**：单模块命令不带 `-am` 会静默取 `~/.m2` 里的旧字节码。

**预期测试类清单**（课件 harness 逐项对号）：

| 测试类 | 落点 | 钉住什么 |
| --- | --- | --- |
| `MemoryStoreContractTest` | `oryxos-memory` | ★ 参数化遍历三档：写后立读（契约一）、截断只裁归档核心区一字不动（契约二）、scope 路由（契约三）、recall 只搜归档（契约四） |
| `MarkdownMemoryStoreTest` | `oryxos-memory` | 截断边界 / header 解析 / 文件缺失 |
| `SqliteMemoryStoreTest` | `oryxos-memory` | 手工脚本能建能读 / 归档 `LIMIT` 生效 / `LIKE` 检索（上游同节漏做，本仓按课件补上） |
| `Mem0MemoryStoreTest` | `oryxos-memory` | `MockRestServiceServer`：`append` 请求体带 scope、`recall` 转发查询——**不碰真 server** |
| `MemoryToolsTest` | `oryxos-memory` | scope 缺省写归档 / 非法 scope 报错 / 未命中返回"没有找到相关记忆"不抛异常 |
| `MemoryServiceImplTest` | `oryxos-memory` | 门面语义：核心区完整在内、空记忆返回空、`HYBRID_BUILTIN` 报错不静默降级 |
| `MemoryEntryRepositoryTest` | `oryxos-storage` | 手工脚本建出的表列齐备 + 三个查询的语义 |
| `MemoryAssemblyTest` | `oryxos-cli` | **起真上下文**：让位机制生效、三档取值、未知取值报错、`InMemoryMemoryStore` 选不中 |

---

## 2. 两条关键回归单测（课件 harness 里写出代码的那两个）

```bash
./mvnw -pl oryxos-memory -am test -Dtest='MemoryStoreContractTest#truncation_onlyTrimsArchival_coreMemorySurvivesIntact'
./mvnw -pl oryxos-memory -am test -Dtest='MemoryStoreContractTest#append_isImmediatelyVisible_noCaching'
```

**预期**：两条各跑 **3 个参数**（markdown / sqlite / 内存假 Mem0 替身），**全绿**。`Mem0MemoryStore` 真类不进参数化源——它由 `Mem0MemoryStoreTest` 用 `MockRestServiceServer` 单独验证。

**变异测试（确认它们真的在守）**——故意改坏一档，对应那一行参数必须变红：

| 故意改坏 | 应该红的断言 |
| --- | --- |
| `MarkdownMemoryStore` 加一个 `private String cached` 字段并在 `load()` 里复用 | `append_isImmediatelyVisible_noCaching` 的 markdown 行 |
| `SqliteMemoryStore.load()` 的核心区查询也加 `PageRequest.of(0, 100)` | `truncation_onlyTrimsArchival_coreMemorySurvivesIntact` 的 sqlite 行 |
| `InMemoryMemoryStore` 的截断改成"裁头保留尾"时把核心 List 也一起截 | 对应的内存替身行 |

---

## 3. 手工冒烟：三档切换（课件「五、做完怎么验」第 1 条）

这是"那道墙"最直观的人工证据：**同一段对话、同一个体感，只是底下换了后端**。

```bash
# 1) 建一个干净工作区
rm -rf /tmp/oryxos-l22 && mkdir -p /tmp/oryxos-l22 && cd /tmp/oryxos-l22
java -jar /home/wngbob/oryxos-wngbob/oryxos-boot/target/oryxos.jar init
ls -la .oryxos/memory/MEMORY.md && cat .oryxos/memory/MEMORY.md
#    预期：文件存在，内容恰好是「## 核心记忆\n\n## 归档记忆\n」（只有两个分区标题，无示例条目）
```

```bash
# 2) markdown 档（默认）走一次对话
cd /tmp/oryxos-l22
java -jar .../oryxos.jar chat --profile <你的 Agent 名>
#    对话里说一句值得记的话（如「我在性能敏感场景偏好用 Java」），看 Agent 是否主动调 save_memory
#    退出后核对文件：
cat .oryxos/memory/MEMORY.md
#    预期：某一区里多了一行「- [2026-10-15] ...」
```

```bash
# 3) 切到 sqlite 档，同一段对话再走一次
#    改 /tmp/oryxos-l22 用的配置（或设 JVM 属性 -Doryxos.memory.backend=sqlite）
#    先补建表（老工作区没有 memory_entries）：
java -jar .../oryxos.jar init
sqlite3 .oryxos/oryxos.db ".schema memory_entries"
#    预期：看到 memory_entries 与 idx_memory_scope
#    再跑一次 chat + save_memory，然后：
sqlite3 .oryxos/oryxos.db "SELECT id, scope, content FROM memory_entries"
#    预期：多了一行，scope 是 CORE 或 ARCHIVAL
```

**判据**：两次对话里 `MemoryTools` / `PromptBuilder` / 引擎侧代码**一个字没改**（SC-004）。

---

## 4. 手工冒烟：跨会话长期记忆（课件「五、做完怎么验」第 3 条）

```bash
# 会话 A：说一句值得记的话，确认 Agent 调了 save_memory
# 退出，开一个全新会话 B：
java -jar .../oryxos.jar chat --profile <同一个 Agent>
#    判据：system prompt 的第二部分带上了长期记忆（核心区完整在场），
#          用户不必重复自我介绍（FR-014 / 契约二）
```

## 5. 工具清单的形态变了

```bash
java -jar .../oryxos.jar tool list
#    预期：builtin (15)（第 20 节的 13 个 + save_memory / recall_memory）
#    annotation (0) / mcp (0) 仍是空——预期状态
```

> 表格前会有 Spring / Hibernate 启动日志，**走 stderr**（第 20 节的分流）——管道只拿命令输出，`2>/dev/null` 可静音。

## 6. 沙箱窗口期（**别误判**）

```bash
java -jar .../oryxos.jar chat --profile <Agent>
#    让 Agent 调 read_file /etc/passwd
#    预期：会成功——第 20~23 节的沙箱是临时实现 PermissiveSandbox，白名单校验不生效（已报批的取舍）
```

**本节与这个窗口期的关系**：记忆文件 IO **根本不调** `Sandbox.enforce`（仲裁 3——路径是编译期常量，模型无法影响，不构成越界面）。**验收 MUST NOT 把上面这条"越界没被拦"当成沙箱已就位**，也 MUST NOT 把它记成本节"漏了校验位"。

---

## 7. 剩余人工项（harness 判不了，等人工过）

| 项 | 命令 / 动作 | 判据 |
| --- | --- | --- |
| **三档切换** | §3 | 同一段对话、同一个体感，只是底下换了后端 |
| **跨会话记忆** | §4 | 新会话的 system prompt 带着核心记忆 |
| **真模型完整走一遍** | §4 用真 Provider | Agent 主动调 `save_memory`；下一轮能 `recall_memory` 到 |
| **Mem0 档真连**（可选） | 部署一个自托管 Mem0，设 `MEM0_BASE_URL` + `oryxos.memory.backend=mem0` | `save_memory` 真进了 Mem0、`recall` 能语义召回 |
| **`USER.md` 只读** | code review + `grep -rn "USER.md" --include=*.java` | 没有任何写 `USER.md` 的代码路径（FR-022 / SC-011） |

---

## 8. 依赖方向自查（跨节契约证据）

```bash
# ① core 一行不改（FR-025）
git diff --stat -- oryxos-core/
#    预期：空

# ② oryxos-storage 不依赖 oryxos-memory（不成环）
grep -c "<artifactId>oryxos-memory</artifactId>" oryxos-storage/pom.xml
#    预期：0（grep -c 在 0 命中时退出码是 1，`|| true` 一下免得打断脚本）

# ③ oryxos-memory 不依赖 oryxos-tool（仲裁 3 的连带）
grep -c "<artifactId>oryxos-tool</artifactId>" oryxos-memory/pom.xml
#    预期：0
#    ⚠️ 别用 grep -c "oryxos-tool"：那份 pom 的**注释**里三处提到 oryxos-tool（解释「方向与它一致」
#    「与它同一坐标」），按裸名字 grep 会得到 3，看着像失败其实一条依赖都没有。判据必须落在 <artifactId> 上。

# ④ 两个新工具名没有渗进三个横切关注点（FR-012 / SC-008）
grep -rn "save_memory\|recall_memory" \
  oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/registry/ToolRegistry.java \
  oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/registry/AnnotatedToolAdapter.java \
  oryxos-core/src/main/java/io/oryxlabs/oryxos/core/engine/ToolExecutor.java
#    预期：无命中

# ⑤ 记忆文件 IO 不调沙箱（仲裁 3）——只看代码行
grep -rn "Sandbox\|enforce" --include=*.java oryxos-memory/src/main/java/ | grep -v '\*'
#    预期：无命中
#    ⚠️ 末尾的 `grep -v '\*'` 不能省：MarkdownMemoryStore 的 **javadoc** 里就写着
#    「IO 不过 Sandbox.enforce」——那是**解释为什么不调**，不是调用。不过滤会得到 1 命中，看着像失败。

# ⑥ 无向量依赖、无自建线程池、无 CompletableFuture / Reactor（章程原则 VIII）
grep -rn "embedding\|milvus\|qdrant\|CompletableFuture\|reactor\|Executors\." oryxos-memory/src/main/java/
#    预期：无命中

# ⑦ 凭证不外泄（FR-019 / SC-010）——只看代码行
grep -rn "baseUrl\|base-url" --include=*.java oryxos-memory/src/main/java/ | grep -i "toString\|log\." | grep -v '\*'
#    预期：无命中
#    ⚠️ 同理：Mem0MemoryStore 的 javadoc 里有一句「baseUrl 含凭证，故本类不打日志、异常消息不含它、
#    toString() 也不含它」——那句**本身就是这条纪律的声明**，会被前两个 grep 命中，故要排掉注释行。
```
