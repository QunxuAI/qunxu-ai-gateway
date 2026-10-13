package tech.qunxu.module.gateway.pipeline.filters;

import tech.qunxu.module.gateway.adapter.ChannelRuntime;
import tech.qunxu.module.gateway.adapter.ChannelRuntimeFactory;
import tech.qunxu.module.gateway.adapter.ModelAdapter;
import tech.qunxu.module.gateway.adapter.ProviderRegistry;
import tech.qunxu.module.gateway.common.exception.ErrorCode;
import tech.qunxu.module.gateway.common.exception.GatewayException;
import tech.qunxu.module.gateway.common.model.UnifiedRequest;
import tech.qunxu.module.gateway.common.model.UnifiedStreamChunk;
import tech.qunxu.module.gateway.config.GatewayProperties;
import tech.qunxu.module.gateway.health.PassiveHealthMonitor;
import tech.qunxu.module.gateway.persistence.entity.LogicalModelEntity;
import tech.qunxu.module.gateway.persistence.entity.ModelChannelEntity;
import tech.qunxu.module.gateway.pipeline.FilterOrder;
import tech.qunxu.module.gateway.pipeline.PipelineChain;
import tech.qunxu.module.gateway.pipeline.PipelineFilter;
import tech.qunxu.module.gateway.pipeline.RequestContext;
import tech.qunxu.module.gateway.router.ChannelCandidate;
import tech.qunxu.module.gateway.router.RoutingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 调用过滤器：把 Unified 请求经适配器发往选中的下游通道，产出 Unified 响应/增量流写回 {@link RequestContext}，
 * 并在调用结束时把结果回写被动健康监控。
 *
 * <p>流式：将适配器返回的<b>冷流</b>（附加首字计时、usage 捕获、健康回写与「首字前故障转移」）存入
 * {@code ctx.streamResult}，交由流水线终点（控制器渲染）订阅，边到边转发。非流式：订阅 {@code invokeUnary}
 * 并在响应到达后填充 {@code ctx.response}/{@code ctx.usage} 再进入终点。</p>
 *
 * <p>故障转移：下游类错误（凭证过期/供应商错误/超时/限流）触发时，剔除当前通道（加入排除集）并经
 * {@link RoutingService} 在剩余健康通道内重选，最多尝试 {@code gateway.downstream.max-retries + 1} 次；
 * 流式仅在<b>首字到达前</b>可转移，首字后已向前端下发帧则不再转移。客户端/配置类错误不转移。</p>
 */
@Component
public class InvokeFilter implements PipelineFilter {

    private static final Logger log = LoggerFactory.getLogger(InvokeFilter.class);

    private final ProviderRegistry registry;
    private final ChannelRuntimeFactory runtimeFactory;
    private final RoutingService routingService;
    private final PassiveHealthMonitor healthMonitor;
    private final GatewayProperties properties;

    public InvokeFilter(ProviderRegistry registry,
                        ChannelRuntimeFactory runtimeFactory,
                        RoutingService routingService,
                        PassiveHealthMonitor healthMonitor,
                        GatewayProperties properties) {
        this.registry = registry;
        this.runtimeFactory = runtimeFactory;
        this.routingService = routingService;
        this.healthMonitor = healthMonitor;
        this.properties = properties;
    }

    @Override
    public int order() {
        return FilterOrder.INVOKE;
    }

    @Override
    public Mono<Void> filter(RequestContext ctx, PipelineChain chain) {
        return Mono.defer(() -> {
            if (ctx.getSelectedChannel() == null) {
                return Mono.error(new GatewayException(ErrorCode.ALL_CHANNELS_DOWN, "未选中通道，无法调用"));
            }
            return invokeAttempt(ctx, chain, 0);
        });
    }

    private int maxAttempts() {
        return Math.max(1, properties.getDownstream().getMaxRetries() + 1);
    }

    /** 对当前 {@code ctx.selectedChannel} 发起一次调用（含健康回写与失败转移）。 */
    private Mono<Void> invokeAttempt(RequestContext ctx, PipelineChain chain, int attempt) {
        ModelChannelEntity channel = ctx.getSelectedChannel();
        if (channel == null) {
            return Mono.error(new GatewayException(ErrorCode.ALL_CHANNELS_DOWN, "无可用通道"));
        }
        ChannelRuntime runtime = runtimeFactory.create(channel, ctx.getSelectedProvider());
        ModelAdapter adapter = registry.adapterFor(runtime.getProtocol());
        if (!adapter.supports(ctx.getCapability())) {
            return Mono.error(new GatewayException(ErrorCode.NOT_IMPLEMENTED,
                    "通道协议 " + runtime.getProtocol() + " 不支持能力 " + ctx.getCapability()));
        }
        UnifiedRequest request = ctx.getRequest();
        if (request != null && request.stream()) {
            return streamAttempt(ctx, chain, adapter, runtime, attempt);
        }
        return unaryAttempt(ctx, chain, adapter, runtime, attempt);
    }

    private Mono<Void> unaryAttempt(RequestContext ctx, PipelineChain chain, ModelAdapter adapter,
                                    ChannelRuntime runtime, int attempt) {
        Long channelId = runtime.getChannelId();
        Long providerId = runtime.getProviderId();
        long start = System.nanoTime();
        if (log.isDebugEnabled()) {
            log.debug("非流式调用 通道={} 上游模型={} 尝试={}", channelId, runtime.getUpstreamModel(), attempt);
        }
        return adapter.invokeUnary(ctx.getRequest(), runtime)
                .flatMap(response -> {
                    ctx.setResponse(response);
                    if (response.usage() != null) {
                        ctx.setUsage(response.usage());
                    }
                    ctx.markFirstToken();
                    healthMonitor.recordSuccessAsync(channelId, providerId, elapsedMs(start));
                    return chain.execute(ctx);
                })
                .onErrorResume(err -> {
                    healthMonitor.recordFailureAsync(channelId, providerId, err);
                    return failover(ctx, chain, err, attempt);
                });
    }

    private Mono<Void> streamAttempt(RequestContext ctx, PipelineChain chain, ModelAdapter adapter,
                                     ChannelRuntime runtime, int attempt) {
        Flux<UnifiedStreamChunk> stream = buildStream(ctx, adapter, runtime, attempt, new AtomicBoolean(false));
        ctx.setStreamResult(stream);
        return chain.execute(ctx);
    }

    /** 构建带「首字前故障转移」的冷流；每次转移会更新 {@code ctx.selectedChannel} 供审计反映真实通道。 */
    private Flux<UnifiedStreamChunk> buildStream(RequestContext ctx, ModelAdapter adapter, ChannelRuntime runtime,
                                                 int attempt, AtomicBoolean firstToken) {
        Long channelId = runtime.getChannelId();
        Long providerId = runtime.getProviderId();
        long start = System.nanoTime();
        if (log.isDebugEnabled()) {
            log.debug("流式调用 通道={} 上游模型={} 尝试={}", channelId, runtime.getUpstreamModel(), attempt);
        }
        return adapter.invokeStream(ctx.getRequest(), runtime)
                .doOnNext(chunk -> {
                    if (chunk.role() != null || chunk.hasContent() || chunk.deltaReasoning() != null) {
                        firstToken.set(true);
                        ctx.markFirstToken();
                    }
                    if (chunk.hasContent()) {
                        ctx.appendResponseText(chunk.deltaContent());
                    }
                    if (chunk.usage() != null) {
                        ctx.setUsage(chunk.usage());
                    }
                })
                .doOnComplete(() -> healthMonitor.recordSuccessAsync(channelId, providerId, elapsedMs(start)))
                .onErrorResume(err -> {
                    healthMonitor.recordFailureAsync(channelId, providerId, err);
                    if (firstToken.get() || !healthMonitor.isFailoverEligible(err) || attempt + 1 >= maxAttempts()) {
                        return Flux.error(err);
                    }
                    return nextStream(ctx, err, attempt, firstToken);
                });
    }

    private Flux<UnifiedStreamChunk> nextStream(RequestContext ctx, Throwable err, int attempt, AtomicBoolean firstToken) {
        LogicalModelEntity model = ctx.getLogicalModel();
        if (model == null) {
            return Flux.error(err);
        }
        excludeChannel(ctx, ctx.getSelectedChannel() != null ? ctx.getSelectedChannel().getId() : null);
        return routingService.route(model, ctx)
                .flatMapMany(next -> {
                    applySelection(ctx, next);
                    ChannelRuntime runtime = runtimeFactory.create(next.channel(), next.provider());
                    ModelAdapter adapter = registry.adapterFor(runtime.getProtocol());
                    if (!adapter.supports(ctx.getCapability())) {
                        return Flux.error(err);
                    }
                    if (log.isInfoEnabled()) {
                        log.info("流式首字前故障转移到通道 {}（原因 {}）", next.channelId(), codeOf(err));
                    }
                    return buildStream(ctx, adapter, runtime, attempt + 1, firstToken);
                })
                .onErrorResume(routeErr -> Flux.error(err));
    }

    /** 非流式故障转移：排除当前通道→重选→递归调用；无更多通道则传播原始下游错误。 */
    private Mono<Void> failover(RequestContext ctx, PipelineChain chain, Throwable err, int attempt) {
        if (!healthMonitor.isFailoverEligible(err) || attempt + 1 >= maxAttempts()) {
            return Mono.error(err);
        }
        LogicalModelEntity model = ctx.getLogicalModel();
        if (model == null) {
            return Mono.error(err);
        }
        Long failedId = ctx.getSelectedChannel() != null ? ctx.getSelectedChannel().getId() : null;
        excludeChannel(ctx, failedId);
        return routingService.route(model, ctx)
                .flatMap(next -> {
                    applySelection(ctx, next);
                    if (log.isInfoEnabled()) {
                        log.info("非流式故障转移到通道 {}（原通道 {} 原因 {}）", next.channelId(), failedId, codeOf(err));
                    }
                    return invokeAttempt(ctx, chain, attempt + 1);
                })
                .onErrorResume(routeErr -> {
                    if (isNoChannel(routeErr)) {
                        return Mono.error(err);   // 无更多通道：传播更有信息量的原始下游错误
                    }
                    return Mono.error(routeErr);
                });
    }

    private void applySelection(RequestContext ctx, ChannelCandidate next) {
        ctx.setSelectedChannel(next.channel());
        ctx.setSelectedProvider(next.provider());
    }

    @SuppressWarnings("unchecked")
    private void excludeChannel(RequestContext ctx, Long channelId) {
        if (channelId == null) {
            return;
        }
        Set<Long> excluded = ctx.attribute(RoutingService.ATTR_EXCLUDED_CHANNELS);
        if (excluded == null) {
            excluded = ConcurrentHashMap.newKeySet();
            ctx.setAttribute(RoutingService.ATTR_EXCLUDED_CHANNELS, excluded);
        }
        excluded.add(channelId);
    }

    private static boolean isNoChannel(Throwable err) {
        return err instanceof GatewayException ge && ge.getErrorCode() == ErrorCode.ALL_CHANNELS_DOWN;
    }

    private static ErrorCode codeOf(Throwable err) {
        return PassiveHealthMonitor.errorCodeOf(err);
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
