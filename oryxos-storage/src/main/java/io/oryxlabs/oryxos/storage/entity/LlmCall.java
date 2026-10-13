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
 * 审计表 {@code llm_calls}：每次 LLM 调用记录（需求文档「核心数据模型」）。
 *
 * <p>与 {@link ToolInvocation} 同属「审计表 Day One 写入」原则（CLAUDE.md 原则 6）。
 *
 * <p>表结构由手工建表脚本维护（{@code db/schema.sql}），不依赖 {@code ddl-auto}。
 */
@Entity
@Table(name = "llm_calls")
public class LlmCall {

  /**
   * 主键。同 {@link ToolInvocation#getId()}：Java 侧 {@code Long}，列类型钉为 SQLite 的 {@code INTEGER}（rowid 别名
   * + {@code AUTOINCREMENT} 的前提），避免默认推出 BIGINT。
   */
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id")
  @JdbcTypeCode(SqlTypes.INTEGER)
  private Long id;

  /** 关联 Session。 */
  @Column(name = "session_id", length = 128)
  private String sessionId;

  /** Provider 名称。 */
  @Column(name = "provider", length = 64)
  private String provider;

  /** 模型名。 */
  @Column(name = "model", length = 128)
  private String model;

  /** 输入 token 数。 */
  @Column(name = "prompt_tokens")
  private Integer promptTokens;

  /** 输出 token 数。 */
  @Column(name = "completion_tokens")
  private Integer completionTokens;

  /** 总 token 数。 */
  @Column(name = "total_tokens")
  private Integer totalTokens;

  /** 调用耗时（毫秒）。 */
  @Column(name = "duration_ms")
  private Long durationMs;

  /** 是否成功。失败 MUST 显式为 {@code false}——只记成功不记失败，等于一次真实事故在系统里完全没留痕迹（章程原则 VI）。 */
  @Column(name = "success")
  private Boolean success;

  /** 失败原因（异常消息）；成功时为空。 */
  @Column(name = "error_message")
  private String errorMessage;

  /** 调用时间。 */
  @Column(name = "created_at")
  private LocalDateTime createdAt;

  /**
   * 无参构造。JPA 要求，同时供写入方（{@code JpaLlmCallRecorder}，在 {@code oryxos-provider}）跨包构造后逐列 set。
   *
   * <p>刻意留空壳而不提供带参构造：审计记录的列由调用场景决定，逐列 set 让"哪一列来自哪里"在写入方一眼可见， 也避免实体反向依赖 Spring AI 的 {@code Usage}
   * 类型（那是 Provider 模块才认识的东西）。
   */
  public LlmCall() {
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

  public String getProvider() {
    return provider;
  }

  public void setProvider(String provider) {
    this.provider = provider;
  }

  public String getModel() {
    return model;
  }

  public void setModel(String model) {
    this.model = model;
  }

  public Integer getPromptTokens() {
    return promptTokens;
  }

  public void setPromptTokens(Integer promptTokens) {
    this.promptTokens = promptTokens;
  }

  public Integer getCompletionTokens() {
    return completionTokens;
  }

  public void setCompletionTokens(Integer completionTokens) {
    this.completionTokens = completionTokens;
  }

  public Integer getTotalTokens() {
    return totalTokens;
  }

  public void setTotalTokens(Integer totalTokens) {
    this.totalTokens = totalTokens;
  }

  public Long getDurationMs() {
    return durationMs;
  }

  public void setDurationMs(Long durationMs) {
    this.durationMs = durationMs;
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

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }
}
