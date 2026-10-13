package tech.qunxu.module.gateway.adapter;

import tech.qunxu.module.gateway.adapter.auth.BearerAuthenticator;
import tech.qunxu.module.gateway.common.enums.Capability;
import tech.qunxu.module.gateway.common.enums.ChannelProtocol;
import tech.qunxu.module.gateway.common.enums.ProviderType;
import tech.qunxu.module.gateway.common.model.UnifiedRequest;
import tech.qunxu.module.gateway.health.HealthResult;
import tech.qunxu.module.gateway.persistence.entity.ModelChannelEntity;
import tech.qunxu.module.gateway.persistence.entity.ProviderEntity;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DashScope 原生适配器集成测试：以内置 JDK {@link HttpServer} 桩重排（同步 REST）与文生图（wanx 异步任务 + 轮询），
 * 真实经过 WebClient → HTTP 链路，验证结果归一、异步轮询直至 SUCCEEDED、主动探测的上线/凭证过期判定。
 */
class DashScopeNativeAdapterTest {

    private static final String RERANK_PATH = "/api/v1/services/rerank/text-rerank/text-rerank";
    private static final String IMAGE_PATH = "/api/v1/services/aigc/text2image/image-synthesis";
    private static final String TASK_PATH = "/api/v1/tasks";

    private static final String RERANK_JSON = """
            {"output":{"results":[\
            {"index":1,"relevance_score":0.95,"document":{"text":"文档B"}},\
            {"index":0,"relevance_score":0.31,"document":{"text":"文档A"}}]},\
            "request_id":"req-rerank-1","usage":{"total_tokens":36}}""";

    private static final String IMAGE_SUBMIT_JSON = """
            {"output":{"task_id":"task-abc","task_status":"PENDING"},"request_id":"req-img-1"}""";

    private static final String TASK_RUNNING_JSON = """
            {"output":{"task_id":"task-abc","task_status":"RUNNING"},"request_id":"req-img-1"}""";

    private static final String TASK_SUCCEEDED_JSON = """
            {"output":{"task_id":"task-abc","task_status":"SUCCEEDED",\
            "results":[{"url":"https://img.example/1.png"}]},"request_id":"req-img-1"}""";

    private static HttpServer server;
    private static String baseUrl;
    private static final AtomicBoolean rejectAuth = new AtomicBoolean(false);
    private static final AtomicInteger taskPollCount = new AtomicInteger(0);
    private static final AtomicReference<String> lastBody = new AtomicReference<>();

    private DashScopeNativeAdapter adapter;
    private ChannelRuntime runtime;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(RERANK_PATH, DashScopeNativeAdapterTest::handleRerank);
        server.createContext(IMAGE_PATH, DashScopeNativeAdapterTest::handleImageSubmit);
        server.createContext(TASK_PATH, DashScopeNativeAdapterTest::handleTask);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static boolean unauthorized(HttpExchange exchange) throws IOException {
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (rejectAuth.get() || auth == null || !auth.startsWith("Bearer ")) {
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
            return true;
        }
        return false;
    }

    private static void handleRerank(HttpExchange exchange) throws IOException {
        lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        if (unauthorized(exchange)) {
            return;
        }
        writeJson(exchange, 200, RERANK_JSON);
    }

    private static void handleImageSubmit(HttpExchange exchange) throws IOException {
        lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        if (unauthorized(exchange)) {
            return;
        }
        writeJson(exchange, 200, IMAGE_SUBMIT_JSON);
    }

    private static void handleTask(HttpExchange exchange) throws IOException {
        if (unauthorized(exchange)) {
            return;
        }
        int n = taskPollCount.getAndIncrement();
        writeJson(exchange, 200, n == 0 ? TASK_RUNNING_JSON : TASK_SUCCEEDED_JSON);
    }

    private static void writeJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] resp = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, resp.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(resp);
        }
        exchange.close();
    }

    @BeforeEach
    void setUp() {
        rejectAuth.set(false);
        taskPollCount.set(0);
        lastBody.set(null);

        WebClient webClient = WebClient.builder().build();
        ObjectMapper mapper = new ObjectMapper();
        UpstreamAuthenticatorResolver resolver = new UpstreamAuthenticatorResolver(List.of(new BearerAuthenticator()));
        adapter = new DashScopeNativeAdapter(webClient, mapper, resolver);

        ModelChannelEntity channel = new ModelChannelEntity();
        channel.setId(1L);
        channel.setUpstreamModel("gte-rerank");
        channel.setProtocol("DASHSCOPE_NATIVE");
        ProviderEntity provider = new ProviderEntity();
        provider.setId(1L);
        provider.setCode("DASHSCOPE");
        provider.setBaseUrl(baseUrl);
        provider.setAuthType("BEARER");
        // 缩短轮询间隔以加速测试
        runtime = new ChannelRuntime(channel, provider, ProviderType.DASHSCOPE, ChannelProtocol.DASHSCOPE_NATIVE,
                baseUrl, "gte-rerank", ProviderCredentials.of(Map.of("apiKey", "sk-dashscope")),
                Map.of("imagePollIntervalMs", 30, "imageMaxPolls", 5));
    }

    @Test
    void invokeRerankParsesResultsAndUsage() {
        UnifiedRequest request = UnifiedRequest.rerank("gte-rerank",
                new UnifiedRequest.RerankPayload("查询", List.of("文档A", "文档B"), 2, true));

        StepVerifier.create(adapter.invokeUnary(request, runtime))
                .assertNext(response -> {
                    assertThat(response.capability()).isEqualTo(Capability.RERANK);
                    assertThat(response.id()).isEqualTo("req-rerank-1");
                    assertThat(response.rerankResults()).hasSize(2);
                    assertThat(response.rerankResults().get(0).index()).isEqualTo(1);
                    assertThat(response.rerankResults().get(0).relevanceScore()).isEqualTo(0.95);
                    assertThat(response.rerankResults().get(0).document()).isEqualTo("文档B");
                    assertThat(response.usage().totalTokens()).isEqualTo(36);
                })
                .verifyComplete();

        assertThat(lastBody.get()).contains("\"query\":\"查询\"").contains("文档A");
    }

    @Test
    void invokeImagePollsUntilSucceeded() {
        UnifiedRequest request = UnifiedRequest.image("wanx-v1", false,
                new UnifiedRequest.ImagePayload("一只猫", "1024*1024", 1, null, null, null));

        StepVerifier.create(adapter.invokeUnary(request, runtime))
                .assertNext(response -> {
                    assertThat(response.capability()).isEqualTo(Capability.IMAGE);
                    assertThat(response.images()).hasSize(1);
                    assertThat(response.images().get(0).url()).isEqualTo("https://img.example/1.png");
                })
                .verifyComplete();

        // 至少经历一次 RUNNING 再 SUCCEEDED
        assertThat(taskPollCount.get()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void probeReturnsUpWhenReachable() {
        StepVerifier.create(adapter.probe(runtime))
                .assertNext(result -> {
                    assertThat(result.isUp()).isTrue();
                    assertThat(result.latencyMs()).isGreaterThanOrEqualTo(0);
                })
                .verifyComplete();
    }

    @Test
    void probeReturnsAuthExpiredOn401() {
        rejectAuth.set(true);
        StepVerifier.create(adapter.probe(runtime))
                .assertNext(result -> {
                    assertThat(result.isUp()).isFalse();
                    assertThat(result.isAuthExpired()).isTrue();
                    assertThat(result.status()).isEqualTo(tech.qunxu.module.gateway.common.enums.HealthStatus.DOWN);
                })
                .verifyComplete();
    }

    @Test
    void invokeStreamIsNotImplemented() {
        UnifiedRequest request = UnifiedRequest.rerank("gte-rerank",
                new UnifiedRequest.RerankPayload("q", List.of("d"), null, null));
        StepVerifier.create(adapter.invokeStream(request, runtime))
                .verifyError(tech.qunxu.module.gateway.common.exception.GatewayException.class);
    }
}
