package tech.qunxu.module.gateway.guardrail;

import java.util.List;

/**
 * 文本脱敏工具：按字符区间将命中内容替换为掩码字符，保持文本等长（便于定位与不破坏结构）。
 *
 * <p>区间可重叠、可乱序，内部以布尔标记数组求并集后统一替换，故多规则/多命中叠加安全。</p>
 */
public final class Masking {

    /** 默认掩码字符。 */
    public static final char DEFAULT_MASK = '*';

    private Masking() {
    }

    /**
     * 将若干 {@code [start,end)} 区间内的字符替换为 {@code mask}。
     *
     * @param text   原文
     * @param ranges 命中区间列表，每项为 {@code int[]{start, end}}（end 不含）
     * @param mask   掩码字符
     * @return 脱敏后的等长文本；无有效区间时返回原文
     */
    public static String maskRanges(String text, List<int[]> ranges, char mask) {
        if (text == null || text.isEmpty() || ranges == null || ranges.isEmpty()) {
            return text;
        }
        char[] chars = text.toCharArray();
        boolean touched = false;
        for (int[] range : ranges) {
            if (range == null || range.length < 2) {
                continue;
            }
            int start = Math.max(0, range[0]);
            int end = Math.min(chars.length, range[1]);
            for (int i = start; i < end; i++) {
                chars[i] = mask;
                touched = true;
            }
        }
        return touched ? new String(chars) : text;
    }

    /** 以默认掩码字符替换区间。 */
    public static String maskRanges(String text, List<int[]> ranges) {
        return maskRanges(text, ranges, DEFAULT_MASK);
    }
}
