package tech.qunxu.module.gateway.health;

import tech.qunxu.module.gateway.common.enums.HealthStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * {@link ChannelHealth} 单元测试：验证失败开放的可路由判定、EWMA 时延平滑、成功/失败计数流转，
 * 以及剔除冷却与恢复的状态迁移。
 */
class ChannelHealthTest {

    @Test
    void routableIsFailOpenForUnknownAndUp() {
        assertThat(ChannelHealth.unknown(1L).isRoutable()).isTrue();
        assertThat(ChannelHealth.up(1L).isRoutable()).isTrue();
        assertThat(new ChannelHealth(1L, null, 0, 0, null, -1d, null).isRoutable()).isTrue();
    }

    @Test
    void downIsNotRoutable() {
        assertThat(ChannelHealth.unknown(1L).down().isRoutable()).isFalse();
    }

    @Test
    void ejectedIsRoutableOnlyAfterCooldown() {
        ChannelHealth future = ChannelHealth.unknown(1L).ejected(60_000L);
        assertThat(future.status()).isEqualTo(HealthStatus.EJECTED);
        assertThat(future.isRoutable()).isFalse();
        assertThat(future.isEjected()).isTrue();

        long past = System.currentTimeMillis() - 1_000L;
        ChannelHealth elapsed = new ChannelHealth(1L, HealthStatus.EJECTED, 3, 0, past, -1d, past);
        assertThat(elapsed.isRoutable()).isTrue();   // 半开：冷却已过允许试探
        assertThat(elapsed.isEjected()).isFalse();
    }

    @Test
    void hasLatencyReflectsSentinel() {
        assertThat(ChannelHealth.unknown(1L).hasLatency()).isFalse();
        assertThat(ChannelHealth.unknown(1L).afterSuccess(50L, 0.3).hasLatency()).isTrue();
    }

    @Test
    void afterSuccessAppliesEwmaAndResetsFailures() {
        ChannelHealth start = new ChannelHealth(1L, HealthStatus.UP, 4, 0, null, -1d, null);
        // 首个样本直接取该值
        ChannelHealth first = start.afterSuccess(100L, 0.3);
        assertThat(first.avgLatencyMs()).isCloseTo(100.0, within(0.0001));
        assertThat(first.consecutiveFailures()).isZero();
        assertThat(first.consecutiveSuccesses()).isEqualTo(1);
        // 第二个样本 EWMA：0.3*200 + 0.7*100 = 130
        ChannelHealth second = first.afterSuccess(200L, 0.3);
        assertThat(second.avgLatencyMs()).isCloseTo(130.0, within(0.0001));
        assertThat(second.consecutiveSuccesses()).isEqualTo(2);
    }

    @Test
    void afterSuccessPromotesUnknownToUp() {
        // 回归：尚无数据的通道（UNKNOWN）收到成功探测/调用后应显示为 UP，而非一直 UNKNOWN
        ChannelHealth start = ChannelHealth.unknown(1L);
        ChannelHealth after = start.afterSuccess(50L, 0.3);
        assertThat(after.status()).isEqualTo(HealthStatus.UP);
        assertThat(after.consecutiveSuccesses()).isEqualTo(1);
        assertThat(after.avgLatencyMs()).isCloseTo(50.0, within(0.0001));
    }

    @Test
    void afterSuccessKeepsDownAndEjectedForHalfOpenRecovery() {
        // DOWN/EJECTED 不因单次成功直接恢复，由上层按半开恢复阈值判定
        ChannelHealth down = ChannelHealth.unknown(1L).down();
        assertThat(down.afterSuccess(10L, 0.3).status()).isEqualTo(HealthStatus.DOWN);

        ChannelHealth ejected = ChannelHealth.unknown(1L).ejected(60_000L);
        assertThat(ejected.afterSuccess(10L, 0.3).status()).isEqualTo(HealthStatus.EJECTED);
    }

    @Test
    void afterFailureIncrementsAndClearsSuccesses() {
        ChannelHealth start = new ChannelHealth(1L, HealthStatus.UP, 0, 5, null, 80d, null);
        ChannelHealth failed = start.afterFailure();
        assertThat(failed.consecutiveFailures()).isEqualTo(1);
        assertThat(failed.consecutiveSuccesses()).isZero();
        assertThat(failed.status()).isEqualTo(HealthStatus.UP);   // 状态迁移由上层判定
        assertThat(failed.avgLatencyMs()).isCloseTo(80.0, within(0.0001));
    }

    @Test
    void recoveredClearsEjectionAndFailures() {
        ChannelHealth ejected = ChannelHealth.unknown(1L).afterFailure().ejected(60_000L);
        ChannelHealth recovered = ejected.recovered();
        assertThat(recovered.status()).isEqualTo(HealthStatus.UP);
        assertThat(recovered.ejectedUntil()).isNull();
        assertThat(recovered.consecutiveFailures()).isZero();
        assertThat(recovered.isRoutable()).isTrue();
    }

    @Test
    void invalidAlphaFallsBackToDefault() {
        ChannelHealth h = ChannelHealth.unknown(1L).afterSuccess(100L, 0.3).afterSuccess(200L, 5.0);
        // alpha 非法（>1）回退 0.3：0.3*200 + 0.7*100 = 130
        assertThat(h.avgLatencyMs()).isCloseTo(130.0, within(0.0001));
    }
}
