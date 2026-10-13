package tech.qunxu.module.gateway.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import tech.qunxu.module.gateway.admin.AdminPage;
import tech.qunxu.module.gateway.config.ConfigVersionPublisher;
import tech.qunxu.module.gateway.config.GatewayProperties;
import tech.qunxu.module.gateway.persistence.entity.GuardrailPolicyEntity;
import tech.qunxu.module.gateway.persistence.mapper.GuardrailPolicyMapper;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * 安全围栏策略管理服务（{@code /admin/guardrail-policies}）。
 *
 * <p>策略按 {@code scope}（GLOBAL/TENANT/MODEL）绑定，{@code GuardrailPolicyService} 在请求路径上
 * 依「特异性升序 + priority 升序」取最高优先级者决定动作，并合并所有适用策略的 {@code rules_json}。
 * 因此本服务重点校验 scope/scopeValue 的一致性与 rules_json 的可解析性——写坏一条策略会让围栏静默失效。</p>
 */
@Service
public class GuardrailPolicyAdminService extends AbstractConfigAdminService<GuardrailPolicyEntity> {

    /** 作用域取值。 */
    public static final List<String> SCOPES = List.of("GLOBAL", "TENANT", "MODEL");
    /** 命中动作取值。 */
    public static final List<String> ACTIONS = List.of("BLOCK", "MASK", "WARN");

    private final GuardrailPolicyMapper policyMapper;
    private final ConfigVersionPublisher publisher;

    public GuardrailPolicyAdminService(GuardrailPolicyMapper policyMapper,
                                       ConfigVersionPublisher publisher,
                                       GatewayProperties properties,
                                       ObjectMapper objectMapper) {
        super(properties, objectMapper);
        this.policyMapper = policyMapper;
        this.publisher = publisher;
    }

    @Override
    protected BaseMapper<GuardrailPolicyEntity> mapper() {
        return policyMapper;
    }

    @Override
    protected String resourceLabel() {
        return "安全围栏策略";
    }

    /** 分页查询围栏策略。{@code keyword} 模糊匹配名称，{@code scope}/{@code enabled} 精确过滤。 */
    public Mono<AdminPage<GuardrailPolicyEntity>> query(String keyword, String scope, Integer enabled,
                                                        Integer page, Integer size) {
        return page(filters(keyword, scope, enabled).orderByAsc("scope").orderByAsc("priority").orderByAsc("id"),
                page, size);
    }

    /** 列出全部围栏策略（不分页）。 */
    public Mono<List<GuardrailPolicyEntity>> queryAll(String keyword, String scope, Integer enabled) {
        return list(filters(keyword, scope, enabled).orderByAsc("scope").orderByAsc("priority").orderByAsc("id"));
    }

    private QueryWrapper<GuardrailPolicyEntity> filters(String keyword, String scope, Integer enabled) {
        QueryWrapper<GuardrailPolicyEntity> wrapper = Wrappers.query();
        if (hasText(keyword)) {
            wrapper.like("name", keyword.trim());
        }
        if (hasText(scope)) {
            wrapper.eq("scope", scope.trim().toUpperCase());
        }
        if (enabled != null) {
            wrapper.eq("enabled", enabled);
        }
        return wrapper;
    }

    @Override
    protected void validateCreate(GuardrailPolicyEntity entity) {
        requireText("策略名称 name", entity.getName(), 128);
        String scope = entity.getScope() == null ? "GLOBAL" : entity.getScope();
        requireOneOf("作用域 scope", scope, SCOPES);
        requireScopeValue(scope, entity.getScopeValue());
        requireAction(entity.getAction());
        requireRulesJson(entity.getRulesJson());
        requireFlag("启用标记 enabled", entity.getEnabled());
    }

    @Override
    protected void validateUpdate(GuardrailPolicyEntity patch, GuardrailPolicyEntity existing) {
        if (hasText(patch.getName())) {
            requireText("策略名称 name", patch.getName(), 128);
        }
        requireOneOf("作用域 scope", patch.getScope(), SCOPES);
        // scope 或 scopeValue 任一变化时，重新校验二者组合的完整性
        String scope = hasText(patch.getScope()) ? patch.getScope() : existing.getScope();
        String scopeValue = patch.getScopeValue() != null ? patch.getScopeValue() : existing.getScopeValue();
        requireScopeValue(scope == null ? "GLOBAL" : scope, scopeValue);
        requireAction(patch.getAction());
        requireRulesJson(patch.getRulesJson());
        requireFlag("启用标记 enabled", patch.getEnabled());
    }

    @Override
    protected void prepareCreate(GuardrailPolicyEntity entity) {
        entity.setName(trimmed(entity.getName()));
        entity.setScope(normalizeScope(entity.getScope()));
        entity.setScopeValue(normalizeScopeValue(entity.getScope(), entity.getScopeValue()));
        entity.setAction(hasText(entity.getAction()) ? entity.getAction().trim().toUpperCase() : "BLOCK");
        if (entity.getEnabled() == null) {
            entity.setEnabled(1);
        }
        if (entity.getPriority() == null) {
            entity.setPriority(100);
        }
    }

    @Override
    protected void mergeForUpdate(GuardrailPolicyEntity patch, GuardrailPolicyEntity existing) {
        if (hasText(patch.getName())) {
            patch.setName(trimmed(patch.getName()));
        }
        String scope = hasText(patch.getScope()) ? normalizeScope(patch.getScope()) : existing.getScope();
        if (hasText(patch.getScope())) {
            patch.setScope(scope);
        }
        if ("GLOBAL".equals(scope)) {
            // 切回 GLOBAL 必须清空 scope_value，否则残留值会让特异性判定错误；
            // MyBatis-Plus 默认跳过 null 字段，故以空串强制写入
            if (existing.getScopeValue() != null || patch.getScopeValue() != null) {
                patch.setScopeValue("");
            }
        } else if (patch.getScopeValue() != null) {
            patch.setScopeValue(trimmed(patch.getScopeValue()));
        }
        if (hasText(patch.getAction())) {
            patch.setAction(patch.getAction().trim().toUpperCase());
        }
    }

    @Override
    protected Mono<Void> afterChange(String action) {
        return publisher.publish(ConfigVersionPublisher.TYPE_GUARDRAIL);
    }

    /** {@code rules_json} 必须可解析且为 JSON 对象（与 {@code GuardrailPolicyService#mergeRules} 的约定一致）。 */
    private void requireRulesJson(String rulesJson) {
        if (!hasText(rulesJson)) {
            return;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(rulesJson);
        } catch (RuntimeException e) {
            throw badRequest("规则集合 rules_json 不是合法 JSON: " + e.getMessage());
        }
        if (root == null || !root.isObject()) {
            throw badRequest("规则集合 rules_json 必须是 JSON 对象，如 {\"sensitiveWord\":{\"enabled\":true}}");
        }
    }

    private void requireAction(String action) {
        requireOneOf("命中动作 action", action, ACTIONS);
    }

    /** 非全局作用域必须提供 scopeValue，否则策略永远不会命中。 */
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

    /** GLOBAL 作用域下不保留 scopeValue（新增时落库为 NULL）；其余作用域保留并 trim。 */
    private static String normalizeScopeValue(String scope, String scopeValue) {
        if ("GLOBAL".equals(normalizeScope(scope))) {
            return null;
        }
        return hasText(scopeValue) ? scopeValue.trim() : scopeValue;
    }
}
