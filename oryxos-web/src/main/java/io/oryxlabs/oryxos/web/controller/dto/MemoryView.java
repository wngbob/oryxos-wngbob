package io.oryxlabs.oryxos.web.controller.dto;

/**
 * 长期记忆的对外视图（data-model.md §3.3），对应记忆门面的只读分区方法。
 *
 * <p>两区口径与注入 prompt 时**完全一致**：核心区一字不截；归档区超限时从**尾部**截断，{@code truncated} 如实告知管理台「你看到的不是全部」。
 *
 * @param core 核心记忆全文
 * @param archival 归档记忆（可能已被截断）
 * @param truncated 归档区是否发生过截断
 */
public record MemoryView(String core, String archival, boolean truncated) {}
