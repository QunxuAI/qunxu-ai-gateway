package tech.qunxu.module.system.framework.operatelog.core.aspect;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import tech.qunxu.framework.common.util.json.JsonUtils;
import tech.qunxu.framework.common.util.servlet.ServletUtils;
import tech.qunxu.framework.security.core.LoginUser;
import tech.qunxu.framework.security.core.SecurityUtils;
import tech.qunxu.module.system.dal.dataobject.logger.OperateLogDO;
import tech.qunxu.module.system.framework.operatelog.core.annotation.OperateLog;
import tech.qunxu.module.system.service.logger.OperateLogService;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;

/**
 * 操作日志切面。
 * <p>
 * 拦截 {@link OperateLog} 注解方法，记录操作日志到数据库。
 */
@Aspect
@Component
@RequiredArgsConstructor
@Slf4j
public class OperateLogAspect {

    private final OperateLogService operateLogService;

    @Around("@annotation(operateLog)")
    public Object around(ProceedingJoinPoint joinPoint, OperateLog operateLog) throws Throwable {
        OperateLogDO logDO = buildBaseLog(joinPoint, operateLog);
        long startTime = System.currentTimeMillis();
        try {
            Object result = joinPoint.proceed();
            logDO.setResultCode(0);
            logDO.setResultMsg("成功");
            logDO.setResultData(safeSerialize(result));
            return result;
        } catch (Throwable ex) {
            logDO.setResultCode(-1);
            logDO.setResultMsg(ex.getMessage());
            throw ex;
        } finally {
            logDO.setDuration((int) (System.currentTimeMillis() - startTime));
            saveLog(logDO);
        }
    }

    private OperateLogDO buildBaseLog(ProceedingJoinPoint joinPoint, OperateLog operateLog) {
        OperateLogDO logDO = new OperateLogDO();
        // 用户信息
        LoginUser loginUser = SecurityUtils.getLoginUser().orElse(null);
        if (loginUser != null) {
            logDO.setUserId(loginUser.getId());
            logDO.setUserType(loginUser.getUserType());
        }
        // 注解信息
        logDO.setModule(operateLog.module());
        logDO.setName(operateLog.name());
        logDO.setType(operateLog.type());
        logDO.setContent(operateLog.content());
        // Java 方法信息
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        logDO.setJavaMethod(signature.getDeclaringTypeName() + "#" + signature.getName());
        logDO.setJavaMethodArgs(safeSerialize(joinPoint.getArgs()));
        // 请求信息
        HttpServletRequest request = ServletUtils.getRequest();
        if (request != null) {
            logDO.setRequestMethod(request.getMethod());
            logDO.setRequestUrl(request.getRequestURI());
            logDO.setUserIp(ServletUtils.getClientIP(request));
            logDO.setUserAgent(request.getHeader("User-Agent"));
        }
        logDO.setStartTime(LocalDateTime.now());
        return logDO;
    }

    private void saveLog(OperateLogDO logDO) {
        try {
            operateLogService.createOperateLog(logDO);
        } catch (Exception ex) {
            log.warn("[around][记录操作日志失败，error: {}]", ex.getMessage());
        }
    }

    private String safeSerialize(Object object) {
        try {
            return JsonUtils.toJsonString(object);
        } catch (Exception ex) {
            return null;
        }
    }

}
