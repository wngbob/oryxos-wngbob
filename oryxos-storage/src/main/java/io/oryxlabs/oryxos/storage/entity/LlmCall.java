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

  /** 调用时间。 */
  @Column(name = "created_at")
  private LocalDateTime createdAt;

  protected LlmCall() {
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

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }
}
