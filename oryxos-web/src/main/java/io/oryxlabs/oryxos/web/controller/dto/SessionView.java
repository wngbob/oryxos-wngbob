package io.oryxlabs.oryxos.web.controller.dto;

import java.time.Instant;
import java.util.List;

/**
 * 会话的对外视图（data-model.md §3.3）。
 *
 * <p><b>两种端点带的内容不同</b>：创建会话的响应只带元数据（{@code messages} 为 {@code null}），查询会话的响应带历史（最多最近 100 条，正序）。
 *
 * @param sessionId 会话标识
 * @param profile 所属 Agent 名
 * @param channel 接入渠道，本节恒为 {@code "web"}
 * @param userId 调用方自报的用户标识
 * @param status {@code active} 或 {@code archived}
 * @param messages 历史消息；创建响应里为 {@code null}
 * @param createdAt 创建时刻
 * @param lastActiveAt 最后活跃时刻
 * @param archivedAt 归档时刻；未归档为 {@code null}
 */
public record SessionView(
    String sessionId,
    String profile,
    String channel,
    String userId,
    String status,
    List<MessageView> messages,
    Instant createdAt,
    Instant lastActiveAt,
    Instant archivedAt) {

  /** 防御性拷贝：{@code messages} 为 {@code null} 时保持 {@code null}（创建响应就是这个形态），否则收敛为不可变视图。 */
  public SessionView {
    messages = messages == null ? null : List.copyOf(messages);
  }
}
