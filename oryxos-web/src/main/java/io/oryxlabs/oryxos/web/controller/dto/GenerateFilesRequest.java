package io.oryxlabs.oryxos.web.controller.dto;

/**
 * 「一句话生成文件」的请求体（第 30 节 FR-004，contracts/agent-lifecycle-api.md §2.9）。
 *
 * <p>只有一个字段：生成的是<b>已存在 Agent</b> 的文件草稿（Agent 名在路径上），模型只负责按描述重写它的 {@code AGENT.md}——厂商 /
 * 工具是否可用由服务层按既有规则校验，不在这里表达。
 *
 * @param description 一句描述；必填
 */
public record GenerateFilesRequest(String description) {}
