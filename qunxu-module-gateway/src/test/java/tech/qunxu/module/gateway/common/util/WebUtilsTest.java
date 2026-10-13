package tech.qunxu.module.gateway.common.util;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link WebUtils} 单元测试：客户端 IP 解析与 Bearer 令牌提取（Servlet 版）。
 */
class WebUtilsTest {

    @Test
    void extractsBearerToken() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/models");
        request.addHeader("Authorization", "Bearer abc.def.ghi");
        assertThat(WebUtils.bearerToken(request)).isEqualTo("abc.def.ghi");
    }

    @Test
    void bearerTokenIsNullWhenAbsentOrNotBearer() {
        assertThat(WebUtils.bearerToken(new MockHttpServletRequest("GET", "/x"))).isNull();

        MockHttpServletRequest basic = new MockHttpServletRequest("GET", "/x");
        basic.addHeader("Authorization", "Basic dXNlcjpwYXNz");
        assertThat(WebUtils.bearerToken(basic)).isNull();
    }

    @Test
    void clientIpPrefersFirstForwardedForEntry() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        request.addHeader("X-Forwarded-For", "203.0.113.5, 70.41.3.18");
        request.setRemoteAddr("10.0.0.1");
        assertThat(WebUtils.clientIp(request)).isEqualTo("203.0.113.5");
    }

    @Test
    void clientIpFallsBackToRemoteAddress() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        request.setRemoteAddr("10.0.0.9");
        assertThat(WebUtils.clientIp(request)).isEqualTo("10.0.0.9");
    }
}
