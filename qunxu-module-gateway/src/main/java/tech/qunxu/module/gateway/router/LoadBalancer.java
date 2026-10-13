package tech.qunxu.module.gateway.router;

import tech.qunxu.module.gateway.persistence.entity.LogicalModelEntity;
import tech.qunxu.module.gateway.pipeline.RequestContext;

import java.util.List;

/**
 * 负载均衡策略 SPI。在「已过滤为健康」的候选通道集中择一。
 *
 * <p>实现须为无副作用的纯选择逻辑（可含跨请求的轮询游标等内部状态），不访问数据库/Redis —— 健康数据
 * 已由 {@code RoutingService} 预取并封装进 {@link ChannelCandidate}。对应计划 SPI
 * {@code Channel select(LogicalModel, List<Channel> healthy, RequestContext)} 的本项目适配版本。</p>
 */
public interface LoadBalancer {

    /** 本实现对应的策略标识。 */
    LoadBalanceStrategy strategy();

    /**
     * 从健康候选中选择一个通道。
     *
     * @param model      已解析的逻辑模型（供按模型维度维护轮询游标/日志）
     * @param candidates 健康候选集，<b>非空</b>（调用方保证；空集应在调用前以 ALL_CHANNELS_DOWN 短路）
     * @param ctx        请求上下文（可调用人/协议等做亲和性扩展，当前策略未使用）
     * @return 选中的候选；若 {@code candidates} 为空返回 {@code null}
     */
    ChannelCandidate select(LogicalModelEntity model, List<ChannelCandidate> candidates, RequestContext ctx);
}
