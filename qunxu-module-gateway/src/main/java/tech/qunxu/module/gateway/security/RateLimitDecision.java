package tech.qunxu.module.gateway.security;

/**
 * 一次限流判定结果。
 *
 * @param allowed           是否放行
 * @param limit             生效的许可数上限（每分钟）；{@code <=0} 表示未限制
 * @param remaining         桶内剩余令牌（向下取整）；未限制时为 {@code -1}
 * @param retryAfterSeconds 建议的重试等待秒数（写入 {@code Retry-After} 头）；放行时为 {@code 0}
 * @param key               计数键（便于日志排查，不回传客户端）
 * @param degraded          是否为「失败开放」的降级放行（存储不可用）
 */
public record RateLimitDecision(
        boolean allowed,
        long limit,
        long remaining,
        long retryAfterSeconds,
        String key,
        boolean degraded) {

    /** 未限制（许可数 <=0）时的放行判定。 */
    public static RateLimitDecision unlimited(String key) {
        return new RateLimitDecision(true, 0L, -1L, 0L, key, false);
    }

    /** 存储不可用时的失败开放放行。 */
    public static RateLimitDecision degradedAllow(String key, long limit) {
        return new RateLimitDecision(true, limit, -1L, 0L, key, true);
    }

    /** 令牌充足，放行。 */
    public static RateLimitDecision allow(String key, long limit, long remaining) {
        return new RateLimitDecision(true, limit, Math.max(0L, remaining), 0L, key, false);
    }

    /** 令牌不足，拒绝。 */
    public static RateLimitDecision deny(String key, long limit, long remaining, long retryAfterSeconds) {
        return new RateLimitDecision(false, limit, Math.max(0L, remaining),
                Math.max(1L, retryAfterSeconds), key, false);
    }
}
