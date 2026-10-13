package tech.qunxu.module.gateway.pipeline.filters;

import tech.qunxu.module.gateway.common.enums.CallStatus;
import tech.qunxu.module.gateway.common.exception.ErrorCode;
import tech.qunxu.module.gateway.common.exception.GatewayException;
import tech.qunxu.module.gateway.moderation.ModerationResult;
import tech.qunxu.module.gateway.moderation.ModerationService;
import tech.qunxu.module.gateway.pipeline.FilterOrder;
import tech.qunxu.module.gateway.pipeline.PipelineChain;
import tech.qunxu.module.gateway.pipeline.PipelineFilter;
import tech.qunxu.module.gateway.pipeline.RequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 输入内容审计过滤器（{@link FilterOrder#INPUT_MODERATION}，位于输入围栏之后、路由之前）。
 *
 * <p>对请求文本（按能力聚合）调用 {@link ModerationService}，其内部已按策略作用域选定提供方并结合策略
 * {@code action} 得出<b>最终执行裁决</b>：<b>BLOCK</b> 短路返回 400（{@link ErrorCode#CONTENT_BLOCKED}）并置审计状态
 * BLOCKED；<b>REVIEW</b> 标记复核后放行；<b>PASS</b> 直接放行。命中（非 PASS）写入 {@code ctx.moderationResult}。</p>
 *
 * <p>{@code gateway.moderation.enabled=false} 或请求无文本时直接透传。审计失败开放（见 {@link ModerationService}），
 * 不因审计服务抖动阻断主链。</p>
 */
@Component
public class InputModerationFilter implements PipelineFilter {

    private static final Logger log = LoggerFactory.getLogger(InputModerationFilter.class);

    private final ModerationService moderationService;

    public InputModerationFilter(ModerationService moderationService) {
        this.moderationService = moderationService;
    }

    @Override
    public int order() {
        return FilterOrder.INPUT_MODERATION;
    }

    @Override
    public Mono<Void> filter(RequestContext ctx, PipelineChain chain) {
        if (!moderationService.isEnabled()) {
            return chain.execute(ctx);
        }
        String text = SafetyTexts.inputText(ctx.getRequest());
        if (text.isEmpty()) {
            return chain.execute(ctx);
        }
        String tenant = ctx.getCaller() != null ? ctx.getCaller().tenant() : null;
        String logicalModel = ctx.getLogicalModelName();
        return moderationService.moderate(text, "INPUT", ctx.getCapability(), logicalModel, tenant)
                .flatMap(result -> {
                    record(ctx, result);
                    if (result.blocked()) {
                        block(ctx, result);
                        return Mono.error(new GatewayException(ErrorCode.CONTENT_BLOCKED,
                                result.detail() != null ? result.detail()
                                        : ErrorCode.CONTENT_BLOCKED.defaultMessage()));
                    }
                    return chain.execute(ctx);
                });
    }

    private void record(RequestContext ctx, ModerationResult result) {
        if (result.flagged()) {
            ctx.setModerationResult(SafetyTexts.mergeModeration(ctx.getModerationResult(), result.toAuditString()));
        }
    }

    private void block(RequestContext ctx, ModerationResult result) {
        ctx.setStatus(CallStatus.BLOCKED);
        ctx.setErrorCode(ErrorCode.CONTENT_BLOCKED);
        ctx.setErrorMessage(result.detail());
        log.info("输入内容审计拦截 trace={} result={}", ctx.getTraceId(), result.toAuditString());
    }
}
