package tech.qunxu.module.gateway.admin;

import tech.qunxu.module.gateway.admin.service.ApiKeyAdminService;
import tech.qunxu.module.gateway.admin.service.HealthAdminService;
import tech.qunxu.module.gateway.admin.service.ProviderAdminService;
import tech.qunxu.module.gateway.admin.service.ProviderModelDiscoveryService;
import tech.qunxu.module.gateway.common.enums.HealthEventType;
import tech.qunxu.module.gateway.common.enums.HealthStatus;
import tech.qunxu.module.gateway.config.ConfigVersionPublisher;
import tech.qunxu.module.gateway.config.ConfigVersionSubscriber;
import tech.qunxu.module.gateway.config.GatewayProperties;
import tech.qunxu.module.gateway.guardrail.SensitiveWordService;
import tech.qunxu.module.gateway.health.ActiveProbeScheduler;
import tech.qunxu.module.gateway.health.ChannelHealth;
import tech.qunxu.module.gateway.health.HealthResult;
import tech.qunxu.module.gateway.persistence.entity.ApiKeyEntity;
import tech.qunxu.module.gateway.persistence.entity.HealthEventEntity;
import tech.qunxu.module.gateway.persistence.entity.ModelChannelEntity;
import tech.qunxu.module.gateway.persistence.entity.ProviderEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Admin REST 契约测试（M8e）：以 {@code WebTestClient.bindToController} 独立装配三个管理控制器，
 * 验证路由、HTTP 语义（201 新增 / 204 删除 / 200 查询）、查询参数绑定与响应 JSON 结构。
 *
 * <p>刻意不启动完整上下文：契约应只由控制器与序列化决定，服务层行为已由
 * {@code ConfigAdminServicesTest} / {@code HealthAdminServiceTest} 单独覆盖。错误体渲染见
 * {@code GlobalErrorHandlerTest}。</p>
 */
class AdminApiTest {

    private ProviderAdminService providerService;
    private HealthAdminService healthAdminService;
    private ActiveProbeScheduler probeScheduler;
    private ConfigVersionPublisher publisher;
    private ConfigVersionSubscriber subscriber;
    private SensitiveWordService sensitiveWordService;
    private GatewayProperties properties;
    private ApiKeyAdminService apiKeyService;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        providerService = mock(ProviderAdminService.class);
        healthAdminService = mock(HealthAdminService.class);
        probeScheduler = mock(ActiveProbeScheduler.class);
        publisher = mock(ConfigVersionPublisher.class);
        subscriber = mock(ConfigVersionSubscriber.class);
        sensitiveWordService = mock(SensitiveWordService.class);
        apiKeyService = mock(ApiKeyAdminService.class);
        properties = new GatewayProperties();
        properties.getRateLimit().setStore("redis");
        properties.getQuota().setEnabled(true);
        properties.getHealth().setStore("redis");

        when(publisher.publish(any())).thenReturn(Mono.empty());
        when(publisher.invalidateLocal(any())).thenReturn(Mono.empty());

        client = WebTestClient.bindToController(
                        new ProviderAdminController(providerService, mock(ProviderModelDiscoveryService.class)),
                        new HealthAdminController(healthAdminService, probeScheduler),
                        new CacheAdminController(publisher, subscriber, sensitiveWordService, properties),
                        new ApiKeyAdminController(apiKeyService))
                .build();
    }

    private static ProviderEntity provider() {
        ProviderEntity entity = new ProviderEntity();
        entity.setId(1L);
        entity.setName("DeepSeek");
        entity.setCode("DEEPSEEK");
        entity.setBaseUrl("https://api.deepseek.com");
        entity.setAuthType("BEARER");
        entity.setCredentials("***");
        entity.setStatus(1);
        entity.setPriority(100);
        return entity;
    }

    // ------------------------------------------------------------------ 供应商 CRUD

    @Test
    void createProviderReturns201WithMaskedCredentials() {
        when(providerService.create(any())).thenReturn(Mono.just(provider()));

        client.post().uri("/admin-api/gateway/providers")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"name":"DeepSeek","code":"DEEPSEEK","baseUrl":"https://api.deepseek.com",
                         "credentials":"sk-plaintext"}
                        """)
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.id").isEqualTo(1)
                .jsonPath("$.code").isEqualTo("DEEPSEEK")
                // 即便请求体提交明文，响应也必须只回掩码
                .jsonPath("$.credentials").isEqualTo("***");
    }

    @Test
    void getProviderByIdReturnsEntity() {
        when(providerService.getById(1L)).thenReturn(Mono.just(provider()));

        client.get().uri("/admin-api/gateway/providers/1")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.name").isEqualTo("DeepSeek")
                .jsonPath("$.baseUrl").isEqualTo("https://api.deepseek.com");
    }

    @Test
    void updateProviderIsPartialAndReturnsFreshEntity() {
        when(providerService.update(eq(1L), any())).thenReturn(Mono.just(provider()));

        client.put().uri("/admin-api/gateway/providers/1")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"remark\":\"改备注\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.id").isEqualTo(1);

        verify(providerService).update(eq(1L), any());
    }

    @Test
    void deleteProviderReturns204WithoutBody() {
        when(providerService.delete(1L)).thenReturn(Mono.empty());

        client.delete().uri("/admin-api/gateway/providers/1")
                .exchange()
                .expectStatus().isNoContent()
                .expectBody().isEmpty();
    }

    @Test
    void queryProvidersBindsFiltersAndReturnsPageEnvelope() {
        when(providerService.query("deep", 1, 2, 20))
                .thenReturn(Mono.just(new AdminPage<>(List.of(provider()), 41L, 2, 20)));

        client.get().uri("/admin-api/gateway/providers?keyword=deep&status=1&page=2&size=20")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items.length()").isEqualTo(1)
                .jsonPath("$.items[0].code").isEqualTo("DEEPSEEK")
                .jsonPath("$.total").isEqualTo(41)
                .jsonPath("$.page").isEqualTo(2)
                .jsonPath("$.size").isEqualTo(20);
    }

    @Test
    void queryAllProvidersReturnsBareArray() {
        when(providerService.queryAll(null, null)).thenReturn(Mono.just(List.of(provider(), provider())));

        client.get().uri("/admin-api/gateway/providers/all")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.length()").isEqualTo(2);
    }

    @Test
    void optionalQueryParamsArePassedAsNull() {
        when(providerService.query(null, null, null, null))
                .thenReturn(Mono.just(AdminPage.empty(1, 20)));

        client.get().uri("/admin-api/gateway/providers")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items").isArray()
                .jsonPath("$.total").isEqualTo(0);

        verify(providerService).query(null, null, null, null);
    }

    // ------------------------------------------------------------------ 健康视图与手动干预

    @Test
    void healthSummaryExposesAllCounters() {
        when(healthAdminService.summary()).thenReturn(Mono.just(
                new HealthAdminService.HealthSummary(9, 5, 1, 2, 1, 6, 3)));

        client.get().uri("/admin-api/gateway/health/summary")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.total").isEqualTo(9)
                .jsonPath("$.up").isEqualTo(5)
                .jsonPath("$.down").isEqualTo(1)
                .jsonPath("$.ejected").isEqualTo(2)
                .jsonPath("$.unknown").isEqualTo(1)
                .jsonPath("$.routable").isEqualTo(6)
                .jsonPath("$.disabled").isEqualTo(3);
    }

    @Test
    void healthChannelsMergesConfigAndRuntimeFieldsWithPagination() {
        ModelChannelEntity channel = new ModelChannelEntity();
        channel.setId(1L);
        channel.setLogicalModelId(10L);
        channel.setProviderId(20L);
        channel.setUpstreamModel("deepseek-chat");
        channel.setProtocol("OPENAI_COMPAT");
        channel.setWeight(100);
        channel.setPriority(10);
        channel.setEnabled(1);
        when(healthAdminService.channelsPage(eq(10L), eq(20L), eq(true), any(), any())).thenReturn(Mono.just(
                new AdminPage<>(List.of(
                        ChannelHealthView.of(channel, "deepseek-chat", "DeepSeek", "DEEPSEEK", ChannelHealth.up(1L))),
                        1L, 1, 10)));

        client.get().uri("/admin-api/gateway/health/channels?logicalModelId=10&providerId=20&onlyUnhealthy=true&page=1&size=10")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items[0].channelId").isEqualTo(1)
                .jsonPath("$.items[0].logicalModel").isEqualTo("deepseek-chat")
                .jsonPath("$.items[0].providerCode").isEqualTo("DEEPSEEK")
                .jsonPath("$.items[0].status").isEqualTo("UP")
                .jsonPath("$.items[0].routable").isEqualTo(true)
                .jsonPath("$.items[0].ejected").isEqualTo(false)
                .jsonPath("$.total").isEqualTo(1)
                .jsonPath("$.page").isEqualTo(1)
                .jsonPath("$.size").isEqualTo(10);
    }

    @Test
    void probeReturnsResultAndPostProbeView() {
        ModelChannelEntity channel = new ModelChannelEntity();
        channel.setId(1L);
        channel.setEnabled(1);
        when(healthAdminService.probe(1L)).thenReturn(Mono.just(new HealthAdminService.ProbeOutcome(
                HealthResult.up(42L),
                ChannelHealthView.of(channel, "m", "p", "DEEPSEEK", ChannelHealth.up(1L)))));

        client.post().uri("/admin-api/gateway/health/channels/1/probe")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.result.status").isEqualTo("UP")
                .jsonPath("$.result.latencyMs").isEqualTo(42)
                .jsonPath("$.health.channelId").isEqualTo(1);
    }

    @Test
    void recoverReturnsUpdatedView() {
        ModelChannelEntity channel = new ModelChannelEntity();
        channel.setId(1L);
        channel.setEnabled(1);
        when(healthAdminService.recover(1L)).thenReturn(Mono.just(
                ChannelHealthView.of(channel, "m", "p", "DEEPSEEK", ChannelHealth.up(1L))));

        client.post().uri("/admin-api/gateway/health/channels/1/recover")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("UP");
    }

    @Test
    void ejectBindsOptionalCooldownSeconds() {
        ModelChannelEntity channel = new ModelChannelEntity();
        channel.setId(1L);
        channel.setEnabled(1);
        when(healthAdminService.eject(eq(1L), any())).thenReturn(Mono.just(
                ChannelHealthView.of(channel, "m", "p", "DEEPSEEK",
                        ChannelHealth.unknown(1L).ejected(30_000L))));

        client.post().uri("/admin-api/gateway/health/channels/1/eject?cooldownSeconds=30")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("EJECTED")
                .jsonPath("$.routable").isEqualTo(false)
                .jsonPath("$.ejectedUntil").isNumber();

        verify(healthAdminService).eject(1L, 30L);
    }

    @Test
    void ejectWithoutCooldownPassesNull() {
        when(healthAdminService.eject(eq(1L), any())).thenReturn(Mono.empty());

        client.post().uri("/admin-api/gateway/health/channels/1/eject")
                .exchange()
                .expectStatus().isOk();

        verify(healthAdminService).eject(1L, null);
    }

    @Test
    void probeRoundReturnsProbedChannelCount() {
        when(probeScheduler.probeRound()).thenReturn(Mono.just(7));

        client.post().uri("/admin-api/gateway/health/probe-round")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("7");
    }

    @Test
    void eventsBindsIsoDateTimeRange() {
        HealthEventEntity event = new HealthEventEntity();
        event.setId(1L);
        event.setChannelId(1L);
        event.setEventType(HealthEventType.EJECT.name());
        event.setFromStatus(HealthStatus.UP.name());
        event.setToStatus(HealthStatus.EJECTED.name());
        event.setReason("连续失败 5 次");
        when(healthAdminService.events(eq(1L), eq(20L), eq("eject"), any(), any(), eq(1), eq(10)))
                .thenReturn(Mono.just(new AdminPage<>(List.of(event), 1L, 1, 10)));

        client.get().uri(builder -> builder.path("/admin-api/gateway/health/events")
                        .queryParam("channelId", "1")
                        .queryParam("providerId", "20")
                        .queryParam("eventType", "eject")
                        .queryParam("from", "2026-01-01T00:00:00")
                        .queryParam("to", "2026-01-31T23:59:59")
                        .queryParam("page", "1")
                        .queryParam("size", "10")
                        .build())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items[0].eventType").isEqualTo("EJECT")
                .jsonPath("$.items[0].fromStatus").isEqualTo("UP")
                .jsonPath("$.items[0].toStatus").isEqualTo("EJECTED");

        verify(healthAdminService).events(1L, 20L, "eject",
                LocalDateTime.parse("2026-01-01T00:00:00"),
                LocalDateTime.parse("2026-01-31T23:59:59"), 1, 10);
    }

    // ------------------------------------------------------------------ 缓存与限流概况

    @Test
    void invalidateBroadcastsGivenType() {
        client.post().uri("/admin-api/gateway/cache/invalidate?type=provider")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("已失效并广播: provider");

        verify(publisher).publish("provider");
    }

    @Test
    void invalidateDefaultsToFullRefresh() {
        client.post().uri("/admin-api/gateway/cache/invalidate")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("已失效并广播: all");

        verify(publisher).publish("all");
    }

    @Test
    void invalidateLocalDoesNotBroadcast() {
        client.post().uri("/admin-api/gateway/cache/invalidate-local?type=guardrail")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("已失效本实例: guardrail");

        verify(publisher).invalidateLocal("guardrail");
    }

    @Test
    void cacheStatusReflectsRuntimeConfiguration() {
        when(sensitiveWordService.size()).thenReturn(128);
        when(subscriber.isActive()).thenReturn(true);

        client.get().uri("/admin-api/gateway/cache/status")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.sensitiveWords").isEqualTo(128)
                .jsonPath("$.pubsubActive").isEqualTo(true)
                .jsonPath("$.pubsubChannel").isEqualTo(ConfigVersionPublisher.CHANNEL)
                .jsonPath("$.rateLimitStore").isEqualTo("redis")
                .jsonPath("$.quotaEnabled").isEqualTo(true)
                .jsonPath("$.healthStore").isEqualTo("redis")
                .jsonPath("$.healthEnabled").isEqualTo(true);
    }

    @Test
    void unknownAdminPathIsNotFound() {
        // 三个控制器共存于同一装配下，路径不应互相遮蔽
        client.get().uri("/admin-api/gateway/nope")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void singleChannelHealthIsAddressableById() {
        ModelChannelEntity channel = new ModelChannelEntity();
        channel.setId(3L);
        channel.setEnabled(1);
        when(healthAdminService.channel(3L)).thenReturn(Mono.just(
                ChannelHealthView.of(channel, "glm-4", "智谱", "ZHIPU", ChannelHealth.up(3L))));

        client.get().uri("/admin-api/gateway/health/channels/3")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.channelId").isEqualTo(3)
                .jsonPath("$.logicalModel").isEqualTo("glm-4")
                .jsonPath("$.status").isEqualTo("UP");
    }

    // ------------------------------------------------------------------ APIKey 管理

    private static ApiKeyEntity apiKeyEntity() {
        ApiKeyEntity entity = new ApiKeyEntity();
        entity.setId(7L);
        entity.setUserId(2L);
        entity.setUserName("test");
        entity.setName("生产环境");
        entity.setApiKeyHash("4f0d83f01bcfdc08d63090e3abd1c42a85b8947864992fd23c0517a1fe3260c4");
        entity.setKeyPrefix("sk-qx-1a2b3c4d");
        entity.setTenant("1");
        entity.setStatus(1);
        return entity;
    }

    @Test
    void createApiKeyReturns201WithPlaintextOnce() {
        when(apiKeyService.createKey(any()))
                .thenReturn(Mono.just(new ApiKeyCreated(apiKeyEntity(), "sk-qx-plaintext-returned-once")));

        client.post().uri("/admin-api/gateway/api-keys")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"生产环境\",\"remark\":\"线上\"}")
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.key.id").isEqualTo(7)
                .jsonPath("$.key.name").isEqualTo("生产环境")
                .jsonPath("$.key.keyPrefix").isEqualTo("sk-qx-1a2b3c4d")
                // 明文仅在创建响应出现
                .jsonPath("$.apiKey").isEqualTo("sk-qx-plaintext-returned-once");
    }

    @Test
    void rotateApiKeyReturnsNewPlaintext() {
        when(apiKeyService.rotate(7L))
                .thenReturn(Mono.just(new ApiKeyCreated(apiKeyEntity(), "sk-qx-rotated-new-plaintext")));

        client.post().uri("/admin-api/gateway/api-keys/7/rotate")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.apiKey").isEqualTo("sk-qx-rotated-new-plaintext");
    }

    @Test
    void queryApiKeysBindsOwnerFilters() {
        when(apiKeyService.query("prod", 2L, 1, 2, 20))
                .thenReturn(Mono.just(new AdminPage<>(List.of(apiKeyEntity()), 1L, 2, 20)));

        client.get().uri("/admin-api/gateway/api-keys?keyword=prod&userId=2&status=1&page=2&size=20")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items[0].name").isEqualTo("生产环境")
                .jsonPath("$.items[0].apiKeyHash").isEqualTo("4f0d83f01bcfdc08d63090e3abd1c42a85b8947864992fd23c0517a1fe3260c4")
                .jsonPath("$.total").isEqualTo(1);
    }

    @Test
    void deleteApiKeyReturns204() {
        when(apiKeyService.delete(7L)).thenReturn(Mono.empty());

        client.delete().uri("/admin-api/gateway/api-keys/7")
                .exchange()
                .expectStatus().isNoContent()
                .expectBody().isEmpty();
    }
}
