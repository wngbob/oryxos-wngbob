package io.oryxlabs.oryxos.cli.command;

import io.oryxlabs.oryxos.cli.workspace.Workspace;
import io.oryxlabs.oryxos.core.profile.Profile;
import io.oryxlabs.oryxos.core.profile.ProfileLoader;
import io.oryxlabs.oryxos.provider.ProviderProperties;
import java.util.Comparator;
import java.util.List;
import picocli.CommandLine.Command;

/**
 * {@code oryxos provider list}：列「底座支持的厂商全集」+「工作区各 Agent 实际引用的厂商」（FR-015、SC-008、
 * contracts/cli-surface.md §2.9）。
 *
 * <p><b>支持集合不在这里定义</b>：取自 {@link ProviderProperties#SUPPORTED_PROVIDERS}——它正是 {@code
 * ProviderProperties.validate()} 的判据。在 CLI 侧复制一份同名集合就是第二处真相：厂商集合一变，这里会静默地列错。
 *
 * <p><b>密钥就绪状态一律标「未接入（第 26 节）」</b>：轻命令不启动 Spring 上下文，读不到 {@code oryxos.providers} 配置（它在 {@code
 * oryxos-boot} 的 classpath 上，不在本模块）。<b>编造一个「就绪」比留白更糟</b> ——用户会据此以为某个 Provider 可用，然后在 {@code chat}
 * 时撞上失败，且不知道该信谁。
 *
 * <p><b>本命令不读、不回显任何密钥值</b>：它连配置文件都不碰，输出里天然没有密钥可泄（章程原则 VIII）。
 * 路径上也不做「猜一个环境变量名」的探测——那等于把「未接入」伪装成「已就绪」。
 *
 * <p><b>Agent 引用了不支持的厂商 → 标「不支持」并给出厂商名，命令仍退出码 0</b>：这是「如实报告现状」，不是运行错误； 真拦住用户的是 {@code chat}（它解析
 * Agent 时就会失败）。
 */
@Command(
    name = "list",
    mixinStandardHelpOptions = true,
    description = "列出底座支持的厂商与工作区各 Agent 引用的厂商（密钥就绪状态本节不判定）")
public final class ProviderListCommand implements Runnable {

  /** 「未接入」的标注文案，逐字取自 contracts/cli-surface.md §4。 */
  private static final String NOT_WIRED = "未接入（第 26 节）";

  private final Workspace workspace;

  /**
   * @param workspace 工作区；Agent 目录取它的 {@code agentsDir()}
   */
  public ProviderListCommand(Workspace workspace) {
    this.workspace = workspace;
  }

  @Override
  public void run() {
    workspace.requireExists();
    List<String> supported = ProviderProperties.SUPPORTED_PROVIDERS.stream().sorted().toList();
    // 已知厂商集合传下去，让「引用了一个底座没有的厂商」按 ProfileLoader 的既有规则判成无效——判据不在这里另写一份
    ProfileLoader.LoadResult result =
        new ProfileLoader(workspace.agentsDir(), ProviderProperties.SUPPORTED_PROVIDERS)
            .loadAllDetailed();

    System.out.println("底座支持的厂商（共 " + supported.size() + " 个）:");
    if (supported.isEmpty()) {
      System.out.println("  （无：底座还没有已实现的厂商）");
    }
    for (String name : supported) {
      System.out.println("  " + name);
    }
    System.out.println("  密钥就绪状态: " + NOT_WIRED);

    System.out.println();
    System.out.println("工作区 Agent 引用的厂商（共 " + result.loaded().size() + " 个 Agent）:");
    if (result.loaded().isEmpty()) {
      System.out.println("  （无：先运行 `oryxos profile create <name>` 建一个）");
    }
    List<Profile> loaded =
        result.loaded().stream().sorted(Comparator.comparing(Profile::name)).toList();
    for (Profile profile : loaded) {
      String vendor = profile.provider().name();
      String mark =
          ProviderProperties.SUPPORTED_PROVIDERS.contains(vendor) ? "已支持" : "不支持（底座未实现该厂商）";
      System.out.println("  " + profile.name() + "  " + vendor + "  " + mark);
    }
    if (!result.failures().isEmpty()) {
      // 无效 Agent 的 frontmatter 没解析出来，它引用了哪个厂商无从得知——不猜，只如实说数量
      System.out.println("  （另有 " + result.failures().size() + " 个 Agent 配置无效，引用的厂商名无法确定）");
    }
  }
}
