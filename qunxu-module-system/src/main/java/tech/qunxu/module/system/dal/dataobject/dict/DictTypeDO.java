package tech.qunxu.module.system.dal.dataobject.dict;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import tech.qunxu.framework.tenant.core.db.TenantBaseDO;

/**
 * 字典类型 DO。
 */
@TableName("system_dict_type")
@Data
@EqualsAndHashCode(callSuper = true)
public class DictTypeDO extends TenantBaseDO {

    /** 字典名称 */
    private String name;

    /** 字典类型：如 system_user_sex */
    private String type;

    /** 状态：0 关闭，1 开启 */
    private Integer status;

    /** 备注 */
    private String remark;

}
