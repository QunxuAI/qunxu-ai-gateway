package tech.qunxu.module.gateway.adapter;

import tech.qunxu.module.gateway.common.enums.ProviderType;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * 鉴权器解析器：按供应商与鉴权类型选择匹配的 {@link UpstreamAuthenticator}（{@link UpstreamAuthenticator#order()} 升序）。
 */
@Component
public class UpstreamAuthenticatorResolver {

    private final List<UpstreamAuthenticator> authenticators;

    public UpstreamAuthenticatorResolver(List<UpstreamAuthenticator> authenticators) {
        this.authenticators = authenticators.stream()
                .sorted(Comparator.comparingInt(UpstreamAuthenticator::order))
                .toList();
    }

    /**
     * 解析匹配的鉴权器。
     *
     * @return 匹配的鉴权器；若无匹配返回 {@code null}（调用方将不附加鉴权头）
     */
    public UpstreamAuthenticator resolve(ProviderType providerType, String authType) {
        for (UpstreamAuthenticator authenticator : authenticators) {
            if (authenticator.supports(providerType, authType)) {
                return authenticator;
            }
        }
        return null;
    }
}
