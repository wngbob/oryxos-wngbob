package io.oryxlabs.oryxos.tool.notify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 飞书 / Lark 自定义机器人方言的推送实现（两者协议相同，故同一个类覆盖两个 {@code type} 取值）。
 *
 * <p><b>为什么需要它，以及它与 {@link WebhookNotifyAdapter} 的唯一实质差异</b>：飞书群机器人<b>用 HTTP 200 承载业务错误</b>
 * ——报文写错时回的是 {@code HTTP 200} + {@code {"code":19002,"msg":"params error, msg_type
 * need"}}，群里一条消息都没有。通用档只判状态码， 会把这次投递判成成功，于是 {@code notify} 返回「已推送」而实际没送到，失败连 {@code
 * tool_invocations} 都进不去。本类读响应体、按业务码判成败，把这种 「假成功」变回可落审计的失败——这是它存在的全部理由。
 *
 * <p><b>报文形状</b>：{@code {"msg_type":"text","content":{"text":"..."}}}（飞书自定义机器人 v2 hook 的约定）。
 * 这个方言知识只属于本类， MUST NOT 回流到接口或通用档：通用档的 {@code {"content":...}} 对通用 webhook 接收端是正确的，改它才是错。
 *
 * <p><b>送达判据是 fail-closed 的</b>：成功当且仅当响应体是 JSON 对象、且 {@code code} 存在且为整数且等于 0。缺 {@code code} 时退看
 * {@code StatusCode}（官方把它列为「兼容存量历史逻辑的冗余字段」，历史形态可能只回它）。其余一切情形——非 JSON、空 body、两个字段都没有、字段不是整数——一律判失败。
 * 理由：一个 2xx 却给不出任何明确成功信号的响应，正是「假成功」最可能藏身之处；判失败会让它<b>吵</b>（审计里有记录），判成功会让它<b>静默</b>，本项目一贯选吵不选静默。
 *
 * <p><b>判据 MUST 用 {@code has(...)} + {@code isIntegralNumber()}，MUST NOT 写成 {@code
 * path("code").asInt() != 0}</b>：Jackson 的 {@code path(...).asInt()} 在<b>字段缺失时也返回
 * 0</b>，那个写法会把「缺失」判成成功，正好与 fail-closed 相反——等于用另一种形态把本类要消灭的缺陷重新引入。
 *
 * <p><b>签名校验（可选）</b>：{@code config} 里有 {@code secret} 时，按官方算法在请求体顶层附上 {@code timestamp} 与 {@code
 * sign} （{@code stringToSign = timestamp + "\n" + secret}，对其做 HMAC-SHA256 后 Base64 编码，message
 * 为空字节）。 未配 {@code secret} 时这两个键<b>不出现</b>（而不是出现为 {@code null}）：用 {@code Map} 逐键 put
 * 而非固定形状的记录，就是为了让「有就加、没有就不加」成为最直白的表达。
 *
 * <p><b>响应体读成 {@code byte[]} 而不是 {@code String}</b>：{@code StringHttpMessageConverter} 在响应头没带
 * charset 时可能按 ISO-8859-1 解码，而 {@code msg} 是要放进异常消息的中文。{@code byte[]} 交给 Jackson 自行探测编码更稳。
 *
 * <p><b>不新增自定义异常类型</b>（契约 §2.3）：失败一律抛 {@link IllegalStateException}——请求本身合法、是对端拒绝，与「参数不对」的 {@link
 * IllegalArgumentException} 不是一回事。异常消息含 {@code code} 与 {@code msg}，<b>MUST NOT 含 {@code url}</b>（含
 * token）。
 *
 * <p><b>非 JSON 响应体刻意不挂 cause</b>：Jackson 解析异常的 message 会带 body 片段，而 body 可能来自中间设备（网关的错误页常把请求 URL
 * 原样回显，那里面有 token）。审计表只写 {@code e.getMessage()}，本类的 message 是一句固定文案，不含 body 内容。
 *
 * <p><b>超时与 {@code Redirect.NEVER} 与通用档同口径</b>：自建带连接 + 读取超时的 {@link
 * HttpClient}（超时收口在实现内，不推给注入方），3xx 一律按失败上抛、不跟随——白名单校验发生在调用方（{@code NotifyTools} 拿 {@code url} 去
 * {@code enforce}），本类看不到沙箱，跟随重定向意味着请求可能落到白名单之外的主机。
 *
 * <p><b>不打日志</b>：{@code url} 与 {@code secret} 都是凭证，写进任何日志都是泄漏。
 *
 * <p><b>读 body 改变了超时语义（2026-10-15 实测确认）</b>：通用档用 {@code toBodilessEntity()} 不读
 * body，其读取上限只量到「响应头到达」； 本类必须读 body 才能看到业务码，于是对端「给了响应头却拖着不给 body」也会被上限兜住。两条路径抛出的异常类型不同——响应头超时是
 * {@code ResourceAccessException}，响应体读取超时被 Spring 在「提取响应体」那一步包成 {@code RestClientException}。两者都是
 * {@code RestClientException} 的实例，都会原样上抛到 {@code ToolExecutor} 的失败审计路径。
 */
public class FeishuNotifyAdapter implements NotifyChannelAdapter {

  /** 连接与读取各自的上限，合计即推送的整体上界。 */
  private static final Duration DEFAULT_PUSH_TIMEOUT = Duration.ofSeconds(10);

  /** {@link NotifyTarget#config()} 里承载地址的键。契约要求该键恒定存在（见 data-model.md §5 第 6 步）。 */
  private static final String URL_KEY = "url";

  /** {@link NotifyTarget#config()} 里承载签名密钥的键，<b>可选</b>：没有它就不开签名校验。 */
  private static final String SECRET_KEY = "secret";

  /** 只用来读响应体，不做序列化；{@code readTree} 是线程安全的。 */
  private static final ObjectMapper JSON = new ObjectMapper();

  /** 飞书文本消息的 {@code msg_type} 取值。 */
  private static final String TEXT_MSG_TYPE = "text";

  /** 官方给出的权威业务码字段。 */
  private static final String CODE_FIELD = "code";

  /** 官方标注为「兼容存量历史逻辑」的冗余业务码字段，仅在 {@link #CODE_FIELD} 缺失时兜底。 */
  private static final String LEGACY_CODE_FIELD = "StatusCode";

  private final RestClient restClient;

  private final Clock clock;

  /** 生产构造：内置 10 秒有界超时，时间取系统时钟。 */
  public FeishuNotifyAdapter() {
    this(DEFAULT_PUSH_TIMEOUT, Clock.systemUTC());
  }

  /**
   * 供同包测试注入短超时的构造。
   *
   * <p><b>刻意不是 public / protected</b>：把超时暴露成公开构造参数，等于对外承诺「调用方可以决定推送等待多久」，而那正是「等待有界」要收口的事。
   *
   * @param pushTimeout 连接与读取各自的上限
   */
  FeishuNotifyAdapter(Duration pushTimeout) {
    this(pushTimeout, Clock.systemUTC());
  }

  /**
   * 供同包测试注入固定时钟的构造：签名里的 {@code timestamp} 随时间变，不注入时钟就写不出确定性断言。
   *
   * @param pushTimeout 连接与读取各自的上限
   * @param clock 生成 {@code timestamp} 的时钟
   */
  FeishuNotifyAdapter(Duration pushTimeout, Clock clock) {
    this.clock = clock;
    HttpClient httpClient =
        HttpClient.newBuilder()
            .connectTimeout(pushTimeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(pushTimeout);
    this.restClient = RestClient.builder().requestFactory(requestFactory).build();
  }

  @Override
  public void send(NotifyTarget target, String content) {
    String url = target.config().get(URL_KEY);
    if (url == null || url.isBlank()) {
      throw new IllegalArgumentException("通知目标的配置里没有可用的 url，无法推送");
    }

    // 非 2xx 由 retrieve() 按状态码抛异常；2xx 里藏着的业务错误由 verifyDelivered 兜住——两道判定缺一不可
    byte[] body =
        restClient
            .post()
            .uri(url)
            .contentType(MediaType.APPLICATION_JSON)
            .body(payload(target, content))
            .retrieve()
            .toEntity(byte[].class)
            .getBody();
    verifyDelivered(body);
  }

  /**
   * 组装请求体。
   *
   * <p>用 {@link LinkedHashMap} 而不是固定形状的记录：{@code timestamp} / {@code sign}
   * 两个键<b>只在配了密钥时才存在</b>，用记录会让它们以 {@code null} 的形态出现在报文里，而一个带 {@code null} 值的签名字段对飞书而言是报文非法。
   *
   * @param target 通知目标，{@code secret} 从它的配置里取
   * @param content 要推送的正文
   * @return 待序列化的请求体
   */
  private Map<String, Object> payload(NotifyTarget target, String content) {
    Map<String, Object> body = new LinkedHashMap<>();
    String secret = target.config().get(SECRET_KEY);
    if (secret != null && !secret.isBlank()) {
      String timestamp = String.valueOf(clock.instant().getEpochSecond());
      body.put("timestamp", timestamp);
      body.put("sign", sign(timestamp, secret));
    }
    body.put("msg_type", TEXT_MSG_TYPE);
    // 内层同样用 LinkedHashMap 而不是 Map.of：Map.of 遇 null 直接抛 NPE，报错会指向与真实原因无关的一行
    // （通用档的 javadoc 为同一个理由选了记录而非 Map.of）。content 为 null 时如实送出，由对端拒绝并给出业务码。
    Map<String, Object> textContent = new LinkedHashMap<>();
    textContent.put("text", content);
    body.put("content", textContent);
    return body;
  }

  /**
   * 按官方算法算签名：{@code stringToSign = timestamp + "\n" + secret} 作为 HMAC-SHA256 的密钥，对<b>空字节</b>求摘要后
   * Base64 编码。
   *
   * @param timestamp 秒级时间戳
   * @param secret 机器人安全设置里的签名密钥
   * @return Base64 编码后的签名
   */
  private static String sign(String timestamp, String secret) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(
          new SecretKeySpec(
              (timestamp + "\n" + secret).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return Base64.getEncoder().encodeToString(mac.doFinal(new byte[0]));
    } catch (GeneralSecurityException e) {
      // 算法与密钥形状都是 JDK 保证的，走到这里说明运行环境异常，不是配置问题
      throw new IllegalStateException("计算飞书签名失败", e);
    }
  }

  /**
   * 按业务码判定是否真的送达，未送达则抛异常（fail-closed）。
   *
   * @param body 响应体原始字节，可能为 null（对端回了空体）
   */
  private static void verifyDelivered(byte[] body) {
    JsonNode node = parseObject(body);
    JsonNode codeNode = node.has(CODE_FIELD) ? node.get(CODE_FIELD) : node.get(LEGACY_CODE_FIELD);
    if (codeNode == null || codeNode.isNull()) {
      throw new IllegalStateException("飞书推送的响应里既没有 code 也没有 " + LEGACY_CODE_FIELD + "，无法确认送达");
    }
    if (!codeNode.isIntegralNumber()) {
      throw new IllegalStateException("飞书推送的响应里业务码不是整数，无法确认送达");
    }
    if (codeNode.asInt() != 0) {
      throw new IllegalStateException(
          "飞书推送失败：业务码 " + codeNode.asInt() + "，msg=" + node.path("msg").asText("(对端未给出 msg)"));
    }
  }

  /**
   * 把响应体解析成 JSON 对象。
   *
   * <p><b>不能只靠 try / catch 判「不是 JSON」</b>：Jackson 对空体与纯空白体返回的是 {@code MissingNode} 而非抛异常，故必须显式判
   * {@code isObject()}。这两种形态都要判失败——它们同样给不出「送达」的信号。
   *
   * @param body 响应体原始字节
   * @return 响应体的 JSON 对象形态
   */
  private static JsonNode parseObject(byte[] body) {
    if (body == null || body.length == 0) {
      throw new IllegalStateException("飞书推送的响应体为空，无法确认送达");
    }
    JsonNode node;
    try {
      node = JSON.readTree(body);
    } catch (IOException e) {
      // 刻意不挂 cause：Jackson 解析异常的 message 会带 body 片段，而 body 可能来自网关（错误页常回显带 token 的 URL）
      throw new IllegalStateException("飞书推送的响应体不是合法 JSON，无法确认送达");
    }
    if (node == null || !node.isObject()) {
      throw new IllegalStateException("飞书推送的响应体不是 JSON 对象，无法确认送达");
    }
    return node;
  }
}
