package tech.qunxu.module.gateway.config;

import tech.qunxu.module.gateway.persistence.service.ConfigService;
import tech.qunxu.module.gateway.security.ApiKeySnapshotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 配置变更发布者：管理接口写入配置后调用，负责「本实例立即失效 + 通知其它实例失效」。
 *
 * <p>网关为无状态多实例部署，供应商/通道/逻辑模型/策略等配置由 {@link ConfigService}
 * 与策略服务做 Caffeine 本地缓存（TTL 数十秒）。若仅依赖 TTL 过期，最坏情况下新配置需等待一个
 * 缓存周期才生效；故写入后主动向 Redis 频道 {@value #CHANNEL} 发布失效通知，
 * 各实例的 {@link ConfigVersionSubscriber} 收到后立即清空本地缓存，实现秒级一致。</p>
 *
 * <p>发布采用<b>失败不阻断</b>策略：Redis 不可用或发布异常时仅告警，管理写入本身仍视为成功——
 * 本地缓存已失效，最迟在 TTL 后其它实例亦会自然收敛。</p>
 */
@Component
public class ConfigVersionPublisher {

    /** 配置失效通知频道，与 {@link ConfigVersionSubscriber} 共用。 */
    public static final String CHANNEL = "gw:config:version";

    /** 变更类型：供应商。 */
    public static final String TYPE_PROVIDER = "provider";
    /** 变更类型：逻辑模型。 */
    public static final String TYPE_LOGICAL_MODEL = "logical-model";
    /** 变更类型：模型通道。 */
    public static final String TYPE_CHANNEL = "channel";
    /** 变更类型：安全围栏策略。 */
    public static final String TYPE_GUARDRAIL = "guardrail";
    /** 变更类型：内容审计策略。 */
    public static final String TYPE_MODERATION = "moderation";
    /** 变更类型：敏感词库。 */
    public static final String TYPE_SENSITIVE_WORD = "sensitive-word";
    /** 变更类型：限流配额规则。 */
    public static final String TYPE_RATE_LIMIT_RULE = "rate-limit-rule";
    /** 变更类型：网关 APIKey。 */
    public static final String TYPE_API_KEY = "api-key";

    private static final Logger log = LoggerFactory.getLogger(ConfigVersionPublisher.class);

    private final ConfigCacheInvalidator invalidator;
    private final ApiKeySnapshotService apiKeySnapshotService;
    private final ObjectProvider<ReactiveStringRedisTemplate> redisTemplateProvider;

    public ConfigVersionPublisher(ConfigCacheInvalidator invalidator,
                                  ApiKeySnapshotService apiKeySnapshotService,
                                  ObjectProvider<ReactiveStringRedisTemplate> redisTemplateProvider) {
        this.invalidator = invalidator;
        this.apiKeySnapshotService = apiKeySnapshotService;
        this.redisTemplateProvider = redisTemplateProvider;
    }

    /**
     * 发布一次配置变更：先失效本实例对应类别的缓存，再向 Redis 频道广播。
     *
     * @param type 变更类型，取值见本类 {@code TYPE_*} 常量；未知类型按全量失效处理
     * @return 完成信号；本地缓存失效与 Redis 发布均在其中执行，永不失败
     */
    public Mono<Void> publish(String type) {
        String kind = (type == null || type.isBlank()) ? "config" : type.trim();
        return invalidateLocal(kind)
                .then(publishToRedis(kind))
                .onErrorResume(e -> {
                    log.warn("发布配置失效通知失败({})，其它实例将在缓存过期后收敛: {}", kind, e.toString());
                    return Mono.empty();
                });
    }

    /** 异步发布变体：订阅后立即返回，适用于不便返回 {@link Mono} 的调用点。 */
    public void publishAsync(String type) {
        publish(type).subscribeOn(Schedulers.boundedElastic())
                .subscribe(null, e -> log.warn("异步发布配置失效通知出错: {}", e.toString()));
    }

    /** 仅失效本实例缓存，不广播（供无需多实例同步的内部调用使用）。 */
    public Mono<Void> invalidateLocal(String kind) {
        return switch (kind) {
            case TYPE_PROVIDER, TYPE_LOGICAL_MODEL, TYPE_CHANNEL -> {
                invalidator.invalidateConfig("local:" + kind);
                yield Mono.empty();
            }
            case TYPE_GUARDRAIL -> {
                invalidator.invalidateGuardrail("local:" + kind);
                yield Mono.empty();
            }
            case TYPE_MODERATION -> {
                invalidator.invalidateModeration("local:" + kind);
                yield Mono.empty();
            }
            case TYPE_SENSITIVE_WORD -> invalidator.refreshSensitiveWords("local:" + kind).then();
            case TYPE_RATE_LIMIT_RULE -> invalidator.refreshRateLimitRules("local:" + kind);
            case TYPE_API_KEY -> apiKeySnapshotService.refresh().then();
            default -> invalidator.invalidateLocal("local:" + kind);
        };
    }

    private Mono<Void> publishToRedis(String kind) {
        ReactiveStringRedisTemplate template = redisTemplateProvider.getIfAvailable();
        if (template == null) {
            if (log.isDebugEnabled()) {
                log.debug("未启用 Redis，配置变更({})仅本实例立即生效", kind);
            }
            return Mono.empty();
        }
        String payload = kind + ':' + System.currentTimeMillis();
        return template.convertAndSend(CHANNEL, payload)
                .doOnNext(receivers -> log.info("已发布配置失效通知 {} -> {} 个订阅者", payload, receivers))
                .onErrorResume(e -> {
                    log.warn("向频道 {} 发布配置变更({})失败: {}", CHANNEL, kind, e.toString());
                    return Mono.empty();
                })
                .then();
    }
}
