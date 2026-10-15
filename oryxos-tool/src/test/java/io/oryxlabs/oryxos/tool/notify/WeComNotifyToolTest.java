package io.oryxlabs.oryxos.tool.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.oryxlabs.oryxos.core.tool.ToolResult;
import io.oryxlabs.oryxos.tool.sandbox.Sandbox;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ★ 端到端回归：{@code notify} 工具 + <b>真实</b>企微适配器 + 假注册表 + 假 webhook。
 *
 * <p><b>为什么必须单独有这个类</b>：既有的 {@code NotifyToolsTest} 注入的是 <b>mock</b>
 * 适配器，它只能证「工具把活交给了适配器」，证不了「适配器有没有把 HTTP 200 + 业务错误码误判成成功」——而那正是 2026-10-14 人工实测抓到的原始缺陷（{@code
 * notify} 返回「已推送」、群里一条消息都没有）。这条链路上少任何一环，缺陷都能藏住。
 *
 * <p><b>本类的断言就是那次实测的对号入座</b>：对端回 {@code HTTP 200} + {@code errcode:40008} 时，{@code notify} MUST
 * 抛异常、MUST NOT 返回 {@code ToolResult.success}——抛出去才能走到 {@code ToolExecutor} 的失败审计路径（{@code
 * tool_invocations} 里留下 {@code success=false}）。
 *
 * <p><b>路由表照装配处同构地写全</b>：飞书 / Lark / 企微三档都在，这样「走的是企微那一档」才是有意义的断言——若表里只有企微， 「发出去的报文是企微方言」这条就恒真了。
 */
class WeComNotifyToolTest {

  private static final String HOOK_PATH = "/cgi-bin/webhook/send?key=KEY-abc123";

  private MockWebServer server;

  @BeforeEach
  void startFakeWebhook() throws IOException {
    server = new MockWebServer();
    server.start();
  }

  @AfterEach
  void stopFakeWebhook() throws IOException {
    server.close();
  }

  private static MockResponse ok() {
    return new MockResponse.Builder().code(200).body("{\"errcode\":0,\"errmsg\":\"ok\"}").build();
  }

  /** 实测抓到的那一次：报文形状不对时企微的真实响应，HTTP 层是成功的。 */
  private static MockResponse businessError() {
    return new MockResponse.Builder()
        .code(200)
        .body("{\"errcode\":40008,\"errmsg\":\"invalid message type\"}")
        .build();
  }

  /** 假注册表：按给定的 type 与地址造一个通知目标，替掉 SQLite 那一层。 */
  private NotifyChannelRegistry registryOf(String type) {
    String url = server.url(HOOK_PATH).toString();
    return channelName -> new NotifyTarget(type, Map.of("url", url));
  }

  /** 与装配处同构的路由表：四档都在，取值与别名都留在这里（路由类本身厂商无关）。 */
  private static NotifyChannelAdapter router() {
    Map<String, NotifyChannelAdapter> byType = new LinkedHashMap<>();
    byType.put("webhook", new WebhookNotifyAdapter());
    NotifyChannelAdapter feishu = new FeishuNotifyAdapter();
    byType.put("feishu", feishu);
    byType.put("lark", feishu);
    byType.put("wecom", new WeComNotifyAdapter());
    return new RoutingNotifyChannelAdapter(byType);
  }

  private NotifyTools toolFor(String type) {
    return new NotifyTools(mock(Sandbox.class), router(), registryOf(type));
  }

  @Test
  @DisplayName("★ 对端回 HTTP 200 + 业务码非 0 时 notify 抛异常，MUST NOT 返回 success（原始缺陷回归）")
  void notifyThrowsInsteadOfClaimingSuccessWhenWeComRejectsDialect() {
    server.enqueue(businessError());

    NotifyTools notifyTools = toolFor("wecom");

    assertThatThrownBy(() -> notifyTools.notify("今天晴，26 度", "team-wecom"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("40008");
    // 一次请求都没成功，也一次都不重试
    assertThat(server.getRequestCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("对端回业务码 0 时 notify 返回 success")
  void notifyReturnsSuccessWhenWeComAcceptsTheMessage() {
    server.enqueue(ok());

    ToolResult result = toolFor("wecom").notify("今天晴，26 度", "team-wecom");

    assertThat(result.success()).isTrue();
    assertThat(result.content()).isEqualTo("已推送");
  }

  @Test
  @DisplayName("★ 端到端：经路由发出的报文真的是企微方言（msgtype 无下划线），不是通用档也不是飞书档的")
  void notifySendsWeComDialectThroughTheRouter() throws Exception {
    server.enqueue(ok());

    toolFor("wecom").notify("今天晴，26 度", "team-wecom");

    RecordedRequest request = server.takeRequest();
    String body = request.getBody().utf8();
    assertThat(body).contains("\"msgtype\":\"text\"");
    assertThat(body).contains("\"content\":\"今天晴，26 度\"");
    // 飞书档的键名 MUST NOT 出现——表里两档都在，这条才排除得了「发到了飞书那一档」
    assertThat(body).doesNotContain("msg_type");
  }

  @Test
  @DisplayName("★ 端到端：type 拼错时在路由处就报错，一次请求都不发（不再静默假成功）")
  void notifyFailsFastOnTypoInChannelTypeWithoutSendingAnyRequest() {
    assertThatThrownBy(() -> toolFor("wecomx").notify("内容", "team-wecom"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("wecomx");

    assertThat(server.getRequestCount()).isZero();
  }
}
