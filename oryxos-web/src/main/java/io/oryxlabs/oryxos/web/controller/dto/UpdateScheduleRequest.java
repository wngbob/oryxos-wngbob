package io.oryxlabs.oryxos.web.controller.dto;

/**
 * 启用 / 停用定时任务的请求体（第 28 节，{@code PUT /api/v1/schedules/{id}}）。
 *
 * <p>只开这一个字段：定时任务的<b>定义</b>真相是 {@code AGENT.md}，REST 面只允许改运行期的「启用与否」， 不让 HTTP 成为第二份定义来源。
 *
 * @param enabled 目标状态；缺失归 400
 */
public record UpdateScheduleRequest(Boolean enabled) {}
