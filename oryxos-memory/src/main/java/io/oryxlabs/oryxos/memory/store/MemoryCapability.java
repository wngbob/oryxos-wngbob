package io.oryxlabs.oryxos.memory.store;

/**
 * 长期记忆后端的检索能力三态（技术方案 5.2，015 修订）。
 *
 * <p>{@code MemoryService} 门面按此路由 {@code recall}：语义路故障时自动降级为关键词 + 时间两路， 并在结果尾行标注。
 */
public enum MemoryCapability {

  /** 仅关键词检索（Markdown / SQLite 档）。 */
  KEYWORD,

  /** 内置语义 + 关键词 + 时间新近三路加权 RRF 融合。 */
  HYBRID_BUILTIN,

  /** 检索能力外置给后端（Mem0 档）。 */
  DELEGATED
}
