package io.oryxlabs.oryxos.provider;

import org.springframework.ai.chat.metadata.Usage;

/**
 * LLM 调用审计的落库出口（审计表 {@code llm_calls}，章程原则 VI「审计数据 Day One 落库」）。
 *
 * <p>抽成接口而非让 {@link ProviderService} 直接持有 {@code LlmCallRepository}，有两个理由：一是把 「{@link Usage} 摊平为三列
 * token 数、由调用成败决定 {@code success}」这层语义收口在一处，不散进 {@code chat()} 方法； 二是「调用失败也必须留下 {@code
 * success=false} 的记录」这条回归测试需要 mock 掉它并断言收到的参数。
 */
public interface LlmCallRecorder {

  /**
   * 记录一次 LLM 调用。
   *
   * <p>成功与失败都必须调用本方法：失败时 {@code success} 为 {@code false}、{@code errorMessage} 为异常消息，且
   * <b>先落账再上抛异常</b>——只记成功不记失败，等于一次真实事故在系统里完全没留痕迹。
   *
   * @param sessionId 会话标识（审计按此关联查询）
   * @param provider 厂商名
   * @param model 模型名
   * @param usage token 用量；失败或厂商不返回时为 {@code null}，此时用量列留空而非整条丢弃
   * @param success 是否成功
   * @param errorMessage 失败原因；成功时为 {@code null}
   * @param durationMs 调用耗时（毫秒）
   */
  void record(
      String sessionId,
      String provider,
      String model,
      Usage usage,
      boolean success,
      String errorMessage,
      long durationMs);
}
