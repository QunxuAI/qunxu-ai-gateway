package tech.qunxu.framework.tenant.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import tech.qunxu.framework.common.enums.WebFilterOrderEnum;
import tech.qunxu.framework.mybatis.core.util.MyBatisUtils;
import tech.qunxu.framework.tenant.core.db.TenantLineHandlerImpl;
import tech.qunxu.framework.tenant.core.web.TenantContextWebFilter;

/**
 * 多租户自动配置。
 * <p>
 * 通过 {@code qunxu.tenant.enable=false} 可整体关闭多租户功能。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "qunxu.tenant", name = "enable", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(TenantProperties.class)
public class QunxuTenantAutoConfiguration {

    // ========== DB：租户行拦截器 ==========

    @Bean
    public TenantLineInnerInterceptor tenantLineInnerInterceptor(TenantProperties properties,
                                                                 MybatisPlusInterceptor interceptor) {
        TenantLineInnerInterceptor inner = new TenantLineInnerInterceptor(new TenantLineHandlerImpl(properties));
        // 添加到 MyBatis Plus 拦截器链的首位，保证在分页插件之前执行
        MyBatisUtils.addInterceptor(interceptor, inner, 0);
        return inner;
    }

    // ========== WEB：租户上下文过滤器 ==========

    @Bean
    public FilterRegistrationBean<TenantContextWebFilter> tenantContextWebFilter(TenantProperties properties) {
        FilterRegistrationBean<TenantContextWebFilter> registrationBean = new FilterRegistrationBean<>();
        registrationBean.setFilter(new TenantContextWebFilter(properties));
        registrationBean.addUrlPatterns("/*");
        registrationBean.setOrder(WebFilterOrderEnum.TENANT_CONTEXT_FILTER);
        return registrationBean;
    }

}
