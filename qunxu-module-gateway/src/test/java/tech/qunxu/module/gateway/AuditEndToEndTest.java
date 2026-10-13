package tech.qunxu.module.gateway;

import tech.qunxu.module.gateway.audit.AuditRecord;
import tech.qunxu.module.gateway.audit.BatchAuditWriter;
import tech.qunxu.module.gateway.common.enums.CallStatus;
import tech.qunxu.module.gateway.persistence.entity.LogicalModelEntity;
import tech.qunxu.module.gateway.persistence.entity.ModelChannelEntity;
import tech.qunxu.module.gateway.persistence.entity.ProviderEntity;
import tech.qunxu.module.gateway.persistence.service.ConfigService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M5 审计端到端测试：真实 Netty + 完整流水线，下游由内置 JDK {@link HttpServer} 桩模拟。
 * {@link BatchAuditWriter} 以 {@link MockitoBean} 替换，从而捕获最外层 {@code AuditFilter} 在链路结束时
 * 组装的 {@link AuditRecord}，验证：非流式成功审计、流式响应文本聚合审计、以及<b>早期失败</b>（模型未找到 404）
 * 同样被审计（证明审计过滤器位于最外层、覆盖短路失败）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AuditEndToEndTest {

    private static final String UNARY_JSON = """
            {"id":"chatcmpl-stub","object":"chat.completion","created":1700000000,"model":"deepseek-chat",\
            "choices":[{"index":0,"message":{"role":"assistant","content":"Hello from stub"},"finish_reason":"stop"}],\
            "usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}""";

    private static final String[] SSE_CHUNKS = {
            "{\"id\":\"c1\",\"model\":\"deepseek-chat\",\"created\":1,\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\"},\"finish_reason\":null}]}",
            "{\"id\":\"c1\",\"model\":\"deepseek-chat\",\"created\":1,\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hello\"},\"finish_reason\":null}]}",
            "{\"id\":\"c1\",\"model\":\"deepseek-chat\",\"created\":1,\"choices\":[{\"index\":0,\"delta\":{\"content\":\" world\"},\"finish_reason\":null}]}",
            "{\"id\":\"c1\",\"model\":\"deepseek-chat\",\"created\":1,\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}",
            "{\"id\":\"c1\",\"model\":\"deepseek-chat\",\"created\":1,\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}"
    };

    private static HttpServer server;
    private static String baseUrl;

    @LocalServerPort
    private int port;

    @MockitoBean
    private ConfigService configService;

    @MockitoBean
    private BatchAuditWriter auditWriter;

    private WebTestClient client;

    @BeforeAll
    static void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", AuditEndToEndTest::handle);
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
        String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
            return;
        }
        if (requestBody.contains("\"stream\":true")) {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream os = exchange.getResponseBody()) {
                for (String chunk : SSE_CHUNKS) {
                    os.write(("data: " + chunk + "\n\n").getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
                os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
        } else {
            byte[] resp = UNARY_JSON.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        }
        exchange.close();
    }

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(20))
                .build();

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

    @Test
    void unarySuccessIsAudited() {
        client.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"model\":\"deepseek-chat\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")
                .exchange()
                .expectStatus().isOk();

        ArgumentCaptor<AuditRecord> captor = ArgumentCaptor.forClass(AuditRecord.class);
        verify(auditWriter, timeout(3000)).submit(captor.capture());
        AuditRecord r = captor.getValue();

        assertThat(r.status()).isEqualTo(CallStatus.SUCCESS);
        assertThat(r.protocol()).isEqualTo("OPENAI");
        assertThat(r.endpoint()).isEqualTo("chat.completions");
        assertThat(r.logicalModel()).isEqualTo("deepseek-chat");
        assertThat(r.channelId()).isEqualTo(10L);
        assertThat(r.providerId()).isEqualTo(100L);
        assertThat(r.providerCode()).isEqualTo("DEEPSEEK");
        assertThat(r.promptTokens()).isEqualTo(10);
        assertThat(r.completionTokens()).isEqualTo(5);
        assertThat(r.totalTokens()).isEqualTo(15);
        assertThat(r.responseBody()).contains("Hello from stub");
        assertThat(r.requestBody()).contains("deepseek-chat");
        assertThat(r.latencyMs()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    void streamSuccessAggregatesResponseTextForAudit() {
        client.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"model\":\"deepseek-chat\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseBody()
                .collectList()
                .block(Duration.ofSeconds(15));

        ArgumentCaptor<AuditRecord> captor = ArgumentCaptor.forClass(AuditRecord.class);
        verify(auditWriter, timeout(3000)).submit(captor.capture());
        AuditRecord r = captor.getValue();

        assertThat(r.status()).isEqualTo(CallStatus.SUCCESS);
        // 流式：响应体来自增量聚合文本，而非序列化 UnifiedResponse
        assertThat(r.responseBody()).contains("Hello").contains("world");
        assertThat(r.totalTokens()).isEqualTo(15);
        assertThat(r.firstTokenMs()).isNotNull();
    }

    @Test
    void earlyFailureModelNotFoundIsAudited() {
        client.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"model\":\"unknown-model\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")
                .exchange()
                .expectStatus().isNotFound();

        ArgumentCaptor<AuditRecord> captor = ArgumentCaptor.forClass(AuditRecord.class);
        verify(auditWriter, timeout(3000)).submit(captor.capture());
        AuditRecord r = captor.getValue();

        // 模型解析阶段短路失败仍被最外层审计捕获
        assertThat(r.status()).isEqualTo(CallStatus.FAILURE);
        assertThat(r.errorCode()).isEqualTo("MODEL_NOT_FOUND");
        assertThat(r.logicalModel()).isEqualTo("unknown-model");
        assertThat(r.channelId()).isNull();
    }
}
