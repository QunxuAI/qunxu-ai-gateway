package tech.qunxu.framework.security.core;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/**
 * 安全工具类。
 * <p>
 * 提供从安全上下文获取当前登录用户、用户编号、租户编号等能力。
 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    /**
     * 获取当前登录用户
     */
    public static Optional<LoginUser> getLoginUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof LoginUser loginUser) {
            return Optional.of(loginUser);
        }
        return Optional.empty();
    }

    /**
     * 获取当前登录用户编号
     */
    public static Long getLoginUserId() {
        return getLoginUser().map(LoginUser::getId).orElse(null);
    }

    /**
     * 获取当前登录用户类型
     */
    public static Integer getLoginUserType() {
        return getLoginUser().map(LoginUser::getUserType).orElse(null);
    }

    /**
     * 获取当前登录用户所属租户编号
     */
    public static Long getTenantId() {
        return getLoginUser().map(LoginUser::getTenantId).orElse(null);
    }

    /**
     * 是否已登录
     */
    public static boolean isLogin() {
        return getLoginUser().isPresent();
    }

    /**
     * 将登录用户写入安全上下文（供安全过滤器使用）
     */
    public static void setLoginUser(LoginUser loginUser) {
        Authentication authentication = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                loginUser, loginUser.getPassword(), loginUser.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    /**
     * 清空安全上下文（登出使用）
     */
    public static void clear() {
        SecurityContextHolder.clearContext();
    }

}
