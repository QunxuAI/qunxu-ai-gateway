package tech.qunxu.module.gateway.admin;

import tech.qunxu.module.gateway.admin.service.SensitiveWordAdminService;
import tech.qunxu.module.gateway.persistence.entity.SensitiveWordEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 敏感词管理接口（{@code /admin/sensitive-words}）。
 *
 * <p>词条增删改后会立即重建本实例的 DFA/Trie 快照，并通过 Redis 广播触发其它实例重建，
 * 因此变更<b>秒级生效</b>，无需重启。</p>
 */
@RestController
@RequestMapping("/admin-api/gateway/sensitive-words")
public class SensitiveWordAdminController extends AbstractConfigAdminController<SensitiveWordEntity> {

    private final SensitiveWordAdminService wordService;

    public SensitiveWordAdminController(SensitiveWordAdminService wordService) {
        super(wordService);
        this.wordService = wordService;
    }

    /** 分页查询敏感词（按 level、id 排序）。 */
    @GetMapping
    public Mono<AdminPage<SensitiveWordEntity>> query(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) Integer enabled,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return wordService.query(keyword, category, level, enabled, page, size);
    }

    /** 列出全部敏感词（不分页）。 */
    @GetMapping("/all")
    public Mono<List<SensitiveWordEntity>> queryAll(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) Integer enabled) {
        return wordService.queryAll(keyword, category, level, enabled);
    }

    /**
     * 批量导入敏感词（按词面去重，单次上限 {@value SensitiveWordAdminService#MAX_BATCH} 条）。
     *
     * @param words 词条数组，仅 {@code word} 必填，其余字段按默认值补齐
     * @return 导入统计（提交数/新增数/跳过数/警告摘要）
     */
    @PostMapping("/batch")
    public Mono<SensitiveWordAdminService.BatchResult> importBatch(
            @RequestBody(required = false) List<SensitiveWordEntity> words) {
        return wordService.importBatch(words);
    }
}
