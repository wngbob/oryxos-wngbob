package io.oryxlabs.oryxos.provider;

import io.oryxlabs.oryxos.storage.entity.LlmCall;
import io.oryxlabs.oryxos.storage.repository.LlmCallRepository;
import java.time.LocalDateTime;
import org.springframework.ai.chat.metadata.Usage;

/**
 * {@link LlmCallRecorder} 的落库实现：把一次调用摊平成 {@code llm_calls} 表的一行（FR-008）。
 *
 * <p><b>为什么把 Usage 的摊平收在这里</b>：Spring AI 的 {@code Usage} 是 Provider 模块才认识的类型，实体和表 都不该知道它。摊平动作只此一处，换
 * Spring AI 版本导致 {@code Usage} 接口变化时，改这里就够了，表和调用方不受影响。
 *
 * <p><b>用量缺失不丢记录</b>：部分厂商不返回 token 统计，此时三列留空、其余照记——因为"这次调用发生了"比"这次调用花了 多少
 * token"更重要，缺了用量就把整条审计丢掉是本末倒置。
 */
public class JpaLlmCallRecorder implements LlmCallRecorder {

  private final LlmCallRepository repository;

  /**
   * @param repository 审计表 Repository
   */
  public JpaLlmCallRecorder(LlmCallRepository repository) {
    this.repository = repository;
  }

  @Override
  public void record(
      String sessionId,
      String provider,
      String model,
      Usage usage,
      boolean success,
      String errorMessage,
      long durationMs) {
    LlmCall call = new LlmCall();
    call.setSessionId(sessionId);
    call.setProvider(provider);
    call.setModel(model);
    if (usage != null) {
      call.setPromptTokens(usage.getPromptTokens());
      call.setCompletionTokens(usage.getCompletionTokens());
      call.setTotalTokens(usage.getTotalTokens());
    }
    call.setDurationMs(durationMs);
    call.setSuccess(success);
    call.setErrorMessage(errorMessage);
    call.setCreatedAt(LocalDateTime.now());
    repository.save(call);
  }
}
