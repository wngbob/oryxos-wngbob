package io.oryxlabs.oryxos.web.controller.dto;

/**
 * 写一个工作区文件的请求体（第 30 节 FR-006，contracts/workspace-memory.md §1.4）。
 *
 * <p>路径相对 {@code .oryxos} 根；越界由 {@code AgentStore.resolveSafe} 拒（400）。命中 {@code
 * agents/<name>/AGENT.md} 时 控制器改走生命周期编排，因此这个 DTO 对两条路径是同一份形状。
 *
 * @param path 相对工作区根的路径
 * @param content 文件完整内容（UTF-8 写入）
 */
public record WriteFileRequest(String path, String content) {}
