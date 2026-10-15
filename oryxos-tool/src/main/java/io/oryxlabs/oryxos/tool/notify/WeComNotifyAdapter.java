package io.oryxlabs.oryxos.tool.notify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 企业微信群机器人方言的推送实现。
 *
 * <p><b>为什么需要它，以及它与 {@link WebhookNotifyAdapter} 的唯一实质差异</b>：企微群机器人<b>用 HTTP 200 承载业务错误</b>
 * ——报文写错时回的是 {@code HTTP 200} + {@code {"errcode":40008,"errmsg":"invalid message
 * type"}}，群里一条消息都没有（2026-10-14 实测，见 CLAUDE.md 与 manual-test-guide.md）。通用档只判状态码， 会把这次投递判成成功，于是
 * {@code notify} 返回「已推送」而实际没送到，失败连 {@code tool_invocations} 都进不去。本类读响应体、按业务码判成败，把这种
 * 「假成功」变回可落审计的失败——这是它存在的全部理由。
 *
 * <p><b>报文形状</b>：{@code {"msgtype":"text","text":{"content":"..."}}}。这个方言知识只属于本类，MUST NOT
 * 回流到接口或通用档： 通用档的 {@code {"content":...}} 对通用 webhook 接收端是正确的，改它才是错。
 *
 * <p><b>与飞书档只差一个字段名，这是最易抄错的一处</b>：企微是 {@code msgtype}（<b>无下划线</b>）且正文在 {@code text.content}； 飞书是
 * {@code msg_type}（<b>有下划线</b>）且正文在 {@code content.text}。两处形状是镜像的，不是同一份。
 *
 * <p><b>送达判据是 fail-closed 的</b>：成功当且仅当响应体是 JSON 对象、且 {@code errcode} 存在且为整数且等于 0。其余一切情形——非 JSON、空
 * body、没有 {@code errcode}、字段不是整数——一律判失败。 理由：一个 2xx
 * 却给不出任何明确成功信号的响应，正是「假成功」最可能藏身之处；判失败会让它<b>吵</b>（审计里有记录），判成功会让它<b>静默</b>，本项目一贯选吵不选静默。
 *
 * <p><b>判据 MUST 用 {@code has(...)} + {@code isIntegralNumber()}，MUST NOT 写成 {@code
 * path("errcode").asInt() != 0}</b>：Jackson 的 {@code path(...).asInt()} 在<b>字段缺失时也返回
 * 0</b>，那个写法会把「缺失」判成成功，正好与 fail-closed 相反——等于用另一种形态把本类要消灭的缺陷重新引入。
 *
 * <p><b>只认 {@code errcode} 一个字段，没有飞书那个 {@code StatusCode} 兜底</b>：飞书档退看 {@code StatusCode} 是因为官方把它列为
 * 「兼容存量历史逻辑的冗余字段」，历史形态可能只回它；企微没有这个历史包袱，多认一个字段就是多一处要维护的口径。
 *
 * <p><b>没有签名那一层，故不注入 {@link java.time.Clock}</b>：企微群机器人只有 URL 里的 {@code key}（可选 IP 白名单）， 不像飞书 /
 * 钉钉那样有签名算法。凭证就是 URL 本身——这也是本类<b>不打日志、异常消息不含 url</b> 的原因。
 *
 * <p><b>响应体读成 {@code byte[]} 而不是 {@code String}</b>：{@code StringHttpMessageConverter} 在响应头没带
 * charset 时可能按 ISO-8859-1 解码，而 {@code errmsg} 是要放进异常消息的中文。{@code byte[]} 交给 Jackson 自行探测编码更稳。
 *
 * <p><b>不新增自定义异常类型</b>（契约 §2.3）：失败一律抛 {@link IllegalStateException}——请求本身合法、是对端拒绝，与「参数不对」的 {@link
 * IllegalArgumentException} 不是一回事。异常消息含 {@code errcode} 与 {@code errmsg}，<b>MUST NOT 含 {@code
 * url}</b>（含 key）。
 *
 * <p><b>非 JSON 响应体刻意不挂 cause</b>：Jackson 解析异常的 message 会带 body 片段，而 body 可能来自中间设备（网关的错误页常把请求 URL
 * 原样回显，那里面有 key）。审计表只写 {@code e.getMessage()}，本类的 message 是一句固定文案，不含 body 内容。
 *
 * <p><b>超时与 {@code Redirect.NEVER} 与其余两档同口径</b>：自建带连接 + 读取超时的 {@link HttpClient}
 * （超时收口在实现内，不推给注入方），3xx 一律按失败上抛、不跟随——白名单校验发生在调用方（{@code NotifyTools} 拿 {@code url} 去 {@code
 * enforce}），本类看不到沙箱，跟随重定向意味着请求可能落到白名单之外的主机。
 *
 * <p><b>读 body 改变了超时语义</b>：通用档用 {@code toBodilessEntity()} 不读 body，其读取上限只量到「响应头到达」； 本类必须读 body
 * 才能看到业务码，于是对端「给了响应头却拖着不给 body」也会被上限兜住。两条路径抛出的异常类型不同——响应头超时是 {@code
 * ResourceAccessException}，响应体读取超时被 Spring 在「提取响应体」那一步包成 {@code RestClientException}。两者都是 {@code
 * RestClientException} 的实例，都会原样上抛到 {@code ToolExecutor} 的失败审计路径。
 *
 * <p><b>与飞书档的重复代码刻意不抽公共基类（2026-10-15 裁决）</b>：{@code send()} 骨架、{@code parseObject()} 与构造器约 60 行同形。
 * 不抽的三条理由：① 抽 = 改动已交付且被 19 条测试钉死的 {@link FeishuNotifyAdapter}，属软门禁②事件，收益小于风险；② 项目既有裁决同向 （{@code
 * ProfileLoader} / {@code McpServerConfigLoader} / {@code JpaNotifyChannelRegistry} 三处 {@code
 * envLookup} 刻意不合并）；③ 目前只有两档方言。<b>第三次出现（钉钉）才是抽的时机</b>——届时抽的是 {@code send()} 模板方法与 {@code
 * parseObject()}，各档只留 {@code payload()} 与「业务码字段名 + 错误文案」。
 *
 * @see NotifyChannelAdapter
 */
public class WeComNotifyAdapter implements NotifyChannelAdapter {

  /** 连接与读取各自的上限，合计即推送的整体上界。 */
  private static final Duration DEFAULT_PUSH_TIMEOUT = Duration.ofSeconds(10);

  /** {@link NotifyTarget#config()} 里承载地址的键。契约要求该键恒定存在（见 data-model.md §5 第 6 步）。 */
  private static final String URL_KEY = "url";

  /** 只用来读响应体，不做序列化；{@code readTree} 是线程安全的。 */
  private static final ObjectMapper JSON = new ObjectMapper();

  /** 企微文本消息的 {@code msgtype} 取值。 */
  private static final String TEXT_MSG_TYPE = "text";

  /** 企微的业务码字段。<b>注意不是飞书的 {@code code}</b>。 */
  private static final String CODE_FIELD = "errcode";

  /** 企微的业务码描述字段。 */
  private static final String MESSAGE_FIELD = "errmsg";

  private final RestClient restClient;

  /** 生产构造：内置 10 秒有界超时。 */
  public WeComNotifyAdapter() {
    this(DEFAULT_PUSH_TIMEOUT);
  }

  /**
   * 供同包测试注入短超时的构造。
   *
   * <p><b>刻意不是 public / protected</b>：把超时暴露成公开构造参数，等于对外承诺「调用方可以决定推送等待多久」，而那正是「等待有界」要收口的事。
   *
   * @param pushTimeout 连接与读取各自的上限
   */
  WeComNotifyAdapter(Duration pushTimeout) {
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
            .body(payload(content))
            .retrieve()
            .toEntity(byte[].class)
            .getBody();
    verifyDelivered(body);
  }

  /**
   * 组装请求体。
   *
   * <p>用 {@link LinkedHashMap} 而不是 {@code Map.of}：{@code Map.of} 遇 null 直接抛 NPE，报错会指向与真实原因无关的一行。
   * {@code content} 为 null 时如实送出，由对端拒绝并给出业务码。
   *
   * @param content 要推送的正文
   * @return 待序列化的请求体
   */
  private static Map<String, Object> payload(String content) {
    Map<String, Object> body = new LinkedHashMap<>();
    // 无下划线的 msgtype —— 写成飞书那个 msg_type 企微会回 errcode 非 0，正是本类要消灭的那种失败
    body.put("msgtype", TEXT_MSG_TYPE);
    Map<String, Object> textContent = new LinkedHashMap<>();
    textContent.put("content", content);
    body.put("text", textContent);
    return body;
  }

  /**
   * 按业务码判定是否真的送达，未送达则抛异常（fail-closed）。
   *
   * @param body 响应体原始字节，可能为 null（对端回了空体）
   */
  private static void verifyDelivered(byte[] body) {
    JsonNode node = parseObject(body);
    if (!node.has(CODE_FIELD)) {
      throw new IllegalStateException("企业微信推送的响应里没有 " + CODE_FIELD + "，无法确认送达");
    }
    JsonNode codeNode = node.get(CODE_FIELD);
    if (codeNode.isNull() || !codeNode.isIntegralNumber()) {
      throw new IllegalStateException("企业微信推送的响应里业务码不是整数，无法确认送达");
    }
    if (codeNode.asInt() != 0) {
      throw new IllegalStateException(
          "企业微信推送失败：业务码 "
              + codeNode.asInt()
              + "，"
              + MESSAGE_FIELD
              + "="
              + node.path(MESSAGE_FIELD).asText("(对端未给出 " + MESSAGE_FIELD + ")"));
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
      throw new IllegalStateException("企业微信推送的响应体为空，无法确认送达");
    }
    JsonNode node;
    try {
      node = JSON.readTree(body);
    } catch (IOException e) {
      // 刻意不挂 cause：Jackson 解析异常的 message 会带 body 片段，而 body 可能来自网关（错误页常回显带 key 的 URL）
      throw new IllegalStateException("企业微信推送的响应体不是合法 JSON，无法确认送达");
    }
    if (node == null || !node.isObject()) {
      throw new IllegalStateException("企业微信推送的响应体不是 JSON 对象，无法确认送达");
    }
    return node;
  }
}
