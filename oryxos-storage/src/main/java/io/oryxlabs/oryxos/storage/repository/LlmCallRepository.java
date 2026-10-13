package io.oryxlabs.oryxos.storage.repository;

import io.oryxlabs.oryxos.storage.entity.LlmCall;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 审计表 {@code llm_calls} 的 Repository（技术方案第 10 章模块表）。
 *
 * <p>写入方是 {@code ProviderService}：每次 LLM 调用都落一条。
 */
public interface LlmCallRepository extends JpaRepository<LlmCall, Long> {

  /**
   * 按 Session 查调用历史，按时间倒序。
   *
   * @param sessionId Session 标识
   * @return 该 Session 的 LLM 调用记录，按创建时间倒序
   */
  List<LlmCall> findBySessionIdOrderByCreatedAtDesc(String sessionId);
}
