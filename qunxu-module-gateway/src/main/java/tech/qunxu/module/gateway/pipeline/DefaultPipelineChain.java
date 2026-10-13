package tech.qunxu.module.gateway.pipeline;

import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 默认的 {@link PipelineChain} 实现：基于不可变索引推进过滤器序列。
 *
 * <p>每次 {@link #execute(RequestContext)} 定位到当前索引的过滤器，并把「索引 +1」的新链
 * 作为其 {@code chain} 参数传入；索引越界时执行链尾终结逻辑 {@code terminal}。
 * 不可变设计保证过滤器多次/嵌套调用链时的正确性。</p>
 */
final class DefaultPipelineChain implements PipelineChain {

    private final List<PipelineFilter> filters;
    private final int index;
    private final Mono<Void> terminal;

    private DefaultPipelineChain(List<PipelineFilter> filters, int index, Mono<Void> terminal) {
        this.filters = filters;
        this.index = index;
        this.terminal = terminal;
    }

    static DefaultPipelineChain of(List<PipelineFilter> filters, Mono<Void> terminal) {
        return new DefaultPipelineChain(filters, 0, terminal);
    }

    @Override
    public Mono<Void> execute(RequestContext ctx) {
        if (index >= filters.size()) {
            return terminal;
        }
        PipelineFilter filter = filters.get(index);
        PipelineChain next = new DefaultPipelineChain(filters, index + 1, terminal);
        return filter.filter(ctx, next);
    }
}
