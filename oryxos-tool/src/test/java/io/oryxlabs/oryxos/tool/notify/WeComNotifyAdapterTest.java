package io.oryxlabs.oryxos.tool.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

/**
 * 企微方言档的验收 harness：真实 {@link WeComNotifyAdapter} 打本地假 webhook。
 *
 * <p><b>本类存在的理由就是那条原始缺陷</b>：企微群机器人用 {@code HTTP 200} 承载业务错误，通用档只看状态码 → {@code notify}
 * 假报「已推送」。所以这里最要紧的两条是「{@code errcode:0} 才放行」与「{@code HTTP 200} + 业务错误码必须抛」——它们直接对号入座到那次实测 （企微回
 * {@code HTTP 200} + {@code errcode:40008 "invalid message type"}，群里一条都没有）。
 *
 * <p><b>用真实实现、不用 mock</b>：要证的正是「HTTP 真的发出去了、body 真的是企微方言、业务码真的被读了」。假服务起在 {@code localhost}，全程不碰外网。
 *
 * <p><b>没有签名相关用例</b>：企微群机器人只有 URL 里的 {@code key}（可选 IP 白名单），没有签名算法，故本档也不注入 {@code Clock}。
 * 飞书档那组签名用例（黄金值 / 省略字段）在本类没有对应物。
 */
class WeComNotifyAdapterTest {

  private static final ObjectMapper JSON = new ObjectMapper();

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

  private static MockResponse okWithBody(String body) {
    return new MockResponse.Builder().code(200).body(body).build();
  }

  /** 实测抓到的那一次：报文形状不对时企微的真实响应（HTTP 层是成功的）。 */
  private static MockResponse businessError() {
    return okWithBody("{\"errcode\":40008,\"errmsg\":\"invalid message type\"}");
  }

  private static NotifyTarget targetFor(MockWebServer fakeWebhook, String path) {
    return new NotifyTarget("wecom", Map.of("url", fakeWebhook.url(path).toString()));
  }

  private static JsonNode bodyOf(RecordedRequest request) throws IOException {
    return JSON.readTree(request.getBody().utf8());
  }

  // ---------- 报文形状：企微方言 ----------

  @Test
  @DisplayName("发送的报文是企微方言：msgtype 为 text、正文在 text.content 里，且没有多余字段")
  void postBodyCarriesWeComTextDialect() throws Exception {
    server.enqueue(ok());

    new WeComNotifyAdapter().send(targetFor(server, HOOK_PATH), "今天晴，26 度");

    RecordedRequest request = server.takeRequest();
    assertThat(request.getMethod()).isEqualTo("POST");
    assertThat(request.getHeaders().get("Content-Type")).contains("application/json");
    JsonNode body = bodyOf(request);
    assertThat(body.path("msgtype").asText()).isEqualTo("text");
    assertThat(body.path("text").path("content").asText()).isEqualTo("今天晴，26 度");
    assertThat(body.size()).isEqualTo(2);
  }

  @Test
  @DisplayName("★ 键名是 msgtype（无下划线），且 MUST NOT 出现飞书那个 msg_type")
  void bodyUsesMsgtypeWithoutUnderscoreUnlikeFeishu() throws Exception {
    server.enqueue(ok());

    new WeComNotifyAdapter().send(targetFor(server, HOOK_PATH), "内容");

    JsonNode body = bodyOf(server.takeRequest());
    // 两家的报文是镜像的：企微 msgtype + text.content，飞书 msg_type + content.text。
    // 抄错一处企微会回 errcode 非 0，而那种失败在本档正是要被 fail-closed 抓住的——所以键名本身要钉死。
    assertThat(body.has("msgtype")).isTrue();
    assertThat(body.has("msg_type")).isFalse();
    assertThat(body.has("text")).isTrue();
    assertThat(body.has("content")).isFalse();
  }

  @Test
  @DisplayName("内容含中文、换行、引号与反斜杠时逐字一致，没有被转义弄坏")
  void keepsContentVerbatimForChineseNewlinesAndQuotes() throws Exception {
    String content = "第一行\n第二行 \"引号\" 与 \\ 反斜杠\t制表符";
    server.enqueue(ok());

    new WeComNotifyAdapter().send(targetFor(server, HOOK_PATH), content);

    assertThat(bodyOf(server.takeRequest()).path("text").path("content").asText())
        .isEqualTo(content);
  }

  @Test
  @DisplayName("URL 来自通知目标的配置、不是硬编码：两个端口的假 webhook 各收各的")
  void sendsToTheUrlFromTargetConfigNotHardcoded() throws Exception {
    MockWebServer another = new MockWebServer();
    another.start();
    try {
      server.enqueue(ok());
      another.enqueue(ok());

      WeComNotifyAdapter adapter = new WeComNotifyAdapter();
      adapter.send(targetFor(server, "/hook-a"), "A");
      adapter.send(targetFor(another, "/hook-b"), "B");

      assertThat(server.takeRequest().getTarget()).isEqualTo("/hook-a");
      assertThat(another.takeRequest().getTarget()).isEqualTo("/hook-b");
      assertThat(server.getRequestCount()).isEqualTo(1);
      assertThat(another.getRequestCount()).isEqualTo(1);
    } finally {
      another.close();
    }
  }

  // ---------- 业务码判定：本档存在的全部理由 ----------

  @Test
  @DisplayName("响应 errcode 为 0 时正常返回，不抛异常")
  void acceptsResponseWithZeroErrcode() {
    server.enqueue(ok());

    assertThatCode(() -> new WeComNotifyAdapter().send(targetFor(server, HOOK_PATH), "内容"))
        .doesNotThrowAnyException();
    assertThat(server.getRequestCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("★ HTTP 200 但 errcode 非 0 时抛异常，绝不假报「已推送」（原始缺陷的回归）")
  void throwsWhenHttpOkButBusinessErrcodeIsNotZero() {
    server.enqueue(businessError());

    assertThatThrownBy(() -> new WeComNotifyAdapter().send(targetFor(server, HOOK_PATH), "内容"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("40008");
    // 一次就是一次：业务错误路径同样不重试
    assertThat(server.getRequestCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("业务码非 0 时异常消息带上对端的 errmsg，便于定位")
  void businessErrorCarriesVendorMessage() {
    server.enqueue(businessError());

    assertThatThrownBy(() -> new WeComNotifyAdapter().send(targetFor(server, HOOK_PATH), "内容"))
        .hasMessageContaining("invalid message type");
  }

  @Test
  @DisplayName("errcode 不存在时判失败——不能把「缺失」当成 0（path().asInt() 的坑）")
  void throwsWhenNoBusinessCodeFieldAtAll() {
    server.enqueue(okWithBody("{\"data\":{}}"));

    assertThatThrownBy(() -> new WeComNotifyAdapter().send(targetFor(server, HOOK_PATH), "内容"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("无法确认送达");
  }

  @Test
  @DisplayName("★ 企微没有飞书那个 StatusCode 兜底字段：只回 StatusCode 不算送达")
  void doesNotAcceptFeishuLegacyStatusCodeField() {
    server.enqueue(okWithBody("{\"StatusCode\":0,\"StatusMessage\":\"success\"}"));

    assertThatThrownBy(() -> new WeComNotifyAdapter().send(targetFor(server, HOOK_PATH), "内容"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("无法确认送达");
  }

  @Test
  @DisplayName("errcode 为 null 或不是整数时判失败（字符串 \"0\" 与小数都不算确认送达）")
  void throwsWhenBusinessCodeIsNotAnInteger() {
    WeComNotifyAdapter adapter = new WeComNotifyAdapter();

    server.enqueue(okWithBody("{\"errcode\":null,\"errmsg\":\"ok\"}"));
    assertThatThrownBy(() -> adapter.send(targetFor(server, HOOK_PATH), "内容"))
        .isInstanceOf(IllegalStateException.class);

    server.enqueue(okWithBody("{\"errcode\":\"0\"}"));
    assertThatThrownBy(() -> adapter.send(targetFor(server, HOOK_PATH), "内容"))
        .isInstanceOf(IllegalStateException.class);

    server.enqueue(okWithBody("{\"errcode\":0.5}"));
    assertThatThrownBy(() -> adapter.send(targetFor(server, HOOK_PATH), "内容"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("响应体为空或纯空白时判失败——Jackson 对这两种形态返回 MissingNode 而不是抛异常")
  void throwsWhenResponseBodyIsEmptyOrBlank() {
    WeComNotifyAdapter adapter = new WeComNotifyAdapter();

    server.enqueue(okWithBody(""));
    assertThatThrownBy(() -> adapter.send(targetFor(server, HOOK_PATH), "内容"))
        .isInstanceOf(IllegalStateException.class);

    server.enqueue(okWithBody("   "));
    assertThatThrownBy(() -> adapter.send(targetFor(server, HOOK_PATH), "内容"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("响应体不是 JSON 时判失败，且报错不回显 body 内容")
  void throwsWhenResponseBodyIsNotJsonAndDoesNotEchoBody() {
    String gatewayPage = "<html>502 Bad Gateway: /cgi-bin/webhook/send?key=KEY-abc123</html>";
    server.enqueue(okWithBody(gatewayPage));

    assertThatThrownBy(() -> new WeComNotifyAdapter().send(targetFor(server, HOOK_PATH), "内容"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageNotContaining("html")
        .hasMessageNotContaining("KEY-abc123");
  }

  @Test
  @DisplayName("响应体是 JSON 数组而非对象时判失败")
  void throwsWhenResponseBodyIsNotAJsonObject() {
    server.enqueue(okWithBody("[{\"errcode\":0}]"));

    assertThatThrownBy(() -> new WeComNotifyAdapter().send(targetFor(server, HOOK_PATH), "内容"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("不是 JSON 对象");
  }

  // ---------- HTTP 层与边界 ----------

  @Test
  @DisplayName("webhook 返回 5xx 时异常向上抛，且不重试")
  void throwsOnServerErrorWithoutRetrying() {
    server.enqueue(new MockResponse.Builder().code(500).body("{\"errcode\":-1}").build());

    assertThatThrownBy(() -> new WeComNotifyAdapter().send(targetFor(server, HOOK_PATH), "内容"))
        .isInstanceOf(HttpServerErrorException.class);
    assertThat(server.getRequestCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("3xx 不跟随重定向：一次请求都不落到 Location 指向的地址上")
  void doesNotFollowRedirects() throws IOException {
    MockWebServer redirectTarget = new MockWebServer();
    redirectTarget.start();
    try {
      server.enqueue(
          new MockResponse.Builder()
              .code(302)
              .addHeader("Location", redirectTarget.url("/stolen").toString())
              .body("")
              .build());

      // 抛异常的直接原因是 fail-closed 的送达判据（3xx 不是 RestClient 默认的错误状态，响应体又是空的），
      // 但真正要钉住的不变量是下一行：请求没有跟着 Location 走
      assertThatThrownBy(() -> new WeComNotifyAdapter().send(targetFor(server, HOOK_PATH), "内容"))
          .isInstanceOf(IllegalStateException.class);
      assertThat(redirectTarget.getRequestCount()).isZero();
      assertThat(server.getRequestCount()).isEqualTo(1);
    } finally {
      redirectTarget.close();
    }
  }

  @Test
  @DisplayName("配置里没有 url 或 url 是空白时明确报错，一次请求都不发")
  void rejectsBlankUrlWithoutSendingAnyRequest() {
    WeComNotifyAdapter adapter = new WeComNotifyAdapter();

    assertThatThrownBy(() -> adapter.send(new NotifyTarget("wecom", Map.of("url", "   ")), "内容"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> adapter.send(new NotifyTarget("wecom", Map.of()), "内容"))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(server.getRequestCount()).isZero();
  }

  @Test
  @DisplayName("异常消息里不含 url——地址里带着 key，是凭证本身")
  void errorMessagesDoNotLeakUrl() {
    server.enqueue(businessError());

    assertThatThrownBy(() -> new WeComNotifyAdapter().send(targetFor(server, HOOK_PATH), "内容"))
        .hasMessageNotContaining("KEY-abc123")
        .hasMessageNotContaining("localhost");
  }

  @Test
  @DisplayName("对端迟迟不给响应头时按超时上抛，推送不会无限期挂着")
  void throwsWhenServerExceedsPushTimeout() {
    server.enqueue(
        new MockResponse.Builder()
            .code(200)
            .body("{\"errcode\":0}")
            .headersDelay(2, TimeUnit.SECONDS)
            .build());

    WeComNotifyAdapter impatient = new WeComNotifyAdapter(Duration.ofMillis(200));

    assertThatThrownBy(() -> impatient.send(targetFor(server, HOOK_PATH), "内容"))
        .isInstanceOf(ResourceAccessException.class);
  }

  @Test
  @DisplayName("读响应体也要有界：对端给了响应头却拖着不给 body 时同样按超时上抛")
  void throwsWhenResponseBodyReadExceedsPushTimeout() {
    server.enqueue(
        new MockResponse.Builder()
            .code(200)
            .body("{\"errcode\":0}")
            .bodyDelay(2, TimeUnit.SECONDS)
            .build());

    WeComNotifyAdapter impatient = new WeComNotifyAdapter(Duration.ofMillis(200));

    // 与飞书档同口径：body 读取超时被 Spring 在「提取响应体」那一步包成 RestClientException
    assertThatThrownBy(() -> impatient.send(targetFor(server, HOOK_PATH), "内容"))
        .isInstanceOf(RestClientException.class)
        .hasMessageContaining("extracting response");
  }
}
