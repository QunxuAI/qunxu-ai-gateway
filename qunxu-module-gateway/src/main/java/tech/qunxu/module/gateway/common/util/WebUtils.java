package tech.qunxu.module.gateway.common.util;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Web 层工具：客户端 IP 解析、Bearer 令牌提取等（Servlet 实现）。
 *
 * <p>原 WebFlux 版本基于 {@code ServerWebExchange}；适配 Qunxu（WebMVC）后改为读取
 * {@link HttpServletRequest}，调用点（{@code AuthFilter} / {@code CallerIdentityResolver}）同步调整。</p>
 */
public final class WebUtils {

    private static final String[] IP_HEADERS = {
            "X-Forwarded-For", "X-Real-IP", "Proxy-Client-IP", "WL-Proxy-Client-IP", "HTTP_CLIENT_IP"
    };

    private WebUtils() {
    }

    /**
     * 解析客户端真实 IP。优先取代理头（X-Forwarded-For 首个非 unknown 项），否则取远程地址。
     */
    public static String clientIp(HttpServletRequest request) {
        for (String header : IP_HEADERS) {
            String value = request.getHeader(header);
            if (value != null && !value.isBlank() && !"unknown".equalsIgnoreCase(value)) {
                int comma = value.indexOf(',');
                String ip = comma > 0 ? value.substring(0, comma) : value;
                return ip.trim();
            }
        }
        if (request.getRemoteAddr() != null) {
            return request.getRemoteAddr();
        }
        return null;
    }

    /**
     * 从 {@code Authorization: Bearer xxx} 头提取令牌，缺失或格式不符返回 {@code null}。
     */
    public static String bearerToken(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = auth.substring(7).trim();
            return token.isEmpty() ? null : token;
        }
        return null;
    }
}
