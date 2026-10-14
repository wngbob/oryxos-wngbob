package io.oryxlabs.oryxos.tool.notify;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * 课件验收 harness 第一批：真实 {@link WebhookNotifyAdapter} 打本地假 webhook。
 *
 * <p><b>用真实实现、不用 mock</b>：本类要证的正是「HTTP 真的发出去了、body 里真有 content、地址真来自配置」， mock 掉 HTTP 出口就只剩自证。假服务起在
 * {@code localhost}，全程不碰外网。
 *
 * <p><b>不断言响应体的业务错误码</b>：2xx 即送达。对端 JSON 里写没写 {@code errcode} 是某一家的方言， 通用 webhook 不认识它（核心阶段不逐家对接专用
 * API）。
 */
class WebhookNotifyAdapterTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** 路径与端口都来自假服务，用来证「地址不是硬编码」。 */
  private static final String HOOK_PATH = "/hook";

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
    return new MockResponse.Builder().code(200).body("{\"errcode\":0}").build();
  }

  private static MockResponse status(int code) {
    return new MockResponse.Builder().code(code).body("{\"errcode\":40001}").build();
  }

  private static NotifyTarget targetFor(MockWebServer fakeWebhook, String path) {
    return new NotifyTarget("webhook", Map.of("url", fakeWebhook.url(path).toString()));
  }

  private static JsonNode bodyOf(RecordedRequest request) throws IOException {
    return JSON.readTree(request.getBody().utf8());
  }

  @Test
  @DisplayName("发送后收到的 POST 请求 body 里带 content，且只有这一个字段")
  void postBodyCarriesContent() throws Exception {
    server.enqueue(ok());

    new WebhookNotifyAdapter().send(targetFor(server, HOOK_PATH), "今天晴，26 度");

    RecordedRequest request = server.takeRequest();
    assertThat(request.getMethod()).isEqualTo("POST");
    JsonNode body = bodyOf(request);
    assertThat(body.path("content").asText()).isEqualTo("今天晴，26 度");
    // 多一个字段就是某一家的方言：通用 webhook 的线上形状只有 content
    assertThat(body.size()).isEqualTo(1);
  }

  @Test
  @DisplayName("URL 来自通知目标的配置、不是硬编码：两个端口的假 webhook 各收各的")
  void sendsToTheUrlFromTargetConfigNotHardcoded() throws Exception {
    MockWebServer another = new MockWebServer();
    another.start();
    try {
      server.enqueue(ok());
      another.enqueue(ok());

      WebhookNotifyAdapter adapter = new WebhookNotifyAdapter();
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

  @Test
  @DisplayName("内容含中文、换行、引号与反斜杠时逐字一致，没有被转义弄坏")
  void keepsContentVerbatimForChineseNewlinesAndQuotes() throws Exception {
    String content = "第一行\n第二行 \"引号\" 与 \\ 反斜杠\t制表符";
    server.enqueue(ok());

    new WebhookNotifyAdapter().send(targetFor(server, HOOK_PATH), content);

    assertThat(bodyOf(server.takeRequest()).path("content").asText()).isEqualTo(content);
  }

  @Test
  @DisplayName("webhook 返回 5xx 时异常向上抛，且不重试")
  void throwsOnServerErrorWithoutRetrying() {
    server.enqueue(status(500));

    assertThatThrownBy(() -> new WebhookNotifyAdapter().send(targetFor(server, HOOK_PATH), "内容"))
        .isInstanceOf(HttpServerErrorException.class);
    // 一次就是一次：重试会把「推了两遍」变成静默事实
    assertThat(server.getRequestCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("webhook 返回 4xx 时同样异常向上抛")
  void throwsOnClientError() {
    server.enqueue(status(404));

    assertThatThrownBy(() -> new WebhookNotifyAdapter().send(targetFor(server, HOOK_PATH), "内容"))
        .isInstanceOf(HttpClientErrorException.class);
    assertThat(server.getRequestCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("地址不可达时异常向上抛，不静默吞")
  void throwsWhenHostUnreachable() throws IOException {
    // 起一个假服务拿到端口再关掉：这个端口上就没人监听了
    MockWebServer dead = new MockWebServer();
    dead.start();
    String deadUrl = dead.url(HOOK_PATH).toString();
    dead.close();

    NotifyTarget unreachable = new NotifyTarget("webhook", Map.of("url", deadUrl));

    assertThatThrownBy(() -> new WebhookNotifyAdapter().send(unreachable, "内容"))
        .isInstanceOf(ResourceAccessException.class);
  }

  @Test
  @DisplayName("对端迟迟不响应时按超时上抛，推送不会无限期挂着")
  void throwsWhenServerExceedsPushTimeout() {
    // 必须拖【响应头】而不是 body：JdkClientHttpRequest 用 ofInputStream + TimeoutHandler，
    // 读取上限量的是「从请求派发到响应头到达」；而 toBodilessEntity() 根本不读 body，
    // 用 bodyDelay 会连拖都拖不住（实测请求 0.3 秒就返回了，超时断言无从触发）。
    server.enqueue(
        new MockResponse.Builder()
            .code(200)
            .body("{\"errcode\":0}")
            .headersDelay(2, TimeUnit.SECONDS)
            .build());

    WebhookNotifyAdapter impatient = new WebhookNotifyAdapter(Duration.ofMillis(200));

    assertThatThrownBy(() -> impatient.send(targetFor(server, HOOK_PATH), "内容"))
        .isInstanceOf(ResourceAccessException.class);
  }

  @Test
  @DisplayName("配置里没有 url 或 url 是空白时明确报错，一次请求都不发")
  void rejectsBlankUrlWithoutSendingAnyRequest() {
    WebhookNotifyAdapter adapter = new WebhookNotifyAdapter();

    assertThatThrownBy(() -> adapter.send(new NotifyTarget("webhook", Map.of("url", "   ")), "内容"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> adapter.send(new NotifyTarget("webhook", Map.of()), "内容"))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(server.getRequestCount()).isZero();
  }
}
