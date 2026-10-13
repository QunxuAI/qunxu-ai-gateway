package tech.qunxu.module.gateway.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Comparator;
import java.util.List;

/**
 * 网关流水线编排器。收集所有 {@link PipelineFilter} Bean，按 {@link PipelineFilter#order()} 升序编排，
 * 并在链尾执行传入的终结逻辑（通常是「调用适配器并将 Unified 响应写回客户端」）。
 *
 * <p>控制器构建 {@link RequestContext} 后调用 {@link #execute(RequestContext, Mono)} 驱动整条链。</p>
 */
@Component
public class GatewayPipeline {

    private static final Logger log = LoggerFactory.getLogger(GatewayPipeline.class);

    private final List<PipelineFilter> filters;

    public GatewayPipeline(List<PipelineFilter> filters) {
        this.filters = filters.stream()
                .sorted(Comparator.comparingInt(PipelineFilter::order))
                .toList();
        if (log.isInfoEnabled()) {
            List<String> names = this.filters.stream()
                    .map(f -> f.getClass().getSimpleName() + "(" + f.order() + ")")
                    .toList();
            log.info("Gateway pipeline initialized with {} filter(s): {}", names.size(), names);
        }
    }

    /**
     * 驱动流水线。
     *
     * @param ctx      请求上下文
     * @param terminal 链尾终结逻辑，可为 {@code null}（表示无终结逻辑）
     * @return 整条链执行完成的信号
     */
    public Mono<Void> execute(RequestContext ctx, Mono<Void> terminal) {
        Mono<Void> tail = terminal != null ? terminal : Mono.empty();
        return DefaultPipelineChain.of(filters, tail).execute(ctx);
    }

    /** 只读的过滤器视图（供测试/诊断）。 */
    public List<PipelineFilter> getFilters() {
        return filters;
    }
}
