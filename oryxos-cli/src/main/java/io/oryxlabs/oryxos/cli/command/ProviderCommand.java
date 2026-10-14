package io.oryxlabs.oryxos.cli.command;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * {@code oryxos provider} 分组命令：只做分组，挂 {@code list}（contracts/cli-surface.md §1）。
 *
 * <p>见 {@link ProfileCommand} 对「为什么必须有分组类」的说明——Picocli 的子命令按首个 token 匹配。
 *
 * <p>本节只有 {@code list} 一个叶子命令，但分组照建：命令面是**对外契约**，后续节往 {@code provider} 下加子命令
 * （如凭证状态查询）时不必改动用户已记住的命令形状。
 */
@Command(name = "provider", mixinStandardHelpOptions = true, description = "模型厂商相关操作")
public final class ProviderCommand implements Runnable {

  @Spec private CommandSpec spec;

  @Override
  public void run() {
    spec.commandLine().usage(System.out);
  }
}
