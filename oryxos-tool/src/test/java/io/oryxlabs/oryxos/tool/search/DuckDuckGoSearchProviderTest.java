package io.oryxlabs.oryxos.tool.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.oryxlabs.oryxos.tool.search.SearchProvider.SearchResult;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link DuckDuckGoSearchProvider} 的七条守点（contracts/builtin-tools.md §2.10）。
 *
 * <p><b>为什么给真实实现写测试、而不是只测替身</b>：{@code WebSearchToolsTest} 证明「工具用对了接缝」，但它对
 * 「接缝背后的真实实现能不能用」一无所知——查询串拼错了、响应结构读错了，那一整片测试照样全绿。核心阶段挂的 这一档是**免 key 的真实实现**，它没有单测就等于没有验证。
 *
 * <p><b>用本地假服务器承接</b>（mockwebserver3），<b>不碰外网</b>（SC-008）：可重复、不依赖 DuckDuckGo 的可用性，
 * 也让「查询串到底长什么样」可以被断言。
 */
class DuckDuckGoSearchProviderTest {

  private static final String UNREACHABLE = "http://127.0.0.1:1/";

  private static final String SAMPLE_RESPONSE =
      """
      {
        "Heading": "杭州",
        "AbstractText": "杭州是浙江省省会。",
        "AbstractURL": "https://example.invalid/wiki/hangzhou",
        "Results": [{"Text": "杭州天气", "FirstURL": "https://example.invalid/w"}],
        "RelatedTopics": [
          {"Name": "分组", "Topics": [{"Text": "西湖", "FirstURL": "https://example.invalid/x"}]}
        ]
      }
      """;

  @Test
  @DisplayName("正常路径：查询词进查询串（URL 编码），三类结果摊平成统一结构")
  void search_encodesQuery_andFlattensAllThreeSources() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.start();
      server.enqueue(new MockResponse.Builder().code(200).body(SAMPLE_RESPONSE).build());

      List<SearchResult> results =
          new DuckDuckGoSearchProvider(server.url("/").toString()).search("杭州 天气", 200);

      // 摘要 → 条目 → 嵌套分组里的条目，三处都要出现，且顺序稳定（最相关的在前）
      assertThat(results).extracting(SearchResult::title).containsExactly("杭州", "杭州天气", "西湖");
      assertThat(results.get(0).url()).isEqualTo("https://example.invalid/wiki/hangzhou");

      String target = server.takeRequest().getTarget();
      assertThat(decodeQuery(target)).isEqualTo("杭州 天气");
      // 原样发出 ≠ 裸发：空格与中文都必须在查询串里被编码
      assertThat(target).doesNotContain(" ").doesNotContain("杭州").contains("format=json");
    }
  }

  @Test
  @DisplayName("请求必须带非 JDK 默认的 User-Agent：端点按 UA 分流，默认值会被回 403")
  void search_sendsNonDefaultUserAgent() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.start();
      server.enqueue(new MockResponse.Builder().code(200).body(SAMPLE_RESPONSE).build());

      new DuckDuckGoSearchProvider(server.url("/").toString()).search("杭州", 200);

      String userAgent = server.takeRequest().getHeaders().get("User-Agent");
      assertThat(userAgent).isNotBlank();
      // 这才是重点：JDK 不给 UA 时自己塞的是 Java-http-client/<版本>，实测会被回 403
      assertThat(userAgent).doesNotStartWith("Java-http-client");
    }
  }

  @Test
  @DisplayName("超出上限：只返回上限条数")
  void search_moreResultsThanLimit_areTruncated() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.start();
      server.enqueue(new MockResponse.Builder().code(200).body(SAMPLE_RESPONSE).build());

      List<SearchResult> results =
          new DuckDuckGoSearchProvider(server.url("/").toString()).search("杭州", 2);

      assertThat(results).hasSize(2);
    }
  }

  @Test
  @DisplayName("服务端返回非 2xx：抛 UncheckedIOException（调用方据此判为可重试）")
  void search_non2xx_throwsUncheckedIOException() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.start();
      server.enqueue(new MockResponse.Builder().code(503).body("upstream down").build());
      DuckDuckGoSearchProvider provider = new DuckDuckGoSearchProvider(server.url("/").toString());

      assertThatThrownBy(() -> provider.search("杭州", 200))
          .isInstanceOf(UncheckedIOException.class)
          .hasMessageContaining("503");
    }
  }

  @Test
  @DisplayName("端点不可达：抛 UncheckedIOException")
  void search_unreachableEndpoint_throwsUncheckedIOException() {
    DuckDuckGoSearchProvider provider = new DuckDuckGoSearchProvider(UNREACHABLE);

    assertThatThrownBy(() -> provider.search("杭州", 200)).isInstanceOf(UncheckedIOException.class);
  }

  @Test
  @DisplayName("endpoint() 就是打请求用的那个地址（沙箱校验对象与真实目标一致）")
  void endpoint_matchesTheAddressActuallyRequested() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.start();
      server.enqueue(new MockResponse.Builder().code(200).body("{}").build());
      String base = server.url("/").toString();
      DuckDuckGoSearchProvider provider = new DuckDuckGoSearchProvider(base);

      assertThat(provider.endpoint()).isEqualTo(base);

      provider.search("杭州", 200);
      assertThat(server.takeRequest().getTarget()).startsWith("/");
    }
  }

  @Test
  @DisplayName("生产构造打的是免 key 的公开端点（核心阶段不依赖任何需要注册的服务）")
  void defaultConstructor_targetsTheKeylessPublicEndpoint() {
    assertThat(new DuckDuckGoSearchProvider().endpoint()).isEqualTo("https://api.duckduckgo.com/");
  }

  /** 从请求目标里取出 {@code q} 参数并解码，用来断言「查询词是原样发出去的」。 */
  private static String decodeQuery(String target) {
    String raw = target.substring(target.indexOf("q=") + 2);
    int ampersand = raw.indexOf('&');
    if (ampersand >= 0) {
      raw = raw.substring(0, ampersand);
    }
    return URLDecoder.decode(raw, StandardCharsets.UTF_8);
  }
}
