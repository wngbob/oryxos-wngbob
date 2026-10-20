package io.oryxlabs.oryxos.storage.repository;

import io.oryxlabs.oryxos.storage.entity.TaskExecution;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 表 {@code task_executions} 的 Repository（第 28 节）。
 *
 * <p>主键自增；查询维度是 {@code task_id}——管理台先看任务列表，再点开某一个任务的执行历史。
 */
public interface TaskExecutionRepository extends JpaRepository<TaskExecution, Long> {

  /**
   * 某任务的执行历史，最近的在前。
   *
   * <p>排序键带上 {@code id} 作为第二维：同一毫秒内完成两次执行时，只按 {@code started_at} 排序的结果不稳定。
   *
   * @param taskId 任务标识
   * @return 执行历史
   */
  List<TaskExecution> findByTaskIdOrderByStartedAtDescIdDesc(String taskId);
}
