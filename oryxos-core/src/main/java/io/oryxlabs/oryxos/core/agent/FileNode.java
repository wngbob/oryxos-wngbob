package io.oryxlabs.oryxos.core.agent;

import java.util.List;

/**
 * 工作区目录树的一个节点（第 30 节，契约 workspace-memory.md §1）。
 *
 * <p><b>{@code path} 相对工作区根（{@code .oryxos}）</b>，与读 / 写文件端点的 {@code path} 参数同一坐标系——管理台点一个节点就能直接把它交给
 * {@code GET /workspace/file?path=}，中间不需要再拼路径。
 *
 * <p><b>文件节点的 {@code children} 恒为空表而不是 {@code null}</b>：前端遍历时不必再判空，且 JSON 里形状统一。
 *
 * @param name 节点名（文件 / 目录名）
 * @param path 相对工作区根的路径，用 {@code /} 分隔
 * @param directory 是否目录
 * @param children 子节点；文件节点恒为空表
 */
public record FileNode(String name, String path, boolean directory, List<FileNode> children) {

  /** 归一化：可空集合收敛为不可变空表。 */
  public FileNode {
    children = children == null ? List.of() : List.copyOf(children);
  }
}
