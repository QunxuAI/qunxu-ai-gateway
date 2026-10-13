package tech.qunxu.module.gateway.persistence.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import tech.qunxu.module.gateway.persistence.entity.LogicalModelEntity;
import tech.qunxu.module.gateway.persistence.entity.ModelChannelEntity;
import tech.qunxu.module.gateway.persistence.entity.ProviderEntity;
import tech.qunxu.module.gateway.persistence.mapper.LogicalModelMapper;
import tech.qunxu.module.gateway.persistence.mapper.ModelChannelMapper;
import tech.qunxu.module.gateway.persistence.mapper.ProviderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;

/**
 * 配置读取服务：以响应式方式向流水线提供逻辑模型、通道与供应商配置。
 *
 * <p>MyBatis-Plus 为阻塞 JDBC，所有查询经 {@link Schedulers#boundedElastic()} 卸载，避免阻塞 Netty 事件循环。
 * 配置读多写少，使用 Caffeine 本地缓存（短 TTL）降低数据库压力；多实例热失效（Redis pub/sub）在后续里程碑接入，
 * TTL 在此期间兜底约束陈旧窗口。</p>
 */
@Service
public class ConfigService {

    private static final Logger log = LoggerFactory.getLogger(ConfigService.class);
    private static final Duration CACHE_TTL = Duration.ofSeconds(30);
    private static final int CACHE_MAX = 2000;

    private final LogicalModelMapper logicalModelMapper;
    private final ModelChannelMapper modelChannelMapper;
    private final ProviderMapper providerMapper;

    private final Cache<String, LogicalModelEntity> modelCache = newCache();
    private final Cache<Long, List<ModelChannelEntity>> channelCache = newCache();
    private final Cache<Long, ProviderEntity> providerCache = newCache();

    public ConfigService(LogicalModelMapper logicalModelMapper,
                         ModelChannelMapper modelChannelMapper,
                         ProviderMapper providerMapper) {
        this.logicalModelMapper = logicalModelMapper;
        this.modelChannelMapper = modelChannelMapper;
        this.providerMapper = providerMapper;
    }

    private static <K, V> Cache<K, V> newCache() {
        return Caffeine.newBuilder().expireAfterWrite(CACHE_TTL).maximumSize(CACHE_MAX).build();
    }

    /** 按对外模型名查找启用的逻辑模型；不存在返回 {@link Mono#empty()}。 */
    public Mono<LogicalModelEntity> findLogicalModelByName(String name) {
        if (name == null || name.isBlank()) {
            return Mono.empty();
        }
        return Mono.fromCallable(() -> {
                    LogicalModelEntity cached = modelCache.getIfPresent(name);
                    if (cached != null) {
                        return cached;
                    }
                    LogicalModelEntity entity = logicalModelMapper.selectOne(
                            Wrappers.<LogicalModelEntity>lambdaQuery()
                                    .eq(LogicalModelEntity::getName, name)
                                    .eq(LogicalModelEntity::getStatus, 1)
                                    .last("LIMIT 1"));
                    if (entity != null) {
                        modelCache.put(name, entity);
                    }
                    return entity;
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 按 ID 查找逻辑模型（不过缓存）；不存在返回 {@link Mono#empty()}。 */
    public Mono<LogicalModelEntity> findLogicalModelById(Long logicalModelId) {
        if (logicalModelId == null) {
            return Mono.empty();
        }
        return Mono.fromCallable(() -> logicalModelMapper.selectById(logicalModelId))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 查找某逻辑模型下启用的通道列表（可能为空）。 */
    public Mono<List<ModelChannelEntity>> findEnabledChannels(Long logicalModelId) {
        if (logicalModelId == null) {
            return Mono.just(List.of());
        }
        return Mono.fromCallable(() -> {
                    List<ModelChannelEntity> cached = channelCache.getIfPresent(logicalModelId);
                    if (cached != null) {
                        return cached;
                    }
                    List<ModelChannelEntity> channels = modelChannelMapper.selectList(
                            Wrappers.<ModelChannelEntity>lambdaQuery()
                                    .eq(ModelChannelEntity::getLogicalModelId, logicalModelId)
                                    .eq(ModelChannelEntity::getEnabled, 1));
                    List<ModelChannelEntity> result = channels != null ? channels : List.of();
                    channelCache.put(logicalModelId, result);
                    return result;
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 查找全部启用通道（供主动探测调度遍历，不区分逻辑模型）。 */
    public Mono<List<ModelChannelEntity>> findAllEnabledChannels() {
        return Mono.fromCallable(() -> {
                    List<ModelChannelEntity> channels = modelChannelMapper.selectList(
                            Wrappers.<ModelChannelEntity>lambdaQuery()
                                    .eq(ModelChannelEntity::getEnabled, 1));
                    return channels != null ? channels : List.<ModelChannelEntity>of();
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 按 ID 查找供应商；不存在返回 {@link Mono#empty()}。 */
    public Mono<ProviderEntity> findProviderById(Long providerId) {
        if (providerId == null) {
            return Mono.empty();
        }
        return Mono.fromCallable(() -> {
                    ProviderEntity cached = providerCache.getIfPresent(providerId);
                    if (cached != null) {
                        return cached;
                    }
                    ProviderEntity entity = providerMapper.selectById(providerId);
                    if (entity != null) {
                        providerCache.put(providerId, entity);
                    }
                    return entity;
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 按 ID 查找通道（不过缓存）；不存在返回 {@link Mono#empty()}。
     *
     * <p>供管理接口与手动探测等低频路径使用；请求主链路仍走 {@link #findEnabledChannels(Long)} 的缓存版本。</p>
     */
    public Mono<ModelChannelEntity> findChannelById(Long channelId) {
        if (channelId == null) {
            return Mono.empty();
        }
        return Mono.fromCallable(() -> modelChannelMapper.selectById(channelId))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 失效全部本地缓存（配置写入后调用；后续与 Redis pub/sub 联动）。 */
    public void invalidateAll() {
        modelCache.invalidateAll();
        channelCache.invalidateAll();
        providerCache.invalidateAll();
        log.debug("配置本地缓存已失效");
    }
}
