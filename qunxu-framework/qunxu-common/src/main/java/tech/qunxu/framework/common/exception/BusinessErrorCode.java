package tech.qunxu.framework.common.exception;

/**
 * 业务错误码实现。
 * <p>
 * 业务模块通过 {@code new BusinessErrorCode(code, msg)} 定义错误码常量，
 * 配合 {@link ServiceException} 与全局异常处理器使用。
 *
 * @param code 错误码编号
 * @param msg  错误提示信息
 */
public record BusinessErrorCode(int code, String msg) implements ErrorCode {

    @Override
    public int getCode() {
        return code;
    }

    @Override
    public String getMsg() {
        return msg;
    }

}
