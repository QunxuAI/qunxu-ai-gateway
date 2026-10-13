package tech.qunxu.module.system.service.logger;

import com.baomidou.dynamic.datasource.annotation.DS;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tech.qunxu.framework.common.pojo.PageResult;
import tech.qunxu.module.system.controller.admin.logger.vo.LoginLogPageReqVO;
import tech.qunxu.module.system.dal.dataobject.logger.LoginLogDO;
import tech.qunxu.module.system.dal.mysql.logger.LoginLogMapper;
import tech.qunxu.module.system.enums.LogTypeEnum;

/**
 * 登录日志服务。
 */
@Service
@RequiredArgsConstructor
public class LoginLogService {

    private final LoginLogMapper loginLogMapper;

    /**
     * 记录登录日志
     */
    public void createLoginLog(LogTypeEnum logType, Long userId, Integer userType,
                               String username, Integer status, String userIp, String userAgent) {
        LoginLogDO log = new LoginLogDO();
        log.setLogType(logType.getType());
        log.setUserId(userId);
        log.setUserType(userType);
        log.setUsername(username);
        log.setStatus(status);
        log.setUserIp(userIp);
        log.setUserAgent(userAgent);
        loginLogMapper.insert(log);
    }

    /**
     * 分页查询登录日志
     */
    @DS("slave")
    public PageResult<LoginLogDO> getLoginLogPage(LoginLogPageReqVO reqVO) {
        return loginLogMapper.selectPage(reqVO, new LambdaQueryWrapper<LoginLogDO>()
                .like(StrUtil.isNotEmpty(reqVO.getUsername()), LoginLogDO::getUsername, reqVO.getUsername())
                .like(StrUtil.isNotEmpty(reqVO.getUserIp()), LoginLogDO::getUserIp, reqVO.getUserIp())
                .eq(reqVO.getStatus() != null, LoginLogDO::getStatus, reqVO.getStatus())
                .eq(reqVO.getLogType() != null, LoginLogDO::getLogType, reqVO.getLogType())
                .orderByDesc(LoginLogDO::getId));
    }

    /**
     * 获取登录日志
     */
    @DS("slave")
    public LoginLogDO getLoginLog(Long id) {
        return loginLogMapper.selectById(id);
    }

}
