package tech.qunxu.framework.security.core.service;

/**
 * 安全框架服务。
 * <p>
 * 提供权限、角色校验能力，作为 Spring Security 方法级安全 {@code @PreAuthorize}
 * 中的 SpEL Bean（名称为 {@code ss}）使用，例如：
 * <pre>{@code
 * @PreAuthorize("@ss.hasPermission('system:user:query')")
 * }</pre>
 */
public interface SecurityFrameworkService {

    /**
     * 是否拥有指定权限
     */
    boolean hasPermission(String permission);

    /**
     * 是否拥有任意一个权限
     */
    boolean hasAnyPermissions(String... permissions);

    /**
     * 是否拥有全部权限
     */
    boolean hasAllPermissions(String... permissions);

    /**
     * 是否拥有指定角色（角色编码）
     */
    boolean hasRole(String role);

    /**
     * 是否拥有任意一个角色
     */
    boolean hasAnyRoles(String... roles);

    /**
     * 是否拥有全部角色
     */
    boolean hasAllRoles(String... roles);

}
