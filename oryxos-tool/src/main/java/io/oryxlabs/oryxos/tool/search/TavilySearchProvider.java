package io.oryxlabs.oryxos.tool.search;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.ai.util.json.JsonParser;

/**
 * {@link SearchProvider} 的<b>带 key 一档</b>：Tavily 的 Search API（FR-018）。
 *
 * <p><b>为什么还要一档带 key 的</b>：免 key 的 {@link DuckDuckGoSearchProvider} 打的是 Instant Answer——那是「问答」接口
 * 不是「检索」接口，长尾查询（比如某个库的发布说明）经常一条结果都返回不了。核心阶段仍然默认用它（不注册账号就能跑通）， 但只要配了 {@code
 * TAVILY_API_KEY}，装配处就换成这一档，{@code web_search} 与调用方一行不改。
 *
 * <p><b>key 不进任何日志与异常消息</b>（FR-027 同款口径）：异常里只说 HTTP 状态码，绝不回显凭证；{@link SearchProvider.SearchResult}
 * 只装 title / url / snippet，也没有位置放 key。本类不生成 {@code toString}。
 *
 * <p><b>用 JDK 自带的 {@link HttpClient} 与既有的 {@code JsonParser}</b>：不新增任何第三方依赖（软门禁 ⑥）—— 请求体由 {@code
 * JsonParser.toJson} 拼（查询词里带引号也不会拼坏），响应由 {@code fromJson} 解。
 */
public class TavilySearchProvider implements SearchProvider {

  /** Tavily 的检索端点。 */
  private static final String DEFAULT_ENDPOINT = "https://api.tavily.com/search";

  /**
   * 响应里承载结果数组的字段名。
   *
   * <p>单列成常量是因为它出现在 {@code if} 条件里——P3C 的 {@code UndefineMagicConstantRule} 禁止条件里出现魔法值。
   * 同一段解析里的其余字段名（{@link #TITLE_FIELD} 等）一并提出来，免得同一个 wire 格式一半是常量、一半是字面量。
   */
  private static final String RESULTS_FIELD = "results";

  private static final String TITLE_FIELD = "title";

  private static final String URL_FIELD = "url";

  private static final String CONTENT_FIELD = "content";

  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  /**
   * Tavily 单次请求的 {@code max_results} 上限。
   *
   * <p>调用方 {@code WebSearchTools} 传的是 200，超过这个上限服务端会直接拒掉整次请求——所以必须在这里夹住， 而不是指望调用方知道别家 API 的脾气。
   */
  private static final int MAX_RESULTS_PER_CALL = 20;

  private final String endpoint;

  private final String apiKey;

  private final HttpClient client;

  /**
   * 生产构造：打 Tavily 的公开端点。
   *
   * @param apiKey Tavily 的 API key，由装配处从环境变量解析后注入；本类不读环境、不读配置
   */
  public TavilySearchProvider(String apiKey) {
    this(DEFAULT_ENDPOINT, apiKey);
  }

  /**
   * 包级私有：单测把端点指向本地假服务器，从而能验证真实实现（鉴权头、请求体、响应解析、非 2xx 处理）而<b>不碰外网</b>。
   *
   * <p>与 {@link DuckDuckGoSearchProvider} 的包级私有构造同款惯例：测试够得着、生产够不着、不新增任何 public 概念。
   *
   * @param endpoint 完整端点
   * @param apiKey 凭证
   */
  TavilySearchProvider(String endpoint, String apiKey) {
    this.endpoint = endpoint;
    this.apiKey = apiKey;
    this.client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
  }

  @Override
  public String endpoint() {
    return endpoint;
  }

  @Override
  public List<SearchResult> search(String query, int limit) {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(endpoint))
            .timeout(TIMEOUT)
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(bodyFor(query, limit), StandardCharsets.UTF_8))
            .build();

    String body;
    try {
      HttpResponse<String> response =
          client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      int code = response.statusCode();
      if (code < HttpURLConnection.HTTP_OK || code >= HttpURLConnection.HTTP_MULT_CHOICE) {
        // 只报状态码：响应体可能回显请求信息，异常消息会进日志
        throw new UncheckedIOException(new IOException("搜索服务返回 HTTP " + code));
      }
      body = response.body();
    } catch (IOException e) {
      throw new UncheckedIOException("搜索服务不可达: " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new UncheckedIOException("搜索被中断", new IOException(e));
    }

    return parse(body, limit);
  }

  private static String bodyFor(String query, int limit) {
    int capped = Math.min(Math.max(limit, 1), MAX_RESULTS_PER_CALL);
    return JsonParser.toJson(Map.of("query", query, "max_results", capped));
  }

  /** 把 Tavily 的 {@code results[]} 摊平成统一的结果类型；空数组就是「搜到了但没结果」。 */
  private static List<SearchResult> parse(String body, int limit) {
    // 走 requireNonNullElse 而不是显式判空：JSON 文本是字面量 "null" 时 fromJson 确实返回 null，但 SpotBugs 认定该返回值
    // 非空、把显式判空报成 RCN_REDUNDANT_NULLCHECK。语义一样，写法换成它不认得的那个
    Map<String, Object> root =
        Objects.requireNonNullElse(JsonParser.fromJson(body, Map.class), Map.of());
    List<SearchResult> results = new ArrayList<>();
    if (root.get(RESULTS_FIELD) instanceof List<?> entries) {
      for (Object entry : entries) {
        if (entry instanceof Map<?, ?> map) {
          results.add(
              new SearchResult(
                  text(map.get(TITLE_FIELD)),
                  text(map.get(URL_FIELD)),
                  text(map.get(CONTENT_FIELD))));
        }
      }
    }
    return results.size() > limit ? List.copyOf(results.subList(0, limit)) : results;
  }

  private static String text(Object value) {
    return value == null ? "" : String.valueOf(value);
  }
}
