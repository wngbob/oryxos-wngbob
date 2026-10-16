package io.oryxlabs.oryxos.memory.store;

import io.oryxlabs.oryxos.storage.entity.MemoryEntry;
import io.oryxlabs.oryxos.storage.repository.MemoryEntryRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;

/**
 * 结构化档：记忆按条进 {@code memory_entries} 表，与 {@code sessions} / 审计表同库。
 *
 * <p><b>什么时候该换到这一档</b>：记忆条数涨到「人翻 Markdown 已经翻不动」的时候。仍然是零外部依赖（复用已有的 SQLite）， 但检索从「读整个文件扫行」变成一条
 * SQL，截断从「裁字符串」变成 {@code LIMIT}。
 *
 * <p><b>截断手段换了，契约没换</b>：归档区取最近 100 条靠 {@code LIMIT}，而核心区的查询<b>不带任何分页参数</b>—— 「核心记忆永不被截断」在 SQL
 * 层是「那条语句里根本没有 LIMIT」，而不是靠调用方自觉。
 *
 * <p><b>表不由本类建</b>：与 {@code sessions} / 审计表同口径，表的存在性归工作区初始化（{@code oryxos init} 无条件重跑建表脚本）。
 * 忘了初始化会撞上 {@code no such table}，本类把它包成带指引的明确消息——底层报错直接抛给用户，用户只会看到一句 SQL 错误。
 *
 * <p><b>检索覆盖归档区全量</b>，不受 {@code load()} 的 {@code LIMIT} 影响：截断只决定「注入上下文的那一份」，
 * 否则被裁掉的记忆就变成「存了但查不到」，Agent 会以为这件事从没记过。
 */
public class SqliteMemoryStore implements LongTermMemoryStore {

  /** 归档区注入 prompt 时保留的最近条数；核心区不受它影响。 */
  static final int MAX_ARCHIVE_ROWS = 100;

  private static final String SCOPE_CORE = "CORE";

  private static final String SCOPE_ARCHIVAL = "ARCHIVAL";

  /**
   * SQLite 在表不存在时给出的报错片段。
   *
   * <p>按<b>消息文本</b>认这个错误，而不是按异常类型：SQLite JDBC 把「表不存在」和别的 SQL 错误都包成同一类 {@code
   * DataAccessException}，类型上分不开。代价是这条判据跟着 SQLite 的文案走——真换了数据库要一起改， 故写成常量集中一处，而不是散在判断里。
   */
  private static final String NO_SUCH_TABLE = "no such table";

  private final MemoryEntryRepository repository;

  public SqliteMemoryStore(MemoryEntryRepository repository) {
    this.repository = Objects.requireNonNull(repository, "记忆条目 Repository 不能为空");
  }

  @Override
  public void append(String content, MemoryScope scope) {
    if (content == null || content.isBlank()) {
      return;
    }
    MemoryEntry entry = new MemoryEntry();
    // 实体层收的是裸字符串而不是枚举：oryxos-storage 不依赖任何内部模块，分区口径归本类
    entry.setScope(scope == MemoryScope.CORE ? SCOPE_CORE : SCOPE_ARCHIVAL);
    entry.setContent(content);
    entry.setCreatedAt(LocalDateTime.now());
    guard(() -> repository.save(entry));
  }

  @Override
  public String load() {
    List<String> core = contentsOf(guard(() -> repository.findByScopeOrderByIdAsc(SCOPE_CORE)));
    // 降序取最近 N 条（LIMIT 在 SQL 层生效），再翻回时间正序——降序是为了让 LIMIT 取到「最近的」
    List<String> archive =
        contentsOf(
            guard(
                () ->
                    repository.findByScopeOrderByIdDesc(
                        SCOPE_ARCHIVAL, PageRequest.of(0, MAX_ARCHIVE_ROWS))));
    java.util.Collections.reverse(archive);
    if (core.isEmpty() && archive.isEmpty()) {
      return "";
    }
    StringBuilder text = new StringBuilder("## 核心记忆\n");
    if (!core.isEmpty()) {
      text.append(String.join("\n", core)).append('\n');
    }
    text.append("\n## 归档记忆\n");
    if (!archive.isEmpty()) {
      text.append(String.join("\n", archive)).append('\n');
    }
    return text.toString().strip();
  }

  @Override
  public List<String> recallByKeyword(String keyword) {
    if (keyword == null || keyword.isBlank()) {
      return List.of();
    }
    // 大小写不敏感由 JPQL 的 LOWER(...) LIKE LOWER(...) 保证，不靠驱动默认行为——三档口径要一致
    return contentsOf(guard(() -> repository.searchArchival("%" + keyword + "%")));
  }

  @Override
  public MemoryCapability capabilities() {
    return MemoryCapability.KEYWORD;
  }

  @Override
  public List<String> archivalEntries() {
    List<String> entries =
        contentsOf(guard(() -> repository.findByScopeOrderByIdAsc(SCOPE_ARCHIVAL)));
    return List.copyOf(entries);
  }

  private static List<String> contentsOf(List<MemoryEntry> rows) {
    List<String> contents = new ArrayList<>(rows.size());
    for (MemoryEntry row : rows) {
      contents.add(row.getContent());
    }
    return contents;
  }

  /**
   * 把「表不存在」这个底层报错包成带指引的消息。
   *
   * <p><b>为什么不原样上抛</b>：{@code no such table: memory_entries} 对用户毫无指向性——他不知道要跑什么。 而这张表的建立只归工作区初始化，忘跑
   * {@code oryxos init} 是最常见的失败原因（老工作区尤其：库文件是跨升级保留的用户数据）。
   */
  private static <T> T guard(RepositoryCall<T> call) {
    try {
      return call.run();
    } catch (DataAccessException e) {
      String message = e.getMostSpecificCause().getMessage();
      if (message != null && message.contains(NO_SUCH_TABLE)) {
        throw new IllegalStateException(
            "记忆表 memory_entries 不存在，请先运行 `oryxos init` 初始化工作区（它会把建表脚本重跑一遍）", e);
      }
      throw e;
    }
  }

  /** 一次 Repository 调用；用它把「表不存在」的包装收在一处。 */
  @FunctionalInterface
  private interface RepositoryCall<T> {

    /**
     * 执行这次调用。
     *
     * @return 调用结果
     */
    T run();
  }
}
