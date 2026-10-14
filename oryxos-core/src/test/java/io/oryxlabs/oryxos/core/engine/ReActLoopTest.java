package io.oryxlabs.oryxos.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.oryxlabs.oryxos.core.llm.LlmClient;
import io.oryxlabs.oryxos.core.profile.Profile;
import io.oryxlabs.oryxos.core.session.Session;
import io.oryxlabs.oryxos.core.session.SessionMessage;
import io.oryxlabs.oryxos.core.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * {@link ReActLoop} 的验收测试（课件第 17 节「四、验收 harness」）。
 *
 * <p>守护的四条：模型不要工具时<b>一轮收尾</b>（SC-001）；要工具时执行并<b>接上下一轮</b>（SC-002）；转满上限<b>强制收尾且恰好 N
 * 轮</b>（SC-003）；每轮响应与工具结果<b>按序累积</b>（SC-004）。全程替身，不碰网络。
 */
class ReActLoopTest {

  private static final String SESSION_ID = "cli:alice:demo";

  private PromptBuilder promptBuilder;
  private LlmClient llmClient;
  private ToolExecutor toolExecutor;
  private ReActLoop loop;
  private Session session;

  @BeforeEach
  void setUp() {
    promptBuilder = mock(PromptBuilder.class);
    llmClient = mock(LlmClient.class);
    toolExecutor = mock(ToolExecutor.class);
    when(promptBuilder.build(any(), any())).thenReturn(new Prompt("本轮内容由替身提供"));
    loop = new ReActLoop(promptBuilder, llmClient, toolExecutor);
    session = Session.create(SESSION_ID, "cli", "alice", "demo");
  }

  @Test
  @DisplayName("一轮能收尾：模型恰好被调 1 次，工具 0 次")
  void stopsAfterSingleRoundWhenNoToolCalls() {
    when(llmClient.chat(anyString(), any(), any())).thenReturn(textResponse("我是 OryxOS 的演示助手。"));

    String reply = loop.run(session, "你是谁？", profileWith(10));

    assertThat(reply).isEqualTo("我是 OryxOS 的演示助手。");
    verify(llmClient, times(1)).chat(eq(SESSION_ID), any(), any());
    verifyNoInteractions(toolExecutor);
    assertThat(session.messages()).hasSize(2);
  }

  @Test
  @DisplayName("多轮能接上：工具结果出现在下一轮发出去的上下文里")
  void executesToolAndFeedsResultIntoNextRound() {
    when(llmClient.chat(anyString(), any(), any()))
        .thenReturn(toolCallResponse("我查一下。", call("call-1", "http_get", "{}")))
        .thenReturn(textResponse("今天晴，26 度，穿短袖就行。"));
    when(toolExecutor.execute(eq(SESSION_ID), any())).thenReturn(ToolResult.success("晴，26 度"));
    List<Integer> messageCountAtEachBuild = new ArrayList<>();
    when(promptBuilder.build(any(), any()))
        .thenAnswer(
            invocation -> {
              messageCountAtEachBuild.add(((Session) invocation.getArgument(0)).messages().size());
              return new Prompt("本轮内容由替身提供");
            });

    String reply = loop.run(session, "今天天气怎么样？", profileWith(10));

    assertThat(reply).isEqualTo("今天晴，26 度，穿短袖就行。");
    verify(toolExecutor, times(1)).execute(eq(SESSION_ID), any());
    // 第一次组装只看得见用户消息；第二次组装看得见「用户 + 模型响应 + 工具结果」——工具结果确实回灌进了下一轮
    assertThat(messageCountAtEachBuild).containsExactly(1, 3);
    assertThat(session.messages())
        .anyMatch(
            message ->
                message.role() == SessionMessage.Role.TOOL && "晴，26 度".equals(message.content()));
  }

  @Test
  @DisplayName("模型一直要调工具：转满最大轮数强制停")
  void hitsMaxIterationsAndStops() {
    when(llmClient.chat(anyString(), any(), any()))
        .thenReturn(toolCallResponse("我再查一下。", call("call-1", "http_get", "{}")));
    when(toolExecutor.execute(eq(SESSION_ID), any())).thenReturn(ToolResult.success("晴，26 度"));

    String reply = loop.run(session, "今天天气怎么样？", profileWith(10));

    verify(llmClient, times(10)).chat(eq(SESSION_ID), any(), any());
    verify(toolExecutor, times(10)).execute(eq(SESSION_ID), any());
    assertThat(reply).contains("达到最大轮数");
  }

  @Test
  @DisplayName("上限可由 Agent 配置覆盖：配 3 就恰好调 3 次")
  void respectsConfiguredMaxIterations() {
    when(llmClient.chat(anyString(), any(), any()))
        .thenReturn(toolCallResponse("我再查一下。", call("call-1", "http_get", "{}")));
    when(toolExecutor.execute(eq(SESSION_ID), any())).thenReturn(ToolResult.success("晴，26 度"));

    String reply = loop.run(session, "今天天气怎么样？", profileWith(3));

    verify(llmClient, times(3)).chat(eq(SESSION_ID), any(), any());
    verify(toolExecutor, times(3)).execute(eq(SESSION_ID), any());
    assertThat(reply).contains("达到最大轮数");
  }

  @Test
  @DisplayName("每轮响应与工具结果都按序累积进会话")
  void accumulatesEveryRoundInOrder() {
    when(llmClient.chat(anyString(), any(), any()))
        .thenReturn(toolCallResponse("第一次查。", call("call-1", "http_get", "{}")))
        .thenReturn(toolCallResponse("第二次查。", call("call-2", "http_get", "{}")))
        .thenReturn(textResponse("查完了，今天晴。"));
    when(toolExecutor.execute(eq(SESSION_ID), any()))
        .thenReturn(ToolResult.success("第一次结果"))
        .thenReturn(ToolResult.success("第二次结果"));

    loop.run(session, "今天天气怎么样？", profileWith(10));

    assertThat(session.messages())
        .extracting(SessionMessage::role)
        .containsExactly(
            SessionMessage.Role.USER,
            SessionMessage.Role.ASSISTANT,
            SessionMessage.Role.TOOL,
            SessionMessage.Role.ASSISTANT,
            SessionMessage.Role.TOOL,
            SessionMessage.Role.ASSISTANT);
    assertThat(session.messages())
        .extracting(SessionMessage::content)
        .containsExactly("今天天气怎么样？", "第一次查。", "第一次结果", "第二次查。", "第二次结果", "查完了，今天晴。");
  }

  @Test
  @DisplayName("转满上限时最后一轮的工具照常执行")
  void lastRoundToolCallsStillExecuted() {
    when(llmClient.chat(anyString(), any(), any()))
        .thenReturn(toolCallResponse("再查一下。", call("call-1", "http_get", "{}")))
        .thenReturn(toolCallResponse("再查一下。", call("call-2", "http_get", "{}")));
    when(toolExecutor.execute(eq(SESSION_ID), any())).thenReturn(ToolResult.success("晴，26 度"));

    loop.run(session, "今天天气怎么样？", profileWith(2));

    verify(toolExecutor, times(2)).execute(eq(SESSION_ID), any());
    Set<String> requested =
        session.messages().stream()
            .flatMap(message -> message.toolCalls().stream())
            .map(AssistantMessage.ToolCall::id)
            .collect(Collectors.toSet());
    List<String> answered =
        session.messages().stream()
            .filter(message -> message.role() == SessionMessage.Role.TOOL)
            .map(SessionMessage::toolCallId)
            .toList();
    assertThat(answered).containsExactlyInAnyOrderElementsOf(requested);
  }

  @Test
  @DisplayName("模型响应没有 generation：抛 IllegalStateException")
  void throwsWhenResponseHasNoGeneration() {
    when(llmClient.chat(anyString(), any(), any())).thenReturn(new ChatResponse(List.of()));

    assertThrows(IllegalStateException.class, () -> loop.run(session, "今天天气怎么样？", profileWith(10)));
  }

  private static Profile profileWith(int maxIterations) {
    return new Profile(
        "demo",
        null,
        new Profile.Identity("demo", "你是演示助手。"),
        new Profile.ProviderSpec("deepseek", "deepseek-chat", null),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        new Profile.Settings(maxIterations, 20));
  }

  private static AssistantMessage.ToolCall call(String id, String name, String arguments) {
    return new AssistantMessage.ToolCall(id, "function", name, arguments);
  }

  private static ChatResponse textResponse(String text) {
    return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
  }

  private static ChatResponse toolCallResponse(String text, AssistantMessage.ToolCall... calls) {
    return new ChatResponse(
        List.of(
            new Generation(
                AssistantMessage.builder().content(text).toolCalls(List.of(calls)).build())));
  }
}
