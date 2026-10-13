package tech.qunxu.module.gateway.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import tech.qunxu.module.gateway.admin.AdminPage;
import tech.qunxu.module.gateway.config.ConfigVersionPublisher;
import tech.qunxu.module.gateway.config.GatewayProperties;
import tech.qunxu.module.gateway.moderation.ModerationProvider;
import tech.qunxu.module.gateway.persistence.entity.ModerationPolicyEntity;
import tech.qunxu.module.gateway.persistence.mapper.ModerationPolicyMapper;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 内容审计策略管理服务（{@code /admin/moderation-policies}）。
 *
 * <p>{@code provider} 字段的合法取值来自运行时<b>实际注册</b>的 {@link ModerationProvider} Bean 名称集合
 * （而非硬编码列表），因此新增一个 SPI 实现即自动成为可选提供方；写坏的 provider 会让
 * {@code ModerationService} 静默回退到默认实现，故在入口即拦截。</p>
 *
 * <p><b>安全提示</b>：{@code config_json} 可能包含提供方凭据（如 llm 审核模型的 apiKey）。
 * 本服务不会将其写入任何日志；管理接口本身受 {@code /admin/**} 鉴权保护。</p>
 */
@Service
public class ModerationPolicyAdminService extends AbstractConfigAdminService<ModerationPolicyEntity> {

    /** 作用域取值，与围栏策略一致。 */
    public static final List<String> SCOPES = List.of("GLOBAL", "TENANT", "MODEL");
    /** 命中动作取值。 */
    public static final List<String> ACTIONS = List.of("BLOCK", "REVIEW", "PASS");

    private final ModerationPolicyMapper policyMapper;
    private final ConfigVersionPublisher publisher;
    private final Set<String> providerNames;

    public ModerationPolicyAdminService(ModerationPolicyMapper policyMapper,
                                        ConfigVersionPublisher publisher,
                                        List<ModerationProvider> providers,
                                        GatewayProperties properties,
                                        ObjectMapper objectMapper) {
        super(properties, objectMapper);
        this.policyMapper = policyMapper;
        this.publisher = publisher;
        this.providerNames = collectProviderNames(providers);
    }

    private static Set<String> collectProviderNames(List<ModerationProvider> providers) {
        Set<String> names = new TreeSet<>();
        if (providers != null) {
            for (ModerationProvider p : providers) {
                if (p != null && p.name() != null && !p.name().isBlank()) {
                    names.add(p.name().trim());
                }
            }
        }
        return Set.copyOf(names);
    }

    /** 运行时已注册的审计提供方名称，供管理界面下拉与 {@code GET /admin/moderation-policies/providers} 使用。 */
    public Set<String> availableProviders() {
        return providerNames;
    }

    @Override
    protected BaseMapper<ModerationPolicyEntity> mapper() {
        return policyMapper;
    }

    @Override
    protected String resourceLabel() {
        return "内容审计策略";
    }

    /** 分页查询审计策略。{@code keyword} 模糊匹配名称，{@code scope}/{@code provider}/{@code enabled} 精确过滤。 */
    public Mono<AdminPage<ModerationPolicyEntity>> query(String keyword, String scope, String provider,
                                                         Integer enabled, Integer page, Integer size) {
        return page(filters(keyword, scope, provider, enabled)
                .orderByAsc("scope").orderByAsc("priority").orderByAsc("id"), page, size);
    }

    /** 列出全部审计策略（不分页）。 */
    public Mono<List<ModerationPolicyEntity>> queryAll(String keyword, String scope, String provider,
                                                       Integer enabled) {
        return list(filters(keyword, scope, provider, enabled)
                .orderByAsc("scope").orderByAsc("priority").orderByAsc("id"));
    }

    private QueryWrapper<ModerationPolicyEntity> filters(String keyword, String scope, String provider,
                                                         Integer enabled) {
        QueryWrapper<ModerationPolicyEntity> wrapper = Wrappers.query();
        if (hasText(keyword)) {
            wrapper.like("name", keyword.trim());
        }
        if (hasText(scope)) {
            wrapper.eq("scope", scope.trim().toUpperCase());
        }
        if (hasText(provider)) {
            wrapper.eq("provider", provider.trim());
        }
        if (enabled != null) {
            wrapper.eq("enabled", enabled);
        }
        return wrapper;
    }

    @Override
    protected void validateCreate(ModerationPolicyEntity entity) {
        requireText("策略名称 name", entity.getName(), 128);
        String scope = entity.getScope() == null ? "GLOBAL" : entity.getScope();
        requireOneOf("作用域 scope", scope, SCOPES);
        requireScopeValue(scope, entity.getScopeValue());
        requireProvider(entity.getProvider());
        requireOneOf("命中动作 action", entity.getAction(), ACTIONS);
        requireJson("提供方配置 config_json", entity.getConfigJson());
        requireFlag("启用标记 enabled", entity.getEnabled());
    }

    @Override
    protected void validateUpdate(ModerationPolicyEntity patch, ModerationPolicyEntity existing) {
        if (hasText(patch.getName())) {
            requireText("策略名称 name", patch.getName(), 128);
        }
        requireOneOf("作用域 scope", patch.getScope(), SCOPES);
        String scope = hasText(patch.getScope()) ? patch.getScope() : existing.getScope();
        String scopeValue = patch.getScopeValue() != null ? patch.getScopeValue() : existing.getScopeValue();
        requireScopeValue(scope == null ? "GLOBAL" : scope, scopeValue);
        requireProvider(patch.getProvider());
        requireOneOf("命中动作 action", patch.getAction(), ACTIONS);
        requireJson("提供方配置 config_json", patch.getConfigJson());
        requireFlag("启用标记 enabled", patch.getEnabled());
    }

    @Override
    protected void prepareCreate(ModerationPolicyEntity entity) {
        entity.setName(trimmed(entity.getName()));
        entity.setScope(normalizeScope(entity.getScope()));
        entity.setScopeValue(normalizeScopeValue(entity.getScope(), entity.getScopeValue()));
        if (!hasText(entity.getProvider())) {
            entity.setProvider(properties.getModeration().getDefaultProvider());
        } else {
            entity.setProvider(entity.getProvider().trim());
        }
        entity.setAction(hasText(entity.getAction()) ? entity.getAction().trim().toUpperCase() : "BLOCK");
        if (entity.getEnabled() == null) {
            entity.setEnabled(1);
        }
        if (entity.getPriority() == null) {
            entity.setPriority(100);
        }
    }

    @Override
    protected void mergeForUpdate(ModerationPolicyEntity patch, ModerationPolicyEntity existing) {
        if (hasText(patch.getName())) {
            patch.setName(trimmed(patch.getName()));
        }
        String scope = hasText(patch.getScope()) ? normalizeScope(patch.getScope()) : existing.getScope();
        if (hasText(patch.getScope())) {
            patch.setScope(scope);
        }
        if ("GLOBAL".equals(scope)) {
            // 切回 GLOBAL 时清空 scope_value；MyBatis-Plus 默认跳过 null 字段，故以空串强制写入
            if (existing.getScopeValue() != null || patch.getScopeValue() != null) {
                patch.setScopeValue("");
            }
        } else if (patch.getScopeValue() != null) {
            patch.setScopeValue(trimmed(patch.getScopeValue()));
        }
        if (hasText(patch.getProvider())) {
            patch.setProvider(patch.getProvider().trim());
        }
        if (hasText(patch.getAction())) {
            patch.setAction(patch.getAction().trim().toUpperCase());
        }
    }

    @Override
    protected Mono<Void> afterChange(String action) {
        return publisher.publish(ConfigVersionPublisher.TYPE_MODERATION);
    }

    /** 提供方必须是运行时已注册的 SPI 实现；集合为空（如测试环境未装配）时跳过校验。 */
    private void requireProvider(String provider) {
        if (!hasText(provider) || providerNames.isEmpty()) {
            return;
        }
        String normalized = provider.trim();
        for (String name : providerNames) {
            if (name.equalsIgnoreCase(normalized)) {
                return;
            }
        }
        throw badRequest("审计提供方 provider 未注册: " + provider + "，可用值 " + providerNames);
    }

    private void requireScopeValue(String scope, String scopeValue) {
        String normalized = scope == null ? "GLOBAL" : scope.trim().toUpperCase();
        if ("GLOBAL".equals(normalized)) {
            return;
        }
        if (!hasText(scopeValue)) {
            throw badRequest("作用域为 " + normalized + " 时 scope_value 不能为空");
        }
        if (scopeValue.trim().length() > 128) {
            throw badRequest("作用域取值 scope_value 长度超出上限 128");
        }
    }

    private void requireFlag(String field, Integer value) {
        if (value != null && value != 0 && value != 1) {
            throw badRequest(field + " 只能为 0 或 1: " + value);
        }
    }

    private static String normalizeScope(String scope) {
        return hasText(scope) ? scope.trim().toUpperCase() : "GLOBAL";
    }

    private static String normalizeScopeValue(String scope, String scopeValue) {
        if ("GLOBAL".equals(normalizeScope(scope))) {
            return null;
        }
        return hasText(scopeValue) ? scopeValue.trim() : scopeValue;
    }
}
