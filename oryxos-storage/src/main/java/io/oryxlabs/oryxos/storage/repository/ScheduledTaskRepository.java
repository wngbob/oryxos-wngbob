package io.oryxlabs.oryxos.storage.repository;

import io.oryxlabs.oryxos.storage.entity.ScheduledTask;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 表 {@code scheduled_tasks} 的 Repository（第 28 节）。
 *
 * <p>主键类型是 {@code String}——{@code task_id} 是 {@code <profileName>#<序号>} 业务键，不是自增。
 */
public interface ScheduledTaskRepository extends JpaRepository<ScheduledTask, String> {

  /**
   * 按任务标识升序列出全部登记任务（派生查询，零手写 SQL）。
   *
   * <p>排序交给数据库，输出才确定：管理台按列表扫读，顺序漂移会让「刚才还在第三行」变成假象。
   *
   * @return 全部任务
   */
  List<ScheduledTask> findAllByOrderByTaskIdAsc();
}
