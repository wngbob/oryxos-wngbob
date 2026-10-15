package io.oryxlabs.oryxos.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.oryxlabs.oryxos.cli.command.NotifyChannelAddCommand;
import io.oryxlabs.oryxos.cli.workspace.Workspace;
import io.oryxlabs.oryxos.cli.workspace.WorkspaceDb;
import io.oryxlabs.oryxos.tool.notify.NotifyChannelRegistry;
import io.oryxlabs.oryxos.tool.notify.NotifyTarget;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;
import picocli.CommandLine;

/**
 * 写入面 → 读路径的端到端往返（2026-10-15 补强②）。
 *
 * <p><b>为什么必须有这一条</b>：本次补强的全部意义是「把 100% 死掉的能力救活」，而「活了没有」恰恰是两边各自的单测都证明不了的——
 * 写入侧测的是「表里存了什么」，读路径测的是「给定一行能解析出什么」，两边都绿而中间对不上的情形（列名拼错、 {@code config_json}
 * 形状不一致、占位符一边写一边不认）完全可能发生。本条把两半用<b>真的</b>库连起来： 写入走真命令 + 真 SQLite，读出走真 Spring 上下文里的真 {@code
 * NotifyChannelRegistry} Bean。
 *
 * <p><b>为什么两条用例都要有</b>：
 *
 * <ul>
 *   <li>字面量那条走通「happy path」——证明行形状两半一致、Bean 能解析出目标。
 *   <li>占位符那条断言的是<b>抛异常</b>，而不是「返回原样的 {@code ${...}}」。这一点是刻意的：如果读路径没接占位解析，它会 把 {@code ${VAR}}
 *       当普通地址返回（测试看着「成功」，运行时却把字面占位符发给对端）；接了解析则因为变量没设而抛。 两种实现只有后一种会让这条变红。变量名用必然未设置的长名字，不依赖进程环境。
 * </ul>
 *
 * <p>放在 {@code io.oryxlabs.oryxos.cli} 而不是 {@code ...cli.command}：{@link CliTestContext} 是包级私有的，
 * 而起真上下文正是本条的一半。
 */
class NotifyChannelRoundTripTest {

  /** 必然未设置：名字长且带前缀，不与任何真实变量撞。 */
  private static final String UNSET_VAR = "ORYXOS_ROUNDTRIP_UNSET_12345";

  private static final String PLAIN_URL = "https://example.invalid/open-apis/bot/v2/hook/plain";

  @TempDir Path tempDir;

  private Workspace workspace;

  @BeforeEach
  void setUp() throws IOException {
    Files.createDirectories(tempDir.resolve(".oryxos"));
    workspace = new Workspace(tempDir);
    try (WorkspaceDb database = WorkspaceDb.open(workspace.dbFile())) {
      database.runSchemaScript();
    }
  }

  private void add(String... args) {
    int exitCode = new CommandLine(new NotifyChannelAddCommand(workspace)).execute(args);
    assertThat(exitCode).as("add 命令必须成功，参数：%s", String.join(" ", args)).isZero();
  }

  @Test
  @DisplayName("add 写的字面量渠道，真上下文里的注册表能按名解析出完整目标")
  void literalChannelWrittenByCliIsResolvableByTheRealRegistry() {
    add("ops-webhook", "--type", "webhook", "--url", PLAIN_URL, "--description", "运维群");

    try (ConfigurableApplicationContext context = CliTestContext.boot(workspace.dbFile())) {
      NotifyChannelRegistry registry = context.getBean(NotifyChannelRegistry.class);

      NotifyTarget target = registry.resolve("ops-webhook");

      assertThat(target.channelType()).isEqualTo("webhook");
      // url 键恒定存在，且以 url 列为准（data-model.md §5 第 6 步）
      assertThat(target.config()).containsEntry("url", PLAIN_URL);
    }
  }

  @Test
  @DisplayName("add --url-env 写下的占位符，读路径真的会去解析它 —— 凭证没落库，且解析确实接通了")
  void placeholderWrittenByCliIsResolvedAtReadTime() {
    add("team-lark", "--type", "feishu", "--url-env", UNSET_VAR);

    // 库里存的是字面量占位符，不是任何解析后的值 —— 这是「凭证不明文落库」的直接证据
    assertThat(storedUrl("team-lark")).isEqualTo("${" + UNSET_VAR + "}");

    try (ConfigurableApplicationContext context = CliTestContext.boot(workspace.dbFile())) {
      NotifyChannelRegistry registry = context.getBean(NotifyChannelRegistry.class);

      // 变量必然没设：接了读时解析 → 抛；没接 → 原样返回 ${...}（那才是真的坏）
      assertThatThrownBy(() -> registry.resolve("team-lark"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining(UNSET_VAR)
          .hasMessageContaining("team-lark");
    }
  }

  @Test
  @DisplayName("--config 写下的键值对，读路径能原样解析出来 —— config_json 的形状两半一致")
  void configWrittenByCliIsReadableByTheRealRegistry() {
    add(
        "team-lark",
        "--type",
        "feishu",
        "--url",
        PLAIN_URL,
        "--config",
        "secret=${" + UNSET_VAR + "}");

    try (ConfigurableApplicationContext context = CliTestContext.boot(workspace.dbFile())) {
      NotifyChannelRegistry registry = context.getBean(NotifyChannelRegistry.class);

      // config 里的占位同样会被解析 —— 所以这里也抛，且消息里带的是那个键名
      assertThatThrownBy(() -> registry.resolve("team-lark"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("secret")
          .hasMessageContaining(UNSET_VAR);
    }
  }

  /** 直接读库断言「存进去的到底是什么」——{@code query} 不收参数，故把名字带出来在 Java 侧对。表只有一行，不值得为它扩接缝。 */
  private String storedUrl(String name) {
    try (WorkspaceDb database = WorkspaceDb.open(workspace.dbFile())) {
      return database
          .query(
              "SELECT name, url FROM notify_channels",
              resultSet -> new String[] {resultSet.getString("name"), resultSet.getString("url")})
          .stream()
          .filter(cell -> name.equals(cell[0]))
          .map(cell -> cell[1])
          .findFirst()
          .orElseThrow(() -> new AssertionError("表里没有渠道 '" + name + "'"));
    }
  }
}
