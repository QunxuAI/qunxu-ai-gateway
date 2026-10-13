package tech.qunxu.module.system.controller.admin.role.vo;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Set;

/**
 * 分配角色菜单请求。
 */
@Data
public class RoleAssignMenuReqVO {

    @NotNull(message = "角色编号不能为空")
    private Long roleId;

    /** 菜单编号集合 */
    private Set<Long> menuIds;

}
