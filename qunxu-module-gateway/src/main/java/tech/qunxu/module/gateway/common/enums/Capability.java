package tech.qunxu.module.gateway.common.enums;

/**
 * 模型能力类型。网关按能力对上游统一建模、对下游选择适配器。
 */
public enum Capability {
    /** 对话补全（含流式）。 */
    CHAT,
    /** 文本向量化。 */
    EMBEDDING,
    /** 重排序。 */
    RERANK,
    /** 语音合成（文本转语音）。 */
    TTS,
    /** 语音识别（语音转文本）。 */
    STT,
    /** 图片生成。 */
    IMAGE;

    public static Capability of(String value) {
        if (value == null) {
            return null;
        }
        for (Capability c : values()) {
            if (c.name().equalsIgnoreCase(value.trim())) {
                return c;
            }
        }
        return null;
    }
}
