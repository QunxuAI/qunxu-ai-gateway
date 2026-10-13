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
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gemini 协议处理器：{@code /v1beta/models/{model}:generateContent} 与 {@code :streamGenerateContent}
 * 的双向翻译（SSE 流式，兼容 Google AI SDK / Vertex 风格）。
 *
 * <p>入向把 {@code contents}/{@code systemInstruction}/{@code generationConfig}/{@code tools} 归一为 Unified IR
 * （{@code model} 由控制器从 URL 路径注入请求体）；出向把 Unified 响应/增量渲染为
 * {@code GenerateContentResponse}，流式以 {@code data: {...}} SSE 帧收尾 {@code [DONE]}。
 * 路由到下游仍复用统一流水线：Gemini 入向可对接任意 OpenAI 兼容上游（P3 协议面扩展）。</p>
 */
@Component
public class GeminiProtocolHandler implements ProtocolHandler {

    private static final String DONE_FRAME = "data: [DONE]\n\n";

    private final ObjectMapper objectMapper;

    public GeminiProtocolHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public ProtocolType protocol() {
        return ProtocolType.GEMINI;
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
        UnifiedRequest.ChatPayload chat = new UnifiedRequest.ChatPayload(
                messages, parseParams(body), parseTools(body.get("tools")), null);
        return UnifiedRequest.chat(model, stream, chat);
    }

    private List<UnifiedMessage> parseMessages(JsonNode body) {
        List<UnifiedMessage> messages = new ArrayList<>();
        String system = parseSystemInstruction(body.get("systemInstruction"));
        if (system != null && !system.isBlank()) {
            messages.add(UnifiedMessage.system(system));
        }
        JsonNode contents = body.get("contents");
        if (contents != null && contents.isArray()) {
            for (JsonNode node : contents) {
                String role = JsonUtils.text(node, "role");
                if (role == null || role.isBlank()) {
                    role = "user";
                }
                String normalized = "model".equals(role) ? "assistant" : "user";
                ParsedContent parsed = parseParts(node.get("parts"));
                messages.add(new UnifiedMessage(normalized, parsed.content(), parsed.parts(),
                        null, null, null, false));
            }
        }
        return messages;
    }

    private String parseSystemInstruction(JsonNode instruction) {
        if (instruction == null || instruction.isNull()) {
            return null;
        }
        ParsedContent parsed = parseParts(instruction.get("parts"));
        return parsed.content();
    }

    private record ParsedContent(String content, List<UnifiedMessage.ContentPart> parts) {
    }

    /** Gemini parts：text / inlineData(图片) / fileData。纯文本块归一为字符串。 */
    private ParsedContent parseParts(JsonNode partsNode) {
        if (partsNode == null || partsNode.isNull()) {
            return new ParsedContent(null, null);
        }
        if (partsNode.isArray()) {
            List<UnifiedMessage.ContentPart> parts = new ArrayList<>();
            StringBuilder textOnly = new StringBuilder();
            boolean hasNonText = false;
            for (JsonNode part : partsNode) {
                String text = JsonUtils.text(part, "text");
                if (text != null) {
                    if (!textOnly.isEmpty()) {
                        textOnly.append('\n');
                    }
                    textOnly.append(text);
                    parts.add(UnifiedMessage.ContentPart.text(text));
                    continue;
                }
                JsonNode inlineData = part.get("inlineData");
                if (inlineData != null && inlineData.isObject()) {
                    hasNonText = true;
                    String mimeType = JsonUtils.text(inlineData, "mimeType");
                    String data = JsonUtils.text(inlineData, "data");
                    parts.add(UnifiedMessage.ContentPart.imageUrl(
                            "data:" + (mimeType != null ? mimeType : "image/png") + ";base64,"
                                    + (data != null ? data : "")));
                }
            }
            if (!hasNonText) {
                return new ParsedContent(textOnly.toString(), null);
            }
            return new ParsedContent(null, parts);
        }
        return new ParsedContent(partsNode.asText(), null);
    }

    private GenerationParams parseParams(JsonNode body) {
        Map<String, Object> extra = new LinkedHashMap<>();
        JsonNode cfg = body.get("generationConfig");
        if (cfg != null && cfg.isObject()) {
            Integer maxOutput = JsonUtils.integer(cfg, "maxOutputTokens");
            List<String> stops = parseStopSequences(cfg.get("stopSequences"));
            if (stops != null && !stops.isEmpty()) {
                extra.put("stop", stops);
            }
            return new GenerationParams(
                    JsonUtils.doubleVal(cfg, "temperature"),
                    JsonUtils.doubleVal(cfg, "topP"),
                    maxOutput,
                    stops,
                    null, null,
                    JsonUtils.integer(cfg, "seed"),
                    null,
                    cfg.hasNonNull("thinkingConfig") ? true : null,
                    extra);
        }
        return GenerationParams.defaults();
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

    private List<UnifiedRequest.ToolDefinition> parseTools(JsonNode toolsNode) {
        if (toolsNode == null || !toolsNode.isArray()) {
            return null;
        }
        List<UnifiedRequest.ToolDefinition> tools = new ArrayList<>();
        for (JsonNode tool : toolsNode) {
            JsonNode functions = tool.get("functionDeclarations");
            if (functions == null || !functions.isArray()) {
                continue;
            }
            for (JsonNode fn : functions) {
                tools.add(new UnifiedRequest.ToolDefinition("function",
                        new UnifiedRequest.ToolDefinition.FunctionDef(
                                JsonUtils.text(fn, "name"),
                                JsonUtils.text(fn, "description"),
                                toMap(fn.get("parameters")))));
            }
        }
        return tools.isEmpty() ? null : tools;
    }

    // ---- 出向：Unified -> 客户端 JSON ----

    @Override
    public Map<String, Object> renderUnary(UnifiedResponse response) {
        Map<String, Object> root = new LinkedHashMap<>();
        List<Map<String, Object>> candidates = new ArrayList<>();
        if (response != null && response.choices() != null && !response.choices().isEmpty()) {
            UnifiedResponse.Choice choice = response.choices().get(0);
            UnifiedMessage message = choice.message();
            String text = message != null ? message.textContent() : "";
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("role", "model");
            content.put("parts", List.of(Map.of("text", text != null ? text : "")));
            Map<String, Object> candidate = new LinkedHashMap<>();
            candidate.put("content", content);
            candidate.put("finishReason", mapFinishReason(choice.finishReason()));
            candidate.put("index", choice.index());
            candidates.add(candidate);
        } else {
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("role", "model");
            content.put("parts", List.of(Map.of("text", "")));
            Map<String, Object> candidate = new LinkedHashMap<>();
            candidate.put("content", content);
            candidate.put("finishReason", "STOP");
            candidate.put("index", 0);
            candidates.add(candidate);
        }
        root.put("candidates", candidates);
        if (response != null && response.usage() != null && !response.usage().isEmpty()) {
            root.put("usageMetadata", usageMetadata(response.usage()));
        }
        if (response != null && response.model() != null) {
            root.put("modelVersion", response.model());
        }
        return root;
    }

    @Override
    public Flux<String> renderStream(Flux<UnifiedStreamChunk> chunks) {
        return chunks.map(this::streamFrame)
                .concatWith(Mono.fromSupplier(() -> DONE_FRAME))
                .onErrorResume(e -> Flux.just(renderErrorFrame(e), DONE_FRAME));
    }

    /** 每个增量渲染为一个 GenerateContentResponse 的 SSE 帧；推理增量映射为 {@code thought} part。 */
    private String streamFrame(UnifiedStreamChunk chunk) {
        List<Map<String, Object>> parts = new ArrayList<>();
        if (chunk.deltaContent() != null) {
            parts.add(Map.of("text", chunk.deltaContent()));
        }
        if (chunk.deltaReasoning() != null) {
            parts.add(Map.of("thought", chunk.deltaReasoning()));
        }
        if (parts.isEmpty() && chunk.finishReason() == null && !chunk.done()) {
            return "data: {}\n\n";
        }
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("role", "model");
        content.put("parts", parts);
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("content", content);
        if (chunk.finishReason() != null) {
            candidate.put("finishReason", mapFinishReason(chunk.finishReason()));
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("candidates", List.of(candidate));
        if (chunk.usage() != null && !chunk.usage().isEmpty()) {
            root.put("usageMetadata", usageMetadata(chunk.usage()));
        }
        return "data: " + objectMapper.writeValueAsString(root) + "\n\n";
    }

    @Override
    public String renderErrorFrame(Throwable error) {
        ErrorCode code = error instanceof GatewayException ge ? ge.getErrorCode() : ErrorCode.PROVIDER_ERROR;
        String message = error.getMessage() != null ? error.getMessage() : code.defaultMessage();
        Map<String, Object> errorObj = new LinkedHashMap<>();
        errorObj.put("code", 400);
        errorObj.put("message", message);
        errorObj.put("status", mapErrorStatus(code));
        return "data: " + objectMapper.writeValueAsString(Map.of("error", errorObj)) + "\n\n";
    }

    private Map<String, Object> usageMetadata(TokenUsage usage) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("promptTokenCount", nz(usage.promptTokens()));
        meta.put("candidatesTokenCount", nz(usage.completionTokens()));
        meta.put("totalTokenCount", nz(usage.totalTokens()));
        return meta;
    }

    private String mapFinishReason(String finishReason) {
        if (finishReason == null) {
            return "STOP";
        }
        return switch (finishReason) {
            case "length" -> "MAX_TOKENS";
            case "content_filter" -> "SAFETY";
            default -> "STOP";
        };
    }

    private String mapErrorStatus(ErrorCode code) {
        return switch (code) {
            case UNAUTHORIZED, AUTH_EXPIRED -> "UNAUTHENTICATED";
            case FORBIDDEN -> "PERMISSION_DENIED";
            case RATE_LIMITED -> "RESOURCE_EXHAUSTED";
            case MODEL_NOT_FOUND, BAD_REQUEST -> "INVALID_ARGUMENT";
            default -> "INTERNAL";
        };
    }

    private static int nz(Integer value) {
        return value != null ? value : 0;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            return null;
        }
        return objectMapper.treeToValue(node, Map.class);
    }
}
