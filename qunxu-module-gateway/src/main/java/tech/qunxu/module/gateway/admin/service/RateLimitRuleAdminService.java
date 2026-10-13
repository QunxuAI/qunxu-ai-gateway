package tech.qunxu.module.gateway.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import tech.qunxu.module.gateway.admin.AdminPage;
import tech.qunxu.module.gateway.config.ConfigVersionPublisher;
import tech.qunxu.module.gateway.config.GatewayProperties;
import tech.qunxu.module.gateway.persistence.entity.RateLimitRuleEntity;
import tech.qunxu.module.gateway.persistence.mapper.RateLimitRuleMapper;
import tech.qunxu.module.gateway.ratelimit.RateLimitRuleService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * 限流配额规则管理服务（{@code /admin/rate-limit/rules}）。
 *
 * <p>规则落库 {@code rate_limit_rule}，写入后发布 {@code rate-limit-rule} 失效通知，
 * 各实例（含本实例）经 {@code RateLimitRuleService} 重建内存快照，秒级生效且无需重启。</p>
 *
 * <p>校验要点：{@code ruleType} 决定字段语义——速率类规则用 {@code permitsPerMinute}、配额类用
 * {@code dailyTokens}；默认类规则（*_DEFAULT）不允许设 {@code target}，覆盖类必须有 {@code target}。</p>
 */
@Service
public class RateLimitRuleAdminService extends AbstractConfigAdminService<RateLimitRuleEntity> {

    /** 全部规则类型取值。 */
    public static final List<String> RULE_TYPES = List.of(
            RateLimitRuleService.CALLER_DEFAULT, RateLimitRuleService.CALLER_OVERRIDE,
            RateLimitRuleService.TENANT_OVERRIDE, RateLimitRuleService.MODEL_DEFAULT,
            RateLimitRuleService.MODEL_OVERRIDE, RateLimitRuleService.QUOTA_DEFAULT,
            RateLimitRuleService.QUOTA_CALLER, RateLimitRuleService.QUOTA_TENANT);

    /** 速率类规则类型（用每分钟许可）。 */
    private static final List<String> RATE_TYPES = List.of(
            RateLimitRuleService.CALLER_DEFAULT, RateLimitRuleService.CALLER_OVERRIDE,
            RateLimitRuleService.TENANT_OVERRIDE, RateLimitRuleService.MODEL_DEFAULT,
            RateLimitRuleService.MODEL_OVERRIDE);

    /** 配额类规则类型（用每日 Token）。 */
    private static final List<String> QUOTA_TYPES = List.of(
            RateLimitRuleService.QUOTA_DEFAULT, RateLimitRuleService.QUOTA_CALLER,
            RateLimitRuleService.QUOTA_TENANT);

    /** 默认类规则类型（不允许 target）。 */
    private static final List<String> DEFAULT_TYPES = List.of(
            RateLimitRuleService.CALLER_DEFAULT, RateLimitRuleService.MODEL_DEFAULT,
            RateLimitRuleService.QUOTA_DEFAULT);

    /** 覆盖类规则类型（必须有 target）。 */
    private static final List<String> OVERRIDE_TYPES = List.of(
            RateLimitRuleService.CALLER_OVERRIDE, RateLimitRuleService.TENANT_OVERRIDE,
            RateLimitRuleService.MODEL_OVERRIDE, RateLimitRuleService.QUOTA_CALLER,
            RateLimitRuleService.QUOTA_TENANT);

    private final RateLimitRuleMapper ruleMapper;
    private final ConfigVersionPublisher publisher;

    public RateLimitRuleAdminService(RateLimitRuleMapper ruleMapper,
                                     ConfigVersionPublisher publisher,
                                     GatewayProperties properties,
                                     ObjectMapper objectMapper) {
        super(properties, objectMapper);
        this.ruleMapper = ruleMapper;
        this.publisher = publisher;
    }

    @Override
    protected BaseMapper<RateLimitRuleEntity> mapper() {
        return ruleMapper;
    }

    @Override
    protected String resourceLabel() {
        return "限流配额规则";
    }

    /** 分页查询限流规则。{@code keyword} 模糊匹配名称，{@code ruleType}/{@code enabled} 精确过滤。 */
    public Mono<AdminPage<RateLimitRuleEntity>> query(String keyword, String ruleType, Integer enabled,
                                                      Integer page, Integer size) {
        return page(filters(keyword, ruleType, enabled).orderByAsc("rule_type").orderByAsc("target").orderByAsc("id"),
                page, size);
    }

    /** 列出全部限流规则（不分页）。 */
    public Mono<List<RateLimitRuleEntity>> queryAll(String keyword, String ruleType, Integer enabled) {
        return list(filters(keyword, ruleType, enabled).orderByAsc("rule_type").orderByAsc("target").orderByAsc("id"));
    }

    private QueryWrapper<RateLimitRuleEntity> filters(String keyword, String ruleType, Integer enabled) {
        QueryWrapper<RateLimitRuleEntity> wrapper = Wrappers.query();
        if (hasText(keyword)) {
            wrapper.like("name", keyword.trim());
        }
        if (hasText(ruleType)) {
            wrapper.eq("rule_type", ruleType.trim().toUpperCase());
        }
        if (enabled != null) {
            wrapper.eq("enabled", enabled);
        }
        return wrapper;
    }

    @Override
    protected void validateCreate(RateLimitRuleEntity entity) {
        requireText("规则名称 name", entity.getName(), 128);
        validateType(entity.getRuleType());
        validateTarget(entity.getRuleType(), entity.getTarget());
        validateValues(entity.getRuleType(), entity.getPermitsPerMinute(), entity.getDailyTokens());
        requireFlag("启用标记 enabled", entity.getEnabled());
    }

    @Override
    protected void validateUpdate(RateLimitRuleEntity patch, RateLimitRuleEntity existing) {
        if (hasText(patch.getName())) {
            requireText("规则名称 name", patch.getName(), 128);
        }
        String type = hasText(patch.getRuleType()) ? patch.getRuleType() : existing.getRuleType();
        validateType(type);
        String target = patch.getTarget() != null ? patch.getTarget() : existing.getTarget();
        validateTarget(type, target);
        Integer permits = patch.getPermitsPerMinute() != null ? patch.getPermitsPerMinute() : existing.getPermitsPerMinute();
        Long daily = patch.getDailyTokens() != null ? patch.getDailyTokens() : existing.getDailyTokens();
        validateValues(type, permits, daily);
        requireFlag("启用标记 enabled", patch.getEnabled());
    }

    @Override
    protected void prepareCreate(RateLimitRuleEntity entity) {
        entity.setName(trimmed(entity.getName()));
        entity.setRuleType(normalizeType(entity.getRuleType()));
        entity.setTarget(normalizeTarget(entity.getRuleType(), entity.getTarget()));
        if (entity.getEnabled() == null) {
            entity.setEnabled(1);
        }
        // 默认类与配额类规则缺省值兜底
        if (entity.getPermitsPerMinute() == null) {
            entity.setPermitsPerMinute(0);
        }
        if (entity.getDailyTokens() == null && QUOTA_TYPES.contains(entity.getRuleType())) {
            entity.setDailyTokens(0L);
        }
    }

    @Override
    protected void mergeForUpdate(RateLimitRuleEntity patch, RateLimitRuleEntity existing) {
        if (hasText(patch.getName())) {
            patch.setName(trimmed(patch.getName()));
        }
        String type = hasText(patch.getRuleType()) ? normalizeType(patch.getRuleType()) : existing.getRuleType();
        if (hasText(patch.getRuleType())) {
            patch.setRuleType(type);
        }
        if (patch.getTarget() != null) {
            patch.setTarget(normalizeTarget(type, patch.getTarget()));
        }
        // 规则类型与字段语义绑定：速率类不允许带 daily_tokens，配额类不允许带 permits
        if (RATE_TYPES.contains(type)) {
            patch.setDailyTokens(null);
        }
        if (QUOTA_TYPES.contains(type)) {
            patch.setPermitsPerMinute(null);
        }
    }

    @Override
    protected Mono<Void> afterChange(String action) {
        return publisher.publish(ConfigVersionPublisher.TYPE_RATE_LIMIT_RULE);
    }

    // ------------------------------------------------------------------ 校验工具

    private void validateType(String ruleType) {
        if (!hasText(ruleType)) {
            throw badRequest("规则类型 ruleType 不能为空");
        }
        requireOneOf("规则类型 ruleType", ruleType, RULE_TYPES);
    }

    private void validateTarget(String ruleType, String target) {
        String type = normalizeType(ruleType);
        if (DEFAULT_TYPES.contains(type)) {
            if (hasText(target)) {
                throw badRequest("默认类规则不允许设置 target（ruleType=" + type + "）");
            }
        } else if (!hasText(target)) {
            throw badRequest("覆盖类规则必须提供 target（限流对象标识）");
        }
    }

    private void validateValues(String ruleType, Integer permits, Long dailyTokens) {
        String type = normalizeType(ruleType);
        if (RATE_TYPES.contains(type)) {
            if (permits == null || permits < 0) {
                throw badRequest("速率类规则必须提供 permitsPerMinute（>=0，0 表示不限制）");
            }
        } else if (QUOTA_TYPES.contains(type)) {
            if (dailyTokens == null || dailyTokens < 0) {
                throw badRequest("配额类规则必须提供 dailyTokens（>=0，0 表示不限制）");
            }
        }
    }

    private void requireFlag(String field, Integer value) {
        if (value != null && value != 0 && value != 1) {
            throw badRequest(field + " 只能为 0 或 1: " + value);
        }
    }

    private static String normalizeType(String ruleType) {
        return hasText(ruleType) ? ruleType.trim().toUpperCase() : "";
    }

    private static String normalizeTarget(String ruleType, String target) {
        String type = normalizeType(ruleType);
        return DEFAULT_TYPES.contains(type) ? null : (hasText(target) ? target.trim() : null);
    }
}
