package io.oryxlabs.oryxos.memory;

import io.oryxlabs.oryxos.core.memory.MemorySource;
import io.oryxlabs.oryxos.memory.store.MemoryScope;
import java.util.List;

/**
 * 记忆门面：上层（{@code PromptBuilder} / {@code MemoryTools}）只认它，不感知底下挂的是哪一档长期记忆后端。
 *
 * <p><b>为什么继承 core 的 {@link MemorySource} 而不是另起一个方法</b>：core 侧的「读长期记忆」接缝是骨架期就交付的窄接口， 实现它即让 {@code
 * PromptBuilder} 零改动地用上真实记忆。故本接口是「宽门面 + 窄接缝」两件事的合体——{@code PromptBuilder} 拿到的是窄的那一半（只有读），{@code
 * MemoryTools} 拿到的是宽的那一半（读与写）。给 prompt 组装器写入能力等于给它越界的能力。
 *
 * <p><b>只有三类操作</b>：读长期记忆文本、记一条、按关键词查。长期记忆与会话历史是两件生命周期完全不同的事，故本接口 MUST NOT 持有 {@code Session}、MUST
 * NOT 有一个把两者混在一起返回的方法——会话历史归 {@code PromptBuilder} 的会话历史段独立负责。
 */
public interface MemoryService extends MemorySource {

  /**
   * 记一条长期记忆。
   *
   * @param content 记忆正文
   * @param scope 目标分区，<b>要求非 null</b>——「缺省归档」是工具层对模型缺省的兜底，门面不替调用方猜
   */
  void remember(String content, MemoryScope scope);

  /**
   * 按关键词检索长期记忆。
   *
   * <p>只搜归档区：核心区本来就会被全量注入，参与检索等于同一件事在上下文里出现两遍。
   *
   * @param keyword 关键词
   * @return 命中的归档条目；未命中返回空列表，不抛异常
   */
  List<String> recall(String keyword);
}
