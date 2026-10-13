package tech.qunxu.module.system.dal.dataobject.post;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import tech.qunxu.framework.tenant.core.db.TenantBaseDO;

/**
 * 岗位 DO。
 */
@TableName("system_post")
@Data
@EqualsAndHashCode(callSuper = true)
public class PostDO extends TenantBaseDO {

    /** 岗位名称 */
    private String name;

    /** 岗位编码 */
    private String code;

    /** 显示顺序 */
    private Integer sort;

    /** 状态：0 关闭，1 开启 */
    private Integer status;

    /** 备注 */
    private String remark;

}
