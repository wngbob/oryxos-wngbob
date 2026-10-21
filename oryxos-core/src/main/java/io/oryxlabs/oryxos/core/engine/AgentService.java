package io.oryxlabs.oryxos.core.engine;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.oryxlabs.oryxos.core.profile.Profile;
import io.oryxlabs.oryxos.core.profile.ProfileRegistry;
import io.oryxlabs.oryxos.core.session.Session;
import io.oryxlabs.oryxos.core.session.SessionService;
import java.util.UUID;

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
 *
 * <p><b>第 26 节新增的无状态入口</b>：{@link #processStateless(String, String)} 服务于 {@code POST
 * /api/v1/agents/{name}/invoke}——它同样走 {@code reActLoop.run}，差别只在会话来自内存且不落库； 上面四条约定对它逐条同样成立。
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
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "依赖注入本身：第 29 节起 ProfileRegistry 是运行时可变索引，本类只读不写、也不该持有副本"
              + "（副本会让运行时注册在 AgentService 里看不见）。与 PromptBuilder 持 ContextLoader 同一口径")
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

  /**
   * 无状态处理一次：不建持久会话、不携带历史（第 26 节 {@code POST /api/v1/agents/{name}/invoke}，裁决 ⑫）。
   *
   * <p><b>走的是同一个 ReAct 循环</b>：与 {@link #process(Session, String)} 的差别只有「会话来自哪里、要不要落库」，
   * 循环本身、工具执行、审计口径完全一致——另起一条链路会让两个入口的行为悄悄分叉。
   *
   * <p><b>为什么不调 {@code save}</b>：一次性调用不该在 {@code sessions} 表里留下记录，两次调用之间更不能互相看见。 审计仍按这里的执行标识落
   * {@code llm_calls}（第 16 节的记录点在处理链路内部，与本方法无关）。
   *
   * <p><b>{@code invoke-exec:} + UUID 不经 {@code SessionId.of}</b>：这个标识只是给审计 / 日志一个可追的标识，
   * 对应的会话<b>永不落库</b>，故不构成第二处会话标识拼接（H4 不变量 ④ 管的是持久化标识）。
   *
   * @param profileName Agent 名
   * @param userMessage 用户输入
   * @return 模型的最终答复
   * @throws IllegalStateException Agent 在 {@link ProfileRegistry} 里查不到
   */
  public String processStateless(String profileName, String userMessage) {
    Profile profile =
        profileRegistry
            .find(profileName)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Agent '" + profileName + "' 的 Profile 不存在，无法处理一次性调用"));
    Session session =
        Session.create("invoke-exec:" + UUID.randomUUID(), "web", "console", profileName);
    ProfileContext.set(profile);
    try {
      return reActLoop.run(session, userMessage, profile);
    } finally {
      // 与有状态入口同一口径：异常路径也必须清（SC-007）
      ProfileContext.clear();
    }
  }
}
