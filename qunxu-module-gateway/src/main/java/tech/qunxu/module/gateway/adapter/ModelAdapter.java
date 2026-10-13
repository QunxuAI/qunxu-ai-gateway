package tech.qunxu.module.gateway.adapter;

import tech.qunxu.module.gateway.common.enums.Capability;
import tech.qunxu.module.gateway.common.enums.ChannelProtocol;
import tech.qunxu.module.gateway.common.model.UnifiedRequest;
import tech.qunxu.module.gateway.common.model.UnifiedResponse;
import tech.qunxu.module.gateway.common.model.UnifiedStreamChunk;
import tech.qunxu.module.gateway.health.HealthResult;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Set;

/**
 * 模型适配器 SPI：把 Unified IR 翻译为某类下游协议的原生请求、调用、再把响应翻译回 Unified IR。
 *
 * <p>按 {@link ChannelProtocol} 归类（OpenAI 兼容 / DashScope 原生 / 讯飞 WS）。新增供应商 = 实现并注册，
 * 无需改动核心流水线。</p>
 */
public interface ModelAdapter {

    /** 该适配器处理的通道协议。 */
    ChannelProtocol protocol();

    /** 该适配器支持的能力集合。 */
    Set<Capability> capabilities();

    /** 是否支持指定能力。 */
    default boolean supports(Capability capability) {
        return capability != null && capabilities().contains(capability);
    }

    /** 流式调用，返回 Unified 增量流。 */
    Flux<UnifiedStreamChunk> invokeStream(UnifiedRequest request, ChannelRuntime runtime);

    /** 非流式调用，返回 Unified 响应。 */
    Mono<UnifiedResponse> invokeUnary(UnifiedRequest request, ChannelRuntime runtime);

    /** 主动健康探测（各适配器实现真实探测，默认返回未知）。 */
    default Mono<HealthResult> probe(ChannelRuntime runtime) {
        return Mono.just(HealthResult.unknown());
    }

    /**
     * 能力感知的主动健康探测：探测方式可随模型能力调整（如向量模型探 {@code /embeddings}、
     * 图片/语音模型探模型清单，而非一律发 chat 请求）。
     *
     * <p>默认退化为 {@link #probe(ChannelRuntime)}，未覆盖能力探测的适配器无需改动。</p>
     *
     * @param runtime    通道运行时
     * @param capability 逻辑模型能力；无法识别时为 {@code null}，适配器按 CHAT 兜底
     */
    default Mono<HealthResult> probe(ChannelRuntime runtime, Capability capability) {
        return probe(runtime);
    }
}
