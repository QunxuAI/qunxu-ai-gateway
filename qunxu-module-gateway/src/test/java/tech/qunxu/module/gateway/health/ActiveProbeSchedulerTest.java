package tech.qunxu.module.gateway.health;

import tech.qunxu.module.gateway.adapter.ChannelRuntime;
import tech.qunxu.module.gateway.adapter.ChannelRuntimeFactory;
import tech.qunxu.module.gateway.adapter.ModelAdapter;
import tech.qunxu.module.gateway.adapter.ProviderCredentials;
import tech.qunxu.module.gateway.adapter.ProviderRegistry;
import tech.qunxu.module.gateway.common.enums.ChannelProtocol;
import tech.qunxu.module.gateway.common.enums.HealthEventType;
import tech.qunxu.module.gateway.common.enums.HealthStatus;
import tech.qunxu.module.gateway.common.enums.ProviderType;
import tech.qunxu.module.gateway.common.exception.ErrorCode;
import tech.qunxu.module.gateway.config.GatewayProperties;
import tech.qunxu.module.gateway.persistence.entity.ModelChannelEntity;
import tech.qunxu.module.gateway.persistence.entity.ProviderEntity;
import tech.qunxu.module.gateway.persistence.service.ConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ActiveProbeScheduler} 单元测试：mock {@link ConfigService}/{@link ProviderRegistry}/{@link ChannelRuntimeFactory}
 * 与 {@link ModelAdapter}，真实 {@link InMemoryHealthStore} + {@link PassiveHealthMonitor}（mock {@link HealthEventLog}）。
 * 验证「仅探测非健康通道」「探测成功恢复」「探测失败剔除」「EJECTED 冷却门控」「分布式锁门控」。
 */
class ActiveProbeSchedulerTest {

    private ConfigService configService;
    private InMemoryHealthStore store;
    private HealthEventLog eventLog;
    private ProviderRegistry registry;
    private ChannelRuntimeFactory runtimeFactory;
    private ModelAdapter adapter;
    private GatewayProperties props;
    private ActiveProbeScheduler scheduler;

    @SuppressWarnings("unchecked")
    private final ObjectProvider<ReactiveStringRedisTemplate> redisProvider = mock(ObjectProvider.class);

    @BeforeEach
    void setUp() {
        props = new GatewayProperties();
        props.getHealth().setEnabled(true);
        props.getHealth().setDistributedLock(false);          // 默认跳过 Redis 锁
        props.getHealth().setProbeTimeoutMs(2000L);
        props.getHealth().setProbeIntervalMs(30_000L);
        props.getHealth().setFailureThreshold(1);             // 单次探测失败即剔除
        props.getHealth().setRecoverySuccessThreshold(1);     // 单次探测成功即恢复
        props.getHealth().setEjectCooldownMs(60_000L);
        props.getHealth().setLatencyAlpha(0.3);

        configService = mock(ConfigService.class);
        store = new InMemoryHealthStore(props);
        eventLog = mock(HealthEventLog.class);
        when(eventLog.record(any(), any(), any(), any(), any(), any())).thenReturn(Mono.empty());
        registry = mock(ProviderRegistry.class);
        runtimeFactory = mock(ChannelRuntimeFactory.class);
        adapter = mock(ModelAdapter.class);

        PassiveHealthMonitor monitor = new PassiveHealthMonitor(store, eventLog, props);
        when(registry.adapterFor(any())).thenReturn(adapter);
        when(runtimeFactory.create(any(ModelChannelEntity.class), any(ProviderEntity.class)))
                .thenAnswer(inv -> {
                    ModelChannelEntity ch = inv.getArgument(0);
                    ProviderEntity pv = inv.getArgument(1);
                    return new ChannelRuntime(ch, pv, ProviderType.DEEPSEEK, ChannelProtocol.OPENAI_COMPAT,
                            pv.getBaseUrl(), ch.getUpstreamModel(), ProviderCredentials.empty(), Map.of());
                });

        scheduler = new ActiveProbeScheduler(configService, store, registry, runtimeFactory,
                monitor, props, redisProvider);
    }

    private static ModelChannelEntity channel(long id, long providerId) {
        ModelChannelEntity c = new ModelChannelEntity();
        c.setId(id);
        c.setLogicalModelId(1L);
        c.setProviderId(providerId);
        c.setUpstreamModel("up-" + id);
        c.setProtocol("OPENAI_COMPAT");
        c.setWeight(100);
        c.setPriority(1);
        c.setEnabled(1);
        return c;
    }

    private static ProviderEntity provider(long id) {
        ProviderEntity p = new ProviderEntity();
        p.setId(id);
        p.setCode("DEEPSEEK");
        p.setBaseUrl("http://stub-" + id);
        p.setAuthType("BEARER");
        p.setStatus(1);
        return p;
    }

    private ChannelHealth health(long id) {
        return store.get(id).block();
    }

    private void stubChannels(ModelChannelEntity... channels) {
        when(configService.findAllEnabledChannels()).thenReturn(Mono.just(List.of(channels)));
        for (ModelChannelEntity c : channels) {
            when(configService.findProviderById(c.getProviderId())).thenReturn(Mono.just(provider(c.getProviderId())));
            when(configService.findLogicalModelById(any())).thenReturn(Mono.empty());
        }
    }

    @Test
    void probesDownChannelAndRecoversOnSuccess() {
        store.save(new ChannelHealth(1L, HealthStatus.DOWN, 3, 0, null, -1d, System.currentTimeMillis())).block();
        store.save(ChannelHealth.up(2L)).block();
        stubChannels(channel(1, 100L), channel(2, 100L));
        when(adapter.probe(any(), any())).thenReturn(Mono.just(HealthResult.up(30)));

        Integer probed = scheduler.probeRound().block();

        assertThat(probed).isEqualTo(1);                       // 仅通道1（DOWN）被探测，通道2（UP）跳过
        assertThat(health(1L).status()).isEqualTo(HealthStatus.UP);
        ArgumentCaptor<ChannelRuntime> cap = ArgumentCaptor.forClass(ChannelRuntime.class);
        verify(adapter).probe(cap.capture(), any());
        assertThat(cap.getValue().getChannelId()).isEqualTo(1L);
    }

    @Test
    void skipsUpChannels() {
        store.save(ChannelHealth.up(1L)).block();
        stubChannels(channel(1, 100L));

        Integer probed = scheduler.probeRound().block();

        assertThat(probed).isZero();
        verify(adapter, never()).probe(any(), any());
    }

    @Test
    void probesUnknownChannelAndRecordsLatency() {
        stubChannels(channel(1, 100L));                        // 无健康记录 → UNKNOWN
        when(adapter.probe(any(), any())).thenReturn(Mono.just(HealthResult.up(12)));

        Integer probed = scheduler.probeRound().block();

        assertThat(probed).isEqualTo(1);
        assertThat(health(1L).consecutiveSuccesses()).isEqualTo(1);
        assertThat(health(1L).hasLatency()).isTrue();
    }

    @Test
    void probeFailureEjectsChannelAndRecordsProbeFail() {
        store.save(new ChannelHealth(1L, HealthStatus.DOWN, 0, 0, null, -1d, System.currentTimeMillis())).block();
        stubChannels(channel(1, 100L));
        when(adapter.probe(any(), any())).thenReturn(Mono.just(HealthResult.down("boom", ErrorCode.PROVIDER_ERROR)));

        Integer probed = scheduler.probeRound().block();

        assertThat(probed).isEqualTo(1);
        assertThat(health(1L).status()).isEqualTo(HealthStatus.EJECTED);
        verify(eventLog).record(eq(1L), eq(100L), eq(HealthEventType.PROBE_FAIL), any(), eq(HealthStatus.EJECTED), any());
    }

    @Test
    void ejectedInCooldownIsSkipped() {
        long future = System.currentTimeMillis() + 60_000L;
        store.save(new ChannelHealth(1L, HealthStatus.EJECTED, 5, 0, future, -1d, System.currentTimeMillis())).block();
        stubChannels(channel(1, 100L));

        Integer probed = scheduler.probeRound().block();

        assertThat(probed).isZero();                           // 冷却未到，不探测
        verify(adapter, never()).probe(any(), any());
    }

    @Test
    void ejectedCooldownPassedIsProbedAndRecovers() {
        long past = System.currentTimeMillis() - 1_000L;
        store.save(new ChannelHealth(1L, HealthStatus.EJECTED, 5, 0, past, -1d, System.currentTimeMillis())).block();
        stubChannels(channel(1, 100L));
        when(adapter.probe(any(), any())).thenReturn(Mono.just(HealthResult.up(20)));

        Integer probed = scheduler.probeRound().block();

        assertThat(probed).isEqualTo(1);                       // 半开期可探测
        assertThat(health(1L).status()).isEqualTo(HealthStatus.UP);
        verify(eventLog).record(eq(1L), eq(100L), eq(HealthEventType.RECOVER), any(), eq(HealthStatus.UP), any());
    }

    @Test
    void noChannelsReturnsZero() {
        when(configService.findAllEnabledChannels()).thenReturn(Mono.just(List.of()));
        assertThat(scheduler.probeRound().block()).isZero();
        verify(adapter, never()).probe(any(), any());
    }

    @Test
    void missingProviderSkipsChannel() {
        when(configService.findAllEnabledChannels()).thenReturn(Mono.just(List.of(channel(1, 100L))));
        when(configService.findLogicalModelById(any())).thenReturn(Mono.empty());
        when(configService.findProviderById(100L)).thenReturn(Mono.empty());

        assertThat(scheduler.probeRound().block()).isZero();
        verify(adapter, never()).probe(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void distributedLockNotAcquiredSkipsRound() {
        props.getHealth().setDistributedLock(true);
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        ReactiveValueOperations<String, String> valueOps = mock(ReactiveValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(Mono.just(false));
        when(redisProvider.getIfAvailable()).thenReturn(redis);
        stubChannels(channel(1, 100L));

        assertThat(scheduler.probeRound().block()).isZero();
        verify(adapter, never()).probe(any(), any());
        verify(configService, never()).findAllEnabledChannels();   // 未获锁不进入探测
    }

    @Test
    @SuppressWarnings("unchecked")
    void distributedLockAcquiredProbes() {
        props.getHealth().setDistributedLock(true);
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        ReactiveValueOperations<String, String> valueOps = mock(ReactiveValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(Mono.just(true));
        when(redisProvider.getIfAvailable()).thenReturn(redis);
        store.save(new ChannelHealth(1L, HealthStatus.DOWN, 3, 0, null, -1d, System.currentTimeMillis())).block();
        stubChannels(channel(1, 100L));
        when(adapter.probe(any(), any())).thenReturn(Mono.just(HealthResult.up(15)));

        assertThat(scheduler.probeRound().block()).isEqualTo(1);
        assertThat(health(1L).status()).isEqualTo(HealthStatus.UP);
    }
}
