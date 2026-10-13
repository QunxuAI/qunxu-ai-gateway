package tech.qunxu.module.gateway.protocol;

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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Anthropic 协议处理器：{@code /v1/messages} 的双向翻译（含 SSE 流式事件）。
 *
 * <p>入向：顶层 {@code system}、content blocks（text/image）、{@code stop_sequences}、{@code tools}(input_schema)
 * 归一为 Unified IR。出向：非流式渲染 {@code message} 对象；流式合成 Anthropic 事件序列
 * {@code message_start → content_block_start → content_block_delta* → content_block_stop → message_delta → message_stop}。</p>
 *
 * <p>M2 覆盖文本对话；{@code tool_use/tool_result} 与 thinking 增量在 M6 完善。</p>
 */
@Component
public class AnthropicProtocolHandler implements ProtocolHandler {

    private final ObjectMapper objectMapper;

    public AnthropicProtocolHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public ProtocolType protocol() {
        return ProtocolType.ANTHROPIC;
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
        List<UnifiedMessage> messages = parseMessages(body);
        GenerationParams params = parseParams(body);
        List<UnifiedRequest.ToolDefinition> tools = parseTools(body.get("tools"));
        String toolChoice = parseToolChoice(body.get("tool_choice"));
        UnifiedRequest.ChatPayload chat = new UnifiedRequest.ChatPayload(messages, params, tools, toolChoice);
        return UnifiedRequest.chat(model, stream, chat);
    }

    private List<UnifiedMessage> parseMessages(JsonNode body) {
        List<UnifiedMessage> messages = new ArrayList<>();
        String system = parseSystem(body.get("system"));
        if (system != null && !system.isBlank()) {
            messages.add(UnifiedMessage.system(system));
        }
        JsonNode arrayNode = body.get("messages");
        if (arrayNode != null && arrayNode.isArray()) {
            for (JsonNode node : arrayNode) {
                String role = JsonUtils.text(node, "role");
                if (role == null || role.isBlank()) {
                    role = "user";
                }
                ParsedContent parsed = parseContent(node.get("content"));
                UnifiedMessage message = new UnifiedMessage(role, parsed.content(), parsed.parts(),
                        JsonUtils.text(node, "name"), null, null, false);
                // P1 协议感知保护：assistant 消息含签名思考块/隐藏思考数据（协议状态），围栏排除、原样保留
                if ("assistant".equals(role) && parsed.protectedBlocks()) {
                    message = message.asProtocolProtected();
                }
                messages.add(message);
            }
        }
        return messages;
    }

    private String parseSystem(JsonNode systemNode) {
        if (systemNode == null || systemNode.isNull()) {
            return null;
        }
        if (systemNode.isTextual()) {
            return systemNode.asText();
        }
        if (systemNode.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode block : systemNode) {
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

    private record ParsedContent(String content, List<UnifiedMessage.ContentPart> parts, boolean protectedBlocks) {
    }

    /** Anthropic content：字符串，或 blocks 数组（text / image / thinking）。纯文本块归一为字符串。 */
    private ParsedContent parseContent(JsonNode contentNode) {
        if (contentNode == null || contentNode.isNull()) {
            return new ParsedContent(null, null, false);
        }
        if (contentNode.isTextual()) {
            return new ParsedContent(contentNode.asText(), null, false);
        }
        if (contentNode.isArray()) {
            List<UnifiedMessage.ContentPart> parts = new ArrayList<>();
            StringBuilder textOnly = new StringBuilder();
            boolean hasNonText = false;
            boolean protectedBlocks = false;
            for (JsonNode block : contentNode) {
                String type = JsonUtils.text(block, "type");
                if ("image".equals(type)) {
                    hasNonText = true;
                    parts.add(UnifiedMessage.ContentPart.imageUrl(extractImageUrl(block.path("source"))));
                } else if ("thinking".equals(type)) {
                    // 思考块：带签名时整体为协议状态，原样保留（P1 协议感知保护）
                    if (JsonUtils.text(block, "signature") != null) {
                        protectedBlocks = true;
                    }
                    String thinking = JsonUtils.text(block, "thinking");
                    if (thinking != null) {
                        if (!textOnly.isEmpty()) {
                            textOnly.append('\n');
                        }
                        textOnly.append(thinking);
                    }
                    parts.add(UnifiedMessage.ContentPart.text(thinking));
                } else if ("redacted_thinking".equals(type)) {
                    // 隐藏思考数据（加密续传）：协议状态，原样保留
                    protectedBlocks = true;
                    String data = JsonUtils.text(block, "data");
                    if (data != null) {
                        if (!textOnly.isEmpty()) {
                            textOnly.append('\n');
                        }
                        textOnly.append(data);
                    }
                    parts.add(UnifiedMessage.ContentPart.text(data));
                } else {
                    String text = JsonUtils.text(block, "text");
                    if (text != null) {
                        if (!textOnly.isEmpty()) {
                            textOnly.append('\n');
                        }
                        textOnly.append(text);
                    }
                    parts.add(UnifiedMessage.ContentPart.text(text));
                }
            }
            if (!hasNonText) {
                return new ParsedContent(textOnly.toString(), null, protectedBlocks);
            }
            return new ParsedContent(null, parts, protectedBlocks);
        }
        return new ParsedContent(contentNode.asText(), null, false);
    }

    private String extractImageUrl(JsonNode source) {
        if (source == null || source.isNull()) {
            return null;
        }
        String type = JsonUtils.text(source, "type");
        if ("base64".equals(type)) {
            String mediaType = JsonUtils.text(source, "media_type");
            String data = JsonUtils.text(source, "data");
            return "data:" + (mediaType != null ? mediaType : "image/png") + ";base64," + (data != null ? data : "");
        }
        if ("url".equals(type)) {
            return JsonUtils.text(source, "url");
        }
        return null;
    }

    private GenerationParams parseParams(JsonNode body) {
        Map<String, Object> extra = new LinkedHashMap<>();
        Integer topK = JsonUtils.integer(body, "top_k");
        if (topK != null) {
            extra.put("top_k", topK);
        }
        return new GenerationParams(
                JsonUtils.doubleVal(body, "temperature"),
                JsonUtils.doubleVal(body, "top_p"),
                JsonUtils.integer(body, "max_tokens"),
                parseStopSequences(body.get("stop_sequences")),
                null, null, null, null, null,
                extra.isEmpty() ? Map.of() : extra);
    }

    private List<String> parseStopSequences(JsonNode node) {
        if (node == null || !node.isArray()) {
            return null;
        }
        List<String> stops = new ArrayList<>();
        for (JsonNode item : node) {
            if (item.isTextual()) {
                stops.add(item.asText());
            }
        }
        return stops.isEmpty() ? null : stops;
    }

    private List<UnifiedRequest.ToolDefinition> parseTools(JsonNode arrayNode) {
        if (arrayNode == null || !arrayNode.isArray()) {
            return null;
        }
        List<UnifiedRequest.ToolDefinition> tools = new ArrayList<>();
        for (JsonNode node : arrayNode) {
            tools.add(new UnifiedRequest.ToolDefinition("function",
                    new UnifiedRequest.ToolDefinition.FunctionDef(
                            JsonUtils.text(node, "name"),
                            JsonUtils.text(node, "description"),
                            toMap(node.get("input_schema")))));
        }
        return tools.isEmpty() ? null : tools;
    }

    private String parseToolChoice(JsonNode toolChoiceNode) {
        if (toolChoiceNode == null || toolChoiceNode.isNull()) {
            return null;
        }
        String type = JsonUtils.text(toolChoiceNode, "type");
        if (type == null) {
            return null;
        }
        return switch (type) {
            case "auto" -> "auto";
            case "any" -> "required";
            case "none" -> "none";
            default -> null;
        };
    }

    // ---- 出向：Unified -> 客户端 JSON ----

    @Override
    public Map<String, Object> renderUnary(UnifiedResponse response) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("id", response != null && response.id() != null ? response.id() : "msg_" + UUID.randomUUID());
        root.put("type", "message");
        root.put("role", "assistant");
        root.put("model", response != null ? response.model() : null);

        List<Map<String, Object>> content = new ArrayList<>();
        String stopReason = "end_turn";
        if (response != null && response.choices() != null && !response.choices().isEmpty()) {
            UnifiedResponse.Choice choice = response.choices().get(0);
            UnifiedMessage message = choice.message();
            String text = message != null ? message.textContent() : "";
            content.add(json("type", "text", "text", text != null ? text : ""));
            if (choice.finishReason() != null) {
                stopReason = mapStopReason(choice.finishReason());
            }
        } else {
            content.add(json("type", "text", "text", ""));
        }
        root.put("content", content);
        root.put("stop_reason", stopReason);
        root.put("stop_sequence", null);
        root.put("usage", response != null && response.usage() != null
                ? json("input_tokens", nz(response.usage().promptTokens()), "output_tokens", nz(response.usage().completionTokens()))
                : json("input_tokens", 0, "output_tokens", 0));
        return root;
    }

    @Override
    public Flux<String> renderStream(Flux<UnifiedStreamChunk> chunks) {
        AtomicReference<String> id = new AtomicReference<>();
        AtomicReference<String> model = new AtomicReference<>();
        AtomicReference<String> stopReason = new AtomicReference<>("end_turn");
        AtomicReference<TokenUsage> usage = new AtomicReference<>();
        AtomicBoolean started = new AtomicBoolean(false);

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
            if (chunk.finishReason() != null) {
                stopReason.set(mapStopReason(chunk.finishReason()));
            }
            List<String> frames = new ArrayList<>();
            if (started.compareAndSet(false, true)) {
                frames.add(messageStartFrame(id.get(), model.get(), usage.get()));
                frames.add(contentBlockStartFrame());
            }
            if (chunk.hasContent()) {
                frames.add(eventFrame("content_block_delta", json(
                        "type", "content_block_delta",
                        "index", 0,
                        "delta", json("type", "text_delta", "text", chunk.deltaContent()))));
            }
            return Flux.fromIterable(frames);
        });

        Flux<String> tail = Flux.defer(() -> {
            List<String> frames = new ArrayList<>();
            if (started.compareAndSet(false, true)) {
                // 空流：仍合成一个最小合法消息
                frames.add(messageStartFrame(id.get(), model.get(), usage.get()));
                frames.add(contentBlockStartFrame());
            }
            frames.add(eventFrame("content_block_stop", json("type", "content_block_stop", "index", 0)));
            frames.add(messageDeltaFrame(stopReason.get(), usage.get()));
            frames.add(eventFrame("message_stop", json("type", "message_stop")));
            return Flux.fromIterable(frames);
        });

        return Flux.concat(body, tail).onErrorResume(e -> Flux.just(renderErrorFrame(e)));
    }

    private String messageStartFrame(String id, String model, TokenUsage usage) {
        Map<String, Object> message = json(
                "id", id != null ? id : "msg_" + UUID.randomUUID(),
                "type", "message",
                "role", "assistant",
                "model", model,
                "content", List.of(),
                "stop_reason", null,
                "stop_sequence", null,
                "usage", json("input_tokens", usage != null ? nz(usage.promptTokens()) : 0, "output_tokens", 0));
        return eventFrame("message_start", json("type", "message_start", "message", message));
    }

    private String contentBlockStartFrame() {
        return eventFrame("content_block_start", json(
                "type", "content_block_start",
                "index", 0,
                "content_block", json("type", "text", "text", "")));
    }

    private String messageDeltaFrame(String stopReason, TokenUsage usage) {
        return eventFrame("message_delta", json(
                "type", "message_delta",
                "delta", json("stop_reason", stopReason, "stop_sequence", null),
                "usage", json("output_tokens", usage != null ? nz(usage.completionTokens()) : 0)));
    }

    @Override
    public String renderErrorFrame(Throwable error) {
        ErrorCode code = error instanceof GatewayException ge ? ge.getErrorCode() : ErrorCode.PROVIDER_ERROR;
        String message = error.getMessage() != null ? error.getMessage() : code.defaultMessage();
        return eventFrame("error", json("type", "error", "error", json("type", mapErrorType(code), "message", message)));
    }

    private String eventFrame(String event, Map<String, Object> data) {
        return "event: " + event + "\ndata: " + objectMapper.writeValueAsString(data) + "\n\n";
    }

    private String mapStopReason(String finishReason) {
        if (finishReason == null) {
            return "end_turn";
        }
        return switch (finishReason) {
            case "length" -> "max_tokens";
            case "tool_calls" -> "tool_use";
            case "content_filter" -> "end_turn";
            default -> "end_turn";
        };
    }

    private String mapErrorType(ErrorCode code) {
        return switch (code) {
            case BAD_REQUEST, GUARDRAIL_BLOCKED, CONTENT_BLOCKED, PAYLOAD_TOO_LARGE -> "invalid_request_error";
            case UNAUTHORIZED, AUTH_EXPIRED -> "authentication_error";
            case FORBIDDEN -> "permission_error";
            case RATE_LIMITED -> "rate_limit_error";
            case MODEL_NOT_FOUND -> "not_found_error";
            default -> "api_error";
        };
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
