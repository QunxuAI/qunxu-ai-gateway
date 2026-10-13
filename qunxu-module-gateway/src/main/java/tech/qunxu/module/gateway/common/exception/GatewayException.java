package tech.qunxu.module.gateway.common.exception;

/**
 * 网关业务异常基类。携带 {@link ErrorCode} 以便统一错误处理器映射 HTTP 状态与错误结构。
 */
public class GatewayException extends RuntimeException {

    private final ErrorCode errorCode;
    /** 下游供应商返回的原始错误信息（可选，便于排查），不会包含敏感凭证。 */
    private final String providerDetail;

    public GatewayException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage(), null, null);
    }

    public GatewayException(ErrorCode errorCode, String message) {
        this(errorCode, message, null, null);
    }

    public GatewayException(ErrorCode errorCode, String message, Throwable cause) {
        this(errorCode, message, null, cause);
    }

    public GatewayException(ErrorCode errorCode, String message, String providerDetail, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.providerDetail = providerDetail;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public String getProviderDetail() {
        return providerDetail;
    }

    public int getHttpStatus() {
        return errorCode.httpStatus();
    }
}
