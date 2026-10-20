package io.oryxlabs.oryxos.core.schedule;

import io.oryxlabs.oryxos.storage.entity.ScheduledTask;
import io.oryxlabs.oryxos.storage.entity.TaskExecution;
import io.oryxlabs.oryxos.storage.repository.ScheduledTaskRepository;
import io.oryxlabs.oryxos.storage.repository.TaskExecutionRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ScheduledTaskStore} 的 JPA 实现（第 28 节，表见 {@code db/schema.sql}）。
 *
 * <p><b>为什么落在 core 而不是 storage</b>：本仓的依赖方向是 {@code core → storage}（{@code JpaSessionService} 同款：接口与
 * JPA 映射在 core、实体与 Repository 在 storage）。把实现放进 storage 就要让 storage 反向依赖 core， 与「core
 * 不反向依赖能力模块、storage 不反向依赖任何模块」直接冲突。课件写的是「storage 里 JPA 实现」，落位按本仓 既有依赖方向调整，行为与接口完全一致。
 *
 * <p><b>实体列用 {@code LocalDateTime}、对外视图用 {@code Instant}</b>：与 {@code JpaSessionService} 同一口径
 * （列是墙上时间、视图是绝对时刻），转换只在本类发生。
 */
public class JpaScheduledTaskStore implements ScheduledTaskStore {

  private final ScheduledTaskRepository taskRepository;
  private final TaskExecutionRepository executionRepository;

  public JpaScheduledTaskStore(
      ScheduledTaskRepository taskRepository, TaskExecutionRepository executionRepository) {
    this.taskRepository = Objects.requireNonNull(taskRepository, "taskRepository");
    this.executionRepository = Objects.requireNonNull(executionRepository, "executionRepository");
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void register(ScheduledTaskView task) {
    ScheduledTask entity =
        taskRepository
            .findById(task.taskId())
            .orElseGet(
                () -> {
                  ScheduledTask created = new ScheduledTask();
                  created.setTaskId(task.taskId());
                  created.setEnabled(task.enabled());
                  created.setRunCount(0);
                  return created;
                });
    // 只更新定义面：enabled 与运行态是「用户 / 运行历史的事实」，重启重新登记 MUST NOT 把它们冲掉
    entity.setProfileName(task.profileName());
    entity.setCron(task.cron());
    entity.setZone(task.zone());
    entity.setMessage(task.message());
    entity.setNextRunAt(toLocalDateTime(task.nextRunAt()));
    entity.setUpdatedAt(LocalDateTime.now());
    taskRepository.save(entity);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void recordExecution(TaskExecutionView execution, Instant nextRunAt) {
    TaskExecution row = new TaskExecution();
    row.setTaskId(execution.taskId());
    row.setSessionId(execution.sessionId());
    row.setStartedAt(toLocalDateTime(execution.startedAt()));
    row.setSuccess(execution.success());
    row.setErrorMessage(execution.errorMessage());
    row.setDurationMs(execution.durationMs());
    executionRepository.save(row);

    taskRepository
        .findById(execution.taskId())
        .ifPresent(
            task -> {
              task.setLastRunAt(toLocalDateTime(execution.startedAt()));
              task.setLastStatus(
                  execution.success()
                      ? ScheduledTaskView.STATUS_SUCCESS
                      : ScheduledTaskView.STATUS_FAILED);
              task.setRunCount((task.getRunCount() == null ? 0 : task.getRunCount()) + 1);
              task.setNextRunAt(toLocalDateTime(nextRunAt));
              task.setUpdatedAt(LocalDateTime.now());
              taskRepository.save(task);
            });
  }

  @Override
  public boolean isEnabled(String taskId) {
    return taskRepository
        .findById(taskId)
        .map(task -> !Boolean.FALSE.equals(task.getEnabled()))
        .orElse(true);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public boolean setEnabled(String taskId, boolean enabled) {
    Optional<ScheduledTask> found = taskRepository.findById(taskId);
    if (found.isEmpty()) {
      return false;
    }
    ScheduledTask task = found.get();
    task.setEnabled(enabled);
    task.setUpdatedAt(LocalDateTime.now());
    taskRepository.save(task);
    return true;
  }

  @Override
  public Optional<ScheduledTaskView> find(String taskId) {
    return taskRepository.findById(taskId).map(JpaScheduledTaskStore::toView);
  }

  @Override
  public List<ScheduledTaskView> list() {
    return taskRepository.findAllByOrderByTaskIdAsc().stream()
        .map(JpaScheduledTaskStore::toView)
        .toList();
  }

  @Override
  public List<TaskExecutionView> executions(String taskId) {
    return executionRepository.findByTaskIdOrderByStartedAtDescIdDesc(taskId).stream()
        .map(JpaScheduledTaskStore::toView)
        .toList();
  }

  private static ScheduledTaskView toView(ScheduledTask task) {
    return new ScheduledTaskView(
        task.getTaskId(),
        task.getProfileName(),
        task.getCron(),
        task.getZone(),
        task.getMessage(),
        !Boolean.FALSE.equals(task.getEnabled()),
        toInstant(task.getNextRunAt()),
        toInstant(task.getLastRunAt()),
        task.getLastStatus(),
        task.getRunCount() == null ? 0 : task.getRunCount());
  }

  private static TaskExecutionView toView(TaskExecution execution) {
    return new TaskExecutionView(
        execution.getId(),
        execution.getTaskId(),
        execution.getSessionId(),
        toInstant(execution.getStartedAt()),
        Boolean.TRUE.equals(execution.getSuccess()),
        execution.getErrorMessage(),
        execution.getDurationMs() == null ? 0L : execution.getDurationMs());
  }

  private static LocalDateTime toLocalDateTime(Instant instant) {
    return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
  }

  private static Instant toInstant(LocalDateTime localDateTime) {
    return localDateTime == null ? null : localDateTime.atZone(ZoneId.systemDefault()).toInstant();
  }
}
