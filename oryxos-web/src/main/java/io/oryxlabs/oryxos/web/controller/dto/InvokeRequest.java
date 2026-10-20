package io.oryxlabs.oryxos.web.controller.dto;

/**
 * 无状态调用某个 Agent 的请求体（data-model.md §3.2）。
 *
 * <p>形状与 {@link MessageRequest} 相同但**不复用它**：两者是两条不同语义的契约（一个属于会话、一个不属于），共用会让将来一边演进时另一边被迫跟着动。
 *
 * @param content 用户输入
 */
public record InvokeRequest(String content) {}
