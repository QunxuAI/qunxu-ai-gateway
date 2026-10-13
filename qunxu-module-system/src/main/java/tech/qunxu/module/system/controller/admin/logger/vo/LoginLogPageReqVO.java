package tech.qunxu.module.system.controller.admin.logger.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;
import tech.qunxu.framework.common.pojo.PageParam;

/**
 * 登录日志分页查询请求。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class LoginLogPageReqVO extends PageParam {

    /** 用户账号 */
    private String username;

    /** 用户 IP */
    private String userIp;

    /** 状态（0 失败 1 成功） */
    private Integer status;

    /** 日志类型（1 登录 2 登出） */
    private Integer logType;

}
