package io.oryxlabs.oryxos.web.controller.dto;

/**
 * 创建会话的请求体（data-model.md §3.2）。
 *
 * <p><b>刻意不收 {@code channel}</b>：渠道由服务端定死为 {@code "web"}，会话身份不交给调用方拼。
 *
 * @param profile 要对话的 Agent 名，必填
 * @param userId 调用方自报的用户标识；空则由控制器补 {@code "default"}
 */
public record CreateSessionRequest(String profile, String userId) {}
