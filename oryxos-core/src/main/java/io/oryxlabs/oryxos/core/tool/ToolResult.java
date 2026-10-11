package io.oryxlabs.oryxos.core.tool;

/**
 * Tool 执行结果（技术方案 6.1：包含成功标识、结果内容、错误信息、是否可重试）。
 *
 * <p>{@code ToolExecutor} 把执行结果包装成本类型返回给 ReAct 循环，并写入 {@code tool_invocations} 表（{@code
 * result_json} / {@code success} / {@code error_message}）。
 *
 * <p><b>骨架状态</b>：技术方案只给了语义（四个要素），未给字段名与类型，此处按语义取直白命名； 字段名随 US-2 落地时若需调整，属预期内的细化。
 *
 * @param success 是否成功
 * @param content 结果内容（成功时的产出）
 * @param errorMessage 错误信息（可空）
 * @param retryable 失败时是否可重试
 */
public record ToolResult(boolean success, String content, String errorMessage, boolean retryable) {

  /** 成功结果。 */
  public static ToolResult success(String content) {
    return new ToolResult(true, content, null, false);
  }

  /** 失败结果（不可重试）。 */
  public static ToolResult failure(String errorMessage) {
    return new ToolResult(false, null, errorMessage, false);
  }

  /** 失败结果（可重试）。 */
  public static ToolResult retryableFailure(String errorMessage) {
    return new ToolResult(false, null, errorMessage, true);
  }
}
