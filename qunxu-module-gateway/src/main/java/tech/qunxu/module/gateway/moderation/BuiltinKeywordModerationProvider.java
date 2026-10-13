package tech.qunxu.module.gateway.moderation;

import tech.qunxu.module.gateway.guardrail.SensitiveWordService;
import tech.qunxu.module.gateway.guardrail.SensitiveWordTrie.Match;
import tech.qunxu.module.gateway.guardrail.SensitiveWordTrie.WordMeta;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 内置关键词内容审计提供方（{@code builtin}，默认）：复用 {@link SensitiveWordService} 的 DFA/Trie 词库，
 * 将命中词条按类别（涉政/色情/暴恐/广告/辱骂等）归类并给出风险裁决。
 *
 * <p>裁决规则：命中词中存在动作为 {@code BLOCK} 或级别为 {@code HIGH} 者 → {@link ModerationDecision#BLOCK}；
 * 其余命中 → {@link ModerationDecision#REVIEW}；无命中 → {@link ModerationDecision#PASS}。纯内存匹配，
 * 无外部依赖，作为兜底审计始终可用。</p>
 */
@Component
public class BuiltinKeywordModerationProvider implements ModerationProvider {

    public static final String NAME = "builtin";

    private final SensitiveWordService sensitiveWordService;

    public BuiltinKeywordModerationProvider(SensitiveWordService sensitiveWordService) {
        this.sensitiveWordService = sensitiveWordService;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Mono<ModerationResult> moderate(String text, ModerationContext context) {
        return Mono.fromCallable(() -> doModerate(text));
    }

    private ModerationResult doModerate(String text) {
        if (text == null || text.isEmpty()) {
            return ModerationResult.pass(NAME);
        }
        List<Match> hits = sensitiveWordService.findHits(text);
        if (hits.isEmpty()) {
            return ModerationResult.pass(NAME);
        }
        Set<String> categories = new LinkedHashSet<>();
        boolean block = false;
        for (Match m : hits) {
            WordMeta meta = m.meta();
            if (meta != null) {
                if (meta.category() != null && !meta.category().isBlank()) {
                    categories.add(meta.category().trim());
                }
                String action = meta.action() == null ? "" : meta.action().trim().toUpperCase();
                String level = meta.level() == null ? "" : meta.level().trim().toUpperCase();
                if ("BLOCK".equals(action) || "HIGH".equals(level)) {
                    block = true;
                }
            } else {
                block = true;
            }
        }
        String detail = "内置关键词命中 " + hits.size() + " 处";
        return ModerationResult.of(block ? ModerationDecision.BLOCK : ModerationDecision.REVIEW,
                List.copyOf(categories), detail, NAME);
    }
}
