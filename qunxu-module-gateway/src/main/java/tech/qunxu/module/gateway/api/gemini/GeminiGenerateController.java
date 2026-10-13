package tech.qunxu.module.gateway.api.gemini;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tech.qunxu.module.gateway.api.GatewayInvoker;
import tech.qunxu.module.gateway.common.enums.Capability;
import tech.qunxu.module.gateway.common.exception.ErrorCode;
import tech.qunxu.module.gateway.common.exception.GatewayException;
import tech.qunxu.module.gateway.protocol.GeminiProtocolHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Gemini 兼容控制器（P3 协议面扩展）：{@code POST /v1beta/models/{model}:generateContent} 与
 * {@code :streamGenerateContent}。
 *
 * <p>Gemini 的模型名位于 URL 路径而非请求体，控制器读取路径变量后<b>注入请求体</b>
 * （{@code model} + {@code stream} 由端点形态决定），再委托 {@link GatewayInvoker}，
 * 协议翻译由 {@link GeminiProtocolHandler} 完成。流式端点以 SSE 帧输出。</p>
 */
@RestController
@RequestMapping("/v1beta")
public class GeminiGenerateController {

    static final String ENDPOINT = "gemini.generateContent";

    private final GatewayInvoker invoker;
    private final GeminiProtocolHandler handler;
    private final ObjectMapper objectMapper;

    public GeminiGenerateController(GatewayInvoker invoker, GeminiProtocolHandler handler,
                                    ObjectMapper objectMapper) {
        this.invoker = invoker;
        this.handler = handler;
        this.objectMapper = objectMapper;
    }

    @PostMapping(value = "/models/{model}:generateContent")
    public Mono<Void> generateContent(@PathVariable String model,
                                      @RequestBody(required = false) String body,
                                      HttpServletRequest request, HttpServletResponse response) {
        String path = "/v1beta/models/" + model + ":generateContent";
        return invoker.invoke(request, response, handler, path, ENDPOINT, Capability.CHAT,
                withModel(body, model, false));
    }

    @PostMapping(value = "/models/{model}:streamGenerateContent")
    public Mono<Void> streamGenerateContent(@PathVariable String model,
                                            @RequestBody(required = false) String body,
                                            HttpServletRequest request, HttpServletResponse response) {
        String path = "/v1beta/models/" + model + ":streamGenerateContent";
        return invoker.invoke(request, response, handler, path, ENDPOINT, Capability.CHAT,
                withModel(body, model, true));
    }

    /** 把 URL 路径中的模型与端点决定的流式标记注入请求体（覆盖同名字段），序列化后交给编排器。 */
    private String withModel(String rawBody, String model, boolean stream) {
        JsonNode node;
        try {
            node = objectMapper.readTree(rawBody == null || rawBody.isBlank() ? "{}" : rawBody);
        } catch (RuntimeException e) {
            throw new GatewayException(ErrorCode.BAD_REQUEST, "请求体不是合法 JSON");
        }
        if (node == null || !node.isObject()) {
            throw new GatewayException(ErrorCode.BAD_REQUEST, "请求体必须为 JSON 对象");
        }
        ObjectNode obj = (ObjectNode) node;
        obj.put("model", model);
        obj.put("stream", stream);
        return obj.toString();
    }
}
