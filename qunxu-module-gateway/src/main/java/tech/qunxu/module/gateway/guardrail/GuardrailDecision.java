package tech.qunxu.module.gateway.guardrail;

/**
 * 安全围栏裁决。
 */
public enum GuardrailDecision {
    /** 放行，文本不变。 */
    ALLOW,
    /** 脱敏放行：命中内容被掩码替换后继续。 */
    MASK,
    /** 拦截：阻断请求/响应。 */
    BLOCK
}
