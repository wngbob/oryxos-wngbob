package io.oryxlabs.oryxos.core.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 课件 harness 第 1 行「{@code AgentLoaderTest}」的落地（第 29 节）。
 *
 * <p>本仓没有 {@code AgentLoader} 类：目录解析与 Profile 派生的落点是第 16 节的 {@link ProfileLoader}（spec 裁决 4），
 * 本类测的是「目录 → frontmatter / 正文 / 资源路径」这条解析链。
 *
 * <p><b>失败断言分两层</b>（FR-004 / SC-003）：启动扫描把失败收进 {@code LoadResult.Failure}，运行时派生把同一份失败抛成 {@link
 * ProfileLoader.ProfileLoadException}——两层的<b>消息必须逐字相同</b>，且消息本身<b>不含 Agent 名</b>（名字在 {@code
 * Failure.agentName} 上，拼进消息会让两条路径的消息对不上）。
 */
class AgentLoaderTest {

  /** 全局层厂商清单：本用例只认 deepseek。 */
  private static final Set<String> KNOWN_PROVIDERS = Set.of("deepseek");

  private static final String VALID_FRONTMATTER =
      """
      description: 每日对账
      identity:
        agent_name: 对账小欧
      provider:
        name: deepseek
        model: deepseek-chat
      tools: [shell, read_file]
      """;

  @TempDir Path tempDir;

  private Path agentsDir;
  private ProfileLoader loader;

  @BeforeEach
  void setUp() throws IOException {
    agentsDir = Files.createDirectories(tempDir.resolve("agents"));
    loader = new ProfileLoader(agentsDir, KNOWN_PROVIDERS, name -> null);
  }

  // ---------- inspect：解析链 ----------

  @Test
  @DisplayName("AgentLoaderTest：frontmatter 与正文拆分")
  void inspect_splitsFrontmatterAndBody() throws IOException {
    Path dir = writeAgent("recon", VALID_FRONTMATTER, "第一行指令。\n\n第二行指令。\n");

    ProfileLoader.AgentDir inspected = loader.inspect(dir);

    assertThat(inspected.name()).isEqualTo("recon");
    assertThat(inspected.path()).isEqualTo(dir);
    assertThat(inspected.frontmatter()).containsEntry("description", "每日对账");
    // 正文去首尾空白、内部空行原样保留
    assertThat(inspected.body()).isEqualTo("第一行指令。\n\n第二行指令。");
  }

  @Test
  @DisplayName("AgentLoaderTest：认出 scripts/、skills/ 与 REFERENCE.md，其余文件不进清单")
  void inspect_recognizesScriptsSkillsAndReference() throws IOException {
    Path dir = writeAgent("recon", VALID_FRONTMATTER, "正文");
    Files.createDirectories(dir.resolve("scripts"));
    Files.writeString(dir.resolve("scripts/reconcile.py"), "print('hi')\n");
    // 嵌套文件不递归：只列 scripts/ 的直接子文件
    Files.createDirectories(dir.resolve("scripts/nested"));
    Files.writeString(dir.resolve("scripts/nested/deep.py"), "print('deep')\n");
    // skills/ 的直接子项（这里用普通目录：资源清单只反映存在性，链接有效性归 ContextLoader）
    Files.createDirectories(dir.resolve("skills/report-format"));
    Files.writeString(dir.resolve("REFERENCE.md"), "# 参考\n");
    Files.writeString(dir.resolve("notes.txt"), "不进清单\n");

    ProfileLoader.AgentDir inspected = loader.inspect(dir);

    assertThat(inspected.resources())
        .containsExactly("REFERENCE.md", "scripts/reconcile.py", "skills/report-format");
  }

  @Test
  @DisplayName("AgentLoaderTest：资源清单只认存在性，不把内容读进快照")
  void inspect_returnsUnmodifiableFrontmatterCopy() throws IOException {
    Path dir = writeAgent("recon", VALID_FRONTMATTER, "正文");

    ProfileLoader.AgentDir inspected = loader.inspect(dir);

    // 快照不可变：谁改了它都改不到下一轮解析
    assertThatThrownBy(() -> inspected.frontmatter().put("description", "被改了"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(loader.inspect(dir).frontmatter()).containsEntry("description", "每日对账");
  }

  @Test
  @DisplayName("AgentLoaderTest：空正文是允许的，快照里是空串")
  void inspect_allowsEmptyBody() throws IOException {
    Path dir = writeAgent("recon", VALID_FRONTMATTER, "");

    assertThat(loader.inspect(dir).body()).isEmpty();
  }

  // ---------- 失败：扫描路径 ----------

  @Test
  @DisplayName("AgentLoaderTest：缺必填项报错点名（缺 AGENT.md / 缺 provider / 厂商未知）")
  void loadAllDetailed_namesEachFailureReason() throws IOException {
    Files.createDirectories(agentsDir.resolve("no-agent-md"));
    writeAgent("no-provider", "description: 没有 provider\n", "正文");
    writeAgent(
        "unknown-vendor",
        """
        description: 厂商未知
        provider:
          name: qwen
          model: qwen-max
        """,
        "正文");
    writeAgent("good", VALID_FRONTMATTER, "正文");

    ProfileLoader.LoadResult result = loader.loadAllDetailed();

    assertThat(result.loaded()).extracting(Profile::name).containsExactly("good");
    assertThat(result.failures())
        .extracting(ProfileLoader.LoadResult.Failure::agentName)
        .containsExactly("no-agent-md", "no-provider", "unknown-vendor");
    assertThat(result.failures())
        .extracting(ProfileLoader.LoadResult.Failure::reason)
        .containsExactly(
            "缺少 AGENT.md",
            "缺少 provider 段：每个 Agent 必须声明用哪家厂商、哪个模型",
            "provider.name 'qwen' 不在全局厂商清单中（已声明: [deepseek]）");
    // 消息本身不含 Agent 名——名字只在 agentName 上，否则运行时路径的消息永远对不上
    assertThat(result.failures())
        .allSatisfy(failure -> assertThat(failure.reason()).doesNotContain(failure.agentName()));
  }

  // ---------- 失败：运行时路径与扫描路径同一类型 + 同一消息 ----------

  @Test
  @DisplayName("AgentLoaderTest：deriveProfile 与 loadAllDetailed 同一异常类型、同一消息")
  void deriveProfile_throwsSameTypeAndMessageAsScanFailure() throws IOException {
    Files.createDirectories(agentsDir.resolve("no-agent-md"));
    writeAgent("no-provider", "description: 没有 provider\n", "正文");
    writeAgent(
        "unknown-vendor",
        """
        provider:
          name: qwen
          model: qwen-max
        """,
        "正文");

    ProfileLoader.LoadResult result = loader.loadAllDetailed();
    assertThat(result.failures()).hasSize(3);
    for (ProfileLoader.LoadResult.Failure failure : result.failures()) {
      Path dir = agentsDir.resolve(failure.agentName());
      assertThatThrownBy(() -> loader.deriveProfile(dir))
          .isInstanceOf(ProfileLoader.ProfileLoadException.class)
          .hasMessage(failure.reason());
    }
  }

  @Test
  @DisplayName("AgentLoaderTest：deriveProfile 与启动扫描派生同一个 Profile")
  void deriveProfile_matchesScanResult() throws IOException {
    Path dir = writeAgent("recon", VALID_FRONTMATTER, "正文");

    // 同一段 toProfile：不是「差不多」，是同一个值
    assertThat(loader.deriveProfile(dir)).isEqualTo(loader.loadAll().get(0));
  }

  @Test
  @DisplayName("AgentLoaderTest：入口显式拒绝 null，不把 NPE 埋在深处")
  void entryPoints_rejectNull() {
    assertThatThrownBy(() -> loader.inspect(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> loader.deriveProfile(null)).isInstanceOf(NullPointerException.class);
  }

  /** 造一个 Agent 目录并写入 {@code AGENT.md}（frontmatter + 正文）。 */
  private Path writeAgent(String agentName, String frontmatter, String body) throws IOException {
    Path dir = Files.createDirectories(agentsDir.resolve(agentName));
    Files.writeString(dir.resolve("AGENT.md"), "---\n" + frontmatter + "---\n\n" + body + "\n");
    return dir;
  }
}
