package tech.qunxu.framework.datapermission.core.aop;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.ObjectProvider;
import tech.qunxu.framework.datapermission.core.annotation.DataPermission;
import tech.qunxu.framework.datapermission.core.context.DataPermissionContext;
import tech.qunxu.framework.datapermission.core.rule.DataPermissionRule;

import java.util.HashMap;
import java.util.Map;

/**
 * 数据权限切面。
 * <p>
 * 拦截标注了 {@link DataPermission} 的方法，在方法执行期间
 * 将 {@link DataPermissionRule} 提供的条件写入数据权限上下文。
 */
@Aspect
@Slf4j
@RequiredArgsConstructor
public class DataPermissionAspect {

    private final ObjectProvider<DataPermissionRule[]> rulesProvider;

    @Around("@annotation(dataPermission)")
    public Object around(ProceedingJoinPoint joinPoint, DataPermission dataPermission) throws Throwable {
        // 未开启数据权限，直接执行
        if (!dataPermission.enable()) {
            return joinPoint.proceed();
        }
        // 收集当前用户适用的数据权限条件
        Map<String, String> expressions = collectExpressions();
        if (expressions.isEmpty()) {
            return joinPoint.proceed();
        }
        DataPermissionContext.setExpressions(expressions);
        try {
            return joinPoint.proceed();
        } finally {
            DataPermissionContext.clear();
        }
    }

    /**
     * 收集当前用户的所有数据权限条件
     */
    private Map<String, String> collectExpressions() {
        Map<String, String> result = new HashMap<>();
        DataPermissionRule[] rules = rulesProvider.getIfAvailable();
        if (rules == null) {
            return result;
        }
        for (DataPermissionRule rule : rules) {
            Map<String, String> expressions = rule.getExpressions();
            if (expressions != null) {
                result.putAll(expressions);
            }
        }
        return result;
    }

}
