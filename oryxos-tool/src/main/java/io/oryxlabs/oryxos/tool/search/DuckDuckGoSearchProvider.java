package io.oryxlabs.oryxos.tool.search;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
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
 * {@link SearchProvider} 的核心阶段实现：DuckDuckGo 的 <b>Instant Answer</b> 接口（免 key，FR-018）。
 *
 * <p><b>为什么选免 key 的一档</b>：核心阶段的验收不该依赖任何一个需要注册账号才能跑通的外部服务。免 key 接口让「装上就能用」成立； 需要 key 的（Google / Bing
 * / Tavily）作为后续实现类加入，接缝与调用方零改动。
 *
 * <p><b>返回结构映射</b>：Instant Answer 的响应里，最相关的一条在顶层 {@code AbstractText} + {@code AbstractURL}，其次是
 * {@code Results} 与 {@code RelatedTopics}（后者可能嵌套一层 {@code Topics}）。三处都摊平成同一种 {@link
 * SearchProvider.SearchResult}——工具侧只认接缝的类型，不认任何一家搜索引擎的响应形状。
 *
 * <p><b>超时与 {@code http_get} 同款 30 秒</b>：外部服务不可达时必须有界返回，否则一次搜索能把 ReAct 循环挂死。
 *
 * <p><b>显式带 {@code User-Agent}</b>：这一档被端点的 UA 策略挡过（JDK 默认 UA 回 403），详见 {@link #USER_AGENT}。
 */
public class DuckDuckGoSearchProvider implements SearchProvider {

  /** 免 key 的 Instant Answer 端点。 */
  private static final String DEFAULT_ENDPOINT = "https://api.duckduckgo.com/";

  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  /**
   * 请求头 {@code User-Agent} 的名字。
   *
   * <p>提成常量只因为它同时出现在实现与断言两侧，改一处就得改两处。
   */
  private static final String USER_AGENT_HEADER = "User-Agent";

  /**
   * 显式声明 UA，<b>不能用 JDK 的默认值</b>。
   *
   * <p>2026-10-15 实测：同一个端点，JDK 默认的 {@code Java-http-client/21.0.12} 被回 <b>HTTP 403</b>，换成浏览器 UA 就回
   * <b>HTTP 202</b>（用 curl 固定变量后逐次确认，见验收报告「证据六·补 ⑤」）。也就是说这一档在修之前对任何一台机器都拿不到结果，不是环境问题。
   *
   * <p>写成「浏览器前缀 + 自我介绍」而不是裸的 {@code Mozilla/5.0}：接口方若按 UA 分流，认的是前缀；而对方运维想查是谁在打时，后半段能指回来。
   */
  private static final String USER_AGENT =
      "Mozilla/5.0 (compatible; OryxOS/0.1; +https://github.com/wngbob/oryxos-wngbob)";

  private final String baseUrl;

  private final HttpClient client;

  /** 生产构造：打 DuckDuckGo 的公开端点。 */
  public DuckDuckGoSearchProvider() {
    this(DEFAULT_ENDPOINT);
  }

  /**
   * 包级私有：单测把端点指向本地假服务器，从而能验证真实实现（查询串拼装、响应解析、非 2xx 处理）而<b>不碰外网</b>。
   *
   * <p>与第 19 节 {@code WebhookNotifyAdapter} 的包级私有构造同款惯例：测试够得着、生产够不着、不新增任何 public 概念。
   *
   * @param baseUrl 端点前缀（不带查询串）
   */
  DuckDuckGoSearchProvider(String baseUrl) {
    this.baseUrl = baseUrl;
    this.client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
  }

  @Override
  public String endpoint() {
    return baseUrl;
  }

  @Override
  public List<SearchResult> search(String query, int limit) {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(urlFor(query)))
            .timeout(TIMEOUT)
            .GET()
            .header("Accept", "application/json")
            .header(USER_AGENT_HEADER, USER_AGENT)
            .build();

    String body;
    try {
      HttpResponse<String> response =
          client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      int code = response.statusCode();
      if (code < HttpURLConnection.HTTP_OK || code >= HttpURLConnection.HTTP_MULT_CHOICE) {
        throw new UncheckedIOException(new IOException("搜索服务返回 HTTP " + response.statusCode()));
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

  private String urlFor(String query) {
    return baseUrl
        + "?q="
        + URLEncoder.encode(query, StandardCharsets.UTF_8)
        + "&format=json&no_html=1&skip_disambig=1";
  }

  /** 把 Instant Answer 的响应摊平成统一的结果类型；三个来源都为空就是「搜到了但没结果」。 */
  private static List<SearchResult> parse(String body, int limit) {
    // 走 requireNonNullElse 而不是显式判空：JSON 文本是字面量 "null" 时 fromJson 确实返回 null，但 SpotBugs 认定该返回值
    // 非空、把显式判空报成 RCN_REDUNDANT_NULLCHECK。语义一样，写法换成它不认得的那个
    Map<String, Object> root =
        Objects.requireNonNullElse(JsonParser.fromJson(body, Map.class), Map.of());
    List<SearchResult> results = new ArrayList<>();

    String abstractText = text(root.get("AbstractText"));
    if (!abstractText.isEmpty()) {
      results.add(
          new SearchResult(text(root.get("Heading")), text(root.get("AbstractURL")), abstractText));
    }
    collect(results, root.get("Results"));
    collect(results, root.get("RelatedTopics"));

    return results.size() > limit ? List.copyOf(results.subList(0, limit)) : results;
  }

  /**
   * 收一个条目数组。
   *
   * <p>{@code RelatedTopics} 里既有平铺的条目，也有带 {@code Topics} 的分组——遇到分组就再下探一层，别把整组当一条结果丢出去。
   */
  private static void collect(List<SearchResult> results, Object raw) {
    if (!(raw instanceof List<?> entries)) {
      return;
    }
    for (Object entry : entries) {
      if (!(entry instanceof Map<?, ?> map)) {
        continue;
      }
      Object nested = map.get("Topics");
      if (nested != null) {
        collect(results, nested);
        continue;
      }
      String text = text(map.get("Text"));
      if (!text.isEmpty()) {
        results.add(new SearchResult(text, text(map.get("FirstURL")), text));
      }
    }
  }

  private static String text(Object value) {
    return value == null ? "" : String.valueOf(value);
  }
}
