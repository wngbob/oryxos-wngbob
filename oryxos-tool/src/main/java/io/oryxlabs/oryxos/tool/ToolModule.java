package io.oryxlabs.oryxos.tool;

/**
 * oryxos-tool —— 能力四（Tool 工具体系，三合一单模块，不再拆）。
 *
 * <p>规划内容：内置 Tool（File / Shell / Http / Notify 共 9 个）、{@code McpClientService}、 {@code
 * McpToolAdapter}、{@code ToolRegistry}、{@code Sandbox} 接口 + {@code WhitelistSandbox} （不使用 Java
 * SecurityManager）。
 *
 * <p>Maven 骨架占位类，标识模块与包结构；具体实现随 user story 落地（见 CLAUDE.md 实施路线）。
 */
public final class ToolModule {

  public static final String NAME = "oryxos-tool";

  private ToolModule() {}
}
