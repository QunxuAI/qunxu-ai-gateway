package tech.qunxu.module.gateway.admin;

import tech.qunxu.module.gateway.admin.service.RateLimitRuleAdminService;
import tech.qunxu.module.gateway.persistence.entity.RateLimitRuleEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 限流配额规则管理接口（{@code /admin/rate-limit/rules}）。
 *
 * <p>区别于 {@code /admin/rate-limit/config}（只读摊平视图）：本接口提供规则的增删改，
 * 写入后经 {@code rate-limit-rule} 失效通知让限流热路径秒级生效，无需重启。</p>
 */
@RestController
@RequestMapping("/admin-api/gateway/rate-limit/rules")
public class RateLimitRuleAdminController extends AbstractConfigAdminController<RateLimitRuleEntity> {

    private final RateLimitRuleAdminService ruleService;

    public RateLimitRuleAdminController(RateLimitRuleAdminService ruleService) {
        super(ruleService);
        this.ruleService = ruleService;
    }

    /** 分页查询限流规则（按 rule_type、target 升序）。 */
    @GetMapping
    public Mono<AdminPage<RateLimitRuleEntity>> query(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String ruleType,
            @RequestParam(required = false) Integer enabled,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ruleService.query(keyword, ruleType, enabled, page, size);
    }

    /** 列出全部限流规则（不分页）。 */
    @GetMapping("/all")
    public Mono<List<RateLimitRuleEntity>> queryAll(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String ruleType,
            @RequestParam(required = false) Integer enabled) {
        return ruleService.queryAll(keyword, ruleType, enabled);
    }

    /** 规则类型取值清单（前端下拉用）。 */
    @GetMapping("/types")
    public Mono<List<String>> types() {
        return Mono.just(RateLimitRuleAdminService.RULE_TYPES);
    }
}
