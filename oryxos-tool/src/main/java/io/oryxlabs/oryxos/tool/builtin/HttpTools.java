package io.oryxlabs.oryxos.tool.builtin;

import io.oryxlabs.oryxos.core.tool.ToolResult;
import io.oryxlabs.oryxos.tool.sandbox.ActionType;
import io.oryxlabs.oryxos.tool.sandbox.Sandbox;
import io.oryxlabs.oryxos.tool.sandbox.SandboxAction;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 内置 HTTP 工具：{@code http_get} / {@code http_post} / {@code web_fetch}（contracts/builtin-tools.md
 * §2.8 / §2.12）。
 *
 * <p><b>三个工具共用同一个校验位与同一个 HTTP 出口</b>：{@code web_fetch} 和另外两个一样，第一行就是 {@code
 * sandbox.enforce(HTTP_REQUEST, url)}，走的是同一个 {@link HttpClient} 与同一个超时。加它没有新开一个类、没有新开一个 Bean，也没有动
 * {@code toolRegistry()} 的形参表——这正是 FR-016「加工具的成本恒定」在 HTTP 这一档的样子。
 *
 * <p><b>结果里不含请求头</b>（FR-027）：返回的只有状态码与响应体。请求头可能带着 {@code ${ENV_VAR}} 解析出来的凭证，
 * 而工具结果是模型可见的对话内容——回显请求头等于把凭证递进上下文（第 19 节 {@code NotifyTools} 已立同一条规矩）。
 *
 * <p><b>失败分两档</b>：非 2xx 是「服务器听懂了但拒绝了」，重试同一个请求不会变好 → 不可重试；连不上、超时是「这次没通」 → 可重试。分档给的是 ReAct
 * 循环判断「值不值得再调一次」的依据（FR-003）。
 *
 * <p>用 JDK 自带的 {@link HttpClient}，不引第三方 HTTP 客户端：本模块的依赖清单只该为「能力」增长，不为「省几行」增长。
 */
public class HttpTools {

  /** 冻结在 contracts/builtin-tools.md §2.8：与 {@code shell} 同款 30 秒有界等待。 */
  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

  /**
   * {@code web_fetch} 返回正文的字符上限（contracts/builtin-tools.md §2.12）。
   *
   * <p>一个正文页动辄几十万字，原样回给模型等于把这一轮上下文直接吃掉。20k 字符够放下大多数文档页的正文，也留得下模型干别的事。
   */
  private static final int MAX_TEXT_CHARS = 20000;

  /**
   * POST 的方法名。
   *
   * <p>单列成常量是因为 {@link #httpPost} 与 {@link #request} 各要写一次，两处必须同源——一处拼错就是「POST 发成了 GET」这种
   * 只有对端才看得出来的错。
   */
  private static final String METHOD_POST = "POST";

  /** GET 的方法名；与 {@link #METHOD_POST} 同理，两处同源。 */
  private static final String METHOD_GET = "GET";

  /** {@code web_fetch} 最多跟随的重定向跳数；超过就放弃，免得一个环把这次调用挂到超时。 */
  private static final int MAX_REDIRECTS = 5;

  /** 307 在 {@link HttpURLConnection} 里没有常量。 */
  private static final int HTTP_TEMPORARY_REDIRECT = 307;

  /**
   * 308 在 {@link HttpURLConnection} 里没有常量。
   *
   * <p>它不是学术上的边角：实测 {@code https://spring.io/blog/...} 就回 308，而这是 Spring 自己的文档域名——一个抓网页的工具在这儿栽了，
   * 等于在最该好用的地方不可用。
   */
  private static final int HTTP_PERMANENT_REDIRECT = 308;

  /** 重定向响应的 {@code Location} 头名。 */
  private static final String LOCATION_HEADER = "Location";

  private final Sandbox sandbox;

  private final HttpClient client;

  private final Duration timeout;

  public HttpTools(Sandbox sandbox) {
    this(sandbox, DEFAULT_TIMEOUT);
  }

  /**
   * 包级私有：单测把 30 秒压到毫秒级来验证超时路径（生产调用方够不着，不构成对外概念）。
   *
   * @param sandbox 沙箱
   * @param timeout 连接与请求的超时上限
   */
  HttpTools(Sandbox sandbox, Duration timeout) {
    this.sandbox = sandbox;
    this.timeout = timeout;
    this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
  }

  /**
   * 发起一个 HTTP GET 请求。
   *
   * @param url 完整 URL
   * @return 成功（2xx）时为「状态码 + 响应体」；非 2xx 为不可重试失败；连不上 / 超时为可重试失败
   */
  @Tool(name = "http_get", description = "发起一个 HTTP GET 请求，返回状态码与响应体")
  public ToolResult httpGet(@ToolParam(description = "完整的 http/https URL") String url) {
    return request(METHOD_GET, url, null);
  }

  /**
   * 发起一个 HTTP POST 请求。
   *
   * @param url 完整 URL
   * @param body 请求体文本
   * @return 成功（2xx）时为「状态码 + 响应体」；非 2xx 为不可重试失败；连不上 / 超时为可重试失败
   */
  @Tool(name = "http_post", description = "发起一个 HTTP POST 请求（请求体是文本），返回状态码与响应体")
  public ToolResult httpPost(
      @ToolParam(description = "完整的 http/https URL") String url,
      @ToolParam(description = "请求体文本") String body) {
    return request(METHOD_POST, url, body);
  }

  /**
   * 抓一个网页并返回正文纯文本。
   *
   * <p><b>为什么是「抓取」而不是又一个 {@code http_get}</b>：{@code http_get} 把整个 HTML（含标签、脚本、样式）原样交给模型，
   * 一个普通文档页光标签就能占掉大半个上下文。{@code web_fetch} 的差别只有一条——把标签去掉，让模型直接读正文。
   *
   * <p><b>只在 {@code Content-Type} 说是 HTML 时才去标签</b>：JSON、纯文本响应里的 {@code <} 是数据不是标签，
   * 一律去标签会静默改坏内容。判据取自响应头，不做「看正文像不像 HTML」的猜测。
   *
   * <p><b>超长截断</b>：上限 {@value #MAX_TEXT_CHARS} 字符，超出时注明原始长度——模型据此知道还有多少没看到。
   *
   * <p><b>会跟随重定向，而且每一跳都重新过白名单</b>：这是它与 {@code http_get} 的第二处差别。{@code http_get} 把状态码原样交给模型， 301/308
   * 本身就是它要回答的东西；而 {@code web_fetch} 的语义是「把那一页的正文给我」——真实世界的文档页大量回 3xx（http→https、补斜杠、
   * 换域名），不跟随就等于在最该好用的地方不可用。
   *
   * <p><b>为什么自己写循环而不用 {@code HttpClient.Redirect.NORMAL}</b>：其一，{@code Redirect} 是客户端级设置，开了就连带改掉
   * {@code http_get} / {@code http_post} 的语义（POST 撞上 301/302 还会被 JDK 改成 GET），那是动前序节已交付的行为；其二，
   * 客户端的自动跳转<b>不会再过一遍沙箱</b>——第 24 节的白名单落地后，一个白名单内的域名只要回一个 {@code Location} 指向任意主机， 校验就被绕过去了。手动循环把
   * {@code enforce} 放在每一跳的开头，这个洞从一开始就不存在。
   *
   * @param url 完整的 http/https URL
   * @return 成功（2xx）时为正文纯文本（HTML 已去标签）；非 2xx 为不可重试失败；连不上 / 超时为可重试失败
   */
  @Tool(name = "web_fetch", description = "抓取一个网页并把正文转成纯文本（HTML 去掉标签；超长会截断）")
  public ToolResult webFetch(@ToolParam(description = "完整的 http/https URL") String url) {
    String target = url;
    for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
      // 首行过白名单：校验不通过时一次请求都不发。放在循环里是因为每一跳都是一个新的目标
      sandbox.enforce(new SandboxAction(ActionType.HTTP_REQUEST, target));

      HttpRequest httpRequest;
      try {
        httpRequest = HttpRequest.newBuilder(URI.create(target)).timeout(timeout).GET().build();
      } catch (IllegalArgumentException e) {
        return ToolResult.failure("URL 非法: " + target);
      }

      HttpResponse<String> response;
      try {
        response =
            client.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      } catch (IOException e) {
        return ToolResult.retryableFailure("请求失败: " + e.getMessage());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return ToolResult.retryableFailure("请求被中断: " + target);
      }

      int code = response.statusCode();
      if (isRedirect(code)) {
        String location = response.headers().firstValue(LOCATION_HEADER).orElse("");
        if (location.isBlank()) {
          return ToolResult.failure("HTTP " + code + " 但响应里没有 " + LOCATION_HEADER + " 头");
        }
        // 相对 Location 按当前地址解析：`/docs` 这类写法在真实站点里很常见
        target = URI.create(target).resolve(location).toString();
        continue;
      }
      if (code < HttpURLConnection.HTTP_OK || code >= HttpURLConnection.HTTP_MULT_CHOICE) {
        return ToolResult.failure("HTTP " + code + "\n" + response.body());
      }

      String contentType = response.headers().firstValue("Content-Type").orElse("");
      String text =
          contentType.toLowerCase(Locale.ROOT).contains("html")
              ? WebPageText.toPlainText(response.body())
              : response.body();
      int total = text.length();
      if (total > MAX_TEXT_CHARS) {
        text =
            text.substring(0, MAX_TEXT_CHARS)
                + "\n（已截断：正文共 "
                + total
                + " 字符，只显示前 "
                + MAX_TEXT_CHARS
                + " 字符）";
      }
      return ToolResult.success(text);
    }
    return ToolResult.failure("重定向超过 " + MAX_REDIRECTS + " 次，放弃: " + url);
  }

  /** 需要跟随的状态码：301 / 302 / 303 / 307 / 308。 */
  private static boolean isRedirect(int code) {
    return code == HttpURLConnection.HTTP_MOVED_PERM
        || code == HttpURLConnection.HTTP_MOVED_TEMP
        || code == HttpURLConnection.HTTP_SEE_OTHER
        || code == HTTP_TEMPORARY_REDIRECT
        || code == HTTP_PERMANENT_REDIRECT;
  }

  private ToolResult request(String method, String url, String body) {
    // 首行过白名单：校验不通过时一次请求都不发
    sandbox.enforce(new SandboxAction(ActionType.HTTP_REQUEST, url));

    HttpRequest httpRequest;
    try {
      HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(timeout);
      if (METHOD_POST.equals(method)) {
        builder.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
      } else {
        builder.GET();
      }
      httpRequest = builder.build();
    } catch (IllegalArgumentException e) {
      return ToolResult.failure("URL 非法: " + url);
    }

    try {
      HttpResponse<String> response =
          client.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      int code = response.statusCode();
      String text = "HTTP " + code + "\n" + response.body();
      boolean ok = code >= HttpURLConnection.HTTP_OK && code < HttpURLConnection.HTTP_MULT_CHOICE;
      return ok ? ToolResult.success(text) : ToolResult.failure(text);
    } catch (IOException e) {
      return ToolResult.retryableFailure("请求失败: " + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return ToolResult.retryableFailure("请求被中断: " + url);
    }
  }
}
