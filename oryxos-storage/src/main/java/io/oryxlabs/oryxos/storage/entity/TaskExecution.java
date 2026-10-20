package io.oryxlabs.oryxos.storage.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 表 {@code task_executions}：定时任务的每次执行历史（第 28 节，技术方案 §8.5 / §9.2）。
 *
 * <p><b>成功失败都记</b>：与 {@code tool_invocations} / {@code llm_calls} 同一条审计纪律。停用期间不执行、不记。
 *
 * <p>主键自增。{@code task_id} 是普通列而不是外键——历史行在任务被删除后仍应保留（可追溯），核心阶段也不做级联删除。
 */
@Entity
@Table(name = "task_executions")
public class TaskExecution {

  /**
   * 主键。列类型显式钉成 SQLite 的 {@code INTEGER}：只有 {@code INTEGER PRIMARY KEY} 才是 rowid 别名、 才能配 {@code
   * AUTOINCREMENT}（与 {@link ToolInvocation} 同一处理）。
   */
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id")
  @JdbcTypeCode(SqlTypes.INTEGER)
  private Long id;

  /** 所属任务标识（{@code <profileName>#<序号>}）。 */
  @Column(name = "task_id", length = 160)
  private String taskId;

  /** 本次触发所用的钟推会话。 */
  @Column(name = "session_id", length = 128)
  private String sessionId;

  /** 执行开始时刻。 */
  @Column(name = "started_at")
  private LocalDateTime startedAt;

  /** 是否成功。 */
  @Column(name = "success")
  private Boolean success;

  /** 失败原因；成功为空。 */
  @Column(name = "error_message", columnDefinition = "TEXT")
  private String errorMessage;

  /** 执行耗时（毫秒）。 */
  @Column(name = "duration_ms")
  private Long durationMs;

  /** JPA 要求的无参构造；同时供 core 的 {@code JpaScheduledTaskStore} 跨包构造后逐列 set。 */
  public TaskExecution() {
    // JPA 要求的无参构造
  }

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getTaskId() {
    return taskId;
  }

  public void setTaskId(String taskId) {
    this.taskId = taskId;
  }

  public String getSessionId() {
    return sessionId;
  }

  public void setSessionId(String sessionId) {
    this.sessionId = sessionId;
  }

  public LocalDateTime getStartedAt() {
    return startedAt;
  }

  public void setStartedAt(LocalDateTime startedAt) {
    this.startedAt = startedAt;
  }

  public Boolean getSuccess() {
    return success;
  }

  public void setSuccess(Boolean success) {
    this.success = success;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public void setErrorMessage(String errorMessage) {
    this.errorMessage = errorMessage;
  }

  public Long getDurationMs() {
    return durationMs;
  }

  public void setDurationMs(Long durationMs) {
    this.durationMs = durationMs;
  }
}
