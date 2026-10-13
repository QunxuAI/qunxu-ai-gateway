package tech.qunxu.module.gateway.pipeline.filters;

import tech.qunxu.module.gateway.common.enums.Capability;
import tech.qunxu.module.gateway.common.exception.ErrorCode;
import tech.qunxu.module.gateway.common.exception.GatewayException;
import tech.qunxu.module.gateway.common.model.UnifiedRequest;
import tech.qunxu.module.gateway.persistence.entity.LogicalModelEntity;
import tech.qunxu.module.gateway.persistence.service.ConfigService;
import tech.qunxu.module.gateway.pipeline.FilterOrder;
import tech.qunxu.module.gateway.pipeline.PipelineChain;
import tech.qunxu.module.gateway.pipeline.PipelineFilter;
import tech.qunxu.module.gateway.pipeline.RequestContext;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 模型解析过滤器：把请求中的对外模型名解析为逻辑模型，写入 {@link RequestContext} 并校验能力匹配。
 *
 * <p>解析失败返回 404（{@link ErrorCode#MODEL_NOT_FOUND}）；若接口能力（如 chat）与逻辑模型登记能力不符，
 * 返回 400。解析出的 {@code logicalModelId} 供后续路由选择通道。</p>
 */
@Component
public class ModelResolveFilter implements PipelineFilter {

    private final ConfigService configService;

    public ModelResolveFilter(ConfigService configService) {
        this.configService = configService;
    }

    @Override
    public int order() {
        return FilterOrder.MODEL_RESOLVE;
    }

    @Override
    public Mono<Void> filter(RequestContext ctx, PipelineChain chain) {
        UnifiedRequest request = ctx.getRequest();
        if (request == null || request.model() == null || request.model().isBlank()) {
            return Mono.error(new GatewayException(ErrorCode.BAD_REQUEST, "缺少模型名"));
        }
        return configService.findLogicalModelByName(request.model())
                .switchIfEmpty(Mono.error(new GatewayException(ErrorCode.MODEL_NOT_FOUND,
                        "模型不存在或未启用: " + request.model())))
                .flatMap(model -> {
                    applyModel(ctx, model);
                    return chain.execute(ctx);
                });
    }

    private void applyModel(RequestContext ctx, LogicalModelEntity model) {
        ctx.setLogicalModel(model);
        ctx.setLogicalModelId(model.getId());
        ctx.setLogicalModelName(model.getName());
        Capability registered = Capability.of(model.getCapability());
        Capability requested = ctx.getCapability();
        if (registered != null && requested != null && registered != requested) {
            throw new GatewayException(ErrorCode.BAD_REQUEST,
                    "模型能力(" + registered + ")与接口能力(" + requested + ")不匹配: " + model.getName());
        }
        if (registered != null) {
            ctx.setCapability(registered);
        }
    }
}
