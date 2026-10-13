package tech.qunxu.module.gateway.router;

import tech.qunxu.module.gateway.health.ChannelHealth;
import tech.qunxu.module.gateway.persistence.entity.ModelChannelEntity;
import tech.qunxu.module.gateway.persistence.entity.ProviderEntity;

/**
 * 路由候选：一个「已通过启用与健康过滤」的通道，连同其供应商与健康快照。
 *
 * <p>{@link LoadBalancer} 在此候选集上按策略择一；供应商与健康随候选传递，选中后无需二次查询。</p>
 *
 * @param channel  通道实体
 * @param provider 通道所属供应商实体
 * @param health   通道健康快照（不为 {@code null}；无数据时为 {@link ChannelHealth#unknown}）
 */
public record ChannelCandidate(ModelChannelEntity channel, ProviderEntity provider, ChannelHealth health) {

    /** 通道 ID。 */
    public Long channelId() {
        return channel != null ? channel.getId() : null;
    }

    /** 有效权重：weight 为空或非正时兜底为 1。 */
    public int weight() {
        Integer w = channel != null ? channel.getWeight() : null;
        return (w == null || w <= 0) ? 1 : w;
    }

    /** 有效优先级：priority 为空时兜底为 {@link Integer#MAX_VALUE}（最低优先）。 */
    public int priority() {
        Integer p = channel != null ? channel.getPriority() : null;
        return p != null ? p : Integer.MAX_VALUE;
    }

    /** EWMA 平均时延；无样本返回 {@code -1}。 */
    public double latencyMs() {
        return health != null ? health.avgLatencyMs() : -1d;
    }

    /** 是否已有时延样本。 */
    public boolean hasLatency() {
        return health != null && health.hasLatency();
    }
}
