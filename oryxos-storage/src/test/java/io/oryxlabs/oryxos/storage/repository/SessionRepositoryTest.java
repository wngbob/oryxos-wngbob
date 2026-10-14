package io.oryxlabs.oryxos.storage.repository;

import static org.assertj.core.api.Assertions.assertThat;

import io.oryxlabs.oryxos.storage.entity.SessionEntity;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * US2 回归测试：手工脚本建得出 {@code sessions} 表、存得进、读得回，且**真的落了盘**。
 *
 * <p><b>用真 SQLite、不用 H2</b>：与 {@link LlmCallRepositoryTest} 同理——H2 的类型系统与 SQLite 不同， 换内存库测出来的绿是假绿。
 *
 * <p><b>建表走手工脚本</b>：{@code db/schema.sql} 是表结构的唯一真相源（FR-011）。Hibernate 自动建表被显式关掉，
 * 否则脚本写错了测试也会「通过」，而那正是本节最需要防的事故。
 *
 * <p><b>为什么整个类关掉测试事务（{@code NOT_SUPPORTED}）</b>：{@code @DataJpaTest} 默认把每个用例包在一个最终回滚的事务里，
 * 未提交的数据**别的连接根本看不见**——那样「模拟重启」读到的永远是空，测试会绿得毫无意义。关掉之后每次 {@code repository.save} 自行提交，独立 JDBC
 * 连接才读得到，才证明得了数据真的落到文件上（research.md D5）。
 *
 * <p><b>本类看不见 {@code Session}</b>：{@code oryxos-storage} MUST NOT 依赖 {@code oryxos-core}（依赖倒置，
 * CLAUDE.md 模块表）。故本类断言的是「<b>传进实体、落在列上的是同一个标识</b>」——它防的是 {@code @Id} 上被加上
 * 生成策略之类把调用方给的标识换掉的改动；「{@code Session.id()} 就等于这个标识」由 core 侧的 {@code
 * SessionServiceTest.sessionIdIsJoinedFromTripleInOnePlace} 钉住，两半合起来才是 FR-022 的持久化侧。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SessionRepositoryTest {

  /** 手工建表脚本，位于 main resources，测试期从 classpath 读取。 */
  private static final String SCHEMA_SCRIPT = "/db/schema.sql";

  private static final Path DB_FILE = createDatabaseFile();

  /** 会话标识形如 {@code channel:user:profile}，与 CLI 的拼接口径一致。 */
  private static final String SESSION_ID = "cli:local:weather";

  /**
   * 「模拟重启」用例单独用一个标识。
   *
   * <p>本类关掉了测试事务，写入是<b>真提交</b>、用例之间互不隔离。共用同一个主键会让用例互相依赖（谁先跑谁插入、 后跑的靠 JPA 的 merge 变成 UPDATE
   * 才不炸）——各用各的标识，每个用例自己成立。
   */
  private static final String RESTART_SESSION_ID = "cli:local:weather-restart";

  /**
   * 落盘形状的一条样本：含中文与换行转义，用来确认列上没有被截断或转码。
   *
   * <p>这里手写 JSON 而不是调 {@code MessageJson}：那是 core 的包级私有类，本模块看不见——本类要测的是 <b>这一列能不能原样存住、原样读回</b>，与
   * JSON 内部结构无关。
   */
  private static final String MESSAGES_JSON =
      "[{\"role\":\"user\",\"content\":\"今天天气怎么样？\",\"timestamp\":\"2026-10-13T09:30:00Z\"},"
          + "{\"role\":\"assistant\",\"content\":\"晴，26 度。\\n记得带伞。\","
          + "\"timestamp\":\"2026-10-13T09:30:01Z\"}]";

  @Autowired private SessionRepository repository;

  private static Path createDatabaseFile() {
    try {
      return Files.createTempDirectory("oryxos-session-it").resolve("oryxos.db");
    } catch (IOException e) {
      throw new UncheckedIOException("无法创建测试用 SQLite 库文件", e);
    }
  }

  private static String jdbcUrl() {
    return "jdbc:sqlite:" + DB_FILE;
  }

  @DynamicPropertySource
  static void sqliteProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", SessionRepositoryTest::jdbcUrl);
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
            SessionRepositoryTest.class.getResourceAsStream(SCHEMA_SCRIPT),
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

  private static SessionEntity newRow(String sessionId, String messagesJson) {
    SessionEntity entity = new SessionEntity();
    entity.setSessionId(sessionId);
    entity.setProfileName("weather");
    entity.setChannel("cli");
    entity.setUserId("local");
    entity.setMessagesJson(messagesJson);
    entity.setStatus("active");
    entity.setCreatedAt(LocalDateTime.of(2026, 10, 13, 9, 30));
    entity.setLastActiveAt(LocalDateTime.of(2026, 10, 13, 9, 30, 1));
    entity.setArchivedAt(null);
    return entity;
  }

  private static List<String> columnNames(String table) throws SQLException {
    List<String> names = new ArrayList<>();
    try (Connection connection = DriverManager.getConnection(jdbcUrl());
        Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
      while (rs.next()) {
        names.add(rs.getString("name"));
      }
    }
    return names;
  }

  @Test
  @DisplayName("手工脚本建出的 sessions 表列齐备：A1 的九列一个不少、一个不多")
  void schemaScriptCreatesSessionColumns() throws SQLException {
    assertThat(columnNames("sessions"))
        .containsExactlyInAnyOrder(
            "session_id",
            "profile_name",
            "channel",
            "user_id",
            "messages_json",
            "status",
            "created_at",
            "last_active_at",
            "archived_at");
  }

  @Test
  @DisplayName("经 Repository 存取往返：九列逐字保真")
  void persistsAndReadsBackEveryColumn() {
    repository.save(newRow(SESSION_ID, MESSAGES_JSON));

    SessionEntity reloaded = repository.findById(SESSION_ID).orElseThrow();

    assertThat(reloaded.getSessionId()).isEqualTo(SESSION_ID);
    assertThat(reloaded.getProfileName()).isEqualTo("weather");
    assertThat(reloaded.getChannel()).isEqualTo("cli");
    assertThat(reloaded.getUserId()).isEqualTo("local");
    assertThat(reloaded.getMessagesJson()).isEqualTo(MESSAGES_JSON);
    assertThat(reloaded.getStatus()).isEqualTo("active");
    assertThat(reloaded.getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 10, 13, 9, 30));
    assertThat(reloaded.getLastActiveAt()).isEqualTo(LocalDateTime.of(2026, 10, 13, 9, 30, 1));
    assertThat(reloaded.getArchivedAt()).isNull();
  }

  @Test
  @DisplayName("模拟重启：独立 JDBC 连接直读，session_id 与 messages_json 与写入时逐字一致")
  void survivesRestartAndKeepsRawColumns() throws SQLException {
    repository.save(newRow(RESTART_SESSION_ID, MESSAGES_JSON));

    // 新建连接 = 进程重启：与写入侧不共享 EntityManager、一级缓存或任何 Spring 上下文
    try (Connection connection = DriverManager.getConnection(jdbcUrl());
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT session_id, messages_json FROM sessions WHERE session_id = ?")) {
      statement.setString(1, RESTART_SESSION_ID);
      try (ResultSet rs = statement.executeQuery()) {
        assertThat(rs.next()).isTrue();
        // ④ 标识不是自增、也没被生成策略换掉：调用方给什么，列上就是什么
        assertThat(rs.getString("session_id")).isEqualTo(RESTART_SESSION_ID);
        // ③ 中文与换行转义原样躺在列上，没被截断也没被转码
        assertThat(rs.getString("messages_json")).isEqualTo(MESSAGES_JSON);
      }
    }
  }

  @Test
  @DisplayName("按标识查不到：得 Optional.empty()，不抛异常")
  void findByIdReturnsEmptyWhenAbsent() {
    assertThat(repository.findById("cli:local:nobody")).isEmpty();
  }
}
