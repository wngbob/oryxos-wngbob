package io.oryxlabs.oryxos.core.session;

/**
 * core 侧的「持久化会话」接缝（contracts/session-model.md §4）。
 *
 * <p><b>实现方</b>：第 18 节的 {@code sessions} 表 + Repository。
 *
 * <p><b>本节只声明 {@link #save(Session)}</b>：{@code AgentService.process} 在循环跑完后调一次，把累积完的历史持久化。 {@code
 * load} / {@code create} / 会话标识生成规则不在本节范围——没有调用方的抽象就是凭空造接口（章程原则 II）。
 */
public interface SessionService {

  /**
   * 持久化一次处理后的会话。
   *
   * @param session 累积完历史的会话；调用点保证它一定发生在 ReAct 循环<b>之后</b>
   */
  void save(Session session);
}
