package tech.qunxu.module.gateway;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import tech.qunxu.module.gateway.health.ChannelHealth;
import tech.qunxu.module.gateway.health.HealthStore;
import tech.qunxu.module.gateway.health.RedisHealthStore;
import tech.qunxu.module.gateway.persistence.service.ConfigService;
import tech.qunxu.module.gateway.security.RateLimiter;
import tech.qunxu.module.gateway.security.RedisRateLimiter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tech.qunxu.framework.security.core.api.OAuth2TokenApi;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * M9 硬化：<b>全真实依赖</b>的端到端集成测试——真 MySQL 8（由仓库级 {@code sql/schema.sql + sql/data.sql}
 * 建表 + 配置与审计落库）、真 Redis 7（健康 hash / Lua 令牌桶 / 配置失效 pub-sub）、
 * 仅下游供应商由 WireMock 桩替代。
 *
 * <p>与 {@code GatewayEndToEndTest}、{@code UpstreamProviderStubTest} 的关键差异在于<b>不启用 test profile</b>：
 * 那些测试用 H2 + 内存健康/限流存储换取快速反馈，因此无法回答「schema.sql 的 MySQL 专用 DDL（如 MEDIUMTEXT）
 * 能否真的跑通」「审计批量写入能否真的落进 InnoDB」「Lua 令牌桶能否真的在多实例共享的 Redis 上原子扣减」
 * 这类只在真实存储上才成立的问题。本类正是为此存在，并顺带覆盖配置热失效与 Actuator 就绪探针。</p>
 *
 * <p>连接信息由 {@code @ServiceConnection} 从容器推导，<b>优先级高于</b> {@code application.yml} 中显式配置的
 * {@code spring.datasource.url} / {@code spring.data.redis.host}，因此无需为测试另造一套配置；
 * 建表通过 {@code spring.sql.init} 执行测试 classpath 中的 {@code sql/schema.sql} 与 {@code sql/data.sql}
 * （即仓库 {@code sql/} 下与生产一致的脚本，由 {@code pom.xml} 的 testResources 引入）。</p>
 *
 * <p>运行前置条件：本机可用 Docker。无 Docker 时由 {@code @Testcontainers(disabledWithoutDocker = true)}
 * <b>跳过而非失败</b>；本类以 {@code IT} 结尾，交由 failsafe 执行，不拖慢 {@code mvn test} 的快速反馈。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // ---- 用仓库级 SQL 建表：schema.sql（结构）+ data.sql（初始化数据）----
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:sql/schema.sql",
        "spring.sql.init.data-locations=classpath:sql/data.sql",
        // ---- 让真实存储上的异步写入在测试时间尺度内可见 ----
        "gateway.audit.enabled=true",
        "gateway.audit.batch-size=1",
        "gateway.audit.flush-interval-ms=100",
        // ---- 健康自愈：一次失败即剔除，冷却足够长以免中途自动恢复干扰断言 ----
        "gateway.health.enabled=true",
        "gateway.health.store=redis",
        "gateway.health.failure-threshold=1",
        "gateway.health.error-rate-threshold=1.0",
        "gateway.health.window-size=1",
        "gateway.health.eject-cooldown-ms=600000",
        "gateway.health.recovery-success-threshold=1",
        // 主动探测调到 1 小时：本类只验证「调用驱动」的被动健康，后台探测会引入不确定性
        "gateway.health.probe-interval-ms=3600000",
        // ---- 限流：默认额度极大以免误伤，仅 throttled 这个调用人被压到 2 次/分钟 ----
        "gateway.rate-limit.enabled=true",
        "gateway.rate-limit.store=redis",
        "gateway.rate-limit.default-permits-per-minute=100000",
        "gateway.rate-limit.per-model-enabled=false",
        "gateway.rate-limit.caller-overrides.throttled=2",
        "gateway.quota.enabled=false",
        // ---- 其余保持生产默认（围栏/内容审计开启、负载均衡用 PRIORITY 便于确定选路） ----
        "gateway.load-balance.default-strategy=PRIORITY",
        "gateway.downstream.read-timeout-ms=5000",
        "gateway.security.enabled=false",
        "gateway.security.static-tokens=it-token-alice:alice,it-token-throttled:throttled",
        "gateway.crypto.secret=integration-test-crypto-secret",
        // ---- 测试上下文不含 qunxu-server：数据面 /v1/** 与网关管理面由安全链保护，
        //      与 application-test.yml 一致地放行，避免 401；OAuth2TokenApi 由下方 MockitoBean 提供 ----
        "qunxu.security.permit-all-urls=/v1/**,/anthropic/**,/v1beta/**,/admin-api/gateway/**,/actuator/health/readiness",
        "logging.level.tech.qunxu.module.gateway=INFO"})
@Testcontainers(disabledWithoutDocker = true)
class GatewayFullStackIT {

    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final Duration POLL = Duration.ofMillis(100);

    // ---- 种子数据主键：显式指定以便跨用例引用与幂等重播 ----
    private static final long PROVIDER_OK = 900L;
    private static final long PROVIDER_BROKEN = 901L;
    private static final long CHANNEL_CHAT = 910L;
    private static final long CHANNEL_STREAM = 911L;
    private static final long CHANNEL_BROKEN = 912L;
    private static final long CHANNEL_DORMANT = 913L;
    private static final long MODEL_CHAT = 920L;
    private static final long MODEL_STREAM = 921L;
    private static final long MODEL_BROKEN = 922L;
    private static final long MODEL_DORMANT = 923L;

    /** 健康上游路径前缀；{@code /broken} 为故障用例专用。 */
    private static final String OK_PREFIX = "/it-upstream";

    /** 与 {@code gateway.security.static-tokens} 对应：alice 用于常规调用，throttled 专供限流用例。 */
    private static final String ALICE_TOKEN = "it-token-alice";
    private static final String THROTTLED_TOKEN = "it-token-throttled";

    private static final String CHAT_JSON = """
            {"id":"chatcmpl-it","object":"chat.completion","created":1700000000,"model":"deepseek-chat",\
            "choices":[{"index":0,"message":{"role":"assistant","content":"pong from full stack"},\
            "finish_reason":"stop"}],"usage":{"prompt_tokens":7,"completion_tokens":3,"total_tokens":10}}""";

    /** 分 5 帧、总时长 500ms 下发，使「首字延迟」必然可测（>0ms）而非恒等于 0。 */
    private static final String CHAT_SSE = """
            data: {"id":"s1","object":"chat.completion.chunk","created":1,"model":"deepseek-chat","choices":[{"index":0,"delta":{"role":"assistant"},"finish_reason":null}]}

            data: {"id":"s1","object":"chat.completion.chunk","created":1,"model":"deepseek-chat","choices":[{"index":0,"delta":{"content":"streamed"},"finish_reason":null}]}

            data: {"id":"s1","object":"chat.completion.chunk","created":1,"model":"deepseek-chat","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

            data: {"id":"s1","object":"chat.completion.chunk","created":1,"model":"deepseek-chat","choices":[],"usage":{"prompt_tokens":7,"completion_tokens":3,"total_tokens":10}}

            data: [DONE]

            """;

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0")
            .withDatabaseName("qunxu_gateway")
            .withUsername("root")
            .withPassword("root");

    @Container
    @ServiceConnection
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    /**
     * 数据源走 dynamic-datasource（{@code spring.datasource.dynamic.*}），而
     * {@code @ServiceConnection} 只映射平铺的 {@code spring.datasource.url}；
     * 故将容器连接显式映射为动态数据源的 master，否则 DynamicRoutingDataSource
     * 无法找到 primary。Redis 仍由 {@code @ServiceConnection} 提供平铺配置即可。
     */
    @DynamicPropertySource
    static void registerDynamicDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.dynamic.primary", () -> "master");
        registry.add("spring.datasource.dynamic.datasource.master.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.dynamic.datasource.master.username", MYSQL::getUsername);
        registry.add("spring.datasource.dynamic.datasource.master.password", MYSQL::getPassword);
        registry.add("spring.datasource.dynamic.datasource.master.driver-class-name",
                () -> "com.mysql.cj.jdbc.Driver");
    }

    private static WireMockServer upstream;
    private static String upstreamBase;

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * 安全链的 Token 过滤器依赖该 API 还原登录用户；本测试上下文不含 qunxu-server，
     * 用 MockitoBean 兜底（返回 null 即「令牌无效」，数据面仍由 {@code gateway.security.static-tokens}
     * 独立鉴权，管理面已按 permit-all 放行）。
     */
    @MockitoBean
    private OAuth2TokenApi oauth2TokenApi;

    @Autowired
    private ReactiveStringRedisTemplate redis;

    @Autowired
    private HealthStore healthStore;

    @Autowired
    private RateLimiter rateLimiter;

    @Autowired
    private ConfigService configService;

    @Autowired
    private tech.qunxu.module.gateway.ratelimit.RateLimitRuleService rateLimitRuleService;

    private WebTestClient client;

    @BeforeAll
    static void startUpstreamStub() {
        upstream = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        upstream.start();
        upstreamBase = "http://127.0.0.1:" + upstream.port();

        // 流式与非流式共用同一路径，按请求体 stream 开关分流（与真实 OpenAI 兼容供应商一致）
        upstream.stubFor(post(urlEqualTo(OK_PREFIX + "/chat/completions"))
                .atPriority(1)
                .withRequestBody(containing("\"stream\":true"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "text/event-stream")
                        .withChunkedDribbleDelay(5, 500)
                        .withBody(CHAT_SSE)));
        upstream.stubFor(post(urlEqualTo(OK_PREFIX + "/chat/completions"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(CHAT_JSON)));
        upstream.stubFor(post(urlEqualTo("/broken/chat/completions"))
                .willReturn(aResponse().withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":{\"message\":\"upstream exploded\",\"type\":\"server_error\"}}")));
    }

    @AfterAll
    static void stopUpstreamStub() {
        if (upstream != null) {
            upstream.stop();
        }
    }

    @BeforeEach
    void resetState() {
        client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(60))
                .build();
        upstream.resetRequests();

        // 审计与健康事件是追加型表：清空以保证「按 trace_id 恰好一行」这类断言不被前序用例污染
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM health_event");
        flushRedisKeys();
        seedConfig();
        // 种子数据每次重播，必须清掉 ConfigService 的 Caffeine 快照，否则读到上一轮的实体
        configService.invalidateAll();
    }

    // ------------------------------------------------------------------ schema.sql / data.sql / 真实 MySQL

    @Test
    void sqlScriptsCreateFullSchemaOnRealMySql() {
        List<String> tables = jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = DATABASE() ORDER BY table_name
                """, String.class);

        assertThat(tables).contains(
                "provider", "logical_model", "model_channel", "audit_log",
                "guardrail_policy", "moderation_policy", "sensitive_word", "health_event",
                "rate_limit_rule", "gateway_api_key");

        // MEDIUMTEXT 是 MySQL 专有类型：H2 兼容模式下不会得到这个 data_type，
        // 因此这一断言等价于「schema.sql 确实跑在真 MySQL 上，而不是被降级执行」
        String bodyType = jdbc.queryForObject("""
                SELECT data_type FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'audit_log' AND column_name = 'request_body'
                """, String.class);
        assertThat(bodyType).isEqualTo("mediumtext");

        // data.sql 的种子数据（安全围栏/内容审计默认策略 + 限流规则）已生效
        Integer guardrails = jdbc.queryForObject(
                "SELECT COUNT(*) FROM guardrail_policy WHERE scope = 'GLOBAL' AND deleted = 0", Integer.class);
        assertThat(guardrails).isGreaterThanOrEqualTo(1);
        Integer rateRules = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rate_limit_rule WHERE rule_type = 'CALLER_DEFAULT' AND deleted = 0", Integer.class);
        assertThat(rateRules).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 端到端 + 审计落库

    @Test
    void chatCompletionRunsFullPipelineAndPersistsAuditToMySql() {
        String traceId = newTraceId();

        chat(traceId, "it-chat", ALICE_TOKEN, "hi")
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Trace-Id", traceId)
                .expectBody(String.class).value(body -> {
                    assertThat(body).contains("\"object\":\"chat.completion\"");
                    assertThat(body).contains("pong from full stack");
                    assertThat(body).contains("\"total_tokens\":10");
                });

        // 配置确实来自真库：逻辑名 it-chat 被替换为通道配置的上游模型名，凭据以明文 Bearer 透传
        upstream.verify(1, postRequestedFor(urlEqualTo(OK_PREFIX + "/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer sk-it-ok"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("deepseek-chat"))));

        Map<String, Object> row = awaitAuditRow(traceId);
        assertThat(row.get("status")).isEqualTo("SUCCESS");
        assertThat(row.get("caller_id")).isEqualTo("alice");
        assertThat(row.get("tenant")).isEqualTo("default");
        assertThat(row.get("protocol")).isEqualTo("OPENAI");
        assertThat(row.get("endpoint")).isEqualTo("chat.completions");
        assertThat(row.get("logical_model")).isEqualTo("it-chat");
        assertThat(intOf(row, "channel_id")).isEqualTo((int) CHANNEL_CHAT);
        assertThat(intOf(row, "provider_id")).isEqualTo((int) PROVIDER_OK);
        assertThat(row.get("provider_code")).isEqualTo("DEEPSEEK");
        assertThat(row.get("upstream_model")).isEqualTo("deepseek-chat");
        assertThat(intOf(row, "prompt_tokens")).isEqualTo(7);
        assertThat(intOf(row, "completion_tokens")).isEqualTo(3);
        assertThat(intOf(row, "total_tokens")).isEqualTo(10);
        assertThat(longOf(row, "latency_ms")).isGreaterThan(0L);
        assertThat(row.get("error_code")).isNull();
        assertThat((String) row.get("request_body")).contains("hi");
        assertThat((String) row.get("response_body")).contains("pong from full stack");

        // 审计读路径同样跑在真 MySQL 上（MyBatis-Plus 分页插件 + LIMIT 语法）
        client.get().uri("/admin-api/gateway/audit?traceId=" + traceId)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.total").isEqualTo(1)
                .jsonPath("$.items[0].logicalModel").isEqualTo("it-chat")
                .jsonPath("$.items[0].status").isEqualTo("SUCCESS");

        // 成功调用把健康状态写进真 Redis，且用的是 Redis 实现而非内存兜底
        assertThat(healthStore).isInstanceOf(RedisHealthStore.class);
        await().atMost(WAIT).pollInterval(POLL).untilAsserted(() -> {
            assertThat(redis.hasKey("gw:channel:health:" + CHANNEL_CHAT).block(WAIT)).isTrue();
            ChannelHealth health = healthStore.get(CHANNEL_CHAT).block(WAIT);
            assertThat(health).isNotNull();
            assertThat(health.consecutiveSuccesses()).isGreaterThanOrEqualTo(1);
            assertThat(health.avgLatencyMs()).isGreaterThan(0d);
            assertThat(health.isRoutable()).isTrue();
        });
    }

    @Test
    void streamingChatAggregatesUsageAndFirstTokenLatency() {
        String traceId = newTraceId();

        List<String> frames = chat(traceId, "it-stream", ALICE_TOKEN, "hi", true)
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(String.class)
                .getResponseBody()
                .collectList()
                .block(WAIT);

        String joined = String.join("", frames == null ? List.of() : frames);
        assertThat(joined).contains("chat.completion.chunk").contains("streamed").contains("[DONE]");

        Map<String, Object> row = awaitAuditRow(traceId);
        assertThat(row.get("status")).isEqualTo("SUCCESS");
        // 流式的 usage 来自上游末帧；若没向上游索要 stream_options.include_usage，这里会是 0
        assertThat(intOf(row, "total_tokens")).isEqualTo(10);
        assertThat(longOf(row, "first_token_ms")).isGreaterThan(0L);
    }

    // ------------------------------------------------------------------ 限流：真 Redis Lua 令牌桶

    @Test
    void redisTokenBucketThrottlesCallerAndStoresBucketInRedis() {
        assertThat(rateLimiter).isInstanceOf(RedisRateLimiter.class);

        chat(newTraceId(), "it-chat", THROTTLED_TOKEN, "hi").expectStatus().isOk();
        chat(newTraceId(), "it-chat", THROTTLED_TOKEN, "hi").expectStatus().isOk();

        String deniedTrace = newTraceId();
        chat(deniedTrace, "it-chat", THROTTLED_TOKEN, "hi")
                .expectStatus().isEqualTo(429)
                .expectHeader().exists("Retry-After")
                .expectHeader().valueEquals("X-RateLimit-Limit", "2")
                .expectBody(String.class).value(body -> assertThat(body).contains("rate_limit_exceeded"));

        // 桶状态确实落在共享 Redis 上（多实例部署时限流一致性依赖它）
        Map<Object, Object> bucket = redis.opsForHash()
                .entries("gw:ratelimit:caller:throttled")
                .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                .block(WAIT);
        assertThat(bucket).isNotNull().containsKeys("tokens", "ts");
        assertThat(redis.getExpire("gw:ratelimit:caller:throttled").block(WAIT)).isPositive();

        // 被限流的请求同样入审计，便于事后追溯谁在何时被拒
        Map<String, Object> row = awaitAuditRow(deniedTrace);
        assertThat(row.get("status")).isEqualTo("FAILURE");
        assertThat(row.get("error_code")).isEqualTo("RATE_LIMITED");
        assertThat(row.get("caller_id")).isEqualTo("throttled");
    }

    // ------------------------------------------------------------------ 健康自愈：Redis 剔除 + MySQL 事件

    @Test
    void upstreamFailureEjectsChannelInRedisAndWritesHealthEventToMySql() {
        String firstTrace = newTraceId();
        chat(firstTrace, "it-broken", ALICE_TOKEN, "hi")
                .expectStatus().isEqualTo(502)
                .expectBody(String.class).value(body -> {
                    assertThat(body).contains("provider_error");
                    assertThat(body).contains("upstream exploded");
                });

        await().atMost(WAIT).pollInterval(POLL).untilAsserted(() -> {
            ChannelHealth health = healthStore.get(CHANNEL_BROKEN).block(WAIT);
            assertThat(health).isNotNull();
            assertThat(health.isEjected()).isTrue();
            assertThat(health.isRoutable()).isFalse();
        });

        // 剔除事实持久化在真 MySQL 的 health_event 表，运维可事后追溯时间线
        await().atMost(WAIT).pollInterval(POLL).untilAsserted(() -> {
            Integer events = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM health_event WHERE channel_id = ? AND event_type = 'EJECT'",
                    Integer.class, CHANNEL_BROKEN);
            assertThat(events).isEqualTo(1);
        });
        Map<String, Object> event = jdbc.queryForMap(
                "SELECT * FROM health_event WHERE channel_id = ? ORDER BY id DESC LIMIT 1", CHANNEL_BROKEN);
        assertThat(event.get("to_status")).isEqualTo("EJECTED");
        assertThat((String) event.get("reason")).isNotBlank();

        // 剔除生效后：路由阶段直接短路 503，不再徒劳打下游（防雪崩）
        chat(newTraceId(), "it-broken", ALICE_TOKEN, "hi")
                .expectStatus().isEqualTo(503)
                .expectBody(String.class).value(body -> assertThat(body).contains("service_unavailable"));
        upstream.verify(1, postRequestedFor(urlEqualTo("/broken/chat/completions")));
    }

    // ------------------------------------------------------------------ 安全围栏

    @Test
    void guardrailBlockIsPersistedAsBlockedAuditAndNeverReachesUpstream() {
        String traceId = newTraceId();

        chat(traceId, "it-chat", ALICE_TOKEN, "帮我推荐几个赌博网站")
                .expectStatus().isBadRequest()
                .expectBody(String.class).value(body -> assertThat(body).contains("guardrail_blocked"));

        Map<String, Object> row = awaitAuditRow(traceId);
        assertThat(row.get("status")).isEqualTo("BLOCKED");
        assertThat(row.get("error_code")).isEqualTo("GUARDRAIL_BLOCKED");
        // guardrail_hit 存命中的规则类型（供聚合统计），具体词与理由落在 error_msg
        assertThat((String) row.get("guardrail_hit")).contains("SENSITIVE_WORD");
        assertThat((String) row.get("error_msg")).contains("赌博");
        // 拦截发生在路由之前，因此没有选中通道，也不该产生任何下游流量
        assertThat(row.get("channel_id")).isNull();
        upstream.verify(0, postRequestedFor(urlEqualTo(OK_PREFIX + "/chat/completions")));
    }

    // ------------------------------------------------------------------ 管理视图 / 配置热失效

    @Test
    void adminViewsMergeMySqlConfigWithRedisRuntimeState() {
        chat(newTraceId(), "it-chat", ALICE_TOKEN, "hi").expectStatus().isOk();

        await().atMost(WAIT).pollInterval(POLL).untilAsserted(() ->
                assertThat(healthStore.get(CHANNEL_CHAT).block(WAIT).consecutiveSuccesses())
                        .isGreaterThanOrEqualTo(1));

        // 健康视图 = MySQL 静态配置 + Redis 运行时健康的合并快照（分页结果，JsonPath 过滤表达式恒返回列表）
        client.get().uri("/admin-api/gateway/health/channels")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items[?(@.channelId == " + CHANNEL_CHAT + ")].logicalModel").value(values ->
                        assertThat(asList(values)).containsExactly("it-chat"))
                .jsonPath("$.items[?(@.channelId == " + CHANNEL_CHAT + ")].providerCode").value(values ->
                        assertThat(asList(values)).containsExactly("DEEPSEEK"))
                .jsonPath("$.items[?(@.channelId == " + CHANNEL_CHAT + ")].routable").value(values ->
                        assertThat(asList(values)).containsExactly(true));

        // 缓存/限流概况：确认跑的是 Redis 实现，且配置失效订阅已在线（多实例一致性的前提）
        client.get().uri("/admin-api/gateway/cache/status")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.healthStore").isEqualTo("redis")
                .jsonPath("$.rateLimitStore").isEqualTo("redis")
                .jsonPath("$.rateLimitEnabled").isEqualTo(true)
                .jsonPath("$.pubsubActive").isEqualTo(true)
                .jsonPath("$.pubsubChannel").isEqualTo("gw:config:version")
                // 敏感词来自 classpath 种子库（真库 sensitive_word 表为空）
                .jsonPath("$.sensitiveWords").isEqualTo(7);
    }

    @Test
    void enablingChannelViaAdminTakesEffectWithoutWaitingForCacheTtl() {
        // 通道初始 enabled=0：首调必然无候选通道。这一调用同时把「空通道列表」写进 Caffeine 缓存
        chat(newTraceId(), "it-dormant", ALICE_TOKEN, "hi")
                .expectStatus().isEqualTo(503)
                .expectBody(String.class).value(body -> assertThat(body).contains("service_unavailable"));

        // 经管理接口启用通道：写库 -> 本地缓存失效 -> Redis 频道广播（多实例同步）
        client.put().uri("/admin-api/gateway/channels/" + CHANNEL_DORMANT)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"enabled\":1}")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.enabled").isEqualTo(1);

        assertThat(jdbc.queryForObject(
                "SELECT enabled FROM model_channel WHERE id = ?", Integer.class, CHANNEL_DORMANT))
                .isEqualTo(1);

        // 若配置热失效没生效，这里会因 30s 的 Caffeine TTL 仍然读到空通道列表而继续 503
        chat(newTraceId(), "it-dormant", ALICE_TOKEN, "hi")
                .expectStatus().isOk()
                .expectBody(String.class).value(body -> assertThat(body).contains("pong from full stack"));
    }

    // ------------------------------------------------------------------ Actuator 就绪探针

    @Test
    void readinessProbeIsUpWithRealDbRedisAndRoutableUpstream() {
        client.get().uri("/actuator/health/readiness")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("UP");
    }

    // ------------------------------------------------------------------ 种子数据与辅助

    /**
     * 以 {@code REPLACE INTO} 幂等重播配置。用 MySQL 专有语法而非 {@code INSERT ... ON DUPLICATE KEY}，
     * 顺带确认迁移建出的唯一键（{@code uk_provider_code_name} / {@code uk_logical_model_name}）真实生效。
     */
    private void seedConfig() {
        String providerSql = """
                REPLACE INTO provider (id, name, code, base_url, auth_type, credentials, status, priority)
                VALUES (?, ?, ?, ?, 'BEARER', ?, 1, 100)
                """;
        // 凭据以明文入库：CredentialCipher.decrypt 对无 enc:: 前缀的值原样返回，便于断言 Bearer 透传
        jdbc.update(providerSql, PROVIDER_OK, "IT Upstream", "DEEPSEEK", upstreamBase + OK_PREFIX, "sk-it-ok");
        jdbc.update(providerSql, PROVIDER_BROKEN, "IT Broken", "CUSTOM", upstreamBase + "/broken", "sk-it-broken");

        String modelSql = """
                REPLACE INTO logical_model (id, name, capability, description, status)
                VALUES (?, ?, 'CHAT', '集成测试模型', 1)
                """;
        jdbc.update(modelSql, MODEL_CHAT, "it-chat");
        jdbc.update(modelSql, MODEL_STREAM, "it-stream");
        jdbc.update(modelSql, MODEL_BROKEN, "it-broken");
        jdbc.update(modelSql, MODEL_DORMANT, "it-dormant");

        String channelSql = """
                REPLACE INTO model_channel
                    (id, logical_model_id, provider_id, upstream_model, protocol, weight, priority, enabled)
                VALUES (?, ?, ?, 'deepseek-chat', 'OPENAI_COMPAT', 100, 1, ?)
                """;
        jdbc.update(channelSql, CHANNEL_CHAT, MODEL_CHAT, PROVIDER_OK, 1);
        jdbc.update(channelSql, CHANNEL_STREAM, MODEL_STREAM, PROVIDER_OK, 1);
        jdbc.update(channelSql, CHANNEL_BROKEN, MODEL_BROKEN, PROVIDER_BROKEN, 1);
        // 热更新用例的「休眠通道」：初始停用，由 Admin PUT 启用
        jdbc.update(channelSql, CHANNEL_DORMANT, MODEL_DORMANT, PROVIDER_OK, 0);

        // 限流规则已从 yaml 迁移到数据库：显式种入 throttled 调用人覆盖（2 次/分钟），
        // 使限流用例的 429 断言不依赖 data.sql 的默认规则（默认 600/分钟不会触发）
        jdbc.update("""
                REPLACE INTO rate_limit_rule
                    (id, name, rule_type, target, permits_per_minute, daily_tokens, enabled, deleted)
                VALUES (?, 'IT 限流覆盖', 'CALLER_OVERRIDE', 'throttled', 2, NULL, 1, 0)
                """, 99001L);
        // 重建限流规则快照：RateLimitRuleService 启动时已按 data.sql 载入（含默认规则），
        // 此处须显式刷新让上面的覆盖规则生效，否则快照停留在「无 throttled 覆盖」的旧状态
        rateLimitRuleService.refresh().block(WAIT);
    }

    /** 清空网关自身的 Redis 键（健康 hash / 限流桶 / 探测锁），使每个用例从干净状态开始。 */
    private void flushRedisKeys() {
        List<String> keys = redis.keys("gw:*").collectList().block(WAIT);
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys.toArray(new String[0])).block(WAIT);
        }
    }

    private static String newTraceId() {
        return "it-" + UUID.randomUUID().toString().replace("-", "");
    }

    private WebTestClient.ResponseSpec chat(String traceId, String model, String token, String content) {
        return chat(traceId, model, token, content, false);
    }
    
    /**
     * 发一次 chat 请求。traceId 由调用方指定并随 {@code X-Trace-Id} 上行，网关原样回写并落审计，
     * 因此断言能精确定位到本次调用而不必依赖时间排序。
     */
    private WebTestClient.ResponseSpec chat(String traceId, String model, String token,
                                            String content, boolean stream) {
        return client.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Trace-Id", traceId)
                // 静态令牌 -> 调用人身份（CallerIdentityResolver），审计与限流均依赖它
                .header("Authorization", "Bearer " + token)
                .bodyValue("{\"model\":\"" + model + "\",\"stream\":" + stream
                        + ",\"messages\":[{\"role\":\"user\",\"content\":\"" + content + "\"}]}")
                .exchange();
    }

    /** 等待审计异步批量落库，返回该 traceId 的唯一一行。 */
    private Map<String, Object> awaitAuditRow(String traceId) {
        await().atMost(WAIT).pollInterval(POLL).untilAsserted(() -> {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM audit_log WHERE trace_id = ?", Integer.class, traceId);
            assertThat(count).as("audit_log rows for trace %s", traceId).isEqualTo(1);
        });
        return jdbc.queryForMap("SELECT * FROM audit_log WHERE trace_id = ?", traceId);
    }

    private static int intOf(Map<String, Object> row, String column) {
        Object value = row.get(column);
        assertThat(value).as("column %s", column).isNotNull();
        return ((Number) value).intValue();
    }

    private static long longOf(Map<String, Object> row, String column) {
        Object value = row.get(column);
        assertThat(value).as("column %s", column).isNotNull();
        return ((Number) value).longValue();
    }

    /** JsonPath 过滤表达式恒返回 {@code List}；转成 {@code List<Object>} 以便 AssertJ 做元素级断言。 */
    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) {
        return (List<Object>) value;
    }
}
