package io.oryxlabs.oryxos.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * 内置 mock 模型：不连任何真实模型、不需要 key，按固定脚本驱动一次确定性的 ReAct（第 27 节）。
 *
 * <p><b>它只替换「模型」这一站</b>：{@code ReActLoop} / {@code PromptBuilder} / {@code ToolExecutor} / {@code
 * MemoryService} / {@code SessionService} / 审计落库全部走真实路径。所以「无 key 也能验全链路」验的是接线， 不是造假数据——mock
 * 产出的会话、记忆、审计记录，和真模型跑出来的落在同一批表里。
 *
 * <p><b>脚本</b>：本轮还没有工具结果时，请求一次 {@code save_memory}（工具名与参数逐字按真实工具契约，参数内容是用户这一轮说的话）；
 * 本轮已经有工具结果时，直接返回最终答复。判据只看「最后一条用户消息之后」，不看全量历史——否则同一会话的第二句话会被上一轮留下的工具结果误判成「已经调过」。
 *
 * <p><b>挂在显式映射表的 {@code mock} 名下</b>（章程原则 10）：不做按类型扫描，不走 Spring AI 的自动装配； 由 {@link
 * ProviderConfiguration#providerMap} 在配置里出现 {@code name: mock} 时逐条构造。
 */
public class MockChatModel implements ChatModel {

  /** 映射表里的厂商名：Agent 的 {@code provider.name: mock} 选中的就是它。 */
  public static final String PROVIDER_NAME = "mock";

  /** 脚本第一轮要调的工具名。真实的 {@code save_memory} 会把内容写进 {@code MEMORY.md}，给全链路一个可观测的文件写入。 */
  public static final String SCRIPTED_TOOL = "save_memory";

  /** 脚本第二轮的最终答复；固定值让断言不必依赖模型措辞。 */
  public static final String FINAL_REPLY = "好的，我记住了。";

  /** 每轮都给非零用量：审计表的 token 列不该因为「模型是假的」而全是 0。 */
  private static final DefaultUsage USAGE = new DefaultUsage(11, 7);

  private static final ObjectMapper JSON = new ObjectMapper();

  @Override
  public ChatResponse call(Prompt prompt) {
    List<Message> messages = prompt.getInstructions();
    int lastUserIndex = lastUserIndex(messages);
    boolean toolAlreadyRan =
        messages.subList(lastUserIndex + 1, messages.size()).stream()
            .anyMatch(ToolResponseMessage.class::isInstance);
    String userText =
        lastUserIndex < 0 ? "" : ((UserMessage) messages.get(lastUserIndex)).getText();
    if (toolAlreadyRan || userText.isBlank()) {
      return reply(new AssistantMessage(FINAL_REPLY));
    }
    String toolCallId = "mock-call-" + (lastUserIndex + 1);
    AssistantMessage message =
        AssistantMessage.builder()
            .content("")
            .toolCalls(
                List.of(
                    new AssistantMessage.ToolCall(
                        toolCallId, "function", SCRIPTED_TOOL, arguments(userText))))
            .build();
    return reply(message);
  }

  private static int lastUserIndex(List<Message> messages) {
    for (int i = messages.size() - 1; i >= 0; i--) {
      if (messages.get(i) instanceof UserMessage) {
        return i;
      }
    }
    return -1;
  }

  private static String arguments(String content) {
    try {
      return JSON.writeValueAsString(Map.of("content", content));
    } catch (JsonProcessingException e) {
      // 参数序列化失败说明内容本身带出了协议问题；吞掉会变成「工具静默没被调用」，必须当场炸
      throw new IllegalStateException("mock 工具调用参数序列化失败", e);
    }
  }

  private static ChatResponse reply(AssistantMessage message) {
    return new ChatResponse(
        List.of(new Generation(message)), ChatResponseMetadata.builder().usage(USAGE).build());
  }
}
