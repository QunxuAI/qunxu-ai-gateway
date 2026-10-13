package tech.qunxu.module.gateway.common.model;

import tech.qunxu.module.gateway.common.enums.Capability;

import java.util.List;

/**
 * 统一响应中间表示（非流式）。由供应商适配器将原生响应翻译而来，
 * 再由上游协议处理器转换为客户端协议格式（OpenAI / Anthropic）。
 */
public record UnifiedResponse(
        Capability capability,
        String id,
        String model,
        long created,
        List<Choice> choices,
        List<Embedding> embeddings,
        List<RerankResult> rerankResults,
        List<ImageResult> images,
        AudioResult audio,
        TokenUsage usage) {

    /** 对话选项。 */
    public record Choice(int index, String finishReason, UnifiedMessage message) {
        public String text() {
            return message == null ? "" : message.textContent();
        }
    }

    /** 向量结果。 */
    public record Embedding(int index, List<Float> embedding) {
    }

    /** 重排结果。 */
    public record RerankResult(int index, Double relevanceScore, String document) {
    }

    /** 图片结果：URL 或 base64 二选一。 */
    public record ImageResult(String url, String b64Json, String revisedPrompt) {
    }

    /** 音频结果：TTS 返回音频字节，STT 返回识别文本。 */
    public record AudioResult(byte[] audio, String text, String contentType) {
    }

    /** 便捷构造：单条对话响应。 */
    public static UnifiedResponse chat(String id, String model, long created, String finishReason,
                                       String content, TokenUsage usage) {
        Choice choice = new Choice(0, finishReason, UnifiedMessage.assistant(content));
        return new UnifiedResponse(Capability.CHAT, id, model, created, List.of(choice),
                null, null, null, null, usage);
    }
}
