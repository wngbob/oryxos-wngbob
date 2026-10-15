package io.oryxlabs.oryxos.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/**
 * 命令树的形状守点（contracts/cli-surface.md §1）。
 *
 * <p><b>为什么必须有这一条</b>：命令类写好了、单测全绿，但忘了在 {@link OryxOsCli#buildCommandLine()} 里挂上去， 用户敲 {@code oryxos
 * notify-channel list} 会得到「Unmatched argument」——而所有针对命令类本身的测试都还是绿的。
 * 这条命令树是<b>显式组装</b>的（注解写不了构造注入的实例），显式组装的东西就靠这条守着。
 *
 * <p><b>为什么连条数一起钉</b>：{@code cli-surface.md §1} 把「12 个子命令」写进了契约，2026-10-15 补强② 扩到 15。
 * 只断言「新增的三条在」而不钉总数，漏挂或误挂都能溜过去；钉了总数，任何一次增删都必须回来改这里，也就必然回去改契约。
 *
 * <p><b>只读命令树，不执行任何命令</b>：{@link OryxOsCli#buildCommandLine()} 只组装对象，不启动 Spring、不碰工作区。
 */
class CliCommandTreeTest {

  /** 契约 §1 的 15 条叶子命令（组命令本身不算——`oryxos profile` 不带子命令只打用法）。 */
  private static final Set<String> EXPECTED_LEAVES =
      Set.of(
          "init",
          "status",
          "chat",
          "serve",
          "gateway",
          "profile list",
          "profile create",
          "profile show",
          "profile delete",
          "notify-channel add",
          "notify-channel list",
          "notify-channel remove",
          "provider list",
          "tool list",
          "session list");

  @Test
  @DisplayName("命令树里的叶子命令恰好是契约 §1 的那 15 条 —— 一条不多、一条不少")
  void commandTreeHasExactlyTheContractedLeaves() {
    assertThat(leaves(OryxOsCli.buildCommandLine())).isEqualTo(new TreeSet<>(EXPECTED_LEAVES));
  }

  @Test
  @DisplayName("notify-channel 三个叶子都挂上了 —— 命令类写好了但忘了挂，用户看到的是 Unmatched argument")
  void notifyChannelGroupIsWired() {
    Map<String, CommandLine> groups = OryxOsCli.buildCommandLine().getSubcommands();

    assertThat(groups).containsKey("notify-channel");
    assertThat(groups.get("notify-channel").getSubcommands().keySet())
        .containsExactlyInAnyOrder("add", "list", "remove");
  }

  @Test
  @DisplayName("notify-channel 的三个叶子都拿得到 --help（mixinStandardHelpOptions 没漏）")
  void notifyChannelLeavesSupportHelp() {
    CommandLine group = OryxOsCli.buildCommandLine().getSubcommands().get("notify-channel");

    for (CommandLine leaf : group.getSubcommands().values()) {
      assertThat(leaf.getCommandSpec().mixinStandardHelpOptions())
          .as("%s 缺 mixinStandardHelpOptions", leaf.getCommandName())
          .isTrue();
    }
  }

  /** 深度优先收集叶子命令的全名（空格分隔），组命令自身不计入。 */
  private static Set<String> leaves(CommandLine root) {
    Set<String> leaves = new TreeSet<>();
    collect(root, "", leaves);
    return leaves;
  }

  private static void collect(CommandLine node, String prefix, Set<String> leaves) {
    Map<String, CommandLine> children = node.getSubcommands();
    if (children.isEmpty()) {
      if (!prefix.isEmpty()) {
        leaves.add(prefix);
      }
      return;
    }
    for (Map.Entry<String, CommandLine> child : children.entrySet()) {
      String name = prefix.isEmpty() ? child.getKey() : prefix + " " + child.getKey();
      collect(child.getValue(), name, leaves);
    }
  }
}
