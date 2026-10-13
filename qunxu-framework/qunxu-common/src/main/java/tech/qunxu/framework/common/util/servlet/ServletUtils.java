package tech.qunxu.framework.common.util.servlet;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Servlet 工具类。
 * <p>
 * 提供获取当前请求、客户端 IP 等常见能力。
 */
public final class ServletUtils {

    private static final String UNKNOWN = "unknown";

    private static final String[] IP_HEADERS = {
            "X-Forwarded-For",
            "X-Real-IP",
            "Proxy-Client-IP",
            "WL-Proxy-Client-IP",
            "HTTP_CLIENT_IP",
            "HTTP_X_FORWARDED_FOR"
    };

    private ServletUtils() {
    }

    /**
     * 获取当前请求，无请求上下文时返回 null
     */
    public static HttpServletRequest getRequest() {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attributes == null ? null : attributes.getRequest();
    }

    /**
     * 获取客户端真实 IP，优先解析代理头
     */
    public static String getClientIP(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String ip = null;
        // 依次尝试常见的代理转发头
        for (String header : IP_HEADERS) {
            ip = request.getHeader(header);
            if (StringUtils.hasText(ip) && !UNKNOWN.equalsIgnoreCase(ip)) {
                // X-Forwarded-For 可能携带多个 IP，取第一个
                int commaIndex = ip.indexOf(',');
                if (commaIndex != -1) {
                    ip = ip.substring(0, commaIndex).trim();
                }
                break;
            }
        }
        if (!StringUtils.hasText(ip) || UNKNOWN.equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        // localhost 特殊处理为 127.0.0.1
        if ("0:0:0:0:0:0:0:1".equals(ip)) {
            ip = "127.0.0.1";
        }
        return ip;
    }

    /**
     * 获取当前请求的客户端 IP
     */
    public static String getClientIP() {
        return getClientIP(getRequest());
    }

    /**
     * 获取请求完整 URL（含 query string）
     */
    public static String getFullUrl(HttpServletRequest request) {
        String query = request.getQueryString();
        return request.getRequestURI() + (query == null ? "" : "?" + query);
    }

    /**
     * 判断是否为内网 IP（简化实现，仅判断本机回环）
     */
    public static boolean isLocalAddress(String ip) {
        if (ip == null) {
            return false;
        }
        try {
            InetAddress address = InetAddress.getByName(ip);
            return address.isLoopbackAddress() || address.isSiteLocalAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

}
