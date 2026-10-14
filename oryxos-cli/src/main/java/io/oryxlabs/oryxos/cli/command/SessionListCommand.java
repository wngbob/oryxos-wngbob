package io.oryxlabs.oryxos.cli.command;

import io.oryxlabs.oryxos.cli.workspace.Workspace;
import io.oryxlabs.oryxos.cli.workspace.WorkspaceDb;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import picocli.CommandLine.Command;

/**
 * {@code oryxos session list}：列出会话（FR-016、contracts/cli-surface.md §2.11）。
 *
 * <p><b>轻命令，走原生 JDBC 直读 {@code sessions} 表</b>：这条命令只查一张表，为它启动一次 Spring 上下文（2~4 秒）
 * 与「轻命令秒回」直接冲突。读库路径见 {@link WorkspaceDb}。
 *
 * <p><b>按最近活跃倒序</b>：会话列表最常见的用途是「我上次跟谁聊到哪了」，最近活跃的排最前面才合用；顺序由 SQL 的 {@code ORDER BY} 保证，不在 Java
 * 侧再排一遍。
 *
 * <p><b>列名即契约给的六列</b>（{@code sessionId} / {@code profile} / {@code channel} / {@code user} / {@code
 * status} / {@code lastActiveAt}），表头照抄，方便对号。
 *
 * <p><b>不打印对话内容</b>：{@code messages_json} 可能很长，且可能含用户隐私；这条命令是「有哪些会话」，看内容归 {@code chat} 接着聊。
 */
@Command(
    name = "list",
    mixinStandardHelpOptions = true,
    description = "列出会话（标识 / 所属 Agent / 渠道 / 用户 / 状态 / 最后活跃时间），按最近活跃倒序")
public final class SessionListCommand implements Runnable {

  /** 六列表头，逐字取自 contracts/cli-surface.md §2.11。 */
  private static final String HEADER = "sessionId  profile  channel  user  status  lastActiveAt";

  private static final String ABSENT = "—";

  /** 查会话六列，按最近活跃倒序。常量 SQL，不含外部输入。 */
  private static final String SQL =
      "SELECT session_id, profile_name, channel, user_id, status, last_active_at"
          + " FROM sessions ORDER BY last_active_at DESC";

  private final Workspace workspace;

  /**
   * @param workspace 工作区；库文件取它的 {@code dbFile()}
   */
  public SessionListCommand(Workspace workspace) {
    this.workspace = workspace;
  }

  @Override
  public void run() {
    workspace.requireExists();
    Path dbFile = workspace.dbFile();
    if (!Files.exists(dbFile)) {
      throw new IllegalStateException("找不到会话库 " + dbFile + "：请先运行 `oryxos init` 建出工作区与库文件");
    }
    List<Row> rows;
    try (WorkspaceDb database = WorkspaceDb.open(dbFile)) {
      rows = database.query(SQL, SessionListCommand::map);
    }
    report(rows);
  }

  private static Row map(ResultSet resultSet) throws SQLException {
    return new Row(
        resultSet.getString("session_id"),
        resultSet.getString("profile_name"),
        resultSet.getString("channel"),
        resultSet.getString("user_id"),
        resultSet.getString("status"),
        resultSet.getString("last_active_at"));
  }

  private static void report(List<Row> rows) {
    System.out.println("会话（共 " + rows.size() + " 条，按最近活跃倒序）:");
    if (rows.isEmpty()) {
      System.out.println("  （无：先运行 `oryxos chat --profile <name>` 聊一句）");
      return;
    }
    System.out.println("  " + HEADER);
    for (Row row : rows) {
      System.out.println(
          "  "
              + render(row.sessionId())
              + "  "
              + render(row.profile())
              + "  "
              + render(row.channel())
              + "  "
              + render(row.user())
              + "  "
              + render(row.status())
              + "  "
              + render(row.lastActiveAt()));
    }
  }

  /** 列可空（老数据、外部写入），空值渲染成占位符而不是空串——空串会让列看起来错位。 */
  private static String render(String value) {
    return value == null || value.isBlank() ? ABSENT : value;
  }

  /**
   * 一行输出。
   *
   * @param sessionId 会话标识，形如 {@code cli:local:weather}
   * @param profile 所属 Agent 名
   * @param channel 接入渠道
   * @param user 用户标识
   * @param status 会话状态（{@code active} / {@code archived}）
   * @param lastActiveAt 最后活跃时间
   */
  private record Row(
      String sessionId,
      String profile,
      String channel,
      String user,
      String status,
      String lastActiveAt) {}
}
