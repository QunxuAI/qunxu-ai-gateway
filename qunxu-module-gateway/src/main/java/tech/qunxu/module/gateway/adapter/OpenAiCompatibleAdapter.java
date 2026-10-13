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
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;

/**
 * OpenAI 兼容适配器：面向 DeepSeek、通义千问（DashScope 兼容模式）、智谱、百度千帆 V2 及任意 OpenAI 兼容供应商。
 *
 * <p>把 Unified IR 翻译为 OpenAI 风格 {@code /chat/completions} 请求（非流式 JSON 与流式 SSE 两种），
 * 再把下游响应翻译回 Unified IR。各家差异（base_url、鉴权、模型名）由 {@link ChannelRuntime} 与
 * {@link UpstreamAuthenticator} 承载，本类只关心协议结构。</p>
 *
 * <p>M2 覆盖 {@link Capability#CHAT}；嵌入/重排/图片/语音等能力在 M6 扩展。</p>
 */
@Component
public class OpenAiCompatibleAdapter implements ModelAdapter {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleAdapter.class);
    private static final String DEFAULT_CHAT_PATH = "/chat/completions";
    private static final String DEFAULT_EMBEDDING_PATH = "/embeddings";
    private static final String DEFAULT_SPEECH_PATH = "/audio/speech";
    private static final String DEFAULT_TRANSCRIPTION_PATH = "/audio/transcriptions";
    private static final String DEFAULT_MODELS_PATH = "/models";
    private static final String DONE_SENTINEL = "[DONE]";
    private static final int MAX_DETAIL = 512;
    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE_STRING =
            new ParameterizedTypeReference<>() {
            };

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final UpstreamAuthenticatorResolver authResolver;

    public OpenAiCompatibleAdapter(@Qualifier("gatewayWebClient") WebClient webClient,
                                   ObjectMapper objectMapper,
                                   UpstreamAuthenticatorResolver authResolver) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
        this.authResolver = authResolver;
    }

    @Override
    public ChannelProtocol protocol() {
        return ChannelProtocol.OPENAI_COMPAT;
    }

    @Override
    public Set<Capability> capabilities() {
        return Set.of(Capability.CHAT, Capability.EMBEDDING, Capability.TTS, Capability.STT);
    }

    @Override
    public Mono<UnifiedResponse> invokeUnary(UnifiedRequest request, ChannelRuntime runtime) {
        Capability capability = request != null ? request.capability() : null;
        if (capability == null) {
            return Mono.error(new GatewayException(ErrorCode.BAD_REQUEST, "请求能力为空"));
        }
        return switch (capability) {
            case CHAT -> invokeChatUnary(request, runtime);
            case EMBEDDING -> invokeEmbedding(request, runtime);
            case TTS -> invokeTts(request, runtime);
            case STT -> invokeStt(request, runtime);
            default -> Mono.error(new GatewayException(ErrorCode.NOT_IMPLEMENTED,
                    "OpenAI 兼容适配器不支持能力: " + capability));
        };
    }

    private Mono<UnifiedResponse> invokeChatUnary(UnifiedRequest request, ChannelRuntime runtime) {
        if (!isChat(request)) {
            return Mono.error(new GatewayException(ErrorCode.BAD_REQUEST, "chat 请求缺少 messages 载荷"));
        }
        String url = runtime.resolveUrl(chatPath(runtime));
        Map<String, Object> body = buildChatBody(request, runtime, false);
        return webClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> applyAuth(headers, runtime))
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .map(this::parseUnaryBody)
                .onErrorMap(e -> mapError(e, runtime));
    }

    /** 嵌入：{@code POST /embeddings}，解析 {@code data[].embedding} 为向量列表。 */
    private Mono<UnifiedResponse> invokeEmbedding(UnifiedRequest request, ChannelRuntime runtime) {
        UnifiedRequest.EmbeddingPayload payload = request.embedding();
        if (payload == null || payload.input() == null || payload.input().isEmpty()) {
            return Mono.error(new GatewayException(ErrorCode.BAD_REQUEST, "embedding 请求缺少 input"));
        }
        String url = runtime.resolveUrl(pathFromConfig(runtime, "embeddingPath", DEFAULT_EMBEDDING_PATH));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", runtime.getUpstreamModel());
        body.put("input", payload.input());
        putIfNotNull(body, "encoding_format", payload.encodingFormat());
        putIfNotNull(body, "dimensions", payload.dimensions());
        return webClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> applyAuth(headers, runtime))
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .map(resp -> parseEmbeddingBody(resp, runtime))
                .onErrorMap(e -> mapError(e, runtime));
    }

    /** 语音合成（TTS）：{@code POST /audio/speech}，返回二进制音频字节 + Content-Type。 */
    private Mono<UnifiedResponse> invokeTts(UnifiedRequest request, ChannelRuntime runtime) {
        UnifiedRequest.TtsPayload payload = request.tts();
        if (payload == null || payload.input() == null || payload.input().isBlank()) {
            return Mono.error(new GatewayException(ErrorCode.BAD_REQUEST, "tts 请求缺少 input"));
        }
        String url = runtime.resolveUrl(pathFromConfig(runtime, "speechPath", DEFAULT_SPEECH_PATH));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", runtime.getUpstreamModel());
        body.put("input", payload.input());
        putIfNotNull(body, "voice", payload.voice());
        putIfNotNull(body, "response_format", payload.responseFormat());
        putIfNotNull(body, "speed", payload.speed());
        return webClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> applyAuth(headers, runtime))
                .bodyValue(body)
                .retrieve()
                .toEntity(byte[].class)
                .map(entity -> {
                    byte[] audio = entity.getBody() != null ? entity.getBody() : new byte[0];
                    MediaType contentType = entity.getHeaders().getContentType();
                    UnifiedResponse.AudioResult result = new UnifiedResponse.AudioResult(
                            audio, null, contentType != null ? contentType.toString() : "audio/mpeg");
                    return new UnifiedResponse(Capability.TTS, null, runtime.getUpstreamModel(),
                            Instant.now().getEpochSecond(), null, null, null, null, result, null);
                })
                .onErrorMap(e -> mapError(e, runtime));
    }

    /** 语音识别（STT）：{@code POST /audio/transcriptions} multipart 上传音频，解析 {@code text}。 */
    private Mono<UnifiedResponse> invokeStt(UnifiedRequest request, ChannelRuntime runtime) {
        UnifiedRequest.SttPayload payload = request.stt();
        if (payload == null || payload.audio() == null || payload.audio().length == 0) {
            return Mono.error(new GatewayException(ErrorCode.BAD_REQUEST, "stt 请求缺少音频数据"));
        }
        String url = runtime.resolveUrl(pathFromConfig(runtime, "transcriptionPath", DEFAULT_TRANSCRIPTION_PATH));
        String filename = payload.filename() != null && !payload.filename().isBlank() ? payload.filename() : "audio";
        MultipartBodyBuilder multipart = new MultipartBodyBuilder();
        multipart.part("model", runtime.getUpstreamModel());
        if (payload.language() != null) {
            multipart.part("language", payload.language());
        }
        multipart.part("response_format", payload.responseFormat() != null ? payload.responseFormat() : "json");
        ByteArrayResource fileResource = new ByteArrayResource(payload.audio()) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        multipart.part("file", fileResource).contentType(guessAudioMediaType(filename));
        return webClient.post()
                .uri(url)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> applyAuth(headers, runtime))
                .body(BodyInserters.fromMultipartData(multipart.build()))
                .retrieve()
                .bodyToMono(String.class)
                .map(resp -> parseSttBody(resp, runtime))
                .onErrorMap(e -> mapError(e, runtime));
    }

    @Override
    public Flux<UnifiedStreamChunk> invokeStream(UnifiedRequest request, ChannelRuntime runtime) {
        if (!isChat(request)) {
            return Flux.error(new GatewayException(ErrorCode.NOT_IMPLEMENTED,
                    "OpenAI 兼容适配器当前仅支持 chat 能力: " + (request == null ? null : request.capability())));
        }
        String url = runtime.resolveUrl(chatPath(runtime));
        Map<String, Object> body = buildChatBody(request, runtime, true);
        return webClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .headers(headers -> applyAuth(headers, runtime))
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(SSE_STRING)
                .mapNotNull(ServerSentEvent::data)
                .map(String::trim)
                .filter(data -> !data.isEmpty())
                .takeWhile(data -> !DONE_SENTINEL.equals(data))
                .mapNotNull(this::parseChunkBody)
                .onErrorMap(e -> mapError(e, runtime));
    }

    /**
     * 主动健康探测：发送一次最小 chat completion（单条 user 消息、{@code max_tokens=1}、非流式），
     * 测量往返时延。成功返回 {@link HealthResult#up(long)}；失败复用 {@link #mapError} 归一化错误码
     * （401/403 → {@link ErrorCode#AUTH_EXPIRED}），返回 {@link HealthResult#down(String, ErrorCode)}。
     */
    @Override
    public Mono<HealthResult> probe(ChannelRuntime runtime) {
        return probe(runtime, null);
    }

    /**
     * 能力感知的主动健康探测：按逻辑模型能力选择探测方式，避免图片/语音/向量等模型被
     * 统一发送 chat 请求而必然探测失败。
     *
     * <ul>
     *   <li>CHAT（默认）→ 最小 chat completion，见 {@link #probeChat(ChannelRuntime)}；</li>
     *   <li>EMBEDDING → {@code POST /embeddings}（一次 "ping" 输入）；</li>
     *   <li>IMAGE / TTS / STT / RERANK → {@code GET /models} 存活探测：这些能力的真实调用端点
     *       各家差异大（文生图有 /images/generations 与各厂商原生 API 之分、TTS 依赖各家 voice 参数），
     *       统一退化为「连通性 + 凭证有效性」检查，2xx 即视为健康。</li>
     * </ul>
     */
    @Override
    public Mono<HealthResult> probe(ChannelRuntime runtime, Capability capability) {
        Capability cap = capability != null ? capability : Capability.CHAT;
        return switch (cap) {
            case EMBEDDING -> probeEmbedding(runtime);
            case IMAGE, TTS, STT, RERANK -> probeLiveness(runtime);
            default -> probeChat(runtime);
        };
    }

    /** 最小探测请求：尽量降低探测成本（1 个 token、非流式）。 */
    private UnifiedRequest probeRequest() {
        GenerationParams params = new GenerationParams(null, null, 1, null, null, null, null, null, null, Map.of());
        UnifiedRequest.ChatPayload chat = new UnifiedRequest.ChatPayload(
                List.of(UnifiedMessage.user("ping")), params, null, null);
        return UnifiedRequest.chat("probe", false, chat);
    }

    /** CHAT 探测：发送一次最小 chat completion（单条 user 消息、max_tokens=1、非流式）。 */
    private Mono<HealthResult> probeChat(ChannelRuntime runtime) {
        long start = System.nanoTime();
        return invokeUnary(probeRequest(), runtime)
                .map(response -> HealthResult.up((System.nanoTime() - start) / 1_000_000L))
                .onErrorResume(e -> Mono.just(toDown(e, runtime)));
    }

    /** EMBEDDING 探测：{@code POST /embeddings}（input="ping"），2xx 即健康。 */
    private Mono<HealthResult> probeEmbedding(ChannelRuntime runtime) {
        long start = System.nanoTime();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", runtime.getUpstreamModel());
        body.put("input", "ping");
        return postJsonProbe(runtime, pathFromConfig(runtime, "embeddingPath", DEFAULT_EMBEDDING_PATH), body)
                .map(resp -> HealthResult.up((System.nanoTime() - start) / 1_000_000L))
                .onErrorResume(e -> Mono.just(toDown(e, runtime)));
    }

    /** IMAGE/TTS/STT/RERANK 存活探测：{@code GET /models}，2xx 即健康（见 {@link #probe(ChannelRuntime, Capability)} 说明）。 */
    private Mono<HealthResult> probeLiveness(ChannelRuntime runtime) {
        long start = System.nanoTime();
        String url = runtime.resolveUrl(pathFromConfig(runtime, "modelsPath", DEFAULT_MODELS_PATH));
        return webClient.get()
                .uri(url)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> applyAuth(headers, runtime))
                .retrieve()
                .bodyToMono(String.class)
                .map(resp -> HealthResult.up((System.nanoTime() - start) / 1_000_000L))
                .onErrorResume(e -> Mono.just(toDown(e, runtime)));
    }

    /** 发送一次最小 JSON POST 探测请求（仅用于探测，不解析响应体）。 */
    private Mono<String> postJsonProbe(ChannelRuntime runtime, String path, Map<String, Object> body) {
        return webClient.post()
                .uri(runtime.resolveUrl(path))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> applyAuth(headers, runtime))
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class);
    }

    /** 把探测异常归一化为 {@link HealthResult#down}，401/403 复用 {@link #mapError} 映射为凭证过期。 */
    private HealthResult toDown(Throwable e, ChannelRuntime runtime) {
        GatewayException ge = mapError(e, runtime);
        String detail = ge.getProviderDetail() != null ? ge.getProviderDetail() : ge.getMessage();
        return HealthResult.down(detail, ge.getErrorCode());
    }

    // ---- 请求构建 ----

    private boolean isChat(UnifiedRequest request) {
        return request != null && request.capability() == Capability.CHAT && request.chat() != null;
    }

    private String chatPath(ChannelRuntime runtime) {
        return pathFromConfig(runtime, "chatPath", DEFAULT_CHAT_PATH);
    }

    /** 从通道配置读取端点路径（可覆盖默认），便于兼容各家自定义端点。 */
    private String pathFromConfig(ChannelRuntime runtime, String key, String defaultPath) {
        Object path = runtime.getConfig().get(key);
        return (path instanceof String s && !s.isBlank()) ? s.trim() : defaultPath;
    }

    private void applyAuth(HttpHeaders headers, ChannelRuntime runtime) {
        UpstreamAuthenticator authenticator = authResolver.resolve(runtime.getProviderType(), runtime.getAuthType());
        if (authenticator != null) {
            authenticator.apply(headers, runtime);
        } else if (log.isDebugEnabled()) {
            log.debug("通道 {} 无匹配鉴权器(authType={})", runtime.getChannelId(), runtime.getAuthType());
        }
    }

    private Map<String, Object> buildChatBody(UnifiedRequest request, ChannelRuntime runtime, boolean stream) {
        UnifiedRequest.ChatPayload chat = request.chat();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", runtime.getUpstreamModel());
        body.put("messages", buildMessages(chat.messages()));
        body.put("stream", stream);
        if (stream) {
            // 请求在最后一个 chunk 返回 usage，便于审计与配额统计
            body.put("stream_options", Map.of("include_usage", true));
        }
        applyParams(body, chat.params());
        applyTools(body, chat);
        return body;
    }

    private void applyParams(Map<String, Object> body, GenerationParams params) {
        if (params == null) {
            return;
        }
        putIfNotNull(body, "temperature", params.temperature());
        putIfNotNull(body, "top_p", params.topP());
        putIfNotNull(body, "max_tokens", params.maxTokens());
        putIfNotNull(body, "stop", params.stop());
        putIfNotNull(body, "frequency_penalty", params.frequencyPenalty());
        putIfNotNull(body, "presence_penalty", params.presencePenalty());
        putIfNotNull(body, "seed", params.seed());
        if (params.responseFormat() != null && !params.responseFormat().isBlank()) {
            body.put("response_format", Map.of("type", params.responseFormat()));
        }
        putIfNotNull(body, "enable_thinking", params.enableThinking());
        if (params.extra() != null && !params.extra().isEmpty()) {
            params.extra().forEach((k, v) -> {
                if (!body.containsKey(k)) {
                    body.put(k, v);
                }
            });
        }
    }

    private void applyTools(Map<String, Object> body, UnifiedRequest.ChatPayload chat) {
        List<UnifiedRequest.ToolDefinition> tools = chat.tools();
        if (tools == null || tools.isEmpty()) {
            return;
        }
        List<Map<String, Object>> toolList = new ArrayList<>();
        for (UnifiedRequest.ToolDefinition def : tools) {
            if (def == null || def.function() == null) {
                continue;
            }
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", def.function().name());
            putIfNotNull(function, "description", def.function().description());
            putIfNotNull(function, "parameters", def.function().parameters());
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("type", def.type() != null ? def.type() : "function");
            tool.put("function", function);
            toolList.add(tool);
        }
        if (!toolList.isEmpty()) {
            body.put("tools", toolList);
            putIfNotNull(body, "tool_choice", chat.toolChoice());
        }
    }

    private List<Map<String, Object>> buildMessages(List<UnifiedMessage> messages) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (messages == null) {
            return result;
        }
        for (UnifiedMessage message : messages) {
            if (message == null) {
                continue;
            }
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("role", message.role());
            msg.put("content", buildContent(message));
            putIfNotNull(msg, "name", message.name());
            putIfNotNull(msg, "tool_call_id", message.toolCallId());
            if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
                msg.put("tool_calls", buildToolCalls(message.toolCalls()));
            }
            result.add(msg);
        }
        return result;
    }

    /** 纯文本用字符串，多模态用 content parts 数组（OpenAI 视觉格式）。 */
    private Object buildContent(UnifiedMessage message) {
        List<UnifiedMessage.ContentPart> parts = message.contentParts();
        if (parts == null || parts.isEmpty()) {
            return message.content() != null ? message.content() : "";
        }
        List<Map<String, Object>> content = new ArrayList<>();
        for (UnifiedMessage.ContentPart part : parts) {
            if (part == null) {
                continue;
            }
            if ("image_url".equals(part.type())) {
                content.add(Map.of("type", "image_url", "image_url", Map.of("url", part.imageUrl() != null ? part.imageUrl() : "")));
            } else {
                content.add(Map.of("type", "text", "text", part.text() != null ? part.text() : ""));
            }
        }
        return content;
    }

    private List<Map<String, Object>> buildToolCalls(List<UnifiedMessage.ToolCall> toolCalls) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (UnifiedMessage.ToolCall call : toolCalls) {
            if (call == null) {
                continue;
            }
            Map<String, Object> function = new LinkedHashMap<>();
            if (call.function() != null) {
                function.put("name", call.function().name());
                function.put("arguments", call.function().arguments());
            }
            Map<String, Object> item = new LinkedHashMap<>();
            putIfNotNull(item, "id", call.id());
            item.put("type", call.type() != null ? call.type() : "function");
            item.put("function", function);
            result.add(item);
        }
        return result;
    }

    private void putIfNotNull(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    // ---- 响应解析（非流式） ----

    private UnifiedResponse parseUnaryBody(String body) {
        JsonNode root = objectMapper.readTree(body);
        String id = textOrNull(root, "id");
        String model = textOrNull(root, "model");
        long created = root.path("created").asLong(0L);

        List<UnifiedResponse.Choice> choices = new ArrayList<>();
        JsonNode choicesNode = root.path("choices");
        if (choicesNode.isArray()) {
            int i = 0;
            for (JsonNode node : choicesNode) {
                int index = node.path("index").asInt(i);
                String finishReason = emptyToNull(textOrNull(node, "finish_reason"));
                UnifiedMessage message = parseMessage(node.path("message"));
                choices.add(new UnifiedResponse.Choice(index, finishReason, message));
                i++;
            }
        }
        TokenUsage usage = parseUsage(root.path("usage"));
        return new UnifiedResponse(Capability.CHAT, id, model, created, choices, null, null, null, null, usage);
    }

    private UnifiedResponse parseEmbeddingBody(String body, ChannelRuntime runtime) {
        JsonNode root = objectMapper.readTree(body);
        List<UnifiedResponse.Embedding> embeddings = new ArrayList<>();
        JsonNode data = root.path("data");
        if (data.isArray()) {
            int i = 0;
            for (JsonNode item : data) {
                int index = item.path("index").asInt(i);
                List<Float> vector = new ArrayList<>();
                JsonNode emb = item.path("embedding");
                if (emb.isArray()) {
                    for (JsonNode f : emb) {
                        vector.add((float) f.asDouble());
                    }
                }
                embeddings.add(new UnifiedResponse.Embedding(index, vector));
                i++;
            }
        }
        String model = textOrNull(root, "model");
        String id = textOrNull(root, "id");
        TokenUsage usage = parseUsage(root.path("usage"));
        return new UnifiedResponse(Capability.EMBEDDING, id,
                model != null ? model : runtime.getUpstreamModel(),
                Instant.now().getEpochSecond(), null, embeddings, null, null, null, usage);
    }

    private UnifiedResponse parseSttBody(String body, ChannelRuntime runtime) {
        JsonNode root = objectMapper.readTree(body);
        String text = textOrNull(root, "text");
        UnifiedResponse.AudioResult audio = new UnifiedResponse.AudioResult(null, text != null ? text : "", "application/json");
        return new UnifiedResponse(Capability.STT, null, runtime.getUpstreamModel(),
                Instant.now().getEpochSecond(), null, null, null, null, audio, null);
    }

    /** 根据文件名后缀推断上传音频的 Content-Type，未知类型退回 {@code application/octet-stream}。 */
    private MediaType guessAudioMediaType(String filename) {
        String lower = filename == null ? "" : filename.toLowerCase();
        if (lower.endsWith(".mp3")) {
            return MediaType.parseMediaType("audio/mpeg");
        }
        if (lower.endsWith(".wav")) {
            return MediaType.parseMediaType("audio/wav");
        }
        if (lower.endsWith(".m4a")) {
            return MediaType.parseMediaType("audio/mp4");
        }
        if (lower.endsWith(".flac")) {
            return MediaType.parseMediaType("audio/flac");
        }
        if (lower.endsWith(".ogg")) {
            return MediaType.parseMediaType("audio/ogg");
        }
        if (lower.endsWith(".webm")) {
            return MediaType.parseMediaType("audio/webm");
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    private UnifiedMessage parseMessage(JsonNode node) {
        if (node == null || !node.isObject()) {
            return UnifiedMessage.assistant("");
        }
        String role = emptyToNull(textOrNull(node, "role"));
        if (role == null) {
            role = "assistant";
        }
        String content = textOrNull(node, "content");
        String name = textOrNull(node, "name");
        String toolCallId = textOrNull(node, "tool_call_id");
        List<UnifiedMessage.ToolCall> toolCalls = parseToolCalls(node.path("tool_calls"));
        return new UnifiedMessage(role, content, null, name, toolCallId, toolCalls, false);
    }

    private List<UnifiedMessage.ToolCall> parseToolCalls(JsonNode node) {
        if (node == null || !node.isArray() || node.size() == 0) {
            return null;
        }
        List<UnifiedMessage.ToolCall> calls = new ArrayList<>();
        for (JsonNode item : node) {
            String id = textOrNull(item, "id");
            String type = emptyToNull(textOrNull(item, "type"));
            JsonNode function = item.path("function");
            String fnName = textOrNull(function, "name");
            String fnArgs = textOrNull(function, "arguments");
            calls.add(new UnifiedMessage.ToolCall(id, type != null ? type : "function",
                    new UnifiedMessage.ToolFunction(fnName, fnArgs)));
        }
        return calls;
    }

    // ---- 响应解析（流式） ----

    private UnifiedStreamChunk parseChunkBody(String data) {
        JsonNode root = objectMapper.readTree(data);
        String id = textOrNull(root, "id");
        String model = textOrNull(root, "model");
        long created = root.path("created").asLong(0L);

        String role = null;
        String content = null;
        String reasoning = null;
        String finishReason = null;
        JsonNode choices = root.path("choices");
        if (choices.isArray() && choices.size() > 0) {
            JsonNode choice = choices.get(0);
            JsonNode delta = choice.path("delta");
            role = emptyToNull(textOrNull(delta, "role"));
            content = emptyToNull(textOrNull(delta, "content"));
            reasoning = emptyToNull(textOrNull(delta, "reasoning_content"));
            finishReason = emptyToNull(textOrNull(choice, "finish_reason"));
        }
        TokenUsage usage = parseUsage(root.path("usage"));

        boolean meaningful = role != null || content != null || reasoning != null
                || finishReason != null || usage != null;
        if (!meaningful) {
            return null;
        }
        boolean done = finishReason != null;
        return new UnifiedStreamChunk(id, model, created, role, content, reasoning, null, finishReason, usage, done);
    }

    // ---- 公共解析辅助 ----

    private TokenUsage parseUsage(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        Integer prompt = intOrNull(node, "prompt_tokens");
        Integer completion = intOrNull(node, "completion_tokens");
        Integer total = intOrNull(node, "total_tokens");
        if (prompt == null && completion == null && total == null) {
            return null;
        }
        if (total == null) {
            return TokenUsage.of(prompt, completion);
        }
        return new TokenUsage(prompt == null ? 0 : prompt, completion == null ? 0 : completion, total);
    }

    private String textOrNull(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.asText();
    }

    private Integer intOrNull(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.asInt();
    }

    private String emptyToNull(String value) {
        return (value == null || value.isEmpty()) ? null : value;
    }

    // ---- 错误映射 ----

    private GatewayException mapError(Throwable e, ChannelRuntime runtime) {
        if (e instanceof GatewayException ge) {
            return ge;
        }
        String provider = runtime.getProviderCode();
        if (e instanceof WebClientResponseException wre) {
            int status = wre.getStatusCode().value();
            String detail = truncate(wre.getResponseBodyAsString());
            ErrorCode code;
            if (status == 401 || status == 403) {
                code = ErrorCode.AUTH_EXPIRED;
            } else if (status == 429) {
                code = ErrorCode.RATE_LIMITED;
            } else if (status == 408) {
                code = ErrorCode.PROVIDER_TIMEOUT;
            } else {
                code = ErrorCode.PROVIDER_ERROR;
            }
            return new GatewayException(code, "下游返回 HTTP " + status + " (" + provider + ")", detail, e);
        }
        if (isTimeout(e)) {
            return new GatewayException(ErrorCode.PROVIDER_TIMEOUT, "下游响应超时 (" + provider + ")", e);
        }
        if (e instanceof WebClientRequestException) {
            return new GatewayException(ErrorCode.PROVIDER_ERROR, "下游连接失败 (" + provider + ")", e);
        }
        return new GatewayException(ErrorCode.PROVIDER_ERROR, "下游调用失败 (" + provider + ")", e);
    }

    private boolean isTimeout(Throwable e) {
        Throwable cur = e;
        int depth = 0;
        while (cur != null && depth++ < 8) {
            if (cur instanceof TimeoutException || cur.getClass().getSimpleName().contains("Timeout")) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    private String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= MAX_DETAIL ? s : s.substring(0, MAX_DETAIL) + "...";
    }
}
