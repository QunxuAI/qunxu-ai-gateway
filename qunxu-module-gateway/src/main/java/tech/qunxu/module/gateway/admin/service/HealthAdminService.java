package tech.qunxu.module.gateway.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 通道健康管理服务（{@code /admin/health}）：健康视图、手动探测/剔除/恢复、健康事件检索。
 *
 * <p>健康视图合并两类数据源——MySQL 中的通道/模型/供应商<b>静态配置</b>与 Redis 中的<b>运行时健康</b>。
 * 静态部分经 {@link Schedulers#boundedElastic()} 卸载；健康读取失败时按
 * {@link HealthStatus#UNKNOWN} 兜底呈现，与路由层「失败开放」一致，不让 Redis 抖动伪装成通道故障。</p>
 *
 * <p>手动操作会写入 {@code health_event}（类型 RECOVER/EJECT），使人工干预与自动自愈在同一时间线上可追溯；
 * 手动探测复用 {@link ActiveProbeScheduler#probeChannel(Long)}，<b>不</b>受分布式锁与「仅探测非健康通道」
 * 的限制，便于上线前验证新配置的凭证与连通性。</p>
 */
@Service
public class HealthAdminService {

    private static final Logger log = LoggerFactory.getLogger(HealthAdminService.class);
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int DEFAULT_MANUAL_EJECT_SECONDS = 300;
    private static final int MAX_MANUAL_EJECT_SECONDS = 86400;

    private final ModelChannelMapper channelMapper;
    private final LogicalModelMapper modelMapper;
    private final ProviderMapper providerMapper;
    private final HealthEventMapper eventMapper;
    private final HealthStore healthStore;
    private final HealthEventLog eventLog;
    private final ActiveProbeScheduler probeScheduler;
    private final GatewayProperties properties;

    public HealthAdminService(ModelChannelMapper channelMapper,
                              LogicalModelMapper modelMapper,
                              ProviderMapper providerMapper,
                              HealthEventMapper eventMapper,
                              HealthStore healthStore,
                              HealthEventLog eventLog,
                              ActiveProbeScheduler probeScheduler,
                              GatewayProperties properties) {
        this.channelMapper = channelMapper;
        this.modelMapper = modelMapper;
        this.providerMapper = providerMapper;
        this.eventMapper = eventMapper;
        this.healthStore = healthStore;
        this.eventLog = eventLog;
        this.probeScheduler = probeScheduler;
        this.properties = properties;
    }

    // ------------------------------------------------------------------ 健康视图

    /**
     * 查询通道健康视图列表。
     *
     * @param logicalModelId 按逻辑模型过滤（可空）
     * @param providerId     按供应商过滤（可空）
     * @param onlyUnhealthy  为 {@code true} 时仅返回当前不可路由的通道
     */
    public Mono<List<ChannelHealthView>> channels(Long logicalModelId, Long providerId, Boolean onlyUnhealthy) {
        return Mono.fromCallable(() -> loadPrepared(logicalModelId, providerId))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(prepared -> withHealth(prepared))
                .map(views -> Boolean.TRUE.equals(onlyUnhealthy)
                        ? views.stream().filter(v -> !v.routable()).toList()
                        : views);
    }

    /**
     * 分页查询通道健康视图（先过滤后分页，顺序与 {@link #channels} 一致）。
     *
     * @param page 页码（从 1 起，缺省 1）
     * @param size 每页条数（缺省 10，上限 100）
     */
    public Mono<AdminPage<ChannelHealthView>> channelsPage(Long logicalModelId, Long providerId,
                                                           Boolean onlyUnhealthy, Integer page, Integer size) {
        int p = page == null || page < 1 ? 1 : page;
        int s = size == null || size < 1 ? 10 : Math.min(size, 100);
        return channels(logicalModelId, providerId, onlyUnhealthy)
                .map(all -> {
                    int from = Math.min((p - 1) * s, all.size());
                    int to = Math.min(from + s, all.size());
                    return new AdminPage<>(new ArrayList<>(all.subList(from, to)), all.size(), p, s);
                });
    }

    /** 查询单个通道的健康视图；通道不存在抛 404。 */
    public Mono<ChannelHealthView> channel(Long channelId) {
        return Mono.fromCallable(() -> loadPreparedById(channelId))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(prepared -> withHealth(List.of(prepared)))
                .flatMap(views -> views.isEmpty()
                        ? Mono.error(new GatewayException(ErrorCode.NOT_FOUND, "模型通道不存在: id=" + channelId))
                        : Mono.just(views.get(0)));
    }

    /**
     * 全量通道健康汇总，供管理概览与 Actuator 自定义 {@code HealthIndicator} 复用。
     */
    public Mono<HealthSummary> summary() {
        return channels(null, null, null).map(HealthAdminService::summarize);
    }

    private static HealthSummary summarize(List<ChannelHealthView> views) {
        int up = 0;
        int down = 0;
        int ejected = 0;
        int unknown = 0;
        int routable = 0;
        int disabled = 0;
        for (ChannelHealthView v : views) {
            if (v.enabled() != null && v.enabled() == 0) {
                disabled++;
            }
            if (v.routable()) {
                routable++;
            }
            switch (v.status()) {
                case UP -> up++;
                case DOWN -> down++;
                case EJECTED -> ejected++;
                case UNKNOWN -> unknown++;
            }
        }
        return new HealthSummary(views.size(), up, down, ejected, unknown, routable, disabled);
    }

    /**
     * 通道健康汇总。
     *
     * @param total    通道总数（含停用）
     * @param up       状态 UP 的通道数
     * @param down     状态 DOWN 的通道数
     * @param ejected  处于剔除冷却期的通道数
     * @param unknown  尚无健康数据的通道数
     * @param routable 当前可参与路由的通道数
     * @param disabled 配置层已停用的通道数
     */
    public record HealthSummary(int total, int up, int down, int ejected,
                                int unknown, int routable, int disabled) {
    }

    // ------------------------------------------------------------------ 手动干预

    /**
     * 手动探测一个通道：无论当前状态如何都发起一次真实探测，探测结果照常回写健康并触发自动恢复/剔除判定。
     *
     * @return 探测结论 + 探测后的健康视图；通道不存在抛 404
     */
    public Mono<ProbeOutcome> probe(Long channelId) {
        return Mono.fromCallable(() -> loadPreparedById(channelId))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(prepared -> probeScheduler.probeChannel(channelId)
                        .defaultIfEmpty(HealthResult.unknown())
                        .flatMap(result -> healthOf(channelId)
                                .map(health -> new ProbeOutcome(result, viewOf(prepared, health)))));
    }

    /**
     * 手动恢复通道：置为 UP、清除冷却与连续失败计数，并记 RECOVER 事件。
     *
     * @return 恢复后的健康视图
     */
    public Mono<ChannelHealthView> recover(Long channelId) {
        return Mono.fromCallable(() -> loadPreparedById(channelId))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(prepared -> healthOf(channelId)
                        .flatMap(before -> healthStore.recover(channelId)
                                .then(eventLog.record(channelId, prepared.channel().getProviderId(),
                                        HealthEventType.RECOVER, before.status(), HealthStatus.UP,
                                        "管理接口手动恢复"))
                                .then(healthOf(channelId))
                                .map(after -> viewOf(prepared, after))));
    }

    /**
     * 手动剔除通道：置为 EJECTED 并进入冷却期，记 EJECT 事件。用于「配置有问题但探测正常」时人工摘流。
     *
     * @param cooldownSeconds 冷却时长（秒）；{@code null} 或非正数取 {@code gateway.health.eject-cooldown-ms}，
     *                        上限 {@value #MAX_MANUAL_EJECT_SECONDS} 秒
     * @return 剔除后的健康视图
     */
    public Mono<ChannelHealthView> eject(Long channelId, Long cooldownSeconds) {
        long cooldownMs = resolveCooldownMs(cooldownSeconds);
        return Mono.fromCallable(() -> loadPreparedById(channelId))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(prepared -> healthOf(channelId)
                        .flatMap(before -> healthStore.eject(channelId, cooldownMs)
                                .then(eventLog.record(channelId, prepared.channel().getProviderId(),
                                        HealthEventType.EJECT, before.status(), HealthStatus.EJECTED,
                                        "管理接口手动剔除，冷却 " + cooldownMs + "ms"))
                                .then(healthOf(channelId))
                                .map(after -> viewOf(prepared, after))));
    }

    private long resolveCooldownMs(Long cooldownSeconds) {
        if (cooldownSeconds == null || cooldownSeconds <= 0) {
            long configured = properties.getHealth().getEjectCooldownMs();
            return configured > 0 ? configured : DEFAULT_MANUAL_EJECT_SECONDS * 1000L;
        }
        return Math.min(cooldownSeconds, MAX_MANUAL_EJECT_SECONDS) * 1000L;
    }

    // ------------------------------------------------------------------ 健康事件检索

    /** 分页查询健康事件（按创建时间倒序），供事后追溯剔除/恢复/凭证过期时间线。 */
    public Mono<AdminPage<HealthEventEntity>> events(Long channelId, Long providerId, String eventType,
                                                     LocalDateTime from, LocalDateTime to,
                                                     Integer page, Integer size) {
        int pageNo = normalizePage(page);
        int pageSize = normalizeSize(size);
        return Mono.fromCallable(() -> {
                    QueryWrapper<HealthEventEntity> wrapper = Wrappers.query();
                    if (channelId != null) {
                        wrapper.eq("channel_id", channelId);
                    }
                    if (providerId != null) {
                        wrapper.eq("provider_id", providerId);
                    }
                    if (eventType != null && !eventType.isBlank()) {
                        HealthEventType parsed = HealthEventType.of(eventType);
                        if (parsed == null) {
                            throw new GatewayException(ErrorCode.BAD_REQUEST,
                                    "事件类型 event_type 取值非法: " + eventType);
                        }
                        wrapper.eq("event_type", parsed.name());
                    }
                    if (from != null) {
                        wrapper.ge("created_at", from);
                    }
                    if (to != null) {
                        wrapper.le("created_at", to);
                    }
                    wrapper.orderByDesc("created_at").orderByDesc("id");
                    Page<HealthEventEntity> result = eventMapper.selectPage(new Page<>(pageNo, pageSize), wrapper);
                    List<HealthEventEntity> rows = result != null && result.getRecords() != null
                            ? result.getRecords() : List.of();
                    long total = result != null ? result.getTotal() : 0L;
                    return new AdminPage<>(rows, total, pageNo, pageSize);
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    // ------------------------------------------------------------------ 内部实现

    /** 通道 + 其逻辑模型名 + 供应商名/编码的预装载组合，避免逐条查询产生 N+1。 */
    private record Prepared(ModelChannelEntity channel, String logicalModel, String providerName, String providerCode) {
    }

    private Prepared loadPreparedById(Long channelId) {
        if (channelId == null) {
            throw new GatewayException(ErrorCode.BAD_REQUEST, "缺少通道 ID");
        }
        ModelChannelEntity channel = channelMapper.selectById(channelId);
        if (channel == null) {
            throw new GatewayException(ErrorCode.NOT_FOUND, "模型通道不存在: id=" + channelId);
        }
        List<Prepared> prepared = enrich(List.of(channel));
        return prepared.get(0);
    }

    private List<Prepared> loadPrepared(Long logicalModelId, Long providerId) {
        QueryWrapper<ModelChannelEntity> wrapper = Wrappers.query();
        if (logicalModelId != null) {
            wrapper.eq("logical_model_id", logicalModelId);
        }
        if (providerId != null) {
            wrapper.eq("provider_id", providerId);
        }
        wrapper.orderByAsc("priority").orderByDesc("weight").orderByAsc("id");
        List<ModelChannelEntity> channels = channelMapper.selectList(wrapper);
        return channels == null || channels.isEmpty() ? List.of() : enrich(channels);
    }

    /** 批量补齐逻辑模型名与供应商名/编码（两次 IN 查询，避免 N+1）。 */
    private List<Prepared> enrich(List<ModelChannelEntity> channels) {
        Set<Long> modelIds = new HashSet<>();
        Set<Long> providerIds = new HashSet<>();
        for (ModelChannelEntity channel : channels) {
            if (channel.getLogicalModelId() != null) {
                modelIds.add(channel.getLogicalModelId());
            }
            if (channel.getProviderId() != null) {
                providerIds.add(channel.getProviderId());
            }
        }
        Map<Long, String> modelNames = new HashMap<>();
        if (!modelIds.isEmpty()) {
            List<LogicalModelEntity> models = modelMapper.selectList(
                    Wrappers.<LogicalModelEntity>query().in("id", modelIds));
            if (models != null) {
                for (LogicalModelEntity model : models) {
                    modelNames.put(model.getId(), model.getName());
                }
            }
        }
        Map<Long, ProviderEntity> providers = new HashMap<>();
        if (!providerIds.isEmpty()) {
            List<ProviderEntity> rows = providerMapper.selectList(
                    Wrappers.<ProviderEntity>query().in("id", providerIds));
            if (rows != null) {
                for (ProviderEntity provider : rows) {
                    providers.put(provider.getId(), provider);
                }
            }
        }
        List<Prepared> result = new ArrayList<>(channels.size());
        for (ModelChannelEntity channel : channels) {
            ProviderEntity provider = providers.get(channel.getProviderId());
            result.add(new Prepared(channel,
                    modelNames.get(channel.getLogicalModelId()),
                    provider != null ? provider.getName() : null,
                    provider != null ? provider.getCode() : null));
        }
        return result;
    }

    /** 批量读取健康并组装视图；健康读取失败时以 UNKNOWN 兜底，绝不因存储抖动而失败。 */
    private Mono<List<ChannelHealthView>> withHealth(List<Prepared> prepared) {
        if (prepared.isEmpty()) {
            return Mono.just(List.of());
        }
        List<Long> ids = prepared.stream().map(p -> p.channel().getId()).toList();
        return healthStore.getAll(ids)
                .onErrorResume(e -> {
                    log.warn("批量读取通道健康失败，按 UNKNOWN 呈现: {}", e.toString());
                    return Mono.just(Map.of());
                })
                .defaultIfEmpty(Map.of())
                .map(healthMap -> prepared.stream()
                        .map(p -> viewOf(p, healthMap.get(p.channel().getId())))
                        .toList());
    }

    private Mono<ChannelHealth> healthOf(Long channelId) {
        return healthStore.get(channelId)
                .onErrorResume(e -> {
                    log.warn("读取通道 {} 健康失败，按 UNKNOWN 处理: {}", channelId, e.toString());
                    return Mono.just(ChannelHealth.unknown(channelId));
                })
                .defaultIfEmpty(ChannelHealth.unknown(channelId));
    }

    private static ChannelHealthView viewOf(Prepared prepared, ChannelHealth health) {
        return ChannelHealthView.of(prepared.channel(), prepared.logicalModel(),
                prepared.providerName(), prepared.providerCode(), health);
    }

    private int normalizePage(Integer page) {
        return page == null || page < 1 ? 1 : page;
    }

    private int normalizeSize(Integer size) {
        int max = properties.getAdmin().getMaxPageSize();
        if (max <= 0) {
            max = 500;
        }
        if (size == null || size <= 0) {
            return Math.min(DEFAULT_PAGE_SIZE, max);
        }
        return Math.min(size, max);
    }

    /**
     * 手动探测结论。
     *
     * @param result 探测结果（状态/时延/说明/归一化错误码）
     * @param health 探测后的通道健康视图
     */
    public record ProbeOutcome(HealthResult result, ChannelHealthView health) {

        public boolean isUp() {
            return result != null && result.isUp();
        }

        public boolean isAuthExpired() {
            return result != null && result.isAuthExpired();
        }
    }
}
