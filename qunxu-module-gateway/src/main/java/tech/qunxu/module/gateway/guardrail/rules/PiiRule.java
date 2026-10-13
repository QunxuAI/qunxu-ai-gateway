package tech.qunxu.module.gateway.guardrail.rules;

import tech.qunxu.module.gateway.guardrail.GuardrailContext;
import tech.qunxu.module.gateway.guardrail.GuardrailRule;
import tech.qunxu.module.gateway.guardrail.Masking;
import tech.qunxu.module.gateway.guardrail.PlaceholderMasking;
import tech.qunxu.module.gateway.guardrail.RedactionCollector;
import tech.qunxu.module.gateway.guardrail.RuleOutcome;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PII（个人身份信息）围栏规则：以正则识别中国大陆手机号、身份证号、银行卡号与邮箱，按配置脱敏或拦截。
 *
 * <p>默认识别后<b>脱敏放行</b>（{@code pii.mask=true}）：脱敏模式由 {@code pii.maskMode}（缺省回退全局
 * {@code mask.mode}）决定——{@code star} 将命中区间替换为等长 {@code *}（不可逆，默认）；{@code placeholder}
 * 替换为 {@code {{PHONE_1}}} 等占位符并在 {@link RedactionCollector} 登记映射，供响应阶段本地还原。
 * 置 {@code pii.mask=false} 则拦截。身份证与银行卡号区间可能重叠，采用「先匹配者优先、跳过重叠区间」策略
 * 避免重复计入。可通过 {@code pii.enabled=false} 关闭。</p>
 */
@Component
public class PiiRule implements GuardrailRule {

    public static final String NAME = "PII";

    /** 模式定义：正则 + 展示标签 + 占位符类型标记。 */
    private record PatternDef(Pattern pattern, String label, String token) {
    }

    /** 身份证（18 位，末位可为 X）先于银行卡匹配，避免 18 位数字被重复归类。 */
    private static final PatternDef ID_CARD = new PatternDef(
            Pattern.compile("(?<!\\d)\\d{17}[\\dXx](?!\\d)"), "身份证", "ID_CARD");
    private static final PatternDef BANK_CARD = new PatternDef(
            Pattern.compile("(?<!\\d)\\d{16,19}(?!\\d)"), "银行卡", "BANK_CARD");
    private static final PatternDef PHONE = new PatternDef(
            Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)"), "手机号", "PHONE");
    private static final PatternDef EMAIL = new PatternDef(
            Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), "邮箱", "EMAIL");

    private static final PatternDef[] PATTERNS = {ID_CARD, BANK_CARD, PHONE, EMAIL};

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public RuleOutcome evaluate(GuardrailContext context) {
        if (!context.bool("pii.enabled", true)) {
            return RuleOutcome.allow(NAME);
        }
        String text = context.text();
        if (text == null || text.isEmpty()) {
            return RuleOutcome.allow(NAME);
        }
        List<int[]> ranges = new ArrayList<>();
        Map<String, List<int[]>> rangesByToken = new LinkedHashMap<>();
        Set<String> types = new LinkedHashSet<>();
        for (PatternDef def : PATTERNS) {
            collect(def, text, ranges, rangesByToken, types);
        }
        if (ranges.isEmpty()) {
            return RuleOutcome.allow(NAME);
        }
        String typeStr = String.join(",", types);
        if (context.bool("pii.mask", true)) {
            String mode = context.maskMode("pii", "star");
            String masked;
            if ("placeholder".equalsIgnoreCase(mode)) {
                masked = maskPlaceholders(text, rangesByToken, context.redactions());
            } else {
                masked = Masking.maskRanges(text, ranges);
            }
            return RuleOutcome.mask(NAME, "PII脱敏: " + typeStr, masked);
        }
        return RuleOutcome.block(NAME, "PII命中: " + typeStr);
    }

    /** 按类型分组逐类替换为占位符（各类区间互不重叠，可安全串行替换）。 */
    private String maskPlaceholders(String text, Map<String, List<int[]>> rangesByToken,
                                    RedactionCollector collector) {
        String masked = text;
        for (Map.Entry<String, List<int[]>> entry : rangesByToken.entrySet()) {
            masked = PlaceholderMasking.maskRanges(masked, entry.getValue(), entry.getKey(), collector);
        }
        return masked;
    }

    /** 收集一个模式的命中区间，跳过与已收集区间重叠者（先匹配者优先）。 */
    private void collect(PatternDef def, String text, List<int[]> ranges,
                         Map<String, List<int[]>> rangesByToken, Set<String> types) {
        Matcher matcher = def.pattern().matcher(text);
        while (matcher.find()) {
            int start = matcher.start();
            int end = matcher.end();
            if (overlaps(ranges, start, end)) {
                continue;
            }
            ranges.add(new int[]{start, end});
            rangesByToken.computeIfAbsent(def.token(), k -> new ArrayList<>()).add(new int[]{start, end});
            types.add(def.label());
        }
    }

    private boolean overlaps(List<int[]> ranges, int start, int end) {
        for (int[] r : ranges) {
            if (start < r[1] && r[0] < end) {
                return true;
            }
        }
        return false;
    }
}
