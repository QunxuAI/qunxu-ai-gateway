package tech.qunxu.framework.security.core;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 登录用户信息。
 * <p>
 * 用户登录成功后，将用户的基础信息、角色、权限等封装为该对象，
 * 序列化到 Redis 的 Token 中，并在每次请求时由安全过滤器还原到安全上下文。
 */
@Data
public class LoginUser implements UserDetails, Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 用户编号 */
    private Long id;

    /** 用户类型：对应 {@link tech.qunxu.framework.common.enums.UserTypeEnum} */
    private Integer userType;

    /** 租户编号 */
    private Long tenantId;

    /** 部门编号 */
    private Long deptId;

    /** 岗位编号集合 */
    private Set<Long> postIds = new HashSet<>();

    /** 角色编号集合 */
    private Set<Long> roleIds = new HashSet<>();

    /** 角色编码集合 */
    private Set<String> roleCodes = new HashSet<>();

    /** 权限字符串集合 */
    private Set<String> permissions = new HashSet<>();

    /** 用户名 */
    private String username;

    /** 昵称 */
    private String nickname;

    /** 头像 */
    private String avatar;

    /** 邮箱 */
    private String email;

    /** 手机号 */
    private String mobile;

    // ========== UserDetails 接口实现 ==========

    @JsonIgnore
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        if (permissions == null || permissions.isEmpty()) {
            return Collections.emptyList();
        }
        return permissions.stream().map(SimpleGrantedAuthority::new).toList();
    }

    @JsonIgnore
    @Override
    public String getPassword() {
        return "";
    }

    @JsonIgnore
    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @JsonIgnore
    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @JsonIgnore
    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @JsonIgnore
    @Override
    public boolean isEnabled() {
        return true;
    }

}
