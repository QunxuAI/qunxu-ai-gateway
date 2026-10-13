package tech.qunxu.framework.common.validation;

/**
 * 提供整数数组的接口。
 * <p>
 * 枚举实现该接口后，可通过 {@link InEnum} 注解校验字段值是否在枚举值集合中。
 */
public interface IntArrayValuable {

    /**
     * 获取枚举的值数组
     */
    int[] array();

}
