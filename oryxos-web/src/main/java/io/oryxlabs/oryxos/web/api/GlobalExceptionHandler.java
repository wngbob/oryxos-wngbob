package io.oryxlabs.oryxos.web.api;

import io.oryxlabs.oryxos.provider.ProviderUnavailableException;
import io.oryxlabs.oryxos.web.error.AgentTimeoutException;
import io.oryxlabs.oryxos.web.error.ProfileNotFoundException;
import io.oryxlabs.oryxos.web.error.SessionNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理：异常 → 统一信封标准 JSON 错误（contracts/rest-api.md）。
 *
 * <p>错误码用标准 HTTP 语义：400（参数非法）/ 404（资源不存在）/ 500（内部错误）/ 503（Provider 故障）/ 504（Agent 调用超时）。
 *
 * <p><b>修订注（2026-10-19，第 26 节）：503 / 504 的映射已落地。</b>骨架期本类只有 400 / 404 / 500 三档，注明「503/504 需业务异常类型，随
 * US-1/US-5 落地后追加」——本节补上 {@link ProviderUnavailableException} → 503、{@link AgentTimeoutException} →
 * 504。
 *
 * <p><b>MUST NOT 把 {@code IllegalStateException} 整类映射成 503</b>（裁决 ④）：Provider 未声明是<b>配置错</b>（
 * {@code ProviderNotFoundException}），归 500；只有真的调用了但对端失败才是 503。「不可用」与「配错了」对运维是两条完全不同的处置路径，
 * 混成同一档会让配置错误被当成临时故障而反复重试。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  /** 500 的对外话术（已定字面量）。内部异常描述只进日志。 */
  private static final String INTERNAL_ERROR_MESSAGE = "Internal server error";

  /** 参数非法：JSON 解析失败、缺必填参数、参数类型不匹配、业务参数校验失败。 */
  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MissingServletRequestParameterException.class,
    MethodArgumentTypeMismatchException.class,
    IllegalArgumentException.class
  })
  public ResponseEntity<ApiResponse<Void>> badRequest(Exception ex) {
    log.warn("bad request: {}", sanitizeForLog(ex.getMessage()));
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(ApiResponse.error(400, ex.getMessage()));
  }

  /** 资源不存在（Spring 6.1+ 路由 / 静态资源未命中）。 */
  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<ApiResponse<Void>> notFound(NoResourceFoundException ex) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(ApiResponse.error(404, "resource not found: " + ex.getResourcePath()));
  }

  /** 会话不存在。 */
  @ExceptionHandler(SessionNotFoundException.class)
  public ResponseEntity<ApiResponse<Void>> sessionNotFound(SessionNotFoundException ex) {
    log.warn("session not found: {}", sanitizeForLog(ex.getMessage()));
    return notFoundWith(ex);
  }

  /**
   * Agent 不存在（{@code AGENT.md} 缺失或装载失败）。
   *
   * <p>归 404 而不是 503（裁决 ③）：「你要的资源没有」与「底座这会儿用不了」是两件事。
   */
  @ExceptionHandler(ProfileNotFoundException.class)
  public ResponseEntity<ApiResponse<Void>> profileNotFound(ProfileNotFoundException ex) {
    log.warn("agent not found: {}", sanitizeForLog(ex.getMessage()));
    return notFoundWith(ex);
  }

  /**
   * Provider 调用失败 → 503。
   *
   * <p><b>对外话术只用厂商名重拼</b>，不用 {@code ex.getMessage()}——后者带着对端报错原文（排障用）， 属内部细节，按 FR-005 只进日志。
   */
  @ExceptionHandler(ProviderUnavailableException.class)
  public ResponseEntity<ApiResponse<Void>> providerUnavailable(ProviderUnavailableException ex) {
    log.warn("provider unavailable", ex);
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .body(ApiResponse.error(503, "provider unavailable: " + ex.providerName()));
  }

  /** Agent 调用超时 → 504。 */
  @ExceptionHandler(AgentTimeoutException.class)
  public ResponseEntity<ApiResponse<Void>> agentTimeout(AgentTimeoutException ex) {
    log.warn("agent invocation timed out");
    return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
        .body(ApiResponse.error(504, ex.getMessage()));
  }

  /** 兜底：内部错误。响应体固定话术，不泄露堆栈与敏感信息，完整异常只进日志。 */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiResponse<Void>> internalError(Exception ex) {
    log.error("internal error", ex);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(ApiResponse.error(500, INTERNAL_ERROR_MESSAGE));
  }

  /**
   * 404 档的统一信封：两个「不存在」共用同一段组装，避免两处各写一遍而漏字段。
   *
   * @param ex 已带对外话术的异常
   * @return 404 信封
   */
  private static ResponseEntity<ApiResponse<Void>> notFoundWith(RuntimeException ex) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(ApiResponse.error(404, ex.getMessage()));
  }

  /** 异常消息可能携带攻击者可控输入（如原始 JSON），入日志前替换 CR/LF 防日志伪造。 */
  private static String sanitizeForLog(String message) {
    return message == null ? "" : message.replace('\r', '_').replace('\n', '_');
  }
}
