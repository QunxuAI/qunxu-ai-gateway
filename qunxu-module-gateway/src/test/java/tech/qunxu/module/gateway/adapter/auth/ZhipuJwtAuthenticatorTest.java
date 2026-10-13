package tech.qunxu.module.gateway.adapter.auth;

import tech.qunxu.module.gateway.adapter.ProviderCredentials;
import tech.qunxu.module.gateway.common.enums.ProviderType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 智谱 GLM 鉴权器单元测试：{@code supports} 匹配规则 + {@code buildToken} 的 JWT 头部/载荷声明
 * （{@code alg/sign_type/typ} 与 {@code api_key/exp/timestamp}），以及凭证不完整时的降级（返回 null）。
 */
class ZhipuJwtAuthenticatorTest {

    private static final long EXPIRE_MILLIS = 3_600_000L;

    private final ObjectMapper mapper = new ObjectMapper();
    private final ZhipuJwtAuthenticator authenticator = new ZhipuJwtAuthenticator(mapper);

    @Test
    void supportsByAuthTypeOrProviderType() {
        assertThat(authenticator.supports(ProviderType.ZHIPU, "ZHIPU_JWT")).isTrue();
        assertThat(authenticator.supports(ProviderType.ZHIPU, null)).isTrue();
        assertThat(authenticator.supports(ProviderType.DEEPSEEK, "ZHIPU_JWT")).isTrue();
        assertThat(authenticator.supports(ProviderType.DEEPSEEK, "BEARER")).isFalse();
    }

    @Test
    void buildTokenFromDottedApiKeyProducesValidHeaderAndClaims() {
        long now = 1_700_000_000_000L;
        ProviderCredentials credentials = ProviderCredentials.of(Map.of("apiKey", "myid.mysecret"));

        String token = authenticator.buildToken(credentials, now);
        assertThat(token).isNotNull();
        String[] parts = token.split("\\.");
        assertThat(parts).hasSize(3);

        Base64.Decoder decoder = Base64.getUrlDecoder();
        JsonNode header = mapper.readTree(new String(decoder.decode(parts[0]), StandardCharsets.UTF_8));
        JsonNode payload = mapper.readTree(new String(decoder.decode(parts[1]), StandardCharsets.UTF_8));

        assertThat(header.path("alg").asText()).isEqualTo("HS256");
        assertThat(header.path("sign_type").asText()).isEqualTo("HMAC");
        assertThat(header.path("typ").asText()).isEqualTo("JWT");

        assertThat(payload.path("api_key").asText()).isEqualTo("myid");
        assertThat(payload.path("timestamp").asLong()).isEqualTo(now);
        assertThat(payload.path("exp").asLong()).isEqualTo(now + EXPIRE_MILLIS);
    }

    @Test
    void buildTokenFromSeparateIdAndSecretUsesApiKeyAsId() {
        long now = 1_700_000_000_000L;
        ProviderCredentials credentials = ProviderCredentials.of(Map.of("apiKey", "theid", "apiSecret", "thesecret"));

        String token = authenticator.buildToken(credentials, now);
        assertThat(token).isNotNull();
        JsonNode payload = mapper.readTree(
                new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8));
        assertThat(payload.path("api_key").asText()).isEqualTo("theid");
    }

    @Test
    void buildTokenReturnsNullWhenCredentialsIncomplete() {
        // apiKey 无 "." 且无 apiSecret → 无法拆出 id/secret
        assertThat(authenticator.buildToken(ProviderCredentials.of(Map.of("apiKey", "nosecrethere")), 1L)).isNull();
        assertThat(authenticator.buildToken(ProviderCredentials.empty(), 1L)).isNull();
        assertThat(authenticator.buildToken(null, 1L)).isNull();
    }
}
