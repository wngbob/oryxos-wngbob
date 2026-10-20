package io.oryxlabs.oryxos.web.controller.dto;

/**
 * 发消息的响应体（data-model.md §3.3）。
 *
 * @param sessionId 会话标识
 * @param reply Agent 的最终答复
 */
public record MessageResponse(String sessionId, String reply) {}
