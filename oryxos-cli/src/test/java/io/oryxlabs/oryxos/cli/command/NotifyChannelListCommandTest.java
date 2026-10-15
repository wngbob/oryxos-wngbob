package io.oryxlabs.oryxos.cli.command;

import static org.assertj.core.api.Assertions.assertThat;

import io.oryxlabs.oryxos.cli.workspace.Workspace;
import io.oryxlabs.oryxos.cli.workspace.WorkspaceDb;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * {@code oryxos notify-channel list} 的守点（contracts/cli-surface.md §2.12）。
 *
 * <p><b>本类的核心不是「列出来了没有」，而是「列出来了什么、没列什么」</b>：{@code url} 列含 token，{@code config} 的值
 * 里可能有签名密钥。所以断言分成两半——该出现的（渠道名、类型、占位符、配置<b>键名</b>）与<b>MUST NOT</b> 出现的（字面量地址、配置值）。 后者才是这一组命令存在的意义所在。
 *
 * <p>行由 {@link WorkspaceDb} 直接种进表，不走 {@code add} 命令——本类要验的是渲染，种数据这一步不该依赖另一个被测对象。
 */
class NotifyChannelListCommandTest {

  private static final String SECRET_URL =
      "https://open.example.invalid/open-apis/bot/v2/hook/TOKEN-abc123";

  private static final String SECRET_VALUE = "signing-secret-xyz";

  @TempDir Path tempDir;

  private Workspace workspace;

  @BeforeEach
  void setUp() {
    workspace = CommandTestSupport.freshWorkspace(tempDir);
  }

  private String list() {
    return CommandTestSupport.run(new CommandLine(new NotifyChannelListCommand(workspace)));
  }

  private void seed(String name, String type, String url, String configJson, String description) {
    try (WorkspaceDb database = WorkspaceDb.open(workspace.dbFile())) {
      database.execute(
          "INSERT INTO notify_channels (name, type, url, config_json, description)"
              + " VALUES (?, ?, ?, ?, ?)",
          name,
          type,
          url,
          configJson,
          description);
    }
  }

  @Test
  @DisplayName("表为空时明确说「无」并给出下一步命令，不是打一张空表")
  void emptyTableSaysSo() {
    String output = list();

    assertThat(output).contains("共 0 条").contains("notify-channel add");
  }

  @Test
  @DisplayName("按注册名升序，首行即默认渠道 —— 与 findFirstByOrderByNameAsc 同序，不是两套口径")
  void rowsAreOrderedByNameSoTheFirstIsTheDefaultChannel() {
    seed("zeta", "webhook", "https://z.invalid/hook", null, null);
    seed("alpha", "feishu", "${ALPHA_HOOK}", null, null);
    seed("mid", "lark", "https://m.invalid/hook", null, null);

    String output = list();

    assertThat(output).contains("首行为默认渠道");
    assertThat(output.indexOf("alpha")).isLessThan(output.indexOf("mid"));
    assertThat(output.indexOf("mid")).isLessThan(output.indexOf("zeta"));
  }

  @Test
  @DisplayName("url 列：纯占位符原样打印（用户要知道设哪个变量），字面量一律打 <已配置>")
  void urlColumnShowsPlaceholderButNeverLiteralAddress() {
    seed("from-env", "feishu", "${FEISHU_HOOK_URL}", null, null);
    seed("literal", "webhook", SECRET_URL, null, null);

    String output = list();

    assertThat(output).contains("${FEISHU_HOOK_URL}");
    assertThat(output).doesNotContain(SECRET_URL).doesNotContain("TOKEN-abc123");
    assertThat(output).contains("<已配置>");
  }

  @Test
  @DisplayName("混排的占位符按字面量对待 —— 只有「整串就是一个占位符」才敢原样打印")
  void partiallyPlaceholderUrlIsTreatedAsLiteral() {
    seed("mixed", "webhook", "https://host.invalid/${PATH_PART}", null, null);

    String output = list();

    assertThat(output).doesNotContain("${PATH_PART}").contains("<已配置>");
  }

  @Test
  @DisplayName("config 列只打键名，值一个字符都不打 —— secret 的值就是密钥")
  void configColumnShowsKeyNamesOnly() {
    seed(
        "with-config",
        "feishu",
        "${HOOK}",
        "{\"secret\":\"" + SECRET_VALUE + "\",\"mention\":\"all\"}",
        null);

    String output = list();

    assertThat(output).contains("secret").contains("mention");
    assertThat(output).doesNotContain(SECRET_VALUE);
  }

  @Test
  @DisplayName("config_json 解析不出来时打占位符，不抛异常 —— 表是人工也可写的，一条坏行不该让整张表列不出来")
  void unparsableConfigJsonDoesNotBreakTheListing() {
    seed("broken", "feishu", "${HOOK}", "not-json-at-all", null);
    seed("fine", "webhook", "${OTHER}", null, null);

    String output = list();

    assertThat(output).contains("<无法解析>").contains("fine");
  }

  @Test
  @DisplayName("空列渲染成占位符而不是空串 —— 空串会让列看起来错位")
  void blankColumnsRenderAsPlaceholder() {
    seed("bare", "webhook", "${HOOK}", null, "   ");

    String output = list();

    assertThat(output).contains("—");
  }

  @Test
  @DisplayName("说明里有中文与空白也原样打出")
  void descriptionIsRenderedVerbatim() {
    seed("team-lark", "feishu", "${HOOK}", null, "研发群 · 每日日报");

    assertThat(list()).contains("研发群 · 每日日报");
  }
}
