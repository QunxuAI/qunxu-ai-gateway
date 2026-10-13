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

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 协议处理器：{@code /v1/chat/completions} 的双向翻译（含 SSE 流式）。
 *
 * <p>入向把 OpenAI 请求解析为 Unified IR（支持文本与多模态 content、生成参数、工具定义）；
 * 出向把 Unified 响应/增量渲染为 {@code chat.completion} 与 {@code chat.completion.chunk}，
 * 流式以 {@code data: [DONE]} 收尾。DeepSeek-R1 等的 {@code reasoning_content} 原样透传。</p>
 */
@Component
public class OpenAiProtocolHandler implements ProtocolHandler {

    private static final String OBJECT_COMPLETION = "chat.completion";
    private static final String OBJECT_CHUNK = "chat.completion.chunk";
    private static final String DONE_FRAME = "data: [DONE]\n\n";

    private final ObjectMapper objectMapper;

    public OpenAiProtocolHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public ProtocolType protocol() {
        return ProtocolType.OPENAI;
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
        List<UnifiedMessage> messages = parseMessages(body.get("messages"));
        GenerationParams params = parseParams(body);
        List<UnifiedRequest.ToolDefinition> tools = parseTools(body.get("tools"));
        String toolChoice = parseToolChoice(body.get("tool_choice"));
        UnifiedRequest.ChatPayload chat = new UnifiedRequest.ChatPayload(messages, params, tools, toolChoice);
        return UnifiedRequest.chat(model, stream, chat);
    }

    /**
     * 按能力分派解析：对话走 {@link #parseRequest(JsonNode)}，其余多模态端点各自解析。
     * STT 为 multipart 上传，不经此 JSON 入口（由控制器直接构造 Unified IR）。
     */
    @Override
    public UnifiedRequest parseRequest(JsonNode body, Capability capability) {
        if (capability == null || capability == Capability.CHAT) {
            return parseRequest(body);
        }
        requireObject(body);
        return switch (capability) {
            case EMBEDDING -> parseEmbedding(body);
            case RERANK -> parseRerank(body);
            case TTS -> parseTts(body);
            case IMAGE -> parseImage(body);
            case STT -> throw new GatewayException(ErrorCode.BAD_REQUEST,
                    "STT 请通过 multipart/form-data 提交音频文件");
            case CHAT -> parseRequest(body);
        };
    }

    private void requireObject(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new GatewayException(ErrorCode.BAD_REQUEST, "请求体必须为 JSON 对象");
        }
    }

    private String requireModel(JsonNode body) {
        String model = JsonUtils.text(body, "model");
        if (model == null || model.isBlank()) {
            throw new GatewayException(ErrorCode.BAD_REQUEST, "缺少必填字段 model");
        }
        return model;
    }

    /** {@code POST /v1/embeddings}：{@code input} 可为单字符串或字符串数组。 */
    private UnifiedRequest parseEmbedding(JsonNode body) {
        String model = requireModel(body);
        JsonNode inputNode = body.get("input");
        List<String> input = new ArrayList<>();
        if (inputNode != null && inputNode.isArray()) {
            for (JsonNode item : inputNode) {
                if (item.isTextual()) {
                    input.add(item.asText());
                } else if (item != null && !item.isNull()) {
                    input.add(item.asText());
                }
            }
        } else if (inputNode != null && !inputNode.isNull()) {
            input.add(inputNode.asText());
        }
        if (input.isEmpty()) {
            throw new GatewayException(ErrorCode.BAD_REQUEST, "缺少必填字段 input");
        }
        UnifiedRequest.EmbeddingPayload payload = new UnifiedRequest.EmbeddingPayload(
                input, JsonUtils.text(body, "encoding_format"), JsonUtils.integer(body, "dimensions"));
        return UnifiedRequest.embedding(model, payload);
    }

    /** {@code POST /v1/rerank}：{@code documents} 可为字符串数组或 {@code {text}} 对象数组。 */
    private UnifiedRequest parseRerank(JsonNode body) {
        String model = requireModel(body);
        String query = JsonUtils.text(body, "query");
        if (query == null || query.isBlank()) {
            throw new GatewayException(ErrorCode.BAD_REQUEST, "缺少必填字段 query");
        }
        JsonNode docsNode = body.get("documents");
        List<String> documents = new ArrayList<>();
        if (docsNode != null && docsNode.isArray()) {
            for (JsonNode doc : docsNode) {
                if (doc.isTextual()) {
                    documents.add(doc.asText());
                } else if (doc != null && doc.isObject()) {
                    documents.add(JsonUtils.text(doc, "text"));
                }
            }
        }
        if (documents.isEmpty()) {
            throw new GatewayException(ErrorCode.BAD_REQUEST, "缺少必填字段 documents");
        }
        UnifiedRequest.RerankPayload payload = new UnifiedRequest.RerankPayload(
                query, documents, JsonUtils.integer(body, "top_n"), JsonUtils.bool(body, "return_documents"));
        return UnifiedRequest.rerank(model, payload);
    }

    /** {@code POST /v1/audio/speech}（TTS）。 */
    private UnifiedRequest parseTts(JsonNode body) {
        String model = requireModel(body);
        String input = JsonUtils.text(body, "input");
        if (input == null || input.isBlank()) {
            throw new GatewayException(ErrorCode.BAD_REQUEST, "缺少必填字段 input");
        }
        UnifiedRequest.TtsPayload payload = new UnifiedRequest.TtsPayload(
                input, JsonUtils.text(body, "voice"), JsonUtils.text(body, "response_format"),
                JsonUtils.doubleVal(body, "speed"));
        return UnifiedRequest.tts(model, payload);
    }

    /** {@code POST /v1/images/generations}。 */
    private UnifiedRequest parseImage(JsonNode body) {
        String model = requireModel(body);
        String prompt = JsonUtils.text(body, "prompt");
        if (prompt == null || prompt.isBlank()) {
            throw new GatewayException(ErrorCode.BAD_REQUEST, "缺少必填字段 prompt");
        }
        UnifiedRequest.ImagePayload payload = new UnifiedRequest.ImagePayload(
                prompt, JsonUtils.text(body, "size"), JsonUtils.integer(body, "n"),
                JsonUtils.text(body, "response_format"), JsonUtils.text(body, "quality"),
                JsonUtils.text(body, "style"));
        return UnifiedRequest.image(model, false, payload);
    }

    private List<UnifiedMessage> parseMessages(JsonNode arrayNode) {
        List<UnifiedMessage> messages = new ArrayList<>();
        if (arrayNode == null || !arrayNode.isArray()) {
            return messages;
        }
        for (JsonNode node : arrayNode) {
            String role = JsonUtils.text(node, "role");
            if (role == null || role.isBlank()) {
                role = "user";
            }
            ParsedContent parsed = parseContent(node.get("content"));
            UnifiedMessage message = new UnifiedMessage(role, parsed.content(), parsed.parts(),
                    JsonUtils.text(node, "name"), JsonUtils.text(node, "tool_call_id"),
                    parseToolCalls(node.get("tool_calls")), false);
            // P1 协议感知保护：assistant 消息携带 reasoning_content / reasoning（思维链或加密续传数据）
            // 属协议状态，围栏排除、原样保留，避免改坏签名或续传密文
            if ("assistant".equals(role)
                    && (node.has("reasoning_content") || node.hasNonNull("reasoning"))) {
                message = message.asProtocolProtected();
            }
            messages.add(message);
        }
        return messages;
    }

    private record ParsedContent(String content, List<UnifiedMessage.ContentPart> parts) {
    }

    /** content 支持字符串（纯文本）与数组（多模态 text/image_url 片段）两种形态。 */
    private ParsedContent parseContent(JsonNode contentNode) {
        if (contentNode == null || contentNode.isNull()) {
            return new ParsedContent(null, null);
        }
        if (contentNode.isTextual()) {
            return new ParsedContent(contentNode.asText(), null);
        }
        if (contentNode.isArray()) {
            List<UnifiedMessage.ContentPart> parts = new ArrayList<>();
            for (JsonNode part : contentNode) {
                String type = JsonUtils.text(part, "type");
                if ("image_url".equals(type)) {
                    JsonNode imageUrl = part.get("image_url");
                    String url = null;
                    if (imageUrl != null) {
                        url = imageUrl.isTextual() ? imageUrl.asText() : JsonUtils.text(imageUrl, "url");
                    }
                    parts.add(UnifiedMessage.ContentPart.imageUrl(url));
                } else {
                    parts.add(UnifiedMessage.ContentPart.text(JsonUtils.text(part, "text")));
                }
            }
            return new ParsedContent(null, parts);
        }
        return new ParsedContent(contentNode.asText(), null);
    }

    private GenerationParams parseParams(JsonNode body) {
        Integer maxTokens = JsonUtils.integer(body, "max_tokens");
        if (maxTokens == null) {
            maxTokens = JsonUtils.integer(body, "max_completion_tokens");
        }
        return new GenerationParams(
                JsonUtils.doubleVal(body, "temperature"),
                JsonUtils.doubleVal(body, "top_p"),
                maxTokens,
                parseStop(body.get("stop")),
                JsonUtils.doubleVal(body, "frequency_penalty"),
                JsonUtils.doubleVal(body, "presence_penalty"),
                JsonUtils.integer(body, "seed"),
                parseResponseFormat(body.get("response_format")),
                JsonUtils.bool(body, "enable_thinking"),
                Map.of());
    }

    private List<String> parseStop(JsonNode stopNode) {
        if (stopNode == null || stopNode.isNull()) {
            return null;
        }
        if (stopNode.isTextual()) {
            return List.of(stopNode.asText());
        }
        if (stopNode.isArray()) {
            List<String> stops = new ArrayList<>();
            for (JsonNode item : stopNode) {
                if (item.isTextual()) {
                    stops.add(item.asText());
                }
            }
            return stops.isEmpty() ? null : stops;
        }
        return null;
    }

    private String parseResponseFormat(JsonNode formatNode) {
        if (formatNode == null || formatNode.isNull()) {
            return null;
        }
        if (formatNode.isTextual()) {
            return formatNode.asText();
        }
        return JsonUtils.text(formatNode, "type");
    }

    /** 仅支持字符串形态（auto/none/required）；指定函数的对象形态在 M6 完善。 */
    private String parseToolChoice(JsonNode toolChoiceNode) {
        if (toolChoiceNode != null && toolChoiceNode.isTextual()) {
            return toolChoiceNode.asText();
        }
        return null;
    }

    private List<UnifiedRequest.ToolDefinition> parseTools(JsonNode arrayNode) {
        if (arrayNode == null || !arrayNode.isArray()) {
            return null;
        }
        List<UnifiedRequest.ToolDefinition> tools = new ArrayList<>();
        for (JsonNode node : arrayNode) {
            String type = JsonUtils.text(node, "type");
            JsonNode function = node.path("function");
            tools.add(new UnifiedRequest.ToolDefinition(
                    type != null ? type : "function",
                    new UnifiedRequest.ToolDefinition.FunctionDef(
                            JsonUtils.text(function, "name"),
                            JsonUtils.text(function, "description"),
                            toMap(function.get("parameters")))));
        }
        return tools.isEmpty() ? null : tools;
    }

    private List<UnifiedMessage.ToolCall> parseToolCalls(JsonNode arrayNode) {
        if (arrayNode == null || !arrayNode.isArray()) {
            return null;
        }
        List<UnifiedMessage.ToolCall> calls = new ArrayList<>();
        for (JsonNode node : arrayNode) {
            String type = JsonUtils.text(node, "type");
            JsonNode function = node.path("function");
            calls.add(new UnifiedMessage.ToolCall(
                    JsonUtils.text(node, "id"),
                    type != null ? type : "function",
                    new UnifiedMessage.ToolFunction(JsonUtils.text(function, "name"), JsonUtils.text(function, "arguments"))));
        }
        return calls.isEmpty() ? null : calls;
    }

    // ---- 出向：Unified -> 客户端 JSON ----

    @Override
    public Map<String, Object> renderUnary(UnifiedResponse response) {
        Capability capability = response != null ? response.capability() : null;
        if (capability == null) {
            return renderChatCompletion(response);
        }
        return switch (capability) {
            case EMBEDDING -> renderEmbedding(response);
            case RERANK -> renderRerank(response);
            case IMAGE -> renderImage(response);
            case STT -> renderTranscription(response);
            case TTS, CHAT -> renderChatCompletion(response);
        };
    }

    /** {@code list} 对象：嵌入向量结果。 */
    private Map<String, Object> renderEmbedding(UnifiedResponse response) {
        List<Map<String, Object>> data = new ArrayList<>();
        if (response.embeddings() != null) {
            for (UnifiedResponse.Embedding e : response.embeddings()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("object", "embedding");
                item.put("index", e.index());
                item.put("embedding", e.embedding() != null ? e.embedding() : List.of());
                data.add(item);
            }
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("object", "list");
        root.put("data", data);
        root.put("model", response.model());
        if (response.usage() != null && !response.usage().isEmpty()) {
            Map<String, Object> usage = new LinkedHashMap<>();
            usage.put("prompt_tokens", nz(response.usage().promptTokens()));
            usage.put("total_tokens", nz(response.usage().totalTokens()));
            root.put("usage", usage);
        }
        return root;
    }

    /** 重排结果（Cohere / Jina 风格，为国内网关常用的 {@code /rerank} 形态）。 */
    private Map<String, Object> renderRerank(UnifiedResponse response) {
        List<Map<String, Object>> results = new ArrayList<>();
        if (response.rerankResults() != null) {
            for (UnifiedResponse.RerankResult r : response.rerankResults()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("index", r.index());
                item.put("relevance_score", r.relevanceScore());
                if (r.document() != null) {
                    item.put("document", Map.of("text", r.document()));
                }
                results.add(item);
            }
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("id", response.id());
        root.put("model", response.model());
        root.put("results", results);
        if (response.usage() != null && !response.usage().isEmpty()) {
            Map<String, Object> usage = new LinkedHashMap<>();
            usage.put("total_tokens", nz(response.usage().totalTokens()));
            root.put("usage", usage);
        }
        return root;
    }

    /** 图片生成结果：{@code url} 或 {@code b64_json} 二选一。 */
    private Map<String, Object> renderImage(UnifiedResponse response) {
        List<Map<String, Object>> data = new ArrayList<>();
        if (response.images() != null) {
            for (UnifiedResponse.ImageResult img : response.images()) {
                Map<String, Object> item = new LinkedHashMap<>();
                if (img.url() != null) {
                    item.put("url", img.url());
                }
                if (img.b64Json() != null) {
                    item.put("b64_json", img.b64Json());
                }
                if (img.revisedPrompt() != null) {
                    item.put("revised_prompt", img.revisedPrompt());
                }
                data.add(item);
            }
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("created", response.created());
        root.put("data", data);
        return root;
    }

    /** 语音识别（STT）结果：默认 {@code json} 形态返回 {@code text}。 */
    private Map<String, Object> renderTranscription(UnifiedResponse response) {
        Map<String, Object> root = new LinkedHashMap<>();
        String text = response.audio() != null ? response.audio().text() : null;
        root.put("text", text != null ? text : "");
        return root;
    }

    private Map<String, Object> renderChatCompletion(UnifiedResponse response) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("id", response != null ? response.id() : null);
        root.put("object", OBJECT_COMPLETION);
        root.put("created", response != null ? response.created() : Instant.now().getEpochSecond());
        root.put("model", response != null ? response.model() : null);

        List<Map<String, Object>> choices = new ArrayList<>();
        if (response != null && response.choices() != null) {
            for (UnifiedResponse.Choice choice : response.choices()) {
                UnifiedMessage message = choice.message();
                Map<String, Object> messageMap = new LinkedHashMap<>();
                messageMap.put("role", message != null && message.role() != null ? message.role() : "assistant");
                messageMap.put("content", message != null && message.content() != null ? message.content() : "");
                if (message != null && message.toolCalls() != null && !message.toolCalls().isEmpty()) {
                    messageMap.put("tool_calls", renderToolCalls(message.toolCalls()));
                }
                Map<String, Object> choiceMap = new LinkedHashMap<>();
                choiceMap.put("index", choice.index());
                choiceMap.put("message", messageMap);
                choiceMap.put("finish_reason", choice.finishReason());
                choices.add(choiceMap);
            }
        }
        root.put("choices", choices);
        if (response != null && response.usage() != null && !response.usage().isEmpty()) {
            root.put("usage", usageMap(response.usage()));
        }
        return root;
    }

    @Override
    public Flux<String> renderStream(Flux<UnifiedStreamChunk> chunks) {
        return chunks.map(this::chunkFrame)
                .concatWith(Mono.fromSupplier(() -> DONE_FRAME))
                .onErrorResume(e -> Flux.just(renderErrorFrame(e), DONE_FRAME));
    }

    private String chunkFrame(UnifiedStreamChunk chunk) {
        Map<String, Object> delta = new LinkedHashMap<>();
        if (chunk.role() != null) {
            delta.put("role", chunk.role());
        }
        if (chunk.deltaContent() != null) {
            delta.put("content", chunk.deltaContent());
        }
        if (chunk.deltaReasoning() != null) {
            delta.put("reasoning_content", chunk.deltaReasoning());
        }
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("delta", delta);
        choice.put("finish_reason", chunk.finishReason());

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("id", chunk.id());
        root.put("object", OBJECT_CHUNK);
        root.put("created", chunk.created());
        root.put("model", chunk.model());
        root.put("choices", List.of(choice));
        if (chunk.usage() != null) {
            root.put("usage", usageMap(chunk.usage()));
        }
        return "data: " + objectMapper.writeValueAsString(root) + "\n\n";
    }

    @Override
    public String renderErrorFrame(Throwable error) {
        ErrorCode code = error instanceof GatewayException ge ? ge.getErrorCode() : ErrorCode.PROVIDER_ERROR;
        String message = error.getMessage() != null ? error.getMessage() : code.defaultMessage();
        Map<String, Object> errorMap = new LinkedHashMap<>();
        errorMap.put("message", message);
        errorMap.put("type", code.code());
        errorMap.put("code", code.code());
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("error", errorMap);
        return "data: " + objectMapper.writeValueAsString(root) + "\n\n";
    }

    private List<Map<String, Object>> renderToolCalls(List<UnifiedMessage.ToolCall> toolCalls) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (UnifiedMessage.ToolCall call : toolCalls) {
            Map<String, Object> function = new LinkedHashMap<>();
            if (call.function() != null) {
                function.put("name", call.function().name());
                function.put("arguments", call.function().arguments());
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", call.id());
            item.put("type", call.type() != null ? call.type() : "function");
            item.put("function", function);
            result.add(item);
        }
        return result;
    }

    private Map<String, Object> usageMap(TokenUsage usage) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("prompt_tokens", usage.promptTokens() != null ? usage.promptTokens() : 0);
        map.put("completion_tokens", usage.completionTokens() != null ? usage.completionTokens() : 0);
        map.put("total_tokens", usage.totalTokens() != null ? usage.totalTokens() : 0);
        return map;
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
