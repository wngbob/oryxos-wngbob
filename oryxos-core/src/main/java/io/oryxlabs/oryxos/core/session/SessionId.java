package io.oryxlabs.oryxos.core.session;

/**
 * 会话标识的唯一拼接点（contracts/session-persistence.md §2）。
 *
 * <p><b>公式</b>：{@code channel + ":" + userId + ":" + profileName}，形如 {@code
 * cli:local:daily-weather}。
 *
 * <p><b>为什么必须只此一处</b>：所有入口（CLI / 第 25 节钟推 / 第 26 节 REST）只提供三元组、MUST NOT 自己拼
 * 字符串——两处各拼一遍、格式差一个分隔符，同一个人就会出现两条互不相认的历史（FR-006 / SC-003 / H4 不变量 ④）。
 *
 * <p><b>包级私有</b>：不是对外概念。调用方只需要 {@code SessionService.getOrCreate}，拿不到也不需要本类。
 */
final class SessionId {

  /** 三段之间的分隔符；同时也是三段的禁用字符——出现即无法唯一还原三元组。 */
  private static final String SEPARATOR = ":";

  /** A1 把 {@code session_id} 定为 {@code VARCHAR(128)}，超长在写入期才炸，不如在这里就说清楚。 */
  private static final int MAX_LENGTH = 128;

  private SessionId() {}

  /**
   * 拼接会话标识。
   *
   * @param channel 接入渠道
   * @param userId 用户标识
   * @param profileName 所属 Agent 名
   * @return 形如 {@code cli:local:daily-weather} 的标识
   * @throws IllegalArgumentException 任一段为空或含分隔符，或拼接结果超过 128 字符——这些是调用方错误， 静默产出脏标识会让两条历史莫名其妙地合并
   */
  static String of(String channel, String userId, String profileName) {
    requireSegment(channel, "channel");
    requireSegment(userId, "userId");
    requireSegment(profileName, "profileName");
    String id = channel + SEPARATOR + userId + SEPARATOR + profileName;
    if (id.length() > MAX_LENGTH) {
      throw new IllegalArgumentException(
          "会话标识超长（" + id.length() + " > " + MAX_LENGTH + "）：请缩短 Agent 名或用户标识");
    }
    return id;
  }

  private static void requireSegment(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("会话标识的 " + name + " 不能为空");
    }
    if (value.contains(SEPARATOR)) {
      throw new IllegalArgumentException("会话标识的 " + name + " 不能含 '" + SEPARATOR + "'：" + value);
    }
  }
}
