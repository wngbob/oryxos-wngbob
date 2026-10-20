package io.oryxlabs.oryxos.core.session;

import java.util.Optional;

/**
 * core 侧的「持久化会话」接缝（contracts/session-model.md §4）。
 *
 * <p><b>实现方</b>：第 18 节的 {@code sessions} 表 + Repository（{@code JpaSessionService}）。
 *
 * <p><b>五个操作</b>：取或建（三元组）/ 按标识取 / 保存 / 归档 / 数活跃会话。（修订注，2026-10-19，第 26
 * 节：原文写「三个操作」，后两个是本节新增——归档态与活跃会话数是管理台与 {@code /info} 的数据源；前三者的签名与行为一字未改。）
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

  /**
   * 归档会话（第 26 节，FR-021）。
   *
   * <p><b>幂等</b>：已归档的会话再调一次，{@code archivedAt} 保持首次归档的时刻不变，仍返回 {@code true}—— 重复 DELETE
   * 不该报错、也不该把归档时刻改成「现在」。归档只是置状态，历史不删，归档后仍可查询。
   *
   * @param sessionId 会话标识
   * @return 会话存在（含已归档）返回 {@code true}；从未存在返回 {@code false}
   */
  boolean archive(String sessionId);

  /**
   * 活跃会话数（第 26 节 {@code /info} 的 {@code activeSessions} 数据源）。
   *
   * @return {@code status="active"} 的会话行数
   */
  long countActive();
}
