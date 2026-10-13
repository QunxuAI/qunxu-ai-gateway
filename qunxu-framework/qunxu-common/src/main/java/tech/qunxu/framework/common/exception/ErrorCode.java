package tech.qunxu.framework.common.exception;

/**
 * 错误码接口。
 * <p>
 * 所有业务模块的错误码实现该接口，配合 {@link ServiceException} 抛出业务异常，
 * 由全局异常处理器统一转换为响应结果返回给前端。
 */
public interface ErrorCode {

    /** 错误码编号 */
    int getCode();

    /** 错误提示信息 */
    String getMsg();

}
