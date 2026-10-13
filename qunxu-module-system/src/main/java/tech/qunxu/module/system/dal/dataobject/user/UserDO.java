package tech.qunxu.module.system.dal.dataobject.user;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import tech.qunxu.framework.tenant.core.db.TenantBaseDO;

/**
 * 用户 DO。
 */
@TableName("system_users")
@Data
@EqualsAndHashCode(callSuper = true)
public class UserDO extends TenantBaseDO {

    /** 用户账号 */
    private String username;

    /** 加密后的密码 */
    private String password;

    /** 密码盐值 */
    private String salt;

    /** 用户昵称 */
    private String nickname;

    /** 手机号 */
    private String mobile;

    /** 邮箱 */
    private String email;

    /** 头像地址 */
    private String avatar;

    /** 状态：0 关闭，1 开启 */
    private Integer status;

    /** 所属部门编号 */
    private Long deptId;

    /** 关联的岗位编号集合（JSON 数组字符串） */
    private String postIds;

    /** 备注 */
    private String remark;

}
