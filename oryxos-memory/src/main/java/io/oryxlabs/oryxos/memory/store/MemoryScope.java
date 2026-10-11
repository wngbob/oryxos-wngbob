package io.oryxlabs.oryxos.memory.store;

/**
 * 长期记忆分区（技术方案 5.2）：{@code MEMORY.md} 内部分「核心记忆」/「归档记忆」两区。
 *
 * <p>写核心还是写归档由 Agent 经 {@code scope} 显式指定，系统不猜；核心阶段不做自动抽取。
 */
public enum MemoryScope {

  /** 核心记忆区：永远全量注入、不截断、不参与检索。 */
  CORE,

  /** 归档记忆区：可截断、可被关键词/语义检索命中（默认分区）。 */
  ARCHIVAL
}
