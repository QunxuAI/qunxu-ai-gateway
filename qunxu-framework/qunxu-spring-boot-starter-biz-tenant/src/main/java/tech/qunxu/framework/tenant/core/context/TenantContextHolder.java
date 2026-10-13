package tech.qunxu.framework.tenant.core.context;

/**
 * 租户上下文持有器。
 * <p>
 * 每个请求的租户编号保存在 ThreadLocal 中，由 {@code TenantContextWebFilter}
 * 解析请求头后写入，供数据库层租户拦截器使用，请求结束后清理。
 */
public final class TenantContextHolder {

    private static final ThreadLocal<Long> TENANT_ID = new ThreadLocal<>();

    private TenantContextHolder() {
    }

    /**
     * 获取当前租户编号，未设置时返回 null
     */
    public static Long getTenantId() {
        return TENANT_ID.get();
    }

    /**
     * 获取当前租户编号，未设置时抛出异常
     */
    public static Long getRequiredTenantId() {
        Long tenantId = TENANT_ID.get();
        if (tenantId == null) {
            throw new IllegalStateException("租户上下文未设置租户编号");
        }
        return tenantId;
    }

    /**
     * 设置当前租户编号
     */
    public static void setTenantId(Long tenantId) {
        TENANT_ID.set(tenantId);
    }

    /**
     * 清理当前租户编号
     */
    public static void clear() {
        TENANT_ID.remove();
    }

}
