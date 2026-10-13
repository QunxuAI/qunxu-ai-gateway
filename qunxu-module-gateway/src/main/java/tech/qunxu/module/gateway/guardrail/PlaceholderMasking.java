package tech.qunxu.module.gateway.guardrail;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 占位符脱敏工具：将命中区间替换为 {@code {{TYPE_n}}} 占位符，并在 {@link RedactionCollector} 中登记
 * 「占位符 → 原文」映射。与 {@link Masking}（等长 {@code *} 掩码）互补：占位符<b>可逆</b>，等长掩码不可逆。
 *
 * <p>区间可重叠、可乱序：内部按起点排序并跳过与已选区间重叠者，再<b>从后往前</b>替换，避免位移错位。</p>
 */
public final class PlaceholderMasking {

    private PlaceholderMasking() {
    }

    /**
     * 以占位符替换 {@code [start,end)} 区间内的文本。
     *
     * @param text      原文
     * @param ranges    命中区间列表，每项为 {@code int[]{start, end}}（end 不含）
     * @param type      占位符类型标记（如 PHONE、EMAIL），最终形如 {@code {{PHONE_1}}}
     * @param collector 收集器（登记映射；为 {@code null} 时原样返回）
     * @return 替换后的文本；无有效区间或收集器缺失时返回原文
     */
    public static String maskRanges(String text, List<int[]> ranges, String type, RedactionCollector collector) {
        if (text == null || text.isEmpty() || ranges == null || ranges.isEmpty() || collector == null) {
            return text;
        }
        List<int[]> selected = select(ranges, text.length());
        if (selected.isEmpty()) {
            return text;
        }
        // 先按文档顺序登记占位符（编号从左到右递增），再统一从后往前替换，避免位移错位
        List<String> placeholders = new ArrayList<>(selected.size());
        for (int[] r : selected) {
            String original = text.substring(r[0], r[1]);
            placeholders.add(collector.register(type, original));
        }
        StringBuilder sb = new StringBuilder(text);
        for (int i = selected.size() - 1; i >= 0; i--) {
            int[] r = selected.get(i);
            sb.replace(r[0], r[1], placeholders.get(i));
        }
        return sb.toString();
    }

    /** 排序 + 重叠去重（先匹配者优先），并裁剪越界区间。 */
    private static List<int[]> select(List<int[]> ranges, int length) {
        List<int[]> sorted = new ArrayList<>(ranges);
        sorted.sort(Comparator.comparingInt(r -> r[0]));
        List<int[]> out = new ArrayList<>(sorted.size());
        for (int[] r : sorted) {
            if (r == null || r.length < 2) {
                continue;
            }
            int start = Math.max(0, r[0]);
            int end = Math.min(length, r[1]);
            if (start >= end) {
                continue;
            }
            if (!out.isEmpty() && start < out.get(out.size() - 1)[1]) {
                continue;
            }
            out.add(new int[]{start, end});
        }
        return out;
    }
}
