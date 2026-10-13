package tech.qunxu.module.gateway.adapter;

import tech.qunxu.module.gateway.common.enums.ChannelProtocol;
import tech.qunxu.module.gateway.common.enums.ProviderType;
import tech.qunxu.module.gateway.common.exception.ErrorCode;
import tech.qunxu.module.gateway.common.exception.GatewayException;
import tech.qunxu.module.gateway.common.model.GenerationParams;
import tech.qunxu.module.gateway.common.model.UnifiedMessage;
import tech.qunxu.module.gateway.common.model.UnifiedRequest;
import tech.qunxu.module.gateway.common.model.UnifiedStreamChunk;
import tech.qunxu.module.gateway.persistence.entity.ModelChannelEntity;
import tech.qunxu.module.gateway.persistence.entity.ProviderEntity;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 讯飞星火 WS 适配器单元测试：出向帧解析（增量帧 / 终帧含 usage / 鉴权错误码 / 通用错误码归一）与入向请求帧构建
 * （app_id、uid、chat.domain、参数、消息角色映射）。{@code parseFrame}/{@code buildRequestFrame} 均为包可见，无需真实 WS 连接。
 */
class SparkWebSocketAdapterTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final SparkWebSocketAdapter adapter = new SparkWebSocketAdapter(null, mapper);

    @Test
    void parseContentFrameYieldsSingleDeltaChunk() {
        String frame = """
                {"header":{"code":0,"status":1,"sid":"sid-1"},
                 "payload":{"choices":{"text":[{"content":"你好","index":0}]}}}
                """;
        List<UnifiedStreamChunk> chunks = adapter.parseFrame(frame, "spark-v4");

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).deltaContent()).isEqualTo("你好");
        assertThat(chunks.get(0).id()).isEqualTo("sid-1");
        assertThat(chunks.get(0).model()).isEqualTo("spark-v4");
        assertThat(chunks.get(0).done()).isFalse();
    }

    @Test
    void parseFinalFrameYieldsContentThenEndWithUsage() {
        String frame = """
                {"header":{"code":0,"status":2,"sid":"sid-2"},
                 "payload":{"choices":{"text":[{"content":"世界","index":0}]},
                            "usage":{"text":{"prompt_tokens":8,"completion_tokens":4,"total_tokens":12}}}}
                """;
        List<UnifiedStreamChunk> chunks = adapter.parseFrame(frame, "spark-v4");

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).deltaContent()).isEqualTo("世界");
        UnifiedStreamChunk end = chunks.get(1);
        assertThat(end.done()).isTrue();
        assertThat(end.finishReason()).isEqualTo("stop");
        assertThat(end.usage().promptTokens()).isEqualTo(8);
        assertThat(end.usage().completionTokens()).isEqualTo(4);
        assertThat(end.usage().totalTokens()).isEqualTo(12);
    }

    @Test
    void parseAuthErrorFrameThrowsAuthExpired() {
        String frame = """
                {"header":{"code":10401,"message":"token expired","status":2,"sid":"sid-3"}}
                """;
        assertThatThrownBy(() -> adapter.parseFrame(frame, "spark-v4"))
                .isInstanceOf(GatewayException.class)
                .satisfies(e -> assertThat(((GatewayException) e).getErrorCode()).isEqualTo(ErrorCode.AUTH_EXPIRED));
    }

    @Test
    void parseGenericErrorFrameThrowsProviderError() {
        String frame = """
                {"header":{"code":10005,"message":"internal error","status":2,"sid":"sid-4"}}
                """;
        assertThatThrownBy(() -> adapter.parseFrame(frame, "spark-v4"))
                .isInstanceOf(GatewayException.class)
                .satisfies(e -> assertThat(((GatewayException) e).getErrorCode()).isEqualTo(ErrorCode.PROVIDER_ERROR));
    }

    @Test
    void buildRequestFrameCarriesAppIdDomainParamsAndMessages() {
        UnifiedRequest request = chatRequest("你是助手", "用户问题");

        String frame = adapter.buildRequestFrame(request, runtime(), "app-123");
        JsonNode root = mapper.readTree(frame);

        assertThat(root.path("header").path("app_id").asText()).isEqualTo("app-123");
        assertThat(root.path("header").path("uid").asText()).isNotBlank();
        assertThat(root.path("parameter").path("chat").path("domain").asText()).isEqualTo("general");
        assertThat(root.path("parameter").path("chat").path("max_tokens").asInt()).isEqualTo(64);
        assertThat(root.path("parameter").path("chat").path("top_k").asInt()).isEqualTo(3);

        JsonNode text = root.path("payload").path("message").path("text");
        assertThat(text.isArray()).isTrue();
        assertThat(text.size()).isEqualTo(2);
        assertThat(text.get(0).path("role").asText()).isEqualTo("system");
        assertThat(text.get(0).path("content").asText()).isEqualTo("你是助手");
        assertThat(text.get(1).path("role").asText()).isEqualTo("user");
        assertThat(text.get(1).path("content").asText()).isEqualTo("用户问题");
    }

    private UnifiedRequest chatRequest(String system, String user) {
        GenerationParams params = new GenerationParams(
                null, null, 64, null, null, null, null, null, null, Map.of("top_k", 3));
        UnifiedRequest.ChatPayload chat = new UnifiedRequest.ChatPayload(
                List.of(UnifiedMessage.system(system), UnifiedMessage.user(user)), params, null, null);
        return UnifiedRequest.chat("spark-v4", false, chat);
    }

    private ChannelRuntime runtime() {
        ModelChannelEntity channel = new ModelChannelEntity();
        channel.setId(1L);
        channel.setUpstreamModel("spark-v4");
        ProviderEntity provider = new ProviderEntity();
        provider.setId(1L);
        provider.setCode("SPARK");
        provider.setBaseUrl("wss://spark-api.xf-yun.com/v4.0/chat");
        provider.setAuthType("SPARK_WS");
        return new ChannelRuntime(channel, provider, ProviderType.SPARK, ChannelProtocol.SPARK_WS,
                "wss://spark-api.xf-yun.com/v4.0/chat", "spark-v4",
                ProviderCredentials.of(Map.of("appId", "app-123", "apiKey", "k", "apiSecret", "s")), Map.of());
    }
}
