package tech.qunxu.module.gateway.health;

import tech.qunxu.module.gateway.common.enums.HealthStatus;

/**
 * 通道健康快照。以 Redis hash（{@code gw:channel:health:{id}}）持久化，多实例共享，
 * 是路由「健康过滤」与最小延迟负载均衡的数据来源。
 *
 * <p>本类型为纯数据 + 纯函数（不可变），不承载阈值决策；「连续失败达阈值→剔除」「连续成功达阈值→恢复」
 * 等策略在 M4 的 {@code HealthMonitor} 中结合 {@code GatewayProperties.Health} 判定，再调用本类型的
 * {@link #ejected(long)} / {@link #recovered()} 生成新快照落库。</p>
 *
 * @param channelId            通道 ID
 * @param status               健康状态；{@code null} 或 {@link HealthStatus#UNKNOWN} 表示尚无探测数据（失败开放，可路由）
 * @param consecutiveFailures  连续失败次数（成功后清零）
 * @param consecutiveSuccesses 连续成功次数（失败后清零）
 * @param ejectedUntil         剔除冷却截止时间（epoch millis）；未剔除为 {@code null}
 * @param avgLatencyMs         EWMA 平滑平均时延（毫秒）；无数据为 {@code -1}
 * @param updatedAt            最近更新时间（epoch millis）；从未更新为 {@code null}
 */
public record ChannelHealth(
        Long channelId,
        HealthStatus status,
        int consecutiveFailures,
        int consecutiveSuccesses,
        Long ejectedUntil,
        double avgLatencyMs,
        Long updatedAt) {

    /** 尚无数据的初始快照（失败开放：视为可路由）。 */
    public static ChannelHealth unknown(Long channelId) {
        return new ChannelHealth(channelId, HealthStatus.UNKNOWN, 0, 0, null, -1d, null);
    }

    /** 标记为健康的快照。 */
    public static ChannelHealth up(Long channelId) {
        return new ChannelHealth(channelId, HealthStatus.UP, 0, 0, null, -1d, System.currentTimeMillis());
    }

    /**
     * 是否可参与路由。失败开放策略：无数据（{@code null}/{@link HealthStatus#UNKNOWN}）视为可路由，
     * 避免 Redis 抖动或首次探测前误伤；仅 {@link HealthStatus#DOWN} 与冷却未到的 {@link HealthStatus#EJECTED}
     * 被排除。剔除冷却已过（半开）允许放行以真实请求试探恢复。
     */
    public boolean isRoutable() {
        if (status == null || status == HealthStatus.UNKNOWN || status == HealthStatus.UP) {
            return true;
        }
        if (status == HealthStatus.EJECTED) {
            return ejectedUntil != null && System.currentTimeMillis() >= ejectedUntil;
        }
        return false;
    }

    /** 是否已有时延样本（供最小延迟策略使用）。 */
    public boolean hasLatency() {
        return avgLatencyMs >= 0;
    }

    /** 是否处于剔除冷却期。 */
    public boolean isEjected() {
        return status == HealthStatus.EJECTED
                && (ejectedUntil == null || System.currentTimeMillis() < ejectedUntil);
    }

    /**
     * 记录一次成功：清零连续失败、累加连续成功、以 EWMA 更新平均时延。
     *
     * <p>状态迁移：尚无数据（{@code null}/{@link HealthStatus#UNKNOWN}）的通道在收到真实成功
     * （调用或探测）后直接提升为 {@link HealthStatus#UP}；{@link HealthStatus#DOWN} /
     * {@link HealthStatus#EJECTED} 保持原状态，由上层 {@code HealthMonitor} 按半开恢复阈值
     * （连续成功次数）判定后再调用 {@link #recovered()} 提升，避免一次成功即恢复被剔除的通道。</p>
     *
     * @param latencyMs 本次调用往返时延
     * @param alpha     EWMA 平滑系数（0~1，越大越重视新样本）
     */
    public ChannelHealth afterSuccess(long latencyMs, double alpha) {
        double a = alpha <= 0 || alpha > 1 ? 0.3d : alpha;
        double newAvg = hasLatency() ? a * latencyMs + (1 - a) * avgLatencyMs : latencyMs;
        HealthStatus nextStatus = (status == null || status == HealthStatus.UNKNOWN)
                ? HealthStatus.UP
                : status;
        return new ChannelHealth(channelId, nextStatus, 0, consecutiveSuccesses + 1,
                ejectedUntil, newAvg, System.currentTimeMillis());
    }

    /** 记录一次失败：累加连续失败、清零连续成功（不改变 status，剔除决策由上层判定）。 */
    public ChannelHealth afterFailure() {
        return new ChannelHealth(channelId, status, consecutiveFailures + 1, 0,
                ejectedUntil, avgLatencyMs, System.currentTimeMillis());
    }

    /** 进入剔除冷却：status=EJECTED，设置冷却截止时间。 */
    public ChannelHealth ejected(long cooldownMs) {
        long now = System.currentTimeMillis();
        return new ChannelHealth(channelId, HealthStatus.EJECTED, consecutiveFailures, 0,
                now + Math.max(0, cooldownMs), avgLatencyMs, now);
    }

    /** 恢复为健康：status=UP，清除冷却与连续失败计数。 */
    public ChannelHealth recovered() {
        return new ChannelHealth(channelId, HealthStatus.UP, 0, consecutiveSuccesses,
                null, avgLatencyMs, System.currentTimeMillis());
    }

    /** 主动探测置为 DOWN（等待后续成功探测恢复）。 */
    public ChannelHealth down() {
        return new ChannelHealth(channelId, HealthStatus.DOWN, consecutiveFailures, 0,
                ejectedUntil, avgLatencyMs, System.currentTimeMillis());
    }
}
