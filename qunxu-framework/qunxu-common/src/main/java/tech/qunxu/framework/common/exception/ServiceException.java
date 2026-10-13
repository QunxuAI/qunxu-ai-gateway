package tech.qunxu.framework.common.exception;

import lombok.Getter;

/**
 * 业务逻辑异常。
 * <p>
 * 当业务校验不通过时，通过 {@link ServiceExceptionUtil#exception(ErrorCode)} 抛出，
 * 全局异常处理器会将其转换为统一响应结果。
 */
@Getter
public final class ServiceException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 错误码 */
    private final Integer code;

    public ServiceException(ErrorCode errorCode) {
        super(errorCode.getMsg());
        this.code = errorCode.getCode();
    }

    public ServiceException(Integer code, String message) {
        super(message);
        this.code = code;
    }

}
