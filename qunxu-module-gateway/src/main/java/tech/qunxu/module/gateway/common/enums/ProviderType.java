package tech.qunxu.module.gateway.common.enums;

/**
 * 下游供应商标识。用于选择鉴权策略、默认 base_url 与适配器行为。
 *
 * <p>国内厂商中 DeepSeek / 通义千问(兼容模式) / 智谱 / 百度千帆 V2 均提供 OpenAI 兼容端点，
 * 差异主要在鉴权方式；讯飞星火为 WebSocket + HMAC-SHA256 签名，需独立适配器。</p>
 */
public enum ProviderType {
    /** DeepSeek：OpenAI 兼容，Bearer API Key。 */
    DEEPSEEK,
    /** 阿里云百炼 / 通义千问 DashScope：兼容模式 + 原生模式（重排/图片/语音）。 */
    DASHSCOPE,
    /** 智谱 GLM：OpenAI 兼容，API Key 形如 {id}.{secret}，需生成 HS256 JWT。 */
    ZHIPU,
    /** 百度千帆 / 文心一言：V2 接口 OpenAI 兼容，IAM Bearer Token。 */
    QIANFAN,
    /** 讯飞星火：WebSocket 协议 + HMAC-SHA256 URL 签名。 */
    SPARK,
    /** OpenAI（用于测试或直连）。 */
    OPENAI,
    /** 任意 OpenAI 兼容的自定义供应商。 */
    CUSTOM;

    public static ProviderType of(String value) {
        if (value == null) {
            return null;
        }
        for (ProviderType p : values()) {
            if (p.name().equalsIgnoreCase(value.trim())) {
                return p;
            }
        }
        return null;
    }
}
