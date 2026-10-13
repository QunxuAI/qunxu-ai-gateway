package tech.qunxu.framework.common.enums;

/**
 * Web 过滤器顺序常量。
 * <p>
 * 定义框架内置过滤器的执行顺序，保证过滤器链的执行次序稳定可预期。
 */
public final class WebFilterOrderEnum {

    private WebFilterOrderEnum() {
    }

    /** 分布式 Trace 过滤器：最早执行，用于生成全链路追踪 ID */
    public static final int TRACE_FILTER = 0;

    /** 租户上下文过滤器：在安全认证前，用于解析租户编号 */
    public static final int TENANT_CONTEXT_FILTER = 100;

    /** 请求体缓存过滤器：用于接口日志记录，需在认证过滤器之前 */
    public static final int CACHE_BODY_FILTER = 200;

    /** 安全认证过滤器：Spring Security 默认位置 */
    public static final int SECURITY_FILTER = -100;

    /** API 日志过滤器：记录请求与响应 */
    public static final int API_ACCESS_LOG_FILTER = 300;

    /** 异常处理过滤器：兜底捕获过滤器链中的异常 */
    public static final int EXCEPTION_FILTER = 400;

}
