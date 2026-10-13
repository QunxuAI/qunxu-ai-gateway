package tech.qunxu.module.gateway.security;

import tech.qunxu.module.gateway.config.GatewayProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 进程内令牌桶限流器单元测试（M8e）：覆盖容量耗尽后拒绝、按速率补充恢复、突发系数扩容、
 * 键隔离、未限制判定与桶跟踪/复位。
 *
 * <p>补充速率断言选用较高的 {@code permitsPerMinute}（600/min ≈ 10 令牌/秒），
 * 使等待时间控制在数百毫秒量级，避免测试变慢或抖动误判。</p>
 */
class InMemoryRateLimiterTest {

    private InMemoryRateLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new InMemoryRateLimiter(new GatewayProperties());
    }

    private RateLimitDecision acquire(String key, int permits) {
        return acquire(key, permits, 1.0d);
    }

    private RateLimitDecision acquire(String key, int permits, double burstRatio) {
        return limiter.tryAcquire(key, permits, burstRatio).block(Duration.ofSeconds(2));
    }

    @Test
    void allowsUpToCapacityThenDenies() {
        String key = "gw:ratelimit:caller:alice";
        for (int i = 0; i < 5; i++) {
            RateLimitDecision decision = acquire(key, 5);
            assertThat(decision.allowed()).as("第 %d 次应放行", i + 1).isTrue();
            assertThat(decision.limit()).isEqualTo(5L);
            assertThat(decision.degraded()).isFalse();
            assertThat(decision.key()).isEqualTo(key);
        }

        RateLimitDecision denied = acquire(key, 5);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.remaining()).isZero();
        // 60 秒补满 5 个令牌 → 补 1 个约需 12 秒
        assertThat(denied.retryAfterSeconds()).isBetween(1L, 13L);
    }

    @Test
    void refillsTokensOverTime() throws InterruptedException {
        String key = "gw:ratelimit:caller:bob";
        // 容量与补充速率均由 permitsPerMinute 导出：600/min → 容量 600、补充 10 令牌/秒
        int permits = 600;
        for (int i = 0; i < permits; i++) {
            assertThat(acquire(key, permits).allowed()).as("容量内第 %d 次应放行", i + 1).isTrue();
        }
        assertThat(acquire(key, permits).allowed()).isFalse();

        Thread.sleep(250L);
        // 约补回 2.5 个令牌
        assertThat(acquire(key, permits).allowed()).isTrue();
    }

    @Test
    void burstRatioExpandsCapacity() {
        String key = "gw:ratelimit:caller:burst";
        for (int i = 0; i < 10; i++) {
            assertThat(acquire(key, 5, 2.0d).allowed()).as("突发容量内应放行 %d", i + 1).isTrue();
        }
        assertThat(acquire(key, 5, 2.0d).allowed()).isFalse();
    }

    @Test
    void burstRatioBelowOneIsClampedToOne() {
        String key = "gw:ratelimit:caller:clamp";
        assertThat(acquire(key, 3, 0.2d).allowed()).isTrue();
        assertThat(acquire(key, 3, 0.2d).allowed()).isTrue();
        assertThat(acquire(key, 3, 0.2d).allowed()).isTrue();
        // 系数被夹取为 1，容量仍为 3 而非 0
        assertThat(acquire(key, 3, 0.2d).allowed()).isFalse();
    }

    @Test
    void keysAreIsolated() {
        for (int i = 0; i < 2; i++) {
            assertThat(acquire("gw:ratelimit:caller:a", 2).allowed()).isTrue();
        }
        assertThat(acquire("gw:ratelimit:caller:a", 2).allowed()).isFalse();
        // 另一个键不受影响
        assertThat(acquire("gw:ratelimit:caller:b", 2).allowed()).isTrue();
    }

    @Test
    void nonPositivePermitsMeansUnlimited() {
        StepVerifier.create(limiter.tryAcquire("gw:ratelimit:caller:free", 0, 1.0d))
                .assertNext(decision -> {
                    assertThat(decision.allowed()).isTrue();
                    assertThat(decision.limit()).isZero();
                    assertThat(decision.remaining()).isEqualTo(-1L);
                })
                .verifyComplete();
    }

    @Test
    void blankKeyMeansUnlimited() {
        StepVerifier.create(limiter.tryAcquire("  ", 10, 1.0d))
                .assertNext(decision -> assertThat(decision.allowed()).isTrue())
                .verifyComplete();
        StepVerifier.create(limiter.tryAcquire(null, 10, 1.0d))
                .assertNext(decision -> assertThat(decision.allowed()).isTrue())
                .verifyComplete();
    }

    @Test
    void tracksAndResetsBuckets() {
        acquire("gw:ratelimit:caller:t1", 10);
        acquire("gw:ratelimit:caller:t2", 10);
        assertThat(limiter.trackedKeys()).isEqualTo(2);

        limiter.reset();
        assertThat(limiter.trackedKeys()).isZero();
        // 复位后桶重新灌满
        assertThat(acquire("gw:ratelimit:caller:t1", 1).allowed()).isTrue();
    }

    @Test
    void defaultTryAcquireUsesUnitBurstRatio() {
        RateLimiter asSpi = limiter;
        RateLimitDecision decision = asSpi.tryAcquire("gw:ratelimit:caller:spi", 1)
                .block(Duration.ofSeconds(2));
        assertThat(decision).isNotNull();
        assertThat(decision.allowed()).isTrue();
        assertThat(asSpi.tryAcquire("gw:ratelimit:caller:spi", 1)
                .block(Duration.ofSeconds(2)).allowed()).isFalse();
    }
}
