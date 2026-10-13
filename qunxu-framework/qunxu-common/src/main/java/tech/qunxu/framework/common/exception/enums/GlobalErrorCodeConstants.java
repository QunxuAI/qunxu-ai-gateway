package tech.qunxu.framework.common.exception.enums;

import tech.qunxu.framework.common.exception.ErrorCode;

/**
 * 全局错误码枚举。
 * <p>
 * 0 ~ 999 为框架与 HTTP 语义对应错误码；
 * 业务模块自定义错误码统一从 1_000_000_000 开始。
 */
public enum GlobalErrorCodeConstants implements ErrorCode {

    /** 成功 */
    SUCCESS(0, "成功"),
    /** 系统内部异常 */
    INTERNAL_SERVER_ERROR(500, "系统异常，请联系管理员"),
    /** 参数校验失败 */
    BAD_REQUEST(400, "请求参数不正确"),
    /** 未登录 */
    UNAUTHORIZED(401, "账号未登录"),
    /** 无权限 */
    FORBIDDEN(403, "没有该操作权限"),
    /** 数据不存在 */
    NOT_FOUND(404, "请求资源不存在"),
    /** 请求频繁 */
    TOO_MANY_REQUESTS(429, "请求过于频繁，请稍后重试"),
    ;

    private final int code;
    private final String msg;

    GlobalErrorCodeConstants(int code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    @Override
    public int getCode() {
        return code;
    }

    @Override
    public String getMsg() {
        return msg;
    }

}
