package tech.qunxu.module.gateway.moderation;

import reactor.core.publisher.Mono;

/**
 * 内容审计提供方 SPI。每个实现对一段文本产出风险裁决（涉政/色情/暴恐/广告/辱骂等类别 + PASS/REVIEW/BLOCK）。
 *
 * <p>实现类注册为 Spring Bean，由 {@link ModerationService} 按 {@link #name()} 索引并根据策略
 * {@code provider} 字段选择。远程实现（阿里绿网 / LLM 裁判）在未配置时应「优雅禁用」——直接返回
 * {@link ModerationResult#pass(String)}，不得抛出影响主链。</p>
 */
public interface ModerationProvider {

    /** 提供方名（策略 {@code provider} 字段引用，如 builtin/aliyun/llm）。 */
    String name();

    /**
     * 审计文本。
     *
     * @param text    待审文本（非空）
     * @param context 审计上下文（含提供方专属配置）
     * @return 审计结果（原始风险裁决，不含策略 action 的执行转换）
     */
    Mono<ModerationResult> moderate(String text, ModerationContext context);
}
