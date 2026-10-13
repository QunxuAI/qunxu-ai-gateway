package tech.qunxu.module.gateway.config;

import tech.qunxu.module.gateway.guardrail.GuardrailPolicyService;
import tech.qunxu.module.gateway.guardrail.SensitiveWordService;
import tech.qunxu.module.gateway.moderation.ModerationPolicyService;
import tech.qunxu.module.gateway.persistence.service.ConfigService;
import tech.qunxu.module.gateway.ratelimit.RateLimitRuleService;
import tech.qunxu.module.gateway.security.ApiKeySnapshotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 配置本地缓存失效器：把「供应商/通道/逻辑模型」「安全围栏策略」「内容审计策略」「敏感词库」
 * 「限流配额规则」五类 Caffeine/快照缓存的失效收敛到一处，供管理接口写入后与 Redis pub/sub 收到远端通知后共同调用。
 *
 * <p>前三类为纯内存失效（同步、瞬时）；敏感词库需<b>重建 Trie</b>（读数据库启用词条并与种子库合并），
 * 限流配额规则需<b>重建快照</b>（读数据库启用规则），均返回 {@link Mono}。
 * 重建失败已由各自服务兜底为「沿用现有快照」，因此本方法永不失败，可安全 fire-and-forget。</p>
 */
@Component
public class ConfigCacheInvalidator {

    private static final Logger log = LoggerFactory.getLogger(ConfigCacheInvalidator.class);

    private final ConfigService configService;
    private final GuardrailPolicyService guardrailPolicyService;
    private final ModerationPolicyService moderationPolicyService;
    private final SensitiveWordService sensitiveWordService;
    private final RateLimitRuleService rateLimitRuleService;
    private final ApiKeySnapshotService apiKeySnapshotService;

    public ConfigCacheInvalidator(ConfigService configService,
                                  GuardrailPolicyService guardrailPolicyService,
                                  ModerationPolicyService moderationPolicyService,
                                  SensitiveWordService sensitiveWordService,
                                  RateLimitRuleService rateLimitRuleService,
                                  ApiKeySnapshotService apiKeySnapshotService) {
        this.configService = configService;
        this.guardrailPolicyService = guardrailPolicyService;
        this.moderationPolicyService = moderationPolicyService;
        this.sensitiveWordService = sensitiveWordService;
        this.rateLimitRuleService = rateLimitRuleService;
        this.apiKeySnapshotService = apiKeySnapshotService;
    }

    /**
     * 失效本实例的全部配置缓存。远端 pub/sub 通知无法（也不必）区分变更类别，故统一全量失效。
     *
     * @param reason 触发原因（如 {@code local:provider}、{@code remote:channel}），仅用于日志
     */
    public Mono<Void> invalidateLocal(String reason) {
        invalidateConfig(reason);
        invalidateGuardrail(reason);
        invalidateModeration(reason);
        return refreshSensitiveWords(reason)
                .then(refreshRateLimitRules(reason))
                .then(refreshApiKeys(reason));
    }

    /** 失效供应商/逻辑模型/通道路由缓存（纯内存，瞬时）。 */
    public void invalidateConfig(String reason) {
        invalidateQuietly(reason, "配置", configService::invalidateAll);
    }

    /** 失效安全围栏策略缓存（纯内存，瞬时）。 */
    public void invalidateGuardrail(String reason) {
        invalidateQuietly(reason, "围栏策略", guardrailPolicyService::invalidate);
    }

    /** 失效内容审计策略缓存（纯内存，瞬时）。 */
    public void invalidateModeration(String reason) {
        invalidateQuietly(reason, "审计策略", moderationPolicyService::invalidate);
    }

    /** 重建限流配额规则快照（需读库），失败时沿用现有快照，因此返回的 {@link Mono} 永不失败。 */
    public Mono<Void> refreshRateLimitRules(String reason) {
        return rateLimitRuleService.refresh()
                .doOnNext(size -> {
                    if (log.isDebugEnabled()) {
                        log.debug("限流规则快照已重建({})，共 {} 条", reason, size);
                    }
                })
                .onErrorResume(e -> {
                    log.warn("重建限流规则快照失败({}): {}", reason, e.toString());
                    return Mono.empty();
                })
                .then();
    }

    /**
     * 重建敏感词 Trie（需读库），失败时沿用现有快照，因此返回的 {@link Mono} 永不失败。
     *
     * @return 重建后的启用词条数
     */
    public Mono<Integer> refreshSensitiveWords(String reason) {
        return sensitiveWordService.refresh()
                .doOnNext(size -> {
                    if (log.isDebugEnabled()) {
                        log.debug("敏感词库已重建({})，共 {} 条", reason, size);
                    }
                })
                .onErrorResume(e -> {
                    log.warn("重建敏感词库失败({}): {}", reason, e.toString());
                    return Mono.empty();
                });
    }

    /**
     * 重建网关 APIKey 鉴权快照（需读库），失败时沿用现有快照，因此返回的 {@link Mono} 永不失败。
     */
    public Mono<Void> refreshApiKeys(String reason) {
        return apiKeySnapshotService.refresh()
                .doOnNext(size -> {
                    if (log.isDebugEnabled()) {
                        log.debug("APIKey 快照已重建({})，共 {} 条", reason, size);
                    }
                })
                .onErrorResume(e -> {
                    log.warn("重建 APIKey 快照失败({}): {}", reason, e.toString());
                    return Mono.empty();
                })
                .then();
    }

    private void invalidateQuietly(String reason, String label, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("失效{}缓存出错({}): {}", label, reason, e.toString());
        }
    }
}
