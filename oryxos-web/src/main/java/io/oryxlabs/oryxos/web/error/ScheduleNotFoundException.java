package io.oryxlabs.oryxos.web.error;

/**
 * 定时任务不存在（第 28 节）：按任务标识查不到任何一行。
 *
 * <p>两种情形共用这一档：任务从未登记过；或定义已从 {@code AGENT.md} 删掉、只剩历史行（本版没有 retire 机制， 见验收报告的已知边界）。
 */
public class ScheduleNotFoundException extends RuntimeException {

  /**
   * @param taskId 查不到的任务标识
   */
  public ScheduleNotFoundException(String taskId) {
    super("schedule not found: " + taskId);
  }
}
