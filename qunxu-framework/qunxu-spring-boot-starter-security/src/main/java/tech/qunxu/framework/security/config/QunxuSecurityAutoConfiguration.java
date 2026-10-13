package tech.qunxu.framework.security.config;

import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tech.qunxu.framework.security.core.api.OAuth2TokenApi;
import tech.qunxu.framework.security.core.filter.TokenAuthenticationFilter;
import tech.qunxu.framework.security.core.handler.AccessDeniedHandlerImpl;
import tech.qunxu.framework.security.core.handler.AuthenticationEntryPointImpl;
import tech.qunxu.framework.security.core.service.SecurityFrameworkService;
import tech.qunxu.framework.security.core.service.SecurityFrameworkServiceImpl;

import java.util.List;

/**
 * 安全自动配置。
 * <p>
 * 提供基于 Token 的无状态认证：默认放行登录、验证码、接口文档等路径，
 * 其余接口需携带 {@code Authorization: Bearer {token}} 请求头访问。
 * <p>
 * 先于 Spring Boot 默认安全配置注册过滤链，使 Boot 的默认过滤链自动让位，
 * 避免「any request」过滤链冲突。
 */
@AutoConfiguration(before = ServletWebSecurityAutoConfiguration.class)
@EnableConfigurationProperties(SecurityProperties.class)
public class QunxuSecurityAutoConfiguration {

    /** 默认免登录路径 */
    private static final List<String> DEFAULT_PERMIT_ALL_URLS = List.of(
            // 登录、验证码、刷新令牌
            "/admin-api/system/auth/login",
            "/admin-api/system/auth/get-captcha",
            "/admin-api/system/auth/refresh-token",
            // 接口文档
            "/doc.html",
            "/webjars/**",
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/v3/api-docs/**",
            // 其他
            "/favicon.ico",
            "/error",
            "/actuator/**",
            "/test"
    );

    /**
     * 安全过滤链
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityProperties properties,
                                                   ObjectProvider<OAuth2TokenApi> oauth2TokenApi) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable) // 基于 Token，无需 CSRF
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(registry -> {
                    // 异步（ASYNC）派发已在初次派发时完成鉴权，放行以免响应线程重复鉴权失败
                    registry.dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll();
                    for (String url : DEFAULT_PERMIT_ALL_URLS) {
                        registry.requestMatchers(url).permitAll();
                    }
                    for (String url : properties.getPermitAllUrls()) {
                        registry.requestMatchers(url).permitAll();
                    }
                    registry.anyRequest().authenticated();
                })
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(new AuthenticationEntryPointImpl())
                        .accessDeniedHandler(new AccessDeniedHandlerImpl()))
                .addFilterBefore(new TokenAuthenticationFilter(oauth2TokenApi.getIfAvailable()),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * 方法级安全：启用 {@code @PreAuthorize} 等注解
     */
    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity(proxyTargetClass = true)
    public static class MethodSecurityConfiguration {
    }

    /**
     * 权限校验服务，Bean 名称为 ss，供 SpEL {@code @ss.hasPermission(...)} 使用
     */
    @Bean("ss")
    public SecurityFrameworkService securityFrameworkService() {
        return new SecurityFrameworkServiceImpl();
    }

    /**
     * 跨域配置：默认放行全部来源
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.addAllowedOriginPattern("*");
        config.addAllowedHeader("*");
        config.addAllowedMethod("*");
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

}
