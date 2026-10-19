package io.oryxlabs.oryxos.core.schedule;

/**
 * 调度视图：调度器眼里的一个定时任务（数据模型 §1.1）。
 *
 * <p>由 {@code AgentScheduler} 注册时从 {@code Profile} 的 {@code schedules} 段逐条派生，id 的派生规则是 「Profile 名 +
 * 配置内序号」（{@code <profileName>#<序号>}，序号从 1 起）。
 *
 * <p><b>纯记录类</b>：不带注解、不做校验、不持有行为——cron 段数归一化与时区解析全在 {@code AgentScheduler} 里，因为报错消息需要「Agent
 * 名」这个本类没有的信息。
 *
 * <p>{@code cron} 存的是 Profile 里写的<b>原文</b>、不是归一化后的形态：归一化是交给调度器之前最后一刻才做的事，
 * 报错消息要带的是用户写的那个表达式，而不是被我们改造过的形态。
 *
 * @param id 任务 id，由 Profile 名 + 序号确定性派生（如 {@code weather#1}），锁键维度
 * @param cron cron 表达式（Profile 里的原文，未归一化；5 段或 6 段）
 * @param zone 时区名（如 {@code Asia/Shanghai}）；可为 {@code null} / 空白，表示按服务器系统时区
 * @param message 到点要发给 Agent 的消息内容
 */
public record ScheduleConfig(String id, String cron, String zone, String message) {}
