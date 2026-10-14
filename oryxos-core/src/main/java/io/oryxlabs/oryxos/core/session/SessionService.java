package io.oryxlabs.oryxos.core.session;

import java.util.Optional;

/**
 * core 侧的「持久化会话」接缝（contracts/session-model.md §4）。
 *
 * <p><b>实现方</b>：第 18 节的 {@code sessions} 表 + Repository（{@code JpaSessionService}）。
 *
 * <p><b>三个操作</b>：取或建（三元组）/ 按标识取 / 保存。第 17 节只声明了 {@code save}——当时没有调用方， 凭空造接口是章程原则 II
 * 反对的；本节有了真实调用方（CLI 的 {@code chat}），补齐另外两个。
 *
 * <p><b>会话标识的拼接不在这里对外暴露</b>：调用方只提供 {@code channel} / {@code userId} / {@code profileName}
 * 三元组，标识由实现内部唯一一处拼接（FR-006 / H4 不变量 ④）——两处各拼一遍、格式差一个 分隔符，同一个人就会出现两条互不相认的历史。
 */
public interface SessionService {

  /**
   * 取或建会话。
   *
   * <p>按三元组算出标识：命中则<b>从库中恢复历史</b>返回，未命中则新建空会话。同一三元组历次调用返回同一标识 （幂等，FR-007）——多轮对话靠它串起来；三元组任一不同即不同会话。
   *
   * @param channel 接入渠道，如 {@code cli}
   * @param userId 用户标识；CLI 固定 {@code local}
   * @param profileName 所属 Agent 名
   * @return 已存在的会话（带历史）或新建的空会话
   */
  Session getOrCreate(String channel, String userId, String profileName);

  /**
   * 按标识读会话。
   *
   * @param sessionId 会话标识
   * @return 命中则返回会话，<b>查不到返回空</b>——调用方决定是报错还是跳过，本接缝不替它决定（FR-009）
   */
  Optional<Session> get(String sessionId);

  /**
   * 持久化一次处理后的会话。
   *
   * @param session 累积完历史的会话；调用点保证它一定发生在 ReAct 循环<b>之后</b>
   */
  void save(Session session);
}
