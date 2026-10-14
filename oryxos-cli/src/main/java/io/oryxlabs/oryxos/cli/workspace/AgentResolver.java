package io.oryxlabs.oryxos.cli.workspace;

import io.oryxlabs.oryxos.core.profile.Profile;
import io.oryxlabs.oryxos.core.profile.ProfileLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 解析「这次用哪个 Agent」的唯一闸门（FR-004、Edge Cases）。
 *
 * <p><b>为什么必须是唯一闸门</b>：{@code profile_name} 的存在性校验只在这里做一次（spec 仲裁 11）。会话层不重复校验 ——接缝签名只拿得到三元组、看不到
 * Agent 清单；命令层也不各自校验——三条重命令各写一遍「Agent 在不在」， 迟早有一条漏掉，然后一个不存在的 Agent 名会一路走到引擎里才炸，报出来的还是引擎内部的话。
 *
 * <p><b>「有效」的判据不在这里</b>：什么叫一个合法的 Agent，真相源是 {@link ProfileLoader}。本类只消费它交出的 「成功清单 + 失败明细」，不重写任何
 * frontmatter 校验规则（research.md D20）。
 *
 * <p><b>三种结果都有明确的下一步</b>：指定了名字但不存在 → 报出可选名；一个 Agent 都没有 → 提示怎么建一个； 有多个却没指定 →
 * 列出可选名让用户自己挑。三者都不返回一个「随便挑的」缺省值——静默挑错 Agent 比报错难查得多。
 */
public final class AgentResolver {

  private final Workspace workspace;
  private final Set<String> knownProviders;

  /**
   * @param workspace 工作区（取其 {@code agents/} 目录）
   * @param knownProviders 已声明的厂商名，直接交给 {@link ProfileLoader} 做校验；本类不判断厂商是否合法
   */
  public AgentResolver(Workspace workspace, Set<String> knownProviders) {
    this.workspace = workspace;
    this.knownProviders = Set.copyOf(knownProviders);
  }

  /**
   * 解析出这次要用的 Agent 名。
   *
   * <p>每次调用都重新扫描 Agent 目录，不缓存：与 {@code ContextLoader} 的口径一致——改完 {@code AGENT.md} 下一轮即生效，用户不必为了看一眼新
   * Agent 去重启什么。
   *
   * @param requestedName {@code --profile} 指定的 Agent 名；为 {@code null} 或空白表示未指定
   * @return Agent 名（目录名）
   * @throws IllegalStateException 指定的 Agent 不存在或无效；未指定时工作区没有 Agent、唯一的一个无效、 或存在多个无法确定
   */
  public String resolve(String requestedName) {
    ProfileLoader.LoadResult result = loadAllDetailed();
    if (requestedName != null && !requestedName.isBlank()) {
      return byName(requestedName, result);
    }
    return bySoleAgent(result);
  }

  private ProfileLoader.LoadResult loadAllDetailed() {
    return new ProfileLoader(workspace.agentsDir(), knownProviders).loadAllDetailed();
  }

  private String byName(String requestedName, ProfileLoader.LoadResult result) {
    for (Profile profile : result.loaded()) {
      if (profile.name().equals(requestedName)) {
        return requestedName;
      }
    }
    for (ProfileLoader.LoadResult.Failure failure : result.failures()) {
      if (failure.agentName().equals(requestedName)) {
        throw new IllegalStateException(
            "Agent '" + requestedName + "' 无效，无法使用：" + failure.reason());
      }
    }
    throw new IllegalStateException("Agent '" + requestedName + "' 不存在；工作区现有: " + names(result));
  }

  private String bySoleAgent(ProfileLoader.LoadResult result) {
    int total = result.loaded().size() + result.failures().size();
    if (total == 0) {
      throw new IllegalStateException(
          "工作区里没有可用 Agent：请先运行 `oryxos profile create <name>` 建一个，再用 --profile <name> 指定");
    }
    if (total > 1) {
      throw new IllegalStateException(
          "工作区有多个 Agent，无法确定用哪个：请用 --profile <name> 指定；可选: " + names(result));
    }
    // 唯一的那个若无效，在这里报出原因比放进引擎再炸有用——引擎只会说「Profile 查不到」
    if (!result.loaded().isEmpty()) {
      return result.loaded().get(0).name();
    }
    ProfileLoader.LoadResult.Failure only = result.failures().get(0);
    throw new IllegalStateException(
        "工作区唯一的 Agent '" + only.agentName() + "' 无效，无法使用：" + only.reason());
  }

  /** 全部 Agent 名（含无效的），按目录名升序——无效的也要列出来，否则用户不知道自己去指定的是哪一个。 */
  private static String names(ProfileLoader.LoadResult result) {
    List<String> names = new ArrayList<>();
    result.loaded().forEach(profile -> names.add(profile.name()));
    result.failures().forEach(failure -> names.add(failure.agentName()));
    if (names.isEmpty()) {
      return "（无）";
    }
    return names.stream().sorted().collect(Collectors.joining(", "));
  }
}
