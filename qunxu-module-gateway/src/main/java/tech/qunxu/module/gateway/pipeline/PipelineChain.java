package tech.qunxu.module.gateway.pipeline;

import reactor.core.publisher.Mono;

/**
 * 流水线链。由 {@link PipelineFilter} 调用以推进到下一个过滤器；当所有过滤器执行完毕后，
 * 触发链尾的终结逻辑（通常为「调用适配器并写回响应」）。
 */
public interface PipelineChain {

    /**
     * 推进流水线。
     *
     * @param ctx 请求上下文
     * @return 后续所有过滤器与终结逻辑完成的信号
     */
    Mono<Void> execute(RequestContext ctx);
}
