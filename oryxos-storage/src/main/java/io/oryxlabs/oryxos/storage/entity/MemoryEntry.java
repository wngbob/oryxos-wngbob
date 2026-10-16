package io.oryxlabs.oryxos.storage.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * 长期记忆条目表 {@code memory_entries}：SQLite 档记忆后端的持久化形态（data-model.md §2）。
 *
 * <p><b>字段全部是基本类型与 {@code String}</b>，MUST NOT 出现 {@code MemoryScope} / {@code MemoryEntry}
 * 等领域类型——{@code oryxos-storage} 不依赖任何内部模块，反向即成环。实体只负责「原样存、原样取」，{@code scope} 的取值口径归消费方 {@code
 * SqliteMemoryStore}。
 *
 * <p><b>主键用 {@code @GeneratedValue(IDENTITY)}</b>：与 {@code sessions} / {@code notify_channels}
 * 不同，记忆条目没有业务键—— 同一条内容可以被重复写入（记忆压缩属明确不做，纯追加），所以「第几条」只能是数据库给的序号。归档区的「最近 N 条」正是按这个序号倒序取。
 *
 * <p>本表是<b>全局单份</b>，不按 Agent 隔离，故不与 {@code sessions} / {@code tool_invocations} / {@code llm_calls}
 * 建立任何外键。
 *
 * <p>表结构由手工建表脚本维护（{@code db/schema.sql}），不依赖 {@code ddl-auto}。
 */
@Entity
@Table(name = "memory_entries")
public class MemoryEntry {

  /** 自增序号。归档区「最近 N 条」的排序依据。 */
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id")
  private Long id;

  /**
   * 分区：{@code CORE} / {@code ARCHIVAL}。
   *
   * <p>本层不做取值校验（与 {@code notify_channels.type} 同口径）——写入方 {@code SqliteMemoryStore}
   * 收的是枚举，非法取值在更上层就被挡掉了，这里再校验一次只会让报错落到持久化异常上。
   */
  @Column(name = "scope", length = 16, nullable = false)
  private String scope;

  /** 记忆正文。与 Markdown 档不同，这里<b>不带</b>日期前缀——时间另有一列。 */
  @Column(name = "content", columnDefinition = "TEXT", nullable = false)
  private String content;

  /** 写入时间。 */
  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  /**
   * 无参构造。JPA 要求，同时供写入方（{@code SqliteMemoryStore}）跨包构造后逐列 set。
   *
   * <p>与 {@link SessionEntity} / {@link LlmCall} 同口径：刻意留空壳而不提供带参构造，让「哪一列来自哪里」在写入方一眼可见。
   */
  public MemoryEntry() {
    // JPA 要求的无参构造
  }

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getScope() {
    return scope;
  }

  public void setScope(String scope) {
    this.scope = scope;
  }

  public String getContent() {
    return content;
  }

  public void setContent(String content) {
    this.content = content;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }
}
