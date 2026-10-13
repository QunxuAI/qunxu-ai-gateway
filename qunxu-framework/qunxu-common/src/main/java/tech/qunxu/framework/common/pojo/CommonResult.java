package tech.qunxu.framework.common.pojo;

import lombok.Data;
import tech.qunxu.framework.common.exception.ErrorCode;
import tech.qunxu.framework.common.exception.enums.GlobalErrorCodeConstants;

import java.io.Serial;
import java.io.Serializable;

/**
 * 通用响应结果。
 * <p>
 * 所有接口统一返回该结构：{@code code} 错误码、{@code data} 数据、{@code msg} 提示信息。
 */
@Data
public class CommonResult<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 错误码 */
    private Integer code;

    /** 返回数据 */
    private T data;

    /** 错误提示 */
    private String msg;

    /**
     * 构建成功结果
     */
    public static <T> CommonResult<T> success(T data) {
        CommonResult<T> result = new CommonResult<>();
        result.code = GlobalErrorCodeConstants.SUCCESS.getCode();
        result.data = data;
        result.msg = "";
        return result;
    }

    public static <T> CommonResult<T> success() {
        return success(null);
    }

    /**
     * 构建失败结果
     */
    public static <T> CommonResult<T> error(Integer code, String msg) {
        CommonResult<T> result = new CommonResult<>();
        result.code = code;
        result.msg = msg;
        return result;
    }

    public static <T> CommonResult<T> error(ErrorCode errorCode) {
        return error(errorCode.getCode(), errorCode.getMsg());
    }

    /**
     * 判断结果是否成功
     */
    public boolean isSuccess() {
        return code != null && code == GlobalErrorCodeConstants.SUCCESS.getCode();
    }

}
