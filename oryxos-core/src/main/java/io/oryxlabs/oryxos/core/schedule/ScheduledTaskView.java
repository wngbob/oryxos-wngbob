package io.oryxlabs.oryxos.core.schedule;

import java.time.Instant;

/**
 * 一个定时任务的「登记信息 + 运行状态」（第 28 节，技术方案 §8.5 / §9.2）。
 *
 * <p><b>定义源不在这里</b>：任务的定义仍然是 {@code AGENT.md} frontmatter 的 {@code schedules}，本记录只承载
 * 「跑过几次、上次成没成、下次几点、是否启用」这些重启后不该丢的状态——进程外的持久化形态由 {@link ScheduledTaskStore} 决定，本类不携带任何存储细节。
 *
 * <p>{@code taskId} 与 {@link ScheduleConfig#id()} 同一个东西（{@code <profileName>#<序号>}），锁键、启停、
 * 立即执行与执行历史都用它定位。本类刻意不做校验：所有规则（cron 归一化、时区解析）都在 {@code AgentScheduler} 一处。
 *
 * @param taskId 任务标识，{@code <profileName>#<配置内序号>}
 * @param profileName 归属 Agent 名
 * @param cron 配置里写的 cron 原文（未归一化，5 段或 6 段）
 * @param zone 时区名；{@code null} / 空白表示按服务器系统时区
 * @param message 到点发给 Agent 的消息
 * @param enabled 是否启用；停用后到点跳过，但「立即执行」仍可手动触发
 * @param nextRunAt 下次触发时刻；算不出来（例如注册失败）时为 {@code null}
 * @param lastRunAt 上次真正执行的开始时刻；从未执行过为 {@code null}
 * @param lastStatus 上次结果：{@code success} / {@code failed}；从未执行过为 {@code null}
 * @param runCount 累计真正执行次数（停用期间不增加、被锁跳过不增加）
 */
public record ScheduledTaskView(
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

  /** 上次执行成功。 */
  public static final String STATUS_SUCCESS = "success";

  /** 上次执行失败。 */
  public static final String STATUS_FAILED = "failed";
}
