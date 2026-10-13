package tech.qunxu.module.gateway.api.openai;

import tech.qunxu.module.gateway.admin.service.LogicalModelAdminService;
import tech.qunxu.module.gateway.persistence.entity.LogicalModelEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * 模型清单端点：{@code GET /v1/models}。
 *
 * <p>返回<b>启用中</b>的逻辑模型，即客户端可在 {@code model} 字段中使用的取值。数据直接来自配置库，
 * 不经过路由/适配器流水线——因此不涉及下游调用，也不产生调用审计。</p>
 *
 * <p>OpenAI 与 Anthropic 的模型清单端点<b>路径与动词完全相同</b>（均为 {@code GET /v1/models}），
 * 故按请求头 {@code anthropic-version} 是否存在来区分渲染格式：</p>
 * <ul>
 *   <li>缺省 → OpenAI：{@code {"object":"list","data":[{"id","object":"model","created","owned_by"}]}}</li>
 *   <li>存在 → Anthropic：{@code {"data":[{"type":"model","id","display_name","created_at"}],
 *       "has_more":false,"first_id":...,"last_id":...}}</li>
 * </ul>
 */
@RestController
@RequestMapping("/v1")
public class ModelController {

    /** Anthropic 客户端必带的协议版本头，用作协议判别依据。 */
    static final String ANTHROPIC_VERSION_HEADER = "anthropic-version";

    private static final String OWNED_BY = "qunxu-gateway";

    private final LogicalModelAdminService modelService;

    public ModelController(LogicalModelAdminService modelService) {
        this.modelService = modelService;
    }

    /** 列出启用中的逻辑模型（按请求头自动选择 OpenAI / Anthropic 响应格式）。 */
    @GetMapping("/models")
    public Mono<Map<String, Object>> models(
            @RequestHeader(value = ANTHROPIC_VERSION_HEADER, required = false) String anthropicVersion) {
        boolean anthropic = anthropicVersion != null && !anthropicVersion.isBlank();
        return modelService.queryEnabled()
                .map(models -> anthropic ? renderAnthropic(models) : renderOpenAi(models));
    }

    private Map<String, Object> renderOpenAi(List<LogicalModelEntity> models) {
        List<Map<String, Object>> data = models.stream()
                .map(m -> Map.<String, Object>of(
                        "id", nullSafe(m.getName()),
                        "object", "model",
                        "created", epochSeconds(m.getCreatedAt()),
                        "owned_by", OWNED_BY))
                .toList();
        return Map.of("object", "list", "data", data);
    }

    private Map<String, Object> renderAnthropic(List<LogicalModelEntity> models) {
        List<Map<String, Object>> data = models.stream()
                .map(m -> Map.<String, Object>of(
                        "type", "model",
                        "id", nullSafe(m.getName()),
                        "display_name", displayName(m),
                        "created_at", iso8601(m.getCreatedAt())))
                .toList();
        return Map.of(
                "data", data,
                "has_more", false,
                "first_id", data.isEmpty() ? "" : data.get(0).get("id"),
                "last_id", data.isEmpty() ? "" : data.get(data.size() - 1).get("id"));
    }

    /** 展示名优先取描述，缺省回落模型名（Anthropic 的 display_name 语义为人类可读名）。 */
    private static String displayName(LogicalModelEntity model) {
        String description = model.getDescription();
        return description != null && !description.isBlank() ? description.trim() : nullSafe(model.getName());
    }

    private static String nullSafe(String value) {
        return value != null ? value : "";
    }

    private static long epochSeconds(LocalDateTime time) {
        return time != null ? time.toEpochSecond(ZoneOffset.UTC) : 0L;
    }

    private static String iso8601(LocalDateTime time) {
        return time != null ? time.atOffset(ZoneOffset.UTC).toString() : "";
    }
}
