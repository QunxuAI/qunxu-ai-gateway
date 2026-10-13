package tech.qunxu.framework.common.exception.util;

import tech.qunxu.framework.common.exception.ErrorCode;
import tech.qunxu.framework.common.exception.ServiceException;

/**
 * 业务异常工具类。
 * <p>
 * 简化业务代码中抛异常的写法：{@code throw exception(ErrorCode.X);}
 */
public final class ServiceExceptionUtil {

    private ServiceExceptionUtil() {
    }

    /**
     * 根据错误码抛出业务异常
     */
    public static ServiceException exception(ErrorCode errorCode) {
        return new ServiceException(errorCode);
    }

    /**
     * 根据错误码与占位参数抛出业务异常，支持消息中的 {} 占位符替换
     */
    public static ServiceException exception(ErrorCode errorCode, Object... params) {
        String message = formatMessage(errorCode.getMsg(), params);
        return new ServiceException(errorCode.getCode(), message);
    }

    /**
     * 直接指定错误码与消息抛出业务异常
     */
    public static ServiceException exception(Integer code, String message) {
        return new ServiceException(code, message);
    }

    /**
     * 简单占位符格式化：将消息中的 {} 依次替换为参数
     */
    private static String formatMessage(String pattern, Object... params) {
        if (params == null || params.length == 0) {
            return pattern;
        }
        StringBuilder sb = new StringBuilder();
        int index = 0;
        int cursor = 0;
        String placeholder = "{}";
        int start;
        while (cursor < pattern.length() && index < params.length
                && (start = pattern.indexOf(placeholder, cursor)) != -1) {
            sb.append(pattern, cursor, start).append(params[index++]);
            cursor = start + placeholder.length();
        }
        sb.append(pattern.substring(cursor));
        return sb.toString();
    }

}
