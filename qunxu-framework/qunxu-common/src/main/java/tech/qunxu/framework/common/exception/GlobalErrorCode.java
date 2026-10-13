package tech.qunxu.framework.common.exception;

import tech.qunxu.framework.common.exception.enums.GlobalErrorCodeConstants;

/**
 * 全局错误码常量。
 * <p>
 * 定义框架层面的通用错误码，业务模块错误码从 1_000_000_000 开始分配，
 * 避免与全局错误码冲突。
 *
 * @see GlobalErrorCodeConstants
 */
public final class GlobalErrorCode {

    private GlobalErrorCode() {
    }

    /** 系统未知异常 */
    public static final ErrorCode INTERNAL_SERVER_ERROR = GlobalErrorCodeConstants.INTERNAL_SERVER_ERROR;

    /** 参数校验失败 */
    public static final ErrorCode BAD_REQUEST = GlobalErrorCodeConstants.BAD_REQUEST;

    /** 未登录 / Token 失效 */
    public static final ErrorCode UNAUTHORIZED = GlobalErrorCodeConstants.UNAUTHORIZED;

    /** 无权限访问 */
    public static final ErrorCode FORBIDDEN = GlobalErrorCodeConstants.FORBIDDEN;

    /** 数据不存在 */
    public static final ErrorCode NOT_FOUND = GlobalErrorCodeConstants.NOT_FOUND;

    /** 请求过于频繁 */
    public static final ErrorCode TOO_MANY_REQUESTS = GlobalErrorCodeConstants.TOO_MANY_REQUESTS;

}
