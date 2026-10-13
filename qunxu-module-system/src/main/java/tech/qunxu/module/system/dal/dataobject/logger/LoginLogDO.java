package tech.qunxu.module.system.dal.dataobject.logger;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import tech.qunxu.framework.tenant.core.db.TenantBaseDO;

/**
 * 登录日志 DO。
 */
@TableName("system_login_log")
@Data
@EqualsAndHashCode(callSuper = true)
public class LoginLogDO extends TenantBaseDO {

    /** 日志类型：1 登录，2 登出 */
    private Integer logType;

    /** 用户编号 */
    private Long userId;

    /** 用户类型 */
    private Integer userType;

    /** 用户账号 */
    private String username;

    /** 日志结果：0 失败，1 成功 */
    private Integer status;

    /** 用户 IP */
    private String userIp;

    /** 浏览器 UA */
    private String userAgent;

}
