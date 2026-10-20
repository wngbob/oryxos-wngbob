package io.oryxlabs.oryxos.web.health;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 数据库探活（第 26 节）：{@code /api/v1/health} 里 {@code db} 分量的唯一来源。
 *
 * <p><b>真探测、不缓存</b>：每次调用都取一次连接并 {@code isValid(1)}。缓存会把「刚才还通、现在断了」的库报成 UP， 健康检查一旦说谎就失去了存在意义。
 *
 * <p><b>永不抛异常</b>：拿不到连接、连接失效一律折成 {@link Level#DOWN}。健康检查本身失败时应当回报「不健康」， 而不是把一个 500 抛给监控系统。
 */
@Component
public class DatabaseHealthProbe {

  /** 探活结论。字面量 {@code UP} / {@code DOWN} 是已定字面量（契约 §4.1）。 */
  public enum Level {
    /** 通。 */
    UP,
    /** 不通。 */
    DOWN
  }

  private static final Logger log = LoggerFactory.getLogger(DatabaseHealthProbe.class);

  private static final int VALIDATION_TIMEOUT_SECONDS = 1;

  private final DataSource dataSource;

  /**
   * <b>关于 {@code @SuppressFBWarnings}</b>：{@code dataSource} 是容器注入的协作对象（{@code HikariDataSource}
   * 本身就不是值类型），按依赖注入惯例持有与被判「暴露内部表示」是同一件事；本类不把它交出去、只借连接。
   *
   * @param dataSource 数据源；只借连接、不在探活里做任何业务查询
   */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "dataSource 是 Spring 注入的协作对象，构造器持有是依赖注入本身，不构成对调用方的内部表示暴露")
  public DatabaseHealthProbe(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  /**
   * 探一次活。
   *
   * @return {@link Level#UP} 当且仅当连接取得到且有效；其余一律 {@link Level#DOWN}
   */
  public Level probe() {
    try (Connection connection = dataSource.getConnection()) {
      return connection.isValid(VALIDATION_TIMEOUT_SECONDS) ? Level.UP : Level.DOWN;
    } catch (SQLException e) {
      // 探活失败是「结果」不是「要上抛的事故」，但也不静默：留一条 WARN，否则「库为什么是 DOWN」只能靠猜。
      // 只带异常 message 且先 sanitize——SQLite 的报错可能带上连接串，CR/LF 也可能伪造日志行
      log.warn("数据库探活失败，按 DOWN 处理：{}", sanitizeForLog(e.getMessage()));
      return Level.DOWN;
    }
  }

  /** 入日志前替换 CR/LF，防日志伪造（与统一异常出口同一口径）。 */
  private static String sanitizeForLog(String message) {
    return message == null ? "" : message.replace('\r', '_').replace('\n', '_');
  }
}
