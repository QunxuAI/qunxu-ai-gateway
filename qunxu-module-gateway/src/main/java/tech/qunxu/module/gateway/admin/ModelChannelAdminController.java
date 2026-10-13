package tech.qunxu.module.gateway.admin;

import tech.qunxu.module.gateway.admin.service.ModelChannelAdminService;
import tech.qunxu.module.gateway.persistence.entity.ModelChannelEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 模型通道管理接口（{@code /admin/channels}）。
 *
 * <p>通道是负载均衡与健康自愈的基本单位：同一逻辑模型下可有多个通道（不同供应商/不同部署），
 * 由 {@code weight} 与 {@code priority} 决定选路顺序。删除通道会同时清理其在 Redis 中的健康键。</p>
 */
@RestController
@RequestMapping("/admin-api/gateway/channels")
public class ModelChannelAdminController extends AbstractConfigAdminController<ModelChannelEntity> {

    private final ModelChannelAdminService channelService;

    public ModelChannelAdminController(ModelChannelAdminService channelService) {
        super(channelService);
        this.channelService = channelService;
    }

    /** 分页查询通道（按 priority 升序、weight 降序）。 */
    @GetMapping
    public Mono<AdminPage<ModelChannelEntity>> query(
            @RequestParam(required = false) Long logicalModelId,
            @RequestParam(required = false) Long providerId,
            @RequestParam(required = false) String protocol,
            @RequestParam(required = false) Integer enabled,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return channelService.query(logicalModelId, providerId, protocol, enabled, page, size);
    }

    /** 列出全部通道（不分页）。 */
    @GetMapping("/all")
    public Mono<List<ModelChannelEntity>> queryAll(
            @RequestParam(required = false) Long logicalModelId,
            @RequestParam(required = false) Long providerId,
            @RequestParam(required = false) String protocol,
            @RequestParam(required = false) Integer enabled) {
        return channelService.queryAll(logicalModelId, providerId, protocol, enabled);
    }

    /** 批量上架模型：把一个供应商的多个模型批量创建为通道。 */
    @PostMapping("/batch")
    public Mono<BatchChannelCreateResult> batchCreate(@RequestBody BatchChannelCreateRequest request) {
        return channelService.batchCreate(request);
    }
}
