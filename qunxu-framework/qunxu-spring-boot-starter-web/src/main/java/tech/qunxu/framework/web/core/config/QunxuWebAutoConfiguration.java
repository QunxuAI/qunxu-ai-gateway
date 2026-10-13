package tech.qunxu.framework.web.core.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import tech.qunxu.framework.common.enums.WebFilterOrderEnum;
import tech.qunxu.framework.web.core.filter.ApiAccessLogFilter;
import tech.qunxu.framework.web.core.handler.GlobalExceptionHandler;

/**
 * Web 自动配置。
 * <p>
 * 注册全局异常处理器与 API 访问日志过滤器。
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class QunxuWebAutoConfiguration {

    /**
     * 全局异常处理器
     */
    @Bean
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }

    /**
     * API 访问日志过滤器
     */
    @Bean
    public FilterRegistrationBean<ApiAccessLogFilter> apiAccessLogFilter() {
        FilterRegistrationBean<ApiAccessLogFilter> registrationBean = new FilterRegistrationBean<>();
        registrationBean.setFilter(new ApiAccessLogFilter());
        registrationBean.addUrlPatterns("/*");
        registrationBean.setOrder(WebFilterOrderEnum.API_ACCESS_LOG_FILTER);
        return registrationBean;
    }

}
