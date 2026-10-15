package io.oryxlabs.oryxos.tool.builtin;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.oryxlabs.oryxos.core.tool.ToolResult;
import io.oryxlabs.oryxos.tool.sandbox.ActionType;
import io.oryxlabs.oryxos.tool.sandbox.Sandbox;
import io.oryxlabs.oryxos.tool.sandbox.SandboxAction;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 内置命令工具：{@code shell}（contracts/builtin-tools.md §2.7）。
 *
 * <p><b>argv 直传，不经 Shell 解释</b>（FR-020）：命令串按空白切分成 argv 列表直接交给 {@link ProcessBuilder}， 管道、重定向、{@code
 * &&}、{@code $VAR} 一律不生效。{@code sh -c "<模型给的字符串>"} 等于把 Shell 的全部语法能力交给 模型——那是命令注入的经典入口；argv
 * 直传把可执行文件钉在第一位，白名单校验才有意义（校验对象是 {@code argv[0]}， 不是整条命令）。
 *
 * <p><b>输出必须先读干再等退出</b>：进程管道缓冲区约 64KB，输出超过它的命令会阻塞在写、永远不退出——直接 {@code waitFor} 会把一个「正常但话多」的命令（{@code
 * ls -R}、{@code git log --stat}）误判成超时。读放在一条虚拟线程里， 主线程才腾得出手执行「超时」这件事；虚拟线程是项目既定的并发模型（章程原则
 * VIII），不是自建线程池。
 */
public class ShellTools {

  /** 冻结在 contracts/builtin-tools.md §2.7：不传 {@code timeoutSeconds} 时的超时，30 秒。 */
  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

  /**
   * {@code timeoutSeconds} 的上限（contracts/builtin-tools.md §2.7）。
   *
   * <p><b>为什么给上限而不是无限放开</b>：模型可以为一个 {@code mvn} 传 600 秒，但若它能传 86400，一次卡住的调用就能把 ReAct
   * 循环占住一整天——超时存在的意义正是「有界返回」，那就不能由调用方把界拆掉。
   */
  private static final int MAX_TIMEOUT_SECONDS = 600;

  /** {@code timeoutSeconds} 的下限：0 或负数会变成「立刻超时」，那是误传参数而不是想表达的意思。 */
  private static final int MIN_TIMEOUT_SECONDS = 1;

  /** 进程已退出，管道必然 EOF；给读线程一点余量即可，不值得为它挂住主线程。 */
  private static final long DRAIN_JOIN_MILLIS = 1000L;

  private final Sandbox sandbox;

  private final Duration timeout;

  public ShellTools(Sandbox sandbox) {
    this(sandbox, DEFAULT_TIMEOUT);
  }

  /**
   * 包级私有：单测把 30 秒压到毫秒级来验证超时路径（生产调用方够不着，不构成对外概念）。
   *
   * @param sandbox 沙箱
   * @param timeout 命令执行超时上限
   */
  ShellTools(Sandbox sandbox, Duration timeout) {
    this.sandbox = sandbox;
    this.timeout = timeout;
  }

  /**
   * 执行一个命令。
   *
   * <p><b>关于 {@code @SuppressFBWarnings}</b>：FindSecBugs 把 {@code @ToolParam} 入参一律当污点源，见到「污点流进
   * {@code ProcessBuilder}」就报 COMMAND_INJECTION。此处是误报，理由与 {@link #split} 的契约一一对应：① 命令串按空白切成 argv
   * <b>逐元素</b>传给 {@code ProcessBuilder}，全程没有 Shell 参与——{@code ;}、{@code |}、{@code &&}、{@code $()}
   * 都只是普通 参数字符；② 真正的控制项是执行前的 {@code sandbox.enforce(argv[0])}，白名单校验的对象正是「跑哪个可执行文件」，这才是
   * 「模型能不能乱跑命令」的答案，不是字符串转义。就地豁免并留下理由，比在全局 excludeFilterFile 里开口子更可审计。
   *
   * @param command 命令行；按空白切分成 argv，不经 Shell 解释
   * @param timeoutSeconds 超时秒数（省略为 {@value #DEFAULT_TIMEOUT} 的 30 秒；上限 {@value
   *     #MAX_TIMEOUT_SECONDS} 秒， 超出按上限夹取）
   * @return 成功时为「退出码 + stdout + stderr」；非零退出码为失败结果（不可重试）；超时为可重试失败
   */
  @SuppressFBWarnings(
      value = "COMMAND_INJECTION",
      justification =
          "argv 逐元素直传 ProcessBuilder、不经 Shell 解释（FR-020），且 argv[0] 已先过 Sandbox 白名单，属 FindSecBugs 对工具入参的误报")
  @Tool(
      name = "shell",
      description =
          "执行一个命令（参数按空白切分后直接交给进程，不经 Shell：管道、重定向、&&、$VAR 都不生效）；"
              + "可传 timeoutSeconds 覆盖默认的 30 秒超时，上限 600 秒")
  public ToolResult shell(
      @ToolParam(description = "命令行；参数之间用空白分隔") String command,
      @ToolParam(description = "超时秒数（省略为 30，上限 600）", required = false) Integer timeoutSeconds) {
    if (timeoutSeconds != null && timeoutSeconds < MIN_TIMEOUT_SECONDS) {
      return ToolResult.failure(
          "timeoutSeconds 至少为 " + MIN_TIMEOUT_SECONDS + " 秒（收到 " + timeoutSeconds + "）");
    }
    List<String> argv = split(command);
    if (argv.isEmpty()) {
      return ToolResult.failure("命令为空");
    }
    // 校验对象是可执行文件名（argv[0]），不是整条命令
    sandbox.enforce(new SandboxAction(ActionType.SHELL_COMMAND, argv.get(0)));

    Process process;
    try {
      process = new ProcessBuilder(argv).start();
    } catch (IOException e) {
      return ToolResult.failure("无法启动命令 '" + argv.get(0) + "': " + e.getMessage());
    }
    return run(process, argv.get(0), effectiveTimeout(timeout, timeoutSeconds));
  }

  /**
   * 生效的超时：不传就用装配进来的 {@code fallback}，传了按 {@code [1, 600]} 秒夹取。
   *
   * <p>包级私有 static 是为了让单测直接验「上限确实夹住了」——否则要证明 600 秒的上限，就得真等一次 600 秒。
   *
   * @param fallback 不传 {@code timeoutSeconds} 时的超时（生产是 30 秒，单测可以压到毫秒级）
   * @param timeoutSeconds 模型给的超时秒数，可为 null
   * @return 实际用于 {@code waitFor} 的超时
   */
  static Duration effectiveTimeout(Duration fallback, Integer timeoutSeconds) {
    if (timeoutSeconds == null) {
      return fallback;
    }
    return Duration.ofSeconds(
        Math.min(Math.max(timeoutSeconds, MIN_TIMEOUT_SECONDS), MAX_TIMEOUT_SECONDS));
  }

  private ToolResult run(Process process, String executable, Duration timeout) {
    ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    Thread drain =
        Thread.ofVirtual()
            .unstarted(
                () -> {
                  copy(process.getInputStream(), stdout);
                  copy(process.getErrorStream(), stderr);
                });
    drain.start();

    boolean finished;
    try {
      finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      process.destroyForcibly();
      return ToolResult.retryableFailure("命令被中断: " + executable);
    }
    if (!finished) {
      process.destroyForcibly();
      joinQuietly(drain);
      return ToolResult.retryableFailure("命令超时（" + timeout.toSeconds() + " 秒）: " + executable);
    }
    joinQuietly(drain);

    String output =
        "退出码 "
            + process.exitValue()
            + "\nstdout:\n"
            + stdout.toString(StandardCharsets.UTF_8)
            + "\nstderr:\n"
            + stderr.toString(StandardCharsets.UTF_8);
    return process.exitValue() == 0 ? ToolResult.success(output) : ToolResult.failure(output);
  }

  /** 按空白切分——契约冻结的行为，不做引号解析（引号解析等于把一层 shell 语义又请回来）。 */
  private static List<String> split(String command) {
    if (command == null || command.isBlank()) {
      return List.of();
    }
    return Arrays.asList(command.trim().split("\\s+"));
  }

  private static void copy(InputStream source, ByteArrayOutputStream sink) {
    try {
      source.transferTo(sink);
    } catch (IOException ignored) {
      // 进程已被销毁时流会提前关闭，这不是错误：结果里已经写明「超时」，多余的异常只会盖掉真正的原因
    }
  }

  private static void joinQuietly(Thread thread) {
    try {
      thread.join(DRAIN_JOIN_MILLIS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
