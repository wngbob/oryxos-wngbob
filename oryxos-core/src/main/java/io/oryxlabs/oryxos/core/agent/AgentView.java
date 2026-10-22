package io.oryxlabs.oryxos.core.agent;

import io.oryxlabs.oryxos.core.profile.Profile;
import java.util.List;
import java.util.Objects;

/**
 * 对外暴露的 Agent 视图（第 30 节，契约 agent-lifecycle-api.md §3）。
 *
 * <p><b>为什么直接复用 {@link Profile.ScheduleSpec}</b>：第 26 节的 {@code ProfileView}
 * 已经有同一先例——为对外视图再造一个同形类型，等于 给同一个概念维护两个字段名，迟早会漂移。{@code schedules} 是定义面（{@code AGENT.md} 里写的那份），运行态在
 * {@code /api/v1/schedules}。
 *
 * <p><b>列表与详情共用这一个形状</b>：列表多带几个字段比维护两套 DTO 便宜；这些字段本来就随 Profile 一起在内存里。
 *
 * @param name Agent 名（= 目录名）
 * @param description 一句话说明
 * @param providerName 厂商名
 * @param providerModel 模型名
 * @param tools 可用工具名清单
 * @param schedules 定时任务定义清单
 */
public record AgentView(
    String name,
    String description,
    String providerName,
    String providerModel,
    List<String> tools,
    List<Profile.ScheduleSpec> schedules) {

  /** 归一化：可空集合收敛为不可变副本，调用方拿到的视图改不动内部状态。 */
  public AgentView {
    tools = tools == null ? List.of() : List.copyOf(tools);
    schedules = schedules == null ? List.of() : List.copyOf(schedules);
  }

  /**
   * 由 Profile 映射成对外视图。
   *
   * @param profile 已注册的 Agent 运行时配置
   * @return 对外视图
   */
  public static AgentView from(Profile profile) {
    Objects.requireNonNull(profile, "profile");
    Profile.ProviderSpec provider = profile.provider();
    return new AgentView(
        profile.name(),
        profile.description(),
        provider == null ? null : provider.name(),
        provider == null ? null : provider.model(),
        profile.tools(),
        profile.schedules());
  }
}
