package tech.qunxu.module.gateway.common.exception;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GlobalErrorHandler} 单元测试：验证 OpenAI 风格错误信封
 * {@code {"error":{message,type,code,provider_detail?}}} 的渲染、{@link ErrorCode} → HTTP 状态码的完整映射、
 * {@link ResponseStatusException} 的状态兜底，以及数据面未分类异常不泄露内部消息的安全约束。
 *
 * <p>Servlet 版处理器为 {@code @RestControllerAdvice}，直接断言各 {@code @ExceptionHandler}
 * 返回的 {@link ResponseEntity}，不再依赖 WebFlux 的 {@code ServerWebExchange}。</p>
 */
class GlobalErrorHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private GlobalErrorHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalErrorHandler(objectMapper);
    }

    private JsonNode body(ResponseEntity<Map<String, Object>> entity) {
        return objectMapper.valueToTree(entity.getBody());
    }

    @Test
    void rendersOpenAiStyleEnvelope() {
        ResponseEntity<Map<String, Object>> entity =
                handler.handleGatewayException(new GatewayException(ErrorCode.RATE_LIMITED, "调用频率超限，请稍后重试"));

        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(entity.getHeaders().getContentType()).isNotNull().isEqualTo(MediaType.APPLICATION_JSON);
        JsonNode error = body(entity).get("error");
        assertThat(error.get("message").asString()).isEqualTo("调用频率超限，请稍后重试");
        assertThat(error.get("type").asString()).isEqualTo("rate_limit_exceeded");
        assertThat(error.get("code").asString()).isEqualTo("rate_limit_exceeded");
        // 无下游细节时不应出现 provider_detail 字段
        assertThat(error.has("provider_detail")).isFalse();
    }

    @Test
    void includesProviderDetailWhenPresent() {
        GatewayException error = new GatewayException(ErrorCode.PROVIDER_ERROR, "下游返回 HTTP 500",
                "{\"error\":\"upstream boom\"}", new IllegalStateException("cause"));

        ResponseEntity<Map<String, Object>> entity = handler.handleGatewayException(error);

        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(body(entity).get("error").get("provider_detail").asString()).isEqualTo("{\"error\":\"upstream boom\"}");
    }

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    void everyErrorCodeMapsToItsDeclaredStatusAndType(ErrorCode code) {
        ResponseEntity<Map<String, Object>> entity =
                handler.handleGatewayException(new GatewayException(code, code.defaultMessage()));

        assertThat(entity.getStatusCode())
                .as("HTTP status for %s", code)
                .isEqualTo(HttpStatus.valueOf(code.httpStatus()));
        JsonNode error = body(entity).get("error");
        assertThat(error.get("type").asString()).isEqualTo(code.code());
        assertThat(error.get("code").asString()).isEqualTo(code.code());
        assertThat(error.get("message").asString()).isEqualTo(code.defaultMessage());
    }

    @Test
    void adminErrorsUseClientErrorSemantics() {
        // 管理接口的三类失败必须与 OpenAI 客户端可识别的状态码一致，前端才能统一处理
        assertThat(handler.handleGatewayException(new GatewayException(ErrorCode.BAD_REQUEST, "字段校验失败")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(handler.handleGatewayException(new GatewayException(ErrorCode.NOT_FOUND, "资源不存在")).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.handleGatewayException(new GatewayException(ErrorCode.CONFLICT, "版本已变更")).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void mapsResponseStatusExceptionByStatus() {
        ResponseEntity<Map<String, Object>> entity =
                handler.handleResponseStatusException(new ResponseStatusException(HttpStatus.CONFLICT, "资源冲突"));

        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode error = body(entity).get("error");
        assertThat(error.get("type").asString()).isEqualTo("conflict");
        assertThat(error.get("message").asString()).isEqualTo("资源冲突");
    }

    @Test
    void responseStatusExceptionWithoutReasonFallsBackToDefaultMessage() {
        ResponseEntity<Map<String, Object>> entity =
                handler.handleResponseStatusException(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS));

        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        JsonNode error = body(entity).get("error");
        assertThat(error.get("type").asString()).isEqualTo("rate_limit_exceeded");
        assertThat(error.get("message").asString()).isEqualTo(ErrorCode.RATE_LIMITED.defaultMessage());
    }

    @Test
    void unmappedStatusFallsBackByErrorRange() {
        // 未显式列举的状态按 4xx/5xx 分段兜底：客户端错误归 BAD_REQUEST，服务端错误归 INTERNAL_ERROR
        assertThat(handler.handleResponseStatusException(new ResponseStatusException(HttpStatus.I_AM_A_TEAPOT)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(handler.handleResponseStatusException(new ResponseStatusException(HttpStatus.valueOf(507))).getStatusCode())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(handler.handleResponseStatusException(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE)).getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void unclassifiedExceptionOnDataPlaneNeverLeaksInternalMessage() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/chat/completions");
        IllegalStateException boom = new IllegalStateException(
                "连接失败 jdbc:mysql://10.0.0.7:3306/gw user=root password=s3cr3t");

        ResponseEntity<Map<String, Object>> entity = handler.handleUnexpectedException(boom, request);

        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        JsonNode error = body(entity).get("error");
        assertThat(error.get("message").asString()).isEqualTo(ErrorCode.INTERNAL_ERROR.defaultMessage());
        assertThat(error.get("type").asString()).isEqualTo("internal_error");
        String rendered = body(entity).toString();
        assertThat(rendered).doesNotContain("s3cr3t").doesNotContain("10.0.0.7").doesNotContain("root");
    }

    @Test
    void errorHandlerIsRegisteredAsRestControllerAdviceWithHighestPrecedence() {
        // @RestControllerAdvice(basePackages=gateway) 只接管网关控制器；
        // @Order(HIGHEST_PRECEDENCE) 确保优先于框架的兜底异常处理器
        assertThat(GlobalErrorHandler.class.getAnnotation(RestControllerAdvice.class)).isNotNull();
        Order order = GlobalErrorHandler.class.getAnnotation(Order.class);
        assertThat(order).isNotNull();
        assertThat(order.value()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }
}
