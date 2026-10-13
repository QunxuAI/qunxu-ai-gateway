package tech.qunxu.module.system.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 角色类型枚举。
 */
@Getter
@AllArgsConstructor
public enum RoleTypeEnum {

    /** 内置角色：不允许修改、删除 */
    SYSTEM(1),
    /** 自定义角色 */
    CUSTOM(2);

    /** 类型值 */
    private final Integer type;

    public static RoleTypeEnum valueOfType(Integer type) {
        return Arrays.stream(values())
                .filter(item -> item.type.equals(type))
                .findFirst()
                .orElse(null);
    }

}
