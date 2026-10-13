package tech.qunxu.framework.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 通用状态枚举。
 * <p>
 * 所有启用/禁用类字段统一使用该枚举：1 开启，0 关闭。
 */
@Getter
@AllArgsConstructor
public enum CommonStatusEnum {

    /** 开启 */
    ENABLE(1, "开启"),

    /** 关闭 */
    DISABLE(0, "关闭");

    /** 状态值 */
    private final Integer status;

    /** 状态名 */
    private final String name;

    /**
     * 判断状态是否为开启
     */
    public static boolean isEnable(Integer status) {
        return ENABLE.status.equals(status);
    }

    /**
     * 判断状态是否为关闭
     */
    public static boolean isDisable(Integer status) {
        return DISABLE.status.equals(status);
    }

    /**
     * 根据状态值获取枚举
     */
    public static CommonStatusEnum valueOfStatus(Integer status) {
        return Arrays.stream(values())
                .filter(item -> item.status.equals(status))
                .findFirst()
                .orElse(null);
    }

}
