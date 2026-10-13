package tech.qunxu.framework.datapermission.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import tech.qunxu.framework.datapermission.core.aop.DataPermissionAspect;
import tech.qunxu.framework.datapermission.core.db.DataPermissionRuleHandler;
import tech.qunxu.framework.datapermission.core.rule.DataPermissionRule;
import tech.qunxu.framework.mybatis.core.util.MyBatisUtils;

/**
 * 数据权限自动配置。
 * <p>
 * 通过 {@code qunxu.data-permission.enable=false} 可关闭数据权限功能。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "qunxu.data-permission", name = "enable", havingValue = "true", matchIfMissing = true)
public class QunxuDataPermissionAutoConfiguration {

    /**
     * 数据权限 SQL 处理器：注册到 MyBatis Plus 拦截器链（排在租户之后、分页之前）
     */
    @Bean
    public DataPermissionRuleHandler dataPermissionRuleHandler(MybatisPlusInterceptor interceptor) {
        DataPermissionRuleHandler handler = new DataPermissionRuleHandler();
        MyBatisUtils.addInterceptor(interceptor, handler, 1);
        return handler;
    }

    /**
     * 数据权限切面：解析 {@code @DataPermission} 注解
     */
    @Bean
    public DataPermissionAspect dataPermissionAspect(ObjectProvider<DataPermissionRule[]> rulesProvider) {
        return new DataPermissionAspect(rulesProvider);
    }

}
