package tech.qunxu.module.gateway.common.exception;

/**
 * 网关统一错误码。对外以 OpenAI 风格的错误结构返回（type/code/message），
 * 同时映射到合适的 HTTP 状态码。
 */
public enum ErrorCode {

    // 4xx - 客户端错误
    BAD_REQUEST(400, "invalid_request_error", "请求参数不合法"),
    UNAUTHORIZED(401, "authentication_error", "鉴权失败或缺少凭证"),
    FORBIDDEN(403, "permission_denied", "无权访问该资源"),
    MODEL_NOT_FOUND(404, "model_not_found", "请求的模型不存在或未启用"),
    RATE_LIMITED(429, "rate_limit_exceeded", "触发限流或配额超限"),
    GUARDRAIL_BLOCKED(400, "guardrail_blocked", "请求或响应被安全围栏拦截"),
    CONTENT_BLOCKED(400, "content_blocked", "内容审计未通过"),
    PAYLOAD_TOO_LARGE(413, "payload_too_large", "请求体超出大小限制"),
    NOT_FOUND(404, "not_found", "资源不存在"),
    CONFLICT(409, "conflict", "资源状态冲突或已被其它请求修改"),

    // 5xx - 网关/下游错误
    INTERNAL_ERROR(500, "internal_error", "网关内部错误"),
    ALL_CHANNELS_DOWN(503, "service_unavailable", "该模型暂无可用通道"),
    PROVIDER_ERROR(502, "provider_error", "下游供应商返回错误"),
    PROVIDER_TIMEOUT(504, "provider_timeout", "下游供应商响应超时"),
    AUTH_EXPIRED(502, "provider_auth_expired", "下游供应商凭证无效或已过期"),
    NOT_IMPLEMENTED(501, "not_implemented", "该能力或协议尚未支持");

    private final int httpStatus;
    private final String code;
    private final String defaultMessage;

    ErrorCode(int httpStatus, String code, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String code() {
        return code;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
