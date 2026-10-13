package tech.qunxu.module.gateway.adapter;

import tech.qunxu.module.gateway.common.enums.ChannelProtocol;
import tech.qunxu.module.gateway.common.exception.ErrorCode;
import tech.qunxu.module.gateway.common.exception.GatewayException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 适配器注册表：按 {@link ChannelProtocol} 索引 {@link ModelAdapter}。
 *
 * <p>启动时收集所有适配器 Bean，路由选中通道后据此取得对应协议的适配器。新增供应商协议 = 实现并注册新适配器。</p>
 */
@Component
public class ProviderRegistry {

    private static final Logger log = LoggerFactory.getLogger(ProviderRegistry.class);

    private final Map<ChannelProtocol, ModelAdapter> adapters = new EnumMap<>(ChannelProtocol.class);

    public ProviderRegistry(List<ModelAdapter> adapterList) {
        for (ModelAdapter adapter : adapterList) {
            ModelAdapter previous = adapters.put(adapter.protocol(), adapter);
            if (previous != null) {
                log.warn("协议 {} 存在多个适配器，{} 覆盖 {}", adapter.protocol(),
                        adapter.getClass().getSimpleName(), previous.getClass().getSimpleName());
            }
        }
        log.info("已注册模型适配器: {}", adapters.keySet());
    }

    /** 取得指定协议的适配器。 */
    public ModelAdapter adapterFor(ChannelProtocol protocol) {
        ModelAdapter adapter = adapters.get(protocol);
        if (adapter == null) {
            throw new GatewayException(ErrorCode.NOT_IMPLEMENTED, "不支持的通道协议: " + protocol);
        }
        return adapter;
    }

    /** 是否存在指定协议的适配器。 */
    public boolean supports(ChannelProtocol protocol) {
        return adapters.containsKey(protocol);
    }
}
