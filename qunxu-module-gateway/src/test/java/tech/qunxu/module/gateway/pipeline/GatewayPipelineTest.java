package tech.qunxu.module.gateway.pipeline;

import tech.qunxu.module.gateway.common.enums.ProtocolType;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GatewayPipeline} 与 {@link DefaultPipelineChain} 单元测试：验证按 order 排序执行、
 * 链尾终结逻辑触发，以及过滤器短路（不调用 chain.execute）的行为。
 */
class GatewayPipelineTest {

    private RequestContext newContext() {
        return RequestContext.create(ProtocolType.OPENAI, "POST",
                "/v1/chat/completions", "/v1/chat/completions", "trace-1", null);
    }

    @Test
    void executesFiltersInOrderThenTerminal() {
        List<String> trail = new ArrayList<>();
        GatewayPipeline pipeline = new GatewayPipeline(List.of(
                new RecordingFilter(FilterOrder.MODEL_RESOLVE, "resolve", trail),
                new RecordingFilter(FilterOrder.AUTH, "auth", trail),
                new RecordingFilter(FilterOrder.RATE_LIMIT, "ratelimit", trail)));

        StepVerifier.create(pipeline.execute(newContext(), Mono.fromRunnable(() -> trail.add("invoke"))))
                .verifyComplete();

        assertThat(trail).containsExactly("auth", "ratelimit", "resolve", "invoke");
    }

    @Test
    void filterCanShortCircuitChain() {
        List<String> trail = new ArrayList<>();
        PipelineFilter blocker = new PipelineFilter() {
            @Override
            public int order() {
                return FilterOrder.AUTH;
            }

            @Override
            public Mono<Void> filter(RequestContext ctx, PipelineChain chain) {
                trail.add("block");
                return Mono.empty(); // 不调用 chain.execute() -> 短路
            }
        };
        GatewayPipeline pipeline = new GatewayPipeline(List.of(
                blocker, new RecordingFilter(FilterOrder.INVOKE, "never", trail)));

        StepVerifier.create(pipeline.execute(newContext(), Mono.fromRunnable(() -> trail.add("terminal"))))
                .verifyComplete();

        assertThat(trail).containsExactly("block");
    }

    @Test
    void terminalRunsWhenNoFilters() {
        List<String> trail = new ArrayList<>();
        GatewayPipeline pipeline = new GatewayPipeline(List.of());
        StepVerifier.create(pipeline.execute(newContext(), Mono.fromRunnable(() -> trail.add("only-terminal"))))
                .verifyComplete();
        assertThat(trail).containsExactly("only-terminal");
    }

    private static final class RecordingFilter implements PipelineFilter {
        private final int order;
        private final String name;
        private final List<String> trail;

        RecordingFilter(int order, String name, List<String> trail) {
            this.order = order;
            this.name = name;
            this.trail = trail;
        }

        @Override
        public int order() {
            return order;
        }

        @Override
        public Mono<Void> filter(RequestContext ctx, PipelineChain chain) {
            return Mono.fromRunnable(() -> trail.add(name)).then(chain.execute(ctx));
        }
    }
}
