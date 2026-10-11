package io.oryxlabs.oryxos.tool.notify;

import java.util.Map;

/**
 * 一个通知目标（技术方案 6.8：{@code NotifyTarget = { channelType: String, config: Map<String, String> }}）。
 *
 * @param channelType 渠道类型（webhook / email 等）
 * @param config 类型相关配置；HTTP 类承载 url，email 承载 host / port / from / to 等
 */
public record NotifyTarget(String channelType, Map<String, String> config) {}
