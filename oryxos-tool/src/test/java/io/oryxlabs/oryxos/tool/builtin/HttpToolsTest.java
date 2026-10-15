package io.oryxlabs.oryxos.tool.builtin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.oryxlabs.oryxos.core.tool.ToolResult;
import io.oryxlabs.oryxos.tool.sandbox.ActionType;
import io.oryxlabs.oryxos.tool.sandbox.Sandbox;
import io.oryxlabs.oryxos.tool.sandbox.SandboxAction;
import io.oryxlabs.oryxos.tool.sandbox.SandboxViolationException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code http_get} / {@code http_post} 的两条硬约束（contracts/builtin-tools.md §2.8 / §3.3）。
 *
 * <p>用本地假服务器（mockwebserver3）承接正常路径——<b>不碰外网</b>（SC-008）：一是可重复，二是「一次请求都没发出」
 * 这种断言只有在请求数可观测时才成立。唯一没走假服务器的用例是「连不上」，它打的是本机一个必然无人监听的端口。
 */
class HttpToolsTest {

  private static final String UNREACHABLE = "http://127.0.0.1:1/nothing-listens-here";

  private static HttpTools permissive() {
    return new HttpTools(new RecordingSandbox(true));
  }

  @Test
  @DisplayName("http_get 正常路径：返回状态码与响应体")
  void httpGet_successfulResponse_returnsStatusCodeAndBody() throws IOException {
    try (MockWebServer server = new MockWebServer()) {
      server.start();
      server.enqueue(new MockResponse.Builder().code(200).body("pong").build());

      ToolResult result = permissive().httpGet(server.url("/ping").toString());

      assertThat(result.success()).isTrue();
      assertThat(result.content()).contains("HTTP 200").contains("pong");
    }
  }

  @Test
  @DisplayName("http_post 正常路径：请求体原样发出，响应体带回")
  void httpPost_successfulResponse_sendsBody() throws IOException, InterruptedException {
    try (MockWebServer server = new MockWebServer()) {
      server.start();
      server.enqueue(new MockResponse.Builder().code(200).body("accepted").build());

      ToolResult result = permissive().httpPost(server.url("/submit").toString(), "payload");

      assertThat(result.success()).isTrue();
      assertThat(result.content()).contains("HTTP 200").contains("accepted");
      assertThat(server.takeRequest().getBody().utf8()).isEqualTo("payload");
    }
  }

  @Test
  @DisplayName("http_get 非 2xx → 不可重试失败（服务器听懂了但拒绝，重试不会变好）")
  void httpGet_nonSuccessStatus_isNotRetryable() throws IOException {
    try (MockWebServer server = new MockWebServer()) {
      server.start();
      server.enqueue(new MockResponse.Builder().code(500).body("boom").build());

      ToolResult result = permissive().httpGet(server.url("/boom").toString());

      assertThat(result.success()).isFalse();
      assertThat(result.retryable()).isFalse();
      assertThat(result.errorMessage()).contains("HTTP 500");
    }
  }

  @Test
  @DisplayName("http_get 连不上 → 可重试失败（这次没通，值得再试一次）")
  void httpGet_unreachableHost_isRetryable() {
    ToolResult result = permissive().httpGet(UNREACHABLE);

    assertThat(result.success()).isFalse();
    assertThat(result.retryable()).isTrue();
    assertThat(result.errorMessage()).contains("请求失败");
  }

  @Test
  @DisplayName("http_get 结果里不回显请求头（凭证不进模型上下文，FR-027）")
  void httpGet_result_doesNotEchoRequestHeaders() throws IOException {
    try (MockWebServer server = new MockWebServer()) {
      server.start();
      server.enqueue(new MockResponse.Builder().code(200).body("ok").build());

      ToolResult result = permissive().httpGet(server.url("/headers").toString());

      assertThat(result.content()).doesNotContain("Host:").doesNotContain("Accept:");
    }
  }

  @Test
  @DisplayName("http_get 越界域名必须被拦，且一次请求都没发出去")
  void httpGet_outsideWhitelist_isBlockedBeforeIo() throws IOException {
    try (MockWebServer server = new MockWebServer()) {
      server.start();
      RecordingSandbox sandbox = new RecordingSandbox(false);
      String url = server.url("/blocked").toString();

      assertThatThrownBy(() -> new HttpTools(sandbox).httpGet(url))
          .isInstanceOf(SandboxViolationException.class);

      assertThat(sandbox.actions())
          .containsExactly(new SandboxAction(ActionType.HTTP_REQUEST, url));
      // 关键：校验在发请求之前——先发后校验的实现下这里会是 1
      assertThat(server.getRequestCount()).isZero();
    }
  }

  @Test
  @DisplayName("顺序断言：enforce 被调用时请求还没发出，放行之后才真正打到服务器")
  void enforce_happensBeforeIo_forHttpGet() throws IOException {
    try (MockWebServer server = new MockWebServer()) {
      server.start();
      server.enqueue(new MockResponse.Builder().code(200).body("ok").build());
      List<Integer> requestCountAtEnforce = new ArrayList<>();
      Sandbox sandbox = action -> requestCountAtEnforce.add(server.getRequestCount());

      ToolResult result = new HttpTools(sandbox).httpGet(server.url("/order").toString());

      assertThat(result.success()).isTrue();
      // 反序实现（先发请求后校验）下这里是 1——「抛了异常」证明不了顺序，这一条才能
      assertThat(requestCountAtEnforce).containsExactly(0);
      assertThat(server.getRequestCount()).isEqualTo(1);
    }
  }

  @Test
  @DisplayName("web_fetch 抓 HTML：标签、脚本、样式都去掉，实体解码成文字")
  void webFetch_htmlResponse_returnsPlainTextWithoutTags() throws IOException {
    try (MockWebServer server = new MockWebServer()) {
      server.start();
      server.enqueue(
          new MockResponse.Builder()
              .code(200)
              .addHeader("Content-Type", "text/html; charset=utf-8")
              .body(
                  "<html><head><style>p{color:red}</style><script>var x=1;</script></head>"
                      + "<body><h1>标题</h1><p>a &amp; b</p><!-- 注释 --></body></html>")
              .build());

      ToolResult result = permissive().webFetch(server.url("/doc").toString());

      assertThat(result.success()).isTrue();
      assertThat(result.content()).contains("标题").contains("a & b");
      assertThat(result.content())
          .doesNotContain("<h1>")
          .doesNotContain("<script>")
          .doesNotContain("var x=1")
          .doesNotContain("color:red")
          .doesNotContain("注释");
    }
  }

  @Test
  @DisplayName("web_fetch 非 HTML 响应原样返回：JSON 里的「<」是数据，去标签会静默改坏它")
  void webFetch_nonHtmlResponse_isReturnedVerbatim() throws IOException {
    try (MockWebServer server = new MockWebServer()) {
      server.start();
      server.enqueue(
          new MockResponse.Builder()
              .code(200)
              .addHeader("Content-Type", "application/json")
              .body("{\"cmp\":\"a < b\"}")
              .build());

      ToolResult result = permissive().webFetch(server.url("/api").toString());

      assertThat(result.content()).isEqualTo("{\"cmp\":\"a < b\"}");
    }
  }

  @Test
  @DisplayName("web_fetch 正文超长：截断并注明原始长度")
  void webFetch_overlongBody_isTruncated() throws IOException {
    try (MockWebServer server = new MockWebServer()) {
      server.start();
      server.enqueue(
          new MockResponse.Builder()
              .code(200)
              .addHeader("Content-Type", "text/plain")
              .body("x".repeat(25000))
              .build());

      ToolResult result = permissive().webFetch(server.url("/big").toString());

      assertThat(result.content()).contains("已截断").contains("25000");
      // 正文 20000 字符 + 说明行，远小于原始 25000
      assertThat(result.content().length()).isLessThan(25000);
    }
  }

  @Test
  @DisplayName("web_fetch 越界域名必须被拦，且一次请求都没发出去")
  void webFetch_outsideWhitelist_isBlockedBeforeIo() throws IOException {
    try (MockWebServer server = new MockWebServer()) {
      server.start();
      RecordingSandbox sandbox = new RecordingSandbox(false);
      String url = server.url("/blocked").toString();

      assertThatThrownBy(() -> new HttpTools(sandbox).webFetch(url))
          .isInstanceOf(SandboxViolationException.class);

      assertThat(sandbox.actions())
          .containsExactly(new SandboxAction(ActionType.HTTP_REQUEST, url));
      // 关键：校验在发请求之前——先发后校验的实现下这里会是 1
      assertThat(server.getRequestCount()).isZero();
    }
  }

  @Test
  @DisplayName("web_fetch 跟随 308 到最终页：拿到的是跳转后的正文，不是那句「HTTP 308」")
  void webFetch_permanentRedirect_isFollowedToTheFinalPage() throws IOException {
    try (MockWebServer server = new MockWebServer()) {
      server.start();
      server.enqueue(
          new MockResponse.Builder()
              .code(308)
              .addHeader("Location", server.url("/final").toString())
              .build());
      server.enqueue(
          new MockResponse.Builder()
              .code(200)
              .addHeader("Content-Type", "text/html")
              .body("<p>真正的正文</p>")
              .build());

      ToolResult result = permissive().webFetch(server.url("/moved").toString());

      assertThat(result.success()).isTrue();
      assertThat(result.content()).contains("真正的正文");
      // 两跳都真的发出去了：第一跳拿 Location，第二跳拿正文
      assertThat(server.getRequestCount()).isEqualTo(2);
    }
  }

  @Test
  @DisplayName("web_fetch 的 Location 是相对路径时按当前地址解析（真实站点大量这么写）")
  void webFetch_relativeLocation_isResolvedAgainstCurrentUrl()
      throws IOException, InterruptedException {
    try (MockWebServer server = new MockWebServer()) {
      server.start();
      server.enqueue(new MockResponse.Builder().code(302).addHeader("Location", "/final").build());
      server.enqueue(new MockResponse.Builder().code(200).body("落点").build());

      ToolResult result = permissive().webFetch(server.url("/moved").toString());

      assertThat(result.content()).isEqualTo("落点");
      server.takeRequest();
      assertThat(server.takeRequest().getTarget()).isEqualTo("/final");
    }
  }

  @Test
  @DisplayName("web_fetch 的每一跳都重新过白名单——白名单域名不能靠一个 Location 把请求转去任意主机")
  void webFetch_everyRedirectHop_passesThroughSandbox() throws IOException {
    try (MockWebServer server = new MockWebServer()) {
      server.start();
      server.enqueue(
          new MockResponse.Builder()
              .code(301)
              .addHeader("Location", server.url("/elsewhere").toString())
              .build());
      server.enqueue(new MockResponse.Builder().code(200).body("ok").build());
      RecordingSandbox sandbox = new RecordingSandbox(true);

      new HttpTools(sandbox).webFetch(server.url("/start").toString());

      // 只校验首跳的实现下这里只有 1 条：跳转目标是一个全新的地址，它同样要审
      assertThat(sandbox.actions())
          .containsExactly(
              new SandboxAction(ActionType.HTTP_REQUEST, server.url("/start").toString()),
              new SandboxAction(ActionType.HTTP_REQUEST, server.url("/elsewhere").toString()));
    }
  }

  @Test
  @DisplayName("web_fetch 撞上重定向环：跳够上限就放弃，不会一直转到超时")
  void webFetch_redirectLoop_givesUpAfterMaxHops() throws IOException {
    try (MockWebServer server = new MockWebServer()) {
      server.start();
      // 永远把自己指回自己：不设上限的实现会一直转到客户端超时
      for (int i = 0; i < 10; i++) {
        server.enqueue(
            new MockResponse.Builder()
                .code(302)
                .addHeader("Location", server.url("/loop").toString())
                .build());
      }

      ToolResult result = permissive().webFetch(server.url("/loop").toString());

      assertThat(result.success()).isFalse();
      assertThat(result.retryable()).isFalse();
      assertThat(result.errorMessage()).contains("重定向超过 5 次");
      // 首跳 + 5 次跟随 = 6 次，一次不多
      assertThat(server.getRequestCount()).isEqualTo(6);
    }
  }

  @Test
  @DisplayName("http_get 不跟随重定向：状态码原样交给模型，这是它和 web_fetch 的有意差异")
  void httpGet_doesNotFollowRedirect() throws IOException {
    try (MockWebServer server = new MockWebServer()) {
      server.start();
      server.enqueue(
          new MockResponse.Builder()
              .code(301)
              .addHeader("Location", server.url("/final").toString())
              .build());

      ToolResult result = permissive().httpGet(server.url("/moved").toString());

      // http_get 的语义是「把这一跳的响应给我」，301 本身就是答案
      assertThat(result.success()).isFalse();
      assertThat(result.errorMessage()).contains("HTTP 301");
      assertThat(server.getRequestCount()).isEqualTo(1);
    }
  }
}
