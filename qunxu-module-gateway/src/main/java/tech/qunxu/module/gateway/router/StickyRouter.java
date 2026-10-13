package tech.qunxu.module.gateway.router;

import tech.qunxu.module.gateway.config.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * 会话级供应商粘性路由器（借鉴 AstrLink 的 provider stickiness）：把「同一会话 → 同一通道」的绑定
 * 存于 Redis（TTL 自动过期），供路由阶段优先复用，避免多轮对话在供应商间漂移导致
 * Anthropic 思考块续传 / OpenAI Responses 加密续传数据失效。
 *
 * <p>绑定键：{@code gw:sticky:{modelId}:{sessionKey}}（{@code sessionKey = callerId:sessionId}）。
 * Redis 不可用或查询异常时按 {@code gateway.sticky.fail-open} 处理：
 * 默认 {@code true} 降级为普通负载均衡路由（fail-open），{@code false} 则向上抛出异常。
 * 粘性为尽力而为的优化：绑定失效（TTL 过期 / 通道被剔除 / 会话新开）时自动重绑，不阻塞请求。</p>
 */
@Service
public class StickyRouter {

    private static final Logger log = LoggerFactory.getLogger(StickyRouter.class);

    private final ReactiveStringRedisTemplate redis;
    private final GatewayProperties properties;

    public StickyRouter(ReactiveStringRedisTemplate redis, GatewayProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    /** 组合会话键（调用人隔离，防止不同租户/令牌互相串会话）。 */
    public static String sessionKey(String callerId, String sessionId) {
        return callerId + ":" + sessionId;
    }

    String key(Long modelId, String sessionKey) {
        return properties.getSticky().getRedisKeyPrefix() + ":" + modelId + ":" + sessionKey;
    }

    /**
     * 查询会话绑定的通道 ID。
     *
     * @return 已绑定通道 ID；无绑定、会话键为空或 Redis 异常（fail-open）时返回 {@link Mono#empty()}
     */
    public Mono<Long> lookup(Long modelId, String sessionKey) {
        if (modelId == null || sessionKey == null || sessionKey.isBlank()) {
            return Mono.empty();
        }
        String k = key(modelId, sessionKey);
        return redis.opsForValue().get(k)
                .flatMap(StickyRouter::parseLong)
                .onErrorResume(e -> failOpen("粘性查询", k, e));
    }

    /**
     * 写入会话绑定（TTL 秒）。
     *
     * @return 完成信号；Redis 异常（fail-open）时降级为空完成，不阻塞请求
     */
    public Mono<Void> bind(Long modelId, String sessionKey, Long channelId) {
        if (modelId == null || sessionKey == null || sessionKey.isBlank() || channelId == null) {
            return Mono.empty();
        }
        String k = key(modelId, sessionKey);
        long ttl = Math.max(properties.getSticky().getTtlSeconds(), 1L);
        return redis.opsForValue().set(k, String.valueOf(channelId), Duration.ofSeconds(ttl))
                .then()
                .onErrorResume(e -> failOpen("粘性绑定", k, e));
    }

    /** 解除会话绑定。 */
    public Mono<Void> unbind(Long modelId, String sessionKey) {
        if (modelId == null || sessionKey == null || sessionKey.isBlank()) {
            return Mono.empty();
        }
        String k = key(modelId, sessionKey);
        return redis.delete(k).then().onErrorResume(e -> failOpen("粘性解绑", k, e));
    }

    private static Mono<Long> parseLong(String value) {
        try {
            return Mono.just(Long.parseLong(value));
        } catch (NumberFormatException e) {
            return Mono.empty();
        }
    }

    private <T> Mono<T> failOpen(String op, String k, Throwable e) {
        if (properties.getSticky().isFailOpen()) {
            log.warn("{}失败，降级普通路由 key={} err={}", op, k, e.getMessage());
            return Mono.empty();
        }
        return Mono.error(e);
    }
}
