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
 * LLM 裁判内容审计提供方<b>参考实现</b>（{@code llm}）：调用一个 OpenAI 兼容的 {@code /chat/completions} 审核模型，
 * 以严格 JSON 指令让模型输出 {@code {"decision":"pass|review|block","categories":[...]}}，据此归一为
 * {@link ModerationResult}。适用于内置词库/绿网难以覆盖的语义级审核（隐喻、上下文违规等）。
 *
 * <p><b>启用条件</b>：策略 {@code config_json} 提供 {@code llm.endpoint} 与 {@code llm.model}（可选 {@code llm.apiKey}）。
 * 未配置时优雅禁用返回 PASS；调用或解析异常时失败开放（PASS 并记录原因），绝不阻断主链。</p>
 *
 * <p>可配置项：{@code llm.endpoint}、{@code llm.model}、{@code llm.apiKey}、{@code llm.temperature}（默认 0）。</p>
 */
@Component
public class LlmJudgeModerationProvider implements ModerationProvider {

    public static final String NAME = "llm";

    private static final Logger log = LoggerFactory.getLogger(LlmJudgeModerationProvider.class);

    private static final String SYSTEM_PROMPT = """
            你是严格的内容安全审核引擎。判断用户文本是否涉及以下风险类别：涉政、色情、暴恐、广告、辱骂、违法、隐私。
            只输出一个 JSON 对象，不要任何解释或多余文本，格式为：
            {"decision":"pass|review|block","categories":["类别1","类别2"]}
            其中 decision：pass=合规，review=疑似需人工复核，block=明确违规；无风险时 categories 为空数组。""";

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public LlmJudgeModerationProvider(@Qualifier("gatewayWebClient") WebClient webClient,
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
        String endpoint = context.stringOrNull("llm.endpoint");
        String model = context.stringOrNull("llm.model");
        if (endpoint == null || model == null || text == null || text.isEmpty()) {
            return Mono.just(ModerationResult.pass(NAME));
        }
        String apiKey = context.stringOrNull("llm.apiKey");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("stream", false);
        body.put("temperature", 0);
        body.put("messages", List.of(
                Map.of("role", "system", "content", SYSTEM_PROMPT),
                Map.of("role", "user", "content", text)));

        return webClient.post()
                .uri(endpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> {
                    if (apiKey != null) {
                        headers.setBearerAuth(apiKey);
                    }
                })
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .map(this::parse)
                .onErrorResume(e -> {
                    log.warn("LLM 裁判内容审计调用失败，失败开放放行: {}", e.toString());
                    return Mono.just(ModerationResult.of(ModerationDecision.PASS, List.of(),
                            "llm-error: " + e.getClass().getSimpleName(), NAME));
                });
    }

    /** 从 chat 响应中提取 {@code choices[0].message.content}，再从中解析审核 JSON。 */
    private ModerationResult parse(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return ModerationResult.pass(NAME);
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode choices = root.path("choices");
            String content = null;
            if (choices.isArray() && choices.size() > 0) {
                content = JsonUtils.text(choices.get(0).path("message"), "content");
            }
            if (content == null || content.isBlank()) {
                return ModerationResult.of(ModerationDecision.PASS, List.of(), "llm-empty", NAME);
            }
            return parseVerdict(content);
        } catch (RuntimeException e) {
            log.warn("LLM 裁判响应解析失败，失败开放放行: {}", e.toString());
            return ModerationResult.of(ModerationDecision.PASS, List.of(), "llm-unparsable", NAME);
        }
    }

    /** 解析模型输出的裁决 JSON（容忍模型夹带少量散文，提取首个 {@code {...}} 片段）。 */
    private ModerationResult parseVerdict(String content) {
        String json = extractJson(content);
        if (json == null) {
            return ModerationResult.of(ModerationDecision.REVIEW, List.of(), "llm-nonjson", NAME);
        }
        JsonNode verdict = objectMapper.readTree(json);
        String decision = JsonUtils.text(verdict, "decision");
        List<String> categories = new ArrayList<>();
        JsonNode cats = verdict.path("categories");
        if (cats.isArray()) {
            for (JsonNode c : cats) {
                if (c != null && !c.isNull()) {
                    String s = c.asText();
                    if (s != null && !s.isBlank()) {
                        categories.add(s.trim());
                    }
                }
            }
        }
        ModerationDecision mapped = switch (decision == null ? "" : decision.trim().toLowerCase()) {
            case "block" -> ModerationDecision.BLOCK;
            case "review" -> ModerationDecision.REVIEW;
            default -> ModerationDecision.PASS;
        };
        return ModerationResult.of(mapped, categories, "llm decision=" + decision, NAME);
    }

    /** 提取文本中首个平衡的 {@code {...}} JSON 对象子串；无则返回 {@code null}。 */
    private String extractJson(String content) {
        int start = content.indexOf('{');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        for (int i = start; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return content.substring(start, i + 1);
                }
            }
        }
        return null;
    }
}
