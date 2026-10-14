package io.oryxlabs.oryxos.cli.command;

import io.oryxlabs.oryxos.cli.workspace.Workspace;
import io.oryxlabs.oryxos.core.profile.Profile;
import io.oryxlabs.oryxos.core.profile.ProfileLoader;
import io.oryxlabs.oryxos.provider.ProviderProperties;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import picocli.CommandLine.Command;

/**
 * {@code oryxos profile list}：列出工作区全部 Agent 及其有效性（FR-012、contracts/cli-surface.md §2.5）。
 *
 * <p><b>轻命令</b>：只列目录与解析 frontmatter，不启动 Spring 上下文。
 *
 * <p><b>「无效」的原因不在这里生成</b>：{@link ProfileLoader#loadAllDetailed()} 交出「成功清单 + 失败明细」， 本命令只负责排版。CLI
 * 侧重写一套 frontmatter 校验就是第二处真相——加字段、改规则时必漏改一处（research.md D20）。
 *
 * <p><b>目录名即 Agent 名</b>：读的是 {@code .oryxos/agents/}。课件的骨架片段读 {@code .oryxos/profiles/}，
 * 那是示意错误（spec 仲裁 10）。
 */
@Command(
    name = "list",
    mixinStandardHelpOptions = true,
    description = "列出工作区全部 Agent 及其有效性（无效的附原因）")
public final class ProfileListCommand implements Runnable {

  /** 缺值占位：宁可显示一个「没有」，也不留空白让人以为是排版错乱。 */
  private static final String ABSENT = "—";

  private final Workspace workspace;

  /**
   * @param workspace 工作区；Agent 目录取它的 {@code agentsDir()}
   */
  public ProfileListCommand(Workspace workspace) {
    this.workspace = workspace;
  }

  @Override
  public void run() {
    workspace.requireExists();
    // 已知厂商集合取自 oryxos-provider 的 SUPPORTED_PROVIDERS：它与 ProviderProperties.validate() 的判据是同一个，
    // 在 CLI 侧再写一份同名集合就是第二处真相（research.md D14）
    ProfileLoader.LoadResult result =
        new ProfileLoader(workspace.agentsDir(), ProviderProperties.SUPPORTED_PROVIDERS)
            .loadAllDetailed();
    List<Row> rows = new ArrayList<>();
    for (Profile profile : result.loaded()) {
      rows.add(
          new Row(
              profile.name(),
              true,
              profile.provider().name() + "/" + profile.provider().model(),
              profile.description(),
              null));
    }
    for (ProfileLoader.LoadResult.Failure failure : result.failures()) {
      rows.add(new Row(failure.agentName(), false, ABSENT, null, failure.reason()));
    }
    rows.sort(Comparator.comparing(Row::name));
    report(rows);
  }

  private static void report(List<Row> rows) {
    System.out.println("Agent（共 " + rows.size() + " 个，目录 .oryxos/agents/）:");
    if (rows.isEmpty()) {
      System.out.println("  （无：先运行 `oryxos profile create <name>` 建一个）");
      return;
    }
    for (Row row : rows) {
      StringBuilder line = new StringBuilder("  ").append(row.name()).append("  ");
      line.append(row.valid() ? "有效" : "无效").append("  provider=").append(row.provider());
      if (row.valid()) {
        line.append("  ").append(row.description() == null ? ABSENT : row.description());
      } else {
        line.append("  原因: ").append(row.reason());
      }
      System.out.println(line);
    }
  }

  /**
   * 一行输出。
   *
   * @param name Agent 名（目录名）
   * @param valid frontmatter 是否装载成功
   * @param provider {@code 厂商/模型}；无效时是占位符
   * @param description 一句话说明；无效时为 {@code null}
   * @param reason 装载失败原因；有效时为 {@code null}
   */
  private record Row(
      String name, boolean valid, String provider, String description, String reason) {}
}
