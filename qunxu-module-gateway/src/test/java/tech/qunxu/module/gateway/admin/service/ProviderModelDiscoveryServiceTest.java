package tech.qunxu.module.gateway.admin.service;

import tech.qunxu.module.gateway.adapter.ChannelRuntimeFactory;
import tech.qunxu.module.gateway.adapter.UpstreamAuthenticatorResolver;
import tech.qunxu.module.gateway.admin.ProviderModelInfo;
import tech.qunxu.module.gateway.common.exception.ErrorCode;
import tech.qunxu.module.gateway.common.exception.GatewayException;
import tech.qunxu.module.gateway.config.GatewayProperties;
import tech.qunxu.module.gateway.persistence.entity.ProviderEntity;
import tech.qunxu.module.gateway.persistence.mapper.ProviderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 供应商模型发现服务单元测试：覆盖模型清单解析的三种格式、能力自动识别与关键错误分支
 * （供应商不存在、供应商已禁用、SPARK_WS 不支持、缺失下游地址）。
 */
class ProviderModelDiscoveryServiceTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ProviderMapper providerMapper;
    private ProviderModelDiscoveryService service;

    @BeforeEach
    void setUp() {
        providerMapper = mock(ProviderMapper.class);
        GatewayProperties properties = new GatewayProperties();
        ChannelRuntimeFactory runtimeFactory = new ChannelRuntimeFactory(new tech.qunxu.module.gateway.common.crypto.CredentialCipher(properties), objectMapper);
        UpstreamAuthenticatorResolver authResolver = new UpstreamAuthenticatorResolver(java.util.List.of());
        service = new ProviderModelDiscoveryService(providerMapper, runtimeFactory, authResolver,
                mock(WebClient.class), objectMapper);
    }

    private static ProviderEntity provider(Long id, String code, String authType, Integer status, String baseUrl) {
        ProviderEntity entity = new ProviderEntity();
        entity.setId(id);
        entity.setCode(code);
        entity.setAuthType(authType);
        entity.setStatus(status);
        entity.setBaseUrl(baseUrl);
        return entity;
    }

    @Test
    void parsesOpenAiCompatibleFormat() {
        JsonNode body = objectMapper.readTree("""
                {"object":"list","data":[{"id":"gpt-4o","object":"model"},{"id":"text-embedding-3-small","object":"model"}]}
                """);
        List<ProviderModelInfo> infos = ProviderModelDiscoveryService.parseModelInfos(body);
        assertThat(infos).extracting(ProviderModelInfo::id).containsExactly("gpt-4o", "text-embedding-3-small");
        // 能力自动识别：chat → CHAT，embedding → EMBEDDING
        assertThat(infos.get(0).capability()).isEqualTo("CHAT");
        assertThat(infos.get(1).capability()).isEqualTo("EMBEDDING");
    }

    @Test
    void parsesStringArrayFormat() {
        JsonNode body = objectMapper.readTree("""
                ["qwen-turbo","bge-reranker-v2-m3"]
                """);
        List<ProviderModelInfo> infos = ProviderModelDiscoveryService.parseModelInfos(body);
        assertThat(infos).extracting(ProviderModelInfo::id).containsExactly("bge-reranker-v2-m3", "qwen-turbo");
        assertThat(infos.get(0).capability()).isEqualTo("RERANK");
        assertThat(infos.get(1).capability()).isEqualTo("CHAT");
    }

    @Test
    void parsesModelsKeyFormat() {
        JsonNode body = objectMapper.readTree("""
                {"models":[{"id":"cosyvoice-v1"},{"id":"whisper-1"}]}
                """);
        List<ProviderModelInfo> infos = ProviderModelDiscoveryService.parseModelInfos(body);
        assertThat(infos).extracting(ProviderModelInfo::id).containsExactly("cosyvoice-v1", "whisper-1");
        assertThat(infos.get(0).capability()).isEqualTo("TTS");
        assertThat(infos.get(1).capability()).isEqualTo("STT");
    }

    @Test
    void dedupesModelsAndSortsById() {
        JsonNode body = objectMapper.readTree("""
                {"data":[{"id":"b","object":"model"},{"id":"a"},{"id":"b"}]}
                """);
        List<ProviderModelInfo> infos = ProviderModelDiscoveryService.parseModelInfos(body);
        assertThat(infos).extracting(ProviderModelInfo::id).containsExactly("a", "b");
    }

    @Test
    void emptyBodyYieldsEmptyList() {
        assertThat(ProviderModelDiscoveryService.parseModelInfos(null)).isEmpty();
        assertThat(ProviderModelDiscoveryService.parseModelInfos(objectMapper.readTree("{}"))).isEmpty();
    }

    @Test
    void rejectsMissingProvider() {
        when(providerMapper.selectById(1L)).thenReturn(null);

        StepVerifier.create(service.listModels(1L))
                .expectErrorSatisfies(error -> {
                    assertThat(((GatewayException) error).getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
                    assertThat(error).hasMessageContaining("供应商不存在");
                }).verify();
    }

    @Test
    void rejectsDisabledProvider() {
        when(providerMapper.selectById(1L)).thenReturn(provider(1L, "DEEPSEEK", "BEARER", 0, "http://x/v1"));

        StepVerifier.create(service.listModels(1L))
                .expectErrorSatisfies(error -> {
                    assertThat(((GatewayException) error).getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
                    assertThat(error).hasMessageContaining("已禁用");
                }).verify();
    }

    @Test
    void rejectsSparkWsProvider() {
        when(providerMapper.selectById(1L)).thenReturn(provider(1L, "SPARK", "SPARK_WS", 1, "ws://x"));

        StepVerifier.create(service.listModels(1L))
                .expectErrorSatisfies(error -> {
                    assertThat(((GatewayException) error).getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
                    assertThat(error).hasMessageContaining("不支持拉取模型清单");
                }).verify();
    }

    @Test
    void rejectsMissingBaseUrl() {
        when(providerMapper.selectById(1L)).thenReturn(provider(1L, "DEEPSEEK", "BEARER", 1, null));

        StepVerifier.create(service.listModels(1L))
                .expectErrorSatisfies(error -> {
                    assertThat(((GatewayException) error).getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
                    assertThat(error).hasMessageContaining("base_url");
                }).verify();
    }
}
