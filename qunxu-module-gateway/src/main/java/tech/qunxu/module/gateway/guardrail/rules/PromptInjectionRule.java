package tech.qunxu.module.gateway.guardrail.rules;

import tech.qunxu.module.gateway.guardrail.GuardrailContext;
import tech.qunxu.module.gateway.guardrail.GuardrailRule;
import tech.qunxu.module.gateway.guardrail.RuleOutcome;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Prompt 注入围栏规则：识别常见的越狱/指令覆盖/系统提示窃取类话术（中英文），按配置告警或拦截。
 *
 * <p>默认动作为 {@code WARN}（放行但记录命中，供审计与人工复核），可经策略配置 {@code promptInjection.action=BLOCK}
 * 升级为拦截。可通过 {@code promptInjection.enabled=false} 关闭。规则为启发式，宁可漏报不可误伤，故默认告警。</p>
 */
@Component
public class PromptInjectionRule implements GuardrailRule {

    public static final String NAME = "PROMPT_INJECTION";

    private static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("(?i)ignore\\s+(all\\s+|any\\s+)?(previous|prior|above|earlier)\\s+(instructions|prompts|rules|directives)"),
            Pattern.compile("(?i)disregard\\s+(all\\s+|any\\s+)?(previous|prior|above|earlier)\\s+(instructions|prompts|rules)"),
            Pattern.compile("(?i)forget\\s+(all\\s+|everything\\s+)?(previous|prior|above)\\s+(instructions|rules)"),
            Pattern.compile("(?i)\\b(system|developer)\\s+prompt\\b"),
            Pattern.compile("(?i)\\bjail\\s*break(ing)?\\b"),
            Pattern.compile("(?i)\\bDAN\\s+(mode|persona)\\b"),
            Pattern.compile("(?i)developer\\s+mode\\s+(enabled|activated)"),
            Pattern.compile("(?i)you\\s+are\\s+now\\s+(in\\s+)?(developer|dan|unrestricted)\\s+mode"),
            Pattern.compile("忽略(之前|以上|上面|先前|前面)(的)?(所有|全部)?(指令|提示|规则|设定|约束)"),
            Pattern.compile("无视(之前|以上|上面|所有)(的)?(指令|规则|设定|约束|限制)"),
            Pattern.compile("忘记(之前|以上|上面)(的)?(所有|全部)?(指令|规则|设定|身份)"),
            Pattern.compile("你(现在|从现在开始|从现在起)(是|扮演|充当)"),
            Pattern.compile("(越狱|开发者|上帝)模式"),
            Pattern.compile("(泄露|告诉我|输出|打印|复述)(你的)?(系统提示词?|初始指令|system\\s*prompt)"),
            Pattern.compile("(扮演|假装你是|装作你是).{0,12}(不受限制|没有限制|无限制|开发者)")
    );

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int order() {
        return 30;
    }

    @Override
    public RuleOutcome evaluate(GuardrailContext context) {
        if (!context.bool("promptInjection.enabled", true)) {
            return RuleOutcome.allow(NAME);
        }
        String text = context.text();
        if (text == null || text.isEmpty()) {
            return RuleOutcome.allow(NAME);
        }
        if (!matches(text)) {
            return RuleOutcome.allow(NAME);
        }
        String action = context.string("promptInjection.action", "WARN").toUpperCase();
        if ("BLOCK".equals(action)) {
            return RuleOutcome.block(NAME, "疑似 Prompt 注入/越狱话术");
        }
        return RuleOutcome.warn(NAME, "疑似 Prompt 注入/越狱话术");
    }

    private boolean matches(String text) {
        for (Pattern p : PATTERNS) {
            if (p.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }
}
