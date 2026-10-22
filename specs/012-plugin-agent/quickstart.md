# Quickstart：插件化 Agent 的验证向导

**Feature**: `specs/012-plugin-agent` | **Date**: 2026-10-21

本文件只写**怎么验**。目录与 frontmatter 的完整契约见 [contracts/agent-directory.md](./contracts/agent-directory.md)；运行时 API 见 [contracts/runtime-registration.md](./contracts/runtime-registration.md)；字段映射见 [data-model.md](./data-model.md)。

> **要按步骤人工验收，用 [manual-test-guide.md](./manual-test-guide.md)**（Step 0~6，含作者侧实测值、已知边界的现场演示、坑表）。本文件是简版验证清单。

---

## 0. 前置

- JDK 21 + `./mvnw`（仓库自带）
- 一个临时工作区（**不要污染仓库根**）：`export WS=/tmp/oryxos-29 && rm -rf "$WS" && mkdir -p "$WS"`
- 手工验证模型链路的场景需要一个真实的 `DEEPSEEK_API_KEY`；只验目录机制则不需要

```bash
export JAVA_HOME=${JAVA_HOME:?先按 CLAUDE.md 设置 JDK 21}
cd /home/wngbob/oryxos-wngbob
```

---

## 1. 自动化判卷（先跑这一条）

```bash
./mvnw -o -pl oryxos-core -am test \
  -Dtest='AgentLoaderTest,DeriveProfileTest,AgentScanRegisterTest,ProfileRegistryRuntimeTest,AgentSchedulerRegisterTest,ProgressiveDisclosureTest' \
  -Dsurefire.failIfNoSpecifiedTests=false

# shell.allowed_commands 含 python3 的源文件判据在 oryxos-cli（读 application.yaml，不进 Spring 上下文）
./mvnw -o -pl oryxos-cli -am test \
  -Dtest='ShellWhitelistSourceTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

**预期**：7 个测试类全部执行（`oryxos-core` 6 个 + `oryxos-cli` 1 个）、`BUILD SUCCESS`；无一被 `@Disabled`。`ShellWhitelistSourceTest` 单独一条命令跑，是因为它落在 `oryxos-cli`：`ToolAssemblyTest` 自己注入属性源，守不住「`shell.allowed_commands` 整块被删」。

全量门禁：

```bash
./mvnw -o clean verify
```

**预期**：`BUILD SUCCESS`，Spotless / P3C / Checkstyle / SpotBugs+FindSecBugs / OWASP 全过，前序节测试无回归。

---

## 2. 一个目录就是一个 Agent（CLI 入口）

```bash
cd "$WS"
java -jar /home/wngbob/oryxos-wngbob/oryxos-boot/target/oryxos.jar init

# 复制示例（-a：保留软连接，别用 -r 跟随）
cp -a /home/wngbob/oryxos-wngbob/examples/skills/report-format        .oryxos/skills/
cp -a /home/wngbob/oryxos-wngbob/examples/agents/daily-reconcile      .oryxos/agents/

java -jar /home/wngbob/oryxos-wngbob/oryxos-boot/target/oryxos.jar profile list
```

**预期**：

- `daily-reconcile` 出现在列表里，状态为有效；
- 全程没有改任何 Java 代码、没有重启任何常驻进程。

**软连接自检**：

```bash
ls -l .oryxos/agents/daily-reconcile/skills/
readlink -f .oryxos/agents/daily-reconcile/skills/report-format
```

**预期**：`ls -l` 显示 `report-format -> ../../../skills/report-format`；`readlink -f` 落在 `$WS/.oryxos/skills/report-format` 之内。

---

## 3. 同一个 Agent 从 Web 入口可见

```bash
cd "$WS"
LOG=/tmp/oryxos-29-serve.log
java -jar /home/wngbob/oryxos-wngbob/oryxos-boot/target/oryxos.jar serve --port 18080 >"$LOG" 2>&1 &
SERVE_PID=$!
sleep 8
curl -s http://localhost:18080/api/v1/profiles | head -c 800; echo
grep "定时任务注册完成" "$LOG"
```

**预期**：

- 响应信封里 `data` 含 `daily-reconcile`；`schedules` 段有 1 条（cron `0 0 9 * * *`、zone `Asia/Shanghai`）；
- `grep` 命中 `定时任务注册完成：共 1 条`（诊断日志走 stderr，此处已并入 `$LOG`）。

停掉服务：

```bash
kill "$SERVE_PID"; wait "$SERVE_PID" 2>/dev/null
```

---

## 4. 运行时注册（无 CLI / REST 入口，由测试判卷）

本节**不提供** `POST /api/v1/agents`（第 30 节），运行时注册的可执行证据是：

```bash
./mvnw -o -pl oryxos-core -am test -Dtest='ProfileRegistryRuntimeTest,AgentSchedulerRegisterTest' -Dsurefire.failIfNoSpecifiedTests=false
```

**预期**：

- `register()` 后立刻 `find()` 命中；`remove()` 后 `exists()` 为假；
- 同一份坏配置，`deriveProfile(dir)` 抛出的异常类型与消息，与 `loadAllDetailed()` 的 `Failure.reason` 逐字相同；
- `registerProfile()` 后 `scheduledTaskHandles()` 有对应句柄；再次注册后句柄数不翻倍、旧句柄已 `cancel`。

---

## 5. 渐进式披露（由测试判卷 + 可选的人工观察）

自动化：

```bash
./mvnw -o -pl oryxos-core -am test -Dtest='ProgressiveDisclosureTest' -Dsurefire.failIfNoSpecifiedTests=false
```

**预期**：system prompt 里有 `AGENT.md` 正文；**没有** `REFERENCE.md` 的内容、**没有** `scripts/reconcile.py` 的代码；Skill 只出现 name / description / 路径。

人工观察（可选，需真 key）：在 `$WS` 里 `chat --profile daily-reconcile`，让它「跑一下对账脚本」：

- 日志里应看到一次 `shell` 工具调用，`argv[0]` 是 `python3`；
- 模型上下文里出现脚本输出的 JSON，而不是脚本源码。

> 脚本缺 `RECON_ORDERS_CSV` / `RECON_SETTLE_CSV` 时会输出一段说明性 JSON 并正常退出——这是**预期**，不是失败（示例无数据时的兜底）。

---

## 6. 「到点自己跑」（人工项，需真模型）

1. 把示例的 `schedules[0].cron` 改到一分钟内（例如当前时间 +1 分钟，6 段 cron）。
2. `serve` 启动，等触发。
3. 观察：
   - 会话表多出一条 `channel=scheduler` 的会话；
   - `llm_calls` / `tool_invocations` 有对应记录（走的是与 CLI / Web 完全相同的链路）；
   - 若配了通知渠道，群里收到消息（渠道需先用 `oryxos notify-channel add` 配好并发 `OPS_WEBHOOK_URL`）。

**注意**：`lastStatus=success` 只表示 ReAct 跑完没抛异常；推送成败要看 `tool_invocations.success` / `error_message`（第 28 节向导的既有教训）。

---

## 7. 清理

```bash
rm -rf "$WS"
```

---

## 8. 已知边界（不是失败）

| 现象 | 解释 |
|---|---|
| `python3 scripts/foo.py` 能读写任意文件、能自行发网络请求 | 脚本是任意代码；「限定本 Agent 的 `scripts/` 目录」本节不做（需改第 20/24 节沙箱契约），归扩展阶段 |
| `tools` 里写了不存在的工具名，Agent 仍然加载成功 | 告警只在运行期 `ToolRegistry.availableFor` 打 WARN 并跳过 |
| 改了 `AGENT.md` 的 frontmatter，`serve` 里不生效 | frontmatter 只在启动时派生一次；改正文即时生效，改配置需重启（热加载归第 30 节） |
| 从 `AGENT.md` 删掉一条 `schedules` 后，`GET /api/v1/schedules` 仍能看到它 | 第 28 节的 v1 没有 `retired` 字段：重启后不再触发，但 `scheduled_tasks` 里的旧行会留下（列表可见、`runNow` 返回 404）。本节只做「同一进程内再次注册时替换」。**2026-10-22 修订注**：「列表可见」已不成立——`/schedules` 四个端点只认当前已注册任务，旧行仅留库 |
| 丢一个目录进去，`/api/v1/profiles` 立刻有它，`POST /api/v1/agents/{name}/invoke` 却 404 | `/profiles` 每次请求实时扫描磁盘；`invoke` 与 `/info` 的计数读的是**启动期快照**（`ProfileRegistry`）。丢目录「可见」不「可调」，重启后两者一致——运行时注册接口是第 30 节交付物 |
