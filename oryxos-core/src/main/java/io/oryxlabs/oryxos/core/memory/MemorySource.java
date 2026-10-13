package io.oryxlabs.oryxos.core.memory;

import io.oryxlabs.oryxos.core.profile.Profile;
import java.util.Optional;

/**
 * core 侧的「读长期记忆」接缝（contracts/core-seams.md §3）。
 *
 * <p><b>实现方</b>：{@code oryxos-memory} 的 {@code MemoryService}（第 22 节）。三层门面的完整形态属第 22 节，本节只消费
 * 「有就读、没有就跳过」这一个语义。
 *
 * <p><b>返回的是长期记忆，不是会话历史</b>：会话历史由 {@code Session} 直接提供，两者 MUST NOT 混在一起——长期记忆是跨会话都在的东西，
 * 会话历史只是这一次对话到目前为止的往来记录。
 */
public interface MemorySource {

  /**
   * 取跨会话的长期记忆文本。
   *
   * @param profile 当前 Agent 的运行时配置（记忆按 Agent 隔离）
   * @return 记忆文本；未启用或为空时返回 {@link Optional#empty()}，调用方据此<b>跳过</b> prompt 的第二部分，不塞占位文本
   */
  Optional<String> longTermMemory(Profile profile);
}
