package tech.qunxu.module.gateway.moderation;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import tech.qunxu.module.gateway.common.util.JsonUtils;
import tech.qunxu.module.gateway.config.GatewayProperties;
import tech.qunxu.module.gateway.persistence.entity.ModerationPolicyEntity;
import tech.qunxu.module.gateway.persistence.mapper.ModerationPolicyMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 内容审计策略服务：加载启用的 {@code moderation_policy}，按作用域（GLOBAL/TENANT/MODEL）解析出适用于当前请求的
 * 提供方、执行动作与合并后的提供方配置。
 *
 * <p>选取规则：适用策略按「作用域特异性升序、同域内 priority 升序」排序，<b>最高优先级者</b>（最具体、priority 最大）
 * 决定 {@code provider} 与 {@code action}；所有适用策略的 {@code config_json} 依序合并（后者覆盖前者）。
 * 无适用策略时回退 {@code gateway.moderation.default-provider}（默认 builtin）与默认动作 {@code BLOCK}。</p>
 *
 * <p>{@code config_json} 约定 schema（按提供方分组，均可选）：
 * <pre>{@code
 * { "aliyun": { "endpoint": "...", "appCode": "...", "scene": "antispam" },
 *   "llm":    { "endpoint": "...", "model": "...", "apiKey": "..." } }
 * }</pre>
 * 查询经 {@link Schedulers#boundedElastic()} 卸载并以 Caffeine 短 TTL 缓存，请求路径不阻塞事件循环。</p>
 */
@Service
public class ModerationPolicyService {

    private static final Logger log = LoggerFactory.getLogger(ModerationPolicyService.class);
    private static final Duration CACHE_TTL = Duration.ofSeconds(30);
    private static final String ALL_KEY = "__all_enabled__";
    private static final String DEFAULT_ACTION = "BLOCK";

    private final ModerationPolicyMapper policyMapper;
    private final GatewayProperties properties;
    private final ObjectMapper objectMapper;

    private final Cache<String, List<ModerationPolicyEntity>> cache = Caffeine.newBuilder()
            .expireAfterWrite(CACHE_TTL)
            .maximumSize(1)
            .build();

    public ModerationPolicyService(ModerationPolicyMapper policyMapper,
                                   GatewayProperties properties,
                                   ObjectMapper objectMapper) {
        this.policyMapper = policyMapper;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** 解析适用于当前请求的审计策略（响应式）。 */
    public Mono<ResolvedModeration> resolve(String tenant, String logicalModel) {
        return loadPolicies().map(policies -> build(policies, tenant, logicalModel));
    }

    /** 失效本地策略缓存（策略写入后调用）。 */
    public void invalidate() {
        cache.invalidateAll();
    }

    private Mono<List<ModerationPolicyEntity>> loadPolicies() {
        return Mono.fromCallable(() -> {
                    List<ModerationPolicyEntity> cached = cache.getIfPresent(ALL_KEY);
                    if (cached != null) {
                        return cached;
                    }
                    List<ModerationPolicyEntity> rows = policyMapper.selectList(
                            Wrappers.<ModerationPolicyEntity>lambdaQuery()
                                    .eq(ModerationPolicyEntity::getEnabled, 1));
                    List<ModerationPolicyEntity> result = rows != null ? rows : List.<ModerationPolicyEntity>of();
                    cache.put(ALL_KEY, result);
                    return result;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> {
                    log.warn("加载内容审计策略失败，使用默认策略: {}", e.toString());
                    return Mono.just(List.of());
                });
    }

    private ResolvedModeration build(List<ModerationPolicyEntity> policies, String tenant, String logicalModel) {
        String defaultProvider = properties.getModeration().getDefaultProvider();
        Map<String, Object> config = new HashMap<>();
        if (policies == null || policies.isEmpty()) {
            return new ResolvedModeration(defaultProvider, DEFAULT_ACTION, config);
        }
        List<ModerationPolicyEntity> applicable = new ArrayList<>();
        for (ModerationPolicyEntity p : policies) {
            if (p != null && applies(p, tenant, logicalModel)) {
                applicable.add(p);
            }
        }
        if (applicable.isEmpty()) {
            return new ResolvedModeration(defaultProvider, DEFAULT_ACTION, config);
        }
        applicable.sort(Comparator
                .comparingInt(this::specificity)
                .thenComparingInt(p -> p.getPriority() == null ? 0 : p.getPriority()));
        for (ModerationPolicyEntity p : applicable) {
            mergeConfig(config, p.getConfigJson());
        }
        ModerationPolicyEntity deciding = applicable.get(applicable.size() - 1);
        String provider = deciding.getProvider() == null || deciding.getProvider().isBlank()
                ? defaultProvider : deciding.getProvider().trim();
        String action = deciding.getAction() == null || deciding.getAction().isBlank()
                ? DEFAULT_ACTION : deciding.getAction().trim().toUpperCase();
        return new ResolvedModeration(provider, action, config);
    }

    private boolean applies(ModerationPolicyEntity p, String tenant, String logicalModel) {
        String scope = p.getScope() == null ? "GLOBAL" : p.getScope().trim().toUpperCase();
        String value = p.getScopeValue();
        return switch (scope) {
            case "TENANT" -> tenant != null && tenant.equalsIgnoreCase(value);
            case "MODEL" -> logicalModel != null && logicalModel.equalsIgnoreCase(value);
            default -> true;
        };
    }

    private int specificity(ModerationPolicyEntity p) {
        String scope = p.getScope() == null ? "GLOBAL" : p.getScope().trim().toUpperCase();
        return switch (scope) {
            case "MODEL" -> 2;
            case "TENANT" -> 1;
            default -> 0;
        };
    }

    /** 按约定 schema 显式解析 config_json（避免依赖对象字段迭代 API，跨 Jackson 版本安全）。 */
    private void mergeConfig(Map<String, Object> config, String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(configJson);
        } catch (RuntimeException e) {
            log.warn("解析内容审计策略 config_json 失败: {}", e.toString());
            return;
        }
        if (root == null || !root.isObject()) {
            return;
        }
        JsonNode aliyun = root.path("aliyun");
        putStr(config, aliyun, "endpoint", "aliyun.endpoint");
        putStr(config, aliyun, "appCode", "aliyun.appCode");
        putStr(config, aliyun, "scene", "aliyun.scene");
        putInt(config, aliyun, "timeoutMs", "aliyun.timeoutMs");

        JsonNode llm = root.path("llm");
        putStr(config, llm, "endpoint", "llm.endpoint");
        putStr(config, llm, "model", "llm.model");
        putStr(config, llm, "apiKey", "llm.apiKey");
    }

    private void putStr(Map<String, Object> config, JsonNode node, String field, String key) {
        String v = JsonUtils.textOrNullEmpty(node, field);
        if (v != null) {
            config.put(key, v);
        }
    }

    private void putInt(Map<String, Object> config, JsonNode node, String field, String key) {
        Integer v = JsonUtils.integer(node, field);
        if (v != null) {
            config.put(key, v);
        }
    }
}
