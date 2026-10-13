package tech.qunxu.module.gateway.common.model;

import java.util.List;

/**
 * 统一流式响应片段（Unified IR）。适配器将下游 SSE/WS 增量翻译为该结构，
 * 上游协议处理器再映射为 OpenAI (chat.completion.chunk) 或 Anthropic (content_block_delta) 事件。
 *
 * @param id            响应 ID
 * @param model         实际模型名
 * @param created       创建时间戳（秒）
 * @param role          首个片段通常携带 role=assistant
 * @param deltaContent  增量正文
 * @param deltaReasoning 增量思维链/推理内容（DeepSeek-R1、讯飞星火 CoT 等）
 * @param toolCallDeltas 增量工具调用（可选）
 * @param finishReason  结束原因（stop/length/tool_calls 等），仅在结束片段出现
 * @param usage         Token 用量，通常在最后一个片段
 * @param done          是否为流结束标记
 */
public record UnifiedStreamChunk(
        String id,
        String model,
        long created,
        String role,
        String deltaContent,
        String deltaReasoning,
        List<UnifiedMessage.ToolCall> toolCallDeltas,
        String finishReason,
        TokenUsage usage,
        boolean done) {

    public static UnifiedStreamChunk role(String id, String model, long created, String role) {
        return new UnifiedStreamChunk(id, model, created, role, null, null, null, null, null, false);
    }

    public static UnifiedStreamChunk content(String id, String model, long created, String delta) {
        return new UnifiedStreamChunk(id, model, created, null, delta, null, null, null, null, false);
    }

    public static UnifiedStreamChunk reasoning(String id, String model, long created, String delta) {
        return new UnifiedStreamChunk(id, model, created, null, null, delta, null, null, null, false);
    }

    public static UnifiedStreamChunk end(String id, String model, long created, String finishReason, TokenUsage usage) {
        return new UnifiedStreamChunk(id, model, created, null, null, null, null, finishReason, usage, true);
    }

    public boolean hasContent() {
        return deltaContent != null && !deltaContent.isEmpty();
    }
}
