package tech.qunxu.module.gateway.guardrail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 安全围栏引擎：按 {@link GuardrailRule#order()} 升序串联全部规则，对文本做聚合裁决。
 *
 * <p>聚合语义：<b>BLOCK 优先且短路</b>——任一规则拦截即返回，携带命中规则集与原因；否则 MASK 规则逐步脱敏
 * （后一规则以前一规则的脱敏文本为输入）；WARN 类规则放行但记入命中集，供审计观测。最终返回
 * {@link GuardrailOutcome}（裁决 + 命中规则名 + 原因 + 最终文本）。</p>
 *
 * <p>健壮性：单条规则抛异常时记 warn 并跳过（fail-safe），避免个别规则缺陷阻断全部流量。</p>
 */
@Component
public class GuardrailEngine {

    private static final Logger log = LoggerFactory.getLogger(GuardrailEngine.class);

    private final List<GuardrailRule> rules;

    public GuardrailEngine(List<GuardrailRule> rules) {
        this.rules = rules == null ? List.of()
                : rules.stream().sorted(Comparator.comparingInt(GuardrailRule::order)).toList();
        if (log.isInfoEnabled()) {
            List<String> names = this.rules.stream()
                    .map(r -> r.name() + "(" + r.order() + ")")
                    .toList();
            log.info("Guardrail engine initialized with {} rule(s): {}", names.size(), names);
        }
    }

    /**
     * 对上下文文本执行全部规则并聚合裁决。
     *
     * @param context 校验上下文
     * @return 聚合裁决；文本为空或无规则时为 ALLOW
     */
    public GuardrailOutcome evaluate(GuardrailContext context) {
        if (context == null) {
            return GuardrailOutcome.allow("");
        }
        String text = context.text() == null ? "" : context.text();
        if (text.isEmpty() || rules.isEmpty()) {
            return GuardrailOutcome.allow(text);
        }
        GuardrailDecision decision = GuardrailDecision.ALLOW;
        List<String> hits = new ArrayList<>();
        String reason = null;

        for (GuardrailRule rule : rules) {
            RuleOutcome outcome;
            try {
                outcome = rule.evaluate(context.withText(text));
            } catch (RuntimeException e) {
                log.warn("围栏规则 {} 执行异常，已跳过: {}", rule.name(), e.toString());
                continue;
            }
            if (outcome == null) {
                continue;
            }
            if (outcome.triggered()) {
                hits.add(rule.name());
            }
            switch (outcome.decision()) {
                case BLOCK -> {
                    reason = outcome.reason();
                    return new GuardrailOutcome(GuardrailDecision.BLOCK, join(hits), reason, text);
                }
                case MASK -> {
                    decision = GuardrailDecision.MASK;
                    if (outcome.maskedText() != null) {
                        text = outcome.maskedText();
                    }
                    if (reason == null) {
                        reason = outcome.reason();
                    }
                }
                case ALLOW -> {
                    if (reason == null && outcome.reason() != null) {
                        reason = outcome.reason();
                    }
                }
            }
        }
        return new GuardrailOutcome(decision, join(hits), reason, text);
    }

    /** 只读规则视图（供测试/诊断）。 */
    public List<GuardrailRule> getRules() {
        return rules;
    }

    private static String join(List<String> hits) {
        return hits.isEmpty() ? null : String.join(",", hits);
    }
}
