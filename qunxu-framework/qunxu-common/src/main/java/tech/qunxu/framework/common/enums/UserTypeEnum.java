package tech.qunxu.framework.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 用户类型枚举。
 * <p>
 * 区分管理端（admin）与会员端（member）用户，用于 Token 会话管理。
 */
@Getter
@AllArgsConstructor
public enum UserTypeEnum {

    /** 管理后台用户 */
    ADMIN(1, "后台用户"),

    /** 会员用户 */
    MEMBER(2, "会员用户");

    /** 用户类型值 */
    private final Integer value;

    /** 用户类型名 */
    private final String name;

    /**
     * 根据类型值获取枚举
     */
    public static UserTypeEnum valueOfType(Integer value) {
        return Arrays.stream(values())
                .filter(item -> item.value.equals(value))
                .findFirst()
                .orElse(null);
    }

}
