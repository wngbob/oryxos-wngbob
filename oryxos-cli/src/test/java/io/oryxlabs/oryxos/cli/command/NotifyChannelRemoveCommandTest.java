package io.oryxlabs.oryxos.cli.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.oryxlabs.oryxos.cli.workspace.Workspace;
import io.oryxlabs.oryxos.cli.workspace.WorkspaceDb;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * {@code oryxos notify-channel remove} 的守点（contracts/cli-surface.md §2.12）。
 *
 * <p><b>两条容易写错的口径</b>：① 名字不存在 MUST 报错，MUST NOT 静默返回「已移除」——打错名字的人会以为删掉了 ② 未确认时<b>不删且正常退出（退出码
 * 0）</b>——按回车表示「算了」，那不是错误（与 {@code profile delete} 同口径）。
 */
class NotifyChannelRemoveCommandTest {

  private static final String SECRET_URL =
      "https://open.example.invalid/open-apis/bot/v2/hook/TOKEN-abc123";

  @TempDir Path tempDir;

  private Workspace workspace;

  @BeforeEach
  void setUp() {
    workspace = CommandTestSupport.freshWorkspace(tempDir);
    seed("team-lark");
    seed("ops-webhook");
  }

  private CommandLine command() {
    return new CommandLine(new NotifyChannelRemoveCommand(workspace));
  }

  private void seed(String name) {
    try (WorkspaceDb database = WorkspaceDb.open(workspace.dbFile())) {
      database.execute(
          "INSERT INTO notify_channels (name, type, url, config_json, description)"
              + " VALUES (?, ?, ?, ?, ?)",
          name,
          "feishu",
          SECRET_URL,
          null,
          null);
    }
  }

  @Test
  @DisplayName("--force 直接移除，只删指定的那一个")
  void forceRemovesTheNamedChannelOnly() {
    String output = CommandTestSupport.run(command(), "team-lark", "--force");

    assertThat(output).contains("已移除").contains("team-lark");
    assertThat(names()).containsExactly("ops-webhook");
  }

  @Test
  @DisplayName("名字不存在就报错并指向 list，MUST NOT 静默返回「已移除」")
  void unknownNameIsAnError() {
    assertThatThrownBy(() -> CommandTestSupport.run(command(), "typo-name", "--force"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("typo-name")
        .hasMessageContaining("notify-channel list");

    assertThat(names()).containsExactly("ops-webhook", "team-lark");
  }

  @Test
  @DisplayName("未确认时不动数据、也不报错（退出码 0）—— 按回车表示「算了」，那不是错误")
  void unansweredPromptLeavesDataIntact() {
    String output = CommandTestSupport.runWithInput(command(), "\n", "team-lark");

    assertThat(output).contains("已取消");
    assertThat(names()).containsExactly("ops-webhook", "team-lark");
  }

  @Test
  @DisplayName("stdin 读到 EOF（Ctrl+D / 管道读完）等同没确认")
  void endOfInputCountsAsNotConfirmed() {
    String output = CommandTestSupport.runWithInput(command(), "", "team-lark");

    assertThat(output).contains("已取消");
    assertThat(names()).containsExactly("ops-webhook", "team-lark");
  }

  @Test
  @DisplayName("回答 y 才真的删")
  void affirmativeAnswerRemoves() {
    String output = CommandTestSupport.runWithInput(command(), "y\n", "team-lark");

    assertThat(output).contains("已移除");
    assertThat(names()).containsExactly("ops-webhook");
  }

  @Test
  @DisplayName("任何输出都不含地址 —— 报错路径尤其要测，那是新增的异常构造点")
  void neverEchoesTheAddress() {
    String ok = CommandTestSupport.run(command(), "team-lark", "--force");
    assertThat(ok).doesNotContain(SECRET_URL).doesNotContain("TOKEN-abc123");

    assertThatThrownBy(() -> CommandTestSupport.run(command(), "nope", "--force"))
        .hasMessageNotContaining(SECRET_URL)
        .hasMessageNotContaining("TOKEN-abc123");
  }

  @Test
  @DisplayName("不给渠道名就报错，报错里带可直接抄的命令行")
  void blankNameIsRejected() {
    assertThatThrownBy(() -> CommandTestSupport.run(command(), "  ", "--force"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("notify-channel remove");

    assertThat(names()).containsExactly("ops-webhook", "team-lark");
  }

  private List<String> names() {
    try (WorkspaceDb database = WorkspaceDb.open(workspace.dbFile())) {
      return database.query(
          "SELECT name FROM notify_channels ORDER BY name",
          (ResultSet resultSet) -> resultSet.getString("name"));
    }
  }
}
