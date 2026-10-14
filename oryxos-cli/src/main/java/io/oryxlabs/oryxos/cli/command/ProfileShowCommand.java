package io.oryxlabs.oryxos.cli.command;

import io.oryxlabs.oryxos.cli.workspace.Workspace;
import io.oryxlabs.oryxos.core.profile.Profile;
import io.oryxlabs.oryxos.core.profile.ProfileLoader;
import io.oryxlabs.oryxos.provider.ProviderProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * {@code oryxos profile show <name>}：展示一个 Agent 派生后的完整 Profile 与校验结果（FR-013、
 * contracts/cli-surface.md §2.7）。
 *
 * <p><b>展示的是「派生后」的结果，不是 frontmatter 原文</b>：用户要确认的是「OryxOS 到底把这份配置理解成了
 * 什么」——缺省值补了没有、列表归一化成空表没有、{@code ${ENV_VAR}} 占位解析成了什么。原文自己就能打开看， 派生结果才是这条命令存在的理由。
 *
 * <p><b>「无效」的原因与 {@code profile list} 同一个来源</b>：都取 {@link ProfileLoader#loadAllDetailed()}
 * 的失败明细，两条命令不各写一份判据（research.md D20）。
 *
 * <p><b>不含任何凭证</b>：{@link Profile} 里只有厂商名、模型名与温度——API key 属于全局层 （{@code oryxos.providers}），不在
 * Profile 里，故本命令的输出天然无密钥可泄（章程原则 VIII）。
 */
@Command(name = "show", mixinStandardHelpOptions = true, description = "展示一个 Agent 派生后的完整配置与校验结果")
public final class ProfileShowCommand implements Runnable {

  /** 缺值占位。 */
  private static final String ABSENT = "—";

  private final Workspace workspace;

  @Parameters(index = "0", paramLabel = "<name>", description = "Agent 名（= 目录名）")
  private String name;

  /**
   * @param workspace 工作区；Agent 目录取它的 {@code agentsDir()}
   */
  public ProfileShowCommand(Workspace workspace) {
    this.workspace = workspace;
  }

  @Override
  public void run() {
    workspace.requireExists();
    String agentName = name == null ? "" : name.trim();
    ProfileLoader.LoadResult result =
        new ProfileLoader(workspace.agentsDir(), ProviderProperties.SUPPORTED_PROVIDERS)
            .loadAllDetailed();

    Optional<Profile> profile =
        result.loaded().stream().filter(loaded -> loaded.name().equals(agentName)).findFirst();
    if (profile.isPresent()) {
      printProfile(profile.get());
      return;
    }
    Optional<ProfileLoader.LoadResult.Failure> failure =
        result.failures().stream()
            .filter(failed -> failed.agentName().equals(agentName))
            .findFirst();
    if (failure.isPresent()) {
      // 存在的 Agent 但配置不合法：命令本身成功了（它如实报告了校验结果），故正常退出
      System.out.println("Agent '" + agentName + "'");
      System.out.println("  校验: 无效");
      System.out.println("  原因: " + failure.get().reason());
      return;
    }
    throw new IllegalStateException(
        "Agent '" + agentName + "' 不存在；工作区现有: " + availableNames(result));
  }

  private static void printProfile(Profile profile) {
    System.out.println("Agent '" + profile.name() + "'");
    System.out.println("  校验: 有效");
    print("name", profile.name());
    print("description", profile.description());
    print("identity.agent_name", profile.identity().agentName());
    print("identity.prompt", profile.identity().prompt());
    print("provider.name", profile.provider().name());
    print("provider.model", profile.provider().model());
    print("provider.temperature", profile.provider().temperature());
    print("tools", profile.tools());
    print("mcp_servers", profile.mcpServers());
    print("channels", profile.channels().stream().map(Profile.ChannelSpec::name).toList());
    print(
        "schedules",
        profile.schedules().stream()
            .map(schedule -> schedule.cron() + " @ " + schedule.zone() + " → " + schedule.message())
            .toList());
    print("bootstrap", profile.bootstrap());
    print("settings.max_iterations", profile.settings().maxIterations());
    print("settings.max_history_turns", profile.settings().maxHistoryTurns());
  }

  private static void print(String label, Object value) {
    System.out.println("  " + label + ": " + render(value));
  }

  /** 空列表与 {@code null} 都渲染成占位符：留空白会让人以为输出被截断了。 */
  private static String render(Object value) {
    if (value instanceof List<?> items) {
      return items.isEmpty()
          ? ABSENT
          : String.join(", ", items.stream().map(String::valueOf).toList());
    }
    return value == null ? ABSENT : String.valueOf(value);
  }

  private static String availableNames(ProfileLoader.LoadResult result) {
    List<String> names = new ArrayList<>();
    result.loaded().forEach(profile -> names.add(profile.name()));
    result.failures().forEach(failure -> names.add(failure.agentName()));
    if (names.isEmpty()) {
      return "（无）";
    }
    return String.join(", ", names.stream().sorted().toList());
  }
}
