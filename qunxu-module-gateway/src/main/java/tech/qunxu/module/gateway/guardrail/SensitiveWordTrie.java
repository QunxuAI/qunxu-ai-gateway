package tech.qunxu.module.gateway.guardrail;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 敏感词 Trie（DFA）匹配器：以字符为边构建多模式串前缀树，对文本做一次线性扫描即可找出全部命中词。
 *
 * <p>匹配策略为<b>最长匹配、不重叠</b>：从每个位置沿树下行，记录能到达的最长词末，命中后从词尾继续扫描，
 * 避免同一字符被重复计入多个命中。英文按 {@link Character#toLowerCase} 归一以忽略大小写，中文不受影响。</p>
 *
 * <p>本类为纯内存数据结构、非线程安全；由 {@link SensitiveWordService} 构建后以不可变快照形式发布，
 * 匹配阶段只读，故多线程并发查询安全。</p>
 */
public final class SensitiveWordTrie {

    private final Node root = new Node();
    private int wordCount;

    /** 追加一个敏感词及其元数据；空词忽略。 */
    public void add(String word, WordMeta meta) {
        if (word == null || word.isEmpty()) {
            return;
        }
        Node node = root;
        for (int i = 0; i < word.length(); i++) {
            char c = normalize(word.charAt(i));
            node = node.children.computeIfAbsent(c, k -> new Node());
        }
        if (!node.end) {
            wordCount++;
        }
        node.end = true;
        node.meta = meta;
    }

    /** 查找文本中全部命中（最长匹配、不重叠），按出现顺序返回。 */
    public List<Match> findHits(String text) {
        List<Match> hits = new ArrayList<>();
        if (text == null || text.isEmpty() || root.children.isEmpty()) {
            return hits;
        }
        int n = text.length();
        int i = 0;
        while (i < n) {
            Node node = root;
            int longestEnd = -1;
            WordMeta longestMeta = null;
            for (int j = i; j < n; j++) {
                Node next = node.children.get(normalize(text.charAt(j)));
                if (next == null) {
                    break;
                }
                node = next;
                if (node.end) {
                    longestEnd = j + 1;
                    longestMeta = node.meta;
                }
            }
            if (longestEnd > 0) {
                hits.add(new Match(text.substring(i, longestEnd), i, longestEnd, longestMeta));
                i = longestEnd;
            } else {
                i++;
            }
        }
        return hits;
    }

    /** 是否包含任一敏感词。 */
    public boolean contains(String text) {
        return !findHits(text).isEmpty();
    }

    /** 将全部命中替换为 {@code maskChar}（等长），返回脱敏后的文本。 */
    public String mask(String text, char maskChar) {
        List<Match> hits = findHits(text);
        if (hits.isEmpty()) {
            return text;
        }
        char[] chars = text.toCharArray();
        for (Match m : hits) {
            for (int k = m.start(); k < m.end(); k++) {
                chars[k] = maskChar;
            }
        }
        return new String(chars);
    }

    public boolean isEmpty() {
        return wordCount == 0;
    }

    public int size() {
        return wordCount;
    }

    private static char normalize(char c) {
        return Character.toLowerCase(c);
    }

    private static final class Node {
        final Map<Character, Node> children = new HashMap<>();
        boolean end;
        WordMeta meta;
    }

    /**
     * 敏感词元数据。
     *
     * @param category 分类（涉政/色情/暴恐/广告/辱骂等）
     * @param level    级别（LOW/MEDIUM/HIGH）
     * @param action   命中动作（BLOCK/MASK/WARN）
     */
    public record WordMeta(String category, String level, String action) {
    }

    /**
     * 一次命中。
     *
     * @param word  命中的敏感词（原文片段）
     * @param start 起始下标（含）
     * @param end   结束下标（不含）
     * @param meta  词元数据（可空）
     */
    public record Match(String word, int start, int end, WordMeta meta) {
    }
}
