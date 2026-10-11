package io.oryxlabs.oryxos.storage.repository;

import io.oryxlabs.oryxos.storage.entity.ToolInvocation;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 审计表 {@code tool_invocations} 的 Repository（技术方案第 10 章模块表）。
 *
 * <p>写入方是 {@code ToolExecutor}：每次 Tool 调用（含失败与沙箱拦截）都落一条。
 */
public interface ToolInvocationRepository extends JpaRepository<ToolInvocation, Long> {

  /** 按 Session 查调用历史，按时间倒序。 */
  List<ToolInvocation> findBySessionIdOrderByCreatedAtDesc(String sessionId);
}
