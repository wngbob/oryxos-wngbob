package io.oryxlabs.oryxos.memory.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.oryxlabs.oryxos.storage.entity.MemoryEntry;
import io.oryxlabs.oryxos.storage.repository.MemoryEntryRepository;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * SQLite 档的档内细节：手工脚本建的表能建能读、归档 {@code LIMIT} 生效、{@code LIKE} 检索、以及「表不存在」时的那句指引。
 *
 * <p><b>用真 SQLite、不用 H2</b>：{@code LOWER(...) LIKE LOWER(...)} 与 {@code LIMIT}
 * 在两边的行为不完全一致，换内存库测出来的绿是假绿 （与 {@code MemoryEntryRepositoryTest} 同口径）。
 *
 * <p><b>实体与 Repository 在兄弟模块</b>：{@code oryxos-memory} 的自动配置扫不到 {@code oryxos-storage} 的包，故显式声明
 * {@code @EntityScan} / {@code @EnableJpaRepositories}（与 {@code oryxos-boot} 因实体在兄弟包而必须显式声明是同一条原因）。
 *
 * <p><b>每个用例包在回滚事务里</b>（{@code @DataJpaTest} 默认）：本类不像 {@code MemoryEntryRepositoryTest} 那样关事务，
 * 因为这里测的是「写进去能不能读出来」，而同一个事务内写读完全够——省掉手写清表，也就不会出现用例之间串数据。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EntityScan(basePackages = "io.oryxlabs.oryxos.storage.entity")
@EnableJpaRepositories(basePackages = "io.oryxlabs.oryxos.storage.repository")
class SqliteMemoryStoreTest {

  private static final String SCHEMA_SCRIPT = "/db/schema.sql";

  @TempDir static Path tempDir;

  @Autowired private MemoryEntryRepository repository;

  private static String jdbcUrl() {
    return "jdbc:sqlite:" + tempDir.resolve("oryxos.db");
  }

  @DynamicPropertySource
  static void sqliteProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", SqliteMemoryStoreTest::jdbcUrl);
    registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
    registry.add(
        "spring.jpa.database-platform", () -> "org.hibernate.community.dialect.SQLiteDialect");
    // 表结构只认手工脚本，框架一律不许插手
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
  }

  @BeforeAll
  static void applySchemaScript() throws SQLException, IOException {
    String script;
    try (InputStream in =
        Objects.requireNonNull(
            SqliteMemoryStoreTest.class.getResourceAsStream(SCHEMA_SCRIPT),
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

  private SqliteMemoryStore store() {
    return new SqliteMemoryStore(repository);
  }

  @Test
  @DisplayName("手工建表脚本建出的表能写能读——写入后立刻 load 得到，不走任何缓存")
  void writesAreReadableThroughTheHandMaintainedTable() {
    SqliteMemoryStore memory = store();

    memory.append("用户叫小王", MemoryScope.CORE);
    memory.append("上周聊过部署", MemoryScope.ARCHIVAL);

    String loaded = memory.load();
    assertThat(loaded).contains("用户叫小王").contains("上周聊过部署");
    // 条目正文不带日期前缀——时间另有一列，渲染时由本档自己决定怎么拼
    assertThat(loaded).doesNotContain("[20");
  }

  @Test
  @DisplayName("两个区都空时 load() 返回空串——MUST NOT 吐出只有 header 的残渣")
  void emptyTableYieldsEmptyString() {
    assertThat(store().load()).isEmpty();
  }

  @Test
  @DisplayName("归档区 LIMIT 生效：灌 101 条只注入最近 100 条，最早那条被挡在 SQL 层之外")
  void archivalSectionIsLimitedToTheMostRecentRows() {
    SqliteMemoryStore memory = store();
    for (int i = 0; i < 101; i++) {
      memory.append("归档流水 " + i, MemoryScope.ARCHIVAL);
    }

    String loaded = memory.load();

    assertThat(loaded).doesNotContain("归档流水 0");
    assertThat(loaded).contains("归档流水 100");
  }

  @Test
  @DisplayName("核心区查询不带 LIMIT：灌 101 条核心记忆一条不少")
  void coreSectionIsNeverLimited() {
    SqliteMemoryStore memory = store();
    for (int i = 0; i < 101; i++) {
      memory.append("核心流水 " + i, MemoryScope.CORE);
    }

    String loaded = memory.load();

    assertThat(loaded).contains("核心流水 0").contains("核心流水 100");
  }

  @Test
  @DisplayName("检索只搜归档区、大小写不敏感，且覆盖被 LIMIT 裁掉的那部分")
  void recallSearchesWholeArchivalCaseInsensitively() {
    SqliteMemoryStore memory = store();
    memory.append("核心区也提到了 Java", MemoryScope.CORE);
    for (int i = 0; i < 101; i++) {
      memory.append("归档提到 java 的第 " + i + " 条", MemoryScope.ARCHIVAL);
    }

    var hits = memory.recallByKeyword("JAVA");

    // 核心区那条不参与检索
    assertThat(hits).noneMatch(hit -> hit.startsWith("核心区"));
    // 101 条全中：检索不受 load() 的 LIMIT 影响，否则记忆会「存了但查不到」
    assertThat(hits).hasSize(101);
  }

  @Test
  @DisplayName("检索未命中返回空列表，capabilities 恒为 KEYWORD，archivalEntries 返回归档区正文")
  void missReturnsEmptyAndCapabilitiesAreKeyword() {
    SqliteMemoryStore memory = store();
    memory.append("记了一条别的", MemoryScope.ARCHIVAL);
    memory.append("核心的一条", MemoryScope.CORE);

    assertThat(memory.recallByKeyword("绝不出现的词")).isEmpty();
    assertThat(memory.capabilities()).isEqualTo(MemoryCapability.KEYWORD);
    assertThat(memory.archivalEntries()).containsExactly("记了一条别的");
  }

  @Test
  @DisplayName("表不存在时把底层报错包成带「请先运行 oryxos init」指引的明确消息")
  void missingTableIsReportedWithAnActionableHint() {
    MemoryEntryRepository broken = mock(MemoryEntryRepository.class);
    when(broken.findByScopeOrderByIdAsc(anyString()))
        .thenThrow(
            new DataAccessResourceFailureException(
                "could not prepare statement", new SQLException("no such table: memory_entries")));

    assertThatThrownBy(() -> new SqliteMemoryStore(broken).load())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("oryxos init")
        .hasMessageContaining("memory_entries");
  }

  @Test
  @DisplayName("非「表不存在」的数据库错误原样上抛——不许被包成 init 指引而掩盖真问题")
  void otherDatabaseErrorsPropagateUnchanged() {
    MemoryEntryRepository broken = mock(MemoryEntryRepository.class);
    DataAccessResourceFailureException boom =
        new DataAccessResourceFailureException(
            "database is locked", new SQLException("database is locked"));
    when(broken.findByScopeOrderByIdAsc(anyString())).thenThrow(boom);

    assertThatThrownBy(() -> new SqliteMemoryStore(broken).load()).isSameAs(boom);
  }

  @Test
  @DisplayName("append 落库的 scope 是裸字符串，正文与写入时间分列存放")
  void appendPersistsScopeAsPlainString() {
    SqliteMemoryStore memory = store();
    memory.append("一条核心记忆", MemoryScope.CORE);

    MemoryEntry row = repository.findByScopeOrderByIdAsc("CORE").get(0);
    assertThat(row.getContent()).isEqualTo("一条核心记忆");
    assertThat(row.getCreatedAt()).isNotNull();
  }
}
