package tech.qunxu.module.gateway.protocol;

import tech.qunxu.module.gateway.common.enums.Capability;
import tech.qunxu.module.gateway.common.model.TokenUsage;
import tech.qunxu.module.gateway.common.model.UnifiedMessage;
import tech.qunxu.module.gateway.common.model.UnifiedRequest;
import tech.qunxu.module.gateway.common.model.UnifiedResponse;
import tech.qunxu.module.gateway.common.model.UnifiedStreamChunk;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gemini 协议处理器单元测试（P3）：generateContent 入向解析与 GenerateContentResponse 出向渲染（unary / SSE）。
 */
class GeminiProtocolHandlerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final GeminiProtocolHandler handler = new GeminiProtocolHandler(mapper);

    @Test
    void parsesGenerateContentRequest() throws Exception {
        String json = """
                {
                  "model": "gemini-2.0-flash",
                  "systemInstruction": {"parts": [{"text": "你是助手"}]},
                  "contents": [
                    {"role": "user", "parts": [{"text": "你好"}, {"text": "世界"}]},
                    {"role": "model", "parts": [{"text": "你好！"}]}
                  ],
                  "generationConfig": {"temperature": 0.3, "maxOutputTokens": 200, "stopSequences": ["END"]},
                  "tools": [{"functionDeclarations": [{"name": "get_weather", "description": "查天气", "parameters": {"type": "object"}}]}]
                }
                """;
        UnifiedRequest request = handler.parseRequest(mapper.readTree(json));

        assertThat(request.capability()).isEqualTo(Capability.CHAT);
        assertThat(request.model()).isEqualTo("gemini-2.0-flash");
        assertThat(request.stream()).isFalse();
        // systemInstruction 归一为首条 system 消息
        assertThat(request.chat().messages().get(0).role()).isEqualTo("system");
        assertThat(request.chat().messages().get(0).content()).isEqualTo("你是助手");
        // model 角色归一为 assistant
        assertThat(request.chat().messages().get(2).role()).isEqualTo("assistant");
        assertThat(request.chat().messages().get(2).content()).isEqualTo("你好！");

        assertThat(request.chat().params().temperature()).isEqualTo(0.3);
        assertThat(request.chat().params().maxTokens()).isEqualTo(200);
        assertThat(request.chat().tools()).hasSize(1);
        assertThat(request.chat().tools().get(0).function().name()).isEqualTo("get_weather");
    }

    @Test
    void parsesInlineImagePartToDataUrl() throws Exception {
        String json = """
                {
                  "model": "gemini-2.0-flash",
                  "contents": [{"role": "user", "parts": [
                    {"text": "看图"},
                    {"inlineData": {"mimeType": "image/png", "data": "AAAA"}}
                  ]}]
                }
                """;
        UnifiedRequest request = handler.parseRequest(mapper.readTree(json));

        var parts = request.chat().messages().get(0).contentParts();
        assertThat(parts.get(1).type()).isEqualTo("image_url");
        assertThat(parts.get(1).imageUrl()).isEqualTo("data:image/png;base64,AAAA");
    }

    @Test
    void rendersUnaryGenerateContentResponse() {
        UnifiedResponse response = UnifiedResponse.chat("r1", "gemini-2.0-flash", 1700000000L,
                "stop", "你好世界", TokenUsage.of(10, 5));

        Map<String, Object> out = handler.renderUnary(response);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) out.get("candidates");
        assertThat(candidates).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> content = (Map<String, Object>) candidates.get(0).get("content");
        assertThat(content.get("role")).isEqualTo("model");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> parts = (List<Map<String, Object>>) content.get("parts");
        assertThat(parts.get(0).get("text")).isEqualTo("你好世界");
        assertThat(candidates.get(0).get("finishReason")).isEqualTo("STOP");

        @SuppressWarnings("unchecked")
        Map<String, Object> usage = (Map<String, Object>) out.get("usageMetadata");
        assertThat(usage.get("promptTokenCount")).isEqualTo(10);
        assertThat(usage.get("candidatesTokenCount")).isEqualTo(5);
        assertThat(out.get("modelVersion")).isEqualTo("gemini-2.0-flash");
    }

    @Test
    void renderStreamEmitsFramesAndDone() {
        Flux<UnifiedStreamChunk> chunks = Flux.just(
                UnifiedStreamChunk.content("r1", "gemini-2.0-flash", 1L, "你"),
                UnifiedStreamChunk.reasoning("r1", "gemini-2.0-flash", 1L, "推理"),
                UnifiedStreamChunk.end("r1", "gemini-2.0-flash", 1L, "stop", TokenUsage.of(3, 2)));

        StepVerifier.create(handler.renderStream(chunks))
                .assertNext(frame -> assertThat(frame).contains("\"text\":\"你\""))
                .assertNext(frame -> assertThat(frame).contains("\"thought\":\"推理\""))
                .assertNext(frame -> assertThat(frame).contains("finishReason"))
                .assertNext(frame -> assertThat(frame).isEqualTo("data: [DONE]\n\n"))
                .verifyComplete();
    }

    @Test
    void renderErrorFrameUsesGeminiErrorShape() {
        String frame = handler.renderErrorFrame(new IllegalStateException("boom"));

        assertThat(frame).startsWith("data: ").contains("\"error\"");
        assertThat(frame).contains("\"message\":\"boom\"");
    }
}
