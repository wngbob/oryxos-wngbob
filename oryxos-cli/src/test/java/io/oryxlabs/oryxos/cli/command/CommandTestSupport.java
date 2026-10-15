package io.oryxlabs.oryxos.cli.command;

import io.oryxlabs.oryxos.cli.workspace.Workspace;
import io.oryxlabs.oryxos.cli.workspace.WorkspaceDb;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import picocli.CommandLine;

/**
 * {@code notify-channel} 三条轻命令的测试脚手架。
 *
 * <p><b>为什么收口这三件事</b>：建一个可用工作区、把 stdout 抓下来、把运行期异常原样抛给断言——三条命令的测试各写一遍必然抄错一处， 尤其是最后一件（Picocli 默认把
 * {@code run()} 抛出的异常包一层再打堆栈，不处理就只能断言输出文本）。
 *
 * <p>包级私有：只在 {@code io.oryxlabs.oryxos.cli.command} 的测试里用，不进任何对外概念清单。
 */
final class CommandTestSupport {

  /** 空输入流：任何命令读 stdin 都立刻得到 EOF，测试不会挂在真终端上。 */
  private static final InputStream NO_INPUT = new ByteArrayInputStream(new byte[0]);

  private CommandTestSupport() {}

  /**
   * 建一个可用工作区：{@code .oryxos/} 目录 + 已执行建表脚本的库文件。
   *
   * <p>建表脚本在这里就跑一次，而不是让被测命令去跑——被测命令自己会跑（{@link NotifyChannelDb}），这里先建好是为了让「命令没跑起来」
   * 与「命令跑了但表没建」两种情况在测试里分得开。
   */
  static Workspace freshWorkspace(Path tempDir) {
    try {
      Files.createDirectories(tempDir.resolve(".oryxos"));
    } catch (IOException e) {
      throw new IllegalStateException("建不出测试工作区：" + tempDir, e);
    }
    Workspace workspace = new Workspace(tempDir);
    try (WorkspaceDb database = WorkspaceDb.open(workspace.dbFile())) {
      database.runSchemaScript();
    }
    return workspace;
  }

  /** 用空 stdin 跑一条命令（不读确认输入的命令走这条）。 */
  static String run(CommandLine commandLine, String... args) {
    return runWithInput(commandLine, NO_INPUT, args);
  }

  /** 用指定 stdin 内容跑一条命令（{@code remove} 的确认输入走这条）。 */
  static String runWithInput(CommandLine commandLine, String stdin, String... args) {
    return runWithInput(
        commandLine, new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)), args);
  }

  /**
   * 跑一条命令，返回它打到 stdout 的内容。
   *
   * <p><b>为什么是 {@code parseArgs} + 直接 {@code run()}，而不是 {@code
   * execute()}</b>：本组最要钉的东西是「抛的是什么异常、消息里有没有凭证」， 而 {@code execute()} 会把 {@code run()} 抛出的异常包成 {@code
   * ExecutionException} 再交给执行异常处理器——处理器里就算原样重抛， 它也已不再是命令自己那个异常类型（实测重抛后 {@code assertThatThrownBy}
   * 什么也抓不到）。{@code parseArgs} 保留<b>真的</b> Picocli 解析（选项名、arity、必填都在这一层生效），之后的 {@code run()}
   * 由本方法直接调用，异常原样上抛。
   *
   * <p>代价如实记录：退出码与「报错不打堆栈」那两条约定本方法不覆盖——它们是 Picocli 处理器的事， 由 {@code OryxOsCli}
   * 的命令树测试与人工验收覆盖，不在这里假装测过。
   *
   * <p><b>为什么必须恢复 {@code System.out} / {@code System.in}</b>：两者都是进程级全局状态，不恢复会污染同 JVM 里后续的测试。
   */
  private static String runWithInput(CommandLine commandLine, InputStream stdin, String... args) {
    commandLine.parseArgs(args);
    Runnable command = commandLine.getCommand();
    PrintStream originalOut = System.out;
    InputStream originalIn = System.in;
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
    System.setIn(stdin);
    try {
      command.run();
    } finally {
      System.setOut(originalOut);
      System.setIn(originalIn);
    }
    return captured.toString(StandardCharsets.UTF_8);
  }
}
