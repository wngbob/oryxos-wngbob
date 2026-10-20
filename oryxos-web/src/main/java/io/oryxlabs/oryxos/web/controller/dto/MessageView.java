package io.oryxlabs.oryxos.web.controller.dto;

import java.time.Instant;

/**
 * 会话历史里的一条消息（data-model.md §3.3）。
 *
 * <p>由核心层 {@code SessionMessage} 映射而来。{@code at} 是那条消息**当初**被追加的时刻，不是序列化时刻。
 *
 * @param role 角色：{@code user} / {@code assistant} / {@code tool}
 * @param content 文本内容；assistant 只请求工具时为空串
 * @param at 追加时刻
 */
public record MessageView(String role, String content, Instant at) {}
