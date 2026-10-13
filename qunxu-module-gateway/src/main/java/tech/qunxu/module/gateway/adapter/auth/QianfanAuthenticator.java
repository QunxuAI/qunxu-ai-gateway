package tech.qunxu.module.gateway.adapter.auth;

import tech.qunxu.module.gateway.adapter.ChannelRuntime;
import tech.qunxu.module.gateway.adapter.UpstreamAuthenticator;
import tech.qunxu.module.gateway.common.enums.ProviderType;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * 百度千帆 V2 鉴权器：以 {@code Authorization: Bearer <api_key>} 承载千帆 V2 的 API Key
 * （形如 {@code bce-v3/ALTAK-.../...}）。
 *
 * <p>千帆 V2 为 OpenAI 兼容接口，鉴权即一枚长期 Bearer Token，无需 IAM OAuth 令牌交换，故 {@link #apply}
 * 保持无阻塞。区别于通用 {@link BearerAuthenticator}：本类按 {@link ProviderType#QIANFAN} 或
 * {@code QIANFAN_IAM/QIANFAN_V2} 鉴权类型显式匹配，使千帆通道即便 authType 缺省也能正确附加凭证。</p>
 */
@Component
public class QianfanAuthenticator implements UpstreamAuthenticator {

    @Override
    public boolean supports(ProviderType providerType, String authType) {
        if (authType != null && !authType.isBlank()) {
            String a = authType.trim();
            return "QIANFAN_IAM".equalsIgnoreCase(a) || "QIANFAN_V2".equalsIgnoreCase(a);
        }
        return providerType == ProviderType.QIANFAN;
    }

    @Override
    public void apply(HttpHeaders headers, ChannelRuntime runtime) {
        String apiKey = runtime.getCredentials().apiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            headers.setBearerAuth(apiKey.trim());
        }
    }

    @Override
    public int order() {
        return 100;
    }
}
