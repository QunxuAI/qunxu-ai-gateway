package tech.qunxu.module.gateway.guardrail;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 可逆脱敏工具单元测试（P0）：占位符替换、重叠去重、收集器映射、响应还原。
 */
class PlaceholderMaskingTest {

    @Test
    void replacesRangesWithPlaceholdersAndRegistersMapping() {
        RedactionCollector collector = new RedactionCollector();
        String masked = PlaceholderMasking.maskRanges(
                "联系 13800138000 或 bob@example.com",
                List.of(new int[]{3, 14}, new int[]{17, 32}), "PHONE", collector);

        assertThat(masked).isEqualTo("联系 {{PHONE_1}} 或 {{PHONE_2}}");
        assertThat(collector.snapshot()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "{{PHONE_1}}", "13800138000",
                "{{PHONE_2}}", "bob@example.com"));
    }

    @Test
    void skipsOverlappingRangesFirstMatchWins() {
        RedactionCollector collector = new RedactionCollector();
        // 两个重叠区间：仅保留先出现者
        String masked = PlaceholderMasking.maskRanges(
                "abcde",
                List.of(new int[]{0, 4}, new int[]{2, 5}), "X", collector);

        assertThat(masked).isEqualTo("{{X_1}}e");
        assertThat(collector.snapshot()).containsExactlyEntriesOf(Map.of("{{X_1}}", "abcd"));
    }

    @Test
    void returnsOriginalWhenNoValidRangesOrCollectorNull() {
        RedactionCollector collector = new RedactionCollector();
        assertThat(PlaceholderMasking.maskRanges("abc", List.of(), "X", collector)).isEqualTo("abc");
        assertThat(PlaceholderMasking.maskRanges("abc", List.of(new int[]{0, 3}), "X", null)).isEqualTo("abc");
        assertThat(PlaceholderMasking.maskRanges(null, List.of(new int[]{0, 1}), "X", collector)).isNull();
    }

    @Test
    void clampsOutOfBoundsRanges() {
        RedactionCollector collector = new RedactionCollector();
        String masked = PlaceholderMasking.maskRanges(
                "12345",
                List.of(new int[]{-2, 3}, new int[]{3, 99}), "N", collector);

        assertThat(masked).isEqualTo("{{N_1}}{{N_2}}");
        assertThat(collector.snapshot()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "{{N_1}}", "123",
                "{{N_2}}", "45"));
    }

    @Test
    void collectorNumbersAreUniqueAcrossRegistrations() {
        RedactionCollector collector = new RedactionCollector();
        collector.register("EMAIL", "a@b.com");
        collector.register("EMAIL", "c@d.com");

        assertThat(collector.snapshot()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "{{EMAIL_1}}", "a@b.com",
                "{{EMAIL_2}}", "c@d.com"));
    }

    @Test
    void restoresPlaceholdersBackToOriginals() {
        Map<String, String> map = Map.of(
                "{{PHONE_1}}", "13800138000",
                "{{EMAIL_1}}", "bob@example.com");
        String restored = Redactions.restore("已联系 {{PHONE_1}}（{{EMAIL_1}}）", map);

        assertThat(restored).isEqualTo("已联系 13800138000（bob@example.com）");
    }

    @Test
    void restoreIsNoOpForNullOrEmptyInput() {
        assertThat(Redactions.restore(null, Map.of("{{A}}", "1"))).isNull();
        assertThat(Redactions.restore("abc", null)).isEqualTo("abc");
        assertThat(Redactions.restore("abc", Map.of())).isEqualTo("abc");
    }
}
