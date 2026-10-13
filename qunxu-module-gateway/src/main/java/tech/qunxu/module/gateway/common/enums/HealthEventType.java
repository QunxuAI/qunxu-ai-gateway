package tech.qunxu.module.gateway.common.enums;

/**
 * 健康事件类型，落库于 {@code health_event.event_type}，供 Admin 与告警查询。
 */
public enum HealthEventType {
    /** 通道被剔除（连续失败或错误率超阈值）。 */
    EJECT,
    /** 通道从 DOWN/EJECTED 恢复为 UP。 */
    RECOVER,
    /** 主动探测失败。 */
    PROBE_FAIL,
    /** 下游凭证无效或过期（401/403）。 */
    AUTH_EXPIRED;

    public static HealthEventType of(String value) {
        if (value == null) {
            return null;
        }
        for (HealthEventType t : values()) {
            if (t.name().equalsIgnoreCase(value.trim())) {
                return t;
            }
        }
        return null;
    }
}
