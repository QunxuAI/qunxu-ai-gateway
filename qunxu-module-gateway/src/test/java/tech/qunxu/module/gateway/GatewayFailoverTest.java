package tech.qunxu.module.gateway;

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
import static org.mockito.Mockito.when;

/**
 * M4 故障转移端到端测试：真实 Netty + 完整流水线，下游由内置 JDK {@link HttpServer} 桩模拟两个 OpenAI 兼容通道。
 *
 * <p>验证 {@code InvokeFilter} 的故障转移：首选通道返回 5xx（PROVIDER_ERROR，可转移）时，剔除该通道并经
 * {@code RoutingService} 在剩余健康通道内重选成功；当所有通道均失败时，传播<b>原始下游错误</b>（502 provider_error）
 * 而非笼统的 503。{@link ConfigService} 以 {@link MockitoBean} 替换，健康存储用内存实现（test profile）。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class GatewayFailoverTest {

    private static final String OK_JSON = """
            {"id":"chatcmpl-ok","object":"chat.completion","created":1700000000,"model":"up-ok",\
            "choices":[{"index":0,"message":{"role":"assistant","content":"Hello from OK"},"finish_reason":"stop"}],\
            "usage":{"prompt_tokens":8,"completion_tokens":4,"total_tokens":12}}""";

    private static final String ERROR_JSON = """
            {"error":{"message":"upstream exploded","type":"server_error","code":500}}""";

    private static HttpServer server;
    private static String base;

    @LocalServerPort
    private int port;

    @MockitoBean
    private ConfigService configService;

    private WebTestClient client;

    @BeforeAll
    static void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/fail1/chat/completions", ex -> respond(ex, 500, ERROR_JSON));
        server.createContext("/fail2/chat/completions", ex -> respond(ex, 500, ERROR_JSON));
        server.createContext("/ok/chat/completions", ex -> respond(ex, 200, OK_JSON));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopStub() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        ex.getRequestBody().readAllBytes();
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
        ex.close();
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
    }

    private static ModelChannelEntity channel(long id, long providerId, int weight) {
        ModelChannelEntity c = new ModelChannelEntity();
        c.setId(id);
        c.setLogicalModelId(1L);
        c.setProviderId(providerId);
        c.setUpstreamModel("up-" + id);
        c.setProtocol("OPENAI_COMPAT");
        c.setWeight(weight);
        c.setPriority(1);
        c.setEnabled(1);
        return c;
    }

    private static ProviderEntity provider(long id, String path) {
        ProviderEntity p = new ProviderEntity();
        p.setId(id);
        p.setCode("DEEPSEEK");
        p.setBaseUrl(base + path);
        p.setAuthType("BEARER");
        p.setStatus(1);
        p.setCredentials("sk-test");
        return p;
    }

    @Test
    void failsOverToHealthyChannelWhenPrimaryReturns5xx() {
        // 通道10（权重1000，首选）指向 /fail1（500）；通道20（权重1）指向 /ok（200）
        when(configService.findEnabledChannels(1L))
                .thenReturn(Mono.just(List.of(channel(10, 100L, 1000), channel(20, 200L, 1))));
        when(configService.findProviderById(100L)).thenReturn(Mono.just(provider(100L, "/fail1")));
        when(configService.findProviderById(200L)).thenReturn(Mono.just(provider(200L, "/ok")));

        client.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"model\":\"deepseek-chat\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> {
                    assertThat(body).contains("Hello from OK");
                    assertThat(body).contains("\"total_tokens\":12");
                });
    }

    @Test
    void allChannelsFailingPropagatesDownstreamError() {
        // 两个通道均返回 500：故障转移耗尽后传播原始 PROVIDER_ERROR（502），而非 503
        when(configService.findEnabledChannels(1L))
                .thenReturn(Mono.just(List.of(channel(10, 100L, 1000), channel(30, 300L, 1))));
        when(configService.findProviderById(100L)).thenReturn(Mono.just(provider(100L, "/fail1")));
        when(configService.findProviderById(300L)).thenReturn(Mono.just(provider(300L, "/fail2")));

        client.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"model\":\"deepseek-chat\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")
                .exchange()
                .expectStatus().isEqualTo(502)
                .expectBody(String.class)
                .value(body -> assertThat(body).contains("provider_error"));
    }
}
