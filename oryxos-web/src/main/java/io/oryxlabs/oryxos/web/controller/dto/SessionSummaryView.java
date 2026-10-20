package io.oryxlabs.oryxos.web.controller.dto;

import java.time.Instant;

/**
 * 会话列表里的一条摘要（第 27 节 {@code GET /api/v1/sessions}）。
 *
 * <p><b>为什么不复用 {@link SessionView}</b>：列表默认返回最近 100 条，若每条都带上完整 {@code messages}，
 * 一次列表请求就会把整库对话正文搬进内存与响应体——列表要回答的是「有哪些会话」，不是「每个会话说过什么」。 详情仍由 {@code GET /api/v1/sessions/{id}} 提供。
 *
 * @param sessionId 会话标识
 * @param profile 所属 Agent 名
 * @param channel 接入渠道
 * @param userId 调用方自报的用户标识
 * @param status {@code active} 或 {@code archived}
 * @param createdAt 创建时刻
 * @param lastActiveAt 最后活跃时刻（列表的排序依据）
 * @param messageCount 历史消息条数；列表只给计数，不给正文
 */
public record SessionSummaryView(
    String sessionId,
    String profile,
    String channel,
    String userId,
    String status,
    Instant createdAt,
    Instant lastActiveAt,
    int messageCount) {}
