package tech.qunxu.module.gateway.pipeline.filters;

import tech.qunxu.module.gateway.observability.GatewayMetrics;
import tech.qunxu.module.gateway.pipeline.FilterOrder;
import tech.qunxu.module.gateway.pipeline.PipelineChain;
import tech.qunxu.module.gateway.pipeline.PipelineFilter;
import tech.qunxu.module.gateway.pipeline.RequestContext;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 指标包裹过滤器（{@link FilterOrder#METRICS}，紧邻审计之内）。
 *
 * <p>以 {@code doFinally} 在链路终止时统一记录请求量、时延、首字延迟、Token 用量、围栏命中与审计裁决，
 * 因此与最外层审计拥有<b>相同口径</b>——包括鉴权/限流/路由等早期失败也被计入。异常经
 * {@link AtomicReference} 透传给指标组件用于独立派生错误码，<b>不</b>提前改写上下文状态，
 * 以免干扰审计的状态归一化。</p>
 */
@Component
public class MetricsFilter implements PipelineFilter {

    private final GatewayMetrics metrics;

    public MetricsFilter(GatewayMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public int order() {
        return FilterOrder.METRICS;
    }

    @Override
    public Mono<Void> filter(RequestContext ctx, PipelineChain chain) {
        AtomicReference<Throwable> error = new AtomicReference<>();
        return Mono.defer(() -> chain.execute(ctx))
                .doOnError(error::set)
                .doFinally(signal -> metrics.recordRequest(ctx, signal, error.get()));
    }
}
