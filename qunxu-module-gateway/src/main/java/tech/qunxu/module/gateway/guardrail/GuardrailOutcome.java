package tech.qunxu.module.gateway.guardrail;

/**
 * 围栏引擎对一段文本的聚合裁决。
 *
 * @param decision 最终裁决（BLOCK 优先，其次 MASK，否则 ALLOW）
 * @param hit      命中的规则名（逗号连接），供审计 {@code guardrail_hit} 记录；未触发为 {@code null}
 * @param reason   代表性命中原因（首个 BLOCK/MASK/WARN 原因）
 * @param text     最终文本：MASK 时为脱敏后文本，否则为原文
 */
public record GuardrailOutcome(GuardrailDecision decision, String hit, String reason, String text) {

    public static GuardrailOutcome allow(String text) {
        return new GuardrailOutcome(GuardrailDecision.ALLOW, null, null, text);
    }

    public boolean blocked() {
        return decision == GuardrailDecision.BLOCK;
    }

    public boolean masked() {
        return decision == GuardrailDecision.MASK;
    }

    /** 是否有任一规则被触发（含 WARN 放行），用于决定是否写审计命中标记。 */
    public boolean triggered() {
        return hit != null && !hit.isBlank();
    }
}
