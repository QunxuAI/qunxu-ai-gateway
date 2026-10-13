package tech.qunxu.module.gateway.observability;

import tech.qunxu.module.gateway.adapter.AdapterErrorMapper;
import tech.qunxu.module.gateway.common.enums.CallStatus;
import tech.qunxu.module.gateway.common.enums.HealthEventType;
import tech.qunxu.module.gateway.common.exception.ErrorCode;
import tech.qunxu.module.gateway.common.exception.GatewayException;
import tech.qunxu.module.gateway.common.model.TokenUsage;
import tech.qunxu.module.gateway.health.PassiveHealthMonitor;
import tech.qunxu.module.gateway.pipeline.RequestContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.SignalType;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 网关业务指标（Micrometer）。统一注册请求量、时延、首字延迟、Token 用量、通道健康事件、
 * 限流拒绝、围栏命中与内容审计裁决等指标，经 Actuator {@code /actuator/metrics} 与
 * {@code /actuator/prometheus} 暴露。
 *
 * <p>标签基数控制：仅使用<b>有界</b>维度（协议、端点、逻辑模型、状态、错误码、供应商编码、事件类型、
 * 围栏规则名），刻意不含调用人 ID / 通道 ID / traceId 等高基数值，避免时序库膨胀。</p>
 *
 * <p>所有方法均「尽力而为」：注册或记录失败仅告警，绝不影响调用链。</p>
 */
@Component
public class GatewayMetrics {

    private static final Logger log = LoggerFactory.getLogger(GatewayMetrics.class);

    /** 请求计数（含成功/失败/拦截）。 */
    public static final String REQUESTS = "gateway.requests";
    /** 端到端时延（自上下文创建至链路终止）。 */
    public static final String LATENCY = "gateway.request.latency";
    /** 首字延迟（仅流式或有响应时记录）。 */
    public static final String FIRST_TOKEN = "gateway.request.first.token";
    /** Token 用量计数，按 {@code type=prompt|completion|total} 区分。 */
    public static final String TOKENS = "gateway.tokens";
    /** 通道健康事件计数（剔除/恢复/探测失败/凭证过期）。 */
    public static final String HEALTH_EVENTS = "gateway.channel.health.events";
    /** 限流与配额拒绝计数，按 {@code scope=caller|model|quota} 区分。 */
    public static final String RATE_LIMIT_REJECTIONS = "gateway.ratelimit.rejections";
    /** 安全围栏命中计数，按规则名区分。 */
    public static final String GUARDRAIL_HITS = "gateway.guardrail.hits";
    /** 内容审计裁决计数，按 {@code decision} 区分。 */
    public static final String MODERATION_DECISIONS = "gateway.moderation.decisions";

    private static final String TAG_PROTOCOL = "protocol";
    private static final String TAG_ENDPOINT = "endpoint";
    private static final String TAG_MODEL = "model";
    private static final String TAG_STATUS = "status";
    private static final String TAG_ERROR = "error";
    private static final String TAG_TYPE = "type";
    private static final String TAG_SCOPE = "scope";
    private static final String TAG_RULE = "rule";
    private static final String TAG_DECISION = "decision";
    private static final String TAG_EVENT = "event";
    private static final String UNKNOWN = "unknown";
    private static final String NONE = "none";

    private final MeterRegistry registry;

    public GatewayMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * 记录一次完整调用的指标。在链路终止（完成/错误/取消）时调用。
     *
     * @param ctx    请求上下文
     * @param signal 终止信号
     * @param error  终止时的异常（无则为 {@code null}）；用于在审计归一化之前独立派生错误码
     */
    public void recordRequest(RequestContext ctx, SignalType signal, Throwable error) {
        if (ctx == null || registry == null) {
            return;
        }
        try {
            CallStatus status = ctx.getStatus() != null ? ctx.getStatus()
                    : (signal == SignalType.ON_COMPLETE ? CallStatus.SUCCESS : CallStatus.FAILURE);
            ErrorCode errorCode = ctx.getErrorCode();
            if (errorCode == null && error != null) {
                errorCode = deriveErrorCode(error);
            }
            String protocol = ctx.getProtocol() != null ? ctx.getProtocol().name() : UNKNOWN;
            String endpoint = orUnknown(ctx.getEndpoint());
            String model = orUnknown(ctx.getLogicalModelName());
            String statusTag = status.name();
            String errorTag = errorCode != null ? errorCode.name() : NONE;

            registry.counter(REQUESTS,
                    TAG_PROTOCOL, protocol, TAG_ENDPOINT, endpoint,
                    TAG_MODEL, model, TAG_STATUS, statusTag, TAG_ERROR, errorTag).increment();

            Timer latency = registry.timer(LATENCY,
                    TAG_PROTOCOL, protocol, TAG_ENDPOINT, endpoint,
                    TAG_MODEL, model, TAG_STATUS, statusTag);
            latency.record(Duration.ofMillis(Math.max(0L, ctx.elapsedMs())));

            Long firstToken = ctx.firstTokenMs();
            if (firstToken != null) {
                registry.timer(FIRST_TOKEN, TAG_PROTOCOL, protocol, TAG_ENDPOINT, endpoint, TAG_MODEL, model)
                        .record(firstToken, TimeUnit.MILLISECONDS);
            }

            recordTokens(model, ctx.getUsage());
            recordGuardrailHits(ctx.getGuardrailHit());
            recordModeration(ctx.getModerationResult());
        } catch (RuntimeException e) {
            log.debug("记录请求指标失败: {}", e.toString());
        }
    }

    /** 记录 Token 用量（prompt/completion/total 三个计数器）。 */
    private void recordTokens(String model, TokenUsage usage) {
        if (usage == null || usage.isEmpty()) {
            return;
        }
        addTokens(model, "prompt", usage.promptTokens());
        addTokens(model, "completion", usage.completionTokens());
        addTokens(model, "total", usage.totalTokens());
    }

    private void addTokens(String model, String type, Integer value) {
        if (value == null || value <= 0) {
            return;
        }
        registry.counter(TOKENS, TAG_MODEL, model, TAG_TYPE, type).increment(value);
    }

    /** 记录围栏命中（{@code ctx.guardrailHit} 为逗号分隔的规则名）。 */
    private void recordGuardrailHits(String hit) {
        if (hit == null || hit.isBlank()) {
            return;
        }
        for (String rule : hit.split(",")) {
            String name = rule.trim();
            if (!name.isEmpty()) {
                registry.counter(GUARDRAIL_HITS, TAG_RULE, name).increment();
            }
        }
    }

    /**
     * 记录内容审计裁决。{@code ctx.moderationResult} 形如 {@code BLOCK:色情} 或
     * {@code REVIEW:广告,辱骂}，仅取决策部分作为标签（类别基数不可控，不入标签）。
     */
    private void recordModeration(String moderationResult) {
        if (moderationResult == null || moderationResult.isBlank()) {
            return;
        }
        int colon = moderationResult.indexOf(':');
        String decision = (colon > 0 ? moderationResult.substring(0, colon) : moderationResult).trim();
        registry.counter(MODERATION_DECISIONS, TAG_DECISION, decision.isEmpty() ? UNKNOWN : decision).increment();
    }

    /** 记录一次通道健康事件（剔除/恢复/探测失败/凭证过期）。 */
    public void recordHealthEvent(HealthEventType type, String reason) {
        if (registry == null || type == null) {
            return;
        }
        try {
            registry.counter(HEALTH_EVENTS, TAG_EVENT, type.name(), TAG_TYPE, classify(type, reason)).increment();
        } catch (RuntimeException e) {
            log.debug("记录健康事件指标失败: {}", e.toString());
        }
    }

    /** 记录一次限流/配额拒绝。 */
    public void recordRateLimitRejection(String scope) {
        if (registry == null) {
            return;
        }
        try {
            registry.counter(RATE_LIMIT_REJECTIONS, TAG_SCOPE, orUnknown(scope)).increment();
        } catch (RuntimeException e) {
            log.debug("记录限流指标失败: {}", e.toString());
        }
    }

    /**
     * 从异常派生错误码标签。指标过滤器位于审计过滤器之<b>内层</b>，其 {@code doFinally} 因而先于审计触发，
     * 此时 {@code ctx.errorCode} 尚未被归一化填充，故需自行判定。
     *
     * <p>依次尝试：异常链中已携带的 {@link GatewayException} 错误码 → 超时 → 下游 HTTP 状态码语义
     * → 兜底归为 {@link ErrorCode#INTERNAL_ERROR}，以保证「确有异常」时错误标签不会是 {@code none}，
     * 使告警可按错误类型分流。</p>
     */
    private static ErrorCode deriveErrorCode(Throwable error) {
        ErrorCode fromChain = PassiveHealthMonitor.errorCodeOf(error);
        if (fromChain != null) {
            return fromChain;
        }
        if (AdapterErrorMapper.isTimeout(error)) {
            return ErrorCode.PROVIDER_TIMEOUT;
        }
        if (error instanceof WebClientResponseException wre) {
            return switch (wre.getStatusCode().value()) {
                case 401, 403 -> ErrorCode.AUTH_EXPIRED;
                case 429 -> ErrorCode.RATE_LIMITED;
                case 408 -> ErrorCode.PROVIDER_TIMEOUT;
                default -> ErrorCode.PROVIDER_ERROR;
            };
        }
        return ErrorCode.INTERNAL_ERROR;
    }

    /** 健康事件的细分类型标签：探测失败区分凭证过期与一般失败，便于告警分流。 */
    private static String classify(HealthEventType type, String reason) {
        if (type == HealthEventType.AUTH_EXPIRED) {
            return "auth_expired";
        }
        if (type == HealthEventType.PROBE_FAIL && reason != null && reason.contains("凭证")) {
            return "auth_expired";
        }
        return type.name().toLowerCase();
    }

    private static String orUnknown(String value) {
        return (value == null || value.isBlank()) ? UNKNOWN : value;
    }
}
