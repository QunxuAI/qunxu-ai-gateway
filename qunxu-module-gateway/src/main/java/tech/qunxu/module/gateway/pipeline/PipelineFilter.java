package tech.qunxu.module.gateway.pipeline;

import reactor.core.publisher.Mono;

/**
 * 流水线过滤器 SPI。每个过滤器完成一个横切关注点（鉴权、限流、模型解析、围栏、路由、调用、审计等），
 * 通过 {@link PipelineChain#execute(RequestContext)} 决定是否继续向后传递。
 *
 * <p>实现类注册为 Spring Bean，由 {@link GatewayPipeline} 按 {@link #order()} 升序编排。
 * 过滤器可在调用 {@code chain.execute} 前后插入逻辑（前置校验 / 后置聚合），也可短路直接返回错误。</p>
 */
public interface PipelineFilter {

    /** 执行顺序，越小越先执行。取值参考 {@link FilterOrder}。 */
    int order();

    /**
     * 处理请求。
     *
     * @param ctx   贯穿全链的请求上下文
     * @param chain 调用以进入下一个过滤器；不调用即短路
     * @return 处理完成的信号
     */
    Mono<Void> filter(RequestContext ctx, PipelineChain chain);
}
