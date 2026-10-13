package tech.qunxu.module.gateway.audit;

import tech.qunxu.module.gateway.common.enums.CallStatus;
import tech.qunxu.module.gateway.common.enums.ProtocolType;
import tech.qunxu.module.gateway.common.exception.ErrorCode;
import tech.qunxu.module.gateway.common.exception.GatewayException;
import tech.qunxu.module.gateway.common.model.TokenUsage;
import tech.qunxu.module.gateway.common.model.UnifiedMessage;
import tech.qunxu.module.gateway.common.model.UnifiedRequest;
import tech.qunxu.module.gateway.common.model.UnifiedResponse;
import tech.qunxu.module.gateway.config.GatewayProperties;
import tech.qunxu.module.gateway.persistence.entity.ModelChannelEntity;
import tech.qunxu.module.gateway.persistence.entity.ProviderEntity;
import tech.qunxu.module.gateway.pipeline.RequestContext;
import tech.qunxu.module.gateway.security.CallerIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.SignalType;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link AuditService} 单元测试：验证从 {@link RequestContext} 组装 {@link AuditRecord} 的字段映射、
 * 状态派生（成功/失败/取消/显式 BLOCKED）、错误捕获、请求/响应体来源（unary 序列化 vs 流式聚合）、
 * 凭证脱敏与长度截断，以及启用开关对提交的影响。{@link BatchAuditWriter} 以 mock 替换。
 */
class AuditServiceTest {

    private BatchAuditWriter writer;
    private GatewayProperties props;
    private AuditService service;

    @BeforeEach
    void setUp() {
        writer = mock(BatchAuditWriter.class);
        props = new GatewayProperties();
        props.getAudit().setEnabled(true);
        props.getAudit().setMaskSensitive(true);
        props.getAudit().setMaxBodyLength(8192);
        service = new AuditService(writer, props, new ObjectMapper());
    }

    private RequestContext baseContext() {
        UnifiedRequest req = UnifiedRequest.chat("deepseek-chat", false,
                new UnifiedRequest.ChatPayload(List.of(UnifiedMessage.user("hi")), null, null, null));
        RequestContext ctx = RequestContext.create(ProtocolType.OPENAI, "POST",
                "/v1/chat/completions", "chat.completions", "trace-1", req);
        ctx.setCaller(CallerIdentity.of("alice", "Alice", "team-a", Set.of(), Map.of()));
        ctx.setClientIp("10.0.0.1");
        ctx.setLogicalModelName("deepseek-chat");

        ModelChannelEntity channel = new ModelChannelEntity();
        channel.setId(10L);
        channel.setProviderId(100L);
        channel.setUpstreamModel("deepseek-chat");
        ctx.setSelectedChannel(channel);

        ProviderEntity provider = new ProviderEntity();
        provider.setId(100L);
        provider.setCode("DEEPSEEK");
        ctx.setSelectedProvider(provider);

        ctx.setRawRequestBody("{\"model\":\"deepseek-chat\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
        return ctx;
    }

    @Test
    void buildMapsAllFieldsFromCompletedUnaryContext() {
        RequestContext ctx = baseContext();
        ctx.setResponse(UnifiedResponse.chat("chatcmpl-1", "deepseek-chat", 1700000000L,
                "stop", "Hello from stub", TokenUsage.of(10, 5)));
        ctx.setUsage(TokenUsage.of(10, 5));

        AuditRecord r = service.build(ctx, SignalType.ON_COMPLETE);

        assertThat(r.status()).isEqualTo(CallStatus.SUCCESS);
        assertThat(r.traceId()).isEqualTo("trace-1");
        assertThat(r.callerId()).isEqualTo("alice");
        assertThat(r.callerName()).isEqualTo("Alice");
        assertThat(r.tenant()).isEqualTo("team-a");
        assertThat(r.clientIp()).isEqualTo("10.0.0.1");
        assertThat(r.protocol()).isEqualTo("OPENAI");
        assertThat(r.endpoint()).isEqualTo("chat.completions");
        assertThat(r.logicalModel()).isEqualTo("deepseek-chat");
        assertThat(r.channelId()).isEqualTo(10L);
        assertThat(r.providerId()).isEqualTo(100L);
        assertThat(r.providerCode()).isEqualTo("DEEPSEEK");
        assertThat(r.upstreamModel()).isEqualTo("deepseek-chat");
        assertThat(r.promptTokens()).isEqualTo(10);
        assertThat(r.completionTokens()).isEqualTo(5);
        assertThat(r.totalTokens()).isEqualTo(15);
        assertThat(r.latencyMs()).isGreaterThanOrEqualTo(0L);
        assertThat(r.createdAt()).isNotNull();
        assertThat(r.errorCode()).isNull();
        // unary：响应体来自序列化 UnifiedResponse
        assertThat(r.responseBody()).contains("Hello from stub");
        assertThat(r.requestBody()).contains("\"model\":\"deepseek-chat\"");
    }

    @Test
    void buildUsesAggregatedStreamTextWhenNoUnaryResponse() {
        RequestContext ctx = baseContext();
        ctx.appendResponseText("Hello");
        ctx.appendResponseText(" world");
        ctx.setUsage(TokenUsage.of(8, 4));

        AuditRecord r = service.build(ctx, SignalType.ON_COMPLETE);

        assertThat(r.responseBody()).isEqualTo("Hello world");
        assertThat(r.totalTokens()).isEqualTo(12);
    }

    @Test
    void statusDerivedFromSignalWhenNotExplicitlySet() {
        assertThat(service.build(baseContext(), SignalType.ON_COMPLETE).status()).isEqualTo(CallStatus.SUCCESS);
        assertThat(service.build(baseContext(), SignalType.ON_ERROR).status()).isEqualTo(CallStatus.FAILURE);

        AuditRecord cancelled = service.build(baseContext(), SignalType.CANCEL);
        assertThat(cancelled.status()).isEqualTo(CallStatus.FAILURE);
        assertThat(cancelled.errorMsg()).isEqualTo("客户端取消/断开");
    }

    @Test
    void explicitBlockedStatusIsPreservedOverSignal() {
        RequestContext ctx = baseContext();
        ctx.setStatus(CallStatus.BLOCKED);
        ctx.setErrorCode(ErrorCode.GUARDRAIL_BLOCKED);
        ctx.setGuardrailHit("sensitive_word");

        AuditRecord r = service.build(ctx, SignalType.ON_COMPLETE);

        assertThat(r.status()).isEqualTo(CallStatus.BLOCKED);
        assertThat(r.errorCode()).isEqualTo("GUARDRAIL_BLOCKED");
        assertThat(r.guardrailHit()).isEqualTo("sensitive_word");
    }

    @Test
    void logicalModelNameFallsBackToRequestModel() {
        RequestContext ctx = baseContext();
        ctx.setLogicalModelName(null);

        assertThat(service.build(ctx, SignalType.ON_COMPLETE).logicalModel()).isEqualTo("deepseek-chat");
    }

    @Test
    void captureErrorSetsFailureAndCodeWithoutOverridingExisting() {
        RequestContext ctx = baseContext();
        service.captureError(ctx, new GatewayException(ErrorCode.PROVIDER_ERROR, "boom"));
        assertThat(ctx.getStatus()).isEqualTo(CallStatus.FAILURE);
        assertThat(ctx.getErrorCode()).isEqualTo(ErrorCode.PROVIDER_ERROR);
        assertThat(ctx.getErrorMessage()).isEqualTo("boom");

        // 已显式设置 BLOCKED 时不被覆盖
        RequestContext blocked = baseContext();
        blocked.setStatus(CallStatus.BLOCKED);
        blocked.setErrorCode(ErrorCode.GUARDRAIL_BLOCKED);
        service.captureError(blocked, new GatewayException(ErrorCode.PROVIDER_ERROR, "late"));
        assertThat(blocked.getStatus()).isEqualTo(CallStatus.BLOCKED);
        assertThat(blocked.getErrorCode()).isEqualTo(ErrorCode.GUARDRAIL_BLOCKED);
    }

    @Test
    void captureErrorFallsBackToInternalErrorForUnknownThrowable() {
        RequestContext ctx = baseContext();
        service.captureError(ctx, new RuntimeException("unexpected"));
        assertThat(ctx.getErrorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR);
    }

    @Test
    void recordSubmitsBuiltRecordToWriter() {
        RequestContext ctx = baseContext();
        ctx.setResponse(UnifiedResponse.chat("id", "deepseek-chat", 1L, "stop", "ok", TokenUsage.of(1, 1)));

        service.record(ctx, SignalType.ON_COMPLETE);

        ArgumentCaptor<AuditRecord> captor = ArgumentCaptor.forClass(AuditRecord.class);
        verify(writer).submit(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(CallStatus.SUCCESS);
        assertThat(captor.getValue().logicalModel()).isEqualTo("deepseek-chat");
    }

    @Test
    void recordIsNoopWhenDisabled() {
        props.getAudit().setEnabled(false);
        service.record(baseContext(), SignalType.ON_COMPLETE);
        verify(writer, never()).submit(any());
    }

    @Test
    void masksCredentialsInRequestBody() {
        RequestContext ctx = baseContext();
        ctx.setRawRequestBody("{\"api_key\":\"sk-abcdef123456\",\"authorization\":\"Bearer eyJhbGci.abc-def\"}");

        AuditRecord r = service.build(ctx, SignalType.ON_COMPLETE);

        assertThat(r.requestBody()).contains("****");
        assertThat(r.requestBody()).doesNotContain("sk-abcdef123456");
        assertThat(r.requestBody()).doesNotContain("eyJhbGci");
    }

    @Test
    void maskingCanBeDisabled() {
        props.getAudit().setMaskSensitive(false);
        RequestContext ctx = baseContext();
        ctx.setRawRequestBody("{\"api_key\":\"sk-abcdef123456\"}");

        assertThat(service.build(ctx, SignalType.ON_COMPLETE).requestBody()).contains("sk-abcdef123456");
    }

    @Test
    void truncatesRequestBodyToMaxBodyLength() {
        props.getAudit().setMaxBodyLength(20);
        RequestContext ctx = baseContext();
        ctx.setRawRequestBody("x".repeat(100));

        AuditRecord r = service.build(ctx, SignalType.ON_COMPLETE);

        assertThat(r.requestBody()).startsWith("xxxxxxxxxxxxxxxxxxxx");
        assertThat(r.requestBody()).contains("...[truncated 80 chars]");
    }

    @Test
    void maskSecretsCoversCommonCredentialShapes() {
        assertThat(AuditService.maskSecrets("apikey=abc123")).isEqualTo("apikey=****");
        // 键值形式：整体吞掉 Bearer 前缀与令牌，避免泄露真实 token
        assertThat(AuditService.maskSecrets("Authorization: Bearer xyz.123-abc"))
                .isEqualTo("Authorization: ****");
        // 独立 Bearer 令牌
        assertThat(AuditService.maskSecrets("Bearer xyz.123-abc")).isEqualTo("Bearer ****");
        assertThat(AuditService.maskSecrets("token sk-live_0123456789 end")).contains("sk-****");
        assertThat(AuditService.maskSecrets(null)).isNull();
        assertThat(AuditService.maskSecrets("no secrets here")).isEqualTo("no secrets here");
    }

    @Test
    void truncateRespectsLimitAndHandlesEdgeCases() {
        assertThat(AuditService.truncate(null, 10)).isNull();
        assertThat(AuditService.truncate("short", 10)).isEqualTo("short");
        assertThat(AuditService.truncate("short", 0)).isEqualTo("short");   // max<=0 视为不限制
        assertThat(AuditService.truncate("0123456789", 4)).isEqualTo("0123...[truncated 6 chars]");
    }
}
