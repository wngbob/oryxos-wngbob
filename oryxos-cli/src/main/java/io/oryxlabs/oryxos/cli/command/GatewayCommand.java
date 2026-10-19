package io.oryxlabs.oryxos.cli.command;

import io.oryxlabs.oryxos.cli.CliApplication;
import io.oryxlabs.oryxos.cli.workspace.Workspace;
import io.oryxlabs.oryxos.core.schedule.AgentScheduler;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import picocli.CommandLine.Command;

/**
 * {@code oryxos gateway}：守护进程多通道模式（spec 仲裁 15 / contracts/cli-surface.md §2.4）。
 *
 * <p><b>本节只把「模式」立起来</b>：启动 Spring 上下文后前台常驻，<b>不挂载任何入站 Channel</b>——企业 IM 通道（飞书 / 企微 / 钉钉）属扩展阶段。
 *
 * <p><b>与 {@code serve} 的关系</b>：三种运行模式（{@code chat} / {@code serve} / {@code gateway}）共享同一份 Profile
 * 配置与 同一套会话存储——它们起的都是同一个 {@link CliApplication} 上下文，区别只在本命令接下来做什么。
 */
@Command(
    name = "gateway",
    mixinStandardHelpOptions = true,
    description = "以守护进程多通道模式启动（前台常驻；通道接入在扩展阶段）")
public final class GatewayCommand implements Runnable {

  @Override
  public void run() {
    // 同 chat：工作区检查必须在起上下文之前，否则连不上库会被悄悄建出一个空库
    Workspace.current().requireExists();
    try (ConfigurableApplicationContext context =
        new SpringApplicationBuilder(CliApplication.class)
            .web(WebApplicationType.NONE)
            .bannerMode(Banner.Mode.OFF)
            .run()) {
      // 定时任务只在常驻模式注册（FR-012 / 裁决 3）：chat 不碰这一行，装配处也不碰
      context.getBean(AgentScheduler.class).registerAll();
      System.out.println("gateway 模式已启动（Bean 数 " + context.getBeanDefinitionCount() + "）");
      System.out.println("本模式将挂载入站 Channel，通道接入在扩展阶段");
      System.out.println("前台常驻中，Ctrl+C 退出。");
      // 与 serve 同一种常驻语义，复用同一处实现，避免两处各写一遍「阻塞到进程结束」
      ServeCommand.keepAlive();
    }
  }
}
