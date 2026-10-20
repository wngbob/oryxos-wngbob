package io.oryxlabs.oryxos.web.controller;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.oryxlabs.oryxos.core.profile.Profile;
import io.oryxlabs.oryxos.core.profile.ProfileLoader;
import io.oryxlabs.oryxos.web.api.ApiResponse;
import io.oryxlabs.oryxos.web.controller.dto.ProfileView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 列表端点（contracts/rest-endpoints.md §3.1）。
 *
 * <p><b>每次实时扫描、不读启动期快照</b>：数据源是 {@code ProfileLoader.loadAllDetailed()}，改完 {@code AGENT.md}
 * 立刻反映出来；用启动时那份 {@code ProfileRegistry} 会把「刚改坏的 Agent」显示成好的。
 *
 * <p><b>失败项也进列表</b>（FR-018）：装载失败的 Agent 给 {@code valid:false} + {@code error:<原因>}，而不是静默消失——
 * 管理台上「哪个 Agent 的 {@code AGENT.md} 写坏了」必须看得见。列表按 {@code name} 升序，输出确定。
 */
@RestController
@RequestMapping("/api/v1/profiles")
@SuppressFBWarnings(
    value = "SPRING_ENDPOINT",
    justification =
        "FindSecBugs 对每个 @RequestMapping 方法报一条 SPRING_ENDPOINT，含义是「这是对外入口、值得人工复核」——"
            + "本类就是第 26 节交付的对外入口本身（FR-001），不是缺陷")
public class ProfileApiController {

  private final ProfileLoader profileLoader;

  /**
   * @param profileLoader 「什么叫有效」的唯一真相源
   */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "profileLoader 是 Spring 注入的协作对象，构造器持有是依赖注入本身，不构成对调用方的内部表示暴露")
  public ProfileApiController(ProfileLoader profileLoader) {
    this.profileLoader = profileLoader;
  }

  /**
   * 列出全部 Agent（成功与失败同列）。
   *
   * @return 按 {@code name} 升序的 Agent 视图
   */
  @GetMapping
  public ApiResponse<List<ProfileView>> list() {
    ProfileLoader.LoadResult result = profileLoader.loadAllDetailed();
    List<ProfileView> views = new ArrayList<>(result.loaded().size() + result.failures().size());
    result.loaded().forEach(profile -> views.add(fromProfile(profile)));
    result
        .failures()
        .forEach(
            failure ->
                views.add(
                    new ProfileView(
                        failure.agentName(),
                        null,
                        null,
                        List.of(),
                        List.of(),
                        false,
                        failure.reason())));
    // 两个清单各自有序，合并后要再排一次：管理台按名扫读，顺序漂移会让「刚才还在第三行」变成假象
    views.sort(Comparator.comparing(ProfileView::name));
    return ApiResponse.ok(List.copyOf(views));
  }

  private static ProfileView fromProfile(Profile profile) {
    Profile.ProviderSpec provider = profile.provider();
    ProfileView.ProviderView providerView =
        provider == null ? null : new ProfileView.ProviderView(provider.name(), provider.model());
    return new ProfileView(
        profile.name(),
        profile.description(),
        providerView,
        profile.tools(),
        profile.schedules(),
        true,
        null);
  }
}
