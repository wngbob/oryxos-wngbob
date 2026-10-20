package io.oryxlabs.oryxos.storage.repository;

import io.oryxlabs.oryxos.storage.entity.SessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 会话表 {@code sessions} 的 Repository（技术方案第 10 章模块表）。
 *
 * <p>主键类型是 {@code String}——{@code session_id} 由 {@code channel:user:profile} 三段拼接生成，不是自增。
 *
 * <p>（修订注，2026-10-19，第 26 节）原文写「本节不新增查询方法」，那是第 18 节的表述，原文保留： 归档态与 {@code /info} 的活跃会话数需要一条按状态的计数，见
 * {@link #countByStatus(String)}。
 */
public interface SessionRepository extends JpaRepository<SessionEntity, String> {

  /**
   * 按状态数会话行（派生查询，零手写 SQL）。
   *
   * @param status {@code active} / {@code archived}
   * @return 该状态的行数
   */
  long countByStatus(String status);
}
