package tech.qunxu.module.system.dal.dataobject.role;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import tech.qunxu.framework.tenant.core.db.TenantBaseDO;

/**
 * 角色与菜单关联 DO。
 */
@TableName("system_role_menu")
@Data
@EqualsAndHashCode(callSuper = true)
public class RoleMenuDO extends TenantBaseDO {

    /** 角色编号 */
    private Long roleId;

    /** 菜单编号 */
    private Long menuId;

}
