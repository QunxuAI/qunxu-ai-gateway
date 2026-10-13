package tech.qunxu.module.system.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 数据权限范围枚举。
 * <p>
 * 决定角色的数据权限：全部数据、指定部门及以下、本部门、本部门及以下、仅本人。
 */
@Getter
@AllArgsConstructor
public enum DataScopeEnum {

    /** 全部数据权限 */
    ALL(1),
    /** 指定部门及以下数据权限 */
    DEPT_CUSTOM(2),
    /** 本部门数据权限 */
    DEPT_ONLY(3),
    /** 本部门及以下数据权限 */
    DEPT_AND_CHILD(4),
    /** 仅本人数据权限 */
    SELF(5);

    /** 数据范围值 */
    private final Integer scope;

    public static DataScopeEnum valueOfScope(Integer scope) {
        return Arrays.stream(values())
                .filter(item -> item.scope.equals(scope))
                .findFirst()
                .orElse(null);
    }

}
