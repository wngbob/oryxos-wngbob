package io.oryxlabs.oryxos.web.controller.dto;

import io.oryxlabs.oryxos.core.schedule.TaskExecutionView;
import java.time.Instant;

/**
 * 一条定时任务执行历史的对外视图（第 28 节）。
 *
 * @param id 历史行主键
 * @param taskId 所属任务标识
 * @param sessionId 本次触发所用的钟推会话；取会话前就失败时为 {@code null}
 * @param startedAt 执行开始时刻
 * @param success 是否成功
 * @param errorMessage 失败原因；成功时为 {@code null}
 * @param durationMs 执行耗时（毫秒）
 */
public record ScheduleExecutionView(
    Long id,
    String taskId,
    String sessionId,
    Instant startedAt,
    boolean success,
    String errorMessage,
    long durationMs) {

  /**
   * 由核心层视图转换。
   *
   * @param execution 核心层执行历史视图
   * @return 对外视图
   */
  public static ScheduleExecutionView from(TaskExecutionView execution) {
    return new ScheduleExecutionView(
        execution.id(),
        execution.taskId(),
        execution.sessionId(),
        execution.startedAt(),
        execution.success(),
        execution.errorMessage(),
        execution.durationMs());
  }
}
