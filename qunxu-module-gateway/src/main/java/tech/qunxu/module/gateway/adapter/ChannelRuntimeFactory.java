package tech.qunxu.module.gateway.adapter;

import tech.qunxu.module.gateway.common.crypto.CredentialCipher;
import tech.qunxu.module.gateway.common.enums.ChannelProtocol;
import tech.qunxu.module.gateway.common.enums.ProviderType;
import tech.qunxu.module.gateway.common.exception.ErrorCode;
import tech.qunxu.module.gateway.common.exception.GatewayException;
import tech.qunxu.module.gateway.persistence.entity.ModelChannelEntity;
import tech.qunxu.module.gateway.persistence.entity.ProviderEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.Map;

/**
 * 通道运行时工厂：把「通道实体 + 供应商实体」装配为适配器可直接使用的 {@link ChannelRuntime}。
 *
 * <p>负责解密供应商凭证（{@link CredentialCipher}）、解析 {@code config_json} 通道级配置、
 * 归一化 {@code baseUrl}（通道配置可覆盖供应商默认），并推断 {@link ProviderType} 与 {@link ChannelProtocol}。
 * 适配器因此无需直接触达数据库与加解密逻辑。</p>
 */
@Component
public class ChannelRuntimeFactory {

    private static final Logger log = LoggerFactory.getLogger(ChannelRuntimeFactory.class);
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {
    };

    private final CredentialCipher cipher;
    private final ObjectMapper objectMapper;

    public ChannelRuntimeFactory(CredentialCipher cipher, ObjectMapper objectMapper) {
        this.cipher = cipher;
        this.objectMapper = objectMapper;
    }

    /** 构建通道运行时快照。 */
    public ChannelRuntime create(ModelChannelEntity channel, ProviderEntity provider) {
        if (channel == null) {
            throw new GatewayException(ErrorCode.ALL_CHANNELS_DOWN, "通道不存在");
        }
        ProviderType providerType = provider != null ? ProviderType.of(provider.getCode()) : null;
        ChannelProtocol protocol = ChannelProtocol.of(channel.getProtocol());
        Map<String, Object> config = parseConfig(channel.getConfigJson());
        String baseUrl = resolveBaseUrl(provider, config);
        ProviderCredentials credentials = parseCredentials(provider != null ? provider.getCredentials() : null);

        return new ChannelRuntime(channel, provider, providerType, protocol, baseUrl,
                channel.getUpstreamModel(), credentials, config);
    }

    /** 构建仅用于供应商探测的运行时快照（无通道，如拉取模型清单、连通性检查）。 */
    public ChannelRuntime createForProvider(ProviderEntity provider) {
        if (provider == null) {
            throw new GatewayException(ErrorCode.BAD_REQUEST, "供应商不存在");
        }
        ProviderType providerType = ProviderType.of(provider.getCode());
        String baseUrl = provider.getBaseUrl();
        ProviderCredentials credentials = parseCredentials(provider.getCredentials());
        return new ChannelRuntime(null, provider, providerType, null, baseUrl, null, credentials, Collections.emptyMap());
    }

    private String resolveBaseUrl(ProviderEntity provider, Map<String, Object> config) {
        Object override = config.get("baseUrl");
        if (override instanceof String s && !s.isBlank()) {
            return s.trim();
        }
        return provider != null ? provider.getBaseUrl() : null;
    }

    private Map<String, Object> parseConfig(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }
        String trimmed = json.trim();
        if (!trimmed.startsWith("{")) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(trimmed, OBJECT_MAP);
        } catch (RuntimeException e) {
            log.warn("通道 config_json 解析失败，按空配置处理: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    private ProviderCredentials parseCredentials(String stored) {
        if (stored == null || stored.isBlank()) {
            return ProviderCredentials.empty();
        }
        String plain = cipher.decrypt(stored);
        if (plain == null || plain.isBlank()) {
            return ProviderCredentials.empty();
        }
        String trimmed = plain.trim();
        try {
            if (trimmed.startsWith("{")) {
                return ProviderCredentials.of(objectMapper.readValue(trimmed, STRING_MAP));
            }
            // 开发环境：直接填写明文 API Key
            return ProviderCredentials.of(Map.of("apiKey", trimmed));
        } catch (RuntimeException e) {
            // 安全：绝不记录凭证明文，仅抛出可读的错误码
            throw new GatewayException(ErrorCode.PROVIDER_ERROR, "供应商凭证解析失败", e);
        }
    }
}
