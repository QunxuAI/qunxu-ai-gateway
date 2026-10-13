package tech.qunxu.module.gateway.health;

import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.Map;

/**
 * 通道健康存储 SPI（响应式）。承载 {@link ChannelHealth} 的读写，供路由健康过滤、最小延迟负载均衡，
 * 以及 M4 的被动熔断/主动探测/自动剔除恢复使用。
 *
 * <p>默认实现 {@code RedisHealthStore} 以 Redis hash 存储、多实例共享；{@code InMemoryHealthStore}
 * 用于单机或测试。读取失败应「失败开放」——返回无数据快照（视为可路由），避免存储抖动误伤路由。</p>
 */
public interface HealthStore {

    /** 读取单个通道健康；无数据返回 {@link ChannelHealth#unknown}（不返回 {@link Mono#empty()}）。 */
    Mono<ChannelHealth> get(Long channelId);

    /**
     * 批量读取通道健康。
     *
     * @param channelIds 通道 ID 集合
     * @return 以通道 ID 为键的健康映射；缺失的 ID 不出现在结果中（调用方按 {@link ChannelHealth#unknown} 兜底）
     */
    Mono<Map<Long, ChannelHealth>> getAll(Collection<Long> channelIds);

    /** 覆盖写入健康快照。 */
    Mono<Void> save(ChannelHealth health);

    /** 记录一次成功调用：清零连续失败、累加连续成功、以 EWMA 更新平均时延。 */
    Mono<Void> recordSuccess(Long channelId, long latencyMs);

    /** 记录一次失败调用：累加连续失败、清零连续成功（剔除决策由上层依据阈值判定）。 */
    Mono<Void> recordFailure(Long channelId);

    /** 剔除通道：置 EJECTED 并设置冷却截止时间。 */
    Mono<Void> eject(Long channelId, long cooldownMs);

    /** 恢复通道：置 UP 并清除冷却与连续失败计数。 */
    Mono<Void> recover(Long channelId);

    /** 删除通道健康数据（通道下线时清理）。 */
    Mono<Void> delete(Long channelId);
}
