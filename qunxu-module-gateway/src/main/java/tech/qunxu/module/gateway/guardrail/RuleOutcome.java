package tech.qunxu.module.gateway.guardrail;

/**
 * 单条规则的裁决结果。
 *
 * @param decision   裁决（ALLOW/MASK/BLOCK）
 * @param rule       规则名（用于审计命中标记）
 * @param reason     命中原因；非空表示规则被触发（含 WARN 类放行）
 * @param maskedText 当 decision=MASK 时为脱敏后的文本，其余为 {@code null}
 */
public record RuleOutcome(GuardrailDecision decision, String rule, String reason, String maskedText) {

    /** 未触发，放行。 */
    public static RuleOutcome allow(String rule) {
        return new RuleOutcome(GuardrailDecision.ALLOW, rule, null, null);
    }

    /** 触发但仅告警（放行，命中记入审计）。 */
    public static RuleOutcome warn(String rule, String reason) {
        return new RuleOutcome(GuardrailDecision.ALLOW, rule, reason, null);
    }

    /** 触发并脱敏放行。 */
    public static RuleOutcome mask(String rule, String reason, String maskedText) {
        return new RuleOutcome(GuardrailDecision.MASK, rule, reason, maskedText);
    }

    /** 触发并拦截。 */
    public static RuleOutcome block(String rule, String reason) {
        return new RuleOutcome(GuardrailDecision.BLOCK, rule, reason, null);
    }

    /** 是否被触发（reason 非空即视为触发，含 WARN）。 */
    public boolean triggered() {
        return reason != null && !reason.isBlank();
    }
}
