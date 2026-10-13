package tech.qunxu.module.gateway.common.enums;

/**
 * 通道（模型部署）的下游通信协议，决定由哪个 {@code ModelAdapter} 处理。
 */
public enum ChannelProtocol {
    /** OpenAI 兼容 HTTP(SSE)：DeepSeek / 通义兼容模式 / 智谱 / 千帆 V2。 */
    OPENAI_COMPAT,
    /** DashScope 原生协议：重排、通义万相图片（异步任务）、语音等。 */
    DASHSCOPE_NATIVE,
    /** 讯飞星火 WebSocket 协议。 */
    SPARK_WS;

    public static ChannelProtocol of(String value) {
        if (value == null) {
            return null;
        }
        for (ChannelProtocol p : values()) {
            if (p.name().equalsIgnoreCase(value.trim())) {
                return p;
            }
        }
        return OPENAI_COMPAT;
    }
}
