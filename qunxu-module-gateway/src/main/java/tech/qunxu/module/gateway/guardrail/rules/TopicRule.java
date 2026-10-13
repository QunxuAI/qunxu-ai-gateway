package tech.qunxu.module.gateway.guardrail.rules;

import tech.qunxu.module.gateway.guardrail.GuardrailContext;
import tech.qunxu.module.gateway.guardrail.GuardrailRule;
import tech.qunxu.module.gateway.guardrail.Masking;
import tech.qunxu.module.gateway.guardrail.RuleOutcome;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 话题围栏规则：按策略配置 {@code topic.keywords}（关键词列表）做大小写不敏感的包含匹配，
 * 动作为 {@code topic.action}（{@code BLOCK}（默认）/{@code MASK}/{@code WARN}）。用于封禁特定主题（如竞品、敏感议题）。
 *
 * <p>与敏感词库的区别：话题关键词由策略即时配置、规模小、按整词包含匹配；敏感词走 DFA 大词库。无配置关键词时不触发。</p>
 */
@Component
public class TopicRule implements GuardrailRule {

    public static final String NAME = "TOPIC";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int order() {
        return 50;
    }

    @Override
    public RuleOutcome evaluate(GuardrailContext context) {
        List<String> keywords = context.stringList("topic.keywords");
        if (keywords.isEmpty()) {
            return RuleOutcome.allow(NAME);
        }
        String text = context.text();
        if (text == null || text.isEmpty()) {
            return RuleOutcome.allow(NAME);
        }
        String lowerText = text.toLowerCase();
        String action = context.string("topic.action", "BLOCK").toUpperCase();
        List<int[]> ranges = new ArrayList<>();
        List<String> matched = new ArrayList<>();
        for (String keyword : keywords) {
            if (keyword == null || keyword.isBlank()) {
                continue;
            }
            String lowerKey = keyword.toLowerCase();
            int from = 0;
            int idx;
            while ((idx = lowerText.indexOf(lowerKey, from)) >= 0) {
                ranges.add(new int[]{idx, idx + lowerKey.length()});
                from = idx + lowerKey.length();
                if (!matched.contains(keyword)) {
                    matched.add(keyword);
                }
            }
        }
        if (ranges.isEmpty()) {
            return RuleOutcome.allow(NAME);
        }
        String detail = "话题命中: " + String.join(",", matched);
        return switch (action) {
            case "MASK" -> RuleOutcome.mask(NAME, detail, Masking.maskRanges(text, ranges));
            case "WARN" -> RuleOutcome.warn(NAME, detail);
            default -> RuleOutcome.block(NAME, detail);
        };
    }
}
