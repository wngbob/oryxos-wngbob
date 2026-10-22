package io.oryxlabs.oryxos.web.controller.dto;

/**
 * 覆写某个 Agent 定义的请求体（data-model.md §2.3）。
 *
 * <p><b>整份文本、不是补丁</b>：{@code AGENT.md} 的 frontmatter 与正文由同一套解析器一次性读入，半份文本没有「合并」语义——
 * 请求体给的就是磁盘上将要存在的那份内容。
 *
 * @param agentMarkdown 完整的 {@code AGENT.md}（frontmatter + 正文）；非空，可解析性由服务层校验
 */
public record UpdateAgentRequest(String agentMarkdown) {}
