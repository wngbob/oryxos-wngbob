package io.oryxlabs.oryxos.cli.command;

import io.oryxlabs.oryxos.cli.workspace.Workspace;
import picocli.CommandLine.Command;

/**
 * {@code oryxos tool list}：列出可用 Tool（FR-002、contracts/cli-surface.md §2.10）。
 *
 * <p><b>本节输出空表，MUST NOT 编造工具</b>：工具注册表在第 20 节才落地，这里把 9 个内置 Tool 的名字先列出来
 * 看着「友好」，实则让用户以为它们已经可用——{@code chat} 里一旦要用就撞上「查不到该工具」。
 *
 * <p><b>本节不接 {@code ToolCatalog} 接缝</b>（本节已报批的裁决）：契约 §2.10 要的是「全量 Tool + 按来源分组 （{@code builtin} /
 * {@code annotation} / {@code mcp:<server>}）」，而第 17 节交付的 {@code ToolCatalog} 只有 {@code find(name)}
 * 与 {@code availableFor(profile)} 两个方法，{@code OryxTool} 也不带来源信息——接缝给不出这两样。 现在硬接上，等于把一个第 20
 * 节必然要改的输出形状先焊死；等 {@code ToolRegistry} 落地时由它决定接缝要不要扩方法， 再回来接。代价如实记在 {@code
 * specs/003-cli-session/tasks.md} T033 的注里。
 *
 * <p><b>表头按最终形态给</b>：{@code name} / {@code source} / {@code description} 三列与契约 §2.10 的「名称 / 说明 /
 * 来源」一一对应，第 20 节补上数据来源即可，列口径不用再议。
 */
@Command(name = "list", mixinStandardHelpOptions = true, description = "列出可用 Tool（本节尚无工具注册表，输出空表）")
public final class ToolListCommand implements Runnable {

  /** 表头：列口径逐字对应 contracts/cli-surface.md §2.10。 */
  private static final String HEADER = "name  source  description";

  /** 「未接入」的标注文案，逐字取自 contracts/cli-surface.md §4。 */
  private static final String NOT_WIRED = "第 20 节接入 ToolRegistry";

  private final Workspace workspace;

  /**
   * @param workspace 工作区；本命令只用它做「是否在工作区内」的前置检查
   */
  public ToolListCommand(Workspace workspace) {
    this.workspace = workspace;
  }

  @Override
  public void run() {
    workspace.requireExists();
    System.out.println("Tool（共 0 个）:");
    System.out.println("  " + HEADER);
    System.out.println("  （无：" + NOT_WIRED + "）");
  }
}
