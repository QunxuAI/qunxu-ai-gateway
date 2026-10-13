package tech.qunxu.module.gateway.router.lb;

import tech.qunxu.module.gateway.persistence.entity.LogicalModelEntity;
import tech.qunxu.module.gateway.pipeline.RequestContext;
import tech.qunxu.module.gateway.router.ChannelCandidate;
import tech.qunxu.module.gateway.router.LoadBalanceStrategy;
import tech.qunxu.module.gateway.router.LoadBalancer;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 加权轮询（默认策略）。按逻辑模型维护一个自增游标，以「累积权重取模」在候选间按 weight 比例平滑分配流量。
 *
 * <p>例：两通道权重 3:1，则每 4 次选择中约 3 次命中前者、1 次命中后者。游标溢出为负时由
 * {@link Math#floorMod} 正确处理。候选集因健康过滤动态变化时，比例在时间维度上仍近似成立。</p>
 */
@Component
public class WeightedRoundRobinLoadBalancer implements LoadBalancer {

    /** 按逻辑模型 ID 维护的轮询游标。 */
    private final ConcurrentMap<Long, AtomicLong> cursors = new ConcurrentHashMap<>();

    @Override
    public LoadBalanceStrategy strategy() {
        return LoadBalanceStrategy.WEIGHTED_ROUND_ROBIN;
    }

    @Override
    public ChannelCandidate select(LogicalModelEntity model, List<ChannelCandidate> candidates, RequestContext ctx) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        int n = candidates.size();
        if (n == 1) {
            return candidates.get(0);
        }
        long key = (model != null && model.getId() != null) ? model.getId() : 0L;
        long seq = cursors.computeIfAbsent(key, k -> new AtomicLong()).getAndIncrement();

        int totalWeight = 0;
        for (ChannelCandidate c : candidates) {
            totalWeight += c.weight();
        }
        if (totalWeight <= 0) {
            return candidates.get((int) Math.floorMod(seq, n));
        }
        int target = (int) Math.floorMod(seq, totalWeight);
        int cumulative = 0;
        for (ChannelCandidate c : candidates) {
            cumulative += c.weight();
            if (target < cumulative) {
                return c;
            }
        }
        return candidates.get(n - 1);
    }
}
