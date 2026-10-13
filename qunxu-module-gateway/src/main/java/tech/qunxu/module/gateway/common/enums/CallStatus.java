package tech.qunxu.module.gateway.common.enums;

/**
 * 单次调用的最终结果状态，用于审计。
 */
public enum CallStatus {
    /** 成功返回。 */
    SUCCESS,
    /** 调用失败（下游错误、超时、无可用通道等）。 */
    FAILURE,
    /** 被安全围栏或内容审计拦截。 */
    BLOCKED;

    public static CallStatus of(String value) {
        if (value == null) {
            return null;
        }
        for (CallStatus s : values()) {
            if (s.name().equalsIgnoreCase(value.trim())) {
                return s;
            }
        }
        return null;
    }
}
