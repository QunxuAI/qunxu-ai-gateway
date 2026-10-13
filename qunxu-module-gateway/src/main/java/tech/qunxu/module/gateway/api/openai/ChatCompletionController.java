package tech.qunxu.module.gateway.api.openai;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tech.qunxu.module.gateway.api.GatewayInvoker;
import tech.qunxu.module.gateway.protocol.OpenAiProtocolHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * OpenAI 兼容上游控制器。M2 覆盖 {@code POST /v1/chat/completions}（含 SSE 流式）；
 * 嵌入/语音/图片/重排/模型列表等端点在 M6/M8 补充。
 *
 * <p>控制器仅负责绑定原始请求体并委派 {@link GatewayInvoker}，协议翻译由 {@link OpenAiProtocolHandler} 完成。
 * 返回 {@code Mono<Void>}，由 Spring MVC 响应式返回值处理异步写回。</p>
 */
@RestController
@RequestMapping("/v1")
public class ChatCompletionController {

    static final String PATH = "/v1/chat/completions";
    static final String ENDPOINT = "chat.completions";

    private final GatewayInvoker invoker;
    private final OpenAiProtocolHandler handler;

    public ChatCompletionController(GatewayInvoker invoker, OpenAiProtocolHandler handler) {
        this.invoker = invoker;
        this.handler = handler;
    }

    @PostMapping("/chat/completions")
    public Mono<Void> chatCompletions(@RequestBody(required = false) String body,
                                      HttpServletRequest request, HttpServletResponse response) {
        return invoker.invoke(request, response, handler, PATH, ENDPOINT, body);
    }
}
