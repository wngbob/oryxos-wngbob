package io.oryxlabs.oryxos.memory.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Mem0 档的报文契约：请求体长什么样、响应怎么读、失败怎么报。
 *
 * <p><b>不碰真 server</b>：{@code MockRestServiceServer} 是 spring-test 既有能力（已核实本地依赖里 {@code
 * bindTo(RestClient.Builder)} 存在），不引 okhttp / mockwebserver3 这类新依赖。
 *
 * <p><b>报文是我们自己定的最小契约，从未与真实 Mem0 服务对过</b>——本节只做 REST 集成，不做真连。 本类钉死的是「我们这一侧发出/读入的形状」，不是「Mem0
 * 官方的形状」；真接上外部服务时要先对一遍报文。
 *
 * <p><b>「服务不可达」那条走真的 127.0.0.1 连接</b>：mock server 只能造 HTTP 层的错， 造不出连接被拒。而「不可达时静默返回空」正是这条要拦的缺陷——用
 * mock 模拟它，测的就是 mock 自己了。
 */
class Mem0MemoryStoreTest {

  private static final String BASE_URL = "http://mem0.local:8080";

  /**
   * 凭证在地址里：任何输出（日志、异常消息、toString）带出它都算泄漏。
   *
   * <p>主机写 {@code 127.0.0.1:1} 而不是一个虚构域名——虚构域名要走 DNS 解析，失败一次要等好几秒（实测本类因此从 1 秒涨到 15 秒）； 而 {@code
   * 127.0.0.1:1} 上不会有监听者，立刻连接被拒，同样不出网。
   */
  private static final String BASE_URL_WITH_TOKEN = "http://token-should-never-leak@127.0.0.1:1";

  private MockRestServiceServer server;

  private RestClient.Builder builder;

  private Mem0MemoryStore memory;

  @BeforeEach
  void bindMockServer() {
    builder = RestClient.builder().baseUrl(BASE_URL);
    server = MockRestServiceServer.bindTo(builder).build();
    memory = new Mem0MemoryStore(builder.build(), "u-1");
  }

  @Test
  @DisplayName("append 发 POST /memories，请求体带 user_id 与 metadata.scope")
  void appendSendsScopeInMetadata() {
    server
        .expect(requestTo(BASE_URL + "/memories"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.user_id").value("u-1"))
        .andExpect(jsonPath("$.metadata.scope").value("CORE"))
        .andExpect(jsonPath("$.messages[0].content").value("用户偏好用 Java"))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

    memory.append("用户偏好用 Java", MemoryScope.CORE);

    server.verify();
  }

  @Test
  @DisplayName("append 归档区发出去的 scope 是 ARCHIVAL——分区靠 metadata 带过去，Mem0 本身没有分区概念")
  void appendSendsArchivalScope() {
    server
        .expect(requestTo(BASE_URL + "/memories"))
        .andExpect(jsonPath("$.metadata.scope").value("ARCHIVAL"))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

    memory.append("一条流水", MemoryScope.ARCHIVAL);

    server.verify();
  }

  @Test
  @DisplayName("空白正文一个请求都不发——不许拿空串去污染外部记忆库")
  void blankContentSendsNothing() {
    memory.append("   ", MemoryScope.CORE);
    memory.append(null, MemoryScope.ARCHIVAL);

    server.verify();
  }

  @Test
  @DisplayName("load() 把两个 scope 分别渲染到对应区块，且不做本地截断（截断交给 Mem0 自己的作用域机制）")
  void loadRendersBothSectionsWithoutLocalTruncation() {
    server
        .expect(requestTo(BASE_URL + "/memories?user_id=u-1"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(
            withSuccess(
                "{\"results\":["
                    + "{\"memory\":\"核心一条\",\"metadata\":{\"scope\":\"CORE\"}},"
                    + "{\"memory\":\"归档一条\",\"metadata\":{\"scope\":\"ARCHIVAL\"}}"
                    + "]}",
                MediaType.APPLICATION_JSON));

    String loaded = memory.load();

    assertThat(loaded).startsWith("## 核心记忆").contains("核心一条").contains("## 归档记忆").contains("归档一条");
    // 核心区在前、归档区在后
    assertThat(loaded.indexOf("核心一条")).isLessThan(loaded.indexOf("归档一条"));
    server.verify();
  }

  @Test
  @DisplayName("load() 对端返回 2xx 但一条记忆都没有时给空串——不吐只有 header 的残渣")
  void loadWithoutMemoriesReturnsEmptyString() {
    server
        .expect(requestTo(BASE_URL + "/memories?user_id=u-1"))
        .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));

    assertThat(memory.load()).isEmpty();
  }

  @Test
  @DisplayName("recall 发 POST /search，请求体带 query 与 filters.scope=ARCHIVAL——只搜归档区由服务端过滤")
  void recallForwardsQueryWithArchivalFilter() {
    server
        .expect(requestTo(BASE_URL + "/search"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(jsonPath("$.query").value("部署"))
        .andExpect(jsonPath("$.user_id").value("u-1"))
        .andExpect(jsonPath("$.filters.scope").value("ARCHIVAL"))
        .andRespond(
            withSuccess("{\"results\":[{\"memory\":\"上周聊过部署\"}]}", MediaType.APPLICATION_JSON));

    assertThat(memory.recallByKeyword("部署")).containsExactly("上周聊过部署");

    server.verify();
  }

  @Test
  @DisplayName("空白关键词一个请求都不发，直接返回空列表")
  void blankKeywordSendsNothing() {
    assertThat(memory.recallByKeyword("  ")).isEmpty();
    assertThat(memory.recallByKeyword(null)).isEmpty();

    server.verify();
  }

  @Test
  @DisplayName("capabilities 是 DELEGATED 而不是 KEYWORD——Mem0 自带 search 是语义检索，声明成字面匹配会白白丢掉它最值钱的能力")
  void capabilitiesAreDelegated() {
    assertThat(memory.capabilities()).isEqualTo(MemoryCapability.DELEGATED);
  }

  @Test
  @DisplayName("archivalEntries 返回空列表——记忆在外部服务里，本进程没有「归档区全量条目」这个视图，MUST NOT 拿 load() 冒充")
  void archivalEntriesAreEmpty() {
    assertThat(memory.archivalEntries()).isEmpty();
  }

  @Test
  @DisplayName("对端报错时以明确错误上抛，MUST NOT 静默返回空——静默返回空会让 Agent 以为记忆是空的，进而重复问用户已经说过的事")
  void serverErrorIsThrownNotSwallowed() {
    server.expect(requestTo(BASE_URL + "/memories?user_id=u-1")).andRespond(withServerError());

    assertThatThrownBy(() -> memory.load())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Mem0");
  }

  @Test
  @DisplayName("服务真的连不上时同样明确上抛，绝不静默返回空（走真连接，mock 造不出连接被拒）")
  void unreachableServiceIsThrownNotSwallowed() {
    // 127.0.0.1:1 上不会有任何监听者：立刻连接被拒，不出网
    Mem0MemoryStore unreachable =
        new Mem0MemoryStore(RestClient.builder().baseUrl("http://127.0.0.1:1").build(), "u-1");

    assertThatThrownBy(unreachable::load)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Mem0");
  }

  @Test
  @DisplayName("异常消息与 cause 都不含 baseUrl——RestClient 的异常 message 会把请求 URL 原样带出来，而地址含凭证")
  void errorsNeverLeakTheBaseUrl() {
    Mem0MemoryStore leaky =
        new Mem0MemoryStore(RestClient.builder().baseUrl(BASE_URL_WITH_TOKEN).build(), "u-1");

    assertThatThrownBy(leaky::load)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageNotContaining("token-should-never-leak")
        // 刻意不挂 cause：挂上去等于把带凭证的 URL 送进日志和审计表
        .hasCause(null);
  }

  @Test
  @DisplayName("toString() 不含 baseUrl——默认实现本来就不含，显式钉死是防后来有人给它加一个 IDE 生成的 toString()")
  void toStringNeverLeaksTheBaseUrl() {
    Mem0MemoryStore leaky =
        new Mem0MemoryStore(RestClient.builder().baseUrl(BASE_URL_WITH_TOKEN).build(), "u-1");

    assertThat(leaky.toString())
        .doesNotContain("token-should-never-leak")
        .doesNotContain("127.0.0.1");
  }

  @Test
  @DisplayName("构造时 baseUrl / userId 为空或空白直接报错，不留到第一次调用才炸——那时报的是「连接失败」，真因在装配处")
  void blankConstructorArgumentsAreRejected() {
    assertThatThrownBy(() -> new Mem0MemoryStore("  ", "u-1"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("base-url");
    // 显式转 String：裸 null 在两个构造器之间是歧义的
    assertThatThrownBy(() -> new Mem0MemoryStore((String) null, "u-1"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Mem0MemoryStore(BASE_URL, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("user-id");
    assertThatThrownBy(() -> new Mem0MemoryStore(BASE_URL, " "))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("响应体不是合法 JSON 时明确报错，不把解析失败当成「没有记忆」")
  void malformedResponseBodyIsRejected() {
    server
        .expect(requestTo(BASE_URL + "/memories?user_id=u-1"))
        .andRespond(withSuccess("not-json-at-all", MediaType.APPLICATION_JSON));

    assertThatThrownBy(() -> memory.load())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("JSON");
  }

  @Test
  @DisplayName("每次调用都真的发请求——不缓存，记忆在别处也可能被改")
  void everyCallHitsTheServiceNoCaching() {
    server
        .expect(requestTo(BASE_URL + "/memories?user_id=u-1"))
        .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));
    server
        .expect(requestTo(BASE_URL + "/memories?user_id=u-1"))
        .andRespond(
            withSuccess(
                "{\"results\":[{\"memory\":\"后来才记的\",\"metadata\":{\"scope\":\"CORE\"}}]}",
                MediaType.APPLICATION_JSON));

    assertThat(memory.load()).isEmpty();
    assertThat(memory.load()).contains("后来才记的");

    server.verify();
  }

  @Test
  @DisplayName("没有 scope 的条目不属于任何区、不进 prompt——我们自己的写入永远带 scope，没带说明这条不是本进程写的")
  void entriesWithoutMetadataAreNotAttributedToAnySection() {
    server
        .expect(requestTo(BASE_URL + "/memories?user_id=u-1"))
        .andRespond(
            withSuccess(
                "{\"results\":[{\"memory\":\"没有 metadata 的一条\"}]}", MediaType.APPLICATION_JSON));

    // 严格只认 CORE / ARCHIVAL 两个取值：替来历不明的条目猜一个区，比不注入它更危险
    assertThat(memory.load()).isEmpty();
  }

  @Test
  @DisplayName("构造器不覆盖注入方给的请求工厂——否则 mock 静默失效，测试照样绿而它一个请求都没拦到")
  void injectedClientKeepsItsOwnRequestFactory() {
    RestClient.Builder injected = RestClient.builder().baseUrl(BASE_URL);
    MockRestServiceServer boundServer = MockRestServiceServer.bindTo(injected).build();
    Mem0MemoryStore store = new Mem0MemoryStore(injected.build(), "u-1");

    boundServer
        .expect(requestTo(BASE_URL + "/memories?user_id=u-1"))
        .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));

    assertThat(store.load()).isEmpty();
    boundServer.verify();
  }

  @Test
  @DisplayName("load() 渲染出的文本里核心区在归档区之前，且不含分区之外的条目")
  void loadOnlyEmitsKnownScopes() {
    server
        .expect(requestTo(BASE_URL + "/memories?user_id=u-1"))
        .andRespond(
            withSuccess(
                "{\"results\":["
                    + "{\"memory\":\"B 归档\",\"metadata\":{\"scope\":\"ARCHIVAL\"}},"
                    + "{\"memory\":\"A 核心\",\"metadata\":{\"scope\":\"CORE\"}}"
                    + "]}",
                MediaType.APPLICATION_JSON));

    List<String> lines = List.of(memory.load().split("\n"));

    assertThat(lines).contains("## 核心记忆", "A 核心", "## 归档记忆", "B 归档");
    assertThat(lines.indexOf("A 核心")).isLessThan(lines.indexOf("## 归档记忆"));
  }
}
