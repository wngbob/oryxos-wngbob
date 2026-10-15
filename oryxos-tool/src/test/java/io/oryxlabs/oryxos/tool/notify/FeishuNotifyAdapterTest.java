package io.oryxlabs.oryxos.tool.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
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
 * 飞书 / Lark 方言档的验收 harness：真实 {@link FeishuNotifyAdapter} 打本地假 webhook。
 *
 * <p><b>本类存在的理由就是那条原始缺陷</b>：飞书群机器人用 {@code HTTP 200} 承载业务错误，通用档只看状态码 → {@code notify} 假报「已推送」。
 * 所以这里最要紧的两条是「{@code code:0} 才放行」与「{@code HTTP 200} + 业务错误码必须抛」——它们直接对号入座到那次实测。
 *
 * <p><b>用真实实现、不用 mock</b>：要证的正是「HTTP 真的发出去了、body 真的是飞书方言、业务码真的被读了」。假服务起在 {@code localhost}，全程不碰外网。
 */
class FeishuNotifyAdapterTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final String HOOK_PATH = "/open-apis/bot/v2/hook/TOKEN-abc123";

  /** 确定性签名用的固定时刻：1599360473 秒（官方文档示例里的时间戳量级）。 */
  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.ofEpochSecond(1599360473L), ZoneOffset.UTC);

  private static final String SECRET = "oryxos-test-secret";

  /**
   * 黄金值：{@code Base64(HmacSHA256(key = "1599360473\noryxos-test-secret", message = 空字节))}。
   *
   * <p><b>刻意用 Python 的 {@code hmac} 模块离线算出后硬编码</b>，而不是在测试里再实现一遍算法——后者与生产代码同源，两边一起写错也照样绿。
   * 硬编码的黄金值才真正钉住「算法与官方口径一致」这件事。
   */
  private static final String EXPECTED_SIGN = "4rMCm93GD6uhX5jBOiU3Q/QsWTaz6CZjb+xCNvNcH6U=";

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
    return new MockResponse.Builder()
        .code(200)
        .body(
            "{\"StatusCode\":0,\"StatusMessage\":\"success\",\"code\":0,\"data\":{},\"msg\":\"success\"}")
        .build();
  }

  private static MockResponse okWithBody(String body) {
    return new MockResponse.Builder().code(200).body(body).build();
  }

  /** 实测抓到的那一次：报文形状不对时飞书的真实响应。 */
  private static MockResponse businessError() {
    return okWithBody("{\"code\":19002,\"data\":{},\"msg\":\"params error, msg_type need\"}");
  }

  private static NotifyTarget targetFor(MockWebServer fakeWebhook, String path) {
    return new NotifyTarget("feishu", Map.of("url", fakeWebhook.url(path).toString()));
  }

  private static NotifyTarget targetWithSecret(MockWebServer fakeWebhook, String path) {
    return new NotifyTarget(
        "feishu", Map.of("url", fakeWebhook.url(path).toString(), "secret", SECRET));
  }

  private static JsonNode bodyOf(RecordedRequest request) throws IOException {
    return JSON.readTree(request.getBody().utf8());
  }

  // ---------- 报文形状：飞书方言 ----------

  @Test
  @DisplayName("发送的报文是飞书方言：msg_type 为 text、正文在 content.text 里，且没有多余字段")
  void postBodyCarriesFeishuTextDialect() throws Exception {
    server.enqueue(ok());

    new FeishuNotifyAdapter().send(targetFor(server, HOOK_PATH), "今天晴，26 度");

    RecordedRequest request = server.takeRequest();
    assertThat(request.getMethod()).isEqualTo("POST");
    assertThat(request.getHeaders().get("Content-Type")).contains("application/json");
    JsonNode body = bodyOf(request);
    assertThat(body.path("msg_type").asText()).isEqualTo("text");
    assertThat(body.path("content").path("text").asText()).isEqualTo("今天晴，26 度");
    // 未配密钥时 timestamp / sign 两个键必须不出现（而不是出现为 null）
    assertThat(body.size()).isEqualTo(2);
  }

  @Test
  @DisplayName("内容含中文、换行、引号与反斜杠时逐字一致，没有被转义弄坏")
  void keepsContentVerbatimForChineseNewlinesAndQuotes() throws Exception {
    String content = "第一行\n第二行 \"引号\" 与 \\ 反斜杠\t制表符";
    server.enqueue(ok());

    new FeishuNotifyAdapter().send(targetFor(server, HOOK_PATH), content);

    assertThat(bodyOf(server.takeRequest()).path("content").path("text").asText())
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

      FeishuNotifyAdapter adapter = new FeishuNotifyAdapter();
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

  // ---------- 业务码判定：本补强的核心 ----------

  @Test
  @DisplayName("响应 code 为 0 时正常返回，不抛异常")
  void acceptsResponseWithZeroCode() {
    server.enqueue(ok());

    assertThatCode(() -> new FeishuNotifyAdapter().send(targetFor(server, HOOK_PATH), "内容"))
        .doesNotThrowAnyException();
    assertThat(server.getRequestCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("★ HTTP 200 但业务码非 0 时抛异常，绝不假报「已推送」（原始缺陷的回归）")
  void throwsWhenHttpOkButBusinessCodeIsNotZero() {
    server.enqueue(businessError());

    assertThatThrownBy(() -> new FeishuNotifyAdapter().send(targetFor(server, HOOK_PATH), "内容"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("19002");
    // 一次就是一次：业务错误路径同样不重试
    assertThat(server.getRequestCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("业务码非 0 时异常消息带上对端的 msg，便于定位")
  void businessErrorCarriesVendorMessage() {
    server.enqueue(businessError());

    assertThatThrownBy(() -> new FeishuNotifyAdapter().send(targetFor(server, HOOK_PATH), "内容"))
        .hasMessageContaining("params error, msg_type need");
  }

  @Test
  @DisplayName("code 与 StatusCode 都不存在时判失败——不能把「缺失」当成 0（path().asInt() 的坑）")
  void throwsWhenNoBusinessCodeFieldAtAll() {
    server.enqueue(okWithBody("{\"data\":{}}"));

    assertThatThrownBy(() -> new FeishuNotifyAdapter().send(targetFor(server, HOOK_PATH), "内容"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("无法确认送达");
  }

  @Test
  @DisplayName("code 存在时以 code 为准：StatusCode 为 0 也不能把 code:19002 洗成成功")
  void codeWinsOverLegacyStatusCode() {
    server.enqueue(okWithBody("{\"StatusCode\":0,\"StatusMessage\":\"success\",\"code\":19002}"));

    assertThatThrownBy(() -> new FeishuNotifyAdapter().send(targetFor(server, HOOK_PATH), "内容"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("19002");
  }

  @Test
  @DisplayName("历史形态只回 StatusCode 为 0 时判成功，不误伤")
  void acceptsLegacyResponseWithOnlyStatusCode() {
    server.enqueue(okWithBody("{\"StatusCode\":0,\"StatusMessage\":\"success\"}"));

    assertThatCode(() -> new FeishuNotifyAdapter().send(targetFor(server, HOOK_PATH), "内容"))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("业务码不是整数时判失败（字符串 \"0\" 与小数都不算确认送达）")
  void throwsWhenBusinessCodeIsNotAnInteger() {
    FeishuNotifyAdapter adapter = new FeishuNotifyAdapter();

    server.enqueue(okWithBody("{\"code\":\"0\"}"));
    assertThatThrownBy(() -> adapter.send(targetFor(server, HOOK_PATH), "内容"))
        .isInstanceOf(IllegalStateException.class);

    server.enqueue(okWithBody("{\"code\":0.5}"));
    assertThatThrownBy(() -> adapter.send(targetFor(server, HOOK_PATH), "内容"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("响应体为空或纯空白时判失败——Jackson 对这两种形态返回 MissingNode 而不是抛异常")
  void throwsWhenResponseBodyIsEmptyOrBlank() {
    FeishuNotifyAdapter adapter = new FeishuNotifyAdapter();

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
    String gatewayPage = "<html>502 Bad Gateway: /open-apis/bot/v2/hook/TOKEN-abc123</html>";
    server.enqueue(okWithBody(gatewayPage));

    assertThatThrownBy(() -> new FeishuNotifyAdapter().send(targetFor(server, HOOK_PATH), "内容"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageNotContaining("html")
        .hasMessageNotContaining("TOKEN-abc123");
  }

  // ---------- 签名校验 ----------

  @Test
  @DisplayName("配了 secret 时附上 timestamp 与 sign，且 sign 与官方算法算出的黄金值一致")
  void addsSignatureMatchingOfficialAlgorithm() throws Exception {
    server.enqueue(ok());

    new FeishuNotifyAdapter(Duration.ofSeconds(10), FIXED_CLOCK)
        .send(targetWithSecret(server, HOOK_PATH), "内容");

    JsonNode body = bodyOf(server.takeRequest());
    assertThat(body.path("timestamp").asText()).isEqualTo("1599360473");
    assertThat(body.path("sign").asText()).isEqualTo(EXPECTED_SIGN);
    // 签名是 SHA-256 的摘要，Base64 解码后固定 32 字节
    assertThat(Base64.getDecoder().decode(body.path("sign").asText())).hasSize(32);
    // 签名之外仍是飞书方言本身
    assertThat(body.path("msg_type").asText()).isEqualTo("text");
    assertThat(body.path("content").path("text").asText()).isEqualTo("内容");
  }

  @Test
  @DisplayName("未配 secret（或只配了空白）时不带 timestamp 与 sign")
  void omitsSignatureFieldsWhenSecretAbsentOrBlank() throws Exception {
    FeishuNotifyAdapter adapter = new FeishuNotifyAdapter(Duration.ofSeconds(10), FIXED_CLOCK);

    server.enqueue(ok());
    adapter.send(targetFor(server, HOOK_PATH), "内容");
    assertThat(bodyOf(server.takeRequest()).size()).isEqualTo(2);

    server.enqueue(ok());
    adapter.send(
        new NotifyTarget(
            "feishu", Map.of("url", server.url(HOOK_PATH).toString(), "secret", "   ")),
        "内容");
    assertThat(bodyOf(server.takeRequest()).size()).isEqualTo(2);
  }

  // ---------- HTTP 层与边界 ----------

  @Test
  @DisplayName("webhook 返回 5xx 时异常向上抛，且不重试")
  void throwsOnServerErrorWithoutRetrying() {
    server.enqueue(new MockResponse.Builder().code(500).body("{\"code\":9499}").build());

    assertThatThrownBy(() -> new FeishuNotifyAdapter().send(targetFor(server, HOOK_PATH), "内容"))
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
      assertThatThrownBy(() -> new FeishuNotifyAdapter().send(targetFor(server, HOOK_PATH), "内容"))
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
    FeishuNotifyAdapter adapter = new FeishuNotifyAdapter();

    assertThatThrownBy(() -> adapter.send(new NotifyTarget("feishu", Map.of("url", "   ")), "内容"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> adapter.send(new NotifyTarget("feishu", Map.of()), "内容"))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(server.getRequestCount()).isZero();
  }

  @Test
  @DisplayName("异常消息里不含 url——地址里带着 token，是敏感串")
  void errorMessagesDoNotLeakUrl() {
    server.enqueue(businessError());

    assertThatThrownBy(() -> new FeishuNotifyAdapter().send(targetFor(server, HOOK_PATH), "内容"))
        .hasMessageNotContaining("TOKEN-abc123")
        .hasMessageNotContaining("localhost");
  }

  @Test
  @DisplayName("对端迟迟不给响应头时按超时上抛，推送不会无限期挂着")
  void throwsWhenServerExceedsPushTimeout() {
    server.enqueue(
        new MockResponse.Builder()
            .code(200)
            .body("{\"code\":0}")
            .headersDelay(2, TimeUnit.SECONDS)
            .build());

    FeishuNotifyAdapter impatient = new FeishuNotifyAdapter(Duration.ofMillis(200));

    assertThatThrownBy(() -> impatient.send(targetFor(server, HOOK_PATH), "内容"))
        .isInstanceOf(ResourceAccessException.class);
  }

  @Test
  @DisplayName("读响应体也要有界：对端给了响应头却拖着不给 body 时同样按超时上抛")
  void throwsWhenResponseBodyReadExceedsPushTimeout() {
    // 这条与通用档那条「不能用 bodyDelay」的注释**方向相反，且两处都对**：通用档用 toBodilessEntity()
    // 压根不读 body，超时自然拖不住；本类必须读 body 才能看到业务码，于是读取上限也覆盖「响应体到达」。
    server.enqueue(
        new MockResponse.Builder()
            .code(200)
            .body("{\"code\":0}")
            .bodyDelay(2, TimeUnit.SECONDS)
            .build());

    FeishuNotifyAdapter impatient = new FeishuNotifyAdapter(Duration.ofMillis(200));

    // 实测：body 读取超时被 Spring 在「提取响应体」那一步包成 RestClientException（header 超时才是 ResourceAccessException）
    assertThatThrownBy(() -> impatient.send(targetFor(server, HOOK_PATH), "内容"))
        .isInstanceOf(RestClientException.class)
        .hasMessageContaining("extracting response");
  }
}
