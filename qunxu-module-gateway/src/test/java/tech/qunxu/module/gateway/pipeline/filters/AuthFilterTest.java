package tech.qunxu.module.gateway.pipeline.filters;

import jakarta.servlet.http.HttpServletRequest;
import tech.qunxu.module.gateway.common.enums.ProtocolType;
import tech.qunxu.module.gateway.config.GatewayProperties;
import tech.qunxu.module.gateway.pipeline.PipelineChain;
import tech.qunxu.module.gateway.pipeline.RequestContext;
import tech.qunxu.module.gateway.security.CallerIdentity;
import tech.qunxu.module.gateway.security.CallerIdentityResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link AuthFilter} 会话标识解析单元测试：验证从可配置请求头读取会话 ID 写入
 * {@link RequestContext}，供路由阶段做会话级粘性；空白头不污染上下文。
 */
class AuthFilterTest {

    private CallerIdentityResolver resolver;
    private GatewayProperties properties;
    private AuthFilter filter;
    private RecordingChain chain;

    private static final class RecordingChain implements PipelineChain {
        private int executions;

        @Override
        public Mono<Void> execute(RequestContext ctx) {
            executions++;
            return Mono.empty();
        }
    }

    @BeforeEach
    void setUp() {
        resolver = mock(CallerIdentityResolver.class);
        when(resolver.resolve(any())).thenReturn(CallerIdentity.of("alice", "Alice", "default", null, null));
        properties = new GatewayProperties();
        filter = new AuthFilter(resolver, properties);
        chain = new RecordingChain();
    }

    private static RequestContext ctx(HttpServletRequest request) {
        RequestContext ctx = RequestContext.create(ProtocolType.OPENAI, "POST",
                "/v1/chat/completions", "/v1/chat/completions", "trace-auth", null);
        if (request != null) {
            ctx.setAttribute(RequestContext.ATTR_REQUEST, request);
        }
        return ctx;
    }

    @Test
    void parsesSessionHeaderIntoContext() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Session-Id")).thenReturn("conv-9");
        RequestContext ctx = ctx(request);

        StepVerifier.create(filter.filter(ctx, chain)).verifyComplete();

        assertThat(chain.executions).isEqualTo(1);
        assertThat(ctx.getSessionId()).isEqualTo("conv-9");
    }

    @Test
    void ignoresBlankSessionHeader() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Session-Id")).thenReturn("   ");
        RequestContext ctx = ctx(request);

        StepVerifier.create(filter.filter(ctx, chain)).verifyComplete();

        assertThat(ctx.getSessionId()).isNull();
    }

    @Test
    void respectsCustomHeaderName() {
        properties.getSticky().setHeaderName("X-Conversation-Id");
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Conversation-Id")).thenReturn("conv-77");
        RequestContext ctx = ctx(request);

        StepVerifier.create(filter.filter(ctx, chain)).verifyComplete();

        assertThat(ctx.getSessionId()).isEqualTo("conv-77");
    }

    @Test
    void leavesSessionNullWithoutRequest() {
        RequestContext ctx = ctx(null);

        StepVerifier.create(filter.filter(ctx, chain)).verifyComplete();

        assertThat(ctx.getSessionId()).isNull();
    }
}
