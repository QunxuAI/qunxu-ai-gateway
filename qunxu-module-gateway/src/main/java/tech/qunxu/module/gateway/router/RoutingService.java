package tech.qunxu.module.gateway.router;

import tech.qunxu.module.gateway.common.exception.ErrorCode;
import tech.qunxu.module.gateway.common.exception.GatewayException;
import tech.qunxu.module.gateway.health.ChannelHealth;
import tech.qunxu.module.gateway.health.HealthStore;
import tech.qunxu.module.gateway.persistence.entity.LogicalModelEntity;
import tech.qunxu.module.gateway.persistence.entity.ModelChannelEntity;
import tech.qunxu.module.gateway.persistence.entity.ProviderEntity;
import tech.qunxu.module.gateway.persistence.service.ConfigService;
import tech.qunxu.module.gateway.pipeline.RequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Set;

/**
 * 路由服务：把「逻辑模型」解析为一个具体可用通道。
 *
 * <p>流程：取启用通道 → 过滤供应商启用 → 批量读取 Redis 健康 → 过滤不健康（熔断/剔除中）→
 * 交由 {@link LoadBalancer} 按策略择一。健康读取失败开放（无数据视为可路由）。任一环节候选耗尽
 * 抛 {@link ErrorCode#ALL_CHANNELS_DOWN}（503）。故障转移（调用失败后剔除当前通道并在剩余健康通道内重试）
 * 在 M4 结合健康自愈接入。</p>
 */
@Service
public class RoutingService {

    private static final Logger log = LoggerFactory.getLogger(RoutingService.class);

    /** 请求级负载均衡策略覆盖键（写入 {@link RequestContext} 属性）；缺省用配置默认策略。 */
    public static final String ATTR_LB_STRATEGY = "gw.lb.strategy";

    /** 故障转移排除集键（{@code Set<Long>} 通道 ID）：已尝试失败的通道不再被选中。 */
    public static final String ATTR_EXCLUDED_CHANNELS = "gw.route.excluded";

    private final ConfigService configService;
    private final HealthStore healthStore;
    private final LoadBalancerResolver resolver;

    public RoutingService(ConfigService configService, HealthStore healthStore, LoadBalancerResolver resolver) {
        this.configService = configService;
        this.healthStore = healthStore;
        this.resolver = resolver;
    }

    /**
     * 为逻辑模型选择一个健康通道。
     *
     * @param model 已解析的逻辑模型（不可为空）
     * @param ctx   请求上下文（可携带 {@link #ATTR_LB_STRATEGY} 覆盖策略）
     * @return 选中的候选（含通道、供应商、健康快照）
     */
    public Mono<ChannelCandidate> route(LogicalModelEntity model, RequestContext ctx) {
        return route(model, ctx, null);
    }

    /**
     * 为逻辑模型选择一个健康通道，优先复用 {@code preferredChannelId}（会话级粘性）。
     *
     * <p>若偏好通道在健康候选集内则直接命中（不做负载均衡）；若偏好通道不存在/不健康/已被排除
     * （熔断、剔除、故障转移中）则回退为正常负载均衡选择——调用方据此判断「命中」还是「失效重绑」。</p>
     *
     * @param model              已解析的逻辑模型（不可为空）
     * @param ctx                请求上下文
     * @param preferredChannelId 会话绑定的偏好通道 ID；为 {@code null} 时等价于 {@link #route(LogicalModelEntity, RequestContext)}
     * @return 选中的候选
     */
    public Mono<ChannelCandidate> route(LogicalModelEntity model, RequestContext ctx, Long preferredChannelId) {
        if (model == null || model.getId() == null) {
            return Mono.error(new GatewayException(ErrorCode.MODEL_NOT_FOUND, "模型未解析，无法路由"));
        }
        LoadBalancer balancer = resolver.resolve(strategyName(ctx));
        return configService.findEnabledChannels(model.getId())
                .flatMap(channels -> {
                    if (channels == null || channels.isEmpty()) {
                        return Mono.error(new GatewayException(ErrorCode.ALL_CHANNELS_DOWN,
                                "该模型暂无启用通道: " + model.getName()));
                    }
                    return withProviders(channels)
                            .flatMap(pairs -> attachHealth(pairs))
                            .flatMap(candidates -> choose(model, candidates, balancer, ctx, preferredChannelId));
                });
    }

    /** 逐个通道加载其供应商，仅保留供应商启用者（健康占位为 unknown，随后批量替换）。 */
    private Mono<List<ChannelCandidate>> withProviders(List<ModelChannelEntity> channels) {
        return Flux.fromIterable(channels)
                .concatMap(channel -> configService.findProviderById(channel.getProviderId())
                        .filter(RoutingService::isProviderEnabled)
                        .map(provider -> new ChannelCandidate(channel, provider, ChannelHealth.unknown(channel.getId()))))
                .collectList();
    }

    /** 批量读取健康并替换占位快照。 */
    private Mono<List<ChannelCandidate>> attachHealth(List<ChannelCandidate> pairs) {
        if (pairs.isEmpty()) {
            return Mono.error(new GatewayException(ErrorCode.ALL_CHANNELS_DOWN, "该模型的通道对应供应商均不可用"));
        }
        List<Long> ids = pairs.stream().map(ChannelCandidate::channelId).toList();
        return healthStore.getAll(ids).map(healthMap -> {
            if (healthMap == null || healthMap.isEmpty()) {
                return pairs;
            }
            return pairs.stream()
                    .map(p -> {
                        ChannelHealth h = healthMap.get(p.channelId());
                        return h != null ? new ChannelCandidate(p.channel(), p.provider(), h) : p;
                    })
                    .toList();
        });
    }

    /** 过滤健康候选（剔除不健康与已排除者）并调用负载均衡选择；偏好通道健康时优先命中。 */
    private Mono<ChannelCandidate> choose(LogicalModelEntity model, List<ChannelCandidate> candidates,
                                          LoadBalancer balancer, RequestContext ctx, Long preferredChannelId) {
        Set<Long> excluded = excludedIds(ctx);
        List<ChannelCandidate> healthy = candidates.stream()
                .filter(c -> c.health() != null && c.health().isRoutable())
                .filter(c -> !excluded.contains(c.channelId()))
                .toList();
        if (healthy.isEmpty()) {
            return Mono.error(new GatewayException(ErrorCode.ALL_CHANNELS_DOWN,
                    "该模型的通道均不健康或已排除（熔断/剔除冷却/故障转移耗尽）: " + model.getName()));
        }
        if (preferredChannelId != null) {
            ChannelCandidate hit = healthy.stream()
                    .filter(c -> preferredChannelId.equals(c.channelId()))
                    .findFirst()
                    .orElse(null);
            if (hit != null) {
                if (log.isDebugEnabled()) {
                    log.debug("模型 {} 会话粘性命中通道 {} (供应商 {})",
                            model.getName(), hit.channelId(),
                            hit.provider() != null ? hit.provider().getCode() : null);
                }
                return Mono.just(hit);
            }
        }
        ChannelCandidate chosen = balancer.select(model, healthy, ctx);
        if (chosen == null) {
            return Mono.error(new GatewayException(ErrorCode.ALL_CHANNELS_DOWN, "负载均衡未能选择通道"));
        }
        if (log.isDebugEnabled()) {
            log.debug("模型 {} 策略 {} 选中通道 {} (供应商 {}, 健康={}, 时延={}ms)",
                    model.getName(), balancer.strategy(), chosen.channelId(),
                    chosen.provider() != null ? chosen.provider().getCode() : null,
                    chosen.health() != null ? chosen.health().status() : null,
                    chosen.hasLatency() ? String.format("%.1f", chosen.latencyMs()) : "n/a");
        }
        return Mono.just(chosen);
    }

    private String strategyName(RequestContext ctx) {
        if (ctx == null) {
            return null;
        }
        Object override = ctx.attribute(ATTR_LB_STRATEGY);
        return override != null ? override.toString() : null;
    }

    @SuppressWarnings("unchecked")
    private Set<Long> excludedIds(RequestContext ctx) {
        if (ctx == null) {
            return Set.of();
        }
        Object value = ctx.attribute(ATTR_EXCLUDED_CHANNELS);
        return value instanceof Set<?> set ? (Set<Long>) set : Set.of();
    }

    private static boolean isProviderEnabled(ProviderEntity provider) {
        return provider != null && (provider.getStatus() == null || provider.getStatus() == 1);
    }
}
