package tech.qunxu.module.gateway.health;

import tech.qunxu.module.gateway.common.enums.HealthStatus;
import tech.qunxu.module.gateway.config.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveHashOperations;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 基于 Redis 的健康存储（默认实现）。以 hash {@code gw:channel:health:{id}} 存储
 * status/failCount/successCount/ejectedUntil/avgLatency/updatedAt，多实例共享。
 *
 * <p>读取失败「失败开放」：返回 {@link ChannelHealth#unknown}（视为可路由），避免 Redis 抖动误伤路由。
 * 成功/失败计数为「读-改-写」，跨实例存在轻微竞态（仅影响统计精度，不影响正确性）；如需严格原子，
 * M4 可改用 Lua 脚本。写入按 {@code gateway.health.health-ttl-seconds} 设置 TTL，防止下线通道键长期驻留。</p>
 */
public class RedisHealthStore implements HealthStore {

    private static final Logger log = LoggerFactory.getLogger(RedisHealthStore.class);

    /** 健康 hash 键前缀。 */
    public static final String KEY_PREFIX = "gw:channel:health:";

    private static final String F_STATUS = "status";
    private static final String F_FAIL = "failCount";
    private static final String F_SUCCESS = "successCount";
    private static final String F_EJECTED = "ejectedUntil";
    private static final String F_LATENCY = "avgLatency";
    private static final String F_UPDATED = "updatedAt";

    private final ReactiveStringRedisTemplate redis;
    private final ReactiveHashOperations<String, String, String> hashOps;
    private final double alpha;
    private final long ttlSeconds;

    public RedisHealthStore(ReactiveStringRedisTemplate redis, GatewayProperties properties) {
        this.redis = redis;
        this.hashOps = redis.opsForHash();
        this.alpha = properties.getHealth().getLatencyAlpha();
        this.ttlSeconds = properties.getHealth().getHealthTtlSeconds();
    }

    private static String key(Long channelId) {
        return KEY_PREFIX + channelId;
    }

    @Override
    public Mono<ChannelHealth> get(Long channelId) {
        if (channelId == null) {
            return Mono.just(ChannelHealth.unknown(null));
        }
        return hashOps.entries(key(channelId))
                .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                .map(entries -> entries.isEmpty() ? ChannelHealth.unknown(channelId) : fromHash(channelId, entries))
                .onErrorResume(e -> {
                    log.warn("读取通道 {} 健康失败，按 unknown 兜底: {}", channelId, e.toString());
                    return Mono.just(ChannelHealth.unknown(channelId));
                });
    }

    @Override
    public Mono<Map<Long, ChannelHealth>> getAll(Collection<Long> channelIds) {
        if (channelIds == null || channelIds.isEmpty()) {
            return Mono.just(Map.of());
        }
        return Flux.fromIterable(channelIds)
                .filter(Objects::nonNull)
                .distinct()
                .flatMap(id -> hashOps.entries(key(id))
                        .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                        .flatMap(entries -> entries.isEmpty()
                                ? Mono.<Map.Entry<Long, ChannelHealth>>empty()
                                : Mono.just(Map.entry(id, fromHash(id, entries))))
                        .onErrorResume(e -> {
                            log.debug("批量读取通道 {} 健康失败，跳过: {}", id, e.toString());
                            return Mono.<Map.Entry<Long, ChannelHealth>>empty();
                        }))
                .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                .onErrorResume(e -> {
                    log.warn("批量读取健康失败，返回空集: {}", e.toString());
                    return Mono.just(Map.of());
                });
    }

    @Override
    public Mono<Void> save(ChannelHealth health) {
        if (health == null || health.channelId() == null) {
            return Mono.empty();
        }
        String key = key(health.channelId());
        return Mono.defer(() -> hashOps.putAll(key, toHash(health))
                        .then(applyTtl(key)))
                .onErrorResume(e -> {
                    log.warn("写入通道 {} 健康失败: {}", health.channelId(), e.toString());
                    return Mono.empty();
                })
                .then();
    }

    @Override
    public Mono<Void> recordSuccess(Long channelId, long latencyMs) {
        if (channelId == null) {
            return Mono.empty();
        }
        return get(channelId).flatMap(cur -> save(cur.afterSuccess(latencyMs, alpha)));
    }

    @Override
    public Mono<Void> recordFailure(Long channelId) {
        if (channelId == null) {
            return Mono.empty();
        }
        return get(channelId).flatMap(cur -> save(cur.afterFailure()));
    }

    @Override
    public Mono<Void> eject(Long channelId, long cooldownMs) {
        if (channelId == null) {
            return Mono.empty();
        }
        return get(channelId).flatMap(cur -> save(cur.ejected(cooldownMs)));
    }

    @Override
    public Mono<Void> recover(Long channelId) {
        if (channelId == null) {
            return Mono.empty();
        }
        return get(channelId).flatMap(cur -> save(cur.recovered()));
    }

    @Override
    public Mono<Void> delete(Long channelId) {
        if (channelId == null) {
            return Mono.empty();
        }
        return redis.delete(key(channelId))
                .onErrorResume(e -> {
                    log.warn("删除通道 {} 健康失败: {}", channelId, e.toString());
                    return Mono.just(0L);
                })
                .then();
    }

    private Mono<Boolean> applyTtl(String key) {
        if (ttlSeconds <= 0) {
            return Mono.just(true);
        }
        return redis.expire(key, Duration.ofSeconds(ttlSeconds));
    }

    // ---- 编解码 ----

    private static Map<String, String> toHash(ChannelHealth h) {
        Map<String, String> map = new HashMap<>(8);
        map.put(F_STATUS, (h.status() != null ? h.status() : HealthStatus.UNKNOWN).name());
        map.put(F_FAIL, Integer.toString(h.consecutiveFailures()));
        map.put(F_SUCCESS, Integer.toString(h.consecutiveSuccesses()));
        map.put(F_EJECTED, h.ejectedUntil() != null ? h.ejectedUntil().toString() : "");
        map.put(F_LATENCY, Double.toString(h.avgLatencyMs()));
        map.put(F_UPDATED, h.updatedAt() != null ? h.updatedAt().toString() : "");
        return map;
    }

    private static ChannelHealth fromHash(Long channelId, Map<String, String> m) {
        return new ChannelHealth(
                channelId,
                parseStatus(m.get(F_STATUS)),
                parseInt(m.get(F_FAIL), 0),
                parseInt(m.get(F_SUCCESS), 0),
                parseLongOrNull(m.get(F_EJECTED)),
                parseDouble(m.get(F_LATENCY), -1d),
                parseLongOrNull(m.get(F_UPDATED)));
    }

    private static HealthStatus parseStatus(String s) {
        if (s == null || s.isBlank()) {
            return HealthStatus.UNKNOWN;
        }
        try {
            return HealthStatus.valueOf(s.trim());
        } catch (IllegalArgumentException ex) {
            return HealthStatus.UNKNOWN;
        }
    }

    private static int parseInt(String s, int def) {
        if (s == null || s.isBlank()) {
            return def;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    private static double parseDouble(String s, double def) {
        if (s == null || s.isBlank()) {
            return def;
        }
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    private static Long parseLongOrNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
