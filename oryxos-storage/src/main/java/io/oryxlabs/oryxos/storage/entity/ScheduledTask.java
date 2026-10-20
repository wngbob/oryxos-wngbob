package io.oryxlabs.oryxos.storage.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * 表 {@code scheduled_tasks}：定时任务的登记信息与运行状态（第 28 节，技术方案 §8.5 / §9.2）。
 *
 * <p><b>只存状态，不存定义真相</b>：cron / zone / message 是注册时从 {@code AGENT.md} 的 {@code schedules}
 * 抄过来的<b>快照</b>，用于在没有 Profile 的情况下也能展示与管理；改定义仍然改文件。
 *
 * <p><b>主键是业务键 {@code task_id}</b>（{@code <profileName>#<序号>}），不用自增——同一任务跨重启要复用同一行，
 * 自增主键做不到「同一条任务」这个语义。
 *
 * <p>表结构由手工建表脚本维护（{@code db/schema.sql}），不依赖 {@code ddl-auto}。
 */
@Entity
@Table(name = "scheduled_tasks")
public class ScheduledTask {

  /** 任务标识，主键。形如 {@code weather-agent#1}。 */
  @Id
  @Column(name = "task_id", length = 160)
  private String taskId;

  /** 归属 Agent 名。 */
  @Column(name = "profile_name", length = 128)
  private String profileName;

  /** cron 表达式原文（5 段或 6 段，未归一化）。 */
  @Column(name = "cron", length = 128)
  private String cron;

  /** 时区名；空表示系统时区。 */
  @Column(name = "zone", length = 64)
  private String zone;

  /** 到点发给 Agent 的消息。 */
  @Column(name = "message", columnDefinition = "TEXT")
  private String message;

  /** 是否启用。停用后到点跳过，但「立即执行」仍可手动触发。 */
  @Column(name = "enabled")
  private Boolean enabled;

  /** 下次触发时刻。 */
  @Column(name = "next_run_at")
  private LocalDateTime nextRunAt;

  /** 上次真正执行的开始时刻。 */
  @Column(name = "last_run_at")
  private LocalDateTime lastRunAt;

  /** 上次结果：{@code success} / {@code failed}。 */
  @Column(name = "last_status", length = 16)
  private String lastStatus;

  /** 累计真正执行次数。 */
  @Column(name = "run_count")
  private Integer runCount;

  /** 状态更新时间。 */
  @Column(name = "updated_at")
  private LocalDateTime updatedAt;

  /** JPA 要求的无参构造；同时供 core 的 {@code JpaScheduledTaskStore} 跨包构造后逐列 set。 */
  public ScheduledTask() {
    // JPA 要求的无参构造
  }

  public String getTaskId() {
    return taskId;
  }

  public void setTaskId(String taskId) {
    this.taskId = taskId;
  }

  public String getProfileName() {
    return profileName;
  }

  public void setProfileName(String profileName) {
    this.profileName = profileName;
  }

  public String getCron() {
    return cron;
  }

  public void setCron(String cron) {
    this.cron = cron;
  }

  public String getZone() {
    return zone;
  }

  public void setZone(String zone) {
    this.zone = zone;
  }

  public String getMessage() {
    return message;
  }

  public void setMessage(String message) {
    this.message = message;
  }

  public Boolean getEnabled() {
    return enabled;
  }

  public void setEnabled(Boolean enabled) {
    this.enabled = enabled;
  }

  public LocalDateTime getNextRunAt() {
    return nextRunAt;
  }

  public void setNextRunAt(LocalDateTime nextRunAt) {
    this.nextRunAt = nextRunAt;
  }

  public LocalDateTime getLastRunAt() {
    return lastRunAt;
  }

  public void setLastRunAt(LocalDateTime lastRunAt) {
    this.lastRunAt = lastRunAt;
  }

  public String getLastStatus() {
    return lastStatus;
  }

  public void setLastStatus(String lastStatus) {
    this.lastStatus = lastStatus;
  }

  public Integer getRunCount() {
    return runCount;
  }

  public void setRunCount(Integer runCount) {
    this.runCount = runCount;
  }

  public LocalDateTime getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(LocalDateTime updatedAt) {
    this.updatedAt = updatedAt;
  }
}
