package tech.qunxu.module.gateway.common.model;

import java.util.List;
import java.util.Map;

/**
 * 统一生成参数。由各上游协议解析而来，向下映射到供应商原生参数。
 * 所有字段可空，为空时不下传，交由供应商使用其默认值。
 */
public record GenerationParams(
        Double temperature,
        Double topP,
        Integer maxTokens,
        List<String> stop,
        Double frequencyPenalty,
        Double presencePenalty,
        Integer seed,
        String responseFormat,
        Boolean enableThinking,
        Map<String, Object> extra) {

    public static GenerationParams defaults() {
        return new GenerationParams(null, null, null, null, null, null, null, null, null, Map.of());
    }
}
