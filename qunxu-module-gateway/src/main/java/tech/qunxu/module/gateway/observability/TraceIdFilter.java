package tech.qunxu.module.gateway.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 链路追踪 ID 过滤器（最高优先级，先于安全与业务过滤器执行）。
 *
 * <p>职责（Servlet 实现，由 WebFlux 版 {@code WebFilter} 迁移而来）：</p>
 * <ol>
 *   <li>入站复用上游传入的 traceId（X-Trace-Id / X-Request-Id / W3C traceparent），否则生成；</li>
 *   <li>写入请求属性（供 {@code GatewayInvoker} 构建 {@code RequestContext}）与响应头（便于客户端关联）；</li>
 *   <li>写入 MDC（请求线程），配合 {@link ReactorMdcConfiguration} 的自动上下文传播，让 Reactor
 *       线程上的异步日志同样携带 {@code %X{traceId}}。</li>
 * </ol>
 */
@Component
public class TraceIdFilter extends OncePerRequestFilter implements Ordered {

    /** MDC / Reactor Context 中 traceId 的键，须与日志 pattern 的 {@code %X{traceId}} 一致。 */
    public static final String TRACE_ID_KEY = "traceId";
    /** 请求属性键：编排器据此把 traceId 写入 {@code RequestContext}。 */
    public static final String TRACE_ID_ATTR = "gateway.traceId";
    /** traceId 出入站响应头。 */
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    private static final String[] INBOUND_HEADERS = {"X-Trace-Id", "X-Request-Id", "X-B3-TraceId", "traceparent"};

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = resolveTraceId(request);
        request.setAttribute(TRACE_ID_ATTR, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);
        MDC.put(TRACE_ID_KEY, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(TRACE_ID_KEY);
        }
    }

    private String resolveTraceId(HttpServletRequest request) {
        for (String header : INBOUND_HEADERS) {
            String value = request.getHeader(header);
            if (value != null && !value.isBlank()) {
                String sanitized = sanitize(header, value.trim());
                if (sanitized != null) {
                    return sanitized;
                }
            }
        }
        return generate();
    }

    /** 清洗入站 traceId：防注入、限长；W3C traceparent 取其中的 trace-id 段。 */
    private String sanitize(String header, String value) {
        if ("traceparent".equalsIgnoreCase(header)) {
            String[] parts = value.split("-");
            // 形如 00-<32位traceId>-<16位spanId>-<flags>
            if (parts.length >= 2 && parts[1].length() == 32 && parts[1].matches("[0-9a-fA-F]+")) {
                return parts[1];
            }
            return null;
        }
        String cleaned = value.replaceAll("[^A-Za-z0-9._-]", "");
        if (cleaned.isEmpty()) {
            return null;
        }
        return cleaned.length() > 64 ? cleaned.substring(0, 64) : cleaned;
    }

    private String generate() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
