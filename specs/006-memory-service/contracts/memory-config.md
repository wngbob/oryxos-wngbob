# 契约：配置与装配（`oryxos.memory.*`）

**Feature**: `specs/006-memory-service` | **Date**: 2026-10-15

> 本节新增**一个**配置键与**两个**子键。三者的取值口径、报错口径、以及"配置怎么变成 Bean"的规则都在这里定死。**配置文件的唯一落点是 `oryxos-boot/src/main/resources/application.yaml`**（`oryxos-cli` 的 pom 明令 MUST NOT 放第二份）。

---

## 1. 配置键

```yaml
oryxos:
  memory:
    backend: markdown
    mem0:
      base-url: ${MEM0_BASE_URL:}
      user-id: ${MEM0_USER_ID:oryxos}
```

| 键 | 类型 | 取值 | 缺省 | 读取方 |
| --- | --- | --- | --- | --- |
| `oryxos.memory.backend` | String | `markdown` \| `sqlite` \| `mem0` | `markdown` | `CliAssemblyConfiguration.memoryService()` |
| `oryxos.memory.mem0.base-url` | String | 环境变量 `MEM0_BASE_URL` 的值 | 空 | 同上（**仅 `backend=mem0` 时校验**） |
| `oryxos.memory.mem0.user-id` | String | 环境变量 `MEM0_USER_ID` 的值 | `oryxos` | 同上 |

**前缀口径（D8，已报批）**：本仓配置键**一律带 `oryxos.` 前缀**——`application.yaml` 有 `oryxos:` 根块，代码侧先例是 `oryxos.search.tavily-api-key`。这是本节与上游的**唯一有意分歧**（上游同节用裸键 `memory.backend`）。

**环境变量名不带前缀**（`MEM0_BASE_URL` / `MEM0_USER_ID`）——那是外部系统的既有命名，与上游一致。

**两个 `mem0` 子键的占位都带默认值**（空 / `oryxos`）：`markdown` 与 `sqlite` 两档根本不读它们，**缺失不该让启动失败**（与 `oryxos.search.tavily-api-key` 的 `${TAVILY_API_KEY:}` 同一条口径）。校验推迟到"真的选中 mem0 档"那一刻。

---

## 2. 报错口径（三条，逐条可测）

### 2.1 `backend` 取值未知

- **必须**：报错，**列出合法取值**，**MUST NOT 静默退回默认档**（FR-016）。
- **为什么不能静默退回**：运维改了配置、重启、以为生效了，实际还在用旧的——这比启动失败更难查。
- **文案形状**：`未知的长期记忆后端 "postgres"（合法取值：markdown / sqlite / mem0）`

### 2.2 `backend=mem0` 但地址变量缺失

- **必须**：报错，且消息**只带变量名、不带值**（FR-018；与 `ProfileLoader.resolveEnv` / `JpaNotifyChannelRegistry` 同口径）。
- **MUST NOT**：把未解析的占位符当值发出去。
- **文案形状**：`长期记忆后端 mem0 需要环境变量 MEM0_BASE_URL，当前未设置`

### 2.3 Mem0 服务不可达

- **必须**：以明确错误上抛（落审计路径），**MUST NOT 静默返回空**。
- **为什么**：静默返回空会让 Agent 以为"记忆是空的"，进而重复询问用户已经说过的事（Edge Case）。

### 2.4 凭证不得外泄（贯穿）

`base-url`（含 token）MUST NOT 出现在**日志 / 异常消息 / 任何 `toString`** 里（FR-019 / SC-010）。`Mem0MemoryStore` MUST NOT 自动生成带 `baseUrl` 的 `toString`（不写 `@Data` / `@ToString` 之类）。

---

## 3. 装配规则

**落点**：`oryxos-cli/src/main/java/io/oryxlabs/oryxos/cli/CliAssemblyConfiguration.java`（`@Bean` 的唯一落点）

| 规则 | 说明 |
| --- | --- |
| **按 `backend` 造一个 `LongTermMemoryStore`** | 经典冒号 `switch`（不用箭头 switch / switch 表达式，避开 PMD 6.55 解析不了的形态），未知取值走 §2.1 |
| **同一个 `MemoryService` 注入选中的那个** | `new MemoryServiceImpl(store)`——换后端只改配置，门面以上的代码一行不动（SC-004） |
| **`memoryService()` 声明在 `memorySource()` 之前** | `@ConditionalOnMissingBean(MemorySource.class)` 在同一个 `@Configuration` 类里按 `@Bean` **声明顺序**求值。若空实现先求值，容器里会出现**两个** `MemorySource`，`PromptBuilder` 注入时抛 `NoUniqueBeanDefinitionException`——而这个错误**只在重命令起上下文时才炸**，`--help` / `--version` 扫不出来 |
| **`EmptyMemorySource` 一行不改** | 让位机制自动生效（仲裁 9）；MUST NOT 手工删、MUST NOT 加 `@Primary` |
| **`memoryEntryRepository` 只在 `sqlite` 档被用到** | 它作为参数注入 `memoryService()` 是安全的：Spring Data 的 Repository Bean 是**代理**，创建时不碰数据库。默认档 `markdown` 下它一次都不会被调用 |
| **`MemoryTools` 进注册表** | `@Bean MemoryTools memoryTools(MemoryService)`；在 `toolRegistry(...)` 的参数里加上它，并在内置工具那一段的末尾、`connectAll(registry)` **之前** `registerAnnotated(memoryTools, "builtin")`（保持"先内置、后 MCP"的既有顺序，重名时保留先注册者） |
| **`InMemoryMemoryStore` 不可被选中** | 不进 `backend` 取值、没有任何 `@Bean` 声明它 |

---

## 4. 工作区的表从哪来

`memory_entries` 是**本节新增**的表，而 `.oryxos/oryxos.db` 是**跨升级保留的用户数据**——老工作区的库文件里没有它。

| 项 | 口径 |
| --- | --- |
| 建表脚本 | `oryxos-storage/src/main/resources/db/schema.sql`（手工维护，`ddl-auto=none`，`CREATE TABLE IF NOT EXISTS` 天然幂等） |
| **落地路径** | `oryxos init` —— `InitCommand.run()` 里 `database.runSchemaScript()` 是**无条件**执行的（`databaseExisted` 只影响输出文案，不影响是否重跑脚本），故**老工作区跑一次 `oryxos init` 即补齐**（仲裁 11，用户已裁决） |
| **`SqliteMemoryStore` 自带建表？** | **不**。与 `sessions` / 审计表同口径：表的存在性由工作区初始化负责。（`notify_channels` 的 `NotifyChannelDb` 自带建表是**特例**——那三条轻命令不启 Spring、且它是那张表的唯一写入面，与本节情形不同） |
| **忘了跑 `oryxos init` 会怎样** | 撞上 `no such table: memory_entries`。**必须**把这个底层报错包成带「请先运行 `oryxos init`」指引的明确消息 |
| **影响面** | 默认后端是 `markdown`，SQLite 档**不在默认路径上**——这张表只在配置切到 `sqlite` 时才被触达 |

---

## 5. 配置与代码的边界（哪些不配）

| 不配的东西 | 为什么 |
| --- | --- |
| `MEMORY.md` 的路径 | 编译期固定常量（`workspace.root()/memory/MEMORY.md`）。**正因为固定**，记忆文件 IO 才不过 `Sandbox.enforce`（仲裁 3：模型无法把它指向别处，不构成越界面） |
| 归档区截断阈值（4000 字符 / 100 条） | 是**代码常量**不是配置项。口径（只裁归档、超出才裁、保留尾部）不可省，**数值可调**（spec Assumptions 欠定项 1~2，已由上游同节实证） |
| 日期标记形式 | 代码常量 `- [YYYY-MM-DD]`（只到日）。上游后来加过时分秒，**本节 MUST 按第 22 节的形状**（spec Assumptions 欠定项 3） |
