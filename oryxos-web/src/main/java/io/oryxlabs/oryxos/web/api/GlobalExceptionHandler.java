package io.oryxlabs.oryxos.web.api;

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
 * 全局异常处理:异常 → 统一信封标准 JSON 错误(contracts/rest-api.md)。
 *
 * <p>覆盖 400(参数非法)/ 404(资源不存在)/ 500(内部错误,响应体不泄露堆栈与密钥,
 * 完整堆栈只进日志)。503(Provider 故障)与 504(Agent 调用超时)需要业务异常类型,
 * 随 US-1/US-5 落地后在此追加映射。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 参数非法:JSON 解析失败、缺必填参数、参数类型不匹配、业务参数校验失败。 */
    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        MissingServletRequestParameterException.class,
        MethodArgumentTypeMismatchException.class,
        IllegalArgumentException.class
    })
    public ResponseEntity<ApiResponse<Void>> badRequest(Exception ex) {
        log.warn("bad request: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(400, ex.getMessage()));
    }

    /** 资源不存在(Spring 6.1+ 路由/静态资源未命中)。 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> notFound(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(404, "resource not found: " + ex.getResourcePath()));
    }

    /** 兜底:内部错误。响应体不泄露堆栈与敏感信息,完整堆栈只进日志。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> internalError(Exception ex) {
        log.error("internal error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(500, "internal error"));
    }
}
