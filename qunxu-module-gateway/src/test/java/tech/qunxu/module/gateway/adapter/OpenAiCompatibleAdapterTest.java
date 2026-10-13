package tech.qunxu.module.gateway.adapter;

import tech.qunxu.module.gateway.adapter.auth.BearerAuthenticator;
import tech.qunxu.module.gateway.common.enums.Capability;
import tech.qunxu.module.gateway.common.enums.ChannelProtocol;
import tech.qunxu.module.gateway.common.enums.ProviderType;
import tech.qunxu.module.gateway.common.model.GenerationParams;
import tech.qunxu.module.gateway.common.model.UnifiedMessage;
import tech.qunxu.module.gateway.common.model.UnifiedRequest;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpenAI 兼容适配器集成测试：以内置 JDK {@link HttpServer} 桩一个 {@code /chat/completions} 端点，
 * 真实经过 WebClient → HTTP → SSE 解析链路，验证非流式响应解析、流式增量解析与鉴权头透传。
 */
class OpenAiCompatibleAdapterTest {

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
    private static final AtomicReference<String> lastAuth = new AtomicReference<>();
    private static final AtomicReference<String> lastBody = new AtomicReference<>();

    private OpenAiCompatibleAdapter adapter;
    private ChannelRuntime runtime;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", OpenAiCompatibleAdapterTest::handle);
        server.createContext("/embeddings", OpenAiCompatibleAdapterTest::handleEmbedding);
        server.createContext("/audio/speech", OpenAiCompatibleAdapterTest::handleSpeech);
        server.createContext("/audio/transcriptions", OpenAiCompatibleAdapterTest::handleTranscription);
        server.createContext("/models", OpenAiCompatibleAdapterTest::handleModels);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static void handle(HttpExchange exchange) throws IOException {
        byte[] requestBytes = exchange.getRequestBody().readAllBytes();
        String requestBody = new String(requestBytes, StandardCharsets.UTF_8);
        lastBody.set(requestBody);
        lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));

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

    private static void handleEmbedding(HttpExchange exchange) throws IOException {
        lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
            return;
        }
        String json = """
                {"object":"list","id":"emb-1","model":"text-embedding-v3",\
                "data":[{"object":"embedding","index":0,"embedding":[0.1,0.2,0.3]},\
                {"object":"embedding","index":1,"embedding":[0.4,0.5,0.6]}],\
                "usage":{"prompt_tokens":4,"total_tokens":4}}""";
        byte[] resp = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, resp.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(resp);
        }
        exchange.close();
    }

    private static void handleSpeech(HttpExchange exchange) throws IOException {
        lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
            return;
        }
        byte[] audio = new byte[]{1, 2, 3, 4, 5};
        exchange.getResponseHeaders().set("Content-Type", "audio/mpeg");
        exchange.sendResponseHeaders(200, audio.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(audio);
        }
        exchange.close();
    }

    private static void handleTranscription(HttpExchange exchange) throws IOException {
        // multipart：读取全部请求体（不解析），校验鉴权后返回识别文本
        exchange.getRequestBody().readAllBytes();
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
            return;
        }
        byte[] resp = "{\"text\":\"识别结果\"}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, resp.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(resp);
        }
        exchange.close();
    }

    private static void handleModels(HttpExchange exchange) throws IOException {
        lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
            return;
        }
        byte[] resp = "{\"object\":\"list\",\"data\":[{\"id\":\"deepseek-chat\",\"object\":\"model\"}]}"
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, resp.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(resp);
        }
        exchange.close();
    }

    @BeforeEach
    void setUp() {
        WebClient webClient = WebClient.builder().build();
        ObjectMapper mapper = new ObjectMapper();
        UpstreamAuthenticatorResolver resolver = new UpstreamAuthenticatorResolver(List.of(new BearerAuthenticator()));
        adapter = new OpenAiCompatibleAdapter(webClient, mapper, resolver);

        ModelChannelEntity channel = new ModelChannelEntity();
        channel.setId(1L);
        channel.setUpstreamModel("deepseek-chat");
        channel.setProtocol("OPENAI_COMPAT");
        ProviderEntity provider = new ProviderEntity();
        provider.setId(1L);
        provider.setCode("DEEPSEEK");
        provider.setBaseUrl(baseUrl);
        provider.setAuthType("BEARER");
        runtime = new ChannelRuntime(channel, provider, ProviderType.DEEPSEEK, ChannelProtocol.OPENAI_COMPAT,
                baseUrl, "deepseek-chat", ProviderCredentials.of(Map.of("apiKey", "sk-test")), Map.of());
    }

    private UnifiedRequest chatRequest(boolean stream) {
        UnifiedRequest.ChatPayload chat = new UnifiedRequest.ChatPayload(
                List.of(UnifiedMessage.user("hi")), GenerationParams.defaults(), null, null);
        return UnifiedRequest.chat("deepseek-chat", stream, chat);
    }

    @Test
    void invokeUnaryParsesResponseAndSendsAuth() {
        StepVerifier.create(adapter.invokeUnary(chatRequest(false), runtime))
                .assertNext(response -> {
                    assertThat(response.capability()).isEqualTo(Capability.CHAT);
                    assertThat(response.id()).isEqualTo("chatcmpl-stub");
                    assertThat(response.model()).isEqualTo("deepseek-chat");
                    assertThat(response.choices()).hasSize(1);
                    assertThat(response.choices().get(0).finishReason()).isEqualTo("stop");
                    assertThat(response.choices().get(0).message().content()).isEqualTo("Hello from stub");
                    assertThat(response.usage().totalTokens()).isEqualTo(15);
                })
                .verifyComplete();

        assertThat(lastAuth.get()).isEqualTo("Bearer sk-test");
        assertThat(lastBody.get()).contains("\"model\":\"deepseek-chat\"").contains("\"stream\":false");
    }

    @Test
    void invokeEmbeddingParsesVectors() {
        UnifiedRequest request = UnifiedRequest.embedding("text-embedding-v3",
                new UnifiedRequest.EmbeddingPayload(List.of("你好", "世界"), "float", null));

        StepVerifier.create(adapter.invokeUnary(request, runtime))
                .assertNext(response -> {
                    assertThat(response.capability()).isEqualTo(Capability.EMBEDDING);
                    assertThat(response.model()).isEqualTo("text-embedding-v3");
                    assertThat(response.embeddings()).hasSize(2);
                    assertThat(response.embeddings().get(0).index()).isEqualTo(0);
                    assertThat(response.embeddings().get(0).embedding()).containsExactly(0.1f, 0.2f, 0.3f);
                    assertThat(response.usage().totalTokens()).isEqualTo(4);
                })
                .verifyComplete();

        assertThat(lastAuth.get()).isEqualTo("Bearer sk-test");
        assertThat(lastBody.get()).contains("你好");
    }

    @Test
    void invokeTtsReturnsAudioBytes() {
        UnifiedRequest request = UnifiedRequest.tts("cosyvoice-v1",
                new UnifiedRequest.TtsPayload("你好世界", "longxiaochun", "mp3", 1.0));

        StepVerifier.create(adapter.invokeUnary(request, runtime))
                .assertNext(response -> {
                    assertThat(response.capability()).isEqualTo(Capability.TTS);
                    assertThat(response.audio()).isNotNull();
                    assertThat(response.audio().audio()).isEqualTo(new byte[]{1, 2, 3, 4, 5});
                    assertThat(response.audio().contentType()).contains("audio/mpeg");
                })
                .verifyComplete();
    }

    @Test
    void invokeSttReturnsTranscribedText() {
        UnifiedRequest request = UnifiedRequest.stt("paraformer",
                new UnifiedRequest.SttPayload(new byte[]{9, 9, 9}, "audio.mp3", "zh", "json"));

        StepVerifier.create(adapter.invokeUnary(request, runtime))
                .assertNext(response -> {
                    assertThat(response.capability()).isEqualTo(Capability.STT);
                    assertThat(response.audio()).isNotNull();
                    assertThat(response.audio().text()).isEqualTo("识别结果");
                })
                .verifyComplete();
    }

    @Test
    void invokeStreamParsesChunksInOrder() {
        StepVerifier.create(adapter.invokeStream(chatRequest(true), runtime))
                .assertNext(chunk -> {
                    assertThat(chunk.role()).isEqualTo("assistant");
                    assertThat(chunk.deltaContent()).isNull();
                })
                .assertNext(chunk -> assertThat(chunk.deltaContent()).isEqualTo("Hello"))
                .assertNext(chunk -> assertThat(chunk.deltaContent()).isEqualTo(" world"))
                .assertNext(chunk -> {
                    assertThat(chunk.finishReason()).isEqualTo("stop");
                    assertThat(chunk.done()).isTrue();
                })
                .assertNext(chunk -> {
                    assertThat(chunk.usage()).isNotNull();
                    assertThat(chunk.usage().totalTokens()).isEqualTo(15);
                })
                .verifyComplete();

        assertThat(lastAuth.get()).isEqualTo("Bearer sk-test");
        assertThat(lastBody.get()).contains("\"stream\":true");
    }

    /* ================= 能力感知探测 ================= */

    @Test
    void probeChatSendsMinimalChatCompletion() {
        StepVerifier.create(adapter.probe(runtime, Capability.CHAT))
                .assertNext(result -> {
                    assertThat(result.isUp()).isTrue();
                    assertThat(result.latencyMs()).isNotNegative();
                })
                .verifyComplete();
        assertThat(lastAuth.get()).isEqualTo("Bearer sk-test");
        assertThat(lastBody.get()).contains("\"stream\":false").contains("\"max_tokens\":1");
    }

    @Test
    void probeEmbeddingHitsEmbeddingsEndpoint() {
        StepVerifier.create(adapter.probe(runtime, Capability.EMBEDDING))
                .assertNext(result -> assertThat(result.isUp()).isTrue())
                .verifyComplete();
        assertThat(lastAuth.get()).isEqualTo("Bearer sk-test");
        assertThat(lastBody.get()).contains("\"model\":\"deepseek-chat\"").contains("\"input\":\"ping\"");
    }

    @Test
    void probeImageTtsSttRerankUsesModelsLiveness() {
        // 回归：图片/语音/重排模型不再发 chat 请求导致必然失败，统一走 GET /models 存活探测
        for (Capability cap : List.of(Capability.IMAGE, Capability.TTS, Capability.STT, Capability.RERANK)) {
            StepVerifier.create(adapter.probe(runtime, cap))
                    .assertNext(result -> assertThat(result.isUp()).as("capability=%s", cap).isTrue())
                    .verifyComplete();
        }
        assertThat(lastAuth.get()).isEqualTo("Bearer sk-test");
    }

    @Test
    void probeNullCapabilityFallsBackToChat() {
        StepVerifier.create(adapter.probe(runtime, null))
                .assertNext(result -> assertThat(result.isUp()).isTrue())
                .verifyComplete();
        assertThat(lastBody.get()).contains("\"stream\":false");
    }
}
