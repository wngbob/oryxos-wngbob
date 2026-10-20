package io.oryxlabs.oryxos.web.controller.dto;

import java.time.Instant;

/**
 * 归档会话的响应体（data-model.md §3.3）。
 *
 * @param sessionId 会话标识
 * @param status 恒为 {@code "archived"}
 * @param archivedAt 归档时刻；重复归档时返回**首次**那个时刻
 */
public record ArchiveResponse(String sessionId, String status, Instant archivedAt) {}
