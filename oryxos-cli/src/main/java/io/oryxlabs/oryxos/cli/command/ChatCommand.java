package io.oryxlabs.oryxos.cli.command;

import io.oryxlabs.oryxos.channel.cli.CliChannel;
import io.oryxlabs.oryxos.cli.CliApplication;
import io.oryxlabs.oryxos.cli.workspace.AgentResolver;
import io.oryxlabs.oryxos.cli.workspace.Workspace;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code oryxos chat [--profile <name>] [--message <text>]}：在终端里和 Agent 对话（FR-004 / FR-005）。
 *
 * <p><b>重命令</b>：它要调模型、跑引擎，所以启动 Spring 上下文（FR-003）。轻命令不启动，秒回。
 *
 * <p><b>本类不碰模型调用与工具调度</b>：经 {@link CliChannel} → {@code AgentService.process}（章程原则 IX）。
 * 命令层只做三件事：解析参数、起上下文、把控制权交出去。
 *
 * <p><b>上下文用 try-with-resources</b>：{@code /quit} 或输入流结束后上下文必须关闭，否则进程挂着一堆非守护线程不退出。
 */
@Command(
    name = "chat",
    mixinStandardHelpOptions = true,
    description = "在终端里和 Agent 对话（交互式；--message 走单条模式）")
public final class ChatCommand implements Runnable {

  @Option(names = "--profile", paramLabel = "<name>", description = "使用的 Agent 名；缺省时取工作区唯一的 Agent")
  private String profile;

  @Option(names = "--message", paramLabel = "<text>", description = "单条模式：处理这一条消息并退出，不进入交互循环")
  private String message;

  @Override
  public void run() {
    // 工作区检查必须在起上下文之前：application.yaml 的 datasource url 指向 .oryxos/oryxos.db，
    // 连一个不存在的库会把它悄悄建出来，于是「没跑过 init」变成一个空库而不是一句清楚的提示
    Workspace.current().requireExists();
    try (ConfigurableApplicationContext context =
        new SpringApplicationBuilder(CliApplication.class)
            .web(WebApplicationType.NONE)
            .bannerMode(Banner.Mode.OFF)
            .run()) {
      // Agent 解析是唯一闸门：名字不存在、工作区没有 Agent、有多个却没指定，都在这里报清楚（仲裁 11）
      String profileName = context.getBean(AgentResolver.class).resolve(profile);
      CliChannel channel = context.getBean(CliChannel.class);
      if (message != null) {
        channel.send(profileName, message);
        return;
      }
      channel.run(profileName);
    }
  }
}
