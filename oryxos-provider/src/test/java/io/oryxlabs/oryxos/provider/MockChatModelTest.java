package io.oryxlabs.oryxos.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * {@link MockChatModel} 的脚本行为（第 27 节「验收 harness」）。
 *
 * <p>守点：第一轮恰请求一次 {@code save_memory} 且参数带着用户原话；本轮已有工具结果时直接给最终答复；
 * <b>同一会话的第二句话仍会重新调工具</b>（判据只看最后一条用户消息之后，不看全量历史）；每轮用量非零， 否则审计表的 token 列会因为「模型是假的」而全是 0。
 */
class MockChatModelTest {

  private final MockChatModel model = new MockChatModel();

  @Test
  @DisplayName("第一轮：请求一次 save_memory，参数里是用户这一轮说的话")
  void firstCall_requestsSaveMemoryWithUserText() {
    ChatResponse response = model.call(prompt(new UserMessage("记住：我喜欢喝美式咖啡")));

    AssistantMessage output = response.getResult().getOutput();
    assertThat(output.getToolCalls()).hasSize(1);
    AssistantMessage.ToolCall call = output.getToolCalls().get(0);
    assertThat(call.name()).isEqualTo(MockChatModel.SCRIPTED_TOOL);
    assertThat(call.arguments()).contains("记住：我喜欢喝美式咖啡").contains("content");
    assertThat(output.getText()).isEmpty();
  }

  @Test
  @DisplayName("第二轮：本轮已有工具结果 → 直接给最终答复，不再请求工具（ReAct 恰好两轮）")
  void secondCall_returnsFinalReplyWithoutToolCall() {
    List<Message> messages = new ArrayList<>();
    messages.add(new SystemMessage("你是 OryxOS 的助手"));
    messages.add(new UserMessage("记住：我喜欢喝美式咖啡"));
    messages.add(toolCallMessage("mock-call-2"));
    messages.add(toolResponse("mock-call-2"));

    ChatResponse response = model.call(new Prompt(messages));

    AssistantMessage output = response.getResult().getOutput();
    assertThat(output.getToolCalls()).isEmpty();
    assertThat(output.getText()).isEqualTo(MockChatModel.FINAL_REPLY);
  }

  @Test
  @DisplayName("同一会话的第二句话：上一轮的工具结果不算数，仍会重新请求一次工具")
  void newUserMessageAfterToolRound_requestsToolAgain() {
    List<Message> messages = new ArrayList<>();
    messages.add(new SystemMessage("你是 OryxOS 的助手"));
    messages.add(new UserMessage("记住：我喜欢喝美式咖啡"));
    messages.add(toolCallMessage("mock-call-2"));
    messages.add(toolResponse("mock-call-2"));
    messages.add(new AssistantMessage(MockChatModel.FINAL_REPLY));
    messages.add(new UserMessage("记住：我住在北京"));

    ChatResponse response = model.call(new Prompt(messages));

    AssistantMessage.ToolCall call = response.getResult().getOutput().getToolCalls().get(0);
    assertThat(call.arguments()).contains("我住在北京");
  }

  @Test
  @DisplayName("每轮用量非零：审计表的 token 列不该因为模型是假的而全是 0")
  void everyCallCarriesNonZeroUsage() {
    ChatResponse response = model.call(prompt(new UserMessage("你好")));

    assertThat(response.getMetadata().getUsage().getPromptTokens()).isPositive();
    assertThat(response.getMetadata().getUsage().getCompletionTokens()).isPositive();
  }

  private Prompt prompt(Message... messages) {
    List<Message> list = new ArrayList<>();
    list.add(new SystemMessage("你是 OryxOS 的助手"));
    list.addAll(List.of(messages));
    return new Prompt(list);
  }

  private AssistantMessage toolCallMessage(String id) {
    return AssistantMessage.builder()
        .content("")
        .toolCalls(
            List.of(
                new AssistantMessage.ToolCall(
                    id, "function", MockChatModel.SCRIPTED_TOOL, "{\"content\":\"x\"}")))
        .build();
  }

  private ToolResponseMessage toolResponse(String id) {
    return ToolResponseMessage.builder()
        .responses(
            List.of(new ToolResponseMessage.ToolResponse(id, MockChatModel.SCRIPTED_TOOL, "已记录")))
        .build();
  }
}
