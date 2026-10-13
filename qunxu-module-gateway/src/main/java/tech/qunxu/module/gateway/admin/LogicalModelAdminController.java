package tech.qunxu.module.gateway.admin;

import tech.qunxu.module.gateway.admin.service.LogicalModelAdminService;
import tech.qunxu.module.gateway.persistence.entity.LogicalModelEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 逻辑模型管理接口（{@code /admin/logical-models}）。
 *
 * <p>逻辑模型的 {@code name} 即客户端请求中的 {@code model} 取值，{@code capability} 限定其可被哪一类
 * 端点调用（CHAT/EMBEDDING/RERANK/TTS/STT/IMAGE）。</p>
 */
@RestController
@RequestMapping("/admin-api/gateway/logical-models")
public class LogicalModelAdminController extends AbstractConfigAdminController<LogicalModelEntity> {

    private final LogicalModelAdminService modelService;

    public LogicalModelAdminController(LogicalModelAdminService modelService) {
        super(modelService);
        this.modelService = modelService;
    }

    /** 分页查询逻辑模型（按 capability、name 升序）。 */
    @GetMapping
    public Mono<AdminPage<LogicalModelEntity>> query(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String capability,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return modelService.query(keyword, capability, status, page, size);
    }

    /** 列出全部逻辑模型（不分页）。 */
    @GetMapping("/all")
    public Mono<List<LogicalModelEntity>> queryAll(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String capability,
            @RequestParam(required = false) Integer status) {
        return modelService.queryAll(keyword, capability, status);
    }
}
