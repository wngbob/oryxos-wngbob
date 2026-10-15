package io.oryxlabs.oryxos.cli.command;

import io.oryxlabs.oryxos.cli.workspace.Workspace;
import io.oryxlabs.oryxos.cli.workspace.WorkspaceDb;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code oryxos notify-channel remove <name> [--force]}：从注册表移除一个通知渠道（contracts/cli-surface.md
 * §2.12）。
 *
 * <p><b>为什么是「移除」而不是「删除」</b>：删掉的是注册表里的一行，<b>不是那个群</b>——群还在，只是 Agent 不再按这个名字推给它。 命令名照上游 REST 资源名
 * {@code /api/v1/notify-channels} 对齐。
 *
 * <p><b>默认要确认，{@code --force} 跳过</b>：照 {@code profile delete} 的口径。未确认时<b>不删且正常退出（退出码 0）</b>
 * ——用户按了回车表示「算了」，那不是错误。
 *
 * <p><b>名字不存在是错误，不是静默成功</b>：{@code executeUpdate()} 返回 0 说明没删到任何行，报错并指向 {@code list}。
 * 静默返回「已移除」会让打错名字的人以为删掉了。
 *
 * <p><b>不打地址</b>：任何输出（含异常消息）只出现渠道名。
 */
@Command(
    name = "remove",
    mixinStandardHelpOptions = true,
    description = "从注册表移除一个通知渠道（需确认；--force 跳过确认）")
public final class NotifyChannelRemoveCommand implements Runnable {

  /**
   * 视为「确认」的输入，逐字比较。
   *
   * <p>不做大小写折叠：{@code toLowerCase} / {@code equalsIgnoreCase} 在静态门禁里是告警点 （{@code
   * IMPROPER_UNICODE}）。取值本来就只有这几种写法，逐个列出来比做一次大小写变换更直白。
   */
  private static final Set<String> AFFIRMATIVE = Set.of("y", "Y", "yes", "YES");

  private static final String DELETE_SQL = "DELETE FROM notify_channels WHERE name = ?";

  private final Workspace workspace;

  @Parameters(index = "0", paramLabel = "<name>", description = "渠道注册名")
  private String name;

  @Option(names = "--force", description = "跳过确认，直接移除")
  private boolean force;

  /**
   * @param workspace 工作区；库文件取它的 {@code dbFile()}
   */
  public NotifyChannelRemoveCommand(Workspace workspace) {
    this.workspace = workspace;
  }

  @Override
  public void run() {
    String channelName = name == null ? "" : name.trim();
    if (channelName.isEmpty()) {
      throw new IllegalStateException("请提供渠道名：`oryxos notify-channel remove <name>`");
    }
    if (!force && !confirmed(channelName)) {
      System.out.println("已取消：未移除通知渠道 '" + channelName + "'");
      return;
    }
    int removed;
    try (WorkspaceDb database = NotifyChannelDb.open(workspace)) {
      removed = database.execute(DELETE_SQL, channelName);
    }
    if (removed == 0) {
      throw new IllegalStateException(
          "通知渠道 '" + channelName + "' 不存在：`oryxos notify-channel list` 可看全部");
    }
    System.out.println("已移除通知渠道 '" + channelName + "'");
  }

  private static boolean confirmed(String channelName) {
    System.out.print("确认从注册表移除通知渠道 '" + channelName + "'？[y/N] ");
    System.out.flush();
    BufferedReader reader =
        new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    String answer;
    try {
      answer = reader.readLine();
    } catch (IOException e) {
      throw new IllegalStateException("读取确认输入失败：" + e.getMessage(), e);
    }
    if (answer == null) {
      // 输入流结束（Ctrl+D / 管道读完）等同「没确认」
      return false;
    }
    return AFFIRMATIVE.contains(answer.trim());
  }
}
