package tech.qunxu.module.gateway.moderation;

import tech.qunxu.module.gateway.common.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 阿里云内容安全（绿网）内容审计提供方<b>参考实现</b>（{@code aliyun}）。
 *
 * <p>以 HTTP 方式调用内容检测接口：请求体 {@code {"content": text}}（可含 {@code scene}），以
 * {@code Authorization: APPCODE <appCode>}（阿里云 API 市场鉴权）或 {@code x-acs-*} 头访问。响应容错解析常见结构
 * （{@code suggestion}/{@code label}，或 {@code data.results[]} 数组）映射为 {@link ModerationDecision} 与风险类别。</p>
 *
 * <p><b>启用条件</b>：策略 {@code config_json} 提供 {@code aliyun.endpoint}（必填）与 {@code aliyun.appCode}。
 * 未配置时本提供方<b>优雅禁用</b>，直接返回 {@link ModerationDecision#PASS}，不影响主链。调用异常时采取
 * <b>失败开放</b>（fail-open，返回 PASS 并记录原因），避免审计服务抖动阻断全部流量；如需失败关闭，可在策略层
 * 叠加内置关键词审计。若使用官方 SDK 的 RPC 签名（HMAC-SHA1）鉴权，可在此扩展请求签名逻辑。</p>
 *
 * <p>可配置项（{@code config_json} 扁平键）：{@code aliyun.endpoint}、{@code aliyun.appCode}、{@code aliyun.scene}、
 * {@code aliyun.timeoutMs}（默认 3000）。</p>
 */
@Component
public class AliyunGreenModerationProvider implements ModerationProvider {

    public static final String NAME = "aliyun";

    private static final Logger log = LoggerFactory.getLogger(AliyunGreenModerationProvider.class);

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public AliyunGreenModerationProvider(@Qualifier("gatewayWebClient") WebClient webClient,
                                         ObjectMapper objectMapper) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Mono<ModerationResult> moderate(String text, ModerationContext context) {
        String endpoint = context.stringOrNull("aliyun.endpoint");
        if (endpoint == null || text == null || text.isEmpty()) {
            return Mono.just(ModerationResult.pass(NAME));
        }
        String appCode = context.stringOrNull("aliyun.appCode");
        String scene = context.stringOrNull("aliyun.scene");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", text);
        if (scene != null) {
            body.put("scene", scene);
        }

        return webClient.post()
                .uri(endpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> {
                    if (appCode != null) {
                        headers.set("Authorization", "APPCODE " + appCode);
                    }
                })
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .map(this::parse)
                .onErrorResume(e -> {
                    log.warn("阿里绿网内容审计调用失败，失败开放放行: {}", e.toString());
                    return Mono.just(ModerationResult.of(ModerationDecision.PASS, List.of(),
                            "aliyun-error: " + e.getClass().getSimpleName(), NAME));
                });
    }

    /** 容错解析响应：优先顶层 {@code suggestion}/{@code label}，回退 {@code data.results[]} 数组。 */
    private ModerationResult parse(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return ModerationResult.pass(NAME);
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(responseBody);
        } catch (RuntimeException e) {
            log.warn("阿里绿网响应解析失败，失败开放放行: {}", e.toString());
            return ModerationResult.of(ModerationDecision.PASS, List.of(), "aliyun-unparsable", NAME);
        }
        String suggestion = JsonUtils.text(root, "suggestion");
        String label = JsonUtils.text(root, "label");
        if (suggestion == null) {
            JsonNode data = root.path("data");
            suggestion = JsonUtils.text(data, "suggestion");
            if (label == null) {
                label = JsonUtils.text(data, "label");
            }
            if (suggestion == null) {
                JsonNode results = data.path("results");
                if (results.isArray() && results.size() > 0) {
                    JsonNode first = results.get(0);
                    suggestion = JsonUtils.text(first, "suggestion");
                    if (label == null) {
                        label = JsonUtils.text(first, "label");
                    }
                }
            }
        }
        ModerationDecision decision = mapSuggestion(suggestion);
        List<String> categories = new ArrayList<>();
        if (label != null && !label.isBlank() && !"normal".equalsIgnoreCase(label) && !"pass".equalsIgnoreCase(label)) {
            categories.add(label);
        }
        String detail = "aliyun suggestion=" + (suggestion == null ? "n/a" : suggestion);
        return ModerationResult.of(decision, categories, detail, NAME);
    }

    private ModerationDecision mapSuggestion(String suggestion) {
        if (suggestion == null) {
            return ModerationDecision.PASS;
        }
        return switch (suggestion.trim().toLowerCase()) {
            case "block" -> ModerationDecision.BLOCK;
            case "review" -> ModerationDecision.REVIEW;
            default -> ModerationDecision.PASS;
        };
    }
}
