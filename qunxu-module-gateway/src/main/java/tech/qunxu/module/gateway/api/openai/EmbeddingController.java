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
 * OpenAI 兼容嵌入端点：{@code POST /v1/embeddings}。
 *
 * <p>控制器仅绑定原始请求体并委派 {@link GatewayInvoker}，显式声明 {@link Capability#EMBEDDING} 以选择
 * 对应的解析/渲染分支；下游由支持 embedding 的适配器（OpenAI 兼容 / DashScope）承接。</p>
 */
@RestController
@RequestMapping("/v1")
public class EmbeddingController {

    static final String PATH = "/v1/embeddings";
    static final String ENDPOINT = "embeddings";

    private final GatewayInvoker invoker;
    private final OpenAiProtocolHandler handler;

    public EmbeddingController(GatewayInvoker invoker, OpenAiProtocolHandler handler) {
        this.invoker = invoker;
        this.handler = handler;
    }

    @PostMapping("/embeddings")
    public Mono<Void> embeddings(@RequestBody(required = false) String body,
                                 HttpServletRequest request, HttpServletResponse response) {
        return invoker.invoke(request, response, handler, PATH, ENDPOINT, Capability.EMBEDDING, body);
    }
}
