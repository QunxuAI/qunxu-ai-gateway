package tech.qunxu.module.system.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 登录日志类型枚举。
 */
@Getter
@AllArgsConstructor
public enum LogTypeEnum {

    /** 登录 */
    LOGIN(1),
    /** 登出 */
    LOGOUT(2);

    /** 类型值 */
    private final Integer type;

    public static LogTypeEnum valueOfType(Integer type) {
        return Arrays.stream(values())
                .filter(item -> item.type.equals(type))
                .findFirst()
                .orElse(null);
    }

}
