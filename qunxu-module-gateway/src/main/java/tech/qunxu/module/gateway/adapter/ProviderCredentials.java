package tech.qunxu.module.gateway.adapter;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 已解密的供应商凭证。以键值对承载各家不同字段，并提供常见别名访问器。
 *
 * <p>凭证来自 {@code provider.credentials}（加密 JSON），由 {@code ChannelRuntimeFactory} 解密并解析。
 * 严禁将其 {@code toString()} 打印到日志或审计。</p>
 */
public final class ProviderCredentials {

    private final Map<String, String> values;

    private ProviderCredentials(Map<String, String> values) {
        this.values = values;
    }

    public static ProviderCredentials of(Map<String, String> values) {
        return new ProviderCredentials(values == null ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(values)));
    }

    public static ProviderCredentials empty() {
        return new ProviderCredentials(Collections.emptyMap());
    }

    /** API Key（兼容多种命名）。 */
    public String apiKey() {
        return first("apiKey", "api_key", "accessKey", "access_key", "apikey", "token", "key");
    }

    /** API Secret（智谱/讯飞等需要）。 */
    public String apiSecret() {
        return first("apiSecret", "api_secret", "secretKey", "secret_key", "secret");
    }

    /** AppId（讯飞星火等需要）。 */
    public String appId() {
        return first("appId", "app_id", "appid", "APPID");
    }

    /** 按精确键取值。 */
    public String get(String key) {
        return values.get(key);
    }

    public Map<String, String> asMap() {
        return values;
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    private String first(String... keys) {
        for (String k : keys) {
            String v = values.get(k);
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        // 安全：绝不输出凭证明文
        return "ProviderCredentials[REDACTED keys=" + values.keySet() + "]";
    }
}
