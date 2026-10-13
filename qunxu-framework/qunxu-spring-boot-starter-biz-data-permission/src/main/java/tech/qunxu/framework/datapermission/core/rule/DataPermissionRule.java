package tech.qunxu.framework.datapermission.core.rule;

import java.util.Map;

/**
 * 数据权限规则。
 * <p>
 * 由业务模块实现（如部门数据权限规则），返回需要过滤的表以及对应的 SQL 条件片段。
 * 例如：{@code system_users -> "dept_id IN (100, 101)"}。
 */
public interface DataPermissionRule {

    /**
     * 获取需要过滤的表与 SQL 条件片段映射
     *
     * @return Key 为表名，Value 为 SQL 条件片段（不含 WHERE 关键字），
     * 为空集合表示当前用户无需过滤（例如超级管理员）
     */
    Map<String, String> getExpressions();

}
