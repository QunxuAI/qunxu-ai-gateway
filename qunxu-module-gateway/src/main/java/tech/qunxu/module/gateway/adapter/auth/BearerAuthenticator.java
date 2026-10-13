package tech.qunxu.module.gateway.adapter.auth;

import tech.qunxu.module.gateway.adapter.ChannelRuntime;
import tech.qunxu.module.gateway.adapter.UpstreamAuthenticator;
import tech.qunxu.module.gateway.common.enums.ProviderType;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * 通用 Bearer Token 鉴权器：设置 {@code Authorization: Bearer <apiKey>}。
 *
 * <p>适用于 DeepSeek、通义千问（DashScope 兼容/原生）、OpenAI 及任意 OpenAI 兼容自定义供应商。
 * 作为最低优先级（{@link #order()} 最大）的兜底鉴权器。</p>
 */
@Component
public class BearerAuthenticator implements UpstreamAuthenticator {

    @Override
    public boolean supports(ProviderType providerType, String authType) {
        if (authType != null && !authType.isBlank()) {
            return "BEARER".equalsIgnoreCase(authType.trim());
        }
        // authType 未显式指定时按供应商推断：除智谱/千帆/讯飞外默认 Bearer
        return providerType == null
                || providerType == ProviderType.DEEPSEEK
                || providerType == ProviderType.DASHSCOPE
                || providerType == ProviderType.OPENAI
                || providerType == ProviderType.CUSTOM;
    }

    @Override
    public void apply(HttpHeaders headers, ChannelRuntime runtime) {
        String apiKey = runtime.getCredentials().apiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            headers.setBearerAuth(apiKey);
        }
    }

    @Override
    public int order() {
        return 1000;
    }
}
