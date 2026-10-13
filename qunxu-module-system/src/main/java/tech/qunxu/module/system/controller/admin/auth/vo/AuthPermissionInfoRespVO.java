package tech.qunxu.module.system.controller.admin.auth.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.io.Serializable;
import java.util.Set;

/**
 * 用户权限信息响应。
 */
@Data
@AllArgsConstructor
public class AuthPermissionInfoRespVO implements Serializable {

    /** 用户类型 */
    private Integer userType;

    /** 角色编码集合 */
    private Set<String> roles;

    /** 权限字符串集合 */
    private Set<String> permissions;

}
