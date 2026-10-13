package tech.qunxu.module.gateway.router.lb;

import tech.qunxu.module.gateway.persistence.entity.LogicalModelEntity;
import tech.qunxu.module.gateway.pipeline.RequestContext;
import tech.qunxu.module.gateway.router.ChannelCandidate;
import tech.qunxu.module.gateway.router.LoadBalanceStrategy;
import tech.qunxu.module.gateway.router.LoadBalancer;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * 最小延迟策略。优先选择 EWMA 平均时延最低的通道；时延相同则权重大者优先，再按通道 ID 升序稳定选择。
 *
 * <p>当所有候选都无时延样本（首次探测前）时，退化为「权重优先」，保证确定性。</p>
 */
@Component
public class LeastLatencyLoadBalancer implements LoadBalancer {

    /** 时延升序 → 权重降序 → 通道 ID 升序。 */
    private static final Comparator<ChannelCandidate> BY_LATENCY =
            Comparator.comparingDouble(ChannelCandidate::latencyMs)
                    .thenComparing(Comparator.comparingInt(ChannelCandidate::weight).reversed())
                    .thenComparing(LeastLatencyLoadBalancer::idOrMax);

    /** 权重降序 → 通道 ID 升序（无时延样本时的退化排序）。 */
    private static final Comparator<ChannelCandidate> BY_WEIGHT =
            Comparator.comparingInt(ChannelCandidate::weight).reversed()
                    .thenComparing(LeastLatencyLoadBalancer::idOrMax);

    private static long idOrMax(ChannelCandidate c) {
        return c.channelId() != null ? c.channelId() : Long.MAX_VALUE;
    }

    @Override
    public LoadBalanceStrategy strategy() {
        return LoadBalanceStrategy.LEAST_LATENCY;
    }

    @Override
    public ChannelCandidate select(LogicalModelEntity model, List<ChannelCandidate> candidates, RequestContext ctx) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        return candidates.stream()
                .filter(ChannelCandidate::hasLatency)
                .min(BY_LATENCY)
                .orElseGet(() -> candidates.stream().min(BY_WEIGHT).orElse(candidates.get(0)));
    }
}
