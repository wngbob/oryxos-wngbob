package io.oryxlabs.oryxos.memory.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 默认档：长期记忆落在工作区的一个 Markdown 文件里（{@code <oryxosDir>/memory/MEMORY.md}）。
 *
 * <p><b>为什么这是默认档</b>：零依赖、人可直接读改、能被 git 跟踪。记忆在核心阶段是「几十条自己写进去的事」，不是「几百万条日志」—— 一个文件扛得住，而且出问题时用眼睛就能看出
 * Agent 到底记住了什么。
 *
 * <p><b>两个区块</b>由 header 切分：{@code ## 核心记忆} 永远全量注入，{@code ## 归档记忆} 超阈值只留尾部。截断<b>物理上只碰归档段</b>——
 * 核心段的字符串根本不进截断函数，所以「核心记忆被截断」在这个实现里是不可能发生的事，不是靠小心翼翼的边界判断。
 *
 * <p><b>不缓存</b>：{@code load()} / {@code recallByKeyword()} 每次都重新读文件。进程内任何一层缓存都会让「Agent 刚记完、下一轮看不见」，
 * 而这正是记忆最容易被用错的地方。
 *
 * <p><b>IO 不过 {@code Sandbox.enforce}</b>：文件路径由本类在构造时定死（工作区根下 {@code memory/MEMORY.md}），模型无法把它指向别处，
 * 不构成越界面。技术方案 §6.7 点名的沙箱范围只有 FileTools / ShellTools / HttpTools。
 */
public class MarkdownMemoryStore implements LongTermMemoryStore {

  /** 核心记忆区 header。人手改文件时按它认区块，故形状一旦交付就不得改。 */
  private static final String CORE_HEADER = "## 核心记忆";

  /** 归档记忆区 header。 */
  private static final String ARCHIVE_HEADER = "## 归档记忆";

  /** 条目前的日期标记形状：只到日，不带时分秒（记忆是「哪天记的」，不是「几点几分记的」）。 */
  private static final String DATE_PATTERN = "- [%s] ";

  /**
   * 归档区注入 prompt 时的字符上限，超出只留尾部。
   *
   * <p>是<b>代码常量不是配置项</b>：口径（只裁归档、超出才裁、保留尾部）不可省，数值本身没什么可调的。取 4000 是因为它约等于上下文窗口里
   * 一段「够用但不喧宾夺主」的记忆量——核心区永远全量，故真正的兜底在核心区。
   */
  static final int MAX_ARCHIVE_CHARS = 4000;

  /** 记忆所在目录，构造时定死。 */
  private final Path memoryDir;

  /** 记忆文件路径，构造时定死。 */
  private final Path memoryFile;

  /**
   * @param oryxosDir 工作区目录（即 {@code .oryxos} 本身），本档只认它下面的 {@code memory/MEMORY.md}；<b>MUST NOT 再叠一层
   *     {@code .oryxos}</b>
   */
  public MarkdownMemoryStore(Path oryxosDir) {
    this.memoryDir = oryxosDir.resolve("memory");
    this.memoryFile = memoryDir.resolve("MEMORY.md");
  }

  @Override
  public void append(String content, MemoryScope scope) {
    if (content == null || content.isBlank()) {
      // 一条空记忆不是记忆：写进去只会让文件多一行光秃秃的日期
      return;
    }
    Sections sections = readSections();
    List<String> target = scope == MemoryScope.CORE ? sections.core() : sections.archive();
    // 正文里的换行压成空格：一条记忆必须占一行，否则下一次读回来的条目数对不上
    target.add(DATE_PATTERN.formatted(LocalDate.now()) + content.replaceAll("\\R+", " "));
    write(sections);
  }

  @Override
  public String load() {
    Sections sections = readSections();
    if (sections.core().isEmpty() && sections.archive().isEmpty()) {
      // 空记忆整条不注入 prompt——MUST NOT 让一段只有标题的空壳占上下文（FR-014 / SC-007）
      return "";
    }
    String archive = String.join("\n", sections.archive());
    if (archive.length() > MAX_ARCHIVE_CHARS) {
      archive = archive.substring(archive.length() - MAX_ARCHIVE_CHARS);
    }
    StringBuilder text = new StringBuilder();
    text.append(CORE_HEADER).append('\n');
    if (!sections.core().isEmpty()) {
      text.append(String.join("\n", sections.core())).append('\n');
    }
    text.append('\n').append(ARCHIVE_HEADER).append('\n');
    if (!archive.isEmpty()) {
      text.append(archive).append('\n');
    }
    return text.toString().strip();
  }

  @Override
  public List<String> recallByKeyword(String keyword) {
    if (keyword == null || keyword.isBlank()) {
      return List.of();
    }
    // 跨档统一不区分大小写：这里用大小写归一，SQLite 档用 LOWER(...) LIKE LOWER(...)
    String needle = keyword.toLowerCase(Locale.ROOT);
    return readSections().archive().stream()
        .filter(entry -> entry.toLowerCase(Locale.ROOT).contains(needle))
        .toList();
  }

  @Override
  public MemoryCapability capabilities() {
    return MemoryCapability.KEYWORD;
  }

  @Override
  public List<String> archivalEntries() {
    // 全量、不受 load() 的截断影响：截断只决定「注入上下文的那一份」，否则记忆会「存了但查不到」
    return List.copyOf(readSections().archive());
  }

  /**
   * 解析出的文件结构。
   *
   * <p>{@code preamble} 是第一个 header <b>之前</b>的内容——它不是记忆（不参与 {@code load()}），但<b>必须原样写回</b>：
   * 这个档的卖点就是「人可以直接改这个文件」，顺手把人写的前言删掉等于把这个卖点砸了。
   *
   * <p>用 {@code List}（可变）而不是直接返回不可变集合：{@code append} 要往对应区块追加一行后再整体写回，中间那次修改不该惊动磁盘。
   */
  private record Sections(List<String> preamble, List<String> core, List<String> archive) {}

  private Sections readSections() {
    List<String> preamble = new ArrayList<>();
    List<String> core = new ArrayList<>();
    List<String> archive = new ArrayList<>();
    if (!Files.exists(memoryFile)) {
      return new Sections(preamble, core, archive);
    }
    List<String> current = null;
    try {
      for (String line : Files.readAllLines(memoryFile, StandardCharsets.UTF_8)) {
        String trimmed = line.strip();
        if (CORE_HEADER.equals(trimmed)) {
          current = core;
        } else if (ARCHIVE_HEADER.equals(trimmed)) {
          current = archive;
        } else if (!trimmed.isEmpty()) {
          // 还没遇到任何 header 的行归前言：两个 header 都缺时整份文件都不算记忆，MUST NOT 把正文当核心记忆
          (current == null ? preamble : current).add(trimmed);
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException("读长期记忆文件失败：" + memoryFile, e);
    }
    return new Sections(preamble, core, archive);
  }

  private void write(Sections sections) {
    StringBuilder text = new StringBuilder();
    for (String line : sections.preamble()) {
      text.append(line).append('\n');
    }
    if (!sections.preamble().isEmpty()) {
      text.append('\n');
    }
    text.append(CORE_HEADER).append('\n');
    for (String entry : sections.core()) {
      text.append(entry).append('\n');
    }
    text.append('\n').append(ARCHIVE_HEADER).append('\n');
    for (String entry : sections.archive()) {
      text.append(entry).append('\n');
    }
    try {
      Files.createDirectories(memoryDir);
      Files.writeString(
          memoryFile,
          text.toString(),
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING,
          StandardOpenOption.WRITE);
    } catch (IOException e) {
      throw new UncheckedIOException("写长期记忆文件失败：" + memoryFile, e);
    }
  }
}
