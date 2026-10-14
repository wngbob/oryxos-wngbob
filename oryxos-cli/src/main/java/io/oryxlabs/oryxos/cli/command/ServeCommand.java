package io.oryxlabs.oryxos.cli.command;

import io.oryxlabs.oryxos.cli.CliApplication;
import io.oryxlabs.oryxos.cli.workspace.Workspace;
import java.util.concurrent.CountDownLatch;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code oryxos serve [--port <8080>]}：HTTP 接入模式（spec 仲裁 15 / contracts/cli-surface.md §2.4）。
 *
 * <p><b>本节只把「模式」立起来</b>：启动 Spring 上下文后前台常驻，<b>不起 HTTP 端口、不装任何 REST 端点</b>——那是第 26 节。 因此本模块 MUST NOT
 * 依赖 {@code oryxos-web}。
 *
 * <p><b>{@code --port} 接受解析但只回显</b>：上游 {@code contracts/cli.md} 已把它定为对外契约，MUST NOT 静默吞掉一个已定参数；
 * 但本节没有端口可开，所以只把它如实打出来，不假装已经生效。
 */
@Command(
    name = "serve",
    mixinStandardHelpOptions = true,
    description = "以 HTTP 接入模式启动（前台常驻；REST 端点在第 26 节接入）")
public final class ServeCommand implements Runnable {

  /** 缺省端口，与上游契约的 8080 一致。 */
  private static final int DEFAULT_PORT = 8080;

  @Option(
      names = "--port",
      paramLabel = "<port>",
      description = "HTTP 端口（默认 " + DEFAULT_PORT + "）；端口在第 26 节启用")
  private int port = DEFAULT_PORT;

  @Override
  public void run() {
    // 同 chat：工作区检查必须在起上下文之前，否则连不上库会被悄悄建出一个空库
    Workspace.current().requireExists();
    try (ConfigurableApplicationContext context =
        new SpringApplicationBuilder(CliApplication.class)
            .web(WebApplicationType.NONE)
            .bannerMode(Banner.Mode.OFF)
            .run()) {
      System.out.println("serve 模式已启动（Bean 数 " + context.getBeanDefinitionCount() + "）");
      System.out.println("本模式将提供 HTTP 接入，REST 端点在第 26 节接入");
      System.out.println("已接受 --port " + port + "；端口在第 26 节启用");
      System.out.println("前台常驻中，Ctrl+C 退出。");
      keepAlive();
    }
  }

  /**
   * 前台常驻：一直等一个永远不会到来的信号。
   *
   * <p><b>为什么必须阻塞</b>：{@code run()} 直接返回等于「启动完就退」，退出码 0——用户会以为服务起来了又立刻停了， 而实际上什么都没在跑（research.md
   * D15 / D16）。Ctrl+C 由 JVM 终止进程，不需要额外的信号处理。
   *
   * <p>包级私有静态：{@code gateway} 是同一种常驻语义，直接复用（两个命令各写一遍这段，迟早有一处写成「启动完就退」）。
   */
  static void keepAlive() {
    try {
      new CountDownLatch(1).await();
    } catch (InterruptedException e) {
      // 被中断（Ctrl+C 之外的中断路径）时恢复中断位后返回，让 try-with-resources 正常关闭上下文
      Thread.currentThread().interrupt();
    }
  }
}
