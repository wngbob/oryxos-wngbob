package io.oryxlabs.oryxos.tool.interaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.oryxlabs.oryxos.core.tool.ToolResult;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code ask_user} 的四条守点（contracts/builtin-tools.md §2.9 / §3.4）。
 *
 * <p><b>本类要证的核心只有一句：拿不到回答时明确报错，绝不静默卡住</b>（FR-017）。所以「不支持交互」那条用 {@link
 * org.junit.jupiter.api.Assertions#assertTimeoutPreemptively} 卡了 2 秒——若哪天有人在实现里加了个 {@code while}
 * 轮询或阻塞等待，这条会超时失败，而不是让测试进程一起挂住。
 *
 * <p><b>终端实现用内存流驱动</b>，不碰进程级的 {@code System.in} / {@code System.out}：注入包级私有构造，既不改
 * 进程状态，也不依赖运行环境有没有真终端。
 */
class InteractionToolsTest {

  private static final Duration NOT_BLOCKING = Duration.ofSeconds(2);

  @Test
  @DisplayName("终端实现拿到回答：回答原样带回，问题被打印出来")
  void askUser_consoleImplementation_returnsAnswerVerbatim() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    InteractionTools tools = new InteractionTools(console("杭州\n", out));

    ToolResult result = tools.askUser("你问的是哪个城市？");

    assertThat(result.success()).isTrue();
    assertThat(result.content()).isEqualTo("杭州");
    assertThat(out.toString(StandardCharsets.UTF_8)).contains("你问的是哪个城市？");
  }

  @Test
  @DisplayName("用户按了回车（空回答）算合法回答，不是失败")
  void askUser_emptyLine_isSuccessNotFailure() {
    InteractionTools tools = new InteractionTools(console("\n", new ByteArrayOutputStream()));

    ToolResult result = tools.askUser("还有什么要补充的吗？");

    assertThat(result.success()).isTrue();
    assertThat(result.content()).isEmpty();
  }

  @Test
  @DisplayName("终端输入已结束（EOF）＝ 没人在答，明确报错而不是把空串当回答")
  void askUser_consoleAtEof_isReportedAsUnsupported() {
    InteractionTools tools = new InteractionTools(console("", new ByteArrayOutputStream()));

    ToolResult result = tools.askUser("继续吗？");

    assertThat(result.success()).isFalse();
    assertThat(result.errorMessage()).contains("不支持");
  }

  @Test
  @DisplayName("注入无人值守实现时明确报错，且不阻塞（超时断言）")
  void askUser_unsupportedChannel_reportsErrorWithoutBlocking() {
    InteractionTools tools = new InteractionTools(new UnsupportedUserInteraction());

    ToolResult result = assertTimeoutPreemptively(NOT_BLOCKING, () -> tools.askUser("请确认是否继续"));

    assertThat(result.success()).isFalse();
    assertThat(result.errorMessage()).isEqualTo("当前渠道不支持与用户交互");
    // 换一档渠道才有意义，重试同一个问题不会变好
    assertThat(result.retryable()).isFalse();
  }

  @Test
  @DisplayName("问题原样透传给接缝，不被工具改写")
  void askUser_forwardsQuestionVerbatim() {
    String[] seen = new String[1];
    UserInteraction recording =
        question -> {
          seen[0] = question;
          return Optional.of("好");
        };

    new InteractionTools(recording).askUser("  带首尾空格的问题？  ");

    assertThat(seen[0]).isEqualTo("  带首尾空格的问题？  ");
  }

  private static ConsoleUserInteraction console(String stdin, ByteArrayOutputStream out) {
    return new ConsoleUserInteraction(
        new BufferedReader(new StringReader(stdin)),
        new PrintStream(out, true, StandardCharsets.UTF_8));
  }
}
