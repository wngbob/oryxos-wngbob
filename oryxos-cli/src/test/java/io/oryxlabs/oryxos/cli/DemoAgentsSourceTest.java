package io.oryxlabs.oryxos.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.oryxlabs.oryxos.core.profile.Profile;
import io.oryxlabs.oryxos.core.profile.ProfileLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * 三个验收 Demo 的「源码级」守点（第 31 节）。
 *
 * <p><b>为什么读仓库里的 {@code examples/} 而不是起 Spring 上下文</b>：这三个 Agent 的交付物就是目录本身，判据是「目录能被真实 {@link
 * ProfileLoader} 解析、白名单真的覆盖它们依赖的外部域」——起上下文反而把「目录不存在」和「装配失败」混成同一种红。
 *
 * <p><b>为什么不跑真实模型</b>：真实链路（工具选择、组稿质量、群里收到消息）归人工向导；这里只钉住那些改一行就会静默失效的事实： frontmatter 形状、cron、Skill
 * 软连接、白名单取值。
 */
class DemoAgentsSourceTest {

  /** Agent 目录内固定文件名。 */
  private static final String AGENT_FILE = "AGENT.md";

  /** 三份 Demo 用的厂商（与随包 provider 清单一致）。 */
  private static final Set<String> PROVIDERS = Set.of("deepseek");

  /** 仓库根下的示例目录。 */
  private static final Path EXAMPLES = repoRoot().resolve("examples");

  /** 示例 Agent 目录。 */
  private static final Path AGENTS = EXAMPLES.resolve("agents");

  /** 与生产装配同一套解析逻辑（目录名 = Agent 名）。 */
  private final ProfileLoader loader = new ProfileLoader(AGENTS, PROVIDERS);

  @Test
  @DisplayName("三个验收 Demo 的 Agent 目录与定义文件都在")
  void threeDemoAgentDirectoriesExist() throws IOException {
    for (String name : List.of("weather-daily", "daily-tech-digest", "github-daily")) {
      assertThat(AGENTS.resolve(name).resolve(AGENT_FILE))
          .as("Agent %s 的 AGENT.md", name)
          .isRegularFile();
    }

    Path skillLink = AGENTS.resolve("daily-tech-digest/skills/digest-format");
    assertThat(Files.isSymbolicLink(skillLink)).as("科技日报的 Skill 绑定必须是软连接").isTrue();
    assertThat(EXAMPLES.resolve("skills/digest-format/SKILL.md")).isRegularFile();

    String script = Files.readString(AGENTS.resolve("github-daily/scripts/github_trending.py"));
    assertThat(script).contains("api.github.com").contains("json.dump");
  }

  @Test
  @DisplayName("三份 AGENT.md 都能被真实 ProfileLoader 解析，工具与 cron 与课件对得上")
  void allAgentMarkdownParsesWithTheRealLoader() throws IOException {
    Profile weather = parse("weather-daily");
    assertThat(weather.tools()).containsExactly("http_get", "notify");
    assertThat(weather.schedules())
        .singleElement()
        .satisfies(
            schedule -> {
              assertThat(schedule.cron()).isEqualTo("0 0 8 * * *");
              assertThat(schedule.zone()).isEqualTo("Asia/Shanghai");
            });

    Profile digest = parse("daily-tech-digest");
    assertThat(digest.tools()).containsExactly("http_get", "read_file", "notify", "save_memory");
    assertThat(digest.schedules())
        .singleElement()
        .satisfies(
            schedule -> {
              assertThat(schedule.cron()).isEqualTo("0 0 9 * * *");
              assertThat(schedule.zone()).isEqualTo("Asia/Shanghai");
            });

    Profile github = parse("github-daily");
    assertThat(github.tools()).containsExactly("shell", "notify", "save_memory");
    assertThat(github.schedules())
        .singleElement()
        .satisfies(
            schedule -> {
              assertThat(schedule.cron()).isEqualTo("0 30 9 * * *");
              assertThat(schedule.zone()).isEqualTo("Asia/Shanghai");
            });
  }

  @Test
  @DisplayName("科技日报的 Skill 绑定是相对软连接，目标落在公共 Skill 库")
  void techDigestSkillBindingIsARelativeSymlink() throws IOException {
    Path link = AGENTS.resolve("daily-tech-digest/skills/digest-format");
    assertThat(Files.readSymbolicLink(link).toString()).isEqualTo("../../../skills/digest-format");
    assertThat(link.toRealPath()).isEqualTo(EXAMPLES.resolve("skills/digest-format").toRealPath());
    assertThat(Files.readString(link.resolve("SKILL.md"))).contains("name: digest-format");
  }

  @Test
  @DisplayName("三份 AGENT.md 都没有把 notify_channels / skills 写进 frontmatter")
  void forbiddenFrontmatterKeysAreAbsent() throws IOException {
    for (String name : List.of("weather-daily", "daily-tech-digest", "github-daily")) {
      String markdown = Files.readString(AGENTS.resolve(name).resolve(AGENT_FILE));
      assertThat(markdown)
          .as("%s 的 frontmatter", name)
          .doesNotContain("\nnotify_channels:")
          .doesNotContain("\nskills:");
    }
  }

  @Test
  @DisplayName("随包白名单覆盖三个 Demo 的天气源 / 新闻源 / GitHub 脚本与 python3")
  @SuppressWarnings("unchecked")
  void sandboxDefaultsCoverDemoDependencies() throws IOException {
    Map<String, Object> root = readApplicationYaml();
    Map<String, Object> http = (Map<String, Object>) root.get("http");
    assertThat((List<String>) http.get("allowed_domains"))
        .contains(
            "api.open-meteo.com",
            "geocoding-api.open-meteo.com",
            "hn.algolia.com",
            "api.github.com");

    // 天气 Demo 的两步都靠这两个端点：城市名只写一次，坐标由 Geocoding 接口给出
    String weather = Files.readString(AGENTS.resolve("weather-daily").resolve(AGENT_FILE));
    assertThat(weather).contains("geocoding-api.open-meteo.com").contains("api.open-meteo.com");
    // 城市字面量必须只出现在第 1 步 geocoding URL 里：写两处时模型只改散文、URL 照抄旧值（2026-10-22 实跑命中）
    assertThat(countOccurrences(weather, "北京"))
        .as("weather-daily/AGENT.md 正文里城市字面量「北京」的个数")
        .isEqualTo(1);

    Map<String, Object> shell = (Map<String, Object>) root.get("shell");
    assertThat((List<String>) shell.get("allowed_commands")).contains("python3");

    Map<String, Object> file = (Map<String, Object>) root.get("file");
    assertThat((List<String>) file.get("allowed_paths")).contains(".");
  }

  private static int countOccurrences(String text, String needle) {
    int count = 0;
    int from = 0;
    while ((from = text.indexOf(needle, from)) >= 0) {
      count++;
      from += needle.length();
    }
    return count;
  }

  /** 从仓库根读随包默认配置——它才是「部署方拿到的那份」，注入属性源守不了这一层。 */
  private static Map<String, Object> readApplicationYaml() throws IOException {
    Path file = repoRoot().resolve("oryxos-boot/src/main/resources/application.yaml");
    try (var reader = Files.newBufferedReader(file)) {
      return new Yaml(new SafeConstructor(new LoaderOptions())).load(reader);
    }
  }

  private Profile parse(String name) throws IOException {
    Path dir = AGENTS.resolve(name);
    return loader.parse(Files.readString(dir.resolve(AGENT_FILE)), name);
  }

  /**
   * 从当前工作目录（surefire 的 cwd 是模块目录）往上找仓库根。
   *
   * <p>与 {@code ShellWhitelistSourceTest#repoRoot} 同一手法；找不到就失败，不静默跳过——静默跳过等于守点悄悄失效。
   */
  private static Path repoRoot() {
    try (Stream<Path> candidates =
        Stream.iterate(Path.of("").toAbsolutePath(), Objects::nonNull, Path::getParent)) {
      return candidates
          .filter(
              dir ->
                  Files.isDirectory(dir.resolve(".git"))
                      && Files.isDirectory(dir.resolve("oryxos-boot")))
          .findFirst()
          .orElseThrow(
              () -> new IllegalStateException("从 " + Path.of("").toAbsolutePath() + " 往上找不到仓库根"));
    }
  }
}
