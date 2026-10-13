package tech.qunxu.module.gateway.moderation;

import java.util.Map;

/**
 * 解析后的内容审计策略：选定提供方、执行动作与合并后的提供方配置。
 *
 * @param provider 提供方名（builtin/aliyun/llm）
 * @param action   执行动作（BLOCK/REVIEW/PASS），决定命中后如何处置
 * @param config   合并后的提供方专属扁平配置
 */
public record ResolvedModeration(String provider, String action, Map<String, Object> config) {
}
