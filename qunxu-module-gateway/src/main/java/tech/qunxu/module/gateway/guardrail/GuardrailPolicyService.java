package tech.qunxu.module.gateway.guardrail;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import tech.qunxu.module.gateway.common.util.JsonUtils;
import tech.qunxu.module.gateway.config.GatewayProperties;
import tech.qunxu.module.gateway.persistence.entity.GuardrailPolicyEntity;
import tech.qunxu.module.gateway.persistence.mapper.GuardrailPolicyMapper;
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
 * 安全围栏策略服务：加载启用的 {@code guardrail_policy} 并按作用域（GLOBAL/TENANT/MODEL）解析出适用于当前请求的
 * <b>扁平化规则配置</b>，供 {@link GuardrailEngine} 与各规则消费。
 *
 * <p>配置来源合并顺序：先以 {@code gateway.guardrail.*} 与内置默认值铺底，再按「作用域特异性升序、同域内 priority 升序」
 * 依次合并各策略的 {@code rules_json}——即<b>越具体、priority 越大的策略越后合并、优先级越高</b>，可覆盖前者。</p>
 *
 * <p>策略列表经 {@link Schedulers#boundedElastic()} 卸载查询并以 Caffeine 短 TTL 缓存（与 {@code ConfigService} 一致），
 * 请求路径不阻塞事件循环；写入后的多实例热失效（Redis pub/sub）在后续里程碑接入，TTL 兜底约束陈旧窗口。</p>
 *
 * <p>{@code rules_json} 约定 schema（均可选，缺省即用默认值）：
 * <pre>{@code
 * {
 *   "sensitiveWord": { "enabled": true },
 *   "pii":           { "enabled": true, "mask": true },
 *   "length":        { "maxInput": 32768, "maxOutput": 65536 },
 *   "promptInjection":{ "enabled": true, "action": "WARN" },
 *   "regex":         { "patterns": ["..."], "action": "BLOCK" },
 *   "topic":         { "keywords": ["..."], "action": "BLOCK" }
 * }
 * }</pre></p>
 */
@Service
public class GuardrailPolicyService {

    private static final Logger log = LoggerFactory.getLogger(GuardrailPolicyService.class);
    private static final Duration CACHE_TTL = Duration.ofSeconds(30);
    private static final String ALL_KEY = "__all_enabled__";

    private final GuardrailPolicyMapper policyMapper;
    private final GatewayProperties properties;
    private final ObjectMapper objectMapper;

    private final Cache<String, List<GuardrailPolicyEntity>> cache = Caffeine.newBuilder()
            .expireAfterWrite(CACHE_TTL)
            .maximumSize(1)
            .build();

    public GuardrailPolicyService(GuardrailPolicyMapper policyMapper,
                                  GatewayProperties properties,
                                  ObjectMapper objectMapper) {
        this.policyMapper = policyMapper;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 解析适用于当前请求的围栏配置（响应式）。
     *
     * @param tenant       租户标识（可空）
     * @param logicalModel 逻辑模型名（可空）
     * @return 扁平化配置 Map（始终非空，至少含默认值）
     */
    public Mono<Map<String, Object>> resolveConfig(String tenant, String logicalModel) {
        return loadPolicies().map(policies -> buildConfig(policies, tenant, logicalModel));
    }

    /** 失效本地策略缓存（策略写入后调用）。 */
    public void invalidate() {
        cache.invalidateAll();
    }

    private Mono<List<GuardrailPolicyEntity>> loadPolicies() {
        return Mono.fromCallable(() -> {
                    List<GuardrailPolicyEntity> cached = cache.getIfPresent(ALL_KEY);
                    if (cached != null) {
                        return cached;
                    }
                    List<GuardrailPolicyEntity> rows = policyMapper.selectList(
                            Wrappers.<GuardrailPolicyEntity>lambdaQuery()
                                    .eq(GuardrailPolicyEntity::getEnabled, 1));
                    List<GuardrailPolicyEntity> result = rows != null ? rows : List.<GuardrailPolicyEntity>of();
                    cache.put(ALL_KEY, result);
                    return result;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> {
                    log.warn("加载围栏策略失败，使用默认配置: {}", e.toString());
                    return Mono.just(List.of());
                });
    }

    private Map<String, Object> buildConfig(List<GuardrailPolicyEntity> policies, String tenant, String logicalModel) {
        Map<String, Object> config = defaults();
        if (policies == null || policies.isEmpty()) {
            return config;
        }
        List<GuardrailPolicyEntity> applicable = new ArrayList<>();
        for (GuardrailPolicyEntity p : policies) {
            if (p != null && applies(p, tenant, logicalModel)) {
                applicable.add(p);
            }
        }
        applicable.sort(Comparator
                .comparingInt(this::specificity)
                .thenComparingInt(p -> p.getPriority() == null ? 0 : p.getPriority()));
        for (GuardrailPolicyEntity p : applicable) {
            mergeRules(config, p.getRulesJson());
        }
        return config;
    }

    /** 以内置默认值 + {@code gateway.guardrail.*} 铺底。 */
    private Map<String, Object> defaults() {
        Map<String, Object> config = new HashMap<>();
        config.put("maxInputLength", properties.getGuardrail().getMaxInputLength());
        config.put("maxOutputLength", properties.getGuardrail().getMaxOutputLength());
        config.put("sensitiveWord.enabled", true);
        config.put("pii.enabled", true);
        config.put("pii.mask", true);
        config.put("promptInjection.enabled", true);
        config.put("promptInjection.action", "WARN");
        return config;
    }

    private boolean applies(GuardrailPolicyEntity p, String tenant, String logicalModel) {
        String scope = p.getScope() == null ? "GLOBAL" : p.getScope().trim().toUpperCase();
        String value = p.getScopeValue();
        return switch (scope) {
            case "TENANT" -> tenant != null && tenant.equalsIgnoreCase(value);
            case "MODEL" -> logicalModel != null && logicalModel.equalsIgnoreCase(value);
            default -> true;
        };
    }

    private int specificity(GuardrailPolicyEntity p) {
        String scope = p.getScope() == null ? "GLOBAL" : p.getScope().trim().toUpperCase();
        return switch (scope) {
            case "MODEL" -> 2;
            case "TENANT" -> 1;
            default -> 0;
        };
    }

    /** 按约定 schema 显式解析 rules_json 并覆盖到扁平配置（避免依赖对象字段迭代 API，跨 Jackson 版本安全）。 */
    private void mergeRules(Map<String, Object> config, String rulesJson) {
        if (rulesJson == null || rulesJson.isBlank()) {
            return;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(rulesJson);
        } catch (RuntimeException e) {
            log.warn("解析围栏策略 rules_json 失败: {}", e.toString());
            return;
        }
        if (root == null || !root.isObject()) {
            return;
        }
        JsonNode sensitiveWord = root.path("sensitiveWord");
        putBool(config, sensitiveWord, "enabled", "sensitiveWord.enabled");

        JsonNode pii = root.path("pii");
        putBool(config, pii, "enabled", "pii.enabled");
        putBool(config, pii, "mask", "pii.mask");

        JsonNode length = root.path("length");
        putInt(config, length, "maxInput", "maxInputLength");
        putInt(config, length, "maxOutput", "maxOutputLength");

        JsonNode injection = root.path("promptInjection");
        putBool(config, injection, "enabled", "promptInjection.enabled");
        putStr(config, injection, "action", "promptInjection.action");

        JsonNode regex = root.path("regex");
        putStrList(config, regex, "patterns", "regex.patterns");
        putStr(config, regex, "action", "regex.action");

        JsonNode topic = root.path("topic");
        putStrList(config, topic, "keywords", "topic.keywords");
        putStr(config, topic, "action", "topic.action");
    }

    private void putBool(Map<String, Object> config, JsonNode node, String field, String key) {
        Boolean v = JsonUtils.bool(node, field);
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

    private void putStr(Map<String, Object> config, JsonNode node, String field, String key) {
        String v = JsonUtils.textOrNullEmpty(node, field);
        if (v != null) {
            config.put(key, v);
        }
    }

    private void putStrList(Map<String, Object> config, JsonNode node, String field, String key) {
        JsonNode arr = JsonUtils.child(node, field);
        if (arr == null || !arr.isArray()) {
            return;
        }
        List<String> list = new ArrayList<>();
        for (JsonNode item : arr) {
            if (item != null && !item.isNull()) {
                String s = item.asText();
                if (s != null && !s.isBlank()) {
                    list.add(s);
                }
            }
        }
        if (!list.isEmpty()) {
            config.put(key, list);
        }
    }
}
