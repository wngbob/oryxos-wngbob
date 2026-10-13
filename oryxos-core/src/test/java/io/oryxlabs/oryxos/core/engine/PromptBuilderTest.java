package io.oryxlabs.oryxos.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.oryxlabs.oryxos.core.context.ContextLoader;
import io.oryxlabs.oryxos.core.memory.MemorySource;
import io.oryxlabs.oryxos.core.profile.Profile;
import io.oryxlabs.oryxos.core.session.Session;
import io.oryxlabs.oryxos.core.tool.OryxTool;
import io.oryxlabs.oryxos.core.tool.ToolCatalog;
import io.oryxlabs.oryxos.core.tool.ToolResult;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

/**
 * {@link PromptBuilder} 的验收测试（课件第 17 节「四、验收 harness」）。
 *
 * <p>守护的四条：四部分顺序与来源一一对应；历史超 N 轮被截断且<b>不以工具消息开头</b>（SC-005）；system prompt 末尾含当前日期时间； 工具说明只翻译不执行（章程原则
 * III）。全程替身，不碰网络。
 */
class PromptBuilderTest {

  private static final String PERSONA = "你是 OryxOS 的演示助手。";
  private static final String FILE_PART = "项目说明：这是一个演示项目。";
  private static final String MEMORY = "长期记忆：用户偏好简洁的回答。";

  private ContextLoader contextLoader;
  private MemorySource memorySource;
  private ToolCatalog toolCatalog;
  private Profile profile;
  private PromptBuilder builder;

  @BeforeEach
  void setUp() {
    contextLoader = mock(ContextLoader.class);
    memorySource = mock(MemorySource.class);
    toolCatalog = mock(ToolCatalog.class);
    when(contextLoader.load(any())).thenReturn(FILE_PART);
    when(memorySource.longTermMemory(any())).thenReturn(Optional.of(MEMORY));
    when(toolCatalog.availableFor(any())).thenReturn(List.of(fakeTool("http_get")));
    profile = profileOf(20);
    builder = new PromptBuilder(contextLoader, memorySource, toolCatalog);
  }

  @Test
  @DisplayName("四部分顺序与来源一一对应")
  void ordersFourPartsInFixedSequence() {
    Session session = Session.create("s-1", "demo");
    session.appendUserMessage("今天天气怎么样？");
    session.appendAssistantMessage("稍等，我查一下。", List.of());

    Prompt prompt = builder.build(session, profile);
    List<Message> instructions = prompt.getInstructions();

    assertThat(instructions).hasSize(4);
    assertThat(instructions.get(0)).isInstanceOf(SystemMessage.class);
    assertThat(((SystemMessage) instructions.get(0)).getText())
        .contains(PERSONA)
        .contains(FILE_PART);
    assertThat(instructions.get(1)).isInstanceOf(SystemMessage.class);
    assertThat(((SystemMessage) instructions.get(1)).getText()).isEqualTo(MEMORY);
    assertThat(instructions.get(2)).isInstanceOf(UserMessage.class);
    assertThat(((UserMessage) instructions.get(2)).getText()).isEqualTo("今天天气怎么样？");
    assertThat(instructions.get(3)).isInstanceOf(AssistantMessage.class);

    assertThat(toolCallbacksOf(prompt)).hasSize(1);
    assertThat(toolCallbacksOf(prompt).get(0).getToolDefinition().name()).isEqualTo("http_get");
  }

  @Test
  @DisplayName("长期记忆为空：整条不出现，不塞占位文本")
  void skipsMemoryPartWhenAbsent() {
    when(memorySource.longTermMemory(any())).thenReturn(Optional.empty());
    Session session = Session.create("s-1", "demo");
    session.appendUserMessage("今天天气怎么样？");
    session.appendAssistantMessage("稍等，我查一下。", List.of());

    List<Message> instructions = builder.build(session, profile).getInstructions();

    assertThat(instructions).hasSize(3);
    assertThat(instructions.get(1)).isInstanceOf(UserMessage.class);
    assertThat(instructions.subList(1, instructions.size()))
        .noneMatch(SystemMessage.class::isInstance);
  }

  @Test
  @DisplayName("system prompt 末尾附当前日期时间")
  void appendsCurrentDateTimeAtEndOfSystemPrompt() {
    Session session = Session.create("s-1", "demo");
    session.appendUserMessage("你是谁？");

    String systemPrompt = systemPromptOf(builder.build(session, profile));

    assertThat(systemPrompt).contains(PERSONA).contains(FILE_PART);
    String lastLine = lastLineOf(systemPrompt);
    assertThat(lastLine).startsWith("当前时间：");
    assertThat(lastLine).contains(LocalDate.now().toString());
  }

  @Test
  @DisplayName("历史超 N 轮：只保留最近 N 轮")
  void truncatesToLastNUserTurns() {
    Session session = sessionWithTurns(25);

    List<Message> history = historyOf(builder.build(session, profile));

    assertThat(history).hasSize(40);
    assertThat(((UserMessage) history.get(0)).getText()).isEqualTo("用户第 6 问");
    assertThat(history).noneMatch(message -> textOf(message).contains("用户第 5 问"));
    assertThat(history).noneMatch(message -> textOf(message).contains("用户第 1 问"));
    // 截断不改动 Session 本身：事后仍能查到本次全部往来
    assertThat(session.messages()).hasSize(50);
  }

  @Test
  @DisplayName("截断只切在用户消息边界：历史从不以工具消息开头")
  void neverStartsHistoryWithToolMessage() {
    Session session = Session.create("s-1", "demo");
    for (int i = 1; i <= 25; i++) {
      session.appendUserMessage("用户第 " + i + " 问");
      session.appendAssistantMessage(
          "第 " + i + " 轮",
          List.of(new AssistantMessage.ToolCall("call-" + i, "function", "http_get", "{}")));
      session.appendToolResult("call-" + i, ToolResult.success("结果 " + i));
    }

    List<Message> history = historyOf(builder.build(session, profile));

    assertThat(history).isNotEmpty();
    assertThat(history.get(0)).isInstanceOf(UserMessage.class);
    // 每一条工具结果都能在同段历史里找到配对的请求（反查必然命中，不留悬空片段）
    List<String> requestedIds =
        history.stream()
            .filter(AssistantMessage.class::isInstance)
            .map(AssistantMessage.class::cast)
            .flatMap(message -> message.getToolCalls().stream())
            .map(AssistantMessage.ToolCall::id)
            .toList();
    List<ToolResponseMessage> toolMessages =
        history.stream()
            .filter(ToolResponseMessage.class::isInstance)
            .map(ToolResponseMessage.class::cast)
            .toList();
    assertThat(toolMessages).hasSize(20);
    assertThat(toolMessages)
        .allSatisfy(
            message ->
                assertThat(requestedIds)
                    .containsAll(
                        message.getResponses().stream()
                            .map(ToolResponseMessage.ToolResponse::id)
                            .toList()));
  }

  @Test
  @DisplayName("恰好 N 轮：不截断")
  void keepsHistoryIntactWhenExactlyN() {
    Session session = sessionWithTurns(20);

    List<Message> history = historyOf(builder.build(session, profile));

    assertThat(history).hasSize(40);
    assertThat(((UserMessage) history.get(0)).getText()).isEqualTo("用户第 1 问");
  }

  @Test
  @DisplayName("工具说明只翻译不执行：产物 call() 抛 UnsupportedOperationException")
  void toolCallbackThrowsOnCall() {
    Session session = Session.create("s-1", "demo");
    session.appendUserMessage("今天天气怎么样？");

    var callback = toolCallbacksOf(builder.build(session, profile)).get(0);

    assertThrows(UnsupportedOperationException.class, () -> callback.call("{}"));
  }

  @Test
  @DisplayName("工具消息的 name 由同段历史的 toolCalls 反查得到")
  void reversesToolNameFromHistory() {
    Session session = Session.create("s-1", "demo");
    session.appendUserMessage("今天天气怎么样？");
    session.appendAssistantMessage(
        "我查一下。", List.of(new AssistantMessage.ToolCall("call-1", "function", "http_get", "{}")));
    session.appendToolResult("call-1", ToolResult.success("晴，26 度"));

    List<Message> history = historyOf(builder.build(session, profile));

    ToolResponseMessage toolMessage = (ToolResponseMessage) history.get(history.size() - 1);
    assertThat(toolMessage.getResponses()).hasSize(1);
    assertThat(toolMessage.getResponses().get(0).id()).isEqualTo("call-1");
    assertThat(toolMessage.getResponses().get(0).name()).isEqualTo("http_get");
    assertThat(toolMessage.getResponses().get(0).responseData()).isEqualTo("晴，26 度");
  }

  private static Session sessionWithTurns(int turns) {
    Session session = Session.create("s-1", "demo");
    for (int i = 1; i <= turns; i++) {
      session.appendUserMessage("用户第 " + i + " 问");
      session.appendAssistantMessage("助手第 " + i + " 答", List.of());
    }
    return session;
  }

  private static Profile profileOf(int maxHistoryTurns) {
    return new Profile(
        "demo",
        null,
        new Profile.Identity("demo", PERSONA),
        new Profile.ProviderSpec("deepseek", "deepseek-chat", null),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        new Profile.Settings(null, maxHistoryTurns));
  }

  private static OryxTool fakeTool(String name) {
    return new OryxTool() {
      @Override
      public String getName() {
        return name;
      }

      @Override
      public String getDescription() {
        return "假工具，只用于断言翻译产物";
      }

      @Override
      public String getInputSchema() {
        return "{\"type\":\"object\"}";
      }

      @Override
      public ToolResult execute(String inputJson) {
        return ToolResult.success("ok");
      }
    };
  }

  private static String systemPromptOf(Prompt prompt) {
    return ((SystemMessage) prompt.getInstructions().get(0)).getText();
  }

  private static List<Message> historyOf(Prompt prompt) {
    return prompt.getInstructions().stream()
        .filter(message -> !(message instanceof SystemMessage))
        .toList();
  }

  private static List<org.springframework.ai.tool.ToolCallback> toolCallbacksOf(Prompt prompt) {
    return ((ToolCallingChatOptions) prompt.getOptions()).getToolCallbacks();
  }

  private static String textOf(Message message) {
    if (message instanceof UserMessage userMessage) {
      return userMessage.getText();
    }
    if (message instanceof AssistantMessage assistantMessage) {
      return assistantMessage.getText();
    }
    if (message instanceof SystemMessage systemMessage) {
      return systemMessage.getText();
    }
    return "";
  }

  private static String lastLineOf(String text) {
    return text.lines().reduce((previous, current) -> current).orElse("");
  }
}
