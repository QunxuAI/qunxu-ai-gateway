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
 * 优先级策略。priority 数值最小者优先（空视为最低优先），其次 weight 大者，再按通道 ID 升序稳定选择。
 *
 * <p>适用于「主备」部署：主通道低 priority 承载全部流量，仅当主通道被健康过滤剔除后备通道才进入候选。</p>
 */
@Component
public class PriorityLoadBalancer implements LoadBalancer {

    /** priority 升序 → weight 降序 → 通道 ID 升序。 */
    private static final Comparator<ChannelCandidate> BY_PRIORITY =
            Comparator.comparingInt(ChannelCandidate::priority)
                    .thenComparing(Comparator.comparingInt(ChannelCandidate::weight).reversed())
                    .thenComparing(c -> c.channelId() != null ? c.channelId() : Long.MAX_VALUE);

    @Override
    public LoadBalanceStrategy strategy() {
        return LoadBalanceStrategy.PRIORITY;
    }

    @Override
    public ChannelCandidate select(LogicalModelEntity model, List<ChannelCandidate> candidates, RequestContext ctx) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        return candidates.stream().min(BY_PRIORITY).orElse(candidates.get(0));
    }
}
