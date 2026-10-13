package io.oryxlabs.oryxos.core.llm;

import io.oryxlabs.oryxos.core.profile.Profile;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * core 侧的「调一次模型」接缝（contracts/core-seams.md §1）。
 *
 * <p><b>为什么接口在 core、实现在能力模块</b>：章程硬约束 {@code oryxos-core} MUST NOT 反向依赖任何能力模块，而 {@code ReActLoop}
 * 又必须能调模型。裁决是接口放 core、实现放能力模块、Spring 注入——本节只定义与调用，测试一律用替身。
 *
 * <p><b>实现方</b>：{@code oryxos-provider} 的 {@code ProviderService}（装配在第 18 节，见
 * contracts/core-seams.md §5）。 签名与第 16 节契约 {@code ChatResponse chat(String, Profile, Prompt)}
 * 逐字一致，实现方加一个 {@code implements} 即可，不改方法体。
 *
 * <p><b>同步阻塞</b>：返回类型就是同步结果本身，不返回任何异步句柄、不引入响应式类型（章程原则 I）。
 */
public interface LlmClient {

  /**
   * 按 Profile 点名的厂商发起一次模型调用。
   *
   * @param sessionId 会话标识；实现方要把它写进 {@code llm_calls.session_id}，签名不带它就写不了审计（FR-014）
   * @param profile 决定用哪家厂商、哪个模型、什么温度
   * @param prompt 本轮发给模型的内容（四部分，见 contracts/engine-api.md §2）
   * @return 模型响应，原样返回——响应里可能带着模型「想调某个工具」的请求，但执行不在实现方，也不在 core 的调度逻辑里
   */
  ChatResponse chat(String sessionId, Profile profile, Prompt prompt);
}
