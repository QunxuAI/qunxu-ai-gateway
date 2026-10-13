package tech.qunxu.module.gateway.admin;

import tech.qunxu.module.gateway.admin.service.ProviderAdminService;
import tech.qunxu.module.gateway.admin.service.ProviderModelDiscoveryService;
import tech.qunxu.module.gateway.persistence.entity.ProviderEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 供应商管理接口（{@code /admin/providers}）。
 *
 * <p>响应中的 {@code credentials} 默认以 {@code ***} 掩码返回（明文与密文均不外泄）；更新时回传掩码或省略该字段
 * 表示保持原凭证不变。如需在受信环境查看密文，设置 {@code gateway.admin.expose-credentials=true}。</p>
 */
@RestController
@RequestMapping("/admin-api/gateway/providers")
public class ProviderAdminController extends AbstractConfigAdminController<ProviderEntity> {

    private final ProviderAdminService providerService;
    private final ProviderModelDiscoveryService modelDiscovery;

    public ProviderAdminController(ProviderAdminService providerService,
                                   ProviderModelDiscoveryService modelDiscovery) {
        super(providerService);
        this.providerService = providerService;
        this.modelDiscovery = modelDiscovery;
    }

    /** 分页查询供应商（按 priority 升序、id 降序）。 */
    @GetMapping
    public Mono<AdminPage<ProviderEntity>> query(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return providerService.query(keyword, status, page, size);
    }

    /** 列出全部供应商（不分页），供通道配置等场景做下拉选择。 */
    @GetMapping("/all")
    public Mono<List<ProviderEntity>> queryAll(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status) {
        return providerService.queryAll(keyword, status);
    }

    /** 拉取供应商上游的模型清单（供「批量上架模型」使用），每个模型附带自动识别的能力。 */
    @GetMapping("/{id}/models")
    public Mono<List<ProviderModelInfo>> listModels(@PathVariable Long id) {
        return modelDiscovery.listModels(id);
    }
}
