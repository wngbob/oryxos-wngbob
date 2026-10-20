package io.oryxlabs.oryxos.storage.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * 会话表 {@code sessions}：对话历史的持久化形态（data-model.md §5）。
 *
 * <p><b>字段全部是基本类型与 {@code String}</b>，MUST NOT 出现 {@code Session} / {@code SessionMessage} 等 core
 * 类型——{@code oryxos-storage} 不依赖 {@code oryxos-core}，反向即成环。实体只负责「原样存、原样取」， {@code messages_json}
 * 的序列化口径归 core。
 *
 * <p><b>主键不用 {@code @GeneratedValue}</b>：{@code session_id} 由 {@code channel:user:profile} 三段拼接生成，
 * 不是数据库自增。
 *
 * <p>表结构由手工建表脚本维护（{@code db/schema.sql}），不依赖 {@code ddl-auto}。
 */
@Entity
@Table(name = "sessions")
public class SessionEntity {

  /** 会话标识，主键。形如 {@code cli:local:daily-weather}，由 core 的 {@code SessionId} 拼接后传入。 */
  @Id
  @Column(name = "session_id", length = 128)
  private String sessionId;

  /** 所属 Agent 名。 */
  @Column(name = "profile_name", length = 128)
  private String profileName;

  /**
   * 接入渠道：{@code cli} / {@code web} / {@code scheduler} / {@code gateway}。
   *
   * <p>（修订注，2026-10-19：此处原写 {@code api}。HTTP 入口的渠道字面量在第 26 节定为 {@code web}—— 与 CLI 的 {@code cli}
   * 同名同形，「{@code api}」是只描述协议、不描述入口的旧名。）
   */
  @Column(name = "channel", length = 32)
  private String channel;

  /** 用户标识；CLI 固定 {@code local}。 */
  @Column(name = "user_id", length = 128)
  private String userId;

  /**
   * 序列化后的完整对话历史。
   *
   * <p>实体不理解其内容，只当一列文本存取——磁盘格式（role 小写、timestamp 用 ISO-8601）的真相源在 core 的 {@code MessageJson}。
   */
  @Column(name = "messages_json")
  private String messagesJson;

  /** 会话状态：{@code active} / {@code archived}。本节只写 {@code active}，归档归第 26 节。 */
  @Column(name = "status", length = 16)
  private String status;

  /** 创建时间。 */
  @Column(name = "created_at")
  private LocalDateTime createdAt;

  /** 最后一次追加消息的时间。 */
  @Column(name = "last_active_at")
  private LocalDateTime lastActiveAt;

  /** 归档时间；未归档为空。本节不写这一列的第二态。 */
  @Column(name = "archived_at")
  private LocalDateTime archivedAt;

  /**
   * 无参构造。JPA 要求，同时供写入方（core 的 {@code JpaSessionService}）跨包构造后逐列 set。
   *
   * <p>与 {@link LlmCall} 同口径：刻意留空壳而不提供带参构造，让「哪一列来自哪里」在写入方一眼可见。
   */
  public SessionEntity() {
    // JPA 要求的无参构造
  }

  public String getSessionId() {
    return sessionId;
  }

  public void setSessionId(String sessionId) {
    this.sessionId = sessionId;
  }

  public String getProfileName() {
    return profileName;
  }

  public void setProfileName(String profileName) {
    this.profileName = profileName;
  }

  public String getChannel() {
    return channel;
  }

  public void setChannel(String channel) {
    this.channel = channel;
  }

  public String getUserId() {
    return userId;
  }

  public void setUserId(String userId) {
    this.userId = userId;
  }

  public String getMessagesJson() {
    return messagesJson;
  }

  public void setMessagesJson(String messagesJson) {
    this.messagesJson = messagesJson;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }

  public LocalDateTime getLastActiveAt() {
    return lastActiveAt;
  }

  public void setLastActiveAt(LocalDateTime lastActiveAt) {
    this.lastActiveAt = lastActiveAt;
  }

  public LocalDateTime getArchivedAt() {
    return archivedAt;
  }

  public void setArchivedAt(LocalDateTime archivedAt) {
    this.archivedAt = archivedAt;
  }
}
