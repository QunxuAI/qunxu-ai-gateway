package tech.qunxu.module.system.controller.admin.logger.vo;

import lombok.Data;
import tech.qunxu.module.system.dal.dataobject.logger.LoginLogDO;

import java.time.LocalDateTime;

/**
 * 登录日志响应。
 */
@Data
public class LoginLogRespVO {

    private Long id;

    private Integer logType;

    private Long userId;

    private Integer userType;

    private String username;

    private Integer status;

    private String userIp;

    private String userAgent;

    private LocalDateTime createTime;

    /**
     * DO → VO
     */
    public static LoginLogRespVO from(LoginLogDO log) {
        LoginLogRespVO vo = new LoginLogRespVO();
        vo.setId(log.getId());
        vo.setLogType(log.getLogType());
        vo.setUserId(log.getUserId());
        vo.setUserType(log.getUserType());
        vo.setUsername(log.getUsername());
        vo.setStatus(log.getStatus());
        vo.setUserIp(log.getUserIp());
        vo.setUserAgent(log.getUserAgent());
        vo.setCreateTime(log.getCreateTime());
        return vo;
    }

}
