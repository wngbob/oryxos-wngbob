package io.oryxlabs.oryxos.memory.store;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 进程内替身档：把记忆放在两个 {@code List} 里，进程一退全没。
 *
 * <p><b>它是测试期替身，不是第四个可选后端档</b>——这一条必须写在这里，否则将来一定会被误当成一档：它<b>不在</b> {@code oryxos.memory.backend}
 * 的取值里（只有 {@code markdown} / {@code sqlite} / {@code mem0} 三个），也<b>没有任何 {@code @Bean}
 * 声明它</b>，装配处选不中它。
 *
 * <p><b>它存在的理由是「契约测试要三行参数」</b>：{@code MemoryStoreContractTest} 要证明「同一套断言对三档实现都成立」， 而 Mem0 真类走的是
 * {@code MockRestServiceServer}（单测级、有严格期望序列），不适合当参数。于是用一个行为守约的内存替身占住第三行—— 它测的是「Mem0
 * 这一档有没有守规矩」，真实报文由 {@code Mem0MemoryStoreTest} 单独测，两者分工不互相替代。
 *
 * <p>行为刻意对齐 {@code MarkdownMemoryStore}（条目行带 {@code - [YYYY-MM-DD]} 前缀、归档区保留最近 100 条）， 这样它替换掉
 * Markdown 档当参数时，同一套断言的含义不变。
 */
public class InMemoryMemoryStore implements LongTermMemoryStore {

  /** 归档区保留的最近条数，与 SQLite 档同值。 */
  static final int MAX_ARCHIVE_ROWS = 100;

  private static final String DATE_PATTERN = "- [%s] ";

  private final List<String> core = new ArrayList<>();

  private final List<String> archive = new ArrayList<>();

  @Override
  public void append(String content, MemoryScope scope) {
    if (content == null || content.isBlank()) {
      return;
    }
    String entry = DATE_PATTERN.formatted(LocalDate.now()) + content.replaceAll("\\R+", " ");
    (scope == MemoryScope.CORE ? core : archive).add(entry);
  }

  @Override
  public String load() {
    if (core.isEmpty() && archive.isEmpty()) {
      return "";
    }
    // 核心区全量；归档区只取最近 MAX_ARCHIVE_ROWS 条——截断物理上碰不到核心那个 List
    List<String> recent =
        archive.size() > MAX_ARCHIVE_ROWS
            ? archive.subList(archive.size() - MAX_ARCHIVE_ROWS, archive.size())
            : archive;
    StringBuilder text = new StringBuilder("## 核心记忆\n");
    if (!core.isEmpty()) {
      text.append(String.join("\n", core)).append('\n');
    }
    text.append("\n## 归档记忆\n");
    if (!recent.isEmpty()) {
      text.append(String.join("\n", recent)).append('\n');
    }
    return text.toString().strip();
  }

  @Override
  public List<String> recallByKeyword(String keyword) {
    if (keyword == null || keyword.isBlank()) {
      return List.of();
    }
    String needle = keyword.toLowerCase(Locale.ROOT);
    // 搜的是 archive 全量，不是 load() 截断后的那一份：截断只决定「注入上下文的那一份」
    return archive.stream()
        .filter(entry -> entry.toLowerCase(Locale.ROOT).contains(needle))
        .toList();
  }

  @Override
  public MemoryCapability capabilities() {
    return MemoryCapability.KEYWORD;
  }

  @Override
  public List<String> archivalEntries() {
    return List.copyOf(archive);
  }

  @Override
  public MemoryPartition partition() {
    // 与 load() 同一段截断口径：只留最近 MAX_ARCHIVE_ROWS 条，核心区一个元素都不动
    boolean truncated = archive.size() > MAX_ARCHIVE_ROWS;
    List<String> recent =
        truncated ? archive.subList(archive.size() - MAX_ARCHIVE_ROWS, archive.size()) : archive;
    return new MemoryPartition(String.join("\n", core), String.join("\n", recent), truncated);
  }
}
