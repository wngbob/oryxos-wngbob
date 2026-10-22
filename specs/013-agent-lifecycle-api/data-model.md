# Phase 1 Data Model: 动态管理 Agent

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md) | **Date**: 2026-10-21

> 本节**不新增数据库表、不改 `schema.sql`**。所有新类型要么是磁盘目录的投影，要么是 REST 请求/响应形状，要么是运行期 ThreadLocal。

---

## 1. 磁盘模型

### 1.1 Agent 目录（唯一真相源）

```text
.oryxos/
├── agents/
│   └── <name>/                  # 一个子目录 = 一个 Agent（Profile.name = 目录名）
│       ├── AGENT.md             # frontmatter（运行配置）+ 正文（任务指令）
│       ├── REFERENCE.md         # 可选：参考文档
│       ├── MEMORY.md            # 本节新增：这个 Agent 的专属长期记忆（Markdown 档）
│       ├── scripts/             # 可选：脚本
│       └── skills/<name>        # 可选：指向 .oryxos/skills/<name> 的相对软连接
└── archive/
    └── <name>[-<epochMillis>]   # 本节新增：删除后的整目录归档，不物理删
```

**命名约束**（API 创建/更新/删除路径参数）：`^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$`，且不是 `.` / `..`。手工丢目录不受 API 限制，但 `ProfileLoader` 的既有校验照常生效。

### 1.2 模板脚手架产物

`POST /api/v1/agents {name, description}` 写出：

| 文件 | 内容 |
|---|---|
| `AGENT.md` | frontmatter：`description` / `provider.name` / `provider.model` / `tools: []`；正文：标题 + 描述 + 「## 任务」占位 |
| `REFERENCE.md` | 一句话占位（「把参考资料放在这里」） |
| `scripts/` | 空目录 |
| `skills/` | 空目录 |

模板不依赖模型；`provider` 取作者配置（见 §3.2）。

---

## 2. 对外视图（core 值对象）

### 2.1 `AgentView`

```java
public record AgentView(
    String name,
    String description,
    String providerName,
    String providerModel,
    List<String> tools,
    List<Profile.ScheduleSpec> schedules) {}
```

- 由 `Profile` 映射；列表与详情共用。
- `schedules` 直接复用 core 的 `Profile.ScheduleSpec`（与第 26 节 `ProfileView` 同一先例：不为对外视图再造同形类型）。
- 只在 `ProfileRegistry` 里的 Agent 才会被返回；磁盘上有、索引里没有（例如刚丢进目录、watcher 还没拾取）暂不可见——重启或 watcher 事件后可见，FR-003 的窗口语义。

### 2.2 `FileNode`

```java
public record FileNode(
    String name,
    String path,          // 相对 .oryxos 根，如 agents/weather/AGENT.md
    boolean directory,
    List<FileNode> children) {}
```

- 目录节点 `children` 非空列表（可空列表）；文件节点 `children` 恒为空列表。
- 树根固定两项：`agents`、`archive`（不存在时返回空目录节点还是省略？**省略**——不凭空造不存在的目录）。

### 2.3 请求体 DTO（web）

| DTO | 字段 | 校验 |
|---|---|---|
| `CreateAgentRequest` | `name`, `description` | name 非空且合法；description 可空 |
| `UpdateAgentRequest` | `agentMarkdown` | 非空 |
| `GenerateFilesRequest` | `description` | 非空 |
| `SaveFilesRequest` | `files: Map<String,String>` | 必须含 `AGENT.md`；路径必须相对、不越界 |
| `WriteFileRequest` | `path`, `content` | path 相对 `.oryxos`、不越界 |
| `MessageRequest` | `content` | 复用第 26 节，非空 ≤32KB |

### 2.4 响应体

- 创建 / 查询 / 更新：`ApiResponse<AgentView>`
- 列表：`ApiResponse<List<AgentView>>`
- 删除：`ApiResponse<Void>`
- 记忆：`ApiResponse<MemoryView>`（复用第 26 节 DTO）
- 固定会话：`ApiResponse<SessionView>`（复用第 26 节 DTO，`messages` 最多 100 条）
- 发消息：`ApiResponse<MessageResponse>`
- 生成草稿：`ApiResponse<Map<String,String>>`
- 保存文件：`ApiResponse<AgentView>`
- 工作区 tree：`ApiResponse<List<FileNode>>`
- 读文件：`ApiResponse<FileContent>`（`record FileContent(String path, String content)`）
- 写文件：`ApiResponse<Void>`

---

## 3. 运行期模型

### 3.1 `ToolExecutionContext`

```java
public final class ToolExecutionContext {
  private static final ThreadLocal<String> CURRENT_AGENT = new ThreadLocal<>();
  public static void set(String agentName);
  public static String current();          // 未设置返回 null
  public static void clear();
  public static <T> T callAs(String agentName, Supplier<T> action);
}
```

生命周期：

```text
AgentService.process
  └─ ProfileContext.set(profile)          （第 17 节）
     └─ ReActLoop.run
        └─ ToolExecutor.execute
           └─ ToolExecutionContext.set(profile.name())
              └─ OryxTool.execute(...)   ← save_memory / recall_memory 读到 Agent 名
           └─ finally clear()
  └─ finally ProfileContext.clear()

MemoryServiceImpl.longTermMemory(profile)
  └─ ToolExecutionContext.set(profile.name())
     └─ store.load()                     ← MarkdownMemoryStore 解析 agents/<name>/MEMORY.md
  └─ finally clear()

GET /agents/{name}/memory
  └─ ToolExecutionContext.set(name)
     └─ memoryService.partition()
  └─ finally clear()
```

**不变量**：任何退出路径都必须清空；嵌套调用时 `callAs` 保存旧值、`finally` 恢复旧值（本节的调用不嵌套，但实现按可嵌套写，防将来加层）。

### 3.2 作者配置

```yaml
oryxos:
  author:
    provider: ${ORYXOS_AUTHOR_PROVIDER:}   # 留空 → 取 oryxos.providers 第一个
    model: ${ORYXOS_AUTHOR_MODEL:}         # 留空 → 生成端点 503
```

装配期解析成 `AuthorConfig(String provider, String model)`（包级私有 record，core / cli 内部使用）：

- `provider` 为空 → `providers.get(0).name()`（装配期解析一次，注入 `AgentLifecycleService`）。
- `providers` 清单为空时 `ProviderProperties.validate` **不报错**，故装配处 MUST 显式判空并给出可读启动错误（否则生成端点会拿到空 provider 名）。
- `model` 为空 → 保留空串，运行期 `generate-files` 抛 `AgentAuthorUnavailableException`。

> **⚠ 2026-10-21 修订注（实现回写，原文保留作决策史）**：上面第二条「装配处给出可读**启动**错误」**没有按字面实现**，落地形态是**请求期**错误——`AgentLifecycleService.create` → 400、`generate-files` → 503，消息分别是「未配置任何 provider，请先设置 `oryxos.providers`」与「未配置作者模型：请设置 `oryxos.author.model`」。原因是 `CliTestContext` 启动时不带 `oryxos.providers`（大量既有测试上下文如此），启动期硬失败会把它们全打掉，而「生成端点拿到空 provider 名」这个真实风险只在**请求到达时**才存在。判据等价：空的 provider 清单下，生成与创建都不会发生、且报错可读。`model` 留空走 503 的行为与原文一致（已人工实测：503 后 `llm_calls` 中 `agent-author:%` 行数仍为 0）。

**凭证纪律**：两个键只存名称，不含 key；日志只打 provider / model 名。

### 3.3 固定管理台会话

| 字段 | 值 |
|---|---|
| `channel` | `admin` |
| `userId` | `console` |
| `profileName` | `<name>` |
| `sessionId` | `admin:console:<name>`（由 `SessionService` 既有拼接规则生成，本节不改） |

状态：普通 `sessions` 行，`status=active|archived`；归档后不能再发消息（400）。

### 3.4 `ProfileRegistry` 与 `AgentScheduler` 的运行态

- `ProfileRegistry`：`ConcurrentHashMap`，磁盘目录的内存投影，不持久化。
- `AgentScheduler.scheduledTasks`：`Map<taskId, ScheduledFuture<?>>`，第 29 节已交付；本节新增 `unregisterProfile(Profile)` 用它取消。
- `scheduled_tasks` / `task_executions`：第 28 节结构，本节不改。删除 Agent 后旧行保留（v1 无 `retired` 字段，已知偏差）。

---

## 4. 状态机

### 4.1 创建

```text
[请求]
  → 校验 name（格式 / 已存在？）          失败 → 400，零写盘
  → 校验 provider / tools                  失败 → 400，零写盘
  → 写脚手架目录
  → deriveProfile（同一套校验）            失败 → 删除刚写目录 → 400
  → ProfileRegistry.register               失败 → 删除刚写目录 → 400
  → 有 schedules ? AgentScheduler.registerProfile : 跳过
  → 返回 AgentView
```

### 4.2 更新

```text
[请求]
  → 校验 Agent 存在                        失败 → 404
  → parse(agentMarkdown, name)             失败 → 400，零写盘
  → 写 AGENT.md
  → 校验新 provider / tools                失败 → 回滚旧 AGENT.md（见下）→ 400
  → AgentScheduler.unregisterProfile(旧)
  → ProfileRegistry.register(新)
  → 有 schedules ? AgentScheduler.registerProfile(新) : 跳过
```

**回滚旧 AGENT.md**：写前读旧内容；新内容校验失败时写回旧内容。实现里把「parse + provider/tools 校验」全部放在写盘**之前**即可，无需回滚。

### 4.3 删除

```text
[请求]
  → 校验 Agent 存在                        失败 → 404
  → AgentScheduler.unregisterProfile        ← 必须先停定时
  → ProfileRegistry.remove
  → AgentStore.archive（移动到 archive/）   ← 不物理删
  → 返回 200
```

### 4.4 监听

```text
start()
  → 全量扫 agents/*：每个目录 register（失败 WARN 跳过）
  → 注册 WatchService
  → daemon 线程循环 take()
      ENTRY_CREATE / ENTRY_MODIFY → register（失败进入 pending 重试 10×300ms，耗尽才 WARN）
      ENTRY_DELETE               → unregister（失败 WARN 跳过）
```

---

## 5. 与既有模型的兼容性

| 既有契约 | 本节影响 |
|---|---|
| `Profile` / `ProfileLoader` / `ProfileRegistry` | 只加 `parse`；其余签名与消息不变 |
| `AgentScheduler.registerAll` / `registerProfile` | 加 `unregisterProfile`；既有行为不变 |
| `OryxTool.execute(JsonNode)` | **不改**；Agent 名走 ThreadLocal |
| `LongTermMemoryStore` SPI | **不改**；三档实现类中只有 `MarkdownMemoryStore` 换路径解析 |
| `MemoryStoreContractTest` | **不改**；无上下文时行为与今天一致 |
| `sessions` 表 | 不改结构；新增固定会话只是新行 |
| `GET /api/v1/profiles` | 保留 |
| `GET /api/v1/memory` | **移除**（用户裁决） |
| `AdminConsoleSourceTest` | 判据方向按新需求反转，断言不减少 |
