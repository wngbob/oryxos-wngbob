package io.oryxlabs.oryxos.core.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.oryxlabs.oryxos.storage.entity.ScheduledTask;
import io.oryxlabs.oryxos.storage.entity.TaskExecution;
import io.oryxlabs.oryxos.storage.repository.ScheduledTaskRepository;
import io.oryxlabs.oryxos.storage.repository.TaskExecutionRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@link JpaScheduledTaskStore} 的行为测试（第 28 节）。
 *
 * <p>守点集中在「登记只动定义面」：重启重新登记 MUST NOT 冲掉 {@code enabled} 与运行历史； 执行历史成功失败都落库，并把任务状态滚成 {@code lastRunAt
 * / lastStatus / runCount / nextRunAt}； 库里有行、调度器没有时 {@code isEnabled} 默认放行（注册前触发是纵深防御，不是正常路径）。
 *
 * <p>Repository 用 mock：本类判的是<b>调用语义</b>（存什么、更新什么、保留什么），真实 SQL 由 {@code ScheduledTaskE2ETest} 在真
 * SQLite 上判。
 */
class JpaScheduledTaskStoreTest {

  private static final String TASK_ID = "daily-weather#1";
  private static final Instant NEXT = Instant.parse("2026-10-21T01:00:00Z");
  private static final Instant STARTED = Instant.parse("2026-10-20T01:00:00Z");

  private ScheduledTaskRepository taskRepository;
  private TaskExecutionRepository executionRepository;
  private JpaScheduledTaskStore store;

  @BeforeEach
  void setUp() {
    taskRepository = mock(ScheduledTaskRepository.class);
    executionRepository = mock(TaskExecutionRepository.class);
    store = new JpaScheduledTaskStore(taskRepository, executionRepository);
  }

  @Test
  void register_newTask_createsRowWithDefinitionAndZeroCount() {
    when(taskRepository.findById(TASK_ID)).thenReturn(Optional.empty());
    when(taskRepository.save(any(ScheduledTask.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    store.register(view(true, 0, null));

    ArgumentCaptor<ScheduledTask> saved = ArgumentCaptor.forClass(ScheduledTask.class);
    verify(taskRepository).save(saved.capture());
    ScheduledTask row = saved.getValue();
    assertThat(row.getTaskId()).isEqualTo(TASK_ID);
    assertThat(row.getProfileName()).isEqualTo("daily-weather");
    assertThat(row.getCron()).isEqualTo("0 9 * * *");
    assertThat(row.getZone()).isEqualTo("Asia/Shanghai");
    assertThat(row.getMessage()).isEqualTo("播报今天的天气");
    assertThat(row.getEnabled()).isTrue();
    assertThat(row.getRunCount()).isZero();
    assertThat(row.getNextRunAt()).isEqualTo(toLocal(NEXT));
    assertThat(row.getLastRunAt()).isNull();
    assertThat(row.getLastStatus()).isNull();
    assertThat(row.getUpdatedAt()).isNotNull();
  }

  @Test
  void register_existingTask_preservesEnabledAndRuntimeState() {
    ScheduledTask existing = new ScheduledTask();
    existing.setTaskId(TASK_ID);
    existing.setEnabled(false);
    existing.setRunCount(7);
    existing.setLastRunAt(toLocal(STARTED));
    existing.setLastStatus(ScheduledTaskView.STATUS_SUCCESS);
    when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(existing));
    when(taskRepository.save(any(ScheduledTask.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    store.register(view(true, 0, null));

    assertThat(existing.getEnabled()).as("用户的停用决定 MUST NOT 被重启冲掉").isFalse();
    assertThat(existing.getRunCount()).isEqualTo(7);
    assertThat(existing.getLastRunAt()).isEqualTo(toLocal(STARTED));
    assertThat(existing.getLastStatus()).isEqualTo(ScheduledTaskView.STATUS_SUCCESS);
    // 定义面与 next_run_at 照常更新
    assertThat(existing.getCron()).isEqualTo("0 9 * * *");
    assertThat(existing.getMessage()).isEqualTo("播报今天的天气");
    assertThat(existing.getNextRunAt()).isEqualTo(toLocal(NEXT));
  }

  @Test
  void recordExecution_savesHistoryAndRollsTaskState() {
    ScheduledTask task = task(2);
    when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(task));
    when(taskRepository.save(any(ScheduledTask.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(executionRepository.save(any(TaskExecution.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    store.recordExecution(execution(true, null), NEXT);

    ArgumentCaptor<TaskExecution> savedExecution = ArgumentCaptor.forClass(TaskExecution.class);
    verify(executionRepository).save(savedExecution.capture());
    TaskExecution row = savedExecution.getValue();
    assertThat(row.getTaskId()).isEqualTo(TASK_ID);
    assertThat(row.getSessionId()).isEqualTo("scheduler:scheduler:daily-weather");
    assertThat(row.getStartedAt()).isEqualTo(toLocal(STARTED));
    assertThat(row.getSuccess()).isTrue();
    assertThat(row.getErrorMessage()).isNull();
    assertThat(row.getDurationMs()).isEqualTo(123L);

    assertThat(task.getLastRunAt()).isEqualTo(toLocal(STARTED));
    assertThat(task.getLastStatus()).isEqualTo(ScheduledTaskView.STATUS_SUCCESS);
    assertThat(task.getRunCount()).isEqualTo(3);
    assertThat(task.getNextRunAt()).isEqualTo(toLocal(NEXT));
  }

  @Test
  void recordExecution_failure_storesMessageAndFailedStatus() {
    ScheduledTask task = task(0);
    when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(task));
    when(executionRepository.save(any(TaskExecution.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    store.recordExecution(execution(false, "boom"), NEXT);

    assertThat(task.getLastStatus()).isEqualTo(ScheduledTaskView.STATUS_FAILED);
    assertThat(task.getRunCount()).isEqualTo(1);
    assertThat(task.getLastRunAt()).isEqualTo(toLocal(STARTED));
  }

  @Test
  void recordExecution_taskRowMissing_stillSavesHistory() {
    // 历史行在任务被删除后仍应保留（可追溯）；只更新不到任务状态
    when(taskRepository.findById(TASK_ID)).thenReturn(Optional.empty());

    store.recordExecution(execution(true, null), NEXT);

    verify(executionRepository).save(any(TaskExecution.class));
    verify(taskRepository, never()).save(any(ScheduledTask.class));
  }

  @Test
  void isEnabled_missingTask_defaultsToTrue() {
    when(taskRepository.findById(TASK_ID)).thenReturn(Optional.empty());
    assertThat(store.isEnabled(TASK_ID)).isTrue();
  }

  @Test
  void isEnabled_nullColumn_defaultsToTrue() {
    ScheduledTask task = task(0);
    task.setEnabled(null);
    when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(task));
    assertThat(store.isEnabled(TASK_ID)).isTrue();
  }

  @Test
  void setEnabled_missingTask_returnsFalse() {
    when(taskRepository.findById(TASK_ID)).thenReturn(Optional.empty());
    assertThat(store.setEnabled(TASK_ID, false)).isFalse();
    verify(taskRepository, never()).save(any(ScheduledTask.class));
  }

  @Test
  void setEnabled_existingTask_updatesFlag() {
    ScheduledTask task = task(0);
    when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(task));
    when(taskRepository.save(any(ScheduledTask.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    assertThat(store.setEnabled(TASK_ID, false)).isTrue();
    assertThat(task.getEnabled()).isFalse();
    assertThat(task.getUpdatedAt()).isNotNull();
  }

  @Test
  void listAndExecutions_mapEntitiesToViews() {
    ScheduledTask task = task(2);
    when(taskRepository.findAllByOrderByTaskIdAsc()).thenReturn(List.of(task));
    TaskExecution execution = new TaskExecution();
    execution.setId(9L);
    execution.setTaskId(TASK_ID);
    execution.setSessionId("scheduler:scheduler:daily-weather");
    execution.setStartedAt(toLocal(STARTED));
    execution.setSuccess(true);
    execution.setDurationMs(5L);
    when(executionRepository.findByTaskIdOrderByStartedAtDescIdDesc(TASK_ID))
        .thenReturn(List.of(execution));

    assertThat(store.list())
        .singleElement()
        .isEqualTo(view(true, 2, ScheduledTaskView.STATUS_SUCCESS));
    assertThat(store.executions(TASK_ID))
        .singleElement()
        .isEqualTo(
            new TaskExecutionView(
                9L, TASK_ID, "scheduler:scheduler:daily-weather", STARTED, true, null, 5L));
  }

  private static ScheduledTask task(int runCount) {
    ScheduledTask task = new ScheduledTask();
    task.setTaskId(TASK_ID);
    task.setProfileName("daily-weather");
    task.setCron("0 9 * * *");
    task.setZone("Asia/Shanghai");
    task.setMessage("播报今天的天气");
    task.setEnabled(true);
    task.setRunCount(runCount);
    task.setNextRunAt(toLocal(NEXT));
    task.setLastRunAt(toLocal(STARTED));
    task.setLastStatus(ScheduledTaskView.STATUS_SUCCESS);
    return task;
  }

  private static ScheduledTaskView view(boolean enabled, int runCount, String lastStatus) {
    return new ScheduledTaskView(
        TASK_ID,
        "daily-weather",
        "0 9 * * *",
        "Asia/Shanghai",
        "播报今天的天气",
        enabled,
        NEXT,
        runCount == 0 ? null : STARTED,
        lastStatus,
        runCount);
  }

  private static TaskExecutionView execution(boolean success, String error) {
    return new TaskExecutionView(
        null, TASK_ID, "scheduler:scheduler:daily-weather", STARTED, success, error, 123L);
  }

  private static LocalDateTime toLocal(Instant instant) {
    return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
  }
}
