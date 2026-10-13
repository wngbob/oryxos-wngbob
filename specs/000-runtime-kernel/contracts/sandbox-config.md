# Contract: 沙箱白名单配置

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
