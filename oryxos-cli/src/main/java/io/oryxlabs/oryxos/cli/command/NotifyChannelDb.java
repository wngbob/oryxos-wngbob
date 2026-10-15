package io.oryxlabs.oryxos.cli.command;

import io.oryxlabs.oryxos.cli.workspace.Workspace;
import io.oryxlabs.oryxos.cli.workspace.WorkspaceDb;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 三条 {@code notify-channel} 轻命令共用的「打开工作区库，并保证 {@code notify_channels} 表在」。
 *
 * <p><b>为什么单独收口这一步</b>：三条命令都要它，而其中一步不是显然的——{@link WorkspaceDb#runSchemaScript()}。 理由： {@code
 * notify_channels} 是第 19 节才有的表，<b>老工作区的库文件里没有它</b>（工作区目录是跨版本保留的用户数据， 而 {@code oryxos init}
 * 是幂等的、不会去补建已存在工作区里的新表）。建表脚本全是 {@code CREATE TABLE IF NOT EXISTS}，
 * 天然幂等，每次执行一次的成本可以忽略，换来的是「这三条命令在任何工作区上都能跑」。
 *
 * <p>{@code session list} 没有这一步，是因为 {@code sessions} 表与工作区同龄——那条命令不需要，不代表这一步多余。
 *
 * <p><b>SQL 与行形状不在这里</b>：本类只负责「把库打开到可用」，各命令自己写死自己的 SQL（{@link WorkspaceDb} 的设计口径）。
 *
 * <p>包级私有：只在 {@code io.oryxlabs.oryxos.cli.command} 内部使用，不进任何对外概念清单。
 */
final class NotifyChannelDb {

  private NotifyChannelDb() {}

  /**
   * 打开工作区库并确保建表脚本已执行。
   *
   * @param workspace 工作区；库文件取它的 {@code dbFile()}
   * @return 已连接、且 {@code notify_channels} 表必然存在的实例，<b>调用方负责 close</b>
   * @throws IllegalStateException 工作区不存在，或库文件不存在（提示先跑 {@code oryxos init}）
   */
  static WorkspaceDb open(Workspace workspace) {
    workspace.requireExists();
    Path dbFile = workspace.dbFile();
    if (!Files.exists(dbFile)) {
      throw new IllegalStateException("找不到工作区库 " + dbFile + "：请先运行 `oryxos init` 建出工作区与库文件");
    }
    WorkspaceDb database = WorkspaceDb.open(dbFile);
    database.runSchemaScript();
    return database;
  }
}
