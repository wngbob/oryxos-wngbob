package io.oryxlabs.oryxos.core.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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

/**
 * 课件 harness「{@code ProgressiveDisclosureTest}」的落地（第 29 节，FR-007）。
 *
 * <p>判三件事：① {@code AGENT.md} 正文常驻 system prompt（frontmatter 不进）；② {@code REFERENCE.md} 正文与 {@code
 * scripts/} 代码<b>不预载</b>——它们只该在模型主动 {@code read_file} / {@code shell} 时才进上下文； ③ 绑定的 Skill 只注入
 * {@code name} / {@code description} / 绝对路径，正文不预载。
 *
 * <p><b>「无缓存」子项不在这里重测</b>：SC-004「改正文后下一次组装即读到新内容」由第 17 节的 {@code
 * ContextLoaderTest.rereadsFileOnEveryLoad} 覆盖（research D10，课件明确不重测）。
 *
 * <p>本测试<b>不修改</b> {@link ContextLoader}：失败只能修示例目录或测试夹具。
 */
class ProgressiveDisclosureTest {

  private static final String BODY_MARKER = "正文入口标记：严格按四步走";

  private static final String REFERENCE_MARKER = "参考正文标记：字段字典与可接受差异";

  private static final String SCRIPT_MARKER = "SCRIPT_CODE_MARKER";

  private static final String SKILL_BODY_MARKER = "Skill 正文标记：P0/P1/P2 分级规则";

  private static final String SKILL_DESCRIPTION = "对账报告的分级与结构说明";

  private static final String AGENT_MARKDOWN =
      """
      ---
      description: 每日对账
      identity:
        agent_name: 对账小欧
        prompt: 只根据脚本给出的确定性数据下结论。
      provider:
        name: deepseek
        model: deepseek-chat
      tools: [shell, read_file]
      ---

      %s。
      第 1 步跑脚本，第 2 步判断，第 3 步写报告，第 4 步推送。
      """
          .formatted(BODY_MARKER);

  private static final String SKILL_MARKDOWN =
      """
      ---
      name: report-format
      description: %s
      ---

      %s
      """
          .formatted(SKILL_DESCRIPTION, SKILL_BODY_MARKER);

  private static final String SCRIPT_PY =
      """
      import json

      %s = "这段代码不该出现在 system prompt 里"
      """
          .formatted(SCRIPT_MARKER);

  @TempDir Path tempDir;

  private Path oryxosDir;
  private Path agentDir;

  @BeforeEach
  void setUp() throws IOException {
    oryxosDir = tempDir.resolve(".oryxos");
    agentDir = oryxosDir.resolve("agents").resolve("daily-reconcile");
    Files.createDirectories(agentDir.resolve("scripts"));
    Files.writeString(agentDir.resolve("AGENT.md"), AGENT_MARKDOWN, StandardCharsets.UTF_8);
    Files.writeString(
        agentDir.resolve("REFERENCE.md"), "# 参考资料\n\n" + REFERENCE_MARKER, StandardCharsets.UTF_8);
    Files.writeString(
        agentDir.resolve("scripts").resolve("reconcile.py"), SCRIPT_PY, StandardCharsets.UTF_8);
  }

  @Test
  @DisplayName("ProgressiveDisclosureTest：正文进 prompt，REFERENCE 与脚本代码不预载")
  void load_includesAgentBodyButNotReferenceOrScript() {
    String prompt = new ContextLoader(oryxosDir).load(profile());

    assertThat(prompt).contains(BODY_MARKER);
    // frontmatter 只是配置，不进 prompt
    assertThat(prompt).doesNotContain("identity:").doesNotContain("deepseek-chat");
    assertThat(prompt).doesNotContain(REFERENCE_MARKER);
    assertThat(prompt).doesNotContain(SCRIPT_MARKER);
    assertThat(prompt).doesNotContain("import json");
  }

  @Test
  @DisplayName("ProgressiveDisclosureTest：Skill 只注入 name / description / 绝对路径，正文不预载")
  void load_injectsSkillSummaryOnly() throws IOException {
    Path skillDir = oryxosDir.resolve("skills").resolve("report-format");
    Files.createDirectories(skillDir);
    Files.writeString(skillDir.resolve("SKILL.md"), SKILL_MARKDOWN, StandardCharsets.UTF_8);
    Path linkDir = agentDir.resolve("skills");
    Files.createDirectories(linkDir);
    Path link = linkDir.resolve("report-format");
    boolean linked;
    try {
      Files.createSymbolicLink(link, Path.of("../../../skills/report-format"));
      linked = true;
    } catch (UnsupportedOperationException | IOException e) {
      linked = false;
    }
    assumeTrue(linked, "文件系统不支持创建软连接，跳过 Skill 披露判据");

    String prompt = new ContextLoader(oryxosDir).load(profile());

    assertThat(prompt).contains("## 可用 Skill");
    assertThat(prompt).contains("report-format");
    assertThat(prompt).contains(SKILL_DESCRIPTION);
    // 绝对路径：模型要靠它去 read_file
    assertThat(prompt).contains(skillDir.toRealPath().toString());
    assertThat(prompt).doesNotContain(SKILL_BODY_MARKER);
    // 正文里的分级规则一个字都不该进来
    assertThat(prompt).doesNotContain("P0/P1/P2 分级规则");
  }

  @Test
  @DisplayName("ProgressiveDisclosureTest：没有 Skill 绑定时不注入 Skill 段")
  void load_withoutSkillBindingsHasNoSkillSection() {
    String prompt = new ContextLoader(oryxosDir).load(profile());

    assertThat(prompt).doesNotContain("## 可用 Skill");
  }

  private static Profile profile() {
    return new Profile(
        "daily-reconcile",
        "每日对账",
        new Profile.Identity("对账小欧", "只根据脚本给出的确定性数据下结论。"),
        new Profile.ProviderSpec("deepseek", "deepseek-chat", 0.2),
        List.of("shell", "read_file"),
        List.of(),
        List.of(),
        List.of(new Profile.ScheduleSpec("0 9 * * *", "Asia/Shanghai", "到点了，对账。")),
        List.of(),
        Profile.Settings.DEFAULT);
  }
}
