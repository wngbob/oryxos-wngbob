package io.oryxlabs.oryxos.storage;

/**
 * oryxos-storage —— SQLite 持久化。
 *
 * <p>规划内容：{@code SessionRepository}、审计表 Repository（{@code tool_invocations} / {@code llm_calls}，Day
 * One 落库）、定时任务表 Repository（{@code scheduled_tasks} / {@code task_executions}）。表结构演进不依赖
 * ddl-auto，手动脚本或 Flyway。
 *
 * <p>Maven 骨架占位类，标识模块与包结构；具体实现随 user story 落地（见 CLAUDE.md 实施路线）。
 */
public final class StorageModule {

  public static final String NAME = "oryxos-storage";

  private StorageModule() {}
}
