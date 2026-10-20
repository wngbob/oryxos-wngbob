package io.oryxlabs.oryxos.web.error;

/**
 * 会话不存在（第 26 节）：按标识查不到任何一行。
 *
 * <p>与「会话已归档」不同：归档过的会话查得到、也仍有历史，只是不能再收消息。 两种情况的对外文案分别是 {@code session not found: <id>} 与 {@code
 * session is archived: <id>}（已定字面量）。
 */
public class SessionNotFoundException extends RuntimeException {

  /**
   * @param sessionId 查不到的会话标识
   */
  public SessionNotFoundException(String sessionId) {
    super("session not found: " + sessionId);
  }
}
