package tech.qunxu.module.gateway.security;

import tech.qunxu.module.gateway.config.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内令牌桶限流器。用于单机部署或<b>无 Redis 的测试环境</b>（{@code gateway.rate-limit.store=in-memory}），
 * 计数不跨实例共享、进程重启即清零，多实例部署时实际放行量约为「配置值 × 实例数」。
 *
 * <p>算法与 {@link RedisRateLimiter} 一致（容量 = 许可数 × 突发系数、补充速率 = 许可数/60 每秒），
 * 使用 {@link System#nanoTime()} 单调时钟避免系统时间跳变影响。桶按 key 惰性创建，
 * 超过 {@value #MAX_KEYS} 个 key 时清理空闲超过 10 分钟的桶，防止 key 无限增长。</p>
 */
public class InMemoryRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(InMemoryRateLimiter.class);

    /** 跟踪的桶数量上限，超出后清理空闲桶。 */
    static final int MAX_KEYS = 20_000;
    /** 空闲桶回收阈值。 */
    private static final long IDLE_NANOS = Duration.ofMinutes(10).toNanos();

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public InMemoryRateLimiter(GatewayProperties properties) {
        log.info("限流器使用进程内令牌桶（单机/测试模式，不跨实例共享），fail-open={}",
                properties.getRateLimit().isFailOpen());
    }

    @Override
    public Mono<RateLimitDecision> tryAcquire(String key, int permitsPerMinute, double burstRatio) {
        if (key == null || key.isBlank() || permitsPerMinute <= 0) {
            return Mono.just(RateLimitDecision.unlimited(key));
        }
        return Mono.fromSupplier(() -> acquire(key, permitsPerMinute, burstRatio));
    }

    private RateLimitDecision acquire(String key, int permitsPerMinute, double burstRatio) {
        double ratio = burstRatio < 1.0d ? 1.0d : burstRatio;
        long capacity = Math.max(1L, Math.round(permitsPerMinute * ratio));
        double refillPerSecond = permitsPerMinute / 60.0d;
        pruneIfOversized();
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(capacity));
        return bucket.tryAcquire(key, permitsPerMinute, capacity, refillPerSecond);
    }

    /** 当前跟踪的桶数量（供测试与诊断）。 */
    public int trackedKeys() {
        return buckets.size();
    }

    /** 清空全部桶（供测试复位）。 */
    public void reset() {
        buckets.clear();
    }

    private void pruneIfOversized() {
        if (buckets.size() <= MAX_KEYS) {
            return;
        }
        long now = System.nanoTime();
        int removed = 0;
        for (Iterator<Map.Entry<String, Bucket>> it = buckets.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Bucket> entry = it.next();
            if (now - entry.getValue().lastAccessNanos() > IDLE_NANOS) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0 && log.isDebugEnabled()) {
            log.debug("清理空闲限流桶 {} 个，剩余 {} 个", removed, buckets.size());
        }
    }

    /** 单个令牌桶；所有状态变更在实例锁内完成，保证同 key 的并发扣减不超发。 */
    private static final class Bucket {

        private double tokens;
        private long lastNanos;

        Bucket(long capacity) {
            this.tokens = capacity;
            this.lastNanos = System.nanoTime();
        }

        long lastAccessNanos() {
            return lastNanos;
        }

        synchronized RateLimitDecision tryAcquire(String key, int limit, long capacity, double refillPerSecond) {
            long now = System.nanoTime();
            long elapsed = Math.max(0L, now - lastNanos);
            lastNanos = now;
            // 容量可能因配置热更新而变化：先按新容量夹取再补充
            tokens = Math.min(capacity, tokens + (elapsed / 1_000_000_000.0d) * refillPerSecond);
            if (tokens >= 1.0d) {
                tokens -= 1.0d;
                return RateLimitDecision.allow(key, limit, (long) tokens);
            }
            long retryAfter = refillPerSecond > 0
                    ? (long) Math.ceil((1.0d - tokens) / refillPerSecond)
                    : 1L;
            return RateLimitDecision.deny(key, limit, (long) tokens, retryAfter);
        }
    }
}
