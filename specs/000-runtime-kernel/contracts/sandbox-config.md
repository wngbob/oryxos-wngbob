# Contract: 沙箱白名单配置

> **修订注（2026-10-17，第 24 节交付时加，不改下方历史正文）**：本文件**已被 [`specs/007-sandbox-whitelist/contracts/sandbox-whitelist.md`](../../007-sandbox-whitelist/contracts/sandbox-whitelist.md) 取代**。取代理由三条：
>
> ① **载体换了**：本节定的 `.oryxos/sandbox.yaml`（工作区文件、由 `oryxos init` 生成）被 `application.yaml` 顶层的 `file` / `shell` / `http` 三块取代。理由是本仓的配置唯一落点已定为 `oryxos-boot/src/main/resources/application.yaml`（重命令与 fat JAR 都从它读），再开一个工作区配置文件会造出第二处真相源。
> ② **键名与粒度变了**：`file.read` / `file.write` 两份白名单合并为一份 `file.allowed_paths`（读写共用）；`shell.executables` → `shell.allowed_commands`；`http.domains` → `http.allowed_domains`；`shell.timeout_seconds` 不在这里配（它是 `ShellTools` 的入参，由模型按次传）。
> ③ **SMTP 那一段落空**：`smtp.endpoints` 在 `ActionType` 里从来没有对应路由（四值：`FILE_READ` / `FILE_WRITE` / `SHELL_COMMAND` / `HTTP_REQUEST`），核心阶段也没有消费者（`EmailNotifyAdapter` 归扩展阶段）。本节把它记为「核心阶段预留」，实际上等于永远不做——第 24 节按此把「四个校验」修订为三个。
>
> **一处刻意推迟、不是遗漏**：本节把 `FILE_READ` 与 `FILE_WRITE` 分开配（读写分权），第 24 节合并成一份。接缝仍在——`ActionType` 已经把两类分开建模，将来真要按读写分权，加配置键 + 在 `WhitelistSandbox.enforce` 的 `case FILE_READ` / `case FILE_WRITE` 上分派即可，**接口与调用方不用改**。

**来源**: spec.md FR-042/FR-046;章程原则 IV/VII 与安全基线;/speckit-analyze 发现项 U1。白名单是「劝阻级」防线,配置权归部署管理员——Agent MUST NOT 自声明(frontmatter 禁止项,见 [agent-directory.md](agent-directory.md) §2)。

## 1. 配置载体

- 文件:`.oryxos/sandbox.yaml`(工作区全局唯一,由 `oryxos init` 生成默认模板,幂等不覆盖)。
- 生效:启动时加载,改后重启生效(核心阶段不做热更新)。
- 作用域:全局,对所有 Agent 生效;无 per-Agent 白名单(需要差异化放行 = 拆 Agent、拆部署,扩展阶段再议)。

## 2. 文件格式

```yaml
file:
  read:                 # FILE_READ 路径白名单:标准化后前缀比对,处理 ../ 穿越
    - .oryxos/agents
    - .oryxos/skills
    - .oryxos/output
  write:                # FILE_WRITE 路径白名单,同口径
    - .oryxos/output
    - .oryxos/memory
shell:
  executables: [git]    # SHELL_COMMAND 可执行文件精确比对(不含 PATH 搜索与解释器隐式解析)
  timeout_seconds: 30   # 单次执行超时
http:
  domains:              # HTTP_REQUEST 域名白名单,支持 *.example.com 通配
    - api.open-meteo.com
smtp:
  endpoints: []         # host:port 精确放行;核心阶段预留(SMTP 通知类型属扩展阶段)
```

规则:

- **缺省全部收紧**(空表 = 全拒),按需放开(最小权限,章程安全基线)。
- 路径以工作区根为基准标准化;软连接真实目标必须仍落在白名单前缀内(与 Skill 软连接防越界同口径)。
- `shell` 以 argv 数组直传执行、MUST NOT 经 Shell 解释;解释器(`python`/`bash` 等)只有显式列入 `executables` 才放行——通用 shell 调用解释器等于授予模型宿主机进程权限,仅可信单机部署(章程 IV)。
- 本文件不含机密,无需 `${ENV_VAR}` 占位;webhook / MCP 凭证仍归 `notify_channels.yaml` / `mcp_servers.yaml`。

## 3. 与其他契约的关系

- 校验执行:`WhitelistSandbox`(tasks T028);校验失败抛 `SandboxViolationException`,复用 `ToolExecutor` 既有失败审计路径(FR-043),core 侧按通用异常捕获、MUST NOT import oryxos-tool 的异常类(analyze U2)。
- init 产物:FR-001 与 [agent-directory.md](agent-directory.md) §7 已同步纳入 `sandbox.yaml`。
