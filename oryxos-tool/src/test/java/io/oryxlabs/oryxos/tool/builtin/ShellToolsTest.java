package io.oryxlabs.oryxos.tool.builtin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.oryxlabs.oryxos.core.tool.ToolResult;
import io.oryxlabs.oryxos.tool.sandbox.ActionType;
import io.oryxlabs.oryxos.tool.sandbox.Sandbox;
import io.oryxlabs.oryxos.tool.sandbox.SandboxAction;
import io.oryxlabs.oryxos.tool.sandbox.SandboxViolationException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code shell} 的两条硬约束（contracts/builtin-tools.md §2.7 / §3.3）：argv 直传不经 Shell、越界先于 IO 被拦。
 *
 * <p>用例只用 {@code echo} / {@code ls} / {@code touch} / {@code sleep} 这些 POSIX 基础命令——不碰网络、不依赖工作目录。
 */
class ShellToolsTest {

  @TempDir Path tempDir;

  private static ShellTools permissive() {
    return new ShellTools(new RecordingSandbox(true));
  }

  @Test
  @DisplayName("shell 正常路径：返回退出码 + stdout")
  void shell_simpleCommand_returnsExitCodeAndStdout() {
    ToolResult result = permissive().shell("echo hello", null);

    assertThat(result.success()).isTrue();
    assertThat(result.content()).contains("退出码 0").contains("hello");
  }

  @Test
  @DisplayName("shell 的 argv 直传不经 Shell 解释：| 、&& 、$VAR 都不被展开")
  void shell_arguments_areNotInterpretedByShell() {
    // 走 Shell 的话 `|` 会变管道、`&&` 会串命令、`$HOME` 会被展开——那样 stdout 里不会出现这些字面量
    ToolResult result = permissive().shell("echo a | b && $HOME", null);

    assertThat(result.success()).isTrue();
    assertThat(result.content()).contains("a | b && $HOME");
  }

  @Test
  @DisplayName("shell 非零退出码 → 失败结果，且 stderr 一并带出")
  void shell_nonZeroExitCode_fails() {
    ToolResult result = permissive().shell("ls /no-such-path-oryxos-test", null);

    assertThat(result.success()).isFalse();
    // 失败结果的消息落在 errorMessage（content 只在成功时有值）——它正是审计表 error_message 的来源
    assertThat(result.errorMessage()).contains("退出码").contains("stderr");
    assertThat(result.retryable()).isFalse();
  }

  @Test
  @DisplayName("shell 超时上限生效：超时销毁进程并返回可重试失败")
  void shell_exceedingTimeout_isKilledAndReportedRetryable() {
    ShellTools tools = new ShellTools(new RecordingSandbox(true), Duration.ofMillis(300));

    ToolResult result = tools.shell("sleep 10", null);

    assertThat(result.success()).isFalse();
    assertThat(result.retryable()).isTrue();
    assertThat(result.errorMessage()).contains("超时");
  }

  @Test
  @DisplayName("shell 传了 timeoutSeconds 就以它为准：比默认超时长的命令也能跑完")
  void shell_customTimeout_isApplied() {
    // 默认超时压到 300 毫秒（单测构造），命令要跑 1 秒——参数不生效的话这里必然超时
    ShellTools tools = new ShellTools(new RecordingSandbox(true), Duration.ofMillis(300));

    ToolResult result = tools.shell("sleep 1", 5);

    assertThat(result.success()).isTrue();
  }

  @Test
  @DisplayName("shell timeoutSeconds 的上限 600 秒夹取：传 9999 不会真的等 9999 秒")
  void shell_timeoutAboveCap_isClamped() {
    // 直接验夹取函数而不是真等一次：600 秒的上限没法用「跑一次」证明
    assertThat(ShellTools.effectiveTimeout(Duration.ofSeconds(30), 9999))
        .isEqualTo(Duration.ofSeconds(600));
    assertThat(ShellTools.effectiveTimeout(Duration.ofSeconds(30), 10))
        .isEqualTo(Duration.ofSeconds(10));
    // 不传就沿用装配进来的默认值（生产是 30 秒）
    assertThat(ShellTools.effectiveTimeout(Duration.ofSeconds(30), null))
        .isEqualTo(Duration.ofSeconds(30));
  }

  @Test
  @DisplayName("shell timeoutSeconds 传 0：不可重试失败，而不是「立刻超时」")
  void shell_timeoutBelowMinimum_fails() {
    ToolResult result = permissive().shell("echo hello", 0);

    assertThat(result.success()).isFalse();
    assertThat(result.retryable()).isFalse();
    assertThat(result.errorMessage()).contains("timeoutSeconds 至少为 1");
  }

  @Test
  @DisplayName("shell 越界时必须被拦，且命令一次都没被执行")
  void shell_outsideWhitelist_isBlockedBeforeIo() {
    RecordingSandbox sandbox = new RecordingSandbox(false);

    assertThatThrownBy(() -> new ShellTools(sandbox).shell("echo hello", null))
        .isInstanceOf(SandboxViolationException.class);

    // 校验对象是可执行文件名，不是整条命令
    assertThat(sandbox.actions())
        .containsExactly(new SandboxAction(ActionType.SHELL_COMMAND, "echo"));
  }

  @Test
  @DisplayName("顺序断言：enforce 被调用时命令还没跑（标记文件尚不存在），放行之后才由命令创建")
  void enforce_happensBeforeIo_forShell() {
    Path marker = tempDir.resolve("marker.txt");
    List<Boolean> markerExistedAtEnforce = new ArrayList<>();
    Sandbox sandbox = action -> markerExistedAtEnforce.add(Files.exists(marker));

    ToolResult result = new ShellTools(sandbox).shell("touch " + marker, null);

    assertThat(result.success()).isTrue();
    // 反序实现（先跑命令后校验）下这里是 true——「抛了异常」证明不了顺序，这一条才能
    assertThat(markerExistedAtEnforce).containsExactly(false);
    assertThat(Files.exists(marker)).isTrue();
  }
}
