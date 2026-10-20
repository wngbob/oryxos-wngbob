package io.oryxlabs.oryxos.provider;

/**
 * Provider 调用失败（超时、连不上、对端 5xx 等）——不是配置错，是「这会儿用不了」（第 26 节，FR-004）。
 *
 * <p><b>与 {@link ProviderNotFoundException} 的分界</b>：那个是 {@code AGENT.md} 点名的厂商根本没声明（配置错误， 归
 * 500）；本类是同一次真实调用失败（归 503 {@code provider unavailable: <name>}）。
 *
 * <p><b>消息里保留 cause 的 message</b>：既有审计与排障依赖它（{@code ProviderServiceTest} 断言调用失败时异常消息含对端报错原文），
 * 这也是「失败也要留痕」的一半——但 <b>HTTP 响应体不用这句</b>：503 的对外文案由统一异常出口按 {@link #providerName()}
 * 重新拼，对端报错原文只进日志（FR-005 的同一条口径：内部细节不出门）。
 */
public class ProviderUnavailableException extends RuntimeException {

  /** 原始厂商名，供统一异常出口拼对外文案。 */
  private final String providerName;

  /**
   * @param providerName Profile 点名的厂商名
   * @param cause 真实的调用失败
   */
  public ProviderUnavailableException(String providerName, Throwable cause) {
    super("provider unavailable: " + providerName + ": " + cause.getMessage(), cause);
    this.providerName = providerName;
  }

  /**
   * @return 原始厂商名
   */
  public String providerName() {
    return providerName;
  }
}
