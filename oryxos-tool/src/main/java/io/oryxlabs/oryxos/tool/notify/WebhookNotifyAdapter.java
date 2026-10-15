package io.oryxlabs.oryxos.tool.notify;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * {@link NotifyChannelAdapter} 的通用档实现：给一个地址发一次 HTTP POST，内容按 JSON 送出。
 *
 * <p><b>只做通用 webhook</b>：不逐家对接企业微信 / 飞书 / 钉钉的专用 API（签名算法、AccessToken 刷新等认证细节不在通用档做，
 * FR-003）。地址与类型相关的配置由 {@link NotifyTarget} 带进来，本类只认 {@code url} 这一个键——多认一个键就意味着某一档实现 的细节漏进了通用路径。
 *
 * <p><b>方言适配是同包的兄弟类，不是本类的分支</b>（2026-10-15 补强）：IM 群机器人不认 {@code {"content":...}}，且用 HTTP 200
 * 承载业务错误， 故那些渠道各有一档实现（{@code FeishuNotifyAdapter} 覆盖飞书 / Lark、{@code WeComNotifyAdapter} 覆盖企微）
 * 把方言与业务码判据收在类内。本类因此<b>不是唯一实现</b>，但它的行为一个字未改—— 「2xx 即送达」对通用 webhook 接收端仍是正确口径。
 *
 * <p><b>2026-10-15 修订（补强③）</b>：上一段的「不逐家对接企业微信 / 飞书 / 钉钉的专用 API」这句，主语是<b>通用档</b>——它至今仍成立
 * （本类确实不认任何方言）。容易被误读成「项目不接这几家」，故补注：<b>群机器人 webhook</b> 已由兄弟类接（飞书 / Lark、企微）， 不接的是需要凭据交换的<b>专用
 * API</b>（AccessToken 刷新、企微「应用消息」等）。原文保留以留痕。
 *
 * <p><b>超时责任在实现内，不推给注入方</b>：{@link #WebhookNotifyAdapter()} 自建带连接 + 读取超时的 {@link HttpClient}。 若改成注入
 * {@code RestClient}，「推送本身有界」就退化成「谁装配谁负责」——而装配方（第 24 节的 Bean 声明）恰恰是最容易漏掉 这个约束的地方（FR-021）。
 *
 * <p><b>{@code Redirect.NEVER} 显式写出</b>：3xx 一律按失败上抛，不跟随。跟随重定向意味着请求可能落到白名单之外的主机，
 * 而白名单校验发生在<b>调用方</b>（{@code NotifyTools} 拿 {@code target} 里的 url 去 {@code enforce}），本类看不到沙箱。
 * 逐跳复检留待有沙箱实现时评估（见 contracts/assembly-handoff.md §4.1）。
 *
 * <p><b>失败一律上抛</b>：非 2xx 与连接 / 读取异常原样抛出，不包装、不重试、不吞——失败必须走到 {@code ToolExecutor} 的审计路径上（FR-010）。
 *
 * <p><b>不打日志</b>：{@code url} 里含 token，写进任何日志都是凭证泄漏（FR-019）。异常消息同理——{@link RestClient} 抛出的异常里带的是
 * URI，这是框架行为，本类不额外添油加醋。
 */
public class WebhookNotifyAdapter implements NotifyChannelAdapter {

  /** 连接与读取各自的上限，合计即推送的整体上界（FR-021）。 */
  private static final Duration DEFAULT_PUSH_TIMEOUT = Duration.ofSeconds(10);

  /** {@link NotifyTarget#config()} 里承载地址的键。契约要求该键恒定存在（见 data-model.md §5 第 6 步）。 */
  private static final String URL_KEY = "url";

  private final RestClient restClient;

  /** 生产构造：内置 10 秒有界超时。 */
  public WebhookNotifyAdapter() {
    this(DEFAULT_PUSH_TIMEOUT);
  }

  /**
   * 供同包测试注入短超时的构造。
   *
   * <p><b>刻意不是 public / protected</b>：把超时暴露成公开构造参数，等于对外承诺「调用方可以决定推送等待多久」， 而那正是 FR-021
   * 要收口的事。包级私有让测试够得着、生产调用方够不着。
   *
   * @param pushTimeout 连接与读取各自的上限
   */
  WebhookNotifyAdapter(Duration pushTimeout) {
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

    // 一次 POST 就是全部：非 2xx 由 retrieve() 按状态码抛异常，网络异常由 RestClient 原样上抛
    restClient
        .post()
        .uri(url)
        .contentType(MediaType.APPLICATION_JSON)
        .body(new WebhookPayload(content))
        .retrieve()
        .toBodilessEntity();
  }

  /**
   * 出站报文的线上形状。
   *
   * <p>只有一个 {@code content} 字段是刻意的：加上 {@code msgtype} 之类就成了某一家 webhook 的方言。用记录而不是 {@code Map.of}
   * 是为了让 {@code content} 为空时也能如实送出（{@code Map.of} 遇 null 直接抛 NPE，报错会指向 与真实原因无关的一行）。
   *
   * @param content 要推送的正文
   */
  private record WebhookPayload(String content) {}
}
