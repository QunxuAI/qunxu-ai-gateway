package tech.qunxu.module.gateway.api.anthropic;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tech.qunxu.module.gateway.api.GatewayInvoker;
import tech.qunxu.module.gateway.protocol.AnthropicProtocolHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Anthropic 兼容上游控制器：{@code POST /v1/messages}（含 SSE 流式事件）。
 *
 * <p>与 OpenAI 控制器共用 {@link GatewayInvoker} 编排，协议差异由 {@link AnthropicProtocolHandler} 承担。</p>
 */
@RestController
public class MessageController {

    static final String PATH = "/v1/messages";
    static final String ENDPOINT = "messages";

    private final GatewayInvoker invoker;
    private final AnthropicProtocolHandler handler;

    public MessageController(GatewayInvoker invoker, AnthropicProtocolHandler handler) {
        this.invoker = invoker;
        this.handler = handler;
    }

    @PostMapping("/v1/messages")
    public Mono<Void> messages(@RequestBody(required = false) String body,
                               HttpServletRequest request, HttpServletResponse response) {
        return invoker.invoke(request, response, handler, PATH, ENDPOINT, body);
    }
}
