package tech.qunxu.module.gateway.common.model;

import tech.qunxu.module.gateway.common.enums.Capability;

import java.util.List;
import java.util.Map;

/**
 * 统一请求中间表示（Unified IR）。
 *
 * <p>由上游协议处理器（OpenAI / Anthropic）解析客户端请求后构建，向下被供应商适配器翻译为原生请求。
 * 采用「能力判别 + 分能力载荷」的结构，避免在流水线中做大量类型分支。</p>
 */
public record UnifiedRequest(
        Capability capability,
        String model,
        boolean stream,
        ChatPayload chat,
        EmbeddingPayload embedding,
        RerankPayload rerank,
        TtsPayload tts,
        SttPayload stt,
        ImagePayload image,
        Map<String, Object> extra) {

    public static UnifiedRequest chat(String model, boolean stream, ChatPayload chat) {
        return new UnifiedRequest(Capability.CHAT, model, stream, chat, null, null, null, null, null, Map.of());
    }

    public static UnifiedRequest embedding(String model, EmbeddingPayload payload) {
        return new UnifiedRequest(Capability.EMBEDDING, model, false, null, payload, null, null, null, null, Map.of());
    }

    public static UnifiedRequest rerank(String model, RerankPayload payload) {
        return new UnifiedRequest(Capability.RERANK, model, false, null, null, payload, null, null, null, Map.of());
    }

    public static UnifiedRequest tts(String model, TtsPayload payload) {
        return new UnifiedRequest(Capability.TTS, model, false, null, null, null, payload, null, null, Map.of());
    }

    public static UnifiedRequest stt(String model, SttPayload payload) {
        return new UnifiedRequest(Capability.STT, model, false, null, null, null, null, payload, null, Map.of());
    }

    public static UnifiedRequest image(String model, boolean stream, ImagePayload payload) {
        return new UnifiedRequest(Capability.IMAGE, model, stream, null, null, null, null, null, payload, Map.of());
    }

    /** 对话载荷。 */
    public record ChatPayload(
            List<UnifiedMessage> messages,
            GenerationParams params,
            List<ToolDefinition> tools,
            String toolChoice) {

        /** 汇总所有消息文本，用于安全围栏与内容审计；协议状态消息（思考块/续传数据）排除在外。 */
        public String fullText() {
            if (messages == null) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (UnifiedMessage m : messages) {
                if (m != null && !m.protocolProtected()) {
                    sb.append(m.textContent()).append('\n');
                }
            }
            return sb.toString();
        }
    }

    /** 工具定义（function calling schema）。 */
    public record ToolDefinition(String type, FunctionDef function) {
        public record FunctionDef(String name, String description, Map<String, Object> parameters) {
        }
    }

    /** 向量化载荷。 */
    public record EmbeddingPayload(List<String> input, String encodingFormat, Integer dimensions) {
    }

    /** 重排载荷。 */
    public record RerankPayload(String query, List<String> documents, Integer topN, Boolean returnDocuments) {
    }

    /** 语音合成载荷。 */
    public record TtsPayload(String input, String voice, String responseFormat, Double speed) {
    }

    /** 语音识别载荷。 */
    public record SttPayload(byte[] audio, String filename, String language, String responseFormat) {
    }

    /** 图片生成载荷。 */
    public record ImagePayload(String prompt, String size, Integer n, String responseFormat,
                               String quality, String style) {
    }
}
