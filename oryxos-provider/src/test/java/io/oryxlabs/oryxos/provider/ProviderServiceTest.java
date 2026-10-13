package io.oryxlabs.oryxos.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.oryxlabs.oryxos.core.profile.Profile;
import io.oryxlabs.oryxos.core.tool.OryxTool;
import io.oryxlabs.oryxos.core.tool.ToolResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;

/**
 * Provider 的核心回归测试。三个关键守点：按名路由不串台、未知名报错含原始名、请求里关闭了自动工具执行。
 *
 * <p>{@code ChatModel} 全部 mock 掉——不联网、不花钱、秒级跑完。
 */
class ProviderServiceTest {

  private static final String SESSION_ID = "s-1";

  private static final String SCHEMA =
      "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}";

  private ChatModel deepseek;
  private ChatModel kimi;
  private LlmCallRecorder audit;
  private ProviderService service;

  @BeforeEach
  void setUp() {
    deepseek = mock(ChatModel.class);
    kimi = mock(ChatModel.class);
    audit = mock(LlmCallRecorder.class);
    service =
        new ProviderService(
            Map.of("deepseek", deepseek, "kimi", kimi), mock(ToolSchemaAdapter.class), audit);
  }

  private static Profile profileOf(String providerName, String model) {
    return new Profile(
        "ops-agent",
        "运维 Agent",
        null,
        new Profile.ProviderSpec(providerName, model, null),
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private static ChatResponse okResponse(String text) {
    return new ChatResponse(
        List.of(new Generation(new AssistantMessage(text))),
        ChatResponseMetadata.builder().usage(new DefaultUsage(11, 7)).build());
  }

  /** 测试替身：只提供三项说明，execute 不被 Provider 模块调用。 */
  private static OryxTool oryxTool(String name, String description, String schema) {
    return new OryxTool() {
      @Override
      public String getName() {
        return name;
      }

      @Override
      public String getDescription() {
        return description;
      }

      @Override
      public String getInputSchema() {
        return schema;
      }

      @Override
      public ToolResult execute(String inputJson) {
        throw new UnsupportedOperationException("测试替身不执行工具");
      }
    };
  }

  @Test
  @DisplayName("按名路由：两个 provider 不串台")
  void routesToNamedProviderOnly() {
    when(kimi.call(any(Prompt.class))).thenReturn(okResponse("hello from kimi"));

    ChatResponse response =
        service.chat(SESSION_ID, profileOf("kimi", "kimi-k2"), new Prompt("ping"));

    assertThat(response.getResult().getOutput().getText()).isEqualTo("hello from kimi");
    verify(kimi, times(1)).call(any(Prompt.class));
    // 「不串台」的直接证据：没被点名的那家一次都不能被调到。MUST NOT 删。
    verify(deepseek, never()).call(any(Prompt.class));
  }

  @Test
  @DisplayName("引用未声明的 provider：报错须含原始名字")
  void unknownProviderNameThrowsWithOriginalName() {
    Profile profile = profileOf("DeepSeek ", "deepseek-chat");

    assertThatThrownBy(() -> service.chat(SESSION_ID, profile, new Prompt("ping")))
        .isInstanceOf(ProviderNotFoundException.class)
        .hasMessageContaining("DeepSeek ");
  }

  @Test
  @DisplayName("调用失败：审计必须留下 success 为 false 的记录")
  void recordsAuditBeforeRethrowingOnFailure() {
    when(deepseek.call(any(Prompt.class))).thenThrow(new RuntimeException("connect timeout"));

    assertThatThrownBy(
            () ->
                service.chat(
                    SESSION_ID, profileOf("deepseek", "deepseek-chat"), new Prompt("ping")))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("timeout");

    // 断言的不是「没抛异常」，而是「抛了异常**并且**审计先落了账」。MUST NOT 删或弱化。
    verify(audit)
        .record(
            eq(SESSION_ID),
            eq("deepseek"),
            eq("deepseek-chat"),
            isNull(),
            eq(false),
            contains("timeout"),
            anyLong());
  }

  @Test
  @DisplayName("带工具 schema 调用：请求里关闭了自动执行")
  void callWithToolSchema_disablesAutoExecution() {
    when(deepseek.call(any(Prompt.class))).thenReturn(okResponse("ok"));
    List<ToolCallback> callbacks =
        new ToolSchemaAdapter().toToolCallbacks(List.of(oryxTool("read_file", "读一个文件", SCHEMA)));
    // 调用方把自动执行开着传进来，本服务必须把它掰成关闭——坑二的回归点
    DefaultToolCallingChatOptions.Builder incomingOptions = DefaultToolCallingChatOptions.builder();
    incomingOptions.toolCallbacks(callbacks);
    incomingOptions.internalToolExecutionEnabled(true);
    Prompt incoming = new Prompt("ping", incomingOptions.build());

    service.chat(SESSION_ID, profileOf("deepseek", "deepseek-chat"), incoming);

    ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
    verify(deepseek).call(captor.capture());
    ChatOptions sentOptions = captor.getValue().getOptions();
    // 任何把它改回开启的改动都必须让这条立刻变红，MUST NOT 删或放宽
    assertThat(ToolCallingChatOptions.isInternalToolExecutionEnabled(sentOptions)).isFalse();
    assertThat(((ToolCallingChatOptions) sentOptions).getToolCallbacks()).isNotEmpty();
  }
}
