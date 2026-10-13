package tech.qunxu.module.gateway.common.enums;

/**
 * 通道健康状态。由被动熔断与主动探测共同维护，存于 Redis 以支持多实例共享。
 */
public enum HealthStatus {
    /** 健康，可参与路由。 */
    UP,
    /** 探测失败或熔断打开，暂不参与路由。 */
    DOWN,
    /** 因连续失败/错误率超阈值被剔除，进入冷却期。 */
    EJECTED,
    /** 尚未探测，初始状态。 */
    UNKNOWN;

    public boolean routable() {
        return this == UP;
    }
}
