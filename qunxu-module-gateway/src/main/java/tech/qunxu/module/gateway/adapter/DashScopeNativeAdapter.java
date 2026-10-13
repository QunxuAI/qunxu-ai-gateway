package tech.qunxu.module.gateway.adapter;

import tech.qunxu.module.gateway.common.enums.Capability;
import tech.qunxu.module.gateway.common.enums.ChannelProtocol;
import tech.qunxu.module.gateway.common.exception.ErrorCode;
import tech.qunxu.module.gateway.common.exception.GatewayException;
import tech.qunxu.module.gateway.common.model.TokenUsage;
import tech.qunxu.module.gateway.common.model.UnifiedRequest;
import tech.qunxu.module.gateway.common.model.UnifiedResponse;
import tech.qunxu.module.gateway.common.model.UnifiedStreamChunk;
import tech.qunxu.module.gateway.health.HealthResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DashScope 原生适配器：面向阿里云百炼「兼容模式未覆盖」的原生能力——重排（gte-rerank）与文生图（通义万相 wanx）。
 *
 * <p>重排为同步 REST；文生图为<b>异步任务</b>：先以 {@code X-DashScope-Async: enable} 提交任务取 {@code task_id}，
 * 再轮询 {@code /api/v1/tasks/{task_id}} 直至 {@code SUCCEEDED/FAILED}，成功后归一为 Unified 图片结果。
 * 鉴权走 {@code Authorization: Bearer <apiKey>}（由 {@link UpstreamAuthenticator} 承载）。</p>
 *
 * <p>语音（paraformer/cosyvoice）为 DashScope WebSocket 能力，形态与本 REST 适配器不同，故不在此覆盖。</p>
 */
@Component
public class DashScopeNativeAdapter implements ModelAdapter {

    private static final Logger log = LoggerFactory.getLogger(DashScopeNativeAdapter.class);

    private static final String DEFAULT_RERANK_PATH = "/api/v1/services/rerank/text-rerank/text-rerank";
    private static final String DEFAULT_IMAGE_PATH = "/api/v1/services/aigc/text2image/image-synthesis";
    private static final String DEFAULT_TASK_PATH = "/api/v1/tasks";
    private static final String ASYNC_HEADER = "X-DashScope-Async";

    /** 图片任务轮询间隔与最大次数（默认约 60s 上限），可由通道配置覆盖。 */
    private static final long DEFAULT_POLL_INTERVAL_MS = 1500L;
    private static final int DEFAULT_MAX_POLLS = 40;

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final UpstreamAuthenticatorResolver authResolver;

    public DashScopeNativeAdapter(@Qualifier("gatewayWebClient") WebClient webClient,
                                  ObjectMapper objectMapper,
                                  UpstreamAuthenticatorResolver authResolver) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
        this.authResolver = authResolver;
    }

    @Override
    public ChannelProtocol protocol() {
        return ChannelProtocol.DASHSCOPE_NATIVE;
    }

    @Override
    public Set<Capability> capabilities() {
        return Set.of(Capability.RERANK, Capability.IMAGE);
    }

    @Override
    public Mono<UnifiedResponse> invokeUnary(UnifiedRequest request, ChannelRuntime runtime) {
        Capability capability = request != null ? request.capability() : null;
        if (capability == null) {
            return Mono.error(new GatewayException(ErrorCode.BAD_REQUEST, "请求能力为空"));
        }
        return switch (capability) {
            case RERANK -> invokeRerank(request, runtime);
            case IMAGE -> invokeImage(request, runtime);
            default -> Mono.error(new GatewayException(ErrorCode.NOT_IMPLEMENTED,
                    "DashScope 原生适配器不支持能力: " + capability));
        };
    }

    @Override
    public Flux<UnifiedStreamChunk> invokeStream(UnifiedRequest request, ChannelRuntime runtime) {
        return Flux.error(new GatewayException(ErrorCode.NOT_IMPLEMENTED,
                "DashScope 原生适配器（重排/图片）不支持流式调用"));
    }

    // ---- 重排（同步 REST） ----

    private Mono<UnifiedResponse> invokeRerank(UnifiedRequest request, ChannelRuntime runtime) {
        UnifiedRequest.RerankPayload payload = request.rerank();
        if (payload == null || payload.query() == null || payload.documents() == null || payload.documents().isEmpty()) {
            return Mono.error(new GatewayException(ErrorCode.BAD_REQUEST, "rerank 请求缺少 query/documents"));
        }
        String url = runtime.resolveUrl(pathFromConfig(runtime, "rerankPath", DEFAULT_RERANK_PATH));
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("query", payload.query());
        input.put("documents", payload.documents());
        Map<String, Object> parameters = new LinkedHashMap<>();
        if (payload.topN() != null) {
            parameters.put("top_n", payload.topN());
        }
        if (payload.returnDocuments() != null) {
            parameters.put("return_documents", payload.returnDocuments());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", runtime.getUpstreamModel());
        body.put("input", input);
        if (!parameters.isEmpty()) {
            body.put("parameters", parameters);
        }
        return webClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> applyAuth(headers, runtime))
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .map(resp -> parseRerankBody(resp, runtime))
                .onErrorMap(e -> AdapterErrorMapper.map(e, runtime.getProviderCode()));
    }

    private UnifiedResponse parseRerankBody(String body, ChannelRuntime runtime) {
        JsonNode root = objectMapper.readTree(body);
        JsonNode output = root.path("output");
        List<UnifiedResponse.RerankResult> results = new ArrayList<>();
        JsonNode resultsNode = output.path("results");
        if (resultsNode.isArray()) {
            for (JsonNode r : resultsNode) {
                int index = r.path("index").asInt(results.size());
                Double score = r.has("relevance_score") && r.path("relevance_score").isNumber()
                        ? r.path("relevance_score").asDouble() : null;
                String document = extractDocumentText(r.path("document"));
                results.add(new UnifiedResponse.RerankResult(index, score, document));
            }
        }
        String id = textOrNull(root, "request_id");
        TokenUsage usage = parseUsage(root.path("usage"));
        return new UnifiedResponse(Capability.RERANK, id, runtime.getUpstreamModel(),
                Instant.now().getEpochSecond(), null, null, results, null, null, usage);
    }

    private String extractDocumentText(JsonNode docNode) {
        if (docNode == null || docNode.isNull() || docNode.isMissingNode()) {
            return null;
        }
        if (docNode.isTextual()) {
            return docNode.asText();
        }
        if (docNode.isObject()) {
            return textOrNull(docNode, "text");
        }
        return null;
    }

    // ---- 文生图（wanx 异步任务 + 轮询） ----

    private Mono<UnifiedResponse> invokeImage(UnifiedRequest request, ChannelRuntime runtime) {
        UnifiedRequest.ImagePayload payload = request.image();
        if (payload == null || payload.prompt() == null || payload.prompt().isBlank()) {
            return Mono.error(new GatewayException(ErrorCode.BAD_REQUEST, "image 请求缺少 prompt"));
        }
        return submitImageTask(payload, runtime)
                .flatMap(taskId -> pollImageTask(taskId, runtime, 0))
                .onErrorMap(e -> AdapterErrorMapper.map(e, runtime.getProviderCode()));
    }

    private Mono<String> submitImageTask(UnifiedRequest.ImagePayload payload, ChannelRuntime runtime) {
        String url = runtime.resolveUrl(pathFromConfig(runtime, "imagePath", DEFAULT_IMAGE_PATH));
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("prompt", payload.prompt());
        Map<String, Object> parameters = new LinkedHashMap<>();
        if (payload.size() != null) {
            parameters.put("size", payload.size());
        }
        if (payload.n() != null) {
            parameters.put("n", payload.n());
        }
        if (payload.style() != null) {
            parameters.put("style", payload.style());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", runtime.getUpstreamModel());
        body.put("input", input);
        if (!parameters.isEmpty()) {
            body.put("parameters", parameters);
        }
        return webClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header(ASYNC_HEADER, "enable")
                .headers(headers -> applyAuth(headers, runtime))
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .map(resp -> {
                    JsonNode root = objectMapper.readTree(resp);
                    String taskId = textOrNull(root.path("output"), "task_id");
                    if (taskId == null || taskId.isBlank()) {
                        throw new GatewayException(ErrorCode.PROVIDER_ERROR, "图片任务未返回 task_id");
                    }
                    return taskId;
                });
    }

    /** 轮询图片任务直至终态；PENDING/RUNNING 则延迟后重试，超过最大次数按超时处理。 */
    private Mono<UnifiedResponse> pollImageTask(String taskId, ChannelRuntime runtime, int attempt) {
        String url = runtime.resolveUrl(pathFromConfig(runtime, "taskPath", DEFAULT_TASK_PATH) + "/" + taskId);
        return webClient.get()
                .uri(url)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> applyAuth(headers, runtime))
                .retrieve()
                .bodyToMono(String.class)
                .flatMap(resp -> {
                    JsonNode root = objectMapper.readTree(resp);
                    JsonNode output = root.path("output");
                    String status = textOrNull(output, "task_status");
                    if (status == null) {
                        status = "";
                    }
                    return switch (status.toUpperCase()) {
                        case "SUCCEEDED" -> Mono.just(parseImageTaskResult(root, runtime));
                        case "FAILED", "CANCELED", "UNKNOWN" -> Mono.error(new GatewayException(
                                ErrorCode.PROVIDER_ERROR, "图片生成任务失败: " + textOrNull(output, "message")));
                        default -> {
                            if (attempt + 1 >= maxPolls(runtime)) {
                                yield Mono.error(new GatewayException(ErrorCode.PROVIDER_TIMEOUT,
                                        "图片生成任务轮询超时: task_id=" + taskId));
                            }
                            yield Mono.delay(Duration.ofMillis(pollIntervalMs(runtime)))
                                    .flatMap(t -> pollImageTask(taskId, runtime, attempt + 1));
                        }
                    };
                });
    }

    private UnifiedResponse parseImageTaskResult(JsonNode root, ChannelRuntime runtime) {
        JsonNode results = root.path("output").path("results");
        List<UnifiedResponse.ImageResult> images = new ArrayList<>();
        if (results.isArray()) {
            for (JsonNode r : results) {
                String url = textOrNull(r, "url");
                String b64 = textOrNull(r, "b64_image");
                images.add(new UnifiedResponse.ImageResult(url, b64, null));
            }
        }
        String id = textOrNull(root, "request_id");
        return new UnifiedResponse(Capability.IMAGE, id, runtime.getUpstreamModel(),
                Instant.now().getEpochSecond(), null, null, null, images, null, null);
    }

    // ---- 主动健康探测 ----

    /**
     * 原生能力无「免费 ping」端点，故以一次最小重排请求探测连通性与凭证有效性：
     * 401/403 → {@link ErrorCode#AUTH_EXPIRED} 下线；5xx/连接失败/超时 → 下线；其余（含 400 业务错误）
     * 视为可达且凭证被受理 → 上线。据此对图片通道（模型名不匹配重排会返回 400）亦能正确判定为在线。
     */
    @Override
    public Mono<HealthResult> probe(ChannelRuntime runtime) {
        long start = System.nanoTime();
        String url = runtime.resolveUrl(pathFromConfig(runtime, "rerankPath", DEFAULT_RERANK_PATH));
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("query", "ping");
        input.put("documents", List.of("ping"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", runtime.getUpstreamModel());
        body.put("input", input);
        body.put("parameters", Map.of("top_n", 1));
        return webClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> applyAuth(headers, runtime))
                .bodyValue(body)
                .exchangeToMono(response -> {
                    HttpStatusCode statusCode = response.statusCode();
                    return response.releaseBody().thenReturn(statusCode);
                })
                .map(status -> {
                    long latency = (System.nanoTime() - start) / 1_000_000L;
                    int code = status.value();
                    if (code == 401 || code == 403) {
                        return HealthResult.down("DashScope 凭证无效或已过期 (HTTP " + code + ")", ErrorCode.AUTH_EXPIRED);
                    }
                    if (code >= 500) {
                        return HealthResult.down("DashScope 服务端错误 (HTTP " + code + ")", ErrorCode.PROVIDER_ERROR);
                    }
                    return HealthResult.up(latency);
                })
                .onErrorResume(e -> {
                    GatewayException ge = AdapterErrorMapper.map(e, runtime.getProviderCode());
                    String detail = ge.getProviderDetail() != null ? ge.getProviderDetail() : ge.getMessage();
                    return Mono.just(HealthResult.down(detail, ge.getErrorCode()));
                });
    }

    // ---- 公共辅助 ----

    private void applyAuth(org.springframework.http.HttpHeaders headers, ChannelRuntime runtime) {
        UpstreamAuthenticator authenticator = authResolver.resolve(runtime.getProviderType(), runtime.getAuthType());
        if (authenticator != null) {
            authenticator.apply(headers, runtime);
        } else if (log.isDebugEnabled()) {
            log.debug("通道 {} 无匹配鉴权器(authType={})", runtime.getChannelId(), runtime.getAuthType());
        }
    }

    private String pathFromConfig(ChannelRuntime runtime, String key, String defaultPath) {
        Object path = runtime.getConfig().get(key);
        return (path instanceof String s && !s.isBlank()) ? s.trim() : defaultPath;
    }

    private long pollIntervalMs(ChannelRuntime runtime) {
        Object v = runtime.getConfig().get("imagePollIntervalMs");
        return v instanceof Number n ? n.longValue() : DEFAULT_POLL_INTERVAL_MS;
    }

    private int maxPolls(ChannelRuntime runtime) {
        Object v = runtime.getConfig().get("imageMaxPolls");
        return v instanceof Number n ? n.intValue() : DEFAULT_MAX_POLLS;
    }

    private TokenUsage parseUsage(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode total = node.get("total_tokens");
        if (total != null && total.isNumber()) {
            return new TokenUsage(0, 0, total.asInt());
        }
        return null;
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
}
