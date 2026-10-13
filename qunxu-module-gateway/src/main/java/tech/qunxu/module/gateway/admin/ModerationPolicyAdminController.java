package tech.qunxu.module.gateway.admin;

import tech.qunxu.module.gateway.admin.service.ModerationPolicyAdminService;
import tech.qunxu.module.gateway.persistence.entity.ModerationPolicyEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Set;

/**
 * 内容审计策略管理接口（{@code /admin/moderation-policies}）。
 *
 * <p>{@code provider} 必须是运行时已注册的 {@code ModerationProvider} Bean 名；
 * 可通过 {@code GET /admin/moderation-policies/providers} 获取当前可用集合。</p>
 */
@RestController
@RequestMapping("/admin-api/gateway/moderation-policies")
public class ModerationPolicyAdminController extends AbstractConfigAdminController<ModerationPolicyEntity> {

    private final ModerationPolicyAdminService policyService;

    public ModerationPolicyAdminController(ModerationPolicyAdminService policyService) {
        super(policyService);
        this.policyService = policyService;
    }

    /** 分页查询审计策略（按 scope、priority 升序）。 */
    @GetMapping
    public Mono<AdminPage<ModerationPolicyEntity>> query(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) Integer enabled,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return policyService.query(keyword, scope, provider, enabled, page, size);
    }

    /** 列出全部审计策略（不分页）。 */
    @GetMapping("/all")
    public Mono<List<ModerationPolicyEntity>> queryAll(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) Integer enabled) {
        return policyService.queryAll(keyword, scope, provider, enabled);
    }

    /** 列出运行时已注册的审计提供方名称（策略 {@code provider} 字段的合法取值）。 */
    @GetMapping("/providers")
    public Set<String> providers() {
        return policyService.availableProviders();
    }
}
