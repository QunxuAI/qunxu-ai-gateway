package tech.qunxu.framework.security.core.service;

import tech.qunxu.framework.security.core.LoginUser;
import tech.qunxu.framework.security.core.SecurityUtils;

import java.util.Arrays;
import java.util.Set;

/**
 * {@link SecurityFrameworkService} 的默认实现。
 * <p>
 * 直接基于当前登录用户携带的角色与权限进行判断，
 * 权限在登录时随 Token 缓存，变更后需重新登录（或由业务模块主动下线会话）。
 */
public class SecurityFrameworkServiceImpl implements SecurityFrameworkService {

    @Override
    public boolean hasPermission(String permission) {
        LoginUser loginUser = getLoginUserOrNull();
        if (loginUser == null || loginUser.getPermissions() == null) {
            return false;
        }
        return loginUser.getPermissions().contains(permission);
    }

    @Override
    public boolean hasAnyPermissions(String... permissions) {
        LoginUser loginUser = getLoginUserOrNull();
        if (loginUser == null || loginUser.getPermissions() == null) {
            return false;
        }
        return loginUser.getPermissions().stream().anyMatch(permission -> contains(permissions, permission));
    }

    @Override
    public boolean hasAllPermissions(String... permissions) {
        LoginUser loginUser = getLoginUserOrNull();
        if (loginUser == null || loginUser.getPermissions() == null) {
            return false;
        }
        return Arrays.stream(permissions).allMatch(loginUser.getPermissions()::contains);
    }

    @Override
    public boolean hasRole(String role) {
        LoginUser loginUser = getLoginUserOrNull();
        if (loginUser == null || loginUser.getRoleCodes() == null) {
            return false;
        }
        return loginUser.getRoleCodes().contains(role);
    }

    @Override
    public boolean hasAnyRoles(String... roles) {
        LoginUser loginUser = getLoginUserOrNull();
        if (loginUser == null || loginUser.getRoleCodes() == null) {
            return false;
        }
        return loginUser.getRoleCodes().stream().anyMatch(roleCode -> contains(roles, roleCode));
    }

    @Override
    public boolean hasAllRoles(String... roles) {
        LoginUser loginUser = getLoginUserOrNull();
        if (loginUser == null || loginUser.getRoleCodes() == null) {
            return false;
        }
        return Arrays.stream(roles).allMatch(loginUser.getRoleCodes()::contains);
    }

    private LoginUser getLoginUserOrNull() {
        return SecurityUtils.getLoginUser().orElse(null);
    }

    private static boolean contains(String[] array, String value) {
        return Arrays.asList(array).contains(value);
    }

}
