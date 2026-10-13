package tech.qunxu.module.gateway.router;

/**
 * 负载均衡策略。对应配置 {@code gateway.load-balance.default-strategy}，
 * 后续亦可按逻辑模型/通道覆盖。
 */
public enum LoadBalanceStrategy {

    /** 加权轮询（默认）：按通道 weight 平滑分配流量。 */
    WEIGHTED_ROUND_ROBIN,
    /** 最小延迟：优先选择 EWMA 平均时延最低的通道（时延来自 Redis 健康统计）。 */
    LEAST_LATENCY,
    /** 随机：在健康通道内等概率随机。 */
    RANDOM,
    /** 优先级：priority 数值最小者优先，其次 weight 大者。 */
    PRIORITY;

    /**
     * 宽松解析策略名（大小写不敏感、忽略空白）；无法识别时返回 {@code fallback}。
     *
     * @param name     策略名，可为 {@code null}
     * @param fallback 兜底策略
     */
    public static LoadBalanceStrategy parse(String name, LoadBalanceStrategy fallback) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        try {
            return valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }
}
