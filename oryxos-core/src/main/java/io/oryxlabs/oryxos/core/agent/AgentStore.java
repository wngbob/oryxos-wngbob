package io.oryxlabs.oryxos.core.agent;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * 工作区磁盘的读写收口：Agent 目录的建 / 改 / 归档，以及管理台文件浏览器要的目录树与单文件读写（第 30 节）。
 *
 * <p><b>磁盘是唯一真相源</b>：本类只做文件系统动作，不碰 {@code ProfileRegistry} / {@code AgentScheduler}——「写完之后让谁生效」是
 * {@link AgentLifecycleService} 的编排。两层分开的直接好处：文件浏览器的「读一个文件」不需要惊动索引，而「写 {@code AGENT.md}」必须走编排那段，
 * 由调用方（控制器）按路径判断走哪条。
 *
 * <p><b>构造参数是工作区根、不是基准目录</b>：{@code workspace.root()} 已经是 {@code <基准>/.oryxos}，本类 MUST NOT 再叠一层
 * {@code .oryxos}。也正因如此本类<b>不依赖 {@code oryxos-cli} 的 {@code Workspace}</b>——依赖方向是 cli → core，反过来会让
 * core 反向依赖命令层。
 *
 * <p><b>路径守卫只有一处</b>（{@link #resolveSafe}）：读、写、写多文件、tree 的子路径判定全走它。分散写守卫的结果是「某一条入口忘了 normalize」，
 * 而那一条就足以读到 {@code /etc/passwd}。
 */
public class AgentStore {

  /** Agent 子目录名。 */
  private static final String AGENTS_DIR = "agents";

  /** 归档子目录名：删除 Agent 时整目录移进来，不物理删。 */
  private static final String ARCHIVE_DIR = "archive";

  /** Agent 目录内固定文件名。 */
  private static final String AGENT_FILE = "AGENT.md";

  /** 模板脚手架同时写出的参考资料占位文件。 */
  private static final String REFERENCE_FILE = "REFERENCE.md";

  /**
   * NUL 字符：{@code Path.of} 遇到它会抛 {@code InvalidPathException}，而那句话对调用方没信息量。
   *
   * <p>单独判一次是为了给出「含 NUL 字符」这种能直接看懂的原因——路径来自 HTTP 请求体与 URL，这类输入里出现 NUL 是攻击特征而不是笔误。
   */
  private static final char NUL_CHAR = '\0';

  /** 模板脚手架同时建出的空子目录：脚本与 Skill 软连接的落点。 */
  private static final String SCRIPTS_DIR = "scripts";

  private static final String SKILLS_DIR = "skills";

  /** 模板脚手架里 AGENT.md 的正文前缀（描述接在后面）。 */
  private static final String SCAFFOLD_BODY_HEAD =
      """

      ## 任务

      """;

  /** 模板脚手架里 AGENT.md 的正文尾注：告诉接手的人这个目录里还能放什么。 */
  private static final String SCAFFOLD_BODY_TAIL =
      """

      <!-- 上面这段来自创建时填的 description。把这个 Agent「收到消息后做什么」写成分步骤的指令； -->
      <!-- 需要脚本时放进 scripts/，需要参考资料时写进 REFERENCE.md，需要复用公共 Skill 时在 skills/ 下建相对软连接。 -->
      """;

  /** 模板脚手架里 REFERENCE.md 的占位内容。 */
  private static final String SCAFFOLD_REFERENCE =
      """
      # 参考资料

      <!-- 这个文件不会被预载进 prompt：Agent 需要时用 read_file 自己读。把领域资料、口径、示例放在这里。 -->
      """;

  /** 工作区根（{@code <基准>/.oryxos}），绝对路径且已归一化。 */
  private final Path root;

  /**
   * @param oryxosRoot 工作区根（{@code <基准>/.oryxos}）；<b>MUST NOT 再 resolve {@code .oryxos}</b>
   */
  public AgentStore(Path oryxosRoot) {
    Objects.requireNonNull(oryxosRoot, "oryxosRoot");
    this.root = oryxosRoot.toAbsolutePath().normalize();
  }

  /**
   * @return 工作区根（{@code .oryxos}），便于调用方在同一坐标系里取别的路径
   */
  public Path root() {
    return root;
  }

  /**
   * Agent 目录（{@code agents/<name>}）。
   *
   * <p>名字同样过一遍守卫：目录名来自 URL 路径段，含 {@code /} 或 {@code ..} 时不允许拼出 {@code agents/} 之外的位置——否则 「删除一个叫
   * {@code ../../etc} 的 Agent」就是一条删除任意目录的路径。
   *
   * @param name Agent 名
   * @return {@code <root>/agents/<name>} 的归一化绝对路径
   * @throws IllegalArgumentException 名字为空或解析后越界
   */
  public Path agentDir(String name) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("Agent 名称不能为空");
    }
    Path agentsRoot = root.resolve(AGENTS_DIR).normalize();
    Path dir = agentsRoot.resolve(name).normalize();
    if (!dir.startsWith(agentsRoot) || dir.equals(agentsRoot)) {
      throw new IllegalArgumentException("Agent 名称非法: '" + name + "'");
    }
    return dir;
  }

  /**
   * @param name Agent 名
   * @return 该 Agent 目录是否存在（不跟随软连接：一个指向别处的软连接不是一个 Agent 目录）
   */
  public boolean exists(String name) {
    return Files.isDirectory(agentDir(name), LinkOption.NOFOLLOW_LINKS);
  }

  /**
   * 按模板脚手架出一个完整 Agent 目录：{@code AGENT.md} + {@code REFERENCE.md} + 空 {@code scripts/} + 空 {@code
   * skills/}。
   *
   * <p><b>不调模型</b>：创建必须可复现——同一份输入每次都产出同一份目录。要「一句话生成」走 {@code generate-files}，那条路径的产物先给人看，看过了才落盘。
   *
   * @param name Agent 名（= 目录名）
   * @param description 一句话说明；空则写空串
   * @param provider 厂商名
   * @param model 模型名
   * @return 刚建出的 Agent 目录
   */
  public Path scaffold(String name, String description, String provider, String model) {
    Path dir = agentDir(name);
    try {
      Files.createDirectories(dir);
      Files.createDirectories(dir.resolve(SCRIPTS_DIR));
      Files.createDirectories(dir.resolve(SKILLS_DIR));
      String text = String.join("\n", description == null ? "" : description);
      Files.writeString(
          dir.resolve(AGENT_FILE),
          agentMarkdown(name, description, provider, model, text),
          StandardCharsets.UTF_8);
      Files.writeString(dir.resolve(REFERENCE_FILE), SCAFFOLD_REFERENCE, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("创建 Agent 目录失败：" + dir + "（" + e.getMessage() + "）", e);
    }
    return dir;
  }

  /**
   * 模板 AGENT.md 的完整文本。
   *
   * <p>{@code description} 走 {@link #yamlString} 转义：业务方填的描述里可能带冒号、引号、井号，裸拼进 YAML 会让整个 frontmatter
   * 解析失败—— 而失败会一路传到「创建成功但 Agent 不可用」，比创建失败更难查。
   */
  private static String agentMarkdown(
      String name, String description, String provider, String model, String body) {
    return "---\n"
        + "description: "
        + yamlString(description == null ? "" : description)
        + "\n"
        + "provider:\n"
        + "  name: "
        + yamlString(provider)
        + "\n"
        + "  model: "
        + yamlString(model)
        + "\n"
        + "tools: []\n"
        + "---\n\n"
        + "# "
        + name
        + "\n"
        + SCAFFOLD_BODY_HEAD
        + (body.isBlank() ? "（还没写。把这个 Agent 收到消息后要做的事写在这里。）" : body)
        + "\n"
        + SCAFFOLD_BODY_TAIL;
  }

  /** 双引号风格的 YAML 标量：换行压成空格，引号与反斜杠转义。 */
  private static String yamlString(String value) {
    String text = value == null ? "" : value.replaceAll("\\R+", " ").strip();
    return '"' + text.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
  }

  /**
   * 覆写某个 Agent 的 {@code AGENT.md}（校验由调用方在写之前做完）。
   *
   * @param name Agent 名
   * @param markdown 完整 AGENT.md 文本
   */
  public void writeAgentMarkdown(String name, String markdown) {
    Path dir = agentDir(name);
    try {
      Files.createDirectories(dir);
      Files.writeString(dir.resolve(AGENT_FILE), markdown, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("写入 AGENT.md 失败：" + dir + "（" + e.getMessage() + "）", e);
    }
  }

  /**
   * 把一组相对路径写进某个 Agent 目录（{@code generate-files} 的「保存并生效」）。
   *
   * <p>每个 key 都是相对 <b>Agent 目录</b>的路径（{@code AGENT.md}、{@code scripts/x.py}），逐个过守卫：越界的 key
   * 让整批<b>一个文件都不写</b>。 调用方已经把 AGENT.md 的内容校验过了；本方法只负责落盘。
   *
   * @param name Agent 名
   * @param files 相对路径 → 内容
   */
  public void writeAll(String name, Map<String, String> files) {
    Path dir = agentDir(name);
    Objects.requireNonNull(files, "files");
    for (Map.Entry<String, String> entry : files.entrySet()) {
      resolveWithin(dir, entry.getKey());
    }
    try {
      Files.createDirectories(dir);
      for (Map.Entry<String, String> entry : files.entrySet()) {
        Path target = resolveWithin(dir, entry.getKey());
        // 建父目录：key 的父部分为空时就是 Agent 目录本身（key 已由 resolveWithin 校验过，不会越界）
        Path keyParent = Path.of(entry.getKey()).getParent();
        Path parentDir = keyParent == null ? dir : dir.resolve(keyParent).normalize();
        Files.createDirectories(parentDir);
        Files.writeString(
            target, entry.getValue() == null ? "" : entry.getValue(), StandardCharsets.UTF_8);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("写入 Agent 文件失败：" + dir + "（" + e.getMessage() + "）", e);
    }
  }

  /**
   * 读一个相对工作区根的文件（管理台文件浏览器）。
   *
   * @param relative 相对 {@code .oryxos} 的路径，如 {@code agents/weather/AGENT.md}
   * @return 文件内容（UTF-8）
   * @throws IllegalArgumentException 路径越界
   * @throws UncheckedIOException 文件不存在或读失败；非 UTF-8 由调用方按 {@link CharacterCodingException} 的先验判断拦在前面
   */
  public String read(String relative) {
    Path file = resolveSafe(relative);
    try {
      return Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("读取文件失败：" + relative + "（" + e.getMessage() + "）", e);
    }
  }

  /**
   * 工作区目录树：{@code agents/} 与 {@code archive/} 两棵子树。
   *
   * <p><b>只遍历这两棵</b>：{@code .oryxos} 下还有库文件、日志、记忆——把它们塞进「Agent 文件浏览器」既没意义又扩大暴露面。不存在的根直接省略，
   * 不凭空造一个空目录节点。
   *
   * <p><b>不跟随软连接</b>：{@code skills/<name>} 正是指向公共 Skill 的相对软连接，跟进去会把 {@code .oryxos/skills} 整棵树复制一遍
   * （且软连接成环时永不终止）。软连接按叶子节点展示。
   *
   * <p><b>目录在前、同名按字典序</b>：输出稳定才可断言，也符合人翻目录的习惯。
   *
   * @return 树根列表，按 {@code agents} / {@code archive} 顺序
   */
  public List<FileNode> tree() {
    List<FileNode> roots = new ArrayList<>();
    for (String name : new String[] {AGENTS_DIR, ARCHIVE_DIR}) {
      Path dir = root.resolve(name);
      if (Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
        roots.add(node(dir, name));
      }
    }
    return List.copyOf(roots);
  }

  private FileNode node(Path path, String relative) {
    Path fileName = path.getFileName();
    String name = fileName == null ? relative : fileName.toString();
    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
      return new FileNode(name, relative, true, children(path, relative));
    }
    return new FileNode(name, relative, false, List.of());
  }

  private List<FileNode> children(Path dir, String relative) {
    List<Path> entries;
    try (Stream<Path> listed = Files.list(dir)) {
      entries = listed.toList();
    } catch (IOException e) {
      throw new UncheckedIOException("读取目录失败：" + dir + "（" + e.getMessage() + "）", e);
    }
    List<Path> sorted = new ArrayList<>(entries);
    sorted.sort(
        Comparator.comparing((Path entry) -> !Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS))
            .thenComparing(entry -> String.valueOf(entry.getFileName())));
    List<FileNode> nodes = new ArrayList<>();
    for (Path entry : sorted) {
      nodes.add(node(entry, relative + "/" + entry.getFileName()));
    }
    return nodes;
  }

  /**
   * 把一个 Agent 目录整体移进 {@code archive/}（删除 = 归档，不物理删）。
   *
   * <p>重名时目标改为 {@code <name>-<epochMillis>}：归档区可能已经躺着同名的旧版本，覆盖它等于把上一份删掉——而「可追溯」正是归档存在的理由。
   *
   * @param name Agent 名
   * @return 归档后的目录路径
   */
  public Path archive(String name) {
    Path source = agentDir(name);
    Path archiveRoot = root.resolve(ARCHIVE_DIR);
    Path target = archiveRoot.resolve(name);
    try {
      Files.createDirectories(archiveRoot);
      if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
        target = archiveRoot.resolve(name + "-" + System.currentTimeMillis());
      }
      return Files.move(source, target);
    } catch (IOException e) {
      throw new UncheckedIOException("归档 Agent 目录失败：" + source + "（" + e.getMessage() + "）", e);
    }
  }

  /**
   * 物理删除一个 Agent 目录。<b>只用于创建失败时的回滚</b>——对外的「删除 Agent」是 {@link #archive}。
   *
   * @param name Agent 名
   */
  public void delete(String name) {
    Path dir = agentDir(name);
    if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    try (Stream<Path> walk = Files.walk(dir)) {
      // 先删子项再删目录：Files.walk 是父先子后，倒序正好反过来
      for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("删除 Agent 目录失败：" + dir + "（" + e.getMessage() + "）", e);
    }
  }

  /**
   * 把「相对工作区根的路径」解析成绝对路径，并断言它<b>仍落在工作区内</b>（第 30 节 FR-006 的目录穿越守卫）。
   *
   * <p>三道判据缺一不可：空白直接拒（空路径会解析成工作区根自己）；绝对路径直接拒（{@code /etc/passwd} 与 {@code root.resolve}
   * 叠加后仍可能指到界外）；{@code normalize()} 之后必须仍以工作区根为前缀（{@code ../../etc/passwd} 正是在这一步被拦下，{@code
   * agents/../../x} 同理）。
   *
   * <p><b>刻意不额外限制 {@code .oryxos} 下的子目录</b>：课件口径就是「落在工作区内即可」，更细的白名单（例如禁止碰 {@code
   * oryxos.db}）随扩展阶段的鉴权一起收。
   *
   * @param relative 相对工作区根的路径，用 {@code /} 或平台分隔符都可
   * @return 归一化的绝对路径
   * @throws IllegalArgumentException 路径为空、含 NUL、是绝对路径或越界
   */
  @SuppressFBWarnings(
      value = "PATH_TRAVERSAL_IN",
      justification =
          "本方法就是那条统一的穿越守卫：Path.of 之后立即 normalize，并用 startsWith(root) 判定是否越界，"
              + "越界一律抛 IllegalArgumentException。FindSecBugs 看到的是「不可信字符串进了 Path.of」，"
              + "看不到紧随其后的校验；把守卫集中在这一处，比在每个调用点各写一遍更可审计")
  public Path resolveSafe(String relative) {
    if (relative == null || relative.isBlank()) {
      throw new IllegalArgumentException("path 不能为空");
    }
    if (relative.indexOf(NUL_CHAR) >= 0) {
      throw new IllegalArgumentException("path 非法: 含 NUL 字符");
    }
    Path resolved;
    try {
      resolved = Path.of(relative);
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("path 非法: '" + relative + "'（" + e.getMessage() + "）", e);
    }
    if (resolved.isAbsolute()) {
      throw new IllegalArgumentException("path 必须是相对工作区的路径: '" + relative + "'");
    }
    Path target = root.resolve(resolved).normalize();
    if (!target.startsWith(root)) {
      throw new IllegalArgumentException("path escapes workspace: " + relative);
    }
    return target;
  }

  /** 把相对路径解析到某个目录内并断言不越界（{@code writeAll} 的 key 相对 Agent 目录）。 */
  private static Path resolveWithin(Path base, String relative) {
    if (relative == null || relative.isBlank()) {
      throw new IllegalArgumentException("文件路径不能为空");
    }
    Path resolved;
    try {
      resolved = Path.of(relative);
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("文件路径非法: '" + relative + "'", e);
    }
    if (resolved.isAbsolute()) {
      throw new IllegalArgumentException("文件路径必须相对 Agent 目录: '" + relative + "'");
    }
    Path target = base.resolve(resolved).normalize();
    if (!target.startsWith(base) || target.equals(base)) {
      throw new IllegalArgumentException("文件路径越界: '" + relative + "'");
    }
    return target;
  }
}
