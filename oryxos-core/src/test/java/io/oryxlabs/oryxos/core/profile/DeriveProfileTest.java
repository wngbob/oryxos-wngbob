package io.oryxlabs.oryxos.core.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 课件 harness 第 2 行「{@code DeriveProfileTest}」的落地（第 29 节）。
 *
 * <p>守两件事：① frontmatter 的每一段都映射到 {@link Profile} 的对应字段；② {@code schedules}
 * <b>原样</b>带进派生结果（内容与顺序都不动，cron / 时区的合法性留给 {@code AgentScheduler} 逐条判定）。
 *
 * <p>失败消息与启动扫描逐字一致，是 FR-004 / SC-003 的一半证据；另一半（异常类型）见 {@code AgentLoaderTest}。
 */
class DeriveProfileTest {

  private static final Set<String> KNOWN_PROVIDERS = Set.of("deepseek", "qwen");

  @TempDir Path tempDir;

  private Path agentsDir;

  @BeforeEach
  void setUp() throws IOException {
    agentsDir = Files.createDirectories(tempDir.resolve("agents"));
  }

  @Test
  @DisplayName("DeriveProfileTest：frontmatter 各字段一一映射到 Profile")
  void deriveProfile_mapsEveryFrontmatterSection() throws IOException {
    Path dir =
        writeAgent(
            "ops",
            """
            description: 运维 Agent
            identity:
              agent_name: 小运维
              prompt: 你是运维助手
            provider:
              name: deepseek
              model: deepseek-chat
              temperature: 0.3
            tools: [read_file, shell]
            mcp_servers: [filesystem]
            channels:
              - name: cli
            bootstrap: [AGENTS.md]
            settings:
              max_iterations: 7
              max_history_turns: 5
            """);

    Profile profile = loader(name -> null).deriveProfile(dir);

    assertThat(profile.name()).isEqualTo("ops");
    assertThat(profile.description()).isEqualTo("运维 Agent");
    assertThat(profile.identity().agentName()).isEqualTo("小运维");
    assertThat(profile.identity().prompt()).isEqualTo("你是运维助手");
    assertThat(profile.provider().name()).isEqualTo("deepseek");
    assertThat(profile.provider().model()).isEqualTo("deepseek-chat");
    assertThat(profile.provider().temperature()).isEqualTo(0.3);
    assertThat(profile.tools()).containsExactly("read_file", "shell");
    assertThat(profile.mcpServers()).containsExactly("filesystem");
    assertThat(profile.channels()).extracting(Profile.ChannelSpec::name).containsExactly("cli");
    assertThat(profile.bootstrap()).containsExactly("AGENTS.md");
    assertThat(profile.settings().maxIterations()).isEqualTo(7);
    assertThat(profile.settings().maxHistoryTurns()).isEqualTo(5);
  }

  @Test
  @DisplayName("DeriveProfileTest：schedules 原样带进派生结果（内容与顺序不变）")
  void deriveProfile_carriesSchedulesVerbatim() throws IOException {
    Path dir =
        writeAgent(
            "recon",
            """
            provider:
              name: deepseek
              model: deepseek-chat
            schedules:
              - cron: "0 0 9 * * *"
                zone: Asia/Shanghai
                message: 早报
              - cron: "0 30 18 * * *"
                zone: UTC
                message: 晚报
            """);

    Profile profile = loader(name -> null).deriveProfile(dir);

    // 非法 cron / 时区不在这里拦：派生是结构映射，逐条判定归 AgentScheduler
    assertThat(profile.schedules())
        .containsExactly(
            new Profile.ScheduleSpec("0 0 9 * * *", "Asia/Shanghai", "早报"),
            new Profile.ScheduleSpec("0 30 18 * * *", "UTC", "晚报"));
  }

  @Test
  @DisplayName("DeriveProfileTest：缺省段落到 Profile 的默认值")
  void deriveProfile_appliesDefaultsForOmittedSections() throws IOException {
    Path dir =
        writeAgent(
            "minimal",
            """
            provider:
              name: deepseek
              model: deepseek-chat
            """);

    Profile profile = loader(name -> null).deriveProfile(dir);

    assertThat(profile.identity()).isEqualTo(Profile.Identity.EMPTY);
    assertThat(profile.settings()).isEqualTo(Profile.Settings.DEFAULT);
    assertThat(profile.tools()).isEmpty();
    assertThat(profile.mcpServers()).isEmpty();
    assertThat(profile.channels()).isEmpty();
    assertThat(profile.schedules()).isEmpty();
    assertThat(profile.bootstrap()).isEmpty();
    assertThat(profile.provider().temperature()).isNull();
  }

  @Test
  @DisplayName("DeriveProfileTest：顶层 name 被忽略，Agent 名取目录名")
  void deriveProfile_ignoresTopLevelName() throws IOException {
    Path dir =
        writeAgent(
            "from-directory",
            """
            name: from-frontmatter
            provider:
              name: deepseek
              model: deepseek-chat
            """);

    assertThat(loader(name -> null).deriveProfile(dir).name()).isEqualTo("from-directory");
  }

  @Test
  @DisplayName("DeriveProfileTest：${ENV_VAR} 解析与启动扫描同口径")
  void deriveProfile_resolvesEnvPlaceholders() throws IOException {
    Path dir =
        writeAgent(
            "env",
            """
            identity:
              prompt: 你用 ${MODEL_NAME} 干活
            provider:
              name: deepseek
              model: ${MODEL_NAME}
            """);
    UnaryOperator<String> env = name -> "MODEL_NAME".equals(name) ? "deepseek-reasoner" : null;

    Profile profile = loader(env).deriveProfile(dir);

    assertThat(profile.provider().model()).isEqualTo("deepseek-reasoner");
    assertThat(profile.identity().prompt()).isEqualTo("你用 deepseek-reasoner 干活");
  }

  @Test
  @DisplayName("DeriveProfileTest：非法配置的消息与启动扫描逐字一致")
  void deriveProfile_failureMessagesMatchScan() throws IOException {
    writeAgent(
        "missing-env",
        """
        provider:
          name: deepseek
          model: ${NOT_SET_MODEL}
        """);
    writeAgent(
        "forbidden-skills",
        """
        provider:
          name: deepseek
          model: deepseek-chat
        skills: [some-skill]
        """);
    writeAgent(
        "forbidden-channels",
        """
        provider:
          name: deepseek
          model: deepseek-chat
        notify_channels: [ops]
        """);
    writeAgent(
        "bad-tools",
        """
        provider:
          name: deepseek
          model: deepseek-chat
        tools: read_file
        """);
    ProfileLoader loader = loader(name -> null);

    ProfileLoader.LoadResult scan = loader.loadAllDetailed();

    assertThat(scan.loaded()).isEmpty();
    assertThat(scan.failures()).hasSize(4);
    for (ProfileLoader.LoadResult.Failure failure : scan.failures()) {
      assertThatThrownBy(() -> loader.deriveProfile(agentsDir.resolve(failure.agentName())))
          .isInstanceOf(ProfileLoader.ProfileLoadException.class)
          .hasMessage(failure.reason());
    }
    assertThat(scan.failures())
        .extracting(ProfileLoader.LoadResult.Failure::reason)
        .contains(
            "环境变量 NOT_SET_MODEL 未设置，占位 ${NOT_SET_MODEL} 无法解析（报错不回显凭证值）",
            "frontmatter 不允许声明 'skills'：Skill 归属由软连接表达、通知渠道归全局注册表",
            "frontmatter 不允许声明 'notify_channels'：Skill 归属由软连接表达、通知渠道归全局注册表",
            "tools 必须是列表");
  }

  private ProfileLoader loader(UnaryOperator<String> env) {
    return new ProfileLoader(agentsDir, KNOWN_PROVIDERS, env);
  }

  /** 写入一个 Agent 目录的 {@code AGENT.md}（只写 frontmatter + 一行正文）。 */
  private Path writeAgent(String agentName, String frontmatter) throws IOException {
    Path dir = Files.createDirectories(agentsDir.resolve(agentName));
    Files.writeString(dir.resolve("AGENT.md"), "---\n" + frontmatter + "---\n\n正文\n");
    return dir;
  }
}
