package io.oryxlabs.oryxos.web.controller.dto;

/**
 * 无状态调用的响应体（data-model.md §3.3）。
 *
 * @param agent 被调用的 Agent 名
 * @param reply Agent 的最终答复
 */
public record InvokeResponse(String agent, String reply) {}
