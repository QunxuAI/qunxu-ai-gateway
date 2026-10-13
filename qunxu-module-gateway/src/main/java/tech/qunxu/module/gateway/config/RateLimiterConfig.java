package tech.qunxu.module.gateway.config;

import tech.qunxu.module.gateway.security.InMemoryRateLimiter;
import tech.qunxu.module.gateway.security.RateLimiter;
import tech.qunxu.module.gateway.security.RedisRateLimiter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

/**
 * 限流器装配。依据 {@code gateway.rate-limit.store} 选择实现：
 * <ul>
 *   <li>{@code redis}（默认，缺省亦生效）：{@link RedisRateLimiter}，Lua 令牌桶、多实例共享；</li>
 *   <li>{@code in-memory}：{@link InMemoryRateLimiter}，单机/测试无 Redis 依赖。</li>
 * </ul>
 *
 * <p>与 {@link HealthStoreConfig} 保持一致的装配风格，使「无 Redis 环境」可通过单一开关整体降级。</p>
 */
@Configuration
public class RateLimiterConfig {

    @Bean
    @ConditionalOnProperty(name = "gateway.rate-limit.store", havingValue = "redis", matchIfMissing = true)
    public RateLimiter redisRateLimiter(ReactiveStringRedisTemplate redisTemplate, GatewayProperties properties) {
        return new RedisRateLimiter(redisTemplate, properties);
    }

    @Bean
    @ConditionalOnProperty(name = "gateway.rate-limit.store", havingValue = "in-memory")
    public RateLimiter inMemoryRateLimiter(GatewayProperties properties) {
        return new InMemoryRateLimiter(properties);
    }
}
