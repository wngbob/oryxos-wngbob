package io.oryxlabs.oryxos.cli.command;

import io.oryxlabs.oryxos.cli.CliApplication;
import io.oryxlabs.oryxos.cli.workspace.Workspace;
import io.oryxlabs.oryxos.core.schedule.AgentScheduler;
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
 * <p><b>修订注（2026-10-19，第 26 节）：本节起 SERVLET 容器、装 REST 端点。</b>第 18 节写的「不起 HTTP 端口、不装任何 REST 端点、本模块
 * MUST NOT 依赖 {@code oryxos-web}」已全部失效——{@code oryxos-cli} 加了到 {@code oryxos-web} 的依赖边，
 * 端口真正打开，{@code /api/v1/**} 与 {@code /admin/**} 都在这个端口上。
 *
 * <p><b>{@code --port} 如何传进容器</b>：{@code WEB_APPLICATION_TYPE} 与端口都是 Spring 启动期读的属性，而 Picocli 已经在
 * Spring 之前跑完了——所以只能用 {@code System.setProperty} 把它塞进属性源（{@code server.port} 的优先级高于 {@code
 * application.yaml}，见 research D6）。不用 {@code --server.port=...} 形式：那会被 Picocli 当成未知选项直接退出码 2（第 22
 * 节切记忆档时实测过同一形态）。
 */
@Command(
    name = "serve",
    mixinStandardHelpOptions = true,
    description = "以 HTTP 接入模式启动（前台常驻；/api/v1 接口与 /admin 管理台在同一端口）")
public final class ServeCommand implements Runnable {

  /** 缺省端口，与上游契约的 8080 一致。 */
  private static final int DEFAULT_PORT = 8080;

  @Option(names = "--port", paramLabel = "<port>", description = "HTTP 端口（默认 " + DEFAULT_PORT + "）")
  private int port = DEFAULT_PORT;

  @Override
  public void run() {
    // 同 chat：工作区检查必须在起上下文之前，否则连不上库会被悄悄建出一个空库
    Workspace.current().requireExists();
    // 必须在 run() 之前设：容器在 SpringApplication.run 内部就已按 server.port 绑好端口
    System.setProperty("server.port", String.valueOf(port));
    try (ConfigurableApplicationContext context =
        new SpringApplicationBuilder(CliApplication.class)
            .web(WebApplicationType.SERVLET)
            .bannerMode(Banner.Mode.OFF)
            .run()) {
      // 定时任务只在常驻模式注册（FR-012 / 裁决 3）：chat 不碰这一行，装配处也不碰
      context.getBean(AgentScheduler.class).registerAll();
      System.out.println("serve 已启动（Bean 数 " + context.getBeanDefinitionCount() + "）");
      System.out.println("HTTP 端口 " + port + " 已打开");
      System.out.println("  REST 接口  http://localhost:" + port + "/api/v1");
      System.out.println("  管理台      http://localhost:" + port + "/admin/");
      System.out.println("  接口文档    http://localhost:" + port + "/swagger-ui");
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
