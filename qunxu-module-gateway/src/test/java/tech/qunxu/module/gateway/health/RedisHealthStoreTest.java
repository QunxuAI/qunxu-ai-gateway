package tech.qunxu.module.gateway.health;

import tech.qunxu.module.gateway.common.enums.HealthStatus;
import tech.qunxu.module.gateway.config.GatewayProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveHashOperations;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link RedisHealthStore} 单元测试：以 Mockito 桩 {@link ReactiveStringRedisTemplate} 与其
 * {@link ReactiveHashOperations}，验证 hash 编解码、缺失键与错误时的「失败开放」，以及 recordSuccess 的
 * EWMA 计算与落库（含 TTL）。不依赖真实 Redis。
 */
class RedisHealthStoreTest {

    private static final String KEY3 = "gw:channel:health:3";

    private ReactiveStringRedisTemplate redis;
    private ReactiveHashOperations<String, String, String> hashOps;
    private RedisHealthStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(ReactiveStringRedisTemplate.class);
        hashOps = mock(ReactiveHashOperations.class);
        when(redis.<String, String>opsForHash()).thenReturn(hashOps);
        store = new RedisHealthStore(redis, new GatewayProperties());
    }

    @Test
    void getParsesHashFields() {
        when(hashOps.entries("gw:channel:health:7")).thenReturn(Flux.just(
                Map.entry("status", "UP"),
                Map.entry("failCount", "1"),
                Map.entry("successCount", "3"),
                Map.entry("ejectedUntil", ""),
                Map.entry("avgLatency", "42.5"),
                Map.entry("updatedAt", "123")));

        StepVerifier.create(store.get(7L))
                .assertNext(h -> {
                    assertThat(h.channelId()).isEqualTo(7L);
                    assertThat(h.status()).isEqualTo(HealthStatus.UP);
                    assertThat(h.consecutiveFailures()).isEqualTo(1);
                    assertThat(h.consecutiveSuccesses()).isEqualTo(3);
                    assertThat(h.ejectedUntil()).isNull();
                    assertThat(h.avgLatencyMs()).isEqualTo(42.5);
                    assertThat(h.updatedAt()).isEqualTo(123L);
                })
                .verifyComplete();
    }

    @Test
    void getReturnsUnknownForMissingKey() {
        when(hashOps.entries("gw:channel:health:9")).thenReturn(Flux.empty());
        StepVerifier.create(store.get(9L))
                .assertNext(h -> {
                    assertThat(h.status()).isEqualTo(HealthStatus.UNKNOWN);
                    assertThat(h.isRoutable()).isTrue();
                })
                .verifyComplete();
    }

    @Test
    void getFailsOpenOnError() {
        when(hashOps.entries("gw:channel:health:5")).thenReturn(Flux.error(new RuntimeException("redis down")));
        StepVerifier.create(store.get(5L))
                .assertNext(h -> {
                    assertThat(h.status()).isEqualTo(HealthStatus.UNKNOWN);
                    assertThat(h.isRoutable()).isTrue();
                })
                .verifyComplete();
    }

    @Test
    void getAllAggregatesPresentKeysOnly() {
        when(hashOps.entries("gw:channel:health:1")).thenReturn(Flux.just(Map.entry("status", "DOWN")));
        when(hashOps.entries("gw:channel:health:2")).thenReturn(Flux.empty());

        StepVerifier.create(store.getAll(List.of(1L, 2L)))
                .assertNext(map -> {
                    assertThat(map).containsKey(1L);
                    assertThat(map.get(1L).status()).isEqualTo(HealthStatus.DOWN);
                    assertThat(map).doesNotContainKey(2L);
                })
                .verifyComplete();
    }

    @Test
    void getAllEmptyInputReturnsEmptyMap() {
        StepVerifier.create(store.getAll(List.of()))
                .assertNext(map -> assertThat(map).isEmpty())
                .verifyComplete();
    }

    @Test
    void recordSuccessComputesEwmaAndPersistsWithTtl() {
        when(hashOps.entries(KEY3)).thenReturn(Flux.just(
                Map.entry("status", "UP"), Map.entry("avgLatency", "100.0")));
        AtomicReference<Map<String, String>> saved = new AtomicReference<>();
        when(hashOps.putAll(eq(KEY3), anyMap())).thenAnswer(inv -> {
            saved.set(inv.getArgument(1));
            return Mono.just(6L);
        });
        when(redis.expire(eq(KEY3), any(Duration.class))).thenReturn(Mono.just(true));

        StepVerifier.create(store.recordSuccess(3L, 200L)).verifyComplete();

        assertThat(saved.get()).isNotNull();
        // EWMA：0.3*200 + 0.7*100 = 130
        assertThat(saved.get().get("avgLatency")).isEqualTo("130.0");
        assertThat(saved.get().get("successCount")).isEqualTo("1");
        assertThat(saved.get().get("failCount")).isEqualTo("0");
        assertThat(saved.get().get("status")).isEqualTo("UP");
    }

    @Test
    void ejectSetsEjectedStatusAndCooldown() {
        when(hashOps.entries(KEY3)).thenReturn(Flux.just(Map.entry("status", "UP")));
        AtomicReference<Map<String, String>> saved = new AtomicReference<>();
        when(hashOps.putAll(eq(KEY3), anyMap())).thenAnswer(inv -> {
            saved.set(inv.getArgument(1));
            return Mono.just(6L);
        });
        when(redis.expire(eq(KEY3), any(Duration.class))).thenReturn(Mono.just(true));

        StepVerifier.create(store.eject(3L, 60_000L)).verifyComplete();

        assertThat(saved.get().get("status")).isEqualTo("EJECTED");
        assertThat(Long.parseLong(saved.get().get("ejectedUntil")))
                .isGreaterThan(System.currentTimeMillis());
    }
}
