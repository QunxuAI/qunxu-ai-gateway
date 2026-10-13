package tech.qunxu.module.gateway.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import tech.qunxu.module.gateway.admin.AdminPage;
import tech.qunxu.module.gateway.admin.ChannelHealthView;
import tech.qunxu.module.gateway.common.enums.HealthEventType;
import tech.qunxu.module.gateway.common.enums.HealthStatus;
import tech.qunxu.module.gateway.common.exception.ErrorCode;
import tech.qunxu.module.gateway.common.exception.GatewayException;
import tech.qunxu.module.gateway.config.GatewayProperties;
import tech.qunxu.module.gateway.health.ActiveProbeScheduler;
import tech.qunxu.module.gateway.health.ChannelHealth;
import tech.qunxu.module.gateway.health.HealthEventLog;
import tech.qunxu.module.gateway.health.HealthResult;
import tech.qunxu.module.gateway.health.HealthStore;
import tech.qunxu.module.gateway.persistence.entity.HealthEventEntity;
import tech.qunxu.module.gateway.persistence.entity.LogicalModelEntity;
import tech.qunxu.module.gateway.persistence.entity.ModelChannelEntity;
import tech.qunxu.module.gateway.persistence.entity.ProviderEntity;
import tech.qunxu.module.gateway.persistence.mapper.HealthEventMapper;
import tech.qunxu.module.gateway.persistence.mapper.LogicalModelMapper;
import tech.qunxu.module.gateway.persistence.mapper.ModelChannelMapper;
import tech.qunxu.module.gateway.persistence.mapper.ProviderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link HealthAdminService} 单元测试（M8e）：健康视图把 MySQL 静态配置与 Redis 运行时健康合并，
 * 并验证三条关键约束——健康读取失败按 UNKNOWN 兜底（失败开放，不让 Redis 抖动伪装成通道故障）、
 * 批量补齐名称只用两次 IN 查询（避免 N+1）、手动干预写入 {@code health_event} 与自动自愈同一时间线。
 */
class HealthAdminServiceTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private ModelChannelMapper channelMapper;
    private LogicalModelMapper modelMapper;
    private ProviderMapper providerMapper;
    private HealthEventMapper eventMapper;
    private HealthStore healthStore;
    private HealthEventLog eventLog;
    private ActiveProbeScheduler probeScheduler;
    private GatewayProperties properties;
    private HealthAdminService service;

    @BeforeEach
    void setUp() {
        channelMapper = mock(ModelChannelMapper.class);
        modelMapper = mock(LogicalModelMapper.class);
        providerMapper = mock(ProviderMapper.class);
        eventMapper = mock(HealthEventMapper.class);
        healthStore = mock(HealthStore.class);
        eventLog = mock(HealthEventLog.class);
        probeScheduler = mock(ActiveProbeScheduler.class);
        properties = new GatewayProperties();
        service = new HealthAdminService(channelMapper, modelMapper, providerMapper, eventMapper,
                healthStore, eventLog, probeScheduler, properties);
        when(eventLog.record(any(), any(), any(), any(), any(), any())).thenReturn(Mono.empty());
    }

    private static ModelChannelEntity channel(Long id, Long modelId, Long providerId, Integer enabled) {
        ModelChannelEntity entity = new ModelChannelEntity();
        entity.setId(id);
        entity.setLogicalModelId(modelId);
        entity.setProviderId(providerId);
        entity.setUpstreamModel("deepseek-chat");
        entity.setProtocol("OPENAI_COMPAT");
        entity.setWeight(100);
        entity.setPriority(100);
        entity.setEnabled(enabled);
        return entity;
    }

    /** 预置逻辑模型名与供应商名/编码的批量查询结果。 */
    private void stubReferences() {
        LogicalModelEntity model = new LogicalModelEntity();
        model.setId(10L);
        model.setName("deepseek-chat");
        when(modelMapper.selectList(any())).thenReturn(List.of(model));
        ProviderEntity provider = new ProviderEntity();
        provider.setId(20L);
        provider.setName("DeepSeek");
        provider.setCode("DEEPSEEK");
        when(providerMapper.selectList(any())).thenReturn(List.of(provider));
    }

    // ------------------------------------------------------------------ 健康视图

    @Test
    void mergesStaticConfigWithRuntimeHealth() {
        when(channelMapper.selectList(any())).thenReturn(List.of(
                channel(1L, 10L, 20L, 1),
                channel(2L, 10L, 20L, 0)));
        stubReferences();
        when(healthStore.getAll(any())).thenReturn(Mono.just(Map.of(
                1L, ChannelHealth.up(1L),
                2L, ChannelHealth.unknown(2L).down())));

        List<ChannelHealthView> views = service.channels(null, null, null).block(TIMEOUT);

        assertThat(views).hasSize(2);
        ChannelHealthView first = views.get(0);
        assertThat(first.channelId()).isEqualTo(1L);
        assertThat(first.logicalModel()).isEqualTo("deepseek-chat");
        assertThat(first.providerName()).isEqualTo("DeepSeek");
        assertThat(first.providerCode()).isEqualTo("DEEPSEEK");
        assertThat(first.upstreamModel()).isEqualTo("deepseek-chat");
        assertThat(first.status()).isEqualTo(HealthStatus.UP);
        assertThat(first.routable()).isTrue();
        // 配置层停用的通道即便运行时健康为 UNKNOWN 也不参与路由
        assertThat(views.get(1).status()).isEqualTo(HealthStatus.DOWN);
        assertThat(views.get(1).enabled()).isZero();
    }

    @Test
    void fallsBackToUnknownWhenHealthStoreFails() {
        when(channelMapper.selectList(any())).thenReturn(List.of(channel(1L, 10L, 20L, 1)));
        stubReferences();
        when(healthStore.getAll(any())).thenReturn(Mono.error(new IllegalStateException("redis down")));

        // Redis 抖动不得让管理视图整体失败，也不得把通道判成故障
        StepVerifier.create(service.channels(null, null, null))
                .assertNext(views -> {
                    assertThat(views).hasSize(1);
                    assertThat(views.get(0).status()).isEqualTo(HealthStatus.UNKNOWN);
                    assertThat(views.get(0).routable()).isTrue();
                })
                .verifyComplete();
    }

    @Test
    void missingHealthSnapshotIsPresentedAsUnknown() {
        when(channelMapper.selectList(any())).thenReturn(List.of(channel(1L, 10L, 20L, 1)));
        stubReferences();
        when(healthStore.getAll(any())).thenReturn(Mono.just(Map.of()));

        List<ChannelHealthView> views = service.channels(null, null, null).block(TIMEOUT);

        assertThat(views).hasSize(1);
        assertThat(views.get(0).status()).isEqualTo(HealthStatus.UNKNOWN);
        assertThat(views.get(0).ejectedUntil()).isNull();
        assertThat(views.get(0).avgLatencyMs()).isNull();
    }

    @Test
    void onlyUnhealthyFiltersOutRoutableChannels() {
        when(channelMapper.selectList(any())).thenReturn(List.of(
                channel(1L, 10L, 20L, 1),
                channel(2L, 10L, 20L, 1)));
        stubReferences();
        when(healthStore.getAll(any())).thenReturn(Mono.just(Map.of(
                1L, ChannelHealth.up(1L),
                2L, ChannelHealth.unknown(2L).down())));

        List<ChannelHealthView> unhealthy = service.channels(null, null, true).block(TIMEOUT);

        assertThat(unhealthy).hasSize(1);
        assertThat(unhealthy.get(0).channelId()).isEqualTo(2L);
    }

    @Test
    void enrichesNamesWithTwoBatchQueriesRegardlessOfChannelCount() {
        when(channelMapper.selectList(any())).thenReturn(List.of(
                channel(1L, 10L, 20L, 1),
                channel(2L, 10L, 20L, 1),
                channel(3L, 10L, 20L, 1)));
        stubReferences();
        when(healthStore.getAll(any())).thenReturn(Mono.just(Map.of()));

        service.channels(null, null, null).block(TIMEOUT);

        // N+1 会让名称补齐随通道数线性放大，这里必须各只查一次
        verify(modelMapper, times(1)).selectList(any());
        verify(providerMapper, times(1)).selectList(any());
        verify(channelMapper, times(1)).selectList(any());
    }

    @Test
    void emptyChannelListSkipsHealthLookup() {
        when(channelMapper.selectList(any())).thenReturn(List.of());

        StepVerifier.create(service.channels(null, null, null))
                .assertNext(views -> assertThat(views).isEmpty())
                .verifyComplete();

        verify(healthStore, never()).getAll(any());
    }

    @Test
    void summaryCountsEachStatusBucket() {
        when(channelMapper.selectList(any())).thenReturn(List.of(
                channel(1L, 10L, 20L, 1),
                channel(2L, 10L, 20L, 1),
                channel(3L, 10L, 20L, 1),
                channel(4L, 10L, 20L, 0)));
        stubReferences();
        when(healthStore.getAll(any())).thenReturn(Mono.just(Map.of(
                1L, ChannelHealth.up(1L),
                2L, ChannelHealth.unknown(2L).down(),
                3L, ChannelHealth.unknown(3L).ejected(60_000L))));

        HealthAdminService.HealthSummary summary = service.summary().block(TIMEOUT);

        assertThat(summary).isNotNull();
        assertThat(summary.total()).isEqualTo(4);
        assertThat(summary.up()).isEqualTo(1);
        assertThat(summary.down()).isEqualTo(1);
        assertThat(summary.ejected()).isEqualTo(1);
        // 通道 4 无任何健康快照 → UNKNOWN，但配置层已停用故不可路由
        assertThat(summary.unknown()).isEqualTo(1);
        assertThat(summary.routable()).isEqualTo(1);
        assertThat(summary.disabled()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 单通道查询

    @Test
    void channelReturnsViewForExistingId() {
        when(channelMapper.selectById(1L)).thenReturn(channel(1L, 10L, 20L, 1));
        stubReferences();
        when(healthStore.getAll(any())).thenReturn(Mono.just(Map.of(1L, ChannelHealth.up(1L))));

        ChannelHealthView view = service.channel(1L).block(TIMEOUT);

        assertThat(view).isNotNull();
        assertThat(view.channelId()).isEqualTo(1L);
        assertThat(view.status()).isEqualTo(HealthStatus.UP);
    }

    @Test
    void channelMissingRowYieldsNotFound() {
        when(channelMapper.selectById(99L)).thenReturn(null);

        StepVerifier.create(service.channel(99L))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(GatewayException.class);
                    assertThat(((GatewayException) error).getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
                })
                .verify(TIMEOUT);
    }

    @Test
    void manualOperationsRejectNullChannelId() {
        for (Mono<?> operation : List.of(service.channel(null), service.probe(null),
                service.recover(null), service.eject(null, 30L))) {
            StepVerifier.create(operation)
                    .expectErrorSatisfies(error -> assertThat(((GatewayException) error).getErrorCode())
                            .isEqualTo(ErrorCode.BAD_REQUEST))
                    .verify(TIMEOUT);
        }
        verify(healthStore, never()).get(anyLong());
    }

    // ------------------------------------------------------------------ 手动干预

    @Test
    void probeReturnsResultTogetherWithPostProbeView() {
        when(channelMapper.selectById(1L)).thenReturn(channel(1L, 10L, 20L, 1));
        stubReferences();
        when(probeScheduler.probeChannel(1L)).thenReturn(Mono.just(HealthResult.up(42L)));
        when(healthStore.get(1L)).thenReturn(Mono.just(ChannelHealth.up(1L)));
        when(healthStore.getAll(any())).thenReturn(Mono.just(Map.of(1L, ChannelHealth.up(1L))));

        HealthAdminService.ProbeOutcome outcome = service.probe(1L).block(TIMEOUT);

        assertThat(outcome).isNotNull();
        assertThat(outcome.isUp()).isTrue();
        assertThat(outcome.isAuthExpired()).isFalse();
        assertThat(outcome.result().latencyMs()).isEqualTo(42L);
        assertThat(outcome.health().status()).isEqualTo(HealthStatus.UP);
    }

    @Test
    void probeReportsExpiredCredentials() {
        when(channelMapper.selectById(1L)).thenReturn(channel(1L, 10L, 20L, 1));
        stubReferences();
        when(probeScheduler.probeChannel(1L)).thenReturn(Mono.just(
                HealthResult.down("HTTP 401", ErrorCode.AUTH_EXPIRED)));
        when(healthStore.get(1L)).thenReturn(Mono.just(ChannelHealth.unknown(1L).down()));
        when(healthStore.getAll(any())).thenReturn(Mono.just(Map.of()));

        HealthAdminService.ProbeOutcome outcome = service.probe(1L).block(TIMEOUT);

        // 凭证过期要与一般故障分流，运维据此换 Key 而不是重启通道
        assertThat(outcome.isUp()).isFalse();
        assertThat(outcome.isAuthExpired()).isTrue();
    }

    @Test
    void probeFallsBackToUnknownWhenSchedulerEmitsNothing() {
        when(channelMapper.selectById(1L)).thenReturn(channel(1L, 10L, 20L, 1));
        stubReferences();
        when(probeScheduler.probeChannel(1L)).thenReturn(Mono.empty());
        when(healthStore.get(1L)).thenReturn(Mono.empty());
        when(healthStore.getAll(any())).thenReturn(Mono.just(Map.of()));

        HealthAdminService.ProbeOutcome outcome = service.probe(1L).block(TIMEOUT);

        assertThat(outcome).isNotNull();
        assertThat(outcome.result().status()).isEqualTo(HealthStatus.UNKNOWN);
        assertThat(outcome.isUp()).isFalse();
    }

    @Test
    void recoverWritesUpAndRecordsEvent() {
        when(channelMapper.selectById(1L)).thenReturn(channel(1L, 10L, 20L, 1));
        stubReferences();
        ChannelHealth before = ChannelHealth.unknown(1L).down();
        ChannelHealth after = ChannelHealth.up(1L);
        when(healthStore.get(1L)).thenReturn(Mono.just(before), Mono.just(after));
        when(healthStore.recover(1L)).thenReturn(Mono.empty());
        when(healthStore.getAll(any())).thenReturn(Mono.just(Map.of(1L, after)));

        ChannelHealthView view = service.recover(1L).block(TIMEOUT);

        assertThat(view).isNotNull();
        assertThat(view.status()).isEqualTo(HealthStatus.UP);
        verify(healthStore).recover(1L);
        verify(eventLog).record(eq(1L), eq(20L), eq(HealthEventType.RECOVER),
                eq(HealthStatus.DOWN), eq(HealthStatus.UP), eq("管理接口手动恢复"));
    }

    @Test
    void ejectUsesConfiguredCooldownWhenNotSpecified() {
        properties.getHealth().setEjectCooldownMs(45_000L);
        when(channelMapper.selectById(1L)).thenReturn(channel(1L, 10L, 20L, 1));
        stubReferences();
        when(healthStore.get(1L)).thenReturn(Mono.just(ChannelHealth.up(1L)));
        when(healthStore.eject(eq(1L), anyLong())).thenReturn(Mono.empty());
        when(healthStore.getAll(any())).thenReturn(Mono.just(Map.of()));

        service.eject(1L, null).block(TIMEOUT);

        verify(healthStore).eject(1L, 45_000L);
        verify(eventLog).record(eq(1L), eq(20L), eq(HealthEventType.EJECT),
                eq(HealthStatus.UP), eq(HealthStatus.EJECTED), eq("管理接口手动剔除，冷却 45000ms"));
    }

    @Test
    void ejectAppliesExplicitCooldownAndCapsAtOneDay() {
        when(channelMapper.selectById(1L)).thenReturn(channel(1L, 10L, 20L, 1));
        stubReferences();
        when(healthStore.get(1L)).thenReturn(Mono.just(ChannelHealth.up(1L)));
        when(healthStore.eject(eq(1L), anyLong())).thenReturn(Mono.empty());
        when(healthStore.getAll(any())).thenReturn(Mono.just(Map.of()));

        service.eject(1L, 30L).block(TIMEOUT);
        // 超过上限的冷却值被夹取，避免误填把通道永久摘流
        service.eject(1L, 1_000_000L).block(TIMEOUT);
        // 非正数回落为配置值
        service.eject(1L, 0L).block(TIMEOUT);

        verify(healthStore).eject(1L, 30_000L);
        verify(healthStore).eject(1L, 86_400_000L);
        verify(healthStore).eject(1L, properties.getHealth().getEjectCooldownMs());
    }

    @Test
    void recoverSurvivesHealthStoreReadFailure() {
        when(channelMapper.selectById(1L)).thenReturn(channel(1L, 10L, 20L, 1));
        stubReferences();
        when(healthStore.get(1L)).thenReturn(Mono.error(new IllegalStateException("redis down")));
        when(healthStore.recover(1L)).thenReturn(Mono.empty());
        when(healthStore.getAll(any())).thenReturn(Mono.just(Map.of()));

        // 读不到「变更前状态」也要能完成恢复，from 记为 UNKNOWN
        StepVerifier.create(service.recover(1L))
                .assertNext(view -> assertThat(view.status()).isEqualTo(HealthStatus.UNKNOWN))
                .verifyComplete();

        verify(eventLog).record(eq(1L), eq(20L), eq(HealthEventType.RECOVER),
                eq(HealthStatus.UNKNOWN), eq(HealthStatus.UP), eq("管理接口手动恢复"));
    }

    // ------------------------------------------------------------------ 健康事件检索

    @Test
    void eventsPaginatesAndFilters() {
        AtomicReference<QueryWrapper<HealthEventEntity>> captured = new AtomicReference<>();
        HealthEventEntity row = new HealthEventEntity();
        row.setId(1L);
        row.setChannelId(1L);
        row.setEventType("EJECT");
        when(eventMapper.selectPage(any(), any())).thenAnswer(inv -> {
            captured.set(inv.getArgument(1));
            Page<HealthEventEntity> page = inv.getArgument(0);
            page.setRecords(List.of(row));
            page.setTotal(1L);
            return page;
        });

        AdminPage<HealthEventEntity> result =
                service.events(1L, 20L, "eject", null, null, 1, 20).block(TIMEOUT);

        assertThat(result).isNotNull();
        assertThat(result.items()).hasSize(1);
        assertThat(result.total()).isEqualTo(1L);
        // MyBatis-Plus 的条件参数是惰性格式化的，须先取 SQL 片段再读参数表
        assertThat(captured.get().getSqlSegment())
                .contains("channel_id", "provider_id", "event_type")
                .contains("ORDER BY created_at DESC");
        assertThat(captured.get().getParamNameValuePairs().values())
                .contains(1L, 20L, HealthEventType.EJECT.name());
    }

    @Test
    void eventsRejectUnknownEventType() {
        StepVerifier.create(service.events(null, null, "BOGUS", null, null, 1, 20))
                .expectErrorSatisfies(error -> {
                    assertThat(((GatewayException) error).getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
                    assertThat(error).hasMessageContaining("BOGUS");
                })
                .verify(TIMEOUT);
        verify(eventMapper, never()).selectPage(any(), any());
    }

    @Test
    void eventsApplyDefaultsAndCapPageSize() {
        properties.getAdmin().setMaxPageSize(50);
        when(eventMapper.selectPage(any(), any())).thenAnswer(inv -> inv.getArgument(0));

        AdminPage<HealthEventEntity> result = service.events(null, null, null, null, null, 0, 9_999).block(TIMEOUT);

        assertThat(result.page()).isEqualTo(1);
        assertThat(result.size()).isEqualTo(50);
        assertThat(result.items()).isEmpty();
    }
}
