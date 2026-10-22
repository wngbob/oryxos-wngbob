# 契约：工作区浏览、文件编辑、实时监听与 per-agent 记忆

**Feature**: [spec.md](../spec.md) | **Plan**: [plan.md](../plan.md) | **Date**: 2026-10-21

---

## 1. 工作区端点

### 1.1 路径坐标系

所有 `path` 参数与 `FileNode.path` 都**相对 `.oryxos` 根**：

```text
agents
agents/weather
agents/weather/AGENT.md
archive/weather
```

`/api/v1/workspace/tree` 只返回 `agents/` 与 `archive/` 两棵子树；其它目录（`memory/`、`logs/`、`sessions/`…）不出现。

### 1.2 `GET /api/v1/workspace/tree`

响应：

```json
[
  {
    "name": "agents",
    "path": "agents",
    "directory": true,
    "children": [
      {
        "name": "weather",
        "path": "agents/weather",
        "directory": true,
        "children": [
          {"name": "AGENT.md", "path": "agents/weather/AGENT.md", "directory": false, "children": []}
        ]
      }
    ]
  }
]
```

规则：

- 目录在前、文件在后，各自按名称排序（稳定输出，测试可断言）。
- **不跟随软连接目录**（`Files.isDirectory(path, NOFOLLOW_LINKS)`）；软连接文件按普通文件列出。
- 不存在的根（如还没有 `archive/`）直接省略，不造空节点。

### 1.3 `GET /api/v1/workspace/file?path=`

- 路径必须通过守卫（§2）；越界 → 400。
- 目标必须是普通文件；不存在 → 404（`ProfileNotFoundException` 不适用，用 `NoResourceFoundException` 或 `IllegalArgumentException`？→ **404 用 `java.nio.file.NoSuchFileException` 包装为 `IllegalArgumentException` 会变 400**，故控制器显式判 `Files.exists` 后抛 `NoResourceFoundException`，走既有 404 映射）。
- 只读文本：`Files.readString(path, UTF_8)`；二进制/非 UTF-8 文件读取失败 → 400 `不是 UTF-8 文本文件`；**文件大小 > 1 MiB → 400 `文件超过 1 MiB 上限`**（先 `Files.size` 判，MUST NOT 整文件读入内存）。
- 响应 `{path, content}`。

### 1.4 `POST /api/v1/workspace/file`

- `{path, content}`；路径过守卫，越界 → 400。
- 若 `path` 形如 `agents/<name>/AGENT.md`：**必须**走 `AgentLifecycleService.update(name, content)`（解析校验 + 写 + 重注册）；Agent 不存在 → 404。
- 其余路径：`Files.createDirectories(parent)` + `Files.writeString`。
- 响应 `data=null`。

---

## 2. 路径守卫（`AgentStore.resolveSafe`）

```java
Path resolveSafe(String relative) {
  // 1. null / blank / 含 \0 → IllegalArgumentException
  // 2. Path.of(relative).isAbsolute() → IllegalArgumentException
  // 3. Path candidate = root.resolve(relative).normalize();
  // 4. !candidate.startsWith(root) → IllegalArgumentException("path escapes workspace: " + relative)
  // 5. return candidate;
}
```

`root = workspace.root().toAbsolutePath().normalize()`（`Workspace.root()` **已经是** `<基准>/.oryxos`；再叠一层会得到 `.oryxos/.oryxos`，tree 为空、所有 file 端点失效）。`AgentStore` 构造器收 `Path oryxosRoot`，不依赖 cli 的 `Workspace` 类型。

**MUST NOT 只做字符串前缀判断**：`root.resolve("../root-evil")` 必须先 `normalize()`，否则同前缀兄弟目录会被放行。

**已知边界**：守卫只保证「在 `.oryxos` 内」，不限制具体子目录；`oryxos.db` 理论上可读。按课件口径，扩展阶段随鉴权收。

---

## 3. `WorkspaceWatcher`

### 3.1 启动与停止

- 由 `ServeCommand.run()` / `GatewayCommand.run()` 在 `AgentScheduler.registerAll()` 之后调用 `context.getBean(WorkspaceWatcher.class).start()`。
- `start()` 幂等（重复调用不重复建线程）。
- `stop()` 关闭 `WatchService` 并中断线程；测试与优雅关闭用。

### 3.2 行为

| 事件 | 动作 |
|---|---|
| 启动 | 遍历 `agents/` 直接子目录 → `lifecycle.register(dir)`；每个 try/catch，坏目录 WARN 后跳过 |
| `ENTRY_CREATE` / `ENTRY_MODIFY` | 若是目录 → `register(dir)`；**失败进入有界延迟重试**（见下），重试耗尽才 WARN |
| `ENTRY_DELETE` | `lifecycle.unregister(name)` |

**拷贝竞态（MUST 处理）**：`cp -a` 先 mkdir 再落文件，`ENTRY_CREATE` 到达时 `AGENT.md` 可能尚未写完；首次 `register` 失败后若不再重试，该 Agent 会**永久漏注册**（内层文件事件打在子目录上，`agents/` 不会再有事件）。实现 MUST 为失败的 CREATE 注册维护 **pending 队列：10 次 × 300ms**（按目录名去重，成功即移除）；主循环用 `watchService.poll(200ms)`，每轮处理到期重试；耗尽后一条 WARN 再跳过。**MUST NOT 为此新开线程 / 定时器 / 线程池**。

**线程模型**：单个 `Thread`，`daemon=true`，名 `oryxos-watcher`；循环 `watchService.poll(200ms)`。**MUST NOT 使用 `ExecutorService` / `CompletableFuture` / Reactor**。

**失败隔离**：任一事件处理抛异常只 WARN，线程继续；`watchService.take()` 的 `InterruptedException` 退出循环。

### 3.3 与 API 路径的一致性

API 创建写完目录后调用 `AgentLifecycleService.register(Path)`；watcher 拾取后调用**同一个方法**。测试 `AgentLifecycleServiceTest.create_andWatcherShareTheSameRegisterPath` 用 `verify(lifecycle).register(dir)` / watcher 真实触发后 `verify(registry).register(...)` 钉死。

---

## 4. per-agent 记忆

### 4.1 `ToolExecutionContext` 契约

| 方法 | 语义 |
|---|---|
| `set(String)` | 设置当前线程的 Agent 名；`null` 视同 `clear` |
| `current()` | 当前 Agent 名；未设置返回 `null` |
| `clear()` | 移除 |
| `callAs(String, Supplier<T>)` | 保存旧值 → 设置 → 执行 → `finally` 恢复旧值 |

**不变量**：`callAs` 的 `finally` 必须恢复旧值（不是无条件 `clear`），异常路径同样恢复。

### 4.2 三个置入点

| # | 置入点 | 取值来源 | 覆盖范围 |
|---|---|---|---|
| 1 | `ToolExecutor.execute` | `ProfileContext.current().name()` | `save_memory` / `recall_memory` 的工具执行 |
| 2 | `MemoryServiceImpl.longTermMemory(Profile)` | `profile.name()` | 组装 prompt 时注入的长期记忆 |
| 3 | `GET /agents/{name}/memory` 控制器 | 路径参数 | `partition()` 读取 |

### 4.3 `MarkdownMemoryStore` 路径规则

```text
ToolExecutionContext.current() != null
  → .oryxos/agents/<name>/MEMORY.md   （父目录不存在时创建）
否则
  → .oryxos/memory/MEMORY.md          （原全局路径，行为与第 22 节一致）
```

**MUST NOT 在构造器里缓存路径**：同一个 store Bean 会被多个 Agent 复用，路径必须每次调用时解析。

### 4.4 三档边界

| 后端 | 本节行为 |
|---|---|
| `markdown`（默认） | per-agent |
| `sqlite` | 全局（`memory_entries` 表不加 `agent_name` 列） |
| `mem0` | 全局 |

`MemoryStoreContractTest` 的参数源与断言**一行不改**；它没有 Agent 上下文，走全局路径。

---

## 5. 测试契约（harness 映射）

| 测试 | 必守断言 |
|---|---|
| `AgentLifecycleServiceTest` | `InOrder` create 顺序；冲突零写；注册失败 `store.delete`；create 与 watcher 同一 `register`；delete 顺序；update 先 `unregisterProfile` 后 `registerProfile` |
| `WorkspaceWatcherTest` | 丢目录后轮询 `registry.exists` 为真；删目录后为假；坏目录之后好目录仍能注册 |
| `WorkspaceApiControllerTest` | tree 含 agents/archive；`../../etc/passwd` → 400；正常读内容；写越界 → 400 |
| `AgentApiControllerTest` | 每个端点转发到 `lifecycle`；冲突 400；不存在 404；信封 `code/message/data/timestamp` |
| `GenerateTest` | 围栏剥离；`parse` 能解析；`store` 零写；`registry` 零 register；非法 → 400；model 空抛 `AgentAuthorUnavailableException`（503 由 web 测试断言） |
| `AgentFixedSessionTest`（core） | 固定会话幂等、两轮同一会话、归档后发消息 400、`callAs` 恢复旧值 |
| `PerAgentMemoryScopeTest`（memory） | per-agent 隔离、无上下文回退全局、路径不跨调用缓存 |
| `AdminConsoleSourceTest` | 写口只开 `ProfilesView.vue` 与 `api.js`；其余视图只读；`noWriteEntryPointText` 收窄到只读页且保留；端点映射表更新；导航无 `/memory` |
