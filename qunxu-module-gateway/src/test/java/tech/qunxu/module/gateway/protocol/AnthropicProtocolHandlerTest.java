package tech.qunxu.module.gateway.protocol;

import tech.qunxu.module.gateway.common.model.TokenUsage;
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
 * Anthropic 协议处理器单元测试：顶层 system 归一、content 解析、message 渲染与 SSE 事件序列合成。
 */
class AnthropicProtocolHandlerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AnthropicProtocolHandler handler = new AnthropicProtocolHandler(mapper);

    @Test
    void parsesSystemAndMessagesAndParams() {
        String json = """
                {
                  "model": "claude-3-5-sonnet",
                  "max_tokens": 128,
                  "temperature": 0.7,
                  "top_k": 5,
                  "stop_sequences": ["STOP"],
                  "system": "你是一个助手",
                  "messages": [{"role": "user", "content": "hi"}]
                }
                """;
        UnifiedRequest request = handler.parseRequest(mapper.readTree(json));

        assertThat(request.model()).isEqualTo("claude-3-5-sonnet");
        // system 被归一为首条 system 消息
        assertThat(request.chat().messages()).hasSize(2);
        assertThat(request.chat().messages().get(0).role()).isEqualTo("system");
        assertThat(request.chat().messages().get(0).content()).isEqualTo("你是一个助手");
        assertThat(request.chat().messages().get(1).role()).isEqualTo("user");

        assertThat(request.chat().params().maxTokens()).isEqualTo(128);
        assertThat(request.chat().params().temperature()).isEqualTo(0.7);
        assertThat(request.chat().params().stop()).containsExactly("STOP");
        assertThat(request.chat().params().extra()).containsEntry("top_k", 5);
    }

    @Test
    void parsesImageBlockToDataUrl() {
        String json = """
                {
                  "model": "claude-3-5-sonnet",
                  "max_tokens": 64,
                  "messages": [{"role": "user", "content": [
                    {"type": "text", "text": "看图"},
                    {"type": "image", "source": {"type": "base64", "media_type": "image/jpeg", "data": "AAAA"}}
                  ]}]
                }
                """;
        UnifiedRequest request = handler.parseRequest(mapper.readTree(json));
        var parts = request.chat().messages().get(0).contentParts();

        assertThat(parts).hasSize(2);
        assertThat(parts.get(1).type()).isEqualTo("image_url");
        assertThat(parts.get(1).imageUrl()).isEqualTo("data:image/jpeg;base64,AAAA");
    }

    @Test
    void marksSignedThinkingBlockAsProtocolProtected() throws Exception {
        String json = """
                {
                  "model": "claude-3-7-sonnet",
                  "max_tokens": 64,
                  "messages": [
                    {"role": "user", "content": "解题"},
                    {"role": "assistant", "content": [
                      {"type": "thinking", "thinking": "设未知数 x", "signature": "sig_abc123"},
                      {"type": "text", "text": "答案是 42"}
                    ]}
                  ]
                }
                """;
        UnifiedRequest request = handler.parseRequest(mapper.readTree(json));

        var messages = request.chat().messages();
        assertThat(messages).hasSize(2);
        // 带签名的思考块整体视为协议状态：围栏排除、原样保留，避免改坏签名导致上游 400
        assertThat(messages.get(1).protocolProtected()).isTrue();
        assertThat(messages.get(0).protocolProtected()).isFalse();
        assertThat(request.chat().fullText())
                .contains("解题")
                .doesNotContain("设未知数 x");
    }

    @Test
    void marksRedactedThinkingBlockAsProtocolProtected() throws Exception {
        String json = """
                {
                  "model": "claude-3-7-sonnet",
                  "max_tokens": 64,
                  "messages": [
                    {"role": "assistant", "content": [
                      {"type": "redacted_thinking", "data": "encrypted-data-xyz"}
                    ]}
                  ]
                }
                """;
        UnifiedRequest request = handler.parseRequest(mapper.readTree(json));

        assertThat(request.chat().messages().get(0).protocolProtected()).isTrue();
        assertThat(request.chat().fullText()).doesNotContain("encrypted-data-xyz");
    }

    @Test
    void doesNotMarkPlainAssistantTextAsProtected() throws Exception {
        String json = """
                {
                  "model": "claude-3-5-sonnet",
                  "max_tokens": 64,
                  "messages": [{"role": "assistant", "content": "普通回答"}]
                }
                """;
        UnifiedRequest request = handler.parseRequest(mapper.readTree(json));

        assertThat(request.chat().messages().get(0).protocolProtected()).isFalse();
        assertThat(request.chat().fullText()).contains("普通回答");
    }

    @Test
    void rendersUnaryMessage() {
        UnifiedResponse response = UnifiedResponse.chat("msg_1", "claude-3-5-sonnet", 1L,
                "stop", "Hello", TokenUsage.of(8, 3));

        Map<String, Object> out = handler.renderUnary(response);

        assertThat(out.get("type")).isEqualTo("message");
        assertThat(out.get("role")).isEqualTo("assistant");
        assertThat(out.get("stop_reason")).isEqualTo("end_turn");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) out.get("content");
        assertThat(content).hasSize(1);
        assertThat(content.get(0).get("type")).isEqualTo("text");
        assertThat(content.get(0).get("text")).isEqualTo("Hello");

        @SuppressWarnings("unchecked")
        Map<String, Object> usage = (Map<String, Object>) out.get("usage");
        assertThat(usage.get("input_tokens")).isEqualTo(8);
        assertThat(usage.get("output_tokens")).isEqualTo(3);
    }

    @Test
    void rendersFullStreamEventSequence() {
        Flux<UnifiedStreamChunk> chunks = Flux.just(
                UnifiedStreamChunk.role("msg_1", "claude-3-5-sonnet", 1L, "assistant"),
                UnifiedStreamChunk.content("msg_1", "claude-3-5-sonnet", 1L, "Hi"),
                UnifiedStreamChunk.end("msg_1", "claude-3-5-sonnet", 1L, "stop", TokenUsage.of(5, 2)));

        StepVerifier.create(handler.renderStream(chunks))
                .assertNext(frame -> assertThat(frame).startsWith("event: message_start\n"))
                .assertNext(frame -> assertThat(frame).startsWith("event: content_block_start\n"))
                .assertNext(frame -> assertThat(frame).startsWith("event: content_block_delta\n").contains("\"text\":\"Hi\""))
                .assertNext(frame -> assertThat(frame).startsWith("event: content_block_stop\n"))
                .assertNext(frame -> assertThat(frame).startsWith("event: message_delta\n").contains("end_turn"))
                .assertNext(frame -> assertThat(frame).startsWith("event: message_stop\n"))
                .verifyComplete();
    }

    @Test
    void rendersMinimalSequenceForEmptyStream() {
        StepVerifier.create(handler.renderStream(Flux.empty()))
                .assertNext(frame -> assertThat(frame).startsWith("event: message_start\n"))
                .assertNext(frame -> assertThat(frame).startsWith("event: content_block_start\n"))
                .assertNext(frame -> assertThat(frame).startsWith("event: content_block_stop\n"))
                .assertNext(frame -> assertThat(frame).startsWith("event: message_delta\n"))
                .assertNext(frame -> assertThat(frame).startsWith("event: message_stop\n"))
                .verifyComplete();
    }
}
