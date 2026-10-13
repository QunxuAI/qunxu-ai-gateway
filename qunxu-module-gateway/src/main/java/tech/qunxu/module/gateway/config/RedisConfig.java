package tech.qunxu.module.gateway.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;

/**
 * Redis 响应式基础设施装配。
 *
 * <p>{@code ReactiveRedisTemplate} / {@code ReactiveStringRedisTemplate} 由 Spring Boot 自动配置提供，
 * 但 Boot 4 的 Redis 自动配置<b>不再</b>注册 {@link ReactiveRedisMessageListenerContainer}，
 * 而多实例配置热失效（{@code gw:config:version} pub/sub）需要它，故在此显式装配。</p>
 *
 * <p>开关用 {@code @ConditionalOnProperty} 而非 {@code @ConditionalOnBean(ReactiveRedisConnectionFactory.class)}：
 * 用户级 {@code @Configuration} 总是<b>先于</b>自动配置被解析，此时连接工厂尚未注册，Bean 条件恒为假，
 * 会造成「监听容器永不创建、跨实例热失效静默失效」的隐蔽缺陷（已由 {@code GatewayFullStackIT} 钉住）。
 * 与 {@code HealthStoreConfig} / {@code RateLimiterConfig} 保持一致的属性驱动风格。</p>
 *
 * <p>关闭后（单机/无 Redis 的测试环境）{@link ConfigVersionSubscriber} 通过 {@code ObjectProvider}
 * 判空降级为「仅本实例生效」，不影响启动。</p>
 */
@Configuration
public class RedisConfig {

    @Bean
    @ConditionalOnProperty(name = "gateway.config.pubsub-enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean(ReactiveRedisMessageListenerContainer.class)
    public ReactiveRedisMessageListenerContainer reactiveRedisMessageListenerContainer(
            ReactiveRedisConnectionFactory connectionFactory) {
        return new ReactiveRedisMessageListenerContainer(connectionFactory);
    }
}
