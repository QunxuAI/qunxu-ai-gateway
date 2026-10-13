package tech.qunxu.module.system.dal.dataobject.user;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import tech.qunxu.framework.tenant.core.db.TenantBaseDO;

/**
 * 用户与岗位关联 DO。
 */
@TableName("system_user_post")
@Data
@EqualsAndHashCode(callSuper = true)
public class UserPostDO extends TenantBaseDO {

    /** 用户编号 */
    private Long userId;

    /** 岗位编号 */
    private Long postId;

}
