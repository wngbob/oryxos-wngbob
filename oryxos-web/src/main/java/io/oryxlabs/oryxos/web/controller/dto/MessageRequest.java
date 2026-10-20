package io.oryxlabs.oryxos.web.controller.dto;

/**
 * 向已有会话发一条消息的请求体（data-model.md §3.2）。
 *
 * <p>字段名是 {@code content} 而不是 {@code message}（裁决 ⑪）。非空且不超过 32KB 由控制器判定，违规归 400。
 *
 * @param content 用户输入
 */
public record MessageRequest(String content) {}
