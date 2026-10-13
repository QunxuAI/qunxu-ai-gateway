package tech.qunxu.module.gateway.api.openai;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tech.qunxu.module.gateway.api.GatewayInvoker;
import tech.qunxu.module.gateway.common.enums.Capability;
import tech.qunxu.module.gateway.protocol.OpenAiProtocolHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 重排端点：{@code POST /v1/rerank}（Cohere / Jina 风格，国内网关通用形态）。
 *
 * <p>入向解析 {@code query + documents} 为 Unified IR，出向渲染 {@code {results:[{index,relevance_score}]}}。
 * 下游由支持 rerank 的适配器（如 DashScope gte-rerank）承接。</p>
 */
@RestController
@RequestMapping("/v1")
public class RerankController {

    static final String PATH = "/v1/rerank";
    static final String ENDPOINT = "rerank";

    private final GatewayInvoker invoker;
    private final OpenAiProtocolHandler handler;

    public RerankController(GatewayInvoker invoker, OpenAiProtocolHandler handler) {
        this.invoker = invoker;
        this.handler = handler;
    }

    @PostMapping("/rerank")
    public Mono<Void> rerank(@RequestBody(required = false) String body,
                             HttpServletRequest request, HttpServletResponse response) {
        return invoker.invoke(request, response, handler, PATH, ENDPOINT, Capability.RERANK, body);
    }
}
