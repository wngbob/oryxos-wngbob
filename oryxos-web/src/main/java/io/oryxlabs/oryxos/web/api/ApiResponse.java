package io.oryxlabs.oryxos.web.api;

import java.time.OffsetDateTime;

/**
 * 统一响应信封(contracts/rest-api.md):成功与错误共用一个信封
 * {@code {code, message, data, timestamp}}。
 *
 * <p>错误码用标准 HTTP 状态码:400 参数错 / 404 不存在 / 500 内部错 /
 * 503 Provider 故障 / 504 Agent 调用超时(后两者的业务异常类型随 US-1/US-5 落地)。
 */
public record ApiResponse<T>(int code, String message, T data, OffsetDateTime timestamp) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(200, "ok", data, OffsetDateTime.now());
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null, OffsetDateTime.now());
    }
}
