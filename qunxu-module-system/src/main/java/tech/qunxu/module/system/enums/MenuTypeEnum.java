package tech.qunxu.module.system.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 菜单类型枚举。
 */
@Getter
@AllArgsConstructor
public enum MenuTypeEnum {

    /** 目录：左侧导航的一级/二级分组 */
    DIR(1),
    /** 菜单：可访问的路由页面 */
    MENU(2),
    /** 按钮：页面内的操作权限 */
    BUTTON(3);

    /** 类型值 */
    private final Integer type;

    public static MenuTypeEnum valueOfType(Integer type) {
        return Arrays.stream(values())
                .filter(item -> item.type.equals(type))
                .findFirst()
                .orElse(null);
    }

}
