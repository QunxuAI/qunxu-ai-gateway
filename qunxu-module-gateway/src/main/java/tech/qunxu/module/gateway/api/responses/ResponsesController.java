package tech.qunxu.module.gateway.api.responses;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tech.qunxu.module.gateway.api.GatewayInvoker;
import tech.qunxu.module.gateway.protocol.ResponsesProtocolHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * OpenAI Responses 兼容控制器（P3 协议面扩展）：{@code POST /v1/responses}（含 SSE 流式）。
 *
 * <p>控制器仅绑定原始请求体并委派 {@link GatewayInvoker}，协议翻译由 {@link ResponsesProtocolHandler} 完成。</p>
 */
@RestController
@RequestMapping("/v1")
public class ResponsesController {

    static final String PATH = "/v1/responses";
    static final String ENDPOINT = "responses";

    private final GatewayInvoker invoker;
    private final ResponsesProtocolHandler handler;

    public ResponsesController(GatewayInvoker invoker, ResponsesProtocolHandler handler) {
        this.invoker = invoker;
        this.handler = handler;
    }

    @PostMapping("/responses")
    public Mono<Void> responses(@RequestBody(required = false) String body,
                                HttpServletRequest request, HttpServletResponse response) {
        return invoker.invoke(request, response, handler, PATH, ENDPOINT, body);
    }
}
