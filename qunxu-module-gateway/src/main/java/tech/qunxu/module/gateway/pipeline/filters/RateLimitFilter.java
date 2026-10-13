package tech.qunxu.module.gateway.pipeline.filters;

import tech.qunxu.module.gateway.common.exception.ErrorCode;
import tech.qunxu.module.gateway.common.exception.GatewayException;
import tech.qunxu.module.gateway.common.model.TokenUsage;
import tech.qunxu.module.gateway.config.GatewayProperties;
import tech.qunxu.module.gateway.observability.GatewayMetrics;
import tech.qunxu.module.gateway.pipeline.FilterOrder;
import tech.qunxu.module.gateway.pipeline.PipelineChain;
import tech.qunxu.module.gateway.pipeline.PipelineFilter;
import tech.qunxu.module.gateway.pipeline.RequestContext;
import tech.qunxu.module.gateway.security.CallerIdentity;
import tech.qunxu.module.gateway.security.QuotaDecision;
import tech.qunxu.module.gateway.security.QuotaService;
import tech.qunxu.module.gateway.security.RateLimitDecision;
import tech.qunxu.module.gateway.security.RateLimiter;
import tech.qunxu.module.gateway.ratelimit.RateLimitRuleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 限流与配额过滤器（{@link FilterOrder#RATE_LIMIT}，紧随鉴权之后、模型解析之前）。
 *
 * <p>两道闸门：</p>
 * <ol>
 *   <li><b>速率限流</b>（令牌桶）：先按调用人（{@code gw:ratelimit:caller:{callerId}}）判定，通过后再按
 *       「调用人 + 模型」（{@code gw:ratelimit:model:{callerId}:{model}}）判定；许可数解析优先级为
 *       调用人覆盖 &gt; 租户覆盖 &gt; 全局默认，模型级为模型覆盖 &gt; 默认。</li>
 *   <li><b>Token 配额</b>：按调用人/租户校验当日累计用量是否超限；调用结束后以 {@code doFinally}
 *       尽力而为地累加本次 {@code total tokens}（fire-and-forget，不拖慢响应、失败仅告警）。</li>
 * </ol>
 *
 * <p>任一闸门拒绝即以 {@link ErrorCode#RATE_LIMITED}(429) 短路，并在响应头写入 {@code Retry-After}
 * 与 {@code X-RateLimit-*}，便于客户端退避重试；异常由最外层审计与指标过滤器捕获记录。</p>
 *
 * <p>限流键使用<b>请求体中的模型名</b>而非解析后的逻辑模型，因为本过滤器先于模型解析执行——两者在
 * 正常配置下同名，模型不存在时也会先被限流后再由模型解析返回 404。</p>
 */
@Component
public class RateLimitFilter implements PipelineFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    /** 调用人级限流键前缀。 */
    static final String CALLER_KEY_PREFIX = "gw:ratelimit:caller:";
    /** 「调用人 + 模型」级限流键前缀。 */
    static final String MODEL_KEY_PREFIX = "gw:ratelimit:model:";

    private static final String SCOPE_CALLER = "caller";
    private static final String SCOPE_MODEL = "model";
    private static final String SCOPE_QUOTA = "quota";
    private static final String NO_KEY = "none";

    private final RateLimiter rateLimiter;
    private final QuotaService quotaService;
    private final GatewayMetrics metrics;
    private final GatewayProperties properties;
    private final RateLimitRuleService ruleService;

    public RateLimitFilter(RateLimiter rateLimiter, QuotaService quotaService,
                           GatewayMetrics metrics, GatewayProperties properties,
                           RateLimitRuleService ruleService) {
        this.rateLimiter = rateLimiter;
        this.quotaService = quotaService;
        this.metrics = metrics;
        this.properties = properties;
        this.ruleService = ruleService;
    }

    @Override
    public int order() {
        return FilterOrder.RATE_LIMIT;
    }

    @Override
    public Mono<Void> filter(RequestContext ctx, PipelineChain chain) {
        GatewayProperties.RateLimit cfg = properties.getRateLimit();
        boolean rateEnabled = cfg.isEnabled();
        boolean quotaEnabled = quotaService.isEnabled();
        if (!rateEnabled && !quotaEnabled) {
            return chain.execute(ctx);
        }

        CallerIdentity caller = ctx.getCaller() != null ? ctx.getCaller() : CallerIdentity.anonymous();
        String callerId = caller.auditId();
        String tenant = caller.tenant();
        String model = ctx.getRequest() != null ? ctx.getRequest().model() : null;

        Mono<RateLimitDecision> rateCheck = rateEnabled
                ? checkRateLimits(callerId, tenant, model, cfg)
                : Mono.just(RateLimitDecision.unlimited(NO_KEY));

        return rateCheck.flatMap(decision -> {
            if (!decision.allowed()) {
                return Mono.error(rejectByRate(ctx, decision));
            }
            return checkQuota(callerId, tenant).flatMap(quota -> {
                if (!quota.allowed()) {
                    return Mono.error(rejectByQuota(ctx, quota));
                }
                return proceed(ctx, chain, quotaEnabled, quota);
            });
        });
    }

    // ---- 速率限流 ----

    /** 先判调用人级，通过后再判模型级；任一拒绝即返回该拒绝判定。 */
    private Mono<RateLimitDecision> checkRateLimits(String callerId, String tenant, String model,
                                                    GatewayProperties.RateLimit cfg) {
        int callerPermits = ruleService.callerPermits(callerId, tenant);
        Mono<RateLimitDecision> callerCheck = acquire(CALLER_KEY_PREFIX + callerId, callerPermits);
        if (!cfg.isPerModelEnabled() || model == null || model.isBlank()) {
            return callerCheck;
        }
        int modelPermits = ruleService.modelPermits(model.trim());
        return callerCheck.flatMap(decision -> decision.allowed()
                ? acquire(MODEL_KEY_PREFIX + callerId + ":" + model.trim(), modelPermits)
                : Mono.just(decision));
    }

    private Mono<RateLimitDecision> acquire(String key, int permitsPerMinute) {
        if (permitsPerMinute <= 0) {
            return Mono.just(RateLimitDecision.unlimited(key));
        }
        return rateLimiter.tryAcquire(key, permitsPerMinute, properties.getRateLimit().getBurstRatio());
    }

    private GatewayException rejectByRate(RequestContext ctx, RateLimitDecision decision) {
        String scope = decision.key() != null && decision.key().startsWith(MODEL_KEY_PREFIX) ? SCOPE_MODEL : SCOPE_CALLER;
        metrics.recordRateLimitRejection(scope);
        writeThrottleHeaders(ctx, decision.limit(), decision.remaining(), decision.retryAfterSeconds());
        if (log.isInfoEnabled()) {
            log.info("限流拒绝 scope={} caller={} key={} limit={}/min remaining={} retryAfter={}s",
                    scope, ctx.getCaller() != null ? ctx.getCaller().auditId() : null,
                    decision.key(), decision.limit(), decision.remaining(), decision.retryAfterSeconds());
        }
        String dimension = SCOPE_MODEL.equals(scope) ? "该模型调用频率" : "调用频率";
        return new GatewayException(ErrorCode.RATE_LIMITED,
                dimension + "超限（" + decision.limit() + " 次/分钟），请在 "
                        + decision.retryAfterSeconds() + " 秒后重试");
    }

    // ---- Token 配额 ----

    private Mono<QuotaDecision> checkQuota(String callerId, String tenant) {
        if (!quotaService.isEnabled()) {
            return Mono.just(QuotaDecision.unlimited(NO_KEY));
        }
        long limit = quotaService.resolveLimit(callerId, tenant);
        return quotaService.check(quotaService.quotaKey(callerId, tenant), limit);
    }

    private GatewayException rejectByQuota(RequestContext ctx, QuotaDecision quota) {
        metrics.recordRateLimitRejection(SCOPE_QUOTA);
        writeThrottleHeaders(ctx, quota.limit(), -1L, secondsUntilNextDay());
        if (log.isInfoEnabled()) {
            log.info("配额拒绝 caller={} key={} limit={} used={}",
                    ctx.getCaller() != null ? ctx.getCaller().auditId() : null,
                    quota.key(), quota.limit(), quota.used());
        }
        return new GatewayException(ErrorCode.RATE_LIMITED,
                "今日 Token 配额已用尽（限额 " + quota.limit() + "，已用 " + Math.max(0L, quota.used())
                        + "），请次日再试或申请提额");
    }

    /** 继续执行链路；启用配额时在链路结束后累加本次 Token 用量（不改变链路结果）。 */
    private Mono<Void> proceed(RequestContext ctx, PipelineChain chain, boolean quotaEnabled, QuotaDecision quota) {
        Mono<Void> run = chain.execute(ctx);
        if (!quotaEnabled || quota.unlimited()) {
            return run;
        }
        String key = quota.key();
        return run.doFinally(signal -> consumeTokensQuietly(ctx, key));
    }

    private void consumeTokensQuietly(RequestContext ctx, String key) {
        TokenUsage usage = ctx.getUsage();
        int total = usage != null && usage.totalTokens() != null ? usage.totalTokens() : 0;
        if (total <= 0) {
            return;
        }
        quotaService.consume(key, total)
                .subscribe(null, e -> log.debug("配额用量累加订阅异常({}): {}", key, e.toString()));
    }

    // ---- 响应头与工具 ----

    /** 写入退避提示头；响应已提交或无请求时静默跳过。 */
    private void writeThrottleHeaders(RequestContext ctx, long limit, long remaining, long retryAfterSeconds) {
        jakarta.servlet.http.HttpServletResponse response = ctx.attribute(RequestContext.ATTR_RESPONSE);
        if (response == null || response.isCommitted()) {
            return;
        }
        if (retryAfterSeconds > 0) {
            response.setHeader("Retry-After", Long.toString(retryAfterSeconds));
        }
        if (limit > 0) {
            response.setHeader("X-RateLimit-Limit", Long.toString(limit));
            if (remaining >= 0) {
                response.setHeader("X-RateLimit-Remaining", Long.toString(remaining));
            }
        }
    }

    /** 距次日零点的秒数（配额拒绝时的建议重试等待），至少 1 秒。 */
    private static long secondsUntilNextDay() {
        long now = System.currentTimeMillis();
        long midnight = LocalDate.now().plusDays(1)
                .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        return Math.max(1L, (midnight - now) / 1000L);
    }
}
