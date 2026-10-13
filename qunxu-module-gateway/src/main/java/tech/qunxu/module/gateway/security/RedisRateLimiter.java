package tech.qunxu.module.gateway.security;

import tech.qunxu.module.gateway.config.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 基于 Redis 的令牌桶限流器（默认实现，多实例共享）。
 *
 * <p>桶状态存于 hash {@code {key}} 的 {@code tokens}/{@code ts} 两个字段，「补充令牌 + 扣减 + 续期」
 * 全部在<b>一段 Lua 脚本</b>内完成，保证跨实例原子性（避免读-改-写竞态导致的超发）。桶容量为
 * {@code permitsPerMinute × burstRatio}，补充速率为 {@code permitsPerMinute / 60} 令牌/秒，
 * 因此稳态吞吐精确等于配置的每分钟许可数，同时允许配置比例的短时突发。</p>
 *
 * <p>失败开放：Redis 不可用或脚本执行异常时，按 {@code gateway.rate-limit.fail-open} 返回
 * {@link RateLimitDecision#degradedAllow}（默认放行）或拒绝，异常绝不外泄到调用链。</p>
 */
public class RedisRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);

    /**
     * 令牌桶 Lua 脚本。
     *
     * <p>ARGV: 1=桶容量 2=每秒补充令牌数 3=当前毫秒时间戳 4=本次消耗令牌数 5=键 TTL 秒。
     * 返回 {@code {allowed, remaining, retryAfterSeconds}}。时钟回拨（{@code elapsed<0}）按 0 处理，
     * 避免负数补充把桶抽干。</p>
     */
    static final String TOKEN_BUCKET_LUA = """
            local key      = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local refill   = tonumber(ARGV[2])
            local now      = tonumber(ARGV[3])
            local cost     = tonumber(ARGV[4])
            local ttl      = tonumber(ARGV[5])

            local data   = redis.call('HMGET', key, 'tokens', 'ts')
            local tokens = tonumber(data[1])
            local ts     = tonumber(data[2])
            if tokens == nil then tokens = capacity end
            if ts == nil then ts = now end

            local elapsed = now - ts
            if elapsed < 0 then elapsed = 0 end
            tokens = math.min(capacity, tokens + (elapsed / 1000.0) * refill)

            local allowed = 0
            if tokens >= cost then
              tokens = tokens - cost
              allowed = 1
            end

            redis.call('HSET', key, 'tokens', tokens, 'ts', now)
            redis.call('EXPIRE', key, math.floor(ttl))

            local retryAfter = 0
            if allowed == 0 and refill > 0 then
              retryAfter = math.ceil((cost - tokens) / refill)
            end
            return { allowed, math.floor(tokens), retryAfter }
            """;

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> SCRIPT = new DefaultRedisScript<>(TOKEN_BUCKET_LUA, List.class);

    /** 桶键最短 TTL（秒）：至少覆盖两倍「灌满整桶」所需时间，避免空闲键长期驻留。 */
    private static final long MIN_TTL_SECONDS = 120L;

    private final ReactiveStringRedisTemplate redis;
    private final boolean failOpen;

    public RedisRateLimiter(ReactiveStringRedisTemplate redis, GatewayProperties properties) {
        this.redis = redis;
        this.failOpen = properties.getRateLimit().isFailOpen();
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Mono<RateLimitDecision> tryAcquire(String key, int permitsPerMinute, double burstRatio) {
        if (key == null || key.isBlank()) {
            return Mono.just(RateLimitDecision.unlimited(key));
        }
        if (permitsPerMinute <= 0) {
            return Mono.just(RateLimitDecision.unlimited(key));
        }
        double ratio = burstRatio < 1.0d ? 1.0d : burstRatio;
        long capacity = Math.max(1L, Math.round(permitsPerMinute * ratio));
        double refillPerSecond = permitsPerMinute / 60.0d;
        long ttl = Math.max(MIN_TTL_SECONDS, Math.round(MIN_TTL_SECONDS * ratio));

        List<String> args = List.of(
                Long.toString(capacity),
                Double.toString(refillPerSecond),
                Long.toString(System.currentTimeMillis()),
                "1",
                Long.toString(ttl));

        return Mono.defer(() -> redis.execute(SCRIPT, List.of(key), args).next())
                .map(result -> toDecision(key, permitsPerMinute, result))
                .defaultIfEmpty(fallback(key, permitsPerMinute, "脚本无返回"))
                .onErrorResume(e -> {
                    log.warn("限流脚本执行失败({}), 按 fail-open={} 处置: {}", key, failOpen, e.toString());
                    return Mono.just(fallback(key, permitsPerMinute, e.toString()));
                });
    }

    /** 解析 Lua 返回的 {@code {allowed, remaining, retryAfter}}；结构异常时按失败开放策略兜底。 */
    private RateLimitDecision toDecision(String key, int limit, List<?> result) {
        if (result == null || result.size() < 3) {
            return fallback(key, limit, "脚本返回结构异常");
        }
        long allowed = toLong(result.get(0));
        long remaining = toLong(result.get(1));
        long retryAfter = toLong(result.get(2));
        if (allowed > 0) {
            return RateLimitDecision.allow(key, limit, remaining);
        }
        return RateLimitDecision.deny(key, limit, remaining, retryAfter);
    }

    private RateLimitDecision fallback(String key, int limit, String reason) {
        if (failOpen) {
            log.debug("限流降级放行 key={} 原因={}", key, reason);
            return RateLimitDecision.degradedAllow(key, limit);
        }
        return RateLimitDecision.deny(key, limit, 0L, 1L);
    }

    private static long toLong(Object v) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v == null) {
            return 0L;
        }
        try {
            return Long.parseLong(v.toString().trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
