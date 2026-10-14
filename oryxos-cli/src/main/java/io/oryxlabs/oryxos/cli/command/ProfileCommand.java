package io.oryxlabs.oryxos.cli.command;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * {@code oryxos profile} 分组命令：只做分组，挂 {@code list} / {@code create} / {@code show} / {@code delete}
 * 四个叶子命令（contracts/cli-surface.md §1）。
 *
 * <p><b>为什么必须有这个类</b>：Picocli 的子命令按**首个 token** 匹配，名为 {@code "profile list"} 的单个子命令
 * 永远匹配不上；对外要的是空格分隔的多级命令（{@code oryxos profile list}），只能用「组 + 子命令」表达 （research.md D12）。
 *
 * <p><b>不带子命令时打印用法</b>：{@code oryxos profile} 本身不干活，静默退出会让人以为它做了什么。用 {@link Spec} 拿到自己所在的 {@code
 * CommandLine} 再打印，这样用法里**带着**已挂上的子命令清单——{@code new CommandLine(this)} 造一个全新的实例会把子命令丢光。
 */
@Command(
    name = "profile",
    mixinStandardHelpOptions = true,
    description = "Agent 管理（一个 Agent = .oryxos/agents/<name>/ 一个目录）")
public final class ProfileCommand implements Runnable {

  @Spec private CommandSpec spec;

  @Override
  public void run() {
    spec.commandLine().usage(System.out);
  }
}
