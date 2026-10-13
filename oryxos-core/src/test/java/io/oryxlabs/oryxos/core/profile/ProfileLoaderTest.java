package io.oryxlabs.oryxos.core.profile;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/**
 * US3 回归测试：Profile 从 Agent 目录派生、坏文件不阻断启动。
 *
 * <p>断言分两类，缺一不可：<b>装载结果</b>（谁进了、谁没进）与<b>错误消息</b>（有没有指名道姓说清是哪个 Agent 坏了）。
 * 只断言"抛没抛异常"等于没测——坏文件被静默吞掉时那种写法照样绿。
 *
 * <p>错误消息从 logback 的 {@link ListAppender} 取，因为设计上单条失败只记日志、不抛异常（FR-002）。
 */
class ProfileLoaderTest {

  /** 全局层厂商清单：本用例只认 deepseek。 */
  private static final Set<String> KNOWN_PROVIDERS = Set.of("deepseek");

  @TempDir Path tempDir;

  private Path agentsDir;
  private ListAppender<ILoggingEvent> logs;
  private Logger loaderLogger;

  @BeforeEach
  void setUp() throws IOException {
    agentsDir = Files.createDirectories(tempDir.resolve("agents"));
    loaderLogger = (Logger) LoggerFactory.getLogger(ProfileLoader.class);
    logs = new ListAppender<>();
    logs.start();
    loaderLogger.addAppender(logs);
  }

  @AfterEach
  void tearDown() {
    loaderLogger.detachAppender(logs);
    logs.stop();
  }

  /** 造一个 Agent 目录，AGENT.md 由 frontmatter 正文拼成。 */
  private void writeAgent(String agentName, String frontmatter) throws IOException {
    Path dir = Files.createDirectories(agentsDir.resolve(agentName));
    Files.writeString(dir.resolve("AGENT.md"), "---\n" + frontmatter + "---\n\n这是正文。\n");
  }

  /** 只造目录、不写 AGENT.md。 */
  private void writeAgentDirWithoutAgentMd(String agentName) throws IOException {
    Files.createDirectories(agentsDir.resolve(agentName));
  }

  private ProfileLoader loader() {
    return loader(name -> null);
  }

  private ProfileLoader loader(java.util.function.UnaryOperator<String> env) {
    return new ProfileLoader(agentsDir, KNOWN_PROVIDERS, env);
  }

  private List<String> loadedNames(List<Profile> profiles) {
    return profiles.stream().map(Profile::name).sorted().toList();
  }

  private String errorLog() {
    return logs.list.stream()
        .filter(event -> event.getLevel() == Level.ERROR)
        .map(ILoggingEvent::getFormattedMessage)
        .collect(Collectors.joining("\n"));
  }

  @Test
  @DisplayName("合法 frontmatter：全字段解析到位")
  void loadsAllFrontmatterFields() throws IOException {
    writeAgent(
        "ops-agent",
        """
        description: 运维 Agent
        identity:
          agent_name: 小运维
          prompt: 你是运维助手
        provider:
          name: deepseek
          model: deepseek-chat
          temperature: 0.3
        tools:
          - read_file
          - shell
        mcp_servers:
          - filesystem
        channels:
          - name: cli
        schedules:
          - cron: "0 9 * * *"
            zone: Asia/Shanghai
            message: 早上好
        bootstrap:
          - AGENTS.md
        settings:
          max_iterations: 7
          max_history_turns: 5
        """);

    List<Profile> profiles = loader().loadAll();

    assertThat(loadedNames(profiles)).containsExactly("ops-agent");
    Profile profile = profiles.get(0);
    assertThat(profile.description()).isEqualTo("运维 Agent");
    assertThat(profile.identity().agentName()).isEqualTo("小运维");
    assertThat(profile.identity().prompt()).isEqualTo("你是运维助手");
    assertThat(profile.provider().name()).isEqualTo("deepseek");
    assertThat(profile.provider().model()).isEqualTo("deepseek-chat");
    assertThat(profile.provider().temperature()).isEqualTo(0.3);
    assertThat(profile.tools()).containsExactly("read_file", "shell");
    assertThat(profile.mcpServers()).containsExactly("filesystem");
    assertThat(profile.channels()).hasSize(1);
    assertThat(profile.channels().get(0).name()).isEqualTo("cli");
    assertThat(profile.schedules()).hasSize(1);
    assertThat(profile.schedules().get(0).cron()).isEqualTo("0 9 * * *");
    assertThat(profile.schedules().get(0).zone()).isEqualTo("Asia/Shanghai");
    assertThat(profile.schedules().get(0).message()).isEqualTo("早上好");
    assertThat(profile.bootstrap()).containsExactly("AGENTS.md");
    assertThat(profile.settings().maxIterations()).isEqualTo(7);
    assertThat(profile.settings().maxHistoryTurns()).isEqualTo(5);
  }

  @Test
  @DisplayName("引用不存在的 provider：报错清晰")
  void rejectsProviderNotInGlobalList() throws IOException {
    writeAgent(
        "chat-agent",
        """
        description: 客服 Agent
        provider:
          name: qwen
          model: qwen-max
        """);
    writeAgent(
        "ops-agent",
        """
        description: 运维 Agent
        provider:
          name: deepseek
          model: deepseek-chat
        """);

    List<Profile> profiles = loader().loadAll();

    // 其余 Agent 照常装载——单条不合法不许拖垮整批（FR-002）
    assertThat(loadedNames(profiles)).containsExactly("ops-agent");
    // 错误消息必须指名道姓：哪个 Agent、引用了哪个厂商
    assertThat(errorLog()).contains("chat-agent").contains("qwen");
  }

  @Test
  @DisplayName("坏文件不阻断其余加载")
  void brokenFileDoesNotBlockOthers() throws IOException {
    writeAgent(
        "good-one",
        """
        description: 好 Agent 一号
        provider:
          name: deepseek
          model: deepseek-chat
        """);
    writeAgent(
        "good-two",
        """
        description: 好 Agent 二号
        provider:
          name: deepseek
          model: deepseek-reasoner
        """);
    // 坏文件一：YAML 语法错（缩进错乱）
    writeAgent("broken-yaml", "description: 坏\nprovider:\n  name: deepseek\n   model: x\n");
    // 坏文件二：写了显式禁止的 skills 字段（data-model.md §1「显式禁止的字段」）
    writeAgent(
        "forbidden-field",
        """
        description: 写了 skills
        provider:
          name: deepseek
          model: deepseek-chat
        skills:
          - some-skill
        """);

    List<Profile> profiles = loader().loadAll();

    assertThat(loadedNames(profiles)).containsExactly("good-one", "good-two");
    assertThat(errorLog()).contains("broken-yaml").contains("forbidden-field");
  }

  @Test
  @DisplayName("${ENV} 占位从环境变量解析")
  void resolvesEnvPlaceholders() throws IOException {
    writeAgent(
        "ops-agent",
        """
        description: 运维 Agent
        identity:
          prompt: 你用 ${MODEL_NAME} 干活
        provider:
          name: deepseek
          model: ${MODEL_NAME}
        """);

    List<Profile> profiles =
        loader(name -> "MODEL_NAME".equals(name) ? "deepseek-reasoner" : null).loadAll();

    assertThat(loadedNames(profiles)).containsExactly("ops-agent");
    assertThat(profiles.get(0).provider().model()).isEqualTo("deepseek-reasoner");
    assertThat(profiles.get(0).identity().prompt()).isEqualTo("你用 deepseek-reasoner 干活");
  }

  @Test
  @DisplayName("目录缺 AGENT.md：按坏文件处理")
  void missingAgentMdTreatedAsBroken() throws IOException {
    writeAgentDirWithoutAgentMd("empty-agent");
    writeAgent(
        "ops-agent",
        """
        description: 运维 Agent
        provider:
          name: deepseek
          model: deepseek-chat
        """);

    List<Profile> profiles = loader().loadAll();

    assertThat(loadedNames(profiles)).containsExactly("ops-agent");
    assertThat(errorLog()).contains("empty-agent");
  }
}
