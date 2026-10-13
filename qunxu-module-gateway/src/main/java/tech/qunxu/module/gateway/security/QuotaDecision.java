package tech.qunxu.module.gateway.security;

/**
 * 一次 Token 配额判定结果。
 *
 * @param allowed   是否仍有配额（限额 {@code <=0} 时恒为 {@code true}）
 * @param limit     当日 Token 限额；{@code <=0} 表示未限制
 * @param used      当日已消耗 Token 数（读取失败时为 {@code -1}，表示未知）
 * @param remaining 当日剩余 Token 数；未限制或未知时为 {@code -1}
 * @param key       计数键（含日期，便于日志排查）
 * @param unlimited 该维度是否未配置限额（未配置时不做任何计数读写）
 */
public record QuotaDecision(
        boolean allowed,
        long limit,
        long used,
        long remaining,
        String key,
        boolean unlimited) {

    /** 未配置限额：不限制、不计数。 */
    public static QuotaDecision unlimited(String key) {
        return new QuotaDecision(true, 0L, -1L, -1L, key, true);
    }

    /** 依据已用量判定。 */
    public static QuotaDecision of(String key, long limit, long used) {
        long safeUsed = Math.max(0L, used);
        boolean allowed = limit <= 0 || safeUsed < limit;
        long remaining = limit <= 0 ? -1L : Math.max(0L, limit - safeUsed);
        return new QuotaDecision(allowed, limit, safeUsed, remaining, key, false);
    }

    /** 已用量未知（存储不可用）时失败开放。 */
    public static QuotaDecision unknown(String key, long limit) {
        return new QuotaDecision(true, limit, -1L, -1L, key, false);
    }
}
