package tech.qunxu.module.gateway.audit;

/**
 * Token 用量聚合行。按 {@link UsageDimension} 分组统计调用次数与各段 token 总量。
 *
 * @param key              分组键：调用人 ID / 逻辑模型名 / 日期（{@code yyyy-MM-dd}）
 * @param calls            调用次数
 * @param promptTokens     输入 token 合计
 * @param completionTokens 输出 token 合计
 * @param totalTokens      总 token 合计
 */
public record TokenUsageStat(
        String key,
        long calls,
        long promptTokens,
        long completionTokens,
        long totalTokens) {
}
