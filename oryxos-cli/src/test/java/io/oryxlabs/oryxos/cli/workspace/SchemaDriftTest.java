package io.oryxlabs.oryxos.cli.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link SchemaDrift} 的守点（2026-10-20 用户批准新增）。
 *
 * <p><b>为什么本类必须存在</b>：{@code init} 此前对旧工作区只跑 {@code CREATE TABLE IF NOT EXISTS}，旧表缺的列永远补不上；补列是
 * <b>写用户数据</b>的操作，判据不能靠「跑一遍看起来没报错」——下面六条把「补什么、不补什么、什么时候停」逐条钉死。
 *
 * <p><b>最要紧的两条</b>：①「旧行数据原样还在」（补列是加性操作，不能顺手重建表）；②「多出来的列 / 不一致的类型一律不碰」（本类的边界是 <b>加性补齐</b>，不是自动改表）。
 */
class SchemaDriftTest {

  /** 第 16 节之前的 {@code llm_calls} 形状：没有 {@code success} / {@code error_message}。 */
  private static final String LEGACY_LLM_CALLS =
      """
      CREATE TABLE llm_calls (
          id                INTEGER PRIMARY KEY AUTOINCREMENT,
          session_id        VARCHAR(128),
          provider          VARCHAR(64),
          model             VARCHAR(128),
          prompt_tokens     INTEGER,
          completion_tokens INTEGER,
          total_tokens      INTEGER,
          duration_ms       BIGINT,
          created_at        TIMESTAMP
      )
      """;

  @TempDir Path tempDir;

  private WorkspaceDb open() {
    return WorkspaceDb.open(tempDir.resolve("drift.db"));
  }

  private static List<String> columns(WorkspaceDb database, String table) {
    return database.query("PRAGMA table_info(" + table + ")", SchemaDriftTest::name);
  }

  private static String name(ResultSet resultSet) throws SQLException {
    return resultSet.getString("name");
  }

  @Test
  @DisplayName("旧表缺列时补上，且原有行数据一个字节不动 —— llm_calls 补 success / error_message")
  void addsMissingColumnsAndKeepsExistingRows() {
    try (WorkspaceDb database = open()) {
      database.execute(LEGACY_LLM_CALLS);
      database.execute("INSERT INTO llm_calls (id, provider) VALUES (1, 'deepseek')");

      List<String> changes = database.applySchemaDrift();

      assertThat(changes).anyMatch(change -> change.contains("llm_calls.success"));
      assertThat(changes).anyMatch(change -> change.contains("llm_calls.error_message"));
      assertThat(columns(database, "llm_calls"))
          .contains("success", "error_message", "provider", "created_at");
      assertThat(
              database.query(
                  "SELECT provider FROM llm_calls WHERE id = 1", rs -> rs.getString("provider")))
          .containsExactly("deepseek");
    }
  }

  @Test
  @DisplayName("补列是幂等的 —— 第二次运行没有任何改动")
  void applyingTwiceReportsNothingTheSecondTime() {
    try (WorkspaceDb database = open()) {
      database.execute(LEGACY_LLM_CALLS);

      assertThat(database.applySchemaDrift()).isNotEmpty();
      assertThat(database.applySchemaDrift()).isEmpty();
    }
  }

  @Test
  @DisplayName("全新库不归补列管 —— 没有表时什么都不做，建表仍由建表脚本负责")
  void freshDatabaseIsLeftToTheSchemaScript() {
    try (WorkspaceDb database = open()) {
      assertThat(database.applySchemaDrift()).isEmpty();
      assertThat(columns(database, "sessions")).isEmpty();

      database.runSchemaScript();

      assertThat(columns(database, "sessions")).contains("session_id", "messages_json", "status");
    }
  }

  @Test
  @DisplayName("类型不一致只报告、不修改 —— SQLite 转不了列类型，硬改就是重建表")
  void reportsTypeMismatchWithoutAlteringIt() {
    try (WorkspaceDb database = open()) {
      database.execute(
          "CREATE TABLE memory_entries (id INTEGER PRIMARY KEY AUTOINCREMENT, "
              + "scope VARCHAR(999) NOT NULL, content TEXT NOT NULL, created_at TIMESTAMP NOT NULL)");

      List<String> changes = database.applySchemaDrift();

      assertThat(changes)
          .anyMatch(change -> change.contains("类型不一致") && change.contains("memory_entries.scope"));
      assertThat(columnTypes(database, "memory_entries")).containsEntry("scope", "VARCHAR(999)");
    }
  }

  @Test
  @DisplayName("库里多出来的列不碰 —— 本类的边界是加性补齐，不是把表改成脚本的样子")
  void leavesUnknownColumnsAlone() {
    try (WorkspaceDb database = open()) {
      database.execute(
          "CREATE TABLE memory_entries (id INTEGER PRIMARY KEY AUTOINCREMENT, "
              + "scope VARCHAR(16) NOT NULL, content TEXT NOT NULL, created_at TIMESTAMP NOT NULL, "
              + "extra TEXT)");
      database.execute(
          "INSERT INTO memory_entries (scope, content, created_at, extra) VALUES ('CORE', 'x', CURRENT_TIMESTAMP, 'keep-me')");

      assertThat(database.applySchemaDrift()).isEmpty();
      assertThat(columns(database, "memory_entries")).contains("extra");
      assertThat(columnTypes(database, "memory_entries")).containsEntry("extra", "TEXT");
    }
  }

  @Test
  @DisplayName("缺主键列时明确报错 —— ALTER TABLE 加不了主键，猜一个补法等于制造半个主键")
  void refusesToGuessAMissingPrimaryKey() {
    try (WorkspaceDb database = open()) {
      database.execute("CREATE TABLE notify_channels (url VARCHAR(1024))");

      assertThatThrownBy(database::applySchemaDrift)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("notify_channels")
          .hasMessageContaining("name")
          .hasMessageContaining("主键");
    }
  }

  private static Map<String, String> columnTypes(WorkspaceDb database, String table) {
    return database
        .query(
            "PRAGMA table_info(" + table + ")",
            rs -> Map.entry(rs.getString("name"), rs.getString("type")))
        .stream()
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
  }
}
