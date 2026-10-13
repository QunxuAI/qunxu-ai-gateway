package tech.qunxu.module.gateway.observability;

import tech.qunxu.module.gateway.common.enums.CallStatus;
import tech.qunxu.module.gateway.common.enums.HealthEventType;
import tech.qunxu.module.gateway.common.enums.ProtocolType;
import tech.qunxu.module.gateway.common.exception.ErrorCode;
import tech.qunxu.module.gateway.common.exception.GatewayException;
import tech.qunxu.module.gateway.common.model.TokenUsage;
import tech.qunxu.module.gateway.common.model.UnifiedMessage;
import tech.qunxu.module.gateway.common.model.UnifiedRequest;
import tech.qunxu.module.gateway.pipeline.RequestContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.SignalType;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GatewayMetrics} 单元测试（M8e）：以 {@link SimpleMeterRegistry} 断言指标名、标签取值与计数，
 * 覆盖状态/错误码的来源优先级（上下文优先于信号与异常派生）、Token 用量、首字延迟、围栏命中按规则拆分、
 * 内容审计裁决取决策部分、健康事件分类，以及「标签不含高基数维度」这一基数控制约束。
 */
class GatewayMetricsTest {

    private SimpleMeterRegistry registry;
    private GatewayMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new GatewayMetrics(registry);
    }

    private static RequestContext context() {
        UnifiedRequest req = UnifiedRequest.chat("deepseek-chat", false,
                new UnifiedRequest.ChatPayload(List.of(UnifiedMessage.user("你好")), null, null, null));
        RequestContext ctx = RequestContext.create(ProtocolType.OPENAI, "POST",
                "/v1/chat/completions", "chat.completions", "trace-m1", req);
        ctx.setLogicalModelName("deepseek-chat");
        return ctx;
    }

    private Counter requestCounter(String status, String error) {
        return registry.find(GatewayMetrics.REQUESTS)
                .tag("protocol", "OPENAI")
                .tag("endpoint", "chat.completions")
                .tag("model", "deepseek-chat")
                .tag("status", status)
                .tag("error", error)
                .counter();
    }

    // ---- 请求计数与时延 ----

    @Test
    void recordsSuccessfulRequestWithLatency() {
        metrics.recordRequest(context(), SignalType.ON_COMPLETE, null);

        Counter counter = requestCounter("SUCCESS", "none");
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);

        Timer latency = registry.find(GatewayMetrics.LATENCY).tag("status", "SUCCESS").timer();
        assertThat(latency).isNotNull();
        assertThat(latency.count()).isEqualTo(1L);
    }

    @Test
    void derivesFailureFromTerminalSignal() {
        metrics.recordRequest(context(), SignalType.CANCEL, null);

        assertThat(requestCounter("FAILURE", "none")).isNotNull();
        assertThat(requestCounter("SUCCESS", "none")).isNull();
    }

    @Test
    void prefersContextStatusAndErrorCodeOverSignal() {
        RequestContext ctx = context();
        ctx.setStatus(CallStatus.BLOCKED);
        ctx.setErrorCode(ErrorCode.GUARDRAIL_BLOCKED);

        // 围栏拦截以异常终止链路，但上下文已明确 BLOCKED，指标应沿用上下文口径与审计一致
        metrics.recordRequest(ctx, SignalType.ON_ERROR,
                new GatewayException(ErrorCode.INTERNAL_ERROR, "不应被采用"));

        assertThat(requestCounter("BLOCKED", "GUARDRAIL_BLOCKED")).isNotNull();
        assertThat(requestCounter("FAILURE", "INTERNAL_ERROR")).isNull();
    }

    @Test
    void fallsBackToUnknownWhenProtocolAndModelMissing() {
        RequestContext ctx = RequestContext.create(null, "POST", "/v1/chat/completions", null, "t", null);

        metrics.recordRequest(ctx, SignalType.ON_COMPLETE, null);

        Counter counter = registry.find(GatewayMetrics.REQUESTS)
                .tag("protocol", "unknown")
                .tag("endpoint", "unknown")
                .tag("model", "unknown")
                .tag("status", "SUCCESS")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void ignoresNullContext() {
        metrics.recordRequest(null, SignalType.ON_COMPLETE, null);

        assertThat(registry.find(GatewayMetrics.REQUESTS).counter()).isNull();
    }

    // ---- 错误码派生 ----

    @Test
    void derivesErrorCodeFromGatewayExceptionChain() {
        metrics.recordRequest(context(), SignalType.ON_ERROR,
                new RuntimeException("wrapper", new GatewayException(ErrorCode.RATE_LIMITED, "超限")));

        assertThat(requestCounter("FAILURE", "RATE_LIMITED")).isNotNull();
    }

    @Test
    void derivesTimeoutFromNestedCause() {
        metrics.recordRequest(context(), SignalType.ON_ERROR,
                new IllegalStateException("boom", new TimeoutException("read timed out")));

        assertThat(requestCounter("FAILURE", "PROVIDER_TIMEOUT")).isNotNull();
    }

    @Test
    void derivesAuthExpiredFromUpstreamStatus() {
        WebClientResponseException unauthorized = new WebClientResponseException(
                401, "Unauthorized", HttpHeaders.EMPTY, new byte[0], null);

        metrics.recordRequest(context(), SignalType.ON_ERROR, unauthorized);

        assertThat(requestCounter("FAILURE", "AUTH_EXPIRED")).isNotNull();
    }

    @Test
    void derivesInternalErrorForUnclassifiedFailure() {
        metrics.recordRequest(context(), SignalType.ON_ERROR, new IllegalStateException("boom"));

        assertThat(requestCounter("FAILURE", "INTERNAL_ERROR")).isNotNull();
        // 确有异常时不应出现 error=none，否则告警无法按错误类型分流
        assertThat(requestCounter("FAILURE", "none")).isNull();
    }

    // ---- 首字延迟与 Token ----

    @Test
    void recordsFirstTokenLatencyWhenStreamed() {
        RequestContext ctx = context();
        ctx.markFirstToken();

        metrics.recordRequest(ctx, SignalType.ON_COMPLETE, null);

        Timer firstToken = registry.find(GatewayMetrics.FIRST_TOKEN).tag("model", "deepseek-chat").timer();
        assertThat(firstToken).isNotNull();
        assertThat(firstToken.count()).isEqualTo(1L);
    }

    @Test
    void skipsFirstTokenLatencyForUnaryCalls() {
        metrics.recordRequest(context(), SignalType.ON_COMPLETE, null);

        assertThat(registry.find(GatewayMetrics.FIRST_TOKEN).timer()).isNull();
    }

    @Test
    void recordsTokenUsageByType() {
        RequestContext ctx = context();
        ctx.setUsage(TokenUsage.of(10, 5));

        metrics.recordRequest(ctx, SignalType.ON_COMPLETE, null);

        assertThat(tokenCounter("prompt").count()).isEqualTo(10.0);
        assertThat(tokenCounter("completion").count()).isEqualTo(5.0);
        assertThat(tokenCounter("total").count()).isEqualTo(15.0);
    }

    @Test
    void skipsEmptyTokenUsage() {
        RequestContext ctx = context();
        ctx.setUsage(TokenUsage.empty());

        metrics.recordRequest(ctx, SignalType.ON_COMPLETE, null);

        assertThat(registry.find(GatewayMetrics.TOKENS).counter()).isNull();
    }

    private Counter tokenCounter(String type) {
        Counter counter = registry.find(GatewayMetrics.TOKENS)
                .tag("model", "deepseek-chat").tag("type", type).counter();
        assertThat(counter).as("token counter type=%s", type).isNotNull();
        return counter;
    }

    // ---- 围栏与内容审计 ----

    @Test
    void recordsEachGuardrailRuleSeparately() {
        RequestContext ctx = context();
        ctx.setGuardrailHit("sensitive_word,pii");

        metrics.recordRequest(ctx, SignalType.ON_COMPLETE, null);

        assertThat(guardrailCounter("sensitive_word").count()).isEqualTo(1.0);
        assertThat(guardrailCounter("pii").count()).isEqualTo(1.0);
    }

    private Counter guardrailCounter(String rule) {
        Counter counter = registry.find(GatewayMetrics.GUARDRAIL_HITS).tag("rule", rule).counter();
        assertThat(counter).as("guardrail counter rule=%s", rule).isNotNull();
        return counter;
    }

    @Test
    void recordsModerationDecisionWithoutCategories() {
        RequestContext ctx = context();
        ctx.setModerationResult("BLOCK:色情,辱骂");

        metrics.recordRequest(ctx, SignalType.ON_COMPLETE, null);

        Counter counter = registry.find(GatewayMetrics.MODERATION_DECISIONS).tag("decision", "BLOCK").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
        // 类别基数不可控，不得进入标签
        assertThat(counter.getId().getTags()).extracting(Tag::getKey).doesNotContain("category");
    }

    // ---- 健康事件与限流 ----

    @Test
    void classifiesHealthEvents() {
        metrics.recordHealthEvent(HealthEventType.EJECT, "连续失败 5 次");
        metrics.recordHealthEvent(HealthEventType.RECOVER, "探测连续成功");
        metrics.recordHealthEvent(HealthEventType.AUTH_EXPIRED, "401");
        metrics.recordHealthEvent(HealthEventType.PROBE_FAIL, "探测失败：凭证无效");
        metrics.recordHealthEvent(HealthEventType.PROBE_FAIL, "探测超时");

        assertThat(healthCounter("EJECT", "eject").count()).isEqualTo(1.0);
        assertThat(healthCounter("RECOVER", "recover").count()).isEqualTo(1.0);
        assertThat(healthCounter("AUTH_EXPIRED", "auth_expired").count()).isEqualTo(1.0);
        // 探测失败按原因细分：凭证问题与一般失败分流告警
        assertThat(healthCounter("PROBE_FAIL", "auth_expired").count()).isEqualTo(1.0);
        assertThat(healthCounter("PROBE_FAIL", "probe_fail").count()).isEqualTo(1.0);
    }

    private Counter healthCounter(String event, String type) {
        Counter counter = registry.find(GatewayMetrics.HEALTH_EVENTS)
                .tag("event", event).tag("type", type).counter();
        assertThat(counter).as("health counter event=%s type=%s", event, type).isNotNull();
        return counter;
    }

    @Test
    void ignoresNullHealthEventType() {
        metrics.recordHealthEvent(null, "reason");

        assertThat(registry.find(GatewayMetrics.HEALTH_EVENTS).counter()).isNull();
    }

    @Test
    void recordsRateLimitRejectionByScope() {
        metrics.recordRateLimitRejection("caller");
        metrics.recordRateLimitRejection("caller");
        metrics.recordRateLimitRejection("quota");
        metrics.recordRateLimitRejection(null);

        assertThat(rejectionCounter("caller").count()).isEqualTo(2.0);
        assertThat(rejectionCounter("quota").count()).isEqualTo(1.0);
        assertThat(rejectionCounter("unknown").count()).isEqualTo(1.0);
    }

    private Counter rejectionCounter(String scope) {
        Counter counter = registry.find(GatewayMetrics.RATE_LIMIT_REJECTIONS).tag("scope", scope).counter();
        assertThat(counter).as("rejection counter scope=%s", scope).isNotNull();
        return counter;
    }

    // ---- 基数控制 ----

    @Test
    void tagsExcludeHighCardinalityDimensions() {
        RequestContext ctx = context();
        ctx.setAttribute("callerId", "alice");
        ctx.setUsage(TokenUsage.of(1, 1));
        metrics.recordRequest(ctx, SignalType.ON_COMPLETE, null);
        metrics.recordRateLimitRejection("caller");
        metrics.recordHealthEvent(HealthEventType.EJECT, "连续失败");

        Set<String> tagKeys = registry.getMeters().stream()
                .flatMap(meter -> meter.getId().getTags().stream())
                .map(Tag::getKey)
                .collect(Collectors.toSet());

        assertThat(tagKeys).doesNotContain("caller", "callerId", "channel", "channelId", "traceId", "clientIp");
        // 允许的标签均为取值可枚举的低基数维度
        assertThat(tagKeys).contains("protocol", "endpoint", "model", "status", "error", "scope", "type", "event");
    }
}
