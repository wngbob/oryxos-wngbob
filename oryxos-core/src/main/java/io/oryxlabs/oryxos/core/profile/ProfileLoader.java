package io.oryxlabs.oryxos.core.profile;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

  /** 目录内按需资源的子目录 / 文件名（第 29 节 `inspect` 的资源识别）。 */
  private static final String SCRIPTS_DIR = "scripts";

  private static final String SKILLS_DIR = "skills";

  private static final String REFERENCE_FILE = "REFERENCE.md";

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
    return loadAllDetailed().loaded();
  }

  /**
   * 装载全部 Agent 的 Profile，<b>并带回每条失败的原因</b>（第 18 节新增，已报批）。
   *
   * <p><b>为什么需要它</b>：{@link #loadAll()} 把每条失败吞成 ERROR 日志、只返回装载成功的那些——日志给运维看， 命令拿不到。而 {@code profile
   * list} / {@code profile show} 要把「无效」连同原因一起打给用户。
   *
   * <p><b>为什么不让 CLI 自己重写一套校验</b>：「什么叫有效」的真相源只有本类一处，重写即第二处真相。 本方法与 {@code loadAll()}
   * 共用同一段扫描逻辑，两者对「有效」的判定必然一致。
   *
   * @return 成功清单 + 失败明细（Agent 名 + 原因），两者都按目录名升序
   */
  public LoadResult loadAllDetailed() {
    if (!Files.isDirectory(agentsDir)) {
      log.atWarn().addKeyValue("agentsDir", agentsDir).log("Agent 目录不存在，装载结果为空：" + agentsDir);
      return new LoadResult(List.of(), List.of());
    }
    List<Path> agentDirs;
    try (Stream<Path> entries = Files.list(agentsDir)) {
      agentDirs = entries.filter(Files::isDirectory).sorted().toList();
    } catch (IOException e) {
      throw new UncheckedIOException("无法列出 Agent 目录: " + agentsDir, e);
    }
    List<Profile> loaded = new ArrayList<>();
    // 必须写全 LoadResult.Failure：嵌套类型的简单名不会进入外层类的方法作用域
    List<LoadResult.Failure> failures = new ArrayList<>();
    for (Path agentDir : agentDirs) {
      // 用 relativize 而非 getFileName：后者签名上可空（根路径无文件名），此处取的必然是直接子项，相对化结果就是目录名
      String agentName = agentsDir.relativize(agentDir).toString();
      try {
        loaded.add(loadOne(agentDir, agentName));
      } catch (ProfileLoadException e) {
        failures.add(new LoadResult.Failure(agentName, e.getMessage()));
        log.atError()
            .addKeyValue("agent", agentName)
            .addKeyValue("agentDir", agentDir)
            .setCause(e.getCause())
            .log("Agent '" + agentName + "' 装载失败，已跳过: " + e.getMessage());
      }
    }
    return new LoadResult(List.copyOf(loaded), List.copyOf(failures));
  }

  /**
   * 一次装载的完整结果：成功的 Profile 与失败的明细。
   *
   * <p>两个清单互斥且按目录名升序——「全部 Agent」= {@code loaded} 与 {@code failures} 的并集。
   *
   * @param loaded 装载成功的 Profile
   * @param failures 装载失败的明细；元素给出 Agent 名与原因
   */
  public record LoadResult(List<Profile> loaded, List<Failure> failures) {

    /** 归一化：可空集合收敛为不可变空表，使调用方不必判空。 */
    public LoadResult {
      loaded = loaded == null ? List.of() : List.copyOf(loaded);
      failures = failures == null ? List.of() : List.copyOf(failures);
    }

    /**
     * 一个 Agent 装载失败的原因。
     *
     * @param agentName Agent 名（目录名）
     * @param reason 失败原因，直接取自装载异常的 message——不加工、不重述
     */
    public record Failure(String agentName, String reason) {}
  }

  /**
   * 运行时派生单个 Agent 目录的 Profile（第 29 节，FR-004 / 契约 runtime-registration §1.1）。
   *
   * <p><b>与启动扫描走同一段校验</b>：最终都落到 {@link #toProfile}，因此失败消息与 {@code loadAllDetailed()} 里的 {@code
   * Failure.reason} <b>逐字相同</b>——这是「同一异常类型 + 同一消息」可机械判定的结构前提。
   *
   * @param agentDir Agent 目录（{@code .../agents/<name>}）
   * @throws ProfileLoadException 缺 {@code AGENT.md}、frontmatter 非法、必填项缺失或厂商未知
   */
  public Profile deriveProfile(Path agentDir) {
    Path dir = Objects.requireNonNull(agentDir, "agentDir");
    return toProfile(agentNameOf(dir), readAgentMarkdown(dir).frontmatter());
  }

  /**
   * 读取一个 Agent 目录的结构快照：frontmatter / 正文 / 资源清单（第 29 节，契约 runtime-registration §1.2）。
   *
   * <p><b>只做结构解析</b>：不校验 {@code provider} 等语义（那是 {@link #deriveProfile} 的事），也不读取资源内容—— 渐进式披露要求
   * {@code REFERENCE.md} / 脚本 / Skill 正文不被预载。
   *
   * @param agentDir Agent 目录（{@code .../agents/<name>}）
   * @throws ProfileLoadException 缺 {@code AGENT.md}、IO 错或 YAML 非法
   */
  public AgentDir inspect(Path agentDir) {
    Path dir = Objects.requireNonNull(agentDir, "agentDir");
    AgentMarkdown markdown = readAgentMarkdown(dir);
    return new AgentDir(
        agentNameOf(dir), dir, markdown.frontmatter(), markdown.body(), resources(dir));
  }

  /**
   * 一个 Agent 目录的结构快照（{@link #inspect} 的产物）。
   *
   * <p>{@code name} 取自目录名——目录名是 Agent 名的唯一真相源，frontmatter 里的 {@code name} 被忽略。
   *
   * @param name Agent 名（目录名）
   * @param path Agent 目录本身
   * @param frontmatter 已按字符串键归一化的 frontmatter；空 frontmatter 为 {@code Map.of()}
   * @param body 收尾 {@code ---} 之后的正文（去首尾空白）；空正文为 {@code ""}
   * @param resources 相对路径、字典序、不递归的资源清单；只反映存在性
   */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification =
          "紧凑构造器已用 Collections.unmodifiableMap 包住副本，调用方改不动内容；"
              + "SpotBugs 只看声明的 Map 类型（可变接口），看不到这层包装")
  public record AgentDir(
      String name,
      Path path,
      Map<String, Object> frontmatter,
      String body,
      List<String> resources) {

    /** 归一化：可空集合收敛为不可变副本，调用方改动不影响快照。 */
    public AgentDir {
      frontmatter =
          frontmatter == null
              ? Map.of()
              : Collections.unmodifiableMap(new LinkedHashMap<>(frontmatter));
      body = body == null ? "" : body;
      resources = resources == null ? List.of() : List.copyOf(resources);
    }
  }

  private Profile loadOne(Path agentDir, String agentName) {
    return toProfile(agentName, readAgentMarkdown(agentDir).frontmatter());
  }

  /** Agent 名 = 目录名；根路径没有文件名，给出能看懂为什么失败的错。 */
  private static String agentNameOf(Path agentDir) {
    Path fileName = agentDir.getFileName();
    if (fileName == null) {
      throw new ProfileLoadException("无法从路径取得 Agent 名（传入的是文件系统根目录？）: " + agentDir);
    }
    return fileName.toString();
  }

  /** 读 {@code AGENT.md} 并拆成 frontmatter + 正文——启动扫描与运行时派生共用这一段。 */
  private static AgentMarkdown readAgentMarkdown(Path agentDir) {
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
    return parseAgentMarkdown(raw);
  }

  /** AGENT.md 的结构解析结果：frontmatter 与正文。{@code inspect} / {@code deriveProfile} / 启动扫描共用一段解析。 */
  private record AgentMarkdown(Map<String, Object> frontmatter, String body) {}

  private static AgentMarkdown parseAgentMarkdown(String raw) {
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
    Map<String, Object> frontmatter;
    if (parsed == null) {
      frontmatter = Map.of();
    } else if (!(parsed instanceof Map<?, ?> map)) {
      throw new ProfileLoadException("frontmatter 顶层必须是键值映射");
    } else {
      frontmatter = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        frontmatter.put(String.valueOf(entry.getKey()), entry.getValue());
      }
    }
    // 正文统一用 \n 重建（CRLF 文件不把 \r 带进 system prompt），并去掉首尾空白
    String body = String.join("\n", Arrays.copyOfRange(lines, closing + 1, lines.length)).strip();
    return new AgentMarkdown(frontmatter, body);
  }

  /**
   * 资源清单：{@code scripts/} 的直接子文件 + {@code skills/} 的直接子项 + {@code REFERENCE.md}。
   *
   * <p>只 {@code list} 一层、<b>不递归、不读内容</b>；结果按字典序排序，让输出稳定可断言。
   */
  private static List<String> resources(Path agentDir) {
    List<String> resources = new ArrayList<>();
    addEntries(agentDir.resolve(SCRIPTS_DIR), true, resources);
    addEntries(agentDir.resolve(SKILLS_DIR), false, resources);
    if (Files.isRegularFile(agentDir.resolve(REFERENCE_FILE))) {
      resources.add(REFERENCE_FILE);
    }
    resources.sort(Comparator.naturalOrder());
    return List.copyOf(resources);
  }

  /** 列一层目录；{@code filesOnly} 为真时只收普通文件（链接指向普通文件也算）。目录不存在即视为没有资源。 */
  private static void addEntries(Path dir, boolean filesOnly, List<String> into) {
    if (!Files.isDirectory(dir)) {
      return;
    }
    try (Stream<Path> entries = Files.list(dir)) {
      entries
          .filter(entry -> !filesOnly || Files.isRegularFile(entry))
          .map(entry -> dir.getFileName() + "/" + entry.getFileName())
          .forEach(into::add);
    } catch (IOException e) {
      throw new ProfileLoadException("无法列出目录: " + dir, e);
    }
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

  /**
   * 装载单条失败的信号（第 29 节由 private 提为 public，<b>仅可见性变更</b>）。
   *
   * <p><b>为什么必须公开</b>：FR-004 / SC-003 要求「启动扫描」与「运行时派生」的失败可机械判定为同一异常类型；第 30 节的运行时 API 也要按类型捕获并映射成
   * HTTP 4xx。
   *
   * <p><b>构造器仍是包级私有</b>：外部只捕获、不构造——异常只能由本类的校验路径产生，不让调用方伪造判定依据。
   *
   * <p>{@code final}：构造器会抛异常的类可被继承的话，子类 finalizer 攻击有缝（FindSecBugs {@code CT_CONSTRUCTOR_THROW}）。
   */
  public static final class ProfileLoadException extends RuntimeException {

    ProfileLoadException(String message) {
      super(message);
    }

    ProfileLoadException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
