package tech.qunxu.module.gateway;

import tech.qunxu.module.gateway.pipeline.GatewayPipeline;
import tech.qunxu.module.gateway.pipeline.PipelineFilter;
import tech.qunxu.module.gateway.security.CallerIdentityResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 上下文加载冒烟测试：验证网关模块可装配进（Servlet）上下文——安全过滤器链
 * （Token 认证 + 网关 TraceId 过滤器）、鉴权解析器、流水线、持久层 Mapper、
 * Jackson、加解密、WebClient、MyBatis-Plus 拦截器等。
 *
 * <p>使用 H2（MySQL 兼容模式），无需真实 MySQL/Redis/Docker。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class GatewayContextLoadTest {

    @Autowired
    private GatewayPipeline pipeline;

    @Autowired
    private CallerIdentityResolver callerIdentityResolver;

    @Autowired
    private FilterChainProxy springSecurityFilterChain;

    @Test
    void contextLoads() {
        assertThat(callerIdentityResolver).isNotNull();
        assertThat(springSecurityFilterChain).isNotNull();
        assertThat(pipeline).isNotNull();
    }

    @Test
    void pipelineRegistersAuthFilter() {
        assertThat(pipeline.getFilters())
                .isNotEmpty()
                .anyMatch(f -> f.getClass().getSimpleName().equals("AuthFilter"));
        // 过滤器按 order 升序排列
        int[] orders = pipeline.getFilters().stream().mapToInt(PipelineFilter::order).toArray();
        for (int i = 1; i < orders.length; i++) {
            assertThat(orders[i]).isGreaterThanOrEqualTo(orders[i - 1]);
        }
    }
}
