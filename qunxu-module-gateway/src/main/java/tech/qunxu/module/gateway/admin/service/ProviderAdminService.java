package tech.qunxu.module.gateway.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import tech.qunxu.module.gateway.admin.AdminPage;
import tech.qunxu.module.gateway.common.crypto.CredentialCipher;
import tech.qunxu.module.gateway.common.enums.ProviderType;
import tech.qunxu.module.gateway.config.ConfigVersionPublisher;
import tech.qunxu.module.gateway.config.GatewayProperties;
import tech.qunxu.module.gateway.persistence.entity.ModelChannelEntity;
import tech.qunxu.module.gateway.persistence.entity.ProviderEntity;
import tech.qunxu.module.gateway.persistence.mapper.ModelChannelMapper;
import tech.qunxu.module.gateway.persistence.mapper.ProviderMapper;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.List;

/**
 * 供应商管理服务（{@code /admin/providers}）。
 *
 * <p>凭证安全是本服务的核心约束：</p>
 * <ul>
 *   <li><b>写</b>：明文凭证经 {@link CredentialCipher} AES-256-GCM 加密后落库；已加密（带前缀）的值原样保留，
 *       因此幂等地重复提交不会二次加密。</li>
 *   <li><b>读</b>：默认把 {@code credentials} 替换为 {@value #MASKED}，仅当
 *       {@code gateway.admin.expose-credentials=true} 时返回<b>密文</b>（任何情况下都不返回明文）。</li>
 *   <li><b>改</b>：更新请求中 {@code credentials} 为 {@code null}/空白/占位值时视为「保持原值」，
 *       该字段不进入 SET 子句，避免前端回显的掩码把真实凭证覆盖掉。</li>
 * </ul>
 *
 * <p>变更后通过 {@link ConfigVersionPublisher} 失效本实例配置缓存并广播给其它实例。</p>
 */
@Service
public class ProviderAdminService extends AbstractConfigAdminService<ProviderEntity> {

    /** 支持的鉴权类型，与 {@code provider.auth_type} 及 {@code UpstreamAuthenticatorResolver} 对应。 */
    public static final List<String> AUTH_TYPES = List.of("BEARER", "ZHIPU_JWT", "QIANFAN_IAM", "SPARK_WS");

    private static final List<String> PROVIDER_CODES =
            Arrays.stream(ProviderType.values()).map(Enum::name).toList();
    private static final List<String> URL_SCHEMES = List.of("http://", "https://", "ws://", "wss://");

    private final ProviderMapper providerMapper;
    private final ModelChannelMapper channelMapper;
    private final CredentialCipher cipher;
    private final ConfigVersionPublisher publisher;

    public ProviderAdminService(ProviderMapper providerMapper,
                                ModelChannelMapper channelMapper,
                                CredentialCipher cipher,
                                ConfigVersionPublisher publisher,
                                GatewayProperties properties,
                                ObjectMapper objectMapper) {
        super(properties, objectMapper);
        this.providerMapper = providerMapper;
        this.channelMapper = channelMapper;
        this.cipher = cipher;
        this.publisher = publisher;
    }

    @Override
    protected BaseMapper<ProviderEntity> mapper() {
        return providerMapper;
    }

    @Override
    protected String resourceLabel() {
        return "供应商";
    }

    /** 分页查询供应商。{@code keyword} 模糊匹配名称或编码，{@code status} 精确匹配启用状态。 */
    public Mono<AdminPage<ProviderEntity>> query(String keyword, Integer status, Integer page, Integer size) {
        return page(filters(keyword, status).orderByAsc("priority").orderByDesc("id"), page, size);
    }

    /** 列出全部供应商（不分页），供管理界面下拉选择。 */
    public Mono<List<ProviderEntity>> queryAll(String keyword, Integer status) {
        return list(filters(keyword, status).orderByAsc("priority").orderByDesc("id"));
    }

    private QueryWrapper<ProviderEntity> filters(String keyword, Integer status) {
        QueryWrapper<ProviderEntity> wrapper = Wrappers.query();
        if (hasText(keyword)) {
            String like = keyword.trim();
            wrapper.and(w -> w.like("name", like).or().like("code", like));
        }
        if (status != null) {
            wrapper.eq("status", status);
        }
        return wrapper;
    }

    @Override
    protected void validateCreate(ProviderEntity entity) {
        requireText("供应商名称 name", entity.getName(), 128);
        requireText("供应商编码 code", entity.getCode(), 64);
        requireOneOf("供应商编码 code", entity.getCode(), PROVIDER_CODES);
        requireText("下游地址 base_url", entity.getBaseUrl(), 512);
        requireUrl("下游地址 base_url", entity.getBaseUrl());
        requireOneOf("鉴权类型 auth_type", entity.getAuthType(), AUTH_TYPES);
        requireFlag("启用状态 status", entity.getStatus());
        requireRemark(entity.getRemark());
        requireUnique(entity.getCode(), entity.getName(), null);
    }

    @Override
    protected void validateUpdate(ProviderEntity patch, ProviderEntity existing) {
        if (hasText(patch.getName())) {
            requireText("供应商名称 name", patch.getName(), 128);
        }
        if (hasText(patch.getCode())) {
            requireOneOf("供应商编码 code", patch.getCode(), PROVIDER_CODES);
        }
        if (hasText(patch.getBaseUrl())) {
            requireText("下游地址 base_url", patch.getBaseUrl(), 512);
            requireUrl("下游地址 base_url", patch.getBaseUrl());
        }
        requireOneOf("鉴权类型 auth_type", patch.getAuthType(), AUTH_TYPES);
        requireFlag("启用状态 status", patch.getStatus());
        requireRemark(patch.getRemark());
        String code = hasText(patch.getCode()) ? trimmed(patch.getCode()) : existing.getCode();
        String name = hasText(patch.getName()) ? trimmed(patch.getName()) : existing.getName();
        if (!sameValue(code, existing.getCode()) || !sameValue(name, existing.getName())) {
            requireUnique(code, name, patch.getId());
        }
    }

    @Override
    protected void prepareCreate(ProviderEntity entity) {
        entity.setName(trimmed(entity.getName()));
        entity.setCode(normalizeCode(entity.getCode()));
        entity.setBaseUrl(trimmed(entity.getBaseUrl()));
        if (!hasText(entity.getAuthType())) {
            entity.setAuthType("BEARER");
        } else {
            entity.setAuthType(entity.getAuthType().trim().toUpperCase());
        }
        if (entity.getStatus() == null) {
            entity.setStatus(1);
        }
        if (entity.getPriority() == null) {
            entity.setPriority(100);
        }
        entity.setCredentials(encryptIfNeeded(entity.getCredentials()));
        entity.setVersion(0);
    }

    @Override
    protected void mergeForUpdate(ProviderEntity patch, ProviderEntity existing) {
        if (hasText(patch.getName())) {
            patch.setName(trimmed(patch.getName()));
        }
        if (hasText(patch.getCode())) {
            patch.setCode(normalizeCode(patch.getCode()));
        }
        if (hasText(patch.getBaseUrl())) {
            patch.setBaseUrl(trimmed(patch.getBaseUrl()));
        }
        if (hasText(patch.getAuthType())) {
            patch.setAuthType(patch.getAuthType().trim().toUpperCase());
        }
        patch.setCredentials(resolveCredentials(patch.getCredentials()));
        // version 由乐观锁插件维护：调用方未提交时不参与锁校验，提交了则原样带入 WHERE 条件
    }

    /**
     * 解析更新请求中的凭证：{@code null}/空白/占位值表示保持原值（返回 {@code null} 使该列不进入 SET 子句），
     * 其余值加密后写入。
     */
    private String resolveCredentials(String submitted) {
        if (!hasText(submitted) || MASKED.equals(submitted.trim())) {
            return null;
        }
        return encryptIfNeeded(submitted);
    }

    private String encryptIfNeeded(String value) {
        if (!hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        if (MASKED.equals(trimmed)) {
            return null;
        }
        return cipher.isEncrypted(trimmed) ? trimmed : cipher.encrypt(trimmed);
    }

    @Override
    protected ProviderEntity sanitize(ProviderEntity entity) {
        if (entity == null) {
            return null;
        }
        if (!hasText(entity.getCredentials())) {
            return entity;
        }
        if (!properties.getAdmin().isExposeCredentials()) {
            entity.setCredentials(MASKED);
        }
        return entity;
    }

    @Override
    protected void beforeDelete(ProviderEntity existing) {
        Long channels = channelMapper.selectCount(Wrappers.<ModelChannelEntity>lambdaQuery()
                .eq(ModelChannelEntity::getProviderId, existing.getId()));
        if (channels != null && channels > 0) {
            throw conflict("供应商仍被 " + channels + " 个模型通道引用，请先删除或迁移这些通道: id=" + existing.getId());
        }
    }

    @Override
    protected Mono<Void> afterChange(String action) {
        return publisher.publish(ConfigVersionPublisher.TYPE_PROVIDER);
    }

    /** 唯一约束 {@code uk_provider_code_name(code, name)} 的应用层前置校验，给出比数据库异常更友好的提示。 */
    private void requireUnique(String code, String name, Long excludeId) {
        if (!hasText(code) || !hasText(name)) {
            return;
        }
        QueryWrapper<ProviderEntity> wrapper = Wrappers.<ProviderEntity>query();
        wrapper.eq("code", normalizeCode(code));
        wrapper.eq("name", name.trim());
        if (excludeId != null) {
            wrapper.ne("id", excludeId);
        }
        Long duplicated = providerMapper.selectCount(wrapper);
        if (duplicated != null && duplicated > 0) {
            throw conflict("供应商已存在（编码 + 名称唯一）: code=" + code + ", name=" + name);
        }
    }

    private void requireUrl(String field, String value) {
        if (!hasText(value)) {
            return;
        }
        String lower = value.trim().toLowerCase();
        for (String scheme : URL_SCHEMES) {
            if (lower.startsWith(scheme)) {
                return;
            }
        }
        throw badRequest(field + " 必须以 http(s):// 或 ws(s):// 开头: " + value);
    }

    private void requireRemark(String remark) {
        if (remark != null && remark.length() > 512) {
            throw badRequest("备注 remark 长度超出上限 512");
        }
    }

    private void requireFlag(String field, Integer value) {
        if (value != null && value != 0 && value != 1) {
            throw badRequest(field + " 只能为 0 或 1: " + value);
        }
    }

    private static String normalizeCode(String code) {
        return code == null ? null : code.trim().toUpperCase();
    }

    private static boolean sameValue(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
