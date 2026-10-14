package io.oryxlabs.oryxos.cli;

import io.oryxlabs.oryxos.cli.command.ChatCommand;
import io.oryxlabs.oryxos.cli.command.GatewayCommand;
import io.oryxlabs.oryxos.cli.command.InitCommand;
import io.oryxlabs.oryxos.cli.command.ProfileCommand;
import io.oryxlabs.oryxos.cli.command.ProfileCreateCommand;
import io.oryxlabs.oryxos.cli.command.ProfileDeleteCommand;
import io.oryxlabs.oryxos.cli.command.ProfileListCommand;
import io.oryxlabs.oryxos.cli.command.ProfileShowCommand;
import io.oryxlabs.oryxos.cli.command.ProviderCommand;
import io.oryxlabs.oryxos.cli.command.ProviderListCommand;
import io.oryxlabs.oryxos.cli.command.ServeCommand;
import io.oryxlabs.oryxos.cli.command.SessionCommand;
import io.oryxlabs.oryxos.cli.command.SessionListCommand;
import io.oryxlabs.oryxos.cli.command.StatusCommand;
import io.oryxlabs.oryxos.cli.command.ToolCommand;
import io.oryxlabs.oryxos.cli.command.ToolListCommand;
import io.oryxlabs.oryxos.cli.workspace.Workspace;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * OryxOS 命令行主入口（Picocli）。
 *
 * <p>无参数运行打印版本信息；{@code -V} / {@code --version} 打印版本号；{@code -h} / {@code --help} 打印用法与 全部子命令。12
 * 个子命令见 {@code specs/003-cli-session/contracts/cli-surface.md} §1。
 *
 * <p><b>命令按轻重分流</b>（FR-003）：轻命令（{@code init} / {@code status} / {@code profile *} / {@code provider
 * list} / {@code tool list} / {@code session list}）不启动 Spring 上下文，直接走文件与 SQLite，秒回；重命令（{@code chat}
 * / {@code serve} / {@code gateway}）才启动 Spring 上下文。判断标准只有一条： 这条命令要不要调模型、跑引擎。
 *
 * <p><b>命令树在这里显式组装，不用 {@code @Command(subcommands = ...)}</b>：轻命令的构造函数要收 {@link Workspace}
 * （装配只此一处，命令类自己不去猜工作区在哪），而注解只能写类字面量、写不了实例。显式 {@code addSubcommand} 是 唯一既保留构造注入、又不引入反射工厂的写法。
 *
 * <p><b>退出码交给 Picocli 的默认约定</b>（成功 0 / 参数错 2 / 运行错 1）：{@link #main} 的 {@code System.exit(new
 * CommandLine(...).execute(args))} 形态保持不变，命令实现只抛异常、不手工 {@code System.exit} ——手工 exit 会跳过 Picocli
 * 的异常处理与用法打印。
 */
@Command(
    name = "oryxos",
    mixinStandardHelpOptions = true,
    versionProvider = OryxOsCli.VersionProvider.class,
    description = "OryxOS —— 企业 Agent 操作系统（Agent Harness OS）命令行工具",
    synopsisHeading = "用法: ",
    descriptionHeading = "%n描述:%n",
    optionListHeading = "%n选项:%n")
public final class OryxOsCli implements Runnable {

  /** 版本标语（无子命令时的默认行为）。 */
  @Override
  public void run() {
    System.out.println("OryxOS " + VersionProvider.loadVersion());
    System.out.println("企业 Agent 操作系统（Agent Harness OS）—— 让每一家公司，都能用自然语言跑起来自己的 Agent。");
    System.out.println("运行 'oryxos --help' 查看可用命令。");
  }

  public static void main(String[] args) {
    // Windows 上 System.out 默认走控制台代码页（GBK），中文在非 GBK 终端会乱码；
    // 统一强制 UTF-8 输出（现代终端均为 UTF-8）
    System.setOut(
        new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
    System.setErr(
        new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
    System.exit(buildCommandLine().execute(args));
  }

  /**
   * 组装命令树并装好通用约定（T037 / T038）。
   *
   * <p>单独成一个方法而不是塞在 {@link #main} 里：{@code main} 只做「流编码 + 交给 Picocli」两件事，命令树的形状
   * 与通用约定集中在一处，读代码时一眼能看全。
   *
   * @return 已挂好全部子命令、并注册了运行错误处理器的命令行实例
   */
  static CommandLine buildCommandLine() {
    // 工作区基准 = 进程当前目录；所有轻命令共用同一个实例，判定「是否在工作区内」只此一处
    Workspace workspace = Workspace.current();
    CommandLine root = new CommandLine(new OryxOsCli());
    root.addSubcommand("init", new InitCommand(workspace));
    root.addSubcommand("status", new StatusCommand(workspace));
    // 重命令：要调模型、跑引擎，自己起 Spring 上下文（FR-003）；它们不接 Workspace，因为工作区在上下文里装配
    root.addSubcommand("chat", new ChatCommand());
    root.addSubcommand("serve", new ServeCommand());
    root.addSubcommand("gateway", new GatewayCommand());
    root.addSubcommand("profile", profileGroup(workspace));
    root.addSubcommand("provider", providerGroup(workspace));
    root.addSubcommand("tool", toolGroup(workspace));
    root.addSubcommand("session", sessionGroup(workspace));
    // 运行错误只打消息、不打堆栈（FR-018 / SC-007）；参数错误的退出码 2 与用法打印仍走 Picocli 默认的
    // IParameterExceptionHandler——两者互不覆盖
    root.setExecutionExceptionHandler(new MessageOnlyExceptionHandler());
    return root;
  }

  private static CommandLine profileGroup(Workspace workspace) {
    return new CommandLine(new ProfileCommand())
        .addSubcommand("list", new ProfileListCommand(workspace))
        .addSubcommand("create", new ProfileCreateCommand(workspace))
        .addSubcommand("show", new ProfileShowCommand(workspace))
        .addSubcommand("delete", new ProfileDeleteCommand(workspace));
  }

  private static CommandLine providerGroup(Workspace workspace) {
    return new CommandLine(new ProviderCommand())
        .addSubcommand("list", new ProviderListCommand(workspace));
  }

  private static CommandLine toolGroup(Workspace workspace) {
    return new CommandLine(new ToolCommand()).addSubcommand("list", new ToolListCommand(workspace));
  }

  private static CommandLine sessionGroup(Workspace workspace) {
    return new CommandLine(new SessionCommand())
        .addSubcommand("list", new SessionListCommand(workspace));
  }

  /**
   * 运行错误的处理器：只把异常消息打给用户，返回退出码 1。
   *
   * <p><b>为什么必须显式注册</b>：Picocli 对未捕获的异常默认把**堆栈**打到 stderr——正是 FR-018 禁止的形态 （「清晰中文报错，不打印 Java
   * 堆栈给用户」）。不注册这个处理器，「报错不抛栈」这条就落不了地。
   *
   * <p>异常消息为空时回退到异常类名：宁可给出一个不好看但存在的提示，也不要打一行空的「错误: 」。
   */
  private static final class MessageOnlyExceptionHandler
      implements CommandLine.IExecutionExceptionHandler {

    @Override
    public int handleExecutionException(
        Exception ex, CommandLine commandLine, CommandLine.ParseResult parseResult) {
      System.err.println("错误: " + message(ex));
      return CommandLine.ExitCode.SOFTWARE;
    }

    private static String message(Throwable ex) {
      // Picocli 可能把 run() 抛出的异常包一层 ExecutionException；包了就取内层，未包则原样用
      Throwable cause =
          ex instanceof CommandLine.ExecutionException && ex.getCause() != null
              ? ex.getCause()
              : ex;
      String message = cause.getMessage();
      return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }
  }

  /**
   * 版本号来源：classpath 上的 {@code oryxos-version.properties}（Maven 构建时注入 {@code
   * project.version}），读取失败回退 {@code dev}，绝不硬编码版本号。
   */
  public static final class VersionProvider implements CommandLine.IVersionProvider {

    private static final String UNKNOWN = "dev";

    @Override
    public String[] getVersion() {
      return new String[] {loadVersion()};
    }

    static String loadVersion() {
      try (InputStream in = OryxOsCli.class.getResourceAsStream("/oryxos-version.properties")) {
        if (in != null) {
          Properties props = new Properties();
          props.load(in);
          String version = props.getProperty("version");
          if (version != null && !version.isBlank()) {
            return version;
          }
        }
      } catch (IOException ignored) {
        // 回退 dev
      }
      return UNKNOWN;
    }
  }
}
