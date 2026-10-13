package tech.qunxu.module.system.dal.dataobject.dept;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import tech.qunxu.framework.tenant.core.db.TenantBaseDO;

/**
 * 部门 DO。
 */
@TableName("system_dept")
@Data
@EqualsAndHashCode(callSuper = true)
public class DeptDO extends TenantBaseDO {

    /** 部门名称 */
    private String name;

    /** 父部门编号，根节点为 0 */
    private Long parentId;

    /** 显示顺序 */
    private Integer sort;

    /** 负责人编号 */
    private Long leaderUserId;

    /** 联系电话 */
    private String phone;

    /** 邮箱 */
    private String email;

    /** 状态：0 关闭，1 开启 */
    private Integer status;

}
