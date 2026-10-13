package tech.qunxu.module.gateway.adapter;

import tech.qunxu.module.gateway.common.enums.ProviderType;
import org.springframework.http.HttpHeaders;

/**
 * 下游供应商鉴权器 SPI：把供应商凭证写入下游请求头（或 URL 签名等）。
 *
 * <p>各供应商鉴权差异（Bearer / 智谱 JWT / 千帆 IAM / 讯飞 WS 签名）由此抽象隔离。契约为同步：
 * 需要异步获取令牌的供应商（如千帆 IAM）应自行缓存令牌，使 {@link #apply} 保持无阻塞。</p>
 */
public interface UpstreamAuthenticator {

    /**
     * 是否支持给定供应商与鉴权类型。
     *
     * @param providerType 供应商标识
     * @param authType     {@code provider.auth_type}（BEARER / ZHIPU_JWT / QIANFAN_IAM / SPARK_WS，可空）
     */
    boolean supports(ProviderType providerType, String authType);

    /** 将鉴权信息写入下游请求头。 */
    void apply(HttpHeaders headers, ChannelRuntime runtime);

    /** 选择优先级，越小越先匹配（专用鉴权器应低于通用 Bearer）。 */
    default int order() {
        return 100;
    }
}
