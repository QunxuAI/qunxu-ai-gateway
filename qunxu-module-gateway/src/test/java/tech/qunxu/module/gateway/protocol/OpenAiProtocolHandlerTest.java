package tech.qunxu.module.gateway.protocol;

import tech.qunxu.module.gateway.common.enums.Capability;
import tech.qunxu.module.gateway.common.exception.GatewayException;
import tech.qunxu.module.gateway.common.model.GenerationParams;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OpenAI 协议处理器单元测试：入向解析（文本/多模态/参数/工具）与出向渲染（unary JSON / SSE 帧）。
 */
class OpenAiProtocolHandlerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final OpenAiProtocolHandler handler = new OpenAiProtocolHandler(mapper);

    @Test
    void parsesTextChatRequestWithParamsAndTools() {
        String json = """
                {
                  "model": "deepseek-chat",
                  "stream": true,
                  "temperature": 0.5,
                  "max_tokens": 100,
                  "stop": ["END"],
                  "messages": [
                    {"role": "user", "content": "你好"},
                    {"role": "assistant", "content": "hi"}
                  ],
                  "tools": [
                    {"type": "function", "function": {"name": "get_weather", "description": "查询天气", "parameters": {"type": "object"}}}
                  ]
                }
                """;
        UnifiedRequest request = handler.parseRequest(mapper.readTree(json));

        assertThat(request.capability()).isEqualTo(Capability.CHAT);
        assertThat(request.model()).isEqualTo("deepseek-chat");
        assertThat(request.stream()).isTrue();
        assertThat(request.chat().messages()).hasSize(2);
        assertThat(request.chat().messages().get(0).role()).isEqualTo("user");
        assertThat(request.chat().messages().get(0).content()).isEqualTo("你好");

        GenerationParams params = request.chat().params();
        assertThat(params.temperature()).isEqualTo(0.5);
        assertThat(params.maxTokens()).isEqualTo(100);
        assertThat(params.stop()).containsExactly("END");

        assertThat(request.chat().tools()).hasSize(1);
        assertThat(request.chat().tools().get(0).function().name()).isEqualTo("get_weather");
        assertThat(request.chat().tools().get(0).function().parameters()).containsEntry("type", "object");
    }

    @Test
    void parsesMultimodalContentParts() {
        String json = """
                {
                  "model": "qwen-vl-plus",
                  "messages": [
                    {"role": "user", "content": [
                      {"type": "text", "text": "描述这张图"},
                      {"type": "image_url", "image_url": {"url": "http://example.com/a.png"}}
                    ]}
                  ]
                }
                """;
        UnifiedRequest request = handler.parseRequest(mapper.readTree(json));
        UnifiedMessage message = request.chat().messages().get(0);

        assertThat(message.contentParts()).hasSize(2);
        assertThat(message.contentParts().get(0).type()).isEqualTo("text");
        assertThat(message.contentParts().get(0).text()).isEqualTo("描述这张图");
        assertThat(message.contentParts().get(1).type()).isEqualTo("image_url");
        assertThat(message.contentParts().get(1).imageUrl()).isEqualTo("http://example.com/a.png");
    }

    @Test
    void rendersUnaryChatCompletion() {
        UnifiedResponse response = UnifiedResponse.chat("chatcmpl-1", "deepseek-chat", 1700000000L,
                "stop", "你好，世界", TokenUsage.of(10, 5));

        Map<String, Object> out = handler.renderUnary(response);

        assertThat(out.get("object")).isEqualTo("chat.completion");
        assertThat(out.get("id")).isEqualTo("chatcmpl-1");
        assertThat(out.get("model")).isEqualTo("deepseek-chat");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> choices = (List<Map<String, Object>>) out.get("choices");
        assertThat(choices).hasSize(1);
        assertThat(choices.get(0).get("finish_reason")).isEqualTo("stop");
        @SuppressWarnings("unchecked")
        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
        assertThat(message.get("role")).isEqualTo("assistant");
        assertThat(message.get("content")).isEqualTo("你好，世界");

        @SuppressWarnings("unchecked")
        Map<String, Object> usage = (Map<String, Object>) out.get("usage");
        assertThat(usage.get("prompt_tokens")).isEqualTo(10);
        assertThat(usage.get("completion_tokens")).isEqualTo(5);
        assertThat(usage.get("total_tokens")).isEqualTo(15);
    }

    @Test
    void rendersStreamFramesWithDoneSentinel() {
        Flux<UnifiedStreamChunk> chunks = Flux.just(
                UnifiedStreamChunk.role("chatcmpl-1", "deepseek-chat", 1L, "assistant"),
                UnifiedStreamChunk.content("chatcmpl-1", "deepseek-chat", 1L, "你好"),
                UnifiedStreamChunk.end("chatcmpl-1", "deepseek-chat", 1L, "stop", TokenUsage.of(3, 2)));

        StepVerifier.create(handler.renderStream(chunks))
                .assertNext(frame -> assertThat(frame).startsWith("data: ").contains("\"role\":\"assistant\""))
                .assertNext(frame -> assertThat(frame).contains("\"content\":\"你好\""))
                .assertNext(frame -> assertThat(frame).contains("\"finish_reason\":\"stop\""))
                .assertNext(frame -> assertThat(frame).isEqualTo("data: [DONE]\n\n"))
                .verifyComplete();
    }

    // ---- M6 多模态：入向解析 ----

    @Test
    void parsesEmbeddingRequestWithArrayInput() {
        String json = """
                {"model":"text-embedding-v3","input":["你好","世界"],"encoding_format":"float","dimensions":1024}
                """;
        UnifiedRequest request = handler.parseRequest(mapper.readTree(json), Capability.EMBEDDING);

        assertThat(request.capability()).isEqualTo(Capability.EMBEDDING);
        assertThat(request.model()).isEqualTo("text-embedding-v3");
        assertThat(request.embedding().input()).containsExactly("你好", "世界");
        assertThat(request.embedding().encodingFormat()).isEqualTo("float");
        assertThat(request.embedding().dimensions()).isEqualTo(1024);
    }

    @Test
    void parsesEmbeddingRequestWithStringInput() {
        String json = """
                {"model":"text-embedding-v3","input":"单条文本"}
                """;
        UnifiedRequest request = handler.parseRequest(mapper.readTree(json), Capability.EMBEDDING);
        assertThat(request.embedding().input()).containsExactly("单条文本");
    }

    @Test
    void parsesRerankRequestWithStringDocuments() {
        String json = """
                {"model":"gte-rerank","query":"查询","documents":["文档A","文档B"],"top_n":2,"return_documents":true}
                """;
        UnifiedRequest request = handler.parseRequest(mapper.readTree(json), Capability.RERANK);

        assertThat(request.capability()).isEqualTo(Capability.RERANK);
        assertThat(request.rerank().query()).isEqualTo("查询");
        assertThat(request.rerank().documents()).containsExactly("文档A", "文档B");
        assertThat(request.rerank().topN()).isEqualTo(2);
        assertThat(request.rerank().returnDocuments()).isTrue();
    }

    @Test
    void parsesRerankRequestWithObjectDocuments() {
        String json = """
                {"model":"gte-rerank","query":"查询","documents":[{"text":"文档A"},{"text":"文档B"}]}
                """;
        UnifiedRequest request = handler.parseRequest(mapper.readTree(json), Capability.RERANK);
        assertThat(request.rerank().documents()).containsExactly("文档A", "文档B");
    }

    @Test
    void parsesTtsRequest() {
        String json = """
                {"model":"cosyvoice-v1","input":"你好世界","voice":"longxiaochun","response_format":"mp3","speed":1.0}
                """;
        UnifiedRequest request = handler.parseRequest(mapper.readTree(json), Capability.TTS);

        assertThat(request.capability()).isEqualTo(Capability.TTS);
        assertThat(request.tts().input()).isEqualTo("你好世界");
        assertThat(request.tts().voice()).isEqualTo("longxiaochun");
        assertThat(request.tts().responseFormat()).isEqualTo("mp3");
        assertThat(request.tts().speed()).isEqualTo(1.0);
    }

    @Test
    void parsesImageRequest() {
        String json = """
                {"model":"wanx-v1","prompt":"一只猫","size":"1024*1024","n":2,"response_format":"url"}
                """;
        UnifiedRequest request = handler.parseRequest(mapper.readTree(json), Capability.IMAGE);

        assertThat(request.capability()).isEqualTo(Capability.IMAGE);
        assertThat(request.image().prompt()).isEqualTo("一只猫");
        assertThat(request.image().size()).isEqualTo("1024*1024");
        assertThat(request.image().n()).isEqualTo(2);
        assertThat(request.image().responseFormat()).isEqualTo("url");
    }

    @Test
    void parseRequestWithSttCapabilityThrowsBadRequest() {
        assertThatThrownBy(() -> handler.parseRequest(mapper.readTree("{\"model\":\"paraformer\"}"), Capability.STT))
                .isInstanceOf(GatewayException.class);
    }

    // ---- M6 多模态：出向渲染 ----

    @Test
    void rendersEmbeddingResponse() {
        UnifiedResponse response = new UnifiedResponse(Capability.EMBEDDING, null, "text-embedding-v3", 1700000000L,
                null, List.of(new UnifiedResponse.Embedding(0, List.of(0.1f, 0.2f, 0.3f))),
                null, null, null, TokenUsage.of(5, 0));

        Map<String, Object> out = handler.renderUnary(response);

        assertThat(out.get("object")).isEqualTo("list");
        assertThat(out.get("model")).isEqualTo("text-embedding-v3");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) out.get("data");
        assertThat(data).hasSize(1);
        assertThat(data.get(0).get("object")).isEqualTo("embedding");
        assertThat(data.get(0).get("index")).isEqualTo(0);
        @SuppressWarnings("unchecked")
        List<Float> embedding = (List<Float>) data.get(0).get("embedding");
        assertThat(embedding).containsExactly(0.1f, 0.2f, 0.3f);
        @SuppressWarnings("unchecked")
        Map<String, Object> usage = (Map<String, Object>) out.get("usage");
        assertThat(usage.get("prompt_tokens")).isEqualTo(5);
        assertThat(usage.get("total_tokens")).isEqualTo(5);
    }

    @Test
    void rendersRerankResponse() {
        UnifiedResponse response = new UnifiedResponse(Capability.RERANK, "rerank-1", "gte-rerank", 1700000000L,
                null, null, List.of(new UnifiedResponse.RerankResult(1, 0.95, "文档B"),
                        new UnifiedResponse.RerankResult(0, 0.31, null)), null, null,
                new TokenUsage(0, 0, 36));

        Map<String, Object> out = handler.renderUnary(response);

        assertThat(out.get("id")).isEqualTo("rerank-1");
        assertThat(out.get("model")).isEqualTo("gte-rerank");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) out.get("results");
        assertThat(results).hasSize(2);
        assertThat(results.get(0).get("index")).isEqualTo(1);
        assertThat(results.get(0).get("relevance_score")).isEqualTo(0.95);
        @SuppressWarnings("unchecked")
        Map<String, Object> doc = (Map<String, Object>) results.get(0).get("document");
        assertThat(doc.get("text")).isEqualTo("文档B");
        assertThat(results.get(1).get("document")).isNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> usage = (Map<String, Object>) out.get("usage");
        assertThat(usage.get("total_tokens")).isEqualTo(36);
    }

    @Test
    void rendersImageResponse() {
        UnifiedResponse response = new UnifiedResponse(Capability.IMAGE, null, "wanx-v1", 1700000000L,
                null, null, null, List.of(new UnifiedResponse.ImageResult("https://img/1.png", null, null),
                        new UnifiedResponse.ImageResult(null, "BASE64DATA", "修订提示")), null, null);

        Map<String, Object> out = handler.renderUnary(response);

        assertThat(out.get("created")).isEqualTo(1700000000L);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) out.get("data");
        assertThat(data).hasSize(2);
        assertThat(data.get(0).get("url")).isEqualTo("https://img/1.png");
        assertThat(data.get(1).get("b64_json")).isEqualTo("BASE64DATA");
        assertThat(data.get(1).get("revised_prompt")).isEqualTo("修订提示");
    }

    @Test
    void rendersTranscriptionResponse() {
        UnifiedResponse response = new UnifiedResponse(Capability.STT, null, "paraformer", 1700000000L,
                null, null, null, null, new UnifiedResponse.AudioResult(null, "识别文本", "application/json"), null);

        Map<String, Object> out = handler.renderUnary(response);
        assertThat(out.get("text")).isEqualTo("识别文本");
    }

    @Test
    void renderStreamEmitsErrorFrameThenDone() {
        Flux<UnifiedStreamChunk> chunks = Flux.concat(
                Flux.just(UnifiedStreamChunk.content("id", "m", 1L, "part")),
                Flux.error(new RuntimeException("boom")));

        StepVerifier.create(handler.renderStream(chunks))
                .assertNext(frame -> assertThat(frame).contains("\"content\":\"part\""))
                .assertNext(frame -> assertThat(frame).startsWith("data: ").contains("error"))
                .assertNext(frame -> assertThat(frame).isEqualTo("data: [DONE]\n\n"))
                .verifyComplete();
    }

    @Test
    void marksAssistantReasoningMessagesAsProtocolProtected() throws Exception {
        String json = """
                {
                  "model": "deepseek-r1",
                  "messages": [
                    {"role": "user", "content": "1+1=?"},
                    {"role": "assistant", "content": "等于2", "reasoning_content": "先算个位，再算十位"},
                    {"role": "user", "content": "再问一个"}
                  ]
                }
                """;
        UnifiedRequest request = handler.parseRequest(mapper.readTree(json));

        List<UnifiedMessage> messages = request.chat().messages();
        assertThat(messages).hasSize(3);
        // 携带 reasoning_content 的 assistant 消息被标记为协议状态，围栏/审计排除其文本
        assertThat(messages.get(1).protocolProtected()).isTrue();
        assertThat(messages.get(0).protocolProtected()).isFalse();
        assertThat(messages.get(2).protocolProtected()).isFalse();
        // 受保护消息不参与围栏文本聚合
        assertThat(request.chat().fullText())
                .contains("1+1=?")
                .contains("再问一个")
                .doesNotContain("先算个位");
    }
}
