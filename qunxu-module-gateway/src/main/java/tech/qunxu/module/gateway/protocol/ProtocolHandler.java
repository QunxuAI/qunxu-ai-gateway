package tech.qunxu.module.gateway.protocol;

import tech.qunxu.module.gateway.common.enums.Capability;
import tech.qunxu.module.gateway.common.enums.ProtocolType;
import tech.qunxu.module.gateway.common.model.UnifiedRequest;
import tech.qunxu.module.gateway.common.model.UnifiedResponse;
import tech.qunxu.module.gateway.common.model.UnifiedStreamChunk;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;

import java.util.Map;

/**
 * 上游协议处理器 SPI：在「客户端协议（OpenAI / Anthropic）」与「Unified IR」之间双向翻译。
 *
 * <p>入向：{@link #parseRequest(JsonNode)} 把客户端 JSON 解析为 {@link UnifiedRequest}；
 * 出向：{@link #renderUnary} 把 {@link UnifiedResponse} 渲染为客户端 JSON，
 * {@link #renderStream} 把 Unified 增量流映射为客户端 SSE 帧（含结束哨兵与流内错误帧）。</p>
 *
 * <p>采用 {@link JsonNode} 入 / {@link Map} 出而非强类型 DTO：网关需对客户端与供应商的字段变体
 * （未知字段、多模态 content 的字符串/数组两态、各家可选参数）保持健壮，避免严格反序列化失败。</p>
 */
public interface ProtocolHandler {

    /** 该处理器面向的上游协议。 */
    ProtocolType protocol();

    /** 客户端请求 JSON → Unified IR（默认按对话能力解析）。 */
    UnifiedRequest parseRequest(JsonNode body);

    /**
     * 按指定能力解析客户端请求 JSON → Unified IR。
     *
     * <p>多模态端点（embeddings / rerank / image / tts）由控制器显式传入 {@link Capability}，
     * 处理器据此选择对应的解析分支。默认实现忽略能力、退回对话解析，故仅支持对话的处理器
     * （如 Anthropic）无需重写。</p>
     */
    default UnifiedRequest parseRequest(JsonNode body, Capability capability) {
        return parseRequest(body);
    }

    /** Unified 非流式响应 → 客户端 JSON（可直接由 ObjectMapper 序列化）。 */
    Map<String, Object> renderUnary(UnifiedResponse response);

    /**
     * Unified 增量流 → 客户端 SSE 帧序列。
     *
     * <p>每个元素为一个完整帧（以 {@code \n\n} 结尾）；实现负责追加协议结束哨兵
     * （OpenAI 的 {@code data: [DONE]}、Anthropic 的 {@code message_stop}），
     * 并通过 {@code onErrorResume} 将流中错误转为 {@link #renderErrorFrame} 后优雅收尾。</p>
     */
    Flux<String> renderStream(Flux<UnifiedStreamChunk> chunks);

    /** 流已开始提交后发生错误时，渲染一个协议相关的错误 SSE 帧。 */
    String renderErrorFrame(Throwable error);
}
