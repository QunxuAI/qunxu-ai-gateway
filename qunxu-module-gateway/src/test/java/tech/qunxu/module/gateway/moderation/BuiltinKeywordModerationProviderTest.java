package tech.qunxu.module.gateway.moderation;

import tech.qunxu.module.gateway.common.enums.Capability;
import tech.qunxu.module.gateway.guardrail.SensitiveWordService;
import tech.qunxu.module.gateway.persistence.entity.SensitiveWordEntity;
import tech.qunxu.module.gateway.persistence.mapper.SensitiveWordMapper;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link BuiltinKeywordModerationProvider} 单元测试（M7c）：验证内置关键词提供方复用敏感词 DFA 词库，
 * 并按「命中词动作 BLOCK 或级别 HIGH → BLOCK，其余命中 → REVIEW，无命中 → PASS」给出风险裁决与类别归集。
 */
class BuiltinKeywordModerationProviderTest {

    private static final ModerationContext CTX = new ModerationContext(
            "", "INPUT", Capability.CHAT, "m", "t", Map.of());

    private static SensitiveWordEntity word(String word, String category, String level, String action) {
        SensitiveWordEntity e = new SensitiveWordEntity();
        e.setWord(word);
        e.setCategory(category);
        e.setLevel(level);
        e.setAction(action);
        e.setEnabled(1);
        return e;
    }

    private static BuiltinKeywordModerationProvider provider(SensitiveWordEntity... dbWords) {
        SensitiveWordMapper mapper = mock(SensitiveWordMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(dbWords));
        SensitiveWordService service = new SensitiveWordService(mapper);
        service.refresh().block(Duration.ofSeconds(5));
        return new BuiltinKeywordModerationProvider(service);
    }

    private static ModerationResult moderate(ModerationProvider provider, String text) {
        return provider.moderate(text, CTX).block(Duration.ofSeconds(5));
    }

    @Test
    void exposesBuiltinName() {
        assertThat(provider().name()).isEqualTo(BuiltinKeywordModerationProvider.NAME);
        assertThat(BuiltinKeywordModerationProvider.NAME).isEqualTo("builtin");
    }

    @Test
    void blockActionWordYieldsBlock() {
        ModerationResult result = moderate(provider(), "赌博网站推荐");

        assertThat(result.decision()).isEqualTo(ModerationDecision.BLOCK);
        assertThat(result.blocked()).isTrue();
        assertThat(result.categories()).containsExactly("广告");
        assertThat(result.provider()).isEqualTo("builtin");
        assertThat(result.detail()).contains("1 处");
    }

    @Test
    void highLevelWordYieldsBlockEvenWithMaskAction() {
        ModerationResult result = moderate(
                provider(word("高危词条", "涉政", "HIGH", "MASK")), "涉及高危词条");

        assertThat(result.decision()).isEqualTo(ModerationDecision.BLOCK);
        assertThat(result.categories()).containsExactly("涉政");
    }

    @Test
    void maskActionWordYieldsReview() {
        ModerationResult result = moderate(provider(), "代办发票");

        assertThat(result.decision()).isEqualTo(ModerationDecision.REVIEW);
        assertThat(result.blocked()).isFalse();
        assertThat(result.flagged()).isTrue();
        assertThat(result.categories()).containsExactly("广告");
    }

    @Test
    void warnActionLowLevelWordYieldsReview() {
        ModerationResult result = moderate(
                provider(word("擦边词", "辱骂", "LOW", "WARN")), "有点擦边词");

        assertThat(result.decision()).isEqualTo(ModerationDecision.REVIEW);
        assertThat(result.categories()).containsExactly("辱骂");
    }

    @Test
    void aggregatesCategoriesAcrossMultipleHits() {
        ModerationResult result = moderate(provider(), "赌博与色情");

        assertThat(result.decision()).isEqualTo(ModerationDecision.BLOCK);
        assertThat(result.categories()).containsExactlyInAnyOrder("广告", "色情");
        assertThat(result.detail()).contains("2 处");
    }

    @Test
    void benignTextPasses() {
        ModerationResult result = moderate(provider(), "请帮我写一首关于春天的诗");

        assertThat(result.decision()).isEqualTo(ModerationDecision.PASS);
        assertThat(result.flagged()).isFalse();
        assertThat(result.categories()).isEmpty();
        assertThat(result.provider()).isEqualTo("builtin");
    }

    @Test
    void blankTextPasses() {
        assertThat(moderate(provider(), "").decision()).isEqualTo(ModerationDecision.PASS);
        assertThat(moderate(provider(), null).decision()).isEqualTo(ModerationDecision.PASS);
    }

    @Test
    void resultIsAvailableAsMono() {
        Mono<ModerationResult> mono = provider().moderate("赌博", CTX);
        assertThat(mono).isNotNull();
        assertThat(mono.block(Duration.ofSeconds(5)).decision()).isEqualTo(ModerationDecision.BLOCK);
    }
}
