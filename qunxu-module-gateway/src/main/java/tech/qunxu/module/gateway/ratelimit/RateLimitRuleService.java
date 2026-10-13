package tech.qunxu.module.gateway.ratelimit;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import tech.qunxu.module.gateway.config.GatewayProperties;
import tech.qunxu.module.gateway.persistence.entity.RateLimitRuleEntity;
import tech.qunxu.module.gateway.persistence.mapper.RateLimitRuleMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 限流配额规则服务：把数据库 {@code rate_limit_rule} 表的启用规则载入<b>不可变内存快照</b>
 * （{@link AtomicReference} 原子替换，请求热路径零 I/O、无锁），供 {@code RateLimitFilter} 与
 * {@code QuotaService} 解析每分钟许可与每日 Token 上限。
 *
 * <p>与敏感词库同款「快照 + 重建」模式：写入后经 {@code ConfigCacheInvalidator} 重建本实例快照，
 * 并经 Redis 频道广播让其它实例同步重建；数据库不可用或未建表（单测/无表环境）时保持空快照，
 * 解析回退到 {@code gateway.rate-limit.*}/{@code gateway.quota.*} 的 yaml 取值——即升级前的既有行为。</p>
 *
 * <p>解析语义（快照生效时）：覆盖优先于默认，默认行被停用/删除时回退 yaml 默认值；因此
 * 「删除默认规则」等价于「恢复出厂配置」，不会让限流失效。</p>
 */
@Service
public class RateLimitRuleService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitRuleService.class);

    /** 规则类型常量。 */
    public static final String CALLER_DEFAULT = "CALLER_DEFAULT";
    public static final String CALLER_OVERRIDE = "CALLER_OVERRIDE";
    public static final String TENANT_OVERRIDE = "TENANT_OVERRIDE";
    public static final String MODEL_DEFAULT = "MODEL_DEFAULT";
    public static final String MODEL_OVERRIDE = "MODEL_OVERRIDE";
    public static final String QUOTA_DEFAULT = "QUOTA_DEFAULT";
    public static final String QUOTA_CALLER = "QUOTA_CALLER";
    public static final String QUOTA_TENANT = "QUOTA_TENANT";

    /** 不可变快照；{@code loaded=false} 时所有解析回退 yaml。 */
    private record Rules(boolean loaded,
                         int defaultCallerPermits,
                         Map<String, Integer> callerOverrides,
                         Map<String, Integer> tenantOverrides,
                         int defaultModelPermits,
                         Map<String, Integer> modelOverrides,
                         long defaultDailyTokens,
                         Map<String, Long> quotaCallerOverrides,
                         Map<String, Long> quotaTenantOverrides) {
        static Rules empty() {
            return new Rules(false, 0, Map.of(), Map.of(), 0, Map.of(), 0L, Map.of(), Map.of());
        }
    }

    private static final Rules EMPTY = Rules.empty();

    private final RateLimitRuleMapper ruleMapper;
    private final GatewayProperties properties;
    private final AtomicReference<Rules> snapshot = new AtomicReference<>(EMPTY);

    public RateLimitRuleService(RateLimitRuleMapper ruleMapper, GatewayProperties properties) {
        this.ruleMapper = ruleMapper;
        this.properties = properties;
    }

    /** 应用就绪后首次载入（表不存在/不可用时静默保持空快照，回退 yaml）。 */
    @EventListener(ApplicationReadyEvent.class)
    public void loadOnReady() {
        refresh().subscribe(null, e -> log.warn("限流规则首次载入失败: {}", e.toString()));
    }

    /** 重建快照（读库，阻塞 JDBC 卸载到 boundedElastic）；失败保持现有快照。 */
    public Mono<Integer> refresh() {
        return Mono.fromCallable(this::loadSync)
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> {
                    log.warn("重建限流规则快照失败，沿用现有配置（{}）: {}",
                            snapshot.get().loaded() ? "数据库规则" : "yaml 默认", e.toString());
                    return Mono.just(snapshot.get().loaded() ? 1 : 0);
                });
    }

    private int loadSync() {
        List<RateLimitRuleEntity> rows = ruleMapper.selectList(
                Wrappers.<RateLimitRuleEntity>query().eq("enabled", 1).orderByAsc("id"));
        int defaultCaller = intOf(yamlRateLimit().getDefaultPermitsPerMinute());
        int defaultModel = intOf(yamlRateLimit().getDefaultModelPermitsPerMinute());
        long defaultDaily = yamlQuota().getDefaultDailyTokens();
        Map<String, Integer> caller = new LinkedHashMap<>();
        Map<String, Integer> tenant = new LinkedHashMap<>();
        Map<String, Integer> model = new LinkedHashMap<>();
        Map<String, Long> quotaCaller = new LinkedHashMap<>();
        Map<String, Long> quotaTenant = new LinkedHashMap<>();

        for (RateLimitRuleEntity row : rows) {
            // 双保险：除 SQL 的 enabled=1 过滤外，代码层再校验一次，停用行绝不进入快照
            if (row.getEnabled() == null || row.getEnabled() != 1) {
                continue;
            }
            String type = row.getRuleType() == null ? "" : row.getRuleType().trim().toUpperCase();
            String target = row.getTarget() == null ? "" : row.getTarget().trim();
            switch (type) {
                case CALLER_DEFAULT -> defaultCaller = intOf(row.getPermitsPerMinute());
                case MODEL_DEFAULT -> defaultModel = intOf(row.getPermitsPerMinute());
                case QUOTA_DEFAULT -> defaultDaily = longOf(row.getDailyTokens());
                case CALLER_OVERRIDE -> caller.put(target, intOf(row.getPermitsPerMinute()));
                case TENANT_OVERRIDE -> tenant.put(target, intOf(row.getPermitsPerMinute()));
                case MODEL_OVERRIDE -> model.put(target, intOf(row.getPermitsPerMinute()));
                case QUOTA_CALLER -> quotaCaller.put(target, longOf(row.getDailyTokens()));
                case QUOTA_TENANT -> quotaTenant.put(target, longOf(row.getDailyTokens()));
                default -> log.debug("忽略未知限流规则类型 {}: id={}", row.getRuleType(), row.getId());
            }
        }
        snapshot.set(new Rules(true, defaultCaller, Map.copyOf(caller), Map.copyOf(tenant),
                defaultModel, Map.copyOf(model), defaultDaily,
                Map.copyOf(quotaCaller), Map.copyOf(quotaTenant)));
        return rows.size();
    }

    // ------------------------------------------------------------ 解析（热路径，零 I/O）

    /** 调用人级每分钟许可：调用人覆盖 &gt; 租户覆盖 &gt; 默认。快照未就绪时回退 yaml。 */
    public int callerPermits(String callerId, String tenant) {
        Rules r = snapshot.get();
        if (!r.loaded()) {
            Integer byCaller = yamlRateLimit().getCallerOverrides().get(callerId);
            if (byCaller != null) return byCaller;
            return orDefault(yamlRateLimit().getTenantOverrides().get(tenant),
                    yamlRateLimit().getDefaultPermitsPerMinute());
        }
        Integer byCaller = r.callerOverrides().get(callerId);
        if (byCaller != null) return byCaller;
        return orDefault(r.tenantOverrides().get(tenant), r.defaultCallerPermits());
    }

    /** 模型级每分钟许可：模型覆盖 &gt; 默认。 */
    public int modelPermits(String model) {
        Rules r = snapshot.get();
        if (!r.loaded()) {
            return orDefault(yamlRateLimit().getModelOverrides().get(model),
                    yamlRateLimit().getDefaultModelPermitsPerMinute());
        }
        return orDefault(r.modelOverrides().get(model), r.defaultModelPermits());
    }

    /**
     * 每日 Token 限额：{@code tenantScope=true} 时按「租户覆盖 &gt; 默认」；否则按
     * 「调用人覆盖 &gt; 租户覆盖 &gt; 默认」。返回 {@code <=0} 表示不限额。
     */
    public long dailyTokens(String callerId, String tenant, boolean tenantScope) {
        Rules r = snapshot.get();
        if (!r.loaded()) {
            GatewayProperties.Quota q = yamlQuota();
            if (tenantScope) {
                return orDefault(q.getTenantOverrides().get(normalize(tenant)), q.getDefaultDailyTokens());
            }
            Long byCaller = q.getCallerOverrides().get(normalize(callerId));
            if (byCaller != null) return byCaller;
            return orDefault(q.getTenantOverrides().get(normalize(tenant)), q.getDefaultDailyTokens());
        }
        if (tenantScope) {
            return orDefault(r.quotaTenantOverrides().get(normalize(tenant)), r.defaultDailyTokens());
        }
        Long byCaller = r.quotaCallerOverrides().get(normalize(callerId));
        if (byCaller != null) return byCaller;
        return orDefault(r.quotaTenantOverrides().get(normalize(tenant)), r.defaultDailyTokens());
    }

    /** 全量规则（含停用，按类型/目标/ID 升序），供管理端摊平视图使用；数据库不可用时返回空列表。 */
    public Mono<List<RateLimitRuleEntity>> allRules() {
        return Mono.fromCallable(() -> ruleMapper.selectList(
                        Wrappers.<RateLimitRuleEntity>query()
                                .orderByAsc("rule_type").orderByAsc("target").orderByAsc("id")))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> {
                    log.warn("读取限流规则列表失败: {}", e.toString());
                    return Mono.just(List.of());
                });
    }

    /** 当前快照是否已从数据库载入（供管理端判定显示来源）。 */
    public boolean isLoaded() {
        return snapshot.get().loaded();
    }

    /** 快照规则行数（管理端展示用；未载入返回 0）。 */
    public int size() {
        Rules r = snapshot.get();
        return r.loaded()
                ? 1 + r.callerOverrides().size() + r.tenantOverrides().size()
                  + 1 + r.modelOverrides().size() + 1 + r.quotaCallerOverrides().size()
                  + r.quotaTenantOverrides().size()
                : 0;
    }

    // ------------------------------------------------------------ 工具

    private GatewayProperties.RateLimit yamlRateLimit() {
        return properties.getRateLimit();
    }

    private GatewayProperties.Quota yamlQuota() {
        return properties.getQuota();
    }

    private static int intOf(Integer v) {
        return v == null ? 0 : v;
    }

    private static long longOf(Long v) {
        return v == null ? 0L : v;
    }

    private static int orDefault(Integer override, int defaultValue) {
        return override != null ? override : defaultValue;
    }

    private static long orDefault(Long override, long defaultValue) {
        return override != null ? override : defaultValue;
    }

    private static String normalize(String s) {
        return s == null ? "" : s;
    }
}
