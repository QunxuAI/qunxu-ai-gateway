package tech.qunxu.module.gateway.router.lb;

import tech.qunxu.module.gateway.common.enums.HealthStatus;
import tech.qunxu.module.gateway.config.GatewayProperties;
import tech.qunxu.module.gateway.health.ChannelHealth;
import tech.qunxu.module.gateway.persistence.entity.LogicalModelEntity;
import tech.qunxu.module.gateway.persistence.entity.ModelChannelEntity;
import tech.qunxu.module.gateway.persistence.entity.ProviderEntity;
import tech.qunxu.module.gateway.router.ChannelCandidate;
import tech.qunxu.module.gateway.router.LoadBalanceStrategy;
import tech.qunxu.module.gateway.router.LoadBalancerResolver;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 负载均衡策略单元测试：加权轮询按比例分配、最小延迟选最低时延（无样本退化权重）、随机覆盖全体、
 * 优先级取最小 priority；并验证 {@link LoadBalancerResolver} 的策略选择与回退，及 {@link LoadBalanceStrategy#parse}。
 */
class LoadBalancerStrategiesTest {

    private static ChannelCandidate candidate(long id, int weight, int priority, Double latency) {
        ModelChannelEntity ch = new ModelChannelEntity();
        ch.setId(id);
        ch.setWeight(weight);
        ch.setPriority(priority);
        ch.setEnabled(1);
        ch.setProtocol("OPENAI_COMPAT");
        ch.setUpstreamModel("m" + id);
        ChannelHealth health = latency != null
                ? new ChannelHealth(id, HealthStatus.UP, 0, 0, null, latency, System.currentTimeMillis())
                : ChannelHealth.unknown(id);
        return new ChannelCandidate(ch, provider(id), health);
    }

    private static ProviderEntity provider(long id) {
        ProviderEntity p = new ProviderEntity();
        p.setId(1000 + id);
        p.setCode("P" + id);
        p.setStatus(1);
        return p;
    }

    private static LogicalModelEntity model(long id) {
        LogicalModelEntity m = new LogicalModelEntity();
        m.setId(id);
        m.setName("logical-" + id);
        m.setCapability("CHAT");
        m.setStatus(1);
        return m;
    }

    @Test
    void weightedRoundRobinDistributesByWeight() {
        WeightedRoundRobinLoadBalancer lb = new WeightedRoundRobinLoadBalancer();
        List<ChannelCandidate> cands = List.of(candidate(1, 3, 1, null), candidate(2, 1, 1, null));

        List<Long> firstFour = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            firstFour.add(lb.select(model(10), cands, null).channelId());
        }
        assertThat(firstFour).containsExactly(1L, 1L, 1L, 2L);

        // 全新实例 40 次：权重 3:1 → 30:10（确定性）
        WeightedRoundRobinLoadBalancer fresh = new WeightedRoundRobinLoadBalancer();
        long c1 = 0;
        long c2 = 0;
        for (int i = 0; i < 40; i++) {
            long id = fresh.select(model(10), cands, null).channelId();
            if (id == 1L) {
                c1++;
            } else {
                c2++;
            }
        }
        assertThat(c1).isEqualTo(30);
        assertThat(c2).isEqualTo(10);
    }

    @Test
    void weightedRoundRobinEqualWeightsAlternates() {
        WeightedRoundRobinLoadBalancer lb = new WeightedRoundRobinLoadBalancer();
        List<ChannelCandidate> cands = List.of(candidate(1, 1, 1, null), candidate(2, 1, 1, null));
        List<Long> picks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            picks.add(lb.select(model(7), cands, null).channelId());
        }
        assertThat(picks).containsExactly(1L, 2L, 1L, 2L);
    }

    @Test
    void leastLatencyPicksLowest() {
        LeastLatencyLoadBalancer lb = new LeastLatencyLoadBalancer();
        List<ChannelCandidate> cands = List.of(
                candidate(1, 100, 1, 50.0), candidate(2, 100, 1, 20.0), candidate(3, 100, 1, 80.0));
        assertThat(lb.select(model(1), cands, null).channelId()).isEqualTo(2L);
    }

    @Test
    void leastLatencyTieBreakByWeight() {
        LeastLatencyLoadBalancer lb = new LeastLatencyLoadBalancer();
        List<ChannelCandidate> cands = List.of(candidate(1, 10, 1, 30.0), candidate(2, 20, 1, 30.0));
        assertThat(lb.select(model(1), cands, null).channelId()).isEqualTo(2L);
    }

    @Test
    void leastLatencyFallsBackToWeightWhenNoSamples() {
        LeastLatencyLoadBalancer lb = new LeastLatencyLoadBalancer();
        List<ChannelCandidate> cands = List.of(candidate(1, 10, 1, null), candidate(2, 50, 1, null));
        assertThat(lb.select(model(1), cands, null).channelId()).isEqualTo(2L);
    }

    @Test
    void randomReturnsMembersAndCoversAll() {
        RandomLoadBalancer lb = new RandomLoadBalancer(new Random(42));
        List<ChannelCandidate> cands = List.of(
                candidate(1, 1, 1, null), candidate(2, 1, 1, null), candidate(3, 1, 1, null));
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < 300; i++) {
            long id = lb.select(model(1), cands, null).channelId();
            assertThat(cands).anyMatch(c -> c.channelId() == id);
            seen.add(id);
        }
        assertThat(seen).containsExactlyInAnyOrder(1L, 2L, 3L);
    }

    @Test
    void priorityPicksLowestPriorityValue() {
        PriorityLoadBalancer lb = new PriorityLoadBalancer();
        List<ChannelCandidate> cands = List.of(
                candidate(1, 100, 5, null), candidate(2, 100, 1, null), candidate(3, 100, 3, null));
        assertThat(lb.select(model(1), cands, null).channelId()).isEqualTo(2L);
    }

    @Test
    void priorityTieBreakByWeight() {
        PriorityLoadBalancer lb = new PriorityLoadBalancer();
        List<ChannelCandidate> cands = List.of(candidate(1, 10, 1, null), candidate(2, 20, 1, null));
        assertThat(lb.select(model(1), cands, null).channelId()).isEqualTo(2L);
    }

    @Test
    void selectReturnsNullForEmptyCandidates() {
        assertThat(new WeightedRoundRobinLoadBalancer().select(model(1), List.of(), null)).isNull();
        assertThat(new LeastLatencyLoadBalancer().select(model(1), List.of(), null)).isNull();
        assertThat(new RandomLoadBalancer().select(model(1), List.of(), null)).isNull();
        assertThat(new PriorityLoadBalancer().select(model(1), List.of(), null)).isNull();
    }

    @Test
    void resolverSelectsByStrategyWithFallback() {
        LoadBalancerResolver resolver = new LoadBalancerResolver(allBalancers(), new GatewayProperties());
        assertThat(resolver.defaultStrategy()).isEqualTo(LoadBalanceStrategy.WEIGHTED_ROUND_ROBIN);
        assertThat(resolver.resolve(LoadBalanceStrategy.LEAST_LATENCY)).isInstanceOf(LeastLatencyLoadBalancer.class);
        assertThat(resolver.resolve("priority")).isInstanceOf(PriorityLoadBalancer.class);
        assertThat(resolver.resolve("RANDOM")).isInstanceOf(RandomLoadBalancer.class);
        assertThat(resolver.resolve("bogus")).isInstanceOf(WeightedRoundRobinLoadBalancer.class);
        assertThat(resolver.resolve((String) null)).isInstanceOf(WeightedRoundRobinLoadBalancer.class);
    }

    @Test
    void resolverHonorsConfiguredDefault() {
        GatewayProperties props = new GatewayProperties();
        props.getLoadBalance().setDefaultStrategy("LEAST_LATENCY");
        LoadBalancerResolver resolver = new LoadBalancerResolver(allBalancers(), props);
        assertThat(resolver.defaultStrategy()).isEqualTo(LoadBalanceStrategy.LEAST_LATENCY);
        assertThat(resolver.resolve("bogus")).isInstanceOf(LeastLatencyLoadBalancer.class);
    }

    @Test
    void parseIsLenient() {
        assertThat(LoadBalanceStrategy.parse("least_latency", LoadBalanceStrategy.WEIGHTED_ROUND_ROBIN))
                .isEqualTo(LoadBalanceStrategy.LEAST_LATENCY);
        assertThat(LoadBalanceStrategy.parse("  Priority ", LoadBalanceStrategy.RANDOM))
                .isEqualTo(LoadBalanceStrategy.PRIORITY);
        assertThat(LoadBalanceStrategy.parse(null, LoadBalanceStrategy.RANDOM))
                .isEqualTo(LoadBalanceStrategy.RANDOM);
        assertThat(LoadBalanceStrategy.parse("nope", LoadBalanceStrategy.PRIORITY))
                .isEqualTo(LoadBalanceStrategy.PRIORITY);
    }

    private static List<tech.qunxu.module.gateway.router.LoadBalancer> allBalancers() {
        return List.of(new WeightedRoundRobinLoadBalancer(), new LeastLatencyLoadBalancer(),
                new RandomLoadBalancer(), new PriorityLoadBalancer());
    }
}
