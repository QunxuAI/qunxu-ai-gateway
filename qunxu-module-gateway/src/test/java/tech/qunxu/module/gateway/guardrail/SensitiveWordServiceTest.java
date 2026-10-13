package tech.qunxu.module.gateway.guardrail;

import tech.qunxu.module.gateway.guardrail.SensitiveWordTrie.Match;
import tech.qunxu.module.gateway.persistence.entity.SensitiveWordEntity;
import tech.qunxu.module.gateway.persistence.mapper.SensitiveWordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link SensitiveWordService} 单元测试（M7a）：验证 classpath 种子库加载、数据库词条与种子库的<b>合并刷新</b>、
 * 词条覆盖语义、空/非法词条跳过，以及查询失败时的<b>快照兜底</b>（沿用现有词库，绝不影响主链）。
 */
class SensitiveWordServiceTest {

    private SensitiveWordMapper mapper;
    private SensitiveWordService service;

    @BeforeEach
    void setUp() {
        mapper = mock(SensitiveWordMapper.class);
        service = new SensitiveWordService(mapper);
    }

    private static SensitiveWordEntity word(String word, String category, String level, String action) {
        SensitiveWordEntity e = new SensitiveWordEntity();
        e.setWord(word);
        e.setCategory(category);
        e.setLevel(level);
        e.setAction(action);
        e.setEnabled(1);
        return e;
    }

    @Test
    void seedLibraryIsLoadedOnConstruction() {
        assertThat(service.size()).isPositive();
        assertThat(service.contains("赌博")).isTrue();
        assertThat(service.contains("今天天气不错")).isFalse();
        assertThat(service.current()).isNotNull();
        assertThat(service.current().size()).isEqualTo(service.size());
    }

    @Test
    void hitsCarrySeedMetadata() {
        List<Match> hits = service.findHits("代办发票请联系");

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).word()).isEqualTo("代办发票");
        assertThat(hits.get(0).meta().action()).isEqualTo("MASK");
        assertThat(hits.get(0).meta().category()).isEqualTo("广告");
    }

    @Test
    void maskUsesStarByDefault() {
        assertThat(service.mask("我要赌博")).isEqualTo("我要**");
        assertThat(service.mask("我要赌博", '#')).isEqualTo("我要##");
        assertThat(service.mask("无害文本")).isEqualTo("无害文本");
    }

    @Test
    void refreshMergesDatabaseWordsWithSeed() {
        int seedSize = service.size();
        when(mapper.selectList(any())).thenReturn(List.of(
                word("内部黑名单词", "广告", "LOW", "WARN")));

        Integer total = service.refresh().block(Duration.ofSeconds(5));

        assertThat(total).isEqualTo(seedSize + 1);
        assertThat(service.size()).isEqualTo(seedSize + 1);
        assertThat(service.contains("内部黑名单词")).isTrue();
        // 种子词条在刷新后依然生效
        assertThat(service.contains("赌博")).isTrue();
    }

    @Test
    void databaseWordOverridesSeedWordMetadata() {
        when(mapper.selectList(any())).thenReturn(List.of(
                word("赌博", "广告", "LOW", "WARN")));
        service.refresh().block(Duration.ofSeconds(5));

        List<Match> hits = service.findHits("赌博");
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).meta().action()).isEqualTo("WARN");
    }

    @Test
    void blankAndMalformedDatabaseRowsAreSkipped() {
        int seedSize = service.size();
        when(mapper.selectList(any())).thenReturn(Arrays.asList(
                null,
                word("   ", "广告", "LOW", "WARN"),
                word(null, "广告", "LOW", "WARN"),
                word("有效词条", "广告", "LOW", "WARN")));

        Integer total = service.refresh().block(Duration.ofSeconds(5));

        assertThat(total).isEqualTo(seedSize + 1);
        assertThat(service.contains("有效词条")).isTrue();
    }

    @Test
    void refreshWithNoDatabaseRowsKeepsSeedOnly() {
        int seedSize = service.size();
        when(mapper.selectList(any())).thenReturn(null);

        assertThat(service.refresh().block(Duration.ofSeconds(5))).isEqualTo(seedSize);
        assertThat(service.size()).isEqualTo(seedSize);
        assertThat(service.contains("赌博")).isTrue();
    }

    @Test
    void refreshFailureFallsBackToCurrentSnapshot() {
        int seedSize = service.size();
        when(mapper.selectList(any())).thenThrow(new IllegalStateException("sensitive_word 表不存在"));

        Integer total = service.refresh().block(Duration.ofSeconds(5));

        // 失败开放：返回现有快照词条数，词库仍可正常匹配
        assertThat(total).isEqualTo(seedSize);
        assertThat(service.size()).isEqualTo(seedSize);
        assertThat(service.contains("赌博")).isTrue();
    }

    @Test
    void refreshIsRepeatableAndIdempotentForSameRows() {
        when(mapper.selectList(any())).thenReturn(List.of(word("重复刷新词", "广告", "LOW", "WARN")));

        Integer first = service.refresh().block(Duration.ofSeconds(5));
        Integer second = service.refresh().block(Duration.ofSeconds(5));

        assertThat(second).isEqualTo(first);
        assertThat(service.contains("重复刷新词")).isTrue();
    }
}
