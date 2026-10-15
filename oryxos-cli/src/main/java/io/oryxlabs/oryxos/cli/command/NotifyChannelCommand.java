package io.oryxlabs.oryxos.cli.command;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * {@code oryxos notify-channel} 分组命令：只做分组，挂 {@code add} / {@code list} / {@code remove}
 * 三个叶子命令（contracts/cli-surface.md §1 / §2.12）。
 *
 * <p><b>这一组是「通知渠道注册表」的写入面</b>（2026-10-15 补强②）：第 19 节交付了 {@code notify_channels} 表和读路径 {@code
 * JpaNotifyChannelRegistry}，但没有任何路径能往表里写一行——写入被挂到第 26 节，而第 26 节的管理平台明确「这一版只读」。 于是 {@code notify}
 * 工具在真实部署里 100% 失败，演示只能靠手工 {@code sqlite3 INSERT}。这三条命令把那半边补上。
 *
 * <p><b>三条都是轻命令</b>：不调模型、不跑引擎，因此不启动 Spring 上下文，直接走 {@link
 * io.oryxlabs.oryxos.cli.workspace.WorkspaceDb} 的原生 JDBC（与 {@code profile create/delete}、{@code
 * session list} 同款），秒回。
 *
 * <p><b>为什么必须有这个类</b>：Picocli 的子命令按首个 token 匹配，名为 {@code "notify-channel list"} 的单个子命令永远匹配不上；
 * 对外要的是空格分隔的多级命令，只能用「组 + 子命令」表达（research.md D12）。
 */
@Command(
    name = "notify-channel",
    mixinStandardHelpOptions = true,
    description = "通知渠道注册表（出站推送的目标；一个渠道可被任意 Agent 按名引用）")
public final class NotifyChannelCommand implements Runnable {

  @Spec private CommandSpec spec;

  @Override
  public void run() {
    spec.commandLine().usage(System.out);
  }
}
