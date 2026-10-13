package tech.qunxu.module.gateway.security;

import tech.qunxu.module.gateway.config.GatewayProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link RedisRateLimiter} 单元测试（M8e）：以 Mockito 桩 {@link ReactiveStringRedisTemplate#execute}
 * 模拟 Lua 脚本返回，验证放行/拒绝判定、桶参数推导（容量与补充速率）、异常与空返回时的失败开放/关闭，
 * 以及脚本自身的原子性要点（单段脚本内完成补充、扣减、续期，并处理时钟回拨）。不依赖真实 Redis。
 */
class RedisRateLimiterTest {

    private static final String KEY = "gw:ratelimit:caller:alice";

    private ReactiveStringRedisTemplate redis;
    private GatewayProperties properties;
    private RedisRateLimiter limiter;

    @BeforeEach
    void setUp() {
        redis = mock(ReactiveStringRedisTemplate.class);
        properties = new GatewayProperties();
        limiter = new RedisRateLimiter(redis, properties);
    }

    /** 桩脚本返回 {@code {allowed, remaining, retryAfter}}。 */
    @SuppressWarnings("rawtypes")
    private void stubScript(Flux<List> result) {
        doReturn(result).when(redis).execute(any(RedisScript.class), anyList(), anyList());
    }

    private void stubScript(Object... tuple) {
        stubScript(Flux.just(List.of(tuple)));
    }

    @Test
    void allowsWhenScriptReturnsPositive() {
        stubScript(1L, 4L, 0L);

        StepVerifier.create(limiter.tryAcquire(KEY, 5, 1.0d))
                .assertNext(decision -> {
                    assertThat(decision.allowed()).isTrue();
                    assertThat(decision.degraded()).isFalse();
                    assertThat(decision.limit()).isEqualTo(5L);
                    assertThat(decision.remaining()).isEqualTo(4L);
                    assertThat(decision.retryAfterSeconds()).isZero();
                    assertThat(decision.key()).isEqualTo(KEY);
                })
                .verifyComplete();
    }

    @Test
    void deniesWithRetryAfterWhenBucketEmpty() {
        stubScript(0L, 0L, 7L);

        StepVerifier.create(limiter.tryAcquire(KEY, 5, 1.0d))
                .assertNext(decision -> {
                    assertThat(decision.allowed()).isFalse();
                    assertThat(decision.remaining()).isZero();
                    assertThat(decision.retryAfterSeconds()).isEqualTo(7L);
                })
                .verifyComplete();
    }

    @Test
    @SuppressWarnings("rawtypes")
    void derivesBucketCapacityRefillAndTtl() {
        stubScript(1L, 9L, 0L);
        limiter.tryAcquire(KEY, 300, 2.0d).block(Duration.ofSeconds(2));

        ArgumentCaptor<List> keys = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List> args = ArgumentCaptor.forClass(List.class);
        verify(redis).execute(any(RedisScript.class), keys.capture(), args.capture());

        assertThat(keys.getValue()).containsExactly(KEY);
        List<?> argv = args.getValue();
        assertThat(argv).hasSize(5);
        // 容量 = 300 × 2.0 = 600
        assertThat(argv.get(0)).isEqualTo("600");
        // 补充速率 = 300 / 60 = 5.0 令牌/秒（稳态吞吐等于配置的每分钟许可数）
        assertThat(argv.get(1)).isEqualTo("5.0");
        // 当前毫秒时间戳
        assertThat(Long.parseLong(argv.get(2).toString())).isPositive();
        // 单次请求消耗 1 个令牌
        assertThat(argv.get(3)).isEqualTo("1");
        // TTL = max(120, 120 × 2.0) = 240 秒
        assertThat(argv.get(4)).isEqualTo("240");
    }

    @Test
    void clampsTtlToMinimum() {
        stubScript(1L, 0L, 0L);
        limiter.tryAcquire(KEY, 60, 1.0d).block(Duration.ofSeconds(2));

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<List> args = ArgumentCaptor.forClass(List.class);
        verify(redis).execute(any(RedisScript.class), anyList(), args.capture());
        assertThat(((List<?>) args.getValue()).get(4)).isEqualTo("120");
    }

    @Test
    void failOpenDegradesToAllowOnEmptyResult() {
        stubScript(Flux.empty());

        StepVerifier.create(limiter.tryAcquire(KEY, 5, 1.0d))
                .assertNext(decision -> {
                    assertThat(decision.allowed()).isTrue();
                    assertThat(decision.degraded()).isTrue();
                    assertThat(decision.limit()).isEqualTo(5L);
                })
                .verifyComplete();
    }

    @Test
    void failOpenDegradesToAllowOnError() {
        stubScript(Flux.error(new IllegalStateException("connection refused")));

        StepVerifier.create(limiter.tryAcquire(KEY, 5, 1.0d))
                .assertNext(decision -> {
                    assertThat(decision.allowed()).isTrue();
                    assertThat(decision.degraded()).isTrue();
                })
                .verifyComplete();
    }

    @Test
    void failClosedDeniesOnError() {
        properties.getRateLimit().setFailOpen(false);
        RedisRateLimiter failClosed = new RedisRateLimiter(redis, properties);
        stubScript(Flux.error(new IllegalStateException("connection refused")));

        StepVerifier.create(failClosed.tryAcquire(KEY, 5, 1.0d))
                .assertNext(decision -> {
                    assertThat(decision.allowed()).isFalse();
                    assertThat(decision.degraded()).isFalse();
                    assertThat(decision.retryAfterSeconds()).isEqualTo(1L);
                })
                .verifyComplete();
    }

    @Test
    void malformedScriptResultFallsBack() {
        stubScript(Flux.just(List.of(1L)));

        StepVerifier.create(limiter.tryAcquire(KEY, 5, 1.0d))
                .assertNext(decision -> {
                    assertThat(decision.allowed()).isTrue();
                    assertThat(decision.degraded()).isTrue();
                })
                .verifyComplete();
    }

    @Test
    void parsesNumericStringsFromScript() {
        // Lettuce 在某些序列化配置下可能回传字符串形式的整数
        stubScript("1", "3", "0");

        StepVerifier.create(limiter.tryAcquire(KEY, 5, 1.0d))
                .assertNext(decision -> {
                    assertThat(decision.allowed()).isTrue();
                    assertThat(decision.remaining()).isEqualTo(3L);
                })
                .verifyComplete();
    }

    @Test
    @SuppressWarnings("rawtypes")
    void nonPositivePermitsSkipsRedis() {
        StepVerifier.create(limiter.tryAcquire(KEY, 0, 1.0d))
                .assertNext(decision -> {
                    assertThat(decision.allowed()).isTrue();
                    assertThat(decision.limit()).isZero();
                    assertThat(decision.remaining()).isEqualTo(-1L);
                })
                .verifyComplete();

        verify(redis, never()).execute(any(RedisScript.class), anyList(), anyList());
    }

    @Test
    @SuppressWarnings("rawtypes")
    void blankKeySkipsRedis() {
        StepVerifier.create(limiter.tryAcquire("  ", 10, 1.0d))
                .assertNext(decision -> assertThat(decision.allowed()).isTrue())
                .verifyComplete();

        verify(redis, never()).execute(any(RedisScript.class), anyList(), anyList());
    }

    @Test
    void tokenBucketScriptIsAtomicAndClockSkewSafe() {
        String lua = RedisRateLimiter.TOKEN_BUCKET_LUA;
        // 读-改-写必须在同一段脚本内完成，避免跨实例竞态导致超发
        assertThat(lua).contains("HMGET").contains("HSET").contains("EXPIRE");
        // 时钟回拨时按 0 处理，避免负数补充把桶抽干
        assertThat(lua).contains("if elapsed < 0 then elapsed = 0 end");
        // 容量夹取，防止补充超过配置上限
        assertThat(lua).contains("math.min(capacity");
        // 返回三元组，供上层解析放行/剩余/退避秒数
        assertThat(lua).contains("return { allowed, math.floor(tokens), retryAfter }");
    }
}
