package tech.qunxu.module.gateway;

import tech.qunxu.module.gateway.observability.GatewayMetrics;
import tech.qunxu.module.gateway.persistence.entity.LogicalModelEntity;
import tech.qunxu.module.gateway.persistence.entity.ModelChannelEntity;
import tech.qunxu.module.gateway.persistence.entity.ProviderEntity;
import tech.qunxu.module.gateway.persistence.service.ConfigService;
import tech.qunxu.module.gateway.security.InMemoryRateLimiter;
import tech.qunxu.module.gateway.security.RateLimiter;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.when;

/**
 * M8 端到端集成测试：真实 Netty 服务器 + 完整流水线，验证<b>限流闸门</b>与<b>可观测性出口</b>。
 *
 * <p>限流以 {@code in-memory} 令牌桶承载并把每分钟许可压到 2，从而在毫秒级内触发 429；断言覆盖
 * 状态码、OpenAI 风格错误体、退避响应头、指标计数，以及「限流先于模型解析」这一顺序契约
 * （解析失败的请求同样占用配额，否则可用未知模型名绕过限流）。</p>
 *
 * <p>可观测性部分验证 Actuator 健康探针（含自定义 upstream 指示器）与 Prometheus 抓取端点确实暴露
 * 网关自定义指标——指标若未被 scrape 到，告警与容量规划都无从谈起。</p>
 *
 * <p>指标断言一律「每用例零点重置 + 轮询等待」：{@link tech.qunxu.module.gateway.api.GatewayInvoker} 把
 * 「写回响应」放在流水线<b>链尾</b>，故 {@code MetricsFilter} 的 {@code doFinally} 必然晚于客户端收到
 * 完整响应才触发；而 {@code MeterRegistry} 又是上下文级单例，跨用例累积。二者叠加使得任何
 * 「响应返回后立即读绝对值」的断言都是竞态的。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "gateway.rate-limit.enabled=true",
        "gateway.rate-limit.store=in-memory",
        "gateway.rate-limit.default-permits-per-minute=2",
        "gateway.rate-limit.burst-ratio=1.0",
        "gateway.rate-limit.per-model-enabled=false",
        "gateway.quota.enabled=false"
})
@ActiveProfiles("test")
class RateLimitEndToEndTest {

    private static final String UNARY_JSON = """
            {"id":"chatcmpl-stub","object":"chat.completion","created":1700000000,"model":"deepseek-chat",\
            "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],\
            "usage":{"prompt_tokens":3,"completion_tokens":2,"total_tokens":5}}""";

    /** 指标落地等待上限。 */
    private static final Duration WAIT = Duration.ofSeconds(10);
    /** 指标落地轮询间隔。 */
    private static final Duration POLL = Duration.ofMillis(50);

    private static HttpServer server;
    private static String baseUrl;

    @LocalServerPort
    private int port;

    @MockitoBean
    private ConfigService configService;

    @Autowired
    private RateLimiter rateLimiter;

    @Autowired
    private MeterRegistry meterRegistry;

    private WebTestClient client;

    /** 上一次采样到的网关指标签名，用于判定注册表是否已静默。 */
    private String metricSignature = "";

    @BeforeAll
    static void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", RateLimitEndToEndTest::handle);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopStub() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static void handle(HttpExchange exchange) throws IOException {
        exchange.getRequestBody().readAllBytes();
        byte[] response = UNARY_JSON.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(response);
        }
        exchange.close();
    }

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(20))
                .build();
        // 令牌桶是上下文级单例，跨用例必须清零，否则补充速率（2/分钟）会让后续断言相互污染
        assertThat(rateLimiter).isInstanceOf(InMemoryRateLimiter.class);
        ((InMemoryRateLimiter) rateLimiter).reset();
        resetGatewayMetrics();

        LogicalModelEntity model = new LogicalModelEntity();
        model.setId(1L);
        model.setName("deepseek-chat");
        model.setCapability("CHAT");
        model.setStatus(1);
        when(configService.findLogicalModelByName("deepseek-chat")).thenReturn(Mono.just(model));
        when(configService.findLogicalModelByName("unknown-model")).thenReturn(Mono.empty());

        ModelChannelEntity channel = new ModelChannelEntity();
        channel.setId(10L);
        channel.setLogicalModelId(1L);
        channel.setProviderId(100L);
        channel.setUpstreamModel("deepseek-chat");
        channel.setProtocol("OPENAI_COMPAT");
        channel.setWeight(100);
        channel.setPriority(1);
        channel.setEnabled(1);
        when(configService.findEnabledChannels(1L)).thenReturn(Mono.just(List.of(channel)));

        ProviderEntity provider = new ProviderEntity();
        provider.setId(100L);
        provider.setCode("DEEPSEEK");
        provider.setBaseUrl(baseUrl);
        provider.setAuthType("BEARER");
        provider.setStatus(1);
        provider.setCredentials("sk-test");
        when(configService.findProviderById(100L)).thenReturn(Mono.just(provider));
    }

    private WebTestClient.ResponseSpec post(String model) {
        return client.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"model\":\"" + model + "\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")
                .exchange();
    }

    /**
     * 清空网关指标，使每个用例都从确定的零点开始，从而可以断言绝对值。
     *
     * <p>直接移除会漏掉上一用例「尚在途中」的计数（响应已回但 {@code doFinally} 未跑完），
     * 因此先轮询等待注册表静默——指标签名连续两次采样一致——再移除全部 {@code gateway.*} 指标。
     * 只清网关自有指标，不动 JVM/Hikari/HTTP 等由 {@code MeterBinder} 一次性绑定的指标
     * （它们被移除后不会重新绑定）。</p>
     */
    private void resetGatewayMetrics() {
        await().atMost(WAIT).pollDelay(Duration.ZERO).pollInterval(POLL).until(() -> {
            String signature = gatewaySignature();
            boolean quiesced = signature.equals(metricSignature);
            metricSignature = signature;
            return quiesced;
        });
        meterRegistry.getMeters().stream()
                .filter(meter -> meter.getId().getName().startsWith("gateway."))
                .toList()
                .forEach(meterRegistry::remove);
        metricSignature = "";
    }

    /** 网关指标的完整快照签名（名称 + 标签 + 当前测量值），用于静默判定。 */
    private String gatewaySignature() {
        StringBuilder signature = new StringBuilder();
        meterRegistry.getMeters().stream()
                .filter(meter -> meter.getId().getName().startsWith("gateway."))
                .map(meter -> {
                    StringBuilder part = new StringBuilder(meter.getId().toString());
                    meter.measure().forEach(measurement -> part.append(measurement));
                    return part.toString();
                })
                .sorted()
                .forEach(part -> signature.append(part).append('|'));
        return signature.toString();
    }

    /** 读取计数器累计值；同名同标签的多个计数器求和，未注册时视为 0。 */
    private double count(String name, String... tags) {
        return meterRegistry.find(name).tags(tags).counters().stream()
                .mapToDouble(Counter::count)
                .sum();
    }

    @Test
    void thirdCallWithinWindowIsThrottledWith429() {
        post("deepseek-chat").expectStatus().isOk();
        post("deepseek-chat").expectStatus().isOk();

        post("deepseek-chat")
                .expectStatus().isEqualTo(429)
                .expectHeader().valueEquals("X-RateLimit-Limit", "2")
                .expectHeader().valueEquals("X-RateLimit-Remaining", "0")
                .expectHeader().exists("Retry-After")
                .expectBody(String.class)
                .value(body -> {
                    assertThat(body).contains("rate_limit_exceeded");
                    assertThat(body).contains("调用频率超限");
                });
    }

    @Test
    void throttlingIsCountedAsRateLimitedRejectionMetric() {
        post("deepseek-chat").expectStatus().isOk();
        post("deepseek-chat").expectStatus().isOk();
        post("deepseek-chat").expectStatus().isEqualTo(429);

        await().atMost(WAIT).pollInterval(POLL).untilAsserted(() -> {
            assertThat(count(GatewayMetrics.RATE_LIMIT_REJECTIONS, "scope", "caller")).isEqualTo(1.0);
            // 限流发生在 MODEL_RESOLVE 之前且不改写 ctx.status，
            // 故请求指标记为 FAILURE + error=RATE_LIMITED，而非围栏专用的 BLOCKED
            assertThat(count(GatewayMetrics.REQUESTS, "status", "FAILURE", "error", "RATE_LIMITED"))
                    .isEqualTo(1.0);
            assertThat(count(GatewayMetrics.REQUESTS, "status", "SUCCESS")).isEqualTo(2.0);
        });
    }

    @Test
    void rateLimitRunsBeforeModelResolution() {
        // 未知模型会在 MODEL_RESOLVE 阶段失败，但仍必须先扣减令牌，否则可用无效模型名刷穿限流
        post("unknown-model").expectStatus().isNotFound();
        post("unknown-model").expectStatus().isNotFound();

        post("unknown-model")
                .expectStatus().isEqualTo(429)
                .expectBody(String.class).value(body -> assertThat(body).contains("rate_limit_exceeded"));
    }

    @Test
    void permittedCallsStillReachUpstream() {
        post("deepseek-chat")
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> assertThat(body).contains("\"total_tokens\":5"));

        await().atMost(WAIT).pollInterval(POLL).untilAsserted(() -> {
            assertThat(count(GatewayMetrics.REQUESTS, "status", "SUCCESS", "model", "deepseek-chat"))
                    .isEqualTo(1.0);
            // 下游 usage 必须原样计入三个分项计数器，成本核算依赖它
            assertThat(count(GatewayMetrics.TOKENS, "model", "deepseek-chat", "type", "prompt")).isEqualTo(3.0);
            assertThat(count(GatewayMetrics.TOKENS, "model", "deepseek-chat", "type", "completion")).isEqualTo(2.0);
            assertThat(count(GatewayMetrics.TOKENS, "model", "deepseek-chat", "type", "total")).isEqualTo(5.0);
        });
    }

    // ------------------------------------------------------------------ 可观测性出口

    @Test
    void actuatorHealthIsUpWithCustomUpstreamIndicator() {
        client.get().uri("/actuator/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> assertThat(body).contains("\"status\":\"UP\""));
    }

    @Test
    void kubernetesProbesAreExposed() {
        // 计划要求无状态水平扩容，K8s 探针端点必须可用
        client.get().uri("/actuator/health/liveness")
                .exchange().expectStatus().isOk();
        client.get().uri("/actuator/health/readiness")
                .exchange().expectStatus().isOk();
    }

    @Test
    void prometheusEndpointScrapesGatewayMetrics() {
        post("deepseek-chat").expectStatus().isOk();
        // 指标晚于响应下发才注册，先等它落到 Prometheus 注册表再抓取
        await().atMost(WAIT).pollInterval(POLL)
                .until(() -> count(GatewayMetrics.REQUESTS, "status", "SUCCESS") == 1.0);

        String scrape = client.get().uri("/actuator/prometheus")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(scrape)
                .contains("gateway_requests_total")
                .contains("gateway_tokens_total")
                .contains("gateway_request_latency_seconds");
    }
}
