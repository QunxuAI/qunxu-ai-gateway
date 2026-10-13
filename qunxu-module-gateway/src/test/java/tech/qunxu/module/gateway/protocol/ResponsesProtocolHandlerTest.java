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
 * OpenAI Responses 协议处理器单元测试（P3）：/v1/responses 入向解析（message/string/function_call_output/
 * reasoning 条目）与 response 出向渲染（unary / SSE 事件）。
 */
class ResponsesProtocolHandlerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ResponsesProtocolHandler handler = new ResponsesProtocolHandler(mapper);

    @Test
    void parsesResponsesRequestWithMixedInputItems() throws Exception {
        String json = """
                {
                  "model": "gpt-4o",
                  "instructions": "你是助手",
                  "max_output_tokens": 100,
                  "input": [
                    "直接字符串",
                    {"role": "user", "content": [{"type": "input_text", "text": "你好"}]},
                    {"type": "function_call_output", "call_id": "call_1", "output": "晴 25度"},
                    {"type": "function_call", "call_id": "call_2", "name": "get_weather", "arguments": "{\\"city\\":\\"上海\\"}"},
                    {"type": "reasoning", "summary": [{"type": "summary_text", "text": "推理摘要"}]}
                  ]
                }
                """;
        UnifiedRequest request = handler.parseRequest(mapper.readTree(json));

        assertThat(request.capability()).isEqualTo(Capability.CHAT);
        assertThat(request.model()).isEqualTo("gpt-4o");
        List<UnifiedMessage> messages = request.chat().messages();
        // system(instructions) + string + user + tool(function_call_output) + assistant(function_call) + reasoning
        assertThat(messages).hasSize(6);
        assertThat(messages.get(0).role()).isEqualTo("system");
        assertThat(messages.get(0).content()).isEqualTo("你是助手");
        assertThat(messages.get(1).role()).isEqualTo("user");
        assertThat(messages.get(2).content()).isEqualTo("你好");
        // function_call_output → tool 消息
        assertThat(messages.get(3).role()).isEqualTo("tool");
        assertThat(messages.get(3).toolCallId()).isEqualTo("call_1");
        assertThat(messages.get(3).content()).isEqualTo("晴 25度");
        // function_call → assistant 消息 + toolCalls
        assertThat(messages.get(4).role()).isEqualTo("assistant");
        assertThat(messages.get(4).toolCalls()).hasSize(1);
        assertThat(messages.get(4).toolCalls().get(0).function().name()).isEqualTo("get_weather");
        // reasoning 条目：协议状态，围栏排除
        assertThat(messages.get(5).protocolProtected()).isTrue();

        assertThat(request.chat().params().maxTokens()).isEqualTo(100);
    }

    @Test
    void parsesStringAndArrayInstructions() throws Exception {
        String json = """
                {
                  "model": "gpt-4o",
                  "instructions": [{"type": "input_text", "text": "第一行"}, {"type": "input_text", "text": "第二行"}],
                  "input": "hi"
                }
                """;
        UnifiedRequest request = handler.parseRequest(mapper.readTree(json));

        assertThat(request.chat().messages().get(0).role()).isEqualTo("system");
        assertThat(request.chat().messages().get(0).content()).isEqualTo("第一行\n第二行");
        assertThat(request.chat().messages().get(1).content()).isEqualTo("hi");
    }

    @Test
    void rendersUnaryResponse() {
        UnifiedResponse response = UnifiedResponse.chat("resp_1", "gpt-4o", 1700000000L,
                "stop", "你好", TokenUsage.of(8, 3));

        Map<String, Object> out = handler.renderUnary(response);

        assertThat(out.get("object")).isEqualTo("response");
        assertThat(out.get("status")).isEqualTo("completed");
        assertThat(out.get("output_text")).isEqualTo("你好");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> output = (List<Map<String, Object>>) out.get("output");
        assertThat(output).hasSize(1);
        assertThat(output.get(0).get("type")).isEqualTo("message");
        assertThat(output.get(0).get("role")).isEqualTo("assistant");

        @SuppressWarnings("unchecked")
        Map<String, Object> usage = (Map<String, Object>) out.get("usage");
        assertThat(usage.get("input_tokens")).isEqualTo(8);
        assertThat(usage.get("output_tokens")).isEqualTo(3);
    }

    @Test
    void renderStreamEmitsFullEventSequence() {
        Flux<UnifiedStreamChunk> chunks = Flux.just(
                UnifiedStreamChunk.role("resp_1", "gpt-4o", 1L, "assistant"),
                UnifiedStreamChunk.content("resp_1", "gpt-4o", 1L, "你"),
                UnifiedStreamChunk.reasoning("resp_1", "gpt-4o", 1L, "推理"),
                UnifiedStreamChunk.end("resp_1", "gpt-4o", 1L, "stop", TokenUsage.of(3, 2)));

        StepVerifier.create(handler.renderStream(chunks))
                .assertNext(f -> assertThat(f).startsWith("event: response.created"))
                .assertNext(f -> assertThat(f).startsWith("event: response.output_item.added"))
                .assertNext(f -> assertThat(f).startsWith("event: response.content_part.added"))
                .assertNext(f -> {
                    assertThat(f).startsWith("event: response.content_part.delta");
                    assertThat(f).contains("\"text\":\"你\"");
                })
                .assertNext(f -> assertThat(f).startsWith("event: response.reasoning_summary_text.delta"))
                .assertNext(f -> assertThat(f).startsWith("event: response.content_part.done"))
                .assertNext(f -> assertThat(f).startsWith("event: response.output_item.done"))
                .assertNext(f -> {
                    assertThat(f).startsWith("event: response.completed");
                    assertThat(f).contains("\"status\":\"completed\"");
                })
                .verifyComplete();
    }

    @Test
    void renderStreamEmitsCompletedForEmptyStream() {
        StepVerifier.create(handler.renderStream(Flux.empty()))
                .assertNext(f -> assertThat(f).startsWith("event: response.created"))
                .assertNext(f -> assertThat(f).startsWith("event: response.output_item.added"))
                .assertNext(f -> assertThat(f).startsWith("event: response.content_part.added"))
                .assertNext(f -> assertThat(f).startsWith("event: response.content_part.done"))
                .assertNext(f -> assertThat(f).startsWith("event: response.output_item.done"))
                .assertNext(f -> assertThat(f).startsWith("event: response.completed"))
                .verifyComplete();
    }

    @Test
    void renderErrorFrameUsesResponsesErrorShape() {
        String frame = handler.renderErrorFrame(new IllegalStateException("boom"));

        assertThat(frame).startsWith("event: error");
        assertThat(frame).contains("\"message\":\"boom\"");
    }
}
