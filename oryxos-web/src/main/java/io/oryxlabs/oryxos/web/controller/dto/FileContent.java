package io.oryxlabs.oryxos.web.controller.dto;

/**
 * 读一个工作区文件的响应体（第 30 节 FR-006，contracts/workspace-memory.md §1.3）。
 *
 * <p>回带 {@code path} 而不是只回内容：前端左侧文件树会被用户反复点，回显请求路径让「正在编辑哪一份」这件事由服务器确认， 而不是由前端自己记着。
 *
 * @param path 相对工作区根的路径（原样回显）
 * @param content 文件内容（UTF-8，≤ 1 MiB）
 */
public record FileContent(String path, String content) {}
