package tech.qunxu.module.gateway.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import tech.qunxu.module.gateway.admin.AdminPage;
import tech.qunxu.module.gateway.admin.BatchChannelCreateRequest;
import tech.qunxu.module.gateway.admin.BatchChannelCreateResult;
import tech.qunxu.module.gateway.common.CapabilityDetector;
import tech.qunxu.module.gateway.common.enums.Capability;
import tech.qunxu.module.gateway.common.enums.ChannelProtocol;
import tech.qunxu.module.gateway.config.ConfigVersionPublisher;
import tech.qunxu.module.gateway.config.GatewayProperties;
import tech.qunxu.module.gateway.health.HealthStore;
import tech.qunxu.module.gateway.persistence.entity.LogicalModelEntity;
import tech.qunxu.module.gateway.persistence.entity.ModelChannelEntity;
import tech.qunxu.module.gateway.persistence.entity.ProviderEntity;
import tech.qunxu.module.gateway.persistence.mapper.LogicalModelMapper;
import tech.qunxu.module.gateway.persistence.mapper.ModelChannelMapper;
import tech.qunxu.module.gateway.persistence.mapper.ProviderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 模型通道管理服务（{@code /admin/channels}）。
 *
 * <p>通道是「逻辑模型 ↔ 供应商真实部署」的映射，也是负载均衡与健康自愈的基本单位。新增/更新时校验
 * 逻辑模型与供应商存在性，避免产生指向已删除配置的悬空通道（会导致路由阶段整条链路不可用）。</p>
 *
 * <p>删除通道时一并清理 Redis 中的健康键，防止 ID 复用后继承旧通道被剔除的状态。</p>
 */
@Service
public class ModelChannelAdminService extends AbstractConfigAdminService<ModelChannelEntity> {

    private static final Logger log = LoggerFactory.getLogger(ModelChannelAdminService.class);

    private static final List<String> PROTOCOLS =
            Arrays.stream(ChannelProtocol.values()).map(Enum::name).toList();

    private final ModelChannelMapper channelMapper;
    private final LogicalModelMapper modelMapper;
    private final ProviderMapper providerMapper;
    private final HealthStore healthStore;
    private final ConfigVersionPublisher publisher;

    public ModelChannelAdminService(ModelChannelMapper channelMapper,
                                    LogicalModelMapper modelMapper,
                                    ProviderMapper providerMapper,
                                    HealthStore healthStore,
                                    ConfigVersionPublisher publisher,
                                    GatewayProperties properties,
                                    ObjectMapper objectMapper) {
        super(properties, objectMapper);
        this.channelMapper = channelMapper;
        this.modelMapper = modelMapper;
        this.providerMapper = providerMapper;
        this.healthStore = healthStore;
        this.publisher = publisher;
    }

    @Override
    protected BaseMapper<ModelChannelEntity> mapper() {
        return channelMapper;
    }

    @Override
    protected String resourceLabel() {
        return "模型通道";
    }

    /** 分页查询通道。可按逻辑模型、供应商、协议与启用状态过滤。 */
    public Mono<AdminPage<ModelChannelEntity>> query(Long logicalModelId, Long providerId, String protocol,
                                                     Integer enabled, Integer page, Integer size) {
        return page(filters(logicalModelId, providerId, protocol, enabled)
                .orderByAsc("priority").orderByDesc("weight").orderByAsc("id"), page, size);
    }

    /** 列出全部通道（不分页）。 */
    public Mono<List<ModelChannelEntity>> queryAll(Long logicalModelId, Long providerId,
                                                   String protocol, Integer enabled) {
        return list(filters(logicalModelId, providerId, protocol, enabled)
                .orderByAsc("priority").orderByDesc("weight").orderByAsc("id"));
    }

    /**
     * 批量上架模型：从供应商模型清单批量创建通道，并自动创建（或复用同名）逻辑模型。
     *
     * <p>每个模型对应一条逻辑模型（名 = 上游模型名，能力取 {@code capability}，默认 CHAT）与一条通道；
     * 已存在（供应商 + 模型名）的通道自动跳过，同名逻辑模型直接复用。全部落库后统一广播一次配置变更。</p>
     */
    public Mono<BatchChannelCreateResult> batchCreate(BatchChannelCreateRequest request) {
        return Mono.fromCallable(() -> {
                    if (request == null) {
                        throw badRequest("请求体不能为空");
                    }
                    Long providerId = request.providerId();
                    List<String> models = request.upstreamModels();
                    if (providerId == null) {
                        throw badRequest("供应商 ID provider_id 不能为空");
                    }
                    if (models == null || models.isEmpty()) {
                        throw badRequest("模型列表 upstream_models 不能为空");
                    }
                    ProviderEntity provider = requireEnabledProvider(providerId);

                    List<String> normalized = models.stream()
                            .map(s -> trimmed(s))
                            .filter(s -> s != null && !s.isEmpty())
                            .distinct()
                            .toList();
                    if (normalized.isEmpty()) {
                        throw badRequest("模型列表 upstream_models 不能为空");
                    }
                    String fallbackCapability = normalizeCapability(request.capability());

                    // 已存在通道（按供应商 + 上游模型名）预先收集，避免重复上架
                    Set<String> existingChannels = new LinkedHashSet<>();
                    channelMapper.selectList(new QueryWrapper<ModelChannelEntity>()
                                    .eq("provider_id", providerId)
                                    .in("upstream_model", normalized))
                            .forEach(ch -> existingChannels.add(ch.getUpstreamModel()));

                    List<LogicalModelEntity> createdModels = new ArrayList<>();
                    List<ModelChannelEntity> created = new ArrayList<>();
                    List<String> skipped = new ArrayList<>();
                    boolean published = false;
                    for (String model : normalized) {
                        // 每个模型自动识别能力；无法识别时回退到请求指定的能力（默认 CHAT）
                        Capability detected = CapabilityDetector.detect(model);
                        String capability = detected != null ? detected.name() : fallbackCapability;
                        LogicalModelEntity logicalModel = resolveLogicalModel(model, capability, provider, createdModels);
                        if (existingChannels.contains(model)) {
                            skipped.add(model);
                            continue;
                        }
                        ModelChannelEntity entity = new ModelChannelEntity();
                        entity.setLogicalModelId(logicalModel.getId());
                        entity.setProviderId(providerId);
                        entity.setUpstreamModel(model);
                        entity.setProtocol(request.protocol());
                        entity.setWeight(request.weight());
                        entity.setPriority(request.priority());
                        entity.setEnabled(request.enabled());
                        entity.setConfigJson(request.configJson());
                        validateChannelFields(entity);
                        prepareCreate(entity);
                        mapper().insert(entity);
                        created.add(entity);
                        existingChannels.add(model);
                        published = true;
                    }
                    log.info("批量上架完成: 请求 {} 条，新建通道 {} 条，自动创建逻辑模型 {} 个，跳过 {} 条",
                            normalized.size(), created.size(), createdModels.size(), skipped.size());
                    if (published) {
                        publisher.publish(ConfigVersionPublisher.TYPE_LOGICAL_MODEL).block(Duration.ofSeconds(5));
                        publisher.publish(ConfigVersionPublisher.TYPE_CHANNEL).block(Duration.ofSeconds(5));
                    }
                    return new BatchChannelCreateResult(createdModels, created, skipped, normalized.size());
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 校验供应商存在且启用。 */
    private ProviderEntity requireEnabledProvider(Long providerId) {
        ProviderEntity provider = providerMapper.selectById(providerId);
        if (provider == null) {
            throw badRequest("引用的供应商不存在: provider_id=" + providerId);
        }
        if (provider.getStatus() != null && provider.getStatus() == 0) {
            throw badRequest("引用的供应商已禁用，请先启用: provider_id=" + providerId);
        }
        return provider;
    }

    /** 解析逻辑模型：同名已存在则复用，否则自动创建。 */
    private LogicalModelEntity resolveLogicalModel(String model, String capability,
                                                   ProviderEntity provider, List<LogicalModelEntity> createdModels) {
        LogicalModelEntity existing = modelMapper.selectOne(new QueryWrapper<LogicalModelEntity>()
                .eq("name", model).eq("deleted", 0));
        if (existing != null) {
            return existing;
        }
        LogicalModelEntity entity = new LogicalModelEntity();
        entity.setName(model);
        entity.setCapability(capability);
        entity.setStatus(1);
        entity.setDescription("由「批量上架」从供应商 " + provider.getName() + " 自动创建");
        try {
            modelMapper.insert(entity);
            createdModels.add(entity);
            return entity;
        } catch (DuplicateKeyException e) {
            // 并发下已由其它请求创建，回查复用
            LogicalModelEntity raced = modelMapper.selectOne(new QueryWrapper<LogicalModelEntity>()
                    .eq("name", model).eq("deleted", 0));
            if (raced != null) {
                return raced;
            }
            throw e;
        }
    }

    private static String normalizeCapability(String capability) {
        if (!hasText(capability)) {
            return "CHAT";
        }
        String normalized = capability.trim().toUpperCase();
        for (Capability c : Capability.values()) {
            if (c.name().equals(normalized)) {
                return normalized;
            }
        }
        throw badRequest("能力 capability 取值非法: " + capability + "，可选值 "
                + Arrays.stream(Capability.values()).map(Enum::name).toList());
    }

    private QueryWrapper<ModelChannelEntity> filters(Long logicalModelId, Long providerId,
                                                     String protocol, Integer enabled) {
        QueryWrapper<ModelChannelEntity> wrapper = Wrappers.query();
        if (logicalModelId != null) {
            wrapper.eq("logical_model_id", logicalModelId);
        }
        if (providerId != null) {
            wrapper.eq("provider_id", providerId);
        }
        if (hasText(protocol)) {
            wrapper.eq("protocol", protocol.trim().toUpperCase());
        }
        if (enabled != null) {
            wrapper.eq("enabled", enabled);
        }
        return wrapper;
    }

    @Override
    protected void validateCreate(ModelChannelEntity entity) {
        validateChannelFields(entity);
        requireReferences(entity.getLogicalModelId(), entity.getProviderId());
    }

    /** 字段级校验（不含引用完整性检查，批量上架时逻辑模型刚创建/解析，无需回查数据库）。 */
    private void validateChannelFields(ModelChannelEntity entity) {
        if (entity.getLogicalModelId() == null) {
            throw badRequest("逻辑模型 ID logical_model_id 不能为空");
        }
        if (entity.getProviderId() == null) {
            throw badRequest("供应商 ID provider_id 不能为空");
        }
        requireText("下游模型名 upstream_model", entity.getUpstreamModel(), 128);
        requireProtocol(entity.getProtocol());
        requireJson("通道配置 config_json", entity.getConfigJson());
        requireWeight(entity.getWeight());
        requireFlag("启用标记 enabled", entity.getEnabled());
    }

    @Override
    protected void validateUpdate(ModelChannelEntity patch, ModelChannelEntity existing) {
        if (hasText(patch.getUpstreamModel())) {
            requireText("下游模型名 upstream_model", patch.getUpstreamModel(), 128);
        }
        requireProtocol(patch.getProtocol());
        requireJson("通道配置 config_json", patch.getConfigJson());
        requireWeight(patch.getWeight());
        requireFlag("启用标记 enabled", patch.getEnabled());
        Long logicalModelId = patch.getLogicalModelId() != null
                ? patch.getLogicalModelId() : existing.getLogicalModelId();
        Long providerId = patch.getProviderId() != null ? patch.getProviderId() : existing.getProviderId();
        if (!sameId(patch.getLogicalModelId(), existing.getLogicalModelId())
                || !sameId(patch.getProviderId(), existing.getProviderId())) {
            requireReferences(logicalModelId, providerId);
        }
    }

    @Override
    protected void prepareCreate(ModelChannelEntity entity) {
        entity.setUpstreamModel(trimmed(entity.getUpstreamModel()));
        entity.setProtocol(normalizeProtocol(entity.getProtocol()));
        if (entity.getWeight() == null) {
            entity.setWeight(100);
        }
        if (entity.getPriority() == null) {
            entity.setPriority(100);
        }
        if (entity.getEnabled() == null) {
            entity.setEnabled(1);
        }
        entity.setVersion(0);
    }

    @Override
    protected void mergeForUpdate(ModelChannelEntity patch, ModelChannelEntity existing) {
        if (hasText(patch.getUpstreamModel())) {
            patch.setUpstreamModel(trimmed(patch.getUpstreamModel()));
        }
        if (hasText(patch.getProtocol())) {
            patch.setProtocol(normalizeProtocol(patch.getProtocol()));
        }
    }

    @Override
    protected Mono<Void> afterChange(String action) {
        return publisher.publish(ConfigVersionPublisher.TYPE_CHANNEL);
    }

    /** 删除后清理 Redis 健康键，避免通道 ID 复用时继承旧的剔除/时延状态。 */
    @Override
    protected Mono<Void> afterDelete(ModelChannelEntity existing) {
        if (existing.getId() == null) {
            return Mono.empty();
        }
        return healthStore.delete(existing.getId())
                .onErrorResume(e -> {
                    log.warn("清理通道健康数据失败 channelId={}: {}", existing.getId(), e.toString());
                    return Mono.empty();
                });
    }

    /** 校验通道引用的逻辑模型与供应商均存在（在 boundedElastic 线程内以阻塞方式查询）。 */
    private void requireReferences(Long logicalModelId, Long providerId) {
        if (logicalModelId != null && modelMapper.selectById(logicalModelId) == null) {
            throw badRequest("引用的逻辑模型不存在: logical_model_id=" + logicalModelId);
        }
        if (providerId != null) {
            ProviderEntity provider = providerMapper.selectById(providerId);
            if (provider == null) {
                throw badRequest("引用的供应商不存在: provider_id=" + providerId);
            }
            if (provider.getStatus() != null && provider.getStatus() == 0) {
                throw badRequest("引用的供应商已禁用，请先启用: provider_id=" + providerId);
            }
        }
    }

    private void requireProtocol(String protocol) {
        if (!hasText(protocol)) {
            return;
        }
        String normalized = protocol.trim();
        for (String candidate : PROTOCOLS) {
            if (candidate.equalsIgnoreCase(normalized)) {
                return;
            }
        }
        throw badRequest("通道协议 protocol 取值非法: " + protocol + "，可选值 " + PROTOCOLS);
    }

    private void requireWeight(Integer weight) {
        if (weight != null && weight < 0) {
            throw badRequest("负载均衡权重 weight 不能为负数: " + weight);
        }
    }

    private void requireFlag(String field, Integer value) {
        if (value != null && value != 0 && value != 1) {
            throw badRequest(field + " 只能为 0 或 1: " + value);
        }
    }

    private static String normalizeProtocol(String protocol) {
        if (!hasText(protocol)) {
            return ChannelProtocol.OPENAI_COMPAT.name();
        }
        ChannelProtocol parsed = ChannelProtocol.of(protocol);
        return parsed != null ? parsed.name() : protocol.trim().toUpperCase();
    }

    private static boolean sameId(Long a, Long b) {
        return a == null || a.equals(b);
    }
}
