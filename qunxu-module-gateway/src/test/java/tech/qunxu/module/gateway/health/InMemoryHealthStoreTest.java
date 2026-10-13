package tech.qunxu.module.gateway.health;

import tech.qunxu.module.gateway.common.enums.HealthStatus;
import tech.qunxu.module.gateway.config.GatewayProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * {@link InMemoryHealthStore} 单元测试：验证读/写/批量读、成功/失败计数与 EWMA、剔除与恢复的完整生命周期。
 * 该实现亦用于无 Redis 的单机/测试环境。
 */
class InMemoryHealthStoreTest {

    private InMemoryHealthStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryHealthStore(new GatewayProperties());
    }

    @Test
    void getReturnsUnknownWhenAbsent() {
        StepVerifier.create(store.get(99L))
                .assertNext(h -> {
                    assertThat(h.channelId()).isEqualTo(99L);
                    assertThat(h.status()).isEqualTo(HealthStatus.UNKNOWN);
                    assertThat(h.isRoutable()).isTrue();
                })
                .verifyComplete();
    }

    @Test
    void recordSuccessAccumulatesEwma() {
        StepVerifier.create(store.recordSuccess(1L, 100L).then(store.recordSuccess(1L, 200L)))
                .verifyComplete();
        StepVerifier.create(store.get(1L))
                .assertNext(h -> {
                    assertThat(h.consecutiveSuccesses()).isEqualTo(2);
                    assertThat(h.avgLatencyMs()).isCloseTo(130.0, within(0.0001)); // 0.3*200+0.7*100
                })
                .verifyComplete();
    }

    @Test
    void recordFailureThenEjectThenRecover() {
        StepVerifier.create(store.recordFailure(2L).then(store.eject(2L, 60_000L)))
                .verifyComplete();
        StepVerifier.create(store.get(2L))
                .assertNext(h -> {
                    assertThat(h.status()).isEqualTo(HealthStatus.EJECTED);
                    assertThat(h.isRoutable()).isFalse();
                })
                .verifyComplete();

        StepVerifier.create(store.recover(2L)).verifyComplete();
        StepVerifier.create(store.get(2L))
                .assertNext(h -> {
                    assertThat(h.status()).isEqualTo(HealthStatus.UP);
                    assertThat(h.isRoutable()).isTrue();
                })
                .verifyComplete();
    }

    @Test
    void getAllReturnsOnlyPresentIds() {
        StepVerifier.create(store.save(ChannelHealth.up(1L)).then(store.save(ChannelHealth.unknown(3L).down())))
                .verifyComplete();
        StepVerifier.create(store.getAll(List.of(1L, 2L, 3L)))
                .assertNext(map -> {
                    assertThat(map).containsKeys(1L, 3L);
                    assertThat(map).doesNotContainKey(2L);
                    assertThat(map.get(1L).status()).isEqualTo(HealthStatus.UP);
                    assertThat(map.get(3L).status()).isEqualTo(HealthStatus.DOWN);
                })
                .verifyComplete();
    }

    @Test
    void deleteRemovesEntry() {
        StepVerifier.create(store.save(ChannelHealth.up(5L)).then(store.delete(5L))).verifyComplete();
        StepVerifier.create(store.get(5L))
                .assertNext(h -> assertThat(h.status()).isEqualTo(HealthStatus.UNKNOWN))
                .verifyComplete();
    }
}
