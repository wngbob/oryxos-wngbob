package io.oryxlabs.oryxos.web.controller.dto;

import io.oryxlabs.oryxos.core.schedule.ScheduledTaskView;
import java.time.Instant;

/**
 * 一条定时任务的对外视图（第 28 节）。
 *
 * <p><b>定义面与运行态同列</b>：{@code cron} / {@code zone} / {@code message} 是注册时从 {@code AGENT.md}
 * 抄来的快照，{@code nextRunAt} / {@code lastRunAt} / {@code lastStatus} / {@code runCount} 是运行事实——
 * 管理台一屏看全，不必再拼一次定义查询。
 *
 * <p>字段名与 {@link ScheduledTaskView} 一致，但对外类型独立：核心层改字段不该直接改对外 JSON 契约。
 *
 * @param taskId 任务标识（{@code <profileName>#<序号>}）
 * @param profileName 归属 Agent
 * @param cron cron 表达式原文
 * @param zone 时区名；缺省为空
 * @param message 到点发给 Agent 的消息
 * @param enabled 是否启用
 * @param nextRunAt 下次触发时刻
 * @param lastRunAt 上次真正执行的开始时刻
 * @param lastStatus 上次结果：{@code success} / {@code failed}；从未执行为 {@code null}
 * @param runCount 累计真正执行次数
 */
public record ScheduleView(
    String taskId,
    String profileName,
    String cron,
    String zone,
    String message,
    boolean enabled,
    Instant nextRunAt,
    Instant lastRunAt,
    String lastStatus,
    int runCount) {

  /**
   * 由核心层视图转换。
   *
   * @param task 核心层任务视图
   * @return 对外视图
   */
  public static ScheduleView from(ScheduledTaskView task) {
    return new ScheduleView(
        task.taskId(),
        task.profileName(),
        task.cron(),
        task.zone(),
        task.message(),
        task.enabled(),
        task.nextRunAt(),
        task.lastRunAt(),
        task.lastStatus(),
        task.runCount());
  }
}
