package io.oryxlabs.oryxos.web.error;

/**
 * Agent 调用超时（第 26 节，映射 504）。
 *
 * <p><b>本节没有抛出点</b>（裁决 ②）：60 秒强制掐断要打断一个正在阻塞的调用，那会撞「全程同步阻塞、不引入异步」的硬门禁。 本节只落异常类型与 504 映射，判卷在 {@code
 * GlobalExceptionHandlerTest}；真链路的人工项如实记为已知边界。
 *
 * <p>留下这个类型而不是只写一条 javadoc：将来真做超时（例如扩展到异步模型时），抛出点直接 {@code throw new AgentTimeoutException(...)}
 * 即可，出口不必再改。
 */
public class AgentTimeoutException extends RuntimeException {

  public AgentTimeoutException() {
    super("agent invocation timed out");
  }
}
