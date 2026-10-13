package io.oryxlabs.oryxos.core.profile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * 扫描 Agent 目录，把每个 {@code AGENT.md} 的 frontmatter 派生成 {@link Profile}（技术方案 §8.2）。
 *
 * <p><b>业务方不写 Profile 文件</b>：Profile 是底座内部的运行时宿主配置，由本类从 Agent 目录派生，业务方只维护 Agent 目录本身。
 *
 * <p><b>单条失败只跳过、不中断</b>（FR-002）：一个写坏的 Agent 目录不该让整个实例起不来。失败一律记 ERROR 日志， 消息里<b>指名道姓</b>写清是哪个
 * Agent、错在哪，否则运维面对一堆静默跳过的 Agent 无从下手。
 *
 * <p><b>本类不认识任何具体厂商</b>：厂商清单由构造参数 {@code knownProviders} 注入。这既守住了 {@code oryxos-core}
 * 不反向依赖能力模块的依赖方向（章程附加约束），也让本类不必知道"厂商"是怎么装配出来的。
 */
public class ProfileLoader {

  private static final Logger log = LoggerFactory.getLogger(ProfileLoader.class);

  /** Agent 目录内固定文件名。 */
  private static final String AGENT_FILE = "AGENT.md";

  /** frontmatter 分隔符：开头与结尾各一行，单独成行。 */
  private static final String FRONTMATTER_DELIMITER = "---";

  /** frontmatter 里的环境变量占位形如 {@code ${VAR_NAME}}。 */
  private static final Pattern ENV_PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

  /**
   * frontmatter 中显式禁止出现的键（data-model.md §1）。
   *
   * <p>{@code skills}：Skill 绑定的唯一真相源是 Agent 目录内指向公共实体的相对软连接（章程原则 IV），写进 frontmatter 会造出第二份真相；{@code
   * notify_channels}：通知渠道归 SQLite 全局注册表，不属于 Agent 配置。
   */
  private static final Set<String> FORBIDDEN_KEYS = Set.of("skills", "notify_channels");

  private final Path agentsDir;
  private final Set<String> knownProviders;
  private final UnaryOperator<String> envLookup;

  /**
   * 生产用法：环境变量占位从进程环境读取。
   *
   * @param agentsDir Agent 目录（即 {@code .oryxos/agents}）
   * @param knownProviders 全局层已声明的厂商名
   */
  public ProfileLoader(Path agentsDir, Set<String> knownProviders) {
    this(agentsDir, knownProviders, System::getenv);
  }

  /**
   * @param agentsDir Agent 目录（即 {@code .oryxos/agents}）
   * @param knownProviders 全局层已声明的厂商名
   * @param envLookup 环境变量查询函数；返回 {@code null} 表示未设置
   */
  public ProfileLoader(
      Path agentsDir, Set<String> knownProviders, UnaryOperator<String> envLookup) {
    this.agentsDir = agentsDir;
    this.knownProviders = Set.copyOf(knownProviders);
    this.envLookup = envLookup;
  }

  /**
   * 装载全部 Agent 的 Profile。
   *
   * @return 装载成功的 Profile，按目录名升序；坏目录不出现在结果里，但一定在 ERROR 日志里
   */
  public List<Profile> loadAll() {
    if (!Files.isDirectory(agentsDir)) {
      log.atWarn().addKeyValue("agentsDir", agentsDir).log("Agent 目录不存在，装载结果为空：" + agentsDir);
      return List.of();
    }
    List<Path> agentDirs;
    try (Stream<Path> entries = Files.list(agentsDir)) {
      agentDirs = entries.filter(Files::isDirectory).sorted().toList();
    } catch (IOException e) {
      throw new UncheckedIOException("无法列出 Agent 目录: " + agentsDir, e);
    }
    List<Profile> loaded = new ArrayList<>();
    for (Path agentDir : agentDirs) {
      // 用 relativize 而非 getFileName：后者签名上可空（根路径无文件名），此处取的必然是直接子项，相对化结果就是目录名
      String agentName = agentsDir.relativize(agentDir).toString();
      try {
        loaded.add(loadOne(agentDir, agentName));
      } catch (ProfileLoadException e) {
        log.atError()
            .addKeyValue("agent", agentName)
            .addKeyValue("agentDir", agentDir)
            .setCause(e.getCause())
            .log("Agent '" + agentName + "' 装载失败，已跳过: " + e.getMessage());
      }
    }
    return List.copyOf(loaded);
  }

  private Profile loadOne(Path agentDir, String agentName) {
    Path agentFile = agentDir.resolve(AGENT_FILE);
    if (!Files.isRegularFile(agentFile)) {
      throw new ProfileLoadException("缺少 " + AGENT_FILE);
    }
    String raw;
    try {
      raw = Files.readString(agentFile, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new ProfileLoadException("读取 " + AGENT_FILE + " 失败: " + e.getMessage(), e);
    }
    return toProfile(agentName, parseFrontmatter(raw));
  }

  private Map<String, Object> parseFrontmatter(String raw) {
    String text = raw.startsWith("\uFEFF") ? raw.substring(1) : raw;
    String[] lines = text.split("\\R", -1);
    if (lines.length == 0 || !FRONTMATTER_DELIMITER.equals(lines[0].trim())) {
      throw new ProfileLoadException("frontmatter 缺失：文件未以 " + FRONTMATTER_DELIMITER + " 开头");
    }
    int closing = -1;
    for (int i = 1; i < lines.length; i++) {
      if (FRONTMATTER_DELIMITER.equals(lines[i].trim())) {
        closing = i;
        break;
      }
    }
    if (closing < 0) {
      throw new ProfileLoadException("frontmatter 未闭合：找不到收尾的 ---");
    }
    String yamlText = String.join("\n", Arrays.copyOfRange(lines, 1, closing));
    Object parsed;
    try {
      // SafeConstructor：frontmatter 是业务方提供的文本，默认构造器允许 !! 标签实例化任意类
      parsed = new Yaml(new SafeConstructor(new LoaderOptions())).load(yamlText);
    } catch (RuntimeException e) {
      throw new ProfileLoadException("frontmatter 不是合法 YAML: " + e.getMessage(), e);
    }
    if (parsed == null) {
      return Map.of();
    }
    if (!(parsed instanceof Map<?, ?> map)) {
      throw new ProfileLoadException("frontmatter 顶层必须是键值映射");
    }
    Map<String, Object> result = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      result.put(String.valueOf(entry.getKey()), entry.getValue());
    }
    return result;
  }

  private Profile toProfile(String agentName, Map<String, Object> frontmatter) {
    for (String forbidden : FORBIDDEN_KEYS) {
      if (frontmatter.containsKey(forbidden)) {
        throw new ProfileLoadException(
            "frontmatter 不允许声明 '" + forbidden + "'：Skill 归属由软连接表达、通知渠道归全局注册表");
      }
    }
    return new Profile(
        agentName,
        stringValue(frontmatter, "description"),
        identity(frontmatter.get("identity")),
        provider(frontmatter.get("provider")),
        stringList(frontmatter.get("tools"), "tools"),
        stringList(frontmatter.get("mcp_servers"), "mcp_servers"),
        channels(frontmatter.get("channels")),
        schedules(frontmatter.get("schedules")),
        stringList(frontmatter.get("bootstrap"), "bootstrap"),
        settings(frontmatter.get("settings")));
  }

  private Profile.Identity identity(Object raw) {
    Map<String, Object> map = asMap(raw, "identity");
    if (map == null) {
      return Profile.Identity.EMPTY;
    }
    return new Profile.Identity(stringValue(map, "agent_name"), stringValue(map, "prompt"));
  }

  private Profile.ProviderSpec provider(Object raw) {
    Map<String, Object> map = asMap(raw, "provider");
    if (map == null) {
      throw new ProfileLoadException("缺少 provider 段：每个 Agent 必须声明用哪家厂商、哪个模型");
    }
    String name = stringValue(map, "name");
    if (name == null || name.isBlank()) {
      throw new ProfileLoadException("provider.name 缺失");
    }
    if (!knownProviders.contains(name)) {
      throw new ProfileLoadException(
          "provider.name '" + name + "' 不在全局厂商清单中（已声明: " + knownProviders + "）");
    }
    String model = stringValue(map, "model");
    if (model == null || model.isBlank()) {
      throw new ProfileLoadException("provider.model 缺失");
    }
    return new Profile.ProviderSpec(
        name, model, doubleValue(map.get("temperature"), "temperature"));
  }

  private List<Profile.ChannelSpec> channels(Object raw) {
    List<Object> items = asList(raw, "channels");
    List<Profile.ChannelSpec> result = new ArrayList<>();
    for (Object item : items) {
      Map<String, Object> map = asMap(item, "channels[]");
      if (map == null) {
        throw new ProfileLoadException("channels 的每一项都必须是键值映射");
      }
      result.add(new Profile.ChannelSpec(stringValue(map, "name"), map));
    }
    return List.copyOf(result);
  }

  private List<Profile.ScheduleSpec> schedules(Object raw) {
    List<Object> items = asList(raw, "schedules");
    List<Profile.ScheduleSpec> result = new ArrayList<>();
    for (Object item : items) {
      Map<String, Object> map = asMap(item, "schedules[]");
      if (map == null) {
        throw new ProfileLoadException("schedules 的每一项都必须是键值映射");
      }
      result.add(
          new Profile.ScheduleSpec(
              stringValue(map, "cron"), stringValue(map, "zone"), stringValue(map, "message")));
    }
    return List.copyOf(result);
  }

  private Profile.Settings settings(Object raw) {
    Map<String, Object> map = asMap(raw, "settings");
    if (map == null) {
      return Profile.Settings.DEFAULT;
    }
    return new Profile.Settings(
        intValue(map.get("max_iterations"), "settings.max_iterations"),
        intValue(map.get("max_history_turns"), "settings.max_history_turns"));
  }

  private String stringValue(Map<String, Object> map, String key) {
    Object raw = map.get(key);
    if (raw == null) {
      return null;
    }
    if (!(raw instanceof String text)) {
      throw new ProfileLoadException(key + " 必须是字符串，实际是 " + raw.getClass().getSimpleName());
    }
    return resolveEnv(text);
  }

  /** 把 {@code ${VAR}} 换成环境变量值；缺失即报错并指明变量名——不回显任何凭证值（FR-010）。 */
  private String resolveEnv(String value) {
    Matcher matcher = ENV_PLACEHOLDER.matcher(value);
    StringBuilder resolved = new StringBuilder();
    while (matcher.find()) {
      String varName = matcher.group(1);
      String replacement = envLookup.apply(varName);
      if (replacement == null) {
        throw new ProfileLoadException(
            "环境变量 " + varName + " 未设置，占位 " + matcher.group() + " 无法解析（报错不回显凭证值）");
      }
      matcher.appendReplacement(resolved, Matcher.quoteReplacement(replacement));
    }
    matcher.appendTail(resolved);
    return resolved.toString();
  }

  private List<String> stringList(Object raw, String key) {
    List<Object> items = asList(raw, key);
    List<String> result = new ArrayList<>();
    for (Object item : items) {
      if (!(item instanceof String text)) {
        throw new ProfileLoadException(key + " 的每一项都必须是字符串");
      }
      result.add(resolveEnv(text));
    }
    return List.copyOf(result);
  }

  private Double doubleValue(Object raw, String key) {
    if (raw == null) {
      return null;
    }
    if (!(raw instanceof Number number)) {
      throw new ProfileLoadException(key + " 必须是数字");
    }
    return number.doubleValue();
  }

  private Integer intValue(Object raw, String key) {
    if (raw == null) {
      return null;
    }
    if (!(raw instanceof Number number)) {
      throw new ProfileLoadException(key + " 必须是整数");
    }
    return number.intValue();
  }

  private List<Object> asList(Object raw, String key) {
    if (raw == null) {
      return List.of();
    }
    if (!(raw instanceof List<?> list)) {
      throw new ProfileLoadException(key + " 必须是列表");
    }
    return List.copyOf(list);
  }

  private Map<String, Object> asMap(Object raw, String key) {
    if (raw == null) {
      return null;
    }
    if (!(raw instanceof Map<?, ?> map)) {
      throw new ProfileLoadException(key + " 必须是键值映射");
    }
    Map<String, Object> result = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      result.put(String.valueOf(entry.getKey()), entry.getValue());
    }
    return result;
  }

  /** 装载单条失败的内部信号，只在本类内流转，不外抛。 */
  private static final class ProfileLoadException extends RuntimeException {

    ProfileLoadException(String message) {
      super(message);
    }

    ProfileLoadException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
