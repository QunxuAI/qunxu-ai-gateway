package tech.qunxu.module.gateway.security;

import tech.qunxu.module.gateway.config.GatewayProperties;
import tech.qunxu.module.gateway.ratelimit.RateLimitRuleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Token 配额服务：按调用人（或租户）限制<b>每日累计消耗的 total tokens</b>。
 *
 * <p>计数存于 Redis 字符串键 {@code gw:quota:tokens:{scope}:{yyyyMMdd}}（跨实例共享、按日自然滚动，
 * 并设 36 小时 TTL 兜底清理）；请求前 {@link #check} 判定是否超额，请求结束后由限流过滤器
 * {@link #consume} 累加本次用量（尽力而为，失败不影响主链）。</p>
 *
 * <p>降级策略：Redis 不可用（无 Bean 或操作异常）时退化为<b>进程内计数</b>——多实例下为单机近似，
 * 且读失败按「已用量未知」失败开放，绝不因配额组件故障阻断业务。限额 {@code <=0} 表示未配置配额，
 * 此时不做任何计数读写。</p>
 */
@Service
public class QuotaService {

    private static final Logger log = LoggerFactory.getLogger(QuotaService.class);

    /** 配额计数键前缀。 */
    public static final String KEY_PREFIX = "gw:quota:tokens:";
    /** 按日滚动的键 TTL（略大于一天，容忍跨日边界的迟到写入）。 */
    private static final Duration KEY_TTL = Duration.ofHours(36);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String SCOPE_TENANT = "tenant";

    private final ObjectProvider<ReactiveStringRedisTemplate> redisProvider;
    private final GatewayProperties properties;
    private final RateLimitRuleService ruleService;
    /** Redis 不可用时的进程内计数（单机近似）。 */
    private final Map<String, AtomicLong> localCounters = new ConcurrentHashMap<>();

    public QuotaService(ObjectProvider<ReactiveStringRedisTemplate> redisProvider, GatewayProperties properties,
                        RateLimitRuleService ruleService) {
        this.redisProvider = redisProvider;
        this.properties = properties;
        this.ruleService = ruleService;
    }

    /** 配额功能是否开启。 */
    public boolean isEnabled() {
        return properties.getQuota().isEnabled();
    }

    /**
     * 解析当日 Token 限额（来源：数据库 {@code rate_limit_rule} 快照；未就绪回退 yaml）。
     *
     * <p>{@code scope=tenant} 时按租户汇总，取「租户覆盖 &gt; 全局默认」；否则按调用人，
     * 取「调用人覆盖 &gt; 租户覆盖 &gt; 全局默认」。返回 {@code <=0} 表示不限额。</p>
     */
    public long resolveLimit(String callerId, String tenant) {
        return ruleService.dailyTokens(callerId, tenant, isTenantScope());
    }

    /** 构造当日计数键（含日期，跨日自动切换到新键）。 */
    public String quotaKey(String callerId, String tenant) {
        String id = isTenantScope() ? normalize(tenant) : normalize(callerId);
        return KEY_PREFIX + (isTenantScope() ? "tenant:" : "caller:") + id + ":" + today();
    }

    /**
     * 判定配额是否仍可用。
     *
     * @param key   {@link #quotaKey} 生成的键
     * @param limit 当日限额，{@code <=0} 表示不限（直接放行且不读计数）
     */
    public Mono<QuotaDecision> check(String key, long limit) {
        if (limit <= 0) {
            return Mono.just(QuotaDecision.unlimited(key));
        }
        return read(key).map(used -> used < 0
                ? QuotaDecision.unknown(key, limit)
                : QuotaDecision.of(key, limit, used));
    }

    /**
     * 累加本次调用的 Token 消耗（尽力而为，异常仅告警）。
     *
     * @return 累加后的当日已用量；{@code tokens<=0} 时返回当前已用量
     */
    public Mono<Long> consume(String key, long tokens) {
        if (key == null || key.isBlank()) {
            return Mono.just(0L);
        }
        if (tokens <= 0) {
            return read(key).map(used -> Math.max(0L, used));
        }
        return write(key, tokens).onErrorResume(e -> {
            log.warn("累加配额用量失败({}): {}", key, e.toString());
            return Mono.just(0L);
        });
    }

    /** 读取当日已用量（供 Admin 查询）；不可用时返回 {@code -1}。 */
    public Mono<Long> usage(String key) {
        return read(key);
    }

    /** 当日日期串（键的一部分，包内可见以便测试断言）。 */
    String today() {
        return LocalDate.now().format(DAY);
    }

    private boolean isTenantScope() {
        String scope = properties.getQuota().getScope();
        return scope != null && SCOPE_TENANT.equalsIgnoreCase(scope.trim());
    }

    /** 读取计数；Redis 缺失返回进程内计数，Redis 异常降级为进程内计数（读不到则为 -1 表示未知）。 */
    private Mono<Long> read(String key) {
        if (key == null || key.isBlank()) {
            return Mono.just(0L);
        }
        ReactiveStringRedisTemplate redis = redis();
        if (redis == null) {
            return Mono.fromSupplier(() -> localCounters.containsKey(key) ? localCounters.get(key).get() : 0L);
        }
        return redis.opsForValue().get(key)
                .map(QuotaService::parse)
                .defaultIfEmpty(0L)
                .onErrorResume(e -> {
                    log.warn("读取配额计数失败({}), 降级为进程内计数: {}", key, e.toString());
                    return Mono.fromSupplier(() -> localCounters.containsKey(key) ? localCounters.get(key).get() : -1L);
                });
    }

    /** 累加计数并续期；Redis 缺失或异常时退化为进程内累加。 */
    private Mono<Long> write(String key, long tokens) {
        ReactiveStringRedisTemplate redis = redis();
        if (redis == null) {
            return Mono.fromSupplier(() -> localCounters.computeIfAbsent(key, k -> new AtomicLong()).addAndGet(tokens));
        }
        return redis.opsForValue().increment(key, tokens)
                .flatMap(value -> redis.expire(key, KEY_TTL).thenReturn(value))
                .onErrorResume(e -> {
                    log.warn("累加配额计数失败({}), 降级为进程内计数: {}", key, e.toString());
                    return Mono.fromSupplier(
                            () -> localCounters.computeIfAbsent(key, k -> new AtomicLong()).addAndGet(tokens));
                });
    }

    /** 获取 Redis 模板；Bean 不存在或创建失败均视为不可用（返回 {@code null}）。 */
    private ReactiveStringRedisTemplate redis() {
        try {
            return redisProvider.getIfAvailable();
        } catch (RuntimeException e) {
            log.debug("Redis 模板不可用，配额退化为进程内计数: {}", e.toString());
            return null;
        }
    }

    private static long parse(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static String normalize(String s) {
        return (s == null || s.isBlank()) ? "anonymous" : s.trim();
    }
}
