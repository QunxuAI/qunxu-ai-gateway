package tech.qunxu.module.gateway.adapter.auth;

import tech.qunxu.module.gateway.adapter.ChannelRuntime;
import tech.qunxu.module.gateway.adapter.ProviderCredentials;
import tech.qunxu.module.gateway.common.enums.ChannelProtocol;
import tech.qunxu.module.gateway.common.enums.ProviderType;
import tech.qunxu.module.gateway.persistence.entity.ModelChannelEntity;
import tech.qunxu.module.gateway.persistence.entity.ProviderEntity;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 百度千帆 V2 鉴权器单元测试：{@code supports} 按 authType/ProviderType 匹配，{@code apply} 以 Bearer 承载 API Key，
 * 且 API Key 为空时不附加鉴权头。
 */
class QianfanAuthenticatorTest {

    private final QianfanAuthenticator authenticator = new QianfanAuthenticator();

    @Test
    void supportsByAuthTypeOrProviderType() {
        assertThat(authenticator.supports(ProviderType.QIANFAN, "QIANFAN_V2")).isTrue();
        assertThat(authenticator.supports(ProviderType.QIANFAN, "QIANFAN_IAM")).isTrue();
        assertThat(authenticator.supports(ProviderType.QIANFAN, null)).isTrue();
        assertThat(authenticator.supports(ProviderType.DEEPSEEK, "QIANFAN_V2")).isTrue();
        assertThat(authenticator.supports(ProviderType.DEEPSEEK, "BEARER")).isFalse();
    }

    @Test
    void applySetsBearerToken() {
        HttpHeaders headers = new HttpHeaders();
        authenticator.apply(headers, runtime("bce-v3/ALTAK-abc/xyz"));
        assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer bce-v3/ALTAK-abc/xyz");
    }

    @Test
    void applySkipsWhenApiKeyBlank() {
        HttpHeaders headers = new HttpHeaders();
        authenticator.apply(headers, runtime(""));
        assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION)).isNull();
    }

    private ChannelRuntime runtime(String apiKey) {
        ModelChannelEntity channel = new ModelChannelEntity();
        channel.setId(1L);
        channel.setUpstreamModel("ernie-4.0-8k");
        ProviderEntity provider = new ProviderEntity();
        provider.setId(1L);
        provider.setCode("QIANFAN");
        provider.setBaseUrl("https://qianfan.baidubce.com/v2");
        provider.setAuthType("QIANFAN_V2");
        return new ChannelRuntime(channel, provider, ProviderType.QIANFAN, ChannelProtocol.OPENAI_COMPAT,
                "https://qianfan.baidubce.com/v2", "ernie-4.0-8k",
                ProviderCredentials.of(Map.of("apiKey", apiKey)), Map.of());
    }
}
