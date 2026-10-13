package tech.qunxu.module.gateway.health;

import tech.qunxu.module.gateway.common.enums.HealthStatus;
import tech.qunxu.module.gateway.common.exception.ErrorCode;

/**
 * 健康探测结果。由 {@code ModelAdapter.probe} 与健康监控使用。
 *
 * @param status    探测后的健康状态
 * @param latencyMs 探测往返时延（毫秒），失败时为 -1
 * @param message   附加说明（失败原因等）
 * @param errorCode 探测失败时归一化的错误码（可空），用于区分凭证过期与一般故障
 */
public record HealthResult(HealthStatus status, long latencyMs, String message, ErrorCode errorCode) {

    public static HealthResult up(long latencyMs) {
        return new HealthResult(HealthStatus.UP, latencyMs, null, null);
    }

    public static HealthResult down(String message) {
        return new HealthResult(HealthStatus.DOWN, -1, message, null);
    }

    public static HealthResult down(String message, ErrorCode errorCode) {
        return new HealthResult(HealthStatus.DOWN, -1, message, errorCode);
    }

    public static HealthResult unknown() {
        return new HealthResult(HealthStatus.UNKNOWN, -1, null, null);
    }

    public boolean isUp() {
        return status == HealthStatus.UP;
    }

    public boolean isAuthExpired() {
        return errorCode == ErrorCode.AUTH_EXPIRED;
    }
}
