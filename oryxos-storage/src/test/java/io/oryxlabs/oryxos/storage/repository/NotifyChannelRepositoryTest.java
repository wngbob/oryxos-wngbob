package io.oryxlabs.oryxos.storage.repository;

import static org.assertj.core.api.Assertions.assertThat;

import io.oryxlabs.oryxos.storage.entity.NotifyChannelEntity;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * US2 回归测试：手工脚本建得出 {@code notify_channels} 表、存得进、读得回，且默认渠道的口径不依赖插入顺序。
 *
 * <p><b>用真 SQLite、不用 H2</b>：与 {@link SessionRepositoryTest} 同理——H2 的类型系统与 SQLite 不同， 换内存库测出来的绿是假绿。
 *
 * <p><b>建表走手工脚本</b>：{@code db/schema.sql} 是表结构的唯一真相源。Hibernate 自动建表被显式关掉，
 * 否则脚本写错了测试也会「通过」——而「脚本按分号切分执行」这条踩坑记录正是本节新增表时要防的事故。
 *
 * <p><b>为什么整个类关掉测试事务（{@code NOT_SUPPORTED}）</b>：{@code @DataJpaTest} 默认把每个用例包在一个
 * 最终回滚的事务里，未提交的数据别的连接根本看不见，独立 JDBC 连接读表信息也就读不到建表结果。
 *
 * <p><b>用例之间不隔离，故各占一段字典序不重叠的名字</b>：写入是真提交，默认渠道又取全表 {@code name} 最小者， 共用名字空间会让用例互相影响。往返用例用 {@code
 * team-lark}，默认渠道用例的三行一律以 {@code alpha-} / {@code mike-} / {@code zulu-} 开头且都排在 {@code team-lark}
 * 之前，各自成立。
 *
 * <p><b>本类看不见 {@code NotifyTarget}</b>：{@code oryxos-storage} MUST NOT 依赖任何内部模块，故断言的是
 * 「传进实体、落在列上的是同一个值」；「这些列怎么变成通知目标」由 {@code JpaNotifyChannelRegistryTest} 钉住。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NotifyChannelRepositoryTest {

  /** 手工建表脚本，位于 main resources，测试期从 classpath 读取。 */
  private static final String SCHEMA_SCRIPT = "/db/schema.sql";

  /**
   * 临时库目录，由 JUnit 注入并**由 JUnit 删除**（静态字段 → 每个测试类一份，{@code @BeforeAll} 之前注入、 {@code @AfterAll}
   * 之后递归删除）。
   *
   * <p><b>MUST NOT 换回 {@code Files.createTempDirectory}</b>：那样没人删，每跑一次 {@code mvn verify} 就往 {@code
   * /tmp} 留一个目录（本类曾如此，累计 45 个）。也 <b>MUST NOT</b> 写成 {@code static final Path DB_FILE =
   * tempDir.resolve(...)}——静态初始化器在 {@code @BeforeAll} <b>之前</b>执行，那时 {@code tempDir} 还是 null。
   */
  @TempDir static Path tempDir;

  /** 库文件路径。惰性求值：{@code @DynamicPropertySource} 收的是方法引用，解析属性时才真正落盘。 */
  private static Path dbFile() {
    return tempDir.resolve("oryxos.db");
  }

  /** 往返用例的注册名；刻意排在默认渠道用例那三行之后，两者名字空间不重叠。 */
  private static final String ROUND_TRIP_NAME = "team-lark";

  /** 假地址：本类不发起任何请求，只验证这一列原样存住。用 {@code example.invalid} 明确它不是真渠道。 */
  private static final String SAMPLE_URL = "https://example.invalid/webhook/team-lark";

  /** 形状合法的一份类型配置：JSON 对象、值均为字符串。 */
  private static final String SAMPLE_CONFIG_JSON = "{\"msgtype\":\"text\"}";

  @Autowired private NotifyChannelRepository repository;

  /**
   * PRAGMA 取回的一列：列名、是否 NOT NULL、是否主键。
   *
   * <p>后两项不是「顺手多测」——它们是建表脚本里两条被明确约束过的口径：主键只能有 {@code name} 一个， 且任何列都 MUST NOT 加 {@code NOT
   * NULL}（校验留在应用层给可读报错）。这两条写错了不会让任何一条读写用例变红。
   */
  private record ColumnInfo(String name, boolean notNull, boolean primaryKey) {}

  private static String jdbcUrl() {
    return "jdbc:sqlite:" + dbFile();
  }

  @DynamicPropertySource
  static void sqliteProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", NotifyChannelRepositoryTest::jdbcUrl);
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
            NotifyChannelRepositoryTest.class.getResourceAsStream(SCHEMA_SCRIPT),
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

  private static NotifyChannelEntity newRow(String name, String configJson) {
    NotifyChannelEntity entity = new NotifyChannelEntity();
    entity.setName(name);
    entity.setType("webhook");
    entity.setUrl(SAMPLE_URL);
    entity.setConfigJson(configJson);
    entity.setDescription("研发群日报推送");
    return entity;
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

  @Test
  @DisplayName("手工脚本建出的 notify_channels 表列齐备：A6 的五列一个不少、一个不多")
  void schemaScriptCreatesNotifyChannelColumns() throws SQLException {
    List<ColumnInfo> columns = columnsOf("notify_channels");

    assertThat(columns)
        .extracting(ColumnInfo::name)
        .containsExactlyInAnyOrder("name", "type", "url", "config_json", "description");
    // 主键只有 name 一个：它是人指定的注册名，不是自增列
    assertThat(columns)
        .filteredOn(ColumnInfo::primaryKey)
        .extracting(ColumnInfo::name)
        .containsExactly("name");
    // 任何列都没有 NOT NULL：空值由应用层给出可读报错，不在建表层拒收
    assertThat(columns).filteredOn(ColumnInfo::notNull).isEmpty();
  }

  @Test
  @DisplayName("经 Repository 存取往返：五列逐字保真，config_json 保持原始字符串")
  void persistsAndReadsBackEveryColumn() {
    repository.save(newRow(ROUND_TRIP_NAME, SAMPLE_CONFIG_JSON));

    NotifyChannelEntity reloaded = repository.findById(ROUND_TRIP_NAME).orElseThrow();

    assertThat(reloaded.getName()).isEqualTo(ROUND_TRIP_NAME);
    assertThat(reloaded.getType()).isEqualTo("webhook");
    assertThat(reloaded.getUrl()).isEqualTo(SAMPLE_URL);
    // 实体不做 JSON 解析：存进去什么样，读出来就什么样
    assertThat(reloaded.getConfigJson()).isEqualTo(SAMPLE_CONFIG_JSON);
    assertThat(reloaded.getDescription()).isEqualTo("研发群日报推送");
  }

  @Test
  @DisplayName("默认渠道取注册名升序的第一个，与插入顺序无关")
  void defaultChannelIsFirstByNameNotByInsertionOrder() {
    // 先插字典序最大的，再插最小的：若实现依赖插入顺序或 SQLite 的裸行序，这条会拿到 zulu
    repository.save(newRow("zulu-webhook", "{}"));
    repository.save(newRow("alpha-webhook", "{}"));
    repository.save(newRow("mike-webhook", "{}"));

    NotifyChannelEntity first = repository.findFirstByOrderByNameAsc().orElseThrow();

    assertThat(first.getName()).isEqualTo("alpha-webhook");
  }
}
