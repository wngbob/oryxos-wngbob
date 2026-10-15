package io.oryxlabs.oryxos.cli.workspace;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 轻命令读库的原生 JDBC 收口（research.md D7）。
 *
 * <p><b>为什么不用 Spring 的 DataSource</b>：轻命令不启动 Spring 上下文（FR-003，启动一次 2~4 秒与「秒回」直接冲突），
 * 而它们要读库，所以必须有一条不依赖 Spring 的读库路径。用 {@code DriverManager} 直连是唯一不引入框架对象的选择。
 *
 * <p><b>收口什么、不收口什么</b>：连接、建表脚本执行、语句执行与异常包装收在这里——这几件事每个命令各写一遍必然 抄错一处；<b>SQL
 * 与行形状留在各命令</b>，因为「这个命令要哪几列」只有那个命令知道。所以 {@link #query} 收的是 {@code SQL + 行映射器}、{@link #execute} 收的是
 * {@code SQL + 参数}，而不是一堆以命令命名的专用方法。
 *
 * <p><b>非线程安全</b>：一条命令一个实例，用完即关（{@link AutoCloseable}）。
 */
public final class WorkspaceDb implements AutoCloseable {

  /** 建表脚本位置：{@code oryxos-storage} 的 main resources，经模块依赖传到本模块的 classpath。 */
  private static final String SCHEMA_SCRIPT = "/db/schema.sql";

  private static final String JDBC_PREFIX = "jdbc:sqlite:";

  /** 建表脚本的语句分隔符。 */
  private static final String STATEMENT_SEPARATOR = ";";

  private final Connection connection;

  private WorkspaceDb(Connection connection) {
    this.connection = connection;
  }

  /**
   * 打开（不存在则创建）工作区库。
   *
   * @param dbFile SQLite 库文件路径
   * @return 已连接的实例，调用方负责关闭
   * @throws IllegalStateException 连不上——消息里带上原因，{@code status} 要把它原样打给用户
   */
  public static WorkspaceDb open(Path dbFile) {
    try {
      return new WorkspaceDb(DriverManager.getConnection(JDBC_PREFIX + dbFile));
    } catch (SQLException e) {
      throw new IllegalStateException("无法打开工作区数据库 " + dbFile + "：" + e.getMessage(), e);
    }
  }

  /**
   * 执行建表脚本（research.md D8）。
   *
   * <p>按 {@code ;} 切分逐句执行，与 {@code LlmCallRepositoryTest} 的既有做法逐字一致——复用已被验证可行的方式， 不另发明一种脚本执行器。脚本内全是
   * {@code CREATE TABLE IF NOT EXISTS}，天然幂等，与 {@code init} 的幂等要求一致。
   *
   * <p><b>前提</b>：脚本里不得出现含分号的字符串字面量或注释（切分会把它们切断）。该约束写在脚本自身的注释里。
   *
   * <p><b>为什么要就地压制告警</b>：FindSecBugs 对「SQL 不是编译期常量」的形态一律报 {@code SQL_INJECTION_JDBC} / {@code
   * SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE}，而本方法的 SQL 按 D8 的定义**只能**从 classpath 的 {@code
   * db/schema.sql} 切出来——换成 {@code PreparedStatement} 一样会报（它同样按「入参非常量」判定），
   * 换个写法绕不过去。注入面为零：脚本是本仓自己的资源文件，切出来的每句都是本仓自己写的 DDL。
   *
   * @throws IllegalStateException 脚本读不到或任一句执行失败
   */
  @SuppressFBWarnings(
      value = {"SQL_INJECTION_JDBC", "SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE"},
      justification = "SQL 来自本仓自己的 classpath 资源 db/schema.sql，无外部输入")
  public void runSchemaScript() {
    String script = readSchemaScript();
    try (Statement statement = connection.createStatement()) {
      for (String sql : script.split(STATEMENT_SEPARATOR)) {
        if (!sql.isBlank()) {
          statement.execute(sql);
        }
      }
    } catch (SQLException e) {
      throw new IllegalStateException("建表脚本执行失败：" + e.getMessage(), e);
    }
  }

  /**
   * 执行一条查询语句，把每行交给映射器。
   *
   * <p><b>为什么要就地压制告警</b>：{@code sql} 是方法入参，静态分析眼里天然是非常量（同 {@link
   * #runSchemaScript()}）。注入面为零：每个调用点传的都是本命令自己写死的常量字面量，不含任何用户输入；而「SQL 作为 入参」正是 D7 的设计——SQL
   * 与行形状留在各命令，本类只收口连接与执行。
   *
   * @param sql 查询语句；由调用命令自己写死的常量，不含外部输入
   * @param mapper 行映射器，把当前行变成调用方要的形状
   * @param <T> 行类型
   * @return 结果行，顺序即 SQL 的 {@code ORDER BY} 结果
   * @throws IllegalStateException 语句执行失败
   */
  @SuppressFBWarnings(value = "SQL_INJECTION_JDBC", justification = "SQL 由调用命令写死为常量字面量，无外部输入")
  public <T> List<T> query(String sql, RowMapper<T> mapper) {
    List<T> rows = new ArrayList<>();
    try (Statement statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery(sql)) {
      while (resultSet.next()) {
        rows.add(mapper.map(resultSet));
      }
    } catch (SQLException e) {
      throw new IllegalStateException("查询失败：" + e.getMessage(), e);
    }
    return List.copyOf(rows);
  }

  /**
   * 执行一条带参的写入语句（{@code INSERT} / {@code UPDATE} / {@code DELETE}）。
   *
   * <p><b>为什么必须走 {@code PreparedStatement} 绑定参数</b>：{@code params} 全部来自命令行输入（渠道名、类型、地址、 描述、配置值），拼进
   * SQL 既是指令注入面、也是引号转义面——一个含单引号的地址就能把语句切坏。绑定把这两件事一起交给驱动。
   *
   * <p><b>为什么要就地压制告警</b>：{@code sql} 是方法入参，静态分析眼里天然是非常量（同 {@link #query}）。注入面为零：
   * 每个调用点传的都是本命令自己写死的常量字面量，值全部走 {@code ?} 占位符绑定，不含任何拼串。
   *
   * @param sql 写入语句；由调用命令自己写死的常量，值一律用 {@code ?} 占位
   * @param params 按 {@code ?} 出现顺序绑定的参数值
   * @return 受影响行数（{@code INSERT} 为 1；{@code DELETE} 命中为 1、未命中为 0）
   * @throws IllegalStateException 语句执行失败
   */
  @SuppressFBWarnings(
      value = "SQL_INJECTION_JDBC",
      justification = "SQL 由调用命令写死为常量字面量，值全部走 ? 占位符绑定")
  public int execute(String sql, Object... params) {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < params.length; i++) {
        statement.setObject(i + 1, params[i]);
      }
      return statement.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException("写入失败：" + e.getMessage(), e);
    }
  }

  @Override
  public void close() {
    try {
      connection.close();
    } catch (SQLException e) {
      throw new IllegalStateException("关闭工作区数据库失败：" + e.getMessage(), e);
    }
  }

  private static String readSchemaScript() {
    try (InputStream in = WorkspaceDb.class.getResourceAsStream(SCHEMA_SCRIPT)) {
      if (in == null) {
        throw new IllegalStateException(
            "classpath 上找不到建表脚本 " + SCHEMA_SCRIPT + "：请确认 oryxos-storage 在依赖中");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取建表脚本 " + SCHEMA_SCRIPT + " 失败：" + e.getMessage(), e);
    }
  }

  /**
   * 把 {@link ResultSet} 的当前行映射成调用方要的形状。
   *
   * <p>单独定义而不是用 {@code java.util.function.Function}：{@code ResultSet} 的取值方法都声明抛 {@link
   * SQLException}，用标准函数式接口就得在映射器里把异常包一层，等于把「取哪一列」这件小事写成一堆样板。
   */
  @FunctionalInterface
  public interface RowMapper<T> {

    /**
     * 把当前行映射成调用方要的形状。
     *
     * @param resultSet 已定位到某一行的结果集
     * @return 该行的映射结果
     * @throws SQLException 取值失败
     */
    T map(ResultSet resultSet) throws SQLException;
  }
}
