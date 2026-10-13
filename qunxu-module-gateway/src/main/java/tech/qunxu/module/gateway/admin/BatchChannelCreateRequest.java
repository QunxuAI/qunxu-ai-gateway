package tech.qunxu.module.gateway.admin;

import java.util.List;

/**
 * 批量创建通道请求：从供应商模型清单批量上架。
 *
 * <p>不再要求调用方指定逻辑模型——每个模型自动创建（或复用同名）逻辑模型并建立通道。
 * 公共字段（能力、协议、权重、优先级、启用状态、通道配置）对所有模型生效；已存在（供应商 + 模型名）的跳过。</p>
 */
public record BatchChannelCreateRequest(
        Long providerId,
        String capability,
        String protocol,
        Integer weight,
        Integer priority,
        Integer enabled,
        String configJson,
        List<String> upstreamModels) {
}
