package io.oryxlabs.oryxos.web.controller.dto;

import io.oryxlabs.oryxos.core.profile.Profile;
import java.util.List;

/**
 * 一个 Agent 的对外视图（data-model.md §3.3）。
 *
 * <p><b>加载失败的 Agent 也在列表里</b>（{@code valid:false} + {@code error:<原因>}），而不是静默消失—— 否则「某个 Agent 的
 * {@code AGENT.md} 写坏了」在管理台上完全看不见。
 *
 * @param name Agent 名
 * @param description 描述；加载失败时可能为空
 * @param provider 厂商与模型；加载失败时为 {@code null}
 * @param tools 声明的工具名
 * @param schedules 定时任务定义；直接复用核心层的 {@link Profile.ScheduleSpec}，不为对外视图再造一份同形类型
 * @param valid 是否加载成功
 * @param error 加载失败的原因；成功时为 {@code null}
 */
public record ProfileView(
    String name,
    String description,
    ProviderView provider,
    List<String> tools,
    List<Profile.ScheduleSpec> schedules,
    boolean valid,
    String error) {

  /** 防御性拷贝：两个列表收敛为不可变视图；加载失败的 Agent 这两项为空表而不是 {@code null}，管理台不必额外判空。 */
  public ProfileView {
    tools = tools == null ? List.of() : List.copyOf(tools);
    schedules = schedules == null ? List.of() : List.copyOf(schedules);
  }

  /**
   * 厂商与模型（嵌套而不是顶层类型：它只是 {@link ProfileView} 的一个分量，不是独立概念）。
   *
   * @param name 厂商名
   * @param model 模型名
   */
  public record ProviderView(String name, String model) {}
}
