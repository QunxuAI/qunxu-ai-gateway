package tech.qunxu.module.gateway.admin;

import tech.qunxu.module.gateway.config.GatewayProperties;
import tech.qunxu.module.gateway.observability.GatewayMetrics;
import tech.qunxu.module.gateway.persistence.entity.RateLimitRuleEntity;
import tech.qunxu.module.gateway.ratelimit.RateLimitRuleService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 限流与配额的<b>只读</b>管理接口（{@code /admin/rate-limit}）。
 *
 * <p>全局开关（总开关/存储/突发系数/配额维度）仍是 {@code gateway.rate-limit.*} /
 * {@code gateway.quota.*} 属性（`application.yml` + 环境变量）；而<b>规则取值</b>（默认限流、
 * 各类覆盖、默认配额）已迁移到数据库 {@code rate_limit_rule}，由 {@code /admin/rate-limit/rules}
 * 提供增删改。本接口只做两件前端做不了的事：</p>
 * <ul>
 *   <li>把生效中的规则<b>摊平成一张可直接渲染的规则表</b>：数据库快照就绪时以数据库规则为准
 *       （行带 {@code id}，前端据此映射编辑/停用/删除），否则回退 yaml 属性摊平（只读视图）；
 *       并按 {@link GatewayProperties.RateLimit#getBurstRatio()} 算出令牌桶容量与每秒补充速率；</li>
 *   <li>从 {@link MeterRegistry} 读出 {@code gateway.ratelimit.rejections} 按 scope 的累计拒绝次数。</li>
 * </ul>
 *
 * <p>⚠️ Micrometer 计数器是<b>自 JVM 启动累计</b>，不是「今日」。响应里以 {@code sinceStartup} 命名，
 * 前端也必须如此标注，避免运维把它当成日报数据。</p>
 */
@RestController
@RequestMapping("/admin-api/gateway/rate-limit")
public class RateLimitAdminController {

    /** 拒绝计数器的 scope 标签名，与 {@code GatewayMetrics} 内部保持一致。 */
    private static final String TAG_SCOPE = "scope";

    private final GatewayProperties properties;
    private final MeterRegistry meterRegistry;
    private final RateLimitRuleService ruleService;

    public RateLimitAdminController(GatewayProperties properties, MeterRegistry meterRegistry,
                                    RateLimitRuleService ruleService) {
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.ruleService = ruleService;
    }

    /** 当前生效的限流/配额配置，含摊平后的规则表（数据库源带 id，yaml 回退源 id 为 null）。 */
    @GetMapping("/config")
    public Mono<RateLimitConfigView> config() {
        GatewayProperties.RateLimit rl = properties.getRateLimit();
        GatewayProperties.Quota quota = properties.getQuota();
        RateLimitConfigView flags = new RateLimitConfigView(
                rl.isEnabled(), rl.getStore(), rl.isFailOpen(), rl.getBurstRatio(),
                rl.getDefaultPermitsPerMinute(), rl.isPerModelEnabled(), rl.getDefaultModelPermitsPerMinute(),
                quota.isEnabled(), quota.getScope(), quota.getDefaultDailyTokens(), List.of());
        if (ruleService.isLoaded()) {
            return ruleService.allRules().map(rules -> withRules(flags, deriveFromDb(rules)));
        }
        return Mono.just(withRules(flags, deriveFromYaml(rl, quota)));
    }

    /** 限流拒绝统计（按 scope 累计，自进程启动起算）。 */
    @GetMapping("/stats")
    public Mono<RateLimitStatsView> stats() {
        return Mono.fromSupplier(this::buildStatsView);
    }

    // ------------------------------------------------------------------ 配置视图

    private static RateLimitConfigView withRules(RateLimitConfigView flags, List<RateLimitRuleView> rules) {
        return new RateLimitConfigView(flags.rateLimitEnabled(), flags.store(), flags.failOpen(),
                flags.burstRatio(), flags.defaultPermitsPerMinute(), flags.perModelEnabled(),
                flags.defaultModelPermitsPerMinute(), flags.quotaEnabled(), flags.quotaScope(),
                flags.quotaDefaultDailyTokens(), rules);
    }

    /**
     * 从数据库规则摊平视图（数据库为唯一事实源）。顺序按 rule_type/target/ID 升序，
     * 与 {@code RateLimitRuleService} 的解析优先级无关（解析只看类型）。
     */
    private List<RateLimitRuleView> deriveFromDb(List<RateLimitRuleEntity> rows) {
        GatewayProperties.RateLimit rl = properties.getRateLimit();
        GatewayProperties.Quota quota = properties.getQuota();
        double burst = rl.getBurstRatio();
        boolean rateOn = rl.isEnabled();
        boolean quotaOn = quota.isEnabled();
        List<RateLimitRuleView> rules = new ArrayList<>();
        for (RateLimitRuleEntity row : rows) {
            String type = row.getRuleType() == null ? "" : row.getRuleType().trim().toUpperCase();
            boolean enabled = row.getEnabled() != null && row.getEnabled() == 1;
            switch (type) {
                case RateLimitRuleService.CALLER_DEFAULT -> rules.add(dbRule(row.getId(), row.getName(), "CALLER",
                        "全部调用人", row.getPermitsPerMinute(), burst, enabled && rateOn));
                case RateLimitRuleService.MODEL_DEFAULT -> rules.add(dbRule(row.getId(), row.getName(), "MODEL",
                        "全部模型（调用人 + 模型维度）", row.getPermitsPerMinute(), burst,
                        enabled && rateOn && rl.isPerModelEnabled()));
                case RateLimitRuleService.CALLER_OVERRIDE, RateLimitRuleService.TENANT_OVERRIDE,
                        RateLimitRuleService.MODEL_OVERRIDE -> rules.add(dbRule(row.getId(), row.getName(),
                        type.startsWith("MODEL") ? "MODEL" : type.startsWith("TENANT") ? "TENANT" : "CALLER",
                        targetText(row.getTarget()), row.getPermitsPerMinute(), burst,
                        enabled && rateOn && (!type.startsWith("MODEL") || rl.isPerModelEnabled())));
                case RateLimitRuleService.QUOTA_DEFAULT -> rules.add(dbQuotaRule(row.getId(), row.getName(), "QUOTA",
                        "全部" + quotaScopeLabel(quota.getScope()), row.getDailyTokens(), enabled && quotaOn));
                case RateLimitRuleService.QUOTA_CALLER, RateLimitRuleService.QUOTA_TENANT ->
                        rules.add(dbQuotaRule(row.getId(), row.getName(), "QUOTA",
                                targetText(row.getTarget()), row.getDailyTokens(), enabled && quotaOn));
                default -> { /* 未知类型不渲染 */ }
            }
        }
        return List.copyOf(rules);
    }

    private static String targetText(String target) {
        return target == null || target.isBlank() ? "—" : target;
    }

    private static RateLimitRuleView dbRule(Long id, String name, String scope, String target,
                                            Integer permitsPerMinute, double burstRatio, boolean enabled) {
        int permits = permitsPerMinute == null ? 0 : permitsPerMinute;
        boolean unlimited = permits <= 0;
        Integer capacity = unlimited ? null : (int) Math.ceil(permits * burstRatio);
        Double refillPerSecond = unlimited ? null : round2(permits / 60.0d);
        return new RateLimitRuleView(id, name, scope, target, permits, capacity, refillPerSecond,
                null, "数据库规则", enabled);
    }

    private static RateLimitRuleView dbQuotaRule(Long id, String name, String scope, String target,
                                                 Long dailyTokens, boolean enabled) {
        long tokens = dailyTokens == null ? 0L : dailyTokens;
        return new RateLimitRuleView(id, name, scope, target, null, null, null, tokens, "数据库规则", enabled);
    }

    /**
     * 把 yaml 属性摊平为规则行（数据库不可用时的回退视图，行 id 为 null 表示只读）。
     * 顺序刻意稳定：调用人默认 → 调用人覆盖 → 租户覆盖 → 模型默认 → 模型覆盖 → 配额。
     */
    private List<RateLimitRuleView> deriveFromYaml(GatewayProperties.RateLimit rl, GatewayProperties.Quota quota) {
        List<RateLimitRuleView> rules = new ArrayList<>();
        double burst = rl.getBurstRatio();
        boolean on = rl.isEnabled();

        rules.add(rule("调用人默认限流", "CALLER", "全部调用人",
                rl.getDefaultPermitsPerMinute(), burst, "default-permits-per-minute", on));
        rl.getCallerOverrides().forEach((caller, permits) ->
                rules.add(rule("调用人覆盖 · " + caller, "CALLER", caller, permits, burst, "caller-overrides", on)));
        rl.getTenantOverrides().forEach((tenant, permits) ->
                rules.add(rule("租户覆盖 · " + tenant, "TENANT", tenant, permits, burst, "tenant-overrides", on)));

        if (rl.isPerModelEnabled()) {
            rules.add(rule("模型默认限流", "MODEL", "全部模型（调用人 + 模型维度）",
                    rl.getDefaultModelPermitsPerMinute(), burst, "default-model-permits-per-minute", on));
        }
        rl.getModelOverrides().forEach((model, permits) ->
                rules.add(rule("模型覆盖 · " + model, "MODEL", model, permits, burst, "model-overrides",
                        on && rl.isPerModelEnabled())));

        // 配额与限流是两套独立开关：限流关掉不影响每日 Token 配额
        rules.add(quotaRule("每日 Token 配额（默认）", "QUOTA", "全部" + quotaScopeLabel(quota.getScope()),
                quota.getDefaultDailyTokens(), "default-daily-tokens", quota.isEnabled()));
        quota.getCallerOverrides().forEach((caller, tokens) ->
                rules.add(quotaRule("每日 Token 配额 · " + caller, "QUOTA", caller,
                        tokens, "quota.caller-overrides", quota.isEnabled())));
        quota.getTenantOverrides().forEach((tenant, tokens) ->
                rules.add(quotaRule("每日 Token 配额 · " + tenant, "QUOTA", tenant,
                        tokens, "quota.tenant-overrides", quota.isEnabled())));
        return List.copyOf(rules);
    }

    private static RateLimitRuleView rule(String name, String scope, String target,
                                          int permitsPerMinute, double burstRatio, String source, boolean enabled) {
        // permits <= 0 表示「不限制」，此时桶容量与补充速率都没有意义，一律留空而不是显示 0
        boolean unlimited = permitsPerMinute <= 0;
        Integer capacity = unlimited ? null : (int) Math.ceil(permitsPerMinute * burstRatio);
        Double refillPerSecond = unlimited ? null : round2(permitsPerMinute / 60.0d);
        return new RateLimitRuleView(null, name, scope, target, permitsPerMinute, capacity, refillPerSecond,
                null, source, enabled);
    }

    private static RateLimitRuleView quotaRule(String name, String scope, String target,
                                               long dailyTokens, String source, boolean enabled) {
        return new RateLimitRuleView(null, name, scope, target, null, null, null, dailyTokens, source, enabled);
    }

    private static String quotaScopeLabel(String scope) {
        return "tenant".equalsIgnoreCase(scope) ? "租户" : "调用人";
    }

    private static double round2(double v) {
        return Math.round(v * 100.0d) / 100.0d;
    }

    // ------------------------------------------------------------------ 统计视图

    private RateLimitStatsView buildStatsView() {
        Map<String, Long> byScope = new LinkedHashMap<>();
        long total = 0L;
        for (Counter counter : meterRegistry.find(GatewayMetrics.RATE_LIMIT_REJECTIONS).counters()) {
            String scope = counter.getId().getTag(TAG_SCOPE);
            long count = (long) counter.count();
            byScope.merge(scope == null ? "unknown" : scope, count, Long::sum);
            total += count;
        }
        return new RateLimitStatsView(total, byScope);
    }

    // ------------------------------------------------------------------ 响应结构

    /**
     * 生效的限流/配额配置。
     *
     * @param rateLimitEnabled               限流总开关
     * @param store                          限流计数存储：{@code redis}（多实例共享）或 {@code in-memory}
     * @param failOpen                       存储不可用时是否放行
     * @param burstRatio                     桶容量系数（桶容量 = 每分钟许可 × 该系数）
     * @param defaultPermitsPerMinute        调用人级默认许可（次/分钟，&lt;=0 不限制）
     * @param perModelEnabled                是否额外按「调用人 + 模型」限流
     * @param defaultModelPermitsPerMinute   模型级默认许可（次/分钟）
     * @param quotaEnabled                   每日 Token 配额开关
     * @param quotaScope                     配额维度 {@code caller}/{@code tenant}
     * @param quotaDefaultDailyTokens        默认每日 Token 上限（&lt;=0 不限）
     * @param rules                          摊平后的规则表
     */
    public record RateLimitConfigView(boolean rateLimitEnabled, String store, boolean failOpen, double burstRatio,
                                      int defaultPermitsPerMinute, boolean perModelEnabled,
                                      int defaultModelPermitsPerMinute, boolean quotaEnabled, String quotaScope,
                                      long quotaDefaultDailyTokens, List<RateLimitRuleView> rules) {

        public RateLimitConfigView {
            rules = rules != null ? List.copyOf(rules) : List.of();
        }
    }

    /**
     * 一条摊平后的限流/配额规则。限流类规则的 {@code dailyTokens} 为 null，配额类规则的许可/桶容量为 null。
     *
     * @param id              数据库规则主键；来自 yaml 回退视图时为 null（表示只读行，前端不渲染操作列）
     * @param name            规则展示名
     * @param scope           维度 {@code CALLER}/{@code TENANT}/{@code MODEL}/{@code QUOTA}
     * @param target          限流对象；默认规则为「全部调用人」等描述性文案
     * @param permitsPerMinute 每分钟许可；&lt;=0 表示不限制
     * @param bucketCapacity  令牌桶容量（已乘 burstRatio 并向上取整）；不限制时为 null
     * @param refillPerSecond 每秒补充令牌数（= 每分钟许可 / 60，保留两位）；不限制时为 null
     * @param dailyTokens     每日 Token 上限；仅配额规则有值
     * @param source          配置来源（数据库规则 / yml 具体属性键），便于运维定位
     * @param enabled         该规则是否实际生效（总开关关闭时全部为 false）
     */
    public record RateLimitRuleView(Long id, String name, String scope, String target, Integer permitsPerMinute,
                                    Integer bucketCapacity, Double refillPerSecond, Long dailyTokens,
                                    String source, boolean enabled) {
    }

    /**
     * 限流拒绝统计。
     *
     * @param totalSinceStartup 自进程启动累计的拒绝总次数
     * @param byScope           按 {@code scope=caller|model|quota} 的累计次数
     */
    public record RateLimitStatsView(long totalSinceStartup, Map<String, Long> byScope) {

        public RateLimitStatsView {
            byScope = byScope != null ? Map.copyOf(byScope) : Map.of();
        }
    }
}
