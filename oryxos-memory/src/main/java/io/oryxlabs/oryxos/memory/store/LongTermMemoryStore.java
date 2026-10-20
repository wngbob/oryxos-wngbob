package io.oryxlabs.oryxos.memory.store;

import java.util.List;

/**
 * 长期记忆后端契约（可插拔，技术方案 5.2）。归 {@code oryxos-memory}。
 *
 * <p>把"长期记忆的读写契约"和"具体存哪、怎么存"解耦——这是第 21 节评审那道"接口墙"在实现层的落地。
 *
 * <p>核心阶段一次交付三档实现，靠配置 {@code memory.backend} 选一个： {@code MarkdownMemoryStore}（默认，单机档）/ {@code
 * SqliteMemoryStore}（共享 DB 档）/ {@code Mem0MemoryStore}（外部集成档）。切换后端不影响上层 Tool。
 *
 * <p><b>骨架状态</b>：技术方案未写明各方法的参数与返回类型（原文自称"对外三个方法"但实列五个， 此处按实际列出的五个落地）；类型口径取最直白者，随 US-3 细化。
 */
public interface LongTermMemoryStore {

  /**
   * 追加内容到指定分区。
   *
   * @param content 记忆内容
   * @param scope 目标分区；调用方未指定时语义上默认 {@link MemoryScope#ARCHIVAL}
   */
  void append(String content, MemoryScope scope);

  /**
   * 载入长期记忆：核心记忆区全量 + 归档记忆区截断后的内容。
   *
   * <p>核心区永远完整不截断。
   *
   * @return 拼装后的记忆文本
   */
  String load();

  /**
   * 关键词检索。
   *
   * <p>只在归档记忆区做匹配、跨档统一不区分大小写；核心区不参与检索（它本来就会被全量注入）。
   *
   * @param keyword 关键词
   * @return 命中的归档记忆条目
   */
  List<String> recallByKeyword(String keyword);

  /**
   * 本后端的检索能力三态，供门面路由 {@code recall}。
   *
   * @return 检索能力
   */
  MemoryCapability capabilities();

  /**
   * 归档区全量条目视图，供时间新近路与索引对账取数。
   *
   * <p>{@link MemoryCapability#DELEGATED} 档返回空。
   *
   * @return 归档区条目
   */
  List<String> archivalEntries();

  /**
   * 只读分区视图（第 26 节 {@code GET /api/v1/memory} 的数据源）。
   *
   * <p>与 {@link #load()} 共用同一段截断逻辑：{@code load()} 是「拼成一段给 prompt」，本方法是「分开给管理台看」，
   * 截断口径必须一致，否则同一份记忆在两个出口显示的内容对不上。
   *
   * @return 核心区全文 + 归档区内容 + 归档区是否被截断
   */
  MemoryPartition partition();
}
