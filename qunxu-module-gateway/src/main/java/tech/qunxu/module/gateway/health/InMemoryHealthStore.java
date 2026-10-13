package tech.qunxu.module.gateway.health;

import tech.qunxu.module.gateway.config.GatewayProperties;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于内存的健康存储。用于单机部署或测试（无 Redis 依赖），进程重启即丢失、不跨实例共享。
 *
 * <p>通过 {@code gateway.health.store=in-memory} 激活；默认由 {@code RedisHealthStore} 承载。</p>
 */
public class InMemoryHealthStore implements HealthStore {

    private final Map<Long, ChannelHealth> store = new ConcurrentHashMap<>();
    private final double alpha;

    public InMemoryHealthStore(GatewayProperties properties) {
        this.alpha = properties.getHealth().getLatencyAlpha();
    }

    @Override
    public Mono<ChannelHealth> get(Long channelId) {
        if (channelId == null) {
            return Mono.just(ChannelHealth.unknown(null));
        }
        return Mono.fromSupplier(() -> store.getOrDefault(channelId, ChannelHealth.unknown(channelId)));
    }

    @Override
    public Mono<Map<Long, ChannelHealth>> getAll(Collection<Long> channelIds) {
        if (channelIds == null || channelIds.isEmpty()) {
            return Mono.just(Map.of());
        }
        return Mono.fromSupplier(() -> {
            Map<Long, ChannelHealth> result = new HashMap<>();
            for (Long id : channelIds) {
                if (id == null) {
                    continue;
                }
                ChannelHealth health = store.get(id);
                if (health != null) {
                    result.put(id, health);
                }
            }
            return result;
        });
    }

    @Override
    public Mono<Void> save(ChannelHealth health) {
        return Mono.fromRunnable(() -> {
            if (health != null && health.channelId() != null) {
                store.put(health.channelId(), health);
            }
        });
    }

    @Override
    public Mono<Void> recordSuccess(Long channelId, long latencyMs) {
        return Mono.fromRunnable(() -> {
            if (channelId != null) {
                store.compute(channelId, (id, cur) ->
                        (cur != null ? cur : ChannelHealth.unknown(id)).afterSuccess(latencyMs, alpha));
            }
        });
    }

    @Override
    public Mono<Void> recordFailure(Long channelId) {
        return Mono.fromRunnable(() -> {
            if (channelId != null) {
                store.compute(channelId, (id, cur) ->
                        (cur != null ? cur : ChannelHealth.unknown(id)).afterFailure());
            }
        });
    }

    @Override
    public Mono<Void> eject(Long channelId, long cooldownMs) {
        return Mono.fromRunnable(() -> {
            if (channelId != null) {
                store.compute(channelId, (id, cur) ->
                        (cur != null ? cur : ChannelHealth.unknown(id)).ejected(cooldownMs));
            }
        });
    }

    @Override
    public Mono<Void> recover(Long channelId) {
        return Mono.fromRunnable(() -> {
            if (channelId != null) {
                store.compute(channelId, (id, cur) ->
                        (cur != null ? cur : ChannelHealth.unknown(id)).recovered());
            }
        });
    }

    @Override
    public Mono<Void> delete(Long channelId) {
        return Mono.fromRunnable(() -> {
            if (channelId != null) {
                store.remove(channelId);
            }
        });
    }
}
