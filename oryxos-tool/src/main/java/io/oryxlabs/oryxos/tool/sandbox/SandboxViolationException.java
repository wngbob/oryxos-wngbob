package io.oryxlabs.oryxos.tool.sandbox;

/**
 * 沙箱白名单校验失败（技术方案 6.7）。
 *
 * <p>校验失败即抛本异常，Tool 执行终止；异常信息直接复用 {@code ToolExecutor} 已有的失败审计路径 写入 {@code tool_invocations}（{@code
 * success=false}、{@code error_message}）， 不需要为 Sandbox 单独新增审计逻辑。
 */
public class SandboxViolationException extends RuntimeException {

  public SandboxViolationException(String message) {
    super(message);
  }
}
