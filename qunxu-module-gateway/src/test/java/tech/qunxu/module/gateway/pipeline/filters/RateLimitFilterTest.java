package tech.qunxu.module.gateway.pipeline.filters;

import tech.qunxu.module.gateway.common.enums.ProtocolType;
import tech.qunxu.module.gateway.common.exception.ErrorCode;
import tech.qunxu.module.gateway.common.exception.GatewayException;
import tech.qunxu.module.gateway.common.model.TokenUsage;
import tech.qunxu.module.gateway.common.model.UnifiedMessage;
import tech.qunxu.module.gateway.common.model.UnifiedRequest;
import tech.qunxu.module.gateway.config.GatewayProperties;
import tech.qunxu.module.gateway.observability.GatewayMetrics;
import tech.qunxu.module.gateway.pipeline.FilterOrder;
import tech.qunxu.module.gateway.pipeline.PipelineChain;
import tech.qunxu.module.gateway.pipeline.RequestContext;
import tech.qunxu.module.gateway.persistence.mapper.RateLimitRuleMapper;
import tech.qunxu.module.gateway.ratelimit.RateLimitRuleService;
import tech.qunxu.module.gateway.security.CallerIdentity;
import tech.qunxu.module.gateway.security.QuotaDecision;
import tech.qunxu.module.gateway.security.QuotaService;
import tech.qunxu.module.gateway.security.RateLimitDecision;
import tech.qunxu.module.gateway.security.RateLimiter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 限流与配额过滤器单元测试（M8e）：验证限流键构造、许可数解析优先级、429 短路（含退避响应头与指标）、
 * 模型级限流开关、配额校验与调用结束后的用量累加，以及两级开关全关时的短路。
 *
 * <p>{@link RateLimiter} 与 {@link QuotaService} 打桩以精确控制判定结果；指标使用真实的
 * {@link SimpleMeterRegistry} 以便断言计数与标签。</p>
 */
class RateLimitFilterTest {

    private static final String CALLER_KEY = RateLimitFilter.CALLER_KEY_PREFIX + "alice";
    private static final String MODEL_KEY = RateLimitFilter.MODEL_KEY_PREFIX + "alice:deepseek-chat";

    private RateLimiter rateLimiter;
    private QuotaService quotaService;
    private SimpleMeterRegistry meterRegistry;
    private GatewayProperties properties;
    private RateLimitFilter filter;
    private RecordingChain chain;

    /** 记录链推进次数；可预置本次调用的 Token 用量以驱动配额累加。 */
    private static final class RecordingChain implements PipelineChain {
        private int executions;
        private TokenUsage usage;

        @Override
        public Mono<Void> execute(RequestContext ctx) {
            executions++;
            if (usage != null) {
                ctx.setUsage(usage);
            }
            return Mono.empty();
        }
    }

    @BeforeEach
    void setUp() {
        rateLimiter = mock(RateLimiter.class);
        quotaService = mock(QuotaService.class);
        meterRegistry = new SimpleMeterRegistry();
        properties = new GatewayProperties();
        properties.getRateLimit().setEnabled(true);
        properties.getRateLimit().setPerModelEnabled(true);
        properties.getRateLimit().setDefaultPermitsPerMinute(600);
        properties.getRateLimit().setDefaultModelPermitsPerMinute(300);
        filter = new RateLimitFilter(rateLimiter, quotaService, new GatewayMetrics(meterRegistry), properties,
                new RateLimitRuleService(mock(RateLimitRuleMapper.class), properties));
        chain = new RecordingChain();
        allowAll();
    }

    private void allowAll() {
        when(rateLimiter.tryAcquire(anyString(), anyInt(), anyDouble()))
                .thenAnswer(inv -> Mono.just(RateLimitDecision.allow(inv.getArgument(0, String.class),
                        inv.getArgument(1, Integer.class).longValue(), 9L)));
        when(quotaService.isEnabled()).thenReturn(false);
    }

    private static RequestContext context(String callerId, String tenant, String model) {
        UnifiedRequest req = UnifiedRequest.chat(model, false,
                new UnifiedRequest.ChatPayload(List.of(UnifiedMessage.user("你好")), null, null, null));
        RequestContext ctx = RequestContext.create(ProtocolType.OPENAI, "POST",
                "/v1/chat/completions", "chat.completions", "trace-rl", req);
        if (callerId != null) {
            ctx.setCaller(CallerIdentity.of(callerId, callerId, tenant, Set.of(), Map.of()));
        }
        ctx.setAttribute(RequestContext.ATTR_RESPONSE, new MockHttpServletResponse());
        return ctx;
    }

    private long rejections(String scope) {
        var counter = meterRegistry.find(GatewayMetrics.RATE_LIMIT_REJECTIONS).tag("scope", scope).counter();
        return counter == null ? 0L : (long) counter.count();
    }

    private static HttpServletResponse responseOf(RequestContext ctx) {
        return ctx.attribute(RequestContext.ATTR_RESPONSE);
    }

    // ---- 顺序与放行 ----

    @Test
    void sitsBetweenAuthAndModelResolve() {
        assertThat(filter.order()).isEqualTo(FilterOrder.RATE_LIMIT);
        assertThat(filter.order()).isGreaterThan(FilterOrder.AUTH);
        assertThat(filter.order()).isLessThan(FilterOrder.MODEL_RESOLVE);
    }

    @Test
    void allowsRequestAndUsesCallerScopedKey() {
        StepVerifier.create(filter.filter(context("alice", "team-a", "deepseek-chat"), chain))
                .verifyComplete();

        assertThat(chain.executions).isEqualTo(1);
        verify(rateLimiter).tryAcquire(eq(CALLER_KEY), eq(600), anyDouble());
        verify(rateLimiter).tryAcquire(eq(MODEL_KEY), eq(300), anyDouble());
    }

    @Test
    void anonymousCallerUsesAnonymousKey() {
        StepVerifier.create(filter.filter(context(null, null, "deepseek-chat"), chain))
                .verifyComplete();

        verify(rateLimiter).tryAcquire(eq(RateLimitFilter.CALLER_KEY_PREFIX + "anonymous"), eq(600), anyDouble());
    }

    @Test
    void resolvesCallerPermitsByOverridePrecedence() {
        properties.getRateLimit().setCallerOverrides(Map.of("alice", 1200));
        properties.getRateLimit().setTenantOverrides(Map.of("team-a", 900, "team-b", 700));

        StepVerifier.create(filter.filter(context("alice", "team-a", "deepseek-chat"), chain)).verifyComplete();
        verify(rateLimiter).tryAcquire(eq(CALLER_KEY), eq(1200), anyDouble());

        StepVerifier.create(filter.filter(context("bob", "team-a", "deepseek-chat"), chain)).verifyComplete();
        verify(rateLimiter).tryAcquire(eq(RateLimitFilter.CALLER_KEY_PREFIX + "bob"), eq(900), anyDouble());

        StepVerifier.create(filter.filter(context("carol", "team-z", "deepseek-chat"), chain)).verifyComplete();
        verify(rateLimiter).tryAcquire(eq(RateLimitFilter.CALLER_KEY_PREFIX + "carol"), eq(600), anyDouble());
    }

    @Test
    void resolvesModelPermitsByOverride() {
        properties.getRateLimit().setModelOverrides(Map.of("deepseek-chat", 42));

        StepVerifier.create(filter.filter(context("alice", "team-a", "deepseek-chat"), chain)).verifyComplete();

        verify(rateLimiter).tryAcquire(eq(MODEL_KEY), eq(42), anyDouble());
    }

    @Test
    void nonPositivePermitsSkipsLimiter() {
        properties.getRateLimit().setCallerOverrides(Map.of("alice", 0));
        properties.getRateLimit().setModelOverrides(Map.of("deepseek-chat", 0));

        StepVerifier.create(filter.filter(context("alice", "team-a", "deepseek-chat"), chain)).verifyComplete();

        assertThat(chain.executions).isEqualTo(1);
        verify(rateLimiter, never()).tryAcquire(anyString(), anyInt(), anyDouble());
    }

    @Test
    void skipsModelLevelWhenDisabled() {
        properties.getRateLimit().setPerModelEnabled(false);

        StepVerifier.create(filter.filter(context("alice", "team-a", "deepseek-chat"), chain)).verifyComplete();

        verify(rateLimiter).tryAcquire(eq(CALLER_KEY), eq(600), anyDouble());
        verify(rateLimiter, never()).tryAcquire(eq(MODEL_KEY), anyInt(), anyDouble());
    }

    @Test
    void shortCircuitsWhenBothSwitchesOff() {
        properties.getRateLimit().setEnabled(false);
        when(quotaService.isEnabled()).thenReturn(false);

        StepVerifier.create(filter.filter(context("alice", "team-a", "deepseek-chat"), chain)).verifyComplete();

        assertThat(chain.executions).isEqualTo(1);
        verify(rateLimiter, never()).tryAcquire(anyString(), anyInt(), anyDouble());
        verify(quotaService, never()).check(anyString(), anyLong());
    }

    // ---- 拒绝路径 ----

    @Test
    void rejectsWhenCallerBucketExhausted() {
        when(rateLimiter.tryAcquire(eq(CALLER_KEY), anyInt(), anyDouble()))
                .thenReturn(Mono.just(RateLimitDecision.deny(CALLER_KEY, 600L, 0L, 12L)));
        RequestContext ctx = context("alice", "team-a", "deepseek-chat");

        StepVerifier.create(filter.filter(ctx, chain))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(GatewayException.class);
                    GatewayException ge = (GatewayException) e;
                    assertThat(ge.getErrorCode()).isEqualTo(ErrorCode.RATE_LIMITED);
                    assertThat(ge.getMessage()).contains("调用频率超限").contains("600").contains("12");
                })
                .verify(Duration.ofSeconds(5));

        assertThat(chain.executions).isZero();
        // 调用人级被拒后不再判模型级
        verify(rateLimiter, never()).tryAcquire(eq(MODEL_KEY), anyInt(), anyDouble());
        assertThat(rejections("caller")).isEqualTo(1L);

        HttpServletResponse response = responseOf(ctx);
        assertThat(response.getHeader("Retry-After")).isEqualTo("12");
        assertThat(response.getHeader("X-RateLimit-Limit")).isEqualTo("600");
        assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("0");
    }

    @Test
    void rejectsWhenModelBucketExhaustedAndTagsScopeAsModel() {
        when(rateLimiter.tryAcquire(eq(MODEL_KEY), anyInt(), anyDouble()))
                .thenReturn(Mono.just(RateLimitDecision.deny(MODEL_KEY, 300L, 0L, 5L)));

        StepVerifier.create(filter.filter(context("alice", "team-a", "deepseek-chat"), chain))
                .expectErrorSatisfies(e -> {
                    GatewayException ge = (GatewayException) e;
                    assertThat(ge.getErrorCode()).isEqualTo(ErrorCode.RATE_LIMITED);
                    assertThat(ge.getMessage()).contains("该模型调用频率超限").contains("300");
                })
                .verify(Duration.ofSeconds(5));

        assertThat(chain.executions).isZero();
        assertThat(rejections("model")).isEqualTo(1L);
        assertThat(rejections("caller")).isZero();
    }

    @Test
    void degradedAllowPassesThrough() {
        when(rateLimiter.tryAcquire(anyString(), anyInt(), anyDouble()))
                .thenReturn(Mono.just(RateLimitDecision.degradedAllow(CALLER_KEY, 600L)));

        StepVerifier.create(filter.filter(context("alice", "team-a", "deepseek-chat"), chain)).verifyComplete();

        assertThat(chain.executions).isEqualTo(1);
        assertThat(rejections("caller")).isZero();
    }

    // ---- 配额 ----

    @Test
    void rejectsWhenDailyQuotaExhausted() {
        when(quotaService.isEnabled()).thenReturn(true);
        when(quotaService.resolveLimit("alice", "team-a")).thenReturn(1000L);
        when(quotaService.quotaKey("alice", "team-a")).thenReturn("gw:quota:tokens:caller:alice:20260101");
        when(quotaService.check(anyString(), anyLong()))
                .thenReturn(Mono.just(QuotaDecision.of("gw:quota:tokens:caller:alice:20260101", 1000L, 1200L)));
        RequestContext ctx = context("alice", "team-a", "deepseek-chat");

        StepVerifier.create(filter.filter(ctx, chain))
                .expectErrorSatisfies(e -> {
                    GatewayException ge = (GatewayException) e;
                    assertThat(ge.getErrorCode()).isEqualTo(ErrorCode.RATE_LIMITED);
                    assertThat(ge.getMessage()).contains("配额").contains("1000").contains("1200");
                })
                .verify(Duration.ofSeconds(5));

        assertThat(chain.executions).isZero();
        assertThat(rejections("quota")).isEqualTo(1L);
        // 配额按日滚动，退避提示为距次日零点的秒数
        String retryAfter = responseOf(ctx).getHeader("Retry-After");
        assertThat(retryAfter).isNotNull();
        assertThat(Long.parseLong(retryAfter)).isBetween(1L, 86400L);
    }

    @Test
    void accumulatesTokenUsageAfterChainCompletes() {
        String key = "gw:quota:tokens:caller:alice:20260101";
        when(quotaService.isEnabled()).thenReturn(true);
        when(quotaService.resolveLimit("alice", "team-a")).thenReturn(10000L);
        when(quotaService.quotaKey("alice", "team-a")).thenReturn(key);
        when(quotaService.check(key, 10000L))
                .thenReturn(Mono.just(QuotaDecision.of(key, 10000L, 0L)));
        when(quotaService.consume(eq(key), anyLong())).thenReturn(Mono.just(15L));
        chain.usage = TokenUsage.of(10, 5);

        StepVerifier.create(filter.filter(context("alice", "team-a", "deepseek-chat"), chain)).verifyComplete();

        assertThat(chain.executions).isEqualTo(1);
        verify(quotaService, timeout(2000)).consume(key, 15L);
    }

    @Test
    void skipsQuotaAccumulationWhenNoUsage() {
        String key = "gw:quota:tokens:caller:alice:20260101";
        when(quotaService.isEnabled()).thenReturn(true);
        when(quotaService.resolveLimit("alice", "team-a")).thenReturn(10000L);
        when(quotaService.quotaKey("alice", "team-a")).thenReturn(key);
        when(quotaService.check(key, 10000L)).thenReturn(Mono.just(QuotaDecision.of(key, 10000L, 0L)));
        chain.usage = TokenUsage.empty();

        StepVerifier.create(filter.filter(context("alice", "team-a", "deepseek-chat"), chain)).verifyComplete();

        verify(quotaService, never()).consume(anyString(), anyLong());
    }

    @Test
    void unknownQuotaUsageFailsOpen() {
        String key = "gw:quota:tokens:caller:alice:20260101";
        when(quotaService.isEnabled()).thenReturn(true);
        when(quotaService.resolveLimit("alice", "team-a")).thenReturn(1000L);
        when(quotaService.quotaKey("alice", "team-a")).thenReturn(key);
        when(quotaService.check(key, 1000L)).thenReturn(Mono.just(QuotaDecision.unknown(key, 1000L)));

        StepVerifier.create(filter.filter(context("alice", "team-a", "deepseek-chat"), chain)).verifyComplete();

        assertThat(chain.executions).isEqualTo(1);
        assertThat(rejections("quota")).isZero();
    }

    @Test
    void unlimitedQuotaSkipsAccumulation() {
        String key = "gw:quota:tokens:caller:alice:20260101";
        when(quotaService.isEnabled()).thenReturn(true);
        when(quotaService.resolveLimit("alice", "team-a")).thenReturn(0L);
        when(quotaService.quotaKey("alice", "team-a")).thenReturn(key);
        when(quotaService.check(key, 0L)).thenReturn(Mono.just(QuotaDecision.unlimited(key)));
        chain.usage = TokenUsage.of(10, 5);

        StepVerifier.create(filter.filter(context("alice", "team-a", "deepseek-chat"), chain)).verifyComplete();

        verify(quotaService, never()).consume(anyString(), anyLong());
    }

    @Test
    void quotaOnlyModeStillGuardsWhenRateLimitDisabled() {
        properties.getRateLimit().setEnabled(false);
        String key = "gw:quota:tokens:caller:alice:20260101";
        when(quotaService.isEnabled()).thenReturn(true);
        when(quotaService.resolveLimit("alice", "team-a")).thenReturn(1000L);
        when(quotaService.quotaKey("alice", "team-a")).thenReturn(key);
        when(quotaService.check(key, 1000L)).thenReturn(Mono.just(QuotaDecision.of(key, 1000L, 5000L)));

        StepVerifier.create(filter.filter(context("alice", "team-a", "deepseek-chat"), chain))
                .expectError(GatewayException.class)
                .verify(Duration.ofSeconds(5));

        verify(rateLimiter, never()).tryAcquire(anyString(), anyInt(), anyDouble());
        assertThat(rejections("quota")).isEqualTo(1L);
    }
}
