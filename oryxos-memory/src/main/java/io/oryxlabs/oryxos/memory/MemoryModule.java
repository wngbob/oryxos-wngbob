package io.oryxlabs.oryxos.memory;

/**
 * oryxos-memory —— 能力三（Memory 记忆）。
 *
 * <p>规划内容：{@code MemoryService} 三层门面（会话记忆 + 长期记忆统一收口）、 {@code
 * LongTermMemory}（MEMORY.md，核心/归档两分区）、{@code MemoryTools} （save_memory / recall_memory）。
 *
 * <p>Maven 骨架占位类，标识模块与包结构；具体实现随 user story 落地（见 CLAUDE.md 实施路线）。
 */
public final class MemoryModule {

  public static final String NAME = "oryxos-memory";

  private MemoryModule() {}
}
