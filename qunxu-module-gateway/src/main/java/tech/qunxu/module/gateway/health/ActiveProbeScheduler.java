package tech.qunxu.module.gateway.health;

import tech.qunxu.module.gateway.adapter.ChannelRuntime;
import tech.qunxu.module.gateway.adapter.ChannelRuntimeFactory;
import tech.qunxu.module.gateway.adapter.ModelAdapter;
import tech.qunxu.module.gateway.adapter.ProviderRegistry;
import tech.qunxu.module.gateway.common.enums.Capability;
import tech.qunxu.module.gateway.common.enums.HealthStatus;
import tech.qunxu.module.gateway.common.exception.ErrorCode;
import tech.qunxu.module.gateway.config.GatewayProperties;
import tech.qunxu.module.gateway.persistence.entity.ModelChannelEntity;
import tech.qunxu.module.gateway.persistence.entity.ProviderEntity;
import tech.qunxu.module.gateway.persistence.service.ConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 主动健康探测调度：定时遍历启用通道，对<b>非健康</b>（UNKNOWN / DOWN / EJECTED 冷却已过）者发起探测，
 * 探测成功交由 {@link PassiveHealthMonitor#recordSuccess} 触发半开恢复，失败交由
 * {@link PassiveHealthMonitor#recordProbeFailure} 计数并按需剔除（记 PROBE_FAIL/AUTH_EXPIRED 事件）。
 *
 * <p>UP 通道不主动探测——它们由真实流量的被动监控持续维护，避免探测对下游造成额外压力。</p>
 *
 * <p>多实例部署用 Redis 分布式锁（{@code SETNX + TTL}，不显式释放靠 TTL 轮换）保证同一时刻仅一个实例探测；
 * 单机/测试可置 {@code gateway.health.distributed-lock=false} 跳过锁。本地 {@link AtomicBoolean} 守卫防止
 * 上一轮未结束时重入（{@code @Scheduled} 方法异步订阅后立即返回）。</p>
 */
@Component
public class ActiveProbeScheduler {

    private static final Logger log = LoggerFactory.getLogger(ActiveProbeScheduler.class);

    /** 主动探测分布式锁键。 */
    public static final String LOCK_KEY = "gw:health:probe:lock";

    private final ConfigService configService;
    private final HealthStore healthStore;
    private final ProviderRegistry registry;
    private final ChannelRuntimeFactory runtimeFactory;
    private final PassiveHealthMonitor monitor;
    private final GatewayProperties properties;
    private final ObjectProvider<ReactiveStringRedisTemplate> redisProvider;

    private final AtomicBoolean running = new AtomicBoolean(false);

    public ActiveProbeScheduler(ConfigService configService,
                                HealthStore healthStore,
                                ProviderRegistry registry,
                                ChannelRuntimeFactory runtimeFactory,
                                PassiveHealthMonitor monitor,
                                GatewayProperties properties,
                                ObjectProvider<ReactiveStringRedisTemplate> redisProvider) {
        this.configService = configService;
        this.healthStore = healthStore;
        this.registry = registry;
        this.runtimeFactory = runtimeFactory;
        this.monitor = monitor;
        this.properties = properties;
        this.redisProvider = redisProvider;
    }

    /** 定时探测入口。异步订阅后立即返回，由 {@link #running} 守卫防重入。 */
    @Scheduled(fixedDelayString = "${gateway.health.probe-interval-ms:30000}",
            initialDelayString = "${gateway.health.probe-interval-ms:30000}")
    public void scheduledProbe() {
        if (!properties.getHealth().isEnabled()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.debug("上一轮主动探测尚未结束，跳过本轮");
            return;
        }
        probeRound()
                .doFinally(sig -> running.set(false))
                .subscribe(
                        probed -> {
                            if (probed > 0 && log.isInfoEnabled()) {
                                log.info("主动探测完成，本轮探测 {} 个通道", probed);
                            }
                        },
                        e -> log.warn("主动探测轮次异常: {}", e.toString()));
    }

    /**
     * 执行一轮探测（可被 Admin 手动触发或测试直接调用）。
     *
     * @return 本轮实际发起探测的通道数
     */
    public Mono<Integer> probeRound() {
        return acquireLock()
                .flatMap(acquired -> acquired ? probeAll() : Mono.just(0));
    }

    /**
     * 手动探测指定通道（Admin 触发）：<b>不</b>判断当前健康状态，强制执行一次探测并回写健康。
     *
     * <p>不参与分布式锁——手动探测是运维的明确意图且仅作用于单通道，与定时轮次并行也无副作用
     * （健康回写均为幂等的快照覆盖）。停用的通道仍可探测，便于上线前先验证凭证。</p>
     *
     * @param channelId 通道 ID
     * @return 探测结果；通道不存在时返回 {@link Mono#empty()}，供应商缺失或适配器不支持探测时返回
     *         {@link HealthResult#unknown()}
     */
    public Mono<HealthResult> probeChannel(Long channelId) {
        if (channelId == null) {
            return Mono.empty();
        }
        return configService.findChannelById(channelId)
                .flatMap(channel -> probeAndApply(channel).defaultIfEmpty(HealthResult.unknown()));
    }

    /** 获取分布式锁；禁用锁或无 Redis 时直接放行。 */
    private Mono<Boolean> acquireLock() {
        if (!properties.getHealth().isDistributedLock()) {
            return Mono.just(true);
        }
        ReactiveStringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return Mono.just(true);
        }
        Duration ttl = Duration.ofMillis(Math.max(1000L, properties.getHealth().getProbeIntervalMs()));
        String token = UUID.randomUUID().toString();
        return redis.opsForValue().setIfAbsent(LOCK_KEY, token, ttl)
                .defaultIfEmpty(false)
                .onErrorResume(e -> {
                    log.warn("获取主动探测分布式锁失败，本轮跳过: {}", e.toString());
                    return Mono.just(false);
                });
    }

    /** 串行探测所有需探测通道，避免同时冲击下游。 */
    private Mono<Integer> probeAll() {
        return configService.findAllEnabledChannels()
                .flatMapMany(Flux::fromIterable)
                .concatMap(this::probeChannelIfUnhealthy)
                .reduce(0, (acc, probed) -> acc + (probed ? 1 : 0));
    }

    /** 仅当通道非健康时探测。返回是否实际发起了探测。 */
    private Mono<Boolean> probeChannelIfUnhealthy(ModelChannelEntity channel) {
        Long channelId = channel != null ? channel.getId() : null;
        if (channelId == null) {
            return Mono.just(false);
        }
        return healthStore.get(channelId)
                .filter(this::shouldProbe)
                .flatMap(health -> probeOne(channel))
                .defaultIfEmpty(false)
                .onErrorResume(e -> {
                    log.debug("探测通道 {} 出错: {}", channelId, e.toString());
                    return Mono.just(false);
                });
    }

    /** 构建运行时、探测、归一化结果并回写健康；返回是否实际完成了探测。 */
    private Mono<Boolean> probeOne(ModelChannelEntity channel) {
        return probeAndApply(channel)
                .map(ActiveProbeScheduler::isRealProbe)
                .defaultIfEmpty(false);
    }

    /**
     * 探测一个通道并把结果回写健康存储。
     *
     * @return 探测结果；供应商不存在/禁用或适配器不可用时分别为 {@link Mono#empty()} / {@link HealthResult#unknown()}
     */
    private Mono<HealthResult> probeAndApply(ModelChannelEntity channel) {
        Long channelId = channel.getId();
        Long providerId = channel.getProviderId();
        return configService.findLogicalModelById(channel.getLogicalModelId())
                .map(model -> Capability.of(model != null ? model.getCapability() : null))
                .map(capability -> capability != null ? capability : Capability.CHAT)
                .defaultIfEmpty(Capability.CHAT)
                .flatMap(capability -> configService.findProviderById(providerId)
                        .flatMap(provider -> probeWith(provider, channel, capability)));
    }

    private Mono<HealthResult> probeWith(ProviderEntity provider, ModelChannelEntity channel, Capability capability) {
        Long channelId = channel.getId();
        ChannelRuntime runtime;
        ModelAdapter adapter;
        try {
            runtime = runtimeFactory.create(channel, provider);
            adapter = registry.adapterFor(runtime.getProtocol());
        } catch (RuntimeException e) {
            log.debug("通道 {} 运行时/适配器不可用，跳过探测: {}", channelId, e.toString());
            return Mono.just(HealthResult.unknown());
        }
        long timeoutMs = Math.max(1000L, properties.getHealth().getProbeTimeoutMs());
        return adapter.probe(runtime, capability)
                .timeout(Duration.ofMillis(timeoutMs))
                .defaultIfEmpty(HealthResult.unknown())
                .onErrorResume(e -> Mono.just(toProbeFailure(e)))
                .flatMap(result -> applyProbeResult(channelId, provider.getId(), result)
                        .thenReturn(result));
    }

    /** 探测结果回写：成功→recordSuccess（含半开恢复）；失败→recordProbeFailure；未知（未实现探测）→忽略。 */
    private Mono<Void> applyProbeResult(Long channelId, Long providerId, HealthResult result) {
        if (!isRealProbe(result)) {
            return Mono.empty();
        }
        if (result.isUp()) {
            if (log.isDebugEnabled()) {
                log.debug("探测通道 {} 成功 latency={}ms", channelId, result.latencyMs());
            }
            return monitor.recordSuccess(channelId, providerId, result.latencyMs());
        }
        if (log.isDebugEnabled()) {
            log.debug("探测通道 {} 失败: {} ({})", channelId, result.message(), result.errorCode());
        }
        return monitor.recordProbeFailure(channelId, providerId, result);
    }

    private static boolean isRealProbe(HealthResult result) {
        return result != null && result.status() != null && result.status() != HealthStatus.UNKNOWN;
    }

    /** 把探测异常（超时/其他）归一化为 HealthResult.down。 */
    private HealthResult toProbeFailure(Throwable e) {
        if (e instanceof TimeoutException || e.getClass().getSimpleName().contains("Timeout")) {
            return HealthResult.down("主动探测超时", ErrorCode.PROVIDER_TIMEOUT);
        }
        ErrorCode code = PassiveHealthMonitor.errorCodeOf(e);
        return HealthResult.down("主动探测异常: " + e.getClass().getSimpleName(),
                code != null ? code : ErrorCode.PROVIDER_ERROR);
    }

    /**
     * 是否应主动探测该通道。UP 跳过（被动监控覆盖）；UNKNOWN/DOWN 探测；
     * EJECTED 仅在冷却已过（半开）时探测以尝试恢复。
     */
    private boolean shouldProbe(ChannelHealth health) {
        if (health == null || health.status() == null) {
            return true;
        }
        return switch (health.status()) {
            case UP -> false;
            case UNKNOWN, DOWN -> true;
            case EJECTED -> health.ejectedUntil() == null || System.currentTimeMillis() >= health.ejectedUntil();
        };
    }
}
