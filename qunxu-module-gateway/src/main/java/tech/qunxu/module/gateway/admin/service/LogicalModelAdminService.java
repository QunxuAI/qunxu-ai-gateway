package tech.qunxu.module.gateway.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import tech.qunxu.module.gateway.admin.AdminPage;
import tech.qunxu.module.gateway.common.enums.Capability;
import tech.qunxu.module.gateway.config.ConfigVersionPublisher;
import tech.qunxu.module.gateway.config.GatewayProperties;
import tech.qunxu.module.gateway.persistence.entity.LogicalModelEntity;
import tech.qunxu.module.gateway.persistence.entity.ModelChannelEntity;
import tech.qunxu.module.gateway.persistence.mapper.LogicalModelMapper;
import tech.qunxu.module.gateway.persistence.mapper.ModelChannelMapper;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.List;

/**
 * 逻辑模型管理服务（{@code /admin/logical-models}）。
 *
 * <p>逻辑模型是客户端请求中 {@code model} 字段的取值，也是 {@code GET /v1/models} 的数据来源；
 * {@code name} 全局唯一，{@code capability} 决定可路由到哪一类适配器与端点。删除前校验无通道引用，
 * 避免产生「有模型无通道」的悬空配置。</p>
 */
@Service
public class LogicalModelAdminService extends AbstractConfigAdminService<LogicalModelEntity> {

    private static final List<String> CAPABILITIES =
            Arrays.stream(Capability.values()).map(Enum::name).toList();

    private final LogicalModelMapper modelMapper;
    private final ModelChannelMapper channelMapper;
    private final ConfigVersionPublisher publisher;

    public LogicalModelAdminService(LogicalModelMapper modelMapper,
                                    ModelChannelMapper channelMapper,
                                    ConfigVersionPublisher publisher,
                                    GatewayProperties properties,
                                    ObjectMapper objectMapper) {
        super(properties, objectMapper);
        this.modelMapper = modelMapper;
        this.channelMapper = channelMapper;
        this.publisher = publisher;
    }

    @Override
    protected BaseMapper<LogicalModelEntity> mapper() {
        return modelMapper;
    }

    @Override
    protected String resourceLabel() {
        return "逻辑模型";
    }

    /** 分页查询逻辑模型。{@code keyword} 模糊匹配名称，{@code capability}/{@code status} 精确过滤。 */
    public Mono<AdminPage<LogicalModelEntity>> query(String keyword, String capability, Integer status,
                                                     Integer page, Integer size) {
        return page(filters(keyword, capability, status).orderByAsc("capability").orderByAsc("name"), page, size);
    }

    /** 列出全部逻辑模型（不分页）。 */
    public Mono<List<LogicalModelEntity>> queryAll(String keyword, String capability, Integer status) {
        return list(filters(keyword, capability, status).orderByAsc("capability").orderByAsc("name"));
    }

    /** 列出启用中的逻辑模型，供 {@code GET /v1/models} 暴露给客户端。 */
    public Mono<List<LogicalModelEntity>> queryEnabled() {
        return list(Wrappers.<LogicalModelEntity>query()
                .eq("status", 1)
                .orderByAsc("capability")
                .orderByAsc("name"));
    }

    private QueryWrapper<LogicalModelEntity> filters(String keyword, String capability, Integer status) {
        QueryWrapper<LogicalModelEntity> wrapper = Wrappers.query();
        if (hasText(keyword)) {
            wrapper.like("name", keyword.trim());
        }
        if (hasText(capability)) {
            wrapper.eq("capability", capability.trim().toUpperCase());
        }
        if (status != null) {
            wrapper.eq("status", status);
        }
        return wrapper;
    }

    @Override
    protected void validateCreate(LogicalModelEntity entity) {
        requireText("模型名称 name", entity.getName(), 128);
        requireText("模型能力 capability", entity.getCapability(), 16);
        requireCapability(entity.getCapability());
        requireFlag("启用状态 status", entity.getStatus());
        requireDescription(entity.getDescription());
        requireUnique(entity.getName(), null);
    }

    @Override
    protected void validateUpdate(LogicalModelEntity patch, LogicalModelEntity existing) {
        if (hasText(patch.getName())) {
            requireText("模型名称 name", patch.getName(), 128);
        }
        requireCapability(patch.getCapability());
        requireFlag("启用状态 status", patch.getStatus());
        requireDescription(patch.getDescription());
        if (hasText(patch.getName()) && !trimmed(patch.getName()).equals(existing.getName())) {
            requireUnique(trimmed(patch.getName()), patch.getId());
        }
    }

    @Override
    protected void prepareCreate(LogicalModelEntity entity) {
        entity.setName(trimmed(entity.getName()));
        entity.setCapability(entity.getCapability().trim().toUpperCase());
        if (entity.getStatus() == null) {
            entity.setStatus(1);
        }
    }

    @Override
    protected void mergeForUpdate(LogicalModelEntity patch, LogicalModelEntity existing) {
        if (hasText(patch.getName())) {
            patch.setName(trimmed(patch.getName()));
        }
        if (hasText(patch.getCapability())) {
            patch.setCapability(patch.getCapability().trim().toUpperCase());
        }
    }

    @Override
    protected void beforeDelete(LogicalModelEntity existing) {
        Long channels = channelMapper.selectCount(Wrappers.<ModelChannelEntity>lambdaQuery()
                .eq(ModelChannelEntity::getLogicalModelId, existing.getId()));
        if (channels != null && channels > 0) {
            throw conflict("逻辑模型仍被 " + channels + " 个通道引用，请先删除这些通道: id=" + existing.getId());
        }
    }

    @Override
    protected Mono<Void> afterChange(String action) {
        return publisher.publish(ConfigVersionPublisher.TYPE_LOGICAL_MODEL);
    }

    /** 唯一约束 {@code uk_logical_model_name} 的应用层前置校验。 */
    private void requireUnique(String name, Long excludeId) {
        if (!hasText(name)) {
            return;
        }
        QueryWrapper<LogicalModelEntity> wrapper = Wrappers.query();
        wrapper.eq("name", name.trim());
        if (excludeId != null) {
            wrapper.ne("id", excludeId);
        }
        Long duplicated = modelMapper.selectCount(wrapper);
        if (duplicated != null && duplicated > 0) {
            throw conflict("逻辑模型名称已存在: " + name);
        }
    }

    private void requireCapability(String capability) {
        if (!hasText(capability)) {
            return;
        }
        if (Capability.of(capability) == null) {
            throw badRequest("模型能力 capability 取值非法: " + capability + "，可选值 " + CAPABILITIES);
        }
    }

    private void requireDescription(String description) {
        if (description != null && description.length() > 512) {
            throw badRequest("模型描述 description 长度超出上限 512");
        }
    }

    private void requireFlag(String field, Integer value) {
        if (value != null && value != 0 && value != 1) {
            throw badRequest(field + " 只能为 0 或 1: " + value);
        }
    }
}
