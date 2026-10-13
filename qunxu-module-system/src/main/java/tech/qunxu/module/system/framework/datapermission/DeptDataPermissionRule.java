package tech.qunxu.module.system.framework.datapermission;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tech.qunxu.framework.common.util.json.JsonUtils;
import tech.qunxu.framework.common.util.collection.CollectionUtils;
import tech.qunxu.framework.datapermission.core.rule.DataPermissionRule;
import tech.qunxu.framework.security.core.LoginUser;
import tech.qunxu.framework.security.core.SecurityUtils;
import tech.qunxu.module.system.dal.dataobject.role.RoleDO;
import tech.qunxu.module.system.dal.dataobject.user.UserRoleDO;
import tech.qunxu.module.system.dal.mysql.role.RoleMapper;
import tech.qunxu.module.system.dal.mysql.user.UserRoleMapper;
import tech.qunxu.module.system.enums.DataScopeEnum;
import tech.qunxu.module.system.service.dept.DeptService;
import tech.qunxu.module.system.service.permission.PermissionService;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 部门数据权限规则。
 * <p>
 * 根据登录用户角色的数据范围，对 {@code system_users} 表追加 {@code dept_id IN (...)} 过滤条件。
 * 超级管理员不参与过滤。
 */
@Component
@RequiredArgsConstructor
public class DeptDataPermissionRule implements DataPermissionRule {

    /** 需要过滤的用户表 */
    private static final String TABLE_USER = "system_users";

    private final PermissionService permissionService;
    private final UserRoleMapper userRoleMapper;
    private final RoleMapper roleMapper;
    private final DeptService deptService;

    @Override
    public Map<String, String> getExpressions() {
        Long userId = SecurityUtils.getLoginUserId();
        if (userId == null) {
            return Map.of();
        }
        // 超级管理员不参与过滤
        if (permissionService.isSuperAdmin(userId)) {
            return Map.of();
        }
        // 查询用户角色
        Set<Long> roleIds = permissionService.getRoleIdsByUserId(userId);
        if (roleIds.isEmpty()) {
            return Map.of();
        }
        List<RoleDO> roles = roleMapper.selectBatchIds(roleIds);
        // 计算数据范围
        Set<Long> deptIds = new HashSet<>();
        Long selfDeptId = SecurityUtils.getLoginUser().map(LoginUser::getDeptId).orElse(null);
        for (RoleDO role : roles) {
            DataScopeEnum scope = DataScopeEnum.valueOfScope(role.getDataScope());
            if (scope == null) {
                continue;
            }
            switch (scope) {
                case ALL -> {
                    // 任一角色为全部数据权限，则不过滤
                    return Map.of();
                }
                case DEPT_CUSTOM -> deptIds.addAll(parseDeptIds(role.getDataScopeDeptIds()));
                case DEPT_ONLY -> {
                    if (selfDeptId != null) {
                        deptIds.add(selfDeptId);
                    }
                }
                case DEPT_AND_CHILD -> {
                    if (selfDeptId != null) {
                        deptIds.add(selfDeptId);
                        deptIds.addAll(deptService.getChildDeptIds(selfDeptId));
                    }
                }
                case SELF -> {
                    // 仅本人：限制为本部门用户
                    if (selfDeptId != null) {
                        deptIds.add(selfDeptId);
                    }
                }
            }
        }
        if (deptIds.isEmpty()) {
            return Map.of();
        }
        String expression = "dept_id IN (" + deptIds.stream().sorted().map(String::valueOf)
                .collect(Collectors.joining(", ")) + ")";
        return Map.of(TABLE_USER, expression);
    }

    /**
     * 解析 JSON 字符串为部门编号集合
     */
    private Set<Long> parseDeptIds(String json) {
        if (StrUtil.isEmpty(json)) {
            return new HashSet<>();
        }
        return new HashSet<>(JsonUtils.parseArray(json, Long.class));
    }

}
