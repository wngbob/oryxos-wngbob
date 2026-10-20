package io.oryxlabs.oryxos.core.schedule;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 定时任务「状态 + 历史」的持久化接缝（第 28 节，技术方案 §8.5）。
 *
 * <p><b>它不是定义源</b>：任务定义仍在 {@code AGENT.md} 的 {@code schedules} 里，重启时由 {@link
 * AgentScheduler#registerAll()} 从文件重新协调（{@link #register}）。本接缝只让「跑过几次、上次成没成、 下次几点、有没有被停用」活过进程重启。
 *
 * <p><b>实现方</b>：{@code oryxos-core} 的 {@code JpaScheduledTaskStore}（JPA 实体与 Repository 在 {@code
 * oryxos-storage}）——与第 18 节的 {@code JpaSessionService} 同一种落位：core 依赖 storage，storage 不反向依赖 core，接口与
 * JPA 实现都留在 core 才不会成环。
 */
public interface ScheduledTaskStore {

  /**
   * 登记 / 更新一个任务（{@code registerAll} 每次启动都会调）。
   *
   * <p><b>已存在的行只更新定义面</b>（cron / zone / message / nextRunAt），<b>MUST NOT 覆盖</b> {@code enabled}
   * 与运行态（{@code lastRunAt} / {@code lastStatus} / {@code runCount}）——否则「停用」在重启后
   * 会被自动改回启用，而「跑过几次」每次重启都归零。不存在则插入一行，{@code enabled} 取视图里的值（注册时恒为 true）。
   *
   * @param task 登记信息与暂算出的下次触发时刻
   */
  void register(ScheduledTaskView task);

  /**
   * 原子地记一条执行历史并滚动任务状态。
   *
   * <p>写 {@code task_executions} 一行，同时更新对应任务的 {@code lastRunAt} / {@code lastStatus} / {@code
   * runCount + 1} / {@code nextRunAt}。成功失败都调——失败那次不记，「任务没跑过」与「每次都失败」 在运维眼里就分不开了。
   *
   * @param execution 本次执行的历史
   * @param nextRunAt 本次之后的下次触发时刻（由调度器按 cron + 时区算出）
   */
  void recordExecution(TaskExecutionView execution, Instant nextRunAt);

  /**
   * 任务当前是否启用。
   *
   * <p><b>查不到时返回 {@code true}</b>：登记与调度在同一个 {@code registerAll} 里完成，查不到说明持久层暂时没跟上
   * （而不是「用户停用了它」）。这里选择照常执行并让失败可见，而不是把任务静默停掉。
   *
   * @param taskId 任务标识
   * @return 启用为 {@code true}
   */
  boolean isEnabled(String taskId);

  /**
   * 启用 / 停用一个任务。
   *
   * @param taskId 任务标识
   * @param enabled 目标状态
   * @return 任务存在并已更新返回 {@code true}；从未登记返回 {@code false}
   */
  boolean setEnabled(String taskId, boolean enabled);

  /**
   * 按任务标识查一条。
   *
   * @param taskId 任务标识
   * @return 命中即返回；从未登记返回空
   */
  Optional<ScheduledTaskView> find(String taskId);

  /**
   * 列出全部已登记任务，按 {@code taskId} 升序（输出确定，管理台不会因为查询顺序漂移而「换行」）。
   *
   * @return 全部任务
   */
  List<ScheduledTaskView> list();

  /**
   * 某任务的执行历史，最近的在前。
   *
   * @param taskId 任务标识
   * @return 执行历史；从未执行返回空表
   */
  List<TaskExecutionView> executions(String taskId);
}
