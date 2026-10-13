package tech.qunxu.module.system.controller.admin.user.vo;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Set;

/**
 * 分配用户角色请求。
 */
@Data
public class UserAssignRoleReqVO {

    @NotNull(message = "用户编号不能为空")
    private Long userId;

    /** 角色编号集合 */
    private Set<Long> roleIds;

}
