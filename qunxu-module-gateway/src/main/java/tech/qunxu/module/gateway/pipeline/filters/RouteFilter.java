package tech.qunxu.module.gateway.pipeline.filters;

import tech.qunxu.module.gateway.common.exception.ErrorCode;
import tech.qunxu.module.gateway.common.exception.GatewayException;
import tech.qunxu.module.gateway.config.GatewayProperties;
import tech.qunxu.module.gateway.persistence.entity.LogicalModelEntity;
import tech.qunxu.module.gateway.pipeline.FilterOrder;
import tech.qunxu.module.gateway.pipeline.PipelineChain;
import tech.qunxu.module.gateway.pipeline.PipelineFilter;
import tech.qunxu.module.gateway.pipeline.RequestContext;
import tech.qunxu.module.gateway.router.ChannelCandidate;
import tech.qunxu.module.gateway.router.RoutingService;
import tech.qunxu.module.gateway.router.StickyRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Optional;

/**
 * 路由过滤器：为已解析的逻辑模型选择一个可用通道（及其供应商），写入 {@link RequestContext}。
 *
 * <p>无会话标识或粘性关闭时，委托 {@link RoutingService} 完成「健康过滤 + 负载均衡」择一；候选耗尽返回 503。
 * 携带会话标识且 {@code gateway.sticky.enabled=true} 时进入<b>会话级粘性</b>：查 Redis 绑定，
 * 命中则复用同一通道（TTL 续期），未命中则按负载均衡首轮选择并写入绑定，绑定通道失效（不健康/被剔除/
 * 故障转移排除）时自动重绑。绑定/重绑事件以 {@value #ATTR_STICKY_EVENT} 属性与 INFO 日志记录
 * （traceId 可关联审计）；Redis 不可用按 {@code gateway.sticky.fail-open} 降级普通路由。</p>
 */
@Component
public class RouteFilter implements PipelineFilter {

    private static final Logger log = LoggerFactory.getLogger(RouteFilter.class);

    /** 会话粘性事件标记键（如 {@code sticky-hit:3} / {@code sticky-bind:3} / {@code sticky-rebind:1->3}）。 */
    public static final String ATTR_STICKY_EVENT = "gw.sticky.event";

    private final RoutingService routingService;
    private final StickyRouter stickyRouter;
    private final GatewayProperties properties;

    public RouteFilter(RoutingService routingService, StickyRouter stickyRouter, GatewayProperties properties) {
        this.routingService = routingService;
        this.stickyRouter = stickyRouter;
        this.properties = properties;
    }

    @Override
    public int order() {
        return FilterOrder.ROUTE;
    }

    @Override
    public Mono<Void> filter(RequestContext ctx, PipelineChain chain) {
        LogicalModelEntity model = ctx.getLogicalModel();
        if (model == null || model.getId() == null) {
            return Mono.error(new GatewayException(ErrorCode.MODEL_NOT_FOUND, "模型未解析，无法路由"));
        }
        String sessionKey = stickySessionKey(ctx, model.getId());
        if (sessionKey == null) {
            return routingService.route(model, ctx)
                    .flatMap(chosen -> {
                        applySelection(ctx, chosen);
                        return chain.execute(ctx);
                    });
        }
        // 有会话键：查绑定 → 命中复用；无绑定则首轮选择后写入；绑定失效则重绑。
        // 用 Optional 显式区分「无绑定」与「命中/重绑后正常完成（可能为空完成）」，避免 switchIfEmpty 误触发
        return stickyRouter.lookup(model.getId(), sessionKey)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(bound -> bound.isPresent()
                        ? routeSticky(ctx, chain, model, sessionKey, bound.get())
                        : routeAndBind(ctx, chain, model, sessionKey));
    }

    /** 已有绑定：优先复用，命中续期；失效（不健康/被排除）则回退负载均衡并重绑。 */
    private Mono<Void> routeSticky(RequestContext ctx, PipelineChain chain, LogicalModelEntity model,
                                   String sessionKey, Long bound) {
        return routingService.route(model, ctx, bound)
                .flatMap(chosen -> {
                    applySelection(ctx, chosen);
                    if (bound.equals(chosen.channelId())) {
                        markEvent(ctx, "sticky-hit:" + bound);
                        // 命中即续期 TTL，随后继续流水线
                        return stickyRouter.bind(model.getId(), sessionKey, bound)
                                .then(chain.execute(ctx));
                    }
                    // 绑定通道已不可路由 → 自动重绑到新选择
                    markEvent(ctx, "sticky-rebind:" + bound + "->" + chosen.channelId());
                    log.info("trace={} 会话粘性失效重绑 {} -> {} (model={}, session={})",
                            ctx.getTraceId(), bound, chosen.channelId(), model.getName(), sessionKey);
                    return stickyRouter.bind(model.getId(), sessionKey, chosen.channelId())
                            .then(chain.execute(ctx));
                });
    }

    /** 无绑定：首轮按负载均衡选择并写入绑定（TTL）。 */
    private Mono<Void> routeAndBind(RequestContext ctx, PipelineChain chain, LogicalModelEntity model,
                                    String sessionKey) {
        return routingService.route(model, ctx)
                .flatMap(chosen -> {
                    applySelection(ctx, chosen);
                    markEvent(ctx, "sticky-bind:" + chosen.channelId());
                    log.info("trace={} 会话粘性绑定 -> {} (model={}, session={})",
                            ctx.getTraceId(), chosen.channelId(), model.getName(), sessionKey);
                    return stickyRouter.bind(model.getId(), sessionKey, chosen.channelId())
                            .then(chain.execute(ctx));
                });
    }

    /** 会话键：未启用粘性或缺失会话 ID 返回 {@code null}（走普通路由）。 */
    private String stickySessionKey(RequestContext ctx, Long modelId) {
        if (!properties.getSticky().isEnabled() || modelId == null) {
            return null;
        }
        String sessionId = ctx.getSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        return StickyRouter.sessionKey(ctx.getCaller().auditId(), sessionId);
    }

    private void markEvent(RequestContext ctx, String event) {
        ctx.setAttribute(ATTR_STICKY_EVENT, event);
    }

    private void applySelection(RequestContext ctx, ChannelCandidate chosen) {
        ctx.setSelectedChannel(chosen.channel());
        ctx.setSelectedProvider(chosen.provider());
        if (log.isDebugEnabled()) {
            log.debug("模型 {} 选中通道 {} (供应商 {})", ctx.getLogicalModelName(),
                    chosen.channelId(),
                    chosen.provider() != null ? chosen.provider().getCode() : null);
        }
    }
}
