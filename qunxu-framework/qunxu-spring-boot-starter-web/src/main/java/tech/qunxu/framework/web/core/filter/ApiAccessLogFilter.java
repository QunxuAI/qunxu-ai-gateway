package tech.qunxu.framework.web.core.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.filter.OncePerRequestFilter;
import tech.qunxu.framework.common.util.servlet.ServletUtils;

import java.io.IOException;

/**
 * API 访问日志过滤器。
 * <p>
 * 记录每个请求的路径、方法、客户端 IP 与耗时，便于排查线上问题。
 */
@Slf4j
public class ApiAccessLogFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long startTime = System.currentTimeMillis();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long duration = System.currentTimeMillis() - startTime;
            String query = request.getQueryString();
            String uri = request.getRequestURI() + (query == null ? "" : "?" + query);
            log.info("[api-access] {} {} ip={} status={} cost={}ms",
                    request.getMethod(), uri, ServletUtils.getClientIP(request),
                    response.getStatus(), duration);
        }
    }

}
