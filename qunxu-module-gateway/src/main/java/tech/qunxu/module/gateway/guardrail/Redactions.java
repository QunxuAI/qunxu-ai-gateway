package tech.qunxu.module.gateway.guardrail;

import java.util.Map;

/**
 * 脱敏还原工具：把响应文本中的占位符替换回原文（借鉴 AstrLink 的「响应本地还原」）。
 *
 * <p>仅还原输入阶段登记的占位符（{@link RedactionCollector#snapshot()} 快照）；输出围栏自身产生的
 * {@code *} 掩码与未登记的占位符保持不变，避免把安全脱敏内容错误还原。</p>
 */
public final class Redactions {

    private Redactions() {
    }

    /**
     * 将文本中出现的每个占位符替换为其原文。
     *
     * @param text 待还原文本（可为 {@code null}）
     * @param map  「占位符 → 原文」映射（可为 {@code null} 或空）
     * @return 还原后的文本
     */
    public static String restore(String text, Map<String, String> map) {
        if (text == null || text.isEmpty() || map == null || map.isEmpty()) {
            return text;
        }
        String result = text;
        for (Map.Entry<String, String> entry : map.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            result = result.replace(entry.getKey(), entry.getValue());
        }
        return result;
    }
}
