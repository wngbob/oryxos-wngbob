package io.oryxlabs.oryxos.storage.repository;

import static org.assertj.core.api.Assertions.assertThat;

import io.oryxlabs.oryxos.storage.entity.LlmCall;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * US4 回归测试：审计表真的建得出来、两列真的存得住。
 *
 * <p><b>用真 SQLite、不用 H2</b>：H2 与 SQLite 的类型系统不同（本表的 {@code id} 依赖 SQLite 的 rowid 语义），
 * 换成内存库测出来的绿是假绿——真跑起来照样挂。
 *
 * <p><b>建表走手工脚本</b>：{@code db/schema.sql} 是表结构的唯一真相源，测试先执行它再存取（FR-011）。Hibernate
 * 的自动建表被显式关掉，否则脚本写错了测试也会"通过"，而那正是本节最需要防的事故。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class LlmCallRepositoryTest {

  /** 手工建表脚本，位于 main resources，测试期从 classpath 读取。 */
  private static final String SCHEMA_SCRIPT = "/db/schema.sql";

  private static final Path DB_FILE = createDatabaseFile();

  @Autowired private LlmCallRepository repository;

  private static Path createDatabaseFile() {
    try {
      return Files.createTempDirectory("oryxos-llm-call-it").resolve("oryxos.db");
    } catch (IOException e) {
      throw new UncheckedIOException("无法创建测试用 SQLite 库文件", e);
    }
  }

  private static String jdbcUrl() {
    return "jdbc:sqlite:" + DB_FILE;
  }

  @DynamicPropertySource
  static void sqliteProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", LlmCallRepositoryTest::jdbcUrl);
    registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
    registry.add(
        "spring.jpa.database-platform", () -> "org.hibernate.community.dialect.SQLiteDialect");
    // 表结构只认手工脚本，框架一律不许插手（FR-011）
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
  }

  /** 先跑手工脚本建表，再让测试碰库。 */
  @BeforeAll
  static void applySchemaScript() throws SQLException, IOException {
    String script;
    try (InputStream in =
        Objects.requireNonNull(
            LlmCallRepositoryTest.class.getResourceAsStream(SCHEMA_SCRIPT),
            "classpath 上找不到建表脚本 " + SCHEMA_SCRIPT)) {
      script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    try (Connection connection = DriverManager.getConnection(jdbcUrl());
        Statement statement = connection.createStatement()) {
      for (String sql : script.split(";")) {
        if (!sql.isBlank()) {
          statement.execute(sql);
        }
      }
    }
  }

  private static LlmCall newCall(
      String sessionId, boolean success, String errorMessage, LocalDateTime createdAt) {
    LlmCall call = new LlmCall();
    call.setSessionId(sessionId);
    call.setProvider("deepseek");
    call.setModel("deepseek-chat");
    call.setPromptTokens(11);
    call.setCompletionTokens(7);
    call.setTotalTokens(18);
    call.setDurationMs(320L);
    call.setSuccess(success);
    call.setErrorMessage(errorMessage);
    call.setCreatedAt(createdAt);
    return call;
  }

  private List<String> columnNames() throws SQLException {
    List<String> names = new ArrayList<>();
    try (Connection connection = DriverManager.getConnection(jdbcUrl());
        Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery("PRAGMA table_info(llm_calls)")) {
      while (rs.next()) {
        names.add(rs.getString("name"));
      }
    }
    return names;
  }

  @Test
  @DisplayName("手工脚本建出的表含 success 与 error_message 两列")
  void schemaScriptCreatesAuditColumns() throws SQLException {
    assertThat(columnNames()).contains("success", "error_message");
  }

  @Test
  @DisplayName("写入并读回：成败两条记录的 success 与 error_message 读写一致")
  void persistsAndReadsBackAuditColumns() {
    LocalDateTime base = LocalDateTime.of(2026, 10, 13, 9, 30);
    LlmCall ok = repository.save(newCall("s-1", true, null, base));
    LlmCall failed =
        repository.save(newCall("s-1", false, "DeepSeek 调用超时: timeout", base.plusSeconds(1)));

    LlmCall reloadedOk = repository.findById(ok.getId()).orElseThrow();
    LlmCall reloadedFailed = repository.findById(failed.getId()).orElseThrow();

    assertThat(reloadedOk.getSuccess()).isTrue();
    assertThat(reloadedOk.getErrorMessage()).isNull();
    assertThat(reloadedOk.getTotalTokens()).isEqualTo(18);
    // 失败也必须留痕：只记成功不记失败，等于一次真实事故在系统里完全没留痕迹（章程原则 VI）
    assertThat(reloadedFailed.getSuccess()).isFalse();
    assertThat(reloadedFailed.getErrorMessage()).isEqualTo("DeepSeek 调用超时: timeout");
  }
}
