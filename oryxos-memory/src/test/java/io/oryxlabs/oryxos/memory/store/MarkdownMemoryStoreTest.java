package io.oryxlabs.oryxos.memory.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Markdown 档的<b>档内细节</b>：字符串截断的边界、两个区块 header 的解析、日期前缀的形状。
 *
 * <p>这些是「三档统一」的契约测试照不到的角落——契约只问「核心区还在不在」，本类问的是「用字符串裁的时候，边界那一刀切在哪」。
 */
class MarkdownMemoryStoreTest {

  /** 一个假的 oryxos 目录（本档只认它下面的 {@code memory/MEMORY.md}）。 */
  @TempDir Path oryxosDir;

  private static final String CORE_HEADER = "## 核心记忆";

  private static final String ARCHIVE_HEADER = "## 归档记忆";

  /** 日期前缀的形状：只到日，不带时分秒。 */
  private static final String DATE_PREFIX = "- \\[\\d{4}-\\d{2}-\\d{2}] ";

  private Path memoryFile() {
    return oryxosDir.resolve("memory").resolve("MEMORY.md");
  }

  /**
   * 实测一行渲染出来有多长。
   *
   * <p><b>为什么不把日期格式写死在测试里</b>：那样测的是「今天是几号」而不是截断逻辑。先写一条一字正文，量出前缀长度，后面所有边界用例都以它为基准。
   */
  private int renderedPrefixLength() {
    MarkdownMemoryStore probe = new MarkdownMemoryStore(oryxosDir.resolve("probe"));
    probe.append("Z", MemoryScope.ARCHIVAL);
    return probe.archivalEntries().get(0).length() - 1;
  }

  @Test
  @DisplayName("文件还不存在时 load() 返回空串——不抛异常、不返回 null")
  void loadOnMissingFile_returnsEmptyString() {
    assertThat(new MarkdownMemoryStore(oryxosDir).load()).isEmpty();
  }

  @Test
  @DisplayName("只有两个 header、一条记忆都没有时 load() 返回空串——MUST NOT 吐出 header 残渣")
  void loadWithHeadersButNoEntries_returnsEmptyString() throws IOException {
    // 这正是 `oryxos init` 刚建出来的那个文件：两个 header，两区皆空
    Files.createDirectories(memoryFile().getParent());
    Files.writeString(
        memoryFile(), CORE_HEADER + "\n\n" + ARCHIVE_HEADER + "\n", StandardCharsets.UTF_8);

    MarkdownMemoryStore memory = new MarkdownMemoryStore(oryxosDir);

    // 空记忆整条不注入 prompt（FR-014 / SC-007）——不是注入一段只有标题的空壳
    assertThat(memory.load()).isEmpty();
    assertThat(memory.archivalEntries()).isEmpty();
  }

  @Test
  @DisplayName("写入后 load() 带两个 header，正文落在对应区块下")
  void loadRendersBothHeadersWithEntriesUnderTheRightOne() {
    MarkdownMemoryStore memory = new MarkdownMemoryStore(oryxosDir);
    memory.append("核心的一条", MemoryScope.CORE);
    memory.append("归档的一条", MemoryScope.ARCHIVAL);

    String loaded = memory.load();

    assertThat(loaded).startsWith(CORE_HEADER);
    assertThat(loaded).contains(ARCHIVE_HEADER);
    // 核心那条必须出现在归档 header 之前——区块顺序不能反
    assertThat(loaded.indexOf("核心的一条")).isLessThan(loaded.indexOf(ARCHIVE_HEADER));
    assertThat(loaded.indexOf("归档的一条")).isGreaterThan(loaded.indexOf(ARCHIVE_HEADER));
  }

  @Test
  @DisplayName("条目行带 - [YYYY-MM-DD] 日期前缀（只到日，不带时分秒）")
  void entriesCarryDateOnlyPrefix() {
    MarkdownMemoryStore memory = new MarkdownMemoryStore(oryxosDir);
    memory.append("今天学到的事", MemoryScope.ARCHIVAL);

    assertThat(memory.archivalEntries()).singleElement().asString().matches(DATE_PREFIX + "今天学到的事");
  }

  @Test
  @DisplayName("人手写的 MEMORY.md 能被解析：预置内容不丢，新写的追加在后面")
  void handwrittenSectionsAreParsedAndPreserved() throws IOException {
    Files.createDirectories(memoryFile().getParent());
    Files.writeString(
        memoryFile(),
        CORE_HEADER + "\n\n- 我是手写进文件的核心记忆\n\n" + ARCHIVE_HEADER + "\n\n- 我是手写进文件的归档记忆\n",
        StandardCharsets.UTF_8);

    MarkdownMemoryStore memory = new MarkdownMemoryStore(oryxosDir);
    memory.append("新追加的一条", MemoryScope.ARCHIVAL);

    String loaded = memory.load();
    // 预置的两条一条不少
    assertThat(loaded).contains("我是手写进文件的核心记忆").contains("我是手写进文件的归档记忆");
    // 新写的追加在归档区末尾
    assertThat(loaded).contains("新追加的一条");
    // 手写那一行没有日期前缀也照收——条目就是「归档区下的行」，不靠正则去认
    assertThat(memory.archivalEntries()).hasSize(2);
    assertThat(memory.archivalEntries().get(0)).isEqualTo("- 我是手写进文件的归档记忆");
    assertThat(memory.archivalEntries().get(1)).contains("新追加的一条");
  }

  @Test
  @DisplayName("两个 header 都缺时整份文件都不算记忆——MUST NOT 把正文当成核心记忆")
  void fileWithoutHeadersIsNotTreatedAsCoreMemory() throws IOException {
    Files.createDirectories(memoryFile().getParent());
    Files.writeString(memoryFile(), "# 我的笔记\n\n- 随手写的一行\n", StandardCharsets.UTF_8);

    MarkdownMemoryStore memory = new MarkdownMemoryStore(oryxosDir);

    assertThat(memory.load()).isEmpty();
    assertThat(memory.archivalEntries()).isEmpty();

    // 但人写的内容 MUST NOT 被写回时抹掉：这个档的卖点就是「人可以直接改这个文件」
    memory.append("新记的一条", MemoryScope.ARCHIVAL);
    String rewritten = Files.readString(memoryFile(), StandardCharsets.UTF_8);
    assertThat(rewritten).contains("我的笔记").contains("随手写的一行").contains("新记的一条");
  }

  @Test
  @DisplayName("归档区刚好等于阈值时不截断——超出才裁")
  void archivalAtExactlyTheThreshold_isNotTrimmed() {
    MarkdownMemoryStore memory = new MarkdownMemoryStore(oryxosDir);
    int bodyLength = MarkdownMemoryStore.MAX_ARCHIVE_CHARS - renderedPrefixLength();
    String body = "x".repeat(bodyLength);
    memory.append(body, MemoryScope.ARCHIVAL);

    // 整条一字不少
    assertThat(memory.load()).contains(body);
  }

  @Test
  @DisplayName("归档区超出阈值就裁——保留尾部，丢掉的是最早的开头")
  void archivalOverTheThreshold_isTrimmedFromTheHead() {
    MarkdownMemoryStore memory = new MarkdownMemoryStore(oryxosDir);
    // 正文比阈值还长一个字符：连日期前缀一起算必然超阈，故开头那个哨兵字符一定会被切掉
    String body = "S" + "x".repeat(MarkdownMemoryStore.MAX_ARCHIVE_CHARS);
    memory.append(body, MemoryScope.ARCHIVAL);

    String loaded = memory.load();

    // 哨兵没了（header 与日期前缀里都没有 S，故这一条断言是干净的）
    assertThat(loaded).doesNotContain("S");
    // 保留的是尾部
    assertThat(loaded).contains("x".repeat(100));
  }

  @Test
  @DisplayName("核心区不受归档区截断牵连——归档被裁到只剩尾巴，核心那条仍完整")
  void coreSectionIsUntouchedByArchivalTruncation() {
    MarkdownMemoryStore memory = new MarkdownMemoryStore(oryxosDir);
    memory.append("核心的定海神针", MemoryScope.CORE);
    for (int i = 0; i < 200; i++) {
      memory.append("归档流水 " + i, MemoryScope.ARCHIVAL);
    }

    String loaded = memory.load();

    assertThat(loaded).contains("核心的定海神针");
    assertThat(loaded).doesNotContain("归档流水 0");
    assertThat(loaded).contains("归档流水 199");
  }

  @Test
  @DisplayName("检索只搜归档区、不区分大小写，且不受截断影响——被裁掉的内容照样查得到")
  void recallSearchesWholeArchivalCaseInsensitively() {
    MarkdownMemoryStore memory = new MarkdownMemoryStore(oryxosDir);
    memory.append("核心区也提到了 Java", MemoryScope.CORE);
    for (int i = 0; i < 200; i++) {
      memory.append("归档提到 java 的第 " + i + " 条", MemoryScope.ARCHIVAL);
    }

    // 关键词用小写去搜大写写进库的内容
    var hits = memory.recallByKeyword("JAVA");

    // 核心区那条不参与检索
    assertThat(hits).noneMatch(hit -> hit.contains("核心区"));
    // 归档区全量命中，包括已被 load() 裁掉的最早那批（FR-009：存了就得查得到）
    assertThat(hits).hasSize(200);
    assertThat(hits).anyMatch(hit -> hit.contains("归档提到 java 的第 0 条"));
  }

  @Test
  @DisplayName("检索未命中返回空列表——不抛异常、不返回 null")
  void recallWithNoHit_returnsEmptyList() {
    MarkdownMemoryStore memory = new MarkdownMemoryStore(oryxosDir);
    memory.append("记了一条别的", MemoryScope.ARCHIVAL);

    assertThat(memory.recallByKeyword("绝不出现的词")).isEmpty();
  }

  @Test
  @DisplayName("capabilities() 恒为 KEYWORD")
  void capabilitiesAreKeyword() {
    MarkdownMemoryStore memory = new MarkdownMemoryStore(oryxosDir);

    assertThat(memory.capabilities()).isEqualTo(MemoryCapability.KEYWORD);
    assertThat(memory.capabilities()).isEqualTo(memory.capabilities());
  }
}
