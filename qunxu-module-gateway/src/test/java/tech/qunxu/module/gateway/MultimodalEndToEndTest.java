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
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
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
 * M6 多模态端到端集成测试：真实 Netty 服务器 + 完整流水线（解析/路由/适配/渲染）+ 真实适配器 HTTP 调用，
 * 下游由内置 JDK {@link HttpServer} 桩同时模拟 OpenAI 兼容端点（embeddings/audio）与 DashScope 原生端点
 * （rerank/image 异步任务）。覆盖 embeddings、rerank、image、TTS（二进制音频）、STT（multipart）五条链路。
 * {@link ConfigService} 以 {@link MockitoBean} 替换，避免依赖真实数据库。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class MultimodalEndToEndTest {

    private static final String EMBEDDING_JSON = """
            {"object":"list","id":"emb-1","model":"text-embedding-v3",\
            "data":[{"object":"embedding","index":0,"embedding":[0.1,0.2,0.3]}],\
            "usage":{"prompt_tokens":2,"total_tokens":2}}""";

    private static final String RERANK_JSON = """
            {"output":{"results":[{"index":0,"relevance_score":0.9,"document":{"text":"a"}}]},\
            "request_id":"r-1","usage":{"total_tokens":10}}""";

    private static final String IMAGE_SUBMIT_JSON = """
            {"output":{"task_id":"t-1","task_status":"PENDING"},"request_id":"i-1"}""";

    private static final String IMAGE_DONE_JSON = """
            {"output":{"task_id":"t-1","task_status":"SUCCEEDED","results":[{"url":"https://img.example/1.png"}]},\
            "request_id":"i-1"}""";

    private static final String STT_JSON = "{\"text\":\"识别结果\"}";
    private static final byte[] TTS_AUDIO = {1, 2, 3, 4, 5};

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
        server.createContext("/embeddings", ex -> guardThen(ex, "application/json", EMBEDDING_JSON));
        server.createContext("/api/v1/services/rerank/text-rerank/text-rerank",
                ex -> guardThen(ex, "application/json", RERANK_JSON));
        server.createContext("/api/v1/services/aigc/text2image/image-synthesis",
                ex -> guardThen(ex, "application/json", IMAGE_SUBMIT_JSON));
        server.createContext("/api/v1/tasks", ex -> guardThen(ex, "application/json", IMAGE_DONE_JSON));
        server.createContext("/audio/speech", MultimodalEndToEndTest::handleSpeech);
        server.createContext("/audio/transcriptions", ex -> guardThen(ex, "application/json", STT_JSON));
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopStub() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** 校验 Bearer 鉴权后返回固定 JSON。 */
    private static void guardThen(HttpExchange exchange, String contentType, String body) throws IOException {
        exchange.getRequestBody().readAllBytes();
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
            return;
        }
        byte[] resp = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(200, resp.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(resp);
        }
        exchange.close();
    }

    /** TTS：返回二进制音频。 */
    private static void handleSpeech(HttpExchange exchange) throws IOException {
        exchange.getRequestBody().readAllBytes();
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "audio/mpeg");
        exchange.sendResponseHeaders(200, TTS_AUDIO.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(TTS_AUDIO);
        }
        exchange.close();
    }

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(20))
                .build();

        ProviderEntity provider = new ProviderEntity();
        provider.setId(100L);
        provider.setCode("DASHSCOPE");
        provider.setBaseUrl(baseUrl);
        provider.setAuthType("BEARER");
        provider.setStatus(1);
        provider.setCredentials("sk-test");
        when(configService.findProviderById(100L)).thenReturn(Mono.just(provider));

        stubModel(1L, "text-embedding-v3", "EMBEDDING", 10L, "OPENAI_COMPAT", "text-embedding-v3");
        stubModel(2L, "gte-rerank", "RERANK", 20L, "DASHSCOPE_NATIVE", "gte-rerank");
        stubModel(3L, "wanx-v1", "IMAGE", 30L, "DASHSCOPE_NATIVE", "wanx-v1");
        stubModel(4L, "cosyvoice-v1", "TTS", 40L, "OPENAI_COMPAT", "cosyvoice-v1");
        stubModel(5L, "paraformer", "STT", 50L, "OPENAI_COMPAT", "paraformer");
    }

    private void stubModel(long modelId, String modelName, String capability,
                           long channelId, String protocol, String upstreamModel) {
        LogicalModelEntity model = new LogicalModelEntity();
        model.setId(modelId);
        model.setName(modelName);
        model.setCapability(capability);
        model.setStatus(1);
        when(configService.findLogicalModelByName(modelName)).thenReturn(Mono.just(model));

        ModelChannelEntity channel = new ModelChannelEntity();
        channel.setId(channelId);
        channel.setLogicalModelId(modelId);
        channel.setProviderId(100L);
        channel.setUpstreamModel(upstreamModel);
        channel.setProtocol(protocol);
        channel.setWeight(100);
        channel.setPriority(1);
        channel.setEnabled(1);
        when(configService.findEnabledChannels(modelId)).thenReturn(Mono.just(List.of(channel)));
    }

    @Test
    void embeddingsEndToEnd() {
        client.post().uri("/v1/embeddings")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"model\":\"text-embedding-v3\",\"input\":[\"你好\"]}")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody(String.class)
                .value(body -> {
                    assertThat(body).contains("\"object\":\"list\"");
                    assertThat(body).contains("\"embedding\"");
                    assertThat(body).contains("0.1");
                });
    }

    @Test
    void rerankEndToEnd() {
        client.post().uri("/v1/rerank")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"model\":\"gte-rerank\",\"query\":\"q\",\"documents\":[\"a\",\"b\"]}")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> {
                    assertThat(body).contains("\"results\"");
                    assertThat(body).contains("\"relevance_score\"");
                    assertThat(body).contains("0.9");
                });
    }

    @Test
    void imageEndToEnd() {
        client.post().uri("/v1/images/generations")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"model\":\"wanx-v1\",\"prompt\":\"一只猫\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> {
                    assertThat(body).contains("\"data\"");
                    assertThat(body).contains("https://img.example/1.png");
                });
    }

    @Test
    void ttsEndToEndReturnsBinaryAudio() {
        client.post().uri("/v1/audio/speech")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"model\":\"cosyvoice-v1\",\"input\":\"你好\"}")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.parseMediaType("audio/mpeg"))
                .expectBody(byte[].class)
                .value(bytes -> assertThat(bytes).isEqualTo(TTS_AUDIO));
    }

    @Test
    void sttEndToEndWithMultipart() {
        MultipartBodyBuilder multipart = new MultipartBodyBuilder();
        multipart.part("model", "paraformer");
        ByteArrayResource file = new ByteArrayResource(new byte[]{9, 9, 9}) {
            @Override
            public String getFilename() {
                return "audio.mp3";
            }
        };
        multipart.part("file", file).contentType(MediaType.parseMediaType("audio/mpeg"));

        client.post().uri("/v1/audio/transcriptions")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(multipart.build()))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> assertThat(body).contains("识别结果"));
    }
}
