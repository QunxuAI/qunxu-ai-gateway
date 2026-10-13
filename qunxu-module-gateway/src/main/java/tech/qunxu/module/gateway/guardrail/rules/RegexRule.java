package tech.qunxu.module.gateway.guardrail.rules;

import tech.qunxu.module.gateway.guardrail.GuardrailContext;
import tech.qunxu.module.gateway.guardrail.GuardrailRule;
import tech.qunxu.module.gateway.guardrail.Masking;
import tech.qunxu.module.gateway.guardrail.RuleOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 正则围栏规则：按策略配置 {@code regex.patterns}（正则列表）匹配文本，动作为 {@code regex.action}
 * （{@code BLOCK}（默认）/{@code MASK}/{@code WARN}）。用于业务方自定义的黑白名单、格式化禁词等。
 *
 * <p>正则编译结果按字面量缓存（{@link ConcurrentHashMap}），避免每请求重复编译；非法正则记 warn 并跳过，
 * 不影响其余模式与主链。无配置模式时规则不触发。</p>
 */
@Component
public class RegexRule implements GuardrailRule {

    public static final String NAME = "REGEX";

    private static final Logger log = LoggerFactory.getLogger(RegexRule.class);
    private static final int MAX_PATTERNS = 64;

    private final Map<String, Pattern> cache = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int order() {
        return 40;
    }

    @Override
    public RuleOutcome evaluate(GuardrailContext context) {
        List<String> patterns = context.stringList("regex.patterns");
        if (patterns.isEmpty()) {
            return RuleOutcome.allow(NAME);
        }
        String text = context.text();
        if (text == null || text.isEmpty()) {
            return RuleOutcome.allow(NAME);
        }
        String action = context.string("regex.action", "BLOCK").toUpperCase();
        List<int[]> ranges = new ArrayList<>();
        List<String> matched = new ArrayList<>();
        int seen = 0;
        for (String raw : patterns) {
            if (seen++ >= MAX_PATTERNS) {
                break;
            }
            Pattern pattern = compile(raw);
            if (pattern == null) {
                continue;
            }
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                ranges.add(new int[]{matcher.start(), matcher.end()});
                if (matched.size() < 8) {
                    matched.add(raw);
                }
            }
        }
        if (ranges.isEmpty()) {
            return RuleOutcome.allow(NAME);
        }
        String detail = "正则命中: " + String.join(",", matched);
        return switch (action) {
            case "MASK" -> RuleOutcome.mask(NAME, detail, Masking.maskRanges(text, ranges));
            case "WARN" -> RuleOutcome.warn(NAME, detail);
            default -> RuleOutcome.block(NAME, detail);
        };
    }

    private Pattern compile(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return cache.computeIfAbsent(raw, key -> {
            try {
                return Pattern.compile(key);
            } catch (PatternSyntaxException e) {
                log.warn("非法围栏正则已跳过: {} ({})", key, e.getDescription());
                return null;
            }
        });
    }
}
