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
 * OpenAI 兼容图片生成端点：{@code POST /v1/images/generations}。
 *
 * <p>出向渲染 {@code {created, data:[{url} | {b64_json}]}}。下游由支持 image 的适配器
 * （如 DashScope 通义万相 wanx 异步任务）承接，异步任务在适配器内轮询至完成后归一为 Unified 响应。</p>
 */
@RestController
@RequestMapping("/v1")
public class ImageController {

    static final String PATH = "/v1/images/generations";
    static final String ENDPOINT = "images.generations";

    private final GatewayInvoker invoker;
    private final OpenAiProtocolHandler handler;

    public ImageController(GatewayInvoker invoker, OpenAiProtocolHandler handler) {
        this.invoker = invoker;
        this.handler = handler;
    }

    @PostMapping("/images/generations")
    public Mono<Void> generate(@RequestBody(required = false) String body,
                               HttpServletRequest request, HttpServletResponse response) {
        return invoker.invoke(request, response, handler, PATH, ENDPOINT, Capability.IMAGE, body);
    }
}
