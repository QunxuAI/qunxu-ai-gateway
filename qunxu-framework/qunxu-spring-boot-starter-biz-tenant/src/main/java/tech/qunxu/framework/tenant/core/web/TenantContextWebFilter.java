package tech.qunxu.framework.tenant.core.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import tech.qunxu.framework.security.core.SecurityUtils;
import tech.qunxu.framework.tenant.config.TenantProperties;
import tech.qunxu.framework.tenant.core.context.TenantContextHolder;

import java.io.IOException;

/**
 * 租户上下文过滤器。
 * <p>
 * 从请求头 {@code tenant-id} 解析租户编号写入租户上下文；
 * 未携带请求头时，回退到当前登录用户的租户编号（已登录场景）。
 * 请求结束后清理上下文，避免线程复用导致数据串租户。
 */
public class TenantContextWebFilter extends OncePerRequestFilter {

    /** 租户编号请求头 */
    public static final String HEADER_TENANT_ID = "tenant-id";

    private final TenantProperties properties;

    public TenantContextWebFilter(TenantProperties properties) {
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 配置了忽略 URL 的请求不解析租户
        String uri = request.getRequestURI();
        return properties.getIgnoreUrls() != null
                && properties.getIgnoreUrls().stream().anyMatch(uri::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // 情况一：优先使用请求头中的租户编号
        String tenantIdStr = request.getHeader(HEADER_TENANT_ID);
        if (StringUtils.hasText(tenantIdStr)) {
            try {
                TenantContextHolder.setTenantId(Long.valueOf(tenantIdStr.trim()));
            } catch (NumberFormatException ignored) {
                // 租户编号格式非法，忽略
            }
        }
        // 情况二：请求头缺失时，回退到登录用户的租户编号
        if (TenantContextHolder.getTenantId() == null && SecurityUtils.getTenantId() != null) {
            TenantContextHolder.setTenantId(SecurityUtils.getTenantId());
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            TenantContextHolder.clear();
        }
    }

}
