package tech.qunxu.module.gateway.admin.service;

import tech.qunxu.module.gateway.adapter.ChannelRuntime;
import tech.qunxu.module.gateway.adapter.ChannelRuntimeFactory;
import tech.qunxu.module.gateway.adapter.UpstreamAuthenticator;
import tech.qunxu.module.gateway.adapter.UpstreamAuthenticatorResolver;
import tech.qunxu.module.gateway.admin.ProviderModelInfo;
import tech.qunxu.module.gateway.common.CapabilityDetector;
import tech.qunxu.module.gateway.common.enums.Capability;
import tech.qunxu.module.gateway.common.enums.ProviderType;
import tech.qunxu.module.gateway.common.exception.ErrorCode;
import tech.qunxu.module.gateway.common.exception.GatewayException;
import tech.qunxu.module.gateway.config.GatewayProperties;
import tech.qunxu.module.gateway.persistence.entity.ProviderEntity;
import tech.qunxu.module.gateway.persistence.mapper.ProviderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 供应商模型发现服务：调用供应商上游 {@code GET /models} 拉取可用模型清单，供管理台「批量上架模型」使用。
 *
 * <p>复用网关的 {@link ChannelRuntimeFactory}（凭证解密、baseUrl 归一化）与
 * {@link UpstreamAuthenticatorResolver}（按供应商/鉴权类型附加鉴权头），与数据面转发行为保持一致。</p>
 *
 * <p>当前主要支持 OpenAI 兼容格式 {@code {data:[{id:...}]}}，并兼容「字符串数组」与 {@code {models:[...]}}
 * 两种常见变体；WS 类供应商（SPARK_WS）与不支持该端点的供应商将返回可读错误。</p>
 */
@Service
public class ProviderModelDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(ProviderModelDiscoveryService.class);

    /** 管理台拉取模型清单的兜底超时（毫秒），避免长时间挂起管理界面。 */
    private static final long DISCOVERY_TIMEOUT_MS = 15_000L;

    private final ProviderMapper providerMapper;
    private final ChannelRuntimeFactory runtimeFactory;
    private final UpstreamAuthenticatorResolver authResolver;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public ProviderModelDiscoveryService(ProviderMapper providerMapper,
                                         ChannelRuntimeFactory runtimeFactory,
                                         UpstreamAuthenticatorResolver authResolver,
                                         @Qualifier("gatewayWebClient") WebClient webClient,
                                         ObjectMapper objectMapper) {
        this.providerMapper = providerMapper;
        this.runtimeFactory = runtimeFactory;
        this.authResolver = authResolver;
        this.webClient = webClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 拉取指定供应商的模型清单。
     *
     * @param providerId 供应商 ID
     * @return 去重并按字典序排列的模型列表，每个模型附带自动识别的能力
     */
    public Mono<List<ProviderModelInfo>> listModels(Long providerId) {
        if (providerId == null) {
            return Mono.error(badRequest("供应商 ID provider_id 不能为空"));
        }
        return Mono.fromCallable(() -> loadProvider(providerId))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(this::fetchModels)
                .onErrorResume(this::translateError);
    }

    private ProviderEntity loadProvider(Long providerId) {
        ProviderEntity provider = providerMapper.selectById(providerId);
        if (provider == null) {
            throw badRequest("供应商不存在: provider_id=" + providerId);
        }
        if (provider.getStatus() != null && provider.getStatus() == 0) {
            throw badRequest("供应商已禁用，请先启用: provider_id=" + providerId);
        }
        return provider;
    }

    private Mono<List<ProviderModelInfo>> fetchModels(ProviderEntity provider) {
        ChannelRuntime runtime = runtimeFactory.createForProvider(provider);
        if (runtime.getBaseUrl() == null || runtime.getBaseUrl().isBlank()) {
            return Mono.error(badRequest("供应商未配置下游地址 base_url"));
        }
        // WS 类供应商没有 HTTP 模型清单端点
        String authType = runtime.getAuthType();
        if (authType != null && "SPARK_WS".equalsIgnoreCase(authType.trim())) {
            return Mono.error(badRequest("讯飞星火（SPARK_WS）为 WebSocket 供应商，不支持拉取模型清单"));
        }

        UpstreamAuthenticator authenticator =
                authResolver.resolve(runtime.getProviderType(), authType);
        WebClient.RequestHeadersSpec<?> spec = webClient.get()
                .uri(runtime.resolveUrl("models"));
        if (authenticator != null) {
            final UpstreamAuthenticator auth = authenticator;
            spec = spec.headers(headers -> auth.apply(headers, runtime));
        }

        log.info("拉取供应商模型清单 providerId={} url={}", provider.getId(), runtime.resolveUrl("models"));
        return spec.exchangeToMono(response -> {
                    if (!response.statusCode().is2xxSuccessful()) {
                        return response.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .map(body -> Mono.<JsonNode>error(upstreamError(provider, response.statusCode().value(), body)))
                                .flatMap(m -> m);
                    }
                    return response.bodyToMono(JsonNode.class);
                })
                .timeout(Duration.ofMillis(DISCOVERY_TIMEOUT_MS))
                .map(body -> parseModelInfos(body))
                .onErrorMap(WebClientRequestException.class,
                        e -> new GatewayException(ErrorCode.PROVIDER_ERROR,
                                "拉取模型清单失败，无法连接下游: " + provider.getName()));
    }

    private GatewayException upstreamError(ProviderEntity provider, int status, String body) {
        String detail = body != null && !body.isBlank()
                ? body.trim() : String.valueOf(status);
        if (detail.length() > 200) {
            detail = detail.substring(0, 200) + "…";
        }
        return new GatewayException(ErrorCode.PROVIDER_ERROR,
                "下游返回错误(" + provider.getName() + ") HTTP " + status + ": " + detail);
    }

    /** 解析模型清单：优先 OpenAI 兼容 {@code data[].id}，兼容字符串数组与 {@code models:[...]}，并按名识别能力。 */
    static List<ProviderModelInfo> parseModelInfos(JsonNode body) {
        if (body == null || body.isNull()) {
            return List.of();
        }
        Map<String, ProviderModelInfo> byId = new LinkedHashMap<>();
        JsonNode data = body.path("data");
        if (data.isArray()) {
            for (JsonNode node : data) {
                String id = node.isTextual() ? node.asText() : node.path("id").asText(null);
                collectModel(byId, id);
            }
        }
        if (byId.isEmpty() && body.isArray()) {
            for (JsonNode node : body) {
                collectModel(byId, node.isTextual() ? node.asText() : node.path("id").asText(null));
            }
        }
        if (byId.isEmpty()) {
            JsonNode models = body.path("models");
            if (models.isArray()) {
                for (JsonNode node : models) {
                    collectModel(byId, node.isTextual() ? node.asText() : node.path("id").asText(null));
                }
            }
        }
        List<ProviderModelInfo> sorted = new ArrayList<>(byId.values());
        sorted.sort(Comparator.comparing(ProviderModelInfo::id));
        return sorted;
    }

    private static void collectModel(Map<String, ProviderModelInfo> byId, String id) {
        if (id == null || id.isBlank()) {
            return;
        }
        String normalized = id.trim();
        byId.putIfAbsent(normalized, new ProviderModelInfo(normalized,
                capabilityName(CapabilityDetector.detect(normalized))));
    }

    private static String capabilityName(Capability capability) {
        return capability != null ? capability.name() : null;
    }

    private Mono<List<ProviderModelInfo>> translateError(Throwable e) {
        if (e instanceof GatewayException ge) {
            return Mono.error(ge);
        }
        if (e instanceof WebClientResponseException re) {
            return Mono.error(new GatewayException(ErrorCode.PROVIDER_ERROR,
                    "拉取模型清单失败: HTTP " + re.getStatusCode().value()));
        }
        if (e instanceof java.util.concurrent.TimeoutException) {
            return Mono.error(new GatewayException(ErrorCode.PROVIDER_TIMEOUT, "拉取模型清单超时"));
        }
        if (e instanceof WebClientRequestException) {
            return Mono.error(new GatewayException(ErrorCode.PROVIDER_ERROR, "拉取模型清单失败，无法连接下游"));
        }
        log.warn("拉取模型清单异常", e);
        return Mono.error(new GatewayException(ErrorCode.PROVIDER_ERROR,
                "拉取模型清单失败: " + e.getMessage()));
    }

    private static GatewayException badRequest(String message) {
        return new GatewayException(ErrorCode.BAD_REQUEST, message);
    }
}
