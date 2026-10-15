package io.oryxlabs.oryxos.tool.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.oryxlabs.oryxos.tool.search.SearchProvider.SearchResult;
import java.io.UncheckedIOException;
import java.util.List;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link TavilySearchProvider} 的四条守点（contracts/builtin-tools.md §2.10）。
 *
 * <p><b>为什么给这一档也写真实实现的测试</b>：与 {@link DuckDuckGoSearchProviderTest} 同理——{@code WebSearchToolsTest}
 * 只能证明「工具用对了接缝」，接缝背后这一档的鉴权头写没写对、请求体字段名对不对、响应读没读对，它一无所知。而这一档在配了 key 的机器上就是生产路径。
 *
 * <p><b>用本地假服务器承接</b>（mockwebserver3），<b>不碰外网</b>（SC-008）：既不需要真 key，也不会因为外网抖动而红。 断言里的 key
 * 一律是假值，真实凭证从不进入测试。
 */
class TavilySearchProviderTest {

  private static final String UNREACHABLE = "http://127.0.0.1:1/search";

  /** 假 key：只为验证它被拼进了鉴权头，不是任何真实凭证。 */
  private static final String FAKE_KEY = "tvly-test-key";

  private static final String SAMPLE_RESPONSE =
      """
      {
        "query": "spring boot 3.5",
        "results": [
          {"title": "Spring Boot 3.5 Release Notes",
           "url": "https://example.invalid/notes",
           "content": "3.5 的发布说明。",
           "score": 0.98},
          {"title": "Spring Boot 文档",
           "url": "https://example.invalid/docs",
           "content": "参考文档。"}
        ]
      }
      """;

  @Test
  @DisplayName("正常路径：带鉴权头发 POST，results[] 摊平成统一结构")
  void search_sendsAuthorizationHeader_andParsesResults() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.start();
      server.enqueue(new MockResponse.Builder().code(200).body(SAMPLE_RESPONSE).build());

      List<SearchResult> results =
          new TavilySearchProvider(server.url("/search").toString(), FAKE_KEY)
              .search("spring boot 3.5", 5);

      assertThat(results)
          .extracting(SearchResult::title)
          .containsExactly("Spring Boot 3.5 Release Notes", "Spring Boot 文档");
      assertThat(results.get(0).url()).isEqualTo("https://example.invalid/notes");
      assertThat(results.get(0).snippet()).isEqualTo("3.5 的发布说明。");

      RecordedRequest request = server.takeRequest();
      assertThat(request.getMethod()).isEqualTo("POST");
      assertThat(request.getHeaders().get("Authorization")).isEqualTo("Bearer " + FAKE_KEY);
      // 请求体走 JsonParser 拼，字段名与 Tavily 的契约一致
      assertThat(request.getBody().utf8()).contains("\"query\"").contains("spring boot 3.5");
    }
  }

  @Test
  @DisplayName("max_results 夹到服务端上限 20：调用方传 200 也不会被整单拒掉")
  void search_clampsLimitToApiMaximum() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.start();
      server.enqueue(new MockResponse.Builder().code(200).body(SAMPLE_RESPONSE).build());

      new TavilySearchProvider(server.url("/search").toString(), FAKE_KEY).search("杭州", 200);

      // 调用方传的是 200（WebSearchTools 的页大小），发出去的必须是 20
      assertThat(server.takeRequest().getBody().utf8())
          .contains("\"max_results\":20")
          .doesNotContain("200");
    }
  }

  @Test
  @DisplayName("结果为空数组：返回空清单，不抛异常（「搜到了但没结果」不是故障）")
  void search_emptyResults_returnsEmptyList() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.start();
      server.enqueue(
          new MockResponse.Builder().code(200).body("{\"query\":\"x\",\"results\":[]}").build());

      List<SearchResult> results =
          new TavilySearchProvider(server.url("/search").toString(), FAKE_KEY).search("x", 5);

      assertThat(results).isEmpty();
    }
  }

  @Test
  @DisplayName("服务端返回非 2xx：抛 UncheckedIOException，且异常消息里不含 key")
  void search_non2xx_throwsWithoutLeakingKey() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.start();
      server.enqueue(
          new MockResponse.Builder().code(401).body("{\"detail\":\"unauthorized\"}").build());
      TavilySearchProvider provider =
          new TavilySearchProvider(server.url("/search").toString(), FAKE_KEY);

      assertThatThrownBy(() -> provider.search("杭州", 5))
          .isInstanceOf(UncheckedIOException.class)
          .hasMessageContaining("401")
          // 凭证不进日志：异常消息是它最容易被带出去的地方
          .hasMessageNotContaining(FAKE_KEY);
    }
  }

  @Test
  @DisplayName("端点不可达：抛 UncheckedIOException")
  void search_unreachableEndpoint_throwsUncheckedIOException() {
    TavilySearchProvider provider = new TavilySearchProvider(UNREACHABLE, FAKE_KEY);

    assertThatThrownBy(() -> provider.search("杭州", 5)).isInstanceOf(UncheckedIOException.class);
  }

  @Test
  @DisplayName("endpoint() 就是打请求用的那个地址（沙箱校验对象与真实目标一致）")
  void endpoint_matchesTheAddressActuallyRequested() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.start();
      server.enqueue(new MockResponse.Builder().code(200).body("{\"results\":[]}").build());
      String endpoint = server.url("/search").toString();
      TavilySearchProvider provider = new TavilySearchProvider(endpoint, FAKE_KEY);

      assertThat(provider.endpoint()).isEqualTo(endpoint);

      provider.search("杭州", 5);
      assertThat(server.takeRequest().getTarget()).startsWith("/");
    }
  }

  @Test
  @DisplayName("生产构造打的是 Tavily 的公开检索端点")
  void defaultConstructor_targetsThePublicSearchEndpoint() {
    assertThat(new TavilySearchProvider(FAKE_KEY).endpoint())
        .isEqualTo("https://api.tavily.com/search");
  }
}
