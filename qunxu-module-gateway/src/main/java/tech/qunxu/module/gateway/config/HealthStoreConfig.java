package tech.qunxu.module.gateway.config;

import tech.qunxu.module.gateway.health.HealthStore;
import tech.qunxu.module.gateway.health.InMemoryHealthStore;
import tech.qunxu.module.gateway.health.RedisHealthStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

/**
 * 健康存储装配。依据 {@code gateway.health.store} 选择实现：
 * <ul>
 *   <li>{@code redis}（默认，缺省亦生效）：{@link RedisHealthStore}，多实例共享；</li>
 *   <li>{@code in-memory}：{@link InMemoryHealthStore}，单机/测试无 Redis 依赖。</li>
 * </ul>
 */
@Configuration
public class HealthStoreConfig {

    @Bean
    @ConditionalOnProperty(name = "gateway.health.store", havingValue = "redis", matchIfMissing = true)
    public HealthStore redisHealthStore(ReactiveStringRedisTemplate redisTemplate, GatewayProperties properties) {
        return new RedisHealthStore(redisTemplate, properties);
    }

    @Bean
    @ConditionalOnProperty(name = "gateway.health.store", havingValue = "in-memory")
    public HealthStore inMemoryHealthStore(GatewayProperties properties) {
        return new InMemoryHealthStore(properties);
    }
}
