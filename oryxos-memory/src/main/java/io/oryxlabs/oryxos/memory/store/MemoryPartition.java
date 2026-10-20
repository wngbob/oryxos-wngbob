package io.oryxlabs.oryxos.memory.store;

/**
 * 长期记忆的只读分区视图（第 26 节）：核心区全文与归档区内容分开给。
 *
 * <p><b>为什么不直接解析 {@link LongTermMemoryStore#load()} 的文本</b>：那等于把两个 header 当成对外契约， 而 {@code sqlite}
 * 档的内容虽然同款拼装、却没有任何「这是给外部解析」的承诺；解析文本还会把「截断」这件事变成字符串长度推断。分区由各档实现自己给出， 口径与 {@code load()}
 * 共用同一段截断逻辑，两处不可能漂移。
 *
 * @param core 核心区全文，<b>永不截断</b>
 * @param archival 归档区内容（可能已截断）
 * @param truncated 归档区是否被截断过；核心区不参与这个判断
 */
public record MemoryPartition(String core, String archival, boolean truncated) {

  /** 归一化：可空分量收敛为空串，调用方不必判空。 */
  public MemoryPartition {
    core = core == null ? "" : core;
    archival = archival == null ? "" : archival;
  }
}
