package tech.qunxu.module.gateway.guardrail;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import tech.qunxu.module.gateway.guardrail.SensitiveWordTrie.Match;
import tech.qunxu.module.gateway.guardrail.SensitiveWordTrie.WordMeta;
import tech.qunxu.module.gateway.persistence.entity.SensitiveWordEntity;
import tech.qunxu.module.gateway.persistence.mapper.SensitiveWordMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 敏感词加载服务：合并<b>内置种子库</b>（classpath {@code sensitive-words.seed}）与<b>数据库启用词条</b>
 * （{@code sensitive_word} 表），构建 {@link SensitiveWordTrie} 不可变快照并原子发布。
 *
 * <p>匹配阶段仅读取当前快照（{@link AtomicReference}），故并发查询安全且无锁；刷新时构建新树后一次性替换，
 * 读写不互相阻塞。种子库在构造时同步加载（体积小、无 I/O 阻塞风险），数据库词条经
 * {@link Schedulers#boundedElastic()} 响应式加载，并在应用就绪后触发首次刷新。</p>
 *
 * <p>词条动作语义：{@code BLOCK} 命中即拦截、{@code MASK} 命中脱敏、{@code WARN} 命中仅告警（记审计不阻断）。
 * 数据库词条覆盖同词种子词条（以词面为键，后加载者胜）。</p>
 */
@Service
public class SensitiveWordService {

    private static final Logger log = LoggerFactory.getLogger(SensitiveWordService.class);
    private static final String SEED_RESOURCE = "sensitive-words.seed";
    private static final char DEFAULT_MASK_CHAR = '*';

    private final SensitiveWordMapper sensitiveWordMapper;

    /** 当前生效的敏感词快照；始终非空（至少含种子库）。 */
    private final AtomicReference<SensitiveWordTrie> snapshot;

    public SensitiveWordService(SensitiveWordMapper sensitiveWordMapper) {
        this.sensitiveWordMapper = sensitiveWordMapper;
        SensitiveWordTrie seed = new SensitiveWordTrie();
        loadSeed(seed);
        this.snapshot = new AtomicReference<>(seed);
        log.info("敏感词种子库已加载: {} 条", seed.size());
    }

    // ---- 查询（只读当前快照，无锁） ----

    /** 查找文本中全部命中（最长匹配、不重叠）。 */
    public List<Match> findHits(String text) {
        return snapshot.get().findHits(text);
    }

    /** 文本是否包含任一敏感词。 */
    public boolean contains(String text) {
        return snapshot.get().contains(text);
    }

    /** 以默认掩码字符（{@code *}）脱敏全部命中，返回等长文本。 */
    public String mask(String text) {
        return mask(text, DEFAULT_MASK_CHAR);
    }

    /** 以指定掩码字符脱敏全部命中。 */
    public String mask(String text, char maskChar) {
        return snapshot.get().mask(text, maskChar);
    }

    /** 当前快照词条数。 */
    public int size() {
        return snapshot.get().size();
    }

    /** 返回当前不可变快照（供规则/审计直接复用，避免重复匹配）。 */
    public SensitiveWordTrie current() {
        return snapshot.get();
    }

    /**
     * 应用就绪后触发首次数据库词条刷新（此时仓库级 sql/schema.sql 建表已完成）。
     * 订阅即忘，刷新失败已在 {@link #refresh()} 内兜底，不影响启动。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        refresh().subscribe();
    }

    // ---- 刷新（种子 + 数据库合并） ----

    /**
     * 响应式刷新：重新加载数据库启用词条并与种子库合并，构建新快照后原子替换。
     *
     * @return 刷新后的词条总数
     */
    public Mono<Integer> refresh() {
        return Mono.fromCallable(() -> {
                    SensitiveWordTrie trie = new SensitiveWordTrie();
                    loadSeed(trie);
                    List<SensitiveWordEntity> rows = sensitiveWordMapper.selectList(
                            Wrappers.<SensitiveWordEntity>lambdaQuery()
                                    .eq(SensitiveWordEntity::getEnabled, 1));
                    int dbCount = 0;
                    if (rows != null) {
                        for (SensitiveWordEntity row : rows) {
                            if (row == null || row.getWord() == null || row.getWord().isBlank()) {
                                continue;
                            }
                            trie.add(row.getWord().trim(),
                                    new WordMeta(row.getCategory(), row.getLevel(), row.getAction()));
                            dbCount++;
                        }
                    }
                    snapshot.set(trie);
                    if (log.isInfoEnabled()) {
                        log.info("敏感词库已刷新: 合计 {} 条（数据库启用 {} 条）", trie.size(), dbCount);
                    }
                    return trie.size();
                })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> {
                    log.warn("敏感词库刷新失败，沿用现有快照: {}", e.toString());
                    return Mono.just(snapshot.get().size());
                });
    }

    // ---- 种子库加载 ----

    /**
     * 从 classpath 加载种子库。格式：每行 {@code word|category|level|action}，以 {@code #} 开头为注释，空行忽略。
     * 字段缺失时以默认值补齐（category=其他、level=MEDIUM、action=BLOCK）。
     */
    private void loadSeed(SensitiveWordTrie trie) {
        ClassPathResource resource = new ClassPathResource(SEED_RESOURCE);
        if (!resource.exists()) {
            log.debug("未找到敏感词种子库 {}，仅使用数据库词条", SEED_RESOURCE);
            return;
        }
        try (InputStream in = resource.getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.charAt(0) == '#') {
                    continue;
                }
                String[] parts = trimmed.split("\\|", -1);
                String word = parts[0].trim();
                if (word.isEmpty()) {
                    continue;
                }
                String category = parts.length > 1 && !parts[1].isBlank() ? parts[1].trim() : "其他";
                String level = parts.length > 2 && !parts[2].isBlank() ? parts[2].trim() : "MEDIUM";
                String action = parts.length > 3 && !parts[3].isBlank() ? parts[3].trim() : "BLOCK";
                trie.add(word, new WordMeta(category, level, action));
            }
        } catch (IOException e) {
            log.warn("加载敏感词种子库失败: {}", e.toString());
        }
    }
}
