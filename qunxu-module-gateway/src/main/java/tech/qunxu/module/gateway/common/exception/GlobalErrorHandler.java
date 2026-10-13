package tech.qunxu.module.gateway.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 网关全局错误处理器（Servlet 版，由 WebFlux 版 {@code ErrorWebExceptionHandler} 迁移而来）。
 *
 * <p>将网关域内的异常统一映射为 OpenAI 风格错误结构：{@code {"error":{"message","type","code","provider_detail?"}}}，
 * 并设置合适的 HTTP 状态码。数据面（{@code /v1/**}）与管理面（{@code /admin-api/gateway/**}）均使用该格式，
 * 管理台前端通过统一请求封装从 {@code error.message} 提取提示文案。</p>
 *
 * <p>仅接管 {@code tech.qunxu.module.gateway} 包下的控制器；其余异常（参数校验、404 等）交由
 * Qunxu 的 {@code GlobalExceptionHandler} 统一转 {@code CommonResult}。</p>
 */
@RestControllerAdvice(basePackages = "tech.qunxu.module.gateway")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GlobalErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalErrorHandler.class);

    private final ObjectMapper objectMapper;

    public GlobalErrorHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 网关业务异常：按 {@link ErrorCode} 映射状态码与错误体。 */
    @ExceptionHandler(GatewayException.class)
    public ResponseEntity<Map<String, Object>> handleGatewayException(GatewayException ex) {
        ErrorCode code = ex.getErrorCode();
        return build(code, ex.getMessage(), ex.getProviderDetail());
    }

    /** Spring 状态异常（如 404 资源不存在）。 */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatusException(ResponseStatusException ex) {
        ErrorCode code = mapStatus(ex.getStatusCode().value());
        String message = ex.getReason() != null ? ex.getReason() : code.defaultMessage();
        return build(code, message, null);
    }

    /**
     * 数据面兜底：{@code /v1}、{@code /anthropic} 上的未预期异常渲染为 OpenAI 风格 500；
     * 其余路径重新抛出，交由全局异常处理器统一转 {@code CommonResult}。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpectedException(Exception ex, HttpServletRequest request) {
        String uri = request.getRequestURI();
        boolean dataPlane = uri.startsWith("/v1") || uri.startsWith("/anthropic");
        if (dataPlane) {
            log.error("数据面未处理异常: {} {}", request.getMethod(), uri, ex);
            return build(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.defaultMessage(), null);
        }
        if (ex instanceof RuntimeException re) {
            throw re;
        }
        throw new IllegalStateException(ex);
    }

    private ResponseEntity<Map<String, Object>> build(ErrorCode code, String message, String detail) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("message", message);
        error.put("type", code.code());
        error.put("code", code.code());
        if (detail != null) {
            error.put("provider_detail", detail);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        return ResponseEntity.status(code.httpStatus()).contentType(MediaType.APPLICATION_JSON).body(body);
    }

    private ErrorCode mapStatus(int status) {
        return switch (status) {
            case 400 -> ErrorCode.BAD_REQUEST;
            case 401 -> ErrorCode.UNAUTHORIZED;
            case 403 -> ErrorCode.FORBIDDEN;
            case 404 -> ErrorCode.MODEL_NOT_FOUND;
            case 409 -> ErrorCode.CONFLICT;
            case 413 -> ErrorCode.PAYLOAD_TOO_LARGE;
            case 429 -> ErrorCode.RATE_LIMITED;
            case 502 -> ErrorCode.PROVIDER_ERROR;
            case 503 -> ErrorCode.ALL_CHANNELS_DOWN;
            case 504 -> ErrorCode.PROVIDER_TIMEOUT;
            default -> status >= 500 ? ErrorCode.INTERNAL_ERROR : ErrorCode.BAD_REQUEST;
        };
    }
}
