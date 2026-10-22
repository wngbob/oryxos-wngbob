package io.oryxlabs.oryxos.core.agent;

/**
 * 作者模型未配置：{@code oryxos.author.model} 为空时「一句话生成」抛它（第 30 节 FR-004）。
 *
 * <p><b>为什么在 core 新建一个异常类型</b>：语义上它最接近「Provider 不可用」，但那个类型 {@code ProviderUnavailableException} 在
 * {@code oryxos-provider} 模块里，core 依赖它会破坏依赖方向（章程硬约束： core 不反向依赖任何能力模块）。新建一个 core
 * 侧异常类型，比为了复用一个类型而破坏模块边界便宜得多。
 *
 * <p><b>归 503 而不是 500</b>：这不是代码缺陷，是部署方少配了一个键——「这会儿没配好、配好就能用」，与 Provider 对端故障同档；而 {@code
 * IllegalStateException} 那种「配置错」归 500 的口径（第 26 节裁决 ④）说的是「配错了还发了请求」，本类 恰恰是<b>一次请求都不发</b>。
 */
public class AgentAuthorUnavailableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * @param message 面向运维的可读原因（只带配置键名，不带任何值——该键虽只存名称，但保持与其他配置错误的纪律一致）
   */
  public AgentAuthorUnavailableException(String message) {
    super(message);
  }
}
