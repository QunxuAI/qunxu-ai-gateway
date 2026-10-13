package tech.qunxu.module.gateway.admin;

import tech.qunxu.module.gateway.admin.service.GuardrailPolicyAdminService;
import tech.qunxu.module.gateway.persistence.entity.GuardrailPolicyEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 安全围栏策略管理接口（{@code /admin/guardrail-policies}）。
 *
 * <p>{@code rules_json} 的 schema 与 {@code GuardrailPolicyService} 约定一致（sensitiveWord/pii/length/
 * promptInjection/regex/topic 六个可选分组）；写入时校验其为合法 JSON 对象，避免围栏静默失效。</p>
 */
@RestController
@RequestMapping("/admin-api/gateway/guardrail-policies")
public class GuardrailPolicyAdminController extends AbstractConfigAdminController<GuardrailPolicyEntity> {

    private final GuardrailPolicyAdminService policyService;

    public GuardrailPolicyAdminController(GuardrailPolicyAdminService policyService) {
        super(policyService);
        this.policyService = policyService;
    }

    /** 分页查询围栏策略（按 scope、priority 升序）。 */
    @GetMapping
    public Mono<AdminPage<GuardrailPolicyEntity>> query(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) Integer enabled,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return policyService.query(keyword, scope, enabled, page, size);
    }

    /** 列出全部围栏策略（不分页）。 */
    @GetMapping("/all")
    public Mono<List<GuardrailPolicyEntity>> queryAll(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) Integer enabled) {
        return policyService.queryAll(keyword, scope, enabled);
    }
}
