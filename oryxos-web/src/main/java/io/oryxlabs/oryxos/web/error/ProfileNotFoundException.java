package io.oryxlabs.oryxos.web.error;

/**
 * Agent 不存在（第 26 节）：{@code AGENT.md} 缺失或装载失败，{@code ProfileRegistry} 里查不到。
 *
 * <p><b>归 404 而不是 503</b>（裁决 ③）：这是「你要的资源没有」，不是「底座这会儿用不了」。 对外文案是已定字面量 {@code agent not found:
 * <name>}。
 */
public class ProfileNotFoundException extends RuntimeException {

  /**
   * @param agentName 查不到的 Agent 名
   */
  public ProfileNotFoundException(String agentName) {
    super("agent not found: " + agentName);
  }
}
