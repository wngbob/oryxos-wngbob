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
 * 审计表 {@code tool_invocations}：每次 Tool 调用记录（需求文档「核心数据模型」）。
 *
 * <p>属于「审计表 Day One 写入」这条不可违背原则（CLAUDE.md 原则 6）：核心阶段就落库， 不是只写日志；定时任务（钟推）的失败调用也走同一审计路径。
 *
 * <p>表结构由手工建表脚本维护（{@code db/schema.sql}），不依赖 {@code ddl-auto} ——SQLite 的 {@code ALTER TABLE} 能力弱，见
 * CLAUDE.md「SQLite 核心表」工程提示。
 */
@Entity
@Table(name = "tool_invocations")
public class ToolInvocation {

  /**
   * 主键。Java 侧用 {@code Long}，但列类型必须是 SQLite 的 {@code INTEGER} ——只有 {@code INTEGER PRIMARY KEY} 才是
   * rowid 别名、才能配 {@code AUTOINCREMENT} （{@code BIGINT PRIMARY KEY AUTOINCREMENT} 会被 SQLite 直接拒绝）。
   * 默认映射会按 {@code Long} 推出 BIGINT，与建表脚本对不上，故显式钉住 JDBC 类型。
   */
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id")
  @JdbcTypeCode(SqlTypes.INTEGER)
  private Long id;

  /** 关联 Session。 */
  @Column(name = "session_id", length = 128)
  private String sessionId;

  /** Tool 名称。 */
  @Column(name = "tool_name", length = 128)
  private String toolName;

  /** 调用参数（JSON）。 */
  @Column(name = "input_json", columnDefinition = "TEXT")
  private String inputJson;

  /** 执行结果（JSON）。 */
  @Column(name = "result_json", columnDefinition = "TEXT")
  private String resultJson;

  /** 是否成功。 */
  @Column(name = "success")
  private Boolean success;

  /** 错误信息（可空）。 */
  @Column(name = "error_message", columnDefinition = "TEXT")
  private String errorMessage;

  /** 执行耗时（毫秒）。 */
  @Column(name = "duration_ms")
  private Long durationMs;

  /** 调用时间。 */
  @Column(name = "created_at")
  private LocalDateTime createdAt;

  /**
   * 无参构造。JPA 要求，同时供写入方（第 17 节的工具执行审计，在 {@code oryxos-core}）跨包构造后逐列 set。
   *
   * <p>与 {@link LlmCall} 保持同一可见性，免得两个审计实体一个能跨包 new、一个不能。
   */
  public ToolInvocation() {
    // JPA 要求的无参构造
  }

  public Long getId() {
    return id;
  }

  public String getSessionId() {
    return sessionId;
  }

  public void setSessionId(String sessionId) {
    this.sessionId = sessionId;
  }

  public String getToolName() {
    return toolName;
  }

  public void setToolName(String toolName) {
    this.toolName = toolName;
  }

  public String getInputJson() {
    return inputJson;
  }

  public void setInputJson(String inputJson) {
    this.inputJson = inputJson;
  }

  public String getResultJson() {
    return resultJson;
  }

  public void setResultJson(String resultJson) {
    this.resultJson = resultJson;
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

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }
}
