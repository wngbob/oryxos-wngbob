package io.oryxlabs.oryxos.cli.workspace;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 工作区库结构与 {@code db/schema.sql} 的漂移检测与加性补齐（2026-10-20 用户批准）。
 *
 * <p><b>要解决的问题</b>：{@code schema.sql} 全是 {@code CREATE TABLE IF NOT EXISTS}，对<b>已存在</b>的表直接跳过—— 第
 * 16 节才给 {@code llm_calls} 加的 {@code success} / {@code error_message} 因此不会进入旧工作区的库，运行期每次发消息都在审计
 * insert 上炸（{@code table llm_calls has no column named error_message}），而 {@code init}
 * 当时报的是「已有（未覆盖）」，看起来一切正常。
 *
 * <p><b>怎么拿到「应有形状」而不另造真相源</b>：把同一份 {@code schema.sql} 跑进一个内存库，再读它的 {@code pragma
 * table_info}——脚本仍是唯一真相源，本类不维护第二份列清单，改脚本即改预期。
 *
 * <p><b>只增不删、只补不改</b>：缺的表交给建表脚本（{@code CREATE TABLE IF NOT EXISTS}）；缺的列用 {@code ALTER TABLE ADD
 * COLUMN} 补；类型不一致<b>只报告、不修改</b>（SQLite 转不了列类型，硬改只能重建表，而那是破坏性操作，不属于「漂移补齐」）；库里多出来的列一律不碰。
 *
 * <p><b>两处必须停下来的情形</b>：① 缺的是<b>主键列</b>——SQLite 的 {@code ALTER TABLE} 加不了主键，猜一个补法等于制造半个主键； ② 声明的列名 /
 * 表名不是合法标识符——那说明脚本本身有问题，继续拼 SQL 只会把错误推远。
 *
 * <p><b>NOT NULL 的如实折中</b>：{@code ALTER TABLE ADD COLUMN} 给非空表加「无默认值的 NOT NULL 列」必然失败，故这种列按可空补上，并在
 * 返回的说明里写明约束未加——运行期写入方总会给值，但库层确实放松了一档，必须让使用者看见。
 *
 * <p><b>为什么 SQL 是拼出来的</b>：{@code ALTER TABLE} 的表名 / 列名 / 类型无法参数化，这是 SQLite 的 DDL 形态决定的。注入面为零：
 * 三个片段全部来自本仓自己的 {@code schema.sql}（经内存库 pragma 读回），且标识符走白名单校验。
 */
final class SchemaDrift {

  /** 内存库：只用来执行 schema.sql、读出「应有形状」，不落盘。 */
  private static final String MEMORY_DB_URL = "jdbc:sqlite::memory:";

  /** 用户表清单：排除 SQLite 自己的 {@code sqlite_*} 内部表。 */
  private static final String LIST_TABLES_SQL =
      "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name";

  /** {@code PRAGMA table_info} 的前缀，表名在 {@link #requireIdentifier} 校验后拼接。 */
  private static final String TABLE_INFO_PREFIX = "PRAGMA table_info(";

  /** 表名 / 列名的白名单：schema.sql 里的名字只会是这个形状。 */
  private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

  /** 类型名白名单：允许 {@code VARCHAR(128)} 里的括号与逗号，不允许引号 / 分号。 */
  private static final Pattern TYPE_NAME = Pattern.compile("[A-Za-z0-9_(), ]+");

  /** 连续空白归一化，用于比较「实际类型」与「脚本类型」。 */
  private static final Pattern WHITESPACE = Pattern.compile("\\s+");

  private SchemaDrift() {}

  /**
   * 把已存在的表补到 {@code schema.sql} 声明的形状。
   *
   * @param connection 已打开的工作区库连接
   * @param schemaScript 建表脚本原文（由 {@link WorkspaceDb} 从 classpath 读取）
   * @return 面向用户的改动说明；空列表表示没有漂移
   * @throws IllegalStateException 读不出应有形状、缺主键列、或补列语句执行失败
   */
  static List<String> apply(Connection connection, String schemaScript) {
    Map<String, List<Column>> expected = expectedSchema(schemaScript);
    Map<String, List<Column>> actual = readTables(connection);
    List<String> changes = new ArrayList<>();
    for (Map.Entry<String, List<Column>> table : expected.entrySet()) {
      List<Column> present = actual.get(table.getKey());
      if (present == null) {
        // 表不存在：交给随后的建表脚本创建，本类只处理「表在、列不全」
        continue;
      }
      Map<String, Column> byName = new LinkedHashMap<>();
      for (Column column : present) {
        byName.put(column.name(), column);
      }
      for (Column column : table.getValue()) {
        Column existing = byName.get(column.name());
        if (existing == null) {
          changes.add(addColumn(connection, table.getKey(), column));
        } else if (!normalize(existing.type()).equals(normalize(column.type()))) {
          changes.add(
              "类型不一致（未修改）: "
                  + table.getKey()
                  + "."
                  + column.name()
                  + " 实际 "
                  + existing.type()
                  + " / 脚本 "
                  + column.type());
        }
      }
    }
    return List.copyOf(changes);
  }

  /**
   * 补一列。
   *
   * <p>{@code NOT NULL} 只在脚本给了默认值时才带上；否则按可空补，并在说明里写明折中。
   *
   * @return 该列的用户可读说明
   */
  @SuppressFBWarnings(
      value = {"SQL_INJECTION_JDBC", "SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE"},
      justification =
          "片段全部来自本仓自己的 db/schema.sql（经内存库 pragma 读回），标识符与类型另有白名单校验，"
              + "SQLite 的 ALTER TABLE 又不支持参数化标识符")
  private static String addColumn(Connection connection, String table, Column column) {
    requireIdentifier(table);
    requireIdentifier(column.name());
    if (column.primaryKey()) {
      throw new IllegalStateException(
          "表 " + table + " 缺主键列 " + column.name() + "：SQLite 的 ALTER TABLE 加不了主键列，请手工迁移或重建该表");
    }
    StringBuilder sql =
        new StringBuilder("ALTER TABLE ")
            .append(table)
            .append(" ADD COLUMN ")
            .append(column.name())
            .append(' ')
            .append(requireType(column.type()));
    boolean keepsNotNull = column.notNull() && column.defaultValue() != null;
    if (keepsNotNull) {
      sql.append(" NOT NULL DEFAULT ").append(column.defaultValue());
    }
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql.toString());
    } catch (SQLException e) {
      throw new IllegalStateException(
          "补列 " + table + "." + column.name() + " 失败：" + e.getMessage(), e);
    }
    String note = "补列: " + table + "." + column.name() + " " + column.type();
    if (column.notNull() && !keepsNotNull) {
      note += "（NOT NULL 约束未加：SQLite 无法给非空表补无默认值的非空列）";
    }
    return note;
  }

  /** 把 schema.sql 跑进内存库，读出脚本声明的表结构。 */
  private static Map<String, List<Column>> expectedSchema(String schemaScript) {
    try (Connection memory = DriverManager.getConnection(MEMORY_DB_URL)) {
      WorkspaceDb.executeSchemaScript(memory, schemaScript);
      return readTables(memory);
    } catch (SQLException e) {
      throw new IllegalStateException("读取建表脚本声明的表结构失败：" + e.getMessage(), e);
    }
  }

  /** 读出一个连接上所有用户表及其列。 */
  private static Map<String, List<Column>> readTables(Connection connection) {
    try {
      List<String> names = new ArrayList<>();
      try (Statement statement = connection.createStatement();
          ResultSet resultSet = statement.executeQuery(LIST_TABLES_SQL)) {
        while (resultSet.next()) {
          names.add(resultSet.getString(1));
        }
      }
      Map<String, List<Column>> tables = new LinkedHashMap<>();
      for (String name : names) {
        tables.put(name, readColumns(connection, name));
      }
      return tables;
    } catch (SQLException e) {
      throw new IllegalStateException("读取库表结构失败：" + e.getMessage(), e);
    }
  }

  /**
   * 读一张表的列（{@code pragma table_info}）。
   *
   * <p>{@code PRAGMA} 的表名无法参数化，故拼串；表名先过 {@link #requireIdentifier} 白名单。
   */
  @SuppressFBWarnings(
      value = {"SQL_INJECTION_JDBC", "SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE"},
      justification = "PRAGMA 不支持参数化标识符；表名来自 sqlite_master 并先过标识符白名单")
  private static List<Column> readColumns(Connection connection, String table) {
    requireIdentifier(table);
    List<Column> columns = new ArrayList<>();
    try (Statement statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery(TABLE_INFO_PREFIX + table + ")")) {
      while (resultSet.next()) {
        columns.add(
            new Column(
                resultSet.getString("name"),
                resultSet.getString("type"),
                resultSet.getBoolean("notnull"),
                resultSet.getString("dflt_value"),
                resultSet.getInt("pk") > 0));
      }
      return columns;
    } catch (SQLException e) {
      throw new IllegalStateException("读取表 " + table + " 的列失败：" + e.getMessage(), e);
    }
  }

  private static String normalize(String type) {
    return type == null
        ? ""
        : WHITESPACE.matcher(type.trim()).replaceAll(" ").toUpperCase(Locale.ROOT);
  }

  private static void requireIdentifier(String identifier) {
    if (identifier == null || !IDENTIFIER.matcher(identifier).matches()) {
      throw new IllegalStateException("建表脚本里的标识符不合法：" + identifier);
    }
  }

  private static String requireType(String type) {
    if (type == null || !TYPE_NAME.matcher(type).matches()) {
      throw new IllegalStateException("建表脚本里的列类型不合法：" + type);
    }
    return type;
  }

  /**
   * 一列的形状（{@code pragma table_info} 的子集）。
   *
   * @param name 列名
   * @param type 声明类型，如 {@code VARCHAR(128)}
   * @param notNull 是否 NOT NULL
   * @param defaultValue 默认值原文；没有则为 {@code null}
   * @param primaryKey 是否为（复合）主键的一员
   */
  private record Column(
      String name, String type, boolean notNull, String defaultValue, boolean primaryKey) {}
}
