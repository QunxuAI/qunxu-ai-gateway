package tech.qunxu.module.gateway.guardrail.rules;

import tech.qunxu.module.gateway.guardrail.GuardrailContext;
import tech.qunxu.module.gateway.guardrail.GuardrailDirection;
import tech.qunxu.module.gateway.guardrail.GuardrailRule;
import tech.qunxu.module.gateway.guardrail.RuleOutcome;
import org.springframework.stereotype.Component;

/**
 * 长度围栏规则：限制输入/输出文本长度，超限拦截（避免超长上下文击穿下游或放大攻击面）。
 *
 * <p>阈值来自扁平配置 {@code maxInputLength}/{@code maxOutputLength}（由 {@code gateway.guardrail.*}
 * 提供默认值，策略可覆盖）。阈值 {@code <=0} 视为不限制。作为最廉价的规则置于最前（order 最小）。</p>
 */
@Component
public class LengthRule implements GuardrailRule {

    public static final String NAME = "LENGTH";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int order() {
        return 5;
    }

    @Override
    public RuleOutcome evaluate(GuardrailContext context) {
        String text = context.text();
        if (text == null || text.isEmpty()) {
            return RuleOutcome.allow(NAME);
        }
        boolean input = context.direction() == GuardrailDirection.INPUT;
        int max = context.integer(input ? "maxInputLength" : "maxOutputLength", input ? 32768 : 65536);
        if (max > 0 && text.length() > max) {
            String label = input ? "输入" : "输出";
            return RuleOutcome.block(NAME, label + "长度超限: " + text.length() + " > " + max);
        }
        return RuleOutcome.allow(NAME);
    }
}
