package tech.qunxu.module.gateway.security;

import reactor.core.publisher.Mono;

/**
 * 限流器 SPI（令牌桶）。默认实现 {@code RedisRateLimiter} 以 Lua 脚本在 Redis 中原子扣减，多实例共享；
 * {@code InMemoryRateLimiter} 用于单机部署与测试（进程内近似，不跨实例）。
 *
 * <p>契约：许可数 {@code <=0} 表示该维度不限制，直接返回 {@link RateLimitDecision#unlimited}；
 * 存储异常时按 {@code gateway.rate-limit.fail-open} 决定放行（默认）或拒绝，<b>绝不</b>把异常抛给调用链。</p>
 */
public interface RateLimiter {

    /**
     * 尝试获取 1 个令牌。
     *
     * @param key               计数键（如 {@code gw:ratelimit:caller:{callerId}}）
     * @param permitsPerMinute  每分钟许可数（同时决定补充速率），{@code <=0} 表示不限制
     * @param burstRatio        桶容量系数，容量 = {@code permitsPerMinute × burstRatio}（{@code <1} 按 1 处理）
     */
    Mono<RateLimitDecision> tryAcquire(String key, int permitsPerMinute, double burstRatio);

    /** 尝试获取 1 个令牌（桶容量等于每分钟许可数，不允许突发）。 */
    default Mono<RateLimitDecision> tryAcquire(String key, int permitsPerMinute) {
        return tryAcquire(key, permitsPerMinute, 1.0d);
    }
}
