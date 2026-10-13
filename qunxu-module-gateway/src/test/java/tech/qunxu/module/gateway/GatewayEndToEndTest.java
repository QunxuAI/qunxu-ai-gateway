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
import static org.mockito.Mockito.when;

/**
 * M2 端到端集成测试：真实 Netty 服务器 + 完整流水线（鉴权/解析/路由/调用）+ 真实适配器 HTTP 调用，
 * 下游由内置 JDK {@link HttpServer} 桩模拟 OpenAI 兼容供应商。验证 OpenAI 非流式/流式与 Anthropic 三条链路，
 * 以及模型未找到错误路径。{@link ConfigService} 以 {@link MockitoBean} 替换，避免依赖真实数据库。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class GatewayEndToEndTest {

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

    private WebTestClient client;

    @BeforeAll
    static void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", GatewayEndToEndTest::handle);
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
    void openAiUnaryChatCompletionEndToEnd() {
        client.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"model\":\"deepseek-chat\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody(String.class)
                .value(body -> {
                    assertThat(body).contains("\"object\":\"chat.completion\"");
                    assertThat(body).contains("Hello from stub");
                    assertThat(body).contains("\"total_tokens\":15");
                });
    }

    @Test
    void openAiStreamChatCompletionEndToEnd() {
        List<String> frames = client.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"model\":\"deepseek-chat\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(String.class)
                .getResponseBody()
                .collectList()
                .block(Duration.ofSeconds(15));

        String joined = String.join("", frames == null ? List.of() : frames);
        assertThat(joined)
                .contains("chat.completion.chunk")
                .contains("Hello")
                .contains("world")
                .contains("[DONE]");
    }

    @Test
    void anthropicMessagesEndToEnd() {
        client.post().uri("/v1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"model\":\"deepseek-chat\",\"max_tokens\":64,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody(String.class)
                .value(body -> {
                    assertThat(body).contains("\"type\":\"message\"");
                    assertThat(body).contains("\"stop_reason\":\"end_turn\"");
                    assertThat(body).contains("Hello from stub");
                });
    }

    @Test
    void unknownModelReturns404() {
        client.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"model\":\"unknown-model\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody(String.class)
                .value(body -> assertThat(body).contains("model_not_found"));
    }
}
