package io.oryxlabs.oryxos.tool.builtin;

import io.oryxlabs.oryxos.core.tool.ToolResult;
import io.oryxlabs.oryxos.tool.sandbox.ActionType;
import io.oryxlabs.oryxos.tool.sandbox.Sandbox;
import io.oryxlabs.oryxos.tool.sandbox.SandboxAction;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 内置文件工具：{@code read_file} / {@code write_file} / {@code list_dir} / {@code edit_file} / {@code
 * grep} / {@code glob}（contracts/builtin-tools.md §2.1~2.6）。
 *
 * <p><b>工具名写在 {@code @Tool(name = ...)} 上，不靠方法名推导</b>：工具名是模型引用它的唯一凭据，一旦交付即不得改
 * （contracts/builtin-tools.md 开篇）。方法名随 Java 习惯取驼峰（{@code readFile}），名字由注解显式钉住，两者解耦——
 * 否则哪天有人为了「可读性」重命名方法，模型侧的工具名会跟着静默漂移。
 *
 * <p><b>每个 {@code @Tool} 方法的第一行都是 {@code sandbox.enforce(...)}</b>（FR-012 / H4 不变量 ①）。顺序不可颠倒： 先做 IO
 * 再校验在功能上完全看不出区别，却把安全不变量变成一句空话——由 {@code FileToolsTest} 用「IO 一次都没发生」的 断言钉死。
 *
 * <p><b>MUST NOT catch {@code SandboxViolationException}</b>（contracts/builtin-tools.md
 * §4.4）：校验失败必须在 {@code ToolExecutor} 的既有失败审计路径里落一条 {@code success=false}。在这里吞掉它，越界调用在审计表里会消失。
 *
 * <p><b>本类不做白名单判断</b>：校验本体归第 24 节 {@code WhitelistSandbox}，这里只以 {@code enforce} 调用形式接入 （§4.1）。注入的是
 * {@link Sandbox} 接口而非任何具体实现，故换沙箱时本类零改动。
 */
public class FileTools {

  /**
   * {@code grep} / {@code glob} 的返回条数上限（contracts/builtin-tools.md §2.5 / §2.6）。
   *
   * <p>与 {@code WebSearchTools} 里同名同值的那一份是<b>刻意各留一份</b>：抽成共享常量就得新开一个 public 类型（软门禁 ①），
   * 而这两个数各自属于一个工具的契约，本来就可能独立调整。
   */
  private static final int MAX_RESULTS = 200;

  /**
   * {@code read_file} 单次返回的行数上限（contracts/builtin-tools.md §2.1）。
   *
   * <p>与 {@link #MAX_RESULTS} 一样是「保护模型上下文」而非「保护 JVM 内存」：本类仍然整读文件（{@code Files.readString}），
   * 上限只决定**回给模型多少**。真正的大文件流式读取不在核心阶段范围内。
   */
  private static final int MAX_READ_LINES = 2000;

  /**
   * 行分隔符：{@code grep} 按它切行并报行号，{@code read_file} 的分页也按它切。
   *
   * <p>只认 {@code \n}：Windows 的 {@code \r\n} 会多出一个行尾 {@code \r}，但它落在行内容里、不影响行号；反过来若在这里做「两种都切」，
   * 同一份文件的行号会因平台而异，行号这条信息就不可复现了。
   *
   * <p><b>两个工具共用同一份切法是有意的</b>：{@code read_file} 报的行号要能直接喂给 {@code edit_file} 和 {@code grep}，
   * 三者对「第几行」的口径必须一致，否则模型会拿着一个错的行号去改文件。
   */
  private static final String LINE_SEPARATOR = "\n";

  /** 上下文行不连续的两组之间的分隔行（grep 惯例），让模型看得出中间跳过了内容。 */
  private static final String GROUP_SEPARATOR = "--";

  /** 二进制文件的兜底判据：合法的 NUL 字符（见 {@link #scanFile}）。 */
  private static final char NUL = '\0';

  /**
   * glob 的「任意层目录」前缀。
   *
   * <p>单列成常量是因为它在 {@link #globMatchers} 里要出现两次（判前缀、剥前缀），两次必须同源，否则剥错了前缀会静默匹配不到。
   */
  private static final String GLOBSTAR_PREFIX = "**/";

  private final Sandbox sandbox;

  public FileTools(Sandbox sandbox) {
    this.sandbox = sandbox;
  }

  /**
   * 读取一个文本文件的一段内容。
   *
   * <p><b>为什么必须有默认上限</b>：无上限地读一个几万行的日志，会把模型的上下文窗口一次撑爆，而撑爆之后这一轮对话已经废了——
   * 模型连「我读多了」都意识不到。给一个默认窗口加一行续读说明，模型就能自己决定要不要往下翻。
   *
   * <p><b>不指定范围且没超上限时返回原样全文</b>：走 {@code Files.readString} 的原始结果，末尾换行、空行逐字节保留。
   * 只有需要分页时才按行切片，两条路径在小文件上等价。
   *
   * @param path 文件路径
   * @param offset 起始行号（1 起；省略为 1）
   * @param limit 最多读取的行数（省略为上限 {@value #MAX_READ_LINES}；超过上限时按上限夹取）
   * @return 成功时为该行窗口的内容，窗口没读到文件末尾时追加一行续读说明；文件不存在或不是普通文件、参数越界、读取失败时为 失败结果（不可重试）
   */
  @Tool(
      name = "read_file",
      description = "读取一个文本文件的内容；可用 offset/limit 按行读一段，单次最多 " + MAX_READ_LINES + " 行")
  public ToolResult readFile(
      @ToolParam(description = "文件路径") String path,
      @ToolParam(description = "起始行号（1 起，省略为 1）", required = false) Integer offset,
      @ToolParam(description = "最多读取的行数（省略则读到末尾或上限）", required = false) Integer limit) {
    sandbox.enforce(new SandboxAction(ActionType.FILE_READ, path));
    Path target = Path.of(path);
    if (!Files.isRegularFile(target)) {
      return ToolResult.failure("文件不存在或不是普通文件: " + path);
    }
    if (offset != null && offset < 1) {
      return ToolResult.failure("offset 从 1 起（收到 " + offset + "）");
    }
    if (limit != null && limit < 1) {
      return ToolResult.failure("limit 至少为 1（收到 " + limit + "）");
    }

    String whole;
    try {
      whole = Files.readString(target, StandardCharsets.UTF_8);
    } catch (IOException e) {
      return ToolResult.failure("读取失败: " + e.getMessage());
    }

    String[] lines = whole.split(LINE_SEPARATOR, -1);
    int last = lastLineIndex(lines);
    if (offset == null && limit == null && last <= MAX_READ_LINES) {
      // 原样返回：小文件的读取结果与「还没有分页」时期逐字节一致，既有调用方不会因为这次改动看到任何差异
      return ToolResult.success(whole);
    }

    int from = offset == null ? 1 : offset;
    if (from > last) {
      // 越界给成功结果而不是失败：这不是「读错了」，是「这一页没有内容」。报失败会让 ReAct 循环当成可重试错误反复试
      return ToolResult.success("（文件共 " + last + " 行，offset=" + from + " 超出末尾）");
    }
    int to =
        Math.min(
            last, from - 1 + (limit == null ? MAX_READ_LINES : Math.min(limit, MAX_READ_LINES)));

    StringBuilder body = new StringBuilder();
    for (int line = from; line <= to; line++) {
      if (body.length() > 0) {
        body.append(LINE_SEPARATOR);
      }
      body.append(lines[line - 1]);
    }
    if (to < last) {
      body.append(LINE_SEPARATOR)
          .append("（已截断：共 ")
          .append(last)
          .append(" 行，本次显示第 ")
          .append(from)
          .append("~")
          .append(to)
          .append(" 行；用 offset=")
          .append(to + 1)
          .append(" 继续）");
    }
    return ToolResult.success(body.toString());
  }

  /**
   * 最后一行<b>真实存在</b>的行号。
   *
   * <p>按 {@code \n} 切出来的数组在文件以换行结尾时会多出一个空元素（{@code "a\nb\n"} 切成 {@code ["a","b",""]}）。那个元素
   * 不是一行，是「换行符后面的空」——报进「共 N 行」里会让模型以为还有第 3 行可读，接着拿着 {@code offset=3} 白跑一次。
   *
   * <p>它只影响<b>行数</b>的报法，不影响行号：第 i 行的行号仍然是 i，与 {@code grep} 的口径一致。
   */
  private static int lastLineIndex(String[] lines) {
    return lines.length > 1 && lines[lines.length - 1].isEmpty() ? lines.length - 1 : lines.length;
  }

  /**
   * 把文本写入一个文件（覆盖写）。
   *
   * <p><b>父目录不存在时不自动创建</b>（contracts/builtin-tools.md §2.2）：模型写错路径时，自动建目录会把文件安静地
   * 落到一个谁也没想到的位置；明确报错才能让它自己发现路径写错了。
   *
   * @param path 文件路径
   * @param content 要写入的文本
   * @return 成功时为「写入字节数 + 目标路径」；父目录不存在或写入失败时为失败结果（不可重试）
   */
  @Tool(name = "write_file", description = "把文本写入一个文件（覆盖写；父目录不存在时不会自动创建）")
  public ToolResult writeFile(
      @ToolParam(description = "文件路径") String path,
      @ToolParam(description = "要写入的文本") String content) {
    sandbox.enforce(new SandboxAction(ActionType.FILE_WRITE, path));
    Path target = Path.of(path).toAbsolutePath();
    Path parent = target.getParent();
    if (parent == null || !Files.isDirectory(parent)) {
      return ToolResult.failure("父目录不存在，不自动创建: " + parent);
    }
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    try {
      Files.write(target, bytes);
    } catch (IOException e) {
      return ToolResult.failure("写入失败: " + e.getMessage());
    }
    return ToolResult.success("已写入 " + bytes.length + " 字节: " + target);
  }

  /**
   * 列出一个目录下的条目。
   *
   * @param path 目录路径
   * @return 成功时每行一条「{@code [文件] 名字}」/「{@code [目录] 名字}」，按名字排序；路径不存在或不是目录时为失败结果
   */
  @Tool(name = "list_dir", description = "列出一个目录下的条目（每行「类型 + 名字」），按名字排序")
  public ToolResult listDir(@ToolParam(description = "目录路径") String path) {
    sandbox.enforce(new SandboxAction(ActionType.FILE_READ, path));
    Path dir = Path.of(path);
    if (!Files.isDirectory(dir)) {
      return ToolResult.failure("路径不存在或不是目录: " + path);
    }
    try (Stream<Path> entries = Files.list(dir)) {
      // 先按文件名排序、再渲染「[类型] 名字」——直接排渲染后的整行会先按类型分组，而契约要的是按名字排
      // 用 String.valueOf 而不是 getFileName().toString()：Path.getFileName() 对根路径返回 null（SpotBugs 据此报
      // NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE）。这里排的是某个目录的子项、实际取不到 null，包一层让这个
      // 「不可能」在类型上也炸不了——真出现 null 时排成 "null" 也比抛 NPE 好
      List<String> lines =
          entries
              .sorted(Comparator.comparing(entry -> String.valueOf(entry.getFileName())))
              .map(FileTools::describe)
              .toList();
      return ToolResult.success(String.join("\n", lines));
    } catch (IOException e) {
      return ToolResult.failure("读取目录失败: " + e.getMessage());
    }
  }

  /**
   * 把文件里的一段文本替换成另一段（contracts/builtin-tools.md §2.4）。
   *
   * <p><b>为什么要求 {@code oldText} 唯一匹配</b>：若它在文件里多处命中，「替换哪一处」无从判定，猜错就是静默改坏文件。宁可报错让 模型给出更长的上下文来定位唯一点。
   *
   * <p><b>原子性靠「先读全文 → 内存里判定 → 通过才写回」</b>：0 次或 ≥2 次命中都在写之前返回，文件一个字都不动。若边读边写
   * （例如流式替换），「命中两次」这个判定要读到第二次才知道，而那时第一处已经写进去了。
   *
   * @param path 文件路径
   * @param oldText 要被替换的原文；必须在文件里恰好出现一次
   * @param newText 替换成的新文本
   * @return 成功时为「替换位置的行号 + 替换前后片段」；不唯一、文件不存在、读写失败时为失败结果（不可重试）
   */
  @Tool(name = "edit_file", description = "把文件里的一段文本替换成另一段；oldText 必须在文件里恰好出现一次，否则报错且不改动文件")
  public ToolResult editFile(
      @ToolParam(description = "文件路径") String path,
      @ToolParam(description = "要被替换的原文（必须在文件里恰好出现一次）") String oldText,
      @ToolParam(description = "替换成的新文本") String newText) {
    sandbox.enforce(new SandboxAction(ActionType.FILE_WRITE, path));
    Path target = Path.of(path);
    if (!Files.isRegularFile(target)) {
      return ToolResult.failure("文件不存在或不是普通文件: " + path);
    }

    String original;
    try {
      original = Files.readString(target, StandardCharsets.UTF_8);
    } catch (IOException e) {
      return ToolResult.failure("读取失败: " + e.getMessage());
    }

    int first = original.indexOf(oldText);
    if (first < 0) {
      return ToolResult.failure("oldText 在文件里找不到: " + path);
    }
    if (original.indexOf(oldText, first + 1) >= 0) {
      return ToolResult.failure("oldText 在文件里出现了多次，无法确定改哪一处: " + path);
    }

    String updated =
        original.substring(0, first) + newText + original.substring(first + oldText.length());
    try {
      Files.writeString(target, updated, StandardCharsets.UTF_8);
    } catch (IOException e) {
      return ToolResult.failure("写入失败: " + e.getMessage());
    }
    return ToolResult.success(
        "已替换第 " + lineNumberOf(original, first) + " 行：\n- " + oldText + "\n+ " + newText);
  }

  /**
   * 按正则在一个文件或目录里搜内容（contracts/builtin-tools.md §2.5）。
   *
   * <p><b>每页上限 {@value #MAX_RESULTS} 条</b>：超大目录一次全吐出来会把模型的上下文撑爆。超出一页时注明<b>总命中数</b>与 <b>下一页的 {@code
   * offset}</b>——只写「已截断」而不给总数、不给续读手段，模型只能靠换一个更窄的正则去猜。
   *
   * <p><b>二进制 / 非 UTF-8 文件跳过，不中断整次搜索</b>（FR-014）：目录里混着一个 jar 或图片是常态，为它中断整次搜索等于让
   * 「搜一个工程目录」这件事基本不可用。判定靠 UTF-8 解码器本身——二进制文件几乎必然含非法 UTF-8 字节序列，解码即失败；再加一道 NUL
   * 字符检查，兜住「全零字节」这类<b>恰好是合法 UTF-8</b> 的二进制文件。
   *
   * @param pattern 正则表达式
   * @param path 文件或目录路径；目录则递归
   * @param offset 从第几条命中开始返回（1 起；省略为 1）
   * @param contextLines 每条命中前后各显示几行上下文（省略为 0，即只显示命中行本身）
   * @return 成功时每行一条「{@code 路径:行号: 行内容}」（上下文行用 {@code 路径:行号- 行内容} 区分）； 正则非法、路径不存在、参数越界时为失败结果（不可重试）
   */
  @Tool(name = "grep", description = "按正则在一个文件或目录里搜内容，返回「路径:行号: 行内容」，每页最多 " + MAX_RESULTS + " 条")
  public ToolResult grep(
      @ToolParam(description = "正则表达式") String pattern,
      @ToolParam(description = "文件或目录路径（目录则递归）") String path,
      @ToolParam(description = "从第几条命中开始返回（1 起，省略为 1）", required = false) Integer offset,
      @ToolParam(description = "每条命中前后各显示 N 行上下文（省略为 0）", required = false) Integer contextLines) {
    sandbox.enforce(new SandboxAction(ActionType.FILE_READ, path));

    Pattern compiled;
    try {
      compiled = Pattern.compile(pattern);
    } catch (PatternSyntaxException e) {
      // 正则非法是「输入错了」，重试同一个正则不会变好
      return ToolResult.failure("正则表达式非法: " + e.getMessage());
    }
    if (offset != null && offset < 1) {
      return ToolResult.failure("offset 从 1 起（收到 " + offset + "）");
    }
    if (contextLines != null && contextLines < 0) {
      return ToolResult.failure("contextLines 不能为负（收到 " + contextLines + "）");
    }

    Path target = Path.of(path);
    if (!Files.exists(target)) {
      return ToolResult.failure("路径不存在: " + path);
    }

    GrepHits hits =
        new GrepHits(offset == null ? 1 : offset, contextLines == null ? 0 : contextLines);
    try {
      for (Path file : collectFiles(target)) {
        scanFile(file, compiled, hits);
      }
    } catch (IOException e) {
      return ToolResult.failure("搜索失败: " + e.getMessage());
    }
    return hits.toResult();
  }

  /**
   * 按通配符找文件路径（contracts/builtin-tools.md §2.6）。
   *
   * <p><b>匹配对象是「相对搜索根的路径」</b>：这样 {@code src/**&#47;*.java} 这类带目录的模式才有意义。但 Java 的 glob 里 {@code
   * **&#47;} <b>至少要吃掉一层目录</b>（{@code **&#47;*.java} 匹配不到根下的 {@code a.java}），与 gitignore / bash
   * globstar 等主流惯例（{@code **&#47;} 可匹配零层）不一致——故额外按「剥掉开头 {@code **&#47;} 再试一次」补上这个惯例，否则用户按惯例写的
   * 模式会静默漏掉根目录下的文件。
   *
   * <p><b>只返回普通文件</b>：这个工具的用途是「找文件」，把目录也混进来只会让模型多绕一步。
   *
   * @param pattern 通配符模式（相对搜索根）
   * @param path 搜索根目录
   * @param offset 从第几条匹配开始返回（1 起；省略为 1）
   * @return 成功时匹配到的路径列表，每行一条、按路径排序，每页最多 {@value #MAX_RESULTS} 条（超出则截断并注明总数与下一页的 {@code
   *     offset}）；路径不存在、模式非法、参数越界时为失败结果（不可重试）
   */
  @Tool(name = "glob", description = "按通配符在一个目录下找文件路径，每页最多 " + MAX_RESULTS + " 条")
  public ToolResult glob(
      @ToolParam(description = "通配符模式，相对搜索根，如 **/*.java") String pattern,
      @ToolParam(description = "搜索根目录") String path,
      @ToolParam(description = "从第几条匹配开始返回（1 起，省略为 1）", required = false) Integer offset) {
    sandbox.enforce(new SandboxAction(ActionType.FILE_READ, path));

    Path root = Path.of(path);
    if (!Files.isDirectory(root)) {
      return ToolResult.failure("路径不存在或不是目录: " + path);
    }
    if (offset != null && offset < 1) {
      return ToolResult.failure("offset 从 1 起（收到 " + offset + "）");
    }

    List<PathMatcher> matchers;
    try {
      matchers = globMatchers(pattern);
    } catch (IllegalArgumentException e) {
      // PatternSyntaxException 是 IllegalArgumentException 的子类，一个 catch 覆盖
      return ToolResult.failure("通配符模式非法: " + e.getMessage());
    }

    List<String> matched = new ArrayList<>();
    try (Stream<Path> entries = Files.walk(root)) {
      entries
          .filter(Files::isRegularFile)
          .filter(file -> matchesAny(matchers, root.relativize(file)))
          .map(file -> root.relativize(file).toString())
          .sorted()
          .forEach(matched::add);
    } catch (IOException e) {
      return ToolResult.failure("查找失败: " + e.getMessage());
    }

    if (matched.isEmpty()) {
      return ToolResult.success("（无匹配路径）");
    }
    int from = offset == null ? 1 : offset;
    if (from > matched.size()) {
      return ToolResult.success("（共 " + matched.size() + " 条匹配，offset=" + from + " 超出末尾）");
    }
    int to = Math.min(matched.size(), from - 1 + MAX_RESULTS);
    String body = String.join(LINE_SEPARATOR, matched.subList(from - 1, to));
    if (to < matched.size()) {
      body +=
          LINE_SEPARATOR
              + "（已截断：共 "
              + matched.size()
              + " 条匹配，本次显示第 "
              + from
              + "~"
              + to
              + " 条；用 offset="
              + (to + 1)
              + " 继续）";
    }
    return ToolResult.success(body);
  }

  private static String describe(Path entry) {
    return (Files.isDirectory(entry) ? "[目录] " : "[文件] ") + entry.getFileName();
  }

  /** 1 起的行号：数 {@code offset} 之前出现过多少个换行。 */
  private static int lineNumberOf(String text, int offset) {
    int line = 1;
    for (int i = 0; i < offset; i++) {
      if (text.charAt(i) == '\n') {
        line++;
      }
    }
    return line;
  }

  /** 目标可能是单个文件，也可能是要递归的目录；顺序固定，让结果可重复。 */
  private static List<Path> collectFiles(Path target) throws IOException {
    if (Files.isRegularFile(target)) {
      return List.of(target);
    }
    try (Stream<Path> entries = Files.walk(target)) {
      return entries.filter(Files::isRegularFile).sorted().toList();
    }
  }

  /** 扫一个文件；读不出文本（二进制 / 非 UTF-8）就跳过它，绝不中断整次搜索。 */
  private static void scanFile(Path file, Pattern pattern, GrepHits hits) {
    String text;
    try {
      text = Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      return;
    }
    if (text.indexOf(NUL) >= 0) {
      // 全零字节这类「恰好是合法 UTF-8」的二进制文件：解码器放它过去，NUL 检查拦下它
      return;
    }
    String[] lines = text.split(LINE_SEPARATOR, -1);
    // 先收本文件的命中行号，扫完再整体渲染：上下文行要在「本文件所有命中都已知」之后才能去重
    SortedSet<Integer> matched = new TreeSet<>();
    for (int line = 1; line <= lines.length; line++) {
      if (pattern.matcher(lines[line - 1]).find() && hits.count()) {
        matched.add(line);
      }
    }
    if (!matched.isEmpty()) {
      hits.collect(file, lines, matched);
    }
  }

  /**
   * 一次 {@code grep} 的收集状态。
   *
   * <p><b>为什么命中不边扫边渲染</b>：开了上下文行之后，同一个文件里相邻命中的上下文会重叠（第 5 行与第 7 行各带 3 行上下文，第 6 行 会被展开两次）。按「文件 +
   * 行号」去重后再渲染，同一行才不会在结果里出现两遍。
   *
   * <p><b>为什么超出一页之后还要继续扫</b>：截断说明要给模型一个<b>真总数</b>（「共 501 条命中」），只报本页条数它无从判断要不要
   * 缩小范围。但本页之外的内容一律不留在内存里——上限保护的正是这件事。
   */
  private static final class GrepHits {

    /** 本页第一条命中的全局序号（1 起）。 */
    private final int offset;

    private final int contextLines;

    private final List<String> page = new ArrayList<>();

    /** 已扫到的命中总数，不受分页影响。 */
    private int total;

    /** 已收进本页的命中条数。 */
    private int paged;

    GrepHits(int offset, int contextLines) {
      this.offset = offset;
      this.contextLines = contextLines;
    }

    /**
     * 记一条命中。
     *
     * @return 它是否落在本页上（true 表示要渲染）
     */
    boolean count() {
      total++;
      if (total < offset || paged >= MAX_RESULTS) {
        return false;
      }
      paged++;
      return true;
    }

    /** 把一个文件里落在本页上的命中、连同各自的上下文行，追加进结果。 */
    void collect(Path file, String[] lines, SortedSet<Integer> matched) {
      SortedSet<Integer> emitted = new TreeSet<>(matched);
      if (contextLines > 0) {
        for (int line : matched) {
          for (int context = Math.max(1, line - contextLines);
              context <= Math.min(lines.length, line + contextLines);
              context++) {
            emitted.add(context);
          }
        }
      }
      int previous = -1;
      for (int line : emitted) {
        if (contextLines > 0 && previous >= 0 && line > previous + 1) {
          page.add(GROUP_SEPARATOR);
        }
        // 命中行用 ':'、上下文行用 '-'（grep 惯例）：模型据此能分辨哪一行才是真正命中的
        page.add(file + ":" + line + (matched.contains(line) ? ": " : "- ") + lines[line - 1]);
        previous = line;
      }
    }

    ToolResult toResult() {
      if (total == 0) {
        return ToolResult.success("（无命中）");
      }
      if (page.isEmpty()) {
        return ToolResult.success("（共 " + total + " 条命中，offset=" + offset + " 超出末尾）");
      }
      String body = String.join(LINE_SEPARATOR, page);
      // 判据是「本页最后一条之后还有没有命中」，不是「本页有没有装满」：翻到第二页时本页只有 100 条、
      // 但总数 300 条已经全部装下，用 total > paged 会误报一次并不存在的截断
      if (total > offset + paged - 1) {
        body +=
            LINE_SEPARATOR
                + "（已截断：共 "
                + total
                + " 条命中，本次显示第 "
                + offset
                + "~"
                + (offset + paged - 1)
                + " 条；用 offset="
                + (offset + paged)
                + " 继续）";
      }
      return ToolResult.success(body);
    }
  }

  /** 原样一个匹配器；模式以 {@code **&#47;} 开头时再补一个「剥掉它」的，让 {@code **&#47;} 也能匹配零层目录。 */
  private static List<PathMatcher> globMatchers(String pattern) {
    PathMatcher direct = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
    if (!pattern.startsWith(GLOBSTAR_PREFIX)) {
      return List.of(direct);
    }
    return List.of(
        direct,
        FileSystems.getDefault()
            .getPathMatcher("glob:" + pattern.substring(GLOBSTAR_PREFIX.length())));
  }

  private static boolean matchesAny(List<PathMatcher> matchers, Path relative) {
    for (PathMatcher matcher : matchers) {
      if (matcher.matches(relative)) {
        return true;
      }
    }
    return false;
  }
}
