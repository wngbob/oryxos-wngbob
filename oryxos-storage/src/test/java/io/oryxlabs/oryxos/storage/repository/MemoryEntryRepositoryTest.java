package io.oryxlabs.oryxos.storage.repository;

import static org.assertj.core.api.Assertions.assertThat;

import io.oryxlabs.oryxos.storage.entity.MemoryEntry;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 第 22 节：手工脚本建得出 {@code memory_entries} 表，且三个查询的语义与 SQLite 档的契约口径一致。
 *
 * <p><b>用真 SQLite、不用 H2</b>：与 {@link NotifyChannelRepositoryTest} 同理——H2 的类型系统与 SQLite
 * 不同，换内存库测出来的绿是假绿。
 *
 * <p><b>建表走手工脚本</b>：{@code db/schema.sql} 是表结构的唯一真相源。Hibernate 自动建表被显式关掉，否则脚本写错了测试也会「通过」。
 *
 * <p><b>为什么整个类关掉测试事务（{@code NOT_SUPPORTED}）</b>：{@code @DataJpaTest}
 * 默认把每个用例包在一个最终回滚的事务里，未提交的数据别的连接看不见，独立 JDBC 连接读表信息也就读不到建表结果。
 *
 * <p><b>本类看不见 {@code MemoryScope}</b>：{@code oryxos-storage} MUST NOT
 * 依赖任何内部模块，故分区是裸字符串，断言的是「传进实体、落在列上的是同一个值」；「这些列怎么变成长期记忆」由 {@code SqliteMemoryStoreTest} 钉住。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MemoryEntryRepositoryTest {

  /** 手工建表脚本，位于 main resources，测试期从 classpath 读取。 */
  private static final String SCHEMA_SCRIPT = "/db/schema.sql";

  private static final String CORE = "CORE";

  private static final String ARCHIVAL = "ARCHIVAL";

  /**
   * 临时库目录，由 JUnit 注入并**由 JUnit 删除**（静态字段 → 每个测试类一份，{@code @BeforeAll} 之前注入、{@code @AfterAll}
   * 之后递归删除）。
   *
   * <p><b>MUST NOT 换回 {@code Files.createTempDirectory}</b>：那样没人删，每跑一次 {@code mvn verify} 就往 {@code
   * /tmp} 留一个目录。
   */
  @TempDir static Path tempDir;

  /** 库文件路径。惰性求值：{@code @DynamicPropertySource} 收的是方法引用，解析属性时才真正落盘。 */
  private static Path dbFile() {
    return tempDir.resolve("oryxos.db");
  }

  @Autowired private MemoryEntryRepository repository;

  /**
   * PRAGMA 取回的一列：列名、是否 NOT NULL、是否主键。
   *
   * <p>后两项是建表脚本里两条被明确约束过的口径：主键只有 {@code id} 一个；三个业务列都 NOT
   * NULL（一行没有分区或没有正文的记忆是无意义的）。这两条写错了不会让任何一条读写用例变红。
   */
  private record ColumnInfo(String name, boolean notNull, boolean primaryKey) {}

  private static String jdbcUrl() {
    return "jdbc:sqlite:" + dbFile();
  }

  @DynamicPropertySource
  static void sqliteProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", MemoryEntryRepositoryTest::jdbcUrl);
    registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
    registry.add(
        "spring.jpa.database-platform", () -> "org.hibernate.community.dialect.SQLiteDialect");
    // 表结构只认手工脚本，框架一律不许插手
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
  }

  /** 先跑手工脚本建表，再让测试碰库。 */
  @BeforeAll
  static void applySchemaScript() throws SQLException, IOException {
    String script;
    try (InputStream in =
        Objects.requireNonNull(
            MemoryEntryRepositoryTest.class.getResourceAsStream(SCHEMA_SCRIPT),
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

  /**
   * 每个用例开始前清空表。
   *
   * <p><b>为什么必须有这一步</b>：本类关掉了测试事务（见类注释），写入是<b>真提交</b>，用例之间共享同一个库文件——不清表的话，前一个用例灌进去的归档流水会被后一个用例的
   * {@code LIMIT} 查询命中。{@code NotifyChannelRepositoryTest} 靠「各用例占一段字典序不重叠的名字」绕开这件事，但本类的查询带 {@code
   * LIMIT} 与聚合式断言，名字空间不重叠挡不住，只能清表。
   */
  @BeforeEach
  void clearTable() {
    repository.deleteAll();
  }

  private static MemoryEntry newRow(String scope, String content) {
    MemoryEntry entry = new MemoryEntry();
    entry.setScope(scope);
    entry.setContent(content);
    entry.setCreatedAt(LocalDateTime.now());
    return entry;
  }

  private static List<ColumnInfo> columnsOf(String table) throws SQLException {
    List<ColumnInfo> columns = new ArrayList<>();
    try (Connection connection = DriverManager.getConnection(jdbcUrl());
        Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
      while (rs.next()) {
        columns.add(
            new ColumnInfo(rs.getString("name"), rs.getInt("notnull") != 0, rs.getInt("pk") != 0));
      }
    }
    return columns;
  }

  private static List<String> indexNamesOf(String table) throws SQLException {
    List<String> names = new ArrayList<>();
    try (Connection connection = DriverManager.getConnection(jdbcUrl());
        Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery("PRAGMA index_list(" + table + ")")) {
      while (rs.next()) {
        names.add(rs.getString("name"));
      }
    }
    return names;
  }

  @Test
  @DisplayName("手工脚本建出的 memory_entries 表列齐备：四列一个不少、一个不多")
  void schemaScriptCreatesMemoryEntryColumns() throws SQLException {
    List<ColumnInfo> columns = columnsOf("memory_entries");

    assertThat(columns)
        .extracting(ColumnInfo::name)
        .containsExactlyInAnyOrder("id", "scope", "content", "created_at");
    // 主键只有 id 一个：记忆条目没有业务键，同一条内容可被重复写入
    assertThat(columns)
        .filteredOn(ColumnInfo::primaryKey)
        .extracting(ColumnInfo::name)
        .containsExactly("id");
    // 三个业务列都 NOT NULL：没有分区或没有正文的记忆是无意义的
    assertThat(columns)
        .filteredOn(ColumnInfo::notNull)
        .extracting(ColumnInfo::name)
        .containsExactlyInAnyOrder("scope", "content", "created_at");
  }

  @Test
  @DisplayName("分区索引 idx_memory_scope 由脚本建出：三处查询都先按 scope 过滤")
  void schemaScriptCreatesScopeIndex() throws SQLException {
    assertThat(indexNamesOf("memory_entries")).contains("idx_memory_scope");
  }

  @Test
  @DisplayName("核心区查询按写入顺序升序、且只返回核心区")
  void coreQueryReturnsOnlyCoreInAscendingOrder() {
    repository.save(newRow(ARCHIVAL, "归档的一条"));
    repository.save(newRow(CORE, "核心第一条"));
    repository.save(newRow(CORE, "核心第二条"));

    List<String> contents =
        repository.findByScopeOrderByIdAsc(CORE).stream().map(MemoryEntry::getContent).toList();

    assertThat(contents).containsExactly("核心第一条", "核心第二条");
  }

  @Test
  @DisplayName("归档区查询按倒序取最近 N 条：LIMIT 在 SQL 层生效，取到的是最近的而非最早的")
  void archivalQueryLimitsToMostRecentRows() {
    for (int i = 0; i < 5; i++) {
      repository.save(newRow(ARCHIVAL, "归档流水 " + i));
    }

    List<String> contents =
        repository.findByScopeOrderByIdDesc(ARCHIVAL, PageRequest.of(0, 2)).stream()
            .map(MemoryEntry::getContent)
            .toList();

    // 降序返回，且只取到最新的两条——最早的几条被 LIMIT 挡在 SQL 层之外
    assertThat(contents).containsExactly("归档流水 4", "归档流水 3");
  }

  @Test
  @DisplayName("归档区检索不区分大小写、只命中归档区、且不受条数上限影响")
  void searchArchivalIsCaseInsensitiveAndUnbounded() {
    repository.save(newRow(CORE, "核心区也提到了 Java"));
    for (int i = 0; i < 5; i++) {
      repository.save(newRow(ARCHIVAL, "归档提到 java 的第 " + i + " 条"));
    }

    List<String> hits =
        repository.searchArchival("%JAVA%").stream().map(MemoryEntry::getContent).toList();

    // 核心区那条 MUST NOT 出现：它本来就被全量注入，参与检索等于重复占上下文
    assertThat(hits).noneMatch(content -> content.startsWith("核心区"));
    // 大小写不敏感：模式串是大写、库里的内容是小写，五条全中
    assertThat(hits).hasSize(5);
  }
}
