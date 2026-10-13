package tech.qunxu.module.gateway.common.enums;

/**
 * 上游（面向客户端）协议类型。网关对外同时提供 OpenAI、Anthropic、Gemini 与 Responses 兼容接口。
 */
public enum ProtocolType {
    /** OpenAI 兼容协议：/v1/chat/completions、/v1/embeddings 等。 */
    OPENAI,
    /** Anthropic 兼容协议：/v1/messages。 */
    ANTHROPIC,
    /** Gemini 协议：/v1beta/models/{model}:generateContent 与 :streamGenerateContent。 */
    GEMINI,
    /** OpenAI Responses 协议：/v1/responses。 */
    RESPONSES;

    public static ProtocolType of(String value) {
        if (value == null) {
            return null;
        }
        for (ProtocolType p : values()) {
            if (p.name().equalsIgnoreCase(value.trim())) {
                return p;
            }
        }
        return null;
    }
}
