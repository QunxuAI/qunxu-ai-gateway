package tech.qunxu.module.gateway.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import tech.qunxu.module.gateway.admin.AdminPage;
import tech.qunxu.module.gateway.config.ConfigVersionPublisher;
import tech.qunxu.module.gateway.config.GatewayProperties;
import tech.qunxu.module.gateway.persistence.entity.SensitiveWordEntity;
import tech.qunxu.module.gateway.persistence.mapper.SensitiveWordMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 敏感词管理服务（{@code /admin/sensitive-words}）。
 *
 * <p>词条写入后必须<b>重建 Trie 快照</b>才对匹配生效，故所有变更都经
 * {@link ConfigVersionPublisher#TYPE_SENSITIVE_WORD} 触发本实例重建并广播给其它实例。</p>
 *
 * <p>另提供批量导入：按词面去重（库内已存在与本次提交内重复的均跳过），单次上限
 * {@value #MAX_BATCH} 条，避免一次提交把 boundedElastic 线程占住过久。</p>
 */
@Service
public class SensitiveWordAdminService extends AbstractConfigAdminService<SensitiveWordEntity> {

    /** 词条级别取值。 */
    public static final List<String> LEVELS = List.of("LOW", "MEDIUM", "HIGH");
    /** 命中动作取值。 */
    public static final List<String> ACTIONS = List.of("BLOCK", "MASK", "WARN");
    /** 单次批量导入上限。 */
    public static final int MAX_BATCH = 5000;
    /** 批量导入响应中保留的警告条数上限。 */
    private static final int MAX_WARNINGS = 20;

    private static final Logger log = LoggerFactory.getLogger(SensitiveWordAdminService.class);

    private final SensitiveWordMapper wordMapper;
    private final ConfigVersionPublisher publisher;

    public SensitiveWordAdminService(SensitiveWordMapper wordMapper,
                                     ConfigVersionPublisher publisher,
                                     GatewayProperties properties,
                                     ObjectMapper objectMapper) {
        super(properties, objectMapper);
        this.wordMapper = wordMapper;
        this.publisher = publisher;
    }

    @Override
    protected BaseMapper<SensitiveWordEntity> mapper() {
        return wordMapper;
    }

    @Override
    protected String resourceLabel() {
        return "敏感词";
    }

    /** 分页查询敏感词。{@code keyword} 模糊匹配词面，{@code category}/{@code level}/{@code enabled} 精确过滤。 */
    public Mono<AdminPage<SensitiveWordEntity>> query(String keyword, String category, String level,
                                                      Integer enabled, Integer page, Integer size) {
        return page(filters(keyword, category, level, enabled)
                .orderByAsc("level").orderByDesc("id"), page, size);
    }

    /** 列出全部敏感词（不分页，受过滤条件约束）。 */
    public Mono<List<SensitiveWordEntity>> queryAll(String keyword, String category, String level, Integer enabled) {
        return list(filters(keyword, category, level, enabled).orderByAsc("level").orderByDesc("id"));
    }

    private QueryWrapper<SensitiveWordEntity> filters(String keyword, String category, String level, Integer enabled) {
        QueryWrapper<SensitiveWordEntity> wrapper = Wrappers.query();
        if (hasText(keyword)) {
            wrapper.like("word", keyword.trim());
        }
        if (hasText(category)) {
            wrapper.eq("category", category.trim());
        }
        if (hasText(level)) {
            wrapper.eq("level", level.trim().toUpperCase());
        }
        if (enabled != null) {
            wrapper.eq("enabled", enabled);
        }
        return wrapper;
    }

    /**
     * 批量导入敏感词：库内已存在与本次提交内重复的词面均跳过，仅插入新词。
     *
     * @param words 待导入词条（{@code word} 必填，其余字段缺省按 level=MEDIUM / action=BLOCK / enabled=1 补齐）
     * @return 导入统计
     */
    public Mono<BatchResult> importBatch(List<SensitiveWordEntity> words) {
        if (words == null || words.isEmpty()) {
            return Mono.just(new BatchResult(0, 0, 0, List.of()));
        }
        if (words.size() > MAX_BATCH) {
            return Mono.error(badRequest("单次批量导入上限 " + MAX_BATCH + " 条，当前 " + words.size() + " 条"));
        }
        return Mono.fromCallable(() -> doImport(words))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(result -> result.inserted() > 0
                        ? publisher.publish(ConfigVersionPublisher.TYPE_SENSITIVE_WORD).thenReturn(result)
                        : Mono.just(result));
    }

    private BatchResult doImport(List<SensitiveWordEntity> words) {
        List<String> candidates = new ArrayList<>();
        Set<String> warnings = new LinkedHashSet<>();
        Set<String> seen = new HashSet<>();
        for (SensitiveWordEntity word : words) {
            if (word == null || !hasText(word.getWord())) {
                warnings.add("存在 word 为空的词条，已跳过");
                continue;
            }
            String normalized = word.getWord().trim();
            if (normalized.length() > 255) {
                warnings.add("词条长度超出上限 255: " + abbreviate(normalized));
                continue;
            }
            if (!seen.add(normalized)) {
                continue;
            }
            candidates.add(normalized);
        }
        if (candidates.isEmpty()) {
            return new BatchResult(words.size(), 0, 0, limit(warnings));
        }

        Set<String> existing = new HashSet<>();
        QueryWrapper<SensitiveWordEntity> wrapper = Wrappers.query();
        wrapper.select("word").in("word", candidates);
        List<SensitiveWordEntity> rows = wordMapper.selectList(wrapper);
        if (rows != null) {
            for (SensitiveWordEntity row : rows) {
                if (row != null && row.getWord() != null) {
                    existing.add(row.getWord().trim());
                }
            }
        }

        int inserted = 0;
        int skipped = 0;
        for (SensitiveWordEntity word : words) {
            if (word == null || !hasText(word.getWord())) {
                continue;
            }
            String normalized = word.getWord().trim();
            if (normalized.length() > 255 || existing.contains(normalized)) {
                skipped++;
                continue;
            }
            word.setId(null);
            word.setDeleted(null);
            word.setWord(normalized);
            applyDefaults(word);
            wordMapper.insert(word);
            // 同一批次内重复词面只插入一次
            existing.add(normalized);
            inserted++;
        }
        log.info("敏感词批量导入完成: 提交 {} 条，新增 {} 条，跳过 {} 条", words.size(), inserted, skipped);
        return new BatchResult(words.size(), inserted, skipped, limit(warnings));
    }

    /** 警告摘要封顶，避免大量非法词条把响应体撞大。 */
    private static List<String> limit(Set<String> warnings) {
        List<String> all = new ArrayList<>(warnings);
        if (all.size() <= MAX_WARNINGS) {
            return all;
        }
        List<String> limited = new ArrayList<>(all.subList(0, MAX_WARNINGS));
        limited.add("其余 " + (all.size() - MAX_WARNINGS) + " 条警告已省略");
        return limited;
    }

    @Override
    protected void validateCreate(SensitiveWordEntity entity) {
        requireText("敏感词 word", entity.getWord(), 255);
        requireCategory(entity.getCategory());
        requireOneOf("级别 level", entity.getLevel(), LEVELS);
        requireOneOf("命中动作 action", entity.getAction(), ACTIONS);
        requireFlag("启用标记 enabled", entity.getEnabled());
    }

    @Override
    protected void validateUpdate(SensitiveWordEntity patch, SensitiveWordEntity existing) {
        if (hasText(patch.getWord())) {
            requireText("敏感词 word", patch.getWord(), 255);
        }
        requireCategory(patch.getCategory());
        requireOneOf("级别 level", patch.getLevel(), LEVELS);
        requireOneOf("命中动作 action", patch.getAction(), ACTIONS);
        requireFlag("启用标记 enabled", patch.getEnabled());
    }

    @Override
    protected void prepareCreate(SensitiveWordEntity entity) {
        entity.setWord(trimmed(entity.getWord()));
        applyDefaults(entity);
    }

    @Override
    protected void mergeForUpdate(SensitiveWordEntity patch, SensitiveWordEntity existing) {
        if (hasText(patch.getWord())) {
            patch.setWord(trimmed(patch.getWord()));
        }
        if (hasText(patch.getCategory())) {
            patch.setCategory(patch.getCategory().trim());
        }
        if (hasText(patch.getLevel())) {
            patch.setLevel(patch.getLevel().trim().toUpperCase());
        }
        if (hasText(patch.getAction())) {
            patch.setAction(patch.getAction().trim().toUpperCase());
        }
    }

    @Override
    protected Mono<Void> afterChange(String action) {
        return publisher.publish(ConfigVersionPublisher.TYPE_SENSITIVE_WORD);
    }

    private void applyDefaults(SensitiveWordEntity entity) {
        if (!hasText(entity.getCategory())) {
            entity.setCategory("其他");
        } else {
            entity.setCategory(entity.getCategory().trim());
        }
        entity.setLevel(hasText(entity.getLevel()) ? entity.getLevel().trim().toUpperCase() : "MEDIUM");
        entity.setAction(hasText(entity.getAction()) ? entity.getAction().trim().toUpperCase() : "BLOCK");
        if (entity.getEnabled() == null) {
            entity.setEnabled(1);
        }
    }

    private void requireCategory(String category) {
        if (category != null && category.trim().length() > 64) {
            throw badRequest("分类 category 长度超出上限 64");
        }
    }

    private void requireFlag(String field, Integer value) {
        if (value != null && value != 0 && value != 1) {
            throw badRequest(field + " 只能为 0 或 1: " + value);
        }
    }

    private static String abbreviate(String value) {
        return value.length() <= 32 ? value : value.substring(0, 32) + "...";
    }

    /**
     * 批量导入统计。
     *
     * @param submitted 提交词条数
     * @param inserted  实际新增数
     * @param skipped   跳过数（库内已存在、批内重复或非法）
     * @param warnings  跳过原因摘要（去重后最多前若干条）
     */
    public record BatchResult(int submitted, int inserted, int skipped, List<String> warnings) {

        public BatchResult {
            warnings = warnings != null ? List.copyOf(warnings) : List.of();
        }
    }
}
