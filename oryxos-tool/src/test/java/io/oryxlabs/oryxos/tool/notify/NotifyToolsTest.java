package io.oryxlabs.oryxos.tool.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.oryxlabs.oryxos.core.tool.ToolResult;
import io.oryxlabs.oryxos.tool.sandbox.ActionType;
import io.oryxlabs.oryxos.tool.sandbox.Sandbox;
import io.oryxlabs.oryxos.tool.sandbox.SandboxAction;
import io.oryxlabs.oryxos.tool.sandbox.SandboxViolationException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 课件验收 harness 第二批：通知工具的三条守点——渠道解析、白名单先行、失败上抛。
 *
 * <p><b>三个接口全部 mock</b>：{@link Sandbox} / {@link NotifyChannelAdapter} / {@link
 * NotifyChannelRegistry}。 本类要证的是<b>调用顺序与调用对象</b>，不是「HTTP 真发出去了」——后者是 {@code
 * WebhookNotifyAdapterTest} 的活。 替身还让「白名单拒绝时一次都不发」这种负面路径能被摆出来。
 *
 * <p><b>★ 顺序断言必须用 {@code InOrder}</b>：两条独立的 {@code verify} 在顺序写反时<b>照样通过</b>——
 * 那正是本节最容易被写错、又最看不出来的地方。
 */
@ExtendWith(MockitoExtension.class)
class NotifyToolsTest {

  private static final String SAMPLE_URL = "https://example.invalid/webhook/team-lark";

  @Mock private Sandbox sandbox;

  @Mock private NotifyChannelAdapter adapter;

  @Mock private NotifyChannelRegistry registry;

  private NotifyTools notifyTools() {
    return new NotifyTools(sandbox, adapter, registry);
  }

  private static NotifyTarget targetWithUrl(String url) {
    return new NotifyTarget("webhook", Map.of("url", url));
  }

  @Test
  @DisplayName("发送前必须先过白名单校验")
  void enforceSandboxBeforeSending() {
    when(registry.resolve("team-lark")).thenReturn(targetWithUrl(SAMPLE_URL));

    notifyTools().notify("测试消息", "team-lark");

    InOrder inOrder = inOrder(sandbox, adapter);
    inOrder.verify(sandbox).enforce(any(SandboxAction.class));
    inOrder.verify(adapter).send(any(NotifyTarget.class), anyString());
    // 一次推送恰好一次 enforce + 一次 send
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  @DisplayName("白名单校验的动作是 HTTP_REQUEST，目标是解析出来的地址")
  void enforceChecksHttpRequestAgainstTheResolvedUrl() {
    when(registry.resolve("team-lark")).thenReturn(targetWithUrl(SAMPLE_URL));

    notifyTools().notify("内容", "team-lark");

    ArgumentCaptor<SandboxAction> action = ArgumentCaptor.forClass(SandboxAction.class);
    verify(sandbox).enforce(action.capture());
    assertThat(action.getValue().type()).isEqualTo(ActionType.HTTP_REQUEST);
    assertThat(action.getValue().target()).isEqualTo(SAMPLE_URL);
  }

  @Test
  @DisplayName("白名单校验不通过时，一次请求都不发")
  void doesNotSendWhenSandboxRejects() {
    when(registry.resolve("team-lark")).thenReturn(targetWithUrl(SAMPLE_URL));
    doThrow(new SandboxViolationException("域名不在白名单里"))
        .when(sandbox)
        .enforce(any(SandboxAction.class));

    assertThatThrownBy(() -> notifyTools().notify("内容", "team-lark"))
        .isInstanceOf(SandboxViolationException.class);

    verify(adapter, never()).send(any(), any());
  }

  @Test
  @DisplayName("渠道未配置时明确报错，不静默失败")
  void failsWhenNoChannelConfigured() {
    when(registry.resolve(isNull())).thenThrow(new IllegalStateException("未配置任何通知渠道，无法推送"));

    assertThatThrownBy(() -> notifyTools().notify("内容", null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("未配置任何通知渠道");

    verify(sandbox, never()).enforce(any());
    verify(adapter, never()).send(any(), any());
  }

  @Test
  @DisplayName("渠道名缺省时送到默认渠道：解析到哪个目标就发哪个")
  void defaultChannelWhenChannelNameIsMissing() {
    NotifyTarget defaultChannel = targetWithUrl("https://example.invalid/webhook/default");
    when(registry.resolve(any())).thenReturn(defaultChannel);

    notifyTools().notify("内容", null);
    notifyTools().notify("内容", "");
    notifyTools().notify("内容", "   ");

    // 空串 / 纯空白的「等同不传」口径归注册表，工具只负责原样传下去
    verify(adapter, times(3)).send(defaultChannel, "内容");
  }

  @Test
  @DisplayName("渠道名解析不到时明确报错，且不发")
  void failsWhenChannelNameUnknown() {
    when(registry.resolve("typo-lark"))
        .thenThrow(new IllegalStateException("未找到名为 'typo-lark' 的通知渠道"));

    assertThatThrownBy(() -> notifyTools().notify("内容", "typo-lark"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("typo-lark");

    verify(adapter, never()).send(any(), any());
  }

  @Test
  @DisplayName("成功返回不可重试的结果，且返回内容里不含渠道地址")
  void returnsSuccessWithoutLeakingChannelAddress() {
    when(registry.resolve("team-lark")).thenReturn(targetWithUrl(SAMPLE_URL));

    ToolResult result = notifyTools().notify("内容", "team-lark");

    assertThat(result.success()).isTrue();
    assertThat(result.retryable()).isFalse();
    // 返回值会进对话与审计，含 token 的地址不得出现在里面
    assertThat(result.content()).doesNotContain(SAMPLE_URL).doesNotContain("example.invalid");
  }

  @Test
  @DisplayName("换一档渠道实现只新增实现类：工具与接口一行不改就能接入")
  void acceptsAnotherAdapterImplementationWithoutAnyChange() {
    when(registry.resolve(isNull())).thenReturn(targetWithUrl(SAMPLE_URL));
    RecordingNotifyChannelAdapter anotherImplementation = new RecordingNotifyChannelAdapter();

    // 与生产路径唯一的差别是换了第三个构造参数：接口与调用方零改动
    ToolResult result =
        new NotifyTools(sandbox, anotherImplementation, registry).notify("内容", null);

    assertThat(anotherImplementation.sentContents).containsExactly("内容");
    assertThat(result.success()).isTrue();
  }

  /**
   * US3 场景 3 的替身：另一档 {@link NotifyChannelAdapter} 实现。
   *
   * <p>刻意写成内联的私有类而不是独立测试类——本节测试类的形状是固定的 4 个，这条只是核对「接口够不够用」。
   */
  private static final class RecordingNotifyChannelAdapter implements NotifyChannelAdapter {

    private final List<String> sentContents = new ArrayList<>();

    @Override
    public void send(NotifyTarget target, String content) {
      sentContents.add(content);
    }
  }
}
