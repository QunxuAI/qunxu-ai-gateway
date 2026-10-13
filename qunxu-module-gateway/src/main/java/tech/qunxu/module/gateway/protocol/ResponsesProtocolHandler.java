package tech.qunxu.module.gateway.protocol;

import tech.qunxu.module.gateway.common.enums.Capability;
import tech.qunxu.module.gateway.common.enums.ProtocolType;
import tech.qunxu.module.gateway.common.exception.ErrorCode;
import tech.qunxu.module.gateway.common.exception.GatewayException;
import tech.qunxu.module.gateway.common.model.GenerationParams;
import tech.qunxu.module.gateway.common.model.TokenUsage;
import tech.qunxu.module.gateway.common.model.UnifiedMessage;
import tech.qunxu.module.gateway.common.model.UnifiedRequest;
import tech.qunxu.module.gateway.common.model.UnifiedResponse;
import tech.qunxu.module.gateway.common.model.UnifiedStreamChunk;
import tech.qunxu.module.gateway.common.util.JsonUtils;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * OpenAI Responses 协议处理器：{@code POST /v1/responses} 的双向翻译（含 SSE 流式事件）。
 *
 * <p>入向把 {@code instructions}/{@code input[]}（message / function_call_output / function_call /
 * reasoning 等条目）与 {@code tools}/{@code max_output_tokens} 归一为 Unified IR；出向把 Unified
 * 响应/增量渲染为 {@code response} 对象与 {@code response.*} 事件序列（created → output_item.added →
 * content_part.delta* → output_item.done → completed），兼容 OpenAI Responses 客户端（P3 协议面扩展）。</p>
 *
 * <p>{@code reasoning} 条目视为协议状态（P1 协议感知保护），原样保留、不参与围栏/审计文本。</p>
 */
@Component
public class ResponsesProtocolHandler implements ProtocolHandler {

    private final ObjectMapper objectMapper;

    public ResponsesProtocolHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public ProtocolType protocol() {
        return ProtocolType.RESPONSES;
    }

    // ---- 入向：客户端 JSON -> Unified IR ----

    @Override
    public UnifiedRequest parseRequest(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new GatewayException(ErrorCode.BAD_REQUEST, "请求体必须为 JSON 对象");
        }
        String model = JsonUtils.text(body, "model");
        if (model == null || model.isBlank()) {
            throw new GatewayException(ErrorCode.BAD_REQUEST, "缺少必填字段 model");
        }
        boolean stream = JsonUtils.boolOr(body, "stream", false);
        List<UnifiedMessage> messages = parseInput(body);
        GenerationParams params = parseParams(body);
        List<UnifiedRequest.ToolDefinition> tools = parseTools(body.get("tools"));
        UnifiedRequest.ChatPayload chat = new UnifiedRequest.ChatPayload(messages, params, tools, null);
        return UnifiedRequest.chat(model, stream, chat);
    }

    private List<UnifiedMessage> parseInput(JsonNode body) {
        List<UnifiedMessage> messages = new ArrayList<>();
        String instructions = parseInstructions(body.get("instructions"));
        if (instructions != null && !instructions.isBlank()) {
            messages.add(UnifiedMessage.system(instructions));
        }
        JsonNode input = body.get("input");
        if (input == null || input.isNull()) {
            return messages;
        }
        if (input.isTextual()) {
            messages.add(UnifiedMessage.user(input.asText()));
            return messages;
        }
        if (input.isArray()) {
            for (JsonNode item : input) {
                if (item == null || item.isNull()) {
                    continue;
                }
                if (item.isTextual()) {
                    messages.add(UnifiedMessage.user(item.asText()));
                    continue;
                }
                if (!item.isObject()) {
                    continue;
                }
                messages.add(parseInputItem(item));
            }
        }
        return messages;
    }

    private UnifiedMessage parseInputItem(JsonNode item) {
        String type = JsonUtils.text(item, "type");
        String role = JsonUtils.text(item, "role");
        // reasoning 条目：协议状态（思维摘要/加密续传），P1 排除围栏、原样保留
        if ("reasoning".equals(type)) {
            return UnifiedMessage.assistant("").asProtocolProtected();
        }
        // function_call_output：工具结果 → tool 消息
        if ("function_call_output".equals(type)) {
            String output = JsonUtils.text(item, "output");
            return new UnifiedMessage("tool", output != null ? output : "",
                    null, null, JsonUtils.text(item, "call_id"), null, false);
        }
        // function_call：模型发起的工具调用（续传场景）→ assistant 消息 + toolCalls
        if ("function_call".equals(type)) {
            UnifiedMessage.ToolFunction fn = new UnifiedMessage.ToolFunction(
                    JsonUtils.text(item, "name"), JsonUtils.text(item, "arguments"));
            UnifiedMessage.ToolCall call = new UnifiedMessage.ToolCall(
                    JsonUtils.text(item, "call_id"), "function", fn);
            return new UnifiedMessage("assistant", null, null, null, null, List.of(call), false);
        }
        // 其余 message 形态：{role, content}（content 为字符串或 content 条目数组）
        if (role == null || role.isBlank()) {
            role = "user";
        }
        String normalized = "assistant".equals(role) ? "assistant" : "user";
        ParsedContent parsed = parseContent(item.get("content"));
        return new UnifiedMessage(normalized, parsed.content(), parsed.parts(), null, null, null, false);
    }

    private String parseInstructions(JsonNode instructions) {
        if (instructions == null || instructions.isNull()) {
            return null;
        }
        if (instructions.isTextual()) {
            return instructions.asText();
        }
        if (instructions.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode block : instructions) {
                String text = JsonUtils.text(block, "text");
                if (text != null) {
                    if (!sb.isEmpty()) {
                        sb.append('\n');
                    }
                    sb.append(text);
                }
            }
            return sb.isEmpty() ? null : sb.toString();
        }
        return null;
    }

    private record ParsedContent(String content, List<UnifiedMessage.ContentPart> parts) {
    }

    /** content 条目数组：input_text / output_text / text / input_image 等归一为文本或图片片段。 */
    private ParsedContent parseContent(JsonNode contentNode) {
        if (contentNode == null || contentNode.isNull()) {
            return new ParsedContent(null, null);
        }
        if (contentNode.isTextual()) {
            return new ParsedContent(contentNode.asText(), null);
        }
        if (contentNode.isArray()) {
            List<UnifiedMessage.ContentPart> parts = new ArrayList<>();
            StringBuilder textOnly = new StringBuilder();
            boolean hasNonText = false;
            for (JsonNode block : contentNode) {
                if (block == null || block.isNull()) {
                    continue;
                }
                if (block.isTextual()) {
                    if (!textOnly.isEmpty()) {
                        textOnly.append('\n');
                    }
                    textOnly.append(block.asText());
                    continue;
                }
                String type = JsonUtils.text(block, "type");
                if ("input_image".equals(type) || "output_image".equals(type)) {
                    hasNonText = true;
                    parts.add(UnifiedMessage.ContentPart.imageUrl(extractImageUrl(block)));
                } else {
                    String text = JsonUtils.text(block, "text");
                    if (text != null) {
                        if (!textOnly.isEmpty()) {
                            textOnly.append('\n');
                        }
                        textOnly.append(text);
                        parts.add(UnifiedMessage.ContentPart.text(text));
                    }
                }
            }
            if (!hasNonText) {
                return new ParsedContent(textOnly.toString(), null);
            }
            return new ParsedContent(null, parts);
        }
        return new ParsedContent(contentNode.asText(), null);
    }

    private String extractImageUrl(JsonNode block) {
        // {image_url: "..."} 或 {type:"input_image", image_url: "..."}（data URL / http URL）
        JsonNode imageUrl = block.get("image_url");
        if (imageUrl == null || imageUrl.isNull()) {
            return null;
        }
        return imageUrl.isTextual() ? imageUrl.asText() : JsonUtils.text(imageUrl, "url");
    }

    private GenerationParams parseParams(JsonNode body) {
        return new GenerationParams(
                JsonUtils.doubleVal(body, "temperature"),
                JsonUtils.doubleVal(body, "top_p"),
                JsonUtils.integer(body, "max_output_tokens"),
                null, null, null,
                JsonUtils.integer(body, "seed"),
                null,
                body.hasNonNull("reasoning") ? true : null,
                Map.of());
    }

    private List<UnifiedRequest.ToolDefinition> parseTools(JsonNode toolsNode) {
        if (toolsNode == null || !toolsNode.isArray()) {
            return null;
        }
        List<UnifiedRequest.ToolDefinition> tools = new ArrayList<>();
        for (JsonNode tool : toolsNode) {
            String type = JsonUtils.text(tool, "type");
            if (!"function".equals(type)) {
                continue;
            }
            tools.add(new UnifiedRequest.ToolDefinition("function",
                    new UnifiedRequest.ToolDefinition.FunctionDef(
                            JsonUtils.text(tool, "name"),
                            JsonUtils.text(tool, "description"),
                            toMap(tool.get("parameters")))));
        }
        return tools.isEmpty() ? null : tools;
    }

    // ---- 出向：Unified -> 客户端 JSON ----

    @Override
    public Map<String, Object> renderUnary(UnifiedResponse response) {
        String id = response != null && response.id() != null ? response.id() : "resp_" + UUID.randomUUID();
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("id", id);
        root.put("object", "response");
        root.put("created_at", response != null ? response.created() : Instant.now().getEpochSecond());
        root.put("status", "completed");
        root.put("model", response != null ? response.model() : null);
        root.put("output", renderOutputItems(response));
        root.put("output_text", outputText(response));
        if (response != null && response.usage() != null && !response.usage().isEmpty()) {
            root.put("usage", usageMap(response.usage()));
        }
        return root;
    }

    private List<Map<String, Object>> renderOutputItems(UnifiedResponse response) {
        List<Map<String, Object>> items = new ArrayList<>();
        if (response != null && response.choices() != null) {
            int index = 0;
            for (UnifiedResponse.Choice choice : response.choices()) {
                UnifiedMessage message = choice.message();
                String text = message != null ? message.textContent() : "";
                Map<String, Object> content = new LinkedHashMap<>();
                content.put("type", "output_text");
                content.put("text", text != null ? text : "");
                content.put("annotations", List.of());
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", "msg_" + UUID.randomUUID());
                item.put("type", "message");
                item.put("status", "completed");
                item.put("role", "assistant");
                item.put("content", List.of(content));
                items.add(item);
                index++;
            }
        }
        return items;
    }

    private String outputText(UnifiedResponse response) {
        if (response == null || response.choices() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (UnifiedResponse.Choice choice : response.choices()) {
            if (choice != null) {
                sb.append(choice.text()).append('\n');
            }
        }
        return sb.toString().trim();
    }

    @Override
    public Flux<String> renderStream(Flux<UnifiedStreamChunk> chunks) {
        AtomicReference<String> id = new AtomicReference<>("resp_" + UUID.randomUUID());
        AtomicReference<String> model = new AtomicReference<>();
        AtomicReference<TokenUsage> usage = new AtomicReference<>();
        AtomicBoolean started = new AtomicBoolean(false);
        AtomicReference<String> itemId = new AtomicReference<>("msg_" + UUID.randomUUID());
        AtomicReference<StringBuilder> contentAcc = new AtomicReference<>(new StringBuilder());

        Flux<String> body = chunks.concatMap(chunk -> {
            if (chunk.id() != null) {
                id.set(chunk.id());
            }
            if (chunk.model() != null) {
                model.set(chunk.model());
            }
            if (chunk.usage() != null) {
                usage.set(chunk.usage());
            }
            List<String> frames = new ArrayList<>();
            if (started.compareAndSet(false, true)) {
                itemId.set("msg_" + UUID.randomUUID());
                frames.add(responseCreatedFrame(id.get(), model.get()));
                frames.add(outputItemAddedFrame(id.get(), itemId.get()));
                frames.add(contentPartAddedFrame(id.get(), itemId.get()));
            }
            if (chunk.deltaContent() != null) {
                contentAcc.get().append(chunk.deltaContent());
                frames.add(contentPartDeltaFrame(id.get(), itemId.get(), chunk.deltaContent()));
            }
            if (chunk.deltaReasoning() != null) {
                frames.add(reasoningDeltaFrame(id.get(), itemId.get(), chunk.deltaReasoning()));
            }
            return Flux.fromIterable(frames);
        });

        Flux<String> tail = Flux.defer(() -> {
            List<String> frames = new ArrayList<>();
            if (started.compareAndSet(false, true)) {
                itemId.set("msg_" + UUID.randomUUID());
                frames.add(responseCreatedFrame(id.get(), model.get()));
                frames.add(outputItemAddedFrame(id.get(), itemId.get()));
                frames.add(contentPartAddedFrame(id.get(), itemId.get()));
            }
            frames.add(contentPartDoneFrame(id.get(), itemId.get(), contentAcc.get().toString()));
            frames.add(outputItemDoneFrame(id.get(), itemId.get()));
            frames.add(completedFrame(id.get(), model.get(), usage.get()));
            return Flux.fromIterable(frames);
        });

        return Flux.concat(body, tail).onErrorResume(e -> Flux.just(renderErrorFrame(e)));
    }

    private String responseCreatedFrame(String id, String model) {
        Map<String, Object> response = json(
                "id", id, "object", "response",
                "created_at", Instant.now().getEpochSecond(),
                "status", "in_progress", "model", model,
                "output", List.of());
        return eventFrame("response.created", json("type", "response.created", "response", response));
    }

    private String outputItemAddedFrame(String id, String itemId) {
        Map<String, Object> item = json("id", itemId, "type", "message", "status", "in_progress", "role", "assistant", "content", List.of());
        return eventFrame("response.output_item.added", json(
                "type", "response.output_item.added", "output_index", 0, "item", item));
    }

    private String contentPartAddedFrame(String id, String itemId) {
        Map<String, Object> part = json("type", "output_text", "text", "", "annotations", List.of());
        return eventFrame("response.content_part.added", json(
                "type", "response.content_part.added", "item_id", itemId,
                "output_index", 0, "content_index", 0, "part", part));
    }

    private String contentPartDeltaFrame(String id, String itemId, String delta) {
        Map<String, Object> part = json("type", "output_text", "text", delta);
        return eventFrame("response.content_part.delta", json(
                "type", "response.content_part.delta", "item_id", itemId,
                "output_index", 0, "content_index", 0, "delta", part));
    }

    private String reasoningDeltaFrame(String id, String itemId, String delta) {
        Map<String, Object> part = json("type", "summary_text", "text", delta);
        return eventFrame("response.reasoning_summary_text.delta", json(
                "type", "response.reasoning_summary_text.delta", "item_id", itemId,
                "output_index", 0, "content_index", 0, "delta", part));
    }

    private String contentPartDoneFrame(String id, String itemId, String text) {
        Map<String, Object> part = json("type", "output_text", "text", text, "annotations", List.of());
        return eventFrame("response.content_part.done", json(
                "type", "response.content_part.done", "item_id", itemId,
                "output_index", 0, "content_index", 0, "part", part));
    }

    private String outputItemDoneFrame(String id, String itemId) {
        Map<String, Object> item = json("id", itemId, "type", "message", "status", "completed", "role", "assistant", "content", List.of());
        return eventFrame("response.output_item.done", json(
                "type", "response.output_item.done", "output_index", 0, "item", item));
    }

    private String completedFrame(String id, String model, TokenUsage usage) {
        Map<String, Object> response = json(
                "id", id, "object", "response",
                "created_at", Instant.now().getEpochSecond(),
                "status", "completed", "model", model,
                "output", List.of(),
                "usage", usage != null ? usageMap(usage) : Map.of());
        return eventFrame("response.completed", json("type", "response.completed", "response", response));
    }

    @Override
    public String renderErrorFrame(Throwable error) {
        ErrorCode code = error instanceof GatewayException ge ? ge.getErrorCode() : ErrorCode.PROVIDER_ERROR;
        String message = error.getMessage() != null ? error.getMessage() : code.defaultMessage();
        Map<String, Object> errorObj = json("type", "error", "code", code.code(), "message", message);
        return eventFrame("error", json("type", "error", "error", errorObj));
    }

    private String eventFrame(String event, Map<String, Object> data) {
        return "event: " + event + "\ndata: " + objectMapper.writeValueAsString(data) + "\n\n";
    }

    private Map<String, Object> usageMap(TokenUsage usage) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("input_tokens", nz(usage.promptTokens()));
        map.put("output_tokens", nz(usage.completionTokens()));
        map.put("total_tokens", nz(usage.totalTokens()));
        return map;
    }

    private static int nz(Integer value) {
        return value != null ? value : 0;
    }

    /** 构建允许 null 值的有序 JSON 对象（{@link Map#of} 不接受 null）。 */
    private static Map<String, Object> json(Object... keyValuePairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValuePairs.length; i += 2) {
            map.put((String) keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            return null;
        }
        return objectMapper.treeToValue(node, Map.class);
    }
}
