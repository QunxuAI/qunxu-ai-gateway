package tech.qunxu.module.gateway.guardrail;

import tech.qunxu.module.gateway.guardrail.SensitiveWordTrie.Match;
import tech.qunxu.module.gateway.guardrail.SensitiveWordTrie.WordMeta;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SensitiveWordTrie} 单元测试（M7a）：验证 DFA 前缀树的<b>最长匹配、不重叠</b>扫描语义、
 * 英文大小写归一、等长脱敏、空词/空文本健壮性，以及词条计数与元数据覆盖。
 */
class SensitiveWordTrieTest {

    private static final WordMeta BLOCK = new WordMeta("广告", "HIGH", "BLOCK");

    private static SensitiveWordTrie trieOf(String... words) {
        SensitiveWordTrie trie = new SensitiveWordTrie();
        for (String w : words) {
            trie.add(w, BLOCK);
        }
        return trie;
    }

    @Test
    void emptyTrieMatchesNothing() {
        SensitiveWordTrie trie = new SensitiveWordTrie();
        assertThat(trie.isEmpty()).isTrue();
        assertThat(trie.size()).isZero();
        assertThat(trie.findHits("赌博")).isEmpty();
        assertThat(trie.contains("赌博")).isFalse();
        // 无命中时原样返回，不做任何替换
        assertThat(trie.mask("赌博", '*')).isEqualTo("赌博");
    }

    @Test
    void blankWordsAreIgnored() {
        SensitiveWordTrie trie = new SensitiveWordTrie();
        trie.add(null, BLOCK);
        trie.add("", BLOCK);
        assertThat(trie.size()).isZero();
        assertThat(trie.isEmpty()).isTrue();
        assertThat(trie.findHits("任意文本")).isEmpty();
    }

    @Test
    void findsHitWithExactRangeAndMeta() {
        SensitiveWordTrie trie = trieOf("赌博");
        List<Match> hits = trie.findHits("我要赌博赚钱");

        assertThat(hits).hasSize(1);
        Match m = hits.get(0);
        assertThat(m.word()).isEqualTo("赌博");
        assertThat(m.start()).isEqualTo(2);
        assertThat(m.end()).isEqualTo(4);
        assertThat(m.meta()).isEqualTo(BLOCK);
        assertThat(trie.contains("我要赌博赚钱")).isTrue();
    }

    @Test
    void longestMatchWinsAndConsumesItsSpan() {
        SensitiveWordTrie trie = trieOf("发票", "代办发票");
        List<Match> hits = trie.findHits("代办发票");

        // 最长匹配：命中「代办发票」而非「发票」，且同一区间不重复计入
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).word()).isEqualTo("代办发票");
        assertThat(hits.get(0).start()).isZero();
        assertThat(hits.get(0).end()).isEqualTo(4);
    }

    @Test
    void multipleHitsReturnedInOrderWithoutOverlap() {
        SensitiveWordTrie trie = trieOf("赌博", "诈骗");
        List<Match> hits = trie.findHits("赌博和诈骗都违法，赌博不行");

        assertThat(hits).extracting(Match::word).containsExactly("赌博", "诈骗", "赌博");
        for (int i = 1; i < hits.size(); i++) {
            assertThat(hits.get(i).start()).isGreaterThanOrEqualTo(hits.get(i - 1).end());
        }
    }

    @Test
    void englishMatchingIsCaseInsensitive() {
        SensitiveWordTrie trie = trieOf("Gambling");

        assertThat(trie.findHits("go gambling now")).extracting(Match::word).containsExactly("gambling");
        assertThat(trie.findHits("GAMBLING!")).extracting(Match::word).containsExactly("GAMBLING");
        assertThat(trie.findHits("gamboling")).isEmpty();
    }

    @Test
    void maskKeepsTextLength() {
        SensitiveWordTrie trie = trieOf("赌博");

        assertThat(trie.mask("我要赌博赚钱", '*')).isEqualTo("我要**赚钱");
        assertThat(trie.mask("我要赌博赚钱", '#')).isEqualTo("我要##赚钱");
        assertThat(trie.mask("我要赌博赚钱", '*')).hasSize("我要赌博赚钱".length());
    }

    @Test
    void maskReplacesEveryHit() {
        SensitiveWordTrie trie = trieOf("赌博", "诈骗");
        assertThat(trie.mask("赌博与诈骗", '*')).isEqualTo("**与**");
    }

    @Test
    void reAddingSameWordOverridesMetaWithoutInflatingCount() {
        SensitiveWordTrie trie = new SensitiveWordTrie();
        trie.add("赌博", BLOCK);
        trie.add("赌博", new WordMeta("广告", "MEDIUM", "MASK"));

        assertThat(trie.size()).isEqualTo(1);
        assertThat(trie.findHits("赌博").get(0).meta().action()).isEqualTo("MASK");
    }

    @Test
    void nullAndEmptyTextYieldNoHits() {
        SensitiveWordTrie trie = trieOf("赌博");
        assertThat(trie.findHits(null)).isEmpty();
        assertThat(trie.findHits("")).isEmpty();
        assertThat(trie.contains(null)).isFalse();
    }
}
