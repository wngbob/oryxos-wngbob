package io.oryxlabs.oryxos.core.engine;

import io.oryxlabs.oryxos.core.llm.LlmClient;
import io.oryxlabs.oryxos.core.profile.Profile;
import io.oryxlabs.oryxos.core.session.Session;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * ReAct 主循环：把「组装 → 调模型 → 判是否收尾 → 执行工具 → 回灌」串成一个循环（FR-001 / FR-002）。
 *
 * <p>它自己不调模型、也不执行工具，而是<b>调度</b>：想的时候通过 {@link LlmClient} 调一次大模型，做的时候把工具交给 {@link
 * ToolExecutor}。循环代码本身很短，难的是循环里要照顾的那些边界。
 *
 * <p><b>四条行为约定</b>：
 *
 * <ol>
 *   <li><b>轮数判断在循环头</b>：第 N 轮的模型响应照常累积、该轮提出的工具<b>照常执行</b>，会话历史以「工具请求 + 工具结果」 成对收尾，不留悬空请求。上限取 {@code
 *       profile.settings().maxIterations()}，默认 10。
 *   <li><b>{@code max_iterations} 的语义是「模型调用次数上限」</b>：配 3 就恰好调 3 次模型，一轮不多。
 *   <li><b>工具顺序执行</b>：一次响应里多个工具调用逐个执行，MUST NOT 并行。
 *   <li><b>不感知消息来源</b>：不认识 channel、不认识 CLI / Web / 定时（FR-008、章程原则 IX）。它只认识 {@code Session} + 用户消息 +
 *       {@code Profile}。
 * </ol>
 *
 * <p><b>同步阻塞</b>：不引入响应式编程、不返回异步句柄、不自建线程池（章程原则 I）。
 */
public class ReActLoop {

  /** 转满上限时的答复。课件 harness 断言 {@code reply.contains("达到最大轮数")}，措辞逐字保真。 */
  private static final String MAX_ITERATIONS_REPLY = "达到最大轮数，已停止";

  private final PromptBuilder promptBuilder;
  private final LlmClient llmClient;
  private final ToolExecutor toolExecutor;

  /**
   * @param promptBuilder 组装本轮发给模型的内容
   * @param llmClient 调模型的接缝（第 18 节由 {@code ProviderService} 实现）
   * @param toolExecutor 工具执行的唯一落点
   */
  public ReActLoop(PromptBuilder promptBuilder, LlmClient llmClient, ToolExecutor toolExecutor) {
    this.promptBuilder = promptBuilder;
    this.llmClient = llmClient;
    this.toolExecutor = toolExecutor;
  }

  /**
   * 跑完一次处理。
   *
   * @param session 会话；本方法把用户消息、每轮模型响应与工具结果按序累积进去
   * @param userMessage 本次处理的用户输入
   * @param profile 当前 Agent 的运行时配置
   * @return 模型的最终答复；转满上限时是带说明的固定答复
   * @throws IllegalStateException 模型响应里没有 generation——这不是「工具失败」，是 Provider 侧故障，不该被伪装成一次正常的空答复
   */
  public String run(Session session, String userMessage, Profile profile) {
    session.appendUserMessage(userMessage);
    int maxIterations = profile.settings().maxIterations();
    // 轮数判断在循环头：第 N 轮的响应照常累积、该轮的工具照常执行，历史以「请求 + 结果」成对收尾
    for (int round = 0; round < maxIterations; round++) {
      Prompt prompt = promptBuilder.build(session, profile);
      ChatResponse response = llmClient.chat(session.id(), profile, prompt);
      if (response.getResult() == null) {
        throw new IllegalStateException("模型响应缺少 generation（Provider 侧故障），会话 " + session.id());
      }
      AssistantMessage output = response.getResult().getOutput();
      session.appendAssistantMessage(output.getText(), output.getToolCalls());
      if (!response.hasToolCalls()) {
        return output.getText();
      }
      for (AssistantMessage.ToolCall call : output.getToolCalls()) {
        session.appendToolResult(call.id(), toolExecutor.execute(session.id(), call));
      }
    }
    return MAX_ITERATIONS_REPLY;
  }
}
