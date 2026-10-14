package io.oryxlabs.oryxos.storage.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 通知渠道全局注册表 {@code notify_channels} 的持久化形态（data-model.md §2 / §3）。
 *
 * <p><b>字段全部是 {@code String}</b>，MUST NOT 出现 {@code NotifyTarget} / {@code NotifyChannelDef} /
 * {@code Profile} 等 core 或 tool 类型——{@code oryxos-storage} 不依赖任何内部模块，反向即成环。实体只负责「原样存、原样取」， {@code
 * config_json} 的解析口径归消费方 {@code JpaNotifyChannelRegistry}。
 *
 * <p><b>主键不用 {@code @GeneratedValue}</b>：{@code name} 是人指定的注册名（如 {@code team-lark}），是业务键。
 * 自增策略会把插入时指定的键改写掉，而「按注册名引用渠道」正是本表的全部用法。
 *
 * <p><b>{@code configJson} 保持原始字符串</b>：不在此层做 {@code @Convert} 或 JSON 解析——「值必须是字符串」
 * 这条校验要给出可读报错，落在这里会变成持久化异常，说不清是哪一行配置写错了。
 *
 * <p>渠道是<b>全局</b>注册表，不属于任何 Agent，故本表不与 {@code sessions} / {@code tool_invocations} / {@code
 * llm_calls} 建立任何外键。
 *
 * <p>表结构由手工建表脚本维护（{@code db/schema.sql}），不依赖 {@code ddl-auto}。
 */
@Entity
@Table(name = "notify_channels")
public class NotifyChannelEntity {

  /** 渠道注册名，主键。Agent 与模型引用的唯一标识。 */
  @Id
  @Column(name = "name", length = 128)
  private String name;

  /** 渠道类型。核心阶段仅 {@code webhook}；本层不做取值校验，只要求非空（校验在消费方）。 */
  @Column(name = "type", length = 32)
  private String type;

  /** webhook 地址。<b>含 token 的敏感串</b>，MUST NOT 出现在对话 / 日志 / 报错 / 命令输出中。 */
  @Column(name = "url", length = 1024)
  private String url;

  /** 类型相关配置的键值对，原始 JSON 文本（形状要求：JSON 对象、值均为字符串）。 */
  @Column(name = "config_json", columnDefinition = "TEXT")
  private String configJson;

  /** 人类可读的一句话说明。 */
  @Column(name = "description", length = 512)
  private String description;

  /**
   * 无参构造。JPA 要求，同时供消费方跨包构造后逐列 set。
   *
   * <p>与 {@link SessionEntity} / {@link LlmCall} 同口径：刻意留空壳而不提供带参构造，让「哪一列来自哪里」一眼可见。
   */
  public NotifyChannelEntity() {
    // JPA 要求的无参构造
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getType() {
    return type;
  }

  public void setType(String type) {
    this.type = type;
  }

  public String getUrl() {
    return url;
  }

  public void setUrl(String url) {
    this.url = url;
  }

  public String getConfigJson() {
    return configJson;
  }

  public void setConfigJson(String configJson) {
    this.configJson = configJson;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }
}
