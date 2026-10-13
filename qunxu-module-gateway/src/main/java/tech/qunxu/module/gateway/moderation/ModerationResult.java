package tech.qunxu.module.gateway.moderation;

import java.util.List;

/**
 * 内容审计结果。
 *
 * @param decision   裁决（PASS/REVIEW/BLOCK）——为结合策略 action 后的<b>最终执行裁决</b>
 * @param categories 命中的风险类别（涉政/色情/暴恐/广告/辱骂等），无命中为空列表
 * @param detail     可读的判定说明（不含明文敏感内容本身，仅类别/计数等元信息）
 * @param provider   产出该结果的审计提供方名（builtin/aliyun/llm）
 */
public record ModerationResult(ModerationDecision decision, List<String> categories, String detail, String provider) {

    public static ModerationResult pass(String provider) {
        return new ModerationResult(ModerationDecision.PASS, List.of(), null, provider);
    }

    public static ModerationResult of(ModerationDecision decision, List<String> categories, String detail, String provider) {
        return new ModerationResult(decision, categories == null ? List.of() : categories, detail, provider);
    }

    public boolean blocked() {
        return decision == ModerationDecision.BLOCK;
    }

    public boolean flagged() {
        return decision != ModerationDecision.PASS;
    }

    /** 用于审计落库的紧凑串：{@code DECISION:cat1,cat2}（PASS 无类别时仅 {@code PASS}）。 */
    public String toAuditString() {
        if (categories == null || categories.isEmpty()) {
            return decision.name();
        }
        return decision.name() + ":" + String.join(",", categories);
    }
}
