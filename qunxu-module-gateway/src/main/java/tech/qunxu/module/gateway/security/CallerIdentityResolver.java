package tech.qunxu.module.gateway.security;

import jakarta.servlet.http.HttpServletRequest;
import tech.qunxu.framework.security.core.LoginUser;
import tech.qunxu.framework.security.core.SecurityUtils;
import tech.qunxu.module.gateway.common.util.WebUtils;
import tech.qunxu.module.gateway.config.GatewayProperties;
import tech.qunxu.module.gateway.security.ApiKeySnapshotService.ApiKeyAuth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 调用人身份解析器（Servlet 版，由 WebFlux 版迁移而来）。
 *
 * <p>解析优先级：</p>
 * <ol>
 *   <li>Qunxu 登录用户（管理员携带 {@code /admin-api} 令牌访问数据面时，直接用登录用户身份）；</li>
 *   <li>网关 APIKey（{@code gateway_api_key} 表管理的、绑定到系统用户的数据面凭证，见
 *       {@link ApiKeySnapshotService}，按 Bearer 令牌的 SHA-256 摘要匹配）；</li>
 *   <li>配置的静态令牌 {@code gateway.security.static-tokens}（本地/联调，格式 {@code token} 或 {@code token:callerId}）；</li>
 *   <li>匿名身份（鉴权关闭且无令牌时）。</li>
 * </ol>
 * 解析结果写入 {@code RequestContext}，供审计、限流与配额使用。
 */
@Component
public class CallerIdentityResolver {

    private static final Logger log = LoggerFactory.getLogger(CallerIdentityResolver.class);

    private static final String SCOPE = "gateway:invoke";

    private final GatewayProperties properties;
    private final ApiKeySnapshotService apiKeySnapshotService;
    /** 静态令牌 -> 调用人ID 的映射（鉴权关闭时的本地识别手段）。 */
    private final Map<String, String> staticTokens;

    public CallerIdentityResolver(GatewayProperties properties, ApiKeySnapshotService apiKeySnapshotService) {
        this.properties = properties;
        this.apiKeySnapshotService = apiKeySnapshotService;
        this.staticTokens = parseStaticTokens(properties.getSecurity().getStaticTokens());
    }

    /**
     * 从请求中解析调用人身份。永不返回 {@code null}。
     */
    public CallerIdentity resolve(HttpServletRequest request) {
        // 1. Qunxu 登录用户（已通过 /admin-api 令牌认证）
        var loginUserOpt = SecurityUtils.getLoginUser();
        if (loginUserOpt.isPresent()) {
            return fromLoginUser(loginUserOpt.get());
        }
        // 2. 网关 APIKey（绑定到系统用户的数据面凭证）
        CallerIdentity byApiKey = fromApiKey(request);
        if (byApiKey != null) {
            return byApiKey;
        }
        // 3. 静态令牌
        CallerIdentity byStatic = fromStaticToken(request);
        if (byStatic != null) {
            return byStatic;
        }
        // 4. 鉴权关闭且无有效令牌：匿名放行，便于本地联调
        return CallerIdentity.anonymous();
    }

    private CallerIdentity fromLoginUser(LoginUser user) {
        String subject = user.getId() != null ? String.valueOf(user.getId()) : user.getUsername();
        String name = user.getNickname() != null && !user.getNickname().isBlank()
                ? user.getNickname() : user.getUsername();
        String tenant = user.getTenantId() != null
                ? String.valueOf(user.getTenantId()) : CallerIdentity.DEFAULT_TENANT;

        Set<String> scopes = new LinkedHashSet<>();
        scopes.add(SCOPE);
        if (user.getPermissions() != null) {
            scopes.addAll(user.getPermissions());
        }
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("auth", "qunxu-login-user");
        if (user.getUsername() != null) {
            attributes.put("username", user.getUsername());
        }
        return CallerIdentity.of(subject, name, tenant, scopes, attributes);
    }

    private CallerIdentity fromApiKey(HttpServletRequest request) {
        String token = WebUtils.bearerToken(request);
        if (token == null) {
            return null;
        }
        return apiKeySnapshotService.resolve(token)
                .map(auth -> fromApiKeyAuth(auth))
                .orElse(null);
    }

    private CallerIdentity fromApiKeyAuth(ApiKeyAuth auth) {
        String callerId = String.valueOf(auth.userId());
        String callerName = auth.userName() != null && !auth.userName().isBlank()
                ? auth.userName() : "user:" + auth.userId();
        String tenant = auth.tenant() != null && !auth.tenant().isBlank()
                ? auth.tenant() : CallerIdentity.DEFAULT_TENANT;
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("auth", "api-key");
        attributes.put("apiKeyId", auth.keyId());
        attributes.put("apiKeyName", auth.keyName());
        return CallerIdentity.of(callerId, callerName, tenant, Set.of(SCOPE), attributes);
    }

    private CallerIdentity fromStaticToken(HttpServletRequest request) {
        String token = WebUtils.bearerToken(request);
        if (token != null) {
            String callerId = staticTokens.get(token);
            if (callerId != null) {
                return CallerIdentity.of(callerId, callerId, CallerIdentity.DEFAULT_TENANT,
                        Set.of(SCOPE), Map.of("auth", "static-token"));
            }
        }
        return null;
    }

    private static Map<String, String> parseStaticTokens(String raw) {
        if (raw == null || raw.isBlank()) {
            return Collections.emptyMap();
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (String entry : raw.split(",")) {
            String item = entry.trim();
            if (item.isEmpty()) {
                continue;
            }
            int colon = item.indexOf(':');
            if (colon > 0) {
                map.put(item.substring(0, colon).trim(), item.substring(colon + 1).trim());
            } else {
                map.put(item, "static");
            }
        }
        if (!map.isEmpty()) {
            log.info("Loaded {} static gateway token(s) for local/dev authentication", map.size());
        }
        return map;
    }
}
