package tech.qunxu.module.gateway.pipeline.filters;

import tech.qunxu.module.gateway.common.enums.CallStatus;
import tech.qunxu.module.gateway.common.enums.Capability;
import tech.qunxu.module.gateway.common.enums.ProtocolType;
import tech.qunxu.module.gateway.common.exception.ErrorCode;
import tech.qunxu.module.gateway.common.exception.GatewayException;
import tech.qunxu.module.gateway.common.model.TokenUsage;
import tech.qunxu.module.gateway.common.model.UnifiedMessage;
import tech.qunxu.module.gateway.common.model.UnifiedRequest;
import tech.qunxu.module.gateway.common.model.UnifiedResponse;
import tech.qunxu.module.gateway.common.model.UnifiedStreamChunk;
import tech.qunxu.module.gateway.moderation.ModerationDecision;
import tech.qunxu.module.gateway.moderation.ModerationResult;
import tech.qunxu.module.gateway.moderation.ModerationService;
import tech.qunxu.module.gateway.pipeline.FilterOrder;
import tech.qunxu.module.gateway.pipeline.PipelineChain;
import tech.qunxu.module.gateway.pipeline.RequestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 输入/输出内容审计过滤器单元测试（M7d）：验证 BLOCK 短路（状态置 BLOCKED + 400 {@code content_blocked}）、
 * REVIEW 标记放行、PASS 无痕放行、审计串「保留更严重裁决」的合并语义、开关与无文本短路，
 * 以及流式「聚合后审计」——命中 BLOCK 时在释放任何内容帧<b>之前</b>失败，违规内容不外泄。
 */
class ModerationFiltersTest {

    private ModerationService moderationService;
    private InputModerationFilter inputFilter;
    private OutputModerationFilter outputFilter;
    private RecordingChain chain;

    private static final class RecordingChain implements PipelineChain {
        private int executions;

        @Override
        public Mono<Void> execute(RequestContext ctx) {
            executions++;
            return Mono.empty();
        }
    }

    @BeforeEach
    void setUp() {
        moderationService = mock(ModerationService.class);
        when(moderationService.isEnabled()).thenReturn(true);
        inputFilter = new InputModerationFilter(moderationService);
        outputFilter = new OutputModerationFilter(moderationService);
        chain = new RecordingChain();
    }

    private void verdict(ModerationResult result) {
        when(moderationService.moderate(any(), any(), any(), any(), any())).thenReturn(Mono.just(result));
    }

    private static ModerationResult block(String... categories) {
        return ModerationResult.of(ModerationDecision.BLOCK, List.of(categories), "命中违规内容", "builtin");
    }

    private static ModerationResult review(String... categories) {
        return ModerationResult.of(ModerationDecision.REVIEW, List.of(categories), "疑似违规", "builtin");
    }

    private static RequestContext chatContext(String userText) {
        UnifiedRequest req = UnifiedRequest.chat("deepseek-chat", false,
                new UnifiedRequest.ChatPayload(List.of(UnifiedMessage.user(userText)), null, null, null));
        RequestContext ctx = RequestContext.create(ProtocolType.OPENAI, "POST",
                "/v1/chat/completions", "chat.completions", "trace-m1", req);
        ctx.setLogicalModelName("deepseek-chat");
        return ctx;
    }

    // ---- 输入方向 ----

    @Test
    void inputBlocksAndShortCircuits() {
        verdict(block("色情"));
        RequestContext ctx = chatContext("违规内容");

        StepVerifier.create(inputFilter.filter(ctx, chain))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(GatewayException.class);
                    assertThat(((GatewayException) e).getErrorCode()).isEqualTo(ErrorCode.CONTENT_BLOCKED);
                    assertThat(e.getMessage()).isEqualTo("命中违规内容");
                })
                .verify(Duration.ofSeconds(5));

        assertThat(chain.executions).isZero();
        assertThat(ctx.getStatus()).isEqualTo(CallStatus.BLOCKED);
        assertThat(ctx.getErrorCode()).isEqualTo(ErrorCode.CONTENT_BLOCKED);
        assertThat(ctx.getErrorMessage()).isEqualTo("命中违规内容");
        assertThat(ctx.getModerationResult()).isEqualTo("BLOCK:色情");
    }

    @Test
    void inputMarksReviewAndContinues() {
        verdict(review("广告"));
        RequestContext ctx = chatContext("可疑内容");

        StepVerifier.create(inputFilter.filter(ctx, chain)).verifyComplete();

        assertThat(chain.executions).isEqualTo(1);
        assertThat(ctx.getModerationResult()).isEqualTo("REVIEW:广告");
        assertThat(ctx.getStatus()).isNull();
        assertThat(ctx.getErrorCode()).isNull();
    }

    @Test
    void inputLeavesNoTraceOnPass() {
        verdict(ModerationResult.pass("builtin"));
        RequestContext ctx = chatContext("良性内容");

        StepVerifier.create(inputFilter.filter(ctx, chain)).verifyComplete();

        assertThat(chain.executions).isEqualTo(1);
        assertThat(ctx.getModerationResult()).isNull();
    }

    @Test
    void inputModeratesAggregatedRequestText() {
        verdict(ModerationResult.pass("builtin"));
        UnifiedRequest req = UnifiedRequest.chat("glm-4", false, new UnifiedRequest.ChatPayload(
                List.of(UnifiedMessage.system("你是助手"), UnifiedMessage.user("讲个笑话")), null, null, null));
        RequestContext ctx = RequestContext.create(ProtocolType.OPENAI, "POST",
                "/v1/chat/completions", "chat.completions", "trace-m2", req);
        ctx.setLogicalModelName("glm-4");

        StepVerifier.create(inputFilter.filter(ctx, chain)).verifyComplete();

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(moderationService).moderate(text.capture(), eq("INPUT"), eq(Capability.CHAT),
                eq("glm-4"), eq("default"));
        assertThat(text.getValue()).isEqualTo("你是助手\n讲个笑话\n");
    }

    @Test
    void inputKeepsMoreSevereExistingVerdict() {
        verdict(review("广告"));
        RequestContext ctx = chatContext("可疑内容");
        ctx.setModerationResult("BLOCK:色情");

        StepVerifier.create(inputFilter.filter(ctx, chain)).verifyComplete();

        // 合并保留更严重者，避免输出侧降级覆盖输入侧的强处置
        assertThat(ctx.getModerationResult()).isEqualTo("BLOCK:色情");
    }

    @Test
    void inputEscalatesToMoreSevereVerdict() {
        verdict(block("暴恐"));
        RequestContext ctx = chatContext("违规内容");
        ctx.setModerationResult("REVIEW:广告");

        StepVerifier.create(inputFilter.filter(ctx, chain))
                .expectError(GatewayException.class)
                .verify(Duration.ofSeconds(5));

        assertThat(ctx.getModerationResult()).isEqualTo("BLOCK:暴恐");
    }

    @Test
    void disabledModerationPassesThrough() {
        when(moderationService.isEnabled()).thenReturn(false);
        RequestContext ctx = chatContext("任意内容");

        StepVerifier.create(inputFilter.filter(ctx, chain)).verifyComplete();

        assertThat(chain.executions).isEqualTo(1);
        verify(moderationService, never()).moderate(any(), any(), any(), any(), any());
    }

    @Test
    void requestWithoutTextPassesThrough() {
        UnifiedRequest stt = UnifiedRequest.stt("paraformer",
                new UnifiedRequest.SttPayload(new byte[]{1}, "a.mp3", "zh", "json"));
        RequestContext ctx = RequestContext.create(ProtocolType.OPENAI, "POST",
                "/v1/audio/transcriptions", "audio.transcriptions", "trace-m3", stt);

        StepVerifier.create(inputFilter.filter(ctx, chain)).verifyComplete();

        assertThat(chain.executions).isEqualTo(1);
        verify(moderationService, never()).moderate(any(), any(), any(), any(), any());
    }

    @Test
    void exposesInputModerationOrder() {
        assertThat(inputFilter.order()).isEqualTo(FilterOrder.INPUT_MODERATION);
        assertThat(inputFilter.order()).isGreaterThan(FilterOrder.INPUT_GUARDRAIL);
        assertThat(inputFilter.order()).isLessThan(FilterOrder.ROUTE);
    }

    // ---- 输出方向（非流式） ----

    private static RequestContext contextWithResponse(String content) {
        RequestContext ctx = chatContext("你好");
        ctx.setResponse(UnifiedResponse.chat("resp-1", "deepseek-chat", 1700000000L, "stop",
                content, TokenUsage.of(3, 4)));
        return ctx;
    }

    @Test
    void outputBlocksAndShortCircuits() {
        verdict(block("涉政"));
        RequestContext ctx = contextWithResponse("违规回答");

        StepVerifier.create(outputFilter.filter(ctx, chain))
                .expectErrorSatisfies(e -> assertThat(((GatewayException) e).getErrorCode())
                        .isEqualTo(ErrorCode.CONTENT_BLOCKED))
                .verify(Duration.ofSeconds(5));

        assertThat(chain.executions).isZero();
        assertThat(ctx.getStatus()).isEqualTo(CallStatus.BLOCKED);
        assertThat(ctx.getModerationResult()).isEqualTo("BLOCK:涉政");
    }

    @Test
    void outputMarksReviewAndReleasesResponse() {
        verdict(review("辱骂"));
        RequestContext ctx = contextWithResponse("可疑回答");
        UnifiedResponse before = ctx.getResponse();

        StepVerifier.create(outputFilter.filter(ctx, chain)).verifyComplete();

        assertThat(chain.executions).isEqualTo(1);
        // 内容审计只做裁决，不改写响应正文（脱敏由围栏负责）
        assertThat(ctx.getResponse()).isSameAs(before);
        assertThat(ctx.getModerationResult()).isEqualTo("REVIEW:辱骂");
    }

    @Test
    void outputPassesBenignResponse() {
        verdict(ModerationResult.pass("builtin"));
        RequestContext ctx = contextWithResponse("正常回答");

        StepVerifier.create(outputFilter.filter(ctx, chain)).verifyComplete();

        assertThat(chain.executions).isEqualTo(1);
        assertThat(ctx.getModerationResult()).isNull();
    }

    @Test
    void outputSkipsWhenNoResponseOrNoText() {
        verdict(ModerationResult.pass("builtin"));

        RequestContext noResponse = chatContext("你好");
        StepVerifier.create(outputFilter.filter(noResponse, chain)).verifyComplete();

        RequestContext embeddings = chatContext("你好");
        embeddings.setCapability(Capability.EMBEDDING);
        embeddings.setResponse(new UnifiedResponse(Capability.EMBEDDING, "id", "m", 1L, null,
                List.of(new UnifiedResponse.Embedding(0, List.of(0.1f))), null, null, null, null));
        StepVerifier.create(outputFilter.filter(embeddings, chain)).verifyComplete();

        assertThat(chain.executions).isEqualTo(2);
        verify(moderationService, never()).moderate(any(), any(), any(), any(), any());
    }

    @Test
    void exposesOutputModerationOrder() {
        assertThat(outputFilter.order()).isEqualTo(FilterOrder.OUTPUT_MODERATION);
        assertThat(outputFilter.order()).isGreaterThan(FilterOrder.INVOKE);
        assertThat(outputFilter.order()).isLessThan(FilterOrder.OUTPUT_GUARDRAIL);
    }

    // ---- 输出方向（流式） ----

    private static List<UnifiedStreamChunk> chatStream(String... deltas) {
        List<UnifiedStreamChunk> chunks = new ArrayList<>();
        chunks.add(UnifiedStreamChunk.role("c1", "deepseek-chat", 1700000000L, "assistant"));
        for (String d : deltas) {
            chunks.add(UnifiedStreamChunk.content("c1", "deepseek-chat", 1700000000L, d));
        }
        chunks.add(UnifiedStreamChunk.end("c1", "deepseek-chat", 1700000000L, "stop", TokenUsage.of(3, 4)));
        return List.copyOf(chunks);
    }

    private static RequestContext streamingContext(List<UnifiedStreamChunk> chunks) {
        RequestContext ctx = RequestContext.create(ProtocolType.OPENAI, "POST",
                "/v1/chat/completions", "chat.completions", "trace-m4",
                UnifiedRequest.chat("deepseek-chat", true,
                        new UnifiedRequest.ChatPayload(List.of(UnifiedMessage.user("你好")), null, null, null)));
        ctx.setLogicalModelName("deepseek-chat");
        ctx.setStreamResult(Flux.fromIterable(chunks));
        return ctx;
    }

    @Test
    void streamingOutputBlocksBeforeReleasingChunks() {
        verdict(block("色情"));
        RequestContext ctx = streamingContext(chatStream("违规", "内容"));

        StepVerifier.create(outputFilter.filter(ctx, chain)).verifyComplete();
        assertThat(chain.executions).isEqualTo(1);

        StepVerifier.create(ctx.getStreamResult())
                .expectErrorSatisfies(e -> assertThat(((GatewayException) e).getErrorCode())
                        .isEqualTo(ErrorCode.CONTENT_BLOCKED))
                .verify(Duration.ofSeconds(5));

        assertThat(ctx.getStatus()).isEqualTo(CallStatus.BLOCKED);
        assertThat(ctx.getModerationResult()).isEqualTo("BLOCK:色情");
    }

    @Test
    void streamingOutputAuditsAggregatedTextAcrossFrames() {
        verdict(ModerationResult.pass("builtin"));
        RequestContext ctx = streamingContext(chatStream("第一段", "第二段"));

        StepVerifier.create(outputFilter.filter(ctx, chain)).verifyComplete();

        StepVerifier.create(ctx.getStreamResult())
                .expectNextCount(4)
                .verifyComplete();

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(moderationService).moderate(text.capture(), eq("OUTPUT"), eq(Capability.CHAT),
                eq("deepseek-chat"), eq("default"));
        assertThat(text.getValue()).isEqualTo("第一段第二段");
    }

    @Test
    void streamingOutputReleasesOriginalChunksOnReview() {
        verdict(review("广告"));
        List<UnifiedStreamChunk> chunks = chatStream("可疑", "内容");
        RequestContext ctx = streamingContext(chunks);

        StepVerifier.create(outputFilter.filter(ctx, chain)).verifyComplete();

        assertThat(ctx.getStreamResult().collectList().block(Duration.ofSeconds(5)))
                .containsExactlyElementsOf(chunks);
        assertThat(ctx.getModerationResult()).isEqualTo("REVIEW:广告");
        assertThat(ctx.getStatus()).isNull();
    }

    @Test
    void streamingOutputWithoutContentSkipsAudit() {
        verdict(ModerationResult.pass("builtin"));
        List<UnifiedStreamChunk> chunks = List.of(
                UnifiedStreamChunk.role("c1", "m", 1L, "assistant"),
                UnifiedStreamChunk.end("c1", "m", 1L, "stop", null));
        RequestContext ctx = streamingContext(chunks);

        StepVerifier.create(outputFilter.filter(ctx, chain)).verifyComplete();

        assertThat(ctx.getStreamResult().collectList().block(Duration.ofSeconds(5)))
                .containsExactlyElementsOf(chunks);
        verify(moderationService, never()).moderate(any(), any(), any(), any(), any());
    }

    @Test
    void disabledModerationKeepsStreamUntouched() {
        when(moderationService.isEnabled()).thenReturn(false);
        RequestContext ctx = streamingContext(chatStream("任意"));
        Flux<UnifiedStreamChunk> before = ctx.getStreamResult();

        StepVerifier.create(outputFilter.filter(ctx, chain)).verifyComplete();

        assertThat(ctx.getStreamResult()).isSameAs(before);
        verify(moderationService, never()).moderate(any(), any(), any(), any(), any());
    }
}
