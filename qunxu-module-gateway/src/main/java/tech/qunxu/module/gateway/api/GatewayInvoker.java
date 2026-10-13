package tech.qunxu.module.gateway.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tech.qunxu.module.gateway.common.enums.Capability;
import tech.qunxu.module.gateway.common.exception.ErrorCode;
import tech.qunxu.module.gateway.common.exception.GatewayException;
import tech.qunxu.module.gateway.common.model.UnifiedRequest;
import tech.qunxu.module.gateway.common.model.UnifiedResponse;
import tech.qunxu.module.gateway.common.model.UnifiedStreamChunk;
import tech.qunxu.module.gateway.observability.TraceIdFilter;
import tech.qunxu.module.gateway.pipeline.GatewayPipeline;
import tech.qunxu.module.gateway.pipeline.RequestContext;
import tech.qunxu.module.gateway.protocol.ProtocolHandler;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * 网关调用编排器：上游控制器共用的「解析请求 → 构建上下文 → 执行流水线 → 写回响应」骨架。
 *
 * <p>把协议无关的编排集中于此，控制器只需提供 {@link ProtocolHandler}、路径与原始请求体，从而保持极薄。
 * 输出按是否流式二选一：非流式渲染 JSON 一次性写回；流式以 {@code text/event-stream} 逐帧 flush，
 * 由 {@link ProtocolHandler#renderStream} 负责协议相关的 SSE 帧与结束哨兵。</p>
 *
 * <p>由 WebFlux 版（{@code ServerWebExchange}）迁移到 Servlet（{@code HttpServletRequest/Response}）：
 * 流水线仍为响应式（Mono/Flux），写回改为 Servlet {@link OutputStream}；控制器返回 {@code Mono<Void>}
 * 交由 Spring MVC 的响应式返回值处理。</p>
 */
@Component
public class GatewayInvoker {

    private final GatewayPipeline pipeline;
    private final ObjectMapper objectMapper;

    public GatewayInvoker(GatewayPipeline pipeline, ObjectMapper objectMapper) {
        this.pipeline = pipeline;
        this.objectMapper = objectMapper;
    }

    /**
     * 处理一次上游调用。
     *
     * @param request   当前请求
     * @param response  当前响应
     * @param handler   协议处理器（决定入向解析与出向渲染）
     * @param path      请求路径（用于审计/日志）
     * @param endpoint  端点标识（如 chat.completions）
     * @param rawBody   原始请求体 JSON 字符串
     */
    public Mono<Void> invoke(HttpServletRequest request, HttpServletResponse response, ProtocolHandler handler,
                             String path, String endpoint, String rawBody) {
        return invoke(request, response, handler, path, endpoint, Capability.CHAT, rawBody);
    }

    /**
     * 处理一次上游调用（指定能力）。
     *
     * <p>多模态端点（embeddings / rerank / image / tts）传入各自的 {@link Capability}，
     * 由 {@link ProtocolHandler#parseRequest(JsonNode, Capability)} 选择解析分支。</p>
     */
    public Mono<Void> invoke(HttpServletRequest request, HttpServletResponse response, ProtocolHandler handler,
                             String path, String endpoint, Capability capability, String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return Mono.error(new GatewayException(ErrorCode.BAD_REQUEST, "请求体为空"));
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(rawBody);
        } catch (RuntimeException e) {
            return Mono.error(new GatewayException(ErrorCode.BAD_REQUEST, "请求体不是合法 JSON"));
        }
        if (node == null || !node.isObject()) {
            return Mono.error(new GatewayException(ErrorCode.BAD_REQUEST, "请求体必须为 JSON 对象"));
        }

        UnifiedRequest unified;
        try {
            unified = handler.parseRequest(node, capability);
        } catch (GatewayException e) {
            return Mono.error(e);
        } catch (RuntimeException e) {
            return Mono.error(new GatewayException(ErrorCode.BAD_REQUEST, "请求解析失败", e));
        }
        return run(request, response, handler, path, endpoint, unified, rawBody);
    }

    /**
     * 处理一次上游调用（请求体已由控制器解析构造）。
     *
     * <p>适用于非 JSON 入口，如 STT 的 {@code multipart/form-data} 音频上传：控制器自行读出
     * 音频字节并构造 {@link UnifiedRequest}，{@code rawBodyForAudit} 仅用于审计记录（可为概要文本）。</p>
     */
    public Mono<Void> invokeWithRequest(HttpServletRequest request, HttpServletResponse response,
                                        ProtocolHandler handler, String path, String endpoint,
                                        UnifiedRequest unified, String rawBodyForAudit) {
        if (unified == null) {
            return Mono.error(new GatewayException(ErrorCode.BAD_REQUEST, "请求体为空"));
        }
        return run(request, response, handler, path, endpoint, unified, rawBodyForAudit);
    }

    private Mono<Void> run(HttpServletRequest request, HttpServletResponse response, ProtocolHandler handler,
                           String path, String endpoint, UnifiedRequest unified, String rawBodyForAudit) {
        String traceId = (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTR);
        String method = request.getMethod() != null ? request.getMethod() : "POST";
        RequestContext ctx = RequestContext.create(handler.protocol(), method, path, endpoint, traceId, unified);
        ctx.setAttribute(RequestContext.ATTR_REQUEST, request);
        ctx.setAttribute(RequestContext.ATTR_RESPONSE, response);
        if (rawBodyForAudit != null) {
            ctx.setRawRequestBody(rawBodyForAudit);
        }
        return pipeline.execute(ctx, Mono.defer(() -> write(response, ctx, handler)));
    }

    private Mono<Void> write(HttpServletResponse response, RequestContext ctx, ProtocolHandler handler) {
        Flux<UnifiedStreamChunk> stream = ctx.getStreamResult();
        if (stream != null) {
            response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            Flux<String> frames = handler.renderStream(stream);
            return frames.concatMap(frame -> Mono.fromRunnable(() -> writeBytes(response, frame.getBytes(StandardCharsets.UTF_8))))
                    .then();
        }

        UnifiedResponse result = ctx.getResponse();
        if (result == null) {
            return Mono.error(new GatewayException(ErrorCode.PROVIDER_ERROR, "下游未返回有效响应"));
        }

        // TTS：二进制音频直接回写，Content-Type 由下游返回（默认 audio/mpeg）
        UnifiedResponse.AudioResult audio = result.audio();
        if (result.capability() == Capability.TTS && audio != null && audio.audio() != null && audio.audio().length > 0) {
            response.setContentType(resolveAudioMediaType(audio.contentType()).toString());
            byte[] bytes = audio.audio();
            return Mono.fromRunnable(() -> writeBytes(response, bytes));
        }

        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        byte[] bytes = objectMapper.writeValueAsBytes(handler.renderUnary(result));
        return Mono.fromRunnable(() -> writeBytes(response, bytes));
    }

    /** 写响应体并 flush。串行调用（SSE 场景由 concatMap 保证顺序）。 */
    private void writeBytes(HttpServletResponse response, byte[] bytes) {
        try {
            OutputStream out = response.getOutputStream();
            out.write(bytes);
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private MediaType resolveAudioMediaType(String contentType) {
        if (contentType != null && !contentType.isBlank()) {
            try {
                return MediaType.parseMediaType(contentType);
            } catch (RuntimeException ignored) {
                // 下游返回非法 Content-Type 时退回默认
            }
        }
        return MediaType.parseMediaType("audio/mpeg");
    }
}
