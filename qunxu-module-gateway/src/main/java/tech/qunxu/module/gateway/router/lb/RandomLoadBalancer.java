package tech.qunxu.module.gateway.router.lb;

import tech.qunxu.module.gateway.persistence.entity.LogicalModelEntity;
import tech.qunxu.module.gateway.pipeline.RequestContext;
import tech.qunxu.module.gateway.router.ChannelCandidate;
import tech.qunxu.module.gateway.router.LoadBalanceStrategy;
import tech.qunxu.module.gateway.router.LoadBalancer;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Random;

/**
 * 随机策略。在健康候选内等概率随机选择。
 *
 * <p>内部 {@link Random} 字段线程安全（CAS 实现）；提供带 {@link Random} 的构造函数以便测试注入可复现随机源。</p>
 */
@Component
public class RandomLoadBalancer implements LoadBalancer {

    private final Random random;

    public RandomLoadBalancer() {
        this(new Random());
    }

    /** 测试用：注入可复现随机源。 */
    RandomLoadBalancer(Random random) {
        this.random = random != null ? random : new Random();
    }

    @Override
    public LoadBalanceStrategy strategy() {
        return LoadBalanceStrategy.RANDOM;
    }

    @Override
    public ChannelCandidate select(LogicalModelEntity model, List<ChannelCandidate> candidates, RequestContext ctx) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        return candidates.get(random.nextInt(candidates.size()));
    }
}
