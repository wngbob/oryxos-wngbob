package io.oryxlabs.oryxos.cli;

/**
 * oryxos-cli —— 命令行工具（Picocli）。
 *
 * <p>规划内容：主入口 {@code OryxOsCli}、12 个子命令（init / status / chat / serve / gateway / profile
 * list|create|show|delete / provider list / tool list / session list）、 {@code
 * ConfigLoader}（统一加载敏感配置，{@code ${ENV_VAR}} 占位解析）。
 *
 * <p>Maven 骨架占位类，标识模块与包结构；具体实现随 user story 落地（见 CLAUDE.md 实施路线）。
 */
public final class CliModule {

  public static final String NAME = "oryxos-cli";

  private CliModule() {}
}
