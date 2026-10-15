package io.oryxlabs.oryxos.cli.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.oryxlabs.oryxos.cli.workspace.Workspace;
import io.oryxlabs.oryxos.cli.workspace.WorkspaceDb;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * {@code oryxos notify-channel add} 的守点（contracts/cli-surface.md §2.12）。
 *
 * <p><b>走真 Picocli 解析 + 真 SQLite，不 mock</b>：本命令的价值全在「选项怎么被解析」与「行怎么写进表」两件事上—— mock 掉任何一头，
 * 剩下的都是自证。工作区是 {@code @TempDir} 下的真目录，库是真 SQLite 文件。
 *
 * <p><b>本类最重的一组断言是「输出里没有凭证」</b>：{@code url} 含 token，命令的任何输出（含异常消息）都不许出现它。 这不是锦上添花——第 19 节那条挂账（IM
 * 群机器人把 token 塞进 webhook 地址）正是靠这条纪律不扩散的。
 */
class NotifyChannelAddCommandTest {

  private static final String SECRET_URL =
      "https://open.example.invalid/open-apis/bot/v2/hook/TOKEN-abc123";

  @TempDir Path tempDir;

  private Workspace workspace;

  @BeforeEach
  void setUp() {
    workspace = CommandTestSupport.freshWorkspace(tempDir);
  }

  private CommandLine command() {
    return new CommandLine(new NotifyChannelAddCommand(workspace));
  }

  @Test
  @DisplayName("--url-env 存的是字面量占位符，不是解析后的值 —— 凭证不落库")
  void urlEnvStoresPlaceholderNotValue() {
    String output =
        CommandTestSupport.run(
            command(), "team-lark", "--type", "feishu", "--url-env", "FEISHU_HOOK_URL");

    assertThat(row("team-lark").url()).isEqualTo("${FEISHU_HOOK_URL}");
    assertThat(row("team-lark").type()).isEqualTo("feishu");
    // 输出里出现的是占位符（变量名，不是凭证），且不提地址的值
    assertThat(output).contains("${FEISHU_HOOK_URL}").doesNotContain(SECRET_URL);
  }

  @Test
  @DisplayName("--url 传字面量就原样入库，但输出 MUST NOT 回显它 —— 那是含 token 的地址")
  void literalUrlIsStoredVerbatimButNeverEchoed() {
    String output =
        CommandTestSupport.run(command(), "team-lark", "--type", "feishu", "--url", SECRET_URL);

    assertThat(row("team-lark").url()).isEqualTo(SECRET_URL);
    assertThat(output).doesNotContain(SECRET_URL).doesNotContain("TOKEN-abc123");
    // 但要给出改道的提示，否则用户不会知道有更安全的写法
    assertThat(output).contains("--url-env");
  }

  @Test
  @DisplayName("--url 与 --url-env 互斥：都给就报错，且一次都不写库")
  void urlAndUrlEnvAreMutuallyExclusive() {
    assertThatThrownBy(
            () ->
                CommandTestSupport.run(
                    command(),
                    "team-lark",
                    "--type",
                    "feishu",
                    "--url",
                    SECRET_URL,
                    "--url-env",
                    "FEISHU_HOOK_URL"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("--url-env")
        .hasMessageNotContaining(SECRET_URL);

    assertThat(rows()).isEmpty();
  }

  @Test
  @DisplayName("两个地址选项一个都不给就报错，报错里给出可直接抄的命令行")
  void neitherAddressOptionIsRejected() {
    assertThatThrownBy(() -> CommandTestSupport.run(command(), "team-lark", "--type", "feishu"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("--url-env");

    assertThat(rows()).isEmpty();
  }

  @Test
  @DisplayName("--url-env 的名字必须能当环境变量用 —— 存下 ${FOO BAR} 会在读路径静默漏掉解析")
  void malformedEnvVarNameIsRejected() {
    assertThatThrownBy(
            () ->
                CommandTestSupport.run(
                    command(), "team-lark", "--type", "feishu", "--url-env", "FOO BAR"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("环境变量名");

    assertThat(rows()).isEmpty();
  }

  @Test
  @DisplayName("重名不覆盖：报错、提示先 remove，原行一个字都不动")
  void duplicateNameIsRejectedWithoutOverwriting() {
    CommandTestSupport.run(command(), "team-lark", "--type", "feishu", "--url-env", "FIRST_HOOK");

    assertThatThrownBy(
            () ->
                CommandTestSupport.run(
                    command(), "team-lark", "--type", "webhook", "--url-env", "SECOND_HOOK"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("已存在")
        .hasMessageContaining("notify-channel remove");

    assertThat(row("team-lark").type()).isEqualTo("feishu");
    assertThat(row("team-lark").url()).isEqualTo("${FIRST_HOOK}");
  }

  @Test
  @DisplayName("--config 的值里有 = 也不影响切分（base64 的 secret 常以 = 结尾）")
  void configValueMayContainEqualsSigns() {
    CommandTestSupport.run(
        command(),
        "team-lark",
        "--type",
        "feishu",
        "--url-env",
        "HOOK",
        "--config",
        "secret=abc=def=");

    assertThat(row("team-lark").configJson()).isEqualTo("{\"secret\":\"abc=def=\"}");
  }

  @Test
  @DisplayName("--config 可重复，写成 JSON 对象、值全是字符串")
  void configBecomesJsonObjectOfStrings() {
    CommandTestSupport.run(
        command(),
        "team-lark",
        "--type",
        "feishu",
        "--url-env",
        "HOOK",
        "--config",
        "secret=${SIGN}",
        "--config",
        "mention=all");

    assertThat(row("team-lark").configJson())
        .isEqualTo("{\"secret\":\"${SIGN}\",\"mention\":\"all\"}");
  }

  @Test
  @DisplayName("--config 里不许写 url=：读路径会往 config 强塞这个键，从这边塞就是第二个真相源")
  void configMustNotCarryUrlKey() {
    assertThatThrownBy(
            () ->
                CommandTestSupport.run(
                    command(),
                    "team-lark",
                    "--type",
                    "feishu",
                    "--url-env",
                    "HOOK",
                    "--config",
                    "url=" + SECRET_URL))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("--url")
        // 报错里 MUST NOT 回显那个地址
        .hasMessageNotContaining(SECRET_URL);

    assertThat(rows()).isEmpty();
  }

  @Test
  @DisplayName("--config 没写 =、键为空、键重复，三种形状错误各自报错且都不写库")
  void malformedConfigEntriesAreRejected() {
    assertThatThrownBy(
            () ->
                CommandTestSupport.run(
                    command(), "a", "--type", "feishu", "--url-env", "HOOK", "--config", "secret"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("k=v");

    assertThatThrownBy(
            () ->
                CommandTestSupport.run(
                    command(), "b", "--type", "feishu", "--url-env", "HOOK", "--config", "=value"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("键不能为空");

    assertThatThrownBy(
            () ->
                CommandTestSupport.run(
                    command(),
                    "c",
                    "--type",
                    "feishu",
                    "--url-env",
                    "HOOK",
                    "--config",
                    "secret=one",
                    "--config",
                    "secret=two"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("secret");

    assertThat(rows()).isEmpty();
  }

  @Test
  @DisplayName("没给 --config 时 config_json 存 NULL —— 读路径把 null 与空对象当同一件事")
  void absentConfigIsStoredAsNull() {
    CommandTestSupport.run(command(), "team-lark", "--type", "feishu", "--url-env", "HOOK");

    assertThat(row("team-lark").configJson()).isNull();
  }

  @Test
  @DisplayName("渠道名去空白后不能为空、也不能含空白")
  void unusableNamesAreRejected() {
    assertThatThrownBy(
            () -> CommandTestSupport.run(command(), "   ", "--type", "feishu", "--url-env", "HOOK"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("请提供渠道名");

    assertThatThrownBy(
            () ->
                CommandTestSupport.run(
                    command(), "team lark", "--type", "feishu", "--url-env", "HOOK"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("不能含空白");

    assertThat(rows()).isEmpty();
  }

  @Test
  @DisplayName("--type 是纯空白时报错 —— Picocli 的 required 只管「给没给」，管不了「给的是不是空的」")
  void blankTypeIsRejected() {
    assertThatThrownBy(
            () ->
                CommandTestSupport.run(command(), "team-lark", "--type", "  ", "--url-env", "HOOK"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("--type");

    assertThat(rows()).isEmpty();
  }

  @Test
  @DisplayName("--description 去空白后入库；纯空白等同没给")
  void descriptionIsTrimmed() {
    CommandTestSupport.run(
        command(), "a", "--type", "feishu", "--url-env", "HOOK", "--description", "  研发群  ");
    CommandTestSupport.run(
        command(), "b", "--type", "feishu", "--url-env", "HOOK", "--description", "  ");

    assertThat(row("a").description()).isEqualTo("研发群");
    assertThat(row("b").description()).isNull();
  }

  /** 读回一行，用来断言「表里到底存了什么」——比断言输出更硬。 */
  private Row row(String name) {
    return rows().stream()
        .filter(candidate -> name.equals(candidate.name()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("表里没有渠道 '" + name + "'：" + rows()));
  }

  private List<Row> rows() {
    try (WorkspaceDb database = WorkspaceDb.open(workspace.dbFile())) {
      return database.query(
          "SELECT name, type, url, config_json, description FROM notify_channels ORDER BY name",
          NotifyChannelAddCommandTest::map);
    }
  }

  private static Row map(ResultSet resultSet) throws SQLException {
    return new Row(
        resultSet.getString("name"),
        resultSet.getString("type"),
        resultSet.getString("url"),
        resultSet.getString("config_json"),
        resultSet.getString("description"));
  }

  /**
   * 一行的原始形状。
   *
   * @param name 渠道注册名
   * @param type 渠道类型
   * @param url 原始地址（可能是占位符，也可能是含 token 的字面量）
   * @param configJson 原始配置 JSON
   * @param description 说明
   */
  private record Row(String name, String type, String url, String configJson, String description) {}
}
