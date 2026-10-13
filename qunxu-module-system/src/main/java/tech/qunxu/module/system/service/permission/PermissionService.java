package tech.qunxu.module.system.service.permission;

import com.baomidou.dynamic.datasource.annotation.DS;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tech.qunxu.framework.common.util.collection.CollectionUtils;
import tech.qunxu.module.system.dal.dataobject.menu.MenuDO;
import tech.qunxu.module.system.dal.dataobject.role.RoleDO;
import tech.qunxu.module.system.dal.dataobject.role.RoleMenuDO;
import tech.qunxu.module.system.dal.dataobject.user.UserRoleDO;
import tech.qunxu.module.system.dal.mysql.role.RoleMenuMapper;
import tech.qunxu.module.system.dal.mysql.user.UserRoleMapper;
import tech.qunxu.module.system.service.menu.MenuService;
import tech.qunxu.module.system.service.oauth2.OAuth2TokenService;
import tech.qunxu.module.system.service.role.RoleService;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 权限服务：用户-角色、角色-菜单的分配与查询。
 */
@Service
@RequiredArgsConstructor
public class PermissionService {

    /** 超级管理员角色编码 */
    public static final String SUPER_ADMIN_ROLE_CODE = "super_admin";

    private final UserRoleMapper userRoleMapper;
    private final RoleMenuMapper roleMenuMapper;
    private final RoleService roleService;
    private final MenuService menuService;
    private final OAuth2TokenService oauth2TokenService;

    // ========== 用户-角色 ==========

    /**
     * 获取用户拥有的角色编号集合
     */
    @DS("slave")
    public Set<Long> getRoleIdsByUserId(Long userId) {
        List<UserRoleDO> list = userRoleMapper.selectList(
                new LambdaQueryWrapper<UserRoleDO>().eq(UserRoleDO::getUserId, userId));
        return CollectionUtils.convertSet(list, UserRoleDO::getRoleId);
    }

    /**
     * 获取用户拥有的角色列表
     */
    @DS("slave")
    public List<RoleDO> getRoleListByUserId(Long userId) {
        return roleService.getRoleListByRoleIds(getRoleIdsByUserId(userId));
    }

    /**
     * 获取用户拥有的角色编码集合
     */
    @DS("slave")
    public Set<String> getRoleCodesByUserId(Long userId) {
        return CollectionUtils.convertSet(getRoleListByUserId(userId), RoleDO::getCode);
    }

    /**
     * 是否为超级管理员
     */
    @DS("slave")
    public boolean isSuperAdmin(Long userId) {
        return getRoleCodesByUserId(userId).contains(SUPER_ADMIN_ROLE_CODE);
    }

    /**
     * 分配用户角色（覆盖式）
     */
    public void assignUserRole(Long userId, Set<Long> roleIds) {
        // 删除旧的用户-角色关联
        userRoleMapper.delete(new LambdaQueryWrapper<UserRoleDO>().eq(UserRoleDO::getUserId, userId));
        // 创建新的用户-角色关联
        if (roleIds != null) {
            for (Long roleId : roleIds) {
                UserRoleDO userRole = new UserRoleDO();
                userRole.setUserId(userId);
                userRole.setRoleId(roleId);
                userRoleMapper.insert(userRole);
            }
        }
        // 权限变更，下线该用户的会话
        oauth2TokenService.removeTokensByUserId(userId);
    }

    // ========== 角色-菜单 ==========

    /**
     * 获取角色拥有的菜单编号集合
     */
    @DS("slave")
    public Set<Long> getMenuIdsByRoleId(Long roleId) {
        List<RoleMenuDO> list = roleMenuMapper.selectList(
                new LambdaQueryWrapper<RoleMenuDO>().eq(RoleMenuDO::getRoleId, roleId));
        return CollectionUtils.convertSet(list, RoleMenuDO::getMenuId);
    }

    /**
     * 根据角色编号集合获取菜单编号集合
     */
    @DS("slave")
    public Set<Long> getMenuIdsByRoleIds(Collection<Long> roleIds) {
        if (CollectionUtils.isEmpty(roleIds)) {
            return Collections.emptySet();
        }
        List<RoleMenuDO> list = roleMenuMapper.selectList(
                new LambdaQueryWrapper<RoleMenuDO>().in(RoleMenuDO::getRoleId, roleIds));
        return CollectionUtils.convertSet(list, RoleMenuDO::getMenuId);
    }

    /**
     * 分配角色菜单（覆盖式）
     */
    public void assignRoleMenu(Long roleId, Set<Long> menuIds) {
        // 删除旧的角色-菜单关联
        roleMenuMapper.delete(new LambdaQueryWrapper<RoleMenuDO>().eq(RoleMenuDO::getRoleId, roleId));
        // 创建新的角色-菜单关联
        if (menuIds != null) {
            for (Long menuId : menuIds) {
                RoleMenuDO roleMenu = new RoleMenuDO();
                roleMenu.setRoleId(roleId);
                roleMenu.setMenuId(menuId);
                roleMenuMapper.insert(roleMenu);
            }
        }
        // 权限变更，下线拥有该角色的用户会话
        List<UserRoleDO> userRoles = userRoleMapper.selectList(
                new LambdaQueryWrapper<UserRoleDO>().eq(UserRoleDO::getRoleId, roleId));
        CollectionUtils.convertList(userRoles, UserRoleDO::getUserId)
                .forEach(oauth2TokenService::removeTokensByUserId);
    }

    // ========== 权限查询 ==========

    /**
     * 获取用户拥有的权限字符串集合
     */
    @DS("slave")
    public Set<String> getMenuPermissionListByUserId(Long userId) {
        // 超级管理员拥有全部权限
        if (isSuperAdmin(userId)) {
            return menuService.getMenuList().stream()
                    .filter(menu -> StrUtil.isNotEmpty(menu.getPermission()))
                    .map(MenuDO::getPermission)
                    .collect(java.util.stream.Collectors.toSet());
        }
        Set<Long> roleIds = getRoleIdsByUserId(userId);
        if (roleIds.isEmpty()) {
            return Collections.emptySet();
        }
        Set<Long> menuIds = getMenuIdsByRoleIds(roleIds);
        if (menuIds.isEmpty()) {
            return Collections.emptySet();
        }
        return menuService.getMenuListByMenuIds(menuIds).stream()
                .filter(menu -> StrUtil.isNotEmpty(menu.getPermission()))
                .map(MenuDO::getPermission)
                .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * 获取用户拥有的菜单列表（用于前端路由）
     */
    @DS("slave")
    public List<MenuDO> getMenuListByUserId(Long userId) {
        // 超级管理员拥有全部菜单
        if (isSuperAdmin(userId)) {
            return menuService.getMenuList();
        }
        Set<Long> roleIds = getRoleIdsByUserId(userId);
        if (roleIds.isEmpty()) {
            return Collections.emptyList();
        }
        return menuService.getMenuListByRoleIds(roleIds);
    }

}
