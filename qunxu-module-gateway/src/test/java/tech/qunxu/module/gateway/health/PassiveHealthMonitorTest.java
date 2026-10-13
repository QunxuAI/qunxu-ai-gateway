package tech.qunxu.module.gateway.health;

import tech.qunxu.module.gateway.common.enums.HealthEventType;
import tech.qunxu.module.gateway.common.enums.HealthStatus;
import tech.qunxu.module.gateway.common.exception.ErrorCode;
import tech.qunxu.module.gateway.common.exception.GatewayException;
import tech.qunxu.module.gateway.config.GatewayProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PassiveHealthMonitor} 单元测试：真实 {@link InMemoryHealthStore} + mock {@link HealthEventLog}，
 * 验证「连续成功恢复」「阈值剔除」「凭证过期立即剔除」「主动探测失败标记 PROBE_FAIL」与故障转移资格判定。
 */
class PassiveHealthMonitorTest {

    private InMemoryHealthStore store;
    private HealthEventLog eventLog;
    private PassiveHealthMonitor monitor;
    private GatewayProperties props;

    @BeforeEach
    void setUp() {
        props = new GatewayProperties();
        props.getHealth().setEnabled(true);
        props.getHealth().setFailureThreshold(3);
        props.getHealth().setRecoverySuccessThreshold(2);
        props.getHealth().setEjectCooldownMs(60_000L);
        props.getHealth().setLatencyAlpha(0.3);
        store = new InMemoryHealthStore(props);
        eventLog = mock(HealthEventLog.class);
        when(eventLog.record(any(), any(), any(), any(), any(), any())).thenReturn(Mono.empty());
        monitor = new PassiveHealthMonitor(store, eventLog, props);
    }

    private ChannelHealth health(long id) {
        return store.get(id).block();
    }

    private static ChannelHealth down(long id) {
        return new ChannelHealth(id, HealthStatus.DOWN, 3, 0, null, -1d, System.currentTimeMillis());
    }

    @Test
    void recordSuccessUpdatesLatencyAndCountersWithoutEvent() {
        store.save(ChannelHealth.up(1L)).block();
        monitor.recordSuccess(1L, 100L, 120L).block();

        ChannelHealth h = health(1L);
        assertThat(h.status()).isEqualTo(HealthStatus.UP);
        assertThat(h.consecutiveSuccesses()).isEqualTo(1);
        assertThat(h.avgLatencyMs()).isCloseTo(120.0, within(0.001));   // 首个样本直接置为时延
        verify(eventLog, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void recoversDownChannelAfterThresholdSuccesses() {
        store.save(down(1L)).block();

        monitor.recordSuccess(1L, 100L, 50L).block();
        assertThat(health(1L).status()).isEqualTo(HealthStatus.DOWN);   // 1 < 2，尚未恢复
        assertThat(health(1L).consecutiveSuccesses()).isEqualTo(1);

        monitor.recordSuccess(1L, 100L, 50L).block();
        assertThat(health(1L).status()).isEqualTo(HealthStatus.UP);     // 达阈值，恢复

        ArgumentCaptor<HealthEventType> type = ArgumentCaptor.forClass(HealthEventType.class);
        verify(eventLog).record(eq(1L), eq(100L), type.capture(),
                eq(HealthStatus.DOWN), eq(HealthStatus.UP), any());
        assertThat(type.getValue()).isEqualTo(HealthEventType.RECOVER);
    }

    @Test
    void recordFailureBelowThresholdDoesNotEject() {
        store.save(ChannelHealth.up(1L)).block();
        monitor.recordFailure(1L, 100L, new GatewayException(ErrorCode.PROVIDER_ERROR, "boom")).block();
        monitor.recordFailure(1L, 100L, new GatewayException(ErrorCode.PROVIDER_ERROR, "boom")).block();

        ChannelHealth h = health(1L);
        assertThat(h.consecutiveFailures()).isEqualTo(2);
        assertThat(h.status()).isEqualTo(HealthStatus.UP);
        verify(eventLog, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void recordFailureReachingThresholdEjects() {
        store.save(ChannelHealth.up(1L)).block();
        for (int i = 0; i < 3; i++) {
            monitor.recordFailure(1L, 100L, new GatewayException(ErrorCode.PROVIDER_ERROR, "boom")).block();
        }

        ChannelHealth h = health(1L);
        assertThat(h.status()).isEqualTo(HealthStatus.EJECTED);
        assertThat(h.ejectedUntil()).isNotNull();

        ArgumentCaptor<HealthEventType> type = ArgumentCaptor.forClass(HealthEventType.class);
        verify(eventLog).record(eq(1L), eq(100L), type.capture(), any(), eq(HealthStatus.EJECTED), any());
        assertThat(type.getValue()).isEqualTo(HealthEventType.EJECT);
    }

    @Test
    void authExpiredEjectsImmediately() {
        store.save(ChannelHealth.up(1L)).block();
        monitor.recordFailure(1L, 100L, new GatewayException(ErrorCode.AUTH_EXPIRED, "401")).block();

        assertThat(health(1L).status()).isEqualTo(HealthStatus.EJECTED);
        ArgumentCaptor<HealthEventType> type = ArgumentCaptor.forClass(HealthEventType.class);
        verify(eventLog).record(eq(1L), eq(100L), type.capture(), any(), eq(HealthStatus.EJECTED), any());
        assertThat(type.getValue()).isEqualTo(HealthEventType.AUTH_EXPIRED);
    }

    @Test
    void probeFailureBelowThresholdRecordsProbeFailEvent() {
        store.save(ChannelHealth.up(1L)).block();
        monitor.recordProbeFailure(1L, 100L, HealthResult.down("timeout", ErrorCode.PROVIDER_TIMEOUT)).block();

        assertThat(health(1L).consecutiveFailures()).isEqualTo(1);
        assertThat(health(1L).status()).isEqualTo(HealthStatus.UP);     // 未达阈值不剔除
        verify(eventLog).record(eq(1L), eq(100L), eq(HealthEventType.PROBE_FAIL), any(), any(), any());
    }

    @Test
    void probeFailureReachingThresholdEjectsWithProbeFailType() {
        store.save(ChannelHealth.up(1L)).block();
        for (int i = 0; i < 3; i++) {
            monitor.recordProbeFailure(1L, 100L, HealthResult.down("boom", ErrorCode.PROVIDER_ERROR)).block();
        }

        assertThat(health(1L).status()).isEqualTo(HealthStatus.EJECTED);
        verify(eventLog, times(3)).record(eq(1L), eq(100L), eq(HealthEventType.PROBE_FAIL), any(), any(), any());
    }

    @Test
    void probeFailureAuthExpiredEjectsImmediately() {
        store.save(ChannelHealth.up(1L)).block();
        monitor.recordProbeFailure(1L, 100L, HealthResult.down("401", ErrorCode.AUTH_EXPIRED)).block();

        assertThat(health(1L).status()).isEqualTo(HealthStatus.EJECTED);
        verify(eventLog).record(eq(1L), eq(100L), eq(HealthEventType.AUTH_EXPIRED), any(), eq(HealthStatus.EJECTED), any());
    }

    @Test
    void disabledHealthIsNoop() {
        props.getHealth().setEnabled(false);
        PassiveHealthMonitor disabled = new PassiveHealthMonitor(store, eventLog, props);
        disabled.recordFailure(1L, 100L, new GatewayException(ErrorCode.AUTH_EXPIRED, "x")).block();

        assertThat(health(1L).status()).isEqualTo(HealthStatus.UNKNOWN);   // 未写入
        verify(eventLog, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void isFailoverEligibleDistinguishesDownstreamFromClientErrors() {
        assertThat(monitor.isFailoverEligible(new GatewayException(ErrorCode.AUTH_EXPIRED, "x"))).isTrue();
        assertThat(monitor.isFailoverEligible(new GatewayException(ErrorCode.PROVIDER_ERROR, "x"))).isTrue();
        assertThat(monitor.isFailoverEligible(new GatewayException(ErrorCode.PROVIDER_TIMEOUT, "x"))).isTrue();
        assertThat(monitor.isFailoverEligible(new GatewayException(ErrorCode.RATE_LIMITED, "x"))).isTrue();
        assertThat(monitor.isFailoverEligible(new GatewayException(ErrorCode.BAD_REQUEST, "x"))).isFalse();
        assertThat(monitor.isFailoverEligible(new GatewayException(ErrorCode.MODEL_NOT_FOUND, "x"))).isFalse();
        assertThat(monitor.isFailoverEligible(new GatewayException(ErrorCode.GUARDRAIL_BLOCKED, "x"))).isFalse();
        assertThat(monitor.isFailoverEligible(new GatewayException(ErrorCode.ALL_CHANNELS_DOWN, "x"))).isFalse();
        assertThat(monitor.isFailoverEligible(new RuntimeException("network"))).isTrue();   // 无错误码视为下游
    }

    @Test
    void errorCodeOfWalksCauseChain() {
        Throwable nested = new RuntimeException("outer",
                new IllegalStateException("mid", new GatewayException(ErrorCode.AUTH_EXPIRED, "inner")));
        assertThat(PassiveHealthMonitor.errorCodeOf(nested)).isEqualTo(ErrorCode.AUTH_EXPIRED);
        assertThat(PassiveHealthMonitor.errorCodeOf(new RuntimeException("x"))).isNull();
    }
}
