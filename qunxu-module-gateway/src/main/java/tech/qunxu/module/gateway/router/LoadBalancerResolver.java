package tech.qunxu.module.gateway.router;

import tech.qunxu.module.gateway.config.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 负载均衡策略解析器。收集所有 {@link LoadBalancer} Bean，按 {@link LoadBalanceStrategy} 建立索引，
 * 依据策略名（或枚举）返回对应实现；缺失时回退到配置的默认策略，仍缺失则回退到加权轮询。
 */
@Component
public class LoadBalancerResolver {

    private static final Logger log = LoggerFactory.getLogger(LoadBalancerResolver.class);

    private final Map<LoadBalanceStrategy, LoadBalancer> registry =
            new EnumMap<>(LoadBalanceStrategy.class);
    private final LoadBalanceStrategy defaultStrategy;

    public LoadBalancerResolver(List<LoadBalancer> balancers, GatewayProperties properties) {
        for (LoadBalancer lb : balancers) {
            LoadBalancer previous = registry.put(lb.strategy(), lb);
            if (previous != null) {
                log.warn("负载均衡策略 {} 存在多个实现，{} 覆盖 {}", lb.strategy(),
                        lb.getClass().getSimpleName(), previous.getClass().getSimpleName());
            }
        }
        this.defaultStrategy = LoadBalanceStrategy.parse(
                properties.getLoadBalance().getDefaultStrategy(), LoadBalanceStrategy.WEIGHTED_ROUND_ROBIN);
        log.info("负载均衡策略已注册 {}，默认策略={}", registry.keySet(), defaultStrategy);
    }

    /** 默认策略（来自配置，兜底加权轮询）。 */
    public LoadBalanceStrategy defaultStrategy() {
        return defaultStrategy;
    }

    /** 按策略枚举解析；无对应实现时回退默认策略，再回退加权轮询。 */
    public LoadBalancer resolve(LoadBalanceStrategy strategy) {
        LoadBalanceStrategy target = strategy != null ? strategy : defaultStrategy;
        LoadBalancer lb = registry.get(target);
        if (lb != null) {
            return lb;
        }
        lb = registry.get(defaultStrategy);
        if (lb != null) {
            return lb;
        }
        return registry.get(LoadBalanceStrategy.WEIGHTED_ROUND_ROBIN);
    }

    /** 按策略名解析（大小写不敏感）；无法识别时使用默认策略。 */
    public LoadBalancer resolve(String strategyName) {
        return resolve(LoadBalanceStrategy.parse(strategyName, defaultStrategy));
    }
}
