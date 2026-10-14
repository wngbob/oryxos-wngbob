package io.oryxlabs.oryxos.cli.command;

import io.oryxlabs.oryxos.cli.workspace.Workspace;
import io.oryxlabs.oryxos.cli.workspace.WorkspaceDb;
import io.oryxlabs.oryxos.core.profile.ProfileLoader;
import io.oryxlabs.oryxos.provider.ProviderProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import picocli.CommandLine.Command;

/**
 * {@code oryxos status}：工作区概览（FR-010、contracts/cli-surface.md §2.2）。
 *
 * <p><b>轻命令，文件系统 + 原生 JDBC</b>：这条命令一行都不调模型，为它启动 Spring 上下文是纯浪费。
 *
 * <p><b>三段必须标注未接入，MUST NOT 编造数值</b>（contracts/cli-surface.md §4）：密钥就绪状态标「未接入（第 26 节）」、定时任务数标「未接入（第
 * 28 节）」。编造一个「就绪」或「0 个任务」比留白更糟——用户会据此以为某块能力已经 能用，然后在真正用它的时候撞上失败，且不知道该信谁。
 *
 * <p><b>数据库不可达时不报 0，报占位符</b>：读不到就是读不到。报 {@code 0} 会让「库坏了」看起来像「还没有会话」， 这两种状态的处理方式完全不同。
 */
@Command(
    name = "status",
    mixinStandardHelpOptions = true,
    description = "输出工作区概览（路径 / Agent 数 / 活跃会话数 / 数据库可达性 / 支持的厂商）")
public final class StatusCommand implements Runnable {

  /** 密钥就绪状态的标注文案，逐字取自 contracts/cli-surface.md §4。 */
  private static final String NOT_WIRED_KEYS = "未接入（第 26 节）";

  /** 定时任务数的标注文案，逐字取自 contracts/cli-surface.md §4。 */
  private static final String NOT_WIRED_SCHEDULES = "未接入（第 28 节）";

  /** 读不到时的占位符。 */
  private static final String ABSENT = "—";

  /** 活跃会话计数。常量 SQL，不含外部输入。 */
  private static final String SQL_ACTIVE_SESSIONS =
      "SELECT COUNT(*) FROM sessions WHERE status = 'active'";

  private final Workspace workspace;

  /**
   * @param workspace 工作区；根路径与库文件分别取它的 {@code root()} / {@code dbFile()}
   */
  public StatusCommand(Workspace workspace) {
    this.workspace = workspace;
  }

  @Override
  public void run() {
    workspace.requireExists();
    Path root = workspace.root();
    Path dbFile = workspace.dbFile();

    System.out.println("工作区: " + root);

    ProfileLoader.LoadResult result =
        new ProfileLoader(workspace.agentsDir(), ProviderProperties.SUPPORTED_PROVIDERS)
            .loadAllDetailed();
    int valid = result.loaded().size();
    int invalid = result.failures().size();
    System.out.println("Agent: 共 " + (valid + invalid) + " 个（有效 " + valid + "，无效 " + invalid + "）");

    System.out.println("数据库: " + databaseState(dbFile));
    System.out.println("活跃会话: " + activeSessions(dbFile) + "（status = 'active'）");

    List<String> supported = ProviderProperties.SUPPORTED_PROVIDERS.stream().sorted().toList();
    System.out.println(
        "底座支持的厂商（共 "
            + supported.size()
            + " 个）: "
            + (supported.isEmpty() ? "（无）" : String.join(", ", supported)));
    System.out.println("  密钥就绪状态: " + NOT_WIRED_KEYS);
    System.out.println("定时任务: " + NOT_WIRED_SCHEDULES);
  }

  /**
   * 数据库可达性。
   *
   * <p><b>先判文件存在再连</b>：{@code DriverManager.getConnection} 对不存在的 SQLite 路径会<b>顺手把文件建出来</b>， 那样
   * {@code status} 会把「库丢了」显示成「可达 + 0 条会话」，还会留下一个空库把问题掩盖掉。
   */
  private static String databaseState(Path dbFile) {
    if (!Files.exists(dbFile)) {
      return "不可达（文件不存在：" + dbFile + "；请先运行 `oryxos init`）";
    }
    try (WorkspaceDb database = WorkspaceDb.open(dbFile)) {
      database.query("SELECT 1", resultSet -> Integer.valueOf(1));
      return "可达（" + dbFile + "）";
    } catch (IllegalStateException e) {
      return "不可达（" + e.getMessage() + "）";
    }
  }

  private static String activeSessions(Path dbFile) {
    if (!Files.exists(dbFile)) {
      return ABSENT;
    }
    try (WorkspaceDb database = WorkspaceDb.open(dbFile)) {
      List<Integer> counts = database.query(SQL_ACTIVE_SESSIONS, resultSet -> resultSet.getInt(1));
      return counts.isEmpty() ? ABSENT : String.valueOf(counts.get(0));
    } catch (IllegalStateException e) {
      // 库读不出来时上面的「数据库」一行已经写了原因，这里只留占位符，不重复一遍
      return ABSENT;
    }
  }
}
