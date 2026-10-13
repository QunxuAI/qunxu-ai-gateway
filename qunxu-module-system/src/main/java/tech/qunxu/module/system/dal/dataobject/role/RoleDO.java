package tech.qunxu.module.system.dal.dataobject.role;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import tech.qunxu.framework.tenant.core.db.TenantBaseDO;

/**
 * 角色 DO。
 */
@TableName("system_role")
@Data
@EqualsAndHashCode(callSuper = true)
public class RoleDO extends TenantBaseDO {

    /** 角色名称 */
    private String name;

    /** 角色编码，如 super_admin */
    private String code;

    /** 显示顺序 */
    private Integer sort;

    /** 状态：0 关闭，1 开启 */
    private Integer status;

    /** 角色类型：1 内置，2 自定义 */
    private Integer type;

    /** 数据权限范围：见 {@link tech.qunxu.module.system.enums.DataScopeEnum} */
    private Integer dataScope;

    /** 数据权限范围对应的部门编号集合（JSON 数组字符串） */
    private String dataScopeDeptIds;

    /** 备注 */
    private String remark;

}
