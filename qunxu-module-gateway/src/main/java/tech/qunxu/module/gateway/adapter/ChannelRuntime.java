package tech.qunxu.module.gateway.adapter;

import tech.qunxu.module.gateway.common.enums.ChannelProtocol;
import tech.qunxu.module.gateway.common.enums.ProviderType;
import tech.qunxu.module.gateway.persistence.entity.ModelChannelEntity;
import tech.qunxu.module.gateway.persistence.entity.ProviderEntity;

import java.util.Collections;
import java.util.Map;

/**
 * 通道运行时：把「通道实体 + 供应商实体 + 解密凭证 + 解析后的配置」聚合为适配器可直接使用的只读快照。
 *
 * <p>由 {@code ChannelRuntimeFactory} 在路由选中通道后构建，避免适配器直接访问数据库与加解密。</p>
 */
public final class ChannelRuntime {

    private final ModelChannelEntity channel;
    private final ProviderEntity provider;
    private final ProviderType providerType;
    private final ChannelProtocol protocol;
    private final String baseUrl;
    private final String upstreamModel;
    private final ProviderCredentials credentials;
    private final Map<String, Object> config;

    public ChannelRuntime(ModelChannelEntity channel,
                          ProviderEntity provider,
                          ProviderType providerType,
                          ChannelProtocol protocol,
                          String baseUrl,
                          String upstreamModel,
                          ProviderCredentials credentials,
                          Map<String, Object> config) {
        this.channel = channel;
        this.provider = provider;
        this.providerType = providerType;
        this.protocol = protocol;
        this.baseUrl = baseUrl;
        this.upstreamModel = upstreamModel;
        this.credentials = credentials != null ? credentials : ProviderCredentials.empty();
        this.config = config != null ? config : Collections.emptyMap();
    }

    public ModelChannelEntity getChannel() { return channel; }
    public ProviderEntity getProvider() { return provider; }
    public ProviderType getProviderType() { return providerType; }
    public ChannelProtocol getProtocol() { return protocol; }
    public String getBaseUrl() { return baseUrl; }
    public String getUpstreamModel() { return upstreamModel; }
    public ProviderCredentials getCredentials() { return credentials; }
    public Map<String, Object> getConfig() { return config; }

    public Long getChannelId() { return channel != null ? channel.getId() : null; }
    public Long getProviderId() { return provider != null ? provider.getId() : null; }
    public String getProviderCode() { return provider != null ? provider.getCode() : null; }
    /** 供应商鉴权类型（BEARER/ZHIPU_JWT/QIANFAN_IAM/SPARK_WS）。 */
    public String getAuthType() { return provider != null ? provider.getAuthType() : null; }

    /** 拼接下游完整 URL：规范化 baseUrl 后追加相对路径。 */
    public String resolveUrl(String path) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String p = path == null ? "" : path.trim();
        if (!p.isEmpty() && !p.startsWith("/")) {
            p = "/" + p;
        }
        return base + p;
    }

    @Override
    public String toString() {
        return "ChannelRuntime{channelId=" + getChannelId()
                + ", provider=" + getProviderCode()
                + ", upstreamModel='" + upstreamModel + '\''
                + ", protocol=" + protocol + '}';
    }
}
