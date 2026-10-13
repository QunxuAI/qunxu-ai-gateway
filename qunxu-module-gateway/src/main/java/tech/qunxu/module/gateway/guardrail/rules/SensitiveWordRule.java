package tech.qunxu.module.gateway.guardrail.rules;

import tech.qunxu.module.gateway.guardrail.GuardrailContext;
import tech.qunxu.module.gateway.guardrail.GuardrailRule;
import tech.qunxu.module.gateway.guardrail.Masking;
import tech.qunxu.module.gateway.guardrail.RuleOutcome;
import tech.qunxu.module.gateway.guardrail.SensitiveWordService;
import tech.qunxu.module.gateway.guardrail.SensitiveWordTrie.Match;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 敏感词围栏规则：基于 {@link SensitiveWordService} 的 DFA/Trie 快照做最长匹配，按命中词条的动作裁决。
 *
 * <p>动作优先级：任一命中词动作为 {@code BLOCK} → 拦截；否则存在 {@code MASK} 命中 → 仅脱敏这些命中区间后放行；
 * 仅剩 {@code WARN} 命中 → 放行但记录原因（写入审计命中标记，不阻断）。可通过 {@code sensitiveWord.enabled=false} 关闭。</p>
 */
@Component
public class SensitiveWordRule implements GuardrailRule {

    public static final String NAME = "SENSITIVE_WORD";

    private final SensitiveWordService sensitiveWordService;

    public SensitiveWordRule(SensitiveWordService sensitiveWordService) {
        this.sensitiveWordService = sensitiveWordService;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public RuleOutcome evaluate(GuardrailContext context) {
        if (!context.bool("sensitiveWord.enabled", true)) {
            return RuleOutcome.allow(NAME);
        }
        String text = context.text();
        if (text == null || text.isEmpty()) {
            return RuleOutcome.allow(NAME);
        }
        List<Match> hits = sensitiveWordService.findHits(text);
        if (hits.isEmpty()) {
            return RuleOutcome.allow(NAME);
        }
        List<String> blockWords = new ArrayList<>();
        List<Match> maskMatches = new ArrayList<>();
        List<String> warnWords = new ArrayList<>();
        for (Match m : hits) {
            String action = m.meta() != null && m.meta().action() != null
                    ? m.meta().action().trim().toUpperCase() : "BLOCK";
            switch (action) {
                case "MASK" -> maskMatches.add(m);
                case "WARN" -> warnWords.add(m.word());
                default -> blockWords.add(m.word());
            }
        }
        if (!blockWords.isEmpty()) {
            return RuleOutcome.block(NAME, "敏感词命中: " + String.join(",", blockWords));
        }
        if (!maskMatches.isEmpty()) {
            List<int[]> ranges = new ArrayList<>();
            List<String> maskedWords = new ArrayList<>();
            for (Match m : maskMatches) {
                ranges.add(new int[]{m.start(), m.end()});
                maskedWords.add(m.word());
            }
            String masked = Masking.maskRanges(text, ranges);
            return RuleOutcome.mask(NAME, "敏感词脱敏: " + String.join(",", maskedWords), masked);
        }
        return RuleOutcome.warn(NAME, "敏感词告警: " + String.join(",", warnWords));
    }
}
