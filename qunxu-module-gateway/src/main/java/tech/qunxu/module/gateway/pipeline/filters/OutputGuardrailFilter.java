package tech.qunxu.module.gateway.pipeline.filters;

import tech.qunxu.module.gateway.common.enums.CallStatus;
import tech.qunxu.module.gateway.common.exception.ErrorCode;
import tech.qunxu.module.gateway.common.exception.GatewayException;
import tech.qunxu.module.gateway.common.model.UnifiedResponse;
import tech.qunxu.module.gateway.common.model.UnifiedStreamChunk;
import tech.qunxu.module.gateway.config.GatewayProperties;
import tech.qunxu.module.gateway.guardrail.GuardrailContext;
import tech.qunxu.module.gateway.guardrail.GuardrailDirection;
import tech.qunxu.module.gateway.guardrail.GuardrailEngine;
import tech.qunxu.module.gateway.guardrail.GuardrailOutcome;
import tech.qunxu.module.gateway.guardrail.GuardrailPolicyService;
import tech.qunxu.module.gateway.guardrail.Redactions;
import tech.qunxu.module.gateway.pipeline.FilterOrder;
import tech.qunxu.module.gateway.pipeline.PipelineChain;
import tech.qunxu.module.gateway.pipeline.PipelineFilter;
import tech.qunxu.module.gateway.pipeline.RequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 输出安全围栏过滤器（{@link FilterOrder#OUTPUT_GUARDRAIL}，位于输出内容审计之后、写回客户端之前）。
 *
 * <p><b>非流式</b>：对响应文本执行 {@link GuardrailEngine}，BLOCK 短路返回 400（{@link ErrorCode#GUARDRAIL_BLOCKED}），
 * MASK 逐段脱敏后<b>保结构回填</b>响应再写回。<b>流式</b>：包裹 {@code ctx.streamResult}，{@code collectList} 聚合增量正文后
 * 校验——BLOCK 在释放任何内容帧前失败（违规不外泄），MASK 以脱敏后的聚合全文重建为「role→content→end」帧序列，
 * ALLOW 原样逐帧释放。命中写入 {@code ctx.guardrailHit} 供审计。</p>
 *
 * <p>{@code gateway.guardrail.enabled=false} 或响应无文本产出时直接透传，流式保持边到边转发。</p>
 */
@Component
public class OutputGuardrailFilter implements PipelineFilter {

    private static final Logger log = LoggerFactory.getLogger(OutputGuardrailFilter.class);

    private final GuardrailEngine engine;
    private final GuardrailPolicyService policyService;
    private final GatewayProperties properties;

    public OutputGuardrailFilter(GuardrailEngine engine,
                                 GuardrailPolicyService policyService,
                                 GatewayProperties properties) {
        this.engine = engine;
        this.policyService = policyService;
        this.properties = properties;
    }

    @Override
    public int order() {
        return FilterOrder.OUTPUT_GUARDRAIL;
    }

    @Override
    public Mono<Void> filter(RequestContext ctx, PipelineChain chain) {
        if (!properties.getGuardrail().isEnabled()) {
            return chain.execute(ctx);
        }
        String tenant = ctx.getCaller() != null ? ctx.getCaller().tenant() : null;
        String logicalModel = ctx.getLogicalModelName();
        return policyService.resolveConfig(tenant, logicalModel)
                .flatMap(config -> apply(ctx, chain, config, logicalModel, tenant));
    }

    private Mono<Void> apply(RequestContext ctx, PipelineChain chain, Map<String, Object> config,
                             String logicalModel, String tenant) {
        Flux<UnifiedStreamChunk> stream = ctx.getStreamResult();
        if (stream != null) {
            ctx.setStreamResult(wrapStream(ctx, stream, config, logicalModel, tenant));
            return chain.execute(ctx);
        }

        UnifiedResponse response = ctx.getResponse();
        String text = SafetyTexts.outputText(response);
        if (response == null || text.isEmpty()) {
            return chain.execute(ctx);
        }
        GuardrailContext gctx = new GuardrailContext(text, GuardrailDirection.OUTPUT,
                ctx.getCapability(), logicalModel, tenant, config);
        GuardrailOutcome outcome = engine.evaluate(gctx);
        if (outcome.triggered()) {
            ctx.setGuardrailHit(SafetyTexts.mergeHit(ctx.getGuardrailHit(), outcome.hit()));
        }
        if (outcome.blocked()) {
            markBlocked(ctx, outcome);
            return Mono.error(blockedException(outcome));
        }
        // MASK 先脱敏；随后无论是否脱敏，都把输入围栏登记的占位符本地还原（ALLOW 但回应引用占位符同样还原）
        UnifiedResponse afterMask = outcome.masked()
                ? SafetyTexts.maskOutput(response, seg -> engine.evaluate(gctx.withText(seg)).text())
                : response;
        Map<String, String> redactions = ctx.getRedactionMap();
        if (redactions != null && !redactions.isEmpty()) {
            ctx.setResponse(SafetyTexts.restoreOutput(afterMask, seg -> Redactions.restore(seg, redactions)));
        } else if (outcome.masked()) {
            ctx.setResponse(afterMask);
        }
        if (outcome.masked()) {
            log.debug("输出围栏脱敏 trace={} hit={}", ctx.getTraceId(), outcome.hit());
        }
        return chain.execute(ctx);
    }

    /** 流式：聚合增量正文后校验；ALLOW 原样释放，MASK 以脱敏全文重建帧序列，BLOCK 在释放前失败。
     *  无论是否脱敏，都把输入围栏登记的占位符本地还原（还原产生变化时才重建帧）。 */
    private Flux<UnifiedStreamChunk> wrapStream(RequestContext ctx, Flux<UnifiedStreamChunk> stream,
                                                Map<String, Object> config, String logicalModel, String tenant) {
        return stream.collectList()
                .flatMapMany(chunks -> {
                    String text = SafetyTexts.aggregateContent(chunks);
                    if (text.isEmpty()) {
                        return Flux.fromIterable(chunks);
                    }
                    GuardrailContext gctx = new GuardrailContext(text, GuardrailDirection.OUTPUT,
                            ctx.getCapability(), logicalModel, tenant, config);
                    GuardrailOutcome outcome = engine.evaluate(gctx);
                    if (outcome.triggered()) {
                        ctx.setGuardrailHit(SafetyTexts.mergeHit(ctx.getGuardrailHit(), outcome.hit()));
                    }
                    if (outcome.blocked()) {
                        markBlocked(ctx, outcome);
                        return Flux.error(blockedException(outcome));
                    }
                    String finalText = outcome.masked() ? outcome.text() : text;
                    Map<String, String> redactions = ctx.getRedactionMap();
                    String restored = Redactions.restore(finalText, redactions);
                    if (outcome.masked() || !restored.equals(finalText)) {
                        return Flux.fromIterable(SafetyTexts.rebuildMaskedStream(chunks, restored));
                    }
                    return Flux.fromIterable(chunks);
                });
    }

    private void markBlocked(RequestContext ctx, GuardrailOutcome outcome) {
        ctx.setStatus(CallStatus.BLOCKED);
        ctx.setErrorCode(ErrorCode.GUARDRAIL_BLOCKED);
        ctx.setErrorMessage(outcome.reason());
        log.info("输出围栏拦截 trace={} hit={} reason={}", ctx.getTraceId(), outcome.hit(), outcome.reason());
    }

    private static GatewayException blockedException(GuardrailOutcome outcome) {
        return new GatewayException(ErrorCode.GUARDRAIL_BLOCKED,
                outcome.reason() != null ? outcome.reason() : ErrorCode.GUARDRAIL_BLOCKED.defaultMessage());
    }
}
