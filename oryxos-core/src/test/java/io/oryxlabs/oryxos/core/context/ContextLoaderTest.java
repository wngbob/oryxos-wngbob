package io.oryxlabs.oryxos.core.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.oryxlabs.oryxos.core.profile.Profile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/**
 * {@link ContextLoader} 的验收测试（课件第 17 节「四、验收 harness」）。
 *
 * <p>全部走 JUnit 临时目录，不碰网络、不碰真实凭证。守护的四条：
 *
 * <ul>
 *   <li>改文件后<b>下一次</b>组装立即读到新内容（SC-008，无缓存）；
 *   <li>Profile 显式引用的文件缺失 → 报错并<b>指明是哪个文件</b>；
 *   <li>默认 Bootstrap 缺失 → 至少 WARN，不静默跳过；
 *   <li>Skill 软连接悬空 → 报错。
 * </ul>
 */
class ContextLoaderTest {

  @TempDir Path tmp;

  private Path oryxos;
  private Profile profile;

  @BeforeEach
  void setUp() throws IOException {
    oryxos = tmp.resolve(".oryxos");
    Files.createDirectories(oryxos.resolve("agents/demo"));
    writeAgentFile("demo", "你是 demo Agent，负责演示。");
    profile = profileOf("demo", List.of());
  }

  @Test
  @DisplayName("改文件后下一次组装立即读到新内容（无缓存）")
  void rereadsFileOnEveryLoad() throws IOException {
    Files.writeString(oryxos.resolve("AGENTS.md"), "第一版项目说明", StandardCharsets.UTF_8);
    ContextLoader loader = new ContextLoader(oryxos);

    assertThat(loader.load(profile)).contains("第一版项目说明");

    Files.writeString(oryxos.resolve("AGENTS.md"), "第二版项目说明", StandardCharsets.UTF_8);
    String second = loader.load(profile);

    assertThat(second).contains("第二版项目说明").doesNotContain("第一版项目说明");
  }

  @Test
  @DisplayName("Profile 显式引用的文件缺失：报错并指明是哪个文件")
  void explicitBootstrapMissingThrowsNamingFile() {
    ContextLoader loader = new ContextLoader(oryxos);

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> loader.load(profileOf("demo", List.of("MISSING_BOOTSTRAP.md"))));

    assertThat(thrown.getMessage()).contains("MISSING_BOOTSTRAP.md");
  }

  @Test
  @DisplayName("默认 Bootstrap 缺失：至少 WARN，不静默跳过")
  void defaultBootstrapMissingWarns() {
    // 三件套一个都不建，load 必须留下 WARN——「人格悄悄丢了」是最难查的软故障
    Logger loaderLogger = (Logger) LoggerFactory.getLogger(ContextLoader.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    loaderLogger.addAppender(appender);
    try {
      new ContextLoader(oryxos).load(profile);
    } finally {
      loaderLogger.detachAppender(appender);
    }

    assertThat(appender.list)
        .as("默认 Bootstrap 缺失必须至少产生一条 WARN")
        .anyMatch(event -> event.getLevel() == Level.WARN);
  }

  @Test
  @DisplayName("Skill 软连接悬空或越出 skills/：报错")
  void danglingSkillSymlinkThrows() throws IOException {
    Files.createDirectories(oryxos.resolve("skills"));
    Path agentSkills = Files.createDirectories(oryxos.resolve("agents/demo/skills"));
    // 指向一个不存在的位置：典型的手工复制 Agent 目录后忘了带公共 Skill 实体
    Files.createSymbolicLink(
        agentSkills.resolve("ghost-skill"), oryxos.resolve("skills/ghost-skill"));

    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, () -> new ContextLoader(oryxos).load(profile));

    assertThat(thrown.getMessage()).contains("ghost-skill");
  }

  private void writeAgentFile(String agentName, String body) throws IOException {
    Files.writeString(
        oryxos.resolve("agents").resolve(agentName).resolve("AGENT.md"),
        "---\nname: " + agentName + "\n---\n" + body,
        StandardCharsets.UTF_8);
  }

  private static Profile profileOf(String name, List<String> bootstrap) {
    return new Profile(
        name,
        null,
        null,
        new Profile.ProviderSpec("deepseek", "deepseek-chat", null),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        bootstrap,
        null);
  }
}
