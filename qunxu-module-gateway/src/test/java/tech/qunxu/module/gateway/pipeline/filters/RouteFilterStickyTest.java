package tech.qunxu.module.gateway.pipeline.filters;

import tech.qunxu.module.gateway.common.enums.HealthStatus;
import tech.qunxu.module.gateway.common.enums.ProtocolType;
import tech.qunxu.module.gateway.config.GatewayProperties;
import tech.qunxu.module.gateway.health.ChannelHealth;
import tech.qunxu.module.gateway.persistence.entity.LogicalModelEntity;
import tech.qunxu.module.gateway.persistence.entity.ModelChannelEntity;
import tech.qunxu.module.gateway.persistence.entity.ProviderEntity;
import tech.qunxu.module.gateway.pipeline.PipelineChain;
import tech.qunxu.module.gateway.pipeline.RequestContext;
import tech.qunxu.module.gateway.router.ChannelCandidate;
import tech.qunxu.module.gateway.router.RoutingService;
import tech.qunxu.module.gateway.router.StickyRouter;
import tech.qunxu.module.gateway.security.CallerIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RouteFilter} 会话级粘性路由单元测试：桩 {@link RoutingService} 与 {@link StickyRouter}，
 * 覆盖「无会话走普通路由」「粘性关闭走普通路由」「首轮绑定」「命中复用并续期」「绑定通道失效自动重绑」。
 */
class RouteFilterStickyTest {

    private RoutingService routingService;
    private StickyRouter stickyRouter;
    private GatewayProperties properties;
    private RouteFilter filter;
    private RecordingChain chain;

    private static final class RecordingChain implements PipelineChain {
        private int executions;

        @Override
        public Mono<Void> execute(RequestContext ctx) {
            executions++;
            return Mono.empty();
        }
    }

    @BeforeEach
    void setUp() {
        routingService = mock(RoutingService.class);
        stickyRouter = mock(StickyRouter.class);
        properties = new GatewayProperties();
        filter = new RouteFilter(routingService, stickyRouter, properties);
        chain = new RecordingChain();
    }

    private static RequestContext ctxWithSession(String sessionId) {
        RequestContext ctx = RequestContext.create(ProtocolType.OPENAI, "POST",
                "/v1/chat/completions", "/v1/chat/completions", "trace-sticky", null);
        ctx.setLogicalModel(model());
        ctx.setCaller(CallerIdentity.of("alice", "Alice", "default", null, null));
        ctx.setSessionId(sessionId);
        return ctx;
    }

    private static LogicalModelEntity model() {
        LogicalModelEntity m = new LogicalModelEntity();
        m.setId(7L);
        m.setName("m");
        return m;
    }

    private static ChannelCandidate candidate(long channelId) {
        ModelChannelEntity c = new ModelChannelEntity();
        c.setId(channelId);
        c.setUpstreamModel("up-" + channelId);
        ProviderEntity p = new ProviderEntity();
        p.setId(100L);
        p.setCode("P" + channelId);
        ChannelHealth h = new ChannelHealth(channelId, HealthStatus.UP, 0, 1, null, 10d, System.currentTimeMillis());
        return new ChannelCandidate(c, p, h);
    }

    @Test
    void routesNormallyWhenStickyDisabledEvenWithSession() {
        // enabled=false（默认）
        RequestContext ctx = ctxWithSession("s1");
        when(routingService.route(ctx.getLogicalModel(), ctx)).thenReturn(Mono.just(candidate(1L)));

        StepVerifier.create(filter.filter(ctx, chain)).verifyComplete();

        assertThat(chain.executions).isEqualTo(1);
        verify(stickyRouter, never()).lookup(anyLong(), anyString());
    }

    @Test
    void routesNormallyWithoutSessionHeader() {
        properties.getSticky().setEnabled(true);
        RequestContext ctx = ctxWithSession(null);
        when(routingService.route(ctx.getLogicalModel(), ctx)).thenReturn(Mono.just(candidate(1L)));

        StepVerifier.create(filter.filter(ctx, chain)).verifyComplete();

        assertThat(chain.executions).isEqualTo(1);
        verify(stickyRouter, never()).lookup(anyLong(), anyString());
    }

    @Test
    void bindsChannelOnFirstRound() {
        properties.getSticky().setEnabled(true);
        RequestContext ctx = ctxWithSession("conv-1");
        when(stickyRouter.lookup(7L, "alice:conv-1")).thenReturn(Mono.empty());
        when(stickyRouter.bind(7L, "alice:conv-1", 3L)).thenReturn(Mono.empty());
        when(routingService.route(ctx.getLogicalModel(), ctx)).thenReturn(Mono.just(candidate(3L)));

        StepVerifier.create(filter.filter(ctx, chain)).verifyComplete();

        assertThat(chain.executions).isEqualTo(1);
        verify(stickyRouter).bind(7L, "alice:conv-1", 3L);
        assertThat(ctx.getSelectedChannel().getId()).isEqualTo(3L);
        assertThat(ctx.<String>attribute(RouteFilter.ATTR_STICKY_EVENT)).isEqualTo("sticky-bind:3");
    }

    @Test
    void reusesBoundChannelOnHitAndRefreshesTtl() {
        properties.getSticky().setEnabled(true);
        RequestContext ctx = ctxWithSession("conv-1");
        when(stickyRouter.lookup(7L, "alice:conv-1")).thenReturn(Mono.just(3L));
        when(stickyRouter.bind(7L, "alice:conv-1", 3L)).thenReturn(Mono.empty());
        when(routingService.route(ctx.getLogicalModel(), ctx, 3L)).thenReturn(Mono.just(candidate(3L)));

        StepVerifier.create(filter.filter(ctx, chain)).verifyComplete();

        assertThat(chain.executions).isEqualTo(1);
        verify(stickyRouter).bind(7L, "alice:conv-1", 3L);   // 命中续期
        assertThat(ctx.<String>attribute(RouteFilter.ATTR_STICKY_EVENT)).isEqualTo("sticky-hit:3");
    }

    @Test
    void rebindsWhenBoundChannelInvalid() {
        properties.getSticky().setEnabled(true);
        RequestContext ctx = ctxWithSession("conv-1");
        when(stickyRouter.lookup(7L, "alice:conv-1")).thenReturn(Mono.just(1L));
        when(stickyRouter.bind(7L, "alice:conv-1", 2L)).thenReturn(Mono.empty());
        // 绑定通道 1 已不可路由 → 回退负载均衡选中 2
        when(routingService.route(ctx.getLogicalModel(), ctx, 1L)).thenReturn(Mono.just(candidate(2L)));

        StepVerifier.create(filter.filter(ctx, chain)).verifyComplete();

        assertThat(chain.executions).isEqualTo(1);
        verify(stickyRouter).bind(7L, "alice:conv-1", 2L);
        assertThat(ctx.getSelectedChannel().getId()).isEqualTo(2L);
        assertThat(ctx.<String>attribute(RouteFilter.ATTR_STICKY_EVENT)).isEqualTo("sticky-rebind:1->2");
    }
}
