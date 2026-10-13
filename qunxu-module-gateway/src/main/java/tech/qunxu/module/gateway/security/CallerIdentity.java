package tech.qunxu.module.gateway.security;

import java.util.Map;
import java.util.Set;

/**
 * 调用人身份。由 {@link CallerIdentityResolver} 从 JWT / 静态令牌解析，贯穿审计与配额。
 *
 * @param callerId      调用人唯一标识（JWT {@code sub} 或静态令牌映射）
 * @param callerName    调用人可读名称
 * @param tenant        租户标识，未指定时为 {@code default}
 * @param scopes        授权范围（OAuth2 scope）
 * @param attributes    其他声明/属性（只读，不含敏感凭证）
 * @param authenticated 是否已认证
 */
public record CallerIdentity(
        String callerId,
        String callerName,
        String tenant,
        Set<String> scopes,
        Map<String, Object> attributes,
        boolean authenticated
) {

    /** 默认租户。 */
    public static final String DEFAULT_TENANT = "default";

    public CallerIdentity {
        scopes = scopes == null ? Set.of() : scopes;
        attributes = attributes == null ? Map.of() : attributes;
        tenant = (tenant == null || tenant.isBlank()) ? DEFAULT_TENANT : tenant;
    }

    /** 未认证（匿名）身份，鉴权关闭时使用。 */
    public static CallerIdentity anonymous() {
        return new CallerIdentity(null, null, DEFAULT_TENANT, Set.of(), Map.of(), false);
    }

    /** 构造一个已认证身份。 */
    public static CallerIdentity of(String callerId, String callerName, String tenant,
                                    Set<String> scopes, Map<String, Object> attributes) {
        return new CallerIdentity(callerId, callerName, tenant, scopes, attributes, true);
    }

    /** 是否拥有指定 scope。 */
    public boolean hasScope(String scope) {
        return scopes != null && scopes.contains(scope);
    }

    /** 用于审计/日志的稳定标识，匿名为 {@code anonymous}。 */
    public String auditId() {
        return callerId != null ? callerId : "anonymous";
    }
}
