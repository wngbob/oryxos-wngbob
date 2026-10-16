package io.oryxlabs.oryxos.memory.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 外部集成档：长期记忆交给一个自托管的 <b>Mem0</b> 服务，Java 侧只发 REST。
 *
 * <p><b>什么时候才该换到这一档</b>：真需要自动抽取、冲突消解、语义检索的时候。前两档都是「Agent 说什么就记什么、按字面查」，
 * 这一档把这些交给外部框架——代价是多了一个要运维的服务，以及本进程再也看不见记忆长什么样（故 {@link #archivalEntries()} 只能返回空）。
 *
 * <p><b>它是「接口先行」那条升级路径的实证</b>：门面、{@code MemoryTools}、{@code PromptBuilder} 一行都没改， 只是换了一个 {@code
 * LongTermMemoryStore} 实现——这就是「墙之下可以随时换实现」这句话的兑现。
 *
 * <p><b>{@code capabilities()} 是 {@code DELEGATED} 而不是 {@code KEYWORD}</b>：Mem0 自带的 {@code search}
 * 是语义检索， 比「关键词包含」更强。声明成 {@code KEYWORD} 会让门面以为这一档只能做字面匹配，白白丢掉它最值钱的能力。
 *
 * <p><b>{@code baseUrl} 含凭证，故本类不打日志、异常消息不含它、{@code toString()} 也不含它</b>（FR-019）。 异常<b>刻意不挂
 * cause</b>：{@code ResourceAccessException} 的 message 会把请求 URL 原样带出来，挂上去等于把凭证送进审计表。
 *
 * <p><b>不缓存</b>：{@code load()} 每次都调 API。外部服务的响应本来就不该被本地缓存——记忆在别处也可能被改。
 *
 * <p><b>{@code final}</b>：两个构造器都会在参数非法时抛异常，而可被继承的类抛构造器异常时，子类可以借 finalizer 拿到一个「部分初始化」
 * 的实例（FindSecBugs 的 {@code CT_CONSTRUCTOR_THROW}）。本类是叶子实现，本就不为继承设计，声明 {@code final} 是这件事的正解。
 */
public final class Mem0MemoryStore implements LongTermMemoryStore {

  /** 连接与读取各自的上限：外部服务不可达时要尽快失败，而不是把整轮对话吊死。 */
  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

  /** 只用来拼请求体与读响应体；{@code readTree} / {@code writeValueAsString} 都是线程安全的。 */
  private static final ObjectMapper JSON = new ObjectMapper();

  private static final String SCOPE_CORE = "CORE";

  private static final String SCOPE_ARCHIVAL = "ARCHIVAL";

  /**
   * Mem0 响应里装记忆列表的字段名。
   *
   * <p><b>报文是我们自己定的最小契约，从未与真实 Mem0 服务对过</b>——真接上外部服务时，第一个要核的就是这个字段名。
   */
  private static final String RESULTS_FIELD = "results";

  private final RestClient restClient;

  /** 记忆归属的用户标识，Mem0 用它做隔离。 */
  private final String userId;

  /**
   * 生产构造：自建带超时的 {@link RestClient}。
   *
   * <p>超时收口在实现内、不推给注入方——「调用有界」是这一档自己的事，装配处最容易漏掉这个约束。
   *
   * @param baseUrl Mem0 服务地址（含凭证），MUST NOT 出现在任何日志或异常消息里
   * @param userId 记忆归属的用户标识
   */
  public Mem0MemoryStore(String baseUrl, String userId) {
    String checkedBaseUrl = requireNonBlank(baseUrl, "Mem0 base-url 不能为空");
    HttpClient httpClient = HttpClient.newBuilder().connectTimeout(DEFAULT_TIMEOUT).build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(DEFAULT_TIMEOUT);
    this.restClient =
        RestClient.builder().baseUrl(checkedBaseUrl).requestFactory(requestFactory).build();
    this.userId = requireNonBlank(userId, "Mem0 user-id 不能为空");
  }

  /**
   * 供同包测试注入 mock 客户端的构造。
   *
   * <p><b>刻意不在这个构造里碰 {@link RestClient.Builder}</b>：{@code MockRestServiceServer.bindTo(builder)} 与
   * {@code builder.build()} 必须是同一个 builder 实例，本类若自己再装一个 {@code requestFactory}，就会把 mock 的请求工厂覆盖掉，
   * mock 静默失效——测试照样绿，而它其实一个请求都没拦到。
   *
   * @param restClient 已经建好的客户端（生产路径不用这个构造）
   * @param userId 记忆归属的用户标识
   */
  Mem0MemoryStore(RestClient restClient, String userId) {
    this.restClient = Objects.requireNonNull(restClient, "RestClient 不能为空");
    this.userId = requireNonBlank(userId, "Mem0 user-id 不能为空");
  }

  /**
   * 空串与空白串一并挡掉：配错的地址留到第一次调用才炸，报出来的是「连接失败」，而真正的原因在装配处。
   *
   * <p>报错消息里<b>只有字段名、没有值</b>——地址含凭证（FR-019）。
   *
   * @param value 待校验的值
   * @param message 为空时的报错消息
   * @return 原值
   */
  private static String requireNonBlank(String value, String message) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(message);
    }
    return value;
  }

  @Override
  public void append(String content, MemoryScope scope) {
    if (content == null || content.isBlank()) {
      return;
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("messages", List.of(Map.of("role", "user", "content", content)));
    payload.put("user_id", userId);
    // scope 走 metadata：Mem0 本身没有分区概念，分区是 OryxOS 这一侧的口径，得自己带过去
    payload.put(
        "metadata", Map.of("scope", scope == MemoryScope.CORE ? SCOPE_CORE : SCOPE_ARCHIVAL));
    call(
        () ->
            restClient
                .post()
                .uri("/memories")
                .contentType(MediaType.APPLICATION_JSON)
                .body(toJson(payload))
                .retrieve()
                .toBodilessEntity());
  }

  @Override
  public String load() {
    byte[] body =
        call(
            () ->
                restClient
                    .get()
                    .uri(
                        uriBuilder ->
                            uriBuilder.path("/memories").queryParam("user_id", userId).build())
                    .retrieve()
                    .toEntity(byte[].class)
                    .getBody());
    // 核心区全量、归档区不截断：截断交给 Mem0 自己的作用域机制，本进程不做第二道裁剪
    List<String> core = memoriesOf(body, SCOPE_CORE);
    List<String> archive = memoriesOf(body, SCOPE_ARCHIVAL);
    if (core.isEmpty() && archive.isEmpty()) {
      return "";
    }
    StringBuilder text = new StringBuilder("## 核心记忆\n");
    if (!core.isEmpty()) {
      text.append(String.join("\n", core)).append('\n');
    }
    text.append("\n## 归档记忆\n");
    if (!archive.isEmpty()) {
      text.append(String.join("\n", archive)).append('\n');
    }
    return text.toString().strip();
  }

  @Override
  public List<String> recallByKeyword(String keyword) {
    if (keyword == null || keyword.isBlank()) {
      return List.of();
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("query", keyword);
    payload.put("user_id", userId);
    // 只搜归档区：核心区本来就会被全量注入，参与检索等于同一件事在上下文里出现两遍
    payload.put("filters", Map.of("scope", SCOPE_ARCHIVAL));
    byte[] body =
        call(
            () ->
                restClient
                    .post()
                    .uri("/search")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(toJson(payload))
                    .retrieve()
                    .toEntity(byte[].class)
                    .getBody());
    return memoriesOf(body, null);
  }

  @Override
  public MemoryCapability capabilities() {
    return MemoryCapability.DELEGATED;
  }

  @Override
  public List<String> archivalEntries() {
    // 记忆存在外部服务里，本进程没有「归档区全量条目」这个视图——如实返回空，MUST NOT 拿 load() 的结果冒充
    return List.of();
  }

  /**
   * 调一次外部服务，把失败统一包成明确错误。
   *
   * <p><b>MUST NOT 静默返回空</b>：静默返回空会让 Agent 以为「记忆是空的」，进而重复询问用户已经说过的事——那比报错难查得多。
   *
   * @param call 一次 REST 调用
   * @return 调用结果
   */
  private static <T> T call(RestCall<T> call) {
    try {
      return call.run();
    } catch (RestClientException e) {
      // 消息里只有异常类型名：RestClient 的异常 message 会把请求 URL 原样带出来，而 base-url 含凭证
      throw new IllegalStateException(
          "调用 Mem0 长期记忆服务失败（"
              + e.getClass().getSimpleName()
              + "）：请检查 oryxos.memory.mem0.base-url 配置与 Mem0 服务状态",
          null);
    }
  }

  private static String toJson(Map<String, Object> payload) {
    try {
      return JSON.writeValueAsString(payload);
    } catch (IOException e) {
      throw new UncheckedIOException("序列化 Mem0 请求体失败", e);
    }
  }

  /**
   * 从 Mem0 的响应里挑出记忆正文。
   *
   * <p><b>只认 {@code CORE} / {@code ARCHIVAL} 两个取值</b>：没有 {@code metadata.scope} 的条目两边都不进。我们自己的写入
   * 永远带 scope，没带就说明这条不是本进程写的——替来历不明的条目猜一个区，比不注入它更危险（猜错会把它塞进一个不该在的区， 而猜错这件事在 prompt 里看不出来）。
   *
   * @param body 响应体；解析不出结果时按空处理（对端返回 2xx 但没有 {@code results} 字段 = 没有记忆）
   * @param scope 只挑该分区的条目；传 {@code null} 表示不筛（检索走这条，分区过滤由服务端做）
   * @return 记忆正文列表
   */
  private static List<String> memoriesOf(byte[] body, String scope) {
    List<String> memories = new ArrayList<>();
    if (body == null || body.length == 0) {
      return memories;
    }
    JsonNode root;
    try {
      root = JSON.readTree(body);
    } catch (IOException e) {
      throw new IllegalStateException("Mem0 返回的响应体不是合法 JSON，无法解析记忆内容");
    }
    for (JsonNode node : root.path(RESULTS_FIELD)) {
      String nodeScope = node.path("metadata").path("scope").asText("");
      if (scope == null || scope.equals(nodeScope)) {
        memories.add(node.path("memory").asText(""));
      }
    }
    return memories;
  }

  /**
   * <b>刻意只返回固定文案</b>：默认的 {@code Object.toString()} 不含字段，但任何人后来给它加一个 IDE 生成的 {@code toString()}（或
   * {@code @Data}）都会把 {@code baseUrl} 连同凭证打进日志。显式钉死这一条，比依赖「没人会加」可靠。
   *
   * @return 固定文案
   */
  @Override
  public String toString() {
    return "Mem0MemoryStore";
  }

  /** 一次 REST 调用；用它把失败包装收在一处。 */
  @FunctionalInterface
  private interface RestCall<T> {

    /**
     * 执行这次调用。
     *
     * @return 调用结果
     */
    T run();
  }
}
