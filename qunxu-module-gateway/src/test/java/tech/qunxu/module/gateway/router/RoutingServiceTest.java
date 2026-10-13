package tech.qunxu.module.gateway.router;

import tech.qunxu.module.gateway.common.enums.HealthStatus;
import tech.qunxu.module.gateway.common.enums.ProtocolType;
import tech.qunxu.module.gateway.common.exception.ErrorCode;
import tech.qunxu.module.gateway.common.exception.GatewayException;
import tech.qunxu.module.gateway.config.GatewayProperties;
import tech.qunxu.module.gateway.health.ChannelHealth;
import tech.qunxu.module.gateway.health.InMemoryHealthStore;
import tech.qunxu.module.gateway.persistence.entity.LogicalModelEntity;
import tech.qunxu.module.gateway.persistence.entity.ModelChannelEntity;
import tech.qunxu.module.gateway.persistence.entity.ProviderEntity;
import tech.qunxu.module.gateway.persistence.service.ConfigService;
import tech.qunxu.module.gateway.pipeline.RequestContext;
import tech.qunxu.module.gateway.router.lb.LeastLatencyLoadBalancer;
import tech.qunxu.module.gateway.router.lb.PriorityLoadBalancer;
import tech.qunxu.module.gateway.router.lb.RandomLoadBalancer;
import tech.qunxu.module.gateway.router.lb.WeightedRoundRobinLoadBalancer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link RoutingService} 单元测试：以 Mockito 桩 {@link ConfigService}、真实 {@link InMemoryHealthStore}
 * 与真实策略 Bean 组合，验证健康过滤、供应商启用过滤、策略选择（含请求级覆盖）与候选耗尽的 503 错误。
 */
class RoutingServiceTest {

    private ConfigService configService;
    private InMemoryHealthStore healthStore;
    private RoutingService routingService;

    @BeforeEach
    void setUp() {
        configService = mock(ConfigService.class);
        healthStore = new InMemoryHealthStore(new GatewayProperties());
        LoadBalancerResolver resolver = new LoadBalancerResolver(List.of(
                new WeightedRoundRobinLoadBalancer(), new LeastLatencyLoadBalancer(),
                new RandomLoadBalancer(), new PriorityLoadBalancer()), new GatewayProperties());
        routingService = new RoutingService(configService, healthStore, resolver);
    }

    private static RequestContext newCtx() {
        return RequestContext.create(ProtocolType.OPENAI, "POST",
                "/v1/chat/completions", "/v1/chat/completions", "trace-route", null);
    }

    private static LogicalModelEntity model(long id, String name) {
        LogicalModelEntity m = new LogicalModelEntity();
        m.setId(id);
        m.setName(name);
        m.setCapability("CHAT");
        m.setStatus(1);
        return m;
    }

    private static ModelChannelEntity channel(long id, long providerId, int weight, int priority) {
        ModelChannelEntity c = new ModelChannelEntity();
        c.setId(id);
        c.setLogicalModelId(1L);
        c.setProviderId(providerId);
        c.setUpstreamModel("up-" + id);
        c.setProtocol("OPENAI_COMPAT");
        c.setWeight(weight);
        c.setPriority(priority);
        c.setEnabled(1);
        return c;
    }

    private static ProviderEntity provider(long id, int status) {
        ProviderEntity p = new ProviderEntity();
        p.setId(id);
        p.setCode("P" + id);
        p.setBaseUrl("http://stub-" + id);
        p.setAuthType("BEARER");
        p.setStatus(status);
        return p;
    }

    @Test
    void excludesUnhealthyChannel() {
        when(configService.findEnabledChannels(1L))
                .thenReturn(Mono.just(List.of(channel(1, 100, 100, 1), channel(2, 100, 100, 2))));
        when(configService.findProviderById(100L)).thenReturn(Mono.just(provider(100, 1)));
        healthStore.save(new ChannelHealth(2L, HealthStatus.DOWN, 5, 0, null, -1d, System.currentTimeMillis())).block();

        StepVerifier.create(routingService.route(model(1, "m"), newCtx()))
                .assertNext(c -> assertThat(c.channelId()).isEqualTo(1L))
                .verifyComplete();
    }

    @Test
    void honorsPerRequestStrategyOverride() {
        when(configService.findEnabledChannels(1L))
                .thenReturn(Mono.just(List.of(channel(1, 100, 100, 1), channel(2, 100, 100, 2))));
        when(configService.findProviderById(100L)).thenReturn(Mono.just(provider(100, 1)));
        healthStore.save(new ChannelHealth(1L, HealthStatus.UP, 0, 1, null, 100d, System.currentTimeMillis())).block();
        healthStore.save(new ChannelHealth(2L, HealthStatus.UP, 0, 1, null, 30d, System.currentTimeMillis())).block();

        RequestContext ctx = newCtx();
        ctx.setAttribute(RoutingService.ATTR_LB_STRATEGY, "LEAST_LATENCY");
        StepVerifier.create(routingService.route(model(1, "m"), ctx))
                .assertNext(c -> assertThat(c.channelId()).isEqualTo(2L))   // 时延最低
                .verifyComplete();
    }

    @Test
    void defaultWeightedRoundRobinDistributesByWeight() {
        when(configService.findEnabledChannels(1L))
                .thenReturn(Mono.just(List.of(channel(1, 100, 1, 1), channel(2, 100, 3, 1))));
        when(configService.findProviderById(100L)).thenReturn(Mono.just(provider(100, 1)));

        long c1 = 0;
        long c2 = 0;
        for (int i = 0; i < 4; i++) {
            Long id = routingService.route(model(1, "m"), newCtx()).map(ChannelCandidate::channelId).block();
            if (id != null && id == 1L) {
                c1++;
            } else {
                c2++;
            }
        }
        // 权重 1:3 → 4 次内 1 次命中通道1、3 次命中通道2
        assertThat(c1).isEqualTo(1);
        assertThat(c2).isEqualTo(3);
    }

    @Test
    void noEnabledChannelsYields503() {
        when(configService.findEnabledChannels(1L)).thenReturn(Mono.just(List.of()));
        StepVerifier.create(routingService.route(model(1, "m"), newCtx()))
                .verifyErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(GatewayException.class);
                    assertThat(((GatewayException) e).getErrorCode()).isEqualTo(ErrorCode.ALL_CHANNELS_DOWN);
                });
    }

    @Test
    void disabledProviderExcludedYields503() {
        when(configService.findEnabledChannels(1L)).thenReturn(Mono.just(List.of(channel(1, 100, 100, 1))));
        when(configService.findProviderById(100L)).thenReturn(Mono.just(provider(100, 0)));
        StepVerifier.create(routingService.route(model(1, "m"), newCtx()))
                .verifyErrorSatisfies(e -> assertThat(((GatewayException) e).getErrorCode())
                        .isEqualTo(ErrorCode.ALL_CHANNELS_DOWN));
    }

    @Test
    void allUnhealthyYields503() {
        when(configService.findEnabledChannels(1L))
                .thenReturn(Mono.just(List.of(channel(1, 100, 100, 1), channel(2, 100, 100, 2))));
        when(configService.findProviderById(100L)).thenReturn(Mono.just(provider(100, 1)));
        healthStore.save(new ChannelHealth(1L, HealthStatus.DOWN, 5, 0, null, -1d, System.currentTimeMillis())).block();
        healthStore.eject(2L, 60_000L).block();

        StepVerifier.create(routingService.route(model(1, "m"), newCtx()))
                .verifyErrorSatisfies(e -> assertThat(((GatewayException) e).getErrorCode())
                        .isEqualTo(ErrorCode.ALL_CHANNELS_DOWN));
    }

    @Test
    void unresolvedModelYieldsModelNotFound() {
        StepVerifier.create(routingService.route(null, newCtx()))
                .verifyErrorSatisfies(e -> assertThat(((GatewayException) e).getErrorCode())
                        .isEqualTo(ErrorCode.MODEL_NOT_FOUND));
    }

    // ---- 会话级粘性：偏好通道选择 ----

    @Test
    void prefersBoundChannelWhenHealthy() {
        // 权重 100:1，正常轮询几乎必然选通道 1；偏好通道 2 应被直接命中
        when(configService.findEnabledChannels(1L))
                .thenReturn(Mono.just(List.of(channel(1, 100, 100, 1), channel(2, 100, 1, 2))));
        when(configService.findProviderById(100L)).thenReturn(Mono.just(provider(100, 1)));

        StepVerifier.create(routingService.route(model(1, "m"), newCtx(), 2L))
                .assertNext(c -> assertThat(c.channelId()).isEqualTo(2L))
                .verifyComplete();
    }

    @Test
    void fallsBackToBalancerWhenBoundChannelUnhealthy() {
        when(configService.findEnabledChannels(1L))
                .thenReturn(Mono.just(List.of(channel(1, 100, 100, 1), channel(2, 100, 100, 2))));
        when(configService.findProviderById(100L)).thenReturn(Mono.just(provider(100, 1)));
        // 偏好通道 1 已 DOWN → 应回退负载均衡选中通道 2
        healthStore.save(new ChannelHealth(1L, HealthStatus.DOWN, 5, 0, null, -1d, System.currentTimeMillis())).block();

        StepVerifier.create(routingService.route(model(1, "m"), newCtx(), 1L))
                .assertNext(c -> assertThat(c.channelId()).isEqualTo(2L))
                .verifyComplete();
    }

    @Test
    void fallsBackToBalancerWhenBoundChannelExcluded() {
        when(configService.findEnabledChannels(1L))
                .thenReturn(Mono.just(List.of(channel(1, 100, 100, 1), channel(2, 100, 100, 2))));
        when(configService.findProviderById(100L)).thenReturn(Mono.just(provider(100, 1)));

        RequestContext ctx = newCtx();
        ctx.setAttribute(RoutingService.ATTR_EXCLUDED_CHANNELS, java.util.Set.of(1L));
        // 偏好通道 1 在故障转移排除集中 → 不得继续命中，回退选中通道 2
        StepVerifier.create(routingService.route(model(1, "m"), ctx, 1L))
                .assertNext(c -> assertThat(c.channelId()).isEqualTo(2L))
                .verifyComplete();
    }
}
