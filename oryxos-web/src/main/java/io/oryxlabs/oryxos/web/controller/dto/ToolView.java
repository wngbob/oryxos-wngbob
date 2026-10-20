package io.oryxlabs.oryxos.web.controller.dto;

/**
 * 一个已注册工具的对外视图（data-model.md §3.3）。
 *
 * @param name 工具名
 * @param description 用途描述
 * @param inputSchema 入参 JSON Schema 文本
 * @param source 来源：{@code builtin} / {@code annotation} / {@code mcp:<server>}
 * @param origin 一眼看出从哪来：{@code builtin} / {@code annotation} 与 {@code source} 相同，{@code
 *     mcp:<server>} 时是 server 名
 */
public record ToolView(
    String name, String description, String inputSchema, String source, String origin) {}
