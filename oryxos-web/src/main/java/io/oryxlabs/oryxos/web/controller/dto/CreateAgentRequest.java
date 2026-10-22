package io.oryxlabs.oryxos.web.controller.dto;

/**
 * 新建 Agent 的请求体（data-model.md §2.3）。
 *
 * <p><b>只有两个字段</b>：方向与「一句话生成整个 Agent」相反——创建走模板脚手架，稳定可复现；要模型参与的是对已存在 Agent 的 {@code
 * generate-files}。带脚本 / 子指令的复杂 Agent 走「手工丢目录」那条路，不在这个请求体里表达。
 *
 * @param name Agent 名（= 目录名）；非空，形状由服务层校验
 * @param description 一句话说明；可空
 */
public record CreateAgentRequest(String name, String description) {}
