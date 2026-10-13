package tech.qunxu.module.gateway.adapter;

import tech.qunxu.module.gateway.common.enums.Capability;
import tech.qunxu.module.gateway.common.enums.ChannelProtocol;
import tech.qunxu.module.gateway.common.exception.ErrorCode;
import tech.qunxu.module.gateway.common.exception.GatewayException;
import tech.qunxu.module.gateway.common.model.GenerationParams;
import tech.qunxu.module.gateway.common.model.TokenUsage;
import tech.qunxu.module.gateway.common.model.UnifiedMessage;
import tech.qunxu.module.gateway.common.model.UnifiedRequest;
import tech.qunxu.module.gateway.common.model.UnifiedResponse;
import tech.qunxu.module.gateway.common.model.UnifiedStreamChunk;
import tech.qunxu.module.gateway.health.HealthResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 讯飞星火 WebSocket 适配器：以 HMAC-SHA256 签名 URL 建立 WS，单帧下发对话请求，聚合服务端多帧增量。
 *
 * <p>入向把 Unified chat 载荷翻译为星火帧（{@code header.app_id + parameter.chat + payload.message.text}）；
 * 出向逐帧解析 {@code payload.choices.text[].content} 为增量，{@code header.status==2} 为终帧并携带 usage。
 * 星火鉴权错误码（如 10401）归一为 {@link ErrorCode#AUTH_EXPIRED}，与健康自愈体系对齐。</p>
 */
@Component
public class SparkWebSocketAdapter implements ModelAdapter {

    private static final Logger log = LoggerFactory.getLogger(SparkWebSocketAdapter.class);

    private static final String DEFAULT_WS_URL = "wss://spark-api.xf-yun.com/v4.0/chat";
    private static final String DEFAULT_DOMAIN = "general";

    private final WebSocketClient webSocketClient;
    private final ObjectMapper objectMapper;

    public SparkWebSocketAdapter(@Qualifier("gatewayWebSocketClient") WebSocketClient webSocketClient,
                                 ObjectMapper objectMapper) {
        this.webSocketClient = webSocketClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public ChannelProtocol protocol() {
        return ChannelProtocol.SPARK_WS;
    }

    @Override
    public Set<Capability> capabilities() {
        return Set.of(Capability.CHAT);
    }

    @Override
    public Flux<UnifiedStreamChunk> invokeStream(UnifiedRequest request, ChannelRuntime runtime) {
        return Flux.defer(() -> {
            if (request == null || request.capability() != Capability.CHAT || request.chat() == null) {
                return Flux.error(new GatewayException(ErrorCode.NOT_IMPLEMENTED,
                        "讯飞星火适配器仅支持 chat 能力"));
            }
            ProviderCredentials credentials = runtime.getCredentials();
            String apiKey = credentials.apiKey();
            String apiSecret = credentials.apiSecret();
            String appId = credentials.appId();
            if (isBlank(apiKey) || isBlank(apiSecret) || isBlank(appId)) {
                return Flux.error(new GatewayException(ErrorCode.PROVIDER_ERROR,
                        "讯飞凭证不完整（需 appId/apiKey/apiSecret）"));
            }
            String url = SparkSignature.buildUrl(wsUrl(runtime), apiKey, apiSecret, Instant.now());
            URI uri = URI.create(url);
            String frame = buildRequestFrame(request, runtime, appId);
            String model = runtime.getUpstreamModel();
            Sinks.Many<UnifiedStreamChunk> sink = Sinks.many().unicast().onBackpressureBuffer();

            Disposable disposable = webSocketClient.execute(uri, session -> session
                            .send(Mono.just(session.textMessage(frame)))
                            .thenMany(session.receive()
                                    .map(WebSocketMessage::getPayloadAsText)
                                    .takeUntil(this::isFinalFrame))
                            .doOnNext(text -> parseFrame(text, model).forEach(sink::tryEmitNext))
                            .then())
                    .subscribe(
                            unused -> {
                            },
                            err -> sink.tryEmitError(AdapterErrorMapper.map(err, runtime.getProviderCode())),
                            sink::tryEmitComplete);

            return sink.asFlux().doFinally(signal -> disposable.dispose());
        });
    }

    @Override
    public Mono<UnifiedResponse> invokeUnary(UnifiedRequest request, ChannelRuntime runtime) {
        return invokeStream(request, runtime)
                .collectList()
                .map(chunks -> aggregate(chunks, runtime));
    }

    /**
     * 主动健康探测：以最小对话（1 个 token）走一次完整 WS 往返，成功即上线，
     * 失败按归一化错误码下线（星火鉴权码 → {@link ErrorCode#AUTH_EXPIRED}）。
     */
    @Override
    public Mono<HealthResult> probe(ChannelRuntime runtime) {
        long start = System.nanoTime();
        GenerationParams params = new GenerationParams(null, null, 1, null, null, null, null, null, null, Map.of());
        UnifiedRequest.ChatPayload chat = new UnifiedRequest.ChatPayload(
                List.of(UnifiedMessage.user("ping")), params, null, null);
        return invokeUnary(UnifiedRequest.chat("probe", false, chat), runtime)
                .map(response -> HealthResult.up((System.nanoTime() - start) / 1_000_000L))
                .onErrorResume(e -> {
                    GatewayException ge = e instanceof GatewayException g ? g
                            : AdapterErrorMapper.map(e, runtime.getProviderCode());
                    String detail = ge.getProviderDetail() != null ? ge.getProviderDetail() : ge.getMessage();
                    return Mono.just(HealthResult.down(detail, ge.getErrorCode()));
                });
    }

    // ---- 帧构建（入向） ----

    String buildRequestFrame(UnifiedRequest request, ChannelRuntime runtime, String appId) {
        UnifiedRequest.ChatPayload chat = request.chat();
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("app_id", appId);
        header.put("uid", UUID.randomUUID().toString().substring(0, 8));

        Map<String, Object> chatParam = new LinkedHashMap<>();
        chatParam.put("domain", domain(runtime));
        GenerationParams params = chat.params();
        if (params != null) {
            if (params.temperature() != null) {
                chatParam.put("temperature", params.temperature());
            }
            if (params.maxTokens() != null) {
                chatParam.put("max_tokens", params.maxTokens());
            }
            Object topK = params.extra() != null ? params.extra().get("top_k") : null;
            if (topK != null) {
                chatParam.put("top_k", topK);
            }
        }

        List<Map<String, Object>> text = new ArrayList<>();
        if (chat.messages() != null) {
            for (UnifiedMessage message : chat.messages()) {
                if (message == null) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("role", normalizeRole(message.role()));
                String content = message.textContent();
                item.put("content", content != null ? content : "");
                text.add(item);
            }
        }

        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("header", header);
        frame.put("parameter", Map.of("chat", chatParam));
        frame.put("payload", Map.of("message", Map.of("text", text)));
        return objectMapper.writeValueAsString(frame);
    }

    private String normalizeRole(String role) {
        if (role == null) {
            return "user";
        }
        return switch (role.toLowerCase()) {
            case "system" -> "system";
            case "assistant" -> "assistant";
            default -> "user";
        };
    }

    // ---- 帧解析（出向） ----

    private boolean isFinalFrame(String text) {
        try {
            JsonNode header = objectMapper.readTree(text).path("header");
            return header.path("status").asInt(0) == 2 || header.path("code").asInt(0) != 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * 解析一帧星火响应为 0..N 个 Unified 增量（包可见以便单元测试）。
     *
     * <p>{@code header.code != 0} 时抛出携带归一化 {@link ErrorCode} 的 {@link GatewayException}
     * （鉴权类码 → {@link ErrorCode#AUTH_EXPIRED}），由上层反应式链转为 {@code onError}。</p>
     */
    List<UnifiedStreamChunk> parseFrame(String text, String model) {
        JsonNode root = objectMapper.readTree(text);
        JsonNode header = root.path("header");
        int code = header.path("code").asInt(0);
        if (code != 0) {
            String message = header.path("message").asText("讯飞星火返回错误");
            ErrorCode errorCode = isAuthCode(code) ? ErrorCode.AUTH_EXPIRED : ErrorCode.PROVIDER_ERROR;
            throw new GatewayException(errorCode, "讯飞星火错误 code=" + code + ": " + message);
        }
        String sid = textOrNull(header, "sid");
        int status = header.path("status").asInt(0);
        long created = Instant.now().getEpochSecond();
        List<UnifiedStreamChunk> chunks = new ArrayList<>();

        JsonNode texts = root.path("payload").path("choices").path("text");
        if (texts.isArray()) {
            for (JsonNode t : texts) {
                String content = textOrNull(t, "content");
                if (content != null && !content.isEmpty()) {
                    chunks.add(UnifiedStreamChunk.content(sid, model, created, content));
                }
            }
        }
        if (status == 2) {
            TokenUsage usage = parseSparkUsage(root.path("payload").path("usage"));
            chunks.add(UnifiedStreamChunk.end(sid, model, created, "stop", usage));
        }
        return chunks;
    }

    /** 讯飞鉴权/授权类错误码，映射为 AUTH_EXPIRED 以便健康自愈区分处理。 */
    private boolean isAuthCode(int code) {
        return code == 10401 || code == 10105 || code == 10107 || code == 10160;
    }

    private TokenUsage parseSparkUsage(JsonNode usage) {
        JsonNode text = usage.path("text");
        if (!text.isObject()) {
            return null;
        }
        Integer prompt = intOrNull(text, "prompt_tokens");
        Integer completion = intOrNull(text, "completion_tokens");
        Integer total = intOrNull(text, "total_tokens");
        if (prompt == null && completion == null && total == null) {
            return null;
        }
        if (total == null) {
            return TokenUsage.of(prompt, completion);
        }
        return new TokenUsage(prompt == null ? 0 : prompt, completion == null ? 0 : completion, total);
    }

    private UnifiedResponse aggregate(List<UnifiedStreamChunk> chunks, ChannelRuntime runtime) {
        StringBuilder content = new StringBuilder();
        String id = null;
        String model = runtime.getUpstreamModel();
        long created = Instant.now().getEpochSecond();
        TokenUsage usage = null;
        String finishReason = "stop";
        for (UnifiedStreamChunk chunk : chunks) {
            if (chunk.id() != null) {
                id = chunk.id();
            }
            if (chunk.model() != null) {
                model = chunk.model();
            }
            if (chunk.deltaContent() != null) {
                content.append(chunk.deltaContent());
            }
            if (chunk.usage() != null) {
                usage = chunk.usage();
            }
            if (chunk.finishReason() != null) {
                finishReason = chunk.finishReason();
            }
        }
        return UnifiedResponse.chat(id, model, created, finishReason, content.toString(), usage);
    }

    // ---- 配置辅助 ----

    private String wsUrl(ChannelRuntime runtime) {
        Object cfg = runtime.getConfig().get("wsUrl");
        if (cfg instanceof String s && !s.isBlank()) {
            return s.trim();
        }
        String base = runtime.getBaseUrl();
        return (base != null && !base.isBlank()) ? base.trim() : DEFAULT_WS_URL;
    }

    private String domain(ChannelRuntime runtime) {
        Object cfg = runtime.getConfig().get("domain");
        return cfg instanceof String s && !s.isBlank() ? s.trim() : DEFAULT_DOMAIN;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private String textOrNull(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return (value == null || value.isNull()) ? null : value.asText();
    }

    private Integer intOrNull(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return (value == null || !value.isNumber()) ? null : value.asInt();
    }
}
