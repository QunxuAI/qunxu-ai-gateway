package tech.qunxu.module.gateway.observability;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockFilterChain;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TraceIdFilter} 单元测试：验证 traceId 的生成/复用、清洗与响应头写入（Servlet 版）。
 */
class TraceIdFilterTest {

    private String run(TraceIdFilter filter, MockHttpServletRequest request, MockHttpServletResponse response) throws Exception {
        filter.doFilter(request, response, new MockFilterChain());
        return (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTR);
    }

    @Test
    void generatesTraceIdAndPopulatesHeaderAndAttribute() throws Exception {
        TraceIdFilter filter = new TraceIdFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/models");
        MockHttpServletResponse response = new MockHttpServletResponse();

        String attr = run(filter, request, response);
        assertThat(attr).isNotBlank();
        assertThat(response.getHeader(TraceIdFilter.TRACE_ID_HEADER)).isEqualTo(attr);
    }

    @Test
    void reusesInboundTraceIdHeader() throws Exception {
        TraceIdFilter filter = new TraceIdFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/models");
        request.addHeader("X-Trace-Id", "upstream-trace-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        String attr = run(filter, request, response);
        assertThat(attr).isEqualTo("upstream-trace-123");
    }

    @Test
    void extractsTraceIdFromW3cTraceparent() throws Exception {
        TraceIdFilter filter = new TraceIdFilter();
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/models");
        request.addHeader("traceparent", "00-" + traceId + "-00f067aa0ba902b7-01");
        MockHttpServletResponse response = new MockHttpServletResponse();

        String attr = run(filter, request, response);
        assertThat(attr).isEqualTo(traceId);
    }

    @Test
    void sanitizesMaliciousInboundHeader() throws Exception {
        TraceIdFilter filter = new TraceIdFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/models");
        request.addHeader("X-Trace-Id", "bad\r\nvalue<>");
        MockHttpServletResponse response = new MockHttpServletResponse();

        String attr = run(filter, request, response);
        assertThat(attr).doesNotContain("\r", "\n", "<", ">");
        // 非法字符被剥离后仍保留合法部分
        assertThat(attr).contains("badvalue");
    }
}
