package tech.qunxu.module.gateway.pipeline.filters;

import tech.qunxu.module.gateway.common.enums.CallStatus;
import tech.qunxu.module.gateway.common.exception.ErrorCode;
import tech.qunxu.module.gateway.common.exception.GatewayException;
import tech.qunxu.module.gateway.common.model.UnifiedResponse;
import tech.qunxu.module.gateway.common.model.UnifiedStreamChunk;
import tech.qunxu.module.gateway.moderation.ModerationResult;
import tech.qunxu.module.gateway.moderation.ModerationService;
import tech.qunxu.module.gateway.pipeline.FilterOrder;
import tech.qunxu.module.gateway.pipeline.PipelineChain;
import tech.qunxu.module.gateway.pipeline.PipelineFilter;
import tech.qunxu.module.gateway.pipeline.RequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 输出内容审计过滤器（{@link FilterOrder#OUTPUT_MODERATION}，位于调用之后、写回客户端之前）。
 *
 * <p><b>非流式</b>：对响应文本执行审计，BLOCK 短路返回 400（{@link ErrorCode#CONTENT_BLOCKED}）——此时响应尚未提交，
 * 客户端只收到错误体。<b>流式</b>：包裹 {@code ctx.streamResult}，先 {@code collectList} <b>聚合</b>增量正文，
 * 审计通过后再逐帧释放；命中 BLOCK 时在释放任何内容帧<b>之前</b>失败，由协议层渲染流内错误帧，违规内容不外泄。
 * 命中（非 PASS）写入 {@code ctx.moderationResult}。</p>
 *
 * <p>流式「聚合后校验」以牺牲增量实时性换取输出侧的强一致拦截；{@code gateway.moderation.enabled=false}
 * 或响应无文本产出（如 embeddings/TTS）时直接透传，流式保持边到边转发。</p>
 */
@Component
public class OutputModerationFilter implements PipelineFilter {

    private static final Logger log = LoggerFactory.getLogger(OutputModerationFilter.class);
    private static final String DIRECTION = "OUTPUT";

    private final ModerationService moderationService;

    public OutputModerationFilter(ModerationService moderationService) {
        this.moderationService = moderationService;
    }

    @Override
    public int order() {
        return FilterOrder.OUTPUT_MODERATION;
    }

    @Override
    public Mono<Void> filter(RequestContext ctx, PipelineChain chain) {
        if (!moderationService.isEnabled()) {
            return chain.execute(ctx);
        }
        String tenant = ctx.getCaller() != null ? ctx.getCaller().tenant() : null;
        String logicalModel = ctx.getLogicalModelName();

        Flux<UnifiedStreamChunk> stream = ctx.getStreamResult();
        if (stream != null) {
            ctx.setStreamResult(wrapStream(ctx, stream, logicalModel, tenant));
            return chain.execute(ctx);
        }

        UnifiedResponse response = ctx.getResponse();
        String text = SafetyTexts.outputText(response);
        if (response == null || text.isEmpty()) {
            return chain.execute(ctx);
        }
        return moderationService.moderate(text, DIRECTION, ctx.getCapability(), logicalModel, tenant)
                .flatMap(result -> {
                    if (result.flagged()) {
                        ctx.setModerationResult(
                                SafetyTexts.mergeModeration(ctx.getModerationResult(), result.toAuditString()));
                    }
                    if (result.blocked()) {
                        markBlocked(ctx, result);
                        return Mono.error(blockedException(result));
                    }
                    return chain.execute(ctx);
                });
    }

    /** 流式：聚合增量正文后审计；通过则原样逐帧释放，BLOCK 则在释放前失败。 */
    private Flux<UnifiedStreamChunk> wrapStream(RequestContext ctx, Flux<UnifiedStreamChunk> stream,
                                                String logicalModel, String tenant) {
        return stream.collectList()
                .flatMapMany(chunks -> {
                    String text = SafetyTexts.aggregateContent(chunks);
                    if (text.isEmpty()) {
                        return Flux.fromIterable(chunks);
                    }
                    return moderationService.moderate(text, DIRECTION, ctx.getCapability(), logicalModel, tenant)
                            .flatMapMany(result -> {
                                if (result.flagged()) {
                                    ctx.setModerationResult(SafetyTexts.mergeModeration(
                                            ctx.getModerationResult(), result.toAuditString()));
                                }
                                if (result.blocked()) {
                                    markBlocked(ctx, result);
                                    return Flux.error(blockedException(result));
                                }
                                return Flux.fromIterable(chunks);
                            });
                });
    }

    private void markBlocked(RequestContext ctx, ModerationResult result) {
        ctx.setStatus(CallStatus.BLOCKED);
        ctx.setErrorCode(ErrorCode.CONTENT_BLOCKED);
        ctx.setErrorMessage(result.detail());
        log.info("输出内容审计拦截 trace={} result={}", ctx.getTraceId(), result.toAuditString());
    }

    private static GatewayException blockedException(ModerationResult result) {
        return new GatewayException(ErrorCode.CONTENT_BLOCKED,
                result.detail() != null ? result.detail() : ErrorCode.CONTENT_BLOCKED.defaultMessage());
    }
}
