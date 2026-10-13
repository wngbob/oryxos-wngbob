package io.oryxlabs.oryxos.core.context;

import io.oryxlabs.oryxos.core.profile.Profile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * 组装 system prompt 里<b>来自文件的那部分</b>：Bootstrap 三件套 + Profile 显式引用的文件 + Agent 正文 + Skill 绑定摘要 （技术方案
 * §8.3）。
 *
 * <p>产出交给 {@code PromptBuilder}——它会在其前放 {@code identity.prompt}、在其后追加当前日期时间行，拼成完整的 system prompt。
 *
 * <p><b>两条铁律</b>（FR-011 / FR-012）：
 *
 * <ol>
 *   <li><b>不缓存</b>：每次 {@link #load(Profile)} 都重新读文件、重新扫 {@code skills/} 目录，不持有任何跨调用的缓存。改完文件
 *       <b>下一轮</b>即生效（SC-008，生效延迟 = 0 轮）。
 *   <li><b>不静默跳过</b>：Profile 显式引用的文件缺失必须报错并<b>指名</b>是哪个文件；默认 Bootstrap 缺失至少 WARN。 「人格悄悄丢了」是最难查的软故障。
 * </ol>
 *
 * <p><b>为什么「显式引用报错、隐式默认 WARN」</b>：显式引用是业务方的明确意图，静默跳过等于业务方以为配上了、其实没生效；三件套是底座给的
 * 默认，业务方没配过，缺了就缺了——但必须留下 WARN。
 *
 * <p><b>路径口径与 {@code ProfileLoader} 一致</b>：注入具体目录（{@code ProfileLoader} 收 {@code
 * .oryxos/agents}，本类收 {@code .oryxos/}），不注入仓库根。两个类对「工作区长什么样」的理解必须一致，否则第 18 节 {@code oryxos init}
 * 建出的目录结构会对不上。
 *
 * <p><b>异常类型不新增</b>（章程原则 II）：IO 失败用 {@link UncheckedIOException}，内容缺失 / 越界用 {@link
 * IllegalStateException}。
 */
public final class ContextLoader {

  private static final Logger log = LoggerFactory.getLogger(ContextLoader.class);

  /** 底座给的默认 Bootstrap 三件套，业务方没配过，缺失只 WARN。 */
  private static final List<String> DEFAULT_BOOTSTRAP = List.of("AGENTS.md", "SOUL.md", "USER.md");

  private static final String AGENTS_DIR = "agents";

  /** Skill 目录名：公共实体库 {@code .oryxos/skills/} 与 Agent 内的软连接目录同名。 */
  private static final String SKILLS_DIR = "skills";

  private static final String AGENT_FILE = "AGENT.md";
  private static final String SKILL_FILE = "SKILL.md";
  private static final String FRONTMATTER_DELIMITER = "---";

  /** UTF-8 BOM：文件开头可能带，不剥掉会把 frontmatter 首行判成非分隔符。 */
  private static final String BOM = "\uFEFF";

  /** 工作区的 {@code .oryxos/} 目录。 */
  private final Path oryxosDir;

  /**
   * @param oryxosDir 工作区的 {@code .oryxos/} 目录
   */
  public ContextLoader(Path oryxosDir) {
    this.oryxosDir = oryxosDir;
  }

  /**
   * 现读现拼一次，产出 system prompt 的文件部分。
   *
   * @param profile 当前 Agent 的运行时配置（决定读哪个 Agent 目录、显式引用了哪些文件）
   * @return 文件部分的文本；无任何文件时返回空串
   * @throws IllegalStateException Profile 显式引用的文件缺失、Agent 正文缺失、或 Skill 绑定悬空 / 越界
   */
  public String load(Profile profile) {
    StringBuilder text = new StringBuilder();
    appendDefaultBootstrap(text);
    appendExplicitBootstrap(text, profile);
    appendAgentBody(text, profile);
    appendSkillBindings(text, profile);
    return text.toString();
  }

  /** 三件套：缺了只 WARN，不阻断——但必须留下痕迹，否则人格丢了没人知道。 */
  private void appendDefaultBootstrap(StringBuilder text) {
    for (String name : DEFAULT_BOOTSTRAP) {
      Path file = oryxosDir.resolve(name);
      if (Files.isRegularFile(file)) {
        appendText(text, readFile(file));
      } else {
        log.atWarn()
            .addKeyValue("file", file.toString())
            .log("默认 Bootstrap 文件缺失，已跳过：" + name + "（人格/偏好可能没生效）");
      }
    }
  }

  /** Profile 显式引用：业务方的明确意图，缺了必须报错并指名文件。 */
  private void appendExplicitBootstrap(StringBuilder text, Profile profile) {
    for (String declared : profile.bootstrap()) {
      Path file = resolveBootstrap(declared);
      if (!Files.isRegularFile(file)) {
        throw new IllegalStateException(
            "Profile 显式引用的 Bootstrap 文件缺失: " + declared + "（解析为 " + file + "）");
      }
      appendText(text, readFile(file));
    }
  }

  /** 绝对路径原样使用，相对路径按 {@code <oryxosDir>/<declared>} 解析。 */
  private Path resolveBootstrap(String declared) {
    Path path = Path.of(declared);
    return path.isAbsolute() ? path : oryxosDir.resolve(declared);
  }

  /** Agent 正文（frontmatter 之后的部分）。Agent 的自身定义文件缺失即报错——这个 Agent 根本没有定义。 */
  private void appendAgentBody(StringBuilder text, Profile profile) {
    Path agentFile = oryxosDir.resolve(AGENTS_DIR).resolve(profile.name()).resolve(AGENT_FILE);
    if (!Files.isRegularFile(agentFile)) {
      throw new IllegalStateException("Agent 定义文件缺失: " + agentFile);
    }
    appendText(text, stripFrontmatter(readFile(agentFile)));
  }

  /**
   * Skill 绑定摘要：Agent 目录内指向公共 Skill 实体的相对软连接。
   *
   * <p>只注入 {@code name} / {@code description} / 本地绝对读取路径，<b>正文与附属资源不预载</b>——由模型经 {@code read_file}
   * / {@code shell} 按需取（渐进式披露）。Skill <b>不是 Tool</b>，MUST NOT 注册进 {@code ToolCatalog}（章程原则 IV）。
   */
  private void appendSkillBindings(StringBuilder text, Profile profile) {
    Path linkDir = oryxosDir.resolve(AGENTS_DIR).resolve(profile.name()).resolve(SKILLS_DIR);
    if (!Files.isDirectory(linkDir)) {
      return;
    }
    List<Path> entries;
    try (Stream<Path> stream = Files.list(linkDir)) {
      entries = stream.sorted().toList();
    } catch (IOException e) {
      throw new UncheckedIOException("无法列出 Skill 绑定目录: " + linkDir, e);
    }
    Map<String, Path> targets = resolveSkillTargets(entries);
    if (targets.isEmpty()) {
      return;
    }
    Path publicRoot = publicSkillsRoot();
    StringBuilder section = new StringBuilder();
    for (Map.Entry<String, Path> binding : targets.entrySet()) {
      Path target = binding.getValue();
      if (!target.startsWith(publicRoot)) {
        throw new IllegalStateException(
            "Skill 绑定越出公共 Skill 目录: " + binding.getKey() + " → " + target);
      }
      section
          .append("- ")
          .append(skillName(binding.getKey(), target))
          .append("（路径：")
          .append(target)
          .append("）：")
          .append(skillDescription(binding.getKey(), target))
          .append(System.lineSeparator());
    }
    text.append(System.lineSeparator())
        .append("## 可用 Skill")
        .append(System.lineSeparator())
        .append(section);
  }

  /**
   * 逐个解析软连接的真实目标。
   *
   * <p>解析失败即「悬空」——典型成因是手工复制 Agent 目录后忘了带公共 Skill 实体。此时<b>必须报错</b>：静默跳过会让模型以为 Skill 可用，实际读不到任何东西。
   */
  private Map<String, Path> resolveSkillTargets(List<Path> entries) {
    Map<String, Path> targets = new LinkedHashMap<>();
    for (Path entry : entries) {
      Path fileName = entry.getFileName();
      if (fileName == null) {
        throw new IllegalStateException("Skill 绑定路径没有文件名，无法识别是哪个 Skill: " + entry);
      }
      String linkName = fileName.toString();
      try {
        targets.put(linkName, entry.toRealPath());
      } catch (IOException e) {
        throw new IllegalStateException(
            "Skill 绑定悬空：软连接 " + linkName + " 指向的目标不存在（" + entry + "）", e);
      }
    }
    return targets;
  }

  /** 公共 Skill 根：解析软连接目标是否越界的基准。 */
  private Path publicSkillsRoot() {
    Path root = oryxosDir.resolve(SKILLS_DIR);
    if (!Files.isDirectory(root)) {
      throw new IllegalStateException("公共 Skill 目录不存在，Skill 绑定无法成立: " + root);
    }
    try {
      return root.toRealPath();
    } catch (IOException e) {
      throw new UncheckedIOException("无法解析公共 Skill 目录: " + root, e);
    }
  }

  /** Skill 名优先取 SKILL.md frontmatter 的 {@code name}，读不到时退化为软连接名。 */
  private String skillName(String linkName, Path target) {
    String declared = skillFrontmatter(linkName, target).get("name");
    return declared == null || declared.isBlank() ? linkName : declared;
  }

  /** Skill 描述来自 SKILL.md frontmatter；读不到时留空——这是渐进式披露的说明字段，缺了不影响可用性。 */
  private String skillDescription(String linkName, Path target) {
    String description = skillFrontmatter(linkName, target).get("description");
    return description == null ? "" : description;
  }

  /**
   * 读 SKILL.md frontmatter 的 {@code name} / {@code description}。
   *
   * <p>SKILL.md 缺失只 WARN 不报错：Skill 实体的可用性由软连接决定，说明字段读不到不影响模型按需读取正文。
   */
  private Map<String, String> skillFrontmatter(String linkName, Path target) {
    Path skillFile = target.resolve(SKILL_FILE);
    if (!Files.isRegularFile(skillFile)) {
      log.atWarn()
          .addKeyValue("skill", linkName)
          .addKeyValue("file", skillFile.toString())
          .log("Skill 缺少 " + SKILL_FILE + "，仅注入名称与路径：" + linkName);
      return Map.of();
    }
    Map<String, String> result = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : parseFrontmatter(readFile(skillFile)).entrySet()) {
      if (entry.getValue() != null) {
        result.put(entry.getKey(), String.valueOf(entry.getValue()));
      }
    }
    return result;
  }

  /** 去掉 {@code ---} 包起来的 frontmatter，只留正文。 */
  private String stripFrontmatter(String raw) {
    String[] lines = stripBom(raw).split("\\R", -1);
    if (lines.length == 0 || !FRONTMATTER_DELIMITER.equals(lines[0].trim())) {
      return raw;
    }
    for (int i = 1; i < lines.length; i++) {
      if (FRONTMATTER_DELIMITER.equals(lines[i].trim())) {
        return String.join(System.lineSeparator(), Arrays.copyOfRange(lines, i + 1, lines.length));
      }
    }
    return raw;
  }

  /**
   * 解析 frontmatter 为键值映射。
   *
   * <p>用 {@code SafeConstructor}：frontmatter 是业务方提供的文本，默认构造器允许 {@code !!} 标签实例化任意类。
   */
  private Map<String, Object> parseFrontmatter(String raw) {
    String[] lines = stripBom(raw).split("\\R", -1);
    if (lines.length == 0 || !FRONTMATTER_DELIMITER.equals(lines[0].trim())) {
      return Map.of();
    }
    int closing = -1;
    for (int i = 1; i < lines.length; i++) {
      if (FRONTMATTER_DELIMITER.equals(lines[i].trim())) {
        closing = i;
        break;
      }
    }
    if (closing < 0) {
      return Map.of();
    }
    Object parsed;
    try {
      parsed =
          new Yaml(new SafeConstructor(new LoaderOptions()))
              .load(String.join("\n", Arrays.copyOfRange(lines, 1, closing)));
    } catch (RuntimeException e) {
      throw new IllegalStateException("frontmatter 不是合法 YAML: " + e.getMessage(), e);
    }
    if (!(parsed instanceof Map<?, ?> map)) {
      return Map.of();
    }
    Map<String, Object> result = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      result.put(String.valueOf(entry.getKey()), entry.getValue());
    }
    return result;
  }

  private static String stripBom(String raw) {
    return raw.startsWith(BOM) ? raw.substring(1) : raw;
  }

  private String readFile(Path file) {
    try {
      return Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("读取文件失败: " + file, e);
    }
  }

  /** 拼接时按段落分隔，避免两个文件的内容首尾粘在一行上。 */
  private static void appendText(StringBuilder text, String content) {
    if (content.isEmpty()) {
      return;
    }
    if (text.length() > 0) {
      text.append(System.lineSeparator());
    }
    text.append(content);
    if (!content.endsWith(System.lineSeparator())) {
      text.append(System.lineSeparator());
    }
  }
}
