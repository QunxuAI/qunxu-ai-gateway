package tech.qunxu.module.system.controller.admin.logger.vo;

import lombok.Data;
import tech.qunxu.module.system.dal.dataobject.logger.OperateLogDO;

import java.time.LocalDateTime;

/**
 * 操作日志响应。
 */
@Data
public class OperateLogRespVO {

    private Long id;

    private Long userId;

    private Integer userType;

    private String module;

    private String name;

    private String type;

    private String content;

    private String requestMethod;

    private String requestUrl;

    private String userIp;

    private String userAgent;

    private String javaMethod;

    private String javaMethodArgs;

    private LocalDateTime startTime;

    private Integer duration;

    private Integer resultCode;

    private String resultMsg;

    private String resultData;

    /**
     * DO → VO
     */
    public static OperateLogRespVO from(OperateLogDO log) {
        OperateLogRespVO vo = new OperateLogRespVO();
        vo.setId(log.getId());
        vo.setUserId(log.getUserId());
        vo.setUserType(log.getUserType());
        vo.setModule(log.getModule());
        vo.setName(log.getName());
        vo.setType(log.getType());
        vo.setContent(log.getContent());
        vo.setRequestMethod(log.getRequestMethod());
        vo.setRequestUrl(log.getRequestUrl());
        vo.setUserIp(log.getUserIp());
        vo.setUserAgent(log.getUserAgent());
        vo.setJavaMethod(log.getJavaMethod());
        vo.setJavaMethodArgs(log.getJavaMethodArgs());
        vo.setStartTime(log.getStartTime());
        vo.setDuration(log.getDuration());
        vo.setResultCode(log.getResultCode());
        vo.setResultMsg(log.getResultMsg());
        vo.setResultData(log.getResultData());
        return vo;
    }

}
