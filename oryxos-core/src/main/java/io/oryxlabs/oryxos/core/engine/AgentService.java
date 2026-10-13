package io.oryxlabs.oryxos.core.engine;

import io.oryxlabs.oryxos.core.profile.Profile;
import io.oryxlabs.oryxos.core.profile.ProfileRegistry;
import io.oryxlabs.oryxos.core.session.Session;
import io.oryxlabs.oryxos.core.session.SessionService;

/**
 * 三种触发源的<b>唯一入口</b>（FR-008 / FR-009 / FR-010）。
 *
 * <p>CLI、Web Service、{@code AgentScheduler}（钟推）最终都汇入 {@link #process(Session, String)}；{@code
 * ReActLoop} 不感知消息来源（章程原则 IX）。
 *
 * <p><b>四条行为约定</b>：
 *
 * <ol>
 *   <li><b>唯一入口</b>：三种触发源都汇入 {@code process}；
 *   <li><b>{@code finally} 清上下文</b>：正常与异常路径都必须清（SC-007）；
 *   <li><b>持久化在循环之后</b>：{@code save} 拿到的必然是累积完的会话历史；
 *   <li><b>不在这里 try-catch 业务异常</b>：处理失败该让调用方知道（第 26 节 Web 层映射成 500 / 503），本类只负责「无论怎么退出都把
 *       ThreadLocal 清干净」。
 * </ol>
 */
public class AgentService {

  private final ProfileRegistry profileRegistry;
  private final ReActLoop reActLoop;
  private final SessionService sessionService;

  /**
   * @param profileRegistry 按 Agent 名取 Profile（第 16 节已交付）
   * @param reActLoop 循环本体
   * @param sessionService 持久化会话的接缝（第 18 节实现）
   */
  public AgentService(
      ProfileRegistry profileRegistry, ReActLoop reActLoop, SessionService sessionService) {
    this.profileRegistry = profileRegistry;
    this.reActLoop = reActLoop;
    this.sessionService = sessionService;
  }

  /**
   * 处理一次用户输入。
   *
   * @param session 会话；其 {@code profileName()} 决定用哪个 Agent 的配置
   * @param userMessage 用户输入
   * @return 模型的最终答复
   * @throws IllegalStateException 会话所属的 Agent 在 {@link ProfileRegistry} 里查不到——这是配置 / 数据错误，不是业务失败
   */
  public String process(Session session, String userMessage) {
    String profileName = session.profileName();
    Profile profile =
        profileRegistry
            .find(profileName)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Agent '" + profileName + "' 的 Profile 不存在，无法处理会话 " + session.id()));
    ProfileContext.set(profile);
    try {
      String reply = reActLoop.run(session, userMessage, profile);
      sessionService.save(session);
      return reply;
    } finally {
      // 异常路径不清 = 下一个复用该线程的请求读到上一个请求的 Agent（SC-007）
      ProfileContext.clear();
    }
  }
}
