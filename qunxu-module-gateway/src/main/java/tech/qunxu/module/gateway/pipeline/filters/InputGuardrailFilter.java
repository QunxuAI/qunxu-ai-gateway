package tech.qunxu.module.gateway.pipeline.filters;

import tech.qunxu.module.gateway.common.enums.CallStatus;
import tech.qunxu.module.gateway.common.exception.ErrorCode;
import tech.qunxu.module.gateway.common.exception.GatewayException;
import tech.qunxu.module.gateway.common.model.UnifiedRequest;
import tech.qunxu.module.gateway.config.GatewayProperties;
import tech.qunxu.module.gateway.guardrail.GuardrailContext;
import tech.qunxu.module.gateway.guardrail.GuardrailDirection;
import tech.qunxu.module.gateway.guardrail.GuardrailEngine;
import tech.qunxu.module.gateway.guardrail.GuardrailOutcome;
import tech.qunxu.module.gateway.guardrail.GuardrailPolicyService;
import tech.qunxu.module.gateway.guardrail.RedactionCollector;
import tech.qunxu.module.gateway.pipeline.FilterOrder;
import tech.qunxu.module.gateway.pipeline.PipelineChain;
import tech.qunxu.module.gateway.pipeline.PipelineFilter;
import tech.qunxu.module.gateway.pipeline.RequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 输入安全围栏过滤器（{@link FilterOrder#INPUT_GUARDRAIL}，位于模型解析之后、路由之前）。
 *
 * <p>按作用域（租户/逻辑模型）解析围栏配置，对请求文本（按能力聚合）执行 {@link GuardrailEngine}：
 * <b>BLOCK</b> 短路返回 400（{@link ErrorCode#GUARDRAIL_BLOCKED}）并置审计状态为 BLOCKED；<b>MASK</b> 逐段脱敏
 * 后<b>保结构回填</b>请求继续下行；<b>ALLOW/WARN</b> 放行。任一命中都写入 {@code ctx.guardrailHit} 供审计。</p>
 *
 * <p>{@code gateway.guardrail.enabled=false} 或请求无文本（如 STT 音频）时直接透传，不做任何校验。</p>
 */
@Component
public class InputGuardrailFilter implements PipelineFilter {

    private static final Logger log = LoggerFactory.getLogger(InputGuardrailFilter.class);

    private final GuardrailEngine engine;
    private final GuardrailPolicyService policyService;
    private final GatewayProperties properties;

    public InputGuardrailFilter(GuardrailEngine engine,
                                GuardrailPolicyService policyService,
                                GatewayProperties properties) {
        this.engine = engine;
        this.policyService = policyService;
        this.properties = properties;
    }

    @Override
    public int order() {
        return FilterOrder.INPUT_GUARDRAIL;
    }

    @Override
    public Mono<Void> filter(RequestContext ctx, PipelineChain chain) {
        if (!properties.getGuardrail().isEnabled()) {
            return chain.execute(ctx);
        }
        UnifiedRequest request = ctx.getRequest();
        String text = SafetyTexts.inputText(request);
        if (text.isEmpty()) {
            return chain.execute(ctx);
        }
        String tenant = ctx.getCaller() != null ? ctx.getCaller().tenant() : null;
        String logicalModel = ctx.getLogicalModelName();
        return policyService.resolveConfig(tenant, logicalModel)
                .flatMap(config -> {
                    RedactionCollector collector = new RedactionCollector();
                    GuardrailContext gctx = new GuardrailContext(text, GuardrailDirection.INPUT,
                            ctx.getCapability(), logicalModel, tenant, config, collector);
                    GuardrailOutcome outcome = engine.evaluate(gctx);
                    if (outcome.triggered()) {
                        ctx.setGuardrailHit(SafetyTexts.mergeHit(ctx.getGuardrailHit(), outcome.hit()));
                    }
                    if (outcome.blocked()) {
                        ctx.setStatus(CallStatus.BLOCKED);
                        ctx.setErrorCode(ErrorCode.GUARDRAIL_BLOCKED);
                        ctx.setErrorMessage(outcome.reason());
                        log.info("输入围栏拦截 trace={} hit={} reason={}",
                                ctx.getTraceId(), outcome.hit(), outcome.reason());
                        return Mono.error(new GatewayException(ErrorCode.GUARDRAIL_BLOCKED,
                                outcome.reason() != null ? outcome.reason()
                                        : ErrorCode.GUARDRAIL_BLOCKED.defaultMessage()));
                    }
                    if (outcome.masked()) {
                        UnifiedRequest masked = SafetyTexts.maskInput(request,
                                seg -> engine.evaluate(gctx.withText(seg)).text());
                        ctx.setRequest(masked);
                        // 占位符模式下把「占位符 → 原文」映射存入上下文，供输出阶段本地还原（不落审计）
                        if (!collector.isEmpty()) {
                            ctx.setRedactionMap(collector.snapshot());
                        }
                        log.debug("输入围栏脱敏 trace={} hit={}", ctx.getTraceId(), outcome.hit());
                    }
                    return chain.execute(ctx);
                });
    }
}
