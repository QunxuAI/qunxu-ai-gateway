package tech.qunxu.module.gateway.moderation;

/**
 * 内容审计裁决（风险级别）。
 */
public enum ModerationDecision {
    /** 内容合规，放行。 */
    PASS,
    /** 疑似违规，建议人工复核（默认放行但标记，供审计追踪）。 */
    REVIEW,
    /** 明确违规，拦截。 */
    BLOCK
}
