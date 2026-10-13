package tech.qunxu.module.gateway.pipeline.filters;

import tech.qunxu.module.gateway.audit.AuditService;
import tech.qunxu.module.gateway.pipeline.FilterOrder;
import tech.qunxu.module.gateway.pipeline.PipelineChain;
import tech.qunxu.module.gateway.pipeline.PipelineFilter;
import tech.qunxu.module.gateway.pipeline.RequestContext;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 审计包裹过滤器（流水线<b>最外层</b>，{@link FilterOrder#AUDIT}）。
 *
 * <p>以最外层进入并包裹整条链，从而在 {@code doFinally} 中于链路结束后统一组装审计，覆盖包括鉴权/限流/
 * 模型解析/路由等<b>早期失败</b>在内的全部结果——这些失败会短路内层过滤器，若审计置于链尾则无法捕获。</p>
 *
 * <p>{@code doOnError} 先把错误归一化写入上下文（状态/错误码/信息，不覆盖已显式设置的 BLOCKED），
 * {@code doFinally} 再据终止信号（完成/错误/取消）派生最终状态并异步落库，全程不阻塞、不改变主链结果。</p>
 */
@Component
public class AuditFilter implements PipelineFilter {

    private final AuditService auditService;

    public AuditFilter(AuditService auditService) {
        this.auditService = auditService;
    }

    @Override
    public int order() {
        return FilterOrder.AUDIT;
    }

    @Override
    public Mono<Void> filter(RequestContext ctx, PipelineChain chain) {
        if (!auditService.isEnabled()) {
            return chain.execute(ctx);
        }
        return Mono.defer(() -> chain.execute(ctx))
                .doOnError(err -> auditService.captureError(ctx, err))
                .doFinally(signal -> auditService.record(ctx, signal));
    }
}
