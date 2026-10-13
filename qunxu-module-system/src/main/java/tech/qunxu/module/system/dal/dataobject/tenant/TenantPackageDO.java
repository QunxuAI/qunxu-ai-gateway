package tech.qunxu.module.system.dal.dataobject.tenant;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import tech.qunxu.framework.mybatis.core.dataobject.BaseDO;

/**
 * 租户套餐 DO。
 * <p>
 * 全局表，不做租户隔离。
 */
@TableName("system_tenant_package")
@Data
@EqualsAndHashCode(callSuper = true)
public class TenantPackageDO extends BaseDO {

    /** 套餐名 */
    private String name;

    /** 状态：0 关闭，1 开启 */
    private Integer status;

    /** 关联的菜单编号集合（JSON 数组字符串） */
    private String menuIds;

    /** 备注 */
    private String remark;

}
