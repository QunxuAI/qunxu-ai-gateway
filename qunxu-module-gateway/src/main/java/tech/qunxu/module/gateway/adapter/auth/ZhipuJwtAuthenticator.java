package tech.qunxu.module.gateway.adapter.auth;

import tech.qunxu.module.gateway.adapter.ChannelRuntime;
import tech.qunxu.module.gateway.adapter.ProviderCredentials;
import tech.qunxu.module.gateway.adapter.UpstreamAuthenticator;
import tech.qunxu.module.gateway.common.enums.ProviderType;
import tech.qunxu.module.gateway.common.util.JwtHs256;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 智谱 GLM 鉴权器：API Key 形如 {@code {id}.{secret}}，据此现签一枚 HS256 JWT 作为 Bearer Token。
 *
 * <p>令牌载荷为 {@code {api_key:id, exp:now+1h, timestamp:now}}（毫秒时间戳，与智谱官方 SDK 一致），
 * 每次调用即时签发、不缓存，故无需处理过期刷新。凭证不完整（无 secret）时不附加鉴权头，交由下游返回 401。</p>
 */
@Component
public class ZhipuJwtAuthenticator implements UpstreamAuthenticator {

    private static final Logger log = LoggerFactory.getLogger(ZhipuJwtAuthenticator.class);

    /** 令牌有效期（毫秒），智谱要求 exp 为毫秒时间戳。 */
    private static final long EXPIRE_MILLIS = 3_600_000L;

    private final ObjectMapper objectMapper;

    public ZhipuJwtAuthenticator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(ProviderType providerType, String authType) {
        if (authType != null && !authType.isBlank()) {
            return "ZHIPU_JWT".equalsIgnoreCase(authType.trim());
        }
        return providerType == ProviderType.ZHIPU;
    }

    @Override
    public void apply(HttpHeaders headers, ChannelRuntime runtime) {
        String token = buildToken(runtime.getCredentials(), System.currentTimeMillis());
        if (token != null) {
            headers.setBearerAuth(token);
        } else if (log.isDebugEnabled()) {
            log.debug("智谱凭证不完整（缺少 id/secret），无法签发 JWT");
        }
    }

    @Override
    public int order() {
        return 100;
    }

    /**
     * 由凭证与给定「当前时间」签发智谱 JWT。凭证不完整时返回 {@code null}。
     *
     * <p>包可见以便单元测试以固定时间戳校验令牌结构与载荷声明。</p>
     */
    String buildToken(ProviderCredentials credentials, long nowMillis) {
        if (credentials == null) {
            return null;
        }
        String[] idAndSecret = resolveIdAndSecret(credentials);
        if (idAndSecret == null) {
            return null;
        }
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "HS256");
        header.put("sign_type", "HMAC");
        header.put("typ", "JWT");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("api_key", idAndSecret[0]);
        payload.put("exp", nowMillis + EXPIRE_MILLIS);
        payload.put("timestamp", nowMillis);
        String headerJson = objectMapper.writeValueAsString(header);
        String payloadJson = objectMapper.writeValueAsString(payload);
        return JwtHs256.sign(headerJson, payloadJson, idAndSecret[1].getBytes(StandardCharsets.UTF_8));
    }

    /** 解析 {@code [id, secret]}：优先 apiKey+apiSecret，否则拆分 {@code {id}.{secret}} 形式的 apiKey。 */
    private String[] resolveIdAndSecret(ProviderCredentials credentials) {
        String apiKey = credentials.apiKey();
        String apiSecret = credentials.apiSecret();
        if (apiKey != null && !apiKey.isBlank() && apiSecret != null && !apiSecret.isBlank()) {
            return new String[]{apiKey.trim(), apiSecret.trim()};
        }
        if (apiKey != null && apiKey.contains(".")) {
            int dot = apiKey.indexOf('.');
            String id = apiKey.substring(0, dot).trim();
            String secret = apiKey.substring(dot + 1).trim();
            if (!id.isEmpty() && !secret.isEmpty()) {
                return new String[]{id, secret};
            }
        }
        return null;
    }
}
