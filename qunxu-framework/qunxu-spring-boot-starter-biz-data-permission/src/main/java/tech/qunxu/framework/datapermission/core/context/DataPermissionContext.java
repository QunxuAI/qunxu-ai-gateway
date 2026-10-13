package tech.qunxu.framework.datapermission.core.context;

import java.util.Map;

/**
 * 数据权限上下文。
 * <p>
 * 在 {@code @DataPermission} 注解方法执行期间，保存当前线程需要追加的数据权限条件，
 * 供 MyBatis Plus 拦截器读取，方法执行结束后清理。
 */
public final class DataPermissionContext {

    private static final ThreadLocal<Map<String, String>> EXPRESSIONS = new ThreadLocal<>();

    private DataPermissionContext() {
    }

    /**
     * 是否开启了数据权限（存在需要追加的条件）
     */
    public static boolean isEnable() {
        Map<String, String> expressions = EXPRESSIONS.get();
        return expressions != null && !expressions.isEmpty();
    }

    /**
     * 获取表与 SQL 条件片段映射
     */
    public static Map<String, String> getExpressions() {
        return EXPRESSIONS.get();
    }

    /**
     * 设置数据权限条件
     */
    public static void setExpressions(Map<String, String> expressions) {
        EXPRESSIONS.set(expressions);
    }

    /**
     * 清理数据权限条件
     */
    public static void clear() {
        EXPRESSIONS.remove();
    }

}
