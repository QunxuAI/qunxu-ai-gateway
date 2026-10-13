package tech.qunxu.module.gateway;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import tech.qunxu.module.gateway.health.ChannelHealth;
import tech.qunxu.module.gateway.health.HealthStore;
import tech.qunxu.module.gateway.persistence.entity.LogicalModelEntity;
import tech.qunxu.module.gateway.persistence.entity.ModelChannelEntity;
import tech.qunxu.module.gateway.persistence.entity.ProviderEntity;
import tech.qunxu.module.gateway.persistence.service.ConfigService;
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

import java.time.Duration;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.when;

/**
 * M9 硬化：以 <b>WireMock</b> 桩替代真实国内供应商，覆盖「上游契约」与「下游故障语义」两类
 * 端到端行为。相比 {@code GatewayEndToEndTest} 的 JDK HttpServer 桩，WireMock 额外提供
 * <b>请求侧断言</b>（{@code verify(postRequestedFor(...))}）——这正是协议网关最该被钉住的东西：
 * 网关究竟向上游发了什么。
 *
 * <p>覆盖矩阵：</p>
 * <ul>
 *   <li>契约：逻辑模型名 → 上游模型名映射、{@code stream}/{@code stream_options} 开关、Bearer 透传、
 *       Anthropic 顶层 {@code system} → OpenAI {@code messages[0].role=system}、嵌入双向翻译</li>
 *   <li>故障：500 → 502 {@code provider_error}；401 → 502 {@code provider_auth_expired} 并<b>立即剔除</b>通道；
 *       读超时 → 504 {@code provider_timeout}；主通道 500 时<b>故障转移</b>到备通道仍返回 200</li>
 *   <li>自愈闭环：通道被剔除后，同模型的后续请求因无健康通道直接 503 {@code service_unavailable}，
 *       不再徒劳打下游（避免雪崩）</li>
 * </ul>
 *
 * <p>为让剔除在一次失败内即触发，本类把 {@code failure-threshold} 压到 1、窗口压到 1，并关闭限流/配额；
 * 主动探测间隔调到 1 小时，避免后台探测干扰断言。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "gateway.health.enabled=true",
        "gateway.health.store=in-memory",
        "gateway.health.failure-threshold=1",
        "gateway.health.error-rate-threshold=1.0",
        "gateway.health.window-size=1",
        "gateway.health.eject-cooldown-ms=600000",
        "gateway.health.recovery-success-threshold=1",
        "gateway.health.probe-interval-ms=3600000",
        "gateway.downstream.read-timeout-ms=2000",
        "gateway.load-balance.default-strategy=PRIORITY",
        "gateway.rate-limit.enabled=false",
        "gateway.quota.enabled=false"
})
@ActiveProfiles("test")
class UpstreamProviderStubTest {

    private static final Duration WAIT = Duration.ofSeconds(15);
    private static final Duration POLL = Duration.ofMillis(50);

    /** 慢桩的响应延迟，必须显著大于 {@code gateway.downstream.read-timeout-ms} 才能触发超时。 */
    private static final int SLOW_DELAY_MS = 5000;

    private static final String CHAT_JSON = """
            {"id":"chatcmpl-wm","object":"chat.completion","created":1700000000,"model":"deepseek-chat",\
            "choices":[{"index":0,"message":{"role":"assistant","content":"pong from wiremock"},"finish_reason":"stop"}],\
            "usage":{"prompt_tokens":7,"completion_tokens":3,"total_tokens":10}}""";

    /** 文本块中的空行即 SSE 的事件分隔符，末尾 {@code [DONE]} 哨兵必须被适配器吞掉而非透传为内容。 */
    private static final String CHAT_SSE = """
            data: {"id":"s1","object":"chat.completion.chunk","created":1,"model":"deepseek-chat","choices":[{"index":0,"delta":{"role":"assistant"},"finish_reason":null}]}

            data: {"id":"s1","object":"chat.completion.chunk","created":1,"model":"deepseek-chat","choices":[{"index":0,"delta":{"content":"streamed"},"finish_reason":null}]}

            data: {"id":"s1","object":"chat.completion.chunk","created":1,"model":"deepseek-chat","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

            data: {"id":"s1","object":"chat.completion.chunk","created":1,"model":"deepseek-chat","choices":[],"usage":{"prompt_tokens":7,"completion_tokens":3,"total_tokens":10}}

            data: [DONE]

            """;

    private static final String EMBEDDING_JSON = """
            {"object":"list","id":"emb-wm","model":"text-embedding-v3",\
            "data":[{"object":"embedding","index":0,"embedding":[0.11,0.22,0.33]},\
            {"object":"embedding","index":1,"embedding":[0.44,0.55,0.66]}],\
            "usage":{"prompt_tokens":4,"completion_tokens":0,"total_tokens":4}}""";

    private static final String UPSTREAM_ERROR = """
            {"error":{"message":"internal upstream boom","type":"server_error","code":500}}""";

    private static WireMockServer server;
    private static String base;

    @LocalServerPort
    private int port;

    @MockitoBean
    private ConfigService configService;

    @Autowired
    private HealthStore healthStore;

    private WebTestClient client;

    @BeforeAll
    static void startStub() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        base = "http://127.0.0.1:" + server.port();

        // 流式与非流式共用同一上游路径，靠请求体的 stream 开关分流（与真实 OpenAI 兼容供应商一致）
        server.stubFor(post(urlEqualTo("/healthy/chat/completions"))
                .atPriority(1)
                .withRequestBody(containing("\"stream\":true"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "text/event-stream")
                        .withChunkedDribbleDelay(5, 100)
                        .withBody(CHAT_SSE)));
        server.stubFor(post(urlEqualTo("/healthy/chat/completions"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(CHAT_JSON)));
        server.stubFor(post(urlEqualTo("/healthy/embeddings"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(EMBEDDING_JSON)));

        server.stubFor(post(urlEqualTo("/broken/chat/completions"))
                .willReturn(aResponse().withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody(UPSTREAM_ERROR)));
        // 故障转移专用的「坏主通道」，与 /broken 分开，避免其它用例把它剔除后影响本用例
        server.stubFor(post(urlEqualTo("/broken-alt/chat/completions"))
                .willReturn(aResponse().withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody(UPSTREAM_ERROR)));
        server.stubFor(post(urlEqualTo("/expired/chat/completions"))
                .willReturn(aResponse().withStatus(401)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":{\"message\":\"Invalid Authentication Token\",\"type\":\"authentication_error\"}}")));
        server.stubFor(post(urlEqualTo("/slow/chat/completions"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withFixedDelay(SLOW_DELAY_MS)
                        .withBody(CHAT_JSON)));
    }

    @AfterAll
    static void stopStub() {
        if (server != null) {
            server.stop();
        }
    }

    @BeforeEach
    void setUp() {
        // 请求计数按用例隔离，否则 verify(times) 会被前序用例污染
        server.resetRequests();
        client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(30))
                .build();

        provider(100L, "DEEPSEEK", base + "/healthy", "sk-healthy");
        provider(101L, "CUSTOM", base + "/broken", "sk-broken");
        provider(102L, "CUSTOM", base + "/broken-alt", "sk-broken-alt");
        provider(103L, "CUSTOM", base + "/expired", "sk-expired");
        provider(104L, "CUSTOM", base + "/slow", "sk-slow");

        model(1L, "chat-ok", "CHAT", channel(10L, 1L, 100L, "deepseek-chat", 1, 100));
        model(2L, "chat-broken", "CHAT", channel(11L, 2L, 101L, "deepseek-chat", 1, 100));
        // PRIORITY 策略下 priority 小者先选：坏通道(12)为主，健康通道(13)为备
        model(3L, "chat-failover", "CHAT",
                channel(12L, 3L, 102L, "deepseek-chat", 1, 100),
                channel(13L, 3L, 100L, "deepseek-chat", 10, 100));
        model(4L, "chat-expired", "CHAT", channel(14L, 4L, 103L, "deepseek-chat", 1, 100));
        model(5L, "chat-slow", "CHAT", channel(15L, 5L, 104L, "deepseek-chat", 1, 100));
        model(6L, "embedding-ok", "EMBEDDING", channel(16L, 6L, 100L, "text-embedding-v3", 1, 100));
    }

    // ------------------------------------------------------------------ 上游请求契约

    @Test
    void unaryRequestMatchesOpenAiUpstreamContract() {
        chat("chat-ok")
                .expectStatus().isOk()
                .expectBody(String.class).value(body -> {
                    assertThat(body).contains("\"object\":\"chat.completion\"");
                    assertThat(body).contains("pong from wiremock");
                    assertThat(body).contains("\"total_tokens\":10");
                });

        server.verify(1, postRequestedFor(urlEqualTo("/healthy/chat/completions"))
                // 凭据以 Bearer 透传，且必须是解密后的明文密钥
                .withHeader("Authorization", equalTo("Bearer sk-healthy"))
                .withHeader("Content-Type", containing("application/json"))
                // 逻辑模型名 chat-ok 必须被替换为通道配置的上游模型名
                .withRequestBody(matchingJsonPath("$.model", equalTo("deepseek-chat")))
                .withRequestBody(matchingJsonPath("$.messages[0].role", equalTo("user")))
                .withRequestBody(matchingJsonPath("$.messages[0].content", equalTo("hi")))
                // 非流式显式声明 stream=false，避免上游按默认值走 SSE 导致解析错位
                .withRequestBody(matchingJsonPath("$[?(@.stream == false)]")));
    }

    @Test
    void streamingRequestAsksUpstreamForUsageInFinalChunk() {
        List<String> frames = chat("chat-ok", true)
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(String.class)
                .getResponseBody()
                .collectList()
                .block(WAIT);

        String joined = String.join("", frames == null ? List.of() : frames);
        assertThat(joined)
                .contains("chat.completion.chunk")
                .contains("streamed")
                // [DONE] 是本网关按 OpenAI 协议补写的结束哨兵，且不得出现上游 usage 帧之外的裸 JSON
                .contains("[DONE]");

        server.verify(1, postRequestedFor(urlEqualTo("/healthy/chat/completions"))
                .withRequestBody(matchingJsonPath("$[?(@.stream == true)]"))
                // 审计与配额依赖末帧 usage，必须显式向上游索要
                .withRequestBody(matchingJsonPath("$.stream_options.include_usage", equalTo("true"))));
    }

    @Test
    void anthropicIngressLandsSystemPromptAsFirstUpstreamMessage() {
        client.post().uri("/v1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model":"chat-ok","max_tokens":64,"system":"你是助手",\
                        "messages":[{"role":"user","content":"hi"}]}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).value(body -> {
                    assertThat(body).contains("\"type\":\"message\"");
                    assertThat(body).contains("pong from wiremock");
                });

        // Anthropic 的顶层 system 在 OpenAI 兼容上游里必须落到 messages[0]，否则系统提示词被静默丢弃
        server.verify(1, postRequestedFor(urlEqualTo("/healthy/chat/completions"))
                .withRequestBody(matchingJsonPath("$.messages[0].role", equalTo("system")))
                .withRequestBody(matchingJsonPath("$.messages[0].content", equalTo("你是助手")))
                .withRequestBody(matchingJsonPath("$.messages[1].role", equalTo("user"))));
    }

    @Test
    void embeddingsAreTranslatedInBothDirections() {
        client.post().uri("/v1/embeddings")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"model\":\"embedding-ok\",\"input\":[\"甲\",\"乙\"]}")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).value(body -> {
                    assertThat(body).contains("\"object\":\"list\"");
                    assertThat(body).contains("\"model\":\"text-embedding-v3\"");
                    assertThat(body).contains("0.11").contains("0.66");
                    assertThat(body).contains("\"total_tokens\":4");
                });

        server.verify(1, postRequestedFor(urlEqualTo("/healthy/embeddings"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("text-embedding-v3")))
                .withRequestBody(matchingJsonPath("$.input[0]", equalTo("甲")))
                .withRequestBody(matchingJsonPath("$.input[1]", equalTo("乙"))));
    }

    // ------------------------------------------------------------------ 下游故障语义

    @Test
    void brokenChannelIsEjectedAfterFailureAndLaterCallsShortCircuitWith503() {
        // 首次：下游 500，无备选通道 → 502 provider_error，并携带上游错误详情便于排障
        chat("chat-broken")
                .expectStatus().isEqualTo(502)
                .expectBody(String.class).value(body -> {
                    assertThat(body).contains("provider_error");
                    assertThat(body).contains("internal upstream boom");
                });
        server.verify(1, postRequestedFor(urlEqualTo("/broken/chat/completions")));

        // 健康回写是 fire-and-forget，剔除需异步等待
        await().atMost(WAIT).pollInterval(POLL).untilAsserted(() -> {
            ChannelHealth health = healthStore.get(11L).block(WAIT);
            assertThat(health).isNotNull();
            assertThat(health.isEjected()).isTrue();
            assertThat(health.isRoutable()).isFalse();
        });

        // 剔除生效后：路由阶段即判定无健康通道，503 且不再打下游（防雪崩）
        chat("chat-broken")
                .expectStatus().isEqualTo(503)
                .expectBody(String.class).value(body -> assertThat(body).contains("service_unavailable"));
        server.verify(1, postRequestedFor(urlEqualTo("/broken/chat/completions")));
    }

    @Test
    void unauthorizedUpstreamIsClassifiedAsAuthExpiredAndEjectsImmediately() {
        chat("chat-expired")
                .expectStatus().isEqualTo(502)
                .expectBody(String.class).value(body -> {
                    // 401/403 必须与 5xx 区分开：这是凭证问题，激进重试只会加速被上游封禁
                    assertThat(body).contains("provider_auth_expired");
                    assertThat(body).contains("Invalid Authentication Token");
                });

        await().atMost(WAIT).pollInterval(POLL).untilAsserted(() -> {
            ChannelHealth health = healthStore.get(14L).block(WAIT);
            assertThat(health).isNotNull();
            assertThat(health.isEjected()).isTrue();
        });
    }

    @Test
    void readTimeoutSurfacesAsProviderTimeout() {
        long start = System.nanoTime();

        chat("chat-slow")
                .expectStatus().isEqualTo(504)
                .expectBody(String.class).value(body -> assertThat(body).contains("provider_timeout"));

        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        // 必须在读超时附近返回，而不是等满慢桩的 5s——否则线程与连接会被慢上游长期占用
        assertThat(elapsedMs).isLessThan(SLOW_DELAY_MS);
    }

    @Test
    void primaryFailureFailsOverToHealthyBackupChannel() {
        chat("chat-failover")
                .expectStatus().isOk()
                .expectBody(String.class).value(body -> assertThat(body).contains("pong from wiremock"));

        // 主通道确实被打过且失败，备通道接管——故障转移对客户端透明
        server.verify(1, postRequestedFor(urlEqualTo("/broken-alt/chat/completions")));
        server.verify(1, postRequestedFor(urlEqualTo("/healthy/chat/completions")));
    }

    // ------------------------------------------------------------------ 桩与装配辅助

    private WebTestClient.ResponseSpec chat(String model) {
        return chat(model, false);
    }

    private WebTestClient.ResponseSpec chat(String model, boolean stream) {
        return client.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"model\":\"" + model + "\",\"stream\":" + stream
                        + ",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")
                .exchange();
    }

    private void provider(long id, String code, String baseUrl, String credentials) {
        ProviderEntity entity = new ProviderEntity();
        entity.setId(id);
        entity.setCode(code);
        entity.setName(code);
        entity.setBaseUrl(baseUrl);
        entity.setAuthType("BEARER");
        entity.setStatus(1);
        entity.setPriority(100);
        entity.setCredentials(credentials);
        when(configService.findProviderById(id)).thenReturn(Mono.just(entity));
    }

    private ModelChannelEntity channel(long id, long modelId, long providerId,
                                       String upstreamModel, int priority, int weight) {
        ModelChannelEntity entity = new ModelChannelEntity();
        entity.setId(id);
        entity.setLogicalModelId(modelId);
        entity.setProviderId(providerId);
        entity.setUpstreamModel(upstreamModel);
        entity.setProtocol("OPENAI_COMPAT");
        entity.setWeight(weight);
        entity.setPriority(priority);
        entity.setEnabled(1);
        return entity;
    }

    private void model(long id, String name, String capability, ModelChannelEntity... channels) {
        LogicalModelEntity entity = new LogicalModelEntity();
        entity.setId(id);
        entity.setName(name);
        entity.setCapability(capability);
        entity.setStatus(1);
        when(configService.findLogicalModelByName(name)).thenReturn(Mono.just(entity));
        when(configService.findEnabledChannels(id)).thenReturn(Mono.just(List.of(channels)));
    }
}
