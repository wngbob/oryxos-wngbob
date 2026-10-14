package io.oryxlabs.oryxos.cli.command;

import io.oryxlabs.oryxos.cli.workspace.Workspace;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Set;
import java.util.stream.Stream;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code oryxos profile delete <name> [--force]}：删除一个 Agent（FR-014、contracts/cli-surface.md §2.8）。
 *
 * <p><b>核心阶段是物理删除</b>：目录连同里面的 {@code AGENT.md}、{@code scripts/}、Skill 软连接一起删掉。
 * 不搬进回收站、不加软删除标记——本节没有「恢复」这条命令，留一个删不干净的中间态只会让 {@code profile list} 的输出和磁盘上的实际内容对不上。
 *
 * <p><b>默认要确认，{@code --force} 跳过</b>：删 Agent 会连带删掉它的任务指令，这是不可逆的。未确认时 <b>不删除且正常退出（退出码
 * 0）</b>——用户按了回车表示「算了」，那不是错误。
 *
 * <p><b>只允许删 {@code agents/} 的直接子目录</b>：名字来自命令行，若不做这一步校验，一个 {@code ../..} 就能把
 * 工作区之外的东西删掉。判据是「解析后的父目录必须正是 agents 目录」，比黑名单更彻底。
 */
@Command(
    name = "delete",
    mixinStandardHelpOptions = true,
    description = "删除一个 Agent（需确认；--force 跳过确认）")
public final class ProfileDeleteCommand implements Runnable {

  /**
   * 视为「确认」的输入，逐字比较。
   *
   * <p><b>不做大小写折叠</b>：{@code toLowerCase} / {@code equalsIgnoreCase} 这类 Unicode 变换在静态门禁里是
   * 告警点（{@code IMPROPER_UNICODE}——某些语言环境下同一个字符会折叠出不同结果）。这里的取值集合本来就只有
   * 四种写法，逐个列出来比做一次大小写变换更直白，也没有环境相关的行为。
   */
  private static final Set<String> AFFIRMATIVE = Set.of("y", "Y", "yes", "YES");

  private final Workspace workspace;

  @Parameters(index = "0", paramLabel = "<name>", description = "Agent 名（= 目录名）")
  private String name;

  @Option(names = "--force", description = "跳过确认，直接删除")
  private boolean force;

  /**
   * @param workspace 工作区；Agent 目录取它的 {@code agentsDir()}
   */
  public ProfileDeleteCommand(Workspace workspace) {
    this.workspace = workspace;
  }

  @Override
  public void run() {
    workspace.requireExists();
    String agentName = name == null ? "" : name.trim();
    Path agentsDir = workspace.agentsDir();
    Path agentDir = agentsDir.resolve(agentName).normalize();
    if (!Files.isDirectory(agentDir) || !agentsDir.normalize().equals(agentDir.getParent())) {
      throw new IllegalStateException(
          "Agent '" + agentName + "' 不存在：.oryxos/agents/ 下没有这个目录（`oryxos profile list` 可看全部）");
    }
    if (!force && !confirmed(agentName)) {
      System.out.println("已取消：未删除 Agent '" + agentName + "'");
      return;
    }
    deleteRecursively(agentDir);
    System.out.println("已删除 Agent '" + agentName + "'：" + agentDir);
  }

  private static boolean confirmed(String agentName) {
    System.out.print("确认删除 Agent '" + agentName + "' 及其目录下的全部文件？[y/N] ");
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

  private static void deleteRecursively(Path directory) {
    try (Stream<Path> paths = Files.walk(directory)) {
      // Files.walk 是前序（父先于子），倒序即后序——先删空子目录，最后删自己
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(path);
      }
    } catch (IOException e) {
      throw new IllegalStateException("删除 " + directory + " 失败：" + e.getMessage(), e);
    }
  }
}
